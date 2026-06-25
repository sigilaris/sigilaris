package org.sigilaris.node.jvm.runtime.txpipeline

import cats.Applicative
import cats.data.EitherT
import cats.effect.kernel.{Concurrent, Deferred, Ref, Sync}
import cats.effect.syntax.all.*
import cats.syntax.all.*

import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{
  CertifiedBlockObservation,
  FinalizedTxRangeObservation,
}
import org.sigilaris.node.txpipeline.*

trait TxPipelineProjectionNotifier[F[_]]:
  def notifyPipelineChanged(pipelineId: TxPipelineId): F[Unit]

object TxPipelineProjectionNotifier:
  def noOp[F[_]: Applicative]: TxPipelineProjectionNotifier[F] =
    _ => Applicative[F].unit

final case class TxPipelineStageFailureObservation(
    pipelineId: TxPipelineId,
    stageIndex: Int,
    reason: String,
    detail: Option[String],
)

final case class TxPipelineStageUnavailableObservation(
    pipelineId: TxPipelineId,
    stageIndex: Int,
    reason: String,
    detail: Option[String],
)

enum TxPipelineProjectionFailure:
  case StoreRejected(failure: TxPipelineStoreFailure)
  case PipelineMissing(pipelineId: TxPipelineId)

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.Recursion",
  ),
)
final class TxPipelineProjectionService[F[_]: Sync](
    store: TxPipelineStore[F],
    notifier: TxPipelineProjectionNotifier[F],
    pageSize: Int,
):
  private val listingPageSize: Int =
    pageSize.max(1)

  def recordCertifiedBlock(
      observation: CertifiedBlockObservation,
  ): F[Either[TxPipelineProjectionFailure, Vector[TxPipelineSnapshot]]] =
    updateMatchingRecords: record =>
      TxPipelineProjectionService.certifyBlock(record, observation)

  def recordFinalizedTxRange(
      observation: FinalizedTxRangeObservation,
  ): F[Either[TxPipelineProjectionFailure, Vector[TxPipelineSnapshot]]] =
    updateMatchingRecords: record =>
      TxPipelineProjectionService.finalizeRange(record, observation)

  def recordStageFailure(
      observation: TxPipelineStageFailureObservation,
  ): F[Either[TxPipelineProjectionFailure, TxPipelineSnapshot]] =
    updateSinglePipeline(
      pipelineId = observation.pipelineId,
      update = record =>
        TxPipelineProjectionService.failFromStage(
          record = record,
          stageIndex = observation.stageIndex,
        ),
    )

  def recordStageUnavailable(
      observation: TxPipelineStageUnavailableObservation,
  ): F[Either[TxPipelineProjectionFailure, TxPipelineSnapshot]] =
    updateSinglePipeline(
      pipelineId = observation.pipelineId,
      update = record =>
        TxPipelineProjectionService.unavailableFromStage(
          record = record,
          stageIndex = observation.stageIndex,
        ),
    )

  private def updateMatchingRecords(
      update: TxPipelineRecord => TxPipelineRecordUpdate,
  ): F[Either[TxPipelineProjectionFailure, Vector[TxPipelineSnapshot]]] =
    val result = for
      records <- loadRecords(offset = 0, accumulated = Vector.empty)
      snapshots <- records.traverse: record =>
        store
          .update(record.pipelineId)(latest =>
            val updated = update(latest)
            TxPipelineStoreUpdate(
              record = updated.record,
              changed = updated.changed,
            ),
          )
          .leftMap(TxPipelineProjectionFailure.StoreRejected(_))
          .semiflatTap:
            case Some(updated) if updated.changed =>
              notifier.notifyPipelineChanged(updated.record.pipelineId)
            case _ =>
              Sync[F].unit
          .map:
            case Some(updated) if updated.changed =>
              Some(updated.record.snapshot)
            case _ =>
              None
    yield snapshots.flatten

    result.value

  private def updateSinglePipeline(
      pipelineId: TxPipelineId,
      update: TxPipelineRecord => TxPipelineRecordUpdate,
  ): F[Either[TxPipelineProjectionFailure, TxPipelineSnapshot]] =
    val result = for
      updated <- store
        .update(pipelineId)(latest =>
          val updated = update(latest)
          TxPipelineStoreUpdate(
            record = updated.record,
            changed = updated.changed,
          ),
        )
        .leftMap(TxPipelineProjectionFailure.StoreRejected(_))
        .flatMap:
          case Some(updated) =>
            EitherT.rightT[F, TxPipelineProjectionFailure](updated)
          case None =>
            EitherT.leftT[F, TxPipelineStoreUpdate](
              TxPipelineProjectionFailure.PipelineMissing(pipelineId),
            )
      _ <-
        if updated.changed then
          EitherT.right[TxPipelineProjectionFailure](
            notifier.notifyPipelineChanged(updated.record.pipelineId),
          )
        else EitherT.rightT[F, TxPipelineProjectionFailure](())
    yield updated.record.snapshot

    result.value

  private def loadRecords(
      offset: Int,
      accumulated: Vector[TxPipelineRecord],
  ): EitherT[F, TxPipelineProjectionFailure, Vector[TxPipelineRecord]] =
    store
      .list(offset = offset, limit = listingPageSize)
      .leftMap(TxPipelineProjectionFailure.StoreRejected(_))
      .flatMap: page =>
        val next = accumulated ++ page
        if page.lengthIs < listingPageSize then
          EitherT.rightT[F, TxPipelineProjectionFailure](next)
        else loadRecords(offset + listingPageSize, next)

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.Recursion",
  ),
)
final class TxPipelineWaitCoordinator[F[_]: Concurrent] private (
    store: TxPipelineStore[F],
    ref: Ref[F, TxPipelineWaitCoordinator.State[F]],
) extends TxPipelineProjectionNotifier[F]:
  override def notifyPipelineChanged(
      pipelineId: TxPipelineId,
  ): F[Unit] =
    ref
      .modify: state =>
        val waiters =
          state.waiters.getOrElse(
            pipelineId,
            Vector.empty[TxPipelineWaitCoordinator.Waiter[F]],
          )
        val nextState = state.copy(
          version = state.version + 1L,
          waiters = state.waiters.removed(pipelineId),
        )
        nextState -> waiters
      .flatMap(_.traverse_(_.deferred.complete(()).void))

  def waitFor(
      pipelineId: TxPipelineId,
      waitFor: TxPipelineWaitMode,
  ): F[Either[TxPipelineProjectionFailure, TxPipelineSnapshot]] =
    waitLoop(pipelineId, waitFor)

  private[txpipeline] def pendingWaiterCount(
      pipelineId: TxPipelineId,
  ): F[Int] =
    ref.get.map(_.waiters.get(pipelineId).fold(0)(_.size))

  private def waitLoop(
      pipelineId: TxPipelineId,
      waitFor: TxPipelineWaitMode,
  ): F[Either[TxPipelineProjectionFailure, TxPipelineSnapshot]] =
    for
      version <- ref.get.map(_.version)
      loaded  <- loadSnapshot(pipelineId).value
      result <- loaded match
        case Left(failure) =>
          Concurrent[F].pure(Left(failure))
        case Right(snapshot)
            if TxPipelineProjectionService.waitSatisfied(snapshot, waitFor) =>
          Concurrent[F].pure(Right(snapshot))
        case Right(_) =>
          Deferred[F, Unit].flatMap: signal =>
            ref
              .modify: state =>
                if state.version =!= version then state -> None
                else
                  val waiter =
                    TxPipelineWaitCoordinator.Waiter(
                      id = state.nextWaiterId,
                      deferred = signal,
                    )
                  val existing =
                    state.waiters.getOrElse(
                      pipelineId,
                      Vector.empty[TxPipelineWaitCoordinator.Waiter[F]],
                    )
                  state.copy(
                    nextWaiterId = state.nextWaiterId + 1L,
                    waiters = state.waiters.updated(
                      pipelineId,
                      existing :+ waiter,
                    ),
                  ) -> Some(waiter)
              .flatMap: registered =>
                registered match
                  case Some(waiter) =>
                    waiter.deferred.get
                      .onCancel(removeWaiter(pipelineId, waiter.id)) >>
                      waitLoop(pipelineId, waitFor)
                  case None =>
                    waitLoop(pipelineId, waitFor)
    yield result

  private def removeWaiter(
      pipelineId: TxPipelineId,
      waiterId: Long,
  ): F[Unit] =
    ref.update: state =>
      state.waiters.get(pipelineId) match
        case None =>
          state
        case Some(waiters) =>
          val remaining = waiters.filterNot(_.id === waiterId)
          if remaining.isEmpty then
            state.copy(waiters = state.waiters.removed(pipelineId))
          else
            state.copy(waiters = state.waiters.updated(pipelineId, remaining))

  private def loadSnapshot(
      pipelineId: TxPipelineId,
  ): EitherT[F, TxPipelineProjectionFailure, TxPipelineSnapshot] =
    store
      .get(pipelineId)
      .leftMap(TxPipelineProjectionFailure.StoreRejected(_))
      .flatMap:
        case Some(record) =>
          EitherT.rightT[F, TxPipelineProjectionFailure](record.snapshot)
        case None =>
          EitherT.leftT[F, TxPipelineSnapshot](
            TxPipelineProjectionFailure.PipelineMissing(pipelineId),
          )

