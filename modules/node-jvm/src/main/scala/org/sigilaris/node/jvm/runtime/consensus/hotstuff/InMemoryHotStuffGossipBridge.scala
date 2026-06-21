package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.nio.ByteBuffer
import java.time.Instant

import cats.effect.kernel.{Ref, Sync}
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.crypto.Hash
import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.jvm.runtime.block.{BlockHeight, BlockId, BlockQuery}
import org.sigilaris.node.gossip.*

final case class HotStuffArtifactSourceRetention private (
    retainedEventsPerTopic: Int,
)

object HotStuffArtifactSourceRetention:
  def fromRetainedEventsPerTopic(
      value: Int,
  ): Either[String, HotStuffArtifactSourceRetention] =
    Either.cond(
      value > 0,
      new HotStuffArtifactSourceRetention(value),
      "retainedEventsPerTopic must be positive",
    )

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def unsafe(
      retainedEventsPerTopic: Int,
  ): HotStuffArtifactSourceRetention =
    fromRetainedEventsPerTopic(retainedEventsPerTopic) match
      case Right(retention) => retention
      case Left(error)      => throw new IllegalArgumentException(error)

  val default: HotStuffArtifactSourceRetention =
    unsafe(retainedEventsPerTopic = 4096)

final case class InMemoryHotStuffSourceDiagnostics(
    retainedEventsPerTopic: Int,
    retainedEventsByTopic: Map[ChainTopic, Int],
    appendedEventsByTopic: Map[ChainTopic, Long],
    prunedEventsByTopic: Map[ChainTopic, Long],
    readByIdMissesByTopic: Map[ChainTopic, Long],
    invalidCursorRejectionsByTopic: Map[ChainTopic, Long],
    staleCursorRejectionsByTopic: Map[ChainTopic, Long],
)

final case class InMemoryHotStuffSourceSnapshot(
    eventsByTopic: Map[ChainTopic, Vector[GossipEvent[HotStuffGossipArtifact]]],
    diagnostics: InMemoryHotStuffSourceDiagnostics,
)

final case class InMemoryHotStuffRelayRejectionKey(
    topic: GossipTopic,
    reason: String,
)

final case class InMemoryHotStuffSinkDiagnostics(
    policyMode: String,
    relayedValidatedArtifactsByTopic: Map[GossipTopic, Long],
    duplicateArtifactsSuppressedByTopic: Map[GossipTopic, Long],
    rejectedArtifactsByTopicAndReason:
      Map[InMemoryHotStuffRelayRejectionKey, Long],
):
  def recordRelay(
      topic: GossipTopic,
  ): InMemoryHotStuffSinkDiagnostics =
    copy(
      relayedValidatedArtifactsByTopic =
        increment(relayedValidatedArtifactsByTopic, topic),
    )

  def recordDuplicate(
      topic: GossipTopic,
  ): InMemoryHotStuffSinkDiagnostics =
    copy(
      duplicateArtifactsSuppressedByTopic =
        increment(duplicateArtifactsSuppressedByTopic, topic),
    )

  def recordRejection(
      topic: GossipTopic,
      reason: String,
  ): InMemoryHotStuffSinkDiagnostics =
    val key = InMemoryHotStuffRelayRejectionKey(topic, reason)
    copy(
      rejectedArtifactsByTopicAndReason =
        increment(rejectedArtifactsByTopicAndReason, key),
    )

  private def increment[A](
      values: Map[A, Long],
      key: A,
  ): Map[A, Long] =
    values.updatedWith(key)(_.map(_ + 1L).orElse(Some(1L)))

object InMemoryHotStuffSinkDiagnostics:
  def empty(
      relayPolicy: HotStuffRelayPolicy,
  ): InMemoryHotStuffSinkDiagnostics =
    InMemoryHotStuffSinkDiagnostics(
      policyMode = relayPolicy.mode,
      relayedValidatedArtifactsByTopic = Map.empty[GossipTopic, Long],
      duplicateArtifactsSuppressedByTopic = Map.empty[GossipTopic, Long],
      rejectedArtifactsByTopicAndReason =
        Map.empty[InMemoryHotStuffRelayRejectionKey, Long],
    )

/** A snapshot of the in-memory gossip artifact sink state, including all stored
  * artifacts and QCs.
  */
final case class InMemoryHotStuffSinkSnapshot(
    proposals: Map[ProposalId, Proposal],
    votes: Map[VoteId, Vote],
    accumulator: VoteAccumulator,
    timeoutVotes: Map[TimeoutVoteId, TimeoutVote],
    timeoutAccumulator: TimeoutVoteAccumulator,
    timeoutCertificates: Map[TimeoutVoteSubject, TimeoutCertificate],
    newViews: Map[NewViewId, NewView],
    newViewsBySenderWindow: Map[(HotStuffWindow, ValidatorId), NewView],
    qcs: Map[ProposalId, QuorumCertificate],
    finalization: Map[ChainId, FinalizationTrackerSnapshot],
    duplicates: Vector[GossipEvent[HotStuffGossipArtifact]],
    diagnostics: InMemoryHotStuffSinkDiagnostics,
):
  def recordRelay(
      topic: GossipTopic,
  ): InMemoryHotStuffSinkSnapshot =
    copy(diagnostics = diagnostics.recordRelay(topic))

  def recordDuplicate(
      event: GossipEvent[HotStuffGossipArtifact],
  ): InMemoryHotStuffSinkSnapshot =
    copy(
      duplicates = duplicates :+ event,
      diagnostics = diagnostics.recordDuplicate(event.topic),
    )

  def recordRejection(
      event: GossipEvent[HotStuffGossipArtifact],
      reason: String,
  ): InMemoryHotStuffSinkSnapshot =
    copy(diagnostics = diagnostics.recordRejection(event.topic, reason))

