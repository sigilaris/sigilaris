package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import scala.annotation.tailrec
import scala.collection.immutable.ListMap

import org.sigilaris.core.codec.json.{JsonDecoder, JsonEncoder, JsonValue}
import org.sigilaris.core.datatype.BigNat
import org.sigilaris.core.failure.DecodeFailure
import org.sigilaris.node.gossip.{ChainId, GossipTopic}

final case class HotStuffGossipDiagnosticsSnapshot(
    source: Option[HotStuffGossipSourceDiagnosticsSnapshot],
    sink: Option[HotStuffGossipSinkDiagnosticsSnapshot],
    warnings: Vector[HotStuffGossipDiagnosticsProjectionWarning],
    metrics: HotStuffGossipDiagnosticsProjectionMetrics,
    projectionDroppedEntries: Vector[HotStuffGossipProjectionDroppedEntry],
    projectionDroppedEntryOverflowGroups: BigNat,
    projectionDroppedEntryOverflowCount: BigNat,
    emergencySuppressed: Boolean,
    emergencySuppressionReason: Option[String],
)

object HotStuffGossipDiagnosticsSnapshot:
  val empty: HotStuffGossipDiagnosticsSnapshot =
    HotStuffGossipDiagnosticsSnapshot(
      source = None,
      sink = None,
      warnings = Vector.empty[HotStuffGossipDiagnosticsProjectionWarning],
      metrics = HotStuffGossipDiagnosticsProjectionMetrics.empty,
      projectionDroppedEntries =
        Vector.empty[HotStuffGossipProjectionDroppedEntry],
      projectionDroppedEntryOverflowGroups = BigNat.Zero,
      projectionDroppedEntryOverflowCount = BigNat.Zero,
      emergencySuppressed = false,
      emergencySuppressionReason = None,
    )

final case class HotStuffGossipSourceDiagnosticsSnapshot(
    retainedEventsLimitPerTopic: BigNat,
    retainedEventsByTopic: Vector[HotStuffGossipChainTopicCountSnapshot],
    appendedEventsByTopic: Vector[HotStuffGossipChainTopicCountSnapshot],
    prunedEventsByTopic: Vector[HotStuffGossipChainTopicCountSnapshot],
    readByIdMissesByTopic: Vector[HotStuffGossipChainTopicCountSnapshot],
    invalidCursorRejectionsByTopic: Vector[
      HotStuffGossipChainTopicCountSnapshot,
    ],
    staleCursorRejectionsByTopic: Vector[HotStuffGossipChainTopicCountSnapshot],
)

final case class HotStuffGossipSinkDiagnosticsSnapshot(
    policyMode: String,
    retentionPolicy: HotStuffSinkRetentionPolicySnapshot,
    retainedCounts: HotStuffSinkRetentionCountsSnapshot,
    prunedCounts: HotStuffSinkRetentionCountsSnapshot,
    retentionWatermarks: HotStuffSinkRetentionWatermarksSnapshot,
    relayedValidatedArtifactsByTopic: Vector[HotStuffGossipTopicCountSnapshot],
    duplicateArtifactsSuppressedByTopic: Vector[
      HotStuffGossipTopicCountSnapshot,
    ],
    rejectedArtifactsByTopicAndReason: Vector[
      HotStuffGossipTopicReasonCountSnapshot,
    ],
)

final case class HotStuffSinkRetentionPolicySnapshot(
    finalizedHeightLag: BigNat,
    certifiedHeightLag: BigNat,
    retainedTimeoutWindows: BigNat,
    retainedNewViewWindows: BigNat,
    retainedDuplicateEvents: BigNat,
    retainedRejectedEventSamples: BigNat,
    pruneEveryAcceptedEvents: BigNat,
)

final case class HotStuffSinkRetentionCountsSnapshot(
    proposals: BigNat,
    votes: BigNat,
    voteAccumulatorVotes: BigNat,
    voteAccumulatorEquivocationKeys: BigNat,
    timeoutVotes: BigNat,
    timeoutAccumulatorVotes: BigNat,
    timeoutAccumulatorEquivocationKeys: BigNat,
    timeoutCertificates: BigNat,
    newViews: BigNat,
    newViewsBySenderWindow: BigNat,
    qcs: BigNat,
    safetyFaults: BigNat,
    duplicateSamples: BigNat,
)

object HotStuffSinkRetentionCountsSnapshot:
  val empty: HotStuffSinkRetentionCountsSnapshot =
    HotStuffSinkRetentionCountsSnapshot(
      proposals = BigNat.Zero,
      votes = BigNat.Zero,
      voteAccumulatorVotes = BigNat.Zero,
      voteAccumulatorEquivocationKeys = BigNat.Zero,
      timeoutVotes = BigNat.Zero,
      timeoutAccumulatorVotes = BigNat.Zero,
      timeoutAccumulatorEquivocationKeys = BigNat.Zero,
      timeoutCertificates = BigNat.Zero,
      newViews = BigNat.Zero,
      newViewsBySenderWindow = BigNat.Zero,
      qcs = BigNat.Zero,
      safetyFaults = BigNat.Zero,
      duplicateSamples = BigNat.Zero,
    )

