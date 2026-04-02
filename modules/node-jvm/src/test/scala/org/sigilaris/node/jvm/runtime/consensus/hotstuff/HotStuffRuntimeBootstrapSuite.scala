package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Instant

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import munit.CatsEffectSuite

import com.typesafe.config.ConfigFactory

import org.sigilaris.core.crypto.CryptoOps
import org.sigilaris.node.jvm.runtime.gossip.*

final class HotStuffRuntimeBootstrapSuite extends CatsEffectSuite:

  private val chainId = ChainId.unsafe("chain-main")
  private val startedAt = Instant.parse("2026-04-02T00:00:00Z")
  private val validatorKeys = Vector.fill(4)(CryptoOps.generate())
  private val validatorIds =
    validatorKeys.indices.toVector.map(index => ValidatorId.unsafe(s"validator-${index + 1}"))
  private val subscription = SessionSubscription.unsafe(
    ChainTopic(chainId, GossipTopic.consensusProposal),
    ChainTopic(chainId, GossipTopic.consensusVote),
  )

  test("config loader builds HotStuff bootstrap and wires runtime services into the gossip graph"):
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
         |""".stripMargin
    )

    for
      clock <- TestClock.create(startedAt)
      bootstrapEither <- HotStuffRuntimeBootstrap.fromConfig[IO](config, clock)
      bootstrap <- IO.fromEither(bootstrapEither.leftMap(new IllegalArgumentException(_)))
      outboundAllowed <- bootstrap.runtime.startOutbound(PeerIdentity.unsafe("node-b"), subscription)
      outboundRejected <- bootstrap.runtime.startOutbound(PeerIdentity.unsafe("node-c"), subscription)
    yield
      assertEquals(bootstrap.topology.localNodeIdentity, PeerIdentity.unsafe("node-a"))
      assertEquals(bootstrap.registry.directNeighbors, Set(PeerIdentity.unsafe("node-b")))
      assertEquals(bootstrap.consensus.localPeer, PeerIdentity.unsafe("node-a"))
      assertEquals(bootstrap.consensus.role, LocalNodeRole.Validator)
      assertEquals(bootstrap.consensus.validatorSet.members.map(_.id), validatorIds)
      assertEquals(bootstrap.consensus.holders.map(_.holder).toSet, Set(PeerIdentity.unsafe("node-a"), PeerIdentity.unsafe("node-b")))
      assertEquals(bootstrap.consensus.localKeys.keySet, validatorIds.take(3).toSet)
      val proposalContract = bootstrap.consensus.topicContracts.contractFor(GossipTopic.consensusProposal).toOption.get
      val voteContract = bootstrap.consensus.topicContracts.contractFor(GossipTopic.consensusVote).toOption.get
      assertEquals(bootstrap.consensus.gossipPolicy.proposal.maxBatchItems, 7)
      assertEquals(bootstrap.consensus.gossipPolicy.vote.deliveryPriority, 5)
      assertEquals(proposalContract.exactKnownSetLimit, Some(300))
      assertEquals(proposalContract.requestByIdLimit, Some(96))
      assertEquals(proposalContract.deliveryPriority, 4)
      assertEquals(voteContract.exactKnownSetLimit, Some(4000))
      assertEquals(voteContract.requestByIdLimit, Some(321))
      assertEquals(voteContract.deliveryPriority, 5)
      assertEquals(outboundAllowed.map(_.acceptor), Right(PeerIdentity.unsafe("node-b")))
      assertEquals(outboundRejected.left.map(_.reason), Left("nonNeighborPeer"))

  test("config loader reports signer references that are outside the validator set"):
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
         |    { id = "${validatorIds(0).value}", public-key = "${validatorKeys(0).publicKey.toBytes.toHex}" }
         |  ]
         |  key-holders = [
         |    { validator-id = "${validatorIds(0).value}", holder = "node-a", status = "active" }
         |  ]
         |  local-signers = [
         |    { validator-id = "${validatorIds(1).value}", private-key = "${validatorKeys(1).privateKey.toHexLower}" }
         |  ]
         |}
         |""".stripMargin
    )

    for
      clock <- TestClock.create(startedAt)
      bootstrapEither <- HotStuffRuntimeBootstrap.fromConfig[IO](config, clock)
    yield
      assertEquals(
        bootstrapEither.left.map(_.startsWith("unknown validator signer:")),
        Left(true),
      )

  test("config loader reports holder references that are outside the validator set"):
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
         |    { id = "${validatorIds(0).value}", public-key = "${validatorKeys(0).publicKey.toBytes.toHex}" }
         |  ]
         |  key-holders = [
         |    { validator-id = "validator-x", holder = "node-a", status = "active" }
         |  ]
         |  local-signers = []
         |}
         |""".stripMargin
    )

    for
      clock <- TestClock.create(startedAt)
      bootstrapEither <- HotStuffRuntimeBootstrap.fromConfig[IO](config, clock)
    yield
      assertEquals(
        bootstrapEither.left.map(_.startsWith("unknown key holder validator:")),
        Left(true),
      )

  test("HotStuff bootstrap default runtime policy enables the same-window retry budget baseline"):
    assertEquals(
      HotStuffRuntimeBootstrap.DefaultRuntimePolicy.maxExactRequestRetriesPerScope,
      Some(HotStuffPolicy.requestPolicy.maxRetryAttemptsPerWindow),
    )

  test("config loader returns both topology and consensus config errors when both sections are invalid"):
    val config = ConfigFactory.parseString(
      """
        |sigilaris.node.gossip.peers {
        |  local-node-identity = "node-a"
        |}
        |
        |sigilaris.node.consensus.hotstuff {
        |  local-role = "observer"
        |}
        |""".stripMargin
    )

    for
      clock <- TestClock.create(startedAt)
      bootstrapEither <- HotStuffRuntimeBootstrap.fromConfig[IO](config, clock)
    yield
      assertEquals(
        bootstrapEither.left.map(error =>
          error.contains("missing required config key: known-peers") &&
            error.contains("unsupported local role: observer")
        ),
        Left(true),
      )

  test("config loader returns typed config errors as Left values instead of throwing"):
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
        |""".stripMargin
    )

    for
      clock <- TestClock.create(startedAt)
      bootstrapEither <- HotStuffRuntimeBootstrap.fromConfig[IO](config, clock)
    yield
      assertEquals(
        bootstrapEither.left.map(error =>
          error.contains("known-peers") &&
            error.contains("validators")
        ),
        Left(true),
      )

  private final class TestClock private (ref: Ref[IO, Instant]) extends GossipClock[IO]:
    override def now: IO[Instant] =
      ref.get

  private object TestClock:
    def create(instant: Instant): IO[TestClock] =
      Ref.of[IO, Instant](instant).map(new TestClock(_))