/** Companion for `InMemoryHotStuffSinkSnapshot`. */
object InMemoryHotStuffSinkSnapshot:
  /** Creates an empty sink snapshot for the supplied relay policy. */
  def empty(
      relayPolicy: HotStuffRelayPolicy,
  ): InMemoryHotStuffSinkSnapshot =
    InMemoryHotStuffSinkSnapshot(
      proposals = Map.empty[ProposalId, Proposal],
      votes = Map.empty[VoteId, Vote],
      accumulator = VoteAccumulator.empty,
      timeoutVotes = Map.empty[TimeoutVoteId, TimeoutVote],
      timeoutAccumulator = TimeoutVoteAccumulator.empty,
      timeoutCertificates = Map.empty[TimeoutVoteSubject, TimeoutCertificate],
      newViews = Map.empty[NewViewId, NewView],
      newViewsBySenderWindow =
        Map.empty[(HotStuffWindow, ValidatorId), NewView],
      qcs = Map.empty[ProposalId, QuorumCertificate],
      finalization = Map.empty[ChainId, FinalizationTrackerSnapshot],
      duplicates = Vector.empty[GossipEvent[HotStuffGossipArtifact]],
      diagnostics = InMemoryHotStuffSinkDiagnostics.empty(relayPolicy),
    )

private final case class AnchorKey(
    chainId: ChainId,
    proposalId: ProposalId,
    blockId: BlockId,
)

private final case class ObservationState(
    firstSeenByProposal: Map[ProposalId, Instant],
    firstSeenOrder: Vector[ProposalId],
    observedAnchors: Map[AnchorKey, FinalizedAnchorObservation],
    observedAnchorOrder: Vector[AnchorKey],
    currentByChain: Map[ChainId, FinalizedAnchorObservation],
    currentTxRangeByChain: Map[ChainId, FinalizedTxRangeObservation],
    txRangeHistory: Vector[FinalizedTxRangeObservation],
):
  def recordProposalFirstSeen(
      proposalId: ProposalId,
      observedAt: Instant,
  ): ObservationState =
    if firstSeenByProposal.contains(proposalId) then this
    else
      val (boundedFirstSeen, boundedOrder) =
        ObservationState.appendBounded(
          values = firstSeenByProposal,
          order = firstSeenOrder,
          key = proposalId,
          value = observedAt,
          cap = ObservationState.FirstSeenCap,
        )
      copy(
        firstSeenByProposal = boundedFirstSeen,
        firstSeenOrder = boundedOrder,
      )

  def recordFinalization(
      finalization: Map[ChainId, FinalizationTrackerSnapshot],
      proposals: Iterable[Proposal],
      observedAt: Instant,
  ): ObservationState =
    val proposalsByChainAndBlockId =
      proposals.iterator
        .map(proposal =>
          (proposal.window.chainId, proposal.targetBlockId) -> proposal,
        )
        .toMap
    finalization.foldLeft(this):
      case (state, (chainId, snapshot)) =>
        snapshot.bestFinalized match
          case Some(suggestion) =>
            val previousTxRangeAnchor =
              state.currentTxRangeByChain.get(chainId).map(_.newFinalized)
            val key = AnchorKey(
              chainId = chainId,
              proposalId = suggestion.proposal.proposalId,
              blockId = suggestion.anchorBlockId,
            )
            val nextRange =
              state.txRangeForAdvancement(
                chainId = chainId,
                previousAnchor = previousTxRangeAnchor,
                suggestion = suggestion,
                proposalsByChainAndBlockId = proposalsByChainAndBlockId,
                observedAt = observedAt,
              )
            state.currentByChain.get(chainId) match
              // currentByChain pins the current observation so bounded-history eviction cannot re-stamp a still-current anchor.
              case Some(current)
                  if current.proposalId === key.proposalId &&
                    current.blockId === key.blockId =>
                state.recordTxRange(chainId, nextRange)
              case _ =>
                state.observedAnchors.get(key) match
                  case Some(existing) =>
                    state
                      .copy(
                        currentByChain = state.currentByChain.updated(
                          chainId,
                          existing,
                        ),
                      )
                      .recordTxRange(chainId, nextRange)
                  case None =>
                    val observation =
                      FinalizedAnchorObservation.fromSuggestion(
                        suggestion = suggestion,
                        proposalObservedAt =
                          state.firstSeenByProposal.getOrElse(
                            suggestion.proposal.proposalId,
                            observedAt,
                          ),
                        finalizedObservedAt = observedAt,
                      )
                    val (boundedObserved, boundedOrder) =
                      ObservationState.appendBounded(
                        values = state.observedAnchors,
                        order = state.observedAnchorOrder,
                        key = key,
                        value = observation,
                        cap = ObservationState.ObservedAnchorCap,
                      )
                    state
                      .copy(
                        observedAnchors = boundedObserved,
                        observedAnchorOrder = boundedOrder,
                        currentByChain =
                          state.currentByChain.updated(chainId, observation),
                      )
                      .recordTxRange(chainId, nextRange)
          case None =>
            state.clearFaultedCurrent(chainId, snapshot)

  private def recordTxRange(
      chainId: ChainId,
      range: Option[FinalizedTxRangeObservation],
  ): ObservationState =
    range match
      case None =>
        this
      case Some(nextRange)
          if currentTxRangeByChain
            .get(chainId)
            .exists(_.newFinalized.blockId === nextRange.newFinalized.blockId) =>
        this
      case Some(nextRange) =>
        copy(
          currentTxRangeByChain =
            currentTxRangeByChain.updated(chainId, nextRange),
          txRangeHistory = ObservationState.appendBoundedValue(
            values = txRangeHistory,
            value = nextRange,
            cap = ObservationState.TxRangeHistoryCap,
          ),
        )

  private def clearFaultedCurrent(
      chainId: ChainId,
      snapshot: FinalizationTrackerSnapshot,
  ): ObservationState =
    if snapshot.safetyFaults.isEmpty then this
    else
      val faultHeights = snapshot.safetyFaults.iterator.map(_.height).toSet
      currentByChain.get(chainId) match
        case Some(existing) if faultHeights.contains(existing.height) =>
          copy(currentByChain = currentByChain.removed(chainId))
        case _ =>
          this

  private def txRangeForAdvancement(
      chainId: ChainId,
      previousAnchor: Option[SnapshotAnchor],
      suggestion: FinalizedAnchorSuggestion,
      proposalsByChainAndBlockId: Map[(ChainId, BlockId), Proposal],
      observedAt: Instant,
  ): Option[FinalizedTxRangeObservation] =
    val nextAnchor = suggestion.snapshotAnchor
    previousAnchor match
      case Some(previous)
          if !Ordering[BlockHeight].lt(previous.height, nextAnchor.height) =>
        None
      case _ =>
        collectNewlyFinalizedProposals(
          chainId = chainId,
          current = suggestion.proposal,
          stopBlockId = previousAnchor.map(_.blockId),
          proposalsByChainAndBlockId = proposalsByChainAndBlockId,
          acc = Vector.empty[FinalizedTxProposalObservation],
        ).map: finalizedProposals =>
          FinalizedTxRangeObservation(
            chainId = chainId,
            previousFinalized = previousAnchor,
            newFinalized = nextAnchor,
            finalizedObservedAt = observedAt,
            proposals = finalizedProposals,
          )

  @scala.annotation.tailrec
  private def collectNewlyFinalizedProposals(
      chainId: ChainId,
      current: Proposal,
      stopBlockId: Option[BlockId],
      proposalsByChainAndBlockId: Map[(ChainId, BlockId), Proposal],
      acc: Vector[FinalizedTxProposalObservation],
  ): Option[Vector[FinalizedTxProposalObservation]] =
    if stopBlockId.exists(_ === current.targetBlockId) then Some(acc)
    else
      val nextAcc =
        FinalizedTxProposalObservation(
          proposalId = current.proposalId,
          blockId = current.targetBlockId,
          height = current.block.height,
          txSet = current.txSet,
        ) +: acc
      current.block.parent match
        case Some(parentBlockId) if stopBlockId.exists(_ === parentBlockId) =>
          Some(nextAcc)
        case Some(parentBlockId) =>
          proposalsByChainAndBlockId.get(chainId -> parentBlockId) match
            case Some(parentProposal) =>
              collectNewlyFinalizedProposals(
                chainId = chainId,
                current = parentProposal,
                stopBlockId = stopBlockId,
                proposalsByChainAndBlockId = proposalsByChainAndBlockId,
                acc = nextAcc,
              )
            case None if stopBlockId.isEmpty =>
              // Initial observations may start above the runtime's local
              // proposal boundary, so expose the contiguous suffix already
              // available and leave finalized-history replay checks to the
              // embedder.
              Some(nextAcc)
            case None =>
              None
        case None =>
          Some(nextAcc)

