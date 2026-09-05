package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import cats.syntax.eq.*

import scala.annotation.tailrec
import scala.collection.immutable.TreeSet

import org.sigilaris.core.datatype.BigNat
import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.gossip.{ChainId, ChainTopic, GossipTopic}

final case class HotStuffGossipDiagnosticsProjectionPolicy private (
    childWarningEstimatedBytes: Long,
    childWarningVariableEntries: Int,
    childEmergencyEstimatedBytes: Long,
    childEmergencyVariableEntries: Int,
    parentEmergencyEstimatedBytes: Long,
    parentEmergencyVariableEntries: Int,
    projectionDroppedEntryGroupLimit: Int,
    maxDecimalDigits: Int,
    maxStringUtf8Bytes: Int,
    maxVectorEntries: Int,
)

object HotStuffGossipDiagnosticsProjectionPolicy:
  val MaxDecimalDigitsLimit: Int               = 4096
  val MaxProjectionDroppedEntryGroupLimit: Int = 4096
  val MaxStringUtf8BytesLimit: Int             = 1024 * 1024
  val MaxVectorEntriesLimit: Int               = 65536
  val MaxEstimateProductLimit: Long            =
    64L * 1024L * 1024L

  private def decimalDigits(
      value: Int,
  ): Int =
    value.toString.length

  def fromValues(
      childWarningEstimatedBytes: Long,
      childWarningVariableEntries: Int,
      childEmergencyEstimatedBytes: Long,
      childEmergencyVariableEntries: Int,
      parentEmergencyEstimatedBytes: Long,
      parentEmergencyVariableEntries: Int,
      projectionDroppedEntryGroupLimit: Int,
      maxDecimalDigits: Int,
      maxStringUtf8Bytes: Int,
      maxVectorEntries: Int,
  ): Either[String, HotStuffGossipDiagnosticsProjectionPolicy] =
    // Single-field hard caps are validated before cross-field constraints so
    // callers see the narrowest offending field first.
    val checks = Vector(
      (childWarningEstimatedBytes > 0L) ->
        "childWarningEstimatedBytes must be positive",
      (childWarningVariableEntries > 0) ->
        "childWarningVariableEntries must be positive",
      (childEmergencyEstimatedBytes >= childWarningEstimatedBytes) ->
        "childEmergencyEstimatedBytes must be greater than or equal to childWarningEstimatedBytes",
      (childEmergencyVariableEntries >= childWarningVariableEntries) ->
        "childEmergencyVariableEntries must be greater than or equal to childWarningVariableEntries",
      (parentEmergencyEstimatedBytes > 0L) ->
        "parentEmergencyEstimatedBytes must be positive",
      (parentEmergencyVariableEntries > 0) ->
        "parentEmergencyVariableEntries must be positive",
      (maxVectorEntries > 0) -> "maxVectorEntries must be positive",
      (maxVectorEntries <= MaxVectorEntriesLimit) ->
        "maxVectorEntries must be less than or equal to 65536",
      (projectionDroppedEntryGroupLimit > 0) ->
        "projectionDroppedEntryGroupLimit must be positive",
      (
        projectionDroppedEntryGroupLimit <= MaxProjectionDroppedEntryGroupLimit
      ) ->
        "projectionDroppedEntryGroupLimit must be less than or equal to 4096",
      (projectionDroppedEntryGroupLimit <= maxVectorEntries) ->
        "projectionDroppedEntryGroupLimit must be less than or equal to maxVectorEntries",
      (maxDecimalDigits > 0) -> "maxDecimalDigits must be positive",
      (maxDecimalDigits <= MaxDecimalDigitsLimit) ->
        "maxDecimalDigits must be less than or equal to 4096",
      (maxDecimalDigits >= decimalDigits(maxVectorEntries)) ->
        "maxDecimalDigits must be greater than or equal to maxVectorEntries digit count",
      (maxStringUtf8Bytes > 0) -> "maxStringUtf8Bytes must be positive",
      (maxStringUtf8Bytes <= MaxStringUtf8BytesLimit) ->
        "maxStringUtf8Bytes must be less than or equal to 1048576",
      (maxVectorEntries.toLong * maxStringUtf8Bytes.toLong <= MaxEstimateProductLimit) ->
        "maxVectorEntries multiplied by maxStringUtf8Bytes exceeds the supported estimate envelope",
    )
    checks.collectFirst { case (false, error) => error } match
      case Some(error) =>
        Left[String, HotStuffGossipDiagnosticsProjectionPolicy](error)
      case None =>
        Right[String, HotStuffGossipDiagnosticsProjectionPolicy](
          new HotStuffGossipDiagnosticsProjectionPolicy(
            childWarningEstimatedBytes = childWarningEstimatedBytes,
            childWarningVariableEntries = childWarningVariableEntries,
            childEmergencyEstimatedBytes = childEmergencyEstimatedBytes,
            childEmergencyVariableEntries = childEmergencyVariableEntries,
            parentEmergencyEstimatedBytes = parentEmergencyEstimatedBytes,
            parentEmergencyVariableEntries = parentEmergencyVariableEntries,
            projectionDroppedEntryGroupLimit = projectionDroppedEntryGroupLimit,
            maxDecimalDigits = maxDecimalDigits,
            maxStringUtf8Bytes = maxStringUtf8Bytes,
            maxVectorEntries = maxVectorEntries,
          ),
        )

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def unsafe(
      childWarningEstimatedBytes: Long,
      childWarningVariableEntries: Int,
      childEmergencyEstimatedBytes: Long,
      childEmergencyVariableEntries: Int,
      parentEmergencyEstimatedBytes: Long,
      parentEmergencyVariableEntries: Int,
      projectionDroppedEntryGroupLimit: Int,
      maxDecimalDigits: Int,
      maxStringUtf8Bytes: Int,
      maxVectorEntries: Int,
  ): HotStuffGossipDiagnosticsProjectionPolicy =
    fromValues(
      childWarningEstimatedBytes = childWarningEstimatedBytes,
      childWarningVariableEntries = childWarningVariableEntries,
      childEmergencyEstimatedBytes = childEmergencyEstimatedBytes,
      childEmergencyVariableEntries = childEmergencyVariableEntries,
      parentEmergencyEstimatedBytes = parentEmergencyEstimatedBytes,
      parentEmergencyVariableEntries = parentEmergencyVariableEntries,
      projectionDroppedEntryGroupLimit = projectionDroppedEntryGroupLimit,
      maxDecimalDigits = maxDecimalDigits,
      maxStringUtf8Bytes = maxStringUtf8Bytes,
      maxVectorEntries = maxVectorEntries,
    ) match
      case Right(policy) => policy
      case Left(error)   => throw new IllegalArgumentException(error)

  val default: HotStuffGossipDiagnosticsProjectionPolicy =
    unsafe(
      childWarningEstimatedBytes = 256L * 1024L,
      childWarningVariableEntries = 512,
      childEmergencyEstimatedBytes = 1024L * 1024L,
      childEmergencyVariableEntries = 4096,
      parentEmergencyEstimatedBytes = 1024L * 1024L,
      parentEmergencyVariableEntries = 4096,
      projectionDroppedEntryGroupLimit = 128,
      maxDecimalDigits = 4096,
      maxStringUtf8Bytes = 8192,
      maxVectorEntries = 4096,
    )

/** Projection result.
  *
  * `warnings` is the authoritative warning stream for callers because
  * `snapshot` is absent when parent assembly fails. When a snapshot is present,
  * `snapshot.warnings` mirrors this vector for self-contained status payloads.
  */
final case class HotStuffGossipDiagnosticsProjectionResult(
    snapshot: Option[HotStuffGossipDiagnosticsSnapshot],
    warnings: Vector[HotStuffGossipDiagnosticsProjectionWarning],
)

