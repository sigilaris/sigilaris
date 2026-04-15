package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Instant

import cats.effect.kernel.{Async, Deferred, Ref, Sync}
import cats.syntax.all.*

import org.sigilaris.node.gossip.ChainId

/** Manages the full lifecycle of HotStuff bootstrap, including stores, coordinator creation, and shutdown. */
trait HotStuffBootstrapLifecycle[F[_]] extends BootstrapDiagnosticsSource[F]:
  /** The snapshot metadata store used during bootstrap. */
  def metadataStore: SnapshotMetadataStore[F]

  /** The snapshot node store used during bootstrap. */
  def nodeStore: SnapshotNodeStore[F]

  /** The forward catch-up materialization store. */
  def forwardStore: ForwardCatchUpStore[F]

  /** The historical proposal archive for backfill. */
  def historicalArchive: HistoricalProposalArchive[F]

  /** Releases resources held by the lifecycle. */
  def close: F[Unit]

  /** Runs the bootstrap process for a given chain. */
  def bootstrap(
      chainId: ChainId,
      sessions: Vector[BootstrapSessionBinding],
      startedAt: Instant,
      liveProposals: Vector[Proposal],
  )(using
      Async[F],
  ): F[Either[BootstrapCoordinatorFailure, BootstrapCoordinatorResult]]

  /** Returns the current vote readiness for the given chain. */
  def voteReadiness(
      chainId: ChainId,
  ): F[BootstrapVoteReadiness]

/** Companion for `HotStuffBootstrapLifecycle`, providing in-memory construction. */
object HotStuffBootstrapLifecycle:
  enum CoordinatorSlot[F[_]]:
    case Building(
        promise: Deferred[F, Either[Throwable, BootstrapCoordinator[F]]],
    )
    case Ready(coordinator: BootstrapCoordinator[F])

  enum CoordinatorLookup[F[_]]:
    case Use(coordinator: BootstrapCoordinator[F])
    case Await(
        promise: Deferred[F, Either[Throwable, BootstrapCoordinator[F]]],
    )
    case Build(
        promise: Deferred[F, Either[Throwable, BootstrapCoordinator[F]]],
    )

  /** Creates an in-memory bootstrap lifecycle with all required stores and services. */
  def inMemory[F[_]: Sync](
      metadataStore: SnapshotMetadataStore[F],
      nodeStore: SnapshotNodeStore[F],
      validatorSetLookup: ValidatorSetLookup[F],
      finalizedAnchorSuggestions: FinalizedAnchorSuggestionService[F],
      snapshotNodeFetch: SnapshotNodeFetchService[F],
      proposalReplay: ProposalReplayService[F],
      historicalBackfill: HistoricalBackfillService[F],
      readiness: ProposalCatchUpReadiness[F],
      forwardStore: ForwardCatchUpStore[F],
      historicalArchive: HistoricalProposalArchive[F],
      retryPolicy: BootstrapRetryPolicy,
      historicalBackfillPolicy: HistoricalBackfillPolicy,
      beforeCoordinatorBuild: Option[ChainId => F[Unit]],
      currentInstant: F[Instant],
  ): F[HotStuffBootstrapLifecycle[F]] =
    Ref
      .of[F, Map[ChainId, CoordinatorSlot[F]]](Map.empty)
      .map: stateRef =>
        val buildHook =
          beforeCoordinatorBuild.getOrElse(_ => Sync[F].unit)
        new InMemoryHotStuffBootstrapLifecycle[F](
          metadataStore = metadataStore,
          nodeStore = nodeStore,
          validatorSetLookup = validatorSetLookup,
          finalizedAnchorSuggestions = finalizedAnchorSuggestions,
          snapshotNodeFetch = snapshotNodeFetch,
          proposalReplay = proposalReplay,
          historicalBackfill = historicalBackfill,
          readiness = readiness,
          forwardStore = forwardStore,
          historicalArchive = historicalArchive,
          retryPolicy = retryPolicy,
          historicalBackfillPolicy = historicalBackfillPolicy,
          beforeCoordinatorBuild = buildHook,
          currentInstant = currentInstant,
          ref = stateRef,
        )

