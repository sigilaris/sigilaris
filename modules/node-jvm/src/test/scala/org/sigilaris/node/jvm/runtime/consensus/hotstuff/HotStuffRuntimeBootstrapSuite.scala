package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue

import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.Using

import cats.effect.IO
import cats.effect.Resource
import cats.effect.kernel.Ref
import cats.syntax.all.*
import munit.CatsEffectSuite
import scodec.bits.ByteVector

import com.typesafe.config.{Config, ConfigFactory}

import org.sigilaris.core.application.scheduling.{
  ConflictFootprint,
  SchedulingClassification,
}
import org.sigilaris.core.codec.byte.ByteDecoder.ops.*
import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.crypto.{CryptoOps, KeyPair}
import org.sigilaris.core.crypto.Hash
import org.sigilaris.core.crypto.Hash.ops.*
import org.sigilaris.core.datatype.{UInt256, Utf8}
import org.sigilaris.core.merkle.MerkleTrieNode
import org.sigilaris.core.merkle.Nibbles.*
import org.sigilaris.node.jvm.runtime.block.{
  BlockBody,
  BlockHeader,
  BlockHeight,
  BlockId,
  BlockRecord,
  BlockStore,
  BlockTimestamp,
  BlockView,
  BodyRoot,
  StateRoot,
}
import org.sigilaris.node.jvm.runtime.gossip.*
import org.sigilaris.node.jvm.storage.swaydb.StorageLayout