private object ObservationState:
  // Bounded internal diagnostic histories from the Phase 0 lock. If a very old
  // anchor is evicted and becomes best again far in the future it may be
  // re-stamped; the practical fallback window is tiny, so that is acceptable.
  private val FirstSeenCap      = 1024
  private val ObservedAnchorCap = 256
  private val TxRangeHistoryCap = 256

  val empty: ObservationState =
    ObservationState(
      firstSeenByProposal = Map.empty[ProposalId, Instant],
      firstSeenOrder = Vector.empty[ProposalId],
      observedAnchors = Map.empty[AnchorKey, FinalizedAnchorObservation],
      observedAnchorOrder = Vector.empty[AnchorKey],
      currentByChain = Map.empty[ChainId, FinalizedAnchorObservation],
      currentTxRangeByChain = Map.empty[ChainId, FinalizedTxRangeObservation],
      txRangeHistory = Vector.empty[FinalizedTxRangeObservation],
    )

  private def appendBounded[K, V](
      values: Map[K, V],
      order: Vector[K],
      key: K,
      value: V,
      cap: Int,
  ): (Map[K, V], Vector[K]) =
    val insertedValues = values.updated(key, value)
    val insertedOrder  = order :+ key
    if insertedOrder.sizeIs <= cap then insertedValues -> insertedOrder
    else
      insertedOrder.headOption.fold(insertedValues -> insertedOrder):
        evictedKey =>
          insertedValues.removed(evictedKey) -> insertedOrder.drop(1)

  private def appendBoundedValue[V](
      values: Vector[V],
      value: V,
      cap: Int,
  ): Vector[V] =
    val inserted = values :+ value
    if inserted.sizeIs <= cap then inserted
    else inserted.drop(inserted.size - cap)

private final case class SinkState(
    snapshot: InMemoryHotStuffSinkSnapshot,
    observations: ObservationState,
)

private object SinkState:
  def empty(
      relayPolicy: HotStuffRelayPolicy,
  ): SinkState =
    SinkState(
      snapshot = InMemoryHotStuffSinkSnapshot.empty(relayPolicy),
      observations = ObservationState.empty,
    )

