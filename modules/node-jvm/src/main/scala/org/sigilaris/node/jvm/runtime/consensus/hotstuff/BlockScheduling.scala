package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import cats.syntax.all.*

import org.sigilaris.core.application.scheduling.{
  AggregateFootprint,
  CompatibilityReason,
  ConflictKind,
  SchedulingClassification,
  StateRef,
}
import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.crypto.Hash
import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.jvm.runtime.block.{
  BlockBody,
  BlockHeader,
  BlockRecord,
  BlockRecordHash,
  BlockValidationFailure,
  BlockView,
}

/** Reasons a block record can be rejected during conflict-free scheduling. */
enum BlockRecordRejectionReason:
  /** The record was rejected for compatibility reasons. */
  case Compatibility(reason: CompatibilityReason)
  /** The record conflicts with an already-scheduled record. */
  case Conflict(stateRef: StateRef, kind: ConflictKind)

/** A block record that was rejected during conflict-free body selection.
  *
  * @tparam TxRef the transaction reference type
  * @tparam ResultRef the result reference type
  * @tparam Event the event type
  * @param record the rejected block record
  * @param reason the reason for rejection
  */
final case class RejectedBlockRecord[TxRef, ResultRef, Event](
    record: BlockRecord[TxRef, ResultRef, Event],
    reason: BlockRecordRejectionReason,
)

/** The result of selecting a conflict-free subset of block records for inclusion in a block body.
  *
  * @tparam TxRef the transaction reference type
  * @tparam ResultRef the result reference type
  * @tparam Event the event type
  * @param accepted records accepted into the block body
  * @param rejected records rejected due to conflicts or compatibility
  * @param aggregate the accumulated scheduling footprint
  */
final case class ConflictFreeBlockBodySelection[TxRef, ResultRef, Event](
    accepted: Vector[BlockRecord[TxRef, ResultRef, Event]],
    rejected: Vector[RejectedBlockRecord[TxRef, ResultRef, Event]],
    aggregate: AggregateFootprint,
):
  /** Returns the conflict footprint of the aggregate. */
  def aggregateFootprint =
    aggregate.footprint

  /** Converts the accepted records into a block body, failing if duplicates exist. */
  def toBody
      : Either[HotStuffValidationFailure, BlockBody[TxRef, ResultRef, Event]] =
    val body = BlockBody(accepted.toSet)
    Either.cond(
      body.records.sizeCompare(accepted) == 0,
      body,
      HotStuffValidationFailure.withoutDetail("duplicateSelectedBlockRecord"),
    )

/** Selects a conflict-free subset of candidate block records for block body inclusion. */
object ConflictFreeBlockBodySelector:

  /** Selects a conflict-free subset from the candidate records based on scheduling classification.
    *
    * @tparam TxRef the transaction reference type
    * @tparam ResultRef the result reference type
    * @tparam Event the event type
    * @param candidates the candidate block records
    * @param classifyTx classifies each transaction for scheduling
    * @return the selection result with accepted and rejected records
    */
  def select[TxRef, ResultRef, Event](
      candidates: Iterable[BlockRecord[TxRef, ResultRef, Event]],
  )(
      classifyTx: TxRef => SchedulingClassification,
  ): ConflictFreeBlockBodySelection[TxRef, ResultRef, Event] =
    candidates.iterator.foldLeft(
      ConflictFreeBlockBodySelection(
        accepted = Vector.empty[BlockRecord[TxRef, ResultRef, Event]],
        rejected = Vector.empty[RejectedBlockRecord[TxRef, ResultRef, Event]],
        aggregate = AggregateFootprint.empty,
      ),
    ): (selection, record) =>
      classifyTx(record.tx) match
        case SchedulingClassification.Compatibility(reason) =>
          selection.copy(
            rejected = selection.rejected :+ RejectedBlockRecord(
              record = record,
              reason = BlockRecordRejectionReason.Compatibility(reason),
            ),
          )
        case SchedulingClassification.Schedulable(footprint) =>
          selection.aggregate.accept(record, footprint) match
            case Left(conflict) =>
              selection.copy(
                rejected = selection.rejected :+ RejectedBlockRecord(
                  record = record,
                  reason = BlockRecordRejectionReason.Conflict(
                    stateRef = conflict.stateRef,
                    kind = conflict.kind,
                  ),
                ),
              )
            case Right(updatedAggregate) =>
              selection.copy(
                accepted = selection.accepted :+ record,
                aggregate = updatedAggregate,
              )

