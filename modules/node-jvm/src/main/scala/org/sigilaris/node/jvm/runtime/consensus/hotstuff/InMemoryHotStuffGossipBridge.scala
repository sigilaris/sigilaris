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

/** Retention policy for the in-memory HotStuff artifact sink.
  *
  * `retainedRejectedEventSamples` is reserved for a bounded rejected-body
  * sample if one is added later. The current sink records rejection counters
  * only.
  */
final case class HotStuffArtifactSinkRetention private (
    finalizedHeightLag: Long,
    certifiedHeightLag: Long,
    retainedTimeoutWindows: Long,
    retainedNewViewWindows: Long,
    retainedDuplicateEvents: Int,
    retainedRejectedEventSamples: Int,
    pruneEveryAcceptedEvents: Int,
)

object HotStuffArtifactSinkRetention:
  val DefaultPruneEveryAcceptedEvents: Int = 16

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def fromValues(
      finalizedHeightLag: Long,
      certifiedHeightLag: Long,
      retainedTimeoutWindows: Long,
      retainedNewViewWindows: Long,
      retainedDuplicateEvents: Int,
      retainedRejectedEventSamples: Int,
      pruneEveryAcceptedEvents: Int = DefaultPruneEveryAcceptedEvents,
  ): Either[String, HotStuffArtifactSinkRetention] =
    val checks = Vector(
      (finalizedHeightLag >= 3L) ->
        "finalizedHeightLag must be at least 3",
      (certifiedHeightLag >= 0L) ->
        "certifiedHeightLag must be non-negative",
      (certifiedHeightLag >= finalizedHeightLag) ->
        "certifiedHeightLag must be greater than or equal to finalizedHeightLag",
      (retainedTimeoutWindows > 0L) ->
        "retainedTimeoutWindows must be positive",
      (retainedNewViewWindows > 0L) ->
        "retainedNewViewWindows must be positive",
      (retainedDuplicateEvents >= 0) ->
        "retainedDuplicateEvents must be non-negative",
      (retainedRejectedEventSamples == 0) ->
        "retainedRejectedEventSamples is not supported yet; leave it at 0",
      (pruneEveryAcceptedEvents > 0) ->
        "pruneEveryAcceptedEvents must be positive",
    )
    checks.collectFirst { case (false, error) => error } match
      case Some(error) => error.asLeft[HotStuffArtifactSinkRetention]
      case None        =>
        HotStuffArtifactSinkRetention(
          finalizedHeightLag = finalizedHeightLag,
          certifiedHeightLag = certifiedHeightLag,
          retainedTimeoutWindows = retainedTimeoutWindows,
          retainedNewViewWindows = retainedNewViewWindows,
          retainedDuplicateEvents = retainedDuplicateEvents,
          retainedRejectedEventSamples = retainedRejectedEventSamples,
          pruneEveryAcceptedEvents = pruneEveryAcceptedEvents,
        ).asRight[String]

  @SuppressWarnings(
    Array(
      "org.wartremover.warts.DefaultArguments",
      "org.wartremover.warts.Throw",
    ),
  )
  def unsafe(
      finalizedHeightLag: Long,
      certifiedHeightLag: Long,
      retainedTimeoutWindows: Long,
      retainedNewViewWindows: Long,
      retainedDuplicateEvents: Int,
      retainedRejectedEventSamples: Int,
      pruneEveryAcceptedEvents: Int = DefaultPruneEveryAcceptedEvents,
  ): HotStuffArtifactSinkRetention =
    fromValues(
      finalizedHeightLag = finalizedHeightLag,
      certifiedHeightLag = certifiedHeightLag,
      retainedTimeoutWindows = retainedTimeoutWindows,
      retainedNewViewWindows = retainedNewViewWindows,
      retainedDuplicateEvents = retainedDuplicateEvents,
      retainedRejectedEventSamples = retainedRejectedEventSamples,
      pruneEveryAcceptedEvents = pruneEveryAcceptedEvents,
    ) match
      case Right(retention) => retention
      case Left(error)      => throw new IllegalArgumentException(error)

  val default: HotStuffArtifactSinkRetention =
    unsafe(
      finalizedHeightLag = 128L,
      certifiedHeightLag = 128L,
      retainedTimeoutWindows = 256L,
      retainedNewViewWindows = 256L,
      retainedDuplicateEvents = 16,
      retainedRejectedEventSamples = 0,
      pruneEveryAcceptedEvents = DefaultPruneEveryAcceptedEvents,
    )

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

final case class HotStuffSinkRetainedCounts(
    proposals: Int,
    votes: Int,
    voteAccumulatorVotes: Int,
    voteAccumulatorEquivocationKeys: Int,
    timeoutVotes: Int,
    timeoutAccumulatorVotes: Int,
    timeoutAccumulatorEquivocationKeys: Int,
    timeoutCertificates: Int,
    newViews: Int,
    newViewsBySenderWindow: Int,
    qcs: Int,
    safetyFaults: Int,
    duplicateSamples: Int,
)

object HotStuffSinkRetainedCounts:
  val empty: HotStuffSinkRetainedCounts =
    HotStuffSinkRetainedCounts(
      proposals = 0,
      votes = 0,
      voteAccumulatorVotes = 0,
      voteAccumulatorEquivocationKeys = 0,
      timeoutVotes = 0,
      timeoutAccumulatorVotes = 0,
      timeoutAccumulatorEquivocationKeys = 0,
      timeoutCertificates = 0,
      newViews = 0,
      newViewsBySenderWindow = 0,
      qcs = 0,
      safetyFaults = 0,
      duplicateSamples = 0,
    )

  def fromSnapshot(
      snapshot: InMemoryHotStuffSinkSnapshot,
  ): HotStuffSinkRetainedCounts =
    HotStuffSinkRetainedCounts(
      proposals = snapshot.proposals.size,
      votes = snapshot.votes.size,
      voteAccumulatorVotes = snapshot.accumulator.retainedVoteCount,
      voteAccumulatorEquivocationKeys =
        snapshot.accumulator.retainedEquivocationKeyCount,
      timeoutVotes = snapshot.timeoutVotes.size,
      timeoutAccumulatorVotes = snapshot.timeoutAccumulator.retainedVoteCount,
      timeoutAccumulatorEquivocationKeys =
        snapshot.timeoutAccumulator.retainedEquivocationKeyCount,
      timeoutCertificates = snapshot.timeoutCertificates.size,
      newViews = snapshot.newViews.size,
      newViewsBySenderWindow = snapshot.newViewsBySenderWindow.size,
      qcs = snapshot.qcs.size,
      safetyFaults =
        snapshot.finalization.valuesIterator.map(_.safetyFaults.size).sum,
      duplicateSamples = snapshot.duplicates.size,
    )

