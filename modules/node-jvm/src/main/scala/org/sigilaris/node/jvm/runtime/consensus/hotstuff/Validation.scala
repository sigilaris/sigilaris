package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.crypto.{CryptoOps, PublicKey, Signature}
import org.sigilaris.core.datatype.UInt256
import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.jvm.runtime.block.BlockHeader

/** Outcome of recording a vote into the accumulator. */
enum VoteRecordOutcome:
  /** The vote was successfully recorded. */
  case Applied
  /** The vote was already present. */
  case Duplicate

/** Outcome of recording a timeout vote into the accumulator. */
enum TimeoutVoteRecordOutcome:
  /** The timeout vote was successfully recorded. */
  case Applied
  /** The timeout vote was already present. */
  case Duplicate

/** Accumulates votes and detects equivocations (double-voting). */
final case class VoteAccumulator(
    votesById: Map[VoteId, Vote],
    votesByEquivocationKey: Map[EquivocationKey, Vote],
):
  /** Records a vote, detecting duplicates and equivocations. */
  def record(
      vote: Vote,
  ): Either[HotStuffValidationFailure, (VoteAccumulator, VoteRecordOutcome)] =
    votesById.get(vote.voteId) match
      case Some(_) =>
        (this -> VoteRecordOutcome.Duplicate).asRight[HotStuffValidationFailure]
      case None =>
        votesByEquivocationKey.get(vote.equivocationKey) match
          case Some(existing)
              if existing.targetProposalId =!= vote.targetProposalId =>
            HotStuffValidationFailure(
              reason = "equivocationDetected",
              detail = Some:
                ss"${vote.equivocationKey.validatorId.value}:${existing.targetProposalId.toHexLower}:${vote.targetProposalId.toHexLower}",
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

  /** Returns all votes for the given window and proposal ID. */
  def votesFor(
      window: HotStuffWindow,
      proposalId: ProposalId,
  ): Vector[Vote] =
    // The in-memory baseline keeps only per-vote maps, so this remains a full
    // scan. Production-backed sinks should index by `(window, proposalId)`
    // instead of depending on repeated linear reads here.
    votesById.values.toVector
      .filter: vote =>
        vote.window === window && vote.targetProposalId === proposalId

/** Companion for `VoteAccumulator`. */
object VoteAccumulator:
  /** An empty vote accumulator. */
  val empty: VoteAccumulator =
    VoteAccumulator(
      votesById = Map.empty[VoteId, Vote],
      votesByEquivocationKey = Map.empty[EquivocationKey, Vote],
    )

/** Accumulates timeout votes and detects equivocations. */
final case class TimeoutVoteAccumulator(
    votesById: Map[TimeoutVoteId, TimeoutVote],
    votesByEquivocationKey: Map[EquivocationKey, TimeoutVote],
):
  def record(
      vote: TimeoutVote,
  ): Either[
    HotStuffValidationFailure,
    (TimeoutVoteAccumulator, TimeoutVoteRecordOutcome),
  ] =
    votesById.get(vote.timeoutVoteId) match
      case Some(_) =>
        (
          this -> TimeoutVoteRecordOutcome.Duplicate
        ).asRight[HotStuffValidationFailure]
      case None =>
        votesByEquivocationKey.get(vote.equivocationKey) match
          case Some(existing) if existing.subject =!= vote.subject =>
            HotStuffValidationFailure(
              reason = "timeoutEquivocationDetected",
              detail = Some:
                ss"${vote.equivocationKey.validatorId.value}:${existing.subject.highestKnownQc.proposalId.toHexLower}:${vote.subject.highestKnownQc.proposalId.toHexLower}",
            ).asLeft[(TimeoutVoteAccumulator, TimeoutVoteRecordOutcome)]
          case Some(_) =>
            HotStuffValidationFailure(
              reason = "duplicateValidatorTimeoutVote",
              detail = Some(vote.equivocationKey.validatorId.value),
            ).asLeft[(TimeoutVoteAccumulator, TimeoutVoteRecordOutcome)]
          case None =>
            (
              copy(
                votesById = votesById.updated(vote.timeoutVoteId, vote),
                votesByEquivocationKey =
                  votesByEquivocationKey.updated(vote.equivocationKey, vote),
              ) -> TimeoutVoteRecordOutcome.Applied
            ).asRight[HotStuffValidationFailure]

  def votesFor(
      subject: TimeoutVoteSubject,
  ): Vector[TimeoutVote] =
    votesById.values.toVector.filter(_.subject === subject)

/** Companion for `TimeoutVoteAccumulator`. */
object TimeoutVoteAccumulator:
  /** An empty timeout vote accumulator. */
  val empty: TimeoutVoteAccumulator =
    TimeoutVoteAccumulator(
      votesById = Map.empty[TimeoutVoteId, TimeoutVote],
      votesByEquivocationKey = Map.empty[EquivocationKey, TimeoutVote],
    )

/** Assembles a quorum certificate from collected votes, verifying signatures and quorum threshold. */
object QuorumCertificateAssembler:
  /** Assembles a QC from votes for the given subject, verifying each vote and checking the quorum. */
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
        .foldLeft(
          Map.empty[ValidatorId, Vote].asRight[HotStuffValidationFailure],
        ):
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
          ss"required=${validatorSet.quorumSize.toString} actual=${byValidator.size.toString}",
      )
    yield QuorumCertificate(
      subject = subject,
      votes = byValidator.values.toVector.sortBy(_.voter.value),
    )

