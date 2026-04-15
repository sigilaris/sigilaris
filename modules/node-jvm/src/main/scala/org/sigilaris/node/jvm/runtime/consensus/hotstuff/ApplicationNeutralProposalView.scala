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
import org.sigilaris.node.gossip.StableArtifactId

/** Provides an application-neutral view over proposals, where the block body
  * is derived entirely from the carried transaction set without application-specific logic.
  */
object ApplicationNeutralProposalView:

  /** Type alias for block record result references in application-neutral proposals. */
  type ResultRef = Utf8

  /** Type alias for block record events in application-neutral proposals. */
  type Event     = Utf8
  private val LegacyAutomaticProposalBodyRootDomain =
    Utf8("sigilaris.hotstuff.auto-proposal.body-root.v1")

  private final case class LegacyAutomaticProposalSeed(
      domain: Utf8,
      chainId: org.sigilaris.node.gossip.ChainId,
      window: HotStuffWindow,
      leader: ValidatorId,
      highestKnownQc: QuorumCertificateSubject,
  ) derives ByteEncoder

  private def classifyTx(
      @annotation.unused txId: StableArtifactId,
  ): SchedulingClassification =
    SchedulingClassification.Schedulable(ConflictFootprint.empty)

  /** Constructs a canonical proposal transaction set from the given transaction IDs.
    *
    * @param txIds the transaction identifiers to include
    * @return a canonically ordered proposal transaction set
    */
  def proposalTxSet(
      txIds: Iterable[StableArtifactId],
  ): ProposalTxSet =
    ProposalTxSet.canonical(ProposalTxSet(txIds.iterator.toVector))

  /** Builds a block body from the given transaction IDs with empty results and events.
    *
    * @param txIds the transaction identifiers to include as block records
    * @return an application-neutral block body
    */
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

  /** Computes the body root hash for the given transaction IDs.
    *
    * @param txIds the transaction identifiers
    * @return the computed body root or a validation failure
    */
  def bodyRoot(
      txIds: Iterable[StableArtifactId],
  ): Either[BlockValidationFailure, BodyRoot] =
    BlockBody.computeBodyRoot(blockBody(txIds))

  /** Constructs a block view from a proposal using its header and derived body.
    *
    * @param proposal the consensus proposal
    * @return the application-neutral block view
    */
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

  /** Validates a proposal against the validator set and returns the derived block view.
    *
    * @param proposal the consensus proposal to validate
    * @param validatorSet the active validator set for validation
    * @return the validated block view or a validation failure
    */
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

  /** Creates a catch-up readiness evaluator that validates proposals using application-neutral body derivation.
    *
    * @tparam F the effect type
    * @param validatorSet the active validator set
    * @return a readiness evaluator for proposal catch-up
    */
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

  /** Parses an application-neutral transaction ID from raw bytes, requiring exactly 32 bytes.
    *
    * @param bytes the raw byte representation
    * @return the parsed stable artifact ID or an error message
    */
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
