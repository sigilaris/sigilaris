package org.sigilaris.node.jvm.runtime.application.v2

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.*
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.datatype.Utf8
import V2Codecs.given

enum ControllerSigningKind(val tag: Byte):
  case ApplicationLock   extends ControllerSigningKind(1.toByte)
  case ApplicationEffect extends ControllerSigningKind(2.toByte)
  case Consensus         extends ControllerSigningKind(3.toByte)
object ControllerSigningKind:
  given ByteEncoder[ControllerSigningKind] = ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[ControllerSigningKind] = V2Codecs.enumDecoder(
    "controller signing kind",
    Vector(ApplicationLock, ApplicationEffect, Consensus).map(v => v.tag -> v),
  )

final case class ControllerSigningMaterial(
    context: DomainContext,
    kind: ControllerSigningKind,
    height: Option[Height],
    canonicalPreimage: Bytes,
)
@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object ControllerSigningMaterial:
  given ByteEncoder[ControllerSigningMaterial] = ByteEncoder.derived
  given ByteDecoder[ControllerSigningMaterial] = ByteDecoder.derived
  val codec = CanonicalCodec.derived[ControllerSigningMaterial](v =>
    DomainContext
      .validate(v.context)
      .flatMap(_ =>
        V2Validation.require(
          v.canonicalPreimage.nonEmpty && (v.kind != ControllerSigningKind.Consensus || v.height.nonEmpty),
          FailureCode.ProofInvalid,
          "controller.signingMaterial",
        ),
      ),
  )
final case class ControllerSigningIntent(
    request: Bytes,
    material: ControllerSigningMaterial,
)
object ControllerSigningIntent:
  given ByteEncoder[ControllerSigningIntent] = ByteEncoder.derived
  given ByteDecoder[ControllerSigningIntent] = ByteDecoder.derived
  val codec = CanonicalCodec.derived[ControllerSigningIntent](v =>
    ControllerSigningMaterial.codec.encode(v.material).map(_ => ()),
  )
  def digest(v: ControllerSigningIntent): Either[CoreFailure, Hash] = codec
    .encode(v)
    .map(
      Commitment
        .hash(Utf8("sigilaris.application.controller.sign-intent.v1"), _),
    )
final case class ControllerSignature(intentDigest: Hash, signature: Bytes)
object ControllerSignature:
  given ByteEncoder[ControllerSignature] = ByteEncoder.derived
  given ByteDecoder[ControllerSignature] = ByteDecoder.derived
  val codec = CanonicalCodec.derived[ControllerSignature](v =>
    ValidatorSignature.validate(
      ValidatorSignature(Utf8("controller"), v.signature),
    ),
  )

final case class ControllerWriteMaterial(
    context: DomainContext,
    namespace: Text,
    key: Text,
    priorDigest: Option[Hash],
    payload: Bytes,
)
object ControllerWriteMaterial:
  given ByteEncoder[ControllerWriteMaterial] = ByteEncoder.derived
  given ByteDecoder[ControllerWriteMaterial] = ByteDecoder.derived
  val codec = CanonicalCodec.derived[ControllerWriteMaterial](v =>
    for
      _ <- DomainContext.validate(v.context)
      _ <- V2Validation.identifier(v.namespace, "controller.writeNamespace")
      _ <- V2Validation.identifier(v.key, "controller.writeKey")
    yield (),
  )
  def contentDigest(payload: Bytes): Hash = Commitment.hash(
    Utf8("sigilaris.application.controller.write-content.v1"),
    payload,
  )
final case class ControllerWriteIntent(
    request: Bytes,
    material: ControllerWriteMaterial,
)
object ControllerWriteIntent:
  given ByteEncoder[ControllerWriteIntent] = ByteEncoder.derived
  given ByteDecoder[ControllerWriteIntent] = ByteDecoder.derived
  val codec = CanonicalCodec.derived[ControllerWriteIntent](v =>
    ControllerWriteMaterial.codec.encode(v.material).map(_ => ()),
  )
  def digest(v: ControllerWriteIntent): Either[CoreFailure, Hash] = codec
    .encode(v)
    .map(
      Commitment
        .hash(Utf8("sigilaris.application.controller.write-intent.v1"), _),
    )
