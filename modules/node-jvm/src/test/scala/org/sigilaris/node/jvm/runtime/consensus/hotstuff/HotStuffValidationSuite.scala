package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Instant

import munit.FunSuite
import scodec.bits.ByteVector

import org.sigilaris.core.crypto.CryptoOps
import org.sigilaris.core.datatype.UInt256
import org.sigilaris.node.jvm.runtime.block.{
  BlockHeader,
  BlockHeight,
  BlockTimestamp,
  BodyRoot,
  StateRoot,
}
import org.sigilaris.node.gossip.{
  ChainId,
  CursorToken,
  GossipEvent,
  GossipTopic,
  StableArtifactId,
}

final class HotStuffValidationSuite extends FunSuite:

  private val chainId       = ChainId.unsafe("chain-main")
  private val validatorKeys = Vector.fill(4)(CryptoOps.generate())
  private val validatorSet = ValidatorSet.unsafe(
    validatorKeys.zipWithIndex.map: (keyPair, index) =>
      ValidatorMember(
        id = ValidatorId.unsafe(s"validator-${index + 1}"),
        publicKey = keyPair.publicKey,
      ),
  )

  test(
    "proposal and vote sign bytes are independent from gossip envelope fields",
  ):
    val proposal = signedProposal()
    val vote     = signedVote(proposal, voterIndex = 1)

    val proposalEventA = gossipEvent(
      topic = GossipTopic.unsafe("consensus.proposal"),
      idHex = "01",
      cursorValue = 1L,
      payload = proposal,
    )
    val proposalEventB = gossipEvent(
      topic = GossipTopic.unsafe("audit"),
      idHex = "02",
      cursorValue = 9L,
      payload = proposal,
    )
    val voteEventA = gossipEvent(
      topic = GossipTopic.unsafe("consensus.vote"),
      idHex = "03",
      cursorValue = 2L,
      payload = vote,
    )
    val voteEventB = gossipEvent(
      topic = GossipTopic.unsafe("replay"),
      idHex = "04",
      cursorValue = 12L,
      payload = vote,
    )

    assertEquals(
      Proposal.signBytes(unsignedProposal(proposalEventA.payload)),
      Proposal.signBytes(unsignedProposal(proposalEventB.payload)),
    )
    assertEquals(
      Vote.signBytes(unsignedVote(voteEventA.payload)),
      Vote.signBytes(unsignedVote(voteEventB.payload)),
    )

  test(
    "proposal and vote signatures validate and proposal id differs from block id",
  ):
    val proposal = signedProposal()
    val vote     = signedVote(proposal, voterIndex = 1)

    assertEquals(
      HotStuffValidator.validateProposal(proposal, validatorSet),
      Right(()),
    )
    assertEquals(
      HotStuffValidator.validateVote(
        vote,
        validatorSet = validatorSet,
        expectedWindow = Some(proposal.window),
        expectedProposalId = Some(proposal.proposalId),
      ),
      Right(()),
    )
    assertNotEquals(
      proposal.proposalId.toHexLower,
      proposal.targetBlockId.toHexLower,
    )

  test("proposal and vote validation reject wrong signers"):
    val validProposal = signedProposal()
    val forgedProposal =
      Proposal
        .sign(
          unsignedProposal(validProposal),
          validatorKeys(1),
        )
        .toOption
        .get
    val forgedVote =
      Vote
        .sign(
          unsignedVote(signedVote(validProposal, voterIndex = 1)),
          validatorKeys(2),
        )
        .toOption
        .get

    assertEquals(
      HotStuffValidator
        .validateProposal(forgedProposal, validatorSet)
        .left
        .map(_.reason),
      Left("proposalSignatureMismatch"),
    )
    assertEquals(
      HotStuffValidator
        .validateVote(
          forgedVote,
          validatorSet = validatorSet,
          expectedWindow = Some(validProposal.window),
          expectedProposalId = Some(validProposal.proposalId),
        )
        .left
        .map(_.reason),
      Left("voteSignatureMismatch"),
    )

  test(
    "proposal and vote validation reject unknown validators and tampered ids",
  ):
    val proposal = signedProposal()
    val unknownProposer =
      proposal.copy(
        proposer = ValidatorId.unsafe("validator-unknown"),
        proposalId = Proposal.recomputeId(
          proposal.copy(proposer = ValidatorId.unsafe("validator-unknown")),
        ),
      )
    val unknownVoter =
      Vote
        .sign(
          UnsignedVote(
            window = proposal.window,
            voter = ValidatorId.unsafe("validator-unknown"),
            targetProposalId = proposal.proposalId,
          ),
          validatorKeys.head,
        )
        .toOption
        .get
    val tamperedProposalId =
      proposal.copy(proposalId = ProposalId(hex("ab")))
    val baseVote = signedVote(proposal, voterIndex = 1)
    val voteWithWrongValidatorSetHash =
      baseVote.copy(
        window =
          baseVote.window.copy(validatorSetHash = ValidatorSetHash(hex("cd"))),
      )
    val tamperedVoteId =
      baseVote.copy(voteId = VoteId(hex("ef")))

    assertEquals(
      HotStuffValidator
        .validateProposal(unknownProposer, validatorSet)
        .left
        .map(_.reason),
      Left("unknownProposer"),
    )
    assertEquals(
      HotStuffValidator
        .validateVote(
          unknownVoter,
          validatorSet = validatorSet,
          expectedWindow = Some(proposal.window),
          expectedProposalId = Some(proposal.proposalId),
        )
        .left
        .map(_.reason),
      Left("unknownVoter"),
    )
    assertEquals(
      HotStuffValidator
        .validateProposal(tamperedProposalId, validatorSet)
        .left
        .map(_.reason),
      Left("proposalIdMismatch"),
    )
    assertEquals(
      HotStuffValidator
        .validateVote(
          voteWithWrongValidatorSetHash,
          validatorSet = validatorSet,
          expectedWindow = Some(proposal.window),
          expectedProposalId = Some(proposal.proposalId),
        )
        .left
        .map(_.reason),
      Left("validatorSetHashMismatch"),
    )
    assertEquals(
      HotStuffValidator
        .validateVote(
          tamperedVoteId,
          validatorSet = validatorSet,
          expectedWindow = Some(proposal.window),
          expectedProposalId = Some(proposal.proposalId),
        )
        .left
        .map(_.reason),
      Left("voteIdMismatch"),
    )

  test(
    "vote accumulator deduplicates exact duplicates and rejects equivocation",
  ):
    val proposal = signedProposal()
    val vote     = signedVote(proposal, voterIndex = 1)
    val equivocated =
      Vote
        .sign(
          UnsignedVote(
            window = proposal.window,
            voter = validatorSet.members(1).id,
            targetProposalId = ProposalId(hex("aa")),
          ),
          validatorKeys(1),
        )
        .toOption
        .get

    val first  = VoteAccumulator.empty.record(vote)
    val second = first.toOption.get._1.record(vote)
    val third  = first.toOption.get._1.record(equivocated)

    assertEquals(first.map(_._2), Right(VoteRecordOutcome.Applied))
    assertEquals(second.map(_._2), Right(VoteRecordOutcome.Duplicate))
    assertEquals(third.left.map(_.reason), Left("equivocationDetected"))

  test(
    "vote accumulator rejects duplicate validator votes with different vote ids for the same proposal",
  ):
    val proposal = signedProposal()
    val vote     = signedVote(proposal, voterIndex = 1)
    val duplicateValidator =
      vote.copy(voteId = VoteId(hex("beef")))

    val result =
      VoteAccumulator.empty
        .record(vote)
        .toOption
        .get
        ._1
        .record(duplicateValidator)

    assertEquals(result.left.map(_.reason), Left("duplicateValidatorVote"))

  test(
    "timeout vote and new-view signatures validate with deterministic leader rotation",
  ):
    val proposal = signedProposal()
    val timeoutVoteA =
      signedTimeoutVoteFor(
        proposal.window,
        proposal.justify,
        voterIndex = 0,
      )
    val timeoutVoteB =
      signedTimeoutVoteFor(
        proposal.window,
        proposal.justify,
        voterIndex = 1,
      )
    val timeoutVoteC =
      signedTimeoutVoteFor(
        proposal.window,
        proposal.justify,
        voterIndex = 2,
      )
    val timeoutCertificate =
      TimeoutCertificateAssembler
        .assemble(
          timeoutVoteA.subject,
          Vector(timeoutVoteA, timeoutVoteB, timeoutVoteC),
          validatorSet,
        )
        .toOption
        .get
    val nextWindow =
      HotStuffPacemaker.nextWindowAfter(timeoutCertificate.subject.window)
    val expectedLeader =
      HotStuffPacemaker.deterministicLeader(nextWindow, validatorSet)
    val newView =
      NewView
        .sign(
          UnsignedNewView(
            window = nextWindow,
            sender = validatorSet.members.head.id,
            nextLeader = expectedLeader,
            highestKnownQc = proposal.justify,
            timeoutCertificate = timeoutCertificate,
          ),
          validatorKeys.head,
        )
        .toOption
        .get

    assertEquals(
      HotStuffValidator.validateTimeoutVote(timeoutVoteA, validatorSet),
      Right(()),
    )
    assertEquals(
      HotStuffValidator.validateTimeoutCertificate(
        timeoutCertificate,
        validatorSet,
      ),
      Right(()),
    )
    assertEquals(
      HotStuffValidator.validateNewView(newView, validatorSet),
      Right(()),
    )
    assertEquals(newView.nextLeader, expectedLeader)

  test(
    "new-view sign bytes stay stable across quorum and timeout certificate duplicate ordering",
  ):
    val proposal = signedProposal()
    val qcVoteA =
      signedVoteFor(
        proposal.justify.subject.window,
        proposal.justify.subject.proposalId,
        voterIndex = 0,
      )
    val qcVoteB =
      signedVoteFor(
        proposal.justify.subject.window,
        proposal.justify.subject.proposalId,
        voterIndex = 1,
      )
    val qcVoteC =
      signedVoteFor(
        proposal.justify.subject.window,
        proposal.justify.subject.proposalId,
        voterIndex = 2,
      )
    val qcDuplicateLow =
      qcVoteA.copy(voteId = VoteId(hex("01")))
    val qcDuplicateHigh =
      qcVoteA.copy(voteId = VoteId(hex("ff")))
    val timeoutVoteA =
      signedTimeoutVoteFor(
        proposal.window,
        proposal.justify,
        voterIndex = 0,
      )
    val timeoutVoteB =
      signedTimeoutVoteFor(
        proposal.window,
        proposal.justify,
        voterIndex = 1,
      )
    val timeoutVoteC =
      signedTimeoutVoteFor(
        proposal.window,
        proposal.justify,
        voterIndex = 2,
      )
    val timeoutDuplicateLow =
      timeoutVoteA.copy(timeoutVoteId = TimeoutVoteId(hex("01")))
    val timeoutDuplicateHigh =
      timeoutVoteA.copy(timeoutVoteId = TimeoutVoteId(hex("ff")))
    val nextWindow =
      HotStuffPacemaker.nextWindowAfter(proposal.window)
    val nextLeader =
      HotStuffPacemaker.deterministicLeader(nextWindow, validatorSet)
    val unsignedA =
      UnsignedNewView(
        window = nextWindow,
        sender = validatorSet.members.head.id,
        nextLeader = nextLeader,
        highestKnownQc = QuorumCertificate(
          proposal.justify.subject,
          Vector(qcDuplicateHigh, qcVoteC, qcVoteB, qcDuplicateLow),
        ),
        timeoutCertificate = TimeoutCertificate(
          TimeoutVoteSubject(
            window = proposal.window,
            highestKnownQc = proposal.justify.subject,
          ),
          Vector(
            timeoutDuplicateHigh,
            timeoutVoteC,
            timeoutVoteB,
            timeoutDuplicateLow,
          ),
        ),
      )
    val unsignedB =
      unsignedA.copy(
        highestKnownQc = QuorumCertificate(
          proposal.justify.subject,
          Vector(qcVoteB, qcDuplicateLow, qcDuplicateHigh, qcVoteC),
        ),
        timeoutCertificate = TimeoutCertificate(
          TimeoutVoteSubject(
            window = proposal.window,
            highestKnownQc = proposal.justify.subject,
          ),
          Vector(
            timeoutVoteB,
            timeoutDuplicateLow,
            timeoutDuplicateHigh,
            timeoutVoteC,
          ),
        ),
      )

    assertEquals(NewView.signBytes(unsignedA), NewView.signBytes(unsignedB))

  test(
    "timeout vote accumulator deduplicates exact duplicates and rejects timeout equivocation",
  ):
    val proposal = signedProposal()
    val timeoutVote =
      signedTimeoutVoteFor(
        proposal.window,
        proposal.justify,
        voterIndex = 0,
      )
    val equivocated =
      TimeoutVote
        .sign(
          UnsignedTimeoutVote(
            subject = TimeoutVoteSubject(
              window = proposal.window,
              highestKnownQc = proposal.justify.subject.copy(
                proposalId = ProposalId(hex("ac")),
                blockId = BlockId(hex("ad")),
              ),
            ),
            voter = validatorSet.members.head.id,
          ),
          validatorKeys.head,
        )
        .toOption
        .get

    val first  = TimeoutVoteAccumulator.empty.record(timeoutVote)
    val second = first.toOption.get._1.record(timeoutVote)
    val third  = first.toOption.get._1.record(equivocated)

    assertEquals(first.map(_._2), Right(TimeoutVoteRecordOutcome.Applied))
    assertEquals(second.map(_._2), Right(TimeoutVoteRecordOutcome.Duplicate))
    assertEquals(third.left.map(_.reason), Left("timeoutEquivocationDetected"))

  test(
    "new-view validation rejects wrong next leader and mismatched highest qc proof",
  ):
    val proposal = signedProposal()
    val timeoutCertificate =
      TimeoutCertificateAssembler
        .assemble(
          TimeoutVoteSubject(
            window = proposal.window,
            highestKnownQc = proposal.justify.subject,
          ),
          Vector(
            signedTimeoutVoteFor(proposal.window, proposal.justify, 0),
            signedTimeoutVoteFor(proposal.window, proposal.justify, 1),
            signedTimeoutVoteFor(proposal.window, proposal.justify, 2),
          ),
          validatorSet,
        )
        .toOption
        .get
    val nextWindow =
      HotStuffPacemaker.nextWindowAfter(timeoutCertificate.subject.window)
    val wrongLeaderNewView =
      NewView
        .sign(
          UnsignedNewView(
            window = nextWindow,
            sender = validatorSet.members.head.id,
            nextLeader = validatorSet.members.last.id,
            highestKnownQc = proposal.justify,
            timeoutCertificate = timeoutCertificate,
          ),
          validatorKeys.head,
        )
        .toOption
        .get
    val mismatchedSubject = proposal.justify.subject.copy(
      proposalId = ProposalId(hex("ba")),
      blockId = BlockId(hex("bb")),
    )
    val mismatchedHighestQc =
      QuorumCertificateAssembler
        .assemble(
          subject = mismatchedSubject,
          votes = Vector(
            signedVoteFor(
              mismatchedSubject.window,
              mismatchedSubject.proposalId,
              0,
            ),
            signedVoteFor(
              mismatchedSubject.window,
              mismatchedSubject.proposalId,
              1,
            ),
            signedVoteFor(
              mismatchedSubject.window,
              mismatchedSubject.proposalId,
              2,
            ),
          ),
          validatorSet = validatorSet,
        )
        .toOption
        .get
    val mismatchedNewView =
      NewView
        .sign(
          UnsignedNewView(
            window = nextWindow,
            sender = validatorSet.members.head.id,
            nextLeader =
              HotStuffPacemaker.deterministicLeader(nextWindow, validatorSet),
            highestKnownQc = mismatchedHighestQc,
            timeoutCertificate = timeoutCertificate,
          ),
          validatorKeys.head,
        )
        .toOption
        .get

    assertEquals(
      HotStuffValidator
        .validateNewView(wrongLeaderNewView, validatorSet)
        .left
        .map(_.reason),
      Left("newViewLeaderMismatch"),
    )
    assertEquals(
      HotStuffValidator
        .validateNewView(mismatchedNewView, validatorSet)
        .left
        .map(_.reason),
      Left("newViewHighestQcMismatch"),
    )

  test(
    "timeout certificate validation rejects insufficient quorum, duplicate validators, and unknown timeout voters",
  ):
    val proposal = signedProposal()
    val subject = TimeoutVoteSubject(
      window = proposal.window,
      highestKnownQc = proposal.justify.subject,
    )
    val timeoutVoteA =
      signedTimeoutVoteFor(proposal.window, proposal.justify, 0)
    val timeoutVoteB =
      signedTimeoutVoteFor(proposal.window, proposal.justify, 1)
    val timeoutVoteC =
      signedTimeoutVoteFor(proposal.window, proposal.justify, 2)
    val duplicateValidator =
      timeoutVoteA.copy(timeoutVoteId = TimeoutVoteId(hex("ce")))
    val unknownTimeoutVoter =
      TimeoutVote
        .sign(
          UnsignedTimeoutVote(
            subject = subject,
            voter = ValidatorId.unsafe("validator-unknown"),
          ),
          validatorKeys.head,
        )
        .toOption
        .get

    assertEquals(
      HotStuffValidator
        .validateTimeoutCertificate(
          TimeoutCertificate(subject, Vector(timeoutVoteA, timeoutVoteB)),
          validatorSet,
        )
        .left
        .map(_.reason),
      Left("insufficientTimeoutQuorum"),
    )
    assertEquals(
      HotStuffValidator
        .validateTimeoutCertificate(
          TimeoutCertificate(
            subject,
            Vector(timeoutVoteA, duplicateValidator, timeoutVoteB, timeoutVoteC),
          ),
          validatorSet,
        )
        .left
        .map(_.reason),
      Left("duplicateValidatorTimeoutVote"),
    )
    assertEquals(
      HotStuffValidator
        .validateTimeoutCertificate(
          TimeoutCertificate(
            subject,
            Vector(timeoutVoteA, timeoutVoteB, unknownTimeoutVoter),
          ),
          validatorSet,
        )
        .left
        .map(_.reason),
      Left("unknownTimeoutVoter"),
    )

  test(
    "timeout vote validation rejects highest qc from the same height and view",
  ):
    val proposal = signedProposal()
    val timeoutVote =
      TimeoutVote
        .sign(
          UnsignedTimeoutVote(
            subject = TimeoutVoteSubject(
              window = proposal.window,
              highestKnownQc =
                proposal.justify.subject.copy(window = proposal.window),
            ),
            voter = validatorSet.members.head.id,
          ),
          validatorKeys.head,
        )
        .toOption
        .get

    assertEquals(
      HotStuffValidator
        .validateTimeoutVote(timeoutVote, validatorSet)
        .left
        .map(_.reason),
      Left("timeoutVoteHighestQcWindowMismatch"),
    )

  test("new-view validation rejects unknown sender and mismatched height"):
    val proposal = signedProposal()
    val timeoutCertificate =
      TimeoutCertificateAssembler
        .assemble(
          TimeoutVoteSubject(
            window = proposal.window,
            highestKnownQc = proposal.justify.subject,
          ),
          Vector(
            signedTimeoutVoteFor(proposal.window, proposal.justify, 0),
            signedTimeoutVoteFor(proposal.window, proposal.justify, 1),
            signedTimeoutVoteFor(proposal.window, proposal.justify, 2),
          ),
          validatorSet,
        )
        .toOption
        .get
    val nextWindow =
      HotStuffPacemaker.nextWindowAfter(timeoutCertificate.subject.window)
    val unknownSenderNewView =
      NewView
        .sign(
          UnsignedNewView(
            window = nextWindow,
            sender = ValidatorId.unsafe("validator-unknown"),
            nextLeader =
              HotStuffPacemaker.deterministicLeader(nextWindow, validatorSet),
            highestKnownQc = proposal.justify,
            timeoutCertificate = timeoutCertificate,
          ),
          validatorKeys.head,
        )
        .toOption
        .get
    val wrongHeightWindow =
      nextWindow.copy(height = nextWindow.height.next)
    val wrongHeightNewView =
      NewView
        .sign(
          UnsignedNewView(
            window = wrongHeightWindow,
            sender = validatorSet.members.head.id,
            nextLeader = HotStuffPacemaker
              .deterministicLeader(wrongHeightWindow, validatorSet),
            highestKnownQc = proposal.justify,
            timeoutCertificate = timeoutCertificate,
          ),
          validatorKeys.head,
        )
        .toOption
        .get

    assertEquals(
      HotStuffValidator
        .validateNewView(unknownSenderNewView, validatorSet)
        .left
        .map(_.reason),
      Left("unknownNewViewSender"),
    )
    assertEquals(
      HotStuffValidator
        .validateNewView(wrongHeightNewView, validatorSet)
        .left
        .map(_.reason),
      Left("newViewWindowMismatch"),
    )

  test("single-validator timeout certificate and new-view validate"):
    val singleKey = CryptoOps.generate()
    val singleId  = ValidatorId.unsafe("validator-single")
    val singleSet = ValidatorSet.unsafe(
      Vector(
        ValidatorMember(
          id = singleId,
          publicKey = singleKey.publicKey,
        ),
      ),
    )
    val qcWindow = HotStuffWindow(chainId, 0L, 0L, singleSet.hash)
    val qcSubject = QuorumCertificateSubject(
      window = qcWindow,
      proposalId = ProposalId(hex("d1")),
      blockId = BlockId(hex("d2")),
    )
    val qc =
      QuorumCertificateAssembler
        .assemble(
          qcSubject,
          Vector(
            Vote
              .sign(
                UnsignedVote(qcWindow, singleId, qcSubject.proposalId),
                singleKey,
              )
              .toOption
              .get,
          ),
          singleSet,
        )
        .toOption
        .get
    val timeoutWindow = HotStuffWindow(chainId, 1L, 1L, singleSet.hash)
    val timeoutVote =
      TimeoutVote
        .sign(
          UnsignedTimeoutVote(
            subject = TimeoutVoteSubject(
              window = timeoutWindow,
              highestKnownQc = qc.subject,
            ),
            voter = singleId,
          ),
          singleKey,
        )
        .toOption
        .get
    val timeoutCertificate =
      TimeoutCertificateAssembler
        .assemble(timeoutVote.subject, Vector(timeoutVote), singleSet)
        .toOption
        .get
    val nextWindow =
      HotStuffPacemaker.nextWindowAfter(timeoutCertificate.subject.window)
    val newView =
      NewView
        .sign(
          UnsignedNewView(
            window = nextWindow,
            sender = singleId,
            nextLeader =
              HotStuffPacemaker.deterministicLeader(nextWindow, singleSet),
            highestKnownQc = qc,
            timeoutCertificate = timeoutCertificate,
          ),
          singleKey,
        )
        .toOption
        .get

    assertEquals(singleSet.quorumSize, 1)
    assertEquals(
      HotStuffValidator
        .validateTimeoutCertificate(timeoutCertificate, singleSet),
      Right(()),
    )
    assertEquals(
      HotStuffValidator.validateNewView(newView, singleSet),
      Right(()),
    )

  test(
    "vote accumulator returns only votes matching the requested window and proposal",
  ):
    val proposal = signedProposal()
    val voteA    = signedVote(proposal, voterIndex = 1)
    val voteB    = signedVote(proposal, voterIndex = 2)
    val otherWindowVote =
      Vote
        .sign(
          UnsignedVote(
            window = proposal.window.copy(view = proposal.window.view + 1L),
            voter = validatorSet.members(3).id,
            targetProposalId = proposal.proposalId,
          ),
          validatorKeys(3),
        )
        .toOption
        .get
    val otherVote =
      Vote
        .sign(
          UnsignedVote(
            window = proposal.window,
            voter = validatorSet.members(3).id,
            targetProposalId = ProposalId(hex("fe")),
          ),
          validatorKeys(3),
        )
        .toOption
        .get

    val accumulator =
      VoteAccumulator.empty
        .record(voteA)
        .toOption
        .get
        ._1
        .record(voteB)
        .toOption
        .get
        ._1
        .record(otherVote)
        .toOption
        .get
        ._1
        .record(otherWindowVote)
        .toOption
        .get
        ._1

    assertEquals(
      accumulator
        .votesFor(proposal.window, proposal.proposalId)
        .map(_.voteId)
        .toSet,
      Set(voteA.voteId, voteB.voteId),
    )
    assertEquals(
      accumulator.votesFor(
        proposal.window.copy(view = proposal.window.view + 99L),
        proposal.proposalId,
      ),
      Vector.empty,
    )

  test(
    "quorum certificate validation rejects insufficient quorum and wrong target proposal id",
  ):
    val parentBlock      = block(parent = None, height = 1L, rootHex = "11")
    val parentWindow     = HotStuffWindow(chainId, 1L, 0L, validatorSet.hash)
    val parentProposalId = ProposalId(hex("99"))
    val subject = QuorumCertificateSubject(
      window = parentWindow,
      proposalId = parentProposalId,
      blockId = BlockHeader.computeId(parentBlock),
    )
    val voteA =
      signedVoteFor(subject.window, subject.proposalId, voterIndex = 0)
    val voteB =
      signedVoteFor(subject.window, subject.proposalId, voterIndex = 1)
    val wrongVote =
      signedVoteFor(subject.window, ProposalId(hex("88")), voterIndex = 2)

    val insufficient = QuorumCertificate(subject, Vector(voteA, voteB))
    val wrongTarget =
      QuorumCertificate(subject, Vector(voteA, voteB, wrongVote))

    assertEquals(
      HotStuffValidator
        .validateQuorumCertificate(insufficient, validatorSet)
        .left
        .map(_.reason),
      Left("insufficientQuorum"),
    )
    assertEquals(
      HotStuffValidator
        .validateQuorumCertificate(wrongTarget, validatorSet)
        .left
        .map(_.reason),
      Left("wrongTargetProposalId"),
    )

  test(
    "quorum certificate validation rejects duplicate validator entries and unknown voters",
  ):
    val parentBlock      = block(parent = None, height = 1L, rootHex = "41")
    val parentWindow     = HotStuffWindow(chainId, 1L, 0L, validatorSet.hash)
    val parentProposalId = ProposalId(hex("42"))
    val subject = QuorumCertificateSubject(
      window = parentWindow,
      proposalId = parentProposalId,
      blockId = BlockHeader.computeId(parentBlock),
    )
    val voteA =
      signedVoteFor(subject.window, subject.proposalId, voterIndex = 0)
    val voteAAgain = voteA.copy(voteId = VoteId(hex("43")))
    val voteB =
      signedVoteFor(subject.window, subject.proposalId, voterIndex = 1)
    val voteC =
      signedVoteFor(subject.window, subject.proposalId, voterIndex = 2)
    val unknownVoter =
      Vote
        .sign(
          UnsignedVote(
            window = subject.window,
            voter = ValidatorId.unsafe("validator-unknown"),
            targetProposalId = subject.proposalId,
          ),
          validatorKeys.head,
        )
        .toOption
        .get

    val duplicateValidatorQc =
      QuorumCertificate(subject, Vector(voteA, voteAAgain, voteB, voteC))
    val unknownVoterQc =
      QuorumCertificate(subject, Vector(voteA, voteB, unknownVoter))

    assertEquals(
      HotStuffValidator
        .validateQuorumCertificate(duplicateValidatorQc, validatorSet)
        .left
        .map(_.reason),
      Left("duplicateValidatorVote"),
    )
    assertEquals(
      HotStuffValidator
        .validateQuorumCertificate(unknownVoterQc, validatorSet)
        .left
        .map(_.reason),
      Left("unknownVoter"),
    )

  test("qc assembly rejects votes signed by the wrong validator key"):
    val parentBlock      = block(parent = None, height = 1L, rootHex = "45")
    val parentWindow     = HotStuffWindow(chainId, 1L, 0L, validatorSet.hash)
    val parentProposalId = ProposalId(hex("46"))
    val subject = QuorumCertificateSubject(
      window = parentWindow,
      proposalId = parentProposalId,
      blockId = BlockHeader.computeId(parentBlock),
    )
    val forgedVote =
      Vote
        .sign(
          UnsignedVote(
            window = subject.window,
            voter = validatorSet.members.head.id,
            targetProposalId = subject.proposalId,
          ),
          validatorKeys(1),
        )
        .toOption
        .get

    assertEquals(
      QuorumCertificateAssembler
        .assemble(
          subject,
          Vector(
            forgedVote,
            signedVoteFor(subject.window, subject.proposalId, 1),
            signedVoteFor(subject.window, subject.proposalId, 2),
          ),
          validatorSet,
        )
        .left
        .map(_.reason),
      Left("voteSignatureMismatch"),
    )

  test("qc assembly rejects votes from a mismatched window"):
    val parentBlock   = block(parent = None, height = 1L, rootHex = "47")
    val subjectWindow = HotStuffWindow(chainId, 1L, 0L, validatorSet.hash)
    val subject = QuorumCertificateSubject(
      window = subjectWindow,
      proposalId = ProposalId(hex("48")),
      blockId = BlockHeader.computeId(parentBlock),
    )
    val mismatchedVote =
      Vote
        .sign(
          UnsignedVote(
            window = subjectWindow.copy(view = view(1L)),
            voter = validatorSet.members.head.id,
            targetProposalId = subject.proposalId,
          ),
          validatorKeys.head,
        )
        .toOption
        .get

    assertEquals(
      QuorumCertificateAssembler
        .assemble(
          subject,
          Vector(
            mismatchedVote,
            signedVoteFor(subjectWindow, subject.proposalId, 1),
            signedVoteFor(subjectWindow, subject.proposalId, 2),
          ),
          validatorSet,
        )
        .left
        .map(_.reason),
      Left("voteWindowMismatch"),
    )

  test(
    "proposal validation rejects wrong validator set hash and malformed justification subject",
  ):
    val proposal = signedProposal()
    val wrongValidatorSetHash =
      proposal.copy(
        window =
          proposal.window.copy(validatorSetHash = ValidatorSetHash(hex("ff"))),
      )
    val wrongTargetBlockId =
      proposal.copy(
        targetBlockId = BlockId(hex("44")),
      )
    val differentChainWindow =
      HotStuffWindow(ChainId.unsafe("chain-remote"), 1L, 0L, validatorSet.hash)
    val differentChainSubject =
      proposal.justify.subject.copy(window = differentChainWindow)
    val differentChainQc =
      QuorumCertificateAssembler
        .assemble(
          subject = differentChainSubject,
          votes = Vector(
            signedVoteFor(
              differentChainWindow,
              differentChainSubject.proposalId,
              0,
            ),
            signedVoteFor(
              differentChainWindow,
              differentChainSubject.proposalId,
              1,
            ),
            signedVoteFor(
              differentChainWindow,
              differentChainSubject.proposalId,
              2,
            ),
          ),
          validatorSet = validatorSet,
        )
        .toOption
        .get
    val justifyChainMismatch =
      Proposal
        .sign(
          UnsignedProposal(
            window = proposal.window,
            proposer = proposal.proposer,
            targetBlockId = proposal.targetBlockId,
            block = proposal.block,
            txSet = proposal.txSet,
            justify = differentChainQc,
          ),
          validatorKeys.head,
        )
        .toOption
        .get
    val nonProgressingSubject =
      proposal.justify.subject.copy(window = proposal.window)
    val nonProgressingQc =
      QuorumCertificateAssembler
        .assemble(
          subject = nonProgressingSubject,
          votes = Vector(
            signedVoteFor(proposal.window, nonProgressingSubject.proposalId, 0),
            signedVoteFor(proposal.window, nonProgressingSubject.proposalId, 1),
            signedVoteFor(proposal.window, nonProgressingSubject.proposalId, 2),
          ),
          validatorSet = validatorSet,
        )
        .toOption
        .get
    val nonProgressingJustification =
      Proposal
        .sign(
          UnsignedProposal(
            window = proposal.window,
            proposer = proposal.proposer,
            targetBlockId = proposal.targetBlockId,
            block = proposal.block,
            txSet = proposal.txSet,
            justify = nonProgressingQc,
          ),
          validatorKeys.head,
        )
        .toOption
        .get
    val wrongHeightBlock =
      proposal.block.copy(height = BlockHeight.unsafeFromLong(99L))
    val wrongHeightProposal =
      Proposal
        .sign(
          UnsignedProposal(
            window = proposal.window,
            proposer = proposal.proposer,
            targetBlockId = BlockHeader.computeId(wrongHeightBlock),
            block = wrongHeightBlock,
            txSet = proposal.txSet,
            justify = proposal.justify,
          ),
          validatorKeys.head,
        )
        .toOption
        .get
    val malformedBlock = proposal.block.copy(parent = None)
    val malformedJustification =
      Proposal
        .sign(
          UnsignedProposal(
            window = proposal.window,
            proposer = proposal.proposer,
            targetBlockId = BlockHeader.computeId(malformedBlock),
            block = malformedBlock,
            txSet = proposal.txSet,
            justify = proposal.justify,
          ),
          validatorKeys.head,
        )
        .toOption
        .get

    assertEquals(
      HotStuffValidator
        .validateProposal(wrongValidatorSetHash, validatorSet)
        .left
        .map(_.reason),
      Left("validatorSetHashMismatch"),
    )
    assertEquals(
      HotStuffValidator
        .validateProposal(wrongTargetBlockId, validatorSet)
        .left
        .map(_.reason),
      Left("targetBlockIdMismatch"),
    )
    assertEquals(
      HotStuffValidator
        .validateProposal(justifyChainMismatch, validatorSet)
        .left
        .map(_.reason),
      Left("justifyChainMismatch"),
    )
    assertEquals(
      HotStuffValidator
        .validateProposal(nonProgressingJustification, validatorSet)
        .left
        .map(_.reason),
      Left("justifyHeightNotProgressing"),
    )
    assertEquals(
      HotStuffValidator
        .validateProposal(wrongHeightProposal, validatorSet)
        .left
        .map(_.reason),
      Left("proposalBlockHeightMismatch"),
    )
    assertEquals(
      HotStuffValidator
        .validateProposal(malformedJustification, validatorSet)
        .left
        .map(_.reason),
      Left("justifyBlockMismatch"),
    )

  test(
    "proposal validation rejects non-canonical tx sets before proposal id checks",
  ):
    val proposal = signedProposal().copy(
      txSet = ProposalTxSet(
        Vector(
          StableArtifactId.unsafeFromHex("02"),
          StableArtifactId.unsafeFromHex("01"),
        ),
      ),
    )

    assertEquals(
      HotStuffValidator
        .validateProposal(proposal, validatorSet)
        .left
        .map(_.reason),
      Left("proposalTxSetNotCanonical"),
    )

  test(
    "qc assembly deduplicates repeated vote ids and produces a quorum certificate",
  ):
    val parentBlock      = block(parent = None, height = 1L, rootHex = "21")
    val parentWindow     = HotStuffWindow(chainId, 1L, 0L, validatorSet.hash)
    val parentProposalId = ProposalId(hex("77"))
    val subject = QuorumCertificateSubject(
      window = parentWindow,
      proposalId = parentProposalId,
      blockId = BlockHeader.computeId(parentBlock),
    )
    val voteA =
      signedVoteFor(subject.window, subject.proposalId, voterIndex = 0)
    val voteB =
      signedVoteFor(subject.window, subject.proposalId, voterIndex = 1)
    val voteC =
      signedVoteFor(subject.window, subject.proposalId, voterIndex = 2)

    val qc = QuorumCertificateAssembler.assemble(
      subject,
      Vector(voteA, voteA, voteB, voteC),
      validatorSet,
    )

    assertEquals(
      qc.map(_.votes.map(_.voter.value)),
      Right(Vector("validator-1", "validator-2", "validator-3")),
    )

  test(
    "proposal id is stable across QC vote ordering and exact duplicate votes",
  ):
    val parentBlock      = block(parent = None, height = 1L, rootHex = "31")
    val parentWindow     = HotStuffWindow(chainId, 1L, 0L, validatorSet.hash)
    val parentProposalId = ProposalId(hex("55"))
    val subject = QuorumCertificateSubject(
      window = parentWindow,
      proposalId = parentProposalId,
      blockId = BlockHeader.computeId(parentBlock),
    )
    val voteA =
      signedVoteFor(subject.window, subject.proposalId, voterIndex = 0)
    val voteB =
      signedVoteFor(subject.window, subject.proposalId, voterIndex = 1)
    val voteC =
      signedVoteFor(subject.window, subject.proposalId, voterIndex = 2)
    val canonicalQc =
      QuorumCertificateAssembler
        .assemble(subject, Vector(voteA, voteB, voteC), validatorSet)
        .toOption
        .get
    val shuffledQc =
      canonicalQc.copy(votes = Vector(voteC, voteA, voteB, voteA))
    val proposalBlock =
      block(parent = Some(subject.blockId), height = 2L, rootHex = "32")
    val canonicalProposal =
      Proposal
        .sign(
          UnsignedProposal(
            window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
            proposer = validatorSet.members.head.id,
            targetBlockId = BlockHeader.computeId(proposalBlock),
            block = proposalBlock,
            txSet = ProposalTxSet.empty,
            justify = canonicalQc,
          ),
          validatorKeys.head,
        )
        .toOption
        .get
    val shuffledProposal =
      Proposal
        .sign(
          UnsignedProposal(
            window = canonicalProposal.window,
            proposer = canonicalProposal.proposer,
            targetBlockId = canonicalProposal.targetBlockId,
            block = canonicalProposal.block,
            txSet = canonicalProposal.txSet,
            justify = shuffledQc,
          ),
          validatorKeys.head,
        )
        .toOption
        .get

    assertEquals(canonicalProposal.proposalId, shuffledProposal.proposalId)
    assertEquals(
      HotStuffValidator.validateProposal(shuffledProposal, validatorSet),
      Right(()),
    )

  test("validator set rejects duplicate public keys"):
    assertEquals(
      ValidatorSet(
        Vector(
          ValidatorMember(
            ValidatorId.unsafe("validator-a"),
            validatorKeys.head.publicKey,
          ),
          ValidatorMember(
            ValidatorId.unsafe("validator-b"),
            validatorKeys.head.publicKey,
          ),
        ),
      ),
      Left(ValidatorSetError.DuplicatePublicKeys),
    )

  test("validator set rejects empty members and duplicate validator ids"):
    assertEquals(
      ValidatorSet(Vector.empty),
      Left(ValidatorSetError.Empty),
    )
    assertEquals(
      ValidatorSet(
        Vector(
          ValidatorMember(
            ValidatorId.unsafe("validator-a"),
            validatorKeys.head.publicKey,
          ),
          ValidatorMember(
            ValidatorId.unsafe("validator-a"),
            validatorKeys(1).publicKey,
          ),
        ),
      ),
      Left(ValidatorSetError.DuplicateIds),
    )

  test("validator set hash is independent from insertion order"):
    val reversed = ValidatorSet.unsafe(validatorSet.members.reverse)
    assertEquals(validatorSet.hash, reversed.hash)

  test("genesis-height proposal can validate with an empty parent pointer"):
    val bootstrapWindow = HotStuffWindow(chainId, 0L, 0L, validatorSet.hash)
    val bootstrapSubject = QuorumCertificateSubject(
      window = bootstrapWindow,
      proposalId = ProposalId(hex("61")),
      blockId = BlockId(hex("62")),
    )
    val bootstrapQc =
      QuorumCertificateAssembler
        .assemble(
          bootstrapSubject,
          Vector(
            signedVoteFor(bootstrapWindow, bootstrapSubject.proposalId, 0),
            signedVoteFor(bootstrapWindow, bootstrapSubject.proposalId, 1),
            signedVoteFor(bootstrapWindow, bootstrapSubject.proposalId, 2),
          ),
          validatorSet,
        )
        .toOption
        .get
    val genesisBlock = block(parent = None, height = 0L, rootHex = "63")
    val proposal =
      Proposal
        .sign(
          UnsignedProposal(
            window = bootstrapWindow,
            proposer = validatorSet.members.head.id,
            targetBlockId = BlockHeader.computeId(genesisBlock),
            block = genesisBlock,
            txSet = ProposalTxSet.empty,
            justify = bootstrapQc,
          ),
          validatorKeys.head,
        )
        .toOption
        .get

    assertEquals(
      HotStuffValidator.validateProposal(proposal, validatorSet),
      Right(()),
    )

  test("genesis-height proposal accepts a justify QC at height zero boundary"):
    val bootstrapWindow = HotStuffWindow(chainId, 0L, 0L, validatorSet.hash)
    val boundarySubject = QuorumCertificateSubject(
      window = bootstrapWindow,
      proposalId = ProposalId(hex("74")),
      blockId = BlockId(hex("75")),
    )
    val boundaryQc =
      QuorumCertificateAssembler
        .assemble(
          boundarySubject,
          Vector(
            signedVoteFor(bootstrapWindow, boundarySubject.proposalId, 0),
            signedVoteFor(bootstrapWindow, boundarySubject.proposalId, 1),
            signedVoteFor(bootstrapWindow, boundarySubject.proposalId, 2),
          ),
          validatorSet,
        )
        .toOption
        .get
    val genesisBlock = block(parent = None, height = 0L, rootHex = "76")
    val proposal =
      Proposal
        .sign(
          UnsignedProposal(
            window = bootstrapWindow,
            proposer = validatorSet.members.head.id,
            targetBlockId = BlockHeader.computeId(genesisBlock),
            block = genesisBlock,
            txSet = ProposalTxSet.empty,
            justify = boundaryQc,
          ),
          validatorKeys.head,
        )
        .toOption
        .get

    assertEquals(
      HotStuffValidator.validateProposal(proposal, validatorSet),
      Right(()),
    )

  test("genesis-height proposal rejects a non-empty parent pointer"):
    val bootstrapWindow = HotStuffWindow(chainId, 0L, 0L, validatorSet.hash)
    val boundarySubject = QuorumCertificateSubject(
      window = bootstrapWindow,
      proposalId = ProposalId(hex("77")),
      blockId = BlockId(hex("78")),
    )
    val boundaryQc =
      QuorumCertificateAssembler
        .assemble(
          boundarySubject,
          Vector(
            signedVoteFor(bootstrapWindow, boundarySubject.proposalId, 0),
            signedVoteFor(bootstrapWindow, boundarySubject.proposalId, 1),
            signedVoteFor(bootstrapWindow, boundarySubject.proposalId, 2),
          ),
          validatorSet,
        )
        .toOption
        .get
    val invalidGenesisBlock =
      block(parent = Some(boundarySubject.blockId), height = 0L, rootHex = "79")
    val proposal =
      Proposal
        .sign(
          UnsignedProposal(
            window = bootstrapWindow,
            proposer = validatorSet.members.head.id,
            targetBlockId = BlockHeader.computeId(invalidGenesisBlock),
            block = invalidGenesisBlock,
            txSet = ProposalTxSet.empty,
            justify = boundaryQc,
          ),
          validatorKeys.head,
        )
        .toOption
        .get

    assertEquals(
      HotStuffValidator
        .validateProposal(proposal, validatorSet)
        .left
        .map(_.reason),
      Left("justifyBlockMismatch"),
    )

  test("genesis-height proposal rejects a justify QC from a higher height"):
    val bootstrapWindow = HotStuffWindow(chainId, 0L, 0L, validatorSet.hash)
    val highSubject = QuorumCertificateSubject(
      window = HotStuffWindow(chainId, 1L, 0L, validatorSet.hash),
      proposalId = ProposalId(hex("71")),
      blockId = BlockId(hex("72")),
    )
    val highQc =
      QuorumCertificateAssembler
        .assemble(
          highSubject,
          Vector(
            signedVoteFor(highSubject.window, highSubject.proposalId, 0),
            signedVoteFor(highSubject.window, highSubject.proposalId, 1),
            signedVoteFor(highSubject.window, highSubject.proposalId, 2),
          ),
          validatorSet,
        )
        .toOption
        .get
    val genesisBlock = block(parent = None, height = 0L, rootHex = "73")
    val proposal =
      Proposal
        .sign(
          UnsignedProposal(
            window = bootstrapWindow,
            proposer = validatorSet.members.head.id,
            targetBlockId = BlockHeader.computeId(genesisBlock),
            block = genesisBlock,
            txSet = ProposalTxSet.empty,
            justify = highQc,
          ),
          validatorKeys.head,
        )
        .toOption
        .get

    assertEquals(
      HotStuffValidator
        .validateProposal(proposal, validatorSet)
        .left
        .map(_.reason),
      Left("justifyHeightNotProgressing"),
    )

  private def signedProposal(): Proposal =
    val parentBlock      = block(parent = None, height = 0L, rootHex = "01")
    val parentWindow     = HotStuffWindow(chainId, 0L, 0L, validatorSet.hash)
    val parentProposalId = ProposalId(hex("10"))
    val subject = QuorumCertificateSubject(
      window = parentWindow,
      proposalId = parentProposalId,
      blockId = BlockHeader.computeId(parentBlock),
    )
    val justify = QuorumCertificateAssembler
      .assemble(
        subject = subject,
        votes = Vector(
          signedVoteFor(parentWindow, parentProposalId, 0),
          signedVoteFor(parentWindow, parentProposalId, 1),
          signedVoteFor(parentWindow, parentProposalId, 2),
        ),
        validatorSet = validatorSet,
      )
      .toOption
      .get
    val proposalBlock =
      block(parent = Some(subject.blockId), height = 1L, rootHex = "02")
    Proposal
      .sign(
        UnsignedProposal(
          window = HotStuffWindow(chainId, 1L, 1L, validatorSet.hash),
          proposer = validatorSet.members.head.id,
          targetBlockId = BlockHeader.computeId(proposalBlock),
          block = proposalBlock,
          txSet = ProposalTxSet.empty,
          justify = justify,
        ),
        validatorKeys.head,
      )
      .toOption
      .get

  private def signedVote(
      proposal: Proposal,
      voterIndex: Int,
  ): Vote =
    signedVoteFor(proposal.window, proposal.proposalId, voterIndex)

  private def signedVoteFor(
      window: HotStuffWindow,
      proposalId: ProposalId,
      voterIndex: Int,
  ): Vote =
    Vote
      .sign(
        UnsignedVote(
          window = window,
          voter = validatorSet.members(voterIndex).id,
          targetProposalId = proposalId,
        ),
        validatorKeys(voterIndex),
      )
      .toOption
      .get

  private def signedTimeoutVoteFor(
      window: HotStuffWindow,
      highestKnownQc: QuorumCertificate,
      voterIndex: Int,
  ): TimeoutVote =
    TimeoutVote
      .sign(
        UnsignedTimeoutVote(
          subject = TimeoutVoteSubject(
            window = window,
            highestKnownQc = highestKnownQc.subject,
          ),
          voter = validatorSet.members(voterIndex).id,
        ),
        validatorKeys(voterIndex),
      )
      .toOption
      .get

  private def unsignedProposal(
      proposal: Proposal,
  ): UnsignedProposal =
    UnsignedProposal(
      window = proposal.window,
      proposer = proposal.proposer,
      targetBlockId = proposal.targetBlockId,
      block = proposal.block,
      txSet = proposal.txSet,
      justify = proposal.justify,
    )

  private def unsignedVote(
      vote: Vote,
  ): UnsignedVote =
    UnsignedVote(
      window = vote.window,
      voter = vote.voter,
      targetProposalId = vote.targetProposalId,
    )

  private def gossipEvent[A](
      topic: GossipTopic,
      idHex: String,
      cursorValue: Long,
      payload: A,
  ): GossipEvent[A] =
    GossipEvent(
      chainId = chainId,
      topic = topic,
      id = StableArtifactId.unsafeFromHex(idHex),
      cursor = CursorToken.unsafeIssue(ByteVector.fromLong(cursorValue)),
      ts = Instant.parse("2026-04-01T00:00:00Z").plusMillis(cursorValue),
      payload = payload,
    )

  private def hex(
      value: String,
  ): UInt256 =
    UInt256.fromHex(value).toOption.get

  private def block(
      parent: Option[BlockId],
      height: Long,
      rootHex: String,
  ): BlockHeader =
    blockAt(
      parent = parent,
      height = height,
      rootHex = rootHex,
      timestampMillis = 1_712_345_678_000L,
    )

  private def blockAt(
      parent: Option[BlockId],
      height: Long,
      rootHex: String,
      timestampMillis: Long,
  ): BlockHeader =
    BlockHeader(
      parent = parent,
      height = BlockHeight.unsafeFromLong(height),
      stateRoot = StateRoot(hex(rootHex)),
      bodyRoot = BodyRoot(hex(rootHex)),
      timestamp = BlockTimestamp.unsafeFromEpochMillis(timestampMillis),
    )

  private def view(
      value: Long,
  ): HotStuffView =
    HotStuffView.unsafeFromLong(value)
