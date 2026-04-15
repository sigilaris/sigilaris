package org.sigilaris.node.jvm.runtime.gossip

import munit.FunSuite

import com.typesafe.config.ConfigFactory
import org.sigilaris.node.gossip.{
  PeerIdentity,
  StaticPeerTopology,
  TransportSharedSecret,
}

final class StaticPeerTransportAuthConfigSuite extends FunSuite:
  private val topology =
    StaticPeerTopology(
      localNodeIdentity = PeerIdentity.unsafe("node-a"),
      knownPeers = Set(PeerIdentity.unsafe("node-b")),
      directNeighbors = Set(PeerIdentity.unsafe("node-b")),
    )

  test("parse extracts a raw peer secret input model before domain validation"):
    val config = ConfigFactory.parseString(
      """
        |sigilaris.node.gossip.peers {
        |  transportAuth {
        |    peerSecrets {
        |      "node-a" = "local-secret"
        |      "node-b" = "neighbor-secret"
        |    }
        |  }
        |}
        |""".stripMargin,
    )

    assertEquals(
      StaticPeerTransportAuthConfig.parse(config),
      Right(
        StaticPeerTransportAuthConfigInput(
          peerSecrets = Map(
            PeerIdentity.unsafe("node-a") ->
              TransportSharedSecret.fromUtf8("local-secret").toOption.get,
            PeerIdentity.unsafe("node-b") ->
              TransportSharedSecret.fromUtf8("neighbor-secret").toOption.get,
          ),
        ),
      ),
    )

  test("load preserves topology-aware secret validation"):
    val config = ConfigFactory.parseString(
      """
        |sigilaris.node.gossip.peers {
        |  transport-auth {
        |    peer-secrets {
        |      "node-a" = "local-secret"
        |      "node-b" = "neighbor-secret"
        |      "node-z" = "unexpected-secret"
        |    }
        |  }
        |}
        |""".stripMargin,
    )

    assertEquals(
      StaticPeerTransportAuthConfig.load(config, topology).left.map(_.contains("unknown peers")),
      Left(true),
    )
