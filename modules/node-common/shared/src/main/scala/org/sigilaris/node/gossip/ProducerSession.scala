package org.sigilaris.node.gossip

import java.time.{Duration, Instant}

/** Quality-of-service settings for gossip event producer batching. */
trait GossipProducerQoS:

  /** @return maximum number of items per batch */
  def maxBatchItems: Int

  /** @return interval after which a partial batch should be flushed */
  def flushInterval: Duration

/** Mutable state of a producer-side gossip session, tracking cursors, replays,
  * and stream progress.
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
  *   the cursor persisted by the consumer via SetCursor
  * @param streamCursor
  *   the cursor tracking the most recently emitted events
  * @param pendingReplay
  *   chain-topics with pending Nack replay requests
  */
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class GossipProducerSessionState(
    sessionId: DirectionalSessionId,
    peer: PeerIdentity,
    peerCorrelationId: PeerCorrelationId,
    subscriptions: SessionSubscription,
    negotiated: NegotiatedSessionParameters,
    durableCursor: CompositeCursor = CompositeCursor.empty,
    streamCursor: CompositeCursor = CompositeCursor.empty,
    pendingReplay: Map[ChainTopic, Option[CursorToken]] = Map.empty,
):

  /** Determines the starting cursor for a chain-topic, preferring pending
    * replay, then stream cursor, then durable cursor.
    *
    * @param chainTopic
    *   the chain-topic pair
    * @return
    *   the cursor to start reading from, or None for origin
    */
  def startCursorFor(
      chainTopic: ChainTopic,
  ): Option[CursorToken] =
    pendingReplay
      .get(chainTopic)
      .flatten
      .orElse(streamCursor.tokenFor(chainTopic))
      .orElse(durableCursor.tokenFor(chainTopic))

  /** Merges additional durable cursor entries into this state.
    *
    * @param cursor
    *   the cursor entries to merge
    * @return
    *   the updated state
    */
  def withDurableCursor(
      cursor: CompositeCursor,
  ): GossipProducerSessionState =
    copy(
      durableCursor = CompositeCursor(durableCursor.values ++ cursor.values),
    )

  /** Marks a chain-topic for replay from the given cursor.
    *
    * @param chainTopic
    *   the chain-topic pair to replay
    * @param cursor
    *   the cursor to replay from, or None for origin
    * @return
    *   the updated state
    */
  def withReplay(
      chainTopic: ChainTopic,
      cursor: Option[CursorToken],
  ): GossipProducerSessionState =
    copy(
      pendingReplay = pendingReplay.updated(chainTopic, cursor),
    )

  /** Clears the pending replay flag for a chain-topic.
    *
    * @param chainTopic
    *   the chain-topic pair to clear
    * @return
    *   the updated state
    */
  def clearReplay(
      chainTopic: ChainTopic,
  ): GossipProducerSessionState =
    copy(pendingReplay = pendingReplay - chainTopic)

  /** Advances the stream cursor to the last emitted event's cursor.
    *
    * @param chainTopic
    *   the chain-topic pair
    * @param emitted
    *   the events that were emitted (may be empty)
    * @return
    *   the updated state
    */
  def advanceStreamCursor(
      chainTopic: ChainTopic,
      emitted: Vector[GossipEvent[?]],
  ): GossipProducerSessionState =
    if emitted.isEmpty then this
    else
      emitted.lastOption.fold(this): lastEmitted =>
        copy(
          streamCursor = CompositeCursor(
            streamCursor.values.updated(chainTopic, lastEmitted.cursor),
          ),
        )

/** Utility for batching available gossip events according to QoS constraints.
  */
object GossipProducerPolling:

  /** Selects events from the candidate pool for delivery, respecting batch
    * size, flush interval, and limit.
    *
    * @tparam A
    *   the artifact payload type
    * @param now
    *   the current instant
    * @param candidates
    *   the available events to consider
    * @param qos
    *   the producer QoS settings
    * @param forceFlush
    *   if true, bypasses flush interval check
    * @param limit
    *   maximum number of events to return
    * @return
    *   the selected events for delivery
    */
  def batchAvailableEvents[A](
      now: Instant,
      candidates: Vector[AvailableGossipEvent[A]],
      qos: GossipProducerQoS,
      forceFlush: Boolean,
      limit: Int,
  ): Vector[GossipEvent[A]] =
    if candidates.isEmpty then Vector.empty
    else
      val threshold = qos.maxBatchItems.min(limit)
      candidates.headOption.fold(Vector.empty[GossipEvent[A]]): headCandidate =>
        if threshold <= 0 then Vector.empty
        else
          val flushByCount = candidates.sizeCompare(threshold) >= 0
          val flushByInterval =
            !now.isBefore(headCandidate.availableAt.plus(qos.flushInterval))
          if forceFlush || flushByCount || flushByInterval then
            candidates.take(threshold).map(_.event)
          else Vector.empty
