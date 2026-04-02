package org.sigilaris.node.jvm.runtime.gossip

import java.time.{Duration, Instant}

trait GossipProducerQoS:
  def maxBatchItems: Int
  def flushInterval: Duration

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
  def startCursorFor(
      chainTopic: ChainTopic,
  ): Option[CursorToken] =
    pendingReplay
      .get(chainTopic)
      .flatten
      .orElse(streamCursor.tokenFor(chainTopic))
      .orElse(durableCursor.tokenFor(chainTopic))

  def withDurableCursor(
      cursor: CompositeCursor,
  ): GossipProducerSessionState =
    copy(
      durableCursor = CompositeCursor(durableCursor.values ++ cursor.values),
    )

  def withReplay(
      chainTopic: ChainTopic,
      cursor: Option[CursorToken],
  ): GossipProducerSessionState =
    copy(
      pendingReplay = pendingReplay.updated(chainTopic, cursor),
    )

  def clearReplay(
      chainTopic: ChainTopic,
  ): GossipProducerSessionState =
    copy(pendingReplay = pendingReplay - chainTopic)

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

object GossipProducerPolling:
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
