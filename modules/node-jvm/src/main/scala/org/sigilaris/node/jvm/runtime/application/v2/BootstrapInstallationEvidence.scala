package org.sigilaris.node.jvm.runtime.application.v2

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.application.protocol.v2.V2Codecs.given
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.datatype.{BigNat, Utf8}
import org.sigilaris.node.jvm.runtime.block.BlockHeader

/** Original, immutable installation material. The inventory names actual bytes;
  * the source root is independently recomputed by the installed transition
  * verifier, never inferred from these storage digests.
  */
final case class BootstrapOriginalArtifact(reference: EvidenceRef, bytes: Bytes)
object BootstrapOriginalArtifact:
  given ByteEncoder[BootstrapOriginalArtifact] = ByteEncoder.derived
  given ByteDecoder[BootstrapOriginalArtifact] = ByteDecoder.derived

final case class BootstrapInstallationEvidence(
    format: Long,
    signedBundle: SignedBootstrapBundle,
    baseline: EvidenceBaseline,
    sourceInventory: Bytes,
    statePayload: Bytes,
    sourceFence: SignedFencePromise,
    initialController: Bytes,
    artifacts: Vector[BootstrapOriginalArtifact],
)

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object BootstrapInstallationEvidence:
  given ByteEncoder[BootstrapInstallationEvidence] = ByteEncoder.derived
  given ByteDecoder[BootstrapInstallationEvidence] = ByteDecoder.derived
  val codec = CanonicalCodec.derived[BootstrapInstallationEvidence](v =>
    for
      _ <- V2Validation.format(v.format, 1L, "bootstrapInstallation.format")
      _ <- SignedBootstrapBundle.validate(v.signedBundle)
      _ <- EvidenceBaseline.validate(v.baseline)
      _ <- TransitionInventory.decode(v.sourceInventory)
      _ <- SignedFencePromise.validate(v.sourceFence)
      _ <- ControllerSnapshot.codec.decode(v.initialController)
      keys = v.artifacts.map(a =>
        (a.reference.kind.tag, a.reference.digest.bytes.toHex),
      )
      _ <- V2Validation.require(
        keys == keys.sorted && keys.distinct.sizeCompare(keys.size) == 0,
        FailureCode.MembershipMismatch,
        "bootstrapInstallation.artifacts",
      )
    yield (),
  )
  val namespace: Text            = Utf8("bootstrap-installation")
  val certificateNamespace: Text = Utf8("bootstrap-certificate")
  private val contentDomain      = Utf8(
    "sigilaris.application.bootstrap.retained-content.v1",
  )

  private def entry(key: String, bytes: Bytes): InventoryEntry =
    InventoryEntry(
      Utf8("bootstrap"),
      Utf8(key),
      Commitment.hash(contentDomain, bytes),
    )

  def inventory(
      v: BootstrapInstallationEvidence,
      initial: InitialBootstrapGenesis,
  ): Either[V2RuntimeFailure, Vector[InventoryEntry]] =
    for
      bundle <- RuntimeCheck.core(
        SignedBootstrapBundle.codec.encode(v.signedBundle),
      )
      baseline <- RuntimeCheck.core(EvidenceBaseline.codec.encode(v.baseline))
      fence <- RuntimeCheck.core(SignedFencePromise.codec.encode(v.sourceFence))
    yield Vector(
      entry("baseline", baseline),
      entry("bundle", bundle),
      entry("controller", v.initialController),
      entry("genesis", ByteEncoder[BlockHeader].encode(initial.header)),
      entry(
        "original-evidence",
        ByteEncoder[Vector[BootstrapOriginalArtifact]].encode(v.artifacts),
      ),
      entry("source-fence", fence),
      entry("source-inventory", v.sourceInventory),
      entry("source-state", v.statePayload),
    )

  def safetyInventory(
      v: BootstrapInstallationEvidence,
      initial: InitialBootstrapGenesis,
  ): Either[V2RuntimeFailure, Hash] =
    // No highQC, lockedQC or previous vote exists for this new chain. The empty
    // canonical vector is an explicit installed initial state, not a watermark
    // for any retired chain. Full prior controller history remains retained.
    RuntimeCheck.core(
      TransitionInventory.digest(
        Vector(
          entry("controller", v.initialController),
          entry(
            "empty-consensus",
            ByteEncoder[Vector[Bytes]].encode(Vector.empty),
          ),
          entry("genesis", ByteEncoder[BlockHeader].encode(initial.header)),
        ),
      ),
    )

  def startup(
      v: BootstrapInstallationEvidence,
      bootstrap: VerifiedBootstrap,
  ): Either[V2RuntimeFailure, BootstrapStartupRecord] = for
    initial   <- InitialBootstrapConsensus.genesis(bootstrap)
    entries   <- inventory(v, initial)
    installed <- RuntimeCheck.core(TransitionInventory.digest(entries))
    safety    <- safetyInventory(v, initial)
    baseline  <- RuntimeCheck.core(EvidenceBaseline.digest(v.baseline))
  yield BootstrapStartupRecord(
    2L,
    initial.bundleDigest,
    initial.blockId.toUInt256,
    installed,
    safety,
    baseline,
    BootstrapPhase.Bound,
    Vector.empty,
  )

  def anchor(
      bootstrap: VerifiedBootstrap,
  ): Either[V2RuntimeFailure, ApplicationAnchor] =
    InitialBootstrapConsensus
      .genesis(bootstrap)
      .map(initial =>
        ApplicationAnchor(
          initial.context,
          initial.blockId.toUInt256,
          org.sigilaris.core.application.protocol
            .InclusionHeight(BigNat.unsafeFromBigInt(BigInt(0))),
          initial.header.stateRoot.toUInt256,
        ),
      )

  private def check(
      condition: Boolean,
      detail: String,
  ): Either[V2RuntimeFailure, Unit] =
    RuntimeCheck.require(
      condition,
      RuntimeFailureCode.StartupIdentityMismatch,
      detail,
    )

  def verifyStructure(
      v: BootstrapInstallationEvidence,
      bootstrap: VerifiedBootstrap,
      record: BootstrapStartupRecord,
  ): Either[V2RuntimeFailure, Unit] = for
    _        <- RuntimeCheck.core(codec.encode(v))
    expected <- startup(v, bootstrap)
    _        <- check(
      v.signedBundle == bootstrap.source.bundle && v.baseline == bootstrap.baseline,
      "installed original signed bundle or fixed evidence baseline changed",
    )
    inventory <- RuntimeCheck.core(
      TransitionInventory.encode(bootstrap.source.closure.dataInventory),
    )
    _ <- check(
      v.sourceInventory == inventory && v.statePayload == bootstrap.source.closure.statePayload &&
        v.sourceFence == bootstrap.source.closure.sourceFence,
      "installed source bytes differ from the independently authenticated original snapshot",
    )
    references =
      (v.baseline.entries ++ v.signedBundle.bundle.retiredEvidence).distinct
        .sortBy(r => (r.kind.tag, r.digest.bytes.toHex))
    _ <- check(
      v.artifacts.map(_.reference) == references,
      "the complete original baseline/retired evidence was not retained",
    )
    _ <- check(
      expected.copy(
        phase = record.phase,
        issuedVoteIntents = record.issuedVoteIntents,
      ) == record,
      "bootstrap journal does not select the original installed state and initial safety inventory",
    )
    snapshot <- RuntimeCheck.core(
      ControllerSnapshot.codec.decode(v.initialController),
    )
    _ <- noTargetHistory(snapshot, bootstrap.source.bundle.bundle.target)
  yield ()

  def noTargetHistory(
      snapshot: ControllerSnapshot,
      target: DomainContext,
  ): Either[V2RuntimeFailure, Unit] =
    def old(context: DomainContext): Boolean = context.chainId != target.chainId
    val initial = snapshot.configuration.initialHistory
    for
      _ <- check(
        initial.contexts.forall(old) && initial.signatures.forall(v =>
          old(v.intent.material.context),
        ) &&
          initial.writes.forall(v =>
            old(v.intent.material.context),
          ) && initial.fences.forall(v => old(v.enforcement.promise.context)) &&
          initial.writeClosures.forall(v => old(v.context)),
        "new bootstrap chain already occurs in authenticated original controller history",
      )
      _ <- snapshot.records.traverse_(r =>
        r.kind match
          case ControllerEventKind.SigningIntent =>
            RuntimeCheck
              .core(ControllerSigningIntent.codec.decode(r.payload))
              .flatMap(v =>
                check(
                  old(v.material.context),
                  "target chain has a previous signing intent",
                ),
              )
          case ControllerEventKind.WriteIntent =>
            RuntimeCheck
              .core(ControllerWriteIntent.codec.decode(r.payload))
              .flatMap(v =>
                check(
                  old(v.material.context),
                  "target chain has a previous canonical write intent",
                ),
              )
          case ControllerEventKind.Enforcement =>
            RuntimeCheck
              .core(ControllerEnforcement.codec.decode(r.payload))
              .flatMap(v =>
                check(
                  old(v.promise.context),
                  "target chain has a previous enforced fence",
                ),
              )
          case ControllerEventKind.WriteClosure =>
            RuntimeCheck
              .core(ControllerWriteClosure.codec.decode(r.payload))
              .flatMap(v =>
                check(
                  old(v.context),
                  "target chain has previously closed writes",
                ),
              )
          case _ => Right[V2RuntimeFailure, Unit](()),
      )
    yield ()

  def load(
      journal: DurableJournal[IO],
      record: BootstrapStartupRecord,
  ): Result[IO, BootstrapInstallationEvidence] =
    journal
      .readBlob(namespace, record.installedStateInventory)
      .flatMap(bytes =>
        EitherT.fromEither[IO](RuntimeCheck.core(codec.decode(bytes))),
      )

  def authenticate(
      v: BootstrapInstallationEvidence,
      bootstrap: VerifiedBootstrap,
      record: BootstrapStartupRecord,
      verifier: TransitionEvidenceVerifier[IO],
      repository: TransitionEvidenceRepository[IO],
  ): Result[IO, VerifiedBootstrap] = for
    current <- verifier.verifyBootstrap(v.signedBundle)
    _       <- EitherT.fromEither[IO](
      check(
        current.digest == bootstrap.digest,
        "bootstrap startup is already fixed to a different signed bundle",
      ),
    )
    _ <- EitherT.fromEither[IO](verifyStructure(v, current, record))
    _ <- v.artifacts.traverse_(artifact =>
      repository
        .read(artifact.reference)
        .flatMap(bytes =>
          EitherT.fromEither[IO](
            check(
              bytes == artifact.bytes,
              "original transition evidence disappeared or changed",
            ),
          ),
        ),
    )
  yield current

/** Parsed only by the installed controller operation interpreter. No signature
  * callback or key is supplied by the caller.
  */
final case class InitialBootstrapSigningRequest(
    format: Long,
    bundleDigest: Hash,
    intentDigest: Hash,
)
object InitialBootstrapSigningRequest:
  given ByteEncoder[InitialBootstrapSigningRequest] = ByteEncoder.derived
  given ByteDecoder[InitialBootstrapSigningRequest] = ByteDecoder.derived
  val codec = CanonicalCodec.derived[InitialBootstrapSigningRequest](v =>
    V2Validation.format(v.format, 1L, "initialBootstrapSigning.format"),
  )
