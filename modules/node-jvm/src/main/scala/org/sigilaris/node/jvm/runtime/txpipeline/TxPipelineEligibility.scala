package org.sigilaris.node.jvm.runtime.txpipeline

import cats.Applicative
import cats.data.EitherT
import cats.effect.kernel.Sync
import cats.syntax.all.*

import org.sigilaris.core.datatype.UInt256
import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.gossip.StableArtifactId
import org.sigilaris.node.jvm.runtime.block.{BlockHeight, BlockId}
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{
  HotStuffProposalInputBranchContext,
  HotStuffProposalInputBounds,
  HotStuffProposalInputDependencyReason,
  HotStuffProposalInputRequest,
  ProposalTxSet,
}
import org.sigilaris.node.txpipeline.*

/** Maps pipeline transaction hashes into the consensus proposal tx-id domain.
  */
trait TxPipelineConsensusTxIdMapper[F[_]]:
  def toProposalTxId(
      txHash: TxPipelineTxHash,
  ): F[Either[String, StableArtifactId]]

object TxPipelineConsensusTxIdMapper:
  def fixedWidthHex[F[_]: Applicative]: TxPipelineConsensusTxIdMapper[F] =
    txHash =>
      Applicative[F].pure:
        StableArtifactId
          .fromHex(txHash.value)
          .flatMap: txId =>
            Either.cond(
              txId.bytes.size === UInt256.Size.toLong,
              txId,
              ss"txHash must be ${UInt256.Size.toString} bytes",
            )

final case class TxPipelineEligibleTransaction(
    pipelineId: TxPipelineId,
    stageIndex: Int,
    transactionIndex: Int,
    txHash: TxPipelineTxHash,
    txId: StableArtifactId,
    payload: TxPipelineTransactionPayload,
)

final case class TxPipelineEligibilityDiagnostic(
    pipelineId: TxPipelineId,
    stageIndex: Int,
    reason: String,
    detail: Option[String],
)

final case class TxPipelineEligibilitySelection(
    transactions: Vector[TxPipelineEligibleTransaction],
    txSet: ProposalTxSet,
    diagnostics: Vector[TxPipelineEligibilityDiagnostic],
):
  def hasWork: Boolean =
    transactions.nonEmpty

object TxPipelineEligibilitySelection:
  val empty: TxPipelineEligibilitySelection =
    TxPipelineEligibilitySelection(
      transactions = Vector.empty[TxPipelineEligibleTransaction],
      txSet = ProposalTxSet.empty,
      diagnostics = Vector.empty[TxPipelineEligibilityDiagnostic],
    )

final case class TxPipelinePlacementTarget(
    blockId: BlockId,
    height: BlockHeight,
)

