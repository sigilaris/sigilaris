package org.sigilaris.node.jvm.transport.armeria.gossip

import sttp.tapir.*

/** Shared Tapir endpoint definitions for HotStuff bootstrap peer services. */
@SuppressWarnings(Array("org.wartremover.warts.Any"))
object HotStuffBootstrapTapirEndpoints:
  def finalizedPath(sessionId: String, chainId: String): String =
    s"/gossip/bootstrap/finalized/${sessionId}/${chainId}"

  def snapshotPath(sessionId: String, chainId: String): String =
    s"/gossip/bootstrap/snapshot/${sessionId}/${chainId}"

  def replayPath(sessionId: String, chainId: String): String =
    s"/gossip/bootstrap/replay/${sessionId}/${chainId}"

  def backfillPath(sessionId: String, chainId: String): String =
    s"/gossip/bootstrap/backfill/${sessionId}/${chainId}"

  private[gossip] val authenticatedPeerHeader =
    header[Option[String]](GossipTransportAuth.AuthenticatedPeerHeaderName)

  private[gossip] val transportProofHeader =
    header[Option[String]](GossipTransportAuth.TransportProofHeaderName)

  private[gossip] val bootstrapCapabilityHeader =
    header[Option[String]](GossipTransportAuth.BootstrapCapabilityHeaderName)

  val finalizedSuggestion =
    endpoint.post
      .in(
        "gossip" / "bootstrap" / "finalized" / path[String]("sessionId") /
          path[String]("chainId"),
      )
      .in(authenticatedPeerHeader)
      .in(transportProofHeader)
      .in(bootstrapCapabilityHeader)
      .errorOut(stringBody)
      .out(stringBody)

  val snapshotFetch =
    endpoint.post
      .in(
        "gossip" / "bootstrap" / "snapshot" / path[String]("sessionId") /
          path[String]("chainId"),
      )
      .in(authenticatedPeerHeader)
      .in(transportProofHeader)
      .in(bootstrapCapabilityHeader)
      .in(stringBody)
      .errorOut(stringBody)
      .out(stringBody)

  val replay =
    endpoint.post
      .in(
        "gossip" / "bootstrap" / "replay" / path[String]("sessionId") /
          path[String]("chainId"),
      )
      .in(authenticatedPeerHeader)
      .in(transportProofHeader)
      .in(bootstrapCapabilityHeader)
      .in(stringBody)
      .errorOut(stringBody)
      .out(stringBody)

  val backfill =
    endpoint.post
      .in(
        "gossip" / "bootstrap" / "backfill" / path[String]("sessionId") /
          path[String]("chainId"),
      )
      .in(authenticatedPeerHeader)
      .in(transportProofHeader)
      .in(bootstrapCapabilityHeader)
      .in(stringBody)
      .errorOut(stringBody)
      .out(stringBody)

  val all: List[AnyEndpoint] =
    List(finalizedSuggestion, snapshotFetch, replay, backfill)
