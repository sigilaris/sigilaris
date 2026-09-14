package org.sigilaris.node.jvm.runtime.application.v2

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{
  Files,
  LinkOption,
  Path,
  StandardCopyOption,
  StandardOpenOption,
}
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*
import scala.util.Using

import cats.data.EitherT
import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.crypto.{CryptoOps, KeyPair}
import org.sigilaris.core.datatype.{UInt256, Utf8}

final class FenceControllerOpenException(val failure: V2RuntimeFailure)
    extends RuntimeException(failure.message)

/** Exclusive key/controller resource on a trusted local filesystem honoring JDK
  * file force, atomic replacement and directory force. A partial pending
  * snapshot is retained and fails closed; a complete descendant is replayed and
  * forced before readiness. No failed fence/publication/restore removes
  * history. Every use of this key and every selected canonical writer must be
  * routed through this resource. Independent copied keys or rollback of the
  * entire trusted controller disk require external enforcement, not a local
  * heuristic.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.Any",
  ),
)
final class FileFenceController private (
    root: Path,
    keys: KeyPair,
    configuration: ControllerConfiguration,
    authentication: ControllerOperationAuthentication,
    writer: ControllerCanonicalWriter,
    faults: ControllerFaultInjector,
    maximumBytes: Long,
    gate: Semaphore[IO],
    state: Ref[IO, FileFenceController.State],
) extends FenceController:
  import FileFenceController.*
  val signerId: Text   = configuration.signerId
  val publicKey: Bytes = configuration.publicKey

  private def value[A](result: Either[V2RuntimeFailure, A]): Result[IO, A] =
    EitherT.fromEither[IO](result)
  private def core[A](result: Either[CoreFailure, A]): Result[IO, A] = value(
    RuntimeCheck.core(result),
  )
  private def current: Result[IO, ControllerHistory] = EitherT(
    state.get.map(s =>
      if s.closed || s.poisoned then Left(requiredRecovery)
      else Right(s.history),
    ),
  )
  private def poison: IO[Unit] = state.update(_.copy(poisoned = true))
  private def checked[A](
      operation: Result[IO, A],
  ): IO[Either[V2RuntimeFailure, A]] = operation.value.attempt.flatMap {
    case Left(error)   => poison.as(Left(storageFailure(error)))
    case Right(result) => IO.pure(result)
  }
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  private def underGate[A](
      allowPoison: Boolean = false,
  )(run: Result[IO, A]): Result[IO, A] = EitherT {
    state.get.flatMap { before =>
      if before.closed || before.stopped || (!allowPoison && before.poisoned)
      then IO.pure(Left(requiredRecovery))
      else
        gate.permit.use(_ =>
          IO.uncancelable(_ =>
            state.get.flatMap { locked =>
              if locked.closed || locked.stopped || (!allowPoison && locked.poisoned)
              then IO.pure(Left(requiredRecovery))
              else checked(run)
            },
          ),
        )
    }
  }
  private def close: IO[Unit] =
    gate.permit.use(_ => state.update(_.copy(closed = true, poisoned = true)))

  private def append(
      kind: ControllerEventKind,
      payload: Bytes,
  ): Result[IO, ControllerHistory] = for
    before   <- current
    previous <- before.snapshot.records.lastOption.traverse(r =>
      core(ControllerRecord.digest(r)),
    )
    record = ControllerRecord(
      1L,
      before.snapshot.records.size.toLong + 1L,
      previous.getOrElse(UInt256.unsafeFromBigIntUnsigned(BigInt(0))),
      kind,
      payload,
    )
    next  <- value(ControllerHistory.append(before, record))
    bytes <- core(ControllerSnapshot.codec.encode(next.snapshot))
    _     <- value(
      RuntimeCheck.require(
        bytes.size <= maximumBytes,
        RuntimeFailureCode.CapacityUnavailable,
        "controller snapshot exceeds configured local capacity",
      ),
    )
    _ <- EitherT(
      IO.blocking {
        val physical = readSnapshots(root, configuration, maximumBytes)
        if !physical.exists(_ == before.snapshot) || !physical.forall(
            ControllerHistory.prefix(_, before.snapshot),
          )
        then
          abort(
            RuntimeFailureCode.JournalCorrupt,
            "controller history changed outside its exclusive resource",
          )
      }.attempt
        .map(_.left.map(storageFailure)),
    ).leftSemiflatTap(_ => poison)
    _ <- EitherT.liftF(poison)
    _ <- EitherT(
      writeSnapshot(next.snapshot, record.sequence, kind, true).attempt
        .map(_.left.map(storageFailure)),
    )
    _ <- EitherT.liftF(state.update(_.copy(history = next, poisoned = false)))
  yield next

  private def writeSnapshot(
      snapshot: ControllerSnapshot,
      sequence: Long,
      kind: ControllerEventKind,
      inject: Boolean,
  ): IO[Unit] =
    val bytes = required(
      RuntimeCheck.core(ControllerSnapshot.codec.encode(snapshot)),
    )
    val framed    = frame(bytes)
    val temporary = root.resolve("pending-" + UUID.randomUUID().toString)
    def after(point: ControllerFaultPoint): IO[Unit] =
      if inject then faults.after(point, sequence, kind) else IO.unit
    Resource
      .fromAutoCloseable(
        IO.blocking(
          FileChannel.open(
            temporary,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
          ),
        ),
      )
      .use { output =>
        IO.blocking(writeAll(output, framed)) *> after(
          ControllerFaultPoint.AfterTempWrite,
        ) *>
          IO.blocking(output.force(true)) *> after(
            ControllerFaultPoint.AfterTempForce,
          )
      } *> IO.blocking {
      ensureRegular(root.resolve(Ledger), required = false);
      Files.move(
        temporary,
        root.resolve(Ledger),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
      );
      ()
    } *>
      after(ControllerFaultPoint.AfterAtomicReplace) *> IO.blocking(
        forceDirectory(root),
      ) *> after(ControllerFaultPoint.AfterDirectoryForce)

  private def authenticate(history: ControllerHistory): Result[IO, Unit] = for
    original <- authentication.initialHistory(
      configuration.initialEvidence,
      signerId,
      publicKey,
    )
    _ <- value(
      RuntimeCheck.require(
        original == configuration.initialHistory,
        RuntimeFailureCode.EvidenceContradictory,
        "controller initial history differs from independently authenticated key inventory",
      ),
    )
    _ <- history.signing.toVector.traverse_ { (_, intent) =>
      authentication
        .signing(intent.request, signerId, publicKey)
        .flatMap(actual =>
          value(
            RuntimeCheck.require(
              actual == intent.material,
              RuntimeFailureCode.EvidenceContradictory,
              "stored signing material differs from original authenticated request",
            ),
          ),
        )
    }
    _ <- history.writes.toVector.traverse_ { (digest, intent) =>
      for
        actual <- authentication.canonicalWrite(intent.request)
        _      <- value(
          RuntimeCheck.require(
            actual == intent.material,
            RuntimeFailureCode.EvidenceContradictory,
            "stored canonical write differs from authenticated request",
          ),
        )
        _ <- history.writeReceipts
          .get(digest)
          .traverse_(writer.verify(intent, _))
      yield ()
    }
    _ <- history.enforcements.values.toVector.traverse_(e =>
      authentication.enforce(e.intent, e.promise, signerId, publicKey),
    )
    _ <- history.closures.values.toVector.traverse_(c =>
      authentication.closeWrites(c, signerId, publicKey),
    )
  yield ()

  def recover: Result[IO, ControllerSnapshot] = underGate(allowPoison = true) {
    val operation = for
      observed  <- EitherT.liftF(state.get.map(_.history.snapshot))
      _         <- EitherT.liftF(poison)
      snapshots <- EitherT(
        IO.blocking(readSnapshots(root, configuration, maximumBytes))
          .attempt
          .map(_.left.map(storageFailure)),
      )
      histories <- snapshots.traverse(s => value(ControllerHistory.recover(s)))
      _         <- histories.traverse_(authenticate)
      selected  <- value(
        histories
          .maxByOption(_.snapshot.records.size)
          .toRight(
            V2RuntimeFailure.at(
              RuntimeFailureCode.JournalCorrupt,
              "controller has no complete durable history",
            ),
          ),
      )
      _ <- value(
        RuntimeCheck.require(
          histories.forall(h =>
            ControllerHistory.prefix(h.snapshot, selected.snapshot),
          ) && ControllerHistory.prefix(observed, selected.snapshot),
          RuntimeFailureCode.JournalCorrupt,
          "controller snapshots fork or lose committed history",
        ),
      )
      _ <- EitherT(
        writeSnapshot(
          selected.snapshot,
          selected.snapshot.records.size.toLong,
          ControllerEventKind.Enforcement,
          false,
        ).attempt.map(_.left.map(storageFailure)),
      )
      _ <- EitherT.liftF(
        state.update(_.copy(history = selected, poisoned = false)),
      )
      _ <- selected.writes.toVector
        .filterNot((digest, _) => selected.writeReceipts.contains(digest))
        .sortBy(_._1.bytes.toHex)
        .traverse_ { (digest, intent) => completeWrite(digest, intent).void }
      ready <- current
    yield ready.snapshot
    operation.leftSemiflatTap(_ => poison)
  }

  def snapshot: Result[IO, ControllerSnapshot] = current.map(_.snapshot)

  private def keySignature(
      preimage: Bytes,
      kind: ControllerEventKind,
  ): Result[IO, Bytes] = for
    before <- current
    sequence = before.snapshot.records.size.toLong
    _ <- EitherT.liftF(
      faults.after(ControllerFaultPoint.BeforeKeyUse, sequence, kind),
    )
    signature <- EitherT(
      IO.delay(CryptoOps.sign(keys, CryptoOps.keccak256(preimage.toArray)))
        .map(
          _.left.map(e =>
            V2RuntimeFailure.at(RuntimeFailureCode.SignerFailure, e.msg),
          ),
        ),
    )
    bytes = ByteEncoder[Long].encode(
      signature.v.toLong,
    ) ++ signature.r.bytes ++ signature.s.bytes
    _ <- EitherT.liftF(
      faults.after(ControllerFaultPoint.AfterKeyUse, sequence, kind),
    )
    _ <- value(ControllerHistory.verifySignature(preimage, bytes, publicKey))
  yield bytes

  def sign(request: Bytes): Result[IO, ControllerSignature] = underGate() {
    for
      before   <- current
      material <- authentication.signing(request, signerId, publicKey)
      intent = ControllerSigningIntent(request, material)
      digest   <- core(ControllerSigningIntent.digest(intent))
      _        <- value(ControllerHistory.signingAllowed(before, material))
      retained <- before.signing.get(digest) match
        case Some(existing) =>
          value(
            RuntimeCheck.require(
              existing == intent,
              RuntimeFailureCode.CommitmentMismatch,
              "same signing id differs",
            ),
          ).as(before)
        case None =>
          core(ControllerSigningIntent.codec.encode(intent))
            .flatMap(append(ControllerEventKind.SigningIntent, _))
      result <- retained.signatures.get(digest) match
        case Some(signature) =>
          EitherT.pure[IO, V2RuntimeFailure](
            ControllerSignature(digest, signature),
          )
        case None =>
          for
            _ <- authentication.authorizeSigning(request, signerId, publicKey)
            signature <- keySignature(
              material.canonicalPreimage,
              ControllerEventKind.SigningResult,
            )
            result = ControllerSignature(digest, signature)
            bytes <- core(ControllerSignature.codec.encode(result))
            _     <- append(ControllerEventKind.SigningResult, bytes)
          yield result
    yield result
  }

  private def completeWrite(
      digest: Hash,
      intent: ControllerWriteIntent,
  ): Result[IO, ControllerWriteReceipt] = for
    before <- current
    _      <- EitherT.liftF(
      faults.after(
        ControllerFaultPoint.BeforeCanonicalWrite,
        before.snapshot.records.size.toLong,
        ControllerEventKind.WriteResult,
      ),
    )
    _      <- EitherT.liftF(poison)
    effect <- writer.apply(intent)
    _      <- writer.verify(intent, effect)
    _      <- EitherT.liftF(
      faults.after(
        ControllerFaultPoint.AfterCanonicalWrite,
        before.snapshot.records.size.toLong,
        ControllerEventKind.WriteResult,
      ),
    )
    _ <- EitherT.liftF(state.update(_.copy(poisoned = false)))
    result = ControllerWriteReceipt(digest, effect)
    bytes <- core(ControllerWriteReceipt.codec.encode(result))
    _     <- append(ControllerEventKind.WriteResult, bytes)
  yield result

  def writeCanonical(request: Bytes): Result[IO, ControllerWriteReceipt] =
    underGate() {
      for
        before   <- current
        material <- authentication.canonicalWrite(request)
        intent = ControllerWriteIntent(request, material)
        digest   <- core(ControllerWriteIntent.digest(intent))
        _        <- value(ControllerHistory.writingAllowed(before, material))
        retained <- before.writes.get(digest) match
          case Some(existing) =>
            value(
              RuntimeCheck.require(
                existing == intent,
                RuntimeFailureCode.CommitmentMismatch,
                "same write id differs",
              ),
            ).as(before)
          case None =>
            core(ControllerWriteIntent.codec.encode(intent))
              .flatMap(append(ControllerEventKind.WriteIntent, _))
        result <- retained.writeReceipts.get(digest) match
          case Some(effect) =>
            writer
              .verify(intent, effect)
              .as(ControllerWriteReceipt(digest, effect))
          case None => completeWrite(digest, intent)
      yield result
    }

  def prepareFence(
      intent: TransitionIntent,
      context: DomainContext,
      scope: FenceScope,
      boundary: Height,
  ): Result[IO, FencePromise] = underGate() {
    for
      before  <- current
      binding <- core(TransitionIntent.digest(intent))
      history <- value(
        ControllerHistory.signingHistoryDigest(before, context, scope),
      )
      promise = FencePromise(
        1L,
        context,
        signerId,
        scope,
        boundary,
        ControllerHistory.watermark(before, context, scope),
        history,
        binding,
      )
      _ <- core(FencePromise.validate(promise))
      _ <- authentication.enforce(intent, promise, signerId, publicKey)
    yield promise
  }

  def enforce(
      intent: TransitionIntent,
      promise: FencePromise,
  ): Result[IO, SignedFencePromise] = underGate() {
    for
      before <- current
      enforcement = ControllerEnforcement(intent, promise)
      digest   <- core(ControllerEnforcement.digest(enforcement))
      _        <- authentication.enforce(intent, promise, signerId, publicKey)
      retained <- before.enforcements.get(digest) match
        case Some(existing) =>
          value(
            RuntimeCheck.require(
              existing == enforcement,
              RuntimeFailureCode.CommitmentMismatch,
              "same enforcement id differs",
            ),
          ).as(before)
        case None =>
          core(ControllerEnforcement.codec.encode(enforcement))
            .flatMap(append(ControllerEventKind.Enforcement, _))
      signed <- retained.fences.get(digest) match
        case Some(result) => EitherT.pure[IO, V2RuntimeFailure](result)
        case None         =>
          for
            preimage  <- core(FencePromise.signingPreimage(promise))
            signature <- keySignature(
              preimage,
              ControllerEventKind.FenceSignature,
            )
            signed = SignedFencePromise(
              promise,
              SignatureEnvelope(signerId, 1.toByte, publicKey, signature),
            )
            bytes <- core(
              ControllerFenceSignature.codec.encode(
                ControllerFenceSignature(digest, signed),
              ),
            )
            _ <- append(ControllerEventKind.FenceSignature, bytes)
          yield signed
    yield signed
  }

  private def closeWrites(
      intent: TransitionIntent,
      context: DomainContext,
  ): Result[IO, Hash] = for
    before <- current
    closure = ControllerWriteClosure(intent, context)
    digest <- core(ControllerWriteClosure.digest(closure))
    _      <- authentication.closeWrites(closure, signerId, publicKey)
    _      <-
      if before.closures.contains(digest) then
        EitherT.pure[IO, V2RuntimeFailure](())
      else
        core(ControllerWriteClosure.codec.encode(closure))
          .flatMap(append(ControllerEventKind.WriteClosure, _))
          .void
  yield digest

  private final class LeaseOperations(
      live: Ref[IO, Boolean],
      leaseGate: Semaphore[IO],
  ):
    private def enabled[A](run: Result[IO, A]): Result[IO, A] = EitherT(
      leaseGate.permit.use(_ =>
        live.get.flatMap(active =>
          if active then checked(run) else IO.pure(Left(requiredRecovery)),
        ),
      ),
    )
    def snapshot: Result[IO, ControllerSnapshot] = enabled(
      FileFenceController.this.snapshot,
    )
    def closeWrites(
        intent: TransitionIntent,
        context: DomainContext,
    ): Result[IO, Hash] = enabled(
      FileFenceController.this.closeWrites(intent, context),
    )

  def withStopped[A](
      capture: StoppedFenceController => Result[IO, A],
  ): Result[IO, A] = underGate() {
    for
      before    <- current
      _         <- value(ControllerHistory.stopped(before))
      live      <- EitherT.liftF(Ref.of[IO, Boolean](true))
      leaseGate <- EitherT.liftF(Semaphore[IO](1L))
      enabled = new LeaseOperations(live, leaseGate)
      lease   = new StoppedFenceController(
        () => enabled.snapshot,
        (intent, context) => enabled.closeWrites(intent, context),
      )
      _      <- EitherT.liftF(state.update(_.copy(stopped = true)))
      result <- EitherT(
        capture(lease).value.guarantee(
          leaseGate.permit.use(_ => live.set(false)) *> state.update(
            _.copy(stopped = false),
          ),
        ),
      )
    yield result
  }

  def preserveAfterRestore(
      backup: ControllerSnapshot,
  ): Result[IO, ControllerSnapshot] = underGate() {
    for
      before <- current
      _      <- value(ControllerHistory.recover(backup))
      _      <- value(
        RuntimeCheck.require(
          ControllerHistory.prefix(backup, before.snapshot),
          RuntimeFailureCode.EvidenceContradictory,
          "backup is not a prefix of complete current controller history",
        ),
      )
    yield before.snapshot
  }

  def audit: Result[IO, ControllerAuditInventory] = snapshot.flatMap {
    original =>
      for
        configurationBytes <- core(
          ControllerConfiguration.codec.encode(original.configuration),
        )
        configurationDigest <- core(
          ControllerConfiguration.digest(original.configuration),
        )
        records <- original.records.traverse { record =>
          for
            bytes  <- core(ControllerRecord.codec.encode(record))
            digest <- core(ControllerRecord.digest(record))
          yield ControllerAuditArtifact(
            InventoryEntry(
              Utf8("controller-records"),
              Utf8(
                UInt256
                  .unsafeFromBigIntUnsigned(BigInt(record.sequence))
                  .bytes
                  .toHex,
              ),
              digest,
            ),
            bytes,
          )
        }
        entries = (ControllerAuditArtifact(
          InventoryEntry(
            Utf8("controller-configuration"),
            signerId,
            configurationDigest,
          ),
          configurationBytes,
        ) +: records).sortBy(a =>
          (
            V2Validation.textKey(a.entry.namespace),
            V2Validation.textKey(a.entry.key),
          ),
        )
        history <- value(ControllerHistory.recover(original))
        orderedSigning = history.signing.toVector.sortBy(_._1.bytes.toHex)
        observed       = orderedSigning.flatMap((digest, intent) =>
          history.signatures
            .get(digest)
            .map(ControllerObservedSignature(intent, _)),
        )
      yield ControllerAuditInventory(
        original,
        entries.map(_.entry),
        entries,
        orderedSigning.map(_._2),
        observed,
        history.enforcements.toVector.sortBy(_._1.bytes.toHex).map(_._2),
        history.fences.toVector.sortBy(_._1.bytes.toHex).map(_._2),
        history.closures.toVector.sortBy(_._1.bytes.toHex).map(_._2),
      )
  }

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.Any",
  ),
)
object FileFenceController:
  private val Ledger   = "LEDGER"
  private val Identity = "IDENTITY"
  private val Magic    = ByteVector.fromValidHex("53494746454e434531")
  private val owners   = new ConcurrentHashMap[String, java.lang.Boolean]()
  private final case class State(
      history: ControllerHistory,
      poisoned: Boolean,
      closed: Boolean,
      stopped: Boolean,
  )
  private val requiredRecovery = V2RuntimeFailure.at(
    RuntimeFailureCode.RecoveryRequired,
    "controller is closed, stopped, or requires complete authenticated recovery",
  )
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def abort(code: RuntimeFailureCode, detail: String): Nothing =
    throw new FenceControllerOpenException(V2RuntimeFailure.at(code, detail))
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def required[A](value: Either[V2RuntimeFailure, A]): A =
    value.fold(e => throw new FenceControllerOpenException(e), identity)
  private def storageFailure(error: Throwable): V2RuntimeFailure = error match
    case known: FenceControllerOpenException => known.failure
    case _                                   =>
      V2RuntimeFailure.at(
        RuntimeFailureCode.StorageUnknown,
        "controller filesystem, cryptographic callback, or durable effect outcome is unknown",
      )
  private def frame(bytes: Bytes): Bytes = Magic ++ Commitment
    .hash(Utf8("sigilaris.application.controller.file.v1"), bytes)
    .bytes ++ bytes
  private def unframe(bytes: Bytes): Bytes =
    if !bytes.startsWith(Magic) || bytes.size < Magic.size + 32L then
      abort(
        RuntimeFailureCode.JournalCorrupt,
        "controller file header is incomplete",
      )
    val payload = bytes.drop(Magic.size + 32L)
    if bytes.slice(Magic.size, Magic.size + 32L) != Commitment
        .hash(Utf8("sigilaris.application.controller.file.v1"), payload)
        .bytes
    then
      abort(
        RuntimeFailureCode.JournalCorrupt,
        "controller file checksum mismatch",
      )
    payload
  private def ensureDirectory(path: Path): Unit =
    if !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files
        .isSymbolicLink(path)
    then
      abort(
        RuntimeFailureCode.JournalCorrupt,
        "controller path is not a physical directory",
      )
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  private def ensureRegular(path: Path, required: Boolean = true): Unit =
    if Files.exists(path, LinkOption.NOFOLLOW_LINKS) then
      if !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files
          .isSymbolicLink(path)
      then
        abort(
          RuntimeFailureCode.JournalCorrupt,
          "controller file is not regular",
        )
    else if required then
      abort(
        RuntimeFailureCode.JournalCorrupt,
        "required controller file is missing",
      )
  private def forceDirectory(path: Path): Unit = Using.resource(
    FileChannel.open(path, StandardOpenOption.READ),
  )(_.force(true))
  @SuppressWarnings(Array("org.wartremover.warts.While"))
  private def writeAll(channel: FileChannel, bytes: Bytes): Unit =
    val buffer = ByteBuffer.wrap(bytes.toArray)
    while buffer.hasRemaining do
      val written = channel.write(buffer)
      if written <= 0 then
        abort(
          RuntimeFailureCode.StorageUnknown,
          "controller file write did not progress",
        )
  private def read(path: Path, maximum: Long): Bytes =
    ensureRegular(path)
    if Files.size(path) > maximum + Magic.size + 32L then
      abort(
        RuntimeFailureCode.CapacityUnavailable,
        "controller file exceeds local read capacity",
      )
    unframe(ByteVector.view(Files.readAllBytes(path)))
  private def writeNew(path: Path, bytes: Bytes): Unit = Using.resource(
    FileChannel.open(
      path,
      StandardOpenOption.CREATE_NEW,
      StandardOpenOption.WRITE,
      LinkOption.NOFOLLOW_LINKS,
    ),
  ) { output => writeAll(output, frame(bytes)); output.force(true) }
  private def readSnapshots(
      root: Path,
      configuration: ControllerConfiguration,
      maximum: Long,
  ): Vector[ControllerSnapshot] =
    ensureDirectory(root)
    val identity = required(
      RuntimeCheck.core(
        ControllerConfiguration.codec.decode(
          read(root.resolve(Identity), maximum),
        ),
      ),
    )
    if identity != configuration then
      abort(
        RuntimeFailureCode.StartupIdentityMismatch,
        "controller identity or original key-use evidence changed",
      )
    val files = Using.resource(Files.list(root))(_.iterator().asScala.toVector)
    if files.exists(p =>
        !Set("LOCK", Identity, Ledger).contains(
          p.getFileName.toString,
        ) && !p.getFileName.toString.startsWith("pending-"),
      )
    then
      abort(
        RuntimeFailureCode.JournalCorrupt,
        "unknown file in controller consistency group",
      )
    files
      .filter(p =>
        p.getFileName.toString == Ledger || p.getFileName.toString
          .startsWith("pending-"),
      )
      .map(p =>
        required(
          RuntimeCheck.core(ControllerSnapshot.codec.decode(read(p, maximum))),
        ),
      )

  /** Acquires the exclusive filesystem/key lease and immediately performs full
    * recovery. `initialEvidence` is immutable, independently authenticated key
    * history, including previously observed signatures, writes and fences.
    */
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def resource(
      root: Path,
      keys: KeyPair,
      signerId: Text,
      initialEvidence: Bytes,
      authentication: ControllerOperationAuthentication,
      writer: ControllerCanonicalWriter,
      faults: ControllerFaultInjector = ControllerFaultInjector.none,
      maximumBytes: Long = 268435456L,
  ): Resource[IO, FileFenceController] =
    val normalized = root.toAbsolutePath.normalize()
    val ownerKeys  = Vector(
      "path:" + normalized.toString,
      "key:" + keys.publicKey.toBytes.toHex,
    )
    val reservation = Resource.make(IO.blocking {
      ownerKeys.foldLeft(Vector.empty[String]) { (held, key) =>
        if owners.putIfAbsent(key, java.lang.Boolean.TRUE) != null then
          held.foreach(k =>
            owners.remove(k); (),
          )
          abort(
            RuntimeFailureCode.RecoveryRequired,
            "another controller owns this path or signing key",
          )
        held :+ key
      }
    })(held =>
      IO.blocking(held.foreach(k =>
        owners.remove(k); (),
      )),
    )
    val opened = for
      _ <- Resource.eval(IO.blocking {
        if maximumBytes <= 0L || maximumBytes > Int.MaxValue.toLong - 128L then
          abort(
            RuntimeFailureCode.CapacityUnavailable,
            "controller capacity must fit a bounded JVM byte array",
          )
        if CryptoOps
            .fromPrivate(keys.privateKey.toBigIntUnsigned)
            .publicKey
            .toBytes != keys.publicKey.toBytes
        then
          abort(
            RuntimeFailureCode.InvalidSignature,
            "controller private/public key pair differs",
          )
      })
      _ <- reservation
      _ <- Resource.eval(IO.blocking {
        val existing = Iterator
          .iterate(normalized)(_.getParent)
          .takeWhile(_ != null)
          .toVector
          .reverse
        existing.foreach(path =>
          if Files.exists(path, LinkOption.NOFOLLOW_LINKS) then
            ensureDirectory(path),
        )
        if !Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) then
          Files.createDirectory(normalized)
          forceDirectory(normalized.getParent)
        ensureDirectory(normalized)
        ensureRegular(normalized.resolve("LOCK"), required = false)
      })
      channel <- Resource.fromAutoCloseable(
        IO.blocking(
          FileChannel.open(
            normalized.resolve("LOCK"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
          ),
        ),
      )
      _ <- Resource.make(
        IO.blocking(
          Option(channel.tryLock()).getOrElse(
            abort(
              RuntimeFailureCode.RecoveryRequired,
              "controller file lock is held",
            ),
          ),
        ),
      )(lock => IO.blocking(lock.release()))
      initial <- Resource.eval(
        authentication
          .initialHistory(initialEvidence, signerId, keys.publicKey.toBytes)
          .value
          .flatMap(v =>
            IO.fromEither(v.left.map(new FenceControllerOpenException(_))),
          ),
      )
      configuration = ControllerConfiguration(
        1L,
        signerId,
        keys.publicKey.toBytes,
        initialEvidence,
        initial,
      )
      seed <- Resource.eval(
        IO.fromEither(
          ControllerHistory
            .seed(configuration)
            .left
            .map(new FenceControllerOpenException(_)),
        ),
      )
      _ <- Resource.eval(IO.blocking {
        val files =
          Using.resource(Files.list(normalized))(_.iterator().asScala.toVector)
        if !Files
            .exists(normalized.resolve(Identity), LinkOption.NOFOLLOW_LINKS)
        then
          if files.exists(_.getFileName.toString != "LOCK") then
            abort(
              RuntimeFailureCode.JournalCorrupt,
              "missing controller identity with surviving history",
            )
          writeNew(
            normalized.resolve(Identity),
            required(
              RuntimeCheck.core(
                ControllerConfiguration.codec.encode(configuration),
              ),
            ),
          )
          forceDirectory(normalized)
          writeNew(
            normalized.resolve(Ledger),
            required(
              RuntimeCheck.core(ControllerSnapshot.codec.encode(seed.snapshot)),
            ),
          )
          forceDirectory(normalized)
      })
      gate  <- Resource.eval(Semaphore[IO](1L))
      state <- Resource.eval(Ref.of[IO, State](State(seed, true, false, false)))
      controller <- Resource.make(
        IO.pure(
          new FileFenceController(
            normalized,
            keys,
            configuration,
            authentication,
            writer,
            faults,
            maximumBytes,
            gate,
            state,
          ),
        ),
      )(_.close)
      _ <- Resource.eval(
        controller.recover.value
          .flatMap(v =>
            IO.fromEither(v.left.map(new FenceControllerOpenException(_))),
          )
          .void,
      )
    yield controller

    opened.handleErrorWith(error =>
      Resource.eval(
        IO.raiseError(new FenceControllerOpenException(storageFailure(error))),
      ),
    )
