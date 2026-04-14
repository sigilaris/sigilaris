package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.{Duration, Instant}

import scala.concurrent.duration.DurationLong

import cats.Applicative
import cats.effect.kernel.{Async, Clock}
import cats.effect.std.Semaphore
import cats.effect.Ref
import cats.syntax.all.*

import org.sigilaris.node.jvm.runtime.block.BlockHeight
import org.sigilaris.node.gossip.{CanonicalRejection, ChainId}

/** Configuration for historical backfill behavior.
  *
  * @param batchSize number of proposals to fetch per batch
  * @param interBatchDelay delay between consecutive batches
  * @param priority the backfill priority level
  * @param archiveSource the archive source label for stored proposals
  * @param enabled whether historical backfill is enabled
  */
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class HistoricalBackfillPolicy(
    batchSize: Int,
    interBatchDelay: Duration,
    priority: HistoricalBackfillPriority =
      HistoricalBackfillPriority.Background,
    archiveSource: HistoricalArchiveSource =
      HistoricalArchiveSource.BackgroundBackfill,
    enabled: Boolean = true,
):
  require(batchSize > 0, "batchSize must be positive")
  require(!interBatchDelay.isNegative, "interBatchDelay must be non-negative")

/** Companion for `HistoricalBackfillPolicy`. */
object HistoricalBackfillPolicy:
  /** Default policy for background backfill (small batches, 1-second delay). */
  val backgroundDefault: HistoricalBackfillPolicy =
    HistoricalBackfillPolicy(
      batchSize = 32,
      interBatchDelay = Duration.ofSeconds(1L),
    )

  /** Default policy for archive backfill (larger batches, minimal delay). */
  val archiveDefault: HistoricalBackfillPolicy =
    HistoricalBackfillPolicy(
      batchSize = 256,
      interBatchDelay = Duration.ofMillis(10L),
      priority = HistoricalBackfillPriority.Archive,
      archiveSource = HistoricalArchiveSource.ArchiveSync,
    )

  /** Returns the appropriate backfill policy for the given node role. */
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def forRole(
      role: LocalNodeRole,
      enabled: Boolean = true,
  ): HistoricalBackfillPolicy =
    val policy =
      role match
        case LocalNodeRole.Validator => backgroundDefault
        case LocalNodeRole.Audit     => archiveDefault
    policy.copy(enabled = enabled)

/** A worker that fetches historical proposals before a snapshot anchor, walking backward to genesis. */
trait HistoricalBackfillWorker[F[_]]:
  /** Starts backfill from the given anchor using the provided peer sessions. */
  def start(
      chainId: ChainId,
      sessions: Vector[BootstrapSessionBinding],
      anchor: SnapshotAnchor,
      now: Instant,
  ): F[Unit]

  /** Pauses the backfill for the given reason. */
  def pause(
      reason: String,
      now: Instant,
  ): F[Unit]

  /** Resumes a paused backfill. */
  def resume(
      now: Instant,
  ): F[Unit]

  /** Returns the current backfill status. */
  def current: F[HistoricalBackfillStatus]

