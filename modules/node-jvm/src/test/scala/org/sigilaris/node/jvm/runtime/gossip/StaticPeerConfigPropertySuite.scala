package org.sigilaris.node.jvm.runtime.gossip

import java.net.URI
import java.time.Duration

import hedgehog.*
import hedgehog.munit.HedgehogSuite

import com.typesafe.config.ConfigFactory
import org.sigilaris.node.gossip.{
  PeerIdentity,
  StaticPeerTopology,
  StaticPeerTransportAuth,
  TransportSharedSecret,
}

final class StaticPeerConfigPropertySuite extends HedgehogSuite:

  private final case class TopologyFixture(
      localNodeIdentity: String,
      knownPeers: List[String],
      directNeighbors: List[String],
  ):
    val topology: StaticPeerTopology =
      StaticPeerTopology
        .fromValidated(
          localNodeIdentity = PeerIdentity.unsafe(localNodeIdentity),
          knownPeers = knownPeers.map(PeerIdentity.unsafe).toSet,
          directNeighbors = directNeighbors.map(PeerIdentity.unsafe).toSet,
        )
        .toOption
        .get

  private val genKnownPeerOrdinals: Gen[List[Int]] =
    Gen.list(Gen.int(Range.linear(1, 32)), Range.linear(1, 5)).map(_.distinct)

  private val genTopologyFixture: Gen[TopologyFixture] =
    for
      ordinals <- genKnownPeerOrdinals
      directNeighborCount <- Gen.int(Range.linear(0, ordinals.size))
    yield
      val knownPeers = ordinals.map(ordinal => s"node-$ordinal")
      TopologyFixture(
        localNodeIdentity = "node-a",
        knownPeers = knownPeers,
        directNeighbors = knownPeers.take(directNeighborCount),
      )

  private val genRequestTimeoutMs: Gen[Long] =
    Gen.long(Range.linear(1L, 5000L))

  private val genMaxConcurrentRequests: Gen[Int] =
    Gen.int(Range.linear(1, 8))

  private def quotedList(values: List[String]): String =
    values.map(value => s""""$value"""").mkString("[", ", ", "]")

  private def stringMapBody(values: Map[String, String]): String =
    values.toVector
      .sortBy(_._1)
      .map: (key, value) =>
        s""""$key" = "$value""""
      .mkString("\n")

  private def topologyConfig(
      fixture: TopologyFixture,
      camelCase: Boolean,
  ) =
    val localKey =
      if camelCase then "localNodeIdentity" else "local-node-identity"
    val knownKey =
      if camelCase then "knownPeers" else "known-peers"
    val directKey =
      if camelCase then "directNeighbors" else "direct-neighbors"
    ConfigFactory.parseString(
      s"""
         |sigilaris.node.gossip.peers {
         |  $localKey = "${fixture.localNodeIdentity}"
         |  $knownKey = ${quotedList(fixture.knownPeers)}
         |  $directKey = ${quotedList(fixture.directNeighbors)}
         |}
         |""".stripMargin,
    )

  private def transportAuthConfig(
      peerSecrets: Map[String, String],
      camelCase: Boolean,
  ) =
    val authKey =
      if camelCase then "transportAuth" else "transport-auth"
    val peerSecretsKey =
      if camelCase then "peerSecrets" else "peer-secrets"
    ConfigFactory.parseString(
      s"""
         |sigilaris.node.gossip.peers {
         |  $authKey {
         |    $peerSecretsKey {
         |      ${stringMapBody(peerSecrets)}
         |    }
         |  }
         |}
         |""".stripMargin,
    )

  private def bootstrapConfig(
      peerBaseUris: Map[String, String],
      requestTimeoutMs: Long,
      maxConcurrentRequests: Int,
      camelCase: Boolean,
  ) =
    val peerBaseUrisKey =
      if camelCase then "peerBaseUris" else "peer-base-uris"
    val requestTimeoutKey =
      if camelCase then "requestTimeoutMs" else "request-timeout-ms"
    val maxConcurrentKey =
      if camelCase then "maxConcurrentRequests" else "max-concurrent-requests"
    ConfigFactory.parseString(
      s"""
         |sigilaris.node.gossip.peers {
         |  bootstrap {
         |    $peerBaseUrisKey {
         |      ${stringMapBody(peerBaseUris)}
         |    }
         |    $requestTimeoutKey = $requestTimeoutMs
         |    $maxConcurrentKey = $maxConcurrentRequests
         |  }
         |}
         |""".stripMargin,
    )

  property("StaticPeerTopologyConfig parses kebab-case and camelCase aliases equivalently"):
    for fixture <- genTopologyFixture.forAll
    yield
      val expected =
        StaticPeerTopologyConfigInput(
          localNodeIdentity = PeerIdentity.unsafe(fixture.localNodeIdentity),
          knownPeers = fixture.knownPeers.map(PeerIdentity.unsafe),
          directNeighbors = fixture.directNeighbors.map(PeerIdentity.unsafe),
        )
      val kebab = StaticPeerTopologyConfig.parse(topologyConfig(fixture, camelCase = false))
      val camel = StaticPeerTopologyConfig.parse(topologyConfig(fixture, camelCase = true))
      Result.all(
        List(
          Result.assert(kebab == Right(expected)),
          Result.assert(camel == Right(expected)),
          Result.assert(kebab == camel),
        ),
      )

  property("StaticPeerTransportAuthConfig parses kebab-case and camelCase aliases equivalently"):
    for fixture <- genTopologyFixture.forAll
    yield
      val peerSecrets =
        (fixture.knownPeers :+ fixture.localNodeIdentity)
          .distinct
          .sorted
          .map(peer => peer -> s"secret-$peer")
          .toMap
      val expected =
        StaticPeerTransportAuthConfigInput(
          peerSecrets = peerSecrets.toVector.map: (peer, secret) =>
            PeerIdentity.unsafe(peer) ->
              TransportSharedSecret.fromUtf8(secret).toOption.get
          .toMap,
        )
      val kebab =
        StaticPeerTransportAuthConfig.parse(
          transportAuthConfig(peerSecrets, camelCase = false),
        )
      val camel =
        StaticPeerTransportAuthConfig.parse(
          transportAuthConfig(peerSecrets, camelCase = true),
        )
      Result.all(
        List(
          Result.assert(kebab == Right(expected)),
          Result.assert(camel == Right(expected)),
          Result.assert(kebab == camel),
        ),
      )

  property("StaticPeerBootstrapHttpTransportConfig parses kebab-case and camelCase aliases equivalently"):
    for
      fixture <- genTopologyFixture.forAll
      requestTimeoutMs <- genRequestTimeoutMs.forAll
      maxConcurrentRequests <- genMaxConcurrentRequests.forAll
    yield
      val peerBaseUris =
        fixture.knownPeers
          .sorted
          .zipWithIndex
          .map: (peer, index) =>
            peer -> s"http://127.0.0.1:${7000 + index}"
          .toMap
      val expected =
        Some(
          StaticPeerBootstrapHttpTransportConfigInput(
            peerBaseUris = peerBaseUris.toVector.map: (peer, uri) =>
              PeerIdentity.unsafe(peer) -> URI.create(uri)
            .toMap,
            requestTimeout = Duration.ofMillis(requestTimeoutMs),
            maxConcurrentRequests = maxConcurrentRequests,
          ),
        )
      val kebab =
        StaticPeerBootstrapHttpTransportConfig.parse(
          bootstrapConfig(
            peerBaseUris = peerBaseUris,
            requestTimeoutMs = requestTimeoutMs,
            maxConcurrentRequests = maxConcurrentRequests,
            camelCase = false,
          ),
        )
      val camel =
        StaticPeerBootstrapHttpTransportConfig.parse(
          bootstrapConfig(
            peerBaseUris = peerBaseUris,
            requestTimeoutMs = requestTimeoutMs,
            maxConcurrentRequests = maxConcurrentRequests,
            camelCase = true,
          ),
        )
      Result.all(
        List(
          Result.assert(kebab == Right(expected)),
          Result.assert(camel == Right(expected)),
          Result.assert(kebab == camel),
        ),
      )

  property("StaticPeerTransportAuth.configure rejects generated missing peer secrets"):
    for fixture <- genTopologyFixture.forAll
    yield
      val omittedPeer = fixture.knownPeers.head
      val peerSecrets =
        (fixture.knownPeers :+ fixture.localNodeIdentity)
          .filterNot(_ == omittedPeer)
          .map: peer =>
            PeerIdentity.unsafe(peer) ->
              TransportSharedSecret.fromUtf8(s"secret-$peer").toOption.get
          .toMap
      Result.assert(
        StaticPeerTransportAuth
          .configure(fixture.topology, peerSecrets)
          .left
          .exists(_.contains("missing transport secret")),
      )

  property("StaticPeerBootstrapHttpTransportConfig.load rejects generated unknown peers"):
    for fixture <- genTopologyFixture.forAll
    yield
      val unknownPeer = "node-z"
      val config =
        bootstrapConfig(
          peerBaseUris = Map(unknownPeer -> "http://127.0.0.1:7999"),
          requestTimeoutMs = 1000L,
          maxConcurrentRequests = 1,
          camelCase = false,
        )
      Result.assert(
        StaticPeerBootstrapHttpTransportConfig
          .load(config, fixture.topology)
          .left
          .exists(_.contains("unknown peer")),
      )

  property("StaticPeerBootstrapHttpTransportConfig.parse rejects bootstrap sections missing peer base URIs"):
    for
      requestTimeoutMs <- genRequestTimeoutMs.forAll
      maxConcurrentRequests <- genMaxConcurrentRequests.forAll
    yield
      val config = ConfigFactory.parseString(
        s"""
           |sigilaris.node.gossip.peers {
           |  bootstrap {
           |    request-timeout-ms = $requestTimeoutMs
           |    max-concurrent-requests = $maxConcurrentRequests
           |  }
           |}
           |""".stripMargin,
      )
      Result.assert(
        StaticPeerBootstrapHttpTransportConfig.parse(config).isLeft,
      )
