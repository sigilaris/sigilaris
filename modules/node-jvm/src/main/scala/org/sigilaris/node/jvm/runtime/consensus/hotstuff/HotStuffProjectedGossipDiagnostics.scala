package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import cats.effect.kernel.Temporal
import cats.syntax.all.*

import org.sigilaris.node.jvm.runtime.diagnostics.{
  DiagnosticsWarningDedup,
  DiagnosticsWarningDedupOverflow,
  DiagnosticsWarningDedupPolicy,
  RuntimeDiagnosticsCache,
  RuntimeDiagnosticsCachePolicy,
}

final case class HotStuffProjectedGossipDiagnosticsPolicies(
    cachePolicy: RuntimeDiagnosticsCachePolicy,
    warningDedupPolicy: DiagnosticsWarningDedupPolicy,
    projectionPolicy: HotStuffGossipDiagnosticsProjectionPolicy,
)

object HotStuffProjectedGossipDiagnosticsPolicies:
  val default: HotStuffProjectedGossipDiagnosticsPolicies =
    HotStuffProjectedGossipDiagnosticsPolicies(
      cachePolicy = RuntimeDiagnosticsCachePolicy.default,
      warningDedupPolicy = DiagnosticsWarningDedupPolicy.default,
      projectionPolicy = HotStuffGossipDiagnosticsProjectionPolicy.default,
    )

final case class HotStuffGossipDiagnosticsWarningKey(
    component: String,
    reason: String,
    message: Option[String],
)

object HotStuffGossipDiagnosticsWarningKey:
  private val ReadFailureReason: String = "read-failure"
  private val SizeWarningReasons: Set[String] =
    Set("size-warning-entries", "size-warning-bytes")

  def fromWarning(
      warning: HotStuffGossipDiagnosticsProjectionWarning,
  ): HotStuffGossipDiagnosticsWarningKey =
    if warning.reason === ReadFailureReason ||
      SizeWarningReasons.contains(warning.reason)
    then
      HotStuffGossipDiagnosticsWarningKey(
        component = warning.component,
        reason = warning.reason,
        message = None,
      )
    else
      HotStuffGossipDiagnosticsWarningKey(
        component = warning.component,
        reason = warning.reason,
        message = warning.message,
      )

final case class HotStuffProjectedGossipDiagnosticsRead(
    result: HotStuffGossipDiagnosticsProjectionResult,
    warningsToEmit: Vector[HotStuffGossipDiagnosticsProjectionWarning],
)

final class HotStuffProjectedGossipDiagnostics[F[_]] private (
    runtime: HotStuffNodeRuntime[F],
    projectionPolicy: HotStuffGossipDiagnosticsProjectionPolicy,
    cache: RuntimeDiagnosticsCache[
      F,
      HotStuffGossipDiagnosticsProjectionResult,
    ],
    warningDedup: DiagnosticsWarningDedup[
      F,
      HotStuffGossipDiagnosticsProjectionWarning,
      HotStuffGossipDiagnosticsWarningKey,
    ],
)(using F: Temporal[F]):

  def current: F[HotStuffGossipDiagnosticsProjectionResult] =
    cache.get(runtime.currentProjectedGossipDiagnostics(projectionPolicy))

  def warningsToEmit(
      result: HotStuffGossipDiagnosticsProjectionResult,
  ): F[Vector[HotStuffGossipDiagnosticsProjectionWarning]] =
    warningDedup.observeAll(result.warnings)

  def currentWithWarningsToEmit: F[HotStuffProjectedGossipDiagnosticsRead] =
    for
      result   <- current
      warnings <- warningsToEmit(result)
    yield HotStuffProjectedGossipDiagnosticsRead(result, warnings)

object HotStuffProjectedGossipDiagnostics:
  def create[F[_]: Temporal](
      runtime: HotStuffNodeRuntime[F],
      policies: HotStuffProjectedGossipDiagnosticsPolicies,
      overflowWarning:
        DiagnosticsWarningDedupOverflow[HotStuffGossipDiagnosticsWarningKey] =>
          Option[HotStuffGossipDiagnosticsProjectionWarning],
  ): F[HotStuffProjectedGossipDiagnostics[F]] =
    for
      cache <- RuntimeDiagnosticsCache.create[
        F,
        HotStuffGossipDiagnosticsProjectionResult,
      ](policies.cachePolicy)
      warningDedup <- DiagnosticsWarningDedup.create[
        F,
        HotStuffGossipDiagnosticsProjectionWarning,
        HotStuffGossipDiagnosticsWarningKey,
      ](
        policy = policies.warningDedupPolicy,
        keyOf = HotStuffGossipDiagnosticsWarningKey.fromWarning,
        overflowWarning = overflowWarning,
      )
    yield new HotStuffProjectedGossipDiagnostics[F](
      runtime = runtime,
      projectionPolicy = policies.projectionPolicy,
      cache = cache,
      warningDedup = warningDedup,
    )

  def createDefault[F[_]: Temporal](
      runtime: HotStuffNodeRuntime[F],
      policies: HotStuffProjectedGossipDiagnosticsPolicies,
  ): F[HotStuffProjectedGossipDiagnostics[F]] =
    create(
      runtime = runtime,
      policies = policies,
      overflowWarning = defaultOverflowWarning,
    )

  private def defaultOverflowWarning(
      overflow:
        DiagnosticsWarningDedupOverflow[HotStuffGossipDiagnosticsWarningKey],
  ): Option[HotStuffGossipDiagnosticsProjectionWarning] =
    Some(
      HotStuffGossipDiagnosticsProjectionWarning(
        component = "diagnostics",
        reason = "warning-dedup-overflow",
        message = Some(
          "keyCap=" + overflow.keyCap.toString +
            " evicted=" + renderKey(overflow.evictedKey) +
            " incoming=" + renderKey(overflow.incomingKey) +
            " suppressedEvictionsSinceLastEmission=" +
            overflow.suppressedEvictionsSinceLastEmission.toString,
        ),
      ),
    )

  private def renderKey(
      key: HotStuffGossipDiagnosticsWarningKey,
  ): String =
    key.component + ":" + key.reason + ":" + key.message.getOrElse("")
