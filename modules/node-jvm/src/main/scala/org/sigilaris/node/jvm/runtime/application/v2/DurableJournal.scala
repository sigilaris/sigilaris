package org.sigilaris.node.jvm.runtime.application.v2

import cats.data.EitherT
import cats.effect.kernel.{Async, Ref}
import cats.effect.std.Semaphore
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.datatype.UInt256

/** Canonical authoritative history. A newly opened backend requires recovery
  * before writes; an unknown write outcome fences it until recovery succeeds.
  */
trait DurableJournal[F[_]]:
  def recover: Result[F, Vector[JournalRecord]]
  def append(record: JournalRecord): Result[F, Unit]
  def putBlob(namespace: Text, digest: Hash, bytes: Bytes): Result[F, Unit]
  def readBlob(namespace: Text, digest: Hash): Result[F, Bytes]

enum JournalFaultPoint:
  case AfterFrameWrite, AfterFrameForce
  case AfterHeadWrite, AfterHeadMove, AfterHeadForce
  case AfterBlobWrite, AfterBlobForce, AfterBlobMove, AfterBlobDirectoryForce

trait JournalFaultInjector[F[_]]:
  def after(point: JournalFaultPoint, sequence: Long): F[Unit]

object JournalFaultInjector:
  def none[F[_]: Async]: JournalFaultInjector[F] = new JournalFaultInjector[F]:
    def after(point: JournalFaultPoint, sequence: Long): F[Unit] = Async[F].unit

final class JournalOpenException(val failure: V2RuntimeFailure)
    extends RuntimeException(failure.message)

private[jvm] final case class JournalHead(sequence: Long, digest: Hash)

private[jvm] object JournalHead:
  val empty: JournalHead =
    JournalHead(0L, UInt256.unsafeFromBigIntUnsigned(BigInt(0)))

private[jvm] final case class JournalHistory(
    records: Vector[JournalRecord],
    head: JournalHead,
    pending: Option[JournalRecord],
)

