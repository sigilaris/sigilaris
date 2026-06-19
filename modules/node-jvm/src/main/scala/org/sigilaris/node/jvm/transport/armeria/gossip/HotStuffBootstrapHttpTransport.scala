package org.sigilaris.node.jvm.transport.armeria.gossip

import java.net.URI
import java.time.Duration

import cats.effect.{Async, Resource}
import sttp.client4.armeria.cats.ArmeriaCatsBackend

import org.sigilaris.node.gossip.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*

/** Compatibility facade for HotStuff bootstrap transport services.
  *
  * New code should prefer `servicesResource`, which owns the Armeria client
  * backend lifecycle. The value-returning `services` method uses Armeria's
  * default client and remains for call sites that cannot yet thread a
  * `Resource`.
  */
object HotStuffBootstrapHttpTransport:
  /** Default timeout for individual bootstrap HTTP requests. */
  val DefaultRequestTimeout: Duration =
    HotStuffBootstrapPeerClient.DefaultRequestTimeout

  /** Default maximum number of concurrent outbound bootstrap requests. */
  val DefaultMaxConcurrentRequests: Int =
    HotStuffBootstrapPeerClient.DefaultMaxConcurrentRequests

  /** Creates bootstrap transport services backed by endpoint-derived Armeria
    * clients.
    */
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def servicesResource[F[_]: Async](
      peerBaseUris: Map[PeerIdentity, URI],
      transportAuth: StaticPeerTransportAuth,
      requestTimeout: Duration = DefaultRequestTimeout,
      maxConcurrentRequests: Int = DefaultMaxConcurrentRequests,
      proposalCatchUpReadiness: Option[ProposalCatchUpReadiness[F]] = None,
  ): Resource[F, HotStuffBootstrapTransportServices[F]] =
    HotStuffBootstrapPeerClient.servicesResource(
      peerBaseUris = peerBaseUris,
      transportAuth = transportAuth,
      requestTimeout = requestTimeout,
      maxConcurrentRequests = maxConcurrentRequests,
      proposalCatchUpReadiness = proposalCatchUpReadiness,
    )

  /** Creates bootstrap transport services using Armeria's default client.
    *
    * This facade is retained for compatibility. Prefer `servicesResource` when
    * the caller owns the transport lifecycle.
    */
  @deprecated(
    "Use servicesResource for owned Armeria backend lifecycle.",
    "0.2.5",
  )
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def services[F[_]: Async](
      peerBaseUris: Map[PeerIdentity, URI],
      transportAuth: StaticPeerTransportAuth,
      requestTimeout: Duration = DefaultRequestTimeout,
      maxConcurrentRequests: Int = DefaultMaxConcurrentRequests,
      proposalCatchUpReadiness: Option[ProposalCatchUpReadiness[F]] = None,
  ): HotStuffBootstrapTransportServices[F] =
    HotStuffBootstrapPeerClient.servicesWithBackend(
      peerBaseUris = peerBaseUris,
      transportAuth = transportAuth,
      backend = ArmeriaCatsBackend.usingDefaultClient[F](),
      requestTimeout = requestTimeout,
      maxConcurrentRequests = maxConcurrentRequests,
      proposalCatchUpReadiness = proposalCatchUpReadiness,
    )
