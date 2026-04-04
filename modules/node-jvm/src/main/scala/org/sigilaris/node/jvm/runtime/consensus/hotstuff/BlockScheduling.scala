package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import cats.syntax.all.*

import org.sigilaris.core.application.scheduling.{AggregateFootprint, CompatibilityReason, ConflictKind, SchedulingClassification, StateRef}
import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.crypto.Hash
import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.jvm.runtime.block.{BlockBody, BlockHeader, BlockRecord, BlockRecordHash, BlockValidationFailure, BlockView}

enum BlockRecordRejectionReason:
  case Compatibility(reason: CompatibilityReason)
  case Conflict(stateRef: StateRef, kind: ConflictKind)

final case class RejectedBlockRecord[TxRef, ResultRef, Event](
    record: BlockRecord[TxRef, ResultRef, Event],
    reason: BlockRecordRejectionReason,
)

final case class ConflictFreeBlockBodySelection[TxRef, ResultRef, Event](
    accepted: Vector[BlockRecord[TxRef, ResultRef, Event]],
    rejected: Vector[RejectedBlockRecord[TxRef, ResultRef, Event]],
    aggregate: AggregateFootprint,
):
  def aggregateFootprint =
    aggregate.footprint

  def toBody: Either[HotStuffValidationFailure, BlockBody[TxRef, ResultRef, Event]] =
    val body = BlockBody(accepted.toSet)
    Either.cond(
      body.records.sizeCompare(accepted) == 0,
      body,
      HotStuffValidationFailure.withoutDetail("duplicateSelectedBlockRecord"),
    )

object ConflictFreeBlockBodySelector:
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
      )
    ): (selection, record) =>
      classifyTx(record.tx) match
        case SchedulingClassification.Compatibility(reason) =>
          selection.copy(
            rejected =
              selection.rejected :+ RejectedBlockRecord(
                record = record,
                reason = BlockRecordRejectionReason.Compatibility(reason),
              ),
          )
        case SchedulingClassification.Schedulable(footprint) =>
          selection.aggregate.accept(record, footprint) match
            case Left(conflict) =>
              selection.copy(
                rejected =
                  selection.rejected :+ RejectedBlockRecord(
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

object HotStuffBlockBodyVerifier:
  def validateBody[TxRef: ByteEncoder, ResultRef: ByteEncoder, Event: ByteEncoder](
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
                compatibilityFailure(recordHash, reason).asLeft[AggregateFootprint]
              case SchedulingClassification.Schedulable(footprint) =>
                aggregate.accept(recordHash, footprint).leftMap: conflict =>
                  conflictFailure(recordHash, conflict.stateRef, conflict.kind)
        .void
    yield ()

  def validateView[TxRef: ByteEncoder, ResultRef: ByteEncoder, Event: ByteEncoder](
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
        ss"record=${recordHash.toHexLower} reason=${reason.reason}${reason.detail.fold("")(detail => ss" detail=$detail")}"
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
        ss"record=${recordHash.toHexLower} kind=$kindLabel stateRef=${stateRef.toHexLower}"
    )

object HotStuffProposalViewValidator:
  def validateProposalView[TxRef: ByteEncoder: Hash, ResultRef: ByteEncoder, Event: ByteEncoder](
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
            ss"proposal=${proposalBlockId.toHexLower} view=${viewBlockId.toHexLower}"
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
