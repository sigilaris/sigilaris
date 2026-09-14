package org.sigilaris.node.jvm.runtime.application.v2

import cats.Applicative
import cats.data.EitherT
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.jvm.runtime.block.{BlockHeaderVersion, BlockId}
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{
  HotStuffApplicationProfileSchedule,
  HotStuffHistoricalApplicationProfile,
  HotStuffWindow,
  Proposal,
}

/** Release provenance is independent of the historical protocol integer and
  * header tag. Both published milestones can carry header V2 with plan V1.
  */
enum HistoricalApplicationRelease(val tag: Byte):
  case LegacyM1      extends HistoricalApplicationRelease(1.toByte)
  case LegacyM2      extends HistoricalApplicationRelease(2.toByte)
  case ApplicationV2 extends HistoricalApplicationRelease(3.toByte)
object HistoricalApplicationRelease:
  given ByteEncoder[HistoricalApplicationRelease] =
    ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[HistoricalApplicationRelease] = V2Codecs.enumDecoder(
    "historical release",
    values.toVector.map(value => value.tag -> value),
  )

/** This is original immutable configuration evidence, not a request-selected
  * version. The release determines the whole published protocol/signature/
  * plan/lock/effect/exact/journal tuple; headerVersion records its selected
  * supported header language. A new application profile carries its complete
  * canonical manifest rather than a caller-supplied protocol flag.
  */
final case class HistoricalArtifactProfile(
    release: HistoricalApplicationRelease,
    headerVersion: Long,
    manifest: Option[ProtocolManifest],
)
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object HistoricalArtifactProfile:
  import V2Codecs.given
  given ByteEncoder[HistoricalArtifactProfile]         = ByteEncoder.derived
  given ByteDecoder[HistoricalArtifactProfile]         = ByteDecoder.derived
  val codec: CanonicalCodec[HistoricalArtifactProfile] =
    CanonicalCodec.derived(validate)
  def validate(value: HistoricalArtifactProfile): Either[CoreFailure, Unit] =
    for
      _ <- V2Validation.require(
        value.headerVersion == 1L || value.headerVersion == 2L,
        FailureCode.UnsupportedTuple,
        "historical.headerVersion",
      )
      _ <- value.release match
        case HistoricalApplicationRelease.LegacyM1 |
            HistoricalApplicationRelease.LegacyM2 =>
          V2Validation.require(
            value.manifest.isEmpty,
            FailureCode.UnsupportedTuple,
            "historical.legacyManifest",
          )
        case HistoricalApplicationRelease.ApplicationV2 =>
          for
            _ <- V2Validation.require(
              value.headerVersion == 2L,
              FailureCode.UnsupportedTuple,
              "historical.applicationHeader",
            )
            manifest <- value.manifest.toRight(
              CoreFailure.at(
                FailureCode.ManifestMismatch,
                "historical.manifest",
              ),
            )
            _ <- ProtocolManifest.validate(manifest)
          yield ()
    yield ()

  def selected(
      value: HistoricalArtifactProfile,
  ): Either[CoreFailure, HotStuffHistoricalApplicationProfile] =
    validate(value).flatMap { _ =>
      val header = if value.headerVersion == 1L then BlockHeaderVersion.V1
      else BlockHeaderVersion.V2
      value.release match
        case HistoricalApplicationRelease.LegacyM1 =>
          Right(HotStuffHistoricalApplicationProfile.LegacyM1(header))
        case HistoricalApplicationRelease.LegacyM2 =>
          Right(HotStuffHistoricalApplicationProfile.LegacyM2(header))
        case HistoricalApplicationRelease.ApplicationV2 =>
          value.manifest
            .toRight(
              CoreFailure
                .at(FailureCode.ManifestMismatch, "historical.manifest"),
            )
            .map(HotStuffHistoricalApplicationProfile.ApplicationV2.apply)
    }

final case class HistoricalProfileRange(
    firstHeight: Height,
    lastHeight: Option[Height],
    context: DomainContext,
    profile: HistoricalArtifactProfile,
    originalConfigurationEvidenceDigest: Hash,
)
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object HistoricalProfileRange:
  import V2Codecs.given
  given ByteEncoder[HistoricalProfileRange]         = ByteEncoder.derived
  given ByteDecoder[HistoricalProfileRange]         = ByteDecoder.derived
  val codec: CanonicalCodec[HistoricalProfileRange] =
    CanonicalCodec.derived(validate)
  def validate(value: HistoricalProfileRange): Either[CoreFailure, Unit] = for
    _ <- DomainContext.validate(value.context)
    _ <- HistoricalArtifactProfile.validate(value.profile)
    _ <- V2Validation.require(
      value.lastHeight.forall(
        _.toBigNat.toBigInt >= value.firstHeight.toBigNat.toBigInt,
      ),
      FailureCode.InvalidDeadline,
      "historical.range",
    )
    _ <- value.profile.release match
      case HistoricalApplicationRelease.LegacyM1 |
          HistoricalApplicationRelease.LegacyM2 =>
        V2Validation.require(
          value.context.protocolVersion == 1L,
          FailureCode.UnsupportedTuple,
          "historical.protocol",
        )
      case HistoricalApplicationRelease.ApplicationV2 =>
        for
          manifest <- value.profile.manifest.toRight(
            CoreFailure.at(FailureCode.ManifestMismatch, "historical.manifest"),
          )
          context <- ProtocolManifest.context(manifest)
          _       <- V2Validation.require(
            context == value.context,
            FailureCode.ManifestMismatch,
            "historical.context",
          )
        yield ()
  yield ()
  def digest(value: HistoricalProfileRange): Either[CoreFailure, Hash] =
    codec
      .encode(value)
      .map(
        Commitment
          .hash(Utf8("sigilaris.application.historical-profile.range.v1"), _),
      )

