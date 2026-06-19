package org.sigilaris.node.jvm.transport.armeria.gossip

import java.net.URI
import java.time.Duration

import cats.effect.{Async, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import sttp.client4.Backend
import sttp.client4.armeria.cats.ArmeriaCatsBackend

import org.sigilaris.core.codec.byte.ByteDecoder
import org.sigilaris.node.gossip.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*

/** Reusable endpoint-derived peer transport for HotStuff gossip runtimes. */
final case class HotStuffPeerTransportClient[F[_], A](
    gossipPeers: Map[PeerIdentity, TxGossipPeerClient[F, A]],
    bootstrap: HotStuffBootstrapTransportServices[F],
):
  def peer(
      peerIdentity: PeerIdentity,
  ): Either[GossipPeerClientError, TxGossipPeerClient[F, A]] =
    gossipPeers
      .get(peerIdentity)
      .toRight:
        GossipPeerClientError.TransportFailure(
          reason = "gossipPeerEndpointUnavailable",
          detail = Some(peerIdentity.value),
        )

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
object HotStuffPeerTransportClient:
  val DefaultRequestTimeout: Duration =
    TxGossipPeerClient.DefaultRequestTimeout
  val DefaultMaxConcurrentRequests: Int =
    TxGossipPeerClient.DefaultMaxConcurrentRequests

  def hotStuffOnlyResource[F[_]: Async](
      peerBaseUris: Map[PeerIdentity, URI],
      transportAuth: StaticPeerTransportAuth,
      authenticatedPeer: PeerIdentity,
      requestTimeout: Duration = DefaultRequestTimeout,
      maxConcurrentRequests: Int = DefaultMaxConcurrentRequests,
      bootstrapRequestTimeout: Duration =
        HotStuffBootstrapPeerClient.DefaultRequestTimeout,
      bootstrapMaxConcurrentRequests: Int =
        HotStuffBootstrapPeerClient.DefaultMaxConcurrentRequests,
      proposalCatchUpReadiness: Option[ProposalCatchUpReadiness[F]] = None,
  ): Resource[
    F,
    HotStuffPeerTransportClient[F, HotStuffGossipArtifact],
  ] =
    resource[F, HotStuffGossipArtifact](
      peerBaseUris = peerBaseUris,
      transportAuth = transportAuth,
      authenticatedPeer = authenticatedPeer,
      requestTimeout = requestTimeout,
      maxConcurrentRequests = maxConcurrentRequests,
      bootstrapRequestTimeout = bootstrapRequestTimeout,
      bootstrapMaxConcurrentRequests = bootstrapMaxConcurrentRequests,
      proposalCatchUpReadiness = proposalCatchUpReadiness,
    )

  def withApplicationTopicsResource[F[_]: Async, A: ByteDecoder](
      peerBaseUris: Map[PeerIdentity, URI],
      transportAuth: StaticPeerTransportAuth,
      authenticatedPeer: PeerIdentity,
      requestTimeout: Duration = DefaultRequestTimeout,
      maxConcurrentRequests: Int = DefaultMaxConcurrentRequests,
      bootstrapRequestTimeout: Duration =
        HotStuffBootstrapPeerClient.DefaultRequestTimeout,
      bootstrapMaxConcurrentRequests: Int =
        HotStuffBootstrapPeerClient.DefaultMaxConcurrentRequests,
      proposalCatchUpReadiness: Option[ProposalCatchUpReadiness[F]] = None,
  ): Resource[
    F,
    HotStuffPeerTransportClient[F, HotStuffPeerArtifact[A]],
  ] =
    resource[F, HotStuffPeerArtifact[A]](
      peerBaseUris = peerBaseUris,
      transportAuth = transportAuth,
      authenticatedPeer = authenticatedPeer,
      requestTimeout = requestTimeout,
      maxConcurrentRequests = maxConcurrentRequests,
      bootstrapRequestTimeout = bootstrapRequestTimeout,
      bootstrapMaxConcurrentRequests = bootstrapMaxConcurrentRequests,
      proposalCatchUpReadiness = proposalCatchUpReadiness,
    )

  def resource[F[_]: Async, A: ByteDecoder](
      peerBaseUris: Map[PeerIdentity, URI],
      transportAuth: StaticPeerTransportAuth,
      authenticatedPeer: PeerIdentity,
      requestTimeout: Duration = DefaultRequestTimeout,
      maxConcurrentRequests: Int = DefaultMaxConcurrentRequests,
      bootstrapRequestTimeout: Duration =
        HotStuffBootstrapPeerClient.DefaultRequestTimeout,
      bootstrapMaxConcurrentRequests: Int =
        HotStuffBootstrapPeerClient.DefaultMaxConcurrentRequests,
      proposalCatchUpReadiness: Option[ProposalCatchUpReadiness[F]] = None,
  ): Resource[F, HotStuffPeerTransportClient[F, A]] =
    Resource
      .eval(
        validateConfig[F](
          requestTimeout = requestTimeout,
          maxConcurrentRequests = maxConcurrentRequests,
          bootstrapRequestTimeout = bootstrapRequestTimeout,
          bootstrapMaxConcurrentRequests = bootstrapMaxConcurrentRequests,
        ),
      )
      .productR:
        ArmeriaCatsBackend
          .resource[F]()
          .evalMap: backend =>
            assembleWithBackend(
              peerBaseUris = peerBaseUris,
              transportAuth = transportAuth,
              authenticatedPeer = authenticatedPeer,
              backend = backend,
              requestTimeout = requestTimeout,
              maxConcurrentRequests = maxConcurrentRequests,
              bootstrapRequestTimeout = bootstrapRequestTimeout,
              bootstrapMaxConcurrentRequests = bootstrapMaxConcurrentRequests,
              proposalCatchUpReadiness = proposalCatchUpReadiness,
            )

  private def assembleWithBackend[F[_]: Async, A: ByteDecoder](
      peerBaseUris: Map[PeerIdentity, URI],
      transportAuth: StaticPeerTransportAuth,
      authenticatedPeer: PeerIdentity,
      backend: Backend[F],
      requestTimeout: Duration,
      maxConcurrentRequests: Int,
      bootstrapRequestTimeout: Duration,
      bootstrapMaxConcurrentRequests: Int,
      proposalCatchUpReadiness: Option[ProposalCatchUpReadiness[F]],
  ): F[HotStuffPeerTransportClient[F, A]] =
    // maxConcurrentRequests is an aggregate outbound gossip cap shared by every
    // peer client assembled here. Bootstrap has its own independent cap.
    Semaphore[F](maxConcurrentRequests.toLong).map: requestGate =>
      val gossipPeers =
        peerBaseUris.map: (peerIdentity, baseUri) =>
          peerIdentity -> TxGossipPeerClient[F, A](
            baseUri = baseUri,
            transportAuth = transportAuth,
            authenticatedPeer = authenticatedPeer,
            backend = backend,
            requestGate = requestGate,
            requestTimeout = requestTimeout,
          )
      HotStuffPeerTransportClient(
        gossipPeers = gossipPeers,
        bootstrap = HotStuffBootstrapPeerClient.servicesWithBackend[F](
          peerBaseUris = peerBaseUris,
          transportAuth = transportAuth,
          backend = backend,
          requestTimeout = bootstrapRequestTimeout,
          maxConcurrentRequests = bootstrapMaxConcurrentRequests,
          proposalCatchUpReadiness = proposalCatchUpReadiness,
        ),
      )

  private def validateConfig[F[_]: Async](
      requestTimeout: Duration,
      maxConcurrentRequests: Int,
      bootstrapRequestTimeout: Duration,
      bootstrapMaxConcurrentRequests: Int,
  ): F[Unit] =
    Async[F].delay:
      require(
        requestTimeout.compareTo(Duration.ZERO) > 0,
        "requestTimeout must be positive",
      )
      require(
        maxConcurrentRequests > 0,
        "maxConcurrentRequests must be positive",
      )
      require(
        bootstrapRequestTimeout.compareTo(Duration.ZERO) > 0,
        "bootstrapRequestTimeout must be positive",
      )
      require(
        bootstrapMaxConcurrentRequests > 0,
        "bootstrapMaxConcurrentRequests must be positive",
      )
