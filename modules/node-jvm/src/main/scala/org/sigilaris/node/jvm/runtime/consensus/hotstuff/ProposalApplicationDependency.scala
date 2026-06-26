package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import cats.effect.kernel.Sync
import cats.syntax.all.*

import org.sigilaris.node.gossip.{
  CanonicalRejection,
  ExactKnownSetScope,
  GossipTopic,
  StableArtifactId,
}
import org.sigilaris.node.gossip.tx.{
  GossipArtifactKnownStateClassifier,
  GossipArtifactKnownStatePolicy,
}

/** Relationship between a proposal tx id and an application artifact. */
enum HotStuffProposalDependencyRelation:
  case DirectProposalTx
  case BarrierAncestor
  case MaterializationOnly

/** Whether a dependency is required before a validator can vote. */
enum HotStuffProposalDependencyCriticality:
  case RequiredForVote
  case HelpfulForMaterialization

/** How a receiver may recover a missing dependency. */
enum HotStuffProposalDependencyRecoveryMode:
  case RequestByIdBackfillable
  case ProactiveFanoutOnly

/** Application artifact dependency for a HotStuff proposal tx id. */
final case class HotStuffProposalApplicationDependency(
    proposalTxId: StableArtifactId,
    topic: GossipTopic,
    artifactId: StableArtifactId,
    exactScope: ExactKnownSetScope,
    estimatedBytes: Option[Long],
    relation: HotStuffProposalDependencyRelation,
    criticality: HotStuffProposalDependencyCriticality,
    recoveryMode: HotStuffProposalDependencyRecoveryMode,
):
  require(
    topic === exactScope.topic,
    "dependency topic must match exact-known scope topic",
  )

/** Result of filtering dependencies for the generic receiver backfill path. */
final case class HotStuffProposalDependencyBackfillFilter(
    requestByIdBackfillable: Vector[HotStuffProposalApplicationDependency],
    proactiveFanoutOnly: Vector[HotStuffProposalApplicationDependency],
    requiredProactiveFanoutOnly: Vector[
      HotStuffProposalApplicationDependency,
    ],
)

/** Companion helpers for proposal application dependencies. */
object HotStuffProposalApplicationDependency:
  def filterForGenericReceiverBackfill(
      dependencies: Vector[HotStuffProposalApplicationDependency],
  ): HotStuffProposalDependencyBackfillFilter =
    dependencies.foldLeft(
      HotStuffProposalDependencyBackfillFilter(
        requestByIdBackfillable =
          Vector.empty[HotStuffProposalApplicationDependency],
        proactiveFanoutOnly =
          Vector.empty[HotStuffProposalApplicationDependency],
        requiredProactiveFanoutOnly =
          Vector.empty[HotStuffProposalApplicationDependency],
      ),
    ): (acc, dependency) =>
      dependency.recoveryMode match
        case HotStuffProposalDependencyRecoveryMode.RequestByIdBackfillable =>
          acc.copy(
            requestByIdBackfillable =
              acc.requestByIdBackfillable :+ dependency,
          )
        case HotStuffProposalDependencyRecoveryMode.ProactiveFanoutOnly =>
          val required = dependency.criticality match
            case HotStuffProposalDependencyCriticality.RequiredForVote => true
            case HotStuffProposalDependencyCriticality.HelpfulForMaterialization =>
              false
          acc.copy(
            proactiveFanoutOnly = acc.proactiveFanoutOnly :+ dependency,
            requiredProactiveFanoutOnly =
              if required then acc.requiredProactiveFanoutOnly :+ dependency
              else acc.requiredProactiveFanoutOnly,
          )

/** Resolver supplied by embedders to map proposal tx ids to application
  * artifacts.
  */
trait HotStuffProposalApplicationDependencyResolver[F[_]]:
  def dependenciesForProposal(
      proposal: Proposal,
  ): F[Either[
    CanonicalRejection.ArtifactContractRejected,
    Vector[HotStuffProposalApplicationDependency],
  ]]

/** Default empty resolver implementation. */
object HotStuffProposalApplicationDependencyResolver:
  def empty[F[_]: Sync]: HotStuffProposalApplicationDependencyResolver[F] =
    _ =>
      Vector.empty[HotStuffProposalApplicationDependency]
        .asRight[CanonicalRejection.ArtifactContractRejected]
        .pure[F]