final class HotStuffRuntimeBootstrapSuite extends CatsEffectSuite:
  private val openedBootstrapReleases = new ConcurrentLinkedQueue[IO[Unit]]()
  private val tempStorageRoots        = new ConcurrentLinkedQueue[Path]()

  private val chainId       = ChainId.unsafe("chain-main")
  private val startedAt     = Instant.parse("2026-04-02T00:00:00Z")
  private val validatorKeys = Vector.fill(4)(CryptoOps.generate())
  private val validatorIds =
    validatorKeys.indices.toVector.map(index =>
      ValidatorId.unsafe(s"validator-${index + 1}"),
    )
  private final case class TestTx(
      body: Utf8,
  ) derives ByteEncoder
  private given Hash[TestTx]          = Hash.build
  private val historicalValidatorKeys = Vector.fill(4)(CryptoOps.generate())
  private val historicalValidatorSet = ValidatorSet.unsafe(
    historicalValidatorKeys.zipWithIndex.map: (keyPair, index) =>
      ValidatorMember(
        id = ValidatorId.unsafe(s"validator-hist-${index + 1}"),
        publicKey = keyPair.publicKey,
      ),
  )
  private val inventoryValidatorKeys = Vector.fill(4)(CryptoOps.generate())
  private val inventoryValidatorSet = ValidatorSet.unsafe(
    inventoryValidatorKeys.zipWithIndex.map: (keyPair, index) =>
      ValidatorMember(
        id = ValidatorId.unsafe(s"validator-inv-${index + 1}"),
        publicKey = keyPair.publicKey,
      ),
  )
  private val subscription = SessionSubscription.unsafe(
    ChainTopic(chainId, GossipTopic.consensusProposal),
    ChainTopic(chainId, GossipTopic.consensusVote),
  )

  private def withTestTransportPeerSecrets(
      config: Config,
  ): Config =
    Try(StaticPeerTopologyConfig.load(config)).toOption match
      case Some(Right(topology)) =>
        val peerSecrets =
          (topology.knownPeers + topology.localNodeIdentity).toVector
            .sortBy(_.value)
            .map(peer =>
              s"""    "${peer.value}" = "sigilaris-test-secret:${peer.value}"""",
            )
            .mkString("\n")
        ConfigFactory
          .parseString(
            s"""
               |sigilaris.node.gossip.peers.transport-auth.peer-secrets {
               |$peerSecrets
               |}
               |""".stripMargin,
          )
          .withFallback(config)
      case _ =>
        config

  test(
    "config loader builds HotStuff bootstrap and wires runtime services into the gossip graph",
  ):
    val config = ConfigFactory.parseString(
      s"""
         |sigilaris.node.gossip.peers {
         |  local-node-identity = "node-a"
         |  known-peers = ["node-b", "node-c"]
         |  direct-neighbors = ["node-b"]
         |}
         |
         |sigilaris.node.consensus.hotstuff {
         |  local-role = "validator"
         |  validators = [
         |    { id = "${validatorIds(0).value}", public-key = "${validatorKeys(
          0,
        ).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(1).value}", public-key = "${validatorKeys(
          1,
        ).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(2).value}", public-key = "${validatorKeys(
          2,
        ).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(3).value}", public-key = "${validatorKeys(
          3,
        ).publicKey.toBytes.toHex}" }
         |  ]
         |  key-holders = [
         |    { validator-id = "${validatorIds(
          0,
        ).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(
          1,
        ).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(
          2,
        ).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(
          3,
        ).value}", holder = "node-b", status = "active" }
         |  ]
         |  local-signers = [
         |    { validator-id = "${validatorIds(
          0,
        ).value}", private-key = "${validatorKeys(0).privateKey.toHexLower}" },
         |    { validator-id = "${validatorIds(
          1,
        ).value}", private-key = "${validatorKeys(1).privateKey.toHexLower}" },
         |    { validator-id = "${validatorIds(
          2,
        ).value}", private-key = "${validatorKeys(2).privateKey.toHexLower}" }
         |  ]
         |  gossip-policy {
         |    proposal {
         |      exact-known-set-limit = 300
         |      request-by-id-limit = 96
         |      max-batch-items = 7
         |      flush-interval-ms = 11
         |      delivery-priority = 4
         |    }
         |    vote {
         |      exactKnownSetLimit = 4000
         |      requestByIdLimit = 321
         |      maxBatchItems = 17
         |      flushIntervalMs = 19
         |      deliveryPriority = 5
         |    }
         |    timeout-vote {
         |      exact-known-set-limit = 4100
         |      request-by-id-limit = 333
         |      max-batch-items = 23
         |      flush-interval-ms = 29
         |      delivery-priority = 6
         |    }
         |    newView {
         |      exactKnownSetLimit = 2050
         |      requestByIdLimit = 111
         |      maxBatchItems = 13
         |      flushIntervalMs = 31
         |      deliveryPriority = 7
         |    }
         |  }
         |}
         |""".stripMargin,
    )

    for
      clock <- TestClock.create(startedAt)
      bootstrap <- loadBootstrapFromConfig(
        config = config,
        clock = clock,
        storageLayout = freshStorageLayout,
      )
      outboundAllowed <- bootstrap.runtime.startOutbound(
        PeerIdentity.unsafe("node-b"),
        subscription,
      )
      outboundRejected <- bootstrap.runtime.startOutbound(
        PeerIdentity.unsafe("node-c"),
        subscription,
      )
    yield
      assertEquals(
        bootstrap.topology.localNodeIdentity,
        PeerIdentity.unsafe("node-a"),
      )
      assertEquals(
        bootstrap.registry.directNeighbors,
        Set(PeerIdentity.unsafe("node-b")),
      )
      assertEquals(bootstrap.consensus.localPeer, PeerIdentity.unsafe("node-a"))
      assertEquals(bootstrap.consensus.role, LocalNodeRole.Validator)
      assertEquals(
        bootstrap.consensus.validatorSet.members.map(_.id),
        validatorIds,
      )
      assertEquals(
        bootstrap.consensus.holders.map(_.holder).toSet,
        Set(PeerIdentity.unsafe("node-a"), PeerIdentity.unsafe("node-b")),
      )
      assertEquals(
        bootstrap.consensus.localKeys.keySet,
        validatorIds.take(3).toSet,
      )
      val proposalContract = bootstrap.consensus.topicContracts
        .contractFor(GossipTopic.consensusProposal)
        .toOption
        .get
      val voteContract = bootstrap.consensus.topicContracts
        .contractFor(GossipTopic.consensusVote)
        .toOption
        .get
      val timeoutVoteContract = bootstrap.consensus.topicContracts
        .contractFor(GossipTopic.consensusTimeoutVote)
        .toOption
        .get
      val newViewContract = bootstrap.consensus.topicContracts
        .contractFor(GossipTopic.consensusNewView)
        .toOption
        .get
      assertEquals(bootstrap.consensus.gossipPolicy.proposal.maxBatchItems, 7)
      assertEquals(bootstrap.consensus.gossipPolicy.vote.deliveryPriority, 5)
      assertEquals(
        bootstrap.consensus.gossipPolicy.timeoutVote.requestByIdLimit,
        333,
      )
      assertEquals(bootstrap.consensus.gossipPolicy.newView.deliveryPriority, 7)
      assertEquals(proposalContract.exactKnownSetLimit, Some(300))
      assertEquals(proposalContract.requestByIdLimit, Some(96))
      assertEquals(proposalContract.deliveryPriority, 4)
      assertEquals(voteContract.exactKnownSetLimit, Some(4000))
      assertEquals(voteContract.requestByIdLimit, Some(321))
      assertEquals(voteContract.deliveryPriority, 5)
      assertEquals(timeoutVoteContract.exactKnownSetLimit, Some(4100))
      assertEquals(timeoutVoteContract.requestByIdLimit, Some(333))
      assertEquals(timeoutVoteContract.deliveryPriority, 6)
      assertEquals(newViewContract.exactKnownSetLimit, Some(2050))
      assertEquals(newViewContract.requestByIdLimit, Some(111))
      assertEquals(newViewContract.deliveryPriority, 7)
      assertEquals(
        outboundAllowed.map(_.acceptor),
        Right(PeerIdentity.unsafe("node-b")),
      )
      assertEquals(outboundRejected.left.map(_.reason), Left("nonNeighborPeer"))

  test(
    "fromTopology fails when the durable historical archive path cannot be opened",
  ):
    tempDirResource.use: root =>
      val layout = StorageLayout.fromRoot(root)
      for
        _ <- IO.blocking(
          Files.createDirectories(layout.state.historicalArchive.getParent),
        )
        _ <- IO.blocking(
          Files.writeString(layout.state.historicalArchive, "blocked"),
        )
        clock <- TestClock.create(startedAt)
        bootstrapEither <- HotStuffRuntimeBootstrap
          .fromTopology[IO](
            topology = topology("node-a", Vector("node-b")),
            transportAuth = StaticPeerTransportAuth.testing(
              topology("node-a", Vector("node-b")),
            ),
            consensusConfig = validatorConfig(),
            clock = clock,
            storageLayout = layout,
          )
          .use(IO.pure)
      yield
        assert(bootstrapEither.isLeft)
        assert(
          bootstrapEither.left.toOption.exists(_.contains("historical-archive")),
        )

  test(
    "config loader reports signer references that are outside the validator set",
  ):
    val config = ConfigFactory.parseString(
      s"""
         |sigilaris.node.gossip.peers {
         |  local-node-identity = "node-a"
         |  known-peers = ["node-b"]
         |  direct-neighbors = ["node-b"]
         |}
         |
         |sigilaris.node.consensus.hotstuff {
         |  local-role = "audit"
         |  validators = [
         |    { id = "${validatorIds(0).value}", public-key = "${validatorKeys(
          0,
        ).publicKey.toBytes.toHex}" }
         |  ]
         |  key-holders = [
         |    { validator-id = "${validatorIds(
          0,
        ).value}", holder = "node-a", status = "active" }
         |  ]
         |  local-signers = [
         |    { validator-id = "${validatorIds(
          1,
        ).value}", private-key = "${validatorKeys(1).privateKey.toHexLower}" }
         |  ]
         |}
         |""".stripMargin,
    )

    for
      clock <- TestClock.create(startedAt)
      bootstrapEither <- HotStuffRuntimeBootstrap
        .fromConfig[IO](
          withTestTransportPeerSecrets(config),
          clock,
          storageLayout = freshStorageLayout,
        )
        .use(IO.pure)
    yield assertEquals(
      bootstrapEither.left.map(_.startsWith("unknown validator signer:")),
      Left(true),
    )

  test(
    "config loader reports holder references that are outside the validator set",
  ):
    val config = ConfigFactory.parseString(
      s"""
         |sigilaris.node.gossip.peers {
         |  local-node-identity = "node-a"
         |  known-peers = ["node-b"]
         |  direct-neighbors = ["node-b"]
         |}
         |
         |sigilaris.node.consensus.hotstuff {
         |  local-role = "audit"
         |  validators = [
         |    { id = "${validatorIds(0).value}", public-key = "${validatorKeys(
          0,
        ).publicKey.toBytes.toHex}" }
         |  ]
         |  key-holders = [
         |    { validator-id = "validator-x", holder = "node-a", status = "active" }
         |  ]
         |  local-signers = []
         |}
         |""".stripMargin,
    )

    for
      clock <- TestClock.create(startedAt)
      bootstrapEither <- HotStuffRuntimeBootstrap
        .fromConfig[IO](
          withTestTransportPeerSecrets(config),
          clock,
          storageLayout = freshStorageLayout,
        )
        .use(IO.pure)
    yield assertEquals(
      bootstrapEither.left.map(_.startsWith("unknown key holder validator:")),
      Left(true),
    )

  test("config loader parses historical sync opt-out"):
    val config = ConfigFactory.parseString(
      s"""
         |sigilaris.node.consensus.hotstuff {
         |  local-role = "validator"
         |  historical-sync-enabled = false
         |  validators = [
         |    { id = "${validatorIds(0).value}", public-key = "${validatorKeys(
          0,
        ).publicKey.toBytes.toHex}" }
         |  ]
         |  key-holders = [
         |    { validator-id = "${validatorIds(
          0,
        ).value}", holder = "node-a", status = "active" }
         |  ]
         |  local-signers = [
         |    { validator-id = "${validatorIds(
          0,
        ).value}", private-key = "${validatorKeys(0).privateKey.toHexLower}" }
         |  ]
         |}
         |""".stripMargin,
    )

    val loaded = HotStuffBootstrapConfig.load(config)

    assertEquals(loaded.map(_.historicalSyncEnabled), Right(false))

  test(
    "assembled runtime reports disabled historical sync and skips backfill transport when opted out",
  ):
    val topology =
      StaticPeerTopology
        .parse(
          localNodeIdentity = "node-a",
          knownPeers = List("node-b"),
          directNeighbors = List("node-b"),
        )
        .toOption
        .get
    val root =
      MerkleTrieNode.branch(
        ByteVector.empty.toNibbles,
        MerkleTrieNode.Children.empty,
      )
    val rootHash = root.toHash
    val anchor   = finalizedSuggestion("c1", StateRoot(rootHash.toUInt256))
    val session =
      BootstrapSessionBinding(
        peer = PeerIdentity.unsafe("node-b"),
        sessionId = DirectionalSessionId
          .parse("cccccccc-cccc-4ccc-8ccc-cccccccccccc")
          .toOption
          .get,
      )
    val holders = Vector(
      ValidatorKeyHolder(
        validatorIds(0),
        PeerIdentity.unsafe("node-a"),
        ValidatorKeyHolderStatus.Active,
      ),
      ValidatorKeyHolder(
        validatorIds(1),
        PeerIdentity.unsafe("node-a"),
        ValidatorKeyHolderStatus.Active,
      ),
      ValidatorKeyHolder(
        validatorIds(2),
        PeerIdentity.unsafe("node-a"),
        ValidatorKeyHolderStatus.Active,
      ),
      ValidatorKeyHolder(
        validatorIds(3),
        PeerIdentity.unsafe("node-b"),
        ValidatorKeyHolderStatus.Active,
      ),
    )
    val config =
      HotStuffBootstrapConfig(
        role = LocalNodeRole.Validator,
        validatorSet = validatorSet,
        holders = holders,
        localKeys = Map(
          validatorIds(0) -> validatorKeys(0),
          validatorIds(1) -> validatorKeys(1),
          validatorIds(2) -> validatorKeys(2),
        ),
        historicalSyncEnabled = false,
      )

    for
      clock         <- TestClock.create(startedAt)
      backfillCalls <- Ref.of[IO, Int](0)
      transport = HotStuffBootstrapTransportServices[IO](
        finalizedAnchorSuggestions = new FinalizedAnchorSuggestionService[IO]:
          override def bestFinalized(
              session: BootstrapSessionBinding,
              chainId: ChainId,
          ): IO[Either[CanonicalRejection, Option[FinalizedAnchorSuggestion]]] =
            IO.pure(Right(Some(anchor)))
        ,
        snapshotNodeFetch = new SnapshotNodeFetchService[IO]:
          override def fetchNodes(
              session: BootstrapSessionBinding,
              chainId: ChainId,
              stateRoot: StateRoot,
              hashes: Vector[MerkleTrieNode.MerkleHash],
          ): IO[Either[CanonicalRejection, Vector[SnapshotTrieNode]]] =
            IO.pure(
              Right(
                hashes
                  .filter(_ === rootHash)
                  .map(_ => SnapshotTrieNode(rootHash, root)),
              ),
            )
        ,
        proposalReplay = new ProposalReplayService[IO]:
          override def readNext(
              session: BootstrapSessionBinding,
              chainId: ChainId,
              anchorBlockId: BlockId,
              nextHeight: BlockHeight,
              limit: Int,
          ): IO[Either[CanonicalRejection, Vector[Proposal]]] =
            IO.pure(Right(Vector.empty))
        ,
        historicalBackfill = new HistoricalBackfillService[IO]:
          override def readPrevious(
              session: BootstrapSessionBinding,
              chainId: ChainId,
              beforeBlockId: BlockId,
              beforeHeight: BlockHeight,
              limit: Int,
          ): IO[Either[CanonicalRejection, Vector[Proposal]]] =
            backfillCalls.update(_ + 1) *> IO.pure(Right(Vector.empty)),
      )
      bootstrap <- loadBootstrapFromTopology(
        topology = topology,
        consensusConfig = config,
        clock = clock,
        bootstrapTransport = transport.some,
        storageLayout = freshStorageLayout,
      )
      result <- bootstrap.consensus.bootstrap(
        chainId = chainId,
        sessions = Vector(session),
        startedAt = startedAt,
        liveProposals = Vector.empty,
      )
      diagnostics <- bootstrap.consensus.currentBootstrapDiagnostics
      calls       <- backfillCalls.get
    yield
      assert(result.isRight)
      assertEquals(calls, 0)
      assertEquals(
        result.map(_.diagnostics.historicalBackfill),
        Right(
          HistoricalBackfillStatus.Disabled(
            HistoricalBackfillWorker.DisabledByPolicyReason,
          ),
        ),
      )
      assertEquals(
        diagnostics.historicalBackfill,
        HistoricalBackfillStatus.Disabled(
          HistoricalBackfillWorker.DisabledByPolicyReason,
        ),
      )

  test(
    "assembled bootstrap runtime fails non-empty catch-up without injected proposal readiness",
  ):
    val root =
      MerkleTrieNode.branch(
        ByteVector.empty.toNibbles,
        MerkleTrieNode.Children.empty,
      )
    val rootHash = root.toHash
    val anchor =
      replayableSuggestion(
        seed = "e1",
        stateRoot = StateRoot(rootHash.toUInt256),
        childTxs = Vector(TestTx(Utf8("tx-1"))),
        grandchildTxs = Vector.empty,
      )

    for
      clock <- TestClock.create(startedAt)
      bootstrap <- loadBootstrapFromTopology(
        topology = topology("node-a", Vector("node-b")),
        consensusConfig = validatorConfig(),
        clock = clock,
        bootstrapTransport = Some(
          proposalTransport(
            anchor = anchor,
            replayed = Vector(anchor.finalizedProof.child),
            snapshotRoot = rootHash -> root,
            proposalCatchUpReadiness = None,
          ),
        ),
        storageLayout = freshStorageLayout,
      )
      result <- bootstrap.consensus.bootstrap(
        chainId = chainId,
        sessions = Vector(
          bootstrapSession(
            peer = "node-b",
            sessionId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
          ),
        ),
        startedAt = startedAt,
        liveProposals = Vector.empty,
      )
    yield assertEquals(
      result.left.map(_.reason),
      Left("proposalBodyRootMismatch"),
    )

  test(
    "application-neutral fallback readiness accepts only matching or legacy proposal bodies",
  ):
    val readiness = ApplicationNeutralProposalView.readiness[IO](validatorSet)
    val applicationNeutral =
      signedApplicationNeutralReplayProposal(
        parent = Some(bootstrapQc().subject.blockId),
        height = 1L,
        proposerIndex = 0,
        justify = bootstrapQc(),
        stateRoot = StateRoot(Utf8("readiness-root").toHash.toUInt256),
        txSeeds = Vector("tx-a"),
      )
    val legacyAutomatic =
      signedLegacyAutomaticReplayProposal(
        parent = Some(applicationNeutral.targetBlockId),
        height = 2L,
        proposerIndex = 1,
        justify = qcFor(applicationNeutral),
        stateRoot = StateRoot(Utf8("readiness-root").toHash.toUInt256),
      )
    val applicationOwned =
      signedReplayProposal(
        parent = Some(bootstrapQc().subject.blockId),
        height = 3L,
        proposerIndex = 2,
        justify = bootstrapQc(),
        stateRoot = StateRoot(Utf8("readiness-root").toHash.toUInt256),
        txs = Vector(TestTx(Utf8("tx-app-owned"))),
        bodyHexFallback = "deadbeef",
      )

    for
      applicationNeutralResult <- readiness.assess(applicationNeutral)
      legacyAutomaticResult    <- readiness.assess(legacyAutomatic)
      applicationOwnedResult   <- readiness.assess(applicationOwned)
    yield
      assertEquals(
        applicationNeutralResult,
        Right(
          ProposalCatchUpAssessment(
            voteReadiness = BootstrapVoteReadiness.Ready,
            controlBatch = None,
          ),
        ),
      )
      assertEquals(
        legacyAutomaticResult,
        Right(
          ProposalCatchUpAssessment(
            voteReadiness = BootstrapVoteReadiness.Ready,
            controlBatch = None,
          ),
        ),
      )
      assertNotEquals(
        applicationOwned.block.bodyRoot,
        ApplicationNeutralProposalView
          .bodyRoot(applicationOwned.txSet.txIds)
          .toOption
          .get,
      )
      assertEquals(
        applicationOwnedResult.left.map(_.reason),
        Left("proposalBodyRootMismatch"),
      )

  test(
    "application-neutral fallback readiness surfaces proposal validation failures after body-root match",
  ):
    val readiness = ApplicationNeutralProposalView.readiness[IO](validatorSet)
    val proposal =
      signedApplicationNeutralReplayProposal(
        parent = Some(bootstrapQc().subject.blockId),
        height = 1L,
        proposerIndex = 0,
        justify = bootstrapQc(),
        stateRoot = StateRoot(Utf8("readiness-root").toHash.toUInt256),
        txSeeds = Vector("tx-valid"),
      )
    val targetBlockMismatch =
      proposal.copy(
        targetBlockId = BlockId(Utf8("wrong-target").toHash.toUInt256),
      )

    for result <- readiness.assess(targetBlockMismatch)
    yield assertEquals(
      result.left.map(_.reason),
      Left("targetBlockIdMismatch"),
    )

  test(
    "assembled bootstrap runtime advances replay catch-up without injected readiness when proposal tx-set carries an application-neutral block view",
  ):
    val root =
      MerkleTrieNode.branch(
        ByteVector.empty.toNibbles,
        MerkleTrieNode.Children.empty,
      )
    val rootHash = root.toHash
    val anchor =
      applicationNeutralReplayableSuggestion(
        seed = "e2-default",
        stateRoot = StateRoot(rootHash.toUInt256),
        childTxSeeds = Vector("tx-1"),
        grandchildTxSeeds = Vector("tx-2"),
      )
    val child      = anchor.finalizedProof.child
    val grandchild = anchor.finalizedProof.grandchild

    for
      clock <- TestClock.create(startedAt)
      bootstrap <- loadBootstrapFromTopology(
        topology = topology("node-a", Vector("node-b")),
        consensusConfig = validatorConfig(),
        clock = clock,
        bootstrapTransport = Some(
          proposalTransport(
            anchor = anchor,
            replayed = Vector(child, grandchild),
            snapshotRoot = rootHash -> root,
            proposalCatchUpReadiness = None,
          ),
        ),
        storageLayout = freshStorageLayout,
      )
      result <- bootstrap.consensus.bootstrap(
        chainId = chainId,
        sessions = Vector(
          bootstrapSession(
            peer = "node-b",
            sessionId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
          ),
        ),
        startedAt = startedAt,
        liveProposals = Vector.empty,
      )
      diagnostics <- bootstrap.consensus.currentBootstrapDiagnostics
    yield
      assertEquals(
        result.map(_.forwardCatchUp.applied.map(_.proposalId)),
        Right(Vector(child.proposalId, grandchild.proposalId)),
      )
      assertEquals(
        result.map(_.forwardCatchUp.voteReadiness),
        Right(BootstrapVoteReadiness.Ready),
      )
      assertEquals(diagnostics.phase, BootstrapPhase.Ready)

  test("proposal codec round-trips non-empty application-neutral tx sets"):
    val proposal =
      signedApplicationNeutralReplayProposal(
        parent = Some(bootstrapQc().subject.blockId),
        height = 1L,
        proposerIndex = 0,
        justify = bootstrapQc(),
        stateRoot = StateRoot(Utf8("codec-root").toHash.toUInt256),
        txSeeds = Vector("tx-a", "tx-b"),
      )

    assertEquals(
      ByteEncoder[Proposal].encode(proposal).to[Proposal],
      Right(proposal),
    )

  test("proposal tx-set codec preserves the legacy fixed-width wire format"):
    val txSet =
      ApplicationNeutralProposalView.proposalTxSet(
        Vector(
          applicationNeutralTxId("legacy-codec-a"),
          applicationNeutralTxId("legacy-codec-b"),
        ),
      )
    val expectedWireBytes =
      ByteVector.fromByte(0x02.toByte) ++ txSet.txIds.foldLeft(
        ByteVector.empty,
      ):
        case (encoded, txId) =>
          encoded ++ txId.bytes

    assertEquals(
      ByteEncoder[ProposalTxSet].encode(txSet),
      expectedWireBytes,
    )
    assertEquals(
      expectedWireBytes.to[ProposalTxSet],
      Right(txSet),
    )

  test(
    "proposal signing rejects tx ids that are not fixed-width wire compatible",
  ):
    val header =
      BlockHeader(
        parent = Some(bootstrapQc().subject.blockId),
        height = BlockHeight.unsafeFromLong(1L),
        stateRoot = StateRoot(Utf8("codec-root").toHash.toUInt256),
        bodyRoot = BodyRoot(Utf8("codec-body").toHash.toUInt256),
        timestamp =
          BlockTimestamp.unsafeFromEpochMillis(startedAt.toEpochMilli + 1L),
      )

    assertEquals(
      Proposal
        .sign(
          UnsignedProposal(
            window = HotStuffWindow(chainId, 1L, 1L, validatorSet.hash),
            proposer = validatorIds(0),
            targetBlockId = BlockHeader.computeId(header),
            block = header,
            txSet = ProposalTxSet.canonical(
              ProposalTxSet(Vector(StableArtifactId.unsafeFromHex("ff"))),
            ),
            justify = bootstrapQc(),
          ),
          validatorKeys(0),
        )
        .left
        .map(_.reason),
      Left("proposalTxIdUnsupported"),
    )

  test(
    "assembled bootstrap runtime advances replay catch-up without injected readiness for legacy automatic proposals",
  ):
    val root =
      MerkleTrieNode.branch(
        ByteVector.empty.toNibbles,
        MerkleTrieNode.Children.empty,
      )
    val rootHash = root.toHash
    val anchor =
      legacyAutomaticReplayableSuggestion(
        stateRoot = StateRoot(rootHash.toUInt256),
      )
    val child      = anchor.finalizedProof.child
    val grandchild = anchor.finalizedProof.grandchild

    for
      clock <- TestClock.create(startedAt)
      bootstrap <- loadBootstrapFromTopology(
        topology = topology("node-a", Vector("node-b")),
        consensusConfig = validatorConfig(),
        clock = clock,
        bootstrapTransport = Some(
          proposalTransport(
            anchor = anchor,
            replayed = Vector(child, grandchild),
            snapshotRoot = rootHash -> root,
            proposalCatchUpReadiness = None,
          ),
        ),
        storageLayout = freshStorageLayout,
      )
      result <- bootstrap.consensus.bootstrap(
        chainId = chainId,
        sessions = Vector(
          bootstrapSession(
            peer = "node-b",
            sessionId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
          ),
        ),
        startedAt = startedAt,
        liveProposals = Vector.empty,
      )
      diagnostics <- bootstrap.consensus.currentBootstrapDiagnostics
    yield
      assertEquals(
        result.map(_.forwardCatchUp.applied.map(_.proposalId)),
        Right(Vector(child.proposalId, grandchild.proposalId)),
      )
      assertEquals(
        result.map(_.forwardCatchUp.voteReadiness),
        Right(BootstrapVoteReadiness.Ready),
      )
      assertEquals(diagnostics.phase, BootstrapPhase.Ready)

  test(
    "assembled bootstrap runtime advances replay catch-up once injected readiness sees tx sufficiency",
  ):
    val root =
      MerkleTrieNode.branch(
        ByteVector.empty.toNibbles,
        MerkleTrieNode.Children.empty,
      )
    val rootHash = root.toHash
    val anchor =
      replayableSuggestion(
        seed = "e2",
        stateRoot = StateRoot(rootHash.toUInt256),
        childTxs = Vector(TestTx(Utf8("tx-1"))),
        grandchildTxs = Vector(TestTx(Utf8("tx-2"))),
      )
    val child          = anchor.finalizedProof.child
    val grandchild     = anchor.finalizedProof.grandchild
    val childTx        = TestTx(Utf8("tx-1"))
    val grandchildTx   = TestTx(Utf8("tx-2"))
    val grandchildTxId = grandchild.txSet.txIds.head

    for
      knownTxIds <- Ref.of[IO, Set[StableArtifactId]](
        Set(child.txSet.txIds.head),
      )
      blockStore <- BlockStore.inMemory[IO, TestTx, Utf8, Utf8]
      _          <- putProposalView(blockStore, child, Vector(childTx))
      _     <- putProposalView(blockStore, grandchild, Vector(grandchildTx))
      clock <- TestClock.create(startedAt)
      bootstrap <- loadBootstrapFromTopology(
        topology = topology("node-a", Vector("node-b")),
        consensusConfig = validatorConfig(),
        clock = clock,
        bootstrapTransport = Some(
          proposalTransport(
            anchor = anchor,
            replayed = Vector(child, grandchild),
            snapshotRoot = rootHash -> root,
            proposalCatchUpReadiness = Some(
              HotStuffRuntimeBootstrap
                .proposalCatchUpReadinessFromBlockQuery[IO, TestTx, Utf8, Utf8](
                  validatorSet = validatorSet,
                  knownTxIds = knownTxIds.get,
                  blockQuery = blockStore,
                )(_ => schedulable()),
            ),
          ),
        ),
        storageLayout = freshStorageLayout,
      )
      session =
        bootstrapSession(
          peer = "node-b",
          sessionId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        )
      first <- bootstrap.consensus.bootstrap(
        chainId = chainId,
        sessions = Vector(session),
        startedAt = startedAt,
        liveProposals = Vector.empty,
      )
      voteWhileHeld <- bootstrap.consensus.emitVote(
        voter = validatorIds.head,
        proposal = grandchild,
        ts = startedAt.plusSeconds(1L),
      )
      materializedWhileHeld <- IO
        .fromOption(
          bootstrap.consensus.bootstrapLifecycle,
        )(new IllegalStateException("missing bootstrap lifecycle"))
        .flatMap(_.forwardStore.current(chainId))
      _ <- knownTxIds.update(_ + grandchildTxId)
      second <- bootstrap.consensus.bootstrap(
        chainId = chainId,
        sessions = Vector(session),
        startedAt = startedAt.plusSeconds(2L),
        liveProposals = Vector.empty,
      )
      voteAfterReady <- bootstrap.consensus.emitVote(
        voter = validatorIds.head,
        proposal = grandchild,
        ts = startedAt.plusSeconds(3L),
      )
      diagnostics <- bootstrap.consensus.currentBootstrapDiagnostics
    yield
      assertEquals(
        first.map(_.forwardCatchUp.applied.map(_.proposalId)),
        Right(Vector(child.proposalId)),
      )
      assertEquals(
        first.map(_.forwardCatchUp.queued.map(_.proposalId)),
        Right(Vector(grandchild.proposalId)),
      )
      assertEquals(
        first.map(_.forwardCatchUp.voteReadiness),
        Right(BootstrapVoteReadiness.Held("missingTxPayload")),
      )
      assertEquals(voteWhileHeld.left.map(_.reason), Left("bootstrapVoteHeld"))
      materializedWhileHeld match
        case Some(materialized) =>
          assertEquals(
            materialized.controlBatches.flatMap(_.ops),
            Vector(
              ControlOp.SetKnownTx(chainId, grandchild.txSet.txIds),
              ControlOp.RequestByIdTx(chainId, Vector(grandchildTxId)),
            ),
          )
        case None =>
          fail("expected materialized forward catch-up state")
      assertEquals(
        second.map(_.forwardCatchUp.applied.map(_.proposalId)),
        Right(Vector(child.proposalId, grandchild.proposalId)),
      )
      assertEquals(
        second.map(_.forwardCatchUp.voteReadiness),
        Right(BootstrapVoteReadiness.Ready),
      )
      assert(voteAfterReady.isRight)
      assertEquals(diagnostics.phase, BootstrapPhase.Ready)
      assertEquals(
        diagnostics.chains(chainId).voteReadiness,
        BootstrapVoteReadiness.Ready,
      )

  test(
    "automatic pacemaker suppresses timeout emission while bootstrap vote-readiness is held and releases it once ready",
  ):
    val root =
      MerkleTrieNode.branch(
        ByteVector.empty.toNibbles,
        MerkleTrieNode.Children.empty,
      )
    val rootHash = root.toHash
    val anchor =
      replayableSuggestion(
        seed = "e4",
        stateRoot = StateRoot(rootHash.toUInt256),
        childTxs = Vector(TestTx(Utf8("tx-4"))),
        grandchildTxs = Vector(TestTx(Utf8("tx-5"))),
      )
    val child          = anchor.finalizedProof.child
    val grandchild     = anchor.finalizedProof.grandchild
    val childTx        = TestTx(Utf8("tx-4"))
    val grandchildTx   = TestTx(Utf8("tx-5"))
    val grandchildTxId = grandchild.txSet.txIds.head
    val localValidatorKey =
      HotStuffPacemakerKey(chainId, validatorIds.head)

    for
      knownTxIds <- Ref.of[IO, Set[StableArtifactId]](
        Set(child.txSet.txIds.head),
      )
      blockStore <- BlockStore.inMemory[IO, TestTx, Utf8, Utf8]
      _          <- putProposalView(blockStore, child, Vector(childTx))
      _     <- putProposalView(blockStore, grandchild, Vector(grandchildTx))
      clock <- TestClock.create(startedAt)
      bootstrap <- loadBootstrapFromTopology(
        topology = topology("node-a", Vector("node-b")),
        consensusConfig = validatorConfig(),
        clock = clock,
        bootstrapTransport = Some(
          proposalTransport(
            anchor = anchor,
            replayed = Vector(child, grandchild),
            snapshotRoot = rootHash -> root,
            proposalCatchUpReadiness = Some(
              HotStuffRuntimeBootstrap
                .proposalCatchUpReadinessFromBlockQuery[IO, TestTx, Utf8, Utf8](
                  validatorSet = validatorSet,
                  knownTxIds = knownTxIds.get,
                  blockQuery = blockStore,
                )(_ => schedulable()),
            ),
          ),
        ),
        storageLayout = freshStorageLayout,
      )
      session =
        bootstrapSession(
          peer = "node-b",
          sessionId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        )
      first <- bootstrap.consensus.bootstrap(
        chainId = chainId,
        sessions = Vector(session),
        startedAt = startedAt,
        liveProposals = Vector.empty,
      )
      proposalEvent <- bootstrap.consensus.services.publisher.append(
        HotStuffGossipArtifact.ProposalArtifact(grandchild),
        startedAt.plusSeconds(10L),
      )
      _ <- bootstrap.consensus.sink
        .applyEvent(proposalEvent)
        .flatMap(result =>
          IO.fromEither(
            result.leftMap(rejection =>
              new IllegalStateException(rejection.reason),
            ),
          ).void,
        )
      _ <- clock.advance(
        HotStuffPacemakerPolicy.default.baseTimeout.plusSeconds(1L),
      )
      heldTimeoutVotes <- bootstrap.consensus.source
        .readAfter(chainId, GossipTopic.consensusTimeoutVote, None)
        .flatMap(result =>
          IO.fromEither(
            result.leftMap(rejection =>
              new IllegalStateException(rejection.toString),
            ),
          ),
        )
      heldPacemaker <- bootstrap.consensus.currentPacemakerSnapshot
      _             <- knownTxIds.update(_ + grandchildTxId)
      second <- bootstrap.consensus.bootstrap(
        chainId = chainId,
        sessions = Vector(session),
        startedAt = startedAt.plusSeconds(2L),
        liveProposals = Vector.empty,
      )
      _ <- clock.advance(
        HotStuffPacemakerPolicy.default.baseTimeout.plusSeconds(1L),
      )
      readyTimeoutVotes <- bootstrap.consensus.source
        .readAfter(chainId, GossipTopic.consensusTimeoutVote, None)
        .flatMap(result =>
          IO.fromEither(
            result.leftMap(rejection =>
              new IllegalStateException(rejection.toString),
            ),
          ),
        )
      readyPacemaker <- bootstrap.consensus.currentPacemakerSnapshot
    yield
      assertEquals(
        first.map(_.forwardCatchUp.voteReadiness),
        Right(BootstrapVoteReadiness.Held("missingTxPayload")),
      )
      assertEquals(heldTimeoutVotes.map(_.event.payload), Vector.empty)
      assertEquals(
        heldPacemaker
          .flatMap(_.entries.get(localValidatorKey))
          .flatMap(_.state.map(_.activeWindow)),
        Some(grandchild.window),
      )
      assertEquals(
        heldPacemaker
          .flatMap(_.entries.get(localValidatorKey))
          .flatMap(_.proposalEligibility),
        Some(
          HotStuffPacemakerProposalEligibility.BootstrapHeld(
            reason = "missingTxPayload",
            expectedLeader = validatorIds(3),
          ),
        ),
      )
      assertEquals(
        second.map(_.forwardCatchUp.voteReadiness),
        Right(BootstrapVoteReadiness.Ready),
      )
      assertEquals(
        readyTimeoutVotes
          .map(available => timeoutVotePayload(available.event).voter)
          .toSet,
        validatorIds.take(3).toSet,
      )
      assert(
        readyPacemaker
          .flatMap(_.entries.get(localValidatorKey))
          .flatMap(_.state.map(_.activeWindow))
          .exists(window =>
            window.height > grandchild.window.height ||
              (window.height === grandchild.window.height &&
                window.view >= HotStuffView.unsafeFromLong(4L)),
          ),
      )

  test(
    "assembled bootstrap runtime advances live catch-up once injected readiness sees the block view",
  ):
    val root =
      MerkleTrieNode.branch(
        ByteVector.empty.toNibbles,
        MerkleTrieNode.Children.empty,
      )
    val rootHash = root.toHash
    val anchor =
      replayableSuggestion(
        seed = "e3",
        stateRoot = StateRoot(rootHash.toUInt256),
        childTxs = Vector(TestTx(Utf8("tx-3"))),
        grandchildTxs = Vector.empty,
      )
    val liveProposal = anchor.finalizedProof.child
    val liveTx       = TestTx(Utf8("tx-3"))

    for
      knownTxIds <- Ref.of[IO, Set[StableArtifactId]](
        liveProposal.txSet.txIds.toSet,
      )
      blockStore <- BlockStore.inMemory[IO, TestTx, Utf8, Utf8]
      clock      <- TestClock.create(startedAt)
      bootstrap <- loadBootstrapFromTopology(
        topology = topology("node-a", Vector("node-b")),
        consensusConfig = validatorConfig(),
        clock = clock,
        bootstrapTransport = Some(
          proposalTransport(
            anchor = anchor,
            replayed = Vector.empty,
            snapshotRoot = rootHash -> root,
            proposalCatchUpReadiness = Some(
              HotStuffRuntimeBootstrap
                .proposalCatchUpReadinessFromBlockQuery[IO, TestTx, Utf8, Utf8](
                  validatorSet = validatorSet,
                  knownTxIds = knownTxIds.get,
                  blockQuery = blockStore,
                )(_ => schedulable()),
            ),
          ),
        ),
        storageLayout = freshStorageLayout,
      )
      session =
        bootstrapSession(
          peer = "node-b",
          sessionId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        )
      first <- bootstrap.consensus.bootstrap(
        chainId = chainId,
        sessions = Vector(session),
        startedAt = startedAt,
        liveProposals = Vector(liveProposal),
      )
      voteWhileHeld <- bootstrap.consensus.emitVote(
        voter = validatorIds.head,
        proposal = liveProposal,
        ts = startedAt.plusSeconds(1L),
      )
      _ <- putProposalView(blockStore, liveProposal, Vector(liveTx))
      second <- bootstrap.consensus.bootstrap(
        chainId = chainId,
        sessions = Vector(session),
        startedAt = startedAt.plusSeconds(2L),
        liveProposals = Vector(liveProposal),
      )
      voteAfterReady <- bootstrap.consensus.emitVote(
        voter = validatorIds.head,
        proposal = liveProposal,
        ts = startedAt.plusSeconds(3L),
      )
    yield
      assertEquals(
        first.map(_.forwardCatchUp.voteReadiness),
        Right(BootstrapVoteReadiness.Held("proposalViewUnavailable")),
      )
      assertEquals(
        first.map(_.forwardCatchUp.queued.map(_.proposalId)),
        Right(Vector(liveProposal.proposalId)),
      )
      assertEquals(voteWhileHeld.left.map(_.reason), Left("bootstrapVoteHeld"))
      assertEquals(
        second.map(_.forwardCatchUp.voteReadiness),
        Right(BootstrapVoteReadiness.Ready),
      )
      assertEquals(
        second.map(_.forwardCatchUp.applied.map(_.proposalId)),
        Right(Vector(liveProposal.proposalId)),
      )
      assert(voteAfterReady.isRight)

  test(
    "config loader parses trusted checkpoint roots and historical validator-set inventory",
  ):
    val config = ConfigFactory.parseString(
      s"""
         |sigilaris.node.consensus.hotstuff {
         |  local-role = "validator"
         |  validators = [
         |${validatorSetEntries(validatorSet, indent = "    ")}
         |  ]
         |  key-holders = [
         |${keyHolderEntries(validatorIds, indent = "    ")}
         |  ]
         |  local-signers = [
         |${localSignerEntries(
          validatorIds.take(3),
          validatorKeys.take(3),
          indent = "    ",
        )}
         |  ]
         |  bootstrap-trust-root {
         |    kind = "trusted-checkpoint"
         |    chain-id = "${chainId.value}"
         |    height = 9
         |    view = 4
         |    validator-set-hash = "${historicalValidatorSet.hash.toHexLower}"
         |    validator-set = [
         |${validatorSetEntries(historicalValidatorSet, indent = "      ")}
         |    ]
         |  }
         |  historical-validator-sets = [
         |    {
         |      validator-set-hash = "${inventoryValidatorSet.hash.toHexLower}"
         |      validators = [
         |${validatorSetEntries(inventoryValidatorSet, indent = "        ")}
         |      ]
         |    }
         |  ]
         |}
         |""".stripMargin,
    )

    val loaded = HotStuffBootstrapConfig.load(config)

    assertEquals(
      loaded.map(_.bootstrapTrustRoot.validatorSetHash),
      Right(historicalValidatorSet.hash),
    )
    assertEquals(
      loaded.map(_.validatorSetLookupInventory.map(_.hash).toSet),
      Right(
        Set(
          historicalValidatorSet.hash,
          inventoryValidatorSet.hash,
          validatorSet.hash,
        ),
      ),
    )

  test(
    "assembled bootstrap runtime wires trusted checkpoint roots and historical inventory into validator-set lookup",
  ):
    val config = ConfigFactory.parseString(
      s"""
         |sigilaris.node.gossip.peers {
         |  local-node-identity = "node-a"
         |  known-peers = ["node-b"]
         |  direct-neighbors = ["node-b"]
         |}
         |
         |sigilaris.node.consensus.hotstuff {
         |  local-role = "validator"
         |  validators = [
         |${validatorSetEntries(validatorSet, indent = "    ")}
         |  ]
         |  key-holders = [
         |${keyHolderEntries(validatorIds, indent = "    ")}
         |  ]
         |  local-signers = [
         |${localSignerEntries(
          validatorIds.take(3),
          validatorKeys.take(3),
          indent = "    ",
        )}
         |  ]
         |  bootstrap-trust-root {
         |    kind = "trusted-checkpoint"
         |    chain-id = "${chainId.value}"
         |    height = 9
         |    view = 4
         |    validator-set = [
         |${validatorSetEntries(historicalValidatorSet, indent = "      ")}
         |    ]
         |  }
         |  historical-validator-sets = [
         |    {
         |      validators = [
         |${validatorSetEntries(inventoryValidatorSet, indent = "        ")}
         |      ]
         |    }
         |  ]
         |}
         |""".stripMargin,
    )

    for
      clock <- TestClock.create(startedAt)
      bootstrap <- loadBootstrapFromConfig(
        config = config,
        clock = clock,
        storageLayout = freshStorageLayout,
      )
      rootLookup <- bootstrap.consensus.bootstrapServices.validatorSetLookup
        .validatorSetFor:
          HotStuffWindow(chainId, 9L, 4L, historicalValidatorSet.hash)
      historicalLookup <-
        bootstrap.consensus.bootstrapServices.validatorSetLookup
          .validatorSetFor:
            HotStuffWindow(chainId, 6L, 2L, inventoryValidatorSet.hash)
      currentLookup <- bootstrap.consensus.bootstrapServices.validatorSetLookup
        .validatorSetFor:
          HotStuffWindow(chainId, 12L, 5L, validatorSet.hash)
    yield
      assert(
        bootstrap.consensus.bootstrapTrustRoot
          .isInstanceOf[BootstrapTrustRoot.TrustedCheckpoint],
      )
      assertEquals(
        bootstrap.consensus.bootstrapTrustRoot.validatorSetHash,
        historicalValidatorSet.hash,
      )
      assertEquals(rootLookup.map(_.hash), Right(historicalValidatorSet.hash))
      assertEquals(
        historicalLookup.map(_.hash),
        Right(inventoryValidatorSet.hash),
      )
      assertEquals(currentLookup.map(_.hash), Right(validatorSet.hash))

  test(
    "assembled bootstrap runtime rejects expired weak-subjectivity anchors",
  ):
    val config = ConfigFactory.parseString(
      s"""
         |sigilaris.node.gossip.peers {
         |  local-node-identity = "node-a"
         |  known-peers = ["node-b"]
         |  direct-neighbors = ["node-b"]
         |}
         |
         |sigilaris.node.consensus.hotstuff {
         |  local-role = "validator"
         |  validators = [
         |${validatorSetEntries(validatorSet, indent = "    ")}
         |  ]
         |  key-holders = [
         |${keyHolderEntries(validatorIds, indent = "    ")}
         |  ]
         |  local-signers = [
         |${localSignerEntries(
          validatorIds.take(3),
          validatorKeys.take(3),
          indent = "    ",
        )}
         |  ]
         |  bootstrap-trust-root {
         |    kind = "weak-subjectivity-anchor"
         |    chain-id = "${chainId.value}"
         |    height = 9
         |    view = 4
         |    fresh-until = "2026-04-01T23:59:59Z"
         |    validator-set = [
         |${validatorSetEntries(historicalValidatorSet, indent = "      ")}
         |    ]
         |  }
         |}
         |""".stripMargin,
    )

    for
      clock <- TestClock.create(startedAt)
      bootstrapEither <- HotStuffRuntimeBootstrap
        .fromConfig[IO](
          withTestTransportPeerSecrets(config),
          clock,
          storageLayout = freshStorageLayout,
        )
        .use(IO.pure)
    yield assertEquals(
      bootstrapEither.left.map(_.startsWith("weakSubjectivityAnchorExpired:")),
      Left(true),
    )

  test(
    "HotStuff bootstrap default runtime policy enables the same-window retry budget baseline",
  ):
    assertEquals(
      HotStuffRuntimeBootstrap.DefaultRuntimePolicy.maxExactRequestRetriesPerScope,
      Some(HotStuffPolicy.requestPolicy.maxRetryAttemptsPerWindow),
    )

  test(
    "assembled bootstrap runtime wires newcomer lifecycle diagnostics and gates votes before ready",
  ):
    val config = ConfigFactory.parseString(
      s"""
         |sigilaris.node.gossip.peers {
         |  local-node-identity = "node-a"
         |  known-peers = ["node-b"]
         |  direct-neighbors = ["node-b"]
         |}
         |
         |sigilaris.node.consensus.hotstuff {
         |  local-role = "validator"
         |  validators = [
         |    { id = "${validatorIds(0).value}", public-key = "${validatorKeys(
          0,
        ).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(1).value}", public-key = "${validatorKeys(
          1,
        ).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(2).value}", public-key = "${validatorKeys(
          2,
        ).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(3).value}", public-key = "${validatorKeys(
          3,
        ).publicKey.toBytes.toHex}" }
         |  ]
         |  key-holders = [
         |    { validator-id = "${validatorIds(
          0,
        ).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(
          1,
        ).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(
          2,
        ).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(
          3,
        ).value}", holder = "node-b", status = "active" }
         |  ]
         |  local-signers = [
         |    { validator-id = "${validatorIds(
          0,
        ).value}", private-key = "${validatorKeys(0).privateKey.toHexLower}" },
         |    { validator-id = "${validatorIds(
          1,
        ).value}", private-key = "${validatorKeys(1).privateKey.toHexLower}" },
         |    { validator-id = "${validatorIds(
          2,
        ).value}", private-key = "${validatorKeys(2).privateKey.toHexLower}" }
         |  ]
         |}
         |""".stripMargin,
    )

    for
      clock <- TestClock.create(startedAt)
      bootstrap <- loadBootstrapFromConfig(
        config = config,
        clock = clock,
        storageLayout = freshStorageLayout,
      )
      metadataBefore <- bootstrap.consensus.bootstrapLifecycle
        .toRight(
          new IllegalStateException("missing bootstrap lifecycle"),
        )
        .liftTo[IO]
      diagnosticsBefore <- bootstrap.consensus.currentBootstrapDiagnostics
      bootstrapFailure <- bootstrap.consensus.bootstrap(
        chainId = chainId,
        sessions = Vector.empty,
        startedAt = startedAt,
        liveProposals = Vector.empty,
      )
      diagnosticsAfter <- bootstrap.consensus.currentBootstrapDiagnostics
      voteAttempt <- bootstrap.consensus.emitVote(
        voter = validatorIds.head,
        proposal = signedProposal("91", 2L),
        ts = startedAt.plusSeconds(1L),
      )
      metadata <- metadataBefore.metadataStore.get(chainId)
      nodeMissing <- metadataBefore.nodeStore.get(
        SnapshotNodeVerifier.rootHash(StateRoot(hex("ff"))),
      )
    yield
      assert(bootstrap.consensus.bootstrapLifecycle.nonEmpty)
      assertEquals(diagnosticsBefore, BootstrapDiagnostics.empty)
      assertEquals(diagnosticsBefore.phase, BootstrapPhase.Discovery)
      assertEquals(diagnosticsBefore.chains, Map.empty)
      assertEquals(
        bootstrapFailure.left.map(_.reason),
        Left("noVerifiableFinalizedAnchor"),
      )
      assertEquals(diagnosticsAfter.phase, BootstrapPhase.Discovery)
      assertEquals(diagnosticsAfter.retryAttempts, 1)
      assertEquals(
        diagnosticsAfter.lastFailure,
        Some(s"${chainId.value}:noVerifiableFinalizedAnchor"),
      )
      assertEquals(voteAttempt.left.map(_.reason), Left("bootstrapVoteHeld"))
      assertEquals(metadata, None)
      assertEquals(nodeMissing, None)

  test(
    "assembled bootstrap runtime gates proposal emission and pacemaker artifacts once bootstrap hold becomes active",
  ):
    val config = ConfigFactory.parseString(
      s"""
         |sigilaris.node.gossip.peers {
         |  local-node-identity = "node-a"
         |  known-peers = ["node-b"]
         |  direct-neighbors = ["node-b"]
         |}
         |
         |sigilaris.node.consensus.hotstuff {
         |  local-role = "validator"
         |  validators = [
         |    { id = "${validatorIds(0).value}", public-key = "${validatorKeys(
          0,
        ).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(1).value}", public-key = "${validatorKeys(
          1,
        ).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(2).value}", public-key = "${validatorKeys(
          2,
        ).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(3).value}", public-key = "${validatorKeys(
          3,
        ).publicKey.toBytes.toHex}" }
         |  ]
         |  key-holders = [
         |    { validator-id = "${validatorIds(
          0,
        ).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(
          1,
        ).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(
          2,
        ).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(
          3,
        ).value}", holder = "node-b", status = "active" }
         |  ]
         |  local-signers = [
         |    { validator-id = "${validatorIds(
          0,
        ).value}", private-key = "${validatorKeys(0).privateKey.toHexLower}" },
         |    { validator-id = "${validatorIds(
          1,
        ).value}", private-key = "${validatorKeys(1).privateKey.toHexLower}" },
         |    { validator-id = "${validatorIds(
          2,
        ).value}", private-key = "${validatorKeys(2).privateKey.toHexLower}" }
         |  ]
         |}
         |""".stripMargin,
    )

    for
      clock <- TestClock.create(startedAt)
      bootstrap <- loadBootstrapFromConfig(
        config = config,
        clock = clock,
        storageLayout = freshStorageLayout,
      )
      _ <- bootstrap.consensus.bootstrap(
        chainId = chainId,
        sessions = Vector.empty,
        startedAt = startedAt,
        liveProposals = Vector.empty,
      )
      proposalAttempt <- bootstrap.consensus.emitProposal(
        proposer = validatorIds.head,
        block = BlockHeader(
          parent = Some(bootstrapQc().subject.blockId),
          height = BlockHeight.unsafeFromLong(2L),
          stateRoot = StateRoot(hex("95")),
          bodyRoot = BodyRoot(hex("95")),
          timestamp =
            BlockTimestamp.unsafeFromEpochMillis(startedAt.toEpochMilli),
        ),
        txSet = ProposalTxSet.empty,
        window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
        justify = bootstrapQc(),
        ts = startedAt.plusSeconds(1L),
      )
      timeoutVoteAttempt <- bootstrap.consensus.signTimeoutVote(
        voter = validatorIds.head,
        window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
        highestKnownQc = bootstrapQc(),
      )
      newViewAttempt <- bootstrap.consensus.signNewView(
        sender = validatorIds.head,
        highestKnownQc = bootstrapQc(),
        timeoutCertificate = timeoutCertificateFor(
          window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
          highestKnownQc = bootstrapQc(),
        ),
      )
    yield
      assertEquals(
        proposalAttempt.left.map(_.reason),
        Left("bootstrapVoteHeld"),
      )
      assertEquals(
        timeoutVoteAttempt.left.map(_.reason),
        Left("bootstrapVoteHeld"),
      )
      assertEquals(newViewAttempt.left.map(_.reason), Left("bootstrapVoteHeld"))

  test(
    "assembled validator runtime does not gate votes before bootstrap activation starts",
  ):
    val config = ConfigFactory.parseString(
      s"""
         |sigilaris.node.gossip.peers {
         |  local-node-identity = "node-a"
         |  known-peers = ["node-b"]
         |  direct-neighbors = ["node-b"]
         |}
         |
         |sigilaris.node.consensus.hotstuff {
         |  local-role = "validator"
         |  validators = [
         |    { id = "${validatorIds(0).value}", public-key = "${validatorKeys(
          0,
        ).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(1).value}", public-key = "${validatorKeys(
          1,
        ).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(2).value}", public-key = "${validatorKeys(
          2,
        ).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(3).value}", public-key = "${validatorKeys(
          3,
        ).publicKey.toBytes.toHex}" }
         |  ]
         |  key-holders = [
         |    { validator-id = "${validatorIds(
          0,
        ).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(
          1,
        ).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(
          2,
        ).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(
          3,
        ).value}", holder = "node-b", status = "active" }
         |  ]
         |  local-signers = [
         |    { validator-id = "${validatorIds(
          0,
        ).value}", private-key = "${validatorKeys(0).privateKey.toHexLower}" },
         |    { validator-id = "${validatorIds(
          1,
        ).value}", private-key = "${validatorKeys(1).privateKey.toHexLower}" },
         |    { validator-id = "${validatorIds(
          2,
        ).value}", private-key = "${validatorKeys(2).privateKey.toHexLower}" }
         |  ]
         |}
         |""".stripMargin,
    )

    for
      clock <- TestClock.create(startedAt)
      bootstrap <- loadBootstrapFromConfig(
        config = config,
        clock = clock,
        storageLayout = freshStorageLayout,
      )
      voteAttempt <- bootstrap.consensus.emitVote(
        voter = validatorIds.head,
        proposal = signedProposal("93", 2L),
        ts = startedAt.plusSeconds(1L),
      )
    yield
      assert(bootstrap.consensus.bootstrapLifecycle.nonEmpty)
      assert(voteAttempt.isRight)

  test(
    "assembled validator runtime can sign pacemaker artifacts before bootstrap activation starts",
  ):
    val config = ConfigFactory.parseString(
      s"""
         |sigilaris.node.gossip.peers {
         |  local-node-identity = "node-a"
         |  known-peers = ["node-b"]
         |  direct-neighbors = ["node-b"]
         |}
         |
         |sigilaris.node.consensus.hotstuff {
         |  local-role = "validator"
         |  validators = [
         |    { id = "${validatorIds(0).value}", public-key = "${validatorKeys(
          0,
        ).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(1).value}", public-key = "${validatorKeys(
          1,
        ).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(2).value}", public-key = "${validatorKeys(
          2,
        ).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(3).value}", public-key = "${validatorKeys(
          3,
        ).publicKey.toBytes.toHex}" }
         |  ]
         |  key-holders = [
         |    { validator-id = "${validatorIds(
          0,
        ).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(
          1,
        ).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(
          2,
        ).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(
          3,
        ).value}", holder = "node-b", status = "active" }
         |  ]
         |  local-signers = [
         |    { validator-id = "${validatorIds(
          0,
        ).value}", private-key = "${validatorKeys(0).privateKey.toHexLower}" },
         |    { validator-id = "${validatorIds(
          1,
        ).value}", private-key = "${validatorKeys(1).privateKey.toHexLower}" },
         |    { validator-id = "${validatorIds(
          2,
        ).value}", private-key = "${validatorKeys(2).privateKey.toHexLower}" }
         |  ]
         |}
         |""".stripMargin,
    )

    for
      clock <- TestClock.create(startedAt)
      bootstrap <- loadBootstrapFromConfig(
        config = config,
        clock = clock,
        storageLayout = freshStorageLayout,
      )
      timeoutVoteAttempt <- bootstrap.consensus.signTimeoutVote(
        voter = validatorIds.head,
        window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
        highestKnownQc = bootstrapQc(),
      )
      timeoutVote <- IO.fromEither(
        timeoutVoteAttempt.leftMap(rejection =>
          new IllegalStateException(rejection.reason),
        ),
      )
      newViewAttempt <- bootstrap.consensus.signNewView(
        sender = validatorIds.head,
        highestKnownQc = bootstrapQc(),
        timeoutCertificate = timeoutCertificateFor(
          window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
          highestKnownQc = bootstrapQc(),
        ),
      )
      newView <- IO.fromEither(
        newViewAttempt.leftMap(rejection =>
          new IllegalStateException(rejection.reason),
        ),
      )
    yield
      assert(bootstrap.consensus.bootstrapLifecycle.nonEmpty)
      assertEquals(
        HotStuffValidator.validateTimeoutVote(timeoutVote, validatorSet),
        Right(()),
      )
      assertEquals(
        HotStuffValidator.validateNewView(newView, validatorSet),
        Right(()),
      )

  test(
    "assembled validator runtime keeps signer failures ahead of the bootstrap vote gate",
  ):
    val config = ConfigFactory.parseString(
      s"""
         |sigilaris.node.gossip.peers {
         |  local-node-identity = "node-a"
         |  known-peers = ["node-b"]
         |  direct-neighbors = ["node-b"]
         |}
         |
         |sigilaris.node.consensus.hotstuff {
         |  local-role = "validator"
         |  validators = [
         |    { id = "${validatorIds(0).value}", public-key = "${validatorKeys(
          0,
        ).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(1).value}", public-key = "${validatorKeys(
          1,
        ).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(2).value}", public-key = "${validatorKeys(
          2,
        ).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(3).value}", public-key = "${validatorKeys(
          3,
        ).publicKey.toBytes.toHex}" }
         |  ]
         |  key-holders = [
         |    { validator-id = "${validatorIds(
          0,
        ).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(
          1,
        ).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(
          2,
        ).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(
          3,
        ).value}", holder = "node-b", status = "active" }
         |  ]
         |  local-signers = [
         |    { validator-id = "${validatorIds(
          0,
        ).value}", private-key = "${validatorKeys(0).privateKey.toHexLower}" }
         |  ]
         |}
         |""".stripMargin,
    )

    for
      clock <- TestClock.create(startedAt)
      bootstrap <- loadBootstrapFromConfig(
        config = config,
        clock = clock,
        storageLayout = freshStorageLayout,
      )
      _ <- bootstrap.consensus.bootstrap(
        chainId = chainId,
        sessions = Vector.empty,
        startedAt = startedAt,
        liveProposals = Vector.empty,
      )
      voteAttempt <- bootstrap.consensus.emitVote(
        voter = validatorIds(1),
        proposal = signedProposal("94", 2L),
        ts = startedAt.plusSeconds(1L),
      )
    yield
      assert(bootstrap.consensus.bootstrapLifecycle.nonEmpty)
      assertEquals(
        voteAttempt.left.map(_.reason),
        Left("localValidatorKeyUnavailable"),
      )

  test(
    "assembled validator runtime keeps pacemaker signer failures ahead of the bootstrap gate",
  ):
    val config = ConfigFactory.parseString(
      s"""
         |sigilaris.node.gossip.peers {
         |  local-node-identity = "node-a"
         |  known-peers = ["node-b"]
         |  direct-neighbors = ["node-b"]
         |}
         |
         |sigilaris.node.consensus.hotstuff {
         |  local-role = "validator"
         |  validators = [
         |    { id = "${validatorIds(0).value}", public-key = "${validatorKeys(
          0,
        ).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(1).value}", public-key = "${validatorKeys(
          1,
        ).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(2).value}", public-key = "${validatorKeys(
          2,
        ).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(3).value}", public-key = "${validatorKeys(
          3,
        ).publicKey.toBytes.toHex}" }
         |  ]
         |  key-holders = [
         |    { validator-id = "${validatorIds(
          0,
        ).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(
          1,
        ).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(
          2,
        ).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(
          3,
        ).value}", holder = "node-b", status = "active" }
         |  ]
         |  local-signers = [
         |    { validator-id = "${validatorIds(
          0,
        ).value}", private-key = "${validatorKeys(0).privateKey.toHexLower}" }
         |  ]
         |}
         |""".stripMargin,
    )

    for
      clock <- TestClock.create(startedAt)
      bootstrap <- loadBootstrapFromConfig(
        config = config,
        clock = clock,
        storageLayout = freshStorageLayout,
      )
      _ <- bootstrap.consensus.bootstrap(
        chainId = chainId,
        sessions = Vector.empty,
        startedAt = startedAt,
        liveProposals = Vector.empty,
      )
      timeoutVoteAttempt <- bootstrap.consensus.signTimeoutVote(
        voter = validatorIds(1),
        window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
        highestKnownQc = bootstrapQc(),
      )
      newViewAttempt <- bootstrap.consensus.signNewView(
        sender = validatorIds(1),
        highestKnownQc = bootstrapQc(),
        timeoutCertificate = timeoutCertificateFor(
          window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
          highestKnownQc = bootstrapQc(),
        ),
      )
    yield
      assert(bootstrap.consensus.bootstrapLifecycle.nonEmpty)
      assertEquals(
        timeoutVoteAttempt.left.map(_.reason),
        Left("localValidatorKeyUnavailable"),
      )
      assertEquals(
        newViewAttempt.left.map(_.reason),
        Left("localValidatorKeyUnavailable"),
      )

  test(
    "assembled audit runtime keeps policy rejection ahead of the bootstrap vote gate",
  ):
    val config = ConfigFactory.parseString(
      s"""
         |sigilaris.node.gossip.peers {
         |  local-node-identity = "node-a"
         |  known-peers = ["node-b"]
         |  direct-neighbors = ["node-b"]
         |}
         |
         |sigilaris.node.consensus.hotstuff {
         |  local-role = "audit"
         |  validators = [
         |    { id = "${validatorIds(0).value}", public-key = "${validatorKeys(
          0,
        ).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(1).value}", public-key = "${validatorKeys(
          1,
        ).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(2).value}", public-key = "${validatorKeys(
          2,
        ).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(3).value}", public-key = "${validatorKeys(
          3,
        ).publicKey.toBytes.toHex}" }
         |  ]
         |  key-holders = [
         |    { validator-id = "${validatorIds(
          0,
        ).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(
          1,
        ).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(
          2,
        ).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(
          3,
        ).value}", holder = "node-b", status = "active" }
         |  ]
         |  local-signers = [
         |    { validator-id = "${validatorIds(
          0,
        ).value}", private-key = "${validatorKeys(0).privateKey.toHexLower}" }
         |  ]
         |}
         |""".stripMargin,
    )

    for
      clock <- TestClock.create(startedAt)
      bootstrap <- loadBootstrapFromConfig(
        config = config,
        clock = clock,
        storageLayout = freshStorageLayout,
      )
      voteAttempt <- bootstrap.consensus.emitVote(
        voter = validatorIds.head,
        proposal = signedProposal("92", 2L),
        ts = startedAt.plusSeconds(1L),
      )
    yield
      assert(bootstrap.consensus.bootstrapLifecycle.nonEmpty)
      assertEquals(voteAttempt.left.map(_.reason), Left("auditNodeCannotEmit"))

  test(
    "assembled bootstrap lifecycle keeps per-chain diagnostics when different chains bootstrap sequentially",
  ):
    val secondaryChainId = ChainId.unsafe("chain-alt")
    val config = ConfigFactory.parseString(
      s"""
         |sigilaris.node.gossip.peers {
         |  local-node-identity = "node-a"
         |  known-peers = ["node-b"]
         |  direct-neighbors = ["node-b"]
         |}
         |
         |sigilaris.node.consensus.hotstuff {
         |  local-role = "validator"
         |  validators = [
         |    { id = "${validatorIds(0).value}", public-key = "${validatorKeys(
          0,
        ).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(1).value}", public-key = "${validatorKeys(
          1,
        ).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(2).value}", public-key = "${validatorKeys(
          2,
        ).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(3).value}", public-key = "${validatorKeys(
          3,
        ).publicKey.toBytes.toHex}" }
         |  ]
         |  key-holders = [
         |    { validator-id = "${validatorIds(
          0,
        ).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(
          1,
        ).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(
          2,
        ).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(
          3,
        ).value}", holder = "node-b", status = "active" }
         |  ]
         |  local-signers = [
         |    { validator-id = "${validatorIds(
          0,
        ).value}", private-key = "${validatorKeys(0).privateKey.toHexLower}" },
         |    { validator-id = "${validatorIds(
          1,
        ).value}", private-key = "${validatorKeys(1).privateKey.toHexLower}" },
         |    { validator-id = "${validatorIds(
          2,
        ).value}", private-key = "${validatorKeys(2).privateKey.toHexLower}" }
         |  ]
         |}
         |""".stripMargin,
    )

    for
      clock <- TestClock.create(startedAt)
      bootstrap <- loadBootstrapFromConfig(
        config = config,
        clock = clock,
        storageLayout = freshStorageLayout,
      )
      first <- bootstrap.consensus.bootstrap(
        chainId = chainId,
        sessions = Vector.empty,
        startedAt = startedAt,
        liveProposals = Vector.empty,
      )
      second <- bootstrap.consensus.bootstrap(
        chainId = secondaryChainId,
        sessions = Vector.empty,
        startedAt = startedAt.plusSeconds(1L),
        liveProposals = Vector.empty,
      )
      diagnostics <- bootstrap.consensus.currentBootstrapDiagnostics
    yield
      assertEquals(
        first.left.map(_.reason),
        Left("noVerifiableFinalizedAnchor"),
      )
      assertEquals(
        second.left.map(_.reason),
        Left("noVerifiableFinalizedAnchor"),
      )
      assertEquals(diagnostics.chains.keySet, Set(chainId, secondaryChainId))

  test(
    "config loader returns both topology and consensus config errors when both sections are invalid",
  ):
    val config = ConfigFactory.parseString(
      """
        |sigilaris.node.gossip.peers {
        |  local-node-identity = "node-a"
        |}
        |
        |sigilaris.node.consensus.hotstuff {
        |  local-role = "observer"
        |}
        |""".stripMargin,
    )

    for
      clock <- TestClock.create(startedAt)
      bootstrapEither <- HotStuffRuntimeBootstrap
        .fromConfig[IO](
          withTestTransportPeerSecrets(config),
          clock,
          storageLayout = freshStorageLayout,
        )
        .use(IO.pure)
    yield assertEquals(
      bootstrapEither.left.map(error =>
        error.contains("missing required config key: known-peers") &&
          error.contains("unsupported local role: observer"),
      ),
      Left(true),
    )

  test(
    "config loader returns typed config errors as Left values instead of throwing",
  ):
    val config = ConfigFactory.parseString(
      """
        |sigilaris.node.gossip.peers {
        |  local-node-identity = "node-a"
        |  known-peers = "node-b"
        |  direct-neighbors = ["node-b"]
        |}
        |
        |sigilaris.node.consensus.hotstuff {
        |  local-role = "validator"
        |  validators = "not-a-list"
        |  key-holders = []
        |  local-signers = []
        |}
        |""".stripMargin,
    )

    for
      clock <- TestClock.create(startedAt)
      bootstrapEither <- HotStuffRuntimeBootstrap
        .fromConfig[IO](
          withTestTransportPeerSecrets(config),
          clock,
          storageLayout = freshStorageLayout,
        )
        .use(IO.pure)
    yield assertEquals(
      bootstrapEither.left.map(error =>
        error.contains("known-peers") &&
          error.contains("validators"),
      ),
      Left(true),
    )

  private final class TestClock private (ref: Ref[IO, Instant])
      extends GossipClock[IO]:
    override def now: IO[Instant] =
      ref.get

    def advance(duration: java.time.Duration): IO[Unit] =
      ref.update(_.plus(duration))

  private object TestClock:
    def create(instant: Instant): IO[TestClock] =
      Ref.of[IO, Instant](instant).map(new TestClock(_))

  private def validatorSet: ValidatorSet =
    ValidatorSet.unsafe(
      validatorKeys.zipWithIndex.map: (keyPair, index) =>
        ValidatorMember(
          id = validatorIds(index),
          publicKey = keyPair.publicKey,
        ),
    )

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

  private def timeoutVotePayload(
      event: GossipEvent[HotStuffGossipArtifact],
  ): TimeoutVote =
    event.payload match
      case HotStuffGossipArtifact.TimeoutVoteArtifact(timeoutVote) =>
        timeoutVote
      case _ =>
        throw new IllegalStateException("expected timeout vote")

  private def finalizedSuggestion(
      seed: String,
      stateRoot: StateRoot,
  ): FinalizedAnchorSuggestion =
    val anchorBlock =
      block(
        parent = Some(bootstrapQc().subject.blockId),
        height = 1L,
        stateRoot = stateRoot,
        bodyHex = seed + "10",
      )
    val anchor =
      Proposal
        .sign(
          UnsignedProposal(
            window = HotStuffWindow(chainId, 1L, 1L, validatorSet.hash),
            proposer = validatorIds(0),
            targetBlockId = BlockHeader.computeId(anchorBlock),
            block = anchorBlock,
            txSet = ProposalTxSet.empty,
            justify = bootstrapQc(),
          ),
          validatorKeys(0),
        )
        .toOption
        .get
    val childBlock =
      block(
        parent = Some(anchor.targetBlockId),
        height = 2L,
        stateRoot = stateRoot,
        bodyHex = seed + "20",
      )
    val child =
      Proposal
        .sign(
          UnsignedProposal(
            window = HotStuffWindow(chainId, 2L, 2L, validatorSet.hash),
            proposer = validatorIds(1),
            targetBlockId = BlockHeader.computeId(childBlock),
            block = childBlock,
            txSet = ProposalTxSet.empty,
            justify = qcFor(anchor),
          ),
          validatorKeys(1),
        )
        .toOption
        .get
    val grandchildBlock =
      block(
        parent = Some(child.targetBlockId),
        height = 3L,
        stateRoot = stateRoot,
        bodyHex = seed + "30",
      )
    val grandchild =
      Proposal
        .sign(
          UnsignedProposal(
            window = HotStuffWindow(chainId, 3L, 3L, validatorSet.hash),
            proposer = validatorIds(2),
            targetBlockId = BlockHeader.computeId(grandchildBlock),
            block = grandchildBlock,
            txSet = ProposalTxSet.empty,
            justify = qcFor(child),
          ),
          validatorKeys(2),
        )
        .toOption
        .get

    FinalizedAnchorSuggestion(
      proposal = anchor,
      finalizedProof = FinalizedProof(child, grandchild),
    )

  private def signedProposal(
      rootHex: String,
      height: Long,
  ): Proposal =
    Proposal
      .sign(
        UnsignedProposal(
          window = HotStuffWindow(chainId, height, 1L, validatorSet.hash),
          proposer = validatorIds.head,
          targetBlockId = BlockId(hex(rootHex)),
          block = BlockHeader(
            parent = Some(bootstrapQc().subject.blockId),
            height = BlockHeight.unsafeFromLong(height),
            stateRoot = StateRoot(hex(rootHex)),
            bodyRoot = BodyRoot(hex(rootHex)),
            timestamp =
              BlockTimestamp.unsafeFromEpochMillis(startedAt.toEpochMilli),
          ),
          txSet = ProposalTxSet.empty,
          justify = bootstrapQc(),
        ),
        validatorKeys.head,
      )
      .toOption
      .get

  private def qcFor(
      proposal: Proposal,
  ): QuorumCertificate =
    QuorumCertificateAssembler
      .assemble(
        QuorumCertificateSubject(
          window = proposal.window,
          proposalId = proposal.proposalId,
          blockId = proposal.targetBlockId,
        ),
        Vector(
          signedVoteFor(proposal.window, proposal.proposalId, 0),
          signedVoteFor(proposal.window, proposal.proposalId, 1),
          signedVoteFor(proposal.window, proposal.proposalId, 2),
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
        UnsignedVote(window, validatorIds(index), proposalId),
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
          voter = validatorIds(index),
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

  private def hex(
      value: String,
  ): UInt256 =
    UInt256.fromHex(value).toOption.get

  private def block(
      parent: Option[BlockId],
      height: Long,
      stateRoot: StateRoot,
      bodyHex: String,
  ): BlockHeader =
    BlockHeader(
      parent = parent,
      height = BlockHeight.unsafeFromLong(height),
      stateRoot = stateRoot,
      bodyRoot = BodyRoot(hex(bodyHex)),
      timestamp =
        BlockTimestamp.unsafeFromEpochMillis(startedAt.toEpochMilli + height),
    )

  private def validatorConfig(): HotStuffBootstrapConfig =
    HotStuffBootstrapConfig(
      role = LocalNodeRole.Validator,
      validatorSet = validatorSet,
      holders = validatorHolders(),
      localKeys = Map(
        validatorIds(0) -> validatorKeys(0),
        validatorIds(1) -> validatorKeys(1),
        validatorIds(2) -> validatorKeys(2),
      ),
    )

  private def validatorHolders(): Vector[ValidatorKeyHolder] =
    Vector(
      ValidatorKeyHolder(
        validatorIds(0),
        PeerIdentity.unsafe("node-a"),
        ValidatorKeyHolderStatus.Active,
      ),
      ValidatorKeyHolder(
        validatorIds(1),
        PeerIdentity.unsafe("node-a"),
        ValidatorKeyHolderStatus.Active,
      ),
      ValidatorKeyHolder(
        validatorIds(2),
        PeerIdentity.unsafe("node-a"),
        ValidatorKeyHolderStatus.Active,
      ),
      ValidatorKeyHolder(
        validatorIds(3),
        PeerIdentity.unsafe("node-b"),
        ValidatorKeyHolderStatus.Active,
      ),
    )

  private def topology(
      localPeer: String,
      peers: Vector[String],
  ): StaticPeerTopology =
    StaticPeerTopology
      .parse(
        localNodeIdentity = localPeer,
        knownPeers = peers.toList,
        directNeighbors = peers.toList,
      )
      .toOption
      .get

  private def tempDirResource: Resource[IO, Path] =
    Resource.make(
      IO.blocking(Files.createTempDirectory("sigilaris-runtime-bootstrap")),
    ) { dir =>
      IO.blocking(deleteRecursively(dir))
    }

  private def freshStorageLayout: StorageLayout =
    val root = Files.createTempDirectory("sigilaris-runtime-bootstrap-storage")
    tempStorageRoots.add(root)
    StorageLayout.fromRoot(root)

  private def deleteRecursively(
      path: Path,
  ): Unit =
    if Files.exists(path) then
      Using.resource(Files.walk(path)): stream =>
        stream.iterator.asScala.toList.reverse.foreach(Files.deleteIfExists)

  private def allocateBootstrap(
      resource: Resource[IO, Either[String, HotStuffRuntimeBootstrap[IO]]],
  ): IO[HotStuffRuntimeBootstrap[IO]] =
    resource.allocated.flatMap:
      case (Left(error), release) =>
        release *> IO.raiseError(new IllegalArgumentException(error))
      case (Right(bootstrap), release) =>
        IO.delay(openedBootstrapReleases.add(release)).as(bootstrap)

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  private def loadBootstrapFromConfig(
      config: com.typesafe.config.Config,
      clock: GossipClock[IO],
      storageLayout: StorageLayout,
  ): IO[HotStuffRuntimeBootstrap[IO]] =
    allocateBootstrap(
      HotStuffRuntimeBootstrap.fromConfig[IO](
        config = withTestTransportPeerSecrets(config),
        clock = clock,
        storageLayout = storageLayout,
      ),
    )

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  private def loadBootstrapFromTopology(
      topology: StaticPeerTopology,
      consensusConfig: HotStuffBootstrapConfig,
      clock: GossipClock[IO],
      bootstrapTransport: Option[HotStuffBootstrapTransportServices[IO]],
      storageLayout: StorageLayout,
  ): IO[HotStuffRuntimeBootstrap[IO]] =
    allocateBootstrap(
      HotStuffRuntimeBootstrap.fromTopology[IO](
        topology = topology,
        transportAuth = StaticPeerTransportAuth.testing(topology),
        consensusConfig = consensusConfig,
        clock = clock,
        bootstrapTransport = bootstrapTransport,
        storageLayout = storageLayout,
      ),
    )

  private def bootstrapSession(
      peer: String,
      sessionId: String,
  ): BootstrapSessionBinding =
    BootstrapSessionBinding(
      peer = PeerIdentity.unsafe(peer),
      sessionId = DirectionalSessionId.parse(sessionId).toOption.get,
    )

  private def proposalTransport(
      anchor: FinalizedAnchorSuggestion,
      replayed: Vector[Proposal],
      snapshotRoot: (MerkleTrieNode.MerkleHash, MerkleTrieNode),
      proposalCatchUpReadiness: Option[ProposalCatchUpReadiness[IO]],
  ): HotStuffBootstrapTransportServices[IO] =
    val (rootHash, root) = snapshotRoot
    HotStuffBootstrapTransportServices[IO](
      finalizedAnchorSuggestions = new FinalizedAnchorSuggestionService[IO]:
        override def bestFinalized(
            session: BootstrapSessionBinding,
            chainId: ChainId,
        ): IO[Either[CanonicalRejection, Option[FinalizedAnchorSuggestion]]] =
          IO.pure(Right(Some(anchor)))
      ,
      snapshotNodeFetch = new SnapshotNodeFetchService[IO]:
        override def fetchNodes(
            session: BootstrapSessionBinding,
            chainId: ChainId,
            stateRoot: StateRoot,
            hashes: Vector[MerkleTrieNode.MerkleHash],
        ): IO[Either[CanonicalRejection, Vector[SnapshotTrieNode]]] =
          IO.pure(
            Right(
              hashes
                .filter(_ === rootHash)
                .map(_ => SnapshotTrieNode(rootHash, root)),
            ),
          )
      ,
      proposalReplay = new ProposalReplayService[IO]:
        override def readNext(
            session: BootstrapSessionBinding,
            chainId: ChainId,
            anchorBlockId: BlockId,
            nextHeight: BlockHeight,
            limit: Int,
        ): IO[Either[CanonicalRejection, Vector[Proposal]]] =
          IO.pure(
            Right(
              replayed
                .filter(proposal =>
                  Ordering[BlockHeight].gteq(
                    proposal.block.height,
                    nextHeight,
                  ),
                )
                .sortBy(proposal =>
                  (proposal.block.height, proposal.proposalId.toHexLower),
                )
                .take(limit.max(0)),
            ),
          )
      ,
      historicalBackfill = new HistoricalBackfillService[IO]:
        override def readPrevious(
            session: BootstrapSessionBinding,
            chainId: ChainId,
            beforeBlockId: BlockId,
            beforeHeight: BlockHeight,
            limit: Int,
        ): IO[Either[CanonicalRejection, Vector[Proposal]]] =
          IO.pure(Right(Vector.empty))
      ,
      proposalCatchUpReadiness = proposalCatchUpReadiness,
    )

  private def replayableSuggestion(
      seed: String,
      stateRoot: StateRoot,
      childTxs: Vector[TestTx],
      grandchildTxs: Vector[TestTx],
  ): FinalizedAnchorSuggestion =
    val anchor =
      signedReplayProposal(
        parent = Some(bootstrapQc().subject.blockId),
        height = 1L,
        proposerIndex = 0,
        justify = bootstrapQc(),
        stateRoot = stateRoot,
        txs = Vector.empty,
        bodyHexFallback = seed + "10",
      )
    val child =
      signedReplayProposal(
        parent = Some(anchor.targetBlockId),
        height = 2L,
        proposerIndex = 1,
        justify = qcFor(anchor),
        stateRoot = stateRoot,
        txs = childTxs,
        bodyHexFallback = seed + "20",
      )
    val grandchild =
      signedReplayProposal(
        parent = Some(child.targetBlockId),
        height = 3L,
        proposerIndex = 2,
        justify = qcFor(child),
        stateRoot = stateRoot,
        txs = grandchildTxs,
        bodyHexFallback = seed + "30",
      )
    FinalizedAnchorSuggestion(
      proposal = anchor,
      finalizedProof = FinalizedProof(child, grandchild),
    )

  private def applicationNeutralReplayableSuggestion(
      seed: String,
      stateRoot: StateRoot,
      childTxSeeds: Vector[String],
      grandchildTxSeeds: Vector[String],
  ): FinalizedAnchorSuggestion =
    val anchor =
      signedApplicationNeutralReplayProposal(
        parent = Some(bootstrapQc().subject.blockId),
        height = 1L,
        proposerIndex = 0,
        justify = bootstrapQc(),
        stateRoot = stateRoot,
        txSeeds = Vector.empty,
      )
    val child =
      signedApplicationNeutralReplayProposal(
        parent = Some(anchor.targetBlockId),
        height = 2L,
        proposerIndex = 1,
        justify = qcFor(anchor),
        stateRoot = stateRoot,
        txSeeds = childTxSeeds.map(seed + "-" + _),
      )
    val grandchild =
      signedApplicationNeutralReplayProposal(
        parent = Some(child.targetBlockId),
        height = 3L,
        proposerIndex = 2,
        justify = qcFor(child),
        stateRoot = stateRoot,
        txSeeds = grandchildTxSeeds.map(seed + "-" + _),
      )
    FinalizedAnchorSuggestion(
      proposal = anchor,
      finalizedProof = FinalizedProof(child, grandchild),
    )

  private def legacyAutomaticReplayableSuggestion(
      stateRoot: StateRoot,
  ): FinalizedAnchorSuggestion =
    val anchor =
      signedApplicationNeutralReplayProposal(
        parent = Some(bootstrapQc().subject.blockId),
        height = 1L,
        proposerIndex = 0,
        justify = bootstrapQc(),
        stateRoot = stateRoot,
        txSeeds = Vector.empty,
      )
    val child =
      signedLegacyAutomaticReplayProposal(
        parent = Some(anchor.targetBlockId),
        height = 2L,
        proposerIndex = 1,
        justify = qcFor(anchor),
        stateRoot = stateRoot,
      )
    val grandchild =
      signedLegacyAutomaticReplayProposal(
        parent = Some(child.targetBlockId),
        height = 3L,
        proposerIndex = 2,
        justify = qcFor(child),
        stateRoot = stateRoot,
      )
    FinalizedAnchorSuggestion(
      proposal = anchor,
      finalizedProof = FinalizedProof(child, grandchild),
    )

  private def signedReplayProposal(
      parent: Option[BlockId],
      height: Long,
      proposerIndex: Int,
      justify: QuorumCertificate,
      stateRoot: StateRoot,
      txs: Vector[TestTx],
      bodyHexFallback: String,
  ): Proposal =
    val header =
      txs match
        case Vector() =>
          block(
            parent = parent,
            height = height,
            stateRoot = stateRoot,
            bodyHex = bodyHexFallback,
          )
        case _ =>
          val body     = blockBodyOf(txs)
          val bodyRoot = BlockBody.computeBodyRoot(body).toOption.get
          BlockHeader(
            parent = parent,
            height = BlockHeight.unsafeFromLong(height),
            stateRoot = stateRoot,
            bodyRoot = bodyRoot,
            timestamp = BlockTimestamp.unsafeFromEpochMillis(
              startedAt.toEpochMilli + height,
            ),
          )
    Proposal
      .sign(
        UnsignedProposal(
          window = HotStuffWindow(chainId, height, height, validatorSet.hash),
          proposer = validatorIds(proposerIndex),
          targetBlockId = BlockHeader.computeId(header),
          block = header,
          txSet = ProposalTxSet.fromTxs(txs),
          justify = justify,
        ),
        validatorKeys(proposerIndex),
      )
      .toOption
      .get

  private def putProposalView(
      blockStore: BlockStore[IO, TestTx, Utf8, Utf8],
      proposal: Proposal,
      txs: Vector[TestTx],
  ): IO[Unit] =
    blockStore
      .putView(
        BlockView(
          header = proposal.block,
          body = blockBodyOf(txs),
        ),
      )
      .value
      .flatMap:
        case Left(error) =>
          IO.raiseError(new IllegalStateException(error.reason))
        case Right(_) => IO.unit

  private def blockBodyOf(
      txs: Vector[TestTx],
  ): BlockBody[TestTx, Utf8, Utf8] =
    BlockBody(
      txs
        .map(tx =>
          BlockRecord[TestTx, Utf8, Utf8](
            tx = tx,
            result = Option.empty[Utf8],
            events = Vector.empty[Utf8],
          ),
        )
        .toSet,
    )

  private def signedApplicationNeutralReplayProposal(
      parent: Option[BlockId],
      height: Long,
      proposerIndex: Int,
      justify: QuorumCertificate,
      stateRoot: StateRoot,
      txSeeds: Vector[String],
  ): Proposal =
    val txIds = txSeeds.map(applicationNeutralTxId)
    val header =
      BlockHeader(
        parent = parent,
        height = BlockHeight.unsafeFromLong(height),
        stateRoot = stateRoot,
        bodyRoot = ApplicationNeutralProposalView
          .bodyRoot(txIds)
          .toOption
          .get,
        timestamp = BlockTimestamp.unsafeFromEpochMillis(
          startedAt.toEpochMilli + height,
        ),
      )
    Proposal
      .sign(
        UnsignedProposal(
          window = HotStuffWindow(chainId, height, height, validatorSet.hash),
          proposer = validatorIds(proposerIndex),
          targetBlockId = BlockHeader.computeId(header),
          block = header,
          txSet = ApplicationNeutralProposalView.proposalTxSet(txIds),
          justify = justify,
        ),
        validatorKeys(proposerIndex),
      )
      .toOption
      .get

  private def signedLegacyAutomaticReplayProposal(
      parent: Option[BlockId],
      height: Long,
      proposerIndex: Int,
      justify: QuorumCertificate,
      stateRoot: StateRoot,
  ): Proposal =
    val window =
      HotStuffWindow(chainId, height, height, validatorSet.hash)
    val proposer = validatorIds(proposerIndex)
    val header =
      BlockHeader(
        parent = parent,
        height = BlockHeight.unsafeFromLong(height),
        stateRoot = stateRoot,
        bodyRoot = ApplicationNeutralProposalView.legacyAutomaticBodyRoot(
          window = window,
          proposer = proposer,
          justify = justify,
        ),
        timestamp = BlockTimestamp.unsafeFromEpochMillis(
          startedAt.toEpochMilli + height,
        ),
      )
    Proposal
      .sign(
        UnsignedProposal(
          window = window,
          proposer = proposer,
          targetBlockId = BlockHeader.computeId(header),
          block = header,
          txSet = ProposalTxSet.empty,
          justify = justify,
        ),
        validatorKeys(proposerIndex),
      )
      .toOption
      .get

  private def applicationNeutralTxId(
      seed: String,
  ): StableArtifactId =
    ApplicationNeutralProposalView
      .txIdFromBytes(Utf8(seed).toHash.toUInt256.bytes)
      .toOption
      .get

  private def schedulable(): SchedulingClassification =
    SchedulingClassification.Schedulable(ConflictFootprint.empty)

  private def validatorSetEntries(
      validatorSet: ValidatorSet,
      indent: String,
  ): String =
    validatorSet.members
      .map(member =>
        s"""${indent}{ id = "${member.id.value}", public-key = "${member.publicKey.toBytes.toHex}" }""",
      )
      .mkString(",\n")

  private def keyHolderEntries(
      validatorIds: Vector[ValidatorId],
      indent: String,
  ): String =
    validatorIds.zipWithIndex
      .map: (validatorId, index) =>
        val holder =
          if index < 3 then "node-a"
          else "node-b"
        s"""${indent}{ validator-id = "${validatorId.value}", holder = "${holder}", status = "active" }"""
      .mkString(",\n")

  private def localSignerEntries(
      validatorIds: Vector[ValidatorId],
      keys: Vector[KeyPair],
      indent: String,
  ): String =
    validatorIds
      .zip(keys)
      .map: (validatorId, keyPair) =>
        s"""${indent}{ validator-id = "${validatorId.value}", private-key = "${keyPair.privateKey.toHexLower}" }"""
      .mkString(",\n")
  override def afterAll(): Unit =
    openedBootstrapReleases.iterator.asScala.foreach: release =>
      val _ = release.attempt.unsafeRunSync()
    val _ = HistoricalProposalArchive.resetSharedStoresForTesting.attempt
      .unsafeRunSync()
    tempStorageRoots.iterator.asScala.foreach(deleteRecursively)
    super.afterAll()
