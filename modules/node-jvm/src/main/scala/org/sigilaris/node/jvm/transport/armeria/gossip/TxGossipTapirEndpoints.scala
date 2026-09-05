package org.sigilaris.node.jvm.transport.armeria.gossip

import cats.Id
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.*

/** Shared Tapir endpoint definitions for the transaction gossip peer protocol.
  */
@SuppressWarnings(Array("org.wartremover.warts.Any"))
object TxGossipTapirEndpoints:
  val SessionOpenPath: String = "/gossip/session/open"

  def eventStreamPath(sessionId: String): String =
    s"/gossip/events/${sessionId}"

  def eventStreamOpenPath(sessionId: String): String =
    s"/gossip/events/${sessionId}/stream"

  val EventStreamOpenRoutePath: String =
    "/gossip/events/{sessionId}/stream"

  def controlPath(sessionId: String): String =
    s"/gossip/control/${sessionId}"

  def disconnectPath(sessionId: String): String =
    s"/gossip/session/${sessionId}/disconnect"

  private[gossip] val authenticatedPeerHeader =
    header[Option[String]](GossipTransportAuth.AuthenticatedPeerHeaderName)

  private[gossip] val transportProofHeader =
    header[Option[String]](GossipTransportAuth.TransportProofHeaderName)

  val sessionOpen =
    endpoint.post
      .in("gossip" / "session" / "open")
      .in(authenticatedPeerHeader)
      .in(transportProofHeader)
      .in(stringBody)
      .errorOut(stringBody)
      .out(stringBody)

  val eventStream =
    endpoint.post
      .in("gossip" / "events" / path[String]("sessionId"))
      .in(authenticatedPeerHeader)
      .in(transportProofHeader)
      .in(stringBody)
      .out(byteArrayBody)

  def eventStreamOpen[F[_]] =
    endpoint.post
      .in("gossip" / "events" / path[String]("sessionId") / "stream")
      .in(authenticatedPeerHeader)
      .in(transportProofHeader)
      .in(stringBody)
      .errorOut(stringBody)
      .out(streamBinaryBody(Fs2Streams[F])(CodecFormat.OctetStream()))

  val control =
    endpoint.post
      .in("gossip" / "control" / path[String]("sessionId"))
      .in(authenticatedPeerHeader)
      .in(transportProofHeader)
      .in(stringBody)
      .errorOut(stringBody)
      .out(stringBody)

  val disconnect =
    endpoint.post
      .in("gossip" / "session" / path[String]("sessionId") / "disconnect")
      .in(authenticatedPeerHeader)
      .in(transportProofHeader)
      .errorOut(stringBody)
      .out(stringBody)

  val all: List[AnyEndpoint] =
    // OpenAPI generation only needs the path and media type; runtime endpoints
    // use the effect-specific `eventStreamOpen[F]` definition.
    List(sessionOpen, eventStream, eventStreamOpen[Id], control, disconnect)