object HotStuffGossipDiagnosticsProjection:
  type DiagnosticsRead[A] = Either[String, Option[A]]

  private val ParentFixedEstimatedBytes: BigInt       = BigInt(256)
  private val WarningFixedEstimatedBytes: BigInt      = BigInt(160)
  private val DropFixedEstimatedBytes: BigInt         = BigInt(256)
  private val DropOverflowFixedEstimatedBytes: BigInt = BigInt(192)
  private val ValidatorSetHashHexBytes: BigInt        =
    BigInt(64)
  private val SourceFixedEstimatedBytes: BigInt =
    BigInt(512)
  private val SinkFixedEstimatedBytes: BigInt =
    BigInt(1024)
  private val ChainTopicFixedEstimatedBytes: BigInt =
    BigInt(128)
  private val TopicCountFixedEstimatedBytes: BigInt =
    BigInt(96)
  private val TopicReasonFixedEstimatedBytes: BigInt =
    BigInt(160)
  private val ChainHeightFixedEstimatedBytes: BigInt =
    BigInt(96)
  private val WindowFixedEstimatedBytes: BigInt =
    BigInt(192)
  private val RetentionPolicyFixedEstimatedBytes: BigInt =
    BigInt(512)
  private val RetentionCountsFixedEstimatedBytes: BigInt =
    BigInt(1024)

  def projectDefaultPolicy(
      sourceRead: DiagnosticsRead[InMemoryHotStuffSourceDiagnostics],
      sinkRead: DiagnosticsRead[InMemoryHotStuffSinkDiagnostics],
  ): HotStuffGossipDiagnosticsProjectionResult =
    project(
      sourceRead = sourceRead,
      sinkRead = sinkRead,
      policy = HotStuffGossipDiagnosticsProjectionPolicy.default,
    )

  def project(
      sourceRead: DiagnosticsRead[InMemoryHotStuffSourceDiagnostics],
      sinkRead: DiagnosticsRead[InMemoryHotStuffSinkDiagnostics],
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): HotStuffGossipDiagnosticsProjectionResult =
    val sourceState =
      componentState(
        component = "source",
        read = sourceRead,
        projectComponent = source => projectSource(source, policy),
        sourceSnapshot = snapshot => Some(snapshot),
        sinkSnapshot = _ => None,
        policy = policy,
      )
    val sinkState =
      componentState(
        component = "sink",
        read = sinkRead,
        projectComponent = sink => projectSink(sink, policy),
        sourceSnapshot = _ => None,
        sinkSnapshot = snapshot => Some(snapshot),
        policy = policy,
      )
    assemble(sourceState, sinkState, policy)

  /** Projects diagnostics values that have already been read by the caller.
    *
    * Use [[project]] when a source or sink read can fail and the failure should
    * be represented as a component read-failure warning. Callers with fallible
    * reads must not use this helper, because it treats `None` as legitimate
    * absence and cannot emit read-failure warnings.
    */
  def projectAvailable(
      source: Option[InMemoryHotStuffSourceDiagnostics],
      sink: Option[InMemoryHotStuffSinkDiagnostics],
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): HotStuffGossipDiagnosticsProjectionResult =
    project(
      Right[String, Option[InMemoryHotStuffSourceDiagnostics]](source),
      Right[String, Option[InMemoryHotStuffSinkDiagnostics]](sink),
      policy,
    )

  private def projectSource(
      diagnostics: InMemoryHotStuffSourceDiagnostics,
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): Either[ProjectionFailure, ComponentProjection[
    HotStuffGossipSourceDiagnosticsSnapshot,
  ]] =
    for retainedEventsLimitPerTopic <-
        fixedBigNat(
          component = "source",
          field = "retainedEventsLimitPerTopic",
          value = BigInt(diagnostics.retainedEventsPerTopic),
          policy = policy,
        )
    yield
      val retained =
        chainTopicCounts(
          component = "source",
          field = "retainedEventsByTopic",
          values = diagnostics.retainedEventsByTopic,
          toBigInt = value => BigInt(value),
          policy = policy,
        )
      val appended =
        chainTopicCounts(
          component = "source",
          field = "appendedEventsByTopic",
          values = diagnostics.appendedEventsByTopic,
          toBigInt = value => BigInt(value),
          policy = policy,
        )
      val pruned =
        chainTopicCounts(
          component = "source",
          field = "prunedEventsByTopic",
          values = diagnostics.prunedEventsByTopic,
          toBigInt = value => BigInt(value),
          policy = policy,
        )
      val readByIdMisses =
        chainTopicCounts(
          component = "source",
          field = "readByIdMissesByTopic",
          values = diagnostics.readByIdMissesByTopic,
          toBigInt = value => BigInt(value),
          policy = policy,
        )
      val invalidCursorRejections =
        chainTopicCounts(
          component = "source",
          field = "invalidCursorRejectionsByTopic",
          values = diagnostics.invalidCursorRejectionsByTopic,
          toBigInt = value => BigInt(value),
          policy = policy,
        )
      val staleCursorRejections =
        chainTopicCounts(
          component = "source",
          field = "staleCursorRejectionsByTopic",
          values = diagnostics.staleCursorRejectionsByTopic,
          toBigInt = value => BigInt(value),
          policy = policy,
        )
      val snapshot =
        HotStuffGossipSourceDiagnosticsSnapshot(
          retainedEventsLimitPerTopic = retainedEventsLimitPerTopic,
          retainedEventsByTopic = retained.entries,
          appendedEventsByTopic = appended.entries,
          prunedEventsByTopic = pruned.entries,
          readByIdMissesByTopic = readByIdMisses.entries,
          invalidCursorRejectionsByTopic = invalidCursorRejections.entries,
          staleCursorRejectionsByTopic = staleCursorRejections.entries,
        )
      val drops =
        retained.drops ++
          appended.drops ++
          pruned.drops ++
          readByIdMisses.drops ++
          invalidCursorRejections.drops ++
          staleCursorRejections.drops
      ComponentProjection(
        snapshot = snapshot,
        variableEntries = retained.entries.size.toLong +
          appended.entries.size.toLong +
          pruned.entries.size.toLong +
          readByIdMisses.entries.size.toLong +
          invalidCursorRejections.entries.size.toLong +
          staleCursorRejections.entries.size.toLong,
        estimatedBytes = estimateSource(snapshot),
        drops = drops,
      )

  private def projectSink(
      diagnostics: InMemoryHotStuffSinkDiagnostics,
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): Either[ProjectionFailure, ComponentProjection[
    HotStuffGossipSinkDiagnosticsSnapshot,
  ]] =
    for
      policyMode <- requiredString(
        component = "sink",
        field = "policyMode",
        value = diagnostics.policyMode,
        policy = policy,
      )
      retentionPolicy <- projectRetentionPolicy(
        diagnostics.retentionPolicy,
        policy,
      )
      retainedCounts <- projectRetainedCounts(
        diagnostics.retainedCounts,
        policy,
      )
      prunedCounts <- projectPrunedCounts(diagnostics.prunedCounts, policy)
    yield
      val retentionWatermarks =
        projectRetentionWatermarks(diagnostics.retentionWatermarks, policy)
      val relayed =
        topicCounts(
          component = "sink",
          field = "relayedValidatedArtifactsByTopic",
          values = diagnostics.relayedValidatedArtifactsByTopic,
          policy = policy,
        )
      val duplicates =
        topicCounts(
          component = "sink",
          field = "duplicateArtifactsSuppressedByTopic",
          values = diagnostics.duplicateArtifactsSuppressedByTopic,
          policy = policy,
        )
      val rejected =
        topicReasonCounts(
          component = "sink",
          field = "rejectedArtifactsByTopicAndReason",
          values = diagnostics.rejectedArtifactsByTopicAndReason,
          policy = policy,
        )
      val snapshot =
        HotStuffGossipSinkDiagnosticsSnapshot(
          policyMode = policyMode,
          retentionPolicy = retentionPolicy,
          retainedCounts = retainedCounts,
          prunedCounts = prunedCounts,
          retentionWatermarks = retentionWatermarks.entries,
          relayedValidatedArtifactsByTopic = relayed.entries,
          duplicateArtifactsSuppressedByTopic = duplicates.entries,
          rejectedArtifactsByTopicAndReason = rejected.entries,
        )
      val drops =
        retentionWatermarks.drops ++
          relayed.drops ++
          duplicates.drops ++
          rejected.drops
      ComponentProjection(
        snapshot = snapshot,
        // Fixed retention/count structures are represented in estimatedBytes;
        // variableEntries counts only emitted variable-length vectors.
        variableEntries = retentionWatermarks.variableEntries +
          relayed.entries.size.toLong +
          duplicates.entries.size.toLong +
          rejected.entries.size.toLong,
        estimatedBytes = estimateSink(snapshot),
        drops = drops,
      )

  private def projectRetentionPolicy(
      retention: HotStuffArtifactSinkRetention,
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): Either[ProjectionFailure, HotStuffSinkRetentionPolicySnapshot] =
    for
      finalizedHeightLag <- fixedBigNat(
        "sink",
        "retentionPolicy.finalizedHeightLag",
        BigInt(retention.finalizedHeightLag),
        policy,
      )
      certifiedHeightLag <- fixedBigNat(
        "sink",
        "retentionPolicy.certifiedHeightLag",
        BigInt(retention.certifiedHeightLag),
        policy,
      )
      retainedTimeoutWindows <- fixedBigNat(
        "sink",
        "retentionPolicy.retainedTimeoutWindows",
        BigInt(retention.retainedTimeoutWindows),
        policy,
      )
      retainedNewViewWindows <- fixedBigNat(
        "sink",
        "retentionPolicy.retainedNewViewWindows",
        BigInt(retention.retainedNewViewWindows),
        policy,
      )
      retainedDuplicateEvents <- fixedBigNat(
        "sink",
        "retentionPolicy.retainedDuplicateEvents",
        BigInt(retention.retainedDuplicateEvents),
        policy,
      )
      retainedRejectedEventSamples <- fixedBigNat(
        "sink",
        "retentionPolicy.retainedRejectedEventSamples",
        BigInt(retention.retainedRejectedEventSamples),
        policy,
      )
      pruneEveryAcceptedEvents <- fixedBigNat(
        "sink",
        "retentionPolicy.pruneEveryAcceptedEvents",
        BigInt(retention.pruneEveryAcceptedEvents),
        policy,
      )
    yield HotStuffSinkRetentionPolicySnapshot(
      finalizedHeightLag = finalizedHeightLag,
      certifiedHeightLag = certifiedHeightLag,
      retainedTimeoutWindows = retainedTimeoutWindows,
      retainedNewViewWindows = retainedNewViewWindows,
      retainedDuplicateEvents = retainedDuplicateEvents,
      retainedRejectedEventSamples = retainedRejectedEventSamples,
      pruneEveryAcceptedEvents = pruneEveryAcceptedEvents,
    )

  private def projectRetainedCounts(
      counts: HotStuffSinkRetainedCounts,
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): Either[ProjectionFailure, HotStuffSinkRetentionCountsSnapshot] =
    projectCounts(
      component = "sink",
      prefix = "retainedCounts",
      values = CountValues(
        proposals = BigInt(counts.proposals),
        votes = BigInt(counts.votes),
        voteAccumulatorVotes = BigInt(counts.voteAccumulatorVotes),
        voteAccumulatorEquivocationKeys =
          BigInt(counts.voteAccumulatorEquivocationKeys),
        timeoutVotes = BigInt(counts.timeoutVotes),
        timeoutAccumulatorVotes = BigInt(counts.timeoutAccumulatorVotes),
        timeoutAccumulatorEquivocationKeys =
          BigInt(counts.timeoutAccumulatorEquivocationKeys),
        timeoutCertificates = BigInt(counts.timeoutCertificates),
        newViews = BigInt(counts.newViews),
        newViewsBySenderWindow = BigInt(counts.newViewsBySenderWindow),
        qcs = BigInt(counts.qcs),
        safetyFaults = BigInt(counts.safetyFaults),
        duplicateSamples = BigInt(counts.duplicateSamples),
      ),
      policy = policy,
    )

  private def projectPrunedCounts(
      counts: HotStuffSinkPrunedCounts,
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): Either[ProjectionFailure, HotStuffSinkRetentionCountsSnapshot] =
    projectCounts(
      component = "sink",
      prefix = "prunedCounts",
      values = CountValues(
        proposals = BigInt(counts.proposals),
        votes = BigInt(counts.votes),
        voteAccumulatorVotes = BigInt(counts.voteAccumulatorVotes),
        voteAccumulatorEquivocationKeys =
          BigInt(counts.voteAccumulatorEquivocationKeys),
        timeoutVotes = BigInt(counts.timeoutVotes),
        timeoutAccumulatorVotes = BigInt(counts.timeoutAccumulatorVotes),
        timeoutAccumulatorEquivocationKeys =
          BigInt(counts.timeoutAccumulatorEquivocationKeys),
        timeoutCertificates = BigInt(counts.timeoutCertificates),
        newViews = BigInt(counts.newViews),
        newViewsBySenderWindow = BigInt(counts.newViewsBySenderWindow),
        qcs = BigInt(counts.qcs),
        safetyFaults = BigInt(counts.safetyFaults),
        duplicateSamples = BigInt(counts.duplicateSamples),
      ),
      policy = policy,
    )

  private def projectCounts(
      component: String,
      prefix: String,
      values: CountValues,
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): Either[ProjectionFailure, HotStuffSinkRetentionCountsSnapshot] =
    for
      proposals <- fixedBigNat(
        component,
        ss"${prefix}.proposals",
        values.proposals,
        policy,
      )
      votes <- fixedBigNat(component, ss"${prefix}.votes", values.votes, policy)
      voteAccumulatorVotes <- fixedBigNat(
        component,
        ss"${prefix}.voteAccumulatorVotes",
        values.voteAccumulatorVotes,
        policy,
      )
      voteAccumulatorEquivocationKeys <- fixedBigNat(
        component,
        ss"${prefix}.voteAccumulatorEquivocationKeys",
        values.voteAccumulatorEquivocationKeys,
        policy,
      )
      timeoutVotes <- fixedBigNat(
        component,
        ss"${prefix}.timeoutVotes",
        values.timeoutVotes,
        policy,
      )
      timeoutAccumulatorVotes <- fixedBigNat(
        component,
        ss"${prefix}.timeoutAccumulatorVotes",
        values.timeoutAccumulatorVotes,
        policy,
      )
      timeoutAccumulatorEquivocationKeys <- fixedBigNat(
        component,
        ss"${prefix}.timeoutAccumulatorEquivocationKeys",
        values.timeoutAccumulatorEquivocationKeys,
        policy,
      )
      timeoutCertificates <- fixedBigNat(
        component,
        ss"${prefix}.timeoutCertificates",
        values.timeoutCertificates,
        policy,
      )
      newViews <- fixedBigNat(
        component,
        ss"${prefix}.newViews",
        values.newViews,
        policy,
      )
      newViewsBySenderWindow <- fixedBigNat(
        component,
        ss"${prefix}.newViewsBySenderWindow",
        values.newViewsBySenderWindow,
        policy,
      )
      qcs <- fixedBigNat(component, ss"${prefix}.qcs", values.qcs, policy)
      safetyFaults <- fixedBigNat(
        component,
        ss"${prefix}.safetyFaults",
        values.safetyFaults,
        policy,
      )
      duplicateSamples <- fixedBigNat(
        component,
        ss"${prefix}.duplicateSamples",
        values.duplicateSamples,
        policy,
      )
    yield HotStuffSinkRetentionCountsSnapshot(
      proposals = proposals,
      votes = votes,
      voteAccumulatorVotes = voteAccumulatorVotes,
      voteAccumulatorEquivocationKeys = voteAccumulatorEquivocationKeys,
      timeoutVotes = timeoutVotes,
      timeoutAccumulatorVotes = timeoutAccumulatorVotes,
      timeoutAccumulatorEquivocationKeys = timeoutAccumulatorEquivocationKeys,
      timeoutCertificates = timeoutCertificates,
      newViews = newViews,
      newViewsBySenderWindow = newViewsBySenderWindow,
      qcs = qcs,
      safetyFaults = safetyFaults,
      duplicateSamples = duplicateSamples,
    )

  private def projectRetentionWatermarks(
      watermarks: HotStuffSinkRetentionWatermarks,
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): WatermarkProjection =
    val finalized =
      chainHeightWatermarks(
        field = "retentionWatermarks.finalizedRetainFromHeightByChain",
        values = watermarks.finalizedRetainFromHeightByChain,
        policy = policy,
      )
    val certified =
      chainHeightWatermarks(
        field = "retentionWatermarks.certifiedRetainFromHeightByChain",
        values = watermarks.certifiedRetainFromHeightByChain,
        policy = policy,
      )
    val timeout =
      windowWatermarks(
        field = "retentionWatermarks.retainedTimeoutWindowFloorByChain",
        values = watermarks.retainedTimeoutWindowFloorByChain,
        policy = policy,
      )
    val newView =
      windowWatermarks(
        field = "retentionWatermarks.retainedNewViewWindowFloorByChain",
        values = watermarks.retainedNewViewWindowFloorByChain,
        policy = policy,
      )
    WatermarkProjection(
      entries = HotStuffSinkRetentionWatermarksSnapshot(
        finalizedRetainFromHeightByChain = finalized.entries,
        certifiedRetainFromHeightByChain = certified.entries,
        retainedTimeoutWindowFloorByChain = timeout.entries,
        retainedNewViewWindowFloorByChain = newView.entries,
      ),
      variableEntries =
        // Child entry guards count emitted child vectors only. Watermark drops
        // are surfaced in parent projectionDroppedEntries and capped there.
        finalized.entries.size.toLong +
          certified.entries.size.toLong +
          timeout.entries.size.toLong +
          newView.entries.size.toLong,
      drops = finalized.drops ++
        certified.drops ++
        timeout.drops ++
        newView.drops,
    )

  private def componentState[Raw, Snapshot](
      component: String,
      read: DiagnosticsRead[Raw],
      projectComponent: Raw => Either[ProjectionFailure, ComponentProjection[
        Snapshot,
      ]],
      sourceSnapshot: Snapshot => Option[
        HotStuffGossipSourceDiagnosticsSnapshot,
      ],
      sinkSnapshot: Snapshot => Option[HotStuffGossipSinkDiagnosticsSnapshot],
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): ComponentState =
    read match
      case Left(error) =>
        ComponentState(
          sourceSnapshot = None,
          sinkSnapshot = None,
          variableEntries = 0,
          estimatedBytes = BigInt(0),
          drops = Vector.empty[ProjectionDrop],
          warnings = Vector(
            warning(
              component,
              "read-failure",
              Some(boundMessage(error, policy)),
            ),
          ),
          emergencySuppressed = false,
          emergencySuppressionReason = None,
        )
      case Right(None) =>
        ComponentState.empty
      case Right(Some(value)) =>
        projectComponent(value) match
          case Left(failure) =>
            ComponentState(
              sourceSnapshot = None,
              sinkSnapshot = None,
              variableEntries = 0,
              estimatedBytes = BigInt(0),
              drops = Vector.empty[ProjectionDrop],
              warnings = Vector(
                warning(
                  component,
                  "projection-failure",
                  Some(boundMessage(failure.message, policy)),
                ),
              ),
              emergencySuppressed = false,
              emergencySuppressionReason = None,
            )
          case Right(projection) =>
            val sizeWarnings = childSizeWarnings(component, projection, policy)
            val emergencyReason =
              childEmergencyReason(component, projection, policy)
            emergencyReason match
              case Some(reason) =>
                ComponentState(
                  sourceSnapshot = None,
                  sinkSnapshot = None,
                  variableEntries = projection.variableEntries,
                  estimatedBytes = projection.estimatedBytes,
                  drops = projection.drops,
                  warnings = sizeWarnings :+ warning(
                    component,
                    "emergency-suppressed",
                    Some(reason),
                  ),
                  emergencySuppressed = true,
                  emergencySuppressionReason = Some(reason),
                )
              case None =>
                ComponentState(
                  sourceSnapshot = sourceSnapshot(projection.snapshot),
                  sinkSnapshot = sinkSnapshot(projection.snapshot),
                  variableEntries = projection.variableEntries,
                  estimatedBytes = projection.estimatedBytes,
                  drops = projection.drops,
                  warnings = sizeWarnings,
                  emergencySuppressed = false,
                  emergencySuppressionReason = None,
                )

  private def assemble(
      sourceState: ComponentState,
      sinkState: ComponentState,
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): HotStuffGossipDiagnosticsProjectionResult =
    if sourceState.isLegitimatelyAbsent &&
      sinkState.isLegitimatelyAbsent
    then HotStuffGossipDiagnosticsProjectionResult(None, Vector.empty)
    else
      val warnings =
        sourceState.warnings ++ sinkState.warnings
      val aggregatedDrops =
        aggregateDrops(
          sourceState.drops ++ sinkState.drops,
          policy.projectionDroppedEntryGroupLimit,
          policy,
        )
      aggregatedDrops match
        case Left(failure) =>
          parentProjectionFailure(warnings, failure, policy)
        case Right(aggregatedDrops) =>
          val preliminaryMetrics =
            metrics(
              sourceVariableEntries = sourceState.emittedVariableEntries,
              sinkVariableEntries = sinkState.emittedVariableEntries,
              warningCount = warnings.size,
              dropCount = aggregatedDrops.entries.size,
              sourceEstimatedBytes = sourceState.emittedEstimatedBytes,
              sinkEstimatedBytes = sinkState.emittedEstimatedBytes,
              policy = policy,
            )
          preliminaryMetrics match
            case Left(failure) =>
              parentProjectionFailure(warnings, failure, policy)
            case Right(metrics) =>
              // Parent emergency checks size/entry envelopes. Overflow counter
              // digit caps are producer numeric-policy checks and remain
              // fail-soft through assembleWithMetrics.
              assembleWithMetrics(
                sourceState = sourceState,
                sinkState = sinkState,
                aggregatedDrops = aggregatedDrops,
                warnings = warnings,
                preliminaryMetrics = metrics,
                policy = policy,
              )

  private def assembleWithMetrics(
      sourceState: ComponentState,
      sinkState: ComponentState,
      aggregatedDrops: AggregatedDrops,
      warnings: Vector[HotStuffGossipDiagnosticsProjectionWarning],
      preliminaryMetrics: HotStuffGossipDiagnosticsProjectionMetrics,
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): HotStuffGossipDiagnosticsProjectionResult =
    val preliminaryParentEntries =
      preliminaryMetrics.parentVariableEntries.toBigInt
    val preliminaryParentBytes =
      preliminaryMetrics.parentEstimatedBytes.toBigInt
    val childEmergencySuppressed =
      sourceState.emergencySuppressed || sinkState.emergencySuppressed
    val childSuppressedReason =
      childSuppressionReason(
        sourceState.emergencySuppressionReason,
        sinkState.emergencySuppressionReason,
      )
    val parentEmergency =
      preliminaryParentEntries > BigInt(
        policy.parentEmergencyVariableEntries,
      ) ||
        preliminaryParentBytes > BigInt(policy.parentEmergencyEstimatedBytes)
    if parentEmergency then
      val reason        = "parent-emergency-guard-exceeded"
      val parentWarning =
        warning("parent", "emergency-suppressed", Some(reason))
      val parentWarnings = warnings :+ parentWarning
      // Metrics on an emergency-suppressed parent describe the emitted
      // envelope, including the parent warning that records suppression.
      val parentMetrics =
        metrics(
          sourceVariableEntries = 0,
          sinkVariableEntries = 0,
          warningCount = parentWarnings.size,
          dropCount = aggregatedDrops.entries.size,
          sourceEstimatedBytes = BigInt(0),
          sinkEstimatedBytes = BigInt(0),
          policy = policy,
        )
      parentMetrics match
        case Left(failure) =>
          parentProjectionFailure(parentWarnings, failure, policy)
        case Right(metrics) =>
          overflowCounters(aggregatedDrops, policy) match
            case Left(failure) =>
              parentProjectionFailure(parentWarnings, failure, policy)
            case Right((overflowGroups, overflowCount)) =>
              val snapshot =
                HotStuffGossipDiagnosticsSnapshot(
                  source = None,
                  sink = None,
                  warnings = parentWarnings,
                  metrics = metrics,
                  projectionDroppedEntries = aggregatedDrops.entries,
                  projectionDroppedEntryOverflowGroups = overflowGroups,
                  projectionDroppedEntryOverflowCount = overflowCount,
                  emergencySuppressed = true,
                  emergencySuppressionReason = Some(reason),
                )
              HotStuffGossipDiagnosticsProjectionResult(
                Some(snapshot),
                parentWarnings,
              )
    else
      val emergencyReason =
        if childEmergencySuppressed then childSuppressedReason
        else None
      overflowCounters(aggregatedDrops, policy) match
        case Left(failure) =>
          parentProjectionFailure(warnings, failure, policy)
        case Right((overflowGroups, overflowCount)) =>
          val snapshot =
            HotStuffGossipDiagnosticsSnapshot(
              source = sourceState.sourceSnapshot,
              sink = sinkState.sinkSnapshot,
              warnings = warnings,
              metrics = preliminaryMetrics,
              projectionDroppedEntries = aggregatedDrops.entries,
              projectionDroppedEntryOverflowGroups = overflowGroups,
              projectionDroppedEntryOverflowCount = overflowCount,
              emergencySuppressed = childEmergencySuppressed,
              emergencySuppressionReason = emergencyReason,
            )
          HotStuffGossipDiagnosticsProjectionResult(Some(snapshot), warnings)

  private def parentProjectionFailure(
      warnings: Vector[HotStuffGossipDiagnosticsProjectionWarning],
      failure: ProjectionFailure,
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): HotStuffGossipDiagnosticsProjectionResult =
    val parentWarning =
      warning(
        "parent",
        "projection-failure",
        Some(boundMessage(failure.message, policy)),
      )
    HotStuffGossipDiagnosticsProjectionResult(None, warnings :+ parentWarning)

  private def childSizeWarnings(
      component: String,
      projection: ComponentProjection[?],
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): Vector[HotStuffGossipDiagnosticsProjectionWarning] =
    val entryWarning =
      Option.when(
        projection.variableEntries > policy.childWarningVariableEntries,
      ):
        warning(
          component,
          "size-warning-entries",
          Some(
            ss"variableEntries=${projection.variableEntries.toString} limit=${policy.childWarningVariableEntries.toString}",
          ),
        )
    val byteWarning =
      Option.when(
        projection.estimatedBytes > BigInt(policy.childWarningEstimatedBytes),
      ):
        warning(
          component,
          "size-warning-bytes",
          Some(
            ss"estimatedBytes=${projection.estimatedBytes.toString} limit=${policy.childWarningEstimatedBytes.toString}",
          ),
        )
    Vector(entryWarning, byteWarning).flatten

  private def childEmergencyReason(
      component: String,
      projection: ComponentProjection[?],
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): Option[String] =
    val reasons =
      Vector(
        Option.when(
          projection.variableEntries > policy.childEmergencyVariableEntries,
        )(
          ss"${component}-variable-entry-emergency-guard-exceeded",
        ),
        Option.when(
          projection.estimatedBytes > BigInt(
            policy.childEmergencyEstimatedBytes,
          ),
        )(
          ss"${component}-estimated-byte-emergency-guard-exceeded",
        ),
      ).flatten
    reasons match
      case Vector()       => None
      case Vector(reason) => Some(reason)
      case many           =>
        Some(
          ss"multiple-${component}-emergency-guards-exceeded:${many.mkString(";")}",
        )

  private def childSuppressionReason(
      sourceReason: Option[String],
      sinkReason: Option[String],
  ): Option[String] =
    (sourceReason, sinkReason) match
      case (Some(source), Some(sink)) =>
        Some(ss"multiple-child-emergency-guards-exceeded:${source};${sink}")
      case (Some(source), None) => Some(source)
      case (None, Some(sink))   => Some(sink)
      case (None, None)         => None

  private def chainTopicCounts[A](
      component: String,
      field: String,
      values: Map[ChainTopic, A],
      toBigInt: A => BigInt,
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): EntryProjection[HotStuffGossipChainTopicCountSnapshot] =
    val capped =
      capSortedEntries(
        component = component,
        field = field,
        rawEntries = values,
        policy = policy,
      ) { case (chainTopic, _) =>
        (chainTopic.chainId.value, chainTopic.topic.value)
      } { case (chainTopic, _) => chainTopicContext(chainTopic) }
    collectEntries(capped.entries) { case (chainTopic, value) =>
      entryBigNat(
        component = component,
        field = field,
        context = Some(chainTopicContext(chainTopic)),
        value = toBigInt(value),
        policy = policy,
      ).map: count =>
        HotStuffGossipChainTopicCountSnapshot(
          chainId = chainTopic.chainId,
          topic = chainTopic.topic,
          count = count,
        )
    }.appendDrops(capped.drops)

  private def topicCounts(
      component: String,
      field: String,
      values: Map[GossipTopic, Long],
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): EntryProjection[HotStuffGossipTopicCountSnapshot] =
    val capped =
      capSortedEntries(
        component = component,
        field = field,
        policy = policy,
        rawEntries = values,
      ) { case (topic, _) => topic.value } { case (topic, _) => topic.value }
    collectEntries(capped.entries) { case (topic, value) =>
      entryBigNat(
        component = component,
        field = field,
        context = Some(topic.value),
        value = BigInt(value),
        policy = policy,
      ).map: count =>
        HotStuffGossipTopicCountSnapshot(topic = topic, count = count)
    }.appendDrops(capped.drops)

  private def topicReasonCounts(
      component: String,
      field: String,
      values: Map[InMemoryHotStuffRelayRejectionKey, Long],
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): EntryProjection[HotStuffGossipTopicReasonCountSnapshot] =
    val capped =
      capSortedEntries(
        component = component,
        field = field,
        rawEntries = values,
        policy = policy,
      ) { case (key, _) => key.topic.value } { case (key, _) =>
        // Capping uses the raw reason for deterministic pre-validation order;
        // semantic reason validation and possible drops happen below.
        key.reason
      }
    collectEntries(capped.entries) { case (key, value) =>
      for
        // If the reason itself is invalid, no validated reason is available for
        // context, so the reason drop uses topic-only context. Count drops occur
        // after reason validation and can include topic:reason context.
        reason <- entryString(
          component = component,
          field = field,
          context = Some(key.topic.value),
          value = key.reason,
          policy = policy,
        )
        count <- entryBigNat(
          component = component,
          field = field,
          context = Some(ss"${key.topic.value}:${reason}"),
          value = BigInt(value),
          policy = policy,
        )
      yield HotStuffGossipTopicReasonCountSnapshot(
        topic = key.topic,
        reason = reason,
        count = count,
      )
    }.appendDrops(capped.drops)

  private def chainHeightWatermarks(
      field: String,
      values: Map[ChainId, BigInt],
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): EntryProjection[HotStuffGossipChainHeightWatermarkSnapshot] =
    val capped =
      capSortedEntries(
        component = "sink",
        field = field,
        rawEntries = values,
        policy = policy,
      ) { case (chainId, _) => chainId.value } { case (chainId, _) =>
        chainId.value
      }
    collectEntries(capped.entries) { case (chainId, value) =>
      entryBigNat(
        component = "sink",
        field = field,
        context = Some(chainId.value),
        value = value,
        policy = policy,
      ).map: height =>
        HotStuffGossipChainHeightWatermarkSnapshot(
          chainId = chainId,
          height = height,
        )
    }.appendDrops(capped.drops)

  private def windowWatermarks(
      field: String,
      values: Map[ChainId, HotStuffWindow],
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): EntryProjection[HotStuffGossipWindowWatermarkSnapshot] =
    val capped =
      capSortedEntries(
        component = "sink",
        field = field,
        rawEntries = values,
        policy = policy,
      ) { case (chainId, _) => chainId.value } { case (chainId, _) =>
        chainId.value
      }
    collectEntries(capped.entries) { case (chainId, window) =>
      if chainId.value =!= window.chainId.value then
        Left[ProjectionDrop, HotStuffGossipWindowWatermarkSnapshot](
          ProjectionDrop(
            component = "sink",
            field = field,
            reason = "chain-id-mismatch",
            context = Some(ss"${chainId.value}:${window.chainId.value}"),
            count = BigInt(1),
          ),
        )
      else
        for
          height <- entryBigNatValue(
            component = "sink",
            field = ss"${field}.height",
            context = Some(chainId.value),
            value = window.height.toBigNat,
            policy = policy,
          )
          view <- entryBigNatValue(
            component = "sink",
            field = ss"${field}.view",
            context = Some(chainId.value),
            value = window.view.toBigNat,
            policy = policy,
          )
        yield HotStuffGossipWindowWatermarkSnapshot(
          chainId = chainId,
          height = height,
          view = view,
          validatorSetHash = window.validatorSetHash,
        )
    }.appendDrops(capped.drops)

  private def collectEntries[A, B](
      values: Vector[A],
  )(
      project: A => Either[ProjectionDrop, B],
  ): EntryProjection[B] =
    val (entries, drops) =
      values.foldLeft((List.empty[B], List.empty[ProjectionDrop])):
        (acc, value) =>
          val (entries, drops) = acc
          project(value) match
            case Right(entry) => (entry :: entries, drops)
            case Left(drop)   => (entries, drop :: drops)
    EntryProjection(entries.reverse.toVector, drops.reverse.toVector)

  private def capSortedEntries[A, K](
      component: String,
      field: String,
      rawEntries: Iterable[A],
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  )(
      sortKey: A => K,
  )(
      tieKey: A => String,
  )(using keyOrdering: Ordering[K]): CappedEntries[A] =
    // Keep the lexicographically smallest K entries for deterministic capped
    // output; larger keys are represented by the vector overflow drop.
    // Current map-backed call sites pass unique sort/tie pairs. The ordinal is
    // a defensive final key for future duplicated iterable entries; such
    // callers should still provide deterministic input iteration if they cannot
    // supply unique sort/tie keys.
    val candidateOrdering: Ordering[SortCandidate[K, A]] =
      new Ordering[SortCandidate[K, A]]:
        override def compare(
            left: SortCandidate[K, A],
            right: SortCandidate[K, A],
        ): Int =
          val keyCompare = keyOrdering.compare(left.key, right.key)
          if keyCompare =!= 0 then keyCompare
          else
            // JVM code-unit order is the deterministic capping order; this is
            // not intended to be Unicode collation.
            val tieCompare = left.tieKey.compareTo(right.tieKey)
            if tieCompare =!= 0 then tieCompare
            else java.lang.Long.compare(left.ordinal, right.ordinal)
    val (kept, total) =
      rawEntries.foldLeft(
        (TreeSet.empty[SortCandidate[K, A]](using candidateOrdering), 0L),
      ): (state, entry) =>
        val (kept, count) = state
        val key           = sortKey(entry)
        val tie           = tieKey(entry)
        val candidate     = SortCandidate(key, tie, count, entry)
        val nextKept      =
          if kept.sizeIs < policy.maxVectorEntries then kept + candidate
          else
            kept.lastOption match
              case Some(last) if candidateOrdering.lt(candidate, last) =>
                (kept - last) + candidate
              case _ => kept
        nextKept -> (count + 1L)
    val entries       = kept.toVector.map(_.value)
    val overflowCount = total - entries.length.toLong
    CappedEntries(
      entries,
      vectorOverflowDrops(component, field, overflowCount),
    )

  private def vectorOverflowDrops(
      component: String,
      field: String,
      overflowCount: Long,
  ): Vector[ProjectionDrop] =
    if overflowCount <= 0 then Vector.empty[ProjectionDrop]
    else
      Vector(
        ProjectionDrop(
          component = component,
          field = field,
          reason = "vector-entry-limit-exceeded",
          context = None,
          count = BigInt(overflowCount),
        ),
      )

  private def fixedBigNat(
      component: String,
      field: String,
      value: BigInt,
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): Either[ProjectionFailure, BigNat] =
    toBigNat(value, policy).left.map: reason =>
      ProjectionFailure(component, field, reason)

  private def entryBigNat(
      component: String,
      field: String,
      context: Option[String],
      value: BigInt,
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): Either[ProjectionDrop, BigNat] =
    toBigNat(value, policy).left.map: reason =>
      ProjectionDrop(
        component,
        field,
        reason,
        context,
        count = BigInt(1),
      )

  private def entryBigNatValue(
      component: String,
      field: String,
      context: Option[String],
      value: BigNat,
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): Either[ProjectionDrop, BigNat] =
    if digits(value) > BigInt(policy.maxDecimalDigits) then
      Left[ProjectionDrop, BigNat]:
        ProjectionDrop(
          component = component,
          field = field,
          reason = "decimal-digit-limit-exceeded",
          context = context,
          count = BigInt(1),
        )
    else Right[ProjectionDrop, BigNat](value)

  private def toBigNat(
      value: BigInt,
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): Either[String, BigNat] =
    if value < BigInt(0) then Left[String, BigNat]("negative-number")
    // Exact cap: maxDecimalDigits digits pass; one digit over is rejected.
    else if value.toString.lengthCompare(policy.maxDecimalDigits) > 0 then
      Left[String, BigNat]("decimal-digit-limit-exceeded")
    else Right[String, BigNat](BigNat.unsafeFromBigInt(value))

  private def requiredString(
      component: String,
      field: String,
      value: String,
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): Either[ProjectionFailure, String] =
    validateString(value, policy).left.map: reason =>
      ProjectionFailure(component, field, reason)

  private def entryString(
      component: String,
      field: String,
      context: Option[String],
      value: String,
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): Either[ProjectionDrop, String] =
    validateString(value, policy).left.map: reason =>
      ProjectionDrop(
        component,
        field,
        reason,
        context,
        count = BigInt(1),
      )

  private def validateString(
      value: String,
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): Either[String, String] =
    // Enforce the byte envelope before semantic blank checks so an oversized
    // blank value cannot bypass producer size accounting.
    if utf8StringBytes(value) > policy.maxStringUtf8Bytes.toLong then
      Left[String, String]("string-limit-exceeded")
    else if value.isBlank then Left[String, String]("blank-string")
    else Right[String, String](value)

  private def aggregateDrops(
      drops: Vector[ProjectionDrop],
      cap: Int,
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): Either[ProjectionFailure, AggregatedDrops] =
    // groupBy's map iteration order is intentionally normalized by the
    // deterministic sort below before capping or emitting drops.
    val grouped =
      drops
        .groupBy(drop =>
          (drop.component, drop.field, drop.reason, drop.context),
        )
        .view
        .map { case ((component, field, reason, context), values) =>
          ProjectionDrop(
            component = component,
            field = field,
            reason = reason,
            context = context,
            count = values.iterator.map(_.count).sum,
          )
        }
        .toVector
        .sortBy(drop =>
          (
            drop.component,
            drop.field,
            drop.reason,
            contextSortKey(drop.context),
          ),
        )
    val kept     = grouped.take(cap)
    val overflow = grouped.drop(cap)
    projectionDropEntries(kept, policy).map: entries =>
      AggregatedDrops(
        entries = entries,
        overflowGroups = BigInt(overflow.size),
        overflowCount = overflow.iterator.map(_.count).sum,
      )

  private def projectionDropEntries(
      drops: Vector[ProjectionDrop],
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): Either[ProjectionFailure, Vector[HotStuffGossipProjectionDroppedEntry]] =
    drops.foldLeft(
      Right[ProjectionFailure, Vector[HotStuffGossipProjectionDroppedEntry]](
        Vector.empty[HotStuffGossipProjectionDroppedEntry],
      ): Either[ProjectionFailure, Vector[HotStuffGossipProjectionDroppedEntry]],
    ): (acc, drop) =>
      for
        entries <- acc
        count   <- metricBigNat(
          "projectionDroppedEntries.count",
          drop.count,
          policy,
        )
      yield entries :+ HotStuffGossipProjectionDroppedEntry(
        component = drop.component,
        field = drop.field,
        reason = drop.reason,
        context = boundedContext(drop.context, policy),
        count = count,
      )

  private def metrics(
      sourceVariableEntries: Long,
      sinkVariableEntries: Long,
      warningCount: Int,
      dropCount: Int,
      sourceEstimatedBytes: BigInt,
      sinkEstimatedBytes: BigInt,
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): Either[ProjectionFailure, HotStuffGossipDiagnosticsProjectionMetrics] =
    val parentVariableEntries =
      BigInt(sourceVariableEntries) +
        BigInt(sinkVariableEntries) +
        BigInt(warningCount) +
        BigInt(dropCount)
    // These are conservative envelope estimates, not exact JSON byte counts.
    // Keep enough fixed overhead for field names, component/reason strings,
    // punctuation, and future diagnostic field-path growth.
    val warningBytes =
      BigInt(warningCount) *
        (WarningFixedEstimatedBytes + BigInt(policy.maxStringUtf8Bytes))
    val dropBytes =
      BigInt(dropCount) *
        (DropFixedEstimatedBytes + BigInt(policy.maxStringUtf8Bytes) +
          BigInt(policy.maxDecimalDigits))
    val dropOverflowBytes =
      DropOverflowFixedEstimatedBytes + BigInt(
        policy.maxDecimalDigits,
      ) * BigInt(2)
    val parentEstimatedBytes =
      ParentFixedEstimatedBytes + sourceEstimatedBytes + sinkEstimatedBytes +
        warningBytes + dropBytes + dropOverflowBytes
    for
      sourceVariableEntriesValue <- metricBigNat(
        "metrics.sourceVariableEntries",
        BigInt(sourceVariableEntries),
        policy,
      )
      sinkVariableEntriesValue <- metricBigNat(
        "metrics.sinkVariableEntries",
        BigInt(sinkVariableEntries),
        policy,
      )
      parentVariableEntriesValue <- metricBigNat(
        "metrics.parentVariableEntries",
        parentVariableEntries,
        policy,
      )
      sourceEstimatedBytesValue <- metricBigNat(
        "metrics.sourceEstimatedBytes",
        sourceEstimatedBytes,
        policy,
      )
      sinkEstimatedBytesValue <- metricBigNat(
        "metrics.sinkEstimatedBytes",
        sinkEstimatedBytes,
        policy,
      )
      parentEstimatedBytesValue <- metricBigNat(
        "metrics.parentEstimatedBytes",
        parentEstimatedBytes,
        policy,
      )
    yield HotStuffGossipDiagnosticsProjectionMetrics(
      sourceVariableEntries = sourceVariableEntriesValue,
      sinkVariableEntries = sinkVariableEntriesValue,
      parentVariableEntries = parentVariableEntriesValue,
      sourceEstimatedBytes = sourceEstimatedBytesValue,
      sinkEstimatedBytes = sinkEstimatedBytesValue,
      parentEstimatedBytes = parentEstimatedBytesValue,
    )

  private def metricBigNat(
      field: String,
      value: BigInt,
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): Either[ProjectionFailure, BigNat] =
    toBigNat(value, policy).left.map: reason =>
      ProjectionFailure("parent", field, reason)

  private def overflowCounters(
      aggregatedDrops: AggregatedDrops,
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): Either[ProjectionFailure, (BigNat, BigNat)] =
    // Overflow groups can span multiple capped vectors, so their digit width is
    // guarded by this fail-soft parent path rather than by maxVectorEntries
    // validation alone.
    for
      overflowGroups <- metricBigNat(
        "projectionDroppedEntryOverflowGroups",
        aggregatedDrops.overflowGroups,
        policy,
      )
      overflowCount <- metricBigNat(
        "projectionDroppedEntryOverflowCount",
        aggregatedDrops.overflowCount,
        policy,
      )
    yield overflowGroups -> overflowCount

  private def contextSortKey(
      context: Option[String],
  ): (Int, String) =
    // Context ordering is a deterministic byte/string tiebreak for capping,
    // not a locale-aware or Unicode-normalizing semantic order.
    context match
      case None        => 0 -> ""
      case Some(value) => 1 -> value

  private def estimateSource(
      snapshot: HotStuffGossipSourceDiagnosticsSnapshot,
  ): BigInt =
    SourceFixedEstimatedBytes +
      digits(snapshot.retainedEventsLimitPerTopic) +
      sumEstimates(snapshot.retainedEventsByTopic)(estimateChainTopicCount) +
      sumEstimates(snapshot.appendedEventsByTopic)(estimateChainTopicCount) +
      sumEstimates(snapshot.prunedEventsByTopic)(estimateChainTopicCount) +
      sumEstimates(snapshot.readByIdMissesByTopic)(estimateChainTopicCount) +
      sumEstimates(snapshot.invalidCursorRejectionsByTopic)(
        estimateChainTopicCount,
      ) +
      sumEstimates(snapshot.staleCursorRejectionsByTopic)(
        estimateChainTopicCount,
      )

  private def estimateSink(
      snapshot: HotStuffGossipSinkDiagnosticsSnapshot,
  ): BigInt =
    SinkFixedEstimatedBytes +
      estimateStringBytes(snapshot.policyMode) +
      estimateRetentionPolicy(snapshot.retentionPolicy) +
      estimateCounts(snapshot.retainedCounts) +
      estimateCounts(snapshot.prunedCounts) +
      sumEstimates(
        snapshot.retentionWatermarks.finalizedRetainFromHeightByChain,
      )(estimateChainHeight) +
      sumEstimates(
        snapshot.retentionWatermarks.certifiedRetainFromHeightByChain,
      )(estimateChainHeight) +
      sumEstimates(
        snapshot.retentionWatermarks.retainedTimeoutWindowFloorByChain,
      )(estimateWindow) +
      sumEstimates(
        snapshot.retentionWatermarks.retainedNewViewWindowFloorByChain,
      )(estimateWindow) +
      sumEstimates(snapshot.relayedValidatedArtifactsByTopic)(
        estimateTopicCount,
      ) +
      sumEstimates(snapshot.duplicateArtifactsSuppressedByTopic)(
        estimateTopicCount,
      ) +
      sumEstimates(snapshot.rejectedArtifactsByTopicAndReason)(
        estimateTopicReasonCount,
      )

  private def estimateChainTopicCount(
      entry: HotStuffGossipChainTopicCountSnapshot,
  ): BigInt =
    ChainTopicFixedEstimatedBytes + estimateStringBytes(entry.chainId.value) +
      estimateStringBytes(entry.topic.value) +
      digits(entry.count)

  private def estimateTopicCount(
      entry: HotStuffGossipTopicCountSnapshot,
  ): BigInt =
    TopicCountFixedEstimatedBytes + estimateStringBytes(entry.topic.value) +
      digits(entry.count)

  private def estimateTopicReasonCount(
      entry: HotStuffGossipTopicReasonCountSnapshot,
  ): BigInt =
    TopicReasonFixedEstimatedBytes + estimateStringBytes(entry.topic.value) +
      digits(entry.count) +
      estimateStringBytes(entry.reason)

  private def estimateChainHeight(
      entry: HotStuffGossipChainHeightWatermarkSnapshot,
  ): BigInt =
    ChainHeightFixedEstimatedBytes + estimateStringBytes(entry.chainId.value) +
      digits(entry.height)

  private def estimateWindow(
      entry: HotStuffGossipWindowWatermarkSnapshot,
  ): BigInt =
    WindowFixedEstimatedBytes + estimateStringBytes(entry.chainId.value) +
      digits(entry.height) +
      digits(entry.view) +
      ValidatorSetHashHexBytes

  private def estimateRetentionPolicy(
      policy: HotStuffSinkRetentionPolicySnapshot,
  ): BigInt =
    RetentionPolicyFixedEstimatedBytes +
      digits(policy.finalizedHeightLag) +
      digits(policy.certifiedHeightLag) +
      digits(policy.retainedTimeoutWindows) +
      digits(policy.retainedNewViewWindows) +
      digits(policy.retainedDuplicateEvents) +
      digits(policy.retainedRejectedEventSamples) +
      digits(policy.pruneEveryAcceptedEvents)

  private def estimateCounts(
      counts: HotStuffSinkRetentionCountsSnapshot,
  ): BigInt =
    RetentionCountsFixedEstimatedBytes +
      digits(counts.proposals) +
      digits(counts.votes) +
      digits(counts.voteAccumulatorVotes) +
      digits(counts.voteAccumulatorEquivocationKeys) +
      digits(counts.timeoutVotes) +
      digits(counts.timeoutAccumulatorVotes) +
      digits(counts.timeoutAccumulatorEquivocationKeys) +
      digits(counts.timeoutCertificates) +
      digits(counts.newViews) +
      digits(counts.newViewsBySenderWindow) +
      digits(counts.qcs) +
      digits(counts.safetyFaults) +
      digits(counts.duplicateSamples)

  private def warning(
      component: String,
      reason: String,
      message: Option[String],
  ): HotStuffGossipDiagnosticsProjectionWarning =
    HotStuffGossipDiagnosticsProjectionWarning(
      component = component,
      reason = reason,
      message = message,
    )

  private def boundMessage(
      value: String,
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): String =
    takeUtf8Bytes(value, policy.maxStringUtf8Bytes)

  private def boundedContext(
      context: Option[String],
      policy: HotStuffGossipDiagnosticsProjectionPolicy,
  ): Option[String] =
    context.map(value => takeUtf8Bytes(value, policy.maxStringUtf8Bytes))

  private def chainTopicContext(
      chainTopic: ChainTopic,
  ): String =
    ss"${chainTopic.chainId.value}:${chainTopic.topic.value}"

  private def digits(
      value: BigNat,
  ): BigInt =
    BigInt(value.toBigInt.toString.length)

  private def sumEstimates[A](
      values: Vector[A],
  )(
      estimate: A => BigInt,
  ): BigInt =
    values.foldLeft(BigInt(0)): (sum, value) =>
      sum + estimate(value)

  private def estimateStringBytes(
      value: String,
  ): BigInt =
    // Count UTF-8 bytes with Long arithmetic, then widen once for BigInt
    // estimate accumulation to avoid per-codepoint BigInt allocation.
    BigInt(utf8StringBytes(value))

  private def utf8StringBytes(
      value: String,
  ): Long =
    utf8StringBytesLoop(value, offset = 0, bytes = 0L)

  @tailrec
  private def utf8StringBytesLoop(
      value: String,
      offset: Int,
      bytes: Long,
  ): Long =
    if offset >= value.length then bytes
    else
      val codePoint = value.codePointAt(offset)
      utf8StringBytesLoop(
        value = value,
        offset = offset + Character.charCount(codePoint),
        bytes = bytes + utf8CodePointBytes(codePoint),
      )

  private def utf8CodePointBytes(
      codePoint: Int,
  ): Long =
    if codePoint < 0x80 then 1L
    else if codePoint < 0x800 then 2L
    else if codePoint < 0x10000 then 3L
    else 4L

  private def takeUtf8Bytes(
      value: String,
      limit: Int,
  ): String =
    takeUtf8Prefix(value, limit, offset = 0, usedBytes = 0L, acc = Nil) match
      case None         => value
      case Some(prefix) =>
        buildUtf8Prefix(prefix.reverse)

  private def buildUtf8Prefix(
      codePoints: List[Int],
  ): String =
    val builder = new java.lang.StringBuilder()
    codePoints.foreach: codePoint =>
      builder.appendCodePoint(codePoint)
      ()
    builder.toString

  @tailrec
  private def takeUtf8Prefix(
      value: String,
      limit: Int,
      offset: Int,
      usedBytes: Long,
      acc: List[Int],
  ): Option[List[Int]] =
    if offset >= value.length then None
    else
      val codePoint = value.codePointAt(offset)
      val nextBytes = utf8CodePointBytes(codePoint)
      if usedBytes + nextBytes > limit.toLong then Some(acc)
      else
        takeUtf8Prefix(
          value = value,
          limit = limit,
          offset = offset + Character.charCount(codePoint),
          usedBytes = usedBytes + nextBytes,
          acc = codePoint :: acc,
        )

  private final case class ProjectionFailure(
      component: String,
      field: String,
      reason: String,
  ):
    def message: String =
      ss"${field}:${reason}"

  private final case class ProjectionDrop(
      component: String,
      field: String,
      reason: String,
      context: Option[String],
      count: BigInt,
  )

  private final case class ComponentProjection[A](
      snapshot: A,
      variableEntries: Long,
      estimatedBytes: BigInt,
      drops: Vector[ProjectionDrop],
  )

  private final case class EntryProjection[A](
      entries: Vector[A],
      drops: Vector[ProjectionDrop],
  ):
    def appendDrops(
        additionalDrops: Vector[ProjectionDrop],
    ): EntryProjection[A] =
      copy(drops = drops ++ additionalDrops)

  private final case class CappedEntries[A](
      entries: Vector[A],
      drops: Vector[ProjectionDrop],
  )

  private final case class SortCandidate[K, A](
      key: K,
      tieKey: String,
      ordinal: Long,
      value: A,
  )

  private final case class WatermarkProjection(
      entries: HotStuffSinkRetentionWatermarksSnapshot,
      variableEntries: Long,
      drops: Vector[ProjectionDrop],
  )

  private final case class ComponentState(
      sourceSnapshot: Option[HotStuffGossipSourceDiagnosticsSnapshot],
      sinkSnapshot: Option[HotStuffGossipSinkDiagnosticsSnapshot],
      variableEntries: Long,
      estimatedBytes: BigInt,
      drops: Vector[ProjectionDrop],
      warnings: Vector[HotStuffGossipDiagnosticsProjectionWarning],
      emergencySuppressed: Boolean,
      emergencySuppressionReason: Option[String],
  ):
    private def hasSnapshot: Boolean =
      sourceSnapshot.nonEmpty || sinkSnapshot.nonEmpty

    // Valid only for ComponentState values built by this projection object:
    // zero payload, no drops, and no warnings is reserved for legitimate
    // component absence.
    def isLegitimatelyAbsent: Boolean =
      !hasSnapshot &&
        variableEntries == 0 &&
        estimatedBytes.signum == 0 &&
        drops.isEmpty &&
        warnings.isEmpty

    // Parent metrics describe the emitted envelope. An emergency-suppressed
    // child is represented by warnings, not by the omitted child payload size.
    def emittedVariableEntries: Long =
      if hasSnapshot then variableEntries else 0

    def emittedEstimatedBytes: BigInt =
      if hasSnapshot then estimatedBytes else BigInt(0)

  private object ComponentState:
    val empty: ComponentState =
      ComponentState(
        sourceSnapshot = None,
        sinkSnapshot = None,
        variableEntries = 0,
        estimatedBytes = BigInt(0),
        drops = Vector.empty[ProjectionDrop],
        warnings = Vector.empty[HotStuffGossipDiagnosticsProjectionWarning],
        emergencySuppressed = false,
        emergencySuppressionReason = None,
      )

  private final case class AggregatedDrops(
      entries: Vector[HotStuffGossipProjectionDroppedEntry],
      overflowGroups: BigInt,
      overflowCount: BigInt,
  )

  private final case class CountValues(
      proposals: BigInt,
      votes: BigInt,
      voteAccumulatorVotes: BigInt,
      voteAccumulatorEquivocationKeys: BigInt,
      timeoutVotes: BigInt,
      timeoutAccumulatorVotes: BigInt,
      timeoutAccumulatorEquivocationKeys: BigInt,
      timeoutCertificates: BigInt,
      newViews: BigInt,
      newViewsBySenderWindow: BigInt,
      qcs: BigInt,
      safetyFaults: BigInt,
      duplicateSamples: BigInt,
  )