/** Companion for `HistoricalBackfillWorker`. */
object HistoricalBackfillWorker:
  /** The reason string used when backfill is disabled by policy. */
  val DisabledByPolicyReason: String = "historicalSyncDisabled"

  /** Creates a no-op worker that never starts backfill. */
  def disabled[F[_]: Applicative]: HistoricalBackfillWorker[F] =
    disabledWithStatus(HistoricalBackfillStatus.Idle)

  /** Creates a no-op worker that always reports the given status. */
  def disabledWithStatus[F[_]: Applicative](
      status: HistoricalBackfillStatus,
  ): HistoricalBackfillWorker[F] =
    new HistoricalBackfillWorker[F]:
      override def start(
          chainId: ChainId,
          sessions: Vector[BootstrapSessionBinding],
          anchor: SnapshotAnchor,
          now: Instant,
      ): F[Unit] =
        Applicative[F].unit

      override def pause(
          reason: String,
          now: Instant,
      ): F[Unit] =
        Applicative[F].unit

      override def resume(
          now: Instant,
      ): F[Unit] =
        Applicative[F].unit

      override def current: F[HistoricalBackfillStatus] =
        status.pure[F]

  /** Creates a backfill worker that fetches and archives historical proposals. */
  def create[F[_]: Async: Clock](
      policy: HistoricalBackfillPolicy,
      historicalBackfill: HistoricalBackfillService[F],
      archive: HistoricalProposalArchive[F],
  ): F[HistoricalBackfillWorker[F]] =
    createWithNow(policy, historicalBackfill, archive, Clock[F].realTimeInstant)

  /** Creates a backfill worker with a custom clock source. */
  def createWithNow[F[_]: Async](
      policy: HistoricalBackfillPolicy,
      historicalBackfill: HistoricalBackfillService[F],
      archive: HistoricalProposalArchive[F],
      now: F[Instant],
  ): F[HistoricalBackfillWorker[F]] =
    if !policy.enabled then
      disabledWithStatus(
        HistoricalBackfillStatus.Disabled(DisabledByPolicyReason),
      ).pure[F]
    else
      Ref
        .of[F, HistoricalBackfillWorkerState](
          HistoricalBackfillWorkerState.empty,
        )
        .flatMap: stateRef =>
          Semaphore[F](1).map: writeLock =>
            new InMemoryHistoricalBackfillWorker[F](
              policy = policy,
              historicalBackfill = historicalBackfill,
              archive = archive,
              now = now,
              ref = stateRef,
              writeLock = writeLock,
            )

private final case class HistoricalBackfillRuntimeState(
    chainId: ChainId,
    sessions: Vector[BootstrapSessionBinding],
    progress: HistoricalBackfillProgress,
)

private final case class HistoricalBackfillWorkerState(
    status: HistoricalBackfillStatus,
    runtime: Option[HistoricalBackfillRuntimeState],
    generation: Long,
)

private object HistoricalBackfillWorkerState:
  val empty: HistoricalBackfillWorkerState =
    HistoricalBackfillWorkerState(
      status = HistoricalBackfillStatus.Idle,
      runtime = None,
      generation = 0L,
    )