final case class HotStuffSinkPrunedCounts(
    proposals: Long,
    votes: Long,
    voteAccumulatorVotes: Long,
    voteAccumulatorEquivocationKeys: Long,
    timeoutVotes: Long,
    timeoutAccumulatorVotes: Long,
    timeoutAccumulatorEquivocationKeys: Long,
    timeoutCertificates: Long,
    newViews: Long,
    newViewsBySenderWindow: Long,
    qcs: Long,
    safetyFaults: Long,
    duplicateSamples: Long,
):
  def +(other: HotStuffSinkPrunedCounts): HotStuffSinkPrunedCounts =
    HotStuffSinkPrunedCounts(
      proposals = proposals + other.proposals,
      votes = votes + other.votes,
      voteAccumulatorVotes = voteAccumulatorVotes + other.voteAccumulatorVotes,
      voteAccumulatorEquivocationKeys = voteAccumulatorEquivocationKeys +
        other.voteAccumulatorEquivocationKeys,
      timeoutVotes = timeoutVotes + other.timeoutVotes,
      timeoutAccumulatorVotes =
        timeoutAccumulatorVotes + other.timeoutAccumulatorVotes,
      timeoutAccumulatorEquivocationKeys = timeoutAccumulatorEquivocationKeys +
        other.timeoutAccumulatorEquivocationKeys,
      timeoutCertificates = timeoutCertificates + other.timeoutCertificates,
      newViews = newViews + other.newViews,
      newViewsBySenderWindow =
        newViewsBySenderWindow + other.newViewsBySenderWindow,
      qcs = qcs + other.qcs,
      safetyFaults = safetyFaults + other.safetyFaults,
      duplicateSamples = duplicateSamples + other.duplicateSamples,
    )

object HotStuffSinkPrunedCounts:
  val empty: HotStuffSinkPrunedCounts =
    HotStuffSinkPrunedCounts(
      proposals = 0L,
      votes = 0L,
      voteAccumulatorVotes = 0L,
      voteAccumulatorEquivocationKeys = 0L,
      timeoutVotes = 0L,
      timeoutAccumulatorVotes = 0L,
      timeoutAccumulatorEquivocationKeys = 0L,
      timeoutCertificates = 0L,
      newViews = 0L,
      newViewsBySenderWindow = 0L,
      qcs = 0L,
      safetyFaults = 0L,
      duplicateSamples = 0L,
    )

  def between(
      before: HotStuffSinkRetainedCounts,
      after: HotStuffSinkRetainedCounts,
  ): HotStuffSinkPrunedCounts =
    // This is called inside a single prune pass after the new artifact has
    // already been inserted, so the after-snapshot is a subset of before.
    HotStuffSinkPrunedCounts(
      proposals = delta(before.proposals, after.proposals),
      votes = delta(before.votes, after.votes),
      voteAccumulatorVotes =
        delta(before.voteAccumulatorVotes, after.voteAccumulatorVotes),
      voteAccumulatorEquivocationKeys = delta(
        before.voteAccumulatorEquivocationKeys,
        after.voteAccumulatorEquivocationKeys,
      ),
      timeoutVotes = delta(before.timeoutVotes, after.timeoutVotes),
      timeoutAccumulatorVotes = delta(
        before.timeoutAccumulatorVotes,
        after.timeoutAccumulatorVotes,
      ),
      timeoutAccumulatorEquivocationKeys = delta(
        before.timeoutAccumulatorEquivocationKeys,
        after.timeoutAccumulatorEquivocationKeys,
      ),
      timeoutCertificates =
        delta(before.timeoutCertificates, after.timeoutCertificates),
      newViews = delta(before.newViews, after.newViews),
      newViewsBySenderWindow =
        delta(before.newViewsBySenderWindow, after.newViewsBySenderWindow),
      qcs = delta(before.qcs, after.qcs),
      safetyFaults = delta(before.safetyFaults, after.safetyFaults),
      duplicateSamples = delta(before.duplicateSamples, after.duplicateSamples),
    )

  private def delta(
      before: Int,
      after: Int,
  ): Long =
    math.max(0, before - after).toLong

final case class HotStuffSinkRetentionWatermarks(
    finalizedRetainFromHeightByChain: Map[ChainId, BigInt],
    certifiedRetainFromHeightByChain: Map[ChainId, BigInt],
    retainedTimeoutWindowFloorByChain: Map[ChainId, HotStuffWindow],
    retainedNewViewWindowFloorByChain: Map[ChainId, HotStuffWindow],
)

object HotStuffSinkRetentionWatermarks:
  val empty: HotStuffSinkRetentionWatermarks =
    HotStuffSinkRetentionWatermarks(
      finalizedRetainFromHeightByChain = Map.empty[ChainId, BigInt],
      certifiedRetainFromHeightByChain = Map.empty[ChainId, BigInt],
      retainedTimeoutWindowFloorByChain = Map.empty[ChainId, HotStuffWindow],
      retainedNewViewWindowFloorByChain = Map.empty[ChainId, HotStuffWindow],
    )

