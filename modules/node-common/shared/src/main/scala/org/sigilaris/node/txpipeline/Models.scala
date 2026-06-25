package org.sigilaris.node.txpipeline

import java.nio.charset.StandardCharsets
import java.time.Instant

import scala.util.Try

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*

final case class TxPipelineId(value: String)

object TxPipelineId:
  def parse(value: String): Either[String, TxPipelineId] =
    TxPipelineFieldValidation.nonBlank("pipelineId", value).map(TxPipelineId(_))

  given Encoder[TxPipelineId] = Encoder.encodeString.contramap(_.value)
  given Decoder[TxPipelineId] = Decoder.decodeString.emap(parse)

final case class TxPipelineTransactionPayload(value: String):
  def utf8ByteSize: Long =
    value.getBytes(StandardCharsets.UTF_8).length.toLong

object TxPipelineTransactionPayload:
  given Encoder[TxPipelineTransactionPayload] =
    Encoder.encodeString.contramap(_.value)
  given Decoder[TxPipelineTransactionPayload] =
    Decoder.decodeString.map(TxPipelineTransactionPayload(_))

final case class TxPipelineTxHash(value: String)

object TxPipelineTxHash:
  def parse(value: String): Either[String, TxPipelineTxHash] =
    TxPipelineFieldValidation.nonBlank("txHash", value).map(TxPipelineTxHash(_))

  given Encoder[TxPipelineTxHash] = Encoder.encodeString.contramap(_.value)
  given Decoder[TxPipelineTxHash] = Decoder.decodeString.emap(parse)

final case class TxPipelineCanonicalPayloadHash(value: String)

object TxPipelineCanonicalPayloadHash:
  def parse(value: String): Either[String, TxPipelineCanonicalPayloadHash] =
    TxPipelineFieldValidation
      .nonBlank("canonicalPayloadHash", value)
      .map(TxPipelineCanonicalPayloadHash(_))

  given Encoder[TxPipelineCanonicalPayloadHash] =
    Encoder.encodeString.contramap(_.value)
  given Decoder[TxPipelineCanonicalPayloadHash] =
    Decoder.decodeString.emap(parse)

final case class TxPipelineIdempotencyKey(value: String)

object TxPipelineIdempotencyKey:
  def parse(value: String): Either[String, TxPipelineIdempotencyKey] =
    TxPipelineFieldValidation
      .nonBlank("idempotencyKey", value)
      .map(TxPipelineIdempotencyKey(_))

  given Encoder[TxPipelineIdempotencyKey] =
    Encoder.encodeString.contramap(_.value)
  given Decoder[TxPipelineIdempotencyKey] =
    Decoder.decodeString.emap(parse)

enum TxPipelineWaitMode(val wire: String):
  case Accepted  extends TxPipelineWaitMode("accepted")
  case Certified extends TxPipelineWaitMode("certified")
  case Finalized extends TxPipelineWaitMode("finalized")

@SuppressWarnings(
  Array("org.wartremover.warts.Any", "org.wartremover.warts.Equals"),
)
object TxPipelineWaitMode:
  val all: Vector[TxPipelineWaitMode] = Vector(Accepted, Certified, Finalized)

  def parse(value: String): Either[String, TxPipelineWaitMode] =
    all
      .find(_.wire == value)
      .toRight(s"unsupported waitFor: $value")

  given Encoder[TxPipelineWaitMode] = Encoder.encodeString.contramap(_.wire)
  given Decoder[TxPipelineWaitMode] = Decoder.decodeString.emap(parse)

enum TxPipelineStatus(val wire: String):
  case Accepted        extends TxPipelineStatus("accepted")
  case Running         extends TxPipelineStatus("running")
  case Certified       extends TxPipelineStatus("certified")
  case Finalized       extends TxPipelineStatus("finalized")
  case Failed          extends TxPipelineStatus("failed")
  case PartiallyFailed extends TxPipelineStatus("partiallyFailed")

