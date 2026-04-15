package org.sigilaris.node.jvm.runtime.gossip

import java.net.URI
import java.time.Duration

import munit.FunSuite

import com.typesafe.config.ConfigFactory
import org.sigilaris.node.gossip.{PeerIdentity, StaticPeerTopology}

final class StaticPeerBootstrapHttpTransportConfigSuite extends FunSuite:

  private val topology =
    StaticPeerTopology(
      localNodeIdentity = PeerIdentity.unsafe("node-a"),
      knownPeers =
        Set(PeerIdentity.unsafe("node-b"), PeerIdentity.unsafe("node-c")),
      directNeighbors = Set(PeerIdentity.unsafe("node-b")),
    )

  test("load parses bootstrap peer base URIs and request tuning"):
    val config = ConfigFactory.parseString(
      """
        |sigilaris.node.gossip.peers {
        |  bootstrap {
        |    peer-base-uris {
        |      "node-b" = "http://127.0.0.1:7001"
        |      "node-c" = "http://127.0.0.1:7002"
        |    }
        |    request-timeout-ms = 2500
        |    max-concurrent-requests = 4
        |  }
        |}
        |""".stripMargin,
    )

    assertEquals(
      StaticPeerBootstrapHttpTransportConfig.load(config, topology),
      Right(
        Some(
          StaticPeerBootstrapHttpTransportConfig(
            peerBaseUris = Map(
              PeerIdentity.unsafe("node-b") -> URI.create("http://127.0.0.1:7001"),
              PeerIdentity.unsafe("node-c") -> URI.create("http://127.0.0.1:7002"),
            ),
            requestTimeout = Duration.ofMillis(2500L),
            maxConcurrentRequests = 4,
          ),
        ),
      ),
    )

  test("loadSection accepts camelCase keys and missing bootstrap section"):
    val section = ConfigFactory.parseString(
      """
        |bootstrap {
        |  peerBaseUris {
        |    "node-b" = "http://127.0.0.1:7001"
        |  }
        |  requestTimeoutMs = 3000
        |  maxConcurrentRequests = 2
        |}
        |""".stripMargin,
    )

    assertEquals(
      StaticPeerBootstrapHttpTransportConfig.loadSection(section, topology),
      Right(
        Some(
          StaticPeerBootstrapHttpTransportConfig(
            peerBaseUris =
              Map(
                PeerIdentity.unsafe("node-b") ->
                  URI.create("http://127.0.0.1:7001"),
              ),
            requestTimeout = Duration.ofMillis(3000L),
            maxConcurrentRequests = 2,
          ),
        ),
      ),
    )
    assertEquals(
      StaticPeerBootstrapHttpTransportConfig.loadSection(
        ConfigFactory.parseString("transport-auth {}"),
        topology,
      ),
      Right(None),
    )

  test("parse exposes a raw bootstrap input model with defaults applied"):
    val config = ConfigFactory.parseString(
      """
        |sigilaris.node.gossip.peers {
        |  bootstrap {
        |    peerBaseUris {
        |      "node-b" = "http://127.0.0.1:7001"
        |    }
        |  }
        |}
        |""".stripMargin,
    )

    assertEquals(
      StaticPeerBootstrapHttpTransportConfig.parse(config),
      Right(
        Some(
          StaticPeerBootstrapHttpTransportConfigInput(
            peerBaseUris =
              Map(
                PeerIdentity.unsafe("node-b") ->
                  URI.create("http://127.0.0.1:7001"),
              ),
            requestTimeout =
              StaticPeerBootstrapHttpTransportConfig.DefaultRequestTimeout,
            maxConcurrentRequests =
              StaticPeerBootstrapHttpTransportConfig.DefaultMaxConcurrentRequests,
          ),
        ),
      ),
    )

  test("load rejects bootstrap peer base URIs for unknown peers"):
    val config = ConfigFactory.parseString(
      """
        |sigilaris.node.gossip.peers {
        |  bootstrap {
        |    peer-base-uris {
        |      "node-z" = "http://127.0.0.1:7999"
        |    }
        |  }
        |}
        |""".stripMargin,
    )

    assertEquals(
      StaticPeerBootstrapHttpTransportConfig
        .load(config, topology)
        .left
        .map(_.contains("unknown peer")),
      Left(true),
    )

  test("parse rejects relative bootstrap peer base URIs"):
    val config = ConfigFactory.parseString(
      """
        |sigilaris.node.gossip.peers {
        |  bootstrap {
        |    peer-base-uris {
        |      "node-b" = "/relative/path"
        |    }
        |  }
        |}
        |""".stripMargin,
    )

    assertEquals(
      StaticPeerBootstrapHttpTransportConfig
        .parse(config)
        .left
        .map(_.contains("must be absolute")),
      Left(true),
    )

  test("load rejects non-positive max concurrent requests"):
    val config = ConfigFactory.parseString(
      """
        |sigilaris.node.gossip.peers {
        |  bootstrap {
        |    peer-base-uris {
        |      "node-b" = "http://127.0.0.1:7001"
        |    }
        |    max-concurrent-requests = 0
        |  }
        |}
        |""".stripMargin,
    )

    assertEquals(
      StaticPeerBootstrapHttpTransportConfig
        .load(config, topology)
        .left
        .map(_.contains("maxConcurrentRequests must be positive")),
      Left(true),
    )

  test("load rejects non-positive request timeout"):
    val config = ConfigFactory.parseString(
      """
        |sigilaris.node.gossip.peers {
        |  bootstrap {
        |    peer-base-uris {
        |      "node-b" = "http://127.0.0.1:7001"
        |    }
        |    request-timeout-ms = 0
        |  }
        |}
        |""".stripMargin,
    )

    assertEquals(
      StaticPeerBootstrapHttpTransportConfig
        .load(config, topology)
        .left
        .map(_.contains("requestTimeoutMs must be positive")),
      Left(true),
    )
