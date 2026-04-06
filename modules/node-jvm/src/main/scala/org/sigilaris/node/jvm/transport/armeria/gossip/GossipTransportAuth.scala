package org.sigilaris.node.jvm.transport.armeria.gossip

import java.nio.charset.StandardCharsets
import java.util.Base64

import scala.util.Try

import org.sigilaris.node.jvm.runtime.gossip.{DirectionalSessionId, PeerIdentity}

private[gossip] final case class BootstrapCapabilityToken(
    peer: PeerIdentity,
    sessionId: DirectionalSessionId,
)

private[gossip] object GossipTransportAuth:
  val AuthenticatedPeerHeaderName: String  = "x-sigilaris-peer-identity"
  val BootstrapCapabilityHeaderName: String =
    "x-sigilaris-bootstrap-capability"

  def parseAuthenticatedPeer(
      raw: String,
  ): Either[String, PeerIdentity] =
    PeerIdentity.parse(raw)

  def issueBootstrapCapability(
      peer: PeerIdentity,
      sessionId: DirectionalSessionId,
  ): String =
    // This token is only a transport projection of a runtime-owned session binding.
    // Stronger cryptographic credential material is deferred to the concrete auth follow-up.
    Base64.getUrlEncoder.withoutPadding().encodeToString(
      (peer.value + ":" + sessionId.value).getBytes(StandardCharsets.UTF_8),
    )

  def decodeBootstrapCapability(
      raw: String,
  ): Either[String, BootstrapCapabilityToken] =
    for
      decoded <- Try:
          String(
            Base64.getUrlDecoder.decode(raw),
            StandardCharsets.UTF_8,
          )
        .toEither
        .left
        .map(_ => "bootstrap capability must be base64url-encoded")
      separator = decoded.indexOf(':')
      _ <- Either.cond(
        separator > 0 && separator < decoded.length - 1,
        (),
        "bootstrap capability must encode <peer>:<sessionId>",
      )
      peer <- PeerIdentity.parse(decoded.take(separator))
      sessionId <- DirectionalSessionId.parse(decoded.drop(separator + 1))
    yield BootstrapCapabilityToken(peer = peer, sessionId = sessionId)
