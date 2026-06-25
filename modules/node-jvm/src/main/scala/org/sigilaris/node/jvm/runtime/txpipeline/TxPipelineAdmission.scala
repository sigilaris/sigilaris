package org.sigilaris.node.jvm.runtime.txpipeline

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.Semaphore

import cats.Applicative
import cats.data.EitherT
import cats.effect.Resource
import cats.effect.kernel.Sync
import cats.syntax.all.*

import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.txpipeline.*

trait TxPipelineTransactionHasher[F[_]]:
  def hash(payload: TxPipelineTransactionPayload): F[TxPipelineTxHash]

object TxPipelineTransactionHasher:
  def utf8Sha256[F[_]: Sync]: TxPipelineTransactionHasher[F] =
    payload =>
      Sync[F].delay:
        val digest = MessageDigest.getInstance("SHA-256")
        TxPipelineTxHash(
          TxPipelineSha256.hex(
            digest.digest(payload.value.getBytes(StandardCharsets.UTF_8)),
          ),
        )

trait TxPipelineApplicationAdmission[F[_]]:
  def validate(
      normalized: TxPipelineNormalizedRequest,
  ): F[Either[TxPipelineValidationFailure, Unit]]

object TxPipelineApplicationAdmission:
  def acceptAll[F[_]: Applicative]: TxPipelineApplicationAdmission[F] =
    _ => Applicative[F].pure(Right[TxPipelineValidationFailure, Unit](()))

trait TxPipelineIdGenerator[F[_]]:
  def nextPipelineId: F[TxPipelineId]

trait TxPipelineAdmissionClock[F[_]]:
  def now: F[Instant]

trait TxPipelineWorkNotifier[F[_]]:
  def notifyApplicationWorkAvailable: F[Unit]

object TxPipelineWorkNotifier:
  def noOp[F[_]: Applicative]: TxPipelineWorkNotifier[F] =
    new TxPipelineWorkNotifier[F]:
      override def notifyApplicationWorkAvailable: F[Unit] =
        Applicative[F].unit

final case class TxPipelineAdmissionOutcome(
    snapshot: TxPipelineSnapshot,
    replayed: Boolean,
)

