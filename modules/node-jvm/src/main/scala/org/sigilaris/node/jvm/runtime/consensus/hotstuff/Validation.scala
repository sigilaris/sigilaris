package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.crypto.{CryptoOps, PublicKey, Signature}
import org.sigilaris.core.datatype.UInt256
import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.jvm.runtime.block.BlockHeader

enum VoteRecordOutcome:
  case Applied, Duplicate

final case class VoteAccumulator(
    votesById: Map[VoteId, Vote],
    votesByEquivocationKey: Map[EquivocationKey, Vote],
):
  def record(
      vote: Vote,
  ): Either[HotStuffValidationFailure, (VoteAccumulator, VoteRecordOutcome)] =
    votesById.get(vote.voteId) match
      case Some(_) =>
        (this -> VoteRecordOutcome.Duplicate).asRight[HotStuffValidationFailure]
      case None =>
        votesByEquivocationKey.get(vote.equivocationKey) match
          case Some(existing) if existing.targetProposalId =!= vote.targetProposalId =>
            HotStuffValidationFailure(
              reason = "equivocationDetected",
              detail = Some:
                ss"${vote.equivocationKey.validatorId.value}:${existing.targetProposalId.toHexLower}:${vote.targetProposalId.toHexLower}"
            ).asLeft[(VoteAccumulator, VoteRecordOutcome)]
          case Some(_) =>
            HotStuffValidationFailure(
              reason = "duplicateValidatorVote",
              detail = Some(vote.equivocationKey.validatorId.value),
            ).asLeft[(VoteAccumulator, VoteRecordOutcome)]
          case None =>
            (
              copy(
                votesById = votesById.updated(vote.voteId, vote),
                votesByEquivocationKey =
                  votesByEquivocationKey.updated(vote.equivocationKey, vote),
              ) -> VoteRecordOutcome.Applied
            ).asRight[HotStuffValidationFailure]

  def votesFor(
      window: HotStuffWindow,
      proposalId: ProposalId,
  ): Vector[Vote] =
    // The in-memory baseline keeps only per-vote maps, so this remains a full
    // scan. Production-backed sinks should index by `(window, proposalId)`
    // instead of depending on repeated linear reads here.
    votesById.values.toVector
      .filter(vote => vote.window === window && vote.targetProposalId === proposalId)

object VoteAccumulator:
  val empty: VoteAccumulator =
    VoteAccumulator(
      votesById = Map.empty[VoteId, Vote],
      votesByEquivocationKey = Map.empty[EquivocationKey, Vote],
    )

