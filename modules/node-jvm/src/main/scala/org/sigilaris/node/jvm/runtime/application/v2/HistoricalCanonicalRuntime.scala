package org.sigilaris.node.jvm.runtime.application.v2

import java.nio.file.Path

import cats.data.EitherT
import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.datatype.UInt256
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.FinalizedAnchorSuggestion

/** The authoritative old-profile canonical frontier after an installed
  * handover. The certified working parent P never becomes finalized through
  * installation alone. Only original-profile, fully replayed real finality may
  * advance this independent journal from the actual finalized checkpoint F.
  */
sealed trait HistoricalCanonicalRuntime[F[_]]:
  def base: HandoverInstalledBase
  def recover: Result[F, ApplicationAnchor]
  def canonical: Result[F, ApplicationAnchor]
  def canonicalPayload: Result[F, Bytes]
  def validateTarget(finalized: FinalizedAnchorSuggestion): Result[F, Unit]
  def catchUp(
      finalized: FinalizedAnchorSuggestion,
  ): Result[F, ApplicationAnchor]

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object HistoricalCanonicalRuntime:
  private val zero = UInt256.unsafeFromBigIntUnsigned(BigInt(0))
  private final case class History(
      records: Vector[HistoricalCanonicalRecord],
      sequence: Long,
      digest: Hash,
      pending: Option[HistoricalCanonicalRecord],
      canonical: ApplicationAnchor,
      payload: Bytes,
  )
  private final case class State(
      history: History,
      ready: Boolean,
      closed: Boolean,
  )

  /** Opens one exclusive physical writer. Recovery authenticates every original
    * source, execution, certificate and current transition fence before this
    * resource can participate in ordinary runtime readiness.
    */
  def resource(
      path: Path,
      handover: VerifiedHandover,
      transitions: TransitionEvidenceVerifier[IO],
      verifier: HistoricalCanonicalVerifier[IO],
      maximumBytes: Long,
      faults: JournalFaultInjector[IO],
  ): Resource[IO, HistoricalCanonicalRuntime[IO]] =
    def required[A](run: Result[IO, A]): IO[A] = run.value.flatMap(
      _.fold(error => IO.raiseError(new JournalOpenException(error)), IO.pure),
    )
    Resource
      .eval(required(for
        current <- transitions.verifyHandover(handover.evidence)
        _       <- EitherT.fromEither[IO](
          RuntimeCheck.require(
            current.digest == handover.digest,
            RuntimeFailureCode.StartupIdentityMismatch,
            "historical materializer changed its installed handover",
          ),
        )
        installed <- verifier.verifyInstallation(current)
      yield installed))
      .flatMap { installed =>
        CanonicalAppendLog
          .resource(path, installed.handoverDigest.bytes, maximumBytes, faults)
          .flatMap { log =>
            Resource
              .eval(
                (
                  Semaphore[IO](1L),
                  Ref.of[IO, State](
                    State(
                      History(
                        Vector.empty,
                        0L,
                        zero,
                        None,
                        installed.canonicalStart,
                        installed.canonicalStartPayload,
                      ),
                      false,
                      false,
                    ),
                  ),
                ).tupled,
              )
              .flatMap { (gate, state) =>
                val runtime = new HistoricalCanonicalRuntime[IO]:
                  val base: HandoverInstalledBase = installed
                  private def pure[A](
                      value: Either[V2RuntimeFailure, A],
                  ): Result[IO, A] = EitherT.fromEither[IO](value)
                  private def core[A](
                      value: Either[CoreFailure, A],
                  ): Result[IO, A] = pure(RuntimeCheck.core(value))
                  private def check(condition: Boolean, detail: String)
                      : Result[IO, Unit] =
                    pure(
                      RuntimeCheck.require(
                        condition,
                        RuntimeFailureCode.JournalCorrupt,
                        detail,
                      ),
                    )
                  private def poison: IO[Unit] =
                    state.update(_.copy(ready = false))
                  private def under[A](
                      recovering: Boolean,
                  )(run: History => Result[IO, A]): Result[IO, A] = EitherT(
                    gate.permit.use(_ =>
                      IO.uncancelable(_ =>
                        state.get.flatMap { before =>
                          if before.closed || (!recovering && !before.ready)
                          then
                            IO.pure(
                              Left(
                                V2RuntimeFailure.at(
                                  RuntimeFailureCode.RecoveryRequired,
                                  "historical canonical materialization requires recovery",
                                ),
                              ),
                            )
                          else
                            run(before.history).value.attempt.flatMap {
                              case Right(Right(value)) => IO.pure(Right(value))
                              case Right(Left(error))  => poison.as(Left(error))
                              case Left(_)             =>
                                poison.as(
                                  Left(
                                    V2RuntimeFailure.at(
                                      RuntimeFailureCode.StorageUnknown,
                                      "historical canonical proof or storage outcome is unknown",
                                    ),
                                  ),
                                )
                            }
                        },
                      ),
                    ),
                  )
                  private def installation: Result[IO, Unit] = for
                    current <- transitions.verifyHandover(
                      base.handover.evidence,
                    )
                    actual <- verifier.verifyInstallation(current)
                    _      <- check(
                      actual.handoverDigest == base.handoverDigest && actual.canonicalStart == base.canonicalStart &&
                        actual.workingParent == base.workingParent && actual.executionAnchor == base.executionAnchor &&
                        actual.workingStatePayload == base.workingStatePayload && actual.canonicalStartPayload == base.canonicalStartPayload,
                      "installed original F/P identity, state data or transition evidence changed",
                    )
                  yield ()

                  private def append(
                      history: History,
                      record: HistoricalCanonicalRecord,
                  ): Result[IO, History] = for
                    _    <- core(HistoricalCanonicalRecord.codec.encode(record))
                    next <- record.status match
                      case HistoricalCanonicalStatus.Prepared =>
                        for
                          _ <- check(
                            history.pending.isEmpty && history.sequence < Long.MaxValue && record.sequence == history.sequence + 1L && record.previousDigest == history.digest,
                            "historical prepare does not extend the committed original history",
                          )
                          verified <- verifier.verify(
                            base,
                            history.canonical,
                            record.advance.finalized,
                          )
                          _ <- check(
                            verified.advance == record.advance && verified.digest == record.advanceDigest,
                            "historical record changed its original context, proposal, finality or replayed state/results",
                          )
                        yield history.copy(
                          records = history.records :+ record,
                          pending = Some(record),
                        )
                      case HistoricalCanonicalStatus.Committed =>
                        for
                          _ <- check(
                            history.pending.contains(
                              record.copy(status =
                                HistoricalCanonicalStatus.Prepared,
                              ),
                            ),
                            "historical commit has no identical complete authenticated prepare",
                          )
                          digest <- core(
                            HistoricalCanonicalRecord.digest(record),
                          )
                        yield History(
                          history.records :+ record,
                          record.sequence,
                          digest,
                          None,
                          ApplicationAnchor(
                            record.advance.context,
                            record.advance.blockId,
                            record.advance.height,
                            record.advance.nextStateRoot,
                          ),
                          record.advance.material.canonicalStatePayload,
                        )
                  yield next

                  private def write(
                      record: HistoricalCanonicalRecord,
                  ): Result[IO, Unit] =
                    core(HistoricalCanonicalRecord.codec.encode(record))
                      .flatMap(log.append)

                  def recover: Result[IO, ApplicationAnchor] = under(true) {
                    observed =>
                      for
                        _       <- EitherT.liftF(poison)
                        _       <- installation
                        raw     <- log.recover
                        decoded <- raw.traverse(bytes =>
                          core(HistoricalCanonicalRecord.codec.decode(bytes)),
                        )
                        _ <- check(
                          decoded.startsWith(observed.records),
                          "historical recovery lost previously observed canonical records",
                        )
                        verified <- decoded.foldLeftM(
                          History(
                            Vector.empty,
                            0L,
                            zero,
                            None,
                            base.canonicalStart,
                            base.canonicalStartPayload,
                          ),
                        )(append)
                        completed <- verified.pending match
                          case None =>
                            EitherT.pure[IO, V2RuntimeFailure](verified)
                          case Some(pending) =>
                            for
                              next <- append(
                                verified,
                                pending.copy(status =
                                  HistoricalCanonicalStatus.Committed,
                                ),
                              )
                              _ <- write(
                                pending.copy(status =
                                  HistoricalCanonicalStatus.Committed,
                                ),
                              )
                            yield next
                        _ <- EitherT.liftF(
                          state.set(State(completed, true, false)),
                        )
                      yield completed.canonical
                  }

                  def canonical: Result[IO, ApplicationAnchor] =
                    under(false)(history => EitherT.pure(history.canonical))
                  def canonicalPayload: Result[IO, Bytes] =
                    under(false)(history => EitherT.pure(history.payload))
                  def validateTarget(
                      finalized: FinalizedAnchorSuggestion,
                  ): Result[IO, Unit] = under(false) { history =>
                    installation *> verifier
                      .catchUp(base, history.canonical, finalized)
                      .void
                  }
                  def catchUp(
                      finalized: FinalizedAnchorSuggestion,
                  ): Result[IO, ApplicationAnchor] = under(false) { before =>
                    for
                      _ <- installation
                      // Authenticate the entire old path before its first durable write.
                      advances <- verifier.catchUp(
                        base,
                        before.canonical,
                        finalized,
                      )
                      after <- advances.foldLeftM(before) { (history, value) =>
                        for
                          _ <- check(
                            history.sequence < Long.MaxValue,
                            "historical journal sequence capacity exhausted",
                          )
                          prepared = HistoricalCanonicalRecord(
                            1L,
                            history.sequence + 1L,
                            history.digest,
                            value.advance,
                            value.digest,
                            HistoricalCanonicalStatus.Prepared,
                          )
                          preparedState <- append(history, prepared)
                          committed = prepared
                            .copy(status = HistoricalCanonicalStatus.Committed)
                          committedState <- append(preparedState, committed)
                          _              <- EitherT.liftF(poison)
                          _              <- write(prepared)
                          _              <- EitherT.liftF(
                            state.set(State(preparedState, false, false)),
                          )
                          _ <- write(committed)
                          _ <- EitherT.liftF(
                            state.set(State(committedState, true, false)),
                          )
                        yield committedState
                      }
                    yield after.canonical
                  }
                Resource.make(IO.pure(runtime))(_ =>
                  gate.permit.use(_ =>
                    state.update(_.copy(ready = false, closed = true)),
                  ),
                )
              }
          }
      }
