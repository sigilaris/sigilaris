package org.sigilaris.core.application.protocol.v2

import scodec.bits.ByteVector
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.datatype.Utf8

import V2Codecs.given

/** Canonical shape does not authenticate the declared authority or trusted key.
  */
final case class SignatureEnvelope(
    authorityId: Text,
    algorithm: Byte,
    publicKey: Bytes,
    signature: Bytes,
)
@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object SignatureEnvelope:
  private val FieldPrime = BigInt(
    "fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f",
    16,
  )
  given ByteEncoder[SignatureEnvelope]         = ByteEncoder.derived
  given ByteDecoder[SignatureEnvelope]         = ByteDecoder.derived
  val codec: CanonicalCodec[SignatureEnvelope] =
    CanonicalCodec.derived(validate)
  def validate(value: SignatureEnvelope): Either[CoreFailure, Unit] =
    for
      _ <- V2Validation.identifier(value.authorityId, "signature.authorityId")
      _ <- V2Validation.require(
        value.algorithm == 1.toByte,
        FailureCode.UnsupportedFormat,
        "signature.algorithm",
      )
      _ <- V2Validation.require(
        value.publicKey.size == 64L,
        FailureCode.InvalidSignature,
        "signature.publicKey",
      )
      x = BigInt(1, value.publicKey.take(32L).toArray)
      y = BigInt(1, value.publicKey.drop(32L).toArray)
      _ <- V2Validation.require(
        x < FieldPrime && y < FieldPrime && ((y * y - x * x * x - 7) mod FieldPrime) == 0,
        FailureCode.InvalidSignature,
        "signature.curvePoint",
      )
      _ <- ValidatorSignature.validate(
        ValidatorSignature(value.authorityId, value.signature),
      )
    yield ()

enum FenceScope(val tag: Byte):
  case ApplicationIssuance                   extends FenceScope(1.toByte)
  case ConsensusProfileAtOrAbove             extends FenceScope(2.toByte)
  case SourceOrRetiredDomainWritesAndSigning extends FenceScope(3.toByte)
object FenceScope:
  given ByteEncoder[FenceScope] = ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[FenceScope] = V2Codecs.enumDecoder(
    "fence scope",
    Vector(
      ApplicationIssuance,
      ConsensusProfileAtOrAbove,
      SourceOrRetiredDomainWritesAndSigning,
    ).map(v => v.tag -> v),
  )
  val codec: CanonicalCodec[FenceScope] =
    CanonicalCodec.derived(_ => Right[CoreFailure, Unit](()))

final case class FencePromise(
    format: Long,
    context: DomainContext,
    signerId: Text,
    scope: FenceScope,
    boundary: Height,
    greatestPreviouslySignedHeight: Option[Height],
    signingHistoryDigest: Hash,
    transitionBinding: Hash,
)
@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object FencePromise:
  val Domain: Text                = Utf8("sigilaris.application.fence.v1")
  given ByteEncoder[FencePromise] = ByteEncoder.derived
  given ByteDecoder[FencePromise] = ByteDecoder.derived
  val codec: CanonicalCodec[FencePromise] = CanonicalCodec.derived(validate)
  def validate(value: FencePromise): Either[CoreFailure, Unit] =
    for
      _ <- V2Validation.format(value.format, 1L, "fence.format")
      _ <- DomainContext.validate(value.context)
      _ <- V2Validation.identifier(value.signerId, "fence.signerId")
      _ <- RecordValidation.check(
        value.scope != FenceScope.ConsensusProfileAtOrAbove || value.greatestPreviouslySignedHeight
          .forall(_.toBigNat.toBigInt < value.boundary.toBigNat.toBigInt),
        "fence.watermark",
      )
    yield ()
  def signingPreimage(value: FencePromise): Either[CoreFailure, Bytes] =
    codec
      .encode(value)
      .map(bytes => Commitment.preimage(Domain, ByteVector(0.toByte) ++ bytes))

final case class SignedFencePromise(
    record: FencePromise,
    authentication: SignatureEnvelope,
)
@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object SignedFencePromise:
  given ByteEncoder[SignedFencePromise]         = ByteEncoder.derived
  given ByteDecoder[SignedFencePromise]         = ByteDecoder.derived
  val codec: CanonicalCodec[SignedFencePromise] =
    CanonicalCodec.derived(validate)
  def validate(value: SignedFencePromise): Either[CoreFailure, Unit] =
    for
      _ <- FencePromise.validate(value.record)
      _ <- SignatureEnvelope.validate(value.authentication)
      _ <- RecordValidation.check(
        value.record.signerId == value.authentication.authorityId,
        "fence.authorityId",
      )
    yield ()
  def digest(value: SignedFencePromise): Either[CoreFailure, Hash] =
    codec
      .encode(value)
      .map(bytes =>
        Commitment.hash(FencePromise.Domain, ByteVector(1.toByte) ++ bytes),
      )

final case class PreparedNamespace(
    name: Text,
    schema: Long,
    contentDigest: Hash,
    sourceInventoryDigest: Hash,
)
object PreparedNamespace:
  given ByteEncoder[PreparedNamespace]         = ByteEncoder.derived
  given ByteDecoder[PreparedNamespace]         = ByteDecoder.derived
  val codec: CanonicalCodec[PreparedNamespace] =
    CanonicalCodec.derived(validate)
  def validate(value: PreparedNamespace): Either[CoreFailure, Unit] =
    V2Validation
      .identifier(value.name, "namespace.name")
      .flatMap(_ =>
        RecordValidation.nonnegative(value.schema, "namespace.schema"),
      )