@SuppressWarnings(
  Array("org.wartremover.warts.Any", "org.wartremover.warts.Equals"),
)
object TxPipelineStatus:
  val all: Vector[TxPipelineStatus] =
    Vector(Accepted, Running, Certified, Finalized, Failed, PartiallyFailed)

  def parse(value: String): Either[String, TxPipelineStatus] =
    all
      .find(_.wire == value)
      .toRight(s"unsupported pipeline status: $value")

  given Encoder[TxPipelineStatus] = Encoder.encodeString.contramap(_.wire)
  given Decoder[TxPipelineStatus] = Decoder.decodeString.emap(parse)

enum TxPipelineStageStatus(val wire: String):
  case Accepted    extends TxPipelineStageStatus("accepted")
  case Held        extends TxPipelineStageStatus("held")
  case Eligible    extends TxPipelineStageStatus("eligible")
  case Proposed    extends TxPipelineStageStatus("proposed")
  case Certified   extends TxPipelineStageStatus("certified")
  case Finalized   extends TxPipelineStageStatus("finalized")
  case Failed      extends TxPipelineStageStatus("failed")
  case Unavailable extends TxPipelineStageStatus("unavailable")

@SuppressWarnings(
  Array("org.wartremover.warts.Any", "org.wartremover.warts.Equals"),
)
object TxPipelineStageStatus:
  val all: Vector[TxPipelineStageStatus] =
    Vector(
      Accepted,
      Held,
      Eligible,
      Proposed,
      Certified,
      Finalized,
      Failed,
      Unavailable,
    )

  def parse(value: String): Either[String, TxPipelineStageStatus] =
    all
      .find(_.wire == value)
      .toRight(s"unsupported stage status: $value")

  given Encoder[TxPipelineStageStatus] = Encoder.encodeString.contramap(_.wire)
  given Decoder[TxPipelineStageStatus] = Decoder.decodeString.emap(parse)

enum TxPipelineTransactionState(val wire: String):
  case Accepted    extends TxPipelineTransactionState("accepted")
  case Held        extends TxPipelineTransactionState("held")
  case Eligible    extends TxPipelineTransactionState("eligible")
  case Proposed    extends TxPipelineTransactionState("proposed")
  case Certified   extends TxPipelineTransactionState("certified")
  case Finalized   extends TxPipelineTransactionState("finalized")
  case Failed      extends TxPipelineTransactionState("failed")
  case Unavailable extends TxPipelineTransactionState("unavailable")

@SuppressWarnings(
  Array("org.wartremover.warts.Any", "org.wartremover.warts.Equals"),
)
object TxPipelineTransactionState:
  val all: Vector[TxPipelineTransactionState] =
    Vector(
      Accepted,
      Held,
      Eligible,
      Proposed,
      Certified,
      Finalized,
      Failed,
      Unavailable,
    )

  def parse(value: String): Either[String, TxPipelineTransactionState] =
    all
      .find(_.wire == value)
      .toRight(s"unsupported transaction pipeline state: $value")

  given Encoder[TxPipelineTransactionState] =
    Encoder.encodeString.contramap(_.wire)
  given Decoder[TxPipelineTransactionState] = Decoder.decodeString.emap(parse)

final case class TxPipelineSubmitRequest(
    stages: Vector[Vector[TxPipelineTransactionPayload]],
    waitFor: TxPipelineWaitMode,
)

object TxPipelineSubmitRequest:
  def oneStage(
      payload: TxPipelineTransactionPayload,
      waitFor: TxPipelineWaitMode,
  ): TxPipelineSubmitRequest =
    TxPipelineSubmitRequest(Vector(Vector(payload)), waitFor)

  given Decoder[TxPipelineSubmitRequest] = deriveDecoder
  given Encoder[TxPipelineSubmitRequest] = deriveEncoder

final case class TxPipelineSubmittedTransaction(
    stageIndex: Int,
    transactionIndex: Int,
    payload: TxPipelineTransactionPayload,
)

object TxPipelineSubmittedTransaction:
  given Decoder[TxPipelineSubmittedTransaction] = deriveDecoder
  given Encoder[TxPipelineSubmittedTransaction] = deriveEncoder

final case class TxPipelineNormalizedStage(
    stageIndex: Int,
    transactions: Vector[TxPipelineSubmittedTransaction],
)

object TxPipelineNormalizedStage:
  given Decoder[TxPipelineNormalizedStage] = deriveDecoder
  given Encoder[TxPipelineNormalizedStage] = deriveEncoder

