package org.sigilaris.node.jvm.runtime.gossip

import munit.FunSuite

import com.typesafe.config.ConfigFactory
import org.sigilaris.node.gossip.{PeerIdentity, StaticPeerTopology}

final class StaticPeerTopologyConfigSuite extends FunSuite:
  test("parse accepts kebab-case and camelCase aliases into a raw input model"):
    val config = ConfigFactory.parseString(
      """
        |sigilaris.node.gossip.peers {
        |  localNodeIdentity = "node-a"
        |  known-peers = ["node-b", "node-c"]
        |  directNeighbors = ["node-b"]
        |}
        |""".stripMargin,
    )

    assertEquals(
      StaticPeerTopologyConfig.parse(config),
      Right(
        StaticPeerTopologyConfigInput(
          localNodeIdentity = PeerIdentity.unsafe("node-a"),
          knownPeers =
            List(PeerIdentity.unsafe("node-b"), PeerIdentity.unsafe("node-c")),
          directNeighbors = List(PeerIdentity.unsafe("node-b")),
        ),
      ),
    )

  test("load still validates the topology domain invariants after parsing"):
    val config = ConfigFactory.parseString(
      """
        |sigilaris.node.gossip.peers {
        |  local-node-identity = "node-a"
        |  known-peers = ["node-b", "node-c"]
        |  direct-neighbors = ["node-b"]
        |}
        |""".stripMargin,
    )

    assertEquals(
      StaticPeerTopologyConfig.load(config),
      Right(
        StaticPeerTopology(
          localNodeIdentity = PeerIdentity.unsafe("node-a"),
          knownPeers =
            Set(PeerIdentity.unsafe("node-b"), PeerIdentity.unsafe("node-c")),
          directNeighbors = Set(PeerIdentity.unsafe("node-b")),
        ),
      ),
    )