final case class HotStuffSinkRetentionWatermarksSnapshot(
    finalizedRetainFromHeightByChain: Vector[
      HotStuffGossipChainHeightWatermarkSnapshot,
    ],
    certifiedRetainFromHeightByChain: Vector[
      HotStuffGossipChainHeightWatermarkSnapshot,
    ],
    retainedTimeoutWindowFloorByChain: Vector[
      HotStuffGossipWindowWatermarkSnapshot,
    ],
    retainedNewViewWindowFloorByChain: Vector[
      HotStuffGossipWindowWatermarkSnapshot,
    ],
)

object HotStuffSinkRetentionWatermarksSnapshot:
  val empty: HotStuffSinkRetentionWatermarksSnapshot =
    HotStuffSinkRetentionWatermarksSnapshot(
      finalizedRetainFromHeightByChain =
        Vector.empty[HotStuffGossipChainHeightWatermarkSnapshot],
      certifiedRetainFromHeightByChain =
        Vector.empty[HotStuffGossipChainHeightWatermarkSnapshot],
      retainedTimeoutWindowFloorByChain =
        Vector.empty[HotStuffGossipWindowWatermarkSnapshot],
      retainedNewViewWindowFloorByChain =
        Vector.empty[HotStuffGossipWindowWatermarkSnapshot],
    )

final case class HotStuffGossipChainTopicCountSnapshot(
    chainId: ChainId,
    topic: GossipTopic,
    count: BigNat,
)

final case class HotStuffGossipTopicCountSnapshot(
    topic: GossipTopic,
    count: BigNat,
)

final case class HotStuffGossipTopicReasonCountSnapshot(
    topic: GossipTopic,
    reason: String,
    count: BigNat,
)

final case class HotStuffGossipChainHeightWatermarkSnapshot(
    chainId: ChainId,
    height: BigNat,
)

final case class HotStuffGossipWindowWatermarkSnapshot(
    chainId: ChainId,
    height: BigNat,
    view: BigNat,
    validatorSetHash: ValidatorSetHash,
)

final case class HotStuffGossipProjectionDroppedEntry(
    component: String,
    field: String,
    reason: String,
    context: Option[String],
    count: BigNat,
)

final case class HotStuffGossipDiagnosticsProjectionWarning(
    component: String,
    reason: String,
    message: Option[String],
)

final case class HotStuffGossipDiagnosticsProjectionMetrics(
    sourceVariableEntries: BigNat,
    sinkVariableEntries: BigNat,
    parentVariableEntries: BigNat,
    sourceEstimatedBytes: BigNat,
    sinkEstimatedBytes: BigNat,
    parentEstimatedBytes: BigNat,
)

object HotStuffGossipDiagnosticsProjectionMetrics:
  val empty: HotStuffGossipDiagnosticsProjectionMetrics =
    HotStuffGossipDiagnosticsProjectionMetrics(
      sourceVariableEntries = BigNat.Zero,
      sinkVariableEntries = BigNat.Zero,
      parentVariableEntries = BigNat.Zero,
      sourceEstimatedBytes = BigNat.Zero,
      sinkEstimatedBytes = BigNat.Zero,
      parentEstimatedBytes = BigNat.Zero,
    )

/** Projection-local JSON codecs for diagnostics snapshots.
  *
  * Import these givens only at projected-diagnostics encode/decode call sites.
  * The local `BigNat` and `String` codecs intentionally differ from the core
  * codecs, and the local `Vector` decoder adds a diagnostics-specific entry
  * cap. These can conflict if imported into a scope that also summons the
  * default core JSON codecs. The value-type codecs for `ChainId`,
  * `GossipTopic`, and `ValidatorSetHash` are likewise intended only for this
  * projected diagnostics surface.
  */