final case class InMemoryHotStuffSinkDiagnostics(
    policyMode: String,
    retentionPolicy: HotStuffArtifactSinkRetention,
    retainedCounts: HotStuffSinkRetainedCounts,
    prunedCounts: HotStuffSinkPrunedCounts,
    retentionWatermarks: HotStuffSinkRetentionWatermarks,
    relayedValidatedArtifactsByTopic: Map[GossipTopic, Long],
    duplicateArtifactsSuppressedByTopic: Map[GossipTopic, Long],
    rejectedArtifactsByTopicAndReason: Map[
      InMemoryHotStuffRelayRejectionKey,
      Long,
    ],
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

  def updateRetention(
      retainedCounts: HotStuffSinkRetainedCounts,
      prunedCounts: HotStuffSinkPrunedCounts,
      retentionWatermarks: HotStuffSinkRetentionWatermarks,
  ): InMemoryHotStuffSinkDiagnostics =
    copy(
      retainedCounts = retainedCounts,
      prunedCounts = this.prunedCounts + prunedCounts,
      retentionWatermarks = retentionWatermarks,
    )

  def updateRetainedCounts(
      retainedCounts: HotStuffSinkRetainedCounts,
  ): InMemoryHotStuffSinkDiagnostics =
    copy(retainedCounts = retainedCounts)

  private def increment[A](
      values: Map[A, Long],
      key: A,
  ): Map[A, Long] =
    values.updatedWith(key)(_.map(_ + 1L).orElse(Some(1L)))

object InMemoryHotStuffSinkDiagnostics:
  def empty(
      relayPolicy: HotStuffRelayPolicy,
      retention: HotStuffArtifactSinkRetention,
  ): InMemoryHotStuffSinkDiagnostics =
    InMemoryHotStuffSinkDiagnostics(
      policyMode = relayPolicy.mode,
      retentionPolicy = retention,
      retainedCounts = HotStuffSinkRetainedCounts.empty,
      prunedCounts = HotStuffSinkPrunedCounts.empty,
      retentionWatermarks = HotStuffSinkRetentionWatermarks.empty,
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
      retention: HotStuffArtifactSinkRetention,
  ): InMemoryHotStuffSinkSnapshot =
    val retainedDuplicates =
      InMemoryHotStuffSinkSnapshot.retainNewest(
        duplicates :+ event,
        retention.retainedDuplicateEvents,
      )
    val prunedDuplicateSamples =
      math.max(0L, duplicates.size.toLong + 1L - retainedDuplicates.size.toLong)
    val updated =
      copy(
        duplicates = retainedDuplicates,
        diagnostics = diagnostics.recordDuplicate(event.topic),
      )
    val afterCounts = HotStuffSinkRetainedCounts.fromSnapshot(updated)
    updated.copy(
      diagnostics = updated.diagnostics.updateRetention(
        retainedCounts = afterCounts,
        prunedCounts = HotStuffSinkPrunedCounts.empty.copy(
          duplicateSamples = prunedDuplicateSamples,
        ),
        retentionWatermarks = diagnostics.retentionWatermarks,
      ),
    )

  def recordRejection(
      event: GossipEvent[HotStuffGossipArtifact],
      reason: String,
  ): InMemoryHotStuffSinkSnapshot =
    copy(diagnostics = diagnostics.recordRejection(event.topic, reason))

  def prune(
      retention: HotStuffArtifactSinkRetention,
  ): InMemoryHotStuffSinkSnapshot =
    InMemoryHotStuffSinkSnapshot.prune(this, retention)

  def refreshRetainedCounts: InMemoryHotStuffSinkSnapshot =
    copy(
      diagnostics = diagnostics.updateRetainedCounts(
        HotStuffSinkRetainedCounts.fromSnapshot(this),
      ),
    )

/** Companion for `InMemoryHotStuffSinkSnapshot`. */
object InMemoryHotStuffSinkSnapshot:
  private[hotstuff] def retainNewest[A](
      values: Vector[A],
      cap: Int,
  ): Vector[A] =
    if cap <= 0 then Vector.empty[A]
    else if values.sizeIs <= cap then values
    else values.drop(values.size - cap)

  /** Creates an empty sink snapshot for the supplied relay policy. */
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def empty(
      relayPolicy: HotStuffRelayPolicy,
      retention: HotStuffArtifactSinkRetention =
        HotStuffArtifactSinkRetention.default,
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
      diagnostics = InMemoryHotStuffSinkDiagnostics.empty(
        relayPolicy,
        retention,
      ),
    )

  private[hotstuff] def prune(
      snapshot: InMemoryHotStuffSinkSnapshot,
      retention: HotStuffArtifactSinkRetention,
  ): InMemoryHotStuffSinkSnapshot =
    val finalizedRetainFromByChain =
      snapshot.finalization.view
        .flatMap: (chainId, finalization) =>
          finalization.bestFinalized.map: suggestion =>
            chainId -> floorHeight(
              blockHeightValue(suggestion.anchorHeight),
              retention.finalizedHeightLag,
            )
        .toMap
    val certifiedRetainFromByChain =
      highestCertifiedHeightByChain(snapshot).view
        .mapValues(floorHeight(_, retention.certifiedHeightLag))
        .toMap

    // BlockHeight and HotStuffHeight are both zero-based BigNat scales. Proposal
    // validation enforces proposal.block.height == proposal.window.height, while
    // QCs only carry the HotStuff window height.
    def oldByKnownRetentionFloors(
        chainId: ChainId,
        height: BigInt,
    ): Boolean =
      val floors =
        Vector(
          finalizedRetainFromByChain.get(chainId),
          certifiedRetainFromByChain.get(chainId),
        ).flatten
      floors.nonEmpty && floors.exists(floor => height < floor)

    def oldByBlockHeight(
        chainId: ChainId,
        height: BlockHeight,
    ): Boolean =
      oldByKnownRetentionFloors(chainId, blockHeightValue(height))

    def oldByWindowHeight(
        chainId: ChainId,
        height: HotStuffHeight,
    ): Boolean =
      oldByKnownRetentionFloors(chainId, hotStuffHeightValue(height))

    def hasKnownRetentionFloor(
        chainId: ChainId,
    ): Boolean =
      finalizedRetainFromByChain.contains(chainId) ||
        certifiedRetainFromByChain.contains(chainId)

    val retainedFinalization =
      pruneFinalization(
        snapshot.finalization,
        oldByBlockHeight,
        hasKnownRetentionFloor,
        safetyFaultFallbackCap(retention),
      )
    val protectedProposalIds =
      retainedFinalizationProposalIds(retainedFinalization)
    val retainedProposals =
      snapshot.proposals.filter: (proposalId, proposal) =>
        protectedProposalIds.contains(proposalId) ||
          !oldByBlockHeight(
            proposal.window.chainId,
            proposal.block.height,
          )
    val retainedProposalIds        = retainedProposals.keySet
    val knownProposalIds           = snapshot.proposals.keySet
    val retainedJustifyProposalIds =
      retainedProposals.valuesIterator.map(_.justify.subject.proposalId).toSet
    val retainedQcs =
      snapshot.qcs.filter: (_, qc) =>
        retainedProposalIds.contains(qc.subject.proposalId) ||
          retainedJustifyProposalIds.contains(qc.subject.proposalId) ||
          !oldByWindowHeight(
            qc.subject.window.chainId,
            qc.subject.window.height,
          )
    val retainedVotes =
      snapshot.votes.filter: (_, vote) =>
        val voteIsRecent =
          !oldByWindowHeight(
            vote.window.chainId,
            vote.window.height,
          )
        val targetWasPruned =
          knownProposalIds.contains(vote.targetProposalId) &&
            !retainedProposalIds.contains(vote.targetProposalId)
        voteIsRecent && !targetWasPruned
    val retainedVoteIds = retainedVotes.keySet

    val retainedTimeoutWindows =
      retainRecentWindows(
        timeoutWindows(snapshot),
        retention.retainedTimeoutWindows,
      )
    val retainedNewViewWindows =
      retainRecentWindows(
        snapshot.newViews.valuesIterator.map(_.window),
        retention.retainedNewViewWindows,
      )
    val retentionWatermarks =
      HotStuffSinkRetentionWatermarks(
        finalizedRetainFromHeightByChain = finalizedRetainFromByChain,
        certifiedRetainFromHeightByChain = certifiedRetainFromByChain,
        retainedTimeoutWindowFloorByChain =
          retainedWindowFloorByChain(retainedTimeoutWindows),
        retainedNewViewWindowFloorByChain =
          retainedWindowFloorByChain(retainedNewViewWindows),
      )
    val retainedNewViews =
      snapshot.newViews.filter: (_, newView) =>
        retainedNewViewWindows.contains(newView.window)
    val retainedNewViewIds                  = retainedNewViews.keySet
    val retainedTimeoutSubjectsFromNewViews =
      retainedNewViews.valuesIterator.map(_.timeoutCertificate.subject).toSet
    val retainedTimeoutCertificates =
      snapshot.timeoutCertificates.filter: (subject, _) =>
        retainedTimeoutWindows.contains(subject.window) ||
          retainedTimeoutSubjectsFromNewViews.contains(subject)
    val retainedTimeoutVotes =
      snapshot.timeoutVotes.filter: (_, vote) =>
        retainedTimeoutWindows.contains(vote.subject.window) ||
          retainedTimeoutSubjectsFromNewViews.contains(vote.subject)
    val retainedTimeoutVoteIds = retainedTimeoutVotes.keySet

    val prunedSnapshot = snapshot.copy(
      proposals = retainedProposals,
      votes = retainedVotes,
      accumulator = snapshot.accumulator.pruneToVoteIds(retainedVoteIds),
      timeoutVotes = retainedTimeoutVotes,
      timeoutAccumulator =
        snapshot.timeoutAccumulator.pruneToVoteIds(retainedTimeoutVoteIds),
      timeoutCertificates = retainedTimeoutCertificates,
      newViews = retainedNewViews,
      newViewsBySenderWindow =
        snapshot.newViewsBySenderWindow.filter { case (_, newView) =>
          retainedNewViewIds.contains(newView.newViewId)
        },
      qcs = retainedQcs,
      finalization = retainedFinalization,
      // recordDuplicate accounts duplicate-sample pruning when appending. The
      // prune pass re-applies the cap defensively for snapshots built directly.
      duplicates =
        retainNewest(snapshot.duplicates, retention.retainedDuplicateEvents),
    )
    val beforeCounts = HotStuffSinkRetainedCounts.fromSnapshot(snapshot)
    val afterCounts  = HotStuffSinkRetainedCounts.fromSnapshot(prunedSnapshot)
    prunedSnapshot.copy(
      diagnostics = prunedSnapshot.diagnostics.updateRetention(
        retainedCounts = afterCounts,
        prunedCounts =
          HotStuffSinkPrunedCounts.between(beforeCounts, afterCounts),
        retentionWatermarks = retentionWatermarks,
      ),
    )

  private def retainedFinalizationProposalIds(
      finalization: Map[ChainId, FinalizationTrackerSnapshot],
  ): Set[ProposalId] =
    finalization.valuesIterator
      .flatMap(_.bestFinalized)
      .flatMap: suggestion =>
        Vector(
          suggestion.proposal.proposalId,
          suggestion.finalizedProof.child.proposalId,
          suggestion.finalizedProof.grandchild.proposalId,
          suggestion.proposal.justify.subject.proposalId,
          suggestion.finalizedProof.child.justify.subject.proposalId,
          suggestion.finalizedProof.grandchild.justify.subject.proposalId,
        )
      .toSet

  private def pruneFinalization(
      finalization: Map[ChainId, FinalizationTrackerSnapshot],
      oldByBlockHeight: (ChainId, BlockHeight) => Boolean,
      hasKnownRetentionFloor: ChainId => Boolean,
      fallbackSafetyFaultCap: Int,
  ): Map[ChainId, FinalizationTrackerSnapshot] =
    finalization.view
      .map: (chainId, snapshot) =>
        val retainedSafetyFaults =
          snapshot.safetyFaults.filterNot: fault =>
            oldByBlockHeight(fault.chainId, fault.height)
        val boundedSafetyFaults =
          if hasKnownRetentionFloor(chainId) then retainedSafetyFaults
          else
            retainNewestSafetyFaults(
              retainedSafetyFaults,
              fallbackSafetyFaultCap,
            )
        chainId -> snapshot.copy(
          safetyFaults = boundedSafetyFaults,
        )
      .filter: (_, snapshot) =>
        snapshot.bestFinalized.nonEmpty || snapshot.safetyFaults.nonEmpty
      .toMap

  private def safetyFaultFallbackCap(
      retention: HotStuffArtifactSinkRetention,
  ): Int =
    math
      .min(
        retention.finalizedHeightLag.max(retention.certifiedHeightLag),
        Int.MaxValue.toLong,
      )
      .toInt

  private def retainNewestSafetyFaults(
      faults: Vector[FinalizedAnchorSafetyFault],
      cap: Int,
  ): Vector[FinalizedAnchorSafetyFault] =
    if cap <= 0 then Vector.empty[FinalizedAnchorSafetyFault]
    else if faults.sizeIs <= cap then faults
    else
      faults
        .sortBy(fault => (fault.height, fault.chainId.value))
        .takeRight(cap)

  private def highestCertifiedHeightByChain(
      snapshot: InMemoryHotStuffSinkSnapshot,
  ): Map[ChainId, BigInt] =
    val qcSubjects =
      snapshot.qcs.valuesIterator.map(_.subject) ++
        snapshot.proposals.valuesIterator.map(_.justify.subject)
    qcSubjects
      .foldLeft(Map.empty[ChainId, BigInt]): (acc, subject) =>
        val chainId = subject.window.chainId
        val height  = hotStuffHeightValue(subject.window.height)
        acc.updatedWith(chainId):
          case Some(existing) => Some(existing.max(height))
          case None           => Some(height)

  private def timeoutWindows(
      snapshot: InMemoryHotStuffSinkSnapshot,
  ): Iterator[HotStuffWindow] =
    snapshot.timeoutVotes.valuesIterator.map(_.subject.window) ++
      snapshot.timeoutCertificates.keysIterator.map(_.window) ++
      snapshot.newViews.valuesIterator.map(_.timeoutCertificate.subject.window)

  private def retainRecentWindows(
      windows: IterableOnce[HotStuffWindow],
      cap: Long,
  ): Set[HotStuffWindow] =
    val retainedPerChain =
      math.min(cap, Int.MaxValue.toLong).toInt
    windows.iterator.toVector
      .groupBy(_.chainId)
      .valuesIterator
      .flatMap: chainWindows =>
        chainWindows.distinct
          .sortBy(window =>
            (
              window.height,
              window.view,
              window.validatorSetHash.toHexLower,
            ),
          )
          .takeRight(retainedPerChain)
      .toSet

  private def retainedWindowFloorByChain(
      windows: Set[HotStuffWindow],
  ): Map[ChainId, HotStuffWindow] =
    windows
      .groupBy(_.chainId)
      .view
      .flatMap: (chainId, chainWindows) =>
        chainWindows
          .minByOption(window =>
            (
              window.height,
              window.view,
              window.validatorSetHash.toHexLower,
            ),
          )
          .map(chainId -> _)
      .toMap

  private[hotstuff] def floorHeight(
      height: BigInt,
      lag: Long,
  ): BigInt =
    (height - BigInt(lag)).max(BigInt(0))

  private[hotstuff] def blockHeightValue(
      height: BlockHeight,
  ): BigInt =
    height.toBigNat.toBigInt

  private[hotstuff] def hotStuffHeightValue(
      height: HotStuffHeight,
  ): BigInt =
    height.toBigNat.toBigInt

private final case class SinkPruneGate(
    acceptedEventsSincePrune: Int,
):
  def recordAccepted(
      forcePrune: Boolean,
      retention: HotStuffArtifactSinkRetention,
  ): (Boolean, SinkPruneGate) =
    val nextCount   = acceptedEventsSincePrune + 1
    val shouldPrune =
      forcePrune || nextCount >= retention.pruneEveryAcceptedEvents
    if shouldPrune then true -> SinkPruneGate.empty
    else false               -> copy(acceptedEventsSincePrune = nextCount)

private object SinkPruneGate:
  val empty: SinkPruneGate =
    SinkPruneGate(acceptedEventsSincePrune = 0)

private final case class AnchorKey(
    chainId: ChainId,
    proposalId: ProposalId,
    blockId: BlockId,
)

private final case class CertifiedBlockKey(
    chainId: ChainId,
    proposalId: ProposalId,
    blockId: BlockId,
)

private final case class ObservationState(
    firstSeenByProposal: Map[ProposalId, Instant],
    firstSeenOrder: Vector[ProposalId],
    firstSeenByQcSubject: Map[QuorumCertificateSubject, Instant],
    firstSeenQcOrder: Vector[QuorumCertificateSubject],
    certifiedBlocks: Map[CertifiedBlockKey, CertifiedBlockObservation],
    certifiedBlockOrder: Vector[CertifiedBlockKey],
    currentCertifiedByChain: Map[ChainId, CertifiedBlockObservation],
    certifiedBlockHistory: Vector[CertifiedBlockObservation],
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

  def recordCertifiedObservations(
      qcs: Iterable[QuorumCertificate],
      proposals: Map[ProposalId, Proposal],
      observedAt: Instant,
  ): ObservationState =
    qcs.toVector
      .sortBy(qc =>
        val subject = qc.subject
        (
          firstSeenByQcSubject.getOrElse(subject, observedAt),
          subject.window.height,
          subject.window.view,
          subject.proposalId.toHexLower,
        ),
      )
      .foldLeft(this): (state, qc) =>
        state
          .recordQcFirstSeen(qc.subject, observedAt)
          .materializeCertifiedSubject(qc.subject, proposals)

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
                    // Keep the public observation total even when first-seen
                    // diagnostic history was evicted or observed out of order.
                    // Bucket consumers can reject such timelines via
                    // `descendantFinalityTiming.locallyMonotonic`.
                    val observation =
                      FinalizedAnchorObservation.fromSuggestion(
                        suggestion = suggestion,
                        proposalObservedAt =
                          state.firstSeenByProposal.getOrElse(
                            suggestion.proposal.proposalId,
                            observedAt,
                          ),
                        certifiedObservedAt =
                          state.firstSeenByQcSubject.getOrElse(
                            suggestion.finalizedProof.child.justify.subject,
                            observedAt,
                          ),
                        childProposalObservedAt =
                          state.firstSeenByProposal.getOrElse(
                            suggestion.finalizedProof.child.proposalId,
                            observedAt,
                          ),
                        childCertifiedObservedAt =
                          state.firstSeenByQcSubject.getOrElse(
                            suggestion.finalizedProof.grandchild.justify.subject,
                            observedAt,
                          ),
                        grandchildProposalObservedAt =
                          state.firstSeenByProposal.getOrElse(
                            suggestion.finalizedProof.grandchild.proposalId,
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
            .exists(
              _.newFinalized.blockId === nextRange.newFinalized.blockId,
            ) =>
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

  private def recordQcFirstSeen(
      subject: QuorumCertificateSubject,
      observedAt: Instant,
  ): ObservationState =
    if firstSeenByQcSubject.contains(subject) then this
    else
      val (boundedFirstSeen, boundedOrder) =
        ObservationState.appendBounded(
          values = firstSeenByQcSubject,
          order = firstSeenQcOrder,
          key = subject,
          value = observedAt,
          cap = ObservationState.FirstSeenQcCap,
        )
      copy(
        firstSeenByQcSubject = boundedFirstSeen,
        firstSeenQcOrder = boundedOrder,
      )

  private[hotstuff] def materializeCertifiedProposal(
      proposal: Proposal,
  ): ObservationState =
    val subject =
      QuorumCertificateSubject(
        window = proposal.window,
        proposalId = proposal.proposalId,
        blockId = proposal.targetBlockId,
      )
    firstSeenByQcSubject
      .get(subject)
      .fold(this): certifiedObservedAt =>
        recordCertifiedBlock(
          proposal = proposal,
          qcSubject = subject,
          certifiedObservedAt = certifiedObservedAt,
        )

  private def materializeCertifiedSubject(
      subject: QuorumCertificateSubject,
      proposals: Map[ProposalId, Proposal],
  ): ObservationState =
    firstSeenByQcSubject.get(subject) match
      case Some(certifiedObservedAt) =>
        proposals.get(subject.proposalId) match
          case Some(proposal)
              if proposal.window === subject.window &&
                proposal.targetBlockId === subject.blockId =>
            recordCertifiedBlock(
              proposal = proposal,
              qcSubject = subject,
              certifiedObservedAt = certifiedObservedAt,
            )
          case _ =>
            this
      case None =>
        this

  private def recordCertifiedBlock(
      proposal: Proposal,
      qcSubject: QuorumCertificateSubject,
      certifiedObservedAt: Instant,
  ): ObservationState =
    val key = CertifiedBlockKey(
      chainId = proposal.window.chainId,
      proposalId = proposal.proposalId,
      blockId = proposal.targetBlockId,
    )
    if certifiedBlocks.contains(key) then this
    else
      val observation =
        CertifiedBlockObservation.fromProposal(
          proposal = proposal,
          qcSubject = qcSubject,
          proposalObservedAt = firstSeenByProposal.getOrElse(
            proposal.proposalId,
            certifiedObservedAt,
          ),
          certifiedObservedAt = certifiedObservedAt,
        )
      val (boundedObserved, boundedOrder) =
        ObservationState.appendBounded(
          values = certifiedBlocks,
          order = certifiedBlockOrder,
          key = key,
          value = observation,
          cap = ObservationState.CertifiedBlockCap,
        )
      val nextCurrentByChain =
        currentCertifiedByChain.get(observation.chainId) match
          case Some(existing) if !isNewerCertified(observation, existing) =>
            currentCertifiedByChain
          case _ =>
            currentCertifiedByChain.updated(observation.chainId, observation)
      copy(
        certifiedBlocks = boundedObserved,
        certifiedBlockOrder = boundedOrder,
        currentCertifiedByChain = nextCurrentByChain,
        certifiedBlockHistory = ObservationState.appendBoundedValue(
          values = certifiedBlockHistory,
          value = observation,
          cap = ObservationState.CertifiedBlockCap,
        ),
      )

  private def isNewerCertified(
      candidate: CertifiedBlockObservation,
      existing: CertifiedBlockObservation,
  ): Boolean =
    Ordering[BlockHeight].gt(candidate.height, existing.height) ||
      (
        candidate.height === existing.height &&
          !candidate.certifiedObservedAt.isBefore(existing.certifiedObservedAt)
      )

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
  private val FirstSeenQcCap    = 1024
  private val CertifiedBlockCap = 256
  private val ObservedAnchorCap = 256
  private val TxRangeHistoryCap = 256

  val empty: ObservationState =
    ObservationState(
      firstSeenByProposal = Map.empty[ProposalId, Instant],
      firstSeenOrder = Vector.empty[ProposalId],
      firstSeenByQcSubject = Map.empty[QuorumCertificateSubject, Instant],
      firstSeenQcOrder = Vector.empty[QuorumCertificateSubject],
      certifiedBlocks = Map.empty[CertifiedBlockKey, CertifiedBlockObservation],
      certifiedBlockOrder = Vector.empty[CertifiedBlockKey],
      currentCertifiedByChain = Map.empty[ChainId, CertifiedBlockObservation],
      certifiedBlockHistory = Vector.empty[CertifiedBlockObservation],
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
    pruneGate: SinkPruneGate,
):
  def recordAcceptedSnapshot(
      updatedSnapshot: InMemoryHotStuffSinkSnapshot,
      updatedObservations: ObservationState,
      retention: HotStuffArtifactSinkRetention,
      forcePrune: Boolean,
  ): SinkState =
    val (shouldPrune, updatedGate) =
      pruneGate.recordAccepted(forcePrune, retention)
    val retainedSnapshot =
      if shouldPrune then updatedSnapshot.prune(retention)
      else updatedSnapshot.refreshRetainedCounts
    copy(
      snapshot = retainedSnapshot,
      observations = updatedObservations,
      pruneGate = updatedGate,
    )

private object SinkState:
  def empty(
      relayPolicy: HotStuffRelayPolicy,
      sinkRetention: HotStuffArtifactSinkRetention,
  ): SinkState =
    SinkState(
      snapshot = InMemoryHotStuffSinkSnapshot.empty(relayPolicy, sinkRetention),
      observations = ObservationState.empty,
      pruneGate = SinkPruneGate.empty,
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
    val pruneCount =
      math.max(0, inserted.size - retention.retainedEventsPerTopic)
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
    notifier: GossipSourceAppendNotifier[F],
    ref: Ref[F, Map[ChainTopic, SourceTopicState]],
) extends GossipArtifactSource[F, HotStuffGossipArtifact]
    with HotStuffArtifactPublisher[F]:
  def append(
      artifact: HotStuffGossipArtifact,
      ts: Instant,
  ): F[GossipEvent[HotStuffGossipArtifact]] =
    clock.now.flatMap: availableAt =>
      ref
        .modify: state =>
          val chainId = artifact match
            case HotStuffGossipArtifact.ProposalArtifact(proposal) =>
              proposal.window.chainId
            case HotStuffGossipArtifact.VoteArtifact(vote) =>
              vote.window.chainId
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
        .flatTap(event =>
          notifier
            .sourceAppended(ChainTopic(event.chainId, event.topic))
            .attempt
            .void,
        )

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
            state.updated(chainTopic, updatedTopicState)
          ,
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
      val missCount   =
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
        eventsByTopic = state.view.mapValues(_.events.map(_.event)).toMap,
        diagnostics = InMemoryHotStuffSourceDiagnostics(
          retainedEventsPerTopic = retention.retainedEventsPerTopic,
          retainedEventsByTopic = state.view.mapValues(_.events.size).toMap,
          appendedEventsByTopic = state.view.mapValues(_.appendedCount).toMap,
          prunedEventsByTopic = state.view.mapValues(_.prunedCount).toMap,
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
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
object InMemoryHotStuffArtifactSource:
  /** Creates a new in-memory gossip artifact source. */
  def create[F[_]: Sync](using
      clock: GossipClock[F],
  ): F[InMemoryHotStuffArtifactSource[F]] =
    createWithRetention[F](HotStuffArtifactSourceRetention.default)

  /** Creates a new in-memory gossip artifact source with append wakeups. */
  def createWithNotifier[F[_]: Sync](
      notifier: GossipSourceAppendNotifier[F],
  )(using
      clock: GossipClock[F],
  ): F[InMemoryHotStuffArtifactSource[F]] =
    createWithRetentionAndNotifier[F](
      HotStuffArtifactSourceRetention.default,
      notifier,
    )

  /** Creates a new in-memory gossip artifact source with explicit retention. */
  def createWithRetention[F[_]: Sync](
      retention: HotStuffArtifactSourceRetention,
  )(using
      clock: GossipClock[F],
  ): F[InMemoryHotStuffArtifactSource[F]] =
    createWithRetentionAndNotifier[F](
      retention,
      GossipSourceAppendNotifier.noop[F],
    )

  /** Creates a new in-memory gossip artifact source with retention and wakeups.
    */
  def createWithRetentionAndNotifier[F[_]: Sync](
      retention: HotStuffArtifactSourceRetention,
      notifier: GossipSourceAppendNotifier[F],
  )(using
      clock: GossipClock[F],
  ): F[InMemoryHotStuffArtifactSource[F]] =
    Ref
      .of[F, Map[ChainTopic, SourceTopicState]](Map.empty)
      .map(new InMemoryHotStuffArtifactSource[F](clock, retention, notifier, _))

/** In-memory implementation of a gossip artifact sink for HotStuff artifacts,
  * handling validation, QC assembly, and finalization tracking.
  */
final class InMemoryHotStuffArtifactSink[F[_]: Sync] private (
    clock: GossipClock[F],
    validatorSet: ValidatorSet,
    relayPolicy: HotStuffRelayPolicy,
    sinkRetention: HotStuffArtifactSinkRetention,
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
                      snapshot = snapshot.recordDuplicate(event, sinkRetention),
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
                    val qcsToObserve =
                      Vector(proposal.justify) ++ assembled.toList
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
                        .recordCertifiedObservations(
                          qcsToObserve,
                          updatedProposals,
                          localObservedAt,
                        )
                        .materializeCertifiedProposal(proposal)
                        .recordFinalization(
                          updatedSnapshot.finalization,
                          updatedSnapshot.proposals.values,
                          localObservedAt,
                        )
                    val previousWatermarks =
                      snapshot.diagnostics.retentionWatermarks
                    val forcePrune =
                      finalizationFloorMoved(
                        previousWatermarks,
                        updatedSnapshot.finalization,
                      ) ||
                        certifiedFloorMoved(
                          previousWatermarks,
                          qcsToObserve.map(_.subject),
                        )
                    state.recordAcceptedSnapshot(
                      updatedSnapshot = updatedSnapshot,
                      updatedObservations = updatedObservations,
                      retention = sinkRetention,
                      forcePrune = forcePrune,
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
    clock.now.flatMap: localObservedAt =>
      ref
        .modify: state =>
          val snapshot = state.snapshot
          if snapshot.votes.contains(vote.voteId) then
            state.copy(
              snapshot = snapshot.recordDuplicate(event, sinkRetention),
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
                      snapshot =
                        snapshot.recordRejection(event, rejection.reason),
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
                    val updatedObservations =
                      maybeQc.fold(state.observations): qc =>
                        state.observations.recordCertifiedObservations(
                          Vector(qc),
                          snapshot.proposals,
                          localObservedAt,
                        )
                    val storedSnapshot =
                      snapshot.copy(
                        votes = updatedVotes,
                        accumulator = updatedAccumulator,
                        qcs = updatedQcs,
                      )
                    val updatedSnapshot =
                      withCurrentFinalization(snapshot, storedSnapshot)
                    val previousWatermarks =
                      snapshot.diagnostics.retentionWatermarks
                    val forcePrune =
                      finalizationFloorMoved(
                        previousWatermarks,
                        updatedSnapshot.finalization,
                      ) ||
                        maybeQc.exists(qc =>
                          certifiedFloorMoved(
                            previousWatermarks,
                            Vector(qc.subject),
                          ),
                        )
                    state.recordAcceptedSnapshot(
                      updatedSnapshot = updatedSnapshot,
                      updatedObservations = updatedObservations,
                      retention = sinkRetention,
                      forcePrune = forcePrune,
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
            snapshot = snapshot.recordDuplicate(event, sinkRetention),
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
                  val storedSnapshot =
                    snapshot.copy(
                      timeoutVotes = updatedTimeoutVotes,
                      timeoutAccumulator = updatedAccumulator,
                      timeoutCertificates = updatedTimeoutCertificates,
                    )
                  val updatedSnapshot =
                    withCurrentFinalization(snapshot, storedSnapshot)
                  state.recordAcceptedSnapshot(
                    updatedSnapshot = updatedSnapshot,
                    updatedObservations = state.observations,
                    retention = sinkRetention,
                    forcePrune = false,
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
            snapshot = snapshot.recordDuplicate(event, sinkRetention),
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
                snapshot = snapshot.recordDuplicate(event, sinkRetention),
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
                  val storedSnapshot =
                    snapshot.copy(
                      newViews =
                        snapshot.newViews.updated(newView.newViewId, newView),
                      newViewsBySenderWindow =
                        snapshot.newViewsBySenderWindow.updated(
                          senderWindowKey,
                          newView,
                        ),
                    )
                  val updatedSnapshot =
                    withCurrentFinalization(snapshot, storedSnapshot)
                  state.recordAcceptedSnapshot(
                    updatedSnapshot = updatedSnapshot,
                    updatedObservations = state.observations,
                    retention = sinkRetention,
                    forcePrune = false,
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

  private def certifiedFloorMoved(
      previousWatermarks: HotStuffSinkRetentionWatermarks,
      subjects: IterableOnce[QuorumCertificateSubject],
  ): Boolean =
    subjects.iterator.exists: subject =>
      val chainId   = subject.window.chainId
      val nextFloor =
        InMemoryHotStuffSinkSnapshot.floorHeight(
          InMemoryHotStuffSinkSnapshot.hotStuffHeightValue(
            subject.window.height,
          ),
          sinkRetention.certifiedHeightLag,
        )
      val previousFloor =
        previousWatermarks.certifiedRetainFromHeightByChain
          .getOrElse(chainId, BigInt(-1))
      nextFloor > previousFloor

  private def finalizationFloorMoved(
      previousWatermarks: HotStuffSinkRetentionWatermarks,
      finalization: Map[ChainId, FinalizationTrackerSnapshot],
  ): Boolean =
    finalization.exists: (chainId, snapshot) =>
      snapshot.bestFinalized.exists: suggestion =>
        val nextFloor =
          InMemoryHotStuffSinkSnapshot.floorHeight(
            InMemoryHotStuffSinkSnapshot.blockHeightValue(
              suggestion.anchorHeight,
            ),
            sinkRetention.finalizedHeightLag,
          )
        val previousFloor =
          previousWatermarks.finalizedRetainFromHeightByChain
            .getOrElse(chainId, BigInt(-1))
        nextFloor > previousFloor

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
    val refreshed =
      HotStuffFinalizationTracker.trackAll(
        snapshot.proposals.values,
      )
    snapshot.copy(
      finalization = mergeSafetyFaults(snapshot.finalization, refreshed),
    )

  private def withCurrentFinalization(
      previous: InMemoryHotStuffSinkSnapshot,
      updated: InMemoryHotStuffSinkSnapshot,
  ): InMemoryHotStuffSinkSnapshot =
    if previous.proposals.keySet === updated.proposals.keySet then
      updated.copy(finalization = previous.finalization)
    else withFinalization(updated)

  private def mergeSafetyFaults(
      previous: Map[ChainId, FinalizationTrackerSnapshot],
      refreshed: Map[ChainId, FinalizationTrackerSnapshot],
  ): Map[ChainId, FinalizationTrackerSnapshot] =
    (previous.keySet ++ refreshed.keySet).iterator
      .map: chainId =>
        val refreshedSnapshot =
          refreshed.getOrElse(chainId, FinalizationTrackerSnapshot.empty)
        val previousFaults =
          previous
            .get(chainId)
            .fold(Vector.empty[FinalizedAnchorSafetyFault])(
              _.safetyFaults,
            )
        val mergedFaults =
          // A chain can surface the same safety fault across repeated
          // finalization refreshes. The fault identity is the finalized
          // (chainId, height); conflicting anchors carry the evidence set.
          (refreshedSnapshot.safetyFaults ++ previousFaults)
            .groupBy(fault => (fault.chainId, fault.height))
            .valuesIterator
            .flatMap: faults =>
              faults.headOption.map: first =>
                first.copy(
                  conflictingAnchors = faults
                    .flatMap(_.conflictingAnchors)
                    .distinctBy(_.blockId.toHexLower)
                    .sortBy(anchor =>
                      (
                        anchor.blockId.toHexLower,
                        anchor.proposalId.toHexLower,
                      ),
                    ),
                )
            .toVector
            .sortBy(fault => (fault.height, fault.chainId.value))
        chainId -> refreshedSnapshot.copy(safetyFaults = mergedFaults)
      .toMap

  /** Returns the current sink snapshot. */
  def snapshot: F[InMemoryHotStuffSinkSnapshot] =
    ref.get.map(_.snapshot)

  private[hotstuff] def finalizationObservations
      : F[Map[ChainId, FinalizedAnchorObservation]] =
    ref.get.map(_.observations.currentByChain)

  private[hotstuff] def certifiedBlockObservations
      : F[Map[ChainId, CertifiedBlockObservation]] =
    ref.get.map(_.observations.currentCertifiedByChain)

  private[hotstuff] def recentCertifiedBlockObservations
      : F[Vector[CertifiedBlockObservation]] =
    ref.get.map(_.observations.certifiedBlockHistory)

  private[hotstuff] def finalizedTxRangeObservations
      : F[Map[ChainId, FinalizedTxRangeObservation]] =
    ref.get.map(_.observations.currentTxRangeByChain)

  private[hotstuff] def recentFinalizedTxRangeObservations
      : F[Vector[FinalizedTxRangeObservation]] =
    ref.get.map(_.observations.txRangeHistory)

  private[hotstuff] def sinkDiagnostics: F[InMemoryHotStuffSinkDiagnostics] =
    ref.get.map(_.snapshot.diagnostics)

/** Companion for `InMemoryHotStuffArtifactSink`. */
object InMemoryHotStuffArtifactSink:
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def create[F[_]: Sync](
      validatorSet: ValidatorSet,
      relayPolicy: HotStuffRelayPolicy,
      relayPublisher: HotStuffArtifactPublisher[F],
      sinkRetention: HotStuffArtifactSinkRetention =
        HotStuffArtifactSinkRetention.default,
  )(using
      clock: GossipClock[F],
  ): F[InMemoryHotStuffArtifactSink[F]] =
    for ref <- Ref.of[F, SinkState](SinkState.empty(relayPolicy, sinkRetention))
    yield new InMemoryHotStuffArtifactSink[F](
      clock,
      validatorSet,
      relayPolicy,
      sinkRetention,
      relayPublisher,
      HotStuffRuntimeScheduling.allowAll[F],
      ref,
    )

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def createWithProposalValidation[
      F[_]: Sync,
      TxRef: ByteEncoder: Hash,
      ResultRef: ByteEncoder,
      Event: ByteEncoder,
  ](
      validatorSet: ValidatorSet,
      relayPolicy: HotStuffRelayPolicy,
      relayPublisher: HotStuffArtifactPublisher[F],
      blockQuery: BlockQuery[F, TxRef, ResultRef, Event],
      sinkRetention: HotStuffArtifactSinkRetention =
        HotStuffArtifactSinkRetention.default,
  )(
      classifyTx: TxRef => org.sigilaris.core.application.scheduling.SchedulingClassification,
  )(using
      clock: GossipClock[F],
  ): F[InMemoryHotStuffArtifactSink[F]] =
    for ref <- Ref.of[F, SinkState](SinkState.empty(relayPolicy, sinkRetention))
    yield new InMemoryHotStuffArtifactSink[F](
      clock,
      validatorSet,
      relayPolicy,
      sinkRetention,
      relayPublisher,
      HotStuffRuntimeScheduling.proposalValidationFromBlockQuery(
        validatorSet = validatorSet,
        blockQuery = blockQuery,
      )(classifyTx),
      ref,
    )
