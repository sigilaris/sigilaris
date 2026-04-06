package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Instant

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import munit.CatsEffectSuite
import scodec.bits.ByteVector

import com.typesafe.config.ConfigFactory

import org.sigilaris.core.crypto.{CryptoOps, KeyPair}
import org.sigilaris.core.crypto.Hash.ops.*
import org.sigilaris.core.datatype.UInt256
import org.sigilaris.core.merkle.MerkleTrieNode
import org.sigilaris.core.merkle.Nibbles.*
import org.sigilaris.node.jvm.runtime.block.{
  BlockHeader,
  BlockHeight,
  BlockId,
  BlockTimestamp,
  BodyRoot,
  StateRoot,
}
import org.sigilaris.node.jvm.runtime.gossip.*

final class HotStuffRuntimeBootstrapSuite extends CatsEffectSuite:

  private val chainId       = ChainId.unsafe("chain-main")
  private val startedAt     = Instant.parse("2026-04-02T00:00:00Z")
  private val validatorKeys = Vector.fill(4)(CryptoOps.generate())
  private val validatorIds =
    validatorKeys.indices.toVector.map(index =>
      ValidatorId.unsafe(s"validator-${index + 1}"),
    )
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
         |  }
         |}
         |""".stripMargin,
    )

    for
      clock           <- TestClock.create(startedAt)
      bootstrapEither <- HotStuffRuntimeBootstrap.fromConfig[IO](config, clock)
      bootstrap <- IO.fromEither(
        bootstrapEither.leftMap(new IllegalArgumentException(_)),
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
      assertEquals(bootstrap.consensus.gossipPolicy.proposal.maxBatchItems, 7)
      assertEquals(bootstrap.consensus.gossipPolicy.vote.deliveryPriority, 5)
      assertEquals(proposalContract.exactKnownSetLimit, Some(300))
      assertEquals(proposalContract.requestByIdLimit, Some(96))
      assertEquals(proposalContract.deliveryPriority, 4)
      assertEquals(voteContract.exactKnownSetLimit, Some(4000))
      assertEquals(voteContract.requestByIdLimit, Some(321))
      assertEquals(voteContract.deliveryPriority, 5)
      assertEquals(
        outboundAllowed.map(_.acceptor),
        Right(PeerIdentity.unsafe("node-b")),
      )
      assertEquals(outboundRejected.left.map(_.reason), Left("nonNeighborPeer"))

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
      clock           <- TestClock.create(startedAt)
      bootstrapEither <- HotStuffRuntimeBootstrap.fromConfig[IO](config, clock)
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
      clock           <- TestClock.create(startedAt)
      bootstrapEither <- HotStuffRuntimeBootstrap.fromConfig[IO](config, clock)
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
         |    { id = "${validatorIds(0).value}", public-key = "${validatorKeys(0).publicKey.toBytes.toHex}" }
         |  ]
         |  key-holders = [
         |    { validator-id = "${validatorIds(0).value}", holder = "node-a", status = "active" }
         |  ]
         |  local-signers = [
         |    { validator-id = "${validatorIds(0).value}", private-key = "${validatorKeys(0).privateKey.toHexLower}" }
         |  ]
         |}
         |""".stripMargin,
    )

    val loaded = HotStuffBootstrapConfig.load(config)

    assertEquals(loaded.map(_.historicalSyncEnabled), Right(false))

  test("assembled runtime reports disabled historical sync and skips backfill transport when opted out"):
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
    val anchor = finalizedSuggestion("c1", StateRoot(rootHash.toUInt256))
    val session =
      BootstrapSessionBinding(
        peer = PeerIdentity.unsafe("node-b"),
        sessionId =
          DirectionalSessionId
            .parse("cccccccc-cccc-4ccc-8ccc-cccccccccccc")
            .toOption
            .get,
      )
    val holders = Vector(
      ValidatorKeyHolder(validatorIds(0), PeerIdentity.unsafe("node-a"), ValidatorKeyHolderStatus.Active),
      ValidatorKeyHolder(validatorIds(1), PeerIdentity.unsafe("node-a"), ValidatorKeyHolderStatus.Active),
      ValidatorKeyHolder(validatorIds(2), PeerIdentity.unsafe("node-a"), ValidatorKeyHolderStatus.Active),
      ValidatorKeyHolder(validatorIds(3), PeerIdentity.unsafe("node-b"), ValidatorKeyHolderStatus.Active),
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
      clock <- TestClock.create(startedAt)
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
            backfillCalls.update(_ + 1) *> IO.pure(Right(Vector.empty))
        ,
      )
      bootstrapEither <- HotStuffRuntimeBootstrap.fromTopology[IO](
        topology = topology,
        consensusConfig = config,
        clock = clock,
        bootstrapTransport = transport.some,
      )
      bootstrap <- IO.fromEither(
        bootstrapEither.leftMap(new IllegalArgumentException(_)),
      )
      result <- bootstrap.consensus.bootstrap(
        chainId = chainId,
        sessions = Vector(session),
        startedAt = startedAt,
        liveProposals = Vector.empty,
      )
      diagnostics <- bootstrap.consensus.currentBootstrapDiagnostics
      calls <- backfillCalls.get
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
         |${localSignerEntries(validatorIds.take(3), validatorKeys.take(3), indent = "    ")}
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
         |${localSignerEntries(validatorIds.take(3), validatorKeys.take(3), indent = "    ")}
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
      clock           <- TestClock.create(startedAt)
      bootstrapEither <- HotStuffRuntimeBootstrap.fromConfig[IO](config, clock)
      bootstrap <- IO.fromEither(
        bootstrapEither.leftMap(new IllegalArgumentException(_)),
      )
      rootLookup <- bootstrap.consensus.bootstrapServices.validatorSetLookup
        .validatorSetFor(HotStuffWindow(chainId, 9L, 4L, historicalValidatorSet.hash))
      historicalLookup <- bootstrap.consensus.bootstrapServices.validatorSetLookup
        .validatorSetFor(HotStuffWindow(chainId, 6L, 2L, inventoryValidatorSet.hash))
      currentLookup <- bootstrap.consensus.bootstrapServices.validatorSetLookup
        .validatorSetFor(HotStuffWindow(chainId, 12L, 5L, validatorSet.hash))
    yield
      assert(
        bootstrap.consensus.bootstrapTrustRoot.isInstanceOf[BootstrapTrustRoot.TrustedCheckpoint],
      )
      assertEquals(
        bootstrap.consensus.bootstrapTrustRoot.validatorSetHash,
        historicalValidatorSet.hash,
      )
      assertEquals(rootLookup.map(_.hash), Right(historicalValidatorSet.hash))
      assertEquals(historicalLookup.map(_.hash), Right(inventoryValidatorSet.hash))
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
         |${localSignerEntries(validatorIds.take(3), validatorKeys.take(3), indent = "    ")}
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
      clock           <- TestClock.create(startedAt)
      bootstrapEither <- HotStuffRuntimeBootstrap.fromConfig[IO](config, clock)
    yield
      assertEquals(
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
      clock           <- TestClock.create(startedAt)
      bootstrapEither <- HotStuffRuntimeBootstrap.fromConfig[IO](config, clock)
      bootstrap <- IO.fromEither(
        bootstrapEither.leftMap(new IllegalArgumentException(_)),
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

  test("assembled bootstrap runtime gates proposal emission and pacemaker artifacts once bootstrap hold becomes active"):
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
         |    { id = "${validatorIds(0).value}", public-key = "${validatorKeys(0).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(1).value}", public-key = "${validatorKeys(1).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(2).value}", public-key = "${validatorKeys(2).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(3).value}", public-key = "${validatorKeys(3).publicKey.toBytes.toHex}" }
         |  ]
         |  key-holders = [
         |    { validator-id = "${validatorIds(0).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(1).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(2).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(3).value}", holder = "node-b", status = "active" }
         |  ]
         |  local-signers = [
         |    { validator-id = "${validatorIds(0).value}", private-key = "${validatorKeys(0).privateKey.toHexLower}" },
         |    { validator-id = "${validatorIds(1).value}", private-key = "${validatorKeys(1).privateKey.toHexLower}" },
         |    { validator-id = "${validatorIds(2).value}", private-key = "${validatorKeys(2).privateKey.toHexLower}" }
         |  ]
         |}
         |""".stripMargin,
    )

    for
      clock <- TestClock.create(startedAt)
      bootstrapEither <- HotStuffRuntimeBootstrap.fromConfig[IO](config, clock)
      bootstrap <- IO.fromEither(
        bootstrapEither.leftMap(new IllegalArgumentException(_)),
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
          timestamp = BlockTimestamp.unsafeFromEpochMillis(startedAt.toEpochMilli),
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
      assertEquals(proposalAttempt.left.map(_.reason), Left("bootstrapVoteHeld"))
      assertEquals(timeoutVoteAttempt.left.map(_.reason), Left("bootstrapVoteHeld"))
      assertEquals(newViewAttempt.left.map(_.reason), Left("bootstrapVoteHeld"))

  test("assembled validator runtime does not gate votes before bootstrap activation starts"):
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
         |    { id = "${validatorIds(0).value}", public-key = "${validatorKeys(0).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(1).value}", public-key = "${validatorKeys(1).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(2).value}", public-key = "${validatorKeys(2).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(3).value}", public-key = "${validatorKeys(3).publicKey.toBytes.toHex}" }
         |  ]
         |  key-holders = [
         |    { validator-id = "${validatorIds(0).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(1).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(2).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(3).value}", holder = "node-b", status = "active" }
         |  ]
         |  local-signers = [
         |    { validator-id = "${validatorIds(0).value}", private-key = "${validatorKeys(0).privateKey.toHexLower}" },
         |    { validator-id = "${validatorIds(1).value}", private-key = "${validatorKeys(1).privateKey.toHexLower}" },
         |    { validator-id = "${validatorIds(2).value}", private-key = "${validatorKeys(2).privateKey.toHexLower}" }
         |  ]
         |}
         |""".stripMargin,
    )

    for
      clock <- TestClock.create(startedAt)
      bootstrapEither <- HotStuffRuntimeBootstrap.fromConfig[IO](config, clock)
      bootstrap <- IO.fromEither(bootstrapEither.leftMap(new IllegalArgumentException(_)))
      voteAttempt <- bootstrap.consensus.emitVote(
        voter = validatorIds.head,
        proposal = signedProposal("93", 2L),
        ts = startedAt.plusSeconds(1L),
      )
    yield
      assert(bootstrap.consensus.bootstrapLifecycle.nonEmpty)
      assert(voteAttempt.isRight)

  test("assembled validator runtime can sign pacemaker artifacts before bootstrap activation starts"):
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
         |    { id = "${validatorIds(0).value}", public-key = "${validatorKeys(0).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(1).value}", public-key = "${validatorKeys(1).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(2).value}", public-key = "${validatorKeys(2).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(3).value}", public-key = "${validatorKeys(3).publicKey.toBytes.toHex}" }
         |  ]
         |  key-holders = [
         |    { validator-id = "${validatorIds(0).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(1).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(2).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(3).value}", holder = "node-b", status = "active" }
         |  ]
         |  local-signers = [
         |    { validator-id = "${validatorIds(0).value}", private-key = "${validatorKeys(0).privateKey.toHexLower}" },
         |    { validator-id = "${validatorIds(1).value}", private-key = "${validatorKeys(1).privateKey.toHexLower}" },
         |    { validator-id = "${validatorIds(2).value}", private-key = "${validatorKeys(2).privateKey.toHexLower}" }
         |  ]
         |}
         |""".stripMargin,
    )

    for
      clock <- TestClock.create(startedAt)
      bootstrapEither <- HotStuffRuntimeBootstrap.fromConfig[IO](config, clock)
      bootstrap <- IO.fromEither(
        bootstrapEither.leftMap(new IllegalArgumentException(_)),
      )
      timeoutVoteAttempt <- bootstrap.consensus.signTimeoutVote(
        voter = validatorIds.head,
        window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
        highestKnownQc = bootstrapQc(),
      )
      timeoutVote <- IO.fromEither(
        timeoutVoteAttempt.leftMap(rejection => new IllegalStateException(rejection.reason)),
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
        newViewAttempt.leftMap(rejection => new IllegalStateException(rejection.reason)),
      )
    yield
      assert(bootstrap.consensus.bootstrapLifecycle.nonEmpty)
      assertEquals(HotStuffValidator.validateTimeoutVote(timeoutVote, validatorSet), Right(()))
      assertEquals(HotStuffValidator.validateNewView(newView, validatorSet), Right(()))

  test("assembled validator runtime keeps signer failures ahead of the bootstrap vote gate"):
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
         |    { id = "${validatorIds(0).value}", public-key = "${validatorKeys(0).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(1).value}", public-key = "${validatorKeys(1).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(2).value}", public-key = "${validatorKeys(2).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(3).value}", public-key = "${validatorKeys(3).publicKey.toBytes.toHex}" }
         |  ]
         |  key-holders = [
         |    { validator-id = "${validatorIds(0).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(1).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(2).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(3).value}", holder = "node-b", status = "active" }
         |  ]
         |  local-signers = [
         |    { validator-id = "${validatorIds(0).value}", private-key = "${validatorKeys(0).privateKey.toHexLower}" }
         |  ]
         |}
         |""".stripMargin,
    )

    for
      clock <- TestClock.create(startedAt)
      bootstrapEither <- HotStuffRuntimeBootstrap.fromConfig[IO](config, clock)
      bootstrap <- IO.fromEither(bootstrapEither.leftMap(new IllegalArgumentException(_)))
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

  test("assembled validator runtime keeps pacemaker signer failures ahead of the bootstrap gate"):
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
         |    { id = "${validatorIds(0).value}", public-key = "${validatorKeys(0).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(1).value}", public-key = "${validatorKeys(1).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(2).value}", public-key = "${validatorKeys(2).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(3).value}", public-key = "${validatorKeys(3).publicKey.toBytes.toHex}" }
         |  ]
         |  key-holders = [
         |    { validator-id = "${validatorIds(0).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(1).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(2).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(3).value}", holder = "node-b", status = "active" }
         |  ]
         |  local-signers = [
         |    { validator-id = "${validatorIds(0).value}", private-key = "${validatorKeys(0).privateKey.toHexLower}" }
         |  ]
         |}
         |""".stripMargin,
    )

    for
      clock <- TestClock.create(startedAt)
      bootstrapEither <- HotStuffRuntimeBootstrap.fromConfig[IO](config, clock)
      bootstrap <- IO.fromEither(bootstrapEither.leftMap(new IllegalArgumentException(_)))
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

  test("assembled audit runtime keeps policy rejection ahead of the bootstrap vote gate"):
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
         |    { id = "${validatorIds(0).value}", public-key = "${validatorKeys(0).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(1).value}", public-key = "${validatorKeys(1).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(2).value}", public-key = "${validatorKeys(2).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(3).value}", public-key = "${validatorKeys(3).publicKey.toBytes.toHex}" }
         |  ]
         |  key-holders = [
         |    { validator-id = "${validatorIds(0).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(1).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(2).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(3).value}", holder = "node-b", status = "active" }
         |  ]
         |  local-signers = [
         |    { validator-id = "${validatorIds(0).value}", private-key = "${validatorKeys(0).privateKey.toHexLower}" }
         |  ]
         |}
         |""".stripMargin,
    )

    for
      clock <- TestClock.create(startedAt)
      bootstrapEither <- HotStuffRuntimeBootstrap.fromConfig[IO](config, clock)
      bootstrap <- IO.fromEither(bootstrapEither.leftMap(new IllegalArgumentException(_)))
      voteAttempt <- bootstrap.consensus.emitVote(
        voter = validatorIds.head,
        proposal = signedProposal("92", 2L),
        ts = startedAt.plusSeconds(1L),
      )
    yield
      assert(bootstrap.consensus.bootstrapLifecycle.nonEmpty)
      assertEquals(voteAttempt.left.map(_.reason), Left("auditNodeCannotEmit"))

  test("assembled bootstrap lifecycle keeps per-chain diagnostics when different chains bootstrap sequentially"):
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
         |    { id = "${validatorIds(0).value}", public-key = "${validatorKeys(0).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(1).value}", public-key = "${validatorKeys(1).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(2).value}", public-key = "${validatorKeys(2).publicKey.toBytes.toHex}" },
         |    { id = "${validatorIds(3).value}", public-key = "${validatorKeys(3).publicKey.toBytes.toHex}" }
         |  ]
         |  key-holders = [
         |    { validator-id = "${validatorIds(0).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(1).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(2).value}", holder = "node-a", status = "active" },
         |    { validator-id = "${validatorIds(3).value}", holder = "node-b", status = "active" }
         |  ]
         |  local-signers = [
         |    { validator-id = "${validatorIds(0).value}", private-key = "${validatorKeys(0).privateKey.toHexLower}" },
         |    { validator-id = "${validatorIds(1).value}", private-key = "${validatorKeys(1).privateKey.toHexLower}" },
         |    { validator-id = "${validatorIds(2).value}", private-key = "${validatorKeys(2).privateKey.toHexLower}" }
         |  ]
         |}
         |""".stripMargin,
    )

    for
      clock <- TestClock.create(startedAt)
      bootstrapEither <- HotStuffRuntimeBootstrap.fromConfig[IO](config, clock)
      bootstrap <- IO.fromEither(bootstrapEither.leftMap(new IllegalArgumentException(_)))
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
      assertEquals(first.left.map(_.reason), Left("noVerifiableFinalizedAnchor"))
      assertEquals(second.left.map(_.reason), Left("noVerifiableFinalizedAnchor"))
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
      clock           <- TestClock.create(startedAt)
      bootstrapEither <- HotStuffRuntimeBootstrap.fromConfig[IO](config, clock)
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
      clock           <- TestClock.create(startedAt)
      bootstrapEither <- HotStuffRuntimeBootstrap.fromConfig[IO](config, clock)
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
    validatorIds.zip(keys)
      .map: (validatorId, keyPair) =>
        s"""${indent}{ validator-id = "${validatorId.value}", private-key = "${keyPair.privateKey.toHexLower}" }"""
      .mkString(",\n")
