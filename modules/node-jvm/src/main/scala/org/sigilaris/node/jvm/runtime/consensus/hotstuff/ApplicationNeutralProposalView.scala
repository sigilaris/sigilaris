package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import cats.effect.kernel.Sync
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.application.scheduling.{
  ConflictFootprint,
  SchedulingClassification,
}
import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.crypto.{Hash, Hash as CryptoHash}
import org.sigilaris.core.datatype.{UInt256, Utf8}
import org.sigilaris.node.jvm.runtime.block.{
  BlockBody,
  BlockRecord,
  BlockValidationFailure,
  BlockView,
  BodyRoot,
}
import org.sigilaris.node.jvm.runtime.gossip.StableArtifactId

object ApplicationNeutralProposalView:
  type ResultRef = Utf8
  type Event     = Utf8
  private val LegacyAutomaticProposalBodyRootDomain =
    Utf8("sigilaris.hotstuff.auto-proposal.body-root.v1")

  private final case class LegacyAutomaticProposalSeed(
      domain: Utf8,
      chainId: org.sigilaris.node.jvm.runtime.gossip.ChainId,
      window: HotStuffWindow,
      leader: ValidatorId,
      highestKnownQc: QuorumCertificateSubject,
  ) derives ByteEncoder

  private def classifyTx(
      @annotation.unused txId: StableArtifactId,
  ): SchedulingClassification =
    SchedulingClassification.Schedulable(ConflictFootprint.empty)

  def proposalTxSet(
      txIds: Iterable[StableArtifactId],
  ): ProposalTxSet =
    ProposalTxSet.canonical(ProposalTxSet(txIds.iterator.toVector))

  def blockBody(
      txIds: Iterable[StableArtifactId],
  ): BlockBody[StableArtifactId, ResultRef, Event] =
    BlockBody(
      txIds.iterator
        .map(txId =>
          BlockRecord[StableArtifactId, ResultRef, Event](
            tx = txId,
            result = none[ResultRef],
            events = Vector.empty[Event],
          ),
        )
        .toSet,
    )

  def bodyRoot(
      txIds: Iterable[StableArtifactId],
  ): Either[BlockValidationFailure, BodyRoot] =
    BlockBody.computeBodyRoot(blockBody(txIds))

  def blockView(
      proposal: Proposal,
  ): BlockView[StableArtifactId, ResultRef, Event] =
    BlockView(
      header = proposal.block,
      body = blockBody(proposal.txSet.txIds),
    )

  private def ensureSupportedTxIds(
      txIds: Iterable[StableArtifactId],
  ): Either[HotStuffValidationFailure, Unit] =
    txIds.iterator
      .find(_.bytes.size =!= UInt256.Size.toLong)
      .fold(().asRight[HotStuffValidationFailure]): txId =>
        HotStuffValidationFailure(
          reason = "applicationNeutralTxIdUnsupported",
          detail = Some(txId.toHexLower),
        ).asLeft[Unit]

  private def ensureCanonicalTxSet(
      proposal: Proposal,
  ): Either[HotStuffValidationFailure, Unit] =
    Either.cond(
      ProposalTxSet.isCanonical(proposal.txSet),
      (),
      HotStuffValidationFailure(
        reason = "proposalTxSetNotCanonical",
        detail = Some(proposal.targetBlockId.toHexLower),
      ),
    )

  private def applicationNeutralBodyMatches(
      proposal: Proposal,
  ): Either[HotStuffValidationFailure, Boolean] =
    ensureCanonicalTxSet(proposal).flatMap: _ =>
      ensureSupportedTxIds(proposal.txSet.txIds).flatMap: _ =>
        bodyRoot(proposal.txSet.txIds)
          .leftMap(fromBlockValidation)
          .map(_ === proposal.block.bodyRoot)

  private def readyAssessment: ProposalCatchUpAssessment =
    ProposalCatchUpAssessment(
      voteReadiness = BootstrapVoteReadiness.Ready,
      controlBatch = None,
    )

  private def fromBlockValidation(
      failure: BlockValidationFailure,
  ): HotStuffValidationFailure =
    HotStuffValidationFailure(
      reason = failure.reason,
      detail = failure.detail,
    )

  private[hotstuff] def legacyAutomaticBodyRoot(
      window: HotStuffWindow,
      proposer: ValidatorId,
      justify: QuorumCertificate,
  ): BodyRoot =
    BodyRoot(
      UInt256.unsafeFromBytesBE(
        ByteVector.view(
          org.sigilaris.core.crypto.CryptoOps.keccak256(
            LegacyAutomaticProposalSeed(
              domain = LegacyAutomaticProposalBodyRootDomain,
              chainId = window.chainId,
              window = window,
              leader = proposer,
              highestKnownQc = justify.subject,
            ).toBytes.toArray,
          ),
        ),
      ),
    )

  private[hotstuff] def isLegacyAutomaticProposal(
      proposal: Proposal,
  ): Boolean =
    proposal.txSet === ProposalTxSet.empty &&
      proposal.block.bodyRoot === legacyAutomaticBodyRoot(
        proposal.window,
        proposal.proposer,
        proposal.justify,
      )

  def validate(
      proposal: Proposal,
      validatorSet: ValidatorSet,
  ): Either[HotStuffValidationFailure, BlockView[
    StableArtifactId,
    ResultRef,
    Event,
  ]] =
    ensureSupportedTxIds(proposal.txSet.txIds).flatMap: _ =>
      given Hash[StableArtifactId] = (txId: StableArtifactId) =>
        CryptoHash.Value[StableArtifactId](
          UInt256.unsafeFromBytesBE(txId.bytes),
        )
      val view = blockView(proposal)
      HotStuffProposalViewValidator
        .validateProposalView(
          proposal = proposal,
          view = view,
          validatorSet = validatorSet,
        )(classifyTx)
        .map(_ => view)

  def readiness[F[_]: Sync](
      validatorSet: ValidatorSet,
  ): ProposalCatchUpReadiness[F] =
    new ProposalCatchUpReadiness[F]:
      override def assess(
          proposal: Proposal,
      ): F[Either[BootstrapCoordinatorFailure, ProposalCatchUpAssessment]] =
        val result =
          if isLegacyAutomaticProposal(proposal) then
            readyAssessment.asRight[BootstrapCoordinatorFailure]
          else
            applicationNeutralBodyMatches(proposal)
              .leftMap(BootstrapCoordinatorFailure.fromValidation)
              .flatMap:
                case false =>
                  BootstrapCoordinatorFailure(
                    reason = "proposalBodyRootMismatch",
                    detail = None,
                  ).asLeft[ProposalCatchUpAssessment]
                case true =>
                  validate(proposal, validatorSet)
                    .map(_ => readyAssessment)
                    .leftMap(BootstrapCoordinatorFailure.fromValidation)
        result.pure[F]

  def txIdFromBytes(
      bytes: ByteVector,
  ): Either[String, StableArtifactId] =
    Either
      .cond(
        bytes.size == UInt256.Size.toLong,
        (),
        "application-neutral tx ids must be 32 bytes",
      )
      .flatMap(_ => StableArtifactId.fromBytes(bytes))
