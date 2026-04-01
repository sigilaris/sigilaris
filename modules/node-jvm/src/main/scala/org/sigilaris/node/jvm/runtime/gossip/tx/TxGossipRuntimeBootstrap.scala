package org.sigilaris.node.jvm.runtime.gossip.tx

import cats.effect.kernel.Sync
import cats.syntax.all.*

import com.typesafe.config.Config

import org.sigilaris.node.jvm.runtime.gossip.*

final case class TxGossipBootstrap[F[_], A](
    topology: StaticPeerTopology,
    registry: StaticPeerRegistry,
    authenticator: StaticPeerAuthenticator[F],
    runtime: TxGossipRuntime[F, A],
)

object TxGossipRuntimeBootstrap:
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
        Sync[F].pure(Left(error))
      case Right(topology) =>
        fromTopology(
          topology = topology,
          clock = clock,
          source = source,
          sink = sink,
          topicContracts = topicContracts,
          runtimePolicy = runtimePolicy,
          handshakePolicy = handshakePolicy,
        ).map(Right(_))

  def fromTopology[F[_]: Sync, A](
      topology: StaticPeerTopology,
      clock: GossipClock[F],
      source: GossipArtifactSource[F, A],
      sink: GossipArtifactSink[F, A],
      topicContracts: GossipTopicContractRegistry[A],
      runtimePolicy: TxRuntimePolicy = TxRuntimePolicy(),
      handshakePolicy: HandshakePolicy = HandshakePolicy.default,
  ): F[TxGossipBootstrap[F, A]] =
    val registry = StaticPeerRegistry(topology)
    val authenticator = StaticPeerAuthenticator[F](registry)
    TxGossipStateStore
      .inMemory[F](GossipSessionEngine(registry.localPeer, topology, policy = handshakePolicy))
      .map: stateStore =>
        TxGossipBootstrap(
          topology = topology,
          registry = registry,
          authenticator = authenticator,
          runtime = TxGossipRuntime[F, A](
            peerAuthenticator = authenticator,
            clock = clock,
            source = source,
            sink = sink,
            topicContracts = topicContracts,
            stateStore = stateStore,
            policy = runtimePolicy,
          ),
        )