/** Publishes HotStuff gossip artifacts to the local gossip source. */
trait HotStuffArtifactPublisher[F[_]]:
  /** Appends an artifact to the gossip source, returning the created gossip
    * event.
    */
  def append(
      artifact: HotStuffGossipArtifact,
      ts: Instant,
  ): F[GossipEvent[HotStuffGossipArtifact]]

private final case class SourceTopicState(
    events: Vector[AvailableGossipEvent[HotStuffGossipArtifact]],
    nextSequence: Long,
    appendedCount: Long,
    prunedCount: Long,
    readByIdMissCount: Long,
    invalidCursorRejectionCount: Long,
    staleCursorRejectionCount: Long,
):
  def append(
      available: AvailableGossipEvent[HotStuffGossipArtifact],
      retention: HotStuffArtifactSourceRetention,
  ): SourceTopicState =
    val inserted   = events :+ available
    val pruneCount = math.max(0, inserted.size - retention.retainedEventsPerTopic)
    val retained =
      if pruneCount === 0 then inserted
      else inserted.drop(pruneCount)
    copy(
      events = retained,
      nextSequence = nextSequence + 1L,
      appendedCount = appendedCount + 1L,
      prunedCount = prunedCount + pruneCount.toLong,
    )

  def recordReadByIdMisses(
      missCount: Int,
  ): SourceTopicState =
    if missCount <= 0 then this
    else copy(readByIdMissCount = readByIdMissCount + missCount.toLong)

  def recordInvalidCursorRejection: SourceTopicState =
    copy(invalidCursorRejectionCount = invalidCursorRejectionCount + 1L)

  def recordStaleCursorRejection: SourceTopicState =
    copy(staleCursorRejectionCount = staleCursorRejectionCount + 1L)

  def firstRetainedSequence: Long =
    nextSequence - events.size.toLong

  def latestSequence: Long =
    nextSequence - 1L

private object SourceTopicState:
  val empty: SourceTopicState =
    SourceTopicState(
      events = Vector.empty[AvailableGossipEvent[HotStuffGossipArtifact]],
      nextSequence = 1L,
      appendedCount = 0L,
      prunedCount = 0L,
      readByIdMissCount = 0L,
      invalidCursorRejectionCount = 0L,
      staleCursorRejectionCount = 0L,
    )

/** In-memory implementation of a gossip artifact source and publisher for
  * HotStuff artifacts.
  */
