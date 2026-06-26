package org.sigilaris.node.gossip.tx

import java.time.Instant

import cats.Applicative
import cats.syntax.all.*

import org.sigilaris.node.gossip.*

/** Structured reason recorded by a proposal sidecar planner. */
enum GossipSidecarDiagnosticReason:
  case SidecarSelected
  case SidecarHeld
  case SidecarFallback
  case SidecarCapExceeded
  case SidecarLocalArtifactUnavailable
  case ProposalDependencyResolverRejected
  case ProposalDependencyResolverFailed

/** Per-peer diagnostic for sidecar decisions and fallback paths. */
final case class GossipSidecarDiagnostic(
    reason: GossipSidecarDiagnosticReason,
    chainTopic: ChainTopic,
    proposalId: StableArtifactId,
    detail: Option[String],
)

/** Bounded hold state for a proposal whose required sidecars are pending. */
final case class GossipSidecarHoldState(
    proposalId: StableArtifactId,
    firstHeldAt: Instant,
    lastHeldAt: Instant,
    attempts: Int,
    reason: GossipSidecarDiagnosticReason,
)

/** Producer decision for the next live event on a gated topic. */
sealed trait GossipSidecarDecision[A]

/** Companion constructors for sidecar planner decisions. */
object GossipSidecarDecision:
  final case class PassThrough[A]() extends GossipSidecarDecision[A]
  final case class Emit[A](
      sidecars: Vector[GossipEvent[A]],
      proposal: GossipEvent[A],
      diagnostics: Vector[GossipSidecarDiagnostic],
  ) extends GossipSidecarDecision[A]
  final case class Hold[A](
      proposalId: StableArtifactId,
      reason: GossipSidecarDiagnosticReason,
      diagnostics: Vector[GossipSidecarDiagnostic],
  ) extends GossipSidecarDecision[A]
  final case class Fallback[A](
      proposal: GossipEvent[A],
      diagnostics: Vector[GossipSidecarDiagnostic],
  ) extends GossipSidecarDecision[A]

/** Optional planner that can prepend explicit sidecar events before a live
  * proposal event and temporarily gate the proposal topic cursor.
  */
trait GossipSidecarPlanner[F[_], A]:
  def plan(
      now: Instant,
      sessionState: TxProducerSessionState,
      chainTopic: ChainTopic,
      candidates: Vector[AvailableGossipEvent[A]],
      remainingCapacity: Int,
      existingHold: Option[GossipSidecarHoldState],
  ): F[GossipSidecarDecision[A]]

/** Companion for disabled/default sidecar planning. */
object GossipSidecarPlanner:
  def disabled[F[_]: Applicative, A]: GossipSidecarPlanner[F, A] =
    new GossipSidecarPlanner[F, A]:
      override def plan(
          @annotation.unused now: Instant,
          @annotation.unused sessionState: TxProducerSessionState,
          @annotation.unused chainTopic: ChainTopic,
          @annotation.unused candidates: Vector[AvailableGossipEvent[A]],
          @annotation.unused remainingCapacity: Int,
          @annotation.unused existingHold: Option[GossipSidecarHoldState],
      ): F[GossipSidecarDecision[A]] =
        GossipSidecarDecision.PassThrough[A]().pure[F]
