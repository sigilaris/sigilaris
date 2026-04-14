package org.sigilaris.node.gossip

import java.nio.charset.StandardCharsets

import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.util.SafeStringInterp.*

/** A shared secret used for transport-level peer authentication.
  *
  * @param bytes
  *   the secret bytes
  */
final case class TransportSharedSecret private (
    bytes: ByteVector,
)

/** Companion for `TransportSharedSecret`. */
object TransportSharedSecret:

  /** Creates a shared secret from a UTF-8 string.
    *
    * @param value
    *   the secret string (must not be empty)
    * @return
    *   the secret, or an error
    */
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
/** Transport authentication state holding per-peer shared secrets.
  *
  * @param localPeer
  *   the identity of the local peer
  * @param peerSecrets
  *   the shared secrets for each known peer
  */
final case class StaticPeerTransportAuth(
    localPeer: PeerIdentity,
    peerSecrets: Map[PeerIdentity, TransportSharedSecret],
):

  /** Returns the shared secret for the given peer.
    *
    * @param peer
    *   the peer identity
    * @return
    *   the secret, or an error if not configured
    */
  def secretFor(
      peer: PeerIdentity,
  ): Either[String, TransportSharedSecret] =
    peerSecrets
      .get(peer)
      .toRight(ss"missing transport secret for peer: ${peer.value}")

  /** Returns the local peer's own shared secret.
    *
    * @return
    *   the local secret, or an error if not configured
    */
  def localSecret: Either[String, TransportSharedSecret] =
    secretFor(localPeer)

/** Companion for `StaticPeerTransportAuth` providing construction and testing
  * helpers.
  */
object StaticPeerTransportAuth:

  /** Configures transport auth from raw string secrets, validating completeness
    * against the topology.
    *
    * @param topology
    *   the peer topology
    * @param peerSecrets
    *   raw peer identity to secret string mappings
    * @return
    *   the transport auth, or an error
    */
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

  /** Creates transport auth with deterministic test secrets for all peers.
    *
    * @param topology
    *   the peer topology
    * @return
    *   the transport auth with test secrets
    */
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