final class TxPipelineAdmissionService[F[_]: Sync](
    store: TxPipelineStore[F],
    idGenerator: TxPipelineIdGenerator[F],
    clock: TxPipelineAdmissionClock[F],
    hasher: TxPipelineTransactionHasher[F],
    applicationAdmission: TxPipelineApplicationAdmission[F],
    workNotifier: TxPipelineWorkNotifier[F],
    limits: TxPipelineShapeLimits,
):
  private val createGate =
    Semaphore(1, true)

  def submit(
      request: TxPipelineSubmitRequest,
      idempotencyKey: Option[TxPipelineIdempotencyKey],
  ): F[Either[TxPipelineAdmissionFailure, TxPipelineSnapshot]] =
    submitOutcome(request, idempotencyKey).map(_.map(_.snapshot))

  def submitOutcome(
      request: TxPipelineSubmitRequest,
      idempotencyKey: Option[TxPipelineIdempotencyKey],
  ): F[Either[TxPipelineAdmissionFailure, TxPipelineAdmissionOutcome]] =
    val result = for
      normalized <- EitherT.fromEither[F]:
        TxPipelineRequestNormalizer
          .normalize(request, limits)
          .leftMap(TxPipelineAdmissionFailure.ValidationRejected(_))
      canonicalHash = TxPipelineAdmissionService.canonicalPayloadHash(
        normalized,
      )
      replay <- existingReplay(idempotencyKey, canonicalHash)
      outcome <- replay match
        case Some(existing) =>
          EitherT.rightT[F, TxPipelineAdmissionFailure](
            TxPipelineAdmissionOutcome(
              snapshot = existing.snapshot,
              replayed = true,
            ),
          )
        case None =>
          acceptNewOutcome(normalized, idempotencyKey, canonicalHash)
    yield outcome

    result.value

  private def existingReplay(
      idempotencyKey: Option[TxPipelineIdempotencyKey],
      canonicalHash: TxPipelineCanonicalPayloadHash,
  ): EitherT[F, TxPipelineAdmissionFailure, Option[TxPipelineRecord]] =
    idempotencyKey match
      case None => EitherT.rightT(None)
      case Some(key) =>
        store
          .getByIdempotencyKey(key)
          .leftMap(TxPipelineAdmissionFailure.StoreRejected(_))
          .flatMap:
            case None => EitherT.rightT[F, TxPipelineAdmissionFailure](None)
            case Some(existing)
                if existing.canonicalPayloadHash.value === canonicalHash.value =>
              EitherT.rightT[F, TxPipelineAdmissionFailure](Some(existing))
            case Some(existing) =>
              EitherT.leftT[F, Option[TxPipelineRecord]](
                TxPipelineAdmissionFailure.IdempotencyConflict(
                  key,
                  existing.pipelineId,
                ),
              )

  private def acceptNewOutcome(
      normalized: TxPipelineNormalizedRequest,
      idempotencyKey: Option[TxPipelineIdempotencyKey],
      canonicalHash: TxPipelineCanonicalPayloadHash,
  ): EitherT[F, TxPipelineAdmissionFailure, TxPipelineAdmissionOutcome] =
    for
      _ <- EitherT:
        applicationAdmission
          .validate(normalized)
          .map(_.leftMap(TxPipelineAdmissionFailure.ValidationRejected(_)))
      txHashes <- hashTransactions(normalized)
      pipelineId <- EitherT.right[TxPipelineAdmissionFailure](
        idGenerator.nextPipelineId,
      )
      acceptedAt <- EitherT.right[TxPipelineAdmissionFailure](clock.now)
      record <- EitherT.fromEither[F]:
        TxPipelineRecord
          .accepted(
            pipelineId = pipelineId,
            normalized = normalized,
            txHashes = txHashes,
            idempotencyKey = idempotencyKey,
            canonicalPayloadHash = canonicalHash,
            acceptedAt = acceptedAt,
          )
          .leftMap(TxPipelineAdmissionFailure.ValidationRejected(_))
      created <- createOrReplay(record, canonicalHash)
      _ <-
        if created.created then
          EitherT.right[TxPipelineAdmissionFailure]:
            workNotifier.notifyApplicationWorkAvailable
        else EitherT.rightT[F, TxPipelineAdmissionFailure](())
    yield TxPipelineAdmissionOutcome(
      snapshot = created.record.snapshot,
      replayed = !created.created,
    )

  private def createOrReplay(
      record: TxPipelineRecord,
      canonicalHash: TxPipelineCanonicalPayloadHash,
  ): EitherT[F, TxPipelineAdmissionFailure, TxPipelineCreateOutcome] =
    EitherT:
      createPermit.use: _ =>
        val result = for
          replay <- existingReplay(record.idempotencyKey, canonicalHash)
          outcome <- replay match
            case Some(existing) =>
              EitherT.rightT[F, TxPipelineAdmissionFailure](
                TxPipelineCreateOutcome(record = existing, created = false),
              )
            case None =>
              createNewRecord(record, canonicalHash)
        yield outcome
        result.value

  private def createNewRecord(
      record: TxPipelineRecord,
      canonicalHash: TxPipelineCanonicalPayloadHash,
  ): EitherT[F, TxPipelineAdmissionFailure, TxPipelineCreateOutcome] =
    for
      _       <- enforceAcceptedNonterminalLimit
      outcome <- createRecordOrReplay(record, canonicalHash)
    yield outcome

  private def createRecordOrReplay(
      record: TxPipelineRecord,
      canonicalHash: TxPipelineCanonicalPayloadHash,
  ): EitherT[F, TxPipelineAdmissionFailure, TxPipelineCreateOutcome] =
    EitherT:
      store.create(record).value.flatMap:
        case Right(created) =>
          Sync[F].pure:
            Right[TxPipelineAdmissionFailure, TxPipelineCreateOutcome](
              TxPipelineCreateOutcome(record = created, created = true),
            )
        case Left(
              failure @ TxPipelineStoreFailure.IdempotencyKeyAlreadyExists(
                idempotencyKey,
                _,
              ),
            ) =>
          existingReplay(Some(idempotencyKey), canonicalHash).value.map:
            case Right(Some(existing)) =>
              Right[TxPipelineAdmissionFailure, TxPipelineCreateOutcome](
                TxPipelineCreateOutcome(record = existing, created = false),
              )
            case Right(None) =>
              Left[TxPipelineAdmissionFailure, TxPipelineCreateOutcome](
                TxPipelineAdmissionFailure.StoreRejected(failure),
              )
            case Left(rejected) =>
              Left[TxPipelineAdmissionFailure, TxPipelineCreateOutcome](
                rejected,
              )
        case Left(failure) =>
          Sync[F].pure:
            Left[TxPipelineAdmissionFailure, TxPipelineCreateOutcome](
              TxPipelineAdmissionFailure.StoreRejected(failure),
            )

  private def createPermit: Resource[F, Unit] =
    Resource.make(Sync[F].blocking(createGate.acquire()))(_ =>
      Sync[F].delay(createGate.release()),
    )

  private def hashTransactions(
      normalized: TxPipelineNormalizedRequest,
  ): EitherT[F, TxPipelineAdmissionFailure, Vector[Vector[TxPipelineTxHash]]] =
    EitherT.right:
      normalized.stages.traverse: stage =>
        stage.transactions.traverse(tx => hasher.hash(tx.payload))

  private def enforceAcceptedNonterminalLimit
      : EitherT[F, TxPipelineAdmissionFailure, Unit] =
    val limit =
      limits.maxAcceptedNonterminalPipelines
    if limit <= 0 then
      EitherT.leftT[F, Unit](acceptedNonterminalLimitFailure(live = 0))
    else
      acceptedNonterminalCount(offset = 0, live = 0).flatMap: live =>
        if live < limit then EitherT.rightT[F, TxPipelineAdmissionFailure](())
        else EitherT.leftT[F, Unit](acceptedNonterminalLimitFailure(live))

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def acceptedNonterminalCount(
      offset: Int,
      live: Int,
  ): EitherT[F, TxPipelineAdmissionFailure, Int] =
    store
      .list(offset = offset, limit = TxPipelineAdmissionService.CountPageSize)
      .leftMap(TxPipelineAdmissionFailure.StoreRejected(_))
      .flatMap: page =>
        val nextLive =
          live + page.count(isAcceptedNonterminal)
        if page.lengthIs < TxPipelineAdmissionService.CountPageSize ||
          nextLive >= limits.maxAcceptedNonterminalPipelines
        then EitherT.rightT[F, TxPipelineAdmissionFailure](nextLive)
        else
          acceptedNonterminalCount(
            offset = offset + TxPipelineAdmissionService.CountPageSize,
            live = nextLive,
          )

  private def acceptedNonterminalLimitFailure(
      live: Int,
  ): TxPipelineAdmissionFailure =
    TxPipelineAdmissionFailure.ValidationRejected(
      TxPipelineValidationFailure(
        reason = "acceptedNonterminalPipelineLimitExceeded",
        detail =
          Some(
            ss"accepted nonterminal pipeline limit exceeded: live=${live.toString},max=${limits.maxAcceptedNonterminalPipelines.toString}",
          ),
        stageIndex = None,
        transactionIndex = None,
      ),
    )

  private def isAcceptedNonterminal(
      record: TxPipelineRecord,
  ): Boolean =
    record.status match
      case TxPipelineStatus.Finalized | TxPipelineStatus.Failed |
          TxPipelineStatus.PartiallyFailed =>
        false
      case _ =>
        !record.stages.exists(stageTerminalUnavailable)

  private def stageTerminalUnavailable(
      stage: TxPipelineStageRecord,
  ): Boolean =
    stage.status match
      case TxPipelineStageStatus.Unavailable =>
        true
      case _ =>
        stage.transactions.exists(transactionTerminalUnavailable)

  private def transactionTerminalUnavailable(
      transaction: TxPipelineTransactionRecord,
  ): Boolean =
    transaction.pipelineState match
      case TxPipelineTransactionState.Unavailable => true
      case _                                      => false

enum TxPipelineAdmissionFailure:
  case ValidationRejected(failure: TxPipelineValidationFailure)
  case IdempotencyConflict(
      idempotencyKey: TxPipelineIdempotencyKey,
      existingPipelineId: TxPipelineId,
  )
  case StoreRejected(failure: TxPipelineStoreFailure)

private final case class TxPipelineCreateOutcome(
    record: TxPipelineRecord,
    created: Boolean,
)

object TxPipelineAdmissionService:
  private val CountPageSize: Int = 256

  def canonicalPayloadHash(
      normalized: TxPipelineNormalizedRequest,
  ): TxPipelineCanonicalPayloadHash =
    val digest = MessageDigest.getInstance("SHA-256")
    TxPipelineCanonicalPayloadHash(
      TxPipelineSha256.hex(
        digest.digest(
          normalized.canonicalPayload.value.getBytes(StandardCharsets.UTF_8),
        ),
      ),
    )

@SuppressWarnings(Array("org.wartremover.warts.Any"))
private object TxPipelineSha256:
  def hex(bytes: Array[Byte]): String =
    bytes.map(byte => f"${byte & 0xff}%02x").mkString