final case class ControllerWriteReceipt(intentDigest: Hash, effect: Bytes)
object ControllerWriteReceipt:
  given ByteEncoder[ControllerWriteReceipt] = ByteEncoder.derived
  given ByteDecoder[ControllerWriteReceipt] = ByteDecoder.derived
  val codec = CanonicalCodec.derived[ControllerWriteReceipt](_ =>
    Right[CoreFailure, Unit](()),
  )
final case class ControllerEnforcement(
    intent: TransitionIntent,
    promise: FencePromise,
)
@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object ControllerEnforcement:
  given ByteEncoder[ControllerEnforcement] = ByteEncoder.derived
  given ByteDecoder[ControllerEnforcement] = ByteDecoder.derived
  val codec = CanonicalCodec.derived[ControllerEnforcement](v =>
    for
      _  <- TransitionIntent.validate(v.intent)
      _  <- FencePromise.validate(v.promise)
      id <- TransitionIntent.digest(v.intent)
      _  <- V2Validation.require(
        id == v.promise.transitionBinding,
        FailureCode.CommitmentMismatch,
        "controller.transitionBinding",
      )
    yield (),
  )
  def digest(v: ControllerEnforcement): Either[CoreFailure, Hash] = codec
    .encode(v)
    .map(
      Commitment
        .hash(Utf8("sigilaris.application.controller.enforcement.v1"), _),
    )
final case class ControllerFenceSignature(
    enforcementDigest: Hash,
    promise: SignedFencePromise,
)
object ControllerFenceSignature:
  given ByteEncoder[ControllerFenceSignature] = ByteEncoder.derived
  given ByteDecoder[ControllerFenceSignature] = ByteDecoder.derived
  val codec = CanonicalCodec.derived[ControllerFenceSignature](v =>
    SignedFencePromise.validate(v.promise),
  )

final case class ControllerObservedSignature(
    intent: ControllerSigningIntent,
    signature: Bytes,
)
object ControllerObservedSignature:
  given ByteEncoder[ControllerObservedSignature] = ByteEncoder.derived
  given ByteDecoder[ControllerObservedSignature] = ByteDecoder.derived
final case class ControllerObservedWrite(
    intent: ControllerWriteIntent,
    effect: Bytes,
)
object ControllerObservedWrite:
  given ByteEncoder[ControllerObservedWrite] = ByteEncoder.derived
  given ByteDecoder[ControllerObservedWrite] = ByteDecoder.derived
final case class ControllerObservedFence(
    enforcement: ControllerEnforcement,
    promise: SignedFencePromise,
)
object ControllerObservedFence:
  given ByteEncoder[ControllerObservedFence] = ByteEncoder.derived
  given ByteDecoder[ControllerObservedFence] = ByteDecoder.derived
final case class ControllerInitialHistory(
    contexts: Vector[DomainContext],
    signatures: Vector[ControllerObservedSignature],
    writes: Vector[ControllerObservedWrite],
    fences: Vector[ControllerObservedFence],
    writeClosures: Vector[ControllerWriteClosure],
)
object ControllerInitialHistory:
  given ByteEncoder[ControllerInitialHistory] = ByteEncoder.derived
  given ByteDecoder[ControllerInitialHistory] = ByteDecoder.derived
  val codec = CanonicalCodec.derived[ControllerInitialHistory](v =>
    v.contexts.traverse_(DomainContext.validate),
  )

/** Required configured interpreters, not caller-supplied success predicates.
  * Recompute the precise existing sign bytes and historical profile/context
  * from the actual canonical source/proposal and retained authorization. In
  * particular HotStuff chain/set/height alone does not authenticate a manifest.
  * `initialHistory` independently proves complete prior key use; an old key may
  * not be initialized with a fabricated empty history. Unresolved historical
  * writes/signing/enforcements require their original controller recovery; this
  * completed seed format cannot silently drop them or invent signatures. It
  * parses original proof bytes and retains all observed signatures, canonical
  * writes and fences. These methods run under the controller gate and must not
  * reacquire it or any outer voting gate already held by their caller.
  */