/** Shared physical-history validation; application authorization is performed
  * by the safety reducer before appending and again after recovery.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
private[jvm] object JournalHistory:
  val empty: JournalHistory =
    JournalHistory(Vector.empty, JournalHead.empty, None)

  def recordDigest(record: JournalRecord): Either[V2RuntimeFailure, Hash] =
    JournalRecord
      .digest(record)
      .left
      .map(V2RuntimeFailure.fromCore)

  def append(
      history: JournalHistory,
      record: JournalRecord,
  ): Either[V2RuntimeFailure, JournalHistory] =
    for
      _ <- JournalRecord.codec
        .encode(record)
        .left
        .map(V2RuntimeFailure.fromCore)
      next <- record.status match
        case JournalStatus.Prepared =>
          if history.pending.nonEmpty || history.head.sequence == Long.MaxValue ||
            record.sequence != history.head.sequence + 1L || record.previousDigest != history.head.digest
          then
            Left(
              V2RuntimeFailure.at(
                RuntimeFailureCode.JournalCorrupt,
                "prepared record does not extend the committed journal",
              ),
            )
          else
            Right(
              history.copy(
                records = history.records :+ record,
                pending = Some(record),
              ),
            )
        case JournalStatus.Committed =>
          if !history.pending.contains(
              record.copy(status = JournalStatus.Prepared),
            )
          then
            Left(
              V2RuntimeFailure.at(
                RuntimeFailureCode.JournalCorrupt,
                "commit does not match the complete prepared record",
              ),
            )
          else
            recordDigest(record).map(digest =>
              JournalHistory(
                history.records :+ record,
                JournalHead(record.sequence, digest),
                None,
              ),
            )
    yield next

  def validate(
      records: Vector[JournalRecord],
  ): Either[V2RuntimeFailure, JournalHistory] =
    records.foldLeft[Either[V2RuntimeFailure, JournalHistory]](Right(empty))(
      (acc, record) => acc.flatMap(append(_, record)),
    )

  def validateHead(
      history: JournalHistory,
      head: JournalHead,
  ): Either[V2RuntimeFailure, Unit] =
    if head == JournalHead.empty then Right(())
    else
      history.records
        .find(record =>
          record.sequence == head.sequence && record.status == JournalStatus.Committed,
        )
        .toRight(
          V2RuntimeFailure.at(
            RuntimeFailureCode.JournalCorrupt,
            "durable HEAD references missing committed history",
          ),
        )
        .flatMap(recordDigest)
        .flatMap(digest =>
          Either.cond(
            digest == head.digest,
            (),
            V2RuntimeFailure.at(
              RuntimeFailureCode.JournalCorrupt,
              "durable HEAD digest differs from committed history",
            ),
          ),
        )

  def namespace(value: Text): Either[V2RuntimeFailure, String] =
    val name = value.asString
    Either.cond(
      name.matches("[a-z0-9][a-z0-9_-]{0,63}"),
      name,
      V2RuntimeFailure.at(
        RuntimeFailureCode.InvalidRequest,
        "blob namespace must be a single bounded lowercase ASCII path component",
      ),
    )

/** In-memory implementation of the same unknown-outcome and ordering contract.
  * Faults after a simulated write retain that write; recover never discards it.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
final class MemoryDurableJournal[F[_]: Async] private (
    state: Ref[F, MemoryDurableJournal.State],
    gate: Semaphore[F],
    faults: JournalFaultInjector[F],
) extends DurableJournal[F]:
  import MemoryDurableJournal.State

  private def fenced: V2RuntimeFailure = V2RuntimeFailure.at(
    RuntimeFailureCode.RecoveryRequired,
    "journal recovery is required",
  )

  private def unknown(error: Throwable): V2RuntimeFailure =
    V2RuntimeFailure.at(
      RuntimeFailureCode.StorageUnknown,
      Option(error.getMessage).getOrElse(error.getClass.getName),
    )

  private def underGate[A](
      run: State => F[Either[V2RuntimeFailure, A]],
  ): Result[F, A] =
    EitherT(
      gate.permit.use(_ => Async[F].uncancelable(_ => state.get.flatMap(run))),
    )

  private def durable[A](run: F[A]): F[Either[V2RuntimeFailure, A]] =
    state.update(_.copy(poisoned = true)) *> run.attempt.flatMap:
      case Left(error)  => Async[F].pure(Left(unknown(error)))
      case Right(value) =>
        state.update(_.copy(poisoned = false)).as(Right(value))

  def recover: Result[F, Vector[JournalRecord]] = underGate: current =>
    val checked = for
      history <- JournalHistory.validate(current.records)
      _       <- JournalHistory.validateHead(history, current.head)
    yield history
    checked match
      case Left(error) => state.update(_.copy(poisoned = true)).as(Left(error))
      case Right(history) =>
        durable(state.update(_.copy(head = history.head)).as(history.records))

  def append(record: JournalRecord): Result[F, Unit] = underGate: current =>
    if current.poisoned then Async[F].pure(Left(fenced))
    else if current.records.lastOption.contains(record) then
      Async[F].pure(Right(()))
    else
      val checked = JournalHistory
        .validate(current.records)
        .flatMap(JournalHistory.append(_, record))
      checked match
        case Left(error)    => Async[F].pure(Left(error))
        case Right(history) =>
          durable:
            state.update(_.copy(records = history.records)) *>
              faults.after(
                JournalFaultPoint.AfterFrameWrite,
                record.sequence,
              ) *>
              faults.after(
                JournalFaultPoint.AfterFrameForce,
                record.sequence,
              ) *>
              (if record.status == JournalStatus.Committed then
                 faults.after(
                   JournalFaultPoint.AfterHeadWrite,
                   record.sequence,
                 ) *>
                   state.update(_.copy(head = history.head)) *>
                   faults.after(
                     JournalFaultPoint.AfterHeadMove,
                     record.sequence,
                   ) *>
                   faults.after(
                     JournalFaultPoint.AfterHeadForce,
                     record.sequence,
                   )
               else Async[F].unit)

  def putBlob(namespace: Text, digest: Hash, bytes: Bytes): Result[F, Unit] =
    underGate: current =>
      if current.poisoned then Async[F].pure(Left(fenced))
      else
        JournalHistory.namespace(namespace) match
          case Left(error) => Async[F].pure(Left(error))
          case Right(name) =>
            val key = name -> digest
            current.blobs.get(key) match
              case Some(existing) =>
                Async[F].pure(
                  Either.cond(
                    existing == bytes,
                    (),
                    V2RuntimeFailure.at(
                      RuntimeFailureCode.CommitmentMismatch,
                      "blob identifier is already bound to different bytes",
                    ),
                  ),
                )
              case None =>
                durable:
                  faults.after(
                    JournalFaultPoint.AfterBlobWrite,
                    current.head.sequence,
                  ) *>
                    faults.after(
                      JournalFaultPoint.AfterBlobForce,
                      current.head.sequence,
                    ) *>
                    state.update(value =>
                      value.copy(blobs = value.blobs.updated(key, bytes)),
                    ) *>
                    faults.after(
                      JournalFaultPoint.AfterBlobMove,
                      current.head.sequence,
                    ) *>
                    faults.after(
                      JournalFaultPoint.AfterBlobDirectoryForce,
                      current.head.sequence,
                    )

  def readBlob(namespace: Text, digest: Hash): Result[F, Bytes] = underGate:
    current =>
      Async[F].pure:
        if current.poisoned then Left(fenced)
        else
          for
            name  <- JournalHistory.namespace(namespace)
            bytes <- current.blobs
              .get(name -> digest)
              .toRight(
                V2RuntimeFailure.at(
                  RuntimeFailureCode.ProofUnavailable,
                  "immutable blob is unavailable",
                ),
              )
          yield bytes

object MemoryDurableJournal:
  private final case class State(
      records: Vector[JournalRecord],
      head: JournalHead,
      blobs: Map[(String, Hash), Bytes],
      poisoned: Boolean,
  )

  def create[F[_]: Async]: F[MemoryDurableJournal[F]] =
    createWithFaults(JournalFaultInjector.none[F])

  def createWithFaults[F[_]: Async](
      faults: JournalFaultInjector[F],
  ): F[MemoryDurableJournal[F]] =
    for
      state <- Ref.of[F, State](
        State(Vector.empty, JournalHead.empty, Map.empty, true),
      )
      gate <- Semaphore[F](1L)
    yield new MemoryDurableJournal(state, gate, faults)
