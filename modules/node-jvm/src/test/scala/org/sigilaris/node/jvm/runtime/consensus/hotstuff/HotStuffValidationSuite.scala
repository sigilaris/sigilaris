package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Instant

import munit.FunSuite
import scodec.bits.ByteVector

import org.sigilaris.core.crypto.CryptoOps
import org.sigilaris.core.datatype.UInt256
import org.sigilaris.node.jvm.runtime.gossip.{ChainId, CursorToken, GossipEvent, GossipTopic, StableArtifactId}

final class HotStuffValidationSuite extends FunSuite:

  private val chainId = ChainId.unsafe("chain-main")
  private val validatorKeys = Vector.fill(4)(CryptoOps.generate())
  private val validatorSet = ValidatorSet(
    validatorKeys.zipWithIndex.map: (keyPair, index) =>
      ValidatorMember(
        id = ValidatorId.unsafe(s"validator-${index + 1}"),
        publicKey = keyPair.publicKey,
      )
  )

  test("proposal and vote sign bytes are independent from gossip envelope fields"):
    val proposal = signedProposal()
    val vote = signedVote(proposal, voterIndex = 1)

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

  test("proposal and vote signatures validate and proposal id differs from block id"):
    val proposal = signedProposal()
    val vote = signedVote(proposal, voterIndex = 1)

    assertEquals(HotStuffValidator.validateProposal(proposal, validatorSet), Right(()))
    assertEquals(
      HotStuffValidator.validateVote(
        vote,
        validatorSet = validatorSet,
        expectedWindow = Some(proposal.window),
        expectedProposalId = Some(proposal.proposalId),
      ),
      Right(()),
    )
    assertNotEquals(proposal.proposalId.toHexLower, proposal.targetBlockId.toHexLower)

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
      HotStuffValidator.validateProposal(forgedProposal, validatorSet).left.map(_.reason),
      Left("proposalSignatureMismatch"),
    )
    assertEquals(
      HotStuffValidator.validateVote(
        forgedVote,
        validatorSet = validatorSet,
        expectedWindow = Some(validProposal.window),
        expectedProposalId = Some(validProposal.proposalId),
      ).left.map(_.reason),
      Left("voteSignatureMismatch"),
    )

  test("proposal and vote validation reject unknown validators and tampered ids"):
    val proposal = signedProposal()
    val unknownProposer =
      proposal.copy(
        proposer = ValidatorId.unsafe("validator-unknown"),
        proposalId = Proposal.recomputeId(
          proposal.copy(proposer = ValidatorId.unsafe("validator-unknown"))
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
        window = baseVote.window.copy(validatorSetHash = ValidatorSetHash(hex("cd")))
      )
    val tamperedVoteId =
      baseVote.copy(voteId = VoteId(hex("ef")))

    assertEquals(
      HotStuffValidator.validateProposal(unknownProposer, validatorSet).left.map(_.reason),
      Left("unknownProposer"),
    )
    assertEquals(
      HotStuffValidator.validateVote(
        unknownVoter,
        validatorSet = validatorSet,
        expectedWindow = Some(proposal.window),
        expectedProposalId = Some(proposal.proposalId),
      ).left.map(_.reason),
      Left("unknownVoter"),
    )
    assertEquals(
      HotStuffValidator.validateProposal(tamperedProposalId, validatorSet).left.map(_.reason),
      Left("proposalIdMismatch"),
    )
    assertEquals(
      HotStuffValidator.validateVote(
        voteWithWrongValidatorSetHash,
        validatorSet = validatorSet,
        expectedWindow = Some(proposal.window),
        expectedProposalId = Some(proposal.proposalId),
      ).left.map(_.reason),
      Left("validatorSetHashMismatch"),
    )
    assertEquals(
      HotStuffValidator.validateVote(
        tamperedVoteId,
        validatorSet = validatorSet,
        expectedWindow = Some(proposal.window),
        expectedProposalId = Some(proposal.proposalId),
      ).left.map(_.reason),
      Left("voteIdMismatch"),
    )

  test("vote accumulator deduplicates exact duplicates and rejects equivocation"):
    val proposal = signedProposal()
    val vote = signedVote(proposal, voterIndex = 1)
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

    val first = VoteAccumulator.empty.record(vote)
    val second = first.toOption.get._1.record(vote)
    val third = first.toOption.get._1.record(equivocated)

    assertEquals(first.map(_._2), Right(VoteRecordOutcome.Applied))
    assertEquals(second.map(_._2), Right(VoteRecordOutcome.Duplicate))
    assertEquals(third.left.map(_.reason), Left("equivocationDetected"))

  test("vote accumulator rejects duplicate validator votes with different vote ids for the same proposal"):
    val proposal = signedProposal()
    val vote = signedVote(proposal, voterIndex = 1)
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

  test("vote accumulator returns only votes matching the requested window and proposal"):
    val proposal = signedProposal()
    val voteA = signedVote(proposal, voterIndex = 1)
    val voteB = signedVote(proposal, voterIndex = 2)
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
      accumulator.votesFor(proposal.window, proposal.proposalId).map(_.voteId).toSet,
      Set(voteA.voteId, voteB.voteId),
    )
    assertEquals(
      accumulator.votesFor(proposal.window.copy(view = proposal.window.view + 99L), proposal.proposalId),
      Vector.empty,
    )

  test("quorum certificate validation rejects insufficient quorum and wrong target proposal id"):
    val parentBlock = Block(parent = None, payloadHash = hex("11"))
    val parentWindow = HotStuffWindow(chainId, 1L, 0L, validatorSet.hash)
    val parentProposalId = ProposalId(hex("99"))
    val subject = QuorumCertificateSubject(
      window = parentWindow,
      proposalId = parentProposalId,
      blockId = Block.computeId(parentBlock),
    )
    val voteA = signedVoteFor(subject.window, subject.proposalId, voterIndex = 0)
    val voteB = signedVoteFor(subject.window, subject.proposalId, voterIndex = 1)
    val wrongVote = signedVoteFor(subject.window, ProposalId(hex("88")), voterIndex = 2)

    val insufficient = QuorumCertificate(subject, Vector(voteA, voteB))
    val wrongTarget = QuorumCertificate(subject, Vector(voteA, voteB, wrongVote))

    assertEquals(
      HotStuffValidator.validateQuorumCertificate(insufficient, validatorSet).left.map(_.reason),
      Left("insufficientQuorum"),
    )
    assertEquals(
      HotStuffValidator.validateQuorumCertificate(wrongTarget, validatorSet).left.map(_.reason),
      Left("wrongTargetProposalId"),
    )

  test("quorum certificate validation rejects duplicate validator entries and unknown voters"):
    val parentBlock = Block(parent = None, payloadHash = hex("41"))
    val parentWindow = HotStuffWindow(chainId, 1L, 0L, validatorSet.hash)
    val parentProposalId = ProposalId(hex("42"))
    val subject = QuorumCertificateSubject(
      window = parentWindow,
      proposalId = parentProposalId,
      blockId = Block.computeId(parentBlock),
    )
    val voteA = signedVoteFor(subject.window, subject.proposalId, voterIndex = 0)
    val voteAAgain = voteA.copy(voteId = VoteId(hex("43")))
    val voteB = signedVoteFor(subject.window, subject.proposalId, voterIndex = 1)
    val voteC = signedVoteFor(subject.window, subject.proposalId, voterIndex = 2)
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

    val duplicateValidatorQc = QuorumCertificate(subject, Vector(voteA, voteAAgain, voteB, voteC))
    val unknownVoterQc = QuorumCertificate(subject, Vector(voteA, voteB, unknownVoter))

    assertEquals(
      HotStuffValidator.validateQuorumCertificate(duplicateValidatorQc, validatorSet).left.map(_.reason),
      Left("duplicateValidatorVote"),
    )
    assertEquals(
      HotStuffValidator.validateQuorumCertificate(unknownVoterQc, validatorSet).left.map(_.reason),
      Left("unknownVoter"),
    )

  test("qc assembly rejects votes signed by the wrong validator key"):
    val parentBlock = Block(parent = None, payloadHash = hex("45"))
    val parentWindow = HotStuffWindow(chainId, 1L, 0L, validatorSet.hash)
    val parentProposalId = ProposalId(hex("46"))
    val subject = QuorumCertificateSubject(
      window = parentWindow,
      proposalId = parentProposalId,
      blockId = Block.computeId(parentBlock),
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
        .assemble(subject, Vector(forgedVote, signedVoteFor(subject.window, subject.proposalId, 1), signedVoteFor(subject.window, subject.proposalId, 2)), validatorSet)
        .left
        .map(_.reason),
      Left("voteSignatureMismatch"),
    )

  test("qc assembly rejects votes from a mismatched window"):
    val parentBlock = Block(parent = None, payloadHash = hex("47"))
    val subjectWindow = HotStuffWindow(chainId, 1L, 0L, validatorSet.hash)
    val subject = QuorumCertificateSubject(
      window = subjectWindow,
      proposalId = ProposalId(hex("48")),
      blockId = Block.computeId(parentBlock),
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
        .assemble(subject, Vector(mismatchedVote, signedVoteFor(subjectWindow, subject.proposalId, 1), signedVoteFor(subjectWindow, subject.proposalId, 2)), validatorSet)
        .left
        .map(_.reason),
      Left("voteWindowMismatch"),
    )

  test("proposal validation rejects wrong validator set hash and malformed justification subject"):
    val proposal = signedProposal()
    val wrongValidatorSetHash =
      proposal.copy(
        window = proposal.window.copy(validatorSetHash = ValidatorSetHash(hex("ff")))
      )
    val wrongTargetBlockId =
      proposal.copy(
        targetBlockId = BlockId(hex("44"))
      )
    val differentChainWindow = HotStuffWindow(ChainId.unsafe("chain-remote"), 1L, 0L, validatorSet.hash)
    val differentChainSubject =
      proposal.justify.subject.copy(window = differentChainWindow)
    val differentChainQc =
      QuorumCertificateAssembler
        .assemble(
          subject = differentChainSubject,
          votes = Vector(
            signedVoteFor(differentChainWindow, differentChainSubject.proposalId, 0),
            signedVoteFor(differentChainWindow, differentChainSubject.proposalId, 1),
            signedVoteFor(differentChainWindow, differentChainSubject.proposalId, 2),
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
            justify = nonProgressingQc,
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
            targetBlockId = Block.computeId(malformedBlock),
            block = malformedBlock,
            justify = proposal.justify,
          ),
          validatorKeys.head,
        )
        .toOption
        .get

    assertEquals(
      HotStuffValidator.validateProposal(wrongValidatorSetHash, validatorSet).left.map(_.reason),
      Left("validatorSetHashMismatch"),
    )
    assertEquals(
      HotStuffValidator.validateProposal(wrongTargetBlockId, validatorSet).left.map(_.reason),
      Left("targetBlockIdMismatch"),
    )
    assertEquals(
      HotStuffValidator.validateProposal(justifyChainMismatch, validatorSet).left.map(_.reason),
      Left("justifyChainMismatch"),
    )
    assertEquals(
      HotStuffValidator.validateProposal(nonProgressingJustification, validatorSet).left.map(_.reason),
      Left("justifyHeightNotProgressing"),
    )
    assertEquals(
      HotStuffValidator.validateProposal(malformedJustification, validatorSet).left.map(_.reason),
      Left("justifyBlockMismatch"),
    )

  test("qc assembly deduplicates repeated vote ids and produces a quorum certificate"):
    val parentBlock = Block(parent = None, payloadHash = hex("21"))
    val parentWindow = HotStuffWindow(chainId, 1L, 0L, validatorSet.hash)
    val parentProposalId = ProposalId(hex("77"))
    val subject = QuorumCertificateSubject(
      window = parentWindow,
      proposalId = parentProposalId,
      blockId = Block.computeId(parentBlock),
    )
    val voteA = signedVoteFor(subject.window, subject.proposalId, voterIndex = 0)
    val voteB = signedVoteFor(subject.window, subject.proposalId, voterIndex = 1)
    val voteC = signedVoteFor(subject.window, subject.proposalId, voterIndex = 2)

    val qc = QuorumCertificateAssembler.assemble(subject, Vector(voteA, voteA, voteB, voteC), validatorSet)

    assertEquals(qc.map(_.votes.map(_.voter.value)), Right(Vector("validator-1", "validator-2", "validator-3")))

  test("proposal id is stable across QC vote ordering and exact duplicate votes"):
    val parentBlock = Block(parent = None, payloadHash = hex("31"))
    val parentWindow = HotStuffWindow(chainId, 1L, 0L, validatorSet.hash)
    val parentProposalId = ProposalId(hex("55"))
    val subject = QuorumCertificateSubject(
      window = parentWindow,
      proposalId = parentProposalId,
      blockId = Block.computeId(parentBlock),
    )
    val voteA = signedVoteFor(subject.window, subject.proposalId, voterIndex = 0)
    val voteB = signedVoteFor(subject.window, subject.proposalId, voterIndex = 1)
    val voteC = signedVoteFor(subject.window, subject.proposalId, voterIndex = 2)
    val canonicalQc =
      QuorumCertificateAssembler.assemble(subject, Vector(voteA, voteB, voteC), validatorSet).toOption.get
    val shuffledQc =
      canonicalQc.copy(votes = Vector(voteC, voteA, voteB, voteA))
    val block = Block(parent = Some(subject.blockId), payloadHash = hex("32"))
    val canonicalProposal =
      Proposal
        .sign(
          UnsignedProposal(
            window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
            proposer = validatorSet.members.head.id,
            targetBlockId = Block.computeId(block),
            block = block,
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
            justify = shuffledQc,
          ),
          validatorKeys.head,
        )
        .toOption
        .get

    assertEquals(canonicalProposal.proposalId, shuffledProposal.proposalId)
    assertEquals(HotStuffValidator.validateProposal(shuffledProposal, validatorSet), Right(()))

  test("validator set rejects duplicate public keys"):
    val _ = intercept[IllegalArgumentException]:
      ValidatorSet(
        Vector(
          ValidatorMember(ValidatorId.unsafe("validator-a"), validatorKeys.head.publicKey),
          ValidatorMember(ValidatorId.unsafe("validator-b"), validatorKeys.head.publicKey),
        )
      )

  test("validator set rejects empty members and duplicate validator ids"):
    val _ = intercept[IllegalArgumentException]:
      ValidatorSet(Vector.empty)

    val duplicateId = intercept[IllegalArgumentException]:
      ValidatorSet(
        Vector(
          ValidatorMember(ValidatorId.unsafe("validator-a"), validatorKeys.head.publicKey),
          ValidatorMember(ValidatorId.unsafe("validator-a"), validatorKeys(1).publicKey),
        )
      )
    assert(duplicateId.getMessage.nonEmpty)

  test("validator set hash is independent from insertion order"):
    val reversed = ValidatorSet(validatorSet.members.reverse)
    assertEquals(validatorSet.hash, reversed.hash)

  test("genesis-height proposal can validate with an empty parent pointer"):
    val bootstrapWindow = HotStuffWindow(chainId, 0L, 0L, validatorSet.hash)
    val bootstrapSubject = QuorumCertificateSubject(
      window = bootstrapWindow,
      proposalId = ProposalId(hex("61")),
      blockId = BlockId(hex("62")),
    )
    val bootstrapQc =
      QuorumCertificateAssembler.assemble(
        bootstrapSubject,
        Vector(
          signedVoteFor(bootstrapWindow, bootstrapSubject.proposalId, 0),
          signedVoteFor(bootstrapWindow, bootstrapSubject.proposalId, 1),
          signedVoteFor(bootstrapWindow, bootstrapSubject.proposalId, 2),
        ),
        validatorSet,
      ).toOption.get
    val genesisBlock = Block(parent = None, payloadHash = hex("63"))
    val proposal =
      Proposal
        .sign(
          UnsignedProposal(
            window = bootstrapWindow,
            proposer = validatorSet.members.head.id,
            targetBlockId = Block.computeId(genesisBlock),
            block = genesisBlock,
            justify = bootstrapQc,
          ),
          validatorKeys.head,
        )
        .toOption
        .get

    assertEquals(HotStuffValidator.validateProposal(proposal, validatorSet), Right(()))

  test("genesis-height proposal accepts a justify QC at height zero boundary"):
    val bootstrapWindow = HotStuffWindow(chainId, 0L, 0L, validatorSet.hash)
    val boundarySubject = QuorumCertificateSubject(
      window = bootstrapWindow,
      proposalId = ProposalId(hex("74")),
      blockId = BlockId(hex("75")),
    )
    val boundaryQc =
      QuorumCertificateAssembler.assemble(
        boundarySubject,
        Vector(
          signedVoteFor(bootstrapWindow, boundarySubject.proposalId, 0),
          signedVoteFor(bootstrapWindow, boundarySubject.proposalId, 1),
          signedVoteFor(bootstrapWindow, boundarySubject.proposalId, 2),
        ),
        validatorSet,
      ).toOption.get
    val genesisBlock = Block(parent = None, payloadHash = hex("76"))
    val proposal =
      Proposal
        .sign(
          UnsignedProposal(
            window = bootstrapWindow,
            proposer = validatorSet.members.head.id,
            targetBlockId = Block.computeId(genesisBlock),
            block = genesisBlock,
            justify = boundaryQc,
          ),
          validatorKeys.head,
        )
        .toOption
        .get

    assertEquals(HotStuffValidator.validateProposal(proposal, validatorSet), Right(()))

  test("genesis-height proposal rejects a non-empty parent pointer"):
    val bootstrapWindow = HotStuffWindow(chainId, 0L, 0L, validatorSet.hash)
    val boundarySubject = QuorumCertificateSubject(
      window = bootstrapWindow,
      proposalId = ProposalId(hex("77")),
      blockId = BlockId(hex("78")),
    )
    val boundaryQc =
      QuorumCertificateAssembler.assemble(
        boundarySubject,
        Vector(
          signedVoteFor(bootstrapWindow, boundarySubject.proposalId, 0),
          signedVoteFor(bootstrapWindow, boundarySubject.proposalId, 1),
          signedVoteFor(bootstrapWindow, boundarySubject.proposalId, 2),
        ),
        validatorSet,
      ).toOption.get
    val invalidGenesisBlock = Block(parent = Some(boundarySubject.blockId), payloadHash = hex("79"))
    val proposal =
      Proposal
        .sign(
          UnsignedProposal(
            window = bootstrapWindow,
            proposer = validatorSet.members.head.id,
            targetBlockId = Block.computeId(invalidGenesisBlock),
            block = invalidGenesisBlock,
            justify = boundaryQc,
          ),
          validatorKeys.head,
        )
        .toOption
        .get

    assertEquals(
      HotStuffValidator.validateProposal(proposal, validatorSet).left.map(_.reason),
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
      QuorumCertificateAssembler.assemble(
        highSubject,
        Vector(
          signedVoteFor(highSubject.window, highSubject.proposalId, 0),
          signedVoteFor(highSubject.window, highSubject.proposalId, 1),
          signedVoteFor(highSubject.window, highSubject.proposalId, 2),
        ),
        validatorSet,
      ).toOption.get
    val genesisBlock = Block(parent = None, payloadHash = hex("73"))
    val proposal =
      Proposal
        .sign(
          UnsignedProposal(
            window = bootstrapWindow,
            proposer = validatorSet.members.head.id,
            targetBlockId = Block.computeId(genesisBlock),
            block = genesisBlock,
            justify = highQc,
          ),
          validatorKeys.head,
        )
        .toOption
        .get

    assertEquals(
      HotStuffValidator.validateProposal(proposal, validatorSet).left.map(_.reason),
      Left("justifyHeightNotProgressing"),
    )

  private def signedProposal(): Proposal =
    val parentBlock = Block(parent = None, payloadHash = hex("01"))
    val parentWindow = HotStuffWindow(chainId, 0L, 0L, validatorSet.hash)
    val parentProposalId = ProposalId(hex("10"))
    val subject = QuorumCertificateSubject(
      window = parentWindow,
      proposalId = parentProposalId,
      blockId = Block.computeId(parentBlock),
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
    val block = Block(parent = Some(subject.blockId), payloadHash = hex("02"))
    Proposal
      .sign(
        UnsignedProposal(
          window = HotStuffWindow(chainId, 1L, 1L, validatorSet.hash),
          proposer = validatorSet.members.head.id,
          targetBlockId = Block.computeId(block),
          block = block,
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

  private def unsignedProposal(
      proposal: Proposal,
  ): UnsignedProposal =
    UnsignedProposal(
      window = proposal.window,
      proposer = proposal.proposer,
      targetBlockId = proposal.targetBlockId,
      block = proposal.block,
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
      cursor = CursorToken.issue(ByteVector.fromLong(cursorValue)),
      ts = Instant.parse("2026-04-01T00:00:00Z").plusMillis(cursorValue),
      payload = payload,
    )

  private def hex(
      value: String,
  ): UInt256 =
    UInt256.fromHex(value).toOption.get

  private def view(
      value: Long,
  ): HotStuffView =
    HotStuffView.unsafeFromLong(value)