final case class ActivationPreparation(
    schema: Long,
    transitionDigest: Hash,
    baselineDigest: Hash,
    completeOldGroupDigest: Hash,
    prepared: Vector[PreparedNamespace],
    parentBlockId: Hash,
    firstHeight: Height,
    targetManifestDigest: Hash,
    retainedSafetyInventoryDigest: Hash,
)
object ActivationPreparation:
  val Domain: Text = Utf8("sigilaris.application.activation.v1")
  given ByteEncoder[ActivationPreparation]         = ByteEncoder.derived
  given ByteDecoder[ActivationPreparation]         = ByteDecoder.derived
  val codec: CanonicalCodec[ActivationPreparation] =
    CanonicalCodec.derived(validate)
  def validate(value: ActivationPreparation): Either[CoreFailure, Unit] =
    for
      _ <- RecordValidation.schema(value.schema)
      _ <- V2Validation.all(value.prepared.map(PreparedNamespace.validate))
      _ <- V2Validation.sortedUnique(
        value.prepared.map(v => V2Validation.textKey(v.name)),
        "activation.namespaces",
      )
    yield ()
  def digest(value: ActivationPreparation): Either[CoreFailure, Hash] =
    codec
      .encode(value)
      .map(bytes => Commitment.hash(Domain, ByteVector(1.toByte) ++ bytes))

final case class ActivationDecision(
    schema: Long,
    preparationDigest: Hash,
    decisionSequence: Long,
    parentBlockId: Hash,
    firstHeight: Height,
    targetManifestDigest: Hash,
    retainedSafetyInventoryDigest: Hash,
)
object ActivationDecision:
  given ByteEncoder[ActivationDecision]         = ByteEncoder.derived
  given ByteDecoder[ActivationDecision]         = ByteDecoder.derived
  val codec: CanonicalCodec[ActivationDecision] =
    CanonicalCodec.derived(validate)
  def validate(value: ActivationDecision): Either[CoreFailure, Unit] =
    RecordValidation
      .schema(value.schema)
      .flatMap(_ =>
        RecordValidation
          .sequence(value.decisionSequence, "activation.decisionSequence"),
      )
  def digest(value: ActivationDecision): Either[CoreFailure, Hash] =
    codec
      .encode(value)
      .map(bytes =>
        Commitment
          .hash(ActivationPreparation.Domain, ByteVector(2.toByte) ++ bytes),
      )

enum BootstrapPhase(val tag: Byte):
  case Bound     extends BootstrapPhase(1.toByte)
  case Installed extends BootstrapPhase(2.toByte)
  case Signing   extends BootstrapPhase(3.toByte)
  case Opened    extends BootstrapPhase(4.toByte)
object BootstrapPhase:
  given ByteEncoder[BootstrapPhase] = ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[BootstrapPhase] = V2Codecs.enumDecoder(
    "bootstrap phase",
    Vector(Bound, Installed, Signing, Opened).map(v => v.tag -> v),
  )
  val codec: CanonicalCodec[BootstrapPhase] =
    CanonicalCodec.derived(_ => Right[CoreFailure, Unit](()))

final case class BootstrapStartupRecord(
    schema: Long,
    bundleDigest: Hash,
    genesisBlockId: Hash,
    installedStateInventory: Hash,
    initializedSafetyInventory: Hash,
    baselineDigest: Hash,
    phase: BootstrapPhase,
    issuedVoteIntents: Vector[Hash],
)
object BootstrapStartupRecord:
  given ByteEncoder[BootstrapStartupRecord]         = ByteEncoder.derived
  given ByteDecoder[BootstrapStartupRecord]         = ByteDecoder.derived
  val codec: CanonicalCodec[BootstrapStartupRecord] =
    CanonicalCodec.derived(validate)
  def validate(value: BootstrapStartupRecord): Either[CoreFailure, Unit] =
    for
      _ <- RecordValidation.schema(value.schema)
      _ <- RecordValidation.keys(
        value.issuedVoteIntents,
        "bootstrap.issuedVoteIntents",
      )
      _ <- RecordValidation.check(
        value.phase.tag >= BootstrapPhase.Signing.tag || value.issuedVoteIntents.isEmpty,
        "bootstrap.phaseIntents",
      )
    yield ()

final case class BootstrapVoteIntent(
    schema: Long,
    bundleDigest: Hash,
    genesisBlockId: Hash,
    validatorId: Text,
    unsignedVoteSignBytes: Bytes,
    initializedSafetyInventory: Hash,
    baselineDigest: Hash,
)
object BootstrapVoteIntent:
  given ByteEncoder[BootstrapVoteIntent]         = ByteEncoder.derived
  given ByteDecoder[BootstrapVoteIntent]         = ByteDecoder.derived
  val codec: CanonicalCodec[BootstrapVoteIntent] =
    CanonicalCodec.derived(validate)
  def validate(value: BootstrapVoteIntent): Either[CoreFailure, Unit] =
    for
      _ <- RecordValidation.schema(value.schema)
      _ <- V2Validation.identifier(value.validatorId, "bootstrap.validatorId")
      _ <- RecordValidation.check(
        value.unsignedVoteSignBytes.nonEmpty,
        "bootstrap.unsignedVote",
      )
    yield ()
  def digest(value: BootstrapVoteIntent): Either[CoreFailure, Hash] =
    RecordValidation.digest(
      "sigilaris.application.bootstrap.vote-intent.v1",
      codec,
      value,
    )
