package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import cats.syntax.all.*

import org.sigilaris.node.gossip.{
  CanonicalRejection,
  ControlBatch,
  ControlOp,
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

  private def controlRejected(
      reason: String,
      detail: String,
  ): CanonicalRejection.ControlBatchRejected =
    CanonicalRejection.ControlBatchRejected(
      reason = reason,
      detail = Some(detail),
    )