enum TxPipelineEligibilityFailure:
  case StoreRejected(failure: TxPipelineStoreFailure)
  case TxIdRejected(txHash: TxPipelineTxHash, detail: String)
  case PipelineMissing(pipelineId: TxPipelineId)
  case InvalidPlacementTarget(detail: String)

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.Recursion",
  ),
)
final class TxPipelineEligibilityService[F[_]: Sync](
    store: TxPipelineStore[F],
    txIdMapper: TxPipelineConsensusTxIdMapper[F],
    workNotifier: TxPipelineWorkNotifier[F],
    pageSize: Int,
):
  private val listingPageSize: Int =
    pageSize.max(1)

  def select(
      request: HotStuffProposalInputRequest,
  ): F[Either[TxPipelineEligibilityFailure, TxPipelineEligibilitySelection]] =
    val result = for
      records <- loadRecords(offset = 0, accumulated = Vector.empty)
      selection <- selectFromRecords(
        records = records,
        branchContext = request.branchContext,
        bounds = request.bounds,
        excludedTxIds = request.txExclusion.excludedTxIds.txIds.toSet,
      )
    yield selection

    result.value

  def markProposed(
      selection: TxPipelineEligibilitySelection,
      target: TxPipelinePlacementTarget,
  ): F[Either[TxPipelineEligibilityFailure, Unit]] =
    val byPipeline =
      selection.transactions.groupBy(_.pipelineId)
    val result = for
      placement <- placementFor(target)
      _ <- byPipeline.toVector.traverse_ { case (pipelineId, transactions) =>
        store
          .update(pipelineId)(record =>
            val updated =
              markPipelineProposed(record, transactions, placement)
            TxPipelineStoreUpdate(
              record = updated.record,
              changed = updated.changed,
            )
          )
          .leftMap(TxPipelineEligibilityFailure.StoreRejected(_))
          .flatMap:
            case Some(_) =>
              EitherT.rightT[F, TxPipelineEligibilityFailure](())
            case None =>
              EitherT.leftT[F, Unit](
                TxPipelineEligibilityFailure.PipelineMissing(pipelineId),
              )
      }
    yield ()

    result.value

  def notifyCertifiedObservationAvailable: F[Unit] =
    workNotifier.notifyApplicationWorkAvailable

  private def loadRecords(
      offset: Int,
      accumulated: Vector[TxPipelineRecord],
  ): EitherT[F, TxPipelineEligibilityFailure, Vector[TxPipelineRecord]] =
    store
      .list(offset = offset, limit = listingPageSize)
      .leftMap(TxPipelineEligibilityFailure.StoreRejected(_))
      .flatMap: page =>
        val next = accumulated ++ page
        if page.lengthIs < listingPageSize then
          EitherT.rightT[F, TxPipelineEligibilityFailure](next)
        else loadRecords(offset + listingPageSize, next)

  private def selectFromRecords(
      records: Vector[TxPipelineRecord],
      branchContext: HotStuffProposalInputBranchContext,
      bounds: HotStuffProposalInputBounds,
      excludedTxIds: Set[StableArtifactId],
  ): EitherT[F, TxPipelineEligibilityFailure, TxPipelineEligibilitySelection] =
    val remaining =
      bounds.maxTxIds.fold(Int.MaxValue)(_.max(0))
    records
      .filter(record => isOpen(record.status))
      .foldM(
        (
          TxPipelineEligibilitySelection.empty,
          remaining,
          excludedTxIds,
        ),
      ):
        case ((selection, available, usedTxIds), record) if available <= 0 =>
          EitherT.rightT[F, TxPipelineEligibilityFailure](
            (selection, available, usedTxIds),
          )
        case ((selection, available, usedTxIds), record) =>
          selectFromRecord(
            record = record,
            branchContext = branchContext,
            excludedTxIds = usedTxIds,
            limit = available,
          ).map: recordSelection =>
            val selected =
              selection.transactions ++ recordSelection.transactions
            val diagnostics =
              selection.diagnostics ++ recordSelection.diagnostics
            val nextUsed =
              usedTxIds ++ recordSelection.transactions.map(_.txId)
            (
              TxPipelineEligibilitySelection(
                transactions = selected,
                txSet =
                  ProposalTxSet.canonical(ProposalTxSet(selected.map(_.txId))),
                diagnostics = diagnostics,
              ),
              available - recordSelection.transactions.length,
              nextUsed,
            )
      .map(_._1)

  private def selectFromRecord(
      record: TxPipelineRecord,
      branchContext: HotStuffProposalInputBranchContext,
      excludedTxIds: Set[StableArtifactId],
      limit: Int,
  ): EitherT[F, TxPipelineEligibilityFailure, TxPipelineEligibilitySelection] =
    record.stages
      .foldM(
        TxPipelineEligibilitySelection.empty -> false,
      ):
        case ((selection, stopped), _) if stopped =>
          EitherT.rightT[F, TxPipelineEligibilityFailure](selection -> stopped)
        case ((selection, _), stage) =>
          barrierForStage(record, stage, branchContext) match
            case TxPipelineStageBarrier.Open =>
              selectTransactions(
                record = record,
                stage = stage,
                excludedTxIds = excludedTxIds,
                limit = limit,
              ).map: transactions =>
                TxPipelineEligibilitySelection(
                  transactions = selection.transactions ++ transactions,
                  txSet = ProposalTxSet.canonical(
                    ProposalTxSet(
                      (selection.transactions ++ transactions).map(_.txId),
                    ),
                  ),
                  diagnostics = selection.diagnostics,
                ) -> transactions.nonEmpty
            case TxPipelineStageBarrier.Blocked(reason, detail) =>
              val diagnostic =
                TxPipelineEligibilityDiagnostic(
                  pipelineId = record.pipelineId,
                  stageIndex = stage.stageIndex,
                  reason = reason,
                  detail = detail,
                )
              EitherT.rightT[F, TxPipelineEligibilityFailure](
                selection.copy(
                  diagnostics = selection.diagnostics :+ diagnostic,
                ) -> true,
              )
      .map(_._1)

  private def selectTransactions(
      record: TxPipelineRecord,
      stage: TxPipelineStageRecord,
      excludedTxIds: Set[StableArtifactId],
      limit: Int,
  ): EitherT[F, TxPipelineEligibilityFailure, Vector[
    TxPipelineEligibleTransaction,
  ]] =
    stage.transactions
      .filter(tx => isSelectable(tx.pipelineState))
      .foldM(Vector.empty[TxPipelineEligibleTransaction]):
        case (selected, _) if selected.lengthIs >= limit =>
          EitherT.rightT[F, TxPipelineEligibilityFailure](selected)
        case (selected, tx) =>
          mapTxId(tx.txHash).map: txId =>
            if excludedTxIds.contains(txId) then selected
            else
              selected :+ TxPipelineEligibleTransaction(
                pipelineId = record.pipelineId,
                stageIndex = stage.stageIndex,
                transactionIndex = tx.transactionIndex,
                txHash = tx.txHash,
                txId = txId,
                payload = tx.payload,
              )

  private def mapTxId(
      txHash: TxPipelineTxHash,
  ): EitherT[F, TxPipelineEligibilityFailure, StableArtifactId] =
    EitherT:
      txIdMapper
        .toProposalTxId(txHash)
        .map(
          _.leftMap(detail =>
            TxPipelineEligibilityFailure.TxIdRejected(txHash, detail),
          ),
        )

  private def barrierForStage(
      record: TxPipelineRecord,
      stage: TxPipelineStageRecord,
      branchContext: HotStuffProposalInputBranchContext,
  ): TxPipelineStageBarrier =
    if stage.stageIndex === 0 then TxPipelineStageBarrier.Open
    else
      record.stages.find(_.stageIndex === stage.stageIndex - 1) match
        case None =>
          TxPipelineStageBarrier.blocked(
            HotStuffProposalInputDependencyReason.Held,
            Some(ss"missingPreviousStage=${(stage.stageIndex - 1).toString}"),
          )
        case Some(previous) =>
          barrierForPreviousStage(previous, branchContext)

  private def barrierForPreviousStage(
      previous: TxPipelineStageRecord,
      branchContext: HotStuffProposalInputBranchContext,
  ): TxPipelineStageBarrier =
    val missingPlacements =
      previous.transactions.find(_.placements.isEmpty)
    missingPlacements match
      case Some(tx) =>
        TxPipelineStageBarrier.blocked(
          HotStuffProposalInputDependencyReason.Held,
          Some(
            ss"stage=${previous.stageIndex.toString},tx=${tx.transactionIndex.toString}",
          ),
        )
      case None =>
        val certifiedBlockHashes =
          branchContext.ancestors.map(_.blockId.toHexLower).toSet
        val unsatisfied =
          previous.transactions.find: tx =>
            !tx.placements.exists(placement =>
              certifiedBlockHashes.contains(placement.blockHash),
            )
        unsatisfied match
          case None => TxPipelineStageBarrier.Open
          case Some(tx) =>
            val detail =
              tx.placements.headOption.map(_.blockHash)
            if branchContext.complete then
              TxPipelineStageBarrier.blocked(
                HotStuffProposalInputDependencyReason.BranchConflict,
                detail,
              )
            else
              TxPipelineStageBarrier.blocked(
                branchContext.unavailableReason.getOrElse(
                  HotStuffProposalInputDependencyReason.AncestorUnavailable,
                ),
                branchContext.unavailableDetail.orElse(detail),
              )

  private def placementFor(
      target: TxPipelinePlacementTarget,
  ): EitherT[F, TxPipelineEligibilityFailure, TxPipelineConsensusPlacement] =
    EitherT.fromEither:
      val height = target.height.toBigNat.toBigInt
      Either
        .cond(
          height <= BigInt(Long.MaxValue),
          height.toLong,
          TxPipelineEligibilityFailure.InvalidPlacementTarget(
            ss"height exceeds Long.MaxValue: ${height.toString}",
          ),
        )
        .map: placementHeight =>
          TxPipelineConsensusPlacement(
            blockHash = target.blockId.toHexLower,
            height = placementHeight,
            certifiedObservedAt = None,
            finalizedObservedAt = None,
          )

  private def markPipelineProposed(
      record: TxPipelineRecord,
      transactions: Vector[TxPipelineEligibleTransaction],
      placement: TxPipelineConsensusPlacement,
  ): TxPipelineProposedUpdate =
    val selectedByStage =
      transactions.groupBy(_.stageIndex)
    val status =
      if isOpen(record.status) then TxPipelineStatus.Running
      else record.status
    val stages =
      record.stages.map: stage =>
        selectedByStage.get(stage.stageIndex) match
          case None => TxPipelineProposedStageUpdate(stage, changed = false)
          case Some(selected) =>
            markStageProposed(stage, selected, placement)
    TxPipelineProposedUpdate(
      record = record.copy(
        status = status,
        stages = stages.map(_.stage),
      ),
      changed = statusChanged(record.status, status) || stages.exists(_.changed),
    )

  private def markStageProposed(
      stage: TxPipelineStageRecord,
      selected: Vector[TxPipelineEligibleTransaction],
      placement: TxPipelineConsensusPlacement,
  ): TxPipelineProposedStageUpdate =
    val selectedTransactionIndexes =
      selected.map(_.transactionIndex).toSet
    val transactionUpdates =
      stage.transactions.map: tx =>
        if selectedTransactionIndexes.contains(tx.transactionIndex) then
          markTransactionProposed(tx, placement)
        else
          tx.pipelineState match
            case TxPipelineTransactionState.Held =>
              TxPipelineProposedTransactionUpdate(
                transaction = tx.copy(
                  pipelineState = TxPipelineTransactionState.Eligible,
                ),
                changed = true,
              )
            case _ =>
              TxPipelineProposedTransactionUpdate(tx, changed = false)
    val transactions =
      transactionUpdates.map(_.transaction)
    val selectedTransactionChanged =
      transactionUpdates.exists(update =>
        selectedTransactionIndexes.contains(update.transaction.transactionIndex) &&
          update.changed,
      )
    val status =
      markedStageStatus(stage.status, transactions)
    val placements =
      if selectedTransactionChanged then
        appendPlacement(stage.placements, placement)
      else TxPipelinePlacementAppend(stage.placements, changed = false)
    TxPipelineProposedStageUpdate(
      stage = stage.copy(
        status = status.status,
        placements = placements.placements,
        transactions = transactions,
      ),
      changed =
        transactionUpdates.exists(_.changed) || status.changed ||
          placements.changed,
    )

  private def markTransactionProposed(
      tx: TxPipelineTransactionRecord,
      placement: TxPipelineConsensusPlacement,
  ): TxPipelineProposedTransactionUpdate =
    tx.pipelineState match
      case state if canMarkProposed(state) =>
        val placements =
          appendPlacement(tx.placements, placement)
        TxPipelineProposedTransactionUpdate(
          transaction = tx.copy(
            pipelineState = TxPipelineTransactionState.Proposed,
            placements = placements.placements,
          ),
          changed = !isProposed(state) || placements.changed,
        )
      case _ =>
        TxPipelineProposedTransactionUpdate(tx, changed = false)

  private def markedStageStatus(
      current: TxPipelineStageStatus,
      transactions: Vector[TxPipelineTransactionRecord],
  ): TxPipelineStageStatusUpdate =
    if transactions.exists(tx => isSelectable(tx.pipelineState)) then
      TxPipelineStageStatusUpdate(
        TxPipelineStageStatus.Eligible,
        !isStageEligible(current),
      )
    else if transactions.exists(tx => isProposed(tx.pipelineState)) then
      TxPipelineStageStatusUpdate(
        TxPipelineStageStatus.Proposed,
        !isStageProposed(current),
      )
    else TxPipelineStageStatusUpdate(current, changed = false)

  private def canMarkProposed(
      state: TxPipelineTransactionState,
  ): Boolean =
    state match
      case TxPipelineTransactionState.Held |
          TxPipelineTransactionState.Eligible |
          TxPipelineTransactionState.Proposed =>
        true
      case _ => false

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

  private def statusChanged(
      before: TxPipelineStatus,
      after: TxPipelineStatus,
  ): Boolean =
    (before, after) match
      case (TxPipelineStatus.Accepted, TxPipelineStatus.Accepted) |
          (TxPipelineStatus.Running, TxPipelineStatus.Running) |
          (TxPipelineStatus.Certified, TxPipelineStatus.Certified) |
          (TxPipelineStatus.Finalized, TxPipelineStatus.Finalized) |
          (TxPipelineStatus.Failed, TxPipelineStatus.Failed) | (
            TxPipelineStatus.PartiallyFailed,
            TxPipelineStatus.PartiallyFailed,
          ) =>
        false
      case _ => true

  private def isOpen(
      status: TxPipelineStatus,
  ): Boolean =
    status match
      case TxPipelineStatus.Accepted | TxPipelineStatus.Running => true
      case _                                                    => false

  private def isSelectable(
      state: TxPipelineTransactionState,
  ): Boolean =
    state match
      case TxPipelineTransactionState.Eligible |
          TxPipelineTransactionState.Held =>
        true
      case _ => false

  private def isProposed(
      state: TxPipelineTransactionState,
  ): Boolean =
    state match
      case TxPipelineTransactionState.Proposed => true
      case _                                   => false

  private def isStageEligible(
      status: TxPipelineStageStatus,
  ): Boolean =
    status match
      case TxPipelineStageStatus.Eligible => true
      case _                              => false

  private def isStageProposed(
      status: TxPipelineStageStatus,
  ): Boolean =
    status match
      case TxPipelineStageStatus.Proposed => true
      case _                              => false

private final case class TxPipelineProposedUpdate(
    record: TxPipelineRecord,
    changed: Boolean,
)

private final case class TxPipelineProposedStageUpdate(
    stage: TxPipelineStageRecord,
    changed: Boolean,
)

private final case class TxPipelineProposedTransactionUpdate(
    transaction: TxPipelineTransactionRecord,
    changed: Boolean,
)

private final case class TxPipelineStageStatusUpdate(
    status: TxPipelineStageStatus,
    changed: Boolean,
)

private final case class TxPipelinePlacementAppend(
    placements: Vector[TxPipelineConsensusPlacement],
    changed: Boolean,
)

private enum TxPipelineStageBarrier:
  case Open
  case Blocked(reason: String, detail: Option[String])

private object TxPipelineStageBarrier:
  def blocked(
      reason: String,
      detail: Option[String],
  ): TxPipelineStageBarrier =
    Blocked(reason, detail)