final case class TxPipelineNormalizedRequest(
    stages: Vector[TxPipelineNormalizedStage],
    waitFor: TxPipelineWaitMode,
):
  def canonicalPayload: TxPipelineCanonicalPayload =
    TxPipelineCanonicalPayload.fromNormalized(this)

object TxPipelineNormalizedRequest:
  given Decoder[TxPipelineNormalizedRequest] = deriveDecoder
  given Encoder[TxPipelineNormalizedRequest] = deriveEncoder

final case class TxPipelineCanonicalPayload(value: String)

@SuppressWarnings(Array("org.wartremover.warts.Any"))
object TxPipelineCanonicalPayload:
  def fromNormalized(
      normalized: TxPipelineNormalizedRequest,
  ): TxPipelineCanonicalPayload =
    val stageParts = normalized.stages.map: stage =>
      val txParts = stage.transactions.map: tx =>
        val payload = tx.payload.value
        val bytes   = tx.payload.utf8ByteSize
        s"tx:${tx.transactionIndex}:$bytes:$payload\n"
      s"stage:${stage.stageIndex}:${stage.transactions.size}\n${txParts.mkString}"

    TxPipelineCanonicalPayload(
      s"waitFor:${normalized.waitFor.wire}\n${stageParts.mkString}",
    )

final case class TxPipelineShapeLimits(
    maxStages: Int,
    maxTransactionsPerStage: Int,
    maxTotalTransactions: Int,
    maxTransactionPayloadBytes: Long,
    maxTotalPayloadBytes: Long,
    maxAcceptedNonterminalPipelines: Int,
)

object TxPipelineShapeLimits:
  val default: TxPipelineShapeLimits =
    TxPipelineShapeLimits(
      maxStages = 64,
      maxTransactionsPerStage = 1024,
      maxTotalTransactions = 4096,
      maxTransactionPayloadBytes = 1048576L,
      maxTotalPayloadBytes = 16777216L,
      maxAcceptedNonterminalPipelines = 10000,
    )

  given Decoder[TxPipelineShapeLimits] = deriveDecoder
  given Encoder[TxPipelineShapeLimits] = deriveEncoder

final case class TxPipelineValidationFailure(
    reason: String,
    detail: Option[String],
    stageIndex: Option[Int],
    transactionIndex: Option[Int],
)

object TxPipelineValidationFailure:
  def apply(reason: String, detail: String): TxPipelineValidationFailure =
    TxPipelineValidationFailure(reason, Some(detail), None, None)

  def atStage(
      reason: String,
      detail: String,
      stageIndex: Int,
  ): TxPipelineValidationFailure =
    TxPipelineValidationFailure(reason, Some(detail), Some(stageIndex), None)

  def atTransaction(
      reason: String,
      detail: String,
      stageIndex: Int,
      transactionIndex: Int,
  ): TxPipelineValidationFailure =
    TxPipelineValidationFailure(
      reason,
      Some(detail),
      Some(stageIndex),
      Some(transactionIndex),
    )

  given Decoder[TxPipelineValidationFailure] = deriveDecoder
  given Encoder[TxPipelineValidationFailure] = deriveEncoder

final case class TxPipelineErrorResponse(
    reason: String,
    detail: Option[String],
    pipelineId: Option[TxPipelineId],
    idempotencyKey: Option[TxPipelineIdempotencyKey],
    validationFailure: Option[TxPipelineValidationFailure],
)