trait ControllerOperationAuthentication:
  def signing(
      request: Bytes,
      signerId: Text,
      publicKey: Bytes,
  ): Result[IO, ControllerSigningMaterial]

  /** Current permission for actual key use. Historical replay calls signing
    * only; it must not depend on current finality, ancestry or readiness. This
    * mandatory check runs after the full intent is forced and directly before
    * each new or retried uncertain key use. It must not reenter gates.
    */
  def authorizeSigning(
      request: Bytes,
      signerId: Text,
      publicKey: Bytes,
  ): Result[IO, Unit]
  def canonicalWrite(request: Bytes): Result[IO, ControllerWriteMaterial]
  def initialHistory(
      originalEvidence: Bytes,
      signerId: Text,
      publicKey: Bytes,
  ): Result[IO, ControllerInitialHistory]
  def enforce(
      intent: TransitionIntent,
      promise: FencePromise,
      signerId: Text,
      publicKey: Bytes,
  ): Result[IO, Unit]

  /** Authenticate installed transition policy and exact old context before its
    * canonical writes are irreversibly closed, without a future decision hash.
    */
  def closeWrites(
      closure: ControllerWriteClosure,
      signerId: Text,
      publicKey: Bytes,
  ): Result[IO, Unit]

/** Installed once with the controller. The actual canonical effect executes
  * while its gate is held, after the exact write intent was forced. `apply`
  * must perform an idempotent durable CAS from priorDigest to payload; after an
  * unknown result it must recognize the already-written identical effect.
  * `verify` rechecks the retained immutable effect proof after restart, even if
  * newer writes superseded its current pointer. No arbitrary per-call closure
  * can substitute another writer or bypass the guard.
  */
trait ControllerCanonicalWriter:
  def apply(intent: ControllerWriteIntent): Result[IO, Bytes]
  def verify(intent: ControllerWriteIntent, effect: Bytes): Result[IO, Unit]
object ControllerCanonicalWriter:
  val unavailable: ControllerCanonicalWriter = new ControllerCanonicalWriter:
    def apply(intent: ControllerWriteIntent): Result[IO, Bytes] = EitherT.leftT(
      V2RuntimeFailure.at(
        RuntimeFailureCode.ProofUnavailable,
        "no canonical writer is installed",
      ),
    )
    def verify(intent: ControllerWriteIntent, effect: Bytes): Result[IO, Unit] =
      EitherT.leftT(
        V2RuntimeFailure.at(
          RuntimeFailureCode.ProofUnavailable,
          "no canonical writer is installed",
        ),
      )

enum ControllerEventKind(val tag: Byte):
  case Enforcement    extends ControllerEventKind(1.toByte)
  case FenceSignature extends ControllerEventKind(2.toByte)
  case SigningIntent  extends ControllerEventKind(3.toByte)
  case SigningResult  extends ControllerEventKind(4.toByte)
  case WriteIntent    extends ControllerEventKind(5.toByte)
  case WriteResult    extends ControllerEventKind(6.toByte)
  case WriteClosure   extends ControllerEventKind(7.toByte)
object ControllerEventKind:
  given ByteEncoder[ControllerEventKind] = ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[ControllerEventKind] = V2Codecs.enumDecoder(
    "controller event",
    Vector(
      Enforcement,
      FenceSignature,
      SigningIntent,
      SigningResult,
      WriteIntent,
      WriteResult,
      WriteClosure,
    ).map(v => v.tag -> v),
  )
