package org.sigilaris.node.jvm.runtime.gossip

import java.nio.charset.StandardCharsets

import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.util.SafeStringInterp.*

final case class TransportSharedSecret private (
    bytes: ByteVector,
)

object TransportSharedSecret:
  def fromUtf8(
      value: String,
  ): Either[String, TransportSharedSecret] =
    Either.cond(
      value.nonEmpty,
      TransportSharedSecret(
        ByteVector.view(value.getBytes(StandardCharsets.UTF_8)),
      ),
      "transport shared secret must not be empty",
    )

  private[gossip] def testing(
      value: String,
  ): TransportSharedSecret =
    TransportSharedSecret(
      ByteVector.view(value.getBytes(StandardCharsets.UTF_8)),
    )
final case class StaticPeerTransportAuth(
    localPeer: PeerIdentity,
    peerSecrets: Map[PeerIdentity, TransportSharedSecret],
):
  def secretFor(
      peer: PeerIdentity,
  ): Either[String, TransportSharedSecret] =
    peerSecrets
      .get(peer)
      .toRight(ss"missing transport secret for peer: ${peer.value}")

  def localSecret: Either[String, TransportSharedSecret] =
    secretFor(localPeer)

object StaticPeerTransportAuth:
  def configure(
      topology: StaticPeerTopology,
      peerSecrets: Map[String, String],
  ): Either[String, StaticPeerTransportAuth] =
    val requiredPeers = topology.knownPeers + topology.localNodeIdentity
    for
      parsedSecrets <- peerSecrets.toList
        .foldLeft[Either[String, Map[PeerIdentity, TransportSharedSecret]]](
          Map.empty[PeerIdentity, TransportSharedSecret].asRight[String],
        ):
          case (acc, (peerRaw, secretRaw)) =>
            for
              current <- acc
              peer    <- PeerIdentity.parse(peerRaw)
              secret  <- TransportSharedSecret.fromUtf8(secretRaw)
            yield current.updated(peer, secret)
      missing = requiredPeers
        .diff(parsedSecrets.keySet)
        .toVector
        .sortBy(_.value)
      _ <- Either.cond(
        missing.isEmpty,
        (),
        ss"missing transport secret for peers: ${missing.map(_.value).mkString(",")}",
      )
      unknown = parsedSecrets.keySet
        .diff(requiredPeers)
        .toVector
        .sortBy(_.value)
      _ <- Either.cond(
        unknown.isEmpty,
        (),
        ss"transport secrets contain unknown peers: ${unknown.map(_.value).mkString(",")}",
      )
    yield StaticPeerTransportAuth(
      localPeer = topology.localNodeIdentity,
      peerSecrets = parsedSecrets,
    )

  def testing(
      topology: StaticPeerTopology,
  ): StaticPeerTransportAuth =
    val peers = topology.knownPeers + topology.localNodeIdentity
    StaticPeerTransportAuth(
      localPeer = topology.localNodeIdentity,
      peerSecrets = peers.iterator
        .map: peer =>
          peer -> TransportSharedSecret.testing(
            ss"sigilaris-test-secret:${peer.value}",
          )
        .toMap,
    )
