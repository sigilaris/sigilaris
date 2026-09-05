package org.sigilaris.node.jvm.runtime.diagnostics

import scala.concurrent.duration.*

import cats.effect.kernel.{Deferred, Outcome, Ref, Temporal}
import cats.syntax.all.*

final case class RuntimeDiagnosticsCachePolicy private (
    ttl: FiniteDuration,
)

object RuntimeDiagnosticsCachePolicy:
  val DefaultTtl: FiniteDuration = 1.second

  val default: RuntimeDiagnosticsCachePolicy =
    unsafe(DefaultTtl)

  def fromTtl(
      ttl: FiniteDuration,
  ): Either[String, RuntimeDiagnosticsCachePolicy] =
    Either.cond(
      ttl > Duration.Zero,
      RuntimeDiagnosticsCachePolicy(ttl),
      "Runtime diagnostics cache TTL must be positive: " + ttl.toString,
    )

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def unsafe(ttl: FiniteDuration): RuntimeDiagnosticsCachePolicy =
    fromTtl(ttl).fold(
      message => throw new IllegalArgumentException(message),
      identity,
    )

final class RuntimeDiagnosticsCache[F[_], A] private (
    policy: RuntimeDiagnosticsCachePolicy,
    state: Ref[F, RuntimeDiagnosticsCache.State[F, A]],
)(using F: Temporal[F]):

  import RuntimeDiagnosticsCache.*

  def get(refresh: F[A]): F[A] =
    F.uncancelable: poll =>
      for
        now     <- F.monotonic
        current <- state.get
        value   <- freshValue(current, now) match
          case Some(cached) =>
            cached.pure[F]
          case None =>
            current.inFlight match
              case Some(existing) =>
                poll(await(existing, refresh))
              case None =>
                for
                  signal   <- Deferred[F, RefreshResult[A]]
                  decision <- state.modify(decide(signal, now))
                  value    <- decision match
                    case Decision.Hit(value) =>
                      value.pure[F]
                    case Decision.Wait(existing) =>
                      poll(await(existing, refresh))
                    case Decision.Start(created) =>
                      startRefresh(created, refresh) *>
                        poll(await(created, refresh))
                yield value
      yield value

  private def freshValue(
      current: State[F, A],
      now: FiniteDuration,
  ): Option[A] =
    current.cached
      .filter(cached => now < cached.expiresAt)
      .map(_.value)

  private def decide(
      created: Deferred[F, RefreshResult[A]],
      now: FiniteDuration,
  )(current: State[F, A]): (State[F, A], Decision[F, A]) =
    freshValue(current, now) match
      case Some(value) =>
        current -> Decision.Hit(value)
      case None =>
        current.inFlight match
          case Some(existing) =>
            current -> Decision.Wait(existing)
          case None =>
            current.copy(inFlight = Some(created)) -> Decision.Start(created)

  private def startRefresh(
      signal: Deferred[F, RefreshResult[A]],
      refresh: F[A],
  ): F[Unit] =
    val run = F.guaranteeCase(
      refresh.attempt
        .flatMap:
          case Right(value) => completeSuccess(signal, value)
          case Left(error)  => completeFailure(signal, error),
    ):
      case Outcome.Canceled() => completeCanceled(signal)
      case _                  => F.unit

    F.start(run).void

  private def completeSuccess(
      signal: Deferred[F, RefreshResult[A]],
      value: A,
  ): F[Unit] =
    for
      now <- F.monotonic
      cached = Cached(value, now + policy.ttl)
      _ <- state.update: current =>
        if current.inFlight.contains(signal) then
          State(
            cached = Some(cached),
            inFlight = None,
          )
        else current
      _ <- signal.complete(RefreshResult.Succeeded(value)).void
    yield ()

  private def completeFailure(
      signal: Deferred[F, RefreshResult[A]],
      error: Throwable,
  ): F[Unit] =
    state.update(clearInFlight(signal)) *>
      signal.complete(RefreshResult.Failed[A](error)).void

  private def completeCanceled(
      signal: Deferred[F, RefreshResult[A]],
  ): F[Unit] =
    state.update(clearInFlight(signal)) *>
      signal.complete(RefreshResult.Retry[A]()).void

  private def clearInFlight(
      signal: Deferred[F, RefreshResult[A]],
  )(current: State[F, A]): State[F, A] =
    if current.inFlight.contains(signal) then current.copy(inFlight = None)
    else current

  private def await(
      signal: Deferred[F, RefreshResult[A]],
      refresh: F[A],
  ): F[A] =
    signal.get.flatMap:
      case RefreshResult.Succeeded(value) => value.pure[F]
      case RefreshResult.Failed(error)    => error.raiseError[F, A]
      case RefreshResult.Retry()          => F.cede *> get(refresh)

object RuntimeDiagnosticsCache:
  def create[F[_]: Temporal, A](
      policy: RuntimeDiagnosticsCachePolicy,
  ): F[RuntimeDiagnosticsCache[F, A]] =
    Ref
      .of[F, State[F, A]](State.empty)
      .map(new RuntimeDiagnosticsCache[F, A](policy, _))

  private final case class Cached[A](
      value: A,
      expiresAt: FiniteDuration,
  )

  private final case class State[F[_], A](
      cached: Option[Cached[A]],
      inFlight: Option[Deferred[F, RefreshResult[A]]],
  )

  private object State:
    def empty[F[_], A]: State[F, A] =
      State(
        cached = None,
        inFlight = None,
      )

  private enum Decision[F[_], A]:
    case Hit(value: A)
    case Wait(signal: Deferred[F, RefreshResult[A]])
    case Start(signal: Deferred[F, RefreshResult[A]])

  private enum RefreshResult[A]:
    case Succeeded(value: A)
    case Failed(error: Throwable)
    case Retry()