final case class ControllerRecord(
    format: Long,
    sequence: Long,
    previousDigest: Hash,
    kind: ControllerEventKind,
    payload: Bytes,
)
object ControllerRecord:
  given ByteEncoder[ControllerRecord] = ByteEncoder.derived
  given ByteDecoder[ControllerRecord] = ByteDecoder.derived
  val codec = CanonicalCodec.derived[ControllerRecord](v =>
    for
      _ <- V2Validation.format(v.format, 1L, "controllerRecord.format")
      _ <- V2Validation.require(
        v.sequence > 0L,
        FailureCode.InvalidLength,
        "controllerRecord.sequence",
      )
      _ <- v.kind match
        case ControllerEventKind.Enforcement =>
          ControllerEnforcement.codec.decode(v.payload).map(_ => ())
        case ControllerEventKind.FenceSignature =>
          ControllerFenceSignature.codec.decode(v.payload).map(_ => ())
        case ControllerEventKind.SigningIntent =>
          ControllerSigningIntent.codec.decode(v.payload).map(_ => ())
        case ControllerEventKind.SigningResult =>
          ControllerSignature.codec.decode(v.payload).map(_ => ())
        case ControllerEventKind.WriteIntent =>
          ControllerWriteIntent.codec.decode(v.payload).map(_ => ())
        case ControllerEventKind.WriteResult =>
          ControllerWriteReceipt.codec.decode(v.payload).map(_ => ())
        case ControllerEventKind.WriteClosure =>
          ControllerWriteClosure.codec.decode(v.payload).map(_ => ())
    yield (),
  )
  def digest(v: ControllerRecord): Either[CoreFailure, Hash] = codec
    .encode(v)
    .map(Commitment.hash(Utf8("sigilaris.application.controller.record.v1"), _))
final case class ControllerConfiguration(
    format: Long,
    signerId: Text,
    publicKey: Bytes,
    initialEvidence: Bytes,
    initialHistory: ControllerInitialHistory,
)
object ControllerConfiguration:
  given ByteEncoder[ControllerConfiguration] = ByteEncoder.derived
  given ByteDecoder[ControllerConfiguration] = ByteDecoder.derived
  val codec = CanonicalCodec.derived[ControllerConfiguration](v =>
    for
      _ <- V2Validation.format(v.format, 1L, "controllerConfiguration.format")
      _ <- InitialValidator.validate(InitialValidator(v.signerId, v.publicKey))
      _ <- ControllerInitialHistory.codec.encode(v.initialHistory).map(_ => ())
    yield (),
  )
  def digest(v: ControllerConfiguration): Either[CoreFailure, Hash] = codec
    .encode(v)
    .map(
      Commitment
        .hash(Utf8("sigilaris.application.controller.configuration.v1"), _),
    )
final case class ControllerSnapshot(
    format: Long,
    configuration: ControllerConfiguration,
    records: Vector[ControllerRecord],
)
object ControllerSnapshot:
  /** Checks the complete canonical record chain, receipts, signatures and fence
    * ordering for an installed namespace decoder. Original request/authority
    * authentication and live controller recovery are separate requirements;
    * success here grants no signing or activation capability.
    */
  def validateHistory(
      value: ControllerSnapshot,
  ): Either[V2RuntimeFailure, Unit] =
    ControllerHistory.recover(value).map(_ => ())
  given ByteEncoder[ControllerSnapshot] = ByteEncoder.derived
  given ByteDecoder[ControllerSnapshot] = ByteDecoder.derived
  val codec = CanonicalCodec.derived[ControllerSnapshot](v =>
    for
      _ <- V2Validation.format(v.format, 1L, "controllerSnapshot.format")
      _ <- ControllerConfiguration.codec.encode(v.configuration).map(_ => ())
      _ <- v.records.traverse_(r =>
        ControllerRecord.codec.encode(r).map(_ => ()),
      )
    yield (),
  )
  def digest(v: ControllerSnapshot): Either[CoreFailure, Hash] = codec
    .encode(v)
    .map(
      Commitment.hash(Utf8("sigilaris.application.controller.snapshot.v1"), _),
    )
final case class ControllerAuditArtifact(entry: InventoryEntry, bytes: Bytes)
final case class ControllerAuditInventory(
    snapshot: ControllerSnapshot,
    inventory: Vector[InventoryEntry],
    artifacts: Vector[ControllerAuditArtifact],
    possibleSigningIntents: Vector[ControllerSigningIntent],
    observedSignatures: Vector[ControllerObservedSignature],
    enforcements: Vector[ControllerEnforcement],
    signedFences: Vector[SignedFencePromise],
    writeClosures: Vector[ControllerWriteClosure],
)

