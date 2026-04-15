package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.nio.ByteBuffer

import java.time.{Duration, Instant}

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import munit.CatsEffectSuite
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
import org.sigilaris.node.gossip.*
import org.sigilaris.node.gossip.tx.{
  ControlBatchOutcome,
  TxGossipRuntime,
  TxGossipStateStore,
}

final class HotStuffGossipLoopbackSuite extends CatsEffectSuite:

  private val chainId     = ChainId.unsafe("chain-main")
  private val baseInstant = Instant.parse("2026-04-01T00:00:00Z")
  private val hotStuffRuntimePolicy =
    HotStuffRuntimeBootstrap.DefaultRuntimePolicy
  private val validatorKeys = Vector.fill(4)(CryptoOps.generate())
  private val validatorSet = ValidatorSet.unsafe(
    validatorKeys.zipWithIndex.map: (keyPair, index) =>
      ValidatorMember(
        ValidatorId.unsafe(s"validator-${index + 1}"),
        keyPair.publicKey,
      ),
  )
  private val subscription = SessionSubscription.unsafe(
    ChainTopic(chainId, GossipTopic.consensusProposal),
    ChainTopic(chainId, GossipTopic.consensusVote),
    ChainTopic(chainId, GossipTopic.consensusTimeoutVote),
    ChainTopic(chainId, GossipTopic.consensusNewView),
  )

  test("consensus topics honor exact known-set, requestById, and QC assembly"):
    val holders = Vector(
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
      ValidatorKeyHolder(
        validatorSet.members(3).id,
        PeerIdentity.unsafe("node-c"),
        ValidatorKeyHolderStatus.Active,
      ),
    )

    for
      a <- Harness.create(
        localNodeId = "node-a",
        remoteNodeId = "node-b",
        role = LocalNodeRole.Validator,
        holders = holders,
        localKeys = Map(
          validatorSet.members(0).id -> validatorKeys(0),
          validatorSet.members(1).id -> validatorKeys(1),
          validatorSet.members(2).id -> validatorKeys(2),
        ),
      )
      b <- Harness.create(
        localNodeId = "node-b",
        remoteNodeId = "node-a",
        role = LocalNodeRole.Audit,
        holders = holders,
        localKeys = Map.empty,
      )
      opened <- openOutbound(a, b)
      sessionId = opened.proposal.sessionId
      justify   = bootstrapQc()
      proposalEvent <- a.consensus
        .emitProposal(
          proposer = validatorSet.members.head.id,
          block = block(
            parent = Some(justify.subject.blockId),
            height = 2L,
            rootHex = "81",
          ),
          txSet = ProposalTxSet.empty,
          window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
          justify = justify,
          ts = baseInstant,
        )
        .flatMap(unwrapPolicy)
      proposal = proposalPayload(proposalEvent)
      _ <- a.consensus
        .emitVote(
          validatorSet.members(0).id,
          proposal,
          baseInstant.plusMillis(1),
        )
        .flatMap(unwrapPolicy)
      _ <- a.consensus
        .emitVote(
          validatorSet.members(1).id,
          proposal,
          baseInstant.plusMillis(2),
        )
        .flatMap(unwrapPolicy)
      _ <- a.consensus
        .emitVote(
          validatorSet.members(2).id,
          proposal,
          baseInstant.plusMillis(3),
        )
        .flatMap(unwrapPolicy)
      _ <- a.gossip.receiveControlBatch(
        sessionId,
        controlBatch(
          "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
          Vector(
            ControlOp.SetKnownExact(
              proposalScope(proposal),
              Vector(
                HotStuffGossipArtifact.stableIdOf(
                  HotStuffGossipArtifact.ProposalArtifact(proposal),
                ),
              ),
            ),
          ),
        ),
      )
      firstPoll     <- a.gossip.pollEvents(sessionId)
      firstReceive  <- b.gossip.receiveEvents(sessionId, firstPoll.toOption.get)
      firstSnapshot <- sinkSnapshot(b.consensus)
      _ <- a.gossip.receiveControlBatch(
        sessionId,
        controlBatch(
          "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
          Vector(
            ControlOp.RequestByIdExact(
              proposalScope(proposal),
              Vector(
                HotStuffGossipArtifact.stableIdOf(
                  HotStuffGossipArtifact.ProposalArtifact(proposal),
                ),
              ),
            ),
          ),
        ),
      )
      secondPoll <- a.gossip.pollEvents(sessionId)
      secondReceive <- b.gossip.receiveEvents(
        sessionId,
        secondPoll.toOption.get,
      )
      secondSnapshot <- sinkSnapshot(b.consensus)
    yield
      assertEquals(
        firstReceive.toOption.get.applied.collect:
          case event if event.topic == GossipTopic.consensusVote =>
            votePayload(event).voter.value
        ,
        Vector("validator-1", "validator-2", "validator-3"),
      )
      assert(!firstSnapshot.proposals.contains(proposal.proposalId))
      assert(!firstSnapshot.qcs.contains(proposal.proposalId))
      assertEquals(
        secondReceive.toOption.get.applied.collect:
          case event if event.topic == GossipTopic.consensusProposal =>
            proposalPayload(event).proposalId
        ,
        Vector(proposal.proposalId),
      )
      assert(secondSnapshot.proposals.contains(proposal.proposalId))
      assert(secondSnapshot.qcs.contains(proposal.justify.subject.proposalId))
      assert(secondSnapshot.qcs.contains(proposal.proposalId))

  test(
    "audit nodes follow consensus artifacts read-only and cannot emit locally",
  ):
    val holders = Vector(
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
    )

    for
      a <- Harness.create(
        "node-a",
        "node-b",
        LocalNodeRole.Validator,
        holders,
        Map(
          validatorSet.members(0).id -> validatorKeys(0),
          validatorSet.members(1).id -> validatorKeys(1),
          validatorSet.members(2).id -> validatorKeys(2),
        ),
      )
      b <- Harness.create(
        "node-b",
        "node-a",
        LocalNodeRole.Audit,
        holders,
        Map.empty,
      )
      opened <- openOutbound(a, b)
      sessionId = opened.proposal.sessionId
      justify   = bootstrapQc()
      proposalEvent <- a.consensus
        .emitProposal(
          proposer = validatorSet.members.head.id,
          block = block(
            parent = Some(justify.subject.blockId),
            height = 2L,
            rootHex = "82",
          ),
          txSet = ProposalTxSet.empty,
          window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
          justify = justify,
          ts = baseInstant,
        )
        .flatMap(unwrapPolicy)
      proposal           = proposalPayload(proposalEvent)
      timeoutWindow      = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash)
      timeoutCertificate = timeoutCertificateFor(timeoutWindow, justify)
      _ <- a.consensus
        .emitVote(
          validatorSet.members(0).id,
          proposal,
          baseInstant.plusMillis(1),
        )
        .flatMap(unwrapPolicy)
      polled <- a.gossip.pollEvents(sessionId)
      _      <- b.gossip.receiveEvents(sessionId, polled.toOption.get)
      emitAuditProposal <- b.consensus.emitProposal(
        proposer = validatorSet.members.head.id,
        block = block(
          parent = Some(justify.subject.blockId),
          height = 3L,
          rootHex = "83",
        ),
        txSet = ProposalTxSet.empty,
        window = HotStuffWindow(chainId, 3L, 2L, validatorSet.hash),
        justify = justify,
        ts = baseInstant.plusMillis(10),
      )
      emitAuditVote <- b.consensus.emitVote(
        validatorSet.members.head.id,
        proposal,
        baseInstant.plusMillis(11),
      )
      emitAuditTimeoutVote <- b.consensus.emitTimeoutVote(
        voter = validatorSet.members.head.id,
        window = timeoutWindow,
        highestKnownQc = justify,
        ts = baseInstant.plusMillis(12),
      )
      emitAuditNewView <- b.consensus.emitNewView(
        sender = validatorSet.members.head.id,
        highestKnownQc = justify,
        timeoutCertificate = timeoutCertificate,
        ts = baseInstant.plusMillis(13),
      )
      snapshot <- sinkSnapshot(b.consensus)
    yield
      assertEquals(
        emitAuditProposal.left.map(_.reason),
        Left("auditNodeCannotEmit"),
      )
      assertEquals(
        emitAuditVote.left.map(_.reason),
        Left("auditNodeCannotEmit"),
      )
      assertEquals(
        emitAuditTimeoutVote.left.map(_.reason),
        Left("auditNodeCannotEmit"),
      )
      assertEquals(
        emitAuditNewView.left.map(_.reason),
        Left("auditNodeCannotEmit"),
      )
      assert(snapshot.proposals.contains(proposal.proposalId))
      assert(snapshot.votes.nonEmpty)

  test(
    "pacemaker runtime attaches only for validators with local validator keys",
  ):
    val holders = Vector(
      ValidatorKeyHolder(
        validatorSet.members(0).id,
        PeerIdentity.unsafe("node-a"),
        ValidatorKeyHolderStatus.Active,
      ),
      ValidatorKeyHolder(
        validatorSet.members(1).id,
        PeerIdentity.unsafe("node-b"),
        ValidatorKeyHolderStatus.Active,
      ),
    )

    for
      validatorWithKey <- Harness.create(
        "node-a",
        "node-b",
        LocalNodeRole.Validator,
        holders,
        Map(validatorSet.members(0).id -> validatorKeys(0)),
      )
      validatorWithoutKey <- Harness.create(
        "node-b",
        "node-a",
        LocalNodeRole.Validator,
        holders,
        Map.empty,
      )
      auditNode <- Harness.create(
        "node-c",
        "node-a",
        LocalNodeRole.Audit,
        holders,
        Map.empty,
      )
      validatorSnapshot <- validatorWithKey.consensus.currentPacemakerSnapshot
      missingKeySnapshot <-
        validatorWithoutKey.consensus.currentPacemakerSnapshot
      auditSnapshot <- auditNode.consensus.currentPacemakerSnapshot
    yield
      assert(validatorSnapshot.nonEmpty)
      assertEquals(missingKeySnapshot, None)
      assertEquals(auditSnapshot, None)

  test(
    "timeout-vote and new-view exact-known request-by-id semantics match the proposal and vote baseline",
  ):
    val holders = Vector(
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
    )

    for
      a <- Harness.create(
        "node-a",
        "node-b",
        LocalNodeRole.Validator,
        holders,
        Map(
          validatorSet.members(0).id -> validatorKeys(0),
          validatorSet.members(1).id -> validatorKeys(1),
          validatorSet.members(2).id -> validatorKeys(2),
        ),
      )
      b <- Harness.create(
        "node-b",
        "node-a",
        LocalNodeRole.Audit,
        holders,
        Map.empty,
      )
      opened <- openOutbound(a, b)
      sessionId      = opened.proposal.sessionId
      highestKnownQc = bootstrapQc()
      timeoutWindow  = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash)
      timeoutVote1Event <- a.consensus
        .emitTimeoutVote(
          voter = validatorSet.members(0).id,
          window = timeoutWindow,
          highestKnownQc = highestKnownQc,
          ts = baseInstant.plusMillis(1),
        )
        .flatMap(unwrapPolicy)
      timeoutVote2Event <- a.consensus
        .emitTimeoutVote(
          voter = validatorSet.members(1).id,
          window = timeoutWindow,
          highestKnownQc = highestKnownQc,
          ts = baseInstant.plusMillis(2),
        )
        .flatMap(unwrapPolicy)
      timeoutVote3Event <- a.consensus
        .emitTimeoutVote(
          voter = validatorSet.members(2).id,
          window = timeoutWindow,
          highestKnownQc = highestKnownQc,
          ts = baseInstant.plusMillis(3),
        )
        .flatMap(unwrapPolicy)
      timeoutVote1 = timeoutVotePayload(timeoutVote1Event)
      timeoutVote2 = timeoutVotePayload(timeoutVote2Event)
      timeoutVote3 = timeoutVotePayload(timeoutVote3Event)
      timeoutCertificate = TimeoutCertificateAssembler
        .assemble(
          timeoutVote1.subject,
          Vector(timeoutVote1, timeoutVote2, timeoutVote3),
          validatorSet,
        )
        .toOption
        .get
      newViewEvent <- a.consensus
        .emitNewView(
          sender = validatorSet.members(0).id,
          highestKnownQc = highestKnownQc,
          timeoutCertificate = timeoutCertificate,
          ts = baseInstant.plusMillis(4),
        )
        .flatMap(unwrapPolicy)
      newView = newViewPayload(newViewEvent)
      _ <- a.gossip.receiveControlBatch(
        sessionId,
        controlBatch(
          "abababab-abab-4bab-8bab-ababababacac",
          Vector(
            ControlOp.SetKnownExact(
              timeoutVoteScope(timeoutVote1),
              Vector(
                HotStuffGossipArtifact.stableIdOf(
                  HotStuffGossipArtifact.TimeoutVoteArtifact(timeoutVote1),
                ),
              ),
            ),
            ControlOp.SetKnownExact(
              newViewScope(newView),
              Vector(
                HotStuffGossipArtifact.stableIdOf(
                  HotStuffGossipArtifact.NewViewArtifact(newView),
                ),
              ),
            ),
          ),
        ),
      )
      firstPoll     <- a.gossip.pollEvents(sessionId)
      firstReceive  <- b.gossip.receiveEvents(sessionId, firstPoll.toOption.get)
      firstSnapshot <- sinkSnapshot(b.consensus)
      requestKnown <- a.gossip.receiveControlBatch(
        sessionId,
        controlBatch(
          "bcbcbcbc-bcbc-4cbc-8cbc-bcbcbcbcbcbc",
          Vector(
            ControlOp.RequestByIdExact(
              timeoutVoteScope(timeoutVote1),
              Vector(
                HotStuffGossipArtifact.stableIdOf(
                  HotStuffGossipArtifact.TimeoutVoteArtifact(timeoutVote1),
                ),
              ),
            ),
            ControlOp.RequestByIdExact(
              newViewScope(newView),
              Vector(
                HotStuffGossipArtifact.stableIdOf(
                  HotStuffGossipArtifact.NewViewArtifact(newView),
                ),
              ),
            ),
          ),
        ),
      )
      secondPoll <- a.gossip.pollEvents(sessionId)
      secondReceive <- b.gossip.receiveEvents(
        sessionId,
        secondPoll.toOption.get,
      )
      secondSnapshot <- sinkSnapshot(b.consensus)
    yield
      assertEquals(
        firstReceive.toOption.get.applied.collect:
          case event if event.topic == GossipTopic.consensusTimeoutVote =>
            timeoutVotePayload(event).voter.value
        ,
        Vector("validator-2", "validator-3"),
      )
      assert(firstSnapshot.timeoutCertificates.isEmpty)
      assert(firstSnapshot.newViews.isEmpty)
      assertEquals(requestKnown, Right(ControlBatchOutcome.Applied))
      assertEquals(
        secondReceive.toOption.get.applied.collect:
          case event if event.topic == GossipTopic.consensusTimeoutVote =>
            timeoutVotePayload(event).timeoutVoteId
        ,
        Vector(timeoutVote1.timeoutVoteId),
      )
      assertEquals(
        secondReceive.toOption.get.applied.collect:
          case event if event.topic == GossipTopic.consensusNewView =>
            newViewPayload(event).newViewId
        ,
        Vector(newView.newViewId),
      )
      assertEquals(
        secondSnapshot.timeoutVotes.keySet,
        Set(
          timeoutVote1.timeoutVoteId,
          timeoutVote2.timeoutVoteId,
          timeoutVote3.timeoutVoteId,
        ),
      )
      assert(secondSnapshot.timeoutCertificates.contains(timeoutVote1.subject))
      assertEquals(secondSnapshot.newViews.keySet, Set(newView.newViewId))

  test(
    "automatic pacemaker emits timeout-votes and advances new-view after a stalled leader without manual timeout artifact injection",
  ):
    val holders = Vector(
      ValidatorKeyHolder(
        validatorSet.members(0).id,
        PeerIdentity.unsafe("node-a"),
        ValidatorKeyHolderStatus.Active,
      ),
      ValidatorKeyHolder(
        validatorSet.members(1).id,
        PeerIdentity.unsafe("node-b"),
        ValidatorKeyHolderStatus.Active,
      ),
      ValidatorKeyHolder(
        validatorSet.members(2).id,
        PeerIdentity.unsafe("node-c"),
        ValidatorKeyHolderStatus.Active,
      ),
      ValidatorKeyHolder(
        validatorSet.members(3).id,
        PeerIdentity.unsafe("node-d"),
        ValidatorKeyHolderStatus.Active,
      ),
    )

    val proposalWindow  = HotStuffWindow(chainId, 2L, 2L, validatorSet.hash)
    val stalledWindow   = HotStuffWindow(chainId, 3L, 3L, validatorSet.hash)
    val recoveredWindow = HotStuffWindow(chainId, 3L, 4L, validatorSet.hash)
    val localLeaderKey =
      HotStuffPacemakerKey(chainId, validatorSet.members(0).id)

    for
      a <- Harness.createWithPeers(
        "node-a",
        List("node-b", "node-c"),
        LocalNodeRole.Validator,
        holders,
        Map(validatorSet.members(0).id -> validatorKeys(0)),
      )
      b <- Harness.createWithPeers(
        "node-b",
        List("node-a", "node-c"),
        LocalNodeRole.Validator,
        holders,
        Map(validatorSet.members(1).id -> validatorKeys(1)),
      )
      c <- Harness.createWithPeers(
        "node-c",
        List("node-a", "node-b"),
        LocalNodeRole.Validator,
        holders,
        Map(validatorSet.members(2).id -> validatorKeys(2)),
      )
      links <- openDirectedMesh(Vector(a, b, c))
      justify = bootstrapQc()
      proposalEvent <- c.consensus
        .emitProposal(
          proposer = validatorSet.members(2).id,
          block = block(
            parent = Some(justify.subject.blockId),
            height = 2L,
            rootHex = "91",
          ),
          txSet = ProposalTxSet.empty,
          window = proposalWindow,
          justify = justify,
          ts = baseInstant,
        )
        .flatMap(unwrapPolicy)
      _ <- applyLocalEvent(c.consensus, proposalEvent)
      proposal = proposalPayload(proposalEvent)
      _ <- drainLinks(links)
      _ <- Vector(
        a -> validatorSet.members(0).id,
        b -> validatorSet.members(1).id,
        c -> validatorSet.members(2).id,
      ).zipWithIndex.traverse_ { case ((node, voter), index) =>
        node.consensus
          .emitVote(voter, proposal, baseInstant.plusMillis(index.toLong + 1L))
          .flatMap(unwrapPolicy)
          .flatMap(applyLocalEvent(node.consensus, _))
      }
      _             <- drainLinks(links)
      beforeTimeout <- a.consensus.currentPacemakerSnapshot
      _ <- Vector(a, b, c).traverse_(
        _.clock.advance(
          HotStuffPacemakerPolicy.default.baseTimeout.plusSeconds(1L),
        ),
      )
      _            <- drainLinks(links, maxRounds = 12)
      snapshotA    <- sinkSnapshot(a.consensus)
      snapshotB    <- sinkSnapshot(b.consensus)
      snapshotC    <- sinkSnapshot(c.consensus)
      afterTimeout <- a.consensus.currentPacemakerSnapshot
    yield
      val snapshots = Vector(snapshotA, snapshotB, snapshotC)
      val proposalWindows =
        snapshots.map(snapshot =>
          snapshot.proposals.valuesIterator.map(_.window).toSet,
        )
      val voteWindows =
        snapshots.map(snapshot =>
          snapshot.votes.valuesIterator.map(_.window).toSet,
        )
      assertEquals(
        beforeTimeout
          .flatMap(_.entries.get(localLeaderKey))
          .flatMap(_.state.map(_.activeWindow)),
        Some(stalledWindow),
      )
      assert(
        snapshots.forall(
          _.timeoutVotes.valuesIterator.exists(
            _.subject.window === stalledWindow,
          ),
        ),
      )
      assert(
        snapshots.forall(
          _.timeoutCertificates.keySet.exists(_.window === stalledWindow),
        ),
      )
      assert(
        snapshots.forall(
          _.newViews.valuesIterator.exists(_.window === recoveredWindow),
        ),
      )
      assertEquals(
        proposalWindows,
        Vector.fill(3)(Set(proposalWindow)),
      )
      assertEquals(
        voteWindows,
        Vector.fill(3)(Set(proposalWindow)),
      )
      assertEquals(
        afterTimeout
          .flatMap(_.entries.get(localLeaderKey))
          .flatMap(_.state.map(_.activeWindow)),
        Some(recoveredWindow),
      )
      assertEquals(
        afterTimeout
          .flatMap(_.entries.get(localLeaderKey))
          .flatMap(_.proposalEligibility),
        Some(
          HotStuffPacemakerProposalEligibility.EligibleAsLeader(
            validatorSet.members(0).id,
          ),
        ),
      )

  test(
    "automatic consensus emits votes and follows consecutive local leaders after a single seeded proposal",
  ):
    val holders = Vector(
      ValidatorKeyHolder(
        validatorSet.members(0).id,
        PeerIdentity.unsafe("node-a"),
        ValidatorKeyHolderStatus.Active,
      ),
      ValidatorKeyHolder(
        validatorSet.members(1).id,
        PeerIdentity.unsafe("node-b"),
        ValidatorKeyHolderStatus.Active,
      ),
      ValidatorKeyHolder(
        validatorSet.members(2).id,
        PeerIdentity.unsafe("node-c"),
        ValidatorKeyHolderStatus.Active,
      ),
      ValidatorKeyHolder(
        validatorSet.members(3).id,
        PeerIdentity.unsafe("node-d"),
        ValidatorKeyHolderStatus.Active,
      ),
    )
    val automaticValidators =
      Set(
        validatorSet.members(0).id,
        validatorSet.members(1).id,
        validatorSet.members(2).id,
      )
    val seedHeight =
      (1L to 12L)
        .find: height =>
          (0L to 2L).forall: delta =>
            automaticValidators.contains(
              HotStuffPacemaker.deterministicLeader(
                HotStuffWindow(
                  chainId,
                  height + delta,
                  height + delta,
                  validatorSet.hash,
                ),
                validatorSet,
              ),
            )
        .getOrElse(
          throw new IllegalStateException("expected local leader run"),
        )
    val seedWindow =
      HotStuffWindow(chainId, seedHeight, seedHeight, validatorSet.hash)
    val seedLeader =
      HotStuffPacemaker.deterministicLeader(seedWindow, validatorSet)

    for
      a <- Harness.createWithPeers(
        "node-a",
        List("node-b", "node-c"),
        LocalNodeRole.Validator,
        holders,
        Map(validatorSet.members(0).id -> validatorKeys(0)),
        automaticConsensus = true,
      )
      b <- Harness.createWithPeers(
        "node-b",
        List("node-a", "node-c"),
        LocalNodeRole.Validator,
        holders,
        Map(validatorSet.members(1).id -> validatorKeys(1)),
        automaticConsensus = true,
      )
      c <- Harness.createWithPeers(
        "node-c",
        List("node-a", "node-b"),
        LocalNodeRole.Validator,
        holders,
        Map(validatorSet.members(2).id -> validatorKeys(2)),
        automaticConsensus = true,
      )
      links <- openDirectedMesh(Vector(a, b, c))
      leaderHarness <- IO.fromOption(
        Map(
          validatorSet.members(0).id -> a,
          validatorSet.members(1).id -> b,
          validatorSet.members(2).id -> c,
        ).get(seedLeader),
      )(new IllegalStateException("seed leader was not local"))
      seedEvent <- leaderHarness.consensus
        .emitProposal(
          proposer = seedLeader,
          block = block(
            parent = Some(bootstrapQc().subject.blockId),
            height = seedHeight,
            rootHex = "97",
          ),
          txSet = ProposalTxSet.empty,
          window = seedWindow,
          justify = bootstrapQc(),
          ts = baseInstant,
        )
        .flatMap(unwrapPolicy)
      _         <- applyLocalEvent(leaderHarness.consensus, seedEvent)
      _         <- drainLinks(links, maxRounds = 24)
      snapshotA <- sinkSnapshot(a.consensus)
      snapshotB <- sinkSnapshot(b.consensus)
      snapshotC <- sinkSnapshot(c.consensus)
    yield
      val expectedHeights =
        Set(seedHeight, seedHeight + 1L, seedHeight + 2L)
      val snapshots = Vector(snapshotA, snapshotB, snapshotC)
      val proposalHeights =
        snapshots.map(snapshot =>
          snapshot.proposals.valuesIterator
            .map(_.block.height.toBigNat.toBigInt.longValue)
            .toSet,
        )
      val qcHeights =
        snapshots.map(snapshot =>
          snapshot.qcs.valuesIterator
            .map(_.subject.window.height.toBigNat.toBigInt.longValue)
            .toSet,
        )
      val voteWindows =
        snapshots.map(snapshot =>
          snapshot.votes.valuesIterator
            .map(vote =>
              (vote.voter.value, vote.window.height.toBigNat.toBigInt.longValue),
            )
            .toSet,
        )
      val seedStateRoot =
        snapshots.head.proposals.valuesIterator
          .find(_.block.height.toBigNat.toBigInt.longValue == seedHeight)
          .map(_.block.stateRoot)
          .getOrElse(throw new IllegalStateException("missing seed proposal"))
      val automaticStateRoots =
        snapshots.map(snapshot =>
          snapshot.proposals.valuesIterator
            .filter(proposal =>
              proposal.block.height.toBigNat.toBigInt.longValue > seedHeight,
            )
            .map(_.block.stateRoot)
            .toSet,
        )
      assert(
        proposalHeights.forall(expectedHeights.subsetOf),
        s"proposals=$proposalHeights qcs=$qcHeights votes=$voteWindows",
      )
      assert(
        qcHeights.forall(Set(seedHeight, seedHeight + 1L).subsetOf),
        s"proposals=$proposalHeights qcs=$qcHeights votes=$voteWindows",
      )
      assert(
        automaticStateRoots.forall(_ == Set(seedStateRoot)),
        s"automaticStateRoots=$automaticStateRoots seedStateRoot=$seedStateRoot",
      )

  test(
    "audit followers relay validated proposal and vote artifacts downstream without local signing",
  ):
    val holders = Vector(
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
      ValidatorKeyHolder(
        validatorSet.members(3).id,
        PeerIdentity.unsafe("node-d"),
        ValidatorKeyHolderStatus.Active,
      ),
    )

    for
      a <- Harness.create(
        "node-a",
        "node-b",
        LocalNodeRole.Validator,
        holders,
        Map(
          validatorSet.members(0).id -> validatorKeys(0),
          validatorSet.members(1).id -> validatorKeys(1),
          validatorSet.members(2).id -> validatorKeys(2),
        ),
      )
      b <- Harness.createWithPeers(
        "node-b",
        List("node-a", "node-c"),
        LocalNodeRole.Audit,
        holders,
        Map.empty,
      )
      c <- Harness.create(
        "node-c",
        "node-b",
        LocalNodeRole.Audit,
        holders,
        Map.empty,
      )
      openedAb <- openOutbound(a, b)
      openedBc <- openOutbound(b, c)
      sessionIdAb = openedAb.proposal.sessionId
      sessionIdBc = openedBc.proposal.sessionId
      justify     = bootstrapQc()
      proposalEvent <- a.consensus
        .emitProposal(
          proposer = validatorSet.members.head.id,
          block = block(
            parent = Some(justify.subject.blockId),
            height = 2L,
            rootHex = "89",
          ),
          txSet = ProposalTxSet.empty,
          window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
          justify = justify,
          ts = baseInstant,
        )
        .flatMap(unwrapPolicy)
      proposal = proposalPayload(proposalEvent)
      voteEvent <- a.consensus
        .emitVote(
          validatorSet.members.head.id,
          proposal,
          baseInstant.plusMillis(1),
        )
        .flatMap(unwrapPolicy)
      aPoll <- a.gossip.pollEvents(sessionIdAb)
      _     <- b.gossip.receiveEvents(sessionIdAb, aPoll.toOption.get)
      bPoll <- b.gossip.pollEvents(sessionIdBc)
      bRelayProposalIds = bPoll.toOption.get.collect:
        case EventStreamMessage.Event(event)
            if event.topic == GossipTopic.consensusProposal =>
          proposalPayload(event).proposalId
      bRelayVoteIds = bPoll.toOption.get.collect:
        case EventStreamMessage.Event(event)
            if event.topic == GossipTopic.consensusVote =>
          votePayload(event).voteId
      cReceive  <- c.gossip.receiveEvents(sessionIdBc, bPoll.toOption.get)
      cSnapshot <- sinkSnapshot(c.consensus)
    yield
      assertEquals(b.consensus.localKeys, Map.empty)
      assertEquals(bRelayProposalIds, Vector(proposal.proposalId))
      assertEquals(bRelayVoteIds, Vector(votePayload(voteEvent).voteId))
      assertEquals(
        cReceive.toOption.get.applied.collect:
          case event if event.topic == GossipTopic.consensusProposal =>
            proposalPayload(event).proposalId
        ,
        Vector(proposal.proposalId),
      )
      assertEquals(
        cReceive.toOption.get.applied.collect:
          case event if event.topic == GossipTopic.consensusVote =>
            votePayload(event).voteId
        ,
        Vector(votePayload(voteEvent).voteId),
      )
      assert(cSnapshot.proposals.contains(proposal.proposalId))
      assert(cSnapshot.votes.contains(votePayload(voteEvent).voteId))

  test("validator-role followers do not relay validated artifacts downstream"):
    val holders = Vector(
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
      ValidatorKeyHolder(
        validatorSet.members(3).id,
        PeerIdentity.unsafe("node-d"),
        ValidatorKeyHolderStatus.Active,
      ),
    )

    for
      a <- Harness.create(
        "node-a",
        "node-b",
        LocalNodeRole.Validator,
        holders,
        Map(
          validatorSet.members(0).id -> validatorKeys(0),
          validatorSet.members(1).id -> validatorKeys(1),
          validatorSet.members(2).id -> validatorKeys(2),
        ),
      )
      b <- Harness.createWithPeers(
        "node-b",
        List("node-a", "node-c"),
        LocalNodeRole.Validator,
        holders,
        Map.empty,
      )
      c <- Harness.create(
        "node-c",
        "node-b",
        LocalNodeRole.Audit,
        holders,
        Map.empty,
      )
      openedAb <- openOutbound(a, b)
      openedBc <- openOutbound(b, c)
      sessionIdAb = openedAb.proposal.sessionId
      sessionIdBc = openedBc.proposal.sessionId
      justify     = bootstrapQc()
      proposalEvent <- a.consensus
        .emitProposal(
          proposer = validatorSet.members.head.id,
          block = block(
            parent = Some(justify.subject.blockId),
            height = 2L,
            rootHex = "8a",
          ),
          txSet = ProposalTxSet.empty,
          window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
          justify = justify,
          ts = baseInstant,
        )
        .flatMap(unwrapPolicy)
      proposal = proposalPayload(proposalEvent)
      _ <- a.consensus
        .emitVote(
          validatorSet.members.head.id,
          proposal,
          baseInstant.plusMillis(1),
        )
        .flatMap(unwrapPolicy)
      aPoll <- a.gossip.pollEvents(sessionIdAb)
      _     <- b.gossip.receiveEvents(sessionIdAb, aPoll.toOption.get)
      bPoll <- b.gossip.pollEvents(sessionIdBc)
    yield assertEquals(
      bPoll.toOption.get.collect:
        case EventStreamMessage.Event(event) => event.topic
      ,
      Vector.empty,
    )

  test("audit followers relay duplicate upstream artifacts only once"):
    val holders = Vector(
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
      ValidatorKeyHolder(
        validatorSet.members(3).id,
        PeerIdentity.unsafe("node-d"),
        ValidatorKeyHolderStatus.Active,
      ),
    )

    for
      a <- Harness.create(
        "node-a",
        "node-b",
        LocalNodeRole.Validator,
        holders,
        Map(
          validatorSet.members(0).id -> validatorKeys(0),
          validatorSet.members(1).id -> validatorKeys(1),
          validatorSet.members(2).id -> validatorKeys(2),
        ),
      )
      b <- Harness.createWithPeers(
        "node-b",
        List("node-a", "node-c"),
        LocalNodeRole.Audit,
        holders,
        Map.empty,
      )
      c <- Harness.create(
        "node-c",
        "node-b",
        LocalNodeRole.Audit,
        holders,
        Map.empty,
      )
      openedAb <- openOutbound(a, b)
      openedBc <- openOutbound(b, c)
      sessionIdAb = openedAb.proposal.sessionId
      sessionIdBc = openedBc.proposal.sessionId
      justify     = bootstrapQc()
      proposalEvent <- a.consensus
        .emitProposal(
          proposer = validatorSet.members.head.id,
          block = block(
            parent = Some(justify.subject.blockId),
            height = 2L,
            rootHex = "8b",
          ),
          txSet = ProposalTxSet.empty,
          window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
          justify = justify,
          ts = baseInstant,
        )
        .flatMap(unwrapPolicy)
      proposal = proposalPayload(proposalEvent)
      voteEvent <- a.consensus
        .emitVote(
          validatorSet.members.head.id,
          proposal,
          baseInstant.plusMillis(1),
        )
        .flatMap(unwrapPolicy)
      aPoll     <- a.gossip.pollEvents(sessionIdAb)
      _         <- b.gossip.receiveEvents(sessionIdAb, aPoll.toOption.get)
      _         <- b.gossip.receiveEvents(sessionIdAb, aPoll.toOption.get)
      bPoll     <- b.gossip.pollEvents(sessionIdBc)
      cReceive  <- c.gossip.receiveEvents(sessionIdBc, bPoll.toOption.get)
      cSnapshot <- sinkSnapshot(c.consensus)
    yield
      assertEquals(
        bPoll.toOption.get.collect:
          case EventStreamMessage.Event(event)
              if event.topic == GossipTopic.consensusProposal =>
            proposalPayload(event).proposalId
        ,
        Vector(proposal.proposalId),
      )
      assertEquals(
        bPoll.toOption.get.collect:
          case EventStreamMessage.Event(event)
              if event.topic == GossipTopic.consensusVote =>
            votePayload(event).voteId
        ,
        Vector(votePayload(voteEvent).voteId),
      )
      assertEquals(
        cReceive.toOption.get.applied.collect:
          case event if event.topic == GossipTopic.consensusProposal =>
            proposalPayload(event).proposalId
        ,
        Vector(proposal.proposalId),
      )
      assertEquals(
        cReceive.toOption.get.applied.collect:
          case event if event.topic == GossipTopic.consensusVote =>
            votePayload(event).voteId
        ,
        Vector(votePayload(voteEvent).voteId),
      )
      assertEquals(cSnapshot.duplicates.map(_.topic), Vector.empty)
      assertEquals(cSnapshot.proposals.keySet, Set(proposal.proposalId))
      assertEquals(cSnapshot.votes.keySet, Set(votePayload(voteEvent).voteId))

  test(
    "consensus proposal nack replays from the subscribed topic instead of rejecting as tx-only",
  ):
    val holders = Vector(
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
    )

    for
      a <- Harness.create(
        "node-a",
        "node-b",
        LocalNodeRole.Validator,
        holders,
        Map(
          validatorSet.members(0).id -> validatorKeys(0),
          validatorSet.members(1).id -> validatorKeys(1),
          validatorSet.members(2).id -> validatorKeys(2),
        ),
      )
      b <- Harness.create(
        "node-b",
        "node-a",
        LocalNodeRole.Audit,
        holders,
        Map.empty,
      )
      opened <- openOutbound(a, b)
      sessionId = opened.proposal.sessionId
      justify   = bootstrapQc()
      proposalEvent <- a.consensus
        .emitProposal(
          proposer = validatorSet.members.head.id,
          block = block(
            parent = Some(justify.subject.blockId),
            height = 2L,
            rootHex = "88",
          ),
          txSet = ProposalTxSet.empty,
          window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
          justify = justify,
          ts = baseInstant,
        )
        .flatMap(unwrapPolicy)
      firstPoll    <- a.gossip.pollEvents(sessionId)
      firstReceive <- b.gossip.receiveEvents(sessionId, firstPoll.toOption.get)
      lastCursor = firstReceive.toOption.get.lastCursor.get
      nackResult <- a.gossip.receiveControlBatch(
        sessionId,
        controlBatch(
          "abababab-abab-4bab-8bab-abababababab",
          Vector(
            ControlOp.SetCursor(
              CompositeCursor(
                Map(
                  ChainTopic(
                    chainId,
                    GossipTopic.consensusProposal,
                  ) -> lastCursor,
                ),
              ),
            ),
            ControlOp.Nack(chainId, GossipTopic.consensusProposal, None),
          ),
        ),
      )
      replayPoll <- a.gossip.pollEvents(sessionId)
    yield
      assertEquals(nackResult, Right(ControlBatchOutcome.Applied))
      assertEquals(
        replayPoll.toOption.get.collect:
          case EventStreamMessage.Event(event) =>
            proposalPayload(event).proposalId
        ,
        Vector(proposalPayload(proposalEvent).proposalId),
      )

  test(
    "key relocation fences the old holder, enables the new holder, and rejects dual-active startup",
  ):
    val validatorId = validatorSet.members.head.id
    val fencedHolders = Vector(
      ValidatorKeyHolder(
        validatorId,
        PeerIdentity.unsafe("node-old"),
        ValidatorKeyHolderStatus.Fenced,
      ),
      ValidatorKeyHolder(
        validatorId,
        PeerIdentity.unsafe("node-new"),
        ValidatorKeyHolderStatus.Active,
      ),
    )
    val dualActive = Vector(
      ValidatorKeyHolder(
        validatorId,
        PeerIdentity.unsafe("node-old"),
        ValidatorKeyHolderStatus.Active,
      ),
      ValidatorKeyHolder(
        validatorId,
        PeerIdentity.unsafe("node-new"),
        ValidatorKeyHolderStatus.Active,
      ),
    )

    for
      clock <- TestClock.create(baseInstant)
      given GossipClock[IO] = clock
      oldRuntime <- HotStuffNodeRuntime.create[IO](
        localPeer = PeerIdentity.unsafe("node-old"),
        role = LocalNodeRole.Validator,
        holders = fencedHolders,
        validatorSet = validatorSet,
        localKeys = Map(validatorId -> validatorKeys.head),
      )
      newRuntime <- HotStuffNodeRuntime.create[IO](
        localPeer = PeerIdentity.unsafe("node-new"),
        role = LocalNodeRole.Validator,
        holders = fencedHolders,
        validatorSet = validatorSet,
        localKeys = Map(validatorId -> validatorKeys.head),
      )
      dualActiveRuntime <- HotStuffNodeRuntime.create[IO](
        localPeer = PeerIdentity.unsafe("node-old"),
        role = LocalNodeRole.Validator,
        holders = dualActive,
        validatorSet = validatorSet,
        localKeys = Map(validatorId -> validatorKeys.head),
      )
      justify = bootstrapQc()
      oldEmit <- unwrapPolicy(oldRuntime)
        .flatMap(
          _.emitProposal(
            proposer = validatorId,
            block = block(
              parent = Some(justify.subject.blockId),
              height = 2L,
              rootHex = "84",
            ),
            txSet = ProposalTxSet.empty,
            window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
            justify = justify,
            ts = baseInstant,
          ),
        )
      newEmit <- unwrapPolicy(newRuntime)
        .flatMap(
          _.emitProposal(
            proposer = validatorId,
            block = block(
              parent = Some(justify.subject.blockId),
              height = 2L,
              rootHex = "85",
            ),
            txSet = ProposalTxSet.empty,
            window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
            justify = justify,
            ts = baseInstant.plusMillis(1),
          ),
        )
    yield
      assertEquals(oldEmit.left.map(_.reason), Left("validatorKeyFenced"))
      assert(newEmit.isRight)
      assertEquals(
        dualActiveRuntime.left.map(_.reason),
        Left("dualActiveKeyHolder"),
      )

  test(
    "exact request path rejects oversize, wrong-window, and unknown requests",
  ):
    val holders = Vector(
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
    )

    for
      a <- Harness.create(
        "node-a",
        "node-b",
        LocalNodeRole.Validator,
        holders,
        Map(
          validatorSet.members(0).id -> validatorKeys(0),
          validatorSet.members(1).id -> validatorKeys(1),
          validatorSet.members(2).id -> validatorKeys(2),
        ),
      )
      b <- Harness.create(
        "node-b",
        "node-a",
        LocalNodeRole.Audit,
        holders,
        Map.empty,
      )
      opened <- openOutbound(a, b)
      sessionId = opened.proposal.sessionId
      justify   = bootstrapQc()
      proposalEvent <- a.consensus
        .emitProposal(
          proposer = validatorSet.members.head.id,
          block = block(
            parent = Some(justify.subject.blockId),
            height = 2L,
            rootHex = "86",
          ),
          txSet = ProposalTxSet.empty,
          window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
          justify = justify,
          ts = baseInstant,
        )
        .flatMap(unwrapPolicy)
      proposal = proposalPayload(proposalEvent)
      wrongWindow = proposalScope(proposal).copy(windowKey =
        TopicWindowKey.unsafeFromHex("deadbeef"),
      )
      oversizeIds = (0 until 129).toVector.map(index =>
        StableArtifactId.unsafeFromHex(f"${index + 1}%02x"),
      )
      oversize <- a.gossip.receiveControlBatch(
        sessionId,
        controlBatch(
          "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
          Vector(
            ControlOp.RequestByIdExact(proposalScope(proposal), oversizeIds),
          ),
        ),
      )
      wrongWindowResult <- a.gossip.receiveControlBatch(
        sessionId,
        controlBatch(
          "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
          Vector(
            ControlOp.RequestByIdExact(
              wrongWindow,
              Vector(
                HotStuffGossipArtifact.stableIdOf(
                  HotStuffGossipArtifact.ProposalArtifact(proposal),
                ),
              ),
            ),
          ),
        ),
      )
      unknown <- a.gossip.receiveControlBatch(
        sessionId,
        controlBatch(
          "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
          Vector(
            ControlOp.RequestByIdExact(
              proposalScope(proposal),
              Vector(StableArtifactId.unsafeFromHex("ff")),
            ),
          ),
        ),
      )
    yield
      assertEquals(oversize.left.map(_.reason), Left("requestByIdTooLarge"))
      assertEquals(wrongWindowResult.left.map(_.reason), Left("wrongWindowKey"))
      assertEquals(unknown.left.map(_.reason), Left("unknownRequestedArtifact"))

  test(
    "same-window requestById retry budget rejects proposal and vote retries after two attempts",
  ):
    val holders = Vector(
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
    )

    for
      a <- Harness.create(
        "node-a",
        "node-b",
        LocalNodeRole.Validator,
        holders,
        Map(
          validatorSet.members(0).id -> validatorKeys(0),
          validatorSet.members(1).id -> validatorKeys(1),
          validatorSet.members(2).id -> validatorKeys(2),
        ),
      )
      b <- Harness.create(
        "node-b",
        "node-a",
        LocalNodeRole.Audit,
        holders,
        Map.empty,
      )
      opened <- openOutbound(a, b)
      sessionId = opened.proposal.sessionId
      justify   = bootstrapQc()
      proposalEvent <- a.consensus
        .emitProposal(
          proposer = validatorSet.members.head.id,
          block = block(
            parent = Some(justify.subject.blockId),
            height = 2L,
            rootHex = "8c",
          ),
          txSet = ProposalTxSet.empty,
          window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
          justify = justify,
          ts = baseInstant,
        )
        .flatMap(unwrapPolicy)
      proposal = proposalPayload(proposalEvent)
      voteEvent <- a.consensus
        .emitVote(
          validatorSet.members.head.id,
          proposal,
          baseInstant.plusMillis(1),
        )
        .flatMap(unwrapPolicy)
      proposalRetry1 <- a.gossip.receiveControlBatch(
        sessionId,
        controlBatch(
          "11111111-1111-4111-8111-111111111111",
          Vector(
            ControlOp.RequestByIdExact(
              proposalScope(proposal),
              Vector(
                HotStuffGossipArtifact.stableIdOf(
                  HotStuffGossipArtifact.ProposalArtifact(proposal),
                ),
              ),
            ),
          ),
        ),
      )
      proposalRetry2 <- a.gossip.receiveControlBatch(
        sessionId,
        controlBatch(
          "22222222-2222-4222-8222-222222222222",
          Vector(
            ControlOp.RequestByIdExact(
              proposalScope(proposal),
              Vector(
                HotStuffGossipArtifact.stableIdOf(
                  HotStuffGossipArtifact.ProposalArtifact(proposal),
                ),
              ),
            ),
          ),
        ),
      )
      proposalRetry3 <- a.gossip.receiveControlBatch(
        sessionId,
        controlBatch(
          "33333333-3333-4333-8333-333333333333",
          Vector(
            ControlOp.RequestByIdExact(
              proposalScope(proposal),
              Vector(
                HotStuffGossipArtifact.stableIdOf(
                  HotStuffGossipArtifact.ProposalArtifact(proposal),
                ),
              ),
            ),
          ),
        ),
      )
      proposalRetry4 <- a.gossip.receiveControlBatch(
        sessionId,
        controlBatch(
          "77777777-7777-4777-8777-777777777777",
          Vector(
            ControlOp.RequestByIdExact(
              proposalScope(proposal),
              Vector(
                HotStuffGossipArtifact.stableIdOf(
                  HotStuffGossipArtifact.ProposalArtifact(proposal),
                ),
              ),
            ),
          ),
        ),
      )
      vote = votePayload(voteEvent)
      voteRetry1 <- a.gossip.receiveControlBatch(
        sessionId,
        controlBatch(
          "44444444-4444-4444-8444-444444444444",
          Vector(
            ControlOp.RequestByIdExact(
              voteScope(vote),
              Vector(
                HotStuffGossipArtifact.stableIdOf(
                  HotStuffGossipArtifact.VoteArtifact(vote),
                ),
              ),
            ),
          ),
        ),
      )
      voteRetry2 <- a.gossip.receiveControlBatch(
        sessionId,
        controlBatch(
          "55555555-5555-4555-8555-555555555555",
          Vector(
            ControlOp.RequestByIdExact(
              voteScope(vote),
              Vector(
                HotStuffGossipArtifact.stableIdOf(
                  HotStuffGossipArtifact.VoteArtifact(vote),
                ),
              ),
            ),
          ),
        ),
      )
      voteRetry3 <- a.gossip.receiveControlBatch(
        sessionId,
        controlBatch(
          "66666666-6666-4666-8666-666666666666",
          Vector(
            ControlOp.RequestByIdExact(
              voteScope(vote),
              Vector(
                HotStuffGossipArtifact.stableIdOf(
                  HotStuffGossipArtifact.VoteArtifact(vote),
                ),
              ),
            ),
          ),
        ),
      )
      voteRetry4 <- a.gossip.receiveControlBatch(
        sessionId,
        controlBatch(
          "88888888-8888-4888-8888-888888888888",
          Vector(
            ControlOp.RequestByIdExact(
              voteScope(vote),
              Vector(
                HotStuffGossipArtifact.stableIdOf(
                  HotStuffGossipArtifact.VoteArtifact(vote),
                ),
              ),
            ),
          ),
        ),
      )
    yield
      assertEquals(proposalRetry1, Right(ControlBatchOutcome.Applied))
      assertEquals(proposalRetry2, Right(ControlBatchOutcome.Applied))
      assertEquals(
        proposalRetry3.left.map(_.reason),
        Left("requestByIdRetryBudgetExceeded"),
      )
      assertEquals(
        proposalRetry4.left.map(_.reason),
        Left("requestByIdRetryBudgetExceeded"),
      )
      assertEquals(voteRetry1, Right(ControlBatchOutcome.Applied))
      assertEquals(voteRetry2, Right(ControlBatchOutcome.Applied))
      assertEquals(
        voteRetry3.left.map(_.reason),
        Left("requestByIdRetryBudgetExceeded"),
      )
      assertEquals(
        voteRetry4.left.map(_.reason),
        Left("requestByIdRetryBudgetExceeded"),
      )

  test(
    "consensus vote exact-known request-by-id and duplicate replay semantics match proposal baseline",
  ):
    val holders = Vector(
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
    )

    for
      a <- Harness.create(
        "node-a",
        "node-b",
        LocalNodeRole.Validator,
        holders,
        Map(
          validatorSet.members(0).id -> validatorKeys(0),
          validatorSet.members(1).id -> validatorKeys(1),
          validatorSet.members(2).id -> validatorKeys(2),
        ),
      )
      b <- Harness.create(
        "node-b",
        "node-a",
        LocalNodeRole.Audit,
        holders,
        Map.empty,
      )
      opened <- openOutbound(a, b)
      sessionId = opened.proposal.sessionId
      justify   = bootstrapQc()
      proposalEvent <- a.consensus
        .emitProposal(
          proposer = validatorSet.members.head.id,
          block = block(
            parent = Some(justify.subject.blockId),
            height = 2L,
            rootHex = "8d",
          ),
          txSet = ProposalTxSet.empty,
          window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
          justify = justify,
          ts = baseInstant,
        )
        .flatMap(unwrapPolicy)
      proposal = proposalPayload(proposalEvent)
      vote1Event <- a.consensus
        .emitVote(
          validatorSet.members(0).id,
          proposal,
          baseInstant.plusMillis(1),
        )
        .flatMap(unwrapPolicy)
      vote2Event <- a.consensus
        .emitVote(
          validatorSet.members(1).id,
          proposal,
          baseInstant.plusMillis(2),
        )
        .flatMap(unwrapPolicy)
      vote3Event <- a.consensus
        .emitVote(
          validatorSet.members(2).id,
          proposal,
          baseInstant.plusMillis(3),
        )
        .flatMap(unwrapPolicy)
      vote1 = votePayload(vote1Event)
      vote2 = votePayload(vote2Event)
      vote3 = votePayload(vote3Event)
      _ <- a.gossip.receiveControlBatch(
        sessionId,
        controlBatch(
          "99999999-9999-4999-8999-999999999999",
          Vector(
            ControlOp.SetKnownExact(
              proposalScope(proposal),
              Vector(
                HotStuffGossipArtifact.stableIdOf(
                  HotStuffGossipArtifact.ProposalArtifact(proposal),
                ),
              ),
            ),
            ControlOp.SetKnownExact(
              voteScope(vote1),
              Vector(
                HotStuffGossipArtifact.stableIdOf(
                  HotStuffGossipArtifact.VoteArtifact(vote1),
                ),
              ),
            ),
          ),
        ),
      )
      firstPoll    <- a.gossip.pollEvents(sessionId)
      firstReceive <- b.gossip.receiveEvents(sessionId, firstPoll.toOption.get)
      oversizeVoteRequest <- a.gossip.receiveControlBatch(
        sessionId,
        controlBatch(
          "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaab",
          Vector(
            ControlOp.RequestByIdExact(
              voteScope(vote1),
              (0 until 513).toVector.map(index =>
                StableArtifactId.unsafeFromHex(f"${index + 1L}%064x"),
              ),
            ),
          ),
        ),
      )
      wrongVoteWindow = voteScope(vote1).copy(windowKey =
        TopicWindowKey.unsafeFromHex("deadbeef"),
      )
      wrongWindowVoteRequest <- a.gossip.receiveControlBatch(
        sessionId,
        controlBatch(
          "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbc",
          Vector(
            ControlOp.RequestByIdExact(
              wrongVoteWindow,
              Vector(
                HotStuffGossipArtifact.stableIdOf(
                  HotStuffGossipArtifact.VoteArtifact(vote1),
                ),
              ),
            ),
          ),
        ),
      )
      requestVote1 <- a.gossip.receiveControlBatch(
        sessionId,
        controlBatch(
          "cccccccc-cccc-4ccc-8ccc-cccccccccccd",
          Vector(
            ControlOp.RequestByIdExact(
              voteScope(vote1),
              Vector(
                HotStuffGossipArtifact.stableIdOf(
                  HotStuffGossipArtifact.VoteArtifact(vote1),
                ),
              ),
            ),
          ),
        ),
      )
      secondPoll <- a.gossip.pollEvents(sessionId)
      secondReceive <- b.gossip.receiveEvents(
        sessionId,
        secondPoll.toOption.get,
      )
      replayVote1 <- a.gossip.receiveControlBatch(
        sessionId,
        controlBatch(
          "dddddddd-dddd-4ddd-8ddd-ddddddddddde",
          Vector(
            ControlOp.RequestByIdExact(
              voteScope(vote1),
              Vector(
                HotStuffGossipArtifact.stableIdOf(
                  HotStuffGossipArtifact.VoteArtifact(vote1),
                ),
              ),
            ),
          ),
        ),
      )
      thirdPoll    <- a.gossip.pollEvents(sessionId)
      thirdReceive <- b.gossip.receiveEvents(sessionId, thirdPoll.toOption.get)
    yield
      assertEquals(
        firstPoll.toOption.get.collect:
          case EventStreamMessage.Event(event) => event.topic
        ,
        Vector(GossipTopic.consensusVote, GossipTopic.consensusVote),
      )
      assertEquals(
        firstReceive.toOption.get.applied.collect {
          case event if event.topic == GossipTopic.consensusVote =>
            votePayload(event).voteId
        }.toSet,
        Set(vote2.voteId, vote3.voteId),
      )
      assertEquals(
        oversizeVoteRequest.left.map(_.reason),
        Left("requestByIdTooLarge"),
      )
      assertEquals(
        wrongWindowVoteRequest.left.map(_.reason),
        Left("wrongWindowKey"),
      )
      assertEquals(requestVote1, Right(ControlBatchOutcome.Applied))
      assertEquals(
        secondReceive.toOption.get.applied.collect:
          case event if event.topic == GossipTopic.consensusVote =>
            votePayload(event).voteId
        ,
        Vector(vote1.voteId),
      )
      assertEquals(replayVote1, Right(ControlBatchOutcome.Applied))
      assertEquals(
        thirdReceive.toOption.get.duplicates.collect:
          case event if event.topic == GossipTopic.consensusVote =>
            votePayload(event).voteId
        ,
        Vector(vote1.voteId),
      )

  test("consensus proposal and vote qos are not blocked by tx backlog"):
    val holders = Vector(
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
    )

    for
      topology <- IO.fromEither(
        StaticPeerTopology
          .parse(
            localNodeIdentity = "node-a",
            knownPeers = List("node-b"),
            directNeighbors = List("node-b"),
          )
          .leftMap(new IllegalArgumentException(_)),
      )
      registry      = StaticPeerRegistry(topology)
      authenticator = StaticPeerAuthenticator[IO](registry)
      clock <- TestClock.create(baseInstant)
      given GossipClock[IO] = clock
      consensus <- HotStuffNodeRuntime
        .create[IO](
          localPeer = PeerIdentity.unsafe("node-a"),
          role = LocalNodeRole.Validator,
          holders = holders,
          validatorSet = validatorSet,
          localKeys = Map(
            validatorSet.members(0).id -> validatorKeys(0),
            validatorSet.members(1).id -> validatorKeys(1),
            validatorSet.members(2).id -> validatorKeys(2),
          ),
        )
        .flatMap(unwrapPolicy)
      mixedSource <- MixedSource.create(clock)
      mixedSink   <- MixedSink.create
      stateStore <- TxGossipStateStore.inMemory[IO](
        GossipSessionEngine(registry.localPeer, topology),
      )
      runtime = TxGossipRuntime.withPolicy[IO, MixedArtifact](
        peerAuthenticator = authenticator,
        clock = clock,
        source = mixedSource,
        sink = mixedSink,
        topicContracts = MixedContracts.registry(consensus.gossipPolicy),
        stateStore = stateStore,
        policy = hotStuffRuntimePolicy,
      )
      sessionProposalEither <- runtime.startOutbound(
        PeerIdentity.unsafe("node-b"),
        SessionSubscription.unsafe(
          ChainTopic(chainId, GossipTopic.tx),
          ChainTopic(chainId, GossipTopic.consensusProposal),
          ChainTopic(chainId, GossipTopic.consensusVote),
        ),
      )
      sessionProposal <- IO.fromEither(
        sessionProposalEither.leftMap(rejection =>
          new IllegalStateException(rejection.reason),
        ),
      )
      inboundAck = SessionNegotiation
        .acknowledge(
          sessionProposal,
          heartbeatInterval = Duration.ofSeconds(10),
          livenessTimeout = Duration.ofSeconds(30),
          maxControlRetryInterval = Duration.ofSeconds(30),
        )
        .toOption
        .get
      ackResult <- runtime.applyHandshakeAck(inboundAck)
      _ <- IO.fromEither(
        ackResult.leftMap(rejection =>
          new IllegalStateException(rejection.reason),
        ),
      )
      sessionId = sessionProposal.sessionId
      _ <- runtime.receiveControlBatch(
        sessionId,
        controlBatch(
          "ffffffff-ffff-4fff-8fff-ffffffffffff",
          Vector(
            ControlOp.Config(
              Map(
                SessionConfigKey.TxMaxBatchItems   -> 32L,
                SessionConfigKey.TxFlushIntervalMs -> 5000L,
              ),
            ),
          ),
        ),
      )
      _ <- mixedSource.appendTx(chainId, "tx-backlog-1", baseInstant)
      justify = bootstrapQc()
      proposal =
        Proposal
          .sign(
            UnsignedProposal(
              window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
              proposer = validatorSet.members.head.id,
              targetBlockId = BlockHeader.computeId(
                block(
                  parent = Some(justify.subject.blockId),
                  height = 2L,
                  rootHex = "87",
                ),
              ),
              block = block(
                parent = Some(justify.subject.blockId),
                height = 2L,
                rootHex = "87",
              ),
              txSet = ProposalTxSet.empty,
              justify = justify,
            ),
            validatorKeys.head,
          )
          .toOption
          .get
      vote =
        Vote
          .sign(
            UnsignedVote(
              window = proposal.window,
              voter = validatorSet.members(1).id,
              targetProposalId = proposal.proposalId,
            ),
            validatorKeys(1),
          )
          .toOption
          .get
      _ <- mixedSource.appendConsensus(
        HotStuffGossipArtifact.ProposalArtifact(proposal),
        baseInstant.plusMillis(1),
      )
      _ <- mixedSource.appendConsensus(
        HotStuffGossipArtifact.VoteArtifact(vote),
        baseInstant.plusMillis(2),
      )
      polled <- runtime.pollEvents(sessionId)
    yield assertEquals(
      polled.toOption.get.collect:
        case EventStreamMessage.Event(event) => event.topic.value
      ,
      Vector(
        GossipTopic.consensusProposal.value,
        GossipTopic.consensusVote.value,
      ),
    )

  private def openOutbound(
      from: Harness,
      to: Harness,
  ): IO[OpenedSession] =
    for
      proposalEither <- from.gossip.startOutbound(
        PeerIdentity.unsafe(to.localNodeId),
        subscription,
      )
      proposal <- IO.fromEither(
        proposalEither.leftMap(rejection =>
          new IllegalStateException(rejection.reason),
        ),
      )
      inboundResult <- to.gossip.handleInboundProposal(proposal)
      accepted <- inboundResult match
        case accepted: InboundHandshakeResult.Accepted =>
          IO.pure(accepted)
        case rejected: InboundHandshakeResult.Rejected =>
          IO.raiseError(new IllegalStateException(rejected.rejection.reason))
      ackResult <- from.gossip.applyHandshakeAck(accepted.ack)
      _ <- IO.fromEither(
        ackResult.leftMap(rejection =>
          new IllegalStateException(rejection.reason),
        ),
      )
    yield OpenedSession(proposal, accepted.ack)

  private def bootstrapQc(): QuorumCertificate =
    val window = HotStuffWindow(chainId, 0L, 0L, validatorSet.hash)
    val subject = QuorumCertificateSubject(
      window = window,
      proposalId = ProposalId(hex("70")),
      blockId = BlockId(hex("71")),
    )
    QuorumCertificateAssembler
      .assemble(
        subject,
        Vector(
          signedVoteFor(window, subject.proposalId, 0),
          signedVoteFor(window, subject.proposalId, 1),
          signedVoteFor(window, subject.proposalId, 2),
        ),
        validatorSet,
      )
      .toOption
      .get

  private def signedVoteFor(
      window: HotStuffWindow,
      proposalId: ProposalId,
      index: Int,
  ): Vote =
    Vote
      .sign(
        UnsignedVote(window, validatorSet.members(index).id, proposalId),
        validatorKeys(index),
      )
      .toOption
      .get

  private def signedTimeoutVoteFor(
      window: HotStuffWindow,
      highestKnownQc: QuorumCertificate,
      index: Int,
  ): TimeoutVote =
    TimeoutVote
      .sign(
        UnsignedTimeoutVote(
          subject = TimeoutVoteSubject(
            window = window,
            highestKnownQc = highestKnownQc.subject,
          ),
          voter = validatorSet.members(index).id,
        ),
        validatorKeys(index),
      )
      .toOption
      .get

  private def timeoutCertificateFor(
      window: HotStuffWindow,
      highestKnownQc: QuorumCertificate,
  ): TimeoutCertificate =
    TimeoutCertificateAssembler
      .assemble(
        TimeoutVoteSubject(
          window = window,
          highestKnownQc = highestKnownQc.subject,
        ),
        Vector(
          signedTimeoutVoteFor(window, highestKnownQc, 0),
          signedTimeoutVoteFor(window, highestKnownQc, 1),
          signedTimeoutVoteFor(window, highestKnownQc, 2),
        ),
        validatorSet,
      )
      .toOption
      .get

  private def proposalScope(
      proposal: Proposal,
  ): ExactKnownSetScope =
    ExactKnownSetScope(
      chainId = proposal.window.chainId,
      topic = GossipTopic.consensusProposal,
      windowKey = HotStuffWindowKey.fromWindow(proposal.window),
    )

  private def voteScope(
      vote: Vote,
  ): ExactKnownSetScope =
    ExactKnownSetScope(
      chainId = vote.window.chainId,
      topic = GossipTopic.consensusVote,
      windowKey = HotStuffWindowKey.fromWindow(vote.window),
    )

  private def timeoutVoteScope(
      timeoutVote: TimeoutVote,
  ): ExactKnownSetScope =
    ExactKnownSetScope(
      chainId = timeoutVote.subject.window.chainId,
      topic = GossipTopic.consensusTimeoutVote,
      windowKey = HotStuffWindowKey.fromWindow(timeoutVote.subject.window),
    )

  private def newViewScope(
      newView: NewView,
  ): ExactKnownSetScope =
    ExactKnownSetScope(
      chainId = newView.window.chainId,
      topic = GossipTopic.consensusNewView,
      windowKey = HotStuffWindowKey.fromWindow(newView.window),
    )

  private def proposalPayload(
      event: GossipEvent[HotStuffGossipArtifact],
  ): Proposal =
    event.payload match
      case HotStuffGossipArtifact.ProposalArtifact(proposal) => proposal
      case _ => throw new IllegalStateException("expected proposal")

  private def votePayload(
      event: GossipEvent[HotStuffGossipArtifact],
  ): Vote =
    event.payload match
      case HotStuffGossipArtifact.VoteArtifact(vote) => vote
      case _ => throw new IllegalStateException("expected vote")

  private def timeoutVotePayload(
      event: GossipEvent[HotStuffGossipArtifact],
  ): TimeoutVote =
    event.payload match
      case HotStuffGossipArtifact.TimeoutVoteArtifact(timeoutVote) =>
        timeoutVote
      case _ =>
        throw new IllegalStateException("expected timeout vote")

  private def newViewPayload(
      event: GossipEvent[HotStuffGossipArtifact],
  ): NewView =
    event.payload match
      case HotStuffGossipArtifact.NewViewArtifact(newView) => newView
      case _ => throw new IllegalStateException("expected new-view")

  private def controlBatch(
      idempotencyKey: String,
      ops: Vector[ControlOp],
  ): ControlBatch =
    ControlBatch.create(idempotencyKey, ops).toOption.get

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
      timestamp = BlockTimestamp.unsafeFromEpochMillis(baseInstant.toEpochMilli),
    )

  private def unwrapPolicy[A](
      result: Either[HotStuffPolicyViolation, A],
  ): IO[A] =
    IO.fromEither(
      result.leftMap(rejection => new IllegalStateException(rejection.reason)),
    )

  private def sinkSnapshot(
      runtime: HotStuffNodeRuntime[IO],
  ): IO[InMemoryHotStuffSinkSnapshot] =
    IO
      .fromOption(runtime.inMemorySink)(
        new IllegalStateException("expected in-memory sink diagnostics"),
      )
      .flatMap(_.snapshot)

  private def applyLocalEvent(
      runtime: HotStuffNodeRuntime[IO],
      event: GossipEvent[HotStuffGossipArtifact],
  ): IO[GossipEvent[HotStuffGossipArtifact]] =
    runtime.sink
      .applyEvent(event)
      .flatMap(result =>
        IO.fromEither(
          result
            .leftMap(rejection => new IllegalStateException(rejection.reason)),
        ).as(event),
      )

  private def openDirectedMesh(
      nodes: Vector[Harness],
  ): IO[Vector[DirectedLink]] =
    nodes
      .traverse: from =>
        nodes
          .filter(_.localNodeId =!= from.localNodeId)
          .traverse(to =>
            openOutbound(from, to).map(opened =>
              DirectedLink(from, to, opened.proposal.sessionId),
            ),
          )
      .map(_.flatten)

  private def drainLinks(
      links: Vector[DirectedLink],
      maxRounds: Int = 8,
  ): IO[Unit] =
    def loop(
        roundsRemaining: Int,
        pending: Int,
    ): IO[Unit] =
      if roundsRemaining <= 0 || pending <= 0 then IO.unit
      else
        links
          .traverse(relayLink)
          .map(_.sum)
          .flatMap(total => loop(roundsRemaining - 1, total))

    links
      .traverse(relayLink)
      .map(_.sum)
      .flatMap(total => loop(maxRounds - 1, total))

  private def relayLink(
      link: DirectedLink,
  ): IO[Int] =
    for
      polled <- link.from.gossip.pollEvents(link.sessionId)
      messages <- IO.fromEither(
        polled.leftMap(rejection => new IllegalStateException(rejection.reason)),
      )
      _ <- link.to.gossip
        .receiveEvents(link.sessionId, messages)
        .flatMap(result =>
          IO.fromEither(
            result
              .leftMap(rejection => new IllegalStateException(rejection.reason)),
          ).void,
        )
    yield messages.collect { case EventStreamMessage.Event(_) => 1 }.sum

  private final case class OpenedSession(
      proposal: SessionOpenProposal,
      ack: SessionOpenAck,
  )

  private final case class DirectedLink(
      from: Harness,
      to: Harness,
      sessionId: DirectionalSessionId,
  )

  private final case class Harness(
      localNodeId: String,
      gossip: TxGossipRuntime[IO, HotStuffGossipArtifact],
      consensus: HotStuffNodeRuntime[IO],
      clock: TestClock,
  )

  private object Harness:
    def create(
        localNodeId: String,
        remoteNodeId: String,
        role: LocalNodeRole,
        holders: Vector[ValidatorKeyHolder],
        localKeys: Map[ValidatorId, org.sigilaris.core.crypto.KeyPair],
        automaticConsensus: Boolean = false,
    ): IO[Harness] =
      createWithPeers(
        localNodeId,
        List(remoteNodeId),
        role,
        holders,
        localKeys,
        automaticConsensus,
      )

    def createWithPeers(
        localNodeId: String,
        remoteNodeIds: List[String],
        role: LocalNodeRole,
        holders: Vector[ValidatorKeyHolder],
        localKeys: Map[ValidatorId, org.sigilaris.core.crypto.KeyPair],
        automaticConsensus: Boolean = false,
    ): IO[Harness] =
      for
        topology <- IO.fromEither(
          StaticPeerTopology
            .parse(
              localNodeIdentity = localNodeId,
              knownPeers = remoteNodeIds,
              directNeighbors = remoteNodeIds,
            )
            .leftMap(new IllegalArgumentException(_)),
        )
        registry      = StaticPeerRegistry(topology)
        authenticator = StaticPeerAuthenticator[IO](registry)
        clock <- TestClock.create(baseInstant)
        given GossipClock[IO] = clock
        consensus <- HotStuffNodeRuntime
          .create[IO](
            localPeer = PeerIdentity.unsafe(localNodeId),
            role = role,
            holders = holders,
            validatorSet = validatorSet,
            localKeys = localKeys,
            automaticConsensus = automaticConsensus,
          )
          .flatMap(unwrapPolicy)
        stateStore <- TxGossipStateStore.inMemory[IO](
          GossipSessionEngine(registry.localPeer, topology),
        )
      yield Harness(
        localNodeId = localNodeId,
        gossip = TxGossipRuntime.withPolicy[IO, HotStuffGossipArtifact](
          peerAuthenticator = authenticator,
          clock = clock,
          source = consensus.source,
          sink = consensus.sink,
          topicContracts = consensus.topicContracts,
          stateStore = stateStore,
          policy = hotStuffRuntimePolicy,
        ),
        consensus = consensus,
        clock = clock,
      )

  private final class TestClock private (ref: Ref[IO, Instant])
      extends GossipClock[IO]:
    override def now: IO[Instant] =
      ref.get

    def advance(duration: Duration): IO[Unit] =
      ref.update(_.plus(duration))

  private object TestClock:
    def create(instant: Instant): IO[TestClock] =
      Ref.of[IO, Instant](instant).map(new TestClock(_))

  private enum MixedArtifact:
    case Tx(body: String)
    case Consensus(artifact: HotStuffGossipArtifact)

  private object MixedContracts:
    private val TxContract = new GossipTopicContract[MixedArtifact]:
      override val topic: GossipTopic = GossipTopic.tx

      override def validateArtifact(
          event: GossipEvent[MixedArtifact],
      ): Either[CanonicalRejection.ArtifactContractRejected, Unit] =
        event.payload match
          case MixedArtifact.Tx(_) => Right(())
          case _ =>
            Left(
              CanonicalRejection.ArtifactContractRejected(
                reason = "unexpectedTopicPayload",
                detail = Some(topic.value),
              ),
            )

    def registry(
        policy: HotStuffGossipPolicy,
    ): GossipTopicContractRegistry[MixedArtifact] =
      GossipTopicContractRegistry.of(
        TxContract,
        wrapHotStuff(HotStuffTopic.proposalContract(policy.proposal)),
        wrapHotStuff(HotStuffTopic.voteContract(policy.vote)),
        wrapHotStuff(HotStuffTopic.timeoutVoteContract(policy.timeoutVote)),
        wrapHotStuff(HotStuffTopic.newViewContract(policy.newView)),
      )

    private def wrapHotStuff(
        contract: GossipTopicContract[HotStuffGossipArtifact],
    ): GossipTopicContract[MixedArtifact] =
      new GossipTopicContract[MixedArtifact]:
        override val topic: GossipTopic = contract.topic
        override val exactKnownSetLimit: Option[Int] =
          contract.exactKnownSetLimit
        override val requestByIdLimit: Option[Int] = contract.requestByIdLimit
        override val deliveryPriority: Int         = contract.deliveryPriority
        override def producerQoS(
            default: GossipProducerQoS,
        ): GossipProducerQoS = contract.producerQoS(default)

        override def validateArtifact(
            event: GossipEvent[MixedArtifact],
        ): Either[CanonicalRejection.ArtifactContractRejected, Unit] =
          event.payload match
            case MixedArtifact.Consensus(artifact) =>
              contract.validateArtifact(event.copy(payload = artifact))
            case _ =>
              Left(
                CanonicalRejection.ArtifactContractRejected(
                  reason = "unexpectedTopicPayload",
                  detail = Some(topic.value),
                ),
              )

        override def exactKnownScopeOf(
            event: GossipEvent[MixedArtifact],
        ): Either[CanonicalRejection.ArtifactContractRejected, Option[
          ExactKnownSetScope,
        ]] =
          event.payload match
            case MixedArtifact.Consensus(artifact) =>
              contract.exactKnownScopeOf(event.copy(payload = artifact))
            case _ =>
              Left(
                CanonicalRejection.ArtifactContractRejected(
                  reason = "unexpectedTopicPayload",
                  detail = Some(topic.value),
                ),
              )

  private final class MixedSource private (
      clock: GossipClock[IO],
      ref: Ref[IO, Map[ChainTopic, Vector[AvailableGossipEvent[MixedArtifact]]]],
  ) extends GossipArtifactSource[IO, MixedArtifact]:
    def appendTx(
        chainId: ChainId,
        body: String,
        ts: Instant,
    ): IO[GossipEvent[MixedArtifact]] =
      append(
        chainId,
        GossipTopic.tx,
        StableArtifactId.unsafeFromBytes(
          ByteVector.encodeUtf8(body).toOption.get,
        ),
        MixedArtifact.Tx(body),
        ts,
      )

    def appendConsensus(
        artifact: HotStuffGossipArtifact,
        ts: Instant,
    ): IO[GossipEvent[MixedArtifact]] =
      append(
        chainId = artifact match
          case HotStuffGossipArtifact.ProposalArtifact(proposal) =>
            proposal.window.chainId
          case HotStuffGossipArtifact.VoteArtifact(vote) => vote.window.chainId
          case HotStuffGossipArtifact.TimeoutVoteArtifact(timeoutVote) =>
            timeoutVote.subject.window.chainId
          case HotStuffGossipArtifact.NewViewArtifact(newView) =>
            newView.window.chainId
        ,
        topic = HotStuffGossipArtifact.topicOf(artifact),
        id = HotStuffGossipArtifact.stableIdOf(artifact),
        payload = MixedArtifact.Consensus(artifact),
        ts = ts,
      )

    override def readAfter(
        chainId: ChainId,
        topic: GossipTopic,
        cursor: Option[CursorToken],
    ): IO[
      Either[CanonicalRejection, Vector[AvailableGossipEvent[MixedArtifact]]],
    ] =
      ref.get.map: state =>
        val events = state.getOrElse(ChainTopic(chainId, topic), Vector.empty)
        cursor match
          case None => Right(events)
          case Some(token) =>
            token
              .validateVersion()
              .map: validated =>
                events.drop(
                  ByteBuffer.wrap(validated.payload.toArray).getLong().toInt,
                )

    override def readByIds(
        chainId: ChainId,
        topic: GossipTopic,
        ids: Vector[StableArtifactId],
    ): IO[Vector[AvailableGossipEvent[MixedArtifact]]] =
      ref.get.map: state =>
        val latest =
          state
            .getOrElse(ChainTopic(chainId, topic), Vector.empty)
            .foldLeft(
              Map.empty[StableArtifactId, AvailableGossipEvent[MixedArtifact]],
            ): (acc, available) =>
              acc.updated(available.event.id, available)
        ids.distinct.flatMap(latest.get)

    private def append(
        chainId: ChainId,
        topic: GossipTopic,
        id: StableArtifactId,
        payload: MixedArtifact,
        ts: Instant,
    ): IO[GossipEvent[MixedArtifact]] =
      clock.now.flatMap: availableAt =>
        ref.modify: state =>
          val chainTopic = ChainTopic(chainId, topic)
          val events     = state.getOrElse(chainTopic, Vector.empty)
          val event = GossipEvent(
            chainId = chainId,
            topic = topic,
            id = id,
            cursor =
              CursorToken.unsafeIssue(
                ByteVector.fromLong(events.size.toLong + 1L),
              ),
            ts = ts,
            payload = payload,
          )
          state.updated(
            chainTopic,
            events :+ AvailableGossipEvent(event, availableAt),
          ) -> event

  private object MixedSource:
    def create(clock: GossipClock[IO]): IO[MixedSource] =
      Ref
        .of[IO, Map[ChainTopic, Vector[AvailableGossipEvent[MixedArtifact]]]](
          Map.empty,
        )
        .map(new MixedSource(clock, _))

  private final class MixedSink private (
      ref: Ref[IO, Set[StableArtifactId]],
  ) extends GossipArtifactSink[IO, MixedArtifact]:
    override def applyEvent(
        event: GossipEvent[MixedArtifact],
    ): IO[
      Either[CanonicalRejection.ArtifactContractRejected, ArtifactApplyResult],
    ] =
      ref.modify: known =>
        if known.contains(event.id) then
          known -> Right(ArtifactApplyResult(applied = false, duplicate = true))
        else
          (known + event.id) -> Right(
            ArtifactApplyResult(applied = true, duplicate = false),
          )

  private object MixedSink:
    def create: IO[MixedSink] =
      Ref.of[IO, Set[StableArtifactId]](Set.empty).map(new MixedSink(_))
