package org.sigilaris.node.jvm.transport.armeria.gossip

import java.net.URI
import java.time.Duration

import cats.effect.{Async, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.Backend
import sttp.client4.StreamBackend
import sttp.client4.armeria.cats.ArmeriaCatsBackend
import sttp.client4.armeria.fs2.ArmeriaFs2Backend

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
  val DefaultStreamRequestTimeout: Duration =
    TxGossipPeerClient.DefaultStreamRequestTimeout
  val DefaultStreamIdleTimeout: Duration =
    TxGossipPeerClient.DefaultStreamIdleTimeout
  val DefaultMaxConcurrentRequests: Int =
    TxGossipPeerClient.DefaultMaxConcurrentRequests

  def hotStuffOnlyResource[F[_]: Async](
      peerBaseUris: Map[PeerIdentity, URI],
      transportAuth: StaticPeerTransportAuth,
      authenticatedPeer: PeerIdentity,
      requestTimeout: Duration = DefaultRequestTimeout,
      streamRequestTimeout: Duration = DefaultStreamRequestTimeout,
      streamIdleTimeout: Duration = DefaultStreamIdleTimeout,
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
      streamRequestTimeout = streamRequestTimeout,
      streamIdleTimeout = streamIdleTimeout,
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
      streamRequestTimeout: Duration = DefaultStreamRequestTimeout,
      streamIdleTimeout: Duration = DefaultStreamIdleTimeout,
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
      streamRequestTimeout = streamRequestTimeout,
      streamIdleTimeout = streamIdleTimeout,
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
      streamRequestTimeout: Duration = DefaultStreamRequestTimeout,
      streamIdleTimeout: Duration = DefaultStreamIdleTimeout,
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
          streamRequestTimeout = streamRequestTimeout,
          streamIdleTimeout = streamIdleTimeout,
          maxConcurrentRequests = maxConcurrentRequests,
          bootstrapRequestTimeout = bootstrapRequestTimeout,
          bootstrapMaxConcurrentRequests = bootstrapMaxConcurrentRequests,
        ),
      )
      .productR:
        ArmeriaCatsBackend
          .resource[F]()
          .flatMap: backend =>
            ArmeriaFs2Backend
              .resource[F]()
              .evalMap: streamBackend =>
                assembleWithBackend(
                  peerBaseUris = peerBaseUris,
                  transportAuth = transportAuth,
                  authenticatedPeer = authenticatedPeer,
                  backend = backend,
                  streamBackend = streamBackend,
                  requestTimeout = requestTimeout,
                  streamRequestTimeout = streamRequestTimeout,
                  streamIdleTimeout = streamIdleTimeout,
                  maxConcurrentRequests = maxConcurrentRequests,
                  bootstrapRequestTimeout = bootstrapRequestTimeout,
                  bootstrapMaxConcurrentRequests =
                    bootstrapMaxConcurrentRequests,
                  proposalCatchUpReadiness = proposalCatchUpReadiness,
                )

  private def assembleWithBackend[F[_]: Async, A: ByteDecoder](
      peerBaseUris: Map[PeerIdentity, URI],
      transportAuth: StaticPeerTransportAuth,
      authenticatedPeer: PeerIdentity,
      backend: Backend[F],
      streamBackend: StreamBackend[F, Fs2Streams[F]],
      requestTimeout: Duration,
      streamRequestTimeout: Duration,
      streamIdleTimeout: Duration,
      maxConcurrentRequests: Int,
      bootstrapRequestTimeout: Duration,
      bootstrapMaxConcurrentRequests: Int,
      proposalCatchUpReadiness: Option[ProposalCatchUpReadiness[F]],
  ): F[HotStuffPeerTransportClient[F, A]] =
    // Finite requests keep an aggregate outbound cap. Streams get per-peer
    // gates so a long-lived stream to one peer cannot block opening another
    // peer's stream.
    Semaphore[F](maxConcurrentRequests.toLong).flatMap: requestGate =>
      peerBaseUris.toVector
        .traverse: (peerIdentity, baseUri) =>
          Semaphore[F](maxConcurrentRequests.toLong).map: streamGate =>
            peerIdentity -> TxGossipPeerClient[F, A](
              baseUri = baseUri,
              transportAuth = transportAuth,
              authenticatedPeer = authenticatedPeer,
              backend = backend,
              streamBackend = streamBackend,
              requestGate = requestGate,
              streamGate = streamGate,
              requestTimeout = requestTimeout,
              streamRequestTimeout = streamRequestTimeout,
              streamIdleTimeout = streamIdleTimeout,
            )
        .map: gossipPeers =>
          HotStuffPeerTransportClient(
            gossipPeers = gossipPeers.toMap,
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
      streamRequestTimeout: Duration,
      streamIdleTimeout: Duration,
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
        !streamRequestTimeout.isNegative,
        "streamRequestTimeout must be zero or positive",
      )
      require(
        !streamIdleTimeout.isNegative,
        "streamIdleTimeout must be zero or positive",
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