enum ControllerFaultPoint:
  case AfterTempWrite, AfterTempForce, AfterAtomicReplace, AfterDirectoryForce
  case BeforeKeyUse, AfterKeyUse, BeforeCanonicalWrite, AfterCanonicalWrite
trait ControllerFaultInjector:
  def after(
      point: ControllerFaultPoint,
      sequence: Long,
      kind: ControllerEventKind,
  ): IO[Unit]
object ControllerFaultInjector:
  val none: ControllerFaultInjector = new ControllerFaultInjector:
    def after(
        point: ControllerFaultPoint,
        sequence: Long,
        kind: ControllerEventKind,
    ): IO[Unit] = IO.unit

final case class ControllerWriteClosure(
    transition: TransitionIntent,
    context: DomainContext,
)
object ControllerWriteClosure:
  given ByteEncoder[ControllerWriteClosure] = ByteEncoder.derived
  given ByteDecoder[ControllerWriteClosure] = ByteDecoder.derived
  val codec = CanonicalCodec.derived[ControllerWriteClosure](v =>
    TransitionIntent
      .validate(v.transition)
      .flatMap(_ => DomainContext.validate(v.context)),
  )
  def digest(v: ControllerWriteClosure): Either[CoreFailure, Hash] = codec
    .encode(v)
    .map(
      Commitment
        .hash(Utf8("sigilaris.application.controller.write-closure.v1"), _),
    )

final case class ControllerSigningHistoryBinding(
    format: Long,
    snapshotDigest: Hash,
    context: DomainContext,
    scope: FenceScope,
)
object ControllerSigningHistoryBinding:
  given ByteEncoder[ControllerSigningHistoryBinding] = ByteEncoder.derived
  given ByteDecoder[ControllerSigningHistoryBinding] = ByteDecoder.derived
  val codec = CanonicalCodec.derived[ControllerSigningHistoryBinding](v =>
    V2Validation
      .format(v.format, 1L, "controllerSigningHistory.format")
      .flatMap(_ => DomainContext.validate(v.context)),
  )
  def digest(v: ControllerSigningHistoryBinding): Either[CoreFailure, Hash] =
    codec
      .encode(v)
      .map(
        Commitment
          .hash(Utf8("sigilaris.application.controller.signing-history.v1"), _),
      )

/** A private live lease, never a signing capability. Its methods are valid only
  * during withStopped. They do not reacquire the gate. Capture through its
  * snapshot AFTER any closeWrites calls; no key or arbitrary writer is exposed.
  */
final class StoppedFenceController private[v2] (
    readSnapshot: () => Result[IO, ControllerSnapshot],
    close: (TransitionIntent, DomainContext) => Result[IO, Hash],
):
  def snapshot: Result[IO, ControllerSnapshot] = readSnapshot()
  def closeWrites(
      intent: TransitionIntent,
      context: DomainContext,
  ): Result[IO, Hash] = close(intent, context)

trait FenceController:
  def signerId: Text
  def publicKey: Bytes
  def sign(request: Bytes): Result[IO, ControllerSignature]
  def writeCanonical(request: Bytes): Result[IO, ControllerWriteReceipt]
  def prepareFence(
      intent: TransitionIntent,
      context: DomainContext,
      scope: FenceScope,
      boundary: Height,
  ): Result[IO, FencePromise]
  def enforce(
      intent: TransitionIntent,
      promise: FencePromise,
  ): Result[IO, SignedFencePromise]
  def withStopped[A](
      capture: StoppedFenceController => Result[IO, A],
  ): Result[IO, A]
  def recover: Result[IO, ControllerSnapshot]
  def snapshot: Result[IO, ControllerSnapshot]
  def audit: Result[IO, ControllerAuditInventory]

  /** Validates an old backup as a prefix and returns the complete CURRENT
    * snapshot. It never replaces the controller files or erases newer effects.
    * This alone grants no application-state rollback permission.
    */
  def preserveAfterRestore(
      backup: ControllerSnapshot,
  ): Result[IO, ControllerSnapshot]
