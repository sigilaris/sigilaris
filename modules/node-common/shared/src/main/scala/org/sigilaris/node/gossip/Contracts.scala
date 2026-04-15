package org.sigilaris.node.gossip

import java.time.Instant

import cats.Applicative
import cats.syntax.all.*

/** Result of applying a gossip artifact to the local sink.
  *
  * @param applied
  *   true if the artifact was newly applied
  * @param duplicate
  *   true if the artifact was already known
  */
final case class ArtifactApplyResult(
    applied: Boolean,
    duplicate: Boolean,
)

/** A gossip event paired with the timestamp at which it became available for
  * delivery.
  *
  * @tparam A
  *   the payload type of the gossip event
  * @param event
  *   the gossip event
  * @param availableAt
  *   the instant at which this event became eligible for producer batching
  */
final case class AvailableGossipEvent[A](
    event: GossipEvent[A],
    availableAt: Instant,
)

/** Abstraction over a clock used by the gossip subsystem.
  *
  * @tparam F
  *   the effect type
  */
trait GossipClock[F[_]]:

  /** @return the current instant */
  def now: F[Instant]

/** Companion for `GossipClock` providing factory methods. */
object GossipClock:

  /** Creates a clock that always returns the given fixed instant.
    *
    * @tparam F
    *   the effect type
    * @param instant
    *   the constant instant to return
    * @return
    *   a fixed-time gossip clock
    */
  def constant[F[_]: Applicative](instant: Instant): GossipClock[F] =
    new GossipClock[F]:
      override def now: F[Instant] =
        instant.pure[F]

/** Registry of peers known to the local gossip node. */
trait PeerRegistry:

  /** @return the identity of the local peer */
  def localPeer: PeerIdentity

  /** @return the set of all known peer identities in the network */
  def knownPeers: Set[PeerIdentity]

  /** @return the set of peers that are direct neighbors of the local peer */
  def directNeighbors: Set[PeerIdentity]

  /** Checks whether the given peer is a known peer.
    *
    * @param peer
    *   the peer identity to check
    * @return
    *   true if the peer is known
    */
  def isKnownPeer(peer: PeerIdentity): Boolean =
    knownPeers.contains(peer)

  /** Checks whether the given peer is a direct neighbor.
    *
    * @param peer
    *   the peer identity to check
    * @return
    *   true if the peer is a direct neighbor
    */
  def isDirectNeighbor(peer: PeerIdentity): Boolean =
    directNeighbors.contains(peer)

/** A `PeerRegistry` backed by a static peer topology.
  *
  * @param topology
  *   the static topology describing known peers and neighbors
  */
final case class StaticPeerRegistry(
    topology: StaticPeerTopology,
) extends PeerRegistry:
  override val localPeer: PeerIdentity            = topology.localNodeIdentity
  override val knownPeers: Set[PeerIdentity]      = topology.knownPeers
  override val directNeighbors: Set[PeerIdentity] = topology.directNeighbors

/** Authenticates a peer identity during handshake.
  *
  * @tparam F
  *   the effect type
  */
trait PeerAuthenticator[F[_]]:

  /** Authenticates the given peer, returning the verified identity or a
    * rejection.
    *
    * @param peer
    *   the peer identity to authenticate
    * @return
    *   the authenticated identity or a handshake rejection
    */
  def authenticate(
      peer: PeerIdentity,
  ): F[Either[CanonicalRejection.HandshakeRejected, PeerIdentity]]

/** A `PeerAuthenticator` that accepts only direct neighbors from a static
  * registry.
  *
  * @tparam F
  *   the effect type
  * @param registry
  *   the peer registry used for neighbor verification
  */
final class StaticPeerAuthenticator[F[_]: Applicative](
    registry: PeerRegistry,
) extends PeerAuthenticator[F]:
  override def authenticate(
      peer: PeerIdentity,
  ): F[Either[CanonicalRejection.HandshakeRejected, PeerIdentity]] =
    Either
      .cond(
        registry.isDirectNeighbor(peer),
        peer,
        CanonicalRejection.HandshakeRejected(
          reason = "nonNeighborPeer",
          detail = Some(peer.value),
        ),
      )
      .pure[F]

/** Registry that maps gossip topics to their validation contracts.
  *
  * @tparam A
  *   the artifact payload type
  */
trait GossipTopicContractRegistry[A]:

  /** Looks up the contract for the given topic.
    *
    * @param topic
    *   the gossip topic
    * @return
    *   the contract or a rejection if the topic is unsupported
    */
  def contractFor(
      topic: GossipTopic,
  ): Either[CanonicalRejection.ArtifactContractRejected, GossipTopicContract[A]]

/** Companion for `GossipTopicContractRegistry` providing factory methods. */
object GossipTopicContractRegistry:

  /** Creates a registry with exactly one contract.
    *
    * @tparam A
    *   the artifact payload type
    * @param contract
    *   the single contract to register
    * @return
    *   a registry containing the given contract
    */
  def single[A](
      contract: GossipTopicContract[A],
  ): GossipTopicContractRegistry[A] =
    of(contract)

  /** Creates a registry from one or more contracts, keyed by their topic.
    *
    * @tparam A
    *   the artifact payload type
    * @param contracts
    *   the contracts to register
    * @return
    *   a registry containing all given contracts
    */
  def of[A](
      contracts: GossipTopicContract[A]*,
  ): GossipTopicContractRegistry[A] =
    val byTopic =
      contracts.iterator.map(contract => contract.topic -> contract).toMap
    new GossipTopicContractRegistry[A]:
      override def contractFor(
          topic: GossipTopic,
      ): Either[
        CanonicalRejection.ArtifactContractRejected,
        GossipTopicContract[A],
      ] =
        byTopic
          .get(topic)
          .toRight:
            CanonicalRejection.ArtifactContractRejected(
              reason = "unsupportedTopic",
              detail = Some(topic.value),
            )

/** Source of gossip artifacts for a producer to read from.
  *
  * @tparam F
  *   the effect type
  * @tparam A
  *   the artifact payload type
  */
trait GossipArtifactSource[F[_], A]:

  /** Reads available events after the given cursor position.
    *
    * @param chainId
    *   the chain to read from
    * @param topic
    *   the gossip topic
    * @param cursor
    *   optional cursor; None starts from the beginning
    * @return
    *   events after the cursor, or a rejection on error
    */
  def readAfter(
      chainId: ChainId,
      topic: GossipTopic,
      cursor: Option[CursorToken],
  ): F[Either[CanonicalRejection, Vector[AvailableGossipEvent[A]]]]

  /** Reads events by their stable artifact identifiers.
    *
    * @param chainId
    *   the chain to read from
    * @param topic
    *   the gossip topic
    * @param ids
    *   the artifact identifiers to look up
    * @return
    *   the matching available events
    */
  def readByIds(
      chainId: ChainId,
      topic: GossipTopic,
      ids: Vector[StableArtifactId],
  ): F[Vector[AvailableGossipEvent[A]]]

/** Sink that consumes incoming gossip artifacts from peers.
  *
  * @tparam F
  *   the effect type
  * @tparam A
  *   the artifact payload type
  */
trait GossipArtifactSink[F[_], A]:

  /** Applies a received gossip event to the local store.
    *
    * @param event
    *   the gossip event to apply
    * @return
    *   the apply result, or a contract rejection on validation failure
    */
  def applyEvent(
      event: GossipEvent[A],
  ): F[Either[CanonicalRejection.ArtifactContractRejected, ArtifactApplyResult]]
