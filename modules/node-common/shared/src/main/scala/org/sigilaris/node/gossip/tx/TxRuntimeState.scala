package org.sigilaris.node.gossip.tx

import java.time.{Duration, Instant}

import cats.effect.kernel.{Ref, Sync}
import cats.syntax.all.*

import org.sigilaris.node.gossip.*

/** Transaction-specific batching configuration implementing
  * `GossipProducerQoS`.
  *
  * @param maxBatchItems
  *   maximum number of items per batch
  * @param flushInterval
  *   interval after which a partial batch should be flushed
  */
final case class TxBatchingConfig(
    maxBatchItems: Int,
    flushInterval: Duration,
) extends GossipProducerQoS

/** Companion for `TxBatchingConfig`. */
object TxBatchingConfig:

  /** Default batching config with 128 items and 1-second flush interval. */
  val default: TxBatchingConfig =
    TxBatchingConfig(
      maxBatchItems = 128,
      flushInterval = Duration.ofSeconds(1),
    )

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
/** Policy governing transaction gossip runtime limits and behavior.
  *
  * @param maxTxSetKnownEntries
  *   maximum number of entries in a SetKnownTx set
  * @param maxTxRequestIds
  *   maximum number of ids in a RequestByIdTx operation
  * @param maxExactRequestRetriesPerScope
  *   optional cap on exact-scope request retries
  * @param controlRetryHorizonMultiplier
  *   multiplier applied to maxControlRetryInterval for idempotency key
  *   retention
  * @param supportedBloomHashFamilyId
  *   the supported Bloom filter hash family identifier
  * @param defaultBatchingConfig
  *   default batching config for new sessions
  */
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

  /** Computes the control retry horizon for idempotency key retention.
    *
    * @param negotiated
    *   the negotiated session parameters
    * @return
    *   the retry horizon duration
    */
  def controlRetryHorizon(
      negotiated: NegotiatedSessionParameters,
  ): Duration =
    negotiated.maxControlRetryInterval.multipliedBy(
      controlRetryHorizonMultiplier.toLong,
    )

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
/** Extended producer session state for the transaction gossip runtime, tracking
  * filters, known ids, pending requests, and idempotency keys.
  *
  * @param sessionId
  *   the directional session identifier
  * @param peer
  *   the remote peer identity
  * @param peerCorrelationId
  *   the peer correlation id
  * @param subscriptions
  *   the session subscriptions
  * @param negotiated
  *   the negotiated timing parameters
  * @param durableCursor
  *   the consumer-persisted cursor
  * @param streamCursor
  *   the cursor tracking most recently emitted events
  * @param filters
  *   per-chain Bloom filters for deduplication
  * @param exactKnownIds
  *   per-chain exact known artifact ids
  * @param exactKnownScopeIds
  *   per-scope exact known artifact ids
  * @param pendingReplay
  *   chain-topics with pending Nack replay requests
  * @param pendingRequestByIds
  *   per-chain pending request-by-id artifact ids
  * @param pendingRequestScopeIds
  *   per-scope pending request-by-id artifact ids
  * @param requestScopeRetryCounts
  *   per-scope request retry counts
  * @param idempotencyKeys
  *   recently applied control batch idempotency keys with timestamps
  * @param batchingConfig
  *   the current batching configuration
  */
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

  /** Extracts the base `GossipProducerSessionState` from this extended state.
    *
    * @return
    *   the base producer session state
    */
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

  /** Updates the cursor and replay state from a base producer session state.
    *
    * @param producerState
    *   the base producer state to merge from
    * @return
    *   the updated state
    */
  def withProducerState(
      producerState: GossipProducerSessionState,
  ): TxProducerSessionState =
    copy(
      durableCursor = producerState.durableCursor,
      streamCursor = producerState.streamCursor,
      pendingReplay = producerState.pendingReplay,
    )

/** Combined runtime state holding the session engine and outbound session
  * states.
  *
  * @param engine
  *   the gossip session engine
  * @param outboundSessions
  *   the active outbound producer session states
  */
final case class TxGossipRuntimeState(
    engine: GossipSessionEngine,
    outboundSessions: Map[DirectionalSessionId, TxProducerSessionState],
):

  /** Returns the outbound session state for the given session id, if present.
    *
    * @param sessionId
    *   the session id
    * @return
    *   the session state, or None
    */
  def outboundSession(
      sessionId: DirectionalSessionId,
  ): Option[TxProducerSessionState] =
    outboundSessions.get(sessionId)

/** Mutable store for the transaction gossip runtime state.
  *
  * @tparam F
  *   the effect type
  */
trait TxGossipStateStore[F[_]]:

  /** Returns the current runtime state.
    *
    * @return
    *   the current state
    */
  def get: F[TxGossipRuntimeState]

  /** Atomically modifies the state and returns a derived value.
    *
    * @tparam A
    *   the result type
    * @param f
    *   the state transformation function
    * @return
    *   the derived value
    */
  def modify[A](f: TxGossipRuntimeState => (TxGossipRuntimeState, A)): F[A]

/** Companion for `TxGossipStateStore` providing factory methods. */
object TxGossipStateStore:

  /** Creates an in-memory state store backed by a Ref.
    *
    * @tparam F
    *   the effect type
    * @param engine
    *   the initial session engine state
    * @return
    *   a new in-memory state store
    */
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
