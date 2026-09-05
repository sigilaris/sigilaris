package org.sigilaris.node.jvm.runtime.diagnostics

import scala.concurrent.duration.*

import cats.effect.kernel.{Ref, Temporal}
import cats.syntax.all.*

final case class DiagnosticsWarningDedupPolicy private (
    maxKeys: Int,
    overflowInterval: FiniteDuration,
)

object DiagnosticsWarningDedupPolicy:
  val DefaultMaxKeys: Int                     = 1024
  val DefaultOverflowInterval: FiniteDuration = 1.minute

  val default: DiagnosticsWarningDedupPolicy =
    DiagnosticsWarningDedupPolicy(
      maxKeys = DefaultMaxKeys,
      overflowInterval = DefaultOverflowInterval,
    )

  def from(
      maxKeys: Int,
      overflowInterval: FiniteDuration,
  ): Either[String, DiagnosticsWarningDedupPolicy] =
    if maxKeys <= 0 then
      Left[String, DiagnosticsWarningDedupPolicy](
        "Diagnostics warning dedup max key count must be positive: " +
          maxKeys.toString,
      )
    else if overflowInterval < Duration.Zero then
      Left[String, DiagnosticsWarningDedupPolicy](
        "Diagnostics warning dedup overflow interval must be non-negative: " +
          overflowInterval.toString,
      )
    else
      Right[String, DiagnosticsWarningDedupPolicy](
        DiagnosticsWarningDedupPolicy(
          maxKeys = maxKeys,
          overflowInterval = overflowInterval,
        ),
      )

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def unsafe(
      maxKeys: Int,
      overflowInterval: FiniteDuration,
  ): DiagnosticsWarningDedupPolicy =
    from(maxKeys, overflowInterval).fold(
      message => throw new IllegalArgumentException(message),
      identity,
    )

final case class DiagnosticsWarningDedupOverflow[K](
    keyCap: Int,
    evictedKey: K,
    incomingKey: K,
    suppressedEvictionsSinceLastEmission: Long,
)

final class DiagnosticsWarningDedup[F[_], W, K] private (
    policy: DiagnosticsWarningDedupPolicy,
    keyOf: W => K,
    overflowWarning: DiagnosticsWarningDedupOverflow[K] => Option[W],
    state: Ref[F, DiagnosticsWarningDedup.State[K]],
)(using F: Temporal[F]):

  import DiagnosticsWarningDedup.*

  def observe(warning: W): F[Vector[W]] =
    for
      now     <- F.monotonic
      emitted <- state.modify(process(warning, now))
    yield emitted

  def observeAll(warnings: IterableOnce[W]): F[Vector[W]] =
    val batch = warnings.iterator.toVector
    for
      now     <- F.monotonic
      emitted <- state.modify(processAll(batch, now))
    yield emitted

  private def processAll(
      warnings: Vector[W],
      now: FiniteDuration,
  )(current: State[K]): (State[K], Vector[W]) =
    warnings.foldLeft(current -> Vector.empty[W]):
      case ((nextState, emitted), warning) =>
        val (updated, nextEmitted) = process(warning, now)(nextState)
        updated -> (emitted ++ nextEmitted)

  private def process(
      warning: W,
      now: FiniteDuration,
  )(current: State[K]): (State[K], Vector[W]) =
    val (timedCurrent, observedAt) = advanceObservedAt(current, now)
    val key                        = keyOf(warning)
    if timedCurrent.keySet.contains(key) then
      refreshKey(timedCurrent, key) -> Vector.empty[W]
    else if timedCurrent.recency.sizeIs < policy.maxKeys then
      addKey(timedCurrent, key) -> Vector(warning)
    else
      timedCurrent.recency.headOption match
        case Some(evictedKey) =>
          val evicted = timedCurrent.copy(
            keySet = timedCurrent.keySet - evictedKey,
            recency = timedCurrent.recency.drop(1),
          )
          val added               = addKey(evicted, key)
          val (updated, overflow) =
            maybeOverflow(added, evictedKey, key, observedAt)

          updated -> (Vector(warning) ++ overflow.fold(Vector.empty[W])(
            Vector(_),
          ))
        case None =>
          addKey(timedCurrent, key) -> Vector(warning)

  private def advanceObservedAt(
      current: State[K],
      now: FiniteDuration,
  ): (State[K], FiniteDuration) =
    val observedAt = current.lastObservedAt match
      case Some(previous) if now < previous => previous
      case _                                => now

    current.copy(lastObservedAt = Some(observedAt)) -> observedAt

  private def refreshKey(current: State[K], key: K): State[K] =
    val keyToRefresh = Set(key)
    current.copy(
      recency = current.recency.filterNot(keyToRefresh.contains) :+ key,
    )

  private def addKey(current: State[K], key: K): State[K] =
    current.copy(
      keySet = current.keySet + key,
      recency = current.recency :+ key,
    )

  private def maybeOverflow(
      current: State[K],
      evictedKey: K,
      incomingKey: K,
      now: FiniteDuration,
  ): (State[K], Option[W]) =
    if overflowReady(current, now) then
      val event = DiagnosticsWarningDedupOverflow(
        keyCap = policy.maxKeys,
        evictedKey = evictedKey,
        incomingKey = incomingKey,
        suppressedEvictionsSinceLastEmission =
          current.suppressedOverflowEvictions,
      )
      current.copy(
        lastOverflowAt = Some(now),
        suppressedOverflowEvictions = 0L,
      ) -> overflowWarning(event)
    else
      current.copy(
        suppressedOverflowEvictions =
          increment(current.suppressedOverflowEvictions),
      ) -> None

  private def overflowReady(current: State[K], now: FiniteDuration): Boolean =
    current.lastOverflowAt match
      case None                 => true
      case Some(lastOverflowAt) =>
        now - lastOverflowAt >= policy.overflowInterval

  private def increment(value: Long): Long =
    if value == Long.MaxValue then Long.MaxValue else value + 1L

object DiagnosticsWarningDedup:
  def create[F[_]: Temporal, W, K](
      policy: DiagnosticsWarningDedupPolicy,
      keyOf: W => K,
      overflowWarning: DiagnosticsWarningDedupOverflow[K] => Option[W],
  ): F[DiagnosticsWarningDedup[F, W, K]] =
    Ref
      .of[F, State[K]](State.empty)
      .map(
        new DiagnosticsWarningDedup[F, W, K](
          policy,
          keyOf,
          overflowWarning,
          _,
        ),
      )

  private final case class State[K](
      keySet: Set[K],
      recency: Vector[K],
      lastObservedAt: Option[FiniteDuration],
      lastOverflowAt: Option[FiniteDuration],
      suppressedOverflowEvictions: Long,
  )

  private object State:
    def empty[K]: State[K] =
      State(
        keySet = Set.empty,
        recency = Vector.empty[K],
        lastObservedAt = None,
        lastOverflowAt = None,
        suppressedOverflowEvictions = 0L,
      )