object HotStuffGossipDiagnosticsJsonCodecs:
  private val MaxDecimalDigits: Int =
    HotStuffGossipDiagnosticsProjectionPolicy.MaxDecimalDigitsLimit
  private val MaxStringUtf8Bytes: Int =
    HotStuffGossipDiagnosticsProjectionPolicy.MaxStringUtf8BytesLimit
  private val MaxVectorEntries: Int =
    HotStuffGossipDiagnosticsProjectionPolicy.MaxVectorEntriesLimit
  private val MaxArrayStringUtf8Bytes: Long =
    HotStuffGossipDiagnosticsProjectionPolicy.MaxEstimateProductLimit

  given JsonEncoder[String] = value => JsonValue.JString(value)

  given JsonDecoder[String] = json =>
    boundedString(json).left.map(DecodeFailure(_))

  given JsonEncoder[Boolean] = value => JsonValue.JBool(value)

  given JsonDecoder[Boolean] =
    case JsonValue.JBool(value) => Right[DecodeFailure, Boolean](value)
    case _ => Left[DecodeFailure, Boolean](DecodeFailure("expected boolean"))

  given [A: JsonEncoder]: JsonEncoder[Vector[A]] = values =>
    JsonValue.JArray(values.map(JsonEncoder[A].encode))

  given [A: JsonDecoder]: JsonDecoder[Vector[A]] = json =>
    json match
      case JsonValue.JArray(values)
          if values.sizeCompare(MaxVectorEntries) > 0 =>
        Left[DecodeFailure, Vector[A]]:
          DecodeFailure("array exceeds maximum entry count")
      // This limits domain decode construction for already parsed diagnostics
      // JSON. Raw input byte limits remain the parser layer's responsibility.
      case JsonValue.JArray(values)
          if jsonValuesStringUtf8BytesWithin(
            values,
            MaxArrayStringUtf8Bytes,
          ).isEmpty =>
        Left[DecodeFailure, Vector[A]]:
          DecodeFailure("array exceeds maximum string UTF-8 byte budget")
      case JsonValue.JArray(values) =>
        values.foldLeft[Either[DecodeFailure, Vector[A]]](
          Right[DecodeFailure, Vector[A]](Vector.empty[A]),
        ): (acc, value) =>
          acc.flatMap: decoded =>
            JsonDecoder[A].decode(value).map(item => decoded :+ item)
      case _ =>
        Left[DecodeFailure, Vector[A]](DecodeFailure("expected array"))

  given [A: JsonEncoder]: JsonEncoder[Option[A]] =
    case Some(value) => JsonEncoder[A].encode(value)
    case None        => JsonValue.JNull

  given [A: JsonDecoder]: JsonDecoder[Option[A]] =
    case JsonValue.JNull => Right[DecodeFailure, Option[A]](None)
    case value           => JsonDecoder[A].decode(value).map(Some(_))

  given JsonEncoder[BigNat] = value =>
    JsonValue.JString(value.toBigInt.toString)

  // The digit cap is an ingress guard for external JSON. Projection assembly
  // keeps emitted diagnostic values within the supported envelope before
  // encoding, so the encoder remains a total BigNat-to-string projection.
  given JsonDecoder[BigNat] = json =>
    json match
      case JsonValue.JString(value)
          if value.lengthCompare(MaxDecimalDigits) > 0 =>
        Left[DecodeFailure, BigNat]:
          DecodeFailure("canonical decimal string exceeds maximum length")
      case JsonValue.JString(value) if isCanonicalDecimal(value) =>
        Right[DecodeFailure, BigNat](BigNat.unsafeFromBigInt(BigInt(value)))
      case JsonValue.JString(_) =>
        Left[DecodeFailure, BigNat]:
          DecodeFailure("malformed canonical non-negative decimal string")
      case _ =>
        Left[DecodeFailure, BigNat]:
          DecodeFailure("expected canonical non-negative decimal string")

  given JsonEncoder[ChainId] = chainId => JsonValue.JString(chainId.value)
  given JsonDecoder[ChainId] = stringDecoder(ChainId.parse)

  given JsonEncoder[GossipTopic] = topic => JsonValue.JString(topic.value)
  given JsonDecoder[GossipTopic] = stringDecoder(GossipTopic.parse)

  given JsonEncoder[ValidatorSetHash] = hash =>
    JsonValue.JString(hash.toHexLower)
  given JsonDecoder[ValidatorSetHash] = stringDecoder(ValidatorSetHash.fromHex)

  given JsonEncoder[HotStuffGossipSourceDiagnosticsSnapshot] =
    value =>
      obj(
        field(
          "retainedEventsLimitPerTopic",
          value.retainedEventsLimitPerTopic,
        ),
        field("retainedEventsByTopic", value.retainedEventsByTopic),
        field("appendedEventsByTopic", value.appendedEventsByTopic),
        field("prunedEventsByTopic", value.prunedEventsByTopic),
        field("readByIdMissesByTopic", value.readByIdMissesByTopic),
        field(
          "invalidCursorRejectionsByTopic",
          value.invalidCursorRejectionsByTopic,
        ),
        field(
          "staleCursorRejectionsByTopic",
          value.staleCursorRejectionsByTopic,
        ),
      )

  given JsonDecoder[HotStuffGossipSourceDiagnosticsSnapshot] =
    objDecoder: fields =>
      for
        retainedEventsLimitPerTopic <-
          read[BigNat](fields, "retainedEventsLimitPerTopic")
        retainedEventsByTopic <-
          read[Vector[HotStuffGossipChainTopicCountSnapshot]](
            fields,
            "retainedEventsByTopic",
          )
        appendedEventsByTopic <-
          read[Vector[HotStuffGossipChainTopicCountSnapshot]](
            fields,
            "appendedEventsByTopic",
          )
        prunedEventsByTopic <-
          read[Vector[HotStuffGossipChainTopicCountSnapshot]](
            fields,
            "prunedEventsByTopic",
          )
        readByIdMissesByTopic <-
          read[Vector[HotStuffGossipChainTopicCountSnapshot]](
            fields,
            "readByIdMissesByTopic",
          )
        invalidCursorRejectionsByTopic <-
          read[Vector[HotStuffGossipChainTopicCountSnapshot]](
            fields,
            "invalidCursorRejectionsByTopic",
          )
        staleCursorRejectionsByTopic <-
          read[Vector[HotStuffGossipChainTopicCountSnapshot]](
            fields,
            "staleCursorRejectionsByTopic",
          )
      yield HotStuffGossipSourceDiagnosticsSnapshot(
        retainedEventsLimitPerTopic = retainedEventsLimitPerTopic,
        retainedEventsByTopic = retainedEventsByTopic,
        appendedEventsByTopic = appendedEventsByTopic,
        prunedEventsByTopic = prunedEventsByTopic,
        readByIdMissesByTopic = readByIdMissesByTopic,
        invalidCursorRejectionsByTopic = invalidCursorRejectionsByTopic,
        staleCursorRejectionsByTopic = staleCursorRejectionsByTopic,
      )

  given JsonEncoder[HotStuffGossipSinkDiagnosticsSnapshot] =
    value =>
      obj(
        field("policyMode", value.policyMode),
        field("retentionPolicy", value.retentionPolicy),
        field("retainedCounts", value.retainedCounts),
        field("prunedCounts", value.prunedCounts),
        field("retentionWatermarks", value.retentionWatermarks),
        field(
          "relayedValidatedArtifactsByTopic",
          value.relayedValidatedArtifactsByTopic,
        ),
        field(
          "duplicateArtifactsSuppressedByTopic",
          value.duplicateArtifactsSuppressedByTopic,
        ),
        field(
          "rejectedArtifactsByTopicAndReason",
          value.rejectedArtifactsByTopicAndReason,
        ),
      )

  given JsonDecoder[HotStuffGossipSinkDiagnosticsSnapshot] =
    objDecoder: fields =>
      for
        policyMode      <- read[String](fields, "policyMode")
        retentionPolicy <-
          read[HotStuffSinkRetentionPolicySnapshot](fields, "retentionPolicy")
        retainedCounts <-
          read[HotStuffSinkRetentionCountsSnapshot](fields, "retainedCounts")
        prunedCounts <-
          read[HotStuffSinkRetentionCountsSnapshot](fields, "prunedCounts")
        retentionWatermarks <-
          read[HotStuffSinkRetentionWatermarksSnapshot](
            fields,
            "retentionWatermarks",
          )
        relayedValidatedArtifactsByTopic <-
          read[Vector[HotStuffGossipTopicCountSnapshot]](
            fields,
            "relayedValidatedArtifactsByTopic",
          )
        duplicateArtifactsSuppressedByTopic <-
          read[Vector[HotStuffGossipTopicCountSnapshot]](
            fields,
            "duplicateArtifactsSuppressedByTopic",
          )
        rejectedArtifactsByTopicAndReason <-
          read[Vector[HotStuffGossipTopicReasonCountSnapshot]](
            fields,
            "rejectedArtifactsByTopicAndReason",
          )
      yield HotStuffGossipSinkDiagnosticsSnapshot(
        policyMode = policyMode,
        retentionPolicy = retentionPolicy,
        retainedCounts = retainedCounts,
        prunedCounts = prunedCounts,
        retentionWatermarks = retentionWatermarks,
        relayedValidatedArtifactsByTopic = relayedValidatedArtifactsByTopic,
        duplicateArtifactsSuppressedByTopic =
          duplicateArtifactsSuppressedByTopic,
        rejectedArtifactsByTopicAndReason = rejectedArtifactsByTopicAndReason,
      )

  given JsonEncoder[HotStuffSinkRetentionPolicySnapshot] =
    value =>
      obj(
        field("finalizedHeightLag", value.finalizedHeightLag),
        field("certifiedHeightLag", value.certifiedHeightLag),
        field("retainedTimeoutWindows", value.retainedTimeoutWindows),
        field("retainedNewViewWindows", value.retainedNewViewWindows),
        field("retainedDuplicateEvents", value.retainedDuplicateEvents),
        field(
          "retainedRejectedEventSamples",
          value.retainedRejectedEventSamples,
        ),
        field("pruneEveryAcceptedEvents", value.pruneEveryAcceptedEvents),
      )

  given JsonDecoder[HotStuffSinkRetentionPolicySnapshot] =
    objDecoder: fields =>
      for
        finalizedHeightLag     <- read[BigNat](fields, "finalizedHeightLag")
        certifiedHeightLag     <- read[BigNat](fields, "certifiedHeightLag")
        retainedTimeoutWindows <-
          read[BigNat](fields, "retainedTimeoutWindows")
        retainedNewViewWindows <-
          read[BigNat](fields, "retainedNewViewWindows")
        retainedDuplicateEvents <-
          read[BigNat](fields, "retainedDuplicateEvents")
        retainedRejectedEventSamples <-
          read[BigNat](fields, "retainedRejectedEventSamples")
        pruneEveryAcceptedEvents <-
          read[BigNat](fields, "pruneEveryAcceptedEvents")
      yield HotStuffSinkRetentionPolicySnapshot(
        finalizedHeightLag = finalizedHeightLag,
        certifiedHeightLag = certifiedHeightLag,
        retainedTimeoutWindows = retainedTimeoutWindows,
        retainedNewViewWindows = retainedNewViewWindows,
        retainedDuplicateEvents = retainedDuplicateEvents,
        retainedRejectedEventSamples = retainedRejectedEventSamples,
        pruneEveryAcceptedEvents = pruneEveryAcceptedEvents,
      )

  given JsonEncoder[HotStuffSinkRetentionCountsSnapshot] =
    value =>
      obj(
        field("proposals", value.proposals),
        field("votes", value.votes),
        field("voteAccumulatorVotes", value.voteAccumulatorVotes),
        field(
          "voteAccumulatorEquivocationKeys",
          value.voteAccumulatorEquivocationKeys,
        ),
        field("timeoutVotes", value.timeoutVotes),
        field("timeoutAccumulatorVotes", value.timeoutAccumulatorVotes),
        field(
          "timeoutAccumulatorEquivocationKeys",
          value.timeoutAccumulatorEquivocationKeys,
        ),
        field("timeoutCertificates", value.timeoutCertificates),
        field("newViews", value.newViews),
        field("newViewsBySenderWindow", value.newViewsBySenderWindow),
        field("qcs", value.qcs),
        field("safetyFaults", value.safetyFaults),
        field("duplicateSamples", value.duplicateSamples),
      )

  given JsonDecoder[HotStuffSinkRetentionCountsSnapshot] =
    objDecoder: fields =>
      for
        proposals            <- read[BigNat](fields, "proposals")
        votes                <- read[BigNat](fields, "votes")
        voteAccumulatorVotes <- read[BigNat](fields, "voteAccumulatorVotes")
        voteAccumulatorEquivocationKeys <-
          read[BigNat](fields, "voteAccumulatorEquivocationKeys")
        timeoutVotes            <- read[BigNat](fields, "timeoutVotes")
        timeoutAccumulatorVotes <-
          read[BigNat](fields, "timeoutAccumulatorVotes")
        timeoutAccumulatorEquivocationKeys <-
          read[BigNat](fields, "timeoutAccumulatorEquivocationKeys")
        timeoutCertificates    <- read[BigNat](fields, "timeoutCertificates")
        newViews               <- read[BigNat](fields, "newViews")
        newViewsBySenderWindow <- read[BigNat](fields, "newViewsBySenderWindow")
        qcs                    <- read[BigNat](fields, "qcs")
        safetyFaults           <- read[BigNat](fields, "safetyFaults")
        duplicateSamples       <- read[BigNat](fields, "duplicateSamples")
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

  given JsonEncoder[HotStuffSinkRetentionWatermarksSnapshot] =
    value =>
      obj(
        field(
          "finalizedRetainFromHeightByChain",
          value.finalizedRetainFromHeightByChain,
        ),
        field(
          "certifiedRetainFromHeightByChain",
          value.certifiedRetainFromHeightByChain,
        ),
        field(
          "retainedTimeoutWindowFloorByChain",
          value.retainedTimeoutWindowFloorByChain,
        ),
        field(
          "retainedNewViewWindowFloorByChain",
          value.retainedNewViewWindowFloorByChain,
        ),
      )

  given JsonDecoder[HotStuffSinkRetentionWatermarksSnapshot] =
    objDecoder: fields =>
      for
        finalizedRetainFromHeightByChain <-
          read[Vector[HotStuffGossipChainHeightWatermarkSnapshot]](
            fields,
            "finalizedRetainFromHeightByChain",
          )
        certifiedRetainFromHeightByChain <-
          read[Vector[HotStuffGossipChainHeightWatermarkSnapshot]](
            fields,
            "certifiedRetainFromHeightByChain",
          )
        retainedTimeoutWindowFloorByChain <-
          read[Vector[HotStuffGossipWindowWatermarkSnapshot]](
            fields,
            "retainedTimeoutWindowFloorByChain",
          )
        retainedNewViewWindowFloorByChain <-
          read[Vector[HotStuffGossipWindowWatermarkSnapshot]](
            fields,
            "retainedNewViewWindowFloorByChain",
          )
      yield HotStuffSinkRetentionWatermarksSnapshot(
        finalizedRetainFromHeightByChain = finalizedRetainFromHeightByChain,
        certifiedRetainFromHeightByChain = certifiedRetainFromHeightByChain,
        retainedTimeoutWindowFloorByChain = retainedTimeoutWindowFloorByChain,
        retainedNewViewWindowFloorByChain = retainedNewViewWindowFloorByChain,
      )

  given JsonEncoder[HotStuffGossipChainTopicCountSnapshot] =
    value =>
      obj(
        field("chainId", value.chainId),
        field("topic", value.topic),
        field("count", value.count),
      )

  given JsonDecoder[HotStuffGossipChainTopicCountSnapshot] =
    objDecoder: fields =>
      for
        chainId <- read[ChainId](fields, "chainId")
        topic   <- read[GossipTopic](fields, "topic")
        count   <- read[BigNat](fields, "count")
      yield HotStuffGossipChainTopicCountSnapshot(
        chainId = chainId,
        topic = topic,
        count = count,
      )

  given JsonEncoder[HotStuffGossipTopicCountSnapshot] =
    value =>
      obj(
        field("topic", value.topic),
        field("count", value.count),
      )

  given JsonDecoder[HotStuffGossipTopicCountSnapshot] =
    objDecoder: fields =>
      for
        topic <- read[GossipTopic](fields, "topic")
        count <- read[BigNat](fields, "count")
      yield HotStuffGossipTopicCountSnapshot(topic = topic, count = count)

  given JsonEncoder[HotStuffGossipTopicReasonCountSnapshot] =
    value =>
      obj(
        field("topic", value.topic),
        field("reason", value.reason),
        field("count", value.count),
      )

  given JsonDecoder[HotStuffGossipTopicReasonCountSnapshot] =
    objDecoder: fields =>
      for
        topic  <- read[GossipTopic](fields, "topic")
        reason <- read[String](fields, "reason")
        count  <- read[BigNat](fields, "count")
      yield HotStuffGossipTopicReasonCountSnapshot(
        topic = topic,
        reason = reason,
        count = count,
      )

  given JsonEncoder[HotStuffGossipChainHeightWatermarkSnapshot] =
    value =>
      obj(
        field("chainId", value.chainId),
        field("height", value.height),
      )

  given JsonDecoder[HotStuffGossipChainHeightWatermarkSnapshot] =
    objDecoder: fields =>
      for
        chainId <- read[ChainId](fields, "chainId")
        height  <- read[BigNat](fields, "height")
      yield HotStuffGossipChainHeightWatermarkSnapshot(
        chainId = chainId,
        height = height,
      )

  given JsonEncoder[HotStuffGossipWindowWatermarkSnapshot] =
    value =>
      obj(
        field("chainId", value.chainId),
        field("height", value.height),
        field("view", value.view),
        field("validatorSetHash", value.validatorSetHash),
      )

  given JsonDecoder[HotStuffGossipWindowWatermarkSnapshot] =
    objDecoder: fields =>
      for
        chainId          <- read[ChainId](fields, "chainId")
        height           <- read[BigNat](fields, "height")
        view             <- read[BigNat](fields, "view")
        validatorSetHash <- read[ValidatorSetHash](fields, "validatorSetHash")
      yield HotStuffGossipWindowWatermarkSnapshot(
        chainId = chainId,
        height = height,
        view = view,
        validatorSetHash = validatorSetHash,
      )

  given JsonEncoder[HotStuffGossipProjectionDroppedEntry] =
    value =>
      obj(
        field("component", value.component),
        field("field", value.field),
        field("reason", value.reason),
        optionalField("context", value.context),
        field("count", value.count),
      )

  given JsonDecoder[HotStuffGossipProjectionDroppedEntry] =
    objDecoder: fields =>
      for
        component <- read[String](fields, "component")
        fieldName <- read[String](fields, "field")
        reason    <- read[String](fields, "reason")
        context   <- readOptional[String](fields, "context")
        count     <- read[BigNat](fields, "count")
      yield HotStuffGossipProjectionDroppedEntry(
        component = component,
        field = fieldName,
        reason = reason,
        context = context,
        count = count,
      )

  given JsonEncoder[HotStuffGossipDiagnosticsProjectionWarning] =
    value =>
      obj(
        field("component", value.component),
        field("reason", value.reason),
        optionalField("message", value.message),
      )

  given JsonDecoder[HotStuffGossipDiagnosticsProjectionWarning] =
    objDecoder: fields =>
      for
        component <- read[String](fields, "component")
        reason    <- read[String](fields, "reason")
        message   <- readOptional[String](fields, "message")
      yield HotStuffGossipDiagnosticsProjectionWarning(
        component = component,
        reason = reason,
        message = message,
      )

  given JsonEncoder[HotStuffGossipDiagnosticsProjectionMetrics] =
    value =>
      obj(
        field("sourceVariableEntries", value.sourceVariableEntries),
        field("sinkVariableEntries", value.sinkVariableEntries),
        field("parentVariableEntries", value.parentVariableEntries),
        field("sourceEstimatedBytes", value.sourceEstimatedBytes),
        field("sinkEstimatedBytes", value.sinkEstimatedBytes),
        field("parentEstimatedBytes", value.parentEstimatedBytes),
      )

  given JsonDecoder[HotStuffGossipDiagnosticsProjectionMetrics] =
    objDecoder: fields =>
      for
        sourceVariableEntries <- read[BigNat](fields, "sourceVariableEntries")
        sinkVariableEntries   <- read[BigNat](fields, "sinkVariableEntries")
        parentVariableEntries <- read[BigNat](fields, "parentVariableEntries")
        sourceEstimatedBytes  <- read[BigNat](fields, "sourceEstimatedBytes")
        sinkEstimatedBytes    <- read[BigNat](fields, "sinkEstimatedBytes")
        parentEstimatedBytes  <- read[BigNat](fields, "parentEstimatedBytes")
      yield HotStuffGossipDiagnosticsProjectionMetrics(
        sourceVariableEntries = sourceVariableEntries,
        sinkVariableEntries = sinkVariableEntries,
        parentVariableEntries = parentVariableEntries,
        sourceEstimatedBytes = sourceEstimatedBytes,
        sinkEstimatedBytes = sinkEstimatedBytes,
        parentEstimatedBytes = parentEstimatedBytes,
      )

  given JsonEncoder[HotStuffGossipDiagnosticsSnapshot] =
    value =>
      obj(
        optionalField("source", value.source),
        optionalField("sink", value.sink),
        field("warnings", value.warnings),
        field("metrics", value.metrics),
        field("projectionDroppedEntries", value.projectionDroppedEntries),
        field(
          "projectionDroppedEntryOverflowGroups",
          value.projectionDroppedEntryOverflowGroups,
        ),
        field(
          "projectionDroppedEntryOverflowCount",
          value.projectionDroppedEntryOverflowCount,
        ),
        field("emergencySuppressed", value.emergencySuppressed),
        optionalField(
          "emergencySuppressionReason",
          value.emergencySuppressionReason,
        ),
      )

  given JsonDecoder[HotStuffGossipDiagnosticsSnapshot] =
    objDecoder: fields =>
      for
        source <-
          readOptional[HotStuffGossipSourceDiagnosticsSnapshot](
            fields,
            "source",
          )
        sink <-
          readOptional[HotStuffGossipSinkDiagnosticsSnapshot](fields, "sink")
        warnings <-
          read[Vector[HotStuffGossipDiagnosticsProjectionWarning]](
            fields,
            "warnings",
          )
        metrics <-
          read[HotStuffGossipDiagnosticsProjectionMetrics](fields, "metrics")
        projectionDroppedEntries <-
          read[Vector[HotStuffGossipProjectionDroppedEntry]](
            fields,
            "projectionDroppedEntries",
          )
        projectionDroppedEntryOverflowGroups <-
          read[BigNat](fields, "projectionDroppedEntryOverflowGroups")
        projectionDroppedEntryOverflowCount <-
          read[BigNat](fields, "projectionDroppedEntryOverflowCount")
        emergencySuppressed <- read[Boolean](fields, "emergencySuppressed")
        emergencySuppressionReason <-
          readOptional[String](fields, "emergencySuppressionReason")
      yield HotStuffGossipDiagnosticsSnapshot(
        source = source,
        sink = sink,
        warnings = warnings,
        metrics = metrics,
        projectionDroppedEntries = projectionDroppedEntries,
        projectionDroppedEntryOverflowGroups =
          projectionDroppedEntryOverflowGroups,
        projectionDroppedEntryOverflowCount =
          projectionDroppedEntryOverflowCount,
        emergencySuppressed = emergencySuppressed,
        emergencySuppressionReason = emergencySuppressionReason,
      )

  private def write[A: JsonEncoder](
      value: A,
  ): JsonValue =
    JsonEncoder[A].encode(value)

  private final case class ObjectField(
      name: String,
      value: JsonValue,
      omitWhenNull: Boolean,
  )

  private def field[A: JsonEncoder](
      name: String,
      value: A,
  ): ObjectField =
    ObjectField(name, write(value), omitWhenNull = false)

  private def optionalField[A: JsonEncoder](
      name: String,
      value: Option[A],
  ): ObjectField =
    ObjectField(name, write(value), omitWhenNull = true)

  private def read[A: JsonDecoder](
      fields: Map[String, JsonValue],
      name: String,
  ): Either[DecodeFailure, A] =
    fields.get(name) match
      case Some(value) =>
        JsonDecoder[A]
          .decode(value)
          .left
          .map:
            case DecodeFailure(message: String) =>
              DecodeFailure(name.concat(": ").concat(message))
      case None =>
        Left[DecodeFailure, A](DecodeFailure(name.concat(": missing field")))

  private def readOptional[A: JsonDecoder](
      fields: Map[String, JsonValue],
      name: String,
  ): Either[DecodeFailure, Option[A]] =
    fields.get(name) match
      case None        => Right[DecodeFailure, Option[A]](None)
      case Some(value) =>
        JsonDecoder[Option[A]]
          .decode(value)
          .left
          .map:
            case DecodeFailure(message: String) =>
              DecodeFailure(name.concat(": ").concat(message))

  private def obj(
      fields: ObjectField*,
  ): JsonValue =
    JsonValue.JObject(
      ListMap.from(
        fields.flatMap:
          case ObjectField(_, JsonValue.JNull, true) => None
          case ObjectField(name, value, _)           => Some(name -> value),
      ),
    )

  private def objDecoder[A](
      decode: Map[String, JsonValue] => Either[DecodeFailure, A],
  ): JsonDecoder[A] =
    json =>
      json match
        case JsonValue.JObject(fields) => decode(fields)
        case _ => Left[DecodeFailure, A](DecodeFailure("expected object"))

  private def stringDecoder[A](
      parse: String => Either[String, A],
  ): JsonDecoder[A] =
    json =>
      boundedString(json)
        .flatMap(parse)
        .left
        .map(DecodeFailure(_))

  private def boundedString(
      json: JsonValue,
  ): Either[String, String] =
    json match
      case JsonValue.JString(value)
          if utf8ByteLengthWithin(value, MaxStringUtf8Bytes.toLong).isEmpty =>
        Left[String, String]("string exceeds maximum UTF-8 byte length")
      case JsonValue.JString(value) => Right[String, String](value)
      case _                        => Left[String, String]("expected string")

  private def jsonValuesStringUtf8BytesWithin(
      values: Vector[JsonValue],
      limit: Long,
  ): Option[Long] =
    values.foldLeft[Option[Long]](Some(0L)): (acc, value) =>
      acc.flatMap: used =>
        jsonValueStringUtf8BytesWithin(value, limit - used).flatMap: bytes =>
          val next = used + bytes
          if next > limit then None else Some(next)

  private def jsonValueStringUtf8BytesWithin(
      value: JsonValue,
      limit: Long,
  ): Option[Long] =
    if limit < 0L then None
    else
      value match
        case JsonValue.JString(text) =>
          utf8ByteLengthWithin(text, limit)
        case JsonValue.JArray(values) =>
          jsonValuesStringUtf8BytesWithin(values, limit)
        case JsonValue.JObject(fields) =>
          jsonObjectStringUtf8BytesWithin(fields, limit)
        case JsonValue.JNull | JsonValue.JBool(_) | JsonValue.JNumber(_) =>
          Some(0L)

  private def jsonObjectStringUtf8BytesWithin(
      fields: Map[String, JsonValue],
      limit: Long,
  ): Option[Long] =
    fields.foldLeft[Option[Long]](Some(0L)): (acc, entry) =>
      acc.flatMap: used =>
        val (key, value) = entry
        utf8ByteLengthWithin(key, limit - used).flatMap: keyBytes =>
          val afterKey = used + keyBytes
          if afterKey > limit then None
          else
            jsonValueStringUtf8BytesWithin(value, limit - afterKey).flatMap:
              valueBytes =>
                val next = afterKey + valueBytes
                if next > limit then None else Some(next)

  private def utf8ByteLengthWithin(
      value: String,
      limit: Long,
  ): Option[Long] =
    if limit < 0L then None
    else utf8ByteLengthWithinLoop(value, offset = 0, bytes = 0L, limit = limit)

  @tailrec
  private def utf8ByteLengthWithinLoop(
      value: String,
      offset: Int,
      bytes: Long,
      limit: Long,
  ): Option[Long] =
    if offset >= value.length then Some(bytes)
    else
      val codePoint = value.codePointAt(offset)
      val nextBytes = bytes + utf8CodePointBytes(codePoint)
      if nextBytes > limit then None
      else
        utf8ByteLengthWithinLoop(
          value = value,
          offset = offset + Character.charCount(codePoint),
          bytes = nextBytes,
          limit = limit,
        )

  private def utf8CodePointBytes(
      codePoint: Int,
  ): Long =
    if codePoint < 0x80 then 1L
    else if codePoint < 0x800 then 2L
    else if codePoint < 0x10000 then 3L
    else 4L

  private def isCanonicalDecimal(
      value: String,
  ): Boolean =
    value match
      case "0" => true
      case _   =>
        value.nonEmpty &&
        value.head >= '1' &&
        value.head <= '9' &&
        value.forall(ch => ch >= '0' && ch <= '9')