object QuorumCertificateAssembler:
  def assemble(
      subject: QuorumCertificateSubject,
      votes: Vector[Vote],
      validatorSet: ValidatorSet,
  ): Either[HotStuffValidationFailure, QuorumCertificate] =
    val deduplicated =
      votes
        .foldLeft((Set.empty[VoteId], Vector.empty[Vote])):
          case ((seen, acc), vote) if seen.contains(vote.voteId) =>
            seen -> acc
          case ((seen, acc), vote) =>
            (seen + vote.voteId) -> (acc :+ vote)
        ._2
    for
      byValidator <- deduplicated
        .foldLeft(Map.empty[ValidatorId, Vote].asRight[HotStuffValidationFailure]):
          case (Right(acc), vote) =>
            acc.get(vote.voter) match
              case Some(existing) if existing.voteId === vote.voteId =>
                acc.asRight[HotStuffValidationFailure]
              case Some(_) =>
                HotStuffValidationFailure(
                  reason = "duplicateValidatorVote",
                  detail = Some(vote.voter.value),
                ).asLeft[Map[ValidatorId, Vote]]
              case None =>
                HotStuffValidator
                  .validateVote(
                    vote,
                    validatorSet = validatorSet,
                    expectedWindow = Some(subject.window),
                    expectedProposalId = Some(subject.proposalId),
                  )
                  .map(_ => acc.updated(vote.voter, vote))
          case (left @ Left(_), _) =>
            left
      _ <- HotStuffValidationSupport.ensure(
        byValidator.sizeCompare(validatorSet.quorumSize) >= 0,
        "insufficientQuorum",
        Some:
          ss"required=${validatorSet.quorumSize.toString} actual=${byValidator.size.toString}"
      )
    yield QuorumCertificate(
      subject = subject,
      votes = byValidator.values.toVector.sortBy(_.voter.value),
    )

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
object HotStuffValidator:
  def validateProposal(
      proposal: Proposal,
      validatorSet: ValidatorSet,
  ): Either[HotStuffValidationFailure, Unit] =
    for
      _ <- HotStuffValidationSupport.ensure(
        proposal.window.validatorSetHash === validatorSet.hash,
        "validatorSetHashMismatch",
        Some(proposal.window.validatorSetHash.toHexLower),
      )
      proposer <- validatorSet
        .member(proposal.proposer)
        .toRight:
          HotStuffValidationFailure(
            reason = "unknownProposer",
            detail = Some(proposal.proposer.value),
          )
      _ <- HotStuffValidationSupport.ensure(
        proposal.block.height.toBigNat === proposal.window.height.toBigNat,
        "proposalBlockHeightMismatch",
        Some:
          ss"window=${proposal.window.height.render} block=${proposal.block.height.render}"
      )
      _ <- HotStuffValidationSupport.ensure(
        proposal.targetBlockId === BlockHeader.computeId(proposal.block),
        "targetBlockIdMismatch",
        Some(proposal.targetBlockId.toHexLower),
      )
      _ <- HotStuffValidationSupport.ensure(
        ProposalTxSet.isCanonical(proposal.txSet),
        "proposalTxSetNotCanonical",
        Some(proposal.targetBlockId.toHexLower),
      )
      _ <- HotStuffValidationSupport.ensure(
        proposal.proposalId === Proposal.recomputeId(proposal),
        "proposalIdMismatch",
        Some(proposal.proposalId.toHexLower),
      )
      // Phase 1 fixes a static validator-set baseline. Validator-set rotation
      // needs a follow-up seam so justify QC validation can use the historical
      // set active at the justify height/view instead of the current one.
      _ <- validateQuorumCertificate(proposal.justify, validatorSet)
      _ <- HotStuffValidationSupport.ensure(
        proposal.justify.subject.window.chainId === proposal.window.chainId,
        "justifyChainMismatch",
        Some(proposal.window.chainId.value),
      )
      _ <- HotStuffValidationSupport.ensure(
        // Genesis uses an explicit bootstrap QC baseline, so height 0 accepts
        // only a height-0 justification while later heights must strictly advance.
        if proposal.window.height === HotStuffHeight.Genesis then
          proposal.justify.subject.window.height === HotStuffHeight.Genesis
        else proposal.justify.subject.window.height < proposal.window.height,
        "justifyHeightNotProgressing",
        Some:
          ss"proposal=${proposal.window.height.render} justify=${proposal.justify.subject.window.height.render}"
      )
      _ <- HotStuffValidationSupport.ensure(
        if proposal.window.height === HotStuffHeight.Genesis then
          proposal.block.parent.isEmpty
        else proposal.block.parent.contains(proposal.justify.subject.blockId),
        "justifyBlockMismatch",
        Some(proposal.justify.subject.blockId.toHexLower),
      )
      _ <- verifySignature(
        Proposal.signBytes:
          UnsignedProposal(
            window = proposal.window,
            proposer = proposal.proposer,
            targetBlockId = proposal.targetBlockId,
            block = proposal.block,
            txSet = proposal.txSet,
            justify = proposal.justify,
          ),
        proposal.signature,
        proposer.publicKey,
        "proposalSignatureMismatch",
      )
    yield ()

  def validateVote(
      vote: Vote,
      validatorSet: ValidatorSet,
      expectedWindow: Option[HotStuffWindow] = None,
      expectedProposalId: Option[ProposalId] = None,
  ): Either[HotStuffValidationFailure, Unit] =
    for
      _ <- HotStuffValidationSupport.ensure(
        vote.window.validatorSetHash === validatorSet.hash,
        "validatorSetHashMismatch",
        Some(vote.window.validatorSetHash.toHexLower),
      )
      _ <- expectedWindow.traverse: window =>
        HotStuffValidationSupport.ensure(
          vote.window === window,
          "voteWindowMismatch",
          Some(ss"${vote.window.height.render}:${vote.window.view.render}"),
        )
      _ <- expectedProposalId.traverse: proposalId =>
        HotStuffValidationSupport.ensure(
          vote.targetProposalId === proposalId,
          "wrongTargetProposalId",
          Some(vote.targetProposalId.toHexLower),
        )
      voter <- validatorSet
        .member(vote.voter)
        .toRight:
          HotStuffValidationFailure(
            reason = "unknownVoter",
            detail = Some(vote.voter.value),
          )
      _ <- HotStuffValidationSupport.ensure(
        vote.voteId === Vote.recomputeId(vote),
        "voteIdMismatch",
        Some(vote.voteId.toHexLower),
      )
      _ <- verifySignature(
        Vote.signBytes:
          UnsignedVote(
            window = vote.window,
            voter = vote.voter,
            targetProposalId = vote.targetProposalId,
          ),
        vote.signature,
        voter.publicKey,
        "voteSignatureMismatch",
      )
    yield ()

  def validateQuorumCertificate(
      qc: QuorumCertificate,
      validatorSet: ValidatorSet,
  ): Either[HotStuffValidationFailure, Unit] =
    for
      _ <- HotStuffValidationSupport.ensure(
        qc.subject.window.validatorSetHash === validatorSet.hash,
        "validatorSetHashMismatch",
        Some(qc.subject.window.validatorSetHash.toHexLower),
      )
      _ <- QuorumCertificateAssembler
        .assemble(qc.subject, qc.votes, validatorSet)
        .void
    yield ()

  private def verifySignature(
      signBytes: ByteVector,
      signature: Signature,
      expectedPublicKey: PublicKey,
      mismatchReason: String,
  ): Either[HotStuffValidationFailure, Unit] =
    val messageHash = UInt256.unsafeFromBytesBE:
      ByteVector.view(CryptoOps.keccak256(signBytes.toArray))
    CryptoOps
      .recover(signature, messageHash.bytes.toArray)
      .leftMap: error =>
        HotStuffValidationFailure(
          reason = mismatchReason,
          detail = Some(error.toString),
        )
      .flatMap: recovered =>
        HotStuffValidationSupport.ensure(
          recovered === expectedPublicKey,
          mismatchReason,
          Some(expectedPublicKey.toString),
        )

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
private object HotStuffValidationSupport:
  def ensure(
      condition: Boolean,
      reason: String,
      detail: Option[String] = None,
  ): Either[HotStuffValidationFailure, Unit] =
    Either.cond(
      condition,
      (),
      HotStuffValidationFailure(reason = reason, detail = detail),
    )