private final class InMemoryHotStuffBootstrapLifecycle[F[_]: Sync](
    override val metadataStore: SnapshotMetadataStore[F],
    override val nodeStore: SnapshotNodeStore[F],
    override val forwardStore: ForwardCatchUpStore[F],
    override val historicalArchive: HistoricalProposalArchive[F],
    validatorSetLookup: ValidatorSetLookup[F],
    finalizedAnchorSuggestions: FinalizedAnchorSuggestionService[F],
    snapshotNodeFetch: SnapshotNodeFetchService[F],
    proposalReplay: ProposalReplayService[F],
    historicalBackfill: HistoricalBackfillService[F],
    readiness: ProposalCatchUpReadiness[F],
    retryPolicy: BootstrapRetryPolicy,
    historicalBackfillPolicy: HistoricalBackfillPolicy,
    beforeCoordinatorBuild: ChainId => F[Unit],
    currentInstant: F[Instant],
    ref: Ref[F, Map[ChainId, HotStuffBootstrapLifecycle.CoordinatorSlot[F]]],
) extends HotStuffBootstrapLifecycle[F]:

  override def close: F[Unit] =
    historicalArchive.close

  override def current: F[BootstrapDiagnostics] =
    coordinators.flatMap: currentCoordinators =>
      currentCoordinators
        .traverse: (_, coordinator) =>
          coordinator.current
        .map(_.zip(currentCoordinators.map(_._1)))
        .map(_.map((diagnostics, chainId) => chainId -> diagnostics))
        .map(mergeDiagnostics)

  override def bootstrap(
      chainId: ChainId,
      sessions: Vector[BootstrapSessionBinding],
      startedAt: Instant,
      liveProposals: Vector[Proposal],
  )(using
      Async[F],
  ): F[Either[BootstrapCoordinatorFailure, BootstrapCoordinatorResult]] =
    coordinatorFor(chainId)
      .flatMap(_.bootstrap(chainId, sessions, startedAt, liveProposals))

  override def voteReadiness(
      chainId: ChainId,
  ): F[BootstrapVoteReadiness] =
    ref.get.flatMap:
      _.get(chainId) match
        case Some(
              HotStuffBootstrapLifecycle.CoordinatorSlot.Ready(coordinator),
            ) =>
          coordinator.current.map:
            _.chains
              .get(chainId)
              .map(_.voteReadiness)
              .getOrElse(BootstrapVoteReadiness.Ready)
        case Some(HotStuffBootstrapLifecycle.CoordinatorSlot.Building(_)) =>
          BootstrapVoteReadiness.Held("bootstrapPending").pure[F]
        case _ =>
          BootstrapVoteReadiness.Ready.pure[F]

  private def coordinators: F[Vector[(ChainId, BootstrapCoordinator[F])]] =
    ref.get.map:
      _.iterator
        .collect {
          case (
                chainId,
                HotStuffBootstrapLifecycle.CoordinatorSlot.Ready(coordinator),
              ) =>
            chainId -> coordinator
        }
        .toVector
        .sortBy(_._1.value)

  private def coordinatorFor(
      chainId: ChainId,
  )(using Async[F]): F[BootstrapCoordinator[F]] =
    Deferred[F, Either[Throwable, BootstrapCoordinator[F]]].flatMap: promise =>
      ref
        .modify: state =>
          state.get(chainId) match
            case Some(
                  HotStuffBootstrapLifecycle.CoordinatorSlot.Ready(coordinator),
                ) =>
              state -> HotStuffBootstrapLifecycle.CoordinatorLookup.Use(
                coordinator,
              )
            case Some(
                  HotStuffBootstrapLifecycle.CoordinatorSlot.Building(existing),
                ) =>
              state -> HotStuffBootstrapLifecycle.CoordinatorLookup.Await(
                existing,
              )
            case None =>
              state.updated(
                chainId,
                HotStuffBootstrapLifecycle.CoordinatorSlot.Building(promise),
              ) -> HotStuffBootstrapLifecycle.CoordinatorLookup.Build(
                promise,
              )
        .flatMap:
          case HotStuffBootstrapLifecycle.CoordinatorLookup.Use(coordinator) =>
            coordinator.pure[F]
          case HotStuffBootstrapLifecycle.CoordinatorLookup.Await(existing) =>
            existing.get.flatMap(Async[F].fromEither)
          case HotStuffBootstrapLifecycle.CoordinatorLookup.Build(current) =>
            beforeCoordinatorBuild(chainId)
              .flatMap(_ => buildCoordinator(chainId))
              .attempt
              .flatMap:
                case Right(coordinator) =>
                  ref.update(
                    _.updated(
                      chainId,
                      HotStuffBootstrapLifecycle.CoordinatorSlot.Ready(
                        coordinator,
                      ),
                    ),
                  ) *>
                    current
                      .complete(coordinator.asRight[Throwable])
                      .void
                      .as(
                        coordinator,
                      )
                case Left(error) =>
                  current
                    .complete(error.asLeft[BootstrapCoordinator[F]])
                    .void *>
                    ref.update(_ - chainId) *>
                    Async[F].raiseError[BootstrapCoordinator[F]](error)

  private def buildCoordinator(
      chainId: ChainId,
  )(using Async[F]): F[BootstrapCoordinator[F]] =
    for
      historicalBackfillWorker <- HistoricalBackfillWorker.createWithNow[F](
        policy = historicalBackfillPolicy,
        historicalBackfill = historicalBackfill,
        archive = historicalArchive,
        now = currentInstant,
      )
      coordinator <- BootstrapCoordinator.createWithBackfill[F](
        retryPolicy = retryPolicy,
        validatorSetLookup = validatorSetLookup,
        finalizedAnchorSuggestions = finalizedAnchorSuggestions,
        snapshotCoordinator = SnapshotCoordinator.createWithNow(
          chainId = chainId,
          metadataStore = metadataStore,
          nodeStore = nodeStore,
          fetchService = snapshotNodeFetch,
          currentInstant = currentInstant,
        ),
        proposalReplay = proposalReplay,
        readiness = readiness,
        forwardStore = forwardStore,
        historicalBackfill = historicalBackfillWorker,
      )
    yield coordinator

  private def mergeDiagnostics(
      diagnosticsByChain: Vector[(ChainId, BootstrapDiagnostics)],
  ): BootstrapDiagnostics =
    if diagnosticsByChain.isEmpty then BootstrapDiagnostics.empty
    else
      val diagnostics = diagnosticsByChain.map(_._2)
      BootstrapDiagnostics(
        phase = diagnostics.iterator
          .map(_.phase)
          .foldLeft(BootstrapPhase.Ready): (current, next) =>
            if phaseRank(next) < phaseRank(current) then next else current,
        chains =
          diagnostics.foldLeft(Map.empty[ChainId, BootstrapChainDiagnostics]):
            (acc, next) => acc ++ next.chains
        ,
        retryAttempts =
          diagnostics.iterator.map(_.retryAttempts).maxOption.getOrElse(0),
        nextRetryAt = diagnostics.iterator
          .flatMap(_.nextRetryAt)
          .minOption,
        lastFailure =
          diagnosticsByChain.iterator.flatMap { case (chainId, diagnostic) =>
            diagnostic.lastFailure match
              case Some(failure: String) =>
                val rendered: String = chainId.value + ":" + failure
                List[String](rendered)
              case None =>
                List.empty[String]
          }.toVector match
            case Vector()     => None
            case Vector(one)  => Some(one)
            case manyFailures => Some(manyFailures.mkString("; "))
        ,
        historicalBackfill = diagnostics.iterator
          .map(_.historicalBackfill)
          .find:
            case HistoricalBackfillStatus.Idle => false
            case _                             => true
          .getOrElse(HistoricalBackfillStatus.Idle),
      )

  private def phaseRank(
      phase: BootstrapPhase,
  ): Int =
    phase match
      case BootstrapPhase.Discovery      => 0
      case BootstrapPhase.SnapshotSync   => 1
      case BootstrapPhase.ForwardCatchUp => 2
      case BootstrapPhase.Ready          => 3