@SuppressWarnings(Array("org.wartremover.warts.Any"))
object TxPipelineErrorResponse:
  def validation(
      failure: TxPipelineValidationFailure,
  ): TxPipelineErrorResponse =
    TxPipelineErrorResponse(
      reason = failure.reason,
      detail = failure.detail,
      pipelineId = None,
      idempotencyKey = None,
      validationFailure = Some(failure),
    )

  def idempotencyConflict(
      idempotencyKey: TxPipelineIdempotencyKey,
      existingPipelineId: TxPipelineId,
  ): TxPipelineErrorResponse =
    TxPipelineErrorResponse(
      reason = "idempotencyConflict",
      detail = Some("idempotency key is already bound to a different payload"),
      pipelineId = Some(existingPipelineId),
      idempotencyKey = Some(idempotencyKey),
      validationFailure = None,
    )

  def notFound(
      pipelineId: TxPipelineId,
  ): TxPipelineErrorResponse =
    TxPipelineErrorResponse(
      reason = "pipelineNotFound",
      detail = Some(s"pipeline not found: ${pipelineId.value}"),
      pipelineId = Some(pipelineId),
      idempotencyKey = None,
      validationFailure = None,
    )

  def storeRejected(
      detail: String,
  ): TxPipelineErrorResponse =
    TxPipelineErrorResponse(
      reason = "pipelineStoreRejected",
      detail = Some(detail),
      pipelineId = None,
      idempotencyKey = None,
      validationFailure = None,
    )

  given Decoder[TxPipelineErrorResponse] = deriveDecoder
  given Encoder[TxPipelineErrorResponse] = deriveEncoder

final case class TxPipelineConsensusPlacement(
    blockHash: String,
    height: Long,
    certifiedObservedAt: Option[Instant],
    finalizedObservedAt: Option[Instant],
)

object TxPipelineConsensusPlacement:
  private given Encoder[Instant] = Encoder.encodeString.contramap(_.toString)
  private given Decoder[Instant] = Decoder.decodeString.emap: value =>
    Try(Instant.parse(value)).toEither.left.map(_.getMessage)

  given Decoder[TxPipelineConsensusPlacement] = deriveDecoder
  given Encoder[TxPipelineConsensusPlacement] = deriveEncoder

final case class TxPipelineCertifiedAncestorBarrier(
    dependsOnStage: Int,
    satisfied: Boolean,
    satisfiedByBlockHash: Option[String],
)

object TxPipelineCertifiedAncestorBarrier:
  given Decoder[TxPipelineCertifiedAncestorBarrier] = deriveDecoder
  given Encoder[TxPipelineCertifiedAncestorBarrier] = deriveEncoder

final case class TxPipelineTransactionRecord(
    transactionIndex: Int,
    payload: TxPipelineTransactionPayload,
    txHash: TxPipelineTxHash,
    pipelineState: TxPipelineTransactionState,
    applicationStatus: Option[String],
    placements: Vector[TxPipelineConsensusPlacement],
)

object TxPipelineTransactionRecord:
  given Decoder[TxPipelineTransactionRecord] = deriveDecoder
  given Encoder[TxPipelineTransactionRecord] = deriveEncoder

final case class TxPipelineStageRecord(
    stageIndex: Int,
    status: TxPipelineStageStatus,
    batchId: Option[String],
    barrier: Option[TxPipelineCertifiedAncestorBarrier],
    placements: Vector[TxPipelineConsensusPlacement],
    transactions: Vector[TxPipelineTransactionRecord],
)

object TxPipelineStageRecord:
  given Decoder[TxPipelineStageRecord] = deriveDecoder
  given Encoder[TxPipelineStageRecord] = deriveEncoder

final case class TxPipelineTransactionSnapshot(
    transactionIndex: Int,
    txHash: TxPipelineTxHash,
    pipelineState: TxPipelineTransactionState,
    applicationStatus: Option[String],
)

object TxPipelineTransactionSnapshot:
  given Decoder[TxPipelineTransactionSnapshot] = deriveDecoder
  given Encoder[TxPipelineTransactionSnapshot] = deriveEncoder

final case class TxPipelineStageSnapshot(
    stageIndex: Int,
    status: TxPipelineStageStatus,
    batchId: Option[String],
    barrier: Option[TxPipelineCertifiedAncestorBarrier],
    placements: Vector[TxPipelineConsensusPlacement],
    transactions: Vector[TxPipelineTransactionSnapshot],
)

object TxPipelineStageSnapshot:
  given Decoder[TxPipelineStageSnapshot] = deriveDecoder
  given Encoder[TxPipelineStageSnapshot] = deriveEncoder

final case class TxPipelineSnapshot(
    pipelineId: TxPipelineId,
    status: TxPipelineStatus,
    waitFor: TxPipelineWaitMode,
    stages: Vector[TxPipelineStageSnapshot],
)

object TxPipelineSnapshot:
  given Decoder[TxPipelineSnapshot] = deriveDecoder
  given Encoder[TxPipelineSnapshot] = deriveEncoder