private final class InMemoryHistoricalBackfillWorker[F[_]: Async](
    policy: HistoricalBackfillPolicy,
    historicalBackfill: HistoricalBackfillService[F],
    archive: HistoricalProposalArchive[F],
    now: F[Instant],
    ref: Ref[F, HistoricalBackfillWorkerState],
    writeLock: Semaphore[F],
) extends HistoricalBackfillWorker[F]:

  override def start(
      chainId: ChainId,
      sessions: Vector[BootstrapSessionBinding],
      anchor: SnapshotAnchor,
      startedAt: Instant,
  ): F[Unit] =
    val initialProgress =
      HistoricalBackfillProgress(
        anchor = anchor,
        nextBeforeBlockId = anchor.blockId,
        nextBeforeHeight = anchor.height,
        fetchedProposalCount = 0L,
        lastUpdatedAt = startedAt,
      )
    if anchor.height === BlockHeight.Genesis then
      ref.set(
        HistoricalBackfillWorkerState(
          status = HistoricalBackfillStatus.Completed(
            reason = "genesisReached",
            progress = initialProgress,
          ),
          runtime = None,
          generation = 0L,
        ),
      )
    else if sessions.isEmpty then
      ref.set(
        HistoricalBackfillWorkerState(
          status = HistoricalBackfillStatus.Failed(
            reason = "historicalBackfillNoPeersAvailable",
            detail = None,
            progress = initialProgress,
          ),
          runtime = None,
          generation = 0L,
        ),
      )
    else
      ref
        .modify: state =>
          state.status match
            case HistoricalBackfillStatus.Idle =>
              val nextGeneration = state.generation + 1L
              HistoricalBackfillWorkerState(
                status = HistoricalBackfillStatus.Running(
                  progress = initialProgress,
                  priority = policy.priority,
                ),
                runtime = Some(
                  HistoricalBackfillRuntimeState(
                    chainId = chainId,
                    sessions = sessions,
                    progress = initialProgress,
                  ),
                ),
                generation = nextGeneration,
              ) -> nextGeneration.some
            case _ =>
              state -> none[Long]
        .flatMap:
          case Some(generation) =>
            Async[F].start(run(generation)).void
          case None =>
            Applicative[F].unit

  override def pause(
      reason: String,
      pausedAt: Instant,
  ): F[Unit] =
    writeLock.permit.use: _ =>
      ref.update: state =>
        state.status match
          case HistoricalBackfillStatus.Running(progress, priority) =>
            state.copy(
              status = HistoricalBackfillStatus.Paused(
                reason = reason,
                progress = progress.copy(lastUpdatedAt = pausedAt),
                priority = priority,
              ),
              generation = state.generation + 1L,
            )
          case _ =>
            state

  override def resume(
      resumedAt: Instant,
  ): F[Unit] =
    writeLock.permit.use: _ =>
      ref
        .modify: state =>
          state.status match
            case HistoricalBackfillStatus.Paused(_, progress, priority) =>
              val nextGeneration = state.generation + 1L
              state.copy(
                status = HistoricalBackfillStatus.Running(
                  progress = progress.copy(lastUpdatedAt = resumedAt),
                  priority = priority,
                ),
                runtime = state.runtime.map(
                  _.copy(progress = progress.copy(lastUpdatedAt = resumedAt)),
                ),
                generation = nextGeneration,
              ) -> nextGeneration.some
            case _ =>
              state -> none[Long]
        .flatMap:
          case Some(generation) =>
            Async[F].start(run(generation)).void
          case None =>
            Applicative[F].unit

  override def current: F[HistoricalBackfillStatus] =
    ref.get.map(_.status)

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def run(
      generation: Long,
  ): F[Unit] =
    ref.get.flatMap: state =>
      state.runtime match
        case Some(runtime) if state.generation === generation =>
          state.status match
            case HistoricalBackfillStatus.Running(_, _) =>
              fetchBatch(runtime).flatMap:
                case Left(rejection) =>
                  now.flatMap: currentTime =>
                    ref.update: latest =>
                      if latest.generation === generation then
                        latest.copy(
                          status = HistoricalBackfillStatus.Failed(
                            reason = rejection.reason,
                            detail = rejection.detail,
                            progress =
                              runtime.progress.copy(lastUpdatedAt = currentTime),
                          ),
                          runtime = None,
                        )
                      else latest
                case Right(batch) =>
                  now.flatMap: currentTime =>
                    applyBatch(generation, runtime, batch, currentTime).flatMap:
                      case true =>
                        Async[F]
                          .sleep(policy.interBatchDelay.toMillis.millis) *>
                          run(generation)
                      case false =>
                        Applicative[F].unit
            case _ =>
              Applicative[F].unit
        case _ =>
          Applicative[F].unit

  private def fetchBatch(
      runtime: HistoricalBackfillRuntimeState,
  ): F[Either[CanonicalRejection, Vector[Proposal]]] =
    runtime.sessions
      .traverse: session =>
        historicalBackfill.readPrevious(
          session = session,
          chainId = runtime.chainId,
          beforeBlockId = runtime.progress.nextBeforeBlockId,
          beforeHeight = runtime.progress.nextBeforeHeight,
          limit = policy.batchSize,
        )
      .map: responses =>
        val successful =
          dedupeByProposalId(
            responses.collect { case Right(proposals) => proposals }.flatten,
          )
            .sortWith(compareProposalDescending)
            .take(policy.batchSize)
        if successful.nonEmpty then successful.asRight[CanonicalRejection]
        else if runtime.progress.nextBeforeHeight === BlockHeight.Genesis then
          Vector.empty[Proposal].asRight[CanonicalRejection]
        else
          responses
            .collectFirst { case Left(rejection) => rejection }
            .getOrElse:
              CanonicalRejection.BackfillUnavailable(
                reason = "historicalBackfillUnavailable",
                detail = Some(runtime.progress.nextBeforeHeight.render),
              )
            .asLeft[Vector[Proposal]]

  private def applyBatch(
      generation: Long,
      runtime: HistoricalBackfillRuntimeState,
      batch: Vector[Proposal],
      currentTime: Instant,
  ): F[Boolean] =
    writeLock.permit.use: _ =>
      if batch.isEmpty then
        ref.modify: state =>
          if state.generation =!= generation then state -> false
          else
            val progress = runtime.progress.copy(lastUpdatedAt = currentTime)
            state.copy(
              status = HistoricalBackfillStatus.Completed(
                reason = "genesisReached",
                progress = progress,
              ),
              runtime = None,
            ) -> false
      else
        ref.get.flatMap: state =>
          if state.generation =!= generation then false.pure[F]
          else
            archive
              .putAll(
                chainId = runtime.chainId,
                proposals = batch,
                source = policy.archiveSource,
                storedAt = currentTime,
              )
              .flatMap: storedProposalIds =>
                ref.modify: latest =>
                  if latest.generation =!= generation then latest -> false
                  else
                    batch.lastOption.fold(latest -> false): oldest =>
                      val storedCount = storedProposalIds.size
                      if storedCount <= 0 then
                        latest.copy(
                          status = HistoricalBackfillStatus.Failed(
                            reason = "historicalBackfillDuplicateBatch",
                            detail = Some(
                              runtime.progress.nextBeforeBlockId.toHexLower,
                            ),
                            progress =
                              runtime.progress.copy(lastUpdatedAt = currentTime),
                          ),
                          runtime = None,
                        ) -> false
                      else if Ordering[BlockHeight].gteq(
                          oldest.block.height,
                          runtime.progress.nextBeforeHeight,
                        )
                      then
                        latest.copy(
                          status = HistoricalBackfillStatus.Failed(
                            reason = "historicalBackfillInvalidBatch",
                            detail = Some(oldest.block.height.render),
                            progress =
                              runtime.progress.copy(lastUpdatedAt = currentTime),
                          ),
                          runtime = None,
                        ) -> false
                      else
                        val updatedProgress =
                          runtime.progress.copy(
                            nextBeforeBlockId = oldest.targetBlockId,
                            nextBeforeHeight = oldest.block.height,
                            fetchedProposalCount =
                              runtime.progress.fetchedProposalCount + batch.size.toLong,
                            lastUpdatedAt = currentTime,
                          )
                        if oldest.block.height === BlockHeight.Genesis || oldest.block.parent.isEmpty
                        then
                          latest.copy(
                            status = HistoricalBackfillStatus.Completed(
                              reason = "genesisReached",
                              progress = updatedProgress,
                            ),
                            runtime = None,
                          ) -> false
                        else
                          latest.copy(
                            status = HistoricalBackfillStatus.Running(
                              progress = updatedProgress,
                              priority = policy.priority,
                            ),
                            runtime =
                              Some(runtime.copy(progress = updatedProgress)),
                          ) -> true

  private def compareProposalDescending(
      left: Proposal,
      right: Proposal,
  ): Boolean =
    Ordering[BlockHeight].gt(left.block.height, right.block.height) ||
      (left.block.height === right.block.height &&
        left.proposalId.toHexLower < right.proposalId.toHexLower)

  private def dedupeByProposalId(
      proposals: Vector[Proposal],
  ): Vector[Proposal] =
    proposals
      .foldLeft(Map.empty[ProposalId, Proposal]): (acc, proposal) =>
        acc.updatedWith(proposal.proposalId):
          case current @ Some(_) => current
          case None              => Some(proposal)
      .values
      .toVector
