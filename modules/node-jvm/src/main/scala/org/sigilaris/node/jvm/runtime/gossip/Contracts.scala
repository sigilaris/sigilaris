package org.sigilaris.node.jvm.runtime.gossip

import java.time.Instant

import cats.Applicative
import cats.syntax.all.*

final case class ArtifactApplyResult(
    applied: Boolean,
    duplicate: Boolean,
)

final case class AvailableGossipEvent[A](
    event: GossipEvent[A],
    availableAt: Instant,
)

trait GossipClock[F[_]]:
  def now: F[Instant]

object GossipClock:
  def constant[F[_]: Applicative](instant: Instant): GossipClock[F] =
    new GossipClock[F]:
      override def now: F[Instant] =
        instant.pure[F]

trait PeerRegistry:
  def localPeer: PeerIdentity
  def knownPeers: Set[PeerIdentity]
  def directNeighbors: Set[PeerIdentity]

  def isKnownPeer(peer: PeerIdentity): Boolean =
    knownPeers.contains(peer)

  def isDirectNeighbor(peer: PeerIdentity): Boolean =
    directNeighbors.contains(peer)

final case class StaticPeerRegistry(
    topology: StaticPeerTopology,
) extends PeerRegistry:
  override val localPeer: PeerIdentity            = topology.localNodeIdentity
  override val knownPeers: Set[PeerIdentity]      = topology.knownPeers
  override val directNeighbors: Set[PeerIdentity] = topology.directNeighbors

trait PeerAuthenticator[F[_]]:
  def authenticate(
      peer: PeerIdentity,
  ): F[Either[CanonicalRejection.HandshakeRejected, PeerIdentity]]

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

trait GossipTopicContractRegistry[A]:
  def contractFor(
      topic: GossipTopic,
  ): Either[CanonicalRejection.ArtifactContractRejected, GossipTopicContract[A]]

object GossipTopicContractRegistry:
  def single[A](
      contract: GossipTopicContract[A],
  ): GossipTopicContractRegistry[A] =
    of(contract)

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

trait GossipArtifactSource[F[_], A]:
  def readAfter(
      chainId: ChainId,
      topic: GossipTopic,
      cursor: Option[CursorToken],
  ): F[Either[CanonicalRejection, Vector[AvailableGossipEvent[A]]]]

  def readByIds(
      chainId: ChainId,
      topic: GossipTopic,
      ids: Vector[StableArtifactId],
  ): F[Vector[AvailableGossipEvent[A]]]

trait GossipArtifactSink[F[_], A]:
  def applyEvent(
      event: GossipEvent[A],
  ): F[Either[CanonicalRejection.ArtifactContractRejected, ArtifactApplyResult]]
