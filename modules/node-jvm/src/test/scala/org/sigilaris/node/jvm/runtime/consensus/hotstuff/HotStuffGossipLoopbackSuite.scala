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
import org.sigilaris.node.jvm.runtime.gossip.*
import org.sigilaris.node.jvm.runtime.gossip.tx.{ControlBatchOutcome, TxGossipRuntime, TxGossipStateStore}

final class HotStuffGossipLoopbackSuite extends CatsEffectSuite:

  private val chainId = ChainId.unsafe("chain-main")
  private val baseInstant = Instant.parse("2026-04-01T00:00:00Z")
  private val validatorKeys = Vector.fill(4)(CryptoOps.generate())
  private val validatorSet = ValidatorSet(
    validatorKeys.zipWithIndex.map: (keyPair, index) =>
      ValidatorMember(ValidatorId.unsafe(s"validator-${index + 1}"), keyPair.publicKey)
  )
  private val subscription = SessionSubscription.unsafe(
    ChainTopic(chainId, GossipTopic.consensusProposal),
    ChainTopic(chainId, GossipTopic.consensusVote),
  )

  test("consensus topics honor exact known-set, requestById, and QC assembly"):
    val holders = Vector(
      ValidatorKeyHolder(validatorSet.members(0).id, PeerIdentity.unsafe("node-a"), ValidatorKeyHolderStatus.Active),
      ValidatorKeyHolder(validatorSet.members(1).id, PeerIdentity.unsafe("node-a"), ValidatorKeyHolderStatus.Active),
      ValidatorKeyHolder(validatorSet.members(2).id, PeerIdentity.unsafe("node-a"), ValidatorKeyHolderStatus.Active),
      ValidatorKeyHolder(validatorSet.members(3).id, PeerIdentity.unsafe("node-c"), ValidatorKeyHolderStatus.Active),
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
      justify = bootstrapQc()
      proposalEvent <- a.consensus.emitProposal(
        proposer = validatorSet.members.head.id,
        block = Block(parent = Some(justify.subject.blockId), payloadHash = hex("81")),
        window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
        justify = justify,
        ts = baseInstant,
      ).flatMap(unwrapPolicy)
      proposal = proposalPayload(proposalEvent)
      _ <- a.consensus.emitVote(validatorSet.members(0).id, proposal, baseInstant.plusMillis(1)).flatMap(unwrapPolicy)
      _ <- a.consensus.emitVote(validatorSet.members(1).id, proposal, baseInstant.plusMillis(2)).flatMap(unwrapPolicy)
      _ <- a.consensus.emitVote(validatorSet.members(2).id, proposal, baseInstant.plusMillis(3)).flatMap(unwrapPolicy)
      _ <- a.gossip.receiveControlBatch(
        sessionId,
        controlBatch(
          "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
          Vector(
            ControlOp.SetKnownExact(
              proposalScope(proposal),
              Vector(HotStuffGossipArtifact.stableIdOf(HotStuffGossipArtifact.ProposalArtifact(proposal))),
            )
          ),
        ),
      )
      firstPoll <- a.gossip.pollEvents(sessionId)
      firstReceive <- b.gossip.receiveEvents(sessionId, firstPoll.toOption.get)
      firstSnapshot <- b.consensus.sink.snapshot
      _ <- a.gossip.receiveControlBatch(
        sessionId,
        controlBatch(
          "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
          Vector(
            ControlOp.RequestByIdExact(
              proposalScope(proposal),
              Vector(HotStuffGossipArtifact.stableIdOf(HotStuffGossipArtifact.ProposalArtifact(proposal))),
            )
          ),
        ),
      )
      secondPoll <- a.gossip.pollEvents(sessionId)
      secondReceive <- b.gossip.receiveEvents(sessionId, secondPoll.toOption.get)
      secondSnapshot <- b.consensus.sink.snapshot
    yield
      assertEquals(
        firstReceive.toOption.get.applied.collect:
          case event if event.topic == GossipTopic.consensusVote => votePayload(event).voter.value,
        Vector("validator-1", "validator-2", "validator-3"),
      )
      assert(!firstSnapshot.proposals.contains(proposal.proposalId))
      assert(!firstSnapshot.qcs.contains(proposal.proposalId))
      assertEquals(
        secondReceive.toOption.get.applied.collect:
          case event if event.topic == GossipTopic.consensusProposal => proposalPayload(event).proposalId,
        Vector(proposal.proposalId),
      )
      assert(secondSnapshot.proposals.contains(proposal.proposalId))
      assert(secondSnapshot.qcs.contains(proposal.justify.subject.proposalId))
      assert(secondSnapshot.qcs.contains(proposal.proposalId))

  test("audit nodes follow consensus artifacts read-only and cannot emit locally"):
    val holders = Vector(
      ValidatorKeyHolder(validatorSet.members(0).id, PeerIdentity.unsafe("node-a"), ValidatorKeyHolderStatus.Active),
      ValidatorKeyHolder(validatorSet.members(1).id, PeerIdentity.unsafe("node-a"), ValidatorKeyHolderStatus.Active),
      ValidatorKeyHolder(validatorSet.members(2).id, PeerIdentity.unsafe("node-a"), ValidatorKeyHolderStatus.Active),
    )

    for
      a <- Harness.create("node-a", "node-b", LocalNodeRole.Validator, holders, Map(
        validatorSet.members(0).id -> validatorKeys(0),
        validatorSet.members(1).id -> validatorKeys(1),
        validatorSet.members(2).id -> validatorKeys(2),
      ))
      b <- Harness.create("node-b", "node-a", LocalNodeRole.Audit, holders, Map.empty)
      opened <- openOutbound(a, b)
      sessionId = opened.proposal.sessionId
      justify = bootstrapQc()
      proposalEvent <- a.consensus.emitProposal(
        proposer = validatorSet.members.head.id,
        block = Block(parent = Some(justify.subject.blockId), payloadHash = hex("82")),
        window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
        justify = justify,
        ts = baseInstant,
      ).flatMap(unwrapPolicy)
      proposal = proposalPayload(proposalEvent)
      _ <- a.consensus.emitVote(validatorSet.members(0).id, proposal, baseInstant.plusMillis(1)).flatMap(unwrapPolicy)
      polled <- a.gossip.pollEvents(sessionId)
      _ <- b.gossip.receiveEvents(sessionId, polled.toOption.get)
      emitAuditProposal <- b.consensus.emitProposal(
        proposer = validatorSet.members.head.id,
        block = Block(parent = Some(justify.subject.blockId), payloadHash = hex("83")),
        window = HotStuffWindow(chainId, 3L, 2L, validatorSet.hash),
        justify = justify,
        ts = baseInstant.plusMillis(10),
      )
      emitAuditVote <- b.consensus.emitVote(validatorSet.members.head.id, proposal, baseInstant.plusMillis(11))
      snapshot <- b.consensus.sink.snapshot
    yield
      assertEquals(emitAuditProposal.left.map(_.reason), Left("auditNodeCannotEmit"))
      assertEquals(emitAuditVote.left.map(_.reason), Left("auditNodeCannotEmit"))
      assert(snapshot.proposals.contains(proposal.proposalId))
      assert(snapshot.votes.nonEmpty)

  test("consensus proposal nack replays from the subscribed topic instead of rejecting as tx-only"):
    val holders = Vector(
      ValidatorKeyHolder(validatorSet.members(0).id, PeerIdentity.unsafe("node-a"), ValidatorKeyHolderStatus.Active),
      ValidatorKeyHolder(validatorSet.members(1).id, PeerIdentity.unsafe("node-a"), ValidatorKeyHolderStatus.Active),
      ValidatorKeyHolder(validatorSet.members(2).id, PeerIdentity.unsafe("node-a"), ValidatorKeyHolderStatus.Active),
    )

    for
      a <- Harness.create("node-a", "node-b", LocalNodeRole.Validator, holders, Map(
        validatorSet.members(0).id -> validatorKeys(0),
        validatorSet.members(1).id -> validatorKeys(1),
        validatorSet.members(2).id -> validatorKeys(2),
      ))
      b <- Harness.create("node-b", "node-a", LocalNodeRole.Audit, holders, Map.empty)
      opened <- openOutbound(a, b)
      sessionId = opened.proposal.sessionId
      justify = bootstrapQc()
      proposalEvent <- a.consensus.emitProposal(
        proposer = validatorSet.members.head.id,
        block = Block(parent = Some(justify.subject.blockId), payloadHash = hex("88")),
        window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
        justify = justify,
        ts = baseInstant,
      ).flatMap(unwrapPolicy)
      firstPoll <- a.gossip.pollEvents(sessionId)
      firstReceive <- b.gossip.receiveEvents(sessionId, firstPoll.toOption.get)
      lastCursor = firstReceive.toOption.get.lastCursor.get
      nackResult <- a.gossip.receiveControlBatch(
        sessionId,
        controlBatch(
          "abababab-abab-4bab-8bab-abababababab",
          Vector(
            ControlOp.SetCursor(CompositeCursor(Map(ChainTopic(chainId, GossipTopic.consensusProposal) -> lastCursor))),
            ControlOp.Nack(chainId, GossipTopic.consensusProposal, None),
          ),
        ),
      )
      replayPoll <- a.gossip.pollEvents(sessionId)
    yield
      assertEquals(nackResult, Right(ControlBatchOutcome.Applied))
      assertEquals(
        replayPoll.toOption.get.collect:
          case EventStreamMessage.Event(event) => proposalPayload(event).proposalId,
        Vector(proposalPayload(proposalEvent).proposalId),
      )

  test("key relocation fences the old holder, enables the new holder, and rejects dual-active startup"):
    val validatorId = validatorSet.members.head.id
    val fencedHolders = Vector(
      ValidatorKeyHolder(validatorId, PeerIdentity.unsafe("node-old"), ValidatorKeyHolderStatus.Fenced),
      ValidatorKeyHolder(validatorId, PeerIdentity.unsafe("node-new"), ValidatorKeyHolderStatus.Active),
    )
    val dualActive = Vector(
      ValidatorKeyHolder(validatorId, PeerIdentity.unsafe("node-old"), ValidatorKeyHolderStatus.Active),
      ValidatorKeyHolder(validatorId, PeerIdentity.unsafe("node-new"), ValidatorKeyHolderStatus.Active),
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
        .flatMap(_.emitProposal(
          proposer = validatorId,
          block = Block(parent = Some(justify.subject.blockId), payloadHash = hex("84")),
          window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
          justify = justify,
          ts = baseInstant,
        ))
      newEmit <- unwrapPolicy(newRuntime)
        .flatMap(_.emitProposal(
          proposer = validatorId,
          block = Block(parent = Some(justify.subject.blockId), payloadHash = hex("85")),
          window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
          justify = justify,
          ts = baseInstant.plusMillis(1),
        ))
    yield
      assertEquals(oldEmit.left.map(_.reason), Left("validatorKeyFenced"))
      assert(newEmit.isRight)
      assertEquals(dualActiveRuntime.left.map(_.reason), Left("dualActiveKeyHolder"))

  test("exact request path rejects oversize, wrong-window, and unknown requests"):
    val holders = Vector(
      ValidatorKeyHolder(validatorSet.members(0).id, PeerIdentity.unsafe("node-a"), ValidatorKeyHolderStatus.Active),
      ValidatorKeyHolder(validatorSet.members(1).id, PeerIdentity.unsafe("node-a"), ValidatorKeyHolderStatus.Active),
      ValidatorKeyHolder(validatorSet.members(2).id, PeerIdentity.unsafe("node-a"), ValidatorKeyHolderStatus.Active),
    )

    for
      a <- Harness.create("node-a", "node-b", LocalNodeRole.Validator, holders, Map(
        validatorSet.members(0).id -> validatorKeys(0),
        validatorSet.members(1).id -> validatorKeys(1),
        validatorSet.members(2).id -> validatorKeys(2),
      ))
      b <- Harness.create("node-b", "node-a", LocalNodeRole.Audit, holders, Map.empty)
      opened <- openOutbound(a, b)
      sessionId = opened.proposal.sessionId
      justify = bootstrapQc()
      proposalEvent <- a.consensus.emitProposal(
        proposer = validatorSet.members.head.id,
        block = Block(parent = Some(justify.subject.blockId), payloadHash = hex("86")),
        window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
        justify = justify,
        ts = baseInstant,
      ).flatMap(unwrapPolicy)
      proposal = proposalPayload(proposalEvent)
      wrongWindow = proposalScope(proposal).copy(windowKey = TopicWindowKey.unsafeFromHex("deadbeef"))
      oversizeIds = (0 until 129).toVector.map(index => StableArtifactId.unsafeFromHex(f"${index + 1}%02x"))
      oversize <- a.gossip.receiveControlBatch(
        sessionId,
        controlBatch(
          "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
          Vector(ControlOp.RequestByIdExact(proposalScope(proposal), oversizeIds)),
        ),
      )
      wrongWindowResult <- a.gossip.receiveControlBatch(
        sessionId,
        controlBatch(
          "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
          Vector(
            ControlOp.RequestByIdExact(
              wrongWindow,
              Vector(HotStuffGossipArtifact.stableIdOf(HotStuffGossipArtifact.ProposalArtifact(proposal))),
            )
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
            )
          ),
        ),
      )
    yield
      assertEquals(oversize.left.map(_.reason), Left("requestByIdTooLarge"))
      assertEquals(wrongWindowResult.left.map(_.reason), Left("wrongWindowKey"))
      assertEquals(unknown.left.map(_.reason), Left("unknownRequestedArtifact"))

  test("consensus proposal and vote qos are not blocked by tx backlog"):
    val holders = Vector(
      ValidatorKeyHolder(validatorSet.members(0).id, PeerIdentity.unsafe("node-a"), ValidatorKeyHolderStatus.Active),
      ValidatorKeyHolder(validatorSet.members(1).id, PeerIdentity.unsafe("node-a"), ValidatorKeyHolderStatus.Active),
      ValidatorKeyHolder(validatorSet.members(2).id, PeerIdentity.unsafe("node-a"), ValidatorKeyHolderStatus.Active),
    )

    for
      topology <- IO.fromEither(
        StaticPeerTopology.parse(
          localNodeIdentity = "node-a",
          knownPeers = List("node-b"),
          directNeighbors = List("node-b"),
        ).leftMap(new IllegalArgumentException(_))
      )
      registry = StaticPeerRegistry(topology)
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
      mixedSink <- MixedSink.create
      stateStore <- TxGossipStateStore.inMemory[IO](GossipSessionEngine(registry.localPeer, topology))
      runtime = TxGossipRuntime[IO, MixedArtifact](
        peerAuthenticator = authenticator,
        clock = clock,
        source = mixedSource,
        sink = mixedSink,
        topicContracts = MixedContracts.registry(consensus.gossipPolicy),
        stateStore = stateStore,
      )
      sessionProposalEither <- runtime.startOutbound(PeerIdentity.unsafe("node-b"), SessionSubscription.unsafe(
        ChainTopic(chainId, GossipTopic.tx),
        ChainTopic(chainId, GossipTopic.consensusProposal),
        ChainTopic(chainId, GossipTopic.consensusVote),
      ))
      sessionProposal <- IO.fromEither(sessionProposalEither.leftMap(rejection => new IllegalStateException(rejection.reason)))
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
      _ <- IO.fromEither(ackResult.leftMap(rejection => new IllegalStateException(rejection.reason)))
      sessionId = sessionProposal.sessionId
      _ <- runtime.receiveControlBatch(
        sessionId,
        controlBatch(
          "ffffffff-ffff-4fff-8fff-ffffffffffff",
          Vector(
            ControlOp.Config(
              Map(
                SessionConfigKey.TxMaxBatchItems -> 32L,
                SessionConfigKey.TxFlushIntervalMs -> 5000L,
              )
            )
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
              targetBlockId = Block.computeId(Block(parent = Some(justify.subject.blockId), payloadHash = hex("87"))),
              block = Block(parent = Some(justify.subject.blockId), payloadHash = hex("87")),
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
      _ <- mixedSource.appendConsensus(HotStuffGossipArtifact.ProposalArtifact(proposal), baseInstant.plusMillis(1))
      _ <- mixedSource.appendConsensus(HotStuffGossipArtifact.VoteArtifact(vote), baseInstant.plusMillis(2))
      polled <- runtime.pollEvents(sessionId)
    yield
      assertEquals(
        polled.toOption.get.collect:
          case EventStreamMessage.Event(event) => event.topic.value,
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
      proposalEither <- from.gossip.startOutbound(PeerIdentity.unsafe(to.localNodeId), subscription)
      proposal <- IO.fromEither(proposalEither.leftMap(rejection => new IllegalStateException(rejection.reason)))
      inboundResult <- to.gossip.handleInboundProposal(proposal)
      accepted <- inboundResult match
        case accepted: InboundHandshakeResult.Accepted =>
          IO.pure(accepted)
        case rejected: InboundHandshakeResult.Rejected =>
          IO.raiseError(new IllegalStateException(rejected.rejection.reason))
      ackResult <- from.gossip.applyHandshakeAck(accepted.ack)
      _ <- IO.fromEither(ackResult.leftMap(rejection => new IllegalStateException(rejection.reason)))
    yield OpenedSession(proposal, accepted.ack)

  private def bootstrapQc(): QuorumCertificate =
    val window = HotStuffWindow(chainId, 1L, 0L, validatorSet.hash)
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

  private def proposalScope(
      proposal: Proposal,
  ): ExactKnownSetScope =
    ExactKnownSetScope(
      chainId = proposal.window.chainId,
      topic = GossipTopic.consensusProposal,
      windowKey = HotStuffWindowKey.fromWindow(proposal.window),
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

  private def controlBatch(
      idempotencyKey: String,
      ops: Vector[ControlOp],
  ): ControlBatch =
    ControlBatch.create(idempotencyKey, ops).toOption.get

  private def hex(
      value: String,
  ): UInt256 =
    UInt256.fromHex(value).toOption.get

  private def unwrapPolicy[A](
      result: Either[HotStuffPolicyViolation, A],
  ): IO[A] =
    IO.fromEither(result.leftMap(rejection => new IllegalStateException(rejection.reason)))

  private final case class OpenedSession(
      proposal: SessionOpenProposal,
      ack: SessionOpenAck,
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
    ): IO[Harness] =
      for
        topology <- IO.fromEither(
          StaticPeerTopology.parse(
            localNodeIdentity = localNodeId,
            knownPeers = List(remoteNodeId),
            directNeighbors = List(remoteNodeId),
          ).leftMap(new IllegalArgumentException(_))
        )
        registry = StaticPeerRegistry(topology)
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
          )
          .flatMap(unwrapPolicy)
        stateStore <- TxGossipStateStore.inMemory[IO](GossipSessionEngine(registry.localPeer, topology))
      yield Harness(
        localNodeId = localNodeId,
        gossip = TxGossipRuntime[IO, HotStuffGossipArtifact](
          peerAuthenticator = authenticator,
          clock = clock,
          source = consensus.source,
          sink = consensus.sink,
          topicContracts = consensus.topicContracts,
          stateStore = stateStore,
        ),
        consensus = consensus,
        clock = clock,
      )

  private final class TestClock private (ref: Ref[IO, Instant]) extends GossipClock[IO]:
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
              )
            )

    def registry(
        policy: HotStuffGossipPolicy,
    ): GossipTopicContractRegistry[MixedArtifact] =
      GossipTopicContractRegistry.of(
        TxContract,
        wrapHotStuff(HotStuffTopic.proposalContract(policy.proposal)),
        wrapHotStuff(HotStuffTopic.voteContract(policy.vote)),
      )

    private def wrapHotStuff(
        contract: GossipTopicContract[HotStuffGossipArtifact],
    ): GossipTopicContract[MixedArtifact] =
      new GossipTopicContract[MixedArtifact]:
        override val topic: GossipTopic = contract.topic
        override val exactKnownSetLimit: Option[Int] = contract.exactKnownSetLimit
        override val requestByIdLimit: Option[Int] = contract.requestByIdLimit
        override val deliveryPriority: Int = contract.deliveryPriority
        override def producerQoS(default: GossipProducerQoS): GossipProducerQoS = contract.producerQoS(default)

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
                )
              )

        override def exactKnownScopeOf(
            event: GossipEvent[MixedArtifact],
        ): Either[CanonicalRejection.ArtifactContractRejected, Option[ExactKnownSetScope]] =
          event.payload match
            case MixedArtifact.Consensus(artifact) =>
              contract.exactKnownScopeOf(event.copy(payload = artifact))
            case _ =>
              Left(
                CanonicalRejection.ArtifactContractRejected(
                  reason = "unexpectedTopicPayload",
                  detail = Some(topic.value),
                )
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
      append(chainId, GossipTopic.tx, StableArtifactId.unsafeFromBytes(ByteVector.encodeUtf8(body).toOption.get), MixedArtifact.Tx(body), ts)

    def appendConsensus(
        artifact: HotStuffGossipArtifact,
        ts: Instant,
    ): IO[GossipEvent[MixedArtifact]] =
      append(
        chainId = artifact match
          case HotStuffGossipArtifact.ProposalArtifact(proposal) => proposal.window.chainId
          case HotStuffGossipArtifact.VoteArtifact(vote)         => vote.window.chainId,
        topic = HotStuffGossipArtifact.topicOf(artifact),
        id = HotStuffGossipArtifact.stableIdOf(artifact),
        payload = MixedArtifact.Consensus(artifact),
        ts = ts,
      )

    override def readAfter(
        chainId: ChainId,
        topic: GossipTopic,
        cursor: Option[CursorToken],
    ): IO[Either[CanonicalRejection, Vector[AvailableGossipEvent[MixedArtifact]]]] =
      ref.get.map: state =>
        val events = state.getOrElse(ChainTopic(chainId, topic), Vector.empty)
        cursor match
          case None => Right(events)
          case Some(token) =>
            token.validateVersion().map: validated =>
              events.drop(ByteBuffer.wrap(validated.payload.toArray).getLong().toInt)

    override def readByIds(
        chainId: ChainId,
        topic: GossipTopic,
        ids: Vector[StableArtifactId],
    ): IO[Vector[AvailableGossipEvent[MixedArtifact]]] =
      ref.get.map: state =>
        val latest =
          state
            .getOrElse(ChainTopic(chainId, topic), Vector.empty)
            .foldLeft(Map.empty[StableArtifactId, AvailableGossipEvent[MixedArtifact]]): (acc, available) =>
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
          val events = state.getOrElse(chainTopic, Vector.empty)
          val event = GossipEvent(
            chainId = chainId,
            topic = topic,
            id = id,
            cursor = CursorToken.issue(ByteVector.fromLong(events.size.toLong + 1L)),
            ts = ts,
            payload = payload,
          )
          state.updated(chainTopic, events :+ AvailableGossipEvent(event, availableAt)) -> event

  private object MixedSource:
    def create(clock: GossipClock[IO]): IO[MixedSource] =
      Ref.of[IO, Map[ChainTopic, Vector[AvailableGossipEvent[MixedArtifact]]]](Map.empty).map(new MixedSource(clock, _))

  private final class MixedSink private (
      ref: Ref[IO, Set[StableArtifactId]],
  ) extends GossipArtifactSink[IO, MixedArtifact]:
    override def applyEvent(
        event: GossipEvent[MixedArtifact],
    ): IO[Either[CanonicalRejection.ArtifactContractRejected, ArtifactApplyResult]] =
      ref.modify: known =>
        if known.contains(event.id) then
          known -> Right(ArtifactApplyResult(applied = false, duplicate = true))
        else
          (known + event.id) -> Right(ArtifactApplyResult(applied = true, duplicate = false))

  private object MixedSink:
    def create: IO[MixedSink] =
      Ref.of[IO, Set[StableArtifactId]](Set.empty).map(new MixedSink(_))