object TxPipelineWaitCoordinator:
  private final case class Waiter[F[_]](
      id: Long,
      deferred: Deferred[F, Unit],
  )

  private final case class State[F[_]](
      version: Long,
      nextWaiterId: Long,
      waiters: Map[TxPipelineId, Vector[Waiter[F]]],
  )

  private object State:
    def empty[F[_]]: State[F] =
      State(
        version = 0L,
        nextWaiterId = 0L,
        waiters = Map.empty[TxPipelineId, Vector[Waiter[F]]],
      )

  def create[F[_]: Concurrent](
      store: TxPipelineStore[F],
  ): F[TxPipelineWaitCoordinator[F]] =
    Ref
      .of[F, State[F]](State.empty[F])
      .map(ref => new TxPipelineWaitCoordinator[F](store, ref))

private final case class TxPipelineRecordUpdate(
    record: TxPipelineRecord,
    changed: Boolean,
)

private final case class TxPipelineStageUpdate(
    stage: TxPipelineStageRecord,
    changed: Boolean,
)

private object TxPipelineProjectionService:
  def certifyBlock(
      record: TxPipelineRecord,
      observation: CertifiedBlockObservation,
  ): TxPipelineRecordUpdate =
    val blockHash = observation.blockId.toHexLower
    val certifiedPlacement =
      TxPipelineConsensusPlacement(
        blockHash = blockHash,
        height = observation.height.toBigNat.toBigInt.toLong,
        certifiedObservedAt = Some(observation.certifiedObservedAt),
        finalizedObservedAt = None,
      )
    val certifiedTxHashes =
      observation.txSet.txIds.map(_.toHexLower).toSet
    val stages =
      record.stages.map(stage =>
        val staged =
          recordCertifiedStagePlacement(
            stage = stage,
            certifiedTxHashes = certifiedTxHashes,
            placement = certifiedPlacement,
          )
        val updated =
          updateStagePlacement(
            stage = staged.stage,
            blockHash = blockHash,
            certifiedObservedAt = Some(observation.certifiedObservedAt),
            finalizedObservedAt = None,
          )
        TxPipelineStageUpdate(
          stage = updated.stage,
          changed = staged.changed || updated.changed,
        )
      )
    summarize(record, stages)

  private def recordCertifiedStagePlacement(
      stage: TxPipelineStageRecord,
      certifiedTxHashes: Set[String],
      placement: TxPipelineConsensusPlacement,
  ): TxPipelineStageUpdate =
    if certifiedTxHashes.isEmpty then
      TxPipelineStageUpdate(stage = stage, changed = false)
    else
      val transactionUpdates =
        stage.transactions.map(tx =>
          if certifiedTxHashes.contains(normalizeTxHash(tx.txHash.value)) then
            appendCertifiedPlacement(tx, placement)
          else TxPipelineCertifiedTransactionUpdate(tx, changed = false),
        )
      val transactions =
        transactionUpdates.map(_.transaction)
      val certifiedAny =
        transactionUpdates.exists(_.changed)
      val placements =
        if certifiedAny then appendPlacement(stage.placements, placement)
        else TxPipelinePlacementAppend(stage.placements, changed = false)
      TxPipelineStageUpdate(
        stage = summarizeStage(
          stage.copy(
            placements = placements.placements,
            transactions = transactions,
          ),
        ),
        changed = certifiedAny || placements.changed,
      )

  private def normalizeTxHash(value: String): String =
    value.toLowerCase(java.util.Locale.ROOT)

  private final case class TxPipelineCertifiedTransactionUpdate(
      transaction: TxPipelineTransactionRecord,
      changed: Boolean,
  )

  private def appendCertifiedPlacement(
      tx: TxPipelineTransactionRecord,
      placement: TxPipelineConsensusPlacement,
  ): TxPipelineCertifiedTransactionUpdate =
    val placements =
      appendPlacement(tx.placements, placement)
    val state =
      if placements.changed then advanceToCertified(tx.pipelineState)
      else tx.pipelineState
    TxPipelineCertifiedTransactionUpdate(
      transaction = tx.copy(
        pipelineState = state,
        placements = placements.placements,
      ),
      changed = placements.changed || !sameTransactionState(
        tx.pipelineState,
        state,
      ),
    )

  def finalizeRange(
      record: TxPipelineRecord,
      observation: FinalizedTxRangeObservation,
  ): TxPipelineRecordUpdate =
    val finalizedBlockHashes =
      observation.proposals.map(_.blockId.toHexLower).toSet
    val stages =
      record.stages.map: stage =>
        val updated =
          finalizedBlockHashes.foldLeft(
            TxPipelineStageUpdate(stage = stage, changed = false),
          ): (acc, blockHash) =>
            val next = updateStagePlacement(
              stage = acc.stage,
              blockHash = blockHash,
              certifiedObservedAt = None,
              finalizedObservedAt = Some(observation.finalizedObservedAt),
            )
            TxPipelineStageUpdate(
              stage = next.stage,
              changed = acc.changed || next.changed,
            )
        updated
    summarize(record, stages)

  private final case class TxPipelinePlacementAppend(
      placements: Vector[TxPipelineConsensusPlacement],
      changed: Boolean,
  )

  private def appendPlacement(
      placements: Vector[TxPipelineConsensusPlacement],
      placement: TxPipelineConsensusPlacement,
  ): TxPipelinePlacementAppend =
    if placements.exists(existing =>
        existing.blockHash === placement.blockHash &&
          existing.height === placement.height,
      )
    then TxPipelinePlacementAppend(placements, changed = false)
    else TxPipelinePlacementAppend(placements :+ placement, changed = true)

  def failFromStage(
      record: TxPipelineRecord,
      stageIndex: Int,
  ): TxPipelineRecordUpdate =
    val stages =
      record.stages.map: stage =>
        if stage.stageIndex >= stageIndex then
          terminalStageUpdate(
            stage = stage,
            stageStatus = TxPipelineStageStatus.Failed,
            txState = TxPipelineTransactionState.Failed,
          )
        else TxPipelineStageUpdate(stage = stage, changed = false)
    summarize(record, stages)

  def unavailableFromStage(
      record: TxPipelineRecord,
      stageIndex: Int,
  ): TxPipelineRecordUpdate =
    val stages =
      record.stages.map: stage =>
        if stage.stageIndex >= stageIndex then
          terminalStageUpdate(
            stage = stage,
            stageStatus = TxPipelineStageStatus.Unavailable,
            txState = TxPipelineTransactionState.Unavailable,
          )
        else TxPipelineStageUpdate(stage = stage, changed = false)
    summarize(record, stages)

  def waitSatisfied(
      snapshot: TxPipelineSnapshot,
      waitFor: TxPipelineWaitMode,
  ): Boolean =
    waitFor match
      case TxPipelineWaitMode.Accepted =>
        true
      case TxPipelineWaitMode.Certified =>
        statusAtLeastCertified(snapshot.status) ||
        terminalOrUnavailable(snapshot)
      case TxPipelineWaitMode.Finalized =>
        isPipelineFinalized(snapshot.status) ||
        terminalOrUnavailable(snapshot)

  private def updateStagePlacement(
      stage: TxPipelineStageRecord,
      blockHash: String,
      certifiedObservedAt: Option[java.time.Instant],
      finalizedObservedAt: Option[java.time.Instant],
  ): TxPipelineStageUpdate =
    val stagePlacements =
      updatePlacements(
        placements = stage.placements,
        blockHash = blockHash,
        certifiedObservedAt = certifiedObservedAt,
        finalizedObservedAt = finalizedObservedAt,
      )
    val transactionUpdates =
      stage.transactions.map: tx =>
        val placements =
          updatePlacements(
            placements = tx.placements,
            blockHash = blockHash,
            certifiedObservedAt = certifiedObservedAt,
            finalizedObservedAt = finalizedObservedAt,
          )
        val state =
          if placements.changed then
            finalizedObservedAt match
              case Some(_) => advanceToFinalized(tx.pipelineState)
              case None    => advanceToCertified(tx.pipelineState)
          else tx.pipelineState
        (
          tx.copy(
            pipelineState = state,
            placements = placements.placements,
          ),
          placements.changed,
        )
    val updatedStage =
      summarizeStage(
        stage.copy(
          placements = stagePlacements.placements,
          transactions = transactionUpdates.map(_._1),
        ),
      )
    TxPipelineStageUpdate(
      stage = updatedStage,
      changed = stagePlacements.changed || transactionUpdates.exists(_._2),
    )

  private final case class PlacementUpdate(
      placements: Vector[TxPipelineConsensusPlacement],
      changed: Boolean,
  )

  private def updatePlacements(
      placements: Vector[TxPipelineConsensusPlacement],
      blockHash: String,
      certifiedObservedAt: Option[java.time.Instant],
      finalizedObservedAt: Option[java.time.Instant],
  ): PlacementUpdate =
    val updated =
      placements.map: placement =>
        if placement.blockHash === blockHash then
          val next = placement.copy(
            certifiedObservedAt =
              placement.certifiedObservedAt.orElse(certifiedObservedAt),
            finalizedObservedAt =
              placement.finalizedObservedAt.orElse(finalizedObservedAt),
          )
          next -> placementChanged(placement, next)
        else placement -> false
    PlacementUpdate(
      placements = updated.map(_._1),
      changed = updated.exists(_._2),
    )

  private def placementChanged(
      before: TxPipelineConsensusPlacement,
      after: TxPipelineConsensusPlacement,
  ): Boolean =
    before.certifiedObservedAt.isEmpty && after.certifiedObservedAt.nonEmpty ||
      before.finalizedObservedAt.isEmpty && after.finalizedObservedAt.nonEmpty

  private def summarize(
      record: TxPipelineRecord,
      stages: Vector[TxPipelineStageUpdate],
  ): TxPipelineRecordUpdate =
    val barrierUpdatedStages =
      satisfyCertifiedBarriers(stages.map(_.stage))
    val changed =
      stages.exists(_.changed) || barrierUpdatedStages.exists(_.changed)
    val summarizedStages =
      barrierUpdatedStages.map(update => summarizeStage(update.stage))
    val summarized =
      record.copy(
        status = summarizePipeline(summarizedStages),
        stages = summarizedStages,
      )
    TxPipelineRecordUpdate(record = summarized, changed = changed)

  private def satisfyCertifiedBarriers(
      stages: Vector[TxPipelineStageRecord],
  ): Vector[TxPipelineStageUpdate] =
    val byStageIndex =
      stages.iterator.map(stage => stage.stageIndex -> stage).toMap
    stages.map: stage =>
      stage.barrier match
        case Some(barrier) if !barrier.satisfied && canSatisfyBarrier(stage) =>
          byStageIndex.get(barrier.dependsOnStage) match
            case Some(previous) if stageFullyCertified(previous) =>
              val satisfiedBy =
                previous.transactions
                  .flatMap(_.placements)
                  .filter(placementAtLeastCertified)
                  .maxByOption(placement =>
                    (placement.height, placement.blockHash),
                  )
                  .map(_.blockHash)
              TxPipelineStageUpdate(
                stage = stage.copy(
                  status = TxPipelineStageStatus.Eligible,
                  barrier = Some(
                    barrier.copy(
                      satisfied = true,
                      satisfiedByBlockHash = satisfiedBy,
                    ),
                  ),
                  transactions = stage.transactions.map: tx =>
                    tx.pipelineState match
                      case TxPipelineTransactionState.Held =>
                        tx.copy(
                          pipelineState = TxPipelineTransactionState.Eligible,
                        )
                      case _ => tx,
                ),
                changed = true,
              )
            case _ =>
              TxPipelineStageUpdate(stage = stage, changed = false)
        case _ =>
          TxPipelineStageUpdate(stage = stage, changed = false)

  private def stageFullyCertified(
      stage: TxPipelineStageRecord,
  ): Boolean =
    stage.transactions.nonEmpty &&
      stage.transactions.forall(tx =>
        tx.placements.exists(placementAtLeastCertified),
      )

  private def placementAtLeastCertified(
      placement: TxPipelineConsensusPlacement,
  ): Boolean =
    placement.certifiedObservedAt.nonEmpty ||
      placement.finalizedObservedAt.nonEmpty

  private def terminalStageUpdate(
      stage: TxPipelineStageRecord,
      stageStatus: TxPipelineStageStatus,
      txState: TxPipelineTransactionState,
  ): TxPipelineStageUpdate =
    val txChanged =
      stage.transactions.exists(tx =>
        !sameTransactionState(tx.pipelineState, txState),
      )
    val transactions =
      stage.transactions.map: tx =>
        if sameTransactionState(tx.pipelineState, txState) then tx
        else tx.copy(pipelineState = txState)
    TxPipelineStageUpdate(
      stage = stage.copy(status = stageStatus, transactions = transactions),
      changed = !sameStageStatus(stage.status, stageStatus) || txChanged,
    )

  private def canSatisfyBarrier(
      stage: TxPipelineStageRecord,
  ): Boolean =
    !isStageFailed(stage.status) &&
      !isStageUnavailable(stage.status) &&
      !stage.transactions.exists(tx => isFailed(tx.pipelineState)) &&
      !stage.transactions.exists(tx => isUnavailable(tx.pipelineState))

  private def summarizeStage(
      stage: TxPipelineStageRecord,
  ): TxPipelineStageRecord =
    stage.copy(status = summarizeStageStatus(stage))

  private def summarizeStageStatus(
      stage: TxPipelineStageRecord,
  ): TxPipelineStageStatus =
    if stage.transactions.isEmpty then stage.status
    else if stage.transactions.exists(tx => isFailed(tx.pipelineState)) then
      TxPipelineStageStatus.Failed
    else if stage.transactions.exists(tx => isUnavailable(tx.pipelineState))
    then TxPipelineStageStatus.Unavailable
    else if stage.transactions.forall(tx => isFinalized(tx.pipelineState)) then
      TxPipelineStageStatus.Finalized
    else if stage.transactions.forall(tx =>
        stateAtLeastCertified(tx.pipelineState),
      )
    then TxPipelineStageStatus.Certified
    else if stage.transactions.exists(tx => isEligible(tx.pipelineState)) then
      TxPipelineStageStatus.Eligible
    else if stage.transactions.exists(tx => isProposed(tx.pipelineState)) then
      TxPipelineStageStatus.Proposed
    else if stage.transactions.forall(tx => isHeld(tx.pipelineState)) then
      TxPipelineStageStatus.Held
    else TxPipelineStageStatus.Accepted

  private def summarizePipeline(
      stages: Vector[TxPipelineStageRecord],
  ): TxPipelineStatus =
    if stages.nonEmpty && stages.forall(stage => isStageFinalized(stage.status))
    then TxPipelineStatus.Finalized
    else if stages.exists(stage => isStageFailed(stage.status)) then
      if stages.forall(stage => isStageFailed(stage.status)) then
        TxPipelineStatus.Failed
      else TxPipelineStatus.PartiallyFailed
    else if stages.nonEmpty &&
      stages.forall(stage => stageAtLeastCertified(stage.status))
    then TxPipelineStatus.Certified
    else TxPipelineStatus.Running

  private def terminalOrUnavailable(
      snapshot: TxPipelineSnapshot,
  ): Boolean =
    isPipelineFailed(snapshot.status) ||
      snapshot.stages.exists(stage => isStageUnavailable(stage.status)) ||
      snapshot.stages.exists(stage =>
        stage.transactions.exists(tx => isUnavailable(tx.pipelineState)),
      )

  private def statusAtLeastCertified(
      status: TxPipelineStatus,
  ): Boolean =
    status match
      case TxPipelineStatus.Certified | TxPipelineStatus.Finalized => true
      case _                                                       => false

  private def isPipelineFinalized(
      status: TxPipelineStatus,
  ): Boolean =
    status match
      case TxPipelineStatus.Finalized => true
      case _                          => false

  private def isPipelineFailed(
      status: TxPipelineStatus,
  ): Boolean =
    status match
      case TxPipelineStatus.Failed | TxPipelineStatus.PartiallyFailed => true
      case _                                                          => false

  private def stageAtLeastCertified(
      status: TxPipelineStageStatus,
  ): Boolean =
    status match
      case TxPipelineStageStatus.Certified | TxPipelineStageStatus.Finalized =>
        true
      case _ => false

  private def isStageFinalized(
      status: TxPipelineStageStatus,
  ): Boolean =
    status match
      case TxPipelineStageStatus.Finalized => true
      case _                               => false

  private def isStageFailed(
      status: TxPipelineStageStatus,
  ): Boolean =
    status match
      case TxPipelineStageStatus.Failed => true
      case _                            => false

  private def isStageUnavailable(
      status: TxPipelineStageStatus,
  ): Boolean =
    status match
      case TxPipelineStageStatus.Unavailable => true
      case _                                 => false

  private def stateAtLeastCertified(
      state: TxPipelineTransactionState,
  ): Boolean =
    state match
      case TxPipelineTransactionState.Certified |
          TxPipelineTransactionState.Finalized =>
        true
      case _ => false

  private def advanceToCertified(
      state: TxPipelineTransactionState,
  ): TxPipelineTransactionState =
    state match
      case TxPipelineTransactionState.Finalized |
          TxPipelineTransactionState.Failed |
          TxPipelineTransactionState.Unavailable =>
        state
      case _ => TxPipelineTransactionState.Certified

  private def advanceToFinalized(
      state: TxPipelineTransactionState,
  ): TxPipelineTransactionState =
    state match
      case TxPipelineTransactionState.Failed |
          TxPipelineTransactionState.Unavailable =>
        state
      case _ => TxPipelineTransactionState.Finalized

  private def isFinalized(
      state: TxPipelineTransactionState,
  ): Boolean =
    state match
      case TxPipelineTransactionState.Finalized => true
      case _                                    => false

  private def isFailed(
      state: TxPipelineTransactionState,
  ): Boolean =
    state match
      case TxPipelineTransactionState.Failed => true
      case _                                 => false

  private def isUnavailable(
      state: TxPipelineTransactionState,
  ): Boolean =
    state match
      case TxPipelineTransactionState.Unavailable => true
      case _                                      => false

  private def isEligible(
      state: TxPipelineTransactionState,
  ): Boolean =
    state match
      case TxPipelineTransactionState.Eligible => true
      case _                                   => false

  private def isProposed(
      state: TxPipelineTransactionState,
  ): Boolean =
    state match
      case TxPipelineTransactionState.Proposed => true
      case _                                   => false

  private def isHeld(
      state: TxPipelineTransactionState,
  ): Boolean =
    state match
      case TxPipelineTransactionState.Held => true
      case _                               => false

  private def sameStageStatus(
      left: TxPipelineStageStatus,
      right: TxPipelineStageStatus,
  ): Boolean =
    (left, right) match
      case (TxPipelineStageStatus.Accepted, TxPipelineStageStatus.Accepted) |
          (TxPipelineStageStatus.Held, TxPipelineStageStatus.Held) |
          (TxPipelineStageStatus.Eligible, TxPipelineStageStatus.Eligible) |
          (TxPipelineStageStatus.Proposed, TxPipelineStageStatus.Proposed) |
          (TxPipelineStageStatus.Certified, TxPipelineStageStatus.Certified) |
          (TxPipelineStageStatus.Finalized, TxPipelineStageStatus.Finalized) |
          (TxPipelineStageStatus.Failed, TxPipelineStageStatus.Failed) | (
            TxPipelineStageStatus.Unavailable,
            TxPipelineStageStatus.Unavailable,
          ) =>
        true
      case _ => false

  private def sameTransactionState(
      left: TxPipelineTransactionState,
      right: TxPipelineTransactionState,
  ): Boolean =
    (left, right) match
      case (
            TxPipelineTransactionState.Accepted,
            TxPipelineTransactionState.Accepted,
          ) |
          (TxPipelineTransactionState.Held, TxPipelineTransactionState.Held) | (
            TxPipelineTransactionState.Eligible,
            TxPipelineTransactionState.Eligible,
          ) | (
            TxPipelineTransactionState.Proposed,
            TxPipelineTransactionState.Proposed,
          ) | (
            TxPipelineTransactionState.Certified,
            TxPipelineTransactionState.Certified,
          ) | (
            TxPipelineTransactionState.Finalized,
            TxPipelineTransactionState.Finalized,
          ) | (
            TxPipelineTransactionState.Failed,
            TxPipelineTransactionState.Failed,
          ) | (
            TxPipelineTransactionState.Unavailable,
            TxPipelineTransactionState.Unavailable,
          ) =>
        true
      case _ => false
