package org.sigilaris.node.gossip.tx

import java.time.Duration

import cats.syntax.all.*

import org.sigilaris.node.gossip.*

/** Outcome of processing a control batch. */
enum ControlBatchOutcome:

  /** The batch was freshly applied. */
  case Applied

  /** The batch was deduplicated via its idempotency key. */
  case Deduplicated

/** Optional overrides for session negotiation parameters.
  *
  * @param heartbeatInterval
  *   optional heartbeat interval override
  * @param livenessTimeout
  *   optional liveness timeout override
  * @param maxControlRetryInterval
  *   optional max control retry interval override
  */
final case class TxSessionNegotiationOverrides(
    heartbeatInterval: Option[Duration],
    livenessTimeout: Option[Duration],
    maxControlRetryInterval: Option[Duration],
)

/** Companion for `TxSessionNegotiationOverrides`. */
object TxSessionNegotiationOverrides:

  /** Default overrides with no values set (uses policy defaults). */
  val default: TxSessionNegotiationOverrides =
    TxSessionNegotiationOverrides(
      heartbeatInterval = None,
      livenessTimeout = None,
      maxControlRetryInterval = None,
    )

/** Result of receiving and applying a batch of inbound events.
  *
  * @tparam A
  *   the artifact payload type
  * @param applied
  *   events that were newly applied
  * @param duplicates
  *   events that were duplicates
  * @param lastCursor
  *   the cursor of the last processed event, if any
  */
final case class TxReceiveEventsResult[A](
    applied: Vector[GossipEvent[A]],
    duplicates: Vector[GossipEvent[A]],
    lastCursor: Option[CursorToken],
)

/** Strategy for selecting which live events to deliver based on peer-declared
  * filters and known ids.
  *
  * @tparam A
  *   the artifact payload type
  */
trait TxCascadeStrategy[A]:

  /** Selects live events from candidates, filtering out known and
    * Bloom-matched artifacts.
    *
    * @param filter
    *   optional Bloom filter from the peer
    * @param exactKnownIds
    *   exact known artifact ids from the peer
    * @param candidates
    *   the candidate events to filter
    * @return
    *   the selected events, or a backfill-unavailable rejection
    */
  def selectLiveEvents(
      filter: Option[GossipFilter.TxBloomFilter],
      exactKnownIds: Set[StableArtifactId],
      candidates: Vector[GossipEvent[A]],
  ): Either[CanonicalRejection.BackfillUnavailable, Vector[GossipEvent[A]]]

/** Companion for `TxCascadeStrategy` providing default implementations. */
object TxCascadeStrategy:

  /** Creates a cascade strategy that requires exact known ids for
    * Bloom-ambiguous events, rejecting with backfill-unavailable otherwise.
    *
    * @tparam A
    *   the artifact payload type
    * @return
    *   the cascade strategy
    */
  def exactKnownOrBackfillUnavailable[A]: TxCascadeStrategy[A] =
    new TxCascadeStrategy[A]:
      override def selectLiveEvents(
          filter: Option[GossipFilter.TxBloomFilter],
          exactKnownIds: Set[StableArtifactId],
          candidates: Vector[GossipEvent[A]],
      ): Either[CanonicalRejection.BackfillUnavailable, Vector[
        GossipEvent[A],
      ]] =
        filter match
          case None =>
            candidates
              .filterNot(event => exactKnownIds.contains(event.id))
              .asRight[CanonicalRejection.BackfillUnavailable]
          case Some(bloomFilter) =>
            val unresolved =
              candidates.filter: event =>
                TxBloomFilterSupport.mightContain(bloomFilter, event.id) &&
                  !exactKnownIds.contains(event.id)
            Either.cond(
              unresolved.isEmpty,
              candidates.filterNot: event =>
                exactKnownIds.contains(event.id) || TxBloomFilterSupport
                  .mightContain(bloomFilter, event.id),
              CanonicalRejection.BackfillUnavailable(
                reason = "txBackfillUnavailable",
                detail = Some(unresolved.map(_.id.toHexLower).mkString(",")),
              ),
            )
