package org.sigilaris.node.jvm.runtime.gossip.tx

import java.time.{Duration, Instant}

import cats.effect.kernel.{Ref, Sync}
import cats.syntax.all.*

import org.sigilaris.node.jvm.runtime.gossip.*

final case class TxBatchingConfig(
    maxBatchItems: Int,
    flushInterval: Duration,
) extends GossipProducerQoS

object TxBatchingConfig:
  val default: TxBatchingConfig =
    TxBatchingConfig(
      maxBatchItems = 128,
      flushInterval = Duration.ofSeconds(1),
    )

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class TxRuntimePolicy(
    maxTxSetKnownEntries: Int = 4096,
    maxTxRequestIds: Int = 1024,
    maxExactRequestRetriesPerScope: Option[Int] = None,
    controlRetryHorizonMultiplier: Int = 2,
    supportedBloomHashFamilyId: String =
      TxBloomFilterSupport.SupportedHashFamilyId,
    defaultBatchingConfig: TxBatchingConfig = TxBatchingConfig.default,
):
  maxExactRequestRetriesPerScope.foreach: limit =>
    require(limit >= 0, "maxExactRequestRetriesPerScope must be non-negative")
  require(
    controlRetryHorizonMultiplier >= 2 && controlRetryHorizonMultiplier <= 10,
    "controlRetryHorizonMultiplier must be within [2, 10]",
  )

  def controlRetryHorizon(
      negotiated: NegotiatedSessionParameters,
  ): Duration =
    negotiated.maxControlRetryInterval.multipliedBy(
      controlRetryHorizonMultiplier.toLong,
    )

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class TxProducerSessionState(
    sessionId: DirectionalSessionId,
    peer: PeerIdentity,
    peerCorrelationId: PeerCorrelationId,
    subscriptions: SessionSubscription,
    negotiated: NegotiatedSessionParameters,
    durableCursor: CompositeCursor = CompositeCursor.empty,
    streamCursor: CompositeCursor = CompositeCursor.empty,
    filters: Map[ChainId, GossipFilter.TxBloomFilter] = Map.empty,
    exactKnownIds: Map[ChainId, Set[StableArtifactId]] = Map.empty,
    exactKnownScopeIds: Map[ExactKnownSetScope, Set[StableArtifactId]] =
      Map.empty,
    pendingReplay: Map[ChainTopic, Option[CursorToken]] = Map.empty,
    pendingRequestByIds: Map[ChainId, Vector[StableArtifactId]] = Map.empty,
    pendingRequestScopeIds: Map[ExactKnownSetScope, Vector[StableArtifactId]] =
      Map.empty,
    requestScopeRetryCounts: Map[ExactKnownSetScope, Int] = Map.empty,
    idempotencyKeys: Map[ControlIdempotencyKey, Instant] = Map.empty,
    batchingConfig: TxBatchingConfig = TxBatchingConfig.default,
):
  def producerState: GossipProducerSessionState =
    GossipProducerSessionState(
      sessionId = sessionId,
      peer = peer,
      peerCorrelationId = peerCorrelationId,
      subscriptions = subscriptions,
      negotiated = negotiated,
      durableCursor = durableCursor,
      streamCursor = streamCursor,
      pendingReplay = pendingReplay,
    )

  def withProducerState(
      producerState: GossipProducerSessionState,
  ): TxProducerSessionState =
    copy(
      durableCursor = producerState.durableCursor,
      streamCursor = producerState.streamCursor,
      pendingReplay = producerState.pendingReplay,
    )

final case class TxGossipRuntimeState(
    engine: GossipSessionEngine,
    outboundSessions: Map[DirectionalSessionId, TxProducerSessionState],
):
  def outboundSession(
      sessionId: DirectionalSessionId,
  ): Option[TxProducerSessionState] =
    outboundSessions.get(sessionId)

trait TxGossipStateStore[F[_]]:
  def get: F[TxGossipRuntimeState]
  def modify[A](f: TxGossipRuntimeState => (TxGossipRuntimeState, A)): F[A]

object TxGossipStateStore:
  def inMemory[F[_]: Sync](
      engine: GossipSessionEngine,
  ): F[TxGossipStateStore[F]] =
    Ref
      .of[F, TxGossipRuntimeState](TxGossipRuntimeState(engine, Map.empty))
      .map(InMemoryTxGossipStateStore[F](_))

private final class InMemoryTxGossipStateStore[F[_]](
    ref: Ref[F, TxGossipRuntimeState],
) extends TxGossipStateStore[F]:
  override def get: F[TxGossipRuntimeState] =
    ref.get

  override def modify[A](
      f: TxGossipRuntimeState => (TxGossipRuntimeState, A),
  ): F[A] =
    ref.modify(f)
