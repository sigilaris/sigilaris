package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.{Duration, Instant}

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite

import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.crypto.{CryptoOps, Hash}
import org.sigilaris.core.datatype.{UInt256, Utf8}
import org.sigilaris.node.jvm.runtime.block.{BlockHeader, BlockHeight, BlockTimestamp, BodyRoot, StateRoot}
import org.sigilaris.node.jvm.runtime.gossip.*
import org.sigilaris.node.jvm.runtime.gossip.tx.{InMemoryTxArtifactSink, InMemoryTxArtifactSource, TxBatchingConfig, TxGossipRuntime, TxGossipStateStore, TxIdentity, TxRuntimePolicy, TxTopic}

final class HotStuffProposalTxSyncSuite extends CatsEffectSuite:

  private val chainId = ChainId.unsafe("chain-main")
  private val baseInstant = Instant.parse("2026-04-04T12:00:00Z")
  private val subscription = SessionSubscription.unsafe(ChainTopic(chainId, GossipTopic.tx))
  private val validatorKeys = Vector.fill(4)(CryptoOps.generate())
  private val validatorSet = ValidatorSet.unsafe(
    validatorKeys.zipWithIndex.map: (keyPair, index) =>
      ValidatorMember(
        id = ValidatorId.unsafe(s"validator-${index + 1}"),
        publicKey = keyPair.publicKey,
      )
  )

  private final case class TestTx(
      body: Utf8,
  ) derives ByteEncoder

  private given Hash[TestTx] = Hash.build
  private given TxIdentity[TestTx] = TxIdentity.fromHash

  test("controlBatchForProposal marks all proposal tx ids known and requests only missing payloads"):
    val tx1 = TestTx(Utf8("tx-1"))
    val tx2 = TestTx(Utf8("tx-2"))
    val proposal =
      signedProposal(
        ProposalTxSet.fromTxs(Vector(tx1, tx2)),
      )

    val batch =
      HotStuffProposalTxSync
        .controlBatchForProposal(
          proposal = proposal,
          knownTxIds = Set(txId(tx1)),
          idempotencyKey = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
          txPolicy = TxRuntimePolicy(),
        )
        .toOption
        .flatten

    assertEquals(
      batch.map(_.ops),
      Some(
        Vector(
          ControlOp.SetKnownTx(chainId, proposal.txSet.txIds),
          ControlOp.RequestByIdTx(chainId, Vector(txId(tx2))),
        )
      ),
    )

  test("proposal tx-sync control batch suppresses live replay and fetches only missing tx payloads"):
    given GossipClock[IO] = GossipClock.constant[IO](baseInstant)

    val txPolicy =
      TxRuntimePolicy(
        defaultBatchingConfig = TxBatchingConfig(
          maxBatchItems = 128,
          flushInterval = Duration.ZERO,
        ),
      )
    val tx1 = TestTx(Utf8("tx-1"))
    val tx2 = TestTx(Utf8("tx-2"))
    val proposal =
      signedProposal(
        ProposalTxSet.fromTxs(Vector(tx1, tx2)),
      )

    for
      runtime <- createTxRuntime(txPolicy)
      sessionId <- openOutbound(runtime)
      tx1Event <- runtime.source.append(chainId, tx1, baseInstant.minusSeconds(2))
      _ <- runtime.source.append(chainId, tx2, baseInstant.minusSeconds(1))
      batch <- IO.fromEither(
        HotStuffProposalTxSync
          .controlBatchForProposal(
            proposal = proposal,
            knownTxIds = Set(tx1Event.id),
            idempotencyKey = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            txPolicy = txPolicy,
          )
          .leftMap(rejection => new IllegalStateException(rejection.reason))
          .flatMap(_.toRight(new IllegalStateException("missingControlBatch")))
      )
      _ <- runtime.runtime.receiveControlBatch(sessionId, batch).flatMap:
        result =>
          IO.fromEither(
            result.leftMap(rejection => new IllegalStateException(rejection.reason))
          )
      polled <- runtime.runtime.pollEvents(sessionId)
    yield
      assertEquals(
        extractEvents(polled.toOption.get).map(_.payload.body.asString),
        Vector("tx-2"),
      )

  private final case class TxRuntimeHarness(
      runtime: TxGossipRuntime[IO, TestTx],
      source: InMemoryTxArtifactSource[IO, TestTx],
  )

  private def createTxRuntime(
      txPolicy: TxRuntimePolicy,
  )(using
      clock: GossipClock[IO],
  ): IO[TxRuntimeHarness] =
    for
      topology <- IO.fromEither(
        StaticPeerTopology
          .parse(
            localNodeIdentity = "node-a",
            knownPeers = List("node-b"),
            directNeighbors = List("node-b"),
          )
          .leftMap(new IllegalArgumentException(_))
      )
      registry = StaticPeerRegistry(topology)
      authenticator = StaticPeerAuthenticator[IO](registry)
      source <- InMemoryTxArtifactSource.create[IO, TestTx]
      sink <- InMemoryTxArtifactSink.create[IO, TestTx]
      stateStore <- TxGossipStateStore.inMemory[IO](
        GossipSessionEngine(registry.localPeer, topology),
      )
    yield
      TxRuntimeHarness(
        runtime = TxGossipRuntime.withPolicy[IO, TestTx](
          peerAuthenticator = authenticator,
          clock = clock,
          source = source,
          sink = sink,
          topicContracts = GossipTopicContractRegistry.of(TxTopic.contract[TestTx]),
          stateStore = stateStore,
          policy = txPolicy,
        ),
        source = source,
      )

  private def openOutbound(
      harness: TxRuntimeHarness,
  ): IO[DirectionalSessionId] =
    for
      proposalEither <- harness.runtime.startOutbound(
        PeerIdentity.unsafe("node-b"),
        subscription,
      )
      proposal <- IO.fromEither(
        proposalEither.leftMap(rejection => new IllegalStateException(rejection.reason))
      )
      ack = SessionNegotiation
        .acknowledge(
          proposal,
          heartbeatInterval = Duration.ofSeconds(10),
          livenessTimeout = Duration.ofSeconds(30),
          maxControlRetryInterval = Duration.ofSeconds(30),
        )
        .toOption
        .get
      _ <- harness.runtime.applyHandshakeAck(ack).flatMap:
        result =>
          IO.fromEither(
            result.leftMap(rejection => new IllegalStateException(rejection.reason))
          )
    yield proposal.sessionId

  private def extractEvents(
      messages: Vector[EventStreamMessage[TestTx]],
  ): Vector[GossipEvent[TestTx]] =
    messages.collect:
      case EventStreamMessage.Event(event) => event

  private def txId(
      tx: TestTx,
  ): StableArtifactId =
    ProposalTxSet.fromTxs(Vector(tx)).txIds.head

  private def signedProposal(
      txSet: ProposalTxSet,
  ): Proposal =
    val parentBlock = block(parent = None, height = 0L, rootHex = "01")
    val parentWindow = HotStuffWindow(chainId, 0L, 0L, validatorSet.hash)
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
    val proposalBlock = block(parent = Some(subject.blockId), height = 1L, rootHex = "02")
    Proposal
      .sign(
        UnsignedProposal(
          window = HotStuffWindow(chainId, 1L, 1L, validatorSet.hash),
          proposer = validatorSet.members.head.id,
          targetBlockId = BlockHeader.computeId(proposalBlock),
          block = proposalBlock,
          txSet = txSet,
          justify = justify,
        ),
        validatorKeys.head,
      )
      .toOption
      .get

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

  private def hex(
      value: String,
  ): UInt256 =
    UInt256.fromHex(value).toOption.get