/** Assembles a timeout certificate from collected timeout votes, verifying signatures and quorum. */
object TimeoutCertificateAssembler:
  /** Assembles a TC from timeout votes for the given subject, verifying each vote and checking the quorum. */
  def assemble(
      subject: TimeoutVoteSubject,
      votes: Vector[TimeoutVote],
      validatorSet: ValidatorSet,
  ): Either[HotStuffValidationFailure, TimeoutCertificate] =
    val deduplicated =
      votes
        .foldLeft((Set.empty[TimeoutVoteId], Vector.empty[TimeoutVote])):
          case ((seen, acc), vote) if seen.contains(vote.timeoutVoteId) =>
            seen -> acc
          case ((seen, acc), vote) =>
            (seen + vote.timeoutVoteId) -> (acc :+ vote)
        ._2
    for
      byValidator <- deduplicated
        .foldLeft(
          Map
            .empty[ValidatorId, TimeoutVote]
            .asRight[HotStuffValidationFailure],
        ):
          case (Right(acc), vote) =>
            acc.get(vote.voter) match
              case Some(existing)
                  if existing.timeoutVoteId === vote.timeoutVoteId =>
                acc.asRight[HotStuffValidationFailure]
              case Some(existing) if existing.subject =!= vote.subject =>
                HotStuffValidationFailure(
                  reason = "timeoutEquivocationDetected",
                  detail = Some:
                    ss"${vote.voter.value}:${existing.subject.highestKnownQc.proposalId.toHexLower}:${vote.subject.highestKnownQc.proposalId.toHexLower}",
                ).asLeft[Map[ValidatorId, TimeoutVote]]
              case Some(_) =>
                HotStuffValidationFailure(
                  reason = "duplicateValidatorTimeoutVote",
                  detail = Some(vote.voter.value),
                ).asLeft[Map[ValidatorId, TimeoutVote]]
              case None =>
                HotStuffValidator
                  .validateTimeoutVote(
                    vote,
                    validatorSet = validatorSet,
                    expectedSubject = Some(subject),
                  )
                  .map(_ => acc.updated(vote.voter, vote))
          case (left @ Left(_), _) =>
            left
      _ <- HotStuffValidationSupport.ensure(
        byValidator.sizeCompare(validatorSet.quorumSize) >= 0,
        "insufficientTimeoutQuorum",
        Some:
          ss"required=${validatorSet.quorumSize.toString} actual=${byValidator.size.toString}",
      )
    yield TimeoutCertificate(
      subject = subject,
      votes = byValidator.values.toVector.sortBy(vote =>
        (vote.voter.value, vote.timeoutVoteId.toHexLower),
      ),
    )