/** Complete ranges start at height zero for every independently named chain. An
  * inherited source chain is a different group, never a prefix of new G. The
  * terminal range is unbounded so missing future dispatch is not guessed.
  */
final case class HistoricalProfileConfiguration(
    format: Long,
    ranges: Vector[HistoricalProfileRange],
)
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object HistoricalProfileConfiguration:
  import V2Codecs.given
  given ByteEncoder[HistoricalProfileConfiguration] = ByteEncoder.derived
  given ByteDecoder[HistoricalProfileConfiguration] = ByteDecoder.derived
  val codec: CanonicalCodec[HistoricalProfileConfiguration] =
    CanonicalCodec.derived(validate)
  def validate(
      value: HistoricalProfileConfiguration,
  ): Either[CoreFailure, Unit] = for
    _ <- V2Validation.format(
      value.format,
      1L,
      "historical.configuration.format",
    )
    _ <- value.ranges.traverse_(HistoricalProfileRange.validate)
    keys = value.ranges.map(range =>
      V2Validation.textKey(
        range.context.chainId,
      ) -> range.firstHeight.toBigNat.toBigInt,
    )
    _ <- V2Validation.require(
      keys == keys.sorted && keys.distinct.sizeCompare(keys.size) == 0,
      FailureCode.MembershipMismatch,
      "historical.rangeOrder",
    )
    _ <- value.ranges.groupBy(_.context.chainId).toVector.traverse_ {
      (_, ranges) =>
        for
          _ <- V2Validation.require(
            ranges.headOption
              .exists(_.firstHeight.toBigNat.toBigInt == BigInt(0)),
            FailureCode.MembershipMismatch,
            "historical.firstHeight",
          )
          _ <- V2Validation.require(
            ranges.lastOption.exists(_.lastHeight.isEmpty),
            FailureCode.MembershipMismatch,
            "historical.lastHeight",
          )
          _ <- ranges.zip(ranges.drop(1)).traverse_ { (left, right) =>
            V2Validation.require(
              left.lastHeight.exists(
                _.toBigNat.toBigInt + 1 == right.firstHeight.toBigNat.toBigInt,
              ),
              FailureCode.MembershipMismatch,
              "historical.gapOrOverlap",
            )
          }
        yield ()
    }
  yield ()
  def digest(value: HistoricalProfileConfiguration): Either[CoreFailure, Hash] =
    codec
      .encode(value)
      .map(
        Commitment.hash(
          Utf8("sigilaris.application.historical-profile.configuration.v1"),
          _,
        ),
      )

/** Constructed only by checking canonical bytes against independently installed
  * trust. The pin is an activation/configuration trust root, never a digest
  * accepted from the same remote request that supplies these bytes. Once built,
  * no mutable provider or wall-clock can relabel a retained height.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
final class AuthenticatedHistoricalProfiles private (
    val configuration: HistoricalProfileConfiguration,
    val digest: Hash,
):
  def at(
      window: HotStuffWindow,
  ): Either[V2RuntimeFailure, HistoricalProfileRange] =
    val height = window.height.toBigNat.toBigInt
    configuration.ranges
      .find(range =>
        range.context.chainId.asString == window.chainId.value && range.firstHeight.toBigNat.toBigInt <= height &&
          range.lastHeight.forall(_.toBigNat.toBigInt >= height),
      )
      .toRight(
        V2RuntimeFailure.at(
          RuntimeFailureCode.ProofUnavailable,
          "authenticated historical profile range is missing",
        ),
      )
      .flatMap(range =>
        RuntimeCheck
          .require(
            range.context.validatorSetHash == window.validatorSetHash.toUInt256,
            RuntimeFailureCode.DomainMismatch,
            "historical validator set differs from the authenticated height range",
          )
          .as(range),
      )

  def proposal(
      value: Proposal,
  ): Either[V2RuntimeFailure, HistoricalProfileRange] = for
    range <- at(value.window)
    _     <- RuntimeCheck.require(
      value.block.height == value.window.height && value.block.version.tag.toLong == range.profile.headerVersion,
      RuntimeFailureCode.DomainMismatch,
      "proposal header is outside its historical profile",
    )
  yield range

  def schedule[F[_]: Applicative]: HotStuffApplicationProfileSchedule[F] =
    new HotStuffApplicationProfileSchedule[F]:
      def at(
          window: HotStuffWindow,
          @annotation.unused parent: Option[BlockId],
      ): Result[F, HotStuffHistoricalApplicationProfile] =
        EitherT.fromEither[F](
          AuthenticatedHistoricalProfiles.this
            .at(window)
            .flatMap(range =>
              HistoricalArtifactProfile
                .selected(range.profile)
                .leftMap(V2RuntimeFailure.fromCore),
            ),
        )

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object AuthenticatedHistoricalProfiles:
  def pinned(
      expectedDigest: Hash,
      canonicalConfiguration: Bytes,
  ): Either[V2RuntimeFailure, AuthenticatedHistoricalProfiles] = for
    configuration <- HistoricalProfileConfiguration.codec
      .decode(canonicalConfiguration)
      .leftMap(V2RuntimeFailure.fromCore)
    digest <- HistoricalProfileConfiguration
      .digest(configuration)
      .leftMap(V2RuntimeFailure.fromCore)
    _ <- RuntimeCheck.require(
      digest == expectedDigest,
      RuntimeFailureCode.CommitmentMismatch,
      "historical configuration differs from the independently installed pin",
    )
  yield new AuthenticatedHistoricalProfiles(configuration, digest)