/** Validates block bodies for conflict-free scheduling compliance. */
object HotStuffBlockBodyVerifier:

  /** Validates that a block body contains no scheduling conflicts.
    *
    * @return unit on success or a validation failure
    */
  def validateBody[
      TxRef: ByteEncoder,
      ResultRef: ByteEncoder,
      Event: ByteEncoder,
  ](
      body: BlockBody[TxRef, ResultRef, Event],
  )(
      classifyTx: TxRef => SchedulingClassification,
  ): Either[HotStuffValidationFailure, Unit] =
    for
      entries <- BlockBody
        .canonicalEntries(body)
        .leftMap(fromBlockValidationFailure)
      _ <- entries
        .foldLeft(
          AggregateFootprint.empty.asRight[HotStuffValidationFailure],
        ): (aggregateEither, entry) =>
          aggregateEither.flatMap: aggregate =>
            val (recordHash, record) = entry
            classifyTx(record.tx) match
              case SchedulingClassification.Compatibility(reason) =>
                compatibilityFailure(recordHash, reason)
                  .asLeft[AggregateFootprint]
              case SchedulingClassification.Schedulable(footprint) =>
                aggregate
                  .accept(recordHash, footprint)
                  .leftMap: conflict =>
                    conflictFailure(
                      recordHash,
                      conflict.stateRef,
                      conflict.kind,
                    )
        .void
    yield ()

  /** Validates a complete block view (header + body) for scheduling compliance. */
  def validateView[
      TxRef: ByteEncoder,
      ResultRef: ByteEncoder,
      Event: ByteEncoder,
  ](
      view: BlockView[TxRef, ResultRef, Event],
  )(
      classifyTx: TxRef => SchedulingClassification,
  ): Either[HotStuffValidationFailure, Unit] =
    for
      _ <- BlockView
        .validate(view)
        .leftMap(fromBlockValidationFailure)
      _ <- validateBody(view.body)(classifyTx)
    yield ()

  private def fromBlockValidationFailure(
      failure: BlockValidationFailure,
  ): HotStuffValidationFailure =
    HotStuffValidationFailure(
      reason = failure.reason,
      detail = failure.detail,
    )

  private def compatibilityFailure(
      recordHash: BlockRecordHash,
      reason: CompatibilityReason,
  ): HotStuffValidationFailure =
    HotStuffValidationFailure(
      reason = "compatibilityTransactionInBlockBody",
      detail = Some:
        ss"record=${recordHash.toHexLower} reason=${reason.reason}${reason.detail
            .fold("")(detail => ss" detail=$detail")}",
    )

  private def conflictFailure(
      recordHash: BlockRecordHash,
      stateRef: StateRef,
      kind: ConflictKind,
  ): HotStuffValidationFailure =
    val kindLabel = kind match
      case ConflictKind.WriteWrite => "writeWrite"
      case ConflictKind.ReadWrite  => "readWrite"
    HotStuffValidationFailure(
      reason = "conflictingBlockBodyTransaction",
      detail = Some:
        ss"record=${recordHash.toHexLower} kind=$kindLabel stateRef=${stateRef.toHexLower}",
    )

/** Validates that a proposal's block view is consistent with the proposal and scheduling rules. */
object HotStuffProposalViewValidator:

  /** Validates the proposal, its block view, and scheduling compliance.
    *
    * @return unit on success or a validation failure
    */
  def validateProposalView[
      TxRef: ByteEncoder: Hash,
      ResultRef: ByteEncoder,
      Event: ByteEncoder,
  ](
      proposal: Proposal,
      view: BlockView[TxRef, ResultRef, Event],
      validatorSet: ValidatorSet,
  )(
      classifyTx: TxRef => SchedulingClassification,
  ): Either[HotStuffValidationFailure, Unit] =
    val proposalBlockId = BlockHeader.computeId(proposal.block)
    val viewBlockId     = BlockHeader.computeId(view.header)
    for
      _ <- HotStuffValidator.validateProposal(proposal, validatorSet)
      _ <- Either.cond(
        proposalBlockId === viewBlockId,
        (),
        HotStuffValidationFailure(
          reason = "proposalBlockViewMismatch",
          detail = Some:
            ss"proposal=${proposalBlockId.toHexLower} view=${viewBlockId.toHexLower}",
        ),
      )
      _ <- Either.cond(
        proposal.txSet === ProposalTxSet.fromTxs(
          view.body.records.toVector.map(_.tx),
        ),
        (),
        HotStuffValidationFailure(
          reason = "proposalTxSetMismatch",
          detail = Some(proposal.targetBlockId.toHexLower),
        ),
      )
      _ <- HotStuffBlockBodyVerifier.validateView(view)(classifyTx)
    yield ()