/** Validates HotStuff consensus artifacts: proposals, votes, QCs, timeout votes, TCs, and new views. */
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
object HotStuffValidator:
  /** Validates a proposal's structure, signature, and justify chain against the validator set. */
  def validateProposal(
      proposal: Proposal,
      validatorSet: ValidatorSet,
      justifyValidatorSet: Option[ValidatorSet] = None,
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
          ss"window=${proposal.window.height.render} block=${proposal.block.height.render}",
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
      _ <- ProposalTxSet
        .firstUnsupportedTxId(proposal.txSet)
        .fold(().asRight[HotStuffValidationFailure]): txId =>
          HotStuffValidationFailure(
            reason = "proposalTxIdUnsupported",
            detail = Some(txId.toHexLower),
          ).asLeft[Unit]
      _ <- HotStuffValidationSupport.ensure(
        proposal.proposalId === Proposal.recomputeId(proposal),
        "proposalIdMismatch",
        Some(proposal.proposalId.toHexLower),
      )
      _ <- validateQuorumCertificate(
        proposal.justify,
        justifyValidatorSet.getOrElse(validatorSet),
      )
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
          ss"proposal=${proposal.window.height.render} justify=${proposal.justify.subject.window.height.render}",
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
          )
        ,
        proposal.signature,
        proposer.publicKey,
        "proposalSignatureMismatch",
      )
    yield ()

  /** Validates a vote's structure, signature, and membership in the validator set. */
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
          )
        ,
        vote.signature,
        voter.publicKey,
        "voteSignatureMismatch",
      )
    yield ()

  /** Validates a quorum certificate by re-assembling it from the contained votes. */
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

  /** Validates a timeout vote's structure, signature, and membership. */
  def validateTimeoutVote(
      vote: TimeoutVote,
      validatorSet: ValidatorSet,
      expectedSubject: Option[TimeoutVoteSubject] = None,
  ): Either[HotStuffValidationFailure, Unit] =
    for
      _ <- validateTimeoutVoteSubject(vote.subject, validatorSet)
      _ <- expectedSubject.traverse: subject =>
        HotStuffValidationSupport.ensure(
          vote.subject === subject,
          "wrongTimeoutVoteSubject",
          Some(vote.subject.highestKnownQc.proposalId.toHexLower),
        )
      voter <- validatorSet
        .member(vote.voter)
        .toRight:
          HotStuffValidationFailure(
            reason = "unknownTimeoutVoter",
            detail = Some(vote.voter.value),
          )
      _ <- HotStuffValidationSupport.ensure(
        vote.timeoutVoteId === TimeoutVote.recomputeId(vote),
        "timeoutVoteIdMismatch",
        Some(vote.timeoutVoteId.toHexLower),
      )
      _ <- verifySignature(
        TimeoutVote.signBytes:
          UnsignedTimeoutVote(
            subject = vote.subject,
            voter = vote.voter,
          )
        ,
        vote.signature,
        voter.publicKey,
        "timeoutVoteSignatureMismatch",
      )
    yield ()

  /** Validates a timeout certificate by re-assembling it from the contained votes. */
  def validateTimeoutCertificate(
      timeoutCertificate: TimeoutCertificate,
      validatorSet: ValidatorSet,
  ): Either[HotStuffValidationFailure, Unit] =
    for
      _ <- validateTimeoutVoteSubject(timeoutCertificate.subject, validatorSet)
      _ <- TimeoutCertificateAssembler
        .assemble(
          timeoutCertificate.subject,
          timeoutCertificate.votes,
          validatorSet,
        )
        .void
    yield ()

  /** Validates a new-view message's structure, signature, QC, TC, and window consistency. */
  def validateNewView(
      newView: NewView,
      validatorSet: ValidatorSet,
  ): Either[HotStuffValidationFailure, Unit] =
    for
      _ <- HotStuffValidationSupport.ensure(
        newView.window.validatorSetHash === validatorSet.hash,
        "validatorSetHashMismatch",
        Some(newView.window.validatorSetHash.toHexLower),
      )
      sender <- validatorSet
        .member(newView.sender)
        .toRight:
          HotStuffValidationFailure(
            reason = "unknownNewViewSender",
            detail = Some(newView.sender.value),
          )
      _ <- validatorSet
        .member(newView.nextLeader)
        .toRight:
          HotStuffValidationFailure(
            reason = "unknownNewViewLeader",
            detail = Some(newView.nextLeader.value),
          )
      _ <- validateQuorumCertificate(newView.highestKnownQc, validatorSet)
      _ <- validateTimeoutCertificate(newView.timeoutCertificate, validatorSet)
      _ <- HotStuffValidationSupport.ensure(
        newView.timeoutCertificate.subject.highestKnownQc === newView.highestKnownQc.subject,
        "newViewHighestQcMismatch",
        Some(newView.highestKnownQc.subject.proposalId.toHexLower),
      )
      _ <- HotStuffValidationSupport.ensure(
        newView.window.chainId === newView.timeoutCertificate.subject.window.chainId &&
          newView.window.height === newView.timeoutCertificate.subject.window.height &&
          newView.window.view === newView.timeoutCertificate.subject.window.view.next &&
          newView.window.validatorSetHash === newView.timeoutCertificate.subject.window.validatorSetHash,
        "newViewWindowMismatch",
        Some(
          ss"${newView.window.height.render}:${newView.window.view.render}",
        ),
      )
      _ <- HotStuffValidationSupport.ensure(
        newView.highestKnownQc.subject.window.chainId === newView.window.chainId &&
          newView.highestKnownQc.subject.window.validatorSetHash === newView.window.validatorSetHash &&
          newView.highestKnownQc.subject.window.height <= newView.window.height,
        "newViewHighestQcMismatch",
        Some(newView.highestKnownQc.subject.proposalId.toHexLower),
      )
      _ <- HotStuffValidationSupport.ensure(
        newView.nextLeader ===
          HotStuffPacemaker.deterministicLeader(newView.window, validatorSet),
        "newViewLeaderMismatch",
        Some(newView.nextLeader.value),
      )
      _ <- HotStuffValidationSupport.ensure(
        newView.newViewId === NewView.recomputeId(newView),
        "newViewIdMismatch",
        Some(newView.newViewId.toHexLower),
      )
      _ <- verifySignature(
        NewView.signBytes:
          UnsignedNewView(
            window = newView.window,
            sender = newView.sender,
            nextLeader = newView.nextLeader,
            highestKnownQc = newView.highestKnownQc,
            timeoutCertificate = newView.timeoutCertificate,
          )
        ,
        newView.signature,
        sender.publicKey,
        "newViewSignatureMismatch",
      )
    yield ()

  private def validateTimeoutVoteSubject(
      subject: TimeoutVoteSubject,
      validatorSet: ValidatorSet,
  ): Either[HotStuffValidationFailure, Unit] =
    for
      _ <- HotStuffValidationSupport.ensure(
        subject.window.validatorSetHash === validatorSet.hash,
        "validatorSetHashMismatch",
        Some(subject.window.validatorSetHash.toHexLower),
      )
      _ <- HotStuffValidationSupport.ensure(
        subject.highestKnownQc.window.chainId === subject.window.chainId,
        "timeoutVoteHighestQcChainMismatch",
        Some(subject.window.chainId.value),
      )
      _ <- HotStuffValidationSupport.ensure(
        subject.highestKnownQc.window.validatorSetHash === subject.window.validatorSetHash,
        "timeoutVoteHighestQcValidatorSetMismatch",
        Some(subject.highestKnownQc.window.validatorSetHash.toHexLower),
      )
      _ <- HotStuffValidationSupport.ensure(
        subject.highestKnownQc.window.height < subject.window.height ||
          (
            subject.highestKnownQc.window.height === subject.window.height &&
              subject.highestKnownQc.window.view < subject.window.view
          ),
        "timeoutVoteHighestQcWindowMismatch",
        Some:
          ss"${subject.highestKnownQc.window.height.render}:${subject.highestKnownQc.window.view.render}",
      )
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