/** Runtime wiring for proposal application dependency resolution. */
final case class HotStuffProposalApplicationDependencyRuntimeConfig[F[_]](
    resolver: Option[HotStuffProposalApplicationDependencyResolver[F]],
    sidecarPolicy: HotStuffProposalSidecarPolicy,
    knownStatePolicy: GossipArtifactKnownStatePolicy,
)

/** Which runtime path is resolving dependencies. */
enum HotStuffProposalApplicationDependencyResolutionPath:
  case Producer
  case Receiver

/** Non-throwing dependency resolution outcome. */
enum HotStuffProposalApplicationDependencyResolution:
  case Resolved(
      dependencies: Vector[HotStuffProposalApplicationDependency],
  )
  case ResolverRejected(
      path: HotStuffProposalApplicationDependencyResolutionPath,
      reason: String,
      detail: Option[String],
  )
  case ResolverFailed(
      path: HotStuffProposalApplicationDependencyResolutionPath,
      detail: Option[String],
  )

  def resolvedDependencies: Option[
    Vector[HotStuffProposalApplicationDependency],
  ] =
    this match
      case HotStuffProposalApplicationDependencyResolution.Resolved(
            dependencies,
          ) =>
        Some(dependencies)
      case _: HotStuffProposalApplicationDependencyResolution.ResolverRejected =>
        None
      case _: HotStuffProposalApplicationDependencyResolution.ResolverFailed =>
        None

/** Companion for proposal dependency runtime wiring. */
object HotStuffProposalApplicationDependencyRuntimeConfig:
  def legacyCompatible[F[_]]
      : HotStuffProposalApplicationDependencyRuntimeConfig[F] =
    HotStuffProposalApplicationDependencyRuntimeConfig(
      resolver = None,
      sidecarPolicy = HotStuffProposalSidecarPolicy.disabled,
      knownStatePolicy = GossipArtifactKnownStateClassifier.defaultPolicy,
    )

  def withResolver[F[_]](
      resolver: HotStuffProposalApplicationDependencyResolver[F],
  ): HotStuffProposalApplicationDependencyRuntimeConfig[F] =
    HotStuffProposalApplicationDependencyRuntimeConfig(
      resolver = Some(resolver),
      sidecarPolicy = HotStuffProposalSidecarPolicy.disabled,
      knownStatePolicy = GossipArtifactKnownStateClassifier.defaultPolicy,
    )

  def withResolverAndSidecars[F[_]](
      resolver: HotStuffProposalApplicationDependencyResolver[F],
      sidecarPolicy: HotStuffProposalSidecarPolicy,
      knownStatePolicy: GossipArtifactKnownStatePolicy,
  ): HotStuffProposalApplicationDependencyRuntimeConfig[F] =
    HotStuffProposalApplicationDependencyRuntimeConfig(
      resolver = Some(resolver),
      sidecarPolicy = sidecarPolicy,
      knownStatePolicy = knownStatePolicy,
    )

  def resolveForProducer[F[_]: Sync](
      config: HotStuffProposalApplicationDependencyRuntimeConfig[F],
      proposal: Proposal,
  ): F[HotStuffProposalApplicationDependencyResolution] =
    resolve(
      config,
      proposal,
      HotStuffProposalApplicationDependencyResolutionPath.Producer,
    )

  def resolveForReceiver[F[_]: Sync](
      config: HotStuffProposalApplicationDependencyRuntimeConfig[F],
      proposal: Proposal,
  ): F[HotStuffProposalApplicationDependencyResolution] =
    resolve(
      config,
      proposal,
      HotStuffProposalApplicationDependencyResolutionPath.Receiver,
    )

  private def resolve[F[_]: Sync](
      config: HotStuffProposalApplicationDependencyRuntimeConfig[F],
      proposal: Proposal,
      path: HotStuffProposalApplicationDependencyResolutionPath,
  ): F[HotStuffProposalApplicationDependencyResolution] =
    config.resolver match
      case None =>
        HotStuffProposalApplicationDependencyResolution
          .Resolved(Vector.empty)
          .pure[F]
      case Some(resolver) =>
        resolver
          .dependenciesForProposal(proposal)
          .attempt
          .map:
            case Right(Right(dependencies)) =>
              HotStuffProposalApplicationDependencyResolution.Resolved(
                dependencies,
              )
            case Right(Left(rejection)) =>
              HotStuffProposalApplicationDependencyResolution.ResolverRejected(
                path = path,
                reason = rejection.reason,
                detail = rejection.detail,
              )
            case Left(error) =>
              HotStuffProposalApplicationDependencyResolution.ResolverFailed(
                path = path,
                detail = Some(error.getClass.getName),
              )