final class InMemoryHotStuffArtifactSource[F[_]: Sync] private (
    clock: GossipClock[F],
    retention: HotStuffArtifactSourceRetention,
    ref: Ref[F, Map[ChainTopic, SourceTopicState]],
) extends GossipArtifactSource[F, HotStuffGossipArtifact]
    with HotStuffArtifactPublisher[F]:
  def append(
      artifact: HotStuffGossipArtifact,
      ts: Instant,
  ): F[GossipEvent[HotStuffGossipArtifact]] =
    clock.now.flatMap: availableAt =>
      ref.modify: state =>
        val chainId = artifact match
          case HotStuffGossipArtifact.ProposalArtifact(proposal) =>
            proposal.window.chainId
          case HotStuffGossipArtifact.VoteArtifact(vote) => vote.window.chainId
          case HotStuffGossipArtifact.TimeoutVoteArtifact(timeoutVote) =>
            timeoutVote.subject.window.chainId
          case HotStuffGossipArtifact.NewViewArtifact(newView) =>
            newView.window.chainId
        val topic      = HotStuffGossipArtifact.topicOf(artifact)
        val chainTopic = ChainTopic(chainId, topic)
        val topicState = state.getOrElse(
          chainTopic,
          SourceTopicState.empty,
        )
        val event = GossipEvent(
          chainId = chainId,
          topic = topic,
          id = HotStuffGossipArtifact.stableIdOf(artifact),
          cursor = cursorFor(topicState.nextSequence),
          ts = ts,
          payload = artifact,
        )
        val available =
          AvailableGossipEvent(event = event, availableAt = availableAt)
        state.updated(chainTopic, topicState.append(available, retention)) ->
          event

  override def readAfter(
      chainId: ChainId,
      topic: GossipTopic,
      cursor: Option[CursorToken],
  ): F[Either[CanonicalRejection, Vector[
    AvailableGossipEvent[HotStuffGossipArtifact],
  ]]] =
    ref.modify: state =>
      val chainTopic = ChainTopic(chainId, topic)
      val topicState = state.getOrElse(
        chainTopic,
        SourceTopicState.empty,
      )
      val result = cursor match
        case None =>
          topicState.events.asRight[CanonicalRejection]
        case Some(token) =>
          decodeSequence(token).flatMap: sequence =>
            if sequence < 1L then
              CanonicalRejection
                .StaleCursor(
                  reason = "unknownCursor",
                  detail = Some:
                    ss"sequence=${sequence.toString} min=1",
                )
                .asLeft[Vector[
                  AvailableGossipEvent[HotStuffGossipArtifact],
                ]]
            else if topicState.events.isEmpty then
              CanonicalRejection
                .StaleCursor(
                  reason = "unknownCursor",
                  detail = Some:
                    ss"sequence=${sequence.toString} max=${topicState.latestSequence.toString}",
                )
                .asLeft[Vector[
                  AvailableGossipEvent[HotStuffGossipArtifact],
                ]]
            else if sequence < topicState.firstRetainedSequence then
              CanonicalRejection
                .StaleCursor(
                  reason = "cursorPruned",
                  detail = Some:
                    ss"sequence=${sequence.toString} firstRetained=${topicState.firstRetainedSequence.toString}",
                )
                .asLeft[Vector[
                  AvailableGossipEvent[HotStuffGossipArtifact],
                ]]
            else if sequence <= topicState.latestSequence then
              val offset =
                (sequence - topicState.firstRetainedSequence + 1L).toInt
              topicState.events.drop(offset).asRight[CanonicalRejection]
            else
              CanonicalRejection
                .StaleCursor(
                  reason = "unknownCursor",
                  detail = Some:
                    ss"sequence=${sequence.toString} max=${topicState.latestSequence.toString}",
                )
                .asLeft[Vector[
                  AvailableGossipEvent[HotStuffGossipArtifact],
                ]]
      val updatedState =
        result.fold(
          rejection =>
            val updatedTopicState =
              if isInvalidCursorRejection(rejection) then
                topicState.recordInvalidCursorRejection
              else topicState.recordStaleCursorRejection
            state.updated(chainTopic, updatedTopicState),
          _ => state,
        )
      updatedState -> result

  override def readByIds(
      chainId: ChainId,
      topic: GossipTopic,
      ids: Vector[StableArtifactId],
  ): F[Vector[AvailableGossipEvent[HotStuffGossipArtifact]]] =
    ref.modify: state =>
      val chainTopic = ChainTopic(chainId, topic)
      val topicState = state.getOrElse(chainTopic, SourceTopicState.empty)
      val latestById =
        topicState.events
          .foldLeft(
            Map.empty[StableArtifactId, AvailableGossipEvent[
              HotStuffGossipArtifact,
            ]],
          ): (acc, available) =>
            acc.updated(available.event.id, available)
      val distinctIds = ids.distinct
      val found       = distinctIds.flatMap(latestById.get)
      val missCount =
        distinctIds.count(id => !latestById.contains(id))
      val updatedState =
        if missCount === 0 then state
        else
          state.updated(
            chainTopic,
            topicState.recordReadByIdMisses(missCount),
          )
      updatedState -> found

  def snapshot: F[InMemoryHotStuffSourceSnapshot] =
    ref.get.map: state =>
      InMemoryHotStuffSourceSnapshot(
        eventsByTopic =
          state.view.mapValues(_.events.map(_.event)).toMap,
        diagnostics = InMemoryHotStuffSourceDiagnostics(
          retainedEventsPerTopic = retention.retainedEventsPerTopic,
          retainedEventsByTopic =
            state.view.mapValues(_.events.size).toMap,
          appendedEventsByTopic =
            state.view.mapValues(_.appendedCount).toMap,
          prunedEventsByTopic =
            state.view.mapValues(_.prunedCount).toMap,
          readByIdMissesByTopic =
            state.view.mapValues(_.readByIdMissCount).toMap,
          invalidCursorRejectionsByTopic =
            state.view.mapValues(_.invalidCursorRejectionCount).toMap,
          staleCursorRejectionsByTopic =
            state.view.mapValues(_.staleCursorRejectionCount).toMap,
        ),
      )

  private def cursorFor(
      sequence: Long,
  ): CursorToken =
    CursorToken.unsafeIssue:
      ByteVector.view:
        ByteBuffer.allocate(java.lang.Long.BYTES).putLong(sequence).array()

  private def decodeSequence(
      token: CursorToken,
  ): Either[CanonicalRejection.StaleCursor, Long] =
    token
      .validateVersion()
      .flatMap: validated =>
        Either.cond(
          validated.payload.size == java.lang.Long.BYTES.toLong,
          ByteBuffer.wrap(validated.payload.toArray).getLong(),
          CanonicalRejection.StaleCursor(
            reason = "invalidCursorPayload",
            detail = Some(ss"size=${validated.payload.size.toString}"),
          ),
        )

  private def isInvalidCursorRejection(
      rejection: CanonicalRejection,
  ): Boolean =
    rejection match
      case stale: CanonicalRejection.StaleCursor =>
        stale.reason === "invalidCursorPayload" ||
          stale.reason === "cursorTokenVersionMismatch"
      case _ => false

/** Companion for `InMemoryHotStuffArtifactSource`. */
object InMemoryHotStuffArtifactSource:
  /** Creates a new in-memory gossip artifact source. */
  def create[F[_]: Sync](using
      clock: GossipClock[F],
  ): F[InMemoryHotStuffArtifactSource[F]] =
    createWithRetention[F](HotStuffArtifactSourceRetention.default)

  /** Creates a new in-memory gossip artifact source with explicit retention. */
  def createWithRetention[F[_]: Sync](
      retention: HotStuffArtifactSourceRetention,
  )(using
      clock: GossipClock[F],
  ): F[InMemoryHotStuffArtifactSource[F]] =
    Ref
      .of[F, Map[ChainTopic, SourceTopicState]](Map.empty)
      .map(new InMemoryHotStuffArtifactSource[F](clock, retention, _))

/** In-memory implementation of a gossip artifact sink for HotStuff artifacts,
  * handling validation, QC assembly, and finalization tracking.
  */
