package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import cats.syntax.all.*

import org.sigilaris.node.gossip.{
  CanonicalRejection,
  ControlBatch,
  ControlOp,
  ExactKnownSetScope,
  StableArtifactId,
}
import org.sigilaris.node.gossip.tx.TxRuntimePolicy

/** Utilities for synchronizing transaction payloads referenced by consensus proposals. */
object HotStuffProposalTxSync:
  /** Returns the transaction IDs from the proposal that are not in the known set. */
  def missingTxIds(
      proposal: Proposal,
      knownTxIds: Set[StableArtifactId],
  ): Vector[StableArtifactId] =
    proposal.txSet.txIds.filterNot(knownTxIds.contains)

  /** Builds a gossip control batch to request missing transaction payloads for a proposal. */
  def controlBatchForProposal(
      proposal: Proposal,
      knownTxIds: Set[StableArtifactId],
      idempotencyKey: String,
      txPolicy: TxRuntimePolicy,
  ): Either[CanonicalRejection.ControlBatchRejected, Option[ControlBatch]] =
    // This helper assumes the caller already accepted the proposal through the
    // normal validation path, including tx-set canonicality.
    val txIds       = proposal.txSet.txIds
    val missingTxId = missingTxIds(proposal, knownTxIds)
    if txIds.isEmpty then
      none[ControlBatch].asRight[CanonicalRejection.ControlBatchRejected]
    else if txIds.sizeIs > txPolicy.maxTxSetKnownEntries then
      controlRejected(
        "setKnownTooLarge",
        "max=" + txPolicy.maxTxSetKnownEntries.toString + " actual=" + txIds.size.toString,
      ).asLeft[Option[ControlBatch]]
    else if missingTxId.sizeIs > txPolicy.maxTxRequestIds then
      controlRejected(
        "requestByIdTooLarge",
        "max=" + txPolicy.maxTxRequestIds.toString + " actual=" + missingTxId.size.toString,
      ).asLeft[Option[ControlBatch]]
    else
      val ops =
        Vector(ControlOp.SetKnownTx(proposal.window.chainId, txIds)) ++
          (if missingTxId.nonEmpty then
             Vector(
               ControlOp.RequestByIdTx(proposal.window.chainId, missingTxId),
             )
           else Vector.empty[ControlOp])
      ControlBatch.create(idempotencyKey, ops).map(_.some)

  /** Builds staged exact-known-set request batches for application topics whose
    * stable IDs are referenced by a HotStuff proposal tx set.
    *
    * The helper emits only bounded `RequestByIdExact` operations. It does not
    * publish `SetKnownExact` updates for proposal tx IDs.
    */
  def controlBatchesForProposalOnExactTopics(
      proposal: Proposal,
      knownTxIds: Set[StableArtifactId],
      idempotencyKeyForBatch: Int => String,
      scopeForId: StableArtifactId => Either[
        CanonicalRejection.ControlBatchRejected,
        ExactKnownSetScope,
      ],
      requestByIdLimitForScope: ExactKnownSetScope => Either[
        CanonicalRejection.ControlBatchRejected,
        Int,
      ],
  ): Either[CanonicalRejection.ControlBatchRejected, Vector[ControlBatch]] =
    val missing = missingTxIds(proposal, knownTxIds)
    missing
      .traverse(id => scopeForId(id).map(_ -> id))
      .flatMap: scopedIds =>
        val grouped =
          scopedIds.foldLeft(
            Vector.empty[(ExactKnownSetScope, Vector[StableArtifactId])],
          ):
            case (acc, (scope, id)) =>
              val index = acc.indexWhere(_._1 === scope)
              if index < 0 then acc :+ (scope -> Vector(id))
              else
                acc.updated(
                  index,
                  scope -> (acc(index)._2 :+ id),
                )
        grouped
          .traverse: (scope, ids) =>
            requestByIdLimitForScope(scope).flatMap: limit =>
              Either
                .cond(
                  limit > 0,
                  ids.grouped(limit).toVector.map(chunk =>
                    ControlOp.RequestByIdExact(scope, chunk),
                  ),
                  controlRejected(
                    "invalidRequestByIdLimit",
                    "scope=" + scope.topic.value + " limit=" + limit.toString,
                  ),
                )
          .map(_.flatten)
          .flatMap: ops =>
            ops.zipWithIndex.traverse: (op, index) =>
              ControlBatch.create(idempotencyKeyForBatch(index), Vector(op))

  private def controlRejected(
      reason: String,
      detail: String,
  ): CanonicalRejection.ControlBatchRejected =
    CanonicalRejection.ControlBatchRejected(
      reason = reason,
      detail = Some(detail),
    )