final case class TxPipelineRecord(
    pipelineId: TxPipelineId,
    idempotencyKey: Option[TxPipelineIdempotencyKey],
    canonicalPayloadHash: TxPipelineCanonicalPayloadHash,
    status: TxPipelineStatus,
    waitFor: TxPipelineWaitMode,
    stages: Vector[TxPipelineStageRecord],
    acceptedAt: Instant,
    updatedAt: Instant,
):
  def snapshot: TxPipelineSnapshot =
    TxPipelineSnapshot(
      pipelineId = pipelineId,
      status = status,
      waitFor = waitFor,
      stages = stages.map: stage =>
        TxPipelineStageSnapshot(
          stageIndex = stage.stageIndex,
          status = stage.status,
          batchId = stage.batchId,
          barrier = stage.barrier,
          placements = stage.placements,
          transactions = stage.transactions.map: tx =>
            TxPipelineTransactionSnapshot(
              transactionIndex = tx.transactionIndex,
              txHash = tx.txHash,
              pipelineState = tx.pipelineState,
              applicationStatus = tx.applicationStatus,
            ),
        ),
    )

@SuppressWarnings(Array("org.wartremover.warts.Nothing"))
object TxPipelineRecord:
  private given Encoder[Instant] = Encoder.encodeString.contramap(_.toString)
  private given Decoder[Instant] = Decoder.decodeString.emap: value =>
    Try(Instant.parse(value)).toEither.left.map(_.getMessage)

  def accepted(
      pipelineId: TxPipelineId,
      normalized: TxPipelineNormalizedRequest,
      txHashes: Vector[Vector[TxPipelineTxHash]],
      idempotencyKey: Option[TxPipelineIdempotencyKey],
      canonicalPayloadHash: TxPipelineCanonicalPayloadHash,
      acceptedAt: Instant,
  ): Either[TxPipelineValidationFailure, TxPipelineRecord] =
    for
      _ <- TxPipelineRequestNormalizer.validateHashShape(normalized, txHashes)
      _ <- TxPipelineRequestNormalizer.validateUniqueHashes(txHashes)
    yield
      val stages = normalized.stages
        .zip(txHashes)
        .map:
          case (stage, stageHashes) =>
            val stageState =
              if stage.stageIndex == 0 then TxPipelineStageStatus.Eligible
              else TxPipelineStageStatus.Held
            val txState =
              if stage.stageIndex == 0 then TxPipelineTransactionState.Eligible
              else TxPipelineTransactionState.Held
            TxPipelineStageRecord(
              stageIndex = stage.stageIndex,
              status = stageState,
              batchId = None,
              barrier =
                if stage.stageIndex == 0 then None
                else
                  Some(
                    TxPipelineCertifiedAncestorBarrier(
                      dependsOnStage = stage.stageIndex - 1,
                      satisfied = false,
                      satisfiedByBlockHash = None,
                    ),
                  )
              ,
              placements = Vector.empty[TxPipelineConsensusPlacement],
              transactions = stage.transactions
                .zip(stageHashes)
                .map:
                  case (tx, txHash) =>
                    TxPipelineTransactionRecord(
                      transactionIndex = tx.transactionIndex,
                      payload = tx.payload,
                      txHash = txHash,
                      pipelineState = txState,
                      applicationStatus = None,
                      placements = Vector.empty[TxPipelineConsensusPlacement],
                    ),
            )

      TxPipelineRecord(
        pipelineId = pipelineId,
        idempotencyKey = idempotencyKey,
        canonicalPayloadHash = canonicalPayloadHash,
        status = TxPipelineStatus.Accepted,
        waitFor = normalized.waitFor,
        stages = stages,
        acceptedAt = acceptedAt,
        updatedAt = acceptedAt,
      )

  given Decoder[TxPipelineRecord] = deriveDecoder
  given Encoder[TxPipelineRecord] = deriveEncoder

@SuppressWarnings(Array("org.wartremover.warts.Any"))
private object TxPipelineFieldValidation:
  def nonBlank(field: String, value: String): Either[String, String] =
    Either.cond(value.trim.nonEmpty, value, s"$field must be non-empty")