final class InMemoryHotStuffArtifactSink[F[_]: Sync] private (
    clock: GossipClock[F],
    validatorSet: ValidatorSet,
    relayPolicy: HotStuffRelayPolicy,
    relayPublisher: HotStuffArtifactPublisher[F],
    proposalValidation: Proposal => F[Either[HotStuffValidationFailure, Unit]],
    ref: Ref[F, SinkState],
) extends GossipArtifactSink[F, HotStuffGossipArtifact]:
  private type RelayEnvelope = (HotStuffGossipArtifact, Instant)

  // This sink is intentionally optimized for deterministic in-memory tests.
  // It keeps QC assembly simple and atomic, but production-backed sinks should
  // replace the repeated full re-assembly path with an incremental cache/index.
  override def applyEvent(
      event: GossipEvent[HotStuffGossipArtifact],
  ): F[
    Either[CanonicalRejection.ArtifactContractRejected, ArtifactApplyResult],
  ] =
    event.payload match
      case HotStuffGossipArtifact.ProposalArtifact(proposal) =>
        applyProposalEvent(event, proposal)
      case HotStuffGossipArtifact.VoteArtifact(vote) =>
        applyVoteEvent(event, vote)
      case HotStuffGossipArtifact.TimeoutVoteArtifact(timeoutVote) =>
        applyTimeoutVoteEvent(event, timeoutVote)
      case HotStuffGossipArtifact.NewViewArtifact(newView) =>
        applyNewViewEvent(event, newView)

  private def applyProposalEvent(
      event: GossipEvent[HotStuffGossipArtifact],
      proposal: Proposal,
  ): F[
    Either[CanonicalRejection.ArtifactContractRejected, ArtifactApplyResult],
  ] =
    HotStuffValidator.validateProposal(proposal, validatorSet) match
      case Left(error) =>
        val rejection = artifactRejected(error)
        recordArtifactRejection(event, rejection.reason)
          .as(rejection.asLeft[ArtifactApplyResult])
      case Right(_) =>
        proposalValidation(proposal).flatMap:
          case Left(error) =>
            val rejection = artifactRejected(error)
            recordArtifactRejection(event, rejection.reason)
              .as(rejection.asLeft[ArtifactApplyResult])
          case Right(_) =>
            clock.now.flatMap: localObservedAt =>
              ref
                .modify: state =>
                  val snapshot = state.snapshot
                  if snapshot.proposals.contains(proposal.proposalId) then
                    state.copy(
                      snapshot = snapshot.recordDuplicate(event),
                    ) -> (
                      ArtifactApplyResult(
                        applied = false,
                        duplicate = true,
                      ) -> Option.empty[RelayEnvelope]
                    ).asRight[CanonicalRejection.ArtifactContractRejected]
                  else
                    val updatedProposals =
                      snapshot.proposals.updated(proposal.proposalId, proposal)
                    val updatedQcs =
                      snapshot.qcs.updated(
                        proposal.justify.subject.proposalId,
                        proposal.justify,
                      )
                    val assembled =
                      assembleQuorumCertificate(
                        QuorumCertificateSubject(
                          window = proposal.window,
                          proposalId = proposal.proposalId,
                          blockId = proposal.targetBlockId,
                        ),
                        snapshot.accumulator
                          .votesFor(proposal.window, proposal.proposalId),
                      )
                    val finalQcs =
                      assembled.fold(updatedQcs)(qc =>
                        updatedQcs.updated(proposal.proposalId, qc),
                      )
                    val relayArtifact =
                      if relayPolicy.relayValidatedArtifacts then
                        Some(event.payload -> event.ts)
                      else Option.empty[RelayEnvelope]
                    val updatedSnapshot = withFinalization(
                      snapshot.copy(
                        proposals = updatedProposals,
                        qcs = finalQcs,
                      ),
                    )
                    val updatedObservations =
                      state.observations
                        .recordProposalFirstSeen(
                          proposal.proposalId,
                          localObservedAt,
                        )
                        .recordFinalization(
                          updatedSnapshot.finalization,
                          updatedSnapshot.proposals.values,
                          localObservedAt,
                        )
                    state.copy(
                      snapshot = updatedSnapshot,
                      observations = updatedObservations,
                    ) -> (
                      ArtifactApplyResult(
                        applied = true,
                        duplicate = false,
                      ) -> relayArtifact
                    ).asRight[CanonicalRejection.ArtifactContractRejected]
                .flatMap(finalizeApply)

  private def applyVoteEvent(
      event: GossipEvent[HotStuffGossipArtifact],
      vote: Vote,
  ): F[
    Either[CanonicalRejection.ArtifactContractRejected, ArtifactApplyResult],
  ] =
    ref
      .modify: state =>
        val snapshot = state.snapshot
        if snapshot.votes.contains(vote.voteId) then
          state.copy(
            snapshot = snapshot.recordDuplicate(event),
          ) -> (
            ArtifactApplyResult(applied = false, duplicate = true) -> Option
              .empty[RelayEnvelope]
          ).asRight[CanonicalRejection.ArtifactContractRejected]
        else
          HotStuffValidator.validateVote(vote, validatorSet) match
            case Left(error) =>
              val rejection = artifactRejected(error)
              state.copy(
                snapshot = snapshot.recordRejection(event, rejection.reason),
              ) -> rejection
                .asLeft[(ArtifactApplyResult, Option[RelayEnvelope])]
            case Right(_) =>
              snapshot.accumulator.record(vote) match
                case Left(error) =>
                  val rejection = artifactRejected(error)
                  state.copy(
                    snapshot = snapshot.recordRejection(event, rejection.reason),
                  ) -> rejection
                    .asLeft[(ArtifactApplyResult, Option[RelayEnvelope])]
                case Right((updatedAccumulator, _)) =>
                  val updatedVotes =
                    snapshot.votes.updated(vote.voteId, vote)
                  val maybeProposal =
                    snapshot.proposals.get(vote.targetProposalId)
                  val maybeQc =
                    maybeProposal.flatMap: proposal =>
                      assembleQuorumCertificate(
                        QuorumCertificateSubject(
                          window = proposal.window,
                          proposalId = proposal.proposalId,
                          blockId = proposal.targetBlockId,
                        ),
                        updatedAccumulator
                          .votesFor(proposal.window, proposal.proposalId),
                      )
                  val updatedQcs =
                    maybeProposal
                      .flatMap(_ => maybeQc)
                      .fold(snapshot.qcs): qc =>
                        snapshot.qcs.updated(qc.subject.proposalId, qc)
                  val relayArtifact =
                    if relayPolicy.relayValidatedArtifacts then
                      Some(event.payload -> event.ts)
                    else Option.empty[RelayEnvelope]
                  // Finalization currently derives only from stored proposal
                  // justify chains, so vote-only updates intentionally retain
                  // the last computed finalization snapshot.
                  state.copy(
                    snapshot = snapshot.copy(
                      votes = updatedVotes,
                      accumulator = updatedAccumulator,
                      qcs = updatedQcs,
                    ),
                  ) -> (
                    ArtifactApplyResult(
                      applied = true,
                      duplicate = false,
                    ) -> relayArtifact
                  ).asRight[CanonicalRejection.ArtifactContractRejected]
      .flatMap(finalizeApply)

  private def applyTimeoutVoteEvent(
      event: GossipEvent[HotStuffGossipArtifact],
      timeoutVote: TimeoutVote,
  ): F[
    Either[CanonicalRejection.ArtifactContractRejected, ArtifactApplyResult],
  ] =
    ref
      .modify: state =>
        val snapshot = state.snapshot
        if snapshot.timeoutVotes.contains(timeoutVote.timeoutVoteId) then
          state.copy(
            snapshot = snapshot.recordDuplicate(event),
          ) -> (
            ArtifactApplyResult(applied = false, duplicate = true) -> Option
              .empty[RelayEnvelope]
          ).asRight[CanonicalRejection.ArtifactContractRejected]
        else
          HotStuffValidator.validateTimeoutVote(timeoutVote, validatorSet) match
            case Left(error) =>
              val rejection = artifactRejected(error)
              state.copy(
                snapshot = snapshot.recordRejection(event, rejection.reason),
              ) -> rejection
                .asLeft[(ArtifactApplyResult, Option[RelayEnvelope])]
            case Right(_) =>
              snapshot.timeoutAccumulator.record(timeoutVote) match
                case Left(error) =>
                  val rejection = artifactRejected(error)
                  state.copy(
                    snapshot = snapshot.recordRejection(event, rejection.reason),
                  ) -> rejection
                    .asLeft[(ArtifactApplyResult, Option[RelayEnvelope])]
                case Right((updatedAccumulator, _)) =>
                  val updatedTimeoutVotes =
                    snapshot.timeoutVotes.updated(
                      timeoutVote.timeoutVoteId,
                      timeoutVote,
                    )
                  val maybeTimeoutCertificate =
                    assembleTimeoutCertificate(
                      timeoutVote.subject,
                      updatedAccumulator.votesFor(timeoutVote.subject),
                    )
                  val updatedTimeoutCertificates =
                    maybeTimeoutCertificate.fold(snapshot.timeoutCertificates):
                      timeoutCertificate =>
                        snapshot.timeoutCertificates.updated(
                          timeoutCertificate.subject,
                          timeoutCertificate,
                        )
                  val relayArtifact =
                    if relayPolicy.relayValidatedArtifacts then
                      Some(event.payload -> event.ts)
                    else Option.empty[RelayEnvelope]
                  state.copy(
                    snapshot = snapshot.copy(
                      timeoutVotes = updatedTimeoutVotes,
                      timeoutAccumulator = updatedAccumulator,
                      timeoutCertificates = updatedTimeoutCertificates,
                    ),
                  ) -> (
                    ArtifactApplyResult(
                      applied = true,
                      duplicate = false,
                    ) -> relayArtifact
                  ).asRight[CanonicalRejection.ArtifactContractRejected]
      .flatMap(finalizeApply)

  private def applyNewViewEvent(
      event: GossipEvent[HotStuffGossipArtifact],
      newView: NewView,
  ): F[
    Either[CanonicalRejection.ArtifactContractRejected, ArtifactApplyResult],
  ] =
    ref
      .modify: state =>
        val snapshot = state.snapshot
        if snapshot.newViews.contains(newView.newViewId) then
          state.copy(
            snapshot = snapshot.recordDuplicate(event),
          ) -> (
            ArtifactApplyResult(applied = false, duplicate = true) -> Option
              .empty[RelayEnvelope]
          ).asRight[CanonicalRejection.ArtifactContractRejected]
        else
          val senderWindowKey = (newView.window, newView.sender)
          snapshot.newViewsBySenderWindow.get(senderWindowKey) match
            case Some(existing) if existing.newViewId =!= newView.newViewId =>
              val rejection =
                CanonicalRejection.ArtifactContractRejected(
                  reason = "conflictingNewView",
                  detail = Some(newView.sender.value),
                )
              state.copy(
                snapshot = snapshot.recordRejection(event, rejection.reason),
              ) -> rejection
                .asLeft[(ArtifactApplyResult, Option[RelayEnvelope])]
            case Some(_) =>
              state.copy(
                snapshot = snapshot.recordDuplicate(event),
              ) -> (
                ArtifactApplyResult(applied = false, duplicate = true) -> Option
                  .empty[RelayEnvelope]
              ).asRight[CanonicalRejection.ArtifactContractRejected]
            case None =>
              HotStuffValidator.validateNewView(newView, validatorSet) match
                case Left(error) =>
                  val rejection = artifactRejected(error)
                  state.copy(
                    snapshot = snapshot.recordRejection(event, rejection.reason),
                  ) -> rejection
                    .asLeft[(ArtifactApplyResult, Option[RelayEnvelope])]
                case Right(_) =>
                  val relayArtifact =
                    if relayPolicy.relayValidatedArtifacts then
                      Some(event.payload -> event.ts)
                    else Option.empty[RelayEnvelope]
                  state.copy(
                    snapshot = snapshot.copy(
                      newViews =
                        snapshot.newViews.updated(newView.newViewId, newView),
                      newViewsBySenderWindow =
                        snapshot.newViewsBySenderWindow.updated(
                          senderWindowKey,
                          newView,
                        ),
                    ),
                  ) -> (
                    ArtifactApplyResult(
                      applied = true,
                      duplicate = false,
                    ) -> relayArtifact
                  ).asRight[CanonicalRejection.ArtifactContractRejected]
      .flatMap(finalizeApply)

  private def finalizeApply(
      stored: Either[
        CanonicalRejection.ArtifactContractRejected,
        (ArtifactApplyResult, Option[RelayEnvelope]),
      ],
  ): F[
    Either[CanonicalRejection.ArtifactContractRejected, ArtifactApplyResult],
  ] =
    stored match
      case Left(rejection) =>
        rejection.asLeft[ArtifactApplyResult].pure[F]
      case Right((result, maybeRelay)) =>
        maybeRelay
          .traverse_ { case (artifact, ts) =>
            val topic = HotStuffGossipArtifact.topicOf(artifact)
            relayPublisher.append(artifact, ts) *>
              ref.update(state =>
                state.copy(snapshot = state.snapshot.recordRelay(topic)),
              )
          }
          .as(result.asRight)

  private def recordArtifactRejection(
      event: GossipEvent[HotStuffGossipArtifact],
      reason: String,
  ): F[Unit] =
    ref.update(state =>
      state.copy(snapshot = state.snapshot.recordRejection(event, reason)),
    )

  private def artifactRejected(
      error: HotStuffValidationFailure,
  ): CanonicalRejection.ArtifactContractRejected =
    CanonicalRejection.ArtifactContractRejected(
      reason = error.reason,
      detail = error.detail,
    )

  private def assembleQuorumCertificate(
      subject: QuorumCertificateSubject,
      votes: Vector[Vote],
  ): Option[QuorumCertificate] =
    QuorumCertificateAssembler
      .assemble(subject, votes, validatorSet)
      .toOption

  private def assembleTimeoutCertificate(
      subject: TimeoutVoteSubject,
      votes: Vector[TimeoutVote],
  ): Option[TimeoutCertificate] =
    TimeoutCertificateAssembler
      .assemble(subject, votes, validatorSet)
      .toOption

  private def withFinalization(
      snapshot: InMemoryHotStuffSinkSnapshot,
  ): InMemoryHotStuffSinkSnapshot =
    snapshot.copy(
      finalization = HotStuffFinalizationTracker.trackAll(
        snapshot.proposals.values,
      ),
    )

  /** Returns the current sink snapshot. */
  def snapshot: F[InMemoryHotStuffSinkSnapshot] =
    ref.get.map(_.snapshot)

  private[hotstuff] def finalizationObservations
      : F[Map[ChainId, FinalizedAnchorObservation]] =
    ref.get.map(_.observations.currentByChain)

  private[hotstuff] def finalizedTxRangeObservations
      : F[Map[ChainId, FinalizedTxRangeObservation]] =
    ref.get.map(_.observations.currentTxRangeByChain)

  private[hotstuff] def recentFinalizedTxRangeObservations
      : F[Vector[FinalizedTxRangeObservation]] =
    ref.get.map(_.observations.txRangeHistory)

