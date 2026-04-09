package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Instant

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import scodec.bits.ByteVector

import org.sigilaris.core.crypto.{CryptoOps, KeyPair}
import org.sigilaris.core.datatype.UInt256
import org.sigilaris.node.jvm.runtime.block.{
  BlockHeader,
  BlockHeight,
  BlockTimestamp,
  BlockId,
  BodyRoot,
  StateRoot,
}
import org.sigilaris.node.jvm.runtime.gossip.{
  ArtifactApplyResult,
  CanonicalRejection,
  ChainId,
  CursorToken,
  DirectionalSessionId,
  GossipClock,
  GossipEvent,
  GossipTopic,
  PeerIdentity,
}

final class HotStuffFinalizationSuite extends CatsEffectSuite:

  private val chainId       = ChainId.unsafe("chain-main")
  private val altChainId    = ChainId.unsafe("chain-alt")
  private val baseInstant   = Instant.parse("2026-04-05T00:00:00Z")
  private val validatorKeys = Vector.fill(4)(CryptoOps.generate())
  private val validatorSet = ValidatorSet.unsafe(
    validatorKeys.zipWithIndex.map: (keyPair, index) =>
      ValidatorMember(
        id = ValidatorId.unsafe(s"validator-${index + 1}"),
        publicKey = keyPair.publicKey,
      ),
  )
  private val otherValidatorKeys = Vector.fill(4)(CryptoOps.generate())
  private val otherValidatorSet = ValidatorSet.unsafe(
    otherValidatorKeys.zipWithIndex.map: (keyPair, index) =>
      ValidatorMember(
        id = ValidatorId.unsafe(s"validator-alt-${index + 1}"),
        publicKey = keyPair.publicKey,
      ),
  )
  private val session =
    BootstrapSessionBinding(
      peer = PeerIdentity.unsafe("node-b"),
      sessionId = DirectionalSessionId
        .parse("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        .toOption
        .get,
    )

  test(
    "finalization tracker materializes the best finalized anchor from a justify 3-chain",
  ):
    val suggestion = threeChain("10")

    val tracked =
      HotStuffFinalizationTracker.track(
        Vector(
          suggestion.proposal,
          suggestion.finalizedProof.child,
          suggestion.finalizedProof.grandchild,
        ),
      )

    assertEquals(
      tracked.bestFinalized.map(_.proposal.proposalId),
      Some(suggestion.proposal.proposalId),
    )
    assertEquals(tracked.safetyFaults, Vector.empty)

  test(
    "finalization tracker stays empty until the full justify 3-chain is present",
  ):
    val suggestion = threeChain("11")

    val anchorOnly =
      HotStuffFinalizationTracker.track(
        Vector(suggestion.proposal),
      )
    val anchorAndChild =
      HotStuffFinalizationTracker.track(
        Vector(
          suggestion.proposal,
          suggestion.finalizedProof.child,
        ),
      )

    assertEquals(anchorOnly.bestFinalized, None)
    assertEquals(anchorAndChild.bestFinalized, None)
    assertEquals(anchorOnly.safetyFaults, Vector.empty)
    assertEquals(anchorAndChild.safetyFaults, Vector.empty)

  test(
    "finalized anchor verifier accepts a valid self-contained suggestion and rejects mismatches",
  ):
    val suggestion = threeChain("20")
    val lookup =
      ValidatorSetLookup.static[IO](
        BootstrapTrustRoot.staticValidatorSet(validatorSet),
      )
    val wrongProof =
      suggestion.copy(
        finalizedProof = FinalizedProof(
          child = suggestion.finalizedProof.grandchild,
          grandchild = suggestion.finalizedProof.child,
        ),
      )
    val wrongTrustLookup =
      ValidatorSetLookup.static[IO](
        BootstrapTrustRoot.staticValidatorSet(otherValidatorSet),
      )

    for
      valid <- HotStuffFinalizedAnchorVerifier.verify(suggestion, lookup)
      wrongProofResult <- HotStuffFinalizedAnchorVerifier.verify(
        wrongProof,
        lookup,
      )
      wrongTrustResult <- HotStuffFinalizedAnchorVerifier.verify(
        suggestion,
        wrongTrustLookup,
      )
    yield
      assertEquals(
        valid.map(_.proposal.proposalId),
        Right(suggestion.proposal.proposalId),
      )
      assertEquals(
        wrongProofResult.left.map(_.reason),
        Left("finalizedProofChildMismatch"),
      )
      assertEquals(
        wrongTrustResult.left.map(_.reason),
        Left("validatorSetUnavailable"),
      )

  test(
    "finalized anchor verifier rejects a suggestion whose embedded justify QC is invalid",
  ):
    val suggestion = threeChain("21")
    val child      = suggestion.finalizedProof.child
    val forgedVote =
      Vote
        .sign(
          UnsignedVote(
            window = child.justify.subject.window,
            voter = validatorSet.members(0).id,
            targetProposalId = child.justify.subject.proposalId,
          ),
          validatorKeys(1),
        )
        .toOption
        .get
    val invalidChildQc =
      child.justify.copy(
        votes = Vector(
          forgedVote,
          child.justify.votes(1),
          child.justify.votes(2),
        ),
      )
    val invalidChild =
      Proposal
        .sign(
          UnsignedProposal(
            window = child.window,
            proposer = child.proposer,
            targetBlockId = child.targetBlockId,
            block = child.block,
            txSet = child.txSet,
            justify = invalidChildQc,
          ),
          validatorKeys(1),
        )
        .toOption
        .get
    val invalidSuggestion =
      suggestion.copy(
        finalizedProof = suggestion.finalizedProof.copy(child = invalidChild),
      )
    val lookup =
      ValidatorSetLookup.static[IO](
        BootstrapTrustRoot.staticValidatorSet(validatorSet),
      )

    for verified <- HotStuffFinalizedAnchorVerifier.verify(
        invalidSuggestion,
        lookup,
      )
    yield assertEquals(
      verified.left.map(_.reason),
      Left("voteSignatureMismatch"),
    )

  test(
    "finalized anchor verifier accepts a suggestion across a validator-set rotation boundary",
  ):
    val suggestion = rotatingThreeChain("22")
    val checkpointRoot =
      BootstrapTrustRoot
        .trustedCheckpoint(
          HotStuffWindow(chainId, 1L, 1L, validatorSet.hash),
          validatorSet,
        )
        .toOption
        .get
    val lookup =
      ValidatorSetLookup.fromInventory[IO](
        checkpointRoot,
        Vector(otherValidatorSet),
      )

    for verified <- HotStuffFinalizedAnchorVerifier.verify(suggestion, lookup)
    yield assertEquals(
      verified.map(_.proposal.proposalId),
      Right(suggestion.proposal.proposalId),
    )

  test(
    "finalized anchor verifier accepts reverse-direction rotation boundaries with the same lookup seam",
  ):
    val suggestion =
      rotatingThreeChain(
        seed = "23",
        anchorValidatorSet = otherValidatorSet,
        anchorKeys = otherValidatorKeys,
        nextValidatorSet = validatorSet,
        nextKeys = validatorKeys,
      )
    val checkpointRoot =
      BootstrapTrustRoot
        .trustedCheckpoint(
          HotStuffWindow(chainId, 1L, 1L, otherValidatorSet.hash),
          otherValidatorSet,
        )
        .toOption
        .get
    val lookup =
      ValidatorSetLookup.fromInventory[IO](
        checkpointRoot,
        Vector(validatorSet),
      )

    for verified <- HotStuffFinalizedAnchorVerifier.verify(suggestion, lookup)
    yield assertEquals(
      verified.map(_.proposal.proposalId),
      Right(suggestion.proposal.proposalId),
    )

  test(
    "selectHighestVerified surfaces a safety fault for conflicting same-height finalized anchors",
  ):
    val suggestionA = threeChain("30", anchorProposerIndex = 0)
    val suggestionB = threeChain("40", anchorProposerIndex = 1)
    val lookup =
      ValidatorSetLookup.static[IO](
        BootstrapTrustRoot.staticValidatorSet(validatorSet),
      )

    for selected <- HotStuffFinalizedAnchorVerifier.selectHighestVerified(
        Vector(suggestionA, suggestionB),
        lookup,
      )
    yield assertEquals(
      selected.left.map(_.reason),
      Left("conflictingFinalizedSuggestion"),
    )

  test(
    "selectHighestVerified returns the valid anchor when there is no conflict",
  ):
    val suggestion = threeChain("31")
    val lookup =
      ValidatorSetLookup.static[IO](
        BootstrapTrustRoot.staticValidatorSet(validatorSet),
      )

    for selected <- HotStuffFinalizedAnchorVerifier.selectHighestVerified(
        Vector(suggestion),
        lookup,
      )
    yield assertEquals(
      selected.map(_.map(_.proposal.proposalId)),
      Right(Some(suggestion.proposal.proposalId)),
    )

  test(
    "in-memory bootstrap services expose the locally tracked best finalized anchor",
  ):
    val suggestion = threeChain("50")

    for
      runtime <- createRuntime()
      applyA  <- applyProposal(runtime, suggestion.proposal, 1L)
      applyB  <- applyProposal(runtime, suggestion.finalizedProof.child, 2L)
      applyC <- applyProposal(runtime, suggestion.finalizedProof.grandchild, 3L)
      best <- runtime.bootstrapServices.finalizedAnchorSuggestions
        .bestFinalized(session, chainId)
      diagnostics  <- runtime.bootstrapServices.diagnostics.current
      sinkSnapshot <- runtime.inMemorySink.get.snapshot
    yield
      assertEquals(
        applyA,
        Right(ArtifactApplyResult(applied = true, duplicate = false)),
      )
      assertEquals(
        applyB,
        Right(ArtifactApplyResult(applied = true, duplicate = false)),
      )
      assertEquals(
        applyC,
        Right(ArtifactApplyResult(applied = true, duplicate = false)),
      )
      assertEquals(
        best.map(_.map(_.proposal.proposalId)),
        Right(Some(suggestion.proposal.proposalId)),
      )
      assertEquals(
        diagnostics.chains
          .get(chainId)
          .flatMap(_.bestFinalized)
          .map(_.proposalId),
        Some(suggestion.proposal.proposalId),
      )
      assertEquals(
        diagnostics.chains.get(chainId).flatMap(_.bestFinalized).map(_.chainId),
        Some(chainId),
      )
      assertEquals(
        sinkSnapshot.finalization
          .get(chainId)
          .flatMap(_.bestFinalized)
          .map(_.proposal.proposalId),
        Some(suggestion.proposal.proposalId),
      )

  test("interleaved vote arrivals do not disturb finalized anchor tracking"):
    val suggestion = threeChain("55")
    val anchorVotes = quorumVotes(
      suggestion.proposal.window,
      suggestion.proposal.proposalId,
    )
    val childVotes = quorumVotes(
      suggestion.finalizedProof.child.window,
      suggestion.finalizedProof.child.proposalId,
    )

    for
      runtime <- createRuntime()
      _       <- applyProposal(runtime, suggestion.proposal, 1L)
      _       <- applyVote(runtime, anchorVotes(0), 10L)
      _       <- applyVote(runtime, anchorVotes(1), 11L)
      _       <- applyVote(runtime, anchorVotes(2), 12L)
      _       <- applyProposal(runtime, suggestion.finalizedProof.child, 2L)
      _       <- applyVote(runtime, childVotes(0), 20L)
      _       <- applyVote(runtime, childVotes(1), 21L)
      _       <- applyVote(runtime, childVotes(2), 22L)
      _ <- applyProposal(runtime, suggestion.finalizedProof.grandchild, 3L)
      best <- runtime.bootstrapServices.finalizedAnchorSuggestions
        .bestFinalized(session, chainId)
    yield assertEquals(
      best.map(_.map(_.proposal.proposalId)),
      Right(Some(suggestion.proposal.proposalId)),
    )

  test(
    "in-memory bootstrap services surface a safety fault when conflicting finalized anchors exist",
  ):
    val suggestionA = threeChain("60", anchorProposerIndex = 0)
    val suggestionB = threeChain("70", anchorProposerIndex = 1)

    for
      runtime <- createRuntime()
      _       <- applyProposal(runtime, suggestionA.proposal, 1L)
      _       <- applyProposal(runtime, suggestionA.finalizedProof.child, 2L)
      _ <- applyProposal(runtime, suggestionA.finalizedProof.grandchild, 3L)
      _ <- applyProposal(runtime, suggestionB.proposal, 4L)
      _ <- applyProposal(runtime, suggestionB.finalizedProof.child, 5L)
      _ <- applyProposal(runtime, suggestionB.finalizedProof.grandchild, 6L)
      best <- runtime.bootstrapServices.finalizedAnchorSuggestions
        .bestFinalized(session, chainId)
      diagnostics <- runtime.bootstrapServices.diagnostics.current
    yield
      assertEquals(
        best.left.map(_.reason),
        Left("conflictingFinalizedSuggestion"),
      )
      assertEquals(
        diagnostics.chains
          .get(chainId)
          .map(_.finalizationSafetyFaults.nonEmpty),
        Some(true),
      )

  test(
    "in-memory bootstrap services keep the best safe anchor when another height has a safety fault",
  ):
    val safeLower = threeChain("82", anchorHeightStart = 1L)
    val conflictHighA =
      threeChain("83", anchorProposerIndex = 0, anchorHeightStart = 4L)
    val conflictHighB =
      threeChain("84", anchorProposerIndex = 1, anchorHeightStart = 4L)

    for
      runtime <- createRuntime()
      _       <- applyProposal(runtime, safeLower.proposal, 1L)
      _       <- applyProposal(runtime, safeLower.finalizedProof.child, 2L)
      _       <- applyProposal(runtime, safeLower.finalizedProof.grandchild, 3L)
      _       <- applyProposal(runtime, conflictHighA.proposal, 4L)
      _       <- applyProposal(runtime, conflictHighA.finalizedProof.child, 5L)
      _ <- applyProposal(runtime, conflictHighA.finalizedProof.grandchild, 6L)
      _ <- applyProposal(runtime, conflictHighB.proposal, 7L)
      _ <- applyProposal(runtime, conflictHighB.finalizedProof.child, 8L)
      _ <- applyProposal(runtime, conflictHighB.finalizedProof.grandchild, 9L)
      best <- runtime.bootstrapServices.finalizedAnchorSuggestions
        .bestFinalized(session, chainId)
      diagnostics <- runtime.bootstrapServices.diagnostics.current
    yield
      assertEquals(
        best.map(_.map(_.proposal.proposalId)),
        Right(Some(safeLower.proposal.proposalId)),
      )
      assertEquals(
        diagnostics.chains
          .get(chainId)
          .map(_.finalizationSafetyFaults.nonEmpty),
        Some(true),
      )

  test("trackAll partitions finalized anchors by chainId"):
    val mainSuggestion = threeChain("80", chain = chainId)
    val altSuggestion  = threeChain("81", chain = altChainId)

    val tracked =
      HotStuffFinalizationTracker.trackAll(
        Vector(
          mainSuggestion.proposal,
          mainSuggestion.finalizedProof.child,
          mainSuggestion.finalizedProof.grandchild,
          altSuggestion.proposal,
          altSuggestion.finalizedProof.child,
          altSuggestion.finalizedProof.grandchild,
        ),
      )

    assertEquals(
      tracked.get(chainId).flatMap(_.bestFinalized).map(_.proposal.proposalId),
      Some(mainSuggestion.proposal.proposalId),
    )
    assertEquals(
      tracked
        .get(altChainId)
        .flatMap(_.bestFinalized)
        .map(_.proposal.proposalId),
      Some(altSuggestion.proposal.proposalId),
    )

  private def createRuntime(): IO[HotStuffNodeRuntime[IO]] =
    given GossipClock[IO] = GossipClock.constant[IO](baseInstant)
    HotStuffNodeRuntime
      .create[IO](
        localPeer = PeerIdentity.unsafe("node-a"),
        role = LocalNodeRole.Audit,
        holders = Vector(
          ValidatorKeyHolder(
            validatorSet.members(0).id,
            PeerIdentity.unsafe("node-a"),
            ValidatorKeyHolderStatus.Active,
          ),
          ValidatorKeyHolder(
            validatorSet.members(1).id,
            PeerIdentity.unsafe("node-a"),
            ValidatorKeyHolderStatus.Active,
          ),
          ValidatorKeyHolder(
            validatorSet.members(2).id,
            PeerIdentity.unsafe("node-a"),
            ValidatorKeyHolderStatus.Active,
          ),
        ),
        validatorSet = validatorSet,
        localKeys = Map.empty,
      )
      .flatMap(result =>
        IO.fromEither(
          result
            .leftMap(rejection => new IllegalStateException(rejection.reason)),
        ),
      )

  private def applyProposal(
      runtime: HotStuffNodeRuntime[IO],
      proposal: Proposal,
      cursorValue: Long,
  ): IO[
    Either[CanonicalRejection.ArtifactContractRejected, ArtifactApplyResult],
  ] =
    runtime.sink.applyEvent(
      GossipEvent(
        chainId = proposal.window.chainId,
        topic = GossipTopic.consensusProposal,
        id = HotStuffGossipArtifact.stableIdOf(
          HotStuffGossipArtifact.ProposalArtifact(proposal),
        ),
        cursor = CursorToken.issue(ByteVector.fromLong(cursorValue)),
        ts = baseInstant.plusMillis(cursorValue),
        payload = HotStuffGossipArtifact.ProposalArtifact(proposal),
      ),
    )

  private def applyVote(
      runtime: HotStuffNodeRuntime[IO],
      vote: Vote,
      cursorValue: Long,
  ): IO[
    Either[CanonicalRejection.ArtifactContractRejected, ArtifactApplyResult],
  ] =
    runtime.sink.applyEvent(
      GossipEvent(
        chainId = vote.window.chainId,
        topic = GossipTopic.consensusVote,
        id = HotStuffGossipArtifact.stableIdOf(
          HotStuffGossipArtifact.VoteArtifact(vote),
        ),
        cursor = CursorToken.issue(ByteVector.fromLong(cursorValue)),
        ts = baseInstant.plusMillis(cursorValue),
        payload = HotStuffGossipArtifact.VoteArtifact(vote),
      ),
    )

  private def threeChain(
      seed: String,
      anchorProposerIndex: Int = 0,
      chain: ChainId = chainId,
      anchorHeightStart: Long = 1L,
  ): FinalizedAnchorSuggestion =
    val justifyHeight = anchorHeightStart - 1L
    val bootstrapSubject = QuorumCertificateSubject(
      window =
        HotStuffWindow(chain, justifyHeight, justifyHeight, validatorSet.hash),
      proposalId = ProposalId(hex(seed + "01")),
      blockId = BlockId(hex(seed + "02")),
    )
    val bootstrapQc =
      QuorumCertificateAssembler
        .assemble(
          bootstrapSubject,
          quorumVotes(bootstrapSubject.window, bootstrapSubject.proposalId),
          validatorSet,
        )
        .toOption
        .get
    val anchorBlock =
      block(
        parent = Some(bootstrapSubject.blockId),
        height = anchorHeightStart,
        rootHex = seed + "10",
      )
    val anchor =
      Proposal
        .sign(
          UnsignedProposal(
            window = HotStuffWindow(
              chain,
              anchorHeightStart,
              anchorHeightStart,
              validatorSet.hash,
            ),
            proposer = validatorSet.members(anchorProposerIndex).id,
            targetBlockId = BlockHeader.computeId(anchorBlock),
            block = anchorBlock,
            txSet = ProposalTxSet.empty,
            justify = bootstrapQc,
          ),
          validatorKeys(anchorProposerIndex),
        )
        .toOption
        .get
    val childQc = qcFor(anchor)
    val childBlock =
      block(
        parent = Some(anchor.targetBlockId),
        height = anchorHeightStart + 1L,
        rootHex = seed + "20",
      )
    val child =
      Proposal
        .sign(
          UnsignedProposal(
            window = HotStuffWindow(
              chain,
              anchorHeightStart + 1L,
              anchorHeightStart + 1L,
              validatorSet.hash,
            ),
            proposer = validatorSet.members((anchorProposerIndex + 1) % 3).id,
            targetBlockId = BlockHeader.computeId(childBlock),
            block = childBlock,
            txSet = ProposalTxSet.empty,
            justify = childQc,
          ),
          validatorKeys((anchorProposerIndex + 1) % 3),
        )
        .toOption
        .get
    val grandchildQc = qcFor(child)
    val grandchildBlock =
      block(
        parent = Some(child.targetBlockId),
        height = anchorHeightStart + 2L,
        rootHex = seed + "30",
      )
    val grandchild =
      Proposal
        .sign(
          UnsignedProposal(
            window = HotStuffWindow(
              chain,
              anchorHeightStart + 2L,
              anchorHeightStart + 2L,
              validatorSet.hash,
            ),
            proposer = validatorSet.members((anchorProposerIndex + 2) % 3).id,
            targetBlockId = BlockHeader.computeId(grandchildBlock),
            block = grandchildBlock,
            txSet = ProposalTxSet.empty,
            justify = grandchildQc,
          ),
          validatorKeys((anchorProposerIndex + 2) % 3),
        )
        .toOption
        .get

    FinalizedAnchorSuggestion(
      proposal = anchor,
      finalizedProof = FinalizedProof(
        child = child,
        grandchild = grandchild,
      ),
    )

  private def qcFor(
      proposal: Proposal,
  ): QuorumCertificate =
    qcForWithValidatorSet(proposal, validatorSet, validatorKeys)

  private def quorumVotes(
      window: HotStuffWindow,
      proposalId: ProposalId,
  ): Vector[Vote] =
    quorumVotesWithValidatorSet(window, proposalId, validatorSet, validatorKeys)

  private def qcForWithValidatorSet(
      proposal: Proposal,
      validatorSet: ValidatorSet,
      keyPairs: Vector[KeyPair],
  ): QuorumCertificate =
    QuorumCertificateAssembler
      .assemble(
        subject = QuorumCertificateSubject(
          window = proposal.window,
          proposalId = proposal.proposalId,
          blockId = proposal.targetBlockId,
        ),
        votes = quorumVotesWithValidatorSet(
          proposal.window,
          proposal.proposalId,
          validatorSet,
          keyPairs,
        ),
        validatorSet = validatorSet,
      )
      .toOption
      .get

  private def quorumVotesWithValidatorSet(
      window: HotStuffWindow,
      proposalId: ProposalId,
      validatorSet: ValidatorSet,
      keyPairs: Vector[KeyPair],
  ): Vector[Vote] =
    Vector(0, 1, 2).map: index =>
      Vote
        .sign(
          UnsignedVote(
            window = window,
            voter = validatorSet.members(index).id,
            targetProposalId = proposalId,
          ),
          keyPairs(index),
        )
        .toOption
        .get

  private def rotatingThreeChain(
      seed: String,
      chain: ChainId = chainId,
      anchorHeightStart: Long = 1L,
      anchorValidatorSet: ValidatorSet = validatorSet,
      anchorKeys: Vector[KeyPair] = validatorKeys,
      nextValidatorSet: ValidatorSet = otherValidatorSet,
      nextKeys: Vector[KeyPair] = otherValidatorKeys,
  ): FinalizedAnchorSuggestion =
    val justifyHeight = anchorHeightStart - 1L
    val bootstrapSubject = QuorumCertificateSubject(
      window = HotStuffWindow(
        chain,
        justifyHeight,
        justifyHeight,
        anchorValidatorSet.hash,
      ),
      proposalId = ProposalId(hex(seed + "41")),
      blockId = BlockId(hex(seed + "42")),
    )
    val bootstrapQc =
      QuorumCertificateAssembler
        .assemble(
          bootstrapSubject,
          quorumVotesWithValidatorSet(
            bootstrapSubject.window,
            bootstrapSubject.proposalId,
            anchorValidatorSet,
            anchorKeys,
          ),
          anchorValidatorSet,
        )
        .toOption
        .get
    val anchorBlock =
      block(
        parent = Some(bootstrapSubject.blockId),
        height = anchorHeightStart,
        rootHex = seed + "43",
      )
    val anchor =
      Proposal
        .sign(
          UnsignedProposal(
            window = HotStuffWindow(
              chain,
              anchorHeightStart,
              anchorHeightStart,
              anchorValidatorSet.hash,
            ),
            proposer = anchorValidatorSet.members(0).id,
            targetBlockId = BlockHeader.computeId(anchorBlock),
            block = anchorBlock,
            txSet = ProposalTxSet.empty,
            justify = bootstrapQc,
          ),
          anchorKeys(0),
        )
        .toOption
        .get
    val childQc = qcForWithValidatorSet(anchor, anchorValidatorSet, anchorKeys)
    val childBlock =
      block(
        parent = Some(anchor.targetBlockId),
        height = anchorHeightStart + 1L,
        rootHex = seed + "44",
      )
    val child =
      Proposal
        .sign(
          UnsignedProposal(
            window = HotStuffWindow(
              chain,
              anchorHeightStart + 1L,
              anchorHeightStart + 1L,
              nextValidatorSet.hash,
            ),
            proposer = nextValidatorSet.members(0).id,
            targetBlockId = BlockHeader.computeId(childBlock),
            block = childBlock,
            txSet = ProposalTxSet.empty,
            justify = childQc,
          ),
          nextKeys(0),
        )
        .toOption
        .get
    val grandchildQc =
      qcForWithValidatorSet(child, nextValidatorSet, nextKeys)
    val grandchildBlock =
      block(
        parent = Some(child.targetBlockId),
        height = anchorHeightStart + 2L,
        rootHex = seed + "45",
      )
    val grandchild =
      Proposal
        .sign(
          UnsignedProposal(
            window = HotStuffWindow(
              chain,
              anchorHeightStart + 2L,
              anchorHeightStart + 2L,
              nextValidatorSet.hash,
            ),
            proposer = nextValidatorSet.members(1).id,
            targetBlockId = BlockHeader.computeId(grandchildBlock),
            block = grandchildBlock,
            txSet = ProposalTxSet.empty,
            justify = grandchildQc,
          ),
          nextKeys(1),
        )
        .toOption
        .get

    FinalizedAnchorSuggestion(
      proposal = anchor,
      finalizedProof = FinalizedProof(
        child = child,
        grandchild = grandchild,
      ),
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
    BlockHeader(
      parent = parent,
      height = BlockHeight.unsafeFromLong(height),
      stateRoot = StateRoot(hex(rootHex)),
      bodyRoot = BodyRoot(hex(rootHex)),
      timestamp =
        BlockTimestamp.unsafeFromEpochMillis(baseInstant.toEpochMilli + height),
    )
