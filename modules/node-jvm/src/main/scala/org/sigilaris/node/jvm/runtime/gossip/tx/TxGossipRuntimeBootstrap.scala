package org.sigilaris.node.jvm.runtime.gossip.tx

import cats.effect.kernel.Sync
import cats.syntax.all.*

import com.typesafe.config.Config

import org.sigilaris.node.gossip.*
import org.sigilaris.node.gossip.tx.*
import org.sigilaris.node.jvm.runtime.gossip.{
  StaticPeerTopologyConfig,
  StaticPeerTransportAuthConfig,
}

/** Assembled bootstrap bundle containing all components needed to run
  * transaction gossip.
  *
  * @tparam F
  *   the effect type
  * @tparam A
  *   the artifact payload type
  * @param topology
  *   the static peer topology
  * @param registry
  *   the peer registry
  * @param authenticator
  *   the peer authenticator
  * @param transportAuth
  *   the transport authentication configuration
  * @param runtime
  *   the transaction gossip runtime
  */
final case class TxGossipBootstrap[F[_], A](
    topology: StaticPeerTopology,
    registry: StaticPeerRegistry,
    authenticator: StaticPeerAuthenticator[F],
    transportAuth: StaticPeerTransportAuth,
    runtime: TxGossipRuntime[F, A],
)

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
/** Factory for bootstrapping a `TxGossipRuntime` from configuration or
  * topology.
  */
object TxGossipRuntimeBootstrap:

  /** Bootstraps a transaction gossip runtime from Typesafe Config.
    *
    * @tparam F
    *   the effect type
    * @tparam A
    *   the artifact payload type
    * @param config
    *   the root config
    * @param clock
    *   the gossip clock
    * @param source
    *   the artifact source
    * @param sink
    *   the artifact sink
    * @param topicContracts
    *   the topic contract registry
    * @param runtimePolicy
    *   the runtime policy
    * @param handshakePolicy
    *   the handshake policy
    * @param configPath
    *   the config path to read topology from
    * @return
    *   the bootstrap bundle, or an error
    */
  def fromConfig[F[_]: Sync, A](
      config: Config,
      clock: GossipClock[F],
      source: GossipArtifactSource[F, A],
      sink: GossipArtifactSink[F, A],
      topicContracts: GossipTopicContractRegistry[A],
      runtimePolicy: TxRuntimePolicy = TxRuntimePolicy(),
      handshakePolicy: HandshakePolicy = HandshakePolicy.default,
      configPath: String = StaticPeerTopologyConfig.DefaultPath,
  ): F[Either[String, TxGossipBootstrap[F, A]]] =
    StaticPeerTopologyConfig.load(config, configPath) match
      case Left(error) =>
        Sync[F].pure(error.asLeft[TxGossipBootstrap[F, A]])
      case Right(topology) =>
        StaticPeerTransportAuthConfig.load(config, topology, configPath) match
          case Left(error) =>
            Sync[F].pure(error.asLeft[TxGossipBootstrap[F, A]])
          case Right(transportAuth) =>
            fromTopology(
              topology = topology,
              transportAuth = transportAuth,
              clock = clock,
              source = source,
              sink = sink,
              topicContracts = topicContracts,
              runtimePolicy = runtimePolicy,
              handshakePolicy = handshakePolicy,
            ).map(_.asRight[String])

  /** Bootstraps a transaction gossip runtime from a pre-parsed topology.
    *
    * @tparam F
    *   the effect type
    * @tparam A
    *   the artifact payload type
    * @param topology
    *   the static peer topology
    * @param transportAuth
    *   the transport authentication configuration
    * @param clock
    *   the gossip clock
    * @param source
    *   the artifact source
    * @param sink
    *   the artifact sink
    * @param topicContracts
    *   the topic contract registry
    * @param runtimePolicy
    *   the runtime policy
    * @param handshakePolicy
    *   the handshake policy
    * @return
    *   the bootstrap bundle
    */
  def fromTopology[F[_]: Sync, A](
      topology: StaticPeerTopology,
      transportAuth: StaticPeerTransportAuth,
      clock: GossipClock[F],
      source: GossipArtifactSource[F, A],
      sink: GossipArtifactSink[F, A],
      topicContracts: GossipTopicContractRegistry[A],
      runtimePolicy: TxRuntimePolicy = TxRuntimePolicy(),
      handshakePolicy: HandshakePolicy = HandshakePolicy.default,
  ): F[TxGossipBootstrap[F, A]] =
    val registry      = StaticPeerRegistry(topology)
    val authenticator = StaticPeerAuthenticator[F](registry)
    TxGossipStateStore
      .inMemory[F](
        GossipSessionEngine(
          registry.localPeer,
          topology,
          policy = handshakePolicy,
        ),
      )
      .map: stateStore =>
        TxGossipBootstrap(
          topology = topology,
          registry = registry,
          authenticator = authenticator,
          transportAuth = transportAuth,
          runtime = TxGossipRuntime.withPolicy[F, A](
            peerAuthenticator = authenticator,
            clock = clock,
            source = source,
            sink = sink,
            topicContracts = topicContracts,
            stateStore = stateStore,
            policy = runtimePolicy,
          ),
        )