/** Companion for `InMemoryHotStuffArtifactSink`. */
object InMemoryHotStuffArtifactSink:
  def create[F[_]: Sync](
      validatorSet: ValidatorSet,
      relayPolicy: HotStuffRelayPolicy,
      relayPublisher: HotStuffArtifactPublisher[F],
  )(using
      clock: GossipClock[F],
  ): F[InMemoryHotStuffArtifactSink[F]] =
    for ref <- Ref.of[F, SinkState](SinkState.empty(relayPolicy))
    yield new InMemoryHotStuffArtifactSink[F](
      clock,
      validatorSet,
      relayPolicy,
      relayPublisher,
      HotStuffRuntimeScheduling.allowAll[F],
      ref,
    )

  def createWithProposalValidation[F[_]
    : Sync, TxRef: ByteEncoder: Hash, ResultRef: ByteEncoder, Event: ByteEncoder](
      validatorSet: ValidatorSet,
      relayPolicy: HotStuffRelayPolicy,
      relayPublisher: HotStuffArtifactPublisher[F],
      blockQuery: BlockQuery[F, TxRef, ResultRef, Event],
  )(
      classifyTx: TxRef => org.sigilaris.core.application.scheduling.SchedulingClassification,
  )(using
      clock: GossipClock[F],
  ): F[InMemoryHotStuffArtifactSink[F]] =
    for ref <- Ref.of[F, SinkState](SinkState.empty(relayPolicy))
    yield new InMemoryHotStuffArtifactSink[F](
      clock,
      validatorSet,
      relayPolicy,
      relayPublisher,
      HotStuffRuntimeScheduling.proposalValidationFromBlockQuery(
        validatorSet = validatorSet,
        blockQuery = blockQuery,
      )(classifyTx),
      ref,
    )
