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
  def nextPipelineId(
      normalized: TxPipelineNormalizedRequest,
  ): F[TxPipelineId]

object TxPipelineIdGenerator:
  private trait DeterministicSha256Metadata:
    def identityScope: String

  def deterministicSha256[F[_]: Sync](
      identityScope: String,
  ): TxPipelineIdGenerator[F] =
    val generatorIdentityScope = identityScope
    new TxPipelineIdGenerator[F] with DeterministicSha256Metadata:
      override val identityScope: String = generatorIdentityScope

      override def nextPipelineId(
          normalized: TxPipelineNormalizedRequest,
      ): F[TxPipelineId] =
        Sync[F].delay:
          TxPipelineIdentityStrategy
            .v1Identity(normalized, identityScope)
            .pipelineId

  private[txpipeline] def nextPipelineIdForLegacy[F[_]: Sync](
      idGenerator: TxPipelineIdGenerator[F],
      normalized: TxPipelineNormalizedRequest,
      identityScope: String,
      canonicalPayloadHash: TxPipelineCanonicalPayloadHash,
  ): F[TxPipelineId] =
    idGenerator match
      case deterministic: DeterministicSha256Metadata
          if deterministic.identityScope === identityScope =>
        Sync[F].pure:
          TxPipelineIdentityStrategy.v1PipelineId(canonicalPayloadHash)
      case _ =>
        idGenerator.nextPipelineId(normalized)

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

@SuppressWarnings(Array("org.wartremover.warts.Overloading"))
final class TxPipelineAdmissionService[F[_]: Sync] private (
    store: TxPipelineStore[F],
    clock: TxPipelineAdmissionClock[F],
    hasher: TxPipelineTransactionHasher[F],
    applicationAdmission: TxPipelineApplicationAdmission[F],
    workNotifier: TxPipelineWorkNotifier[F],
    limits: TxPipelineShapeLimits,
    identityStrategy: TxPipelineIdentityStrategy[F],
):
  // Scala 3 carries the class context-bound evidence onto each secondary
  // constructor descriptor. Do not add another explicit `using Sync[F]` here:
  // the emitted legacy descriptor already ends in exactly one Sync parameter,
  // as pinned by TxPipelineIdentityStrategyAdmissionSuite and verified against
  // the published 0.2.11 class.
  /** Backward-compatible constructor for the pre-strategy admission surface.
    *
    * The supplied id generator is adapted to the single-result identity path.
    * `deterministicSha256` consumes the already calculated v1 hash without
    * recalculating it. Existing custom generators remain source-compatible, but
    * the callback now runs once for every normalized submission, including
    * idempotent replays and requests rejected after normalization, because
    * identity is calculated before replay lookup and later admission checks.
    */
  def this(
      store: TxPipelineStore[F],
      idGenerator: TxPipelineIdGenerator[F],
      identityScope: String,
      clock: TxPipelineAdmissionClock[F],
      hasher: TxPipelineTransactionHasher[F],
      applicationAdmission: TxPipelineApplicationAdmission[F],
      workNotifier: TxPipelineWorkNotifier[F],
      limits: TxPipelineShapeLimits,
  ) =
    this(
      store,
      clock,
      hasher,
      applicationAdmission,
      workNotifier,
      limits,
      TxPipelineAdmissionService.legacyIdentityStrategy(
        identityScope,
        idGenerator,
      ),
    )

  /** Constructor for direct identity-strategy injection. */
  def this(
      store: TxPipelineStore[F],
      identityStrategy: TxPipelineIdentityStrategy[F],
      clock: TxPipelineAdmissionClock[F],
      hasher: TxPipelineTransactionHasher[F],
      applicationAdmission: TxPipelineApplicationAdmission[F],
      workNotifier: TxPipelineWorkNotifier[F],
      limits: TxPipelineShapeLimits,
  ) =
    this(
      store,
      clock,
      hasher,
      applicationAdmission,
      workNotifier,
      limits,
      identityStrategy,
    )

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
      identity <- EitherT.right[TxPipelineAdmissionFailure]:
        identityStrategy.identify(normalized)
      replay <- existingReplay(
        idempotencyKey,
        identity.canonicalPayloadHash,
      )
      outcome <- replay match
        case Some(existing) =>
          EitherT.rightT[F, TxPipelineAdmissionFailure](
            TxPipelineAdmissionOutcome(
              snapshot = existing.snapshot,
              replayed = true,
            ),
          )
        case None =>
          acceptNewOutcome(normalized, idempotencyKey, identity)
    yield outcome

    result.value

  private def existingReplay(
      idempotencyKey: Option[TxPipelineIdempotencyKey],
      canonicalHash: TxPipelineCanonicalPayloadHash,
  ): EitherT[F, TxPipelineAdmissionFailure, Option[TxPipelineRecord]] =
    idempotencyKey match
      case None      => EitherT.rightT(None)
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
      identity: TxPipelineIdentity,
  ): EitherT[F, TxPipelineAdmissionFailure, TxPipelineAdmissionOutcome] =
    for
      _ <- EitherT:
        applicationAdmission
          .validate(normalized)
          .map(_.leftMap(TxPipelineAdmissionFailure.ValidationRejected(_)))
      txHashes   <- hashTransactions(normalized)
      acceptedAt <- EitherT.right[TxPipelineAdmissionFailure](clock.now)
      record     <- EitherT.fromEither[F]:
        TxPipelineRecord
          .accepted(
            pipelineId = identity.pipelineId,
            normalized = normalized,
            txHashes = txHashes,
            idempotencyKey = idempotencyKey,
            canonicalPayloadHash = identity.canonicalPayloadHash,
            acceptedAt = acceptedAt,
          )
          .leftMap(TxPipelineAdmissionFailure.ValidationRejected(_))
      created <- createOrReplay(record, identity.canonicalPayloadHash)
      _       <-
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
          replay  <- existingReplay(record.idempotencyKey, canonicalHash)
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
      convergence <- convergeExistingPipelineIfPresent(
        pipelineId = record.pipelineId,
        canonicalHash = canonicalHash,
        idempotencyKey = record.idempotencyKey,
      )
      outcome <- convergence match
        case Some(existing) =>
          EitherT.rightT[F, TxPipelineAdmissionFailure](existing)
        case None =>
          for
            _       <- enforceAcceptedNonterminalLimit
            outcome <- createRecordOrReplay(record, canonicalHash)
          yield outcome
    yield outcome

  private def createRecordOrReplay(
      record: TxPipelineRecord,
      canonicalHash: TxPipelineCanonicalPayloadHash,
  ): EitherT[F, TxPipelineAdmissionFailure, TxPipelineCreateOutcome] =
    EitherT:
      store
        .create(record)
        .value
        .flatMap:
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
          case Left(TxPipelineStoreFailure.PipelineAlreadyExists(pipelineId)) =>
            convergeExistingPipeline(
              pipelineId = pipelineId,
              canonicalHash = canonicalHash,
              idempotencyKey = record.idempotencyKey,
            ).value
          case Left(failure) =>
            Sync[F].pure:
              Left[TxPipelineAdmissionFailure, TxPipelineCreateOutcome](
                TxPipelineAdmissionFailure.StoreRejected(failure),
              )

  private def convergeExistingPipelineIfPresent(
      pipelineId: TxPipelineId,
      canonicalHash: TxPipelineCanonicalPayloadHash,
      idempotencyKey: Option[TxPipelineIdempotencyKey],
  ): EitherT[F, TxPipelineAdmissionFailure, Option[TxPipelineCreateOutcome]] =
    store
      .get(pipelineId)
      .leftMap(TxPipelineAdmissionFailure.StoreRejected(_))
      .flatMap:
        case None =>
          EitherT.rightT[F, TxPipelineAdmissionFailure](None)
        case Some(existing) =>
          convergeExistingRecord(
            existing,
            canonicalHash,
            idempotencyKey,
          ).map(Some(_))

  private def convergeExistingPipeline(
      pipelineId: TxPipelineId,
      canonicalHash: TxPipelineCanonicalPayloadHash,
      idempotencyKey: Option[TxPipelineIdempotencyKey],
  ): EitherT[F, TxPipelineAdmissionFailure, TxPipelineCreateOutcome] =
    for
      existing <- store
        .get(pipelineId)
        .leftMap(TxPipelineAdmissionFailure.StoreRejected(_))
        .flatMap:
          case Some(record) =>
            EitherT.rightT[F, TxPipelineAdmissionFailure](record)
          case None =>
            EitherT.leftT[F, TxPipelineRecord](
              TxPipelineAdmissionFailure.StoreRejected(
                TxPipelineStoreFailure.PipelineMissing(pipelineId),
              ),
            )
      outcome <- convergeExistingRecord(existing, canonicalHash, idempotencyKey)
    yield outcome

  private def convergeExistingRecord(
      existing: TxPipelineRecord,
      canonicalHash: TxPipelineCanonicalPayloadHash,
      idempotencyKey: Option[TxPipelineIdempotencyKey],
  ): EitherT[F, TxPipelineAdmissionFailure, TxPipelineCreateOutcome] =
    for
      _ <-
        if existing.canonicalPayloadHash.value === canonicalHash.value then
          EitherT.rightT[F, TxPipelineAdmissionFailure](())
        else
          EitherT.leftT[F, Unit](
            TxPipelineAdmissionFailure.StoreRejected(
              TxPipelineStoreFailure.DecodeFailed(
                ss"pipeline id collision for ${existing.pipelineId.value}",
              ),
            ),
          )
      aliased <- bindAliasIfNeeded(existing, idempotencyKey, canonicalHash)
    yield TxPipelineCreateOutcome(record = aliased, created = false)

  private def bindAliasIfNeeded(
      record: TxPipelineRecord,
      idempotencyKey: Option[TxPipelineIdempotencyKey],
      canonicalHash: TxPipelineCanonicalPayloadHash,
  ): EitherT[F, TxPipelineAdmissionFailure, TxPipelineRecord] =
    idempotencyKey match
      case None      => EitherT.rightT[F, TxPipelineAdmissionFailure](record)
      case Some(key) =>
        store
          .addIdempotencyAlias(
            key,
            TxPipelineIdempotencyBinding(
              pipelineId = record.pipelineId,
              canonicalPayloadHash = canonicalHash,
            ),
          )
          .leftMap:
            case TxPipelineStoreFailure.IdempotencyKeyAlreadyExists(
                  idempotencyKey,
                  pipelineId,
                ) =>
              TxPipelineAdmissionFailure.IdempotencyConflict(
                idempotencyKey,
                pipelineId,
              )
            case failure =>
              TxPipelineAdmissionFailure.StoreRejected(failure)

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
        detail = Some(
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

  private def legacyIdentityStrategy[F[_]: Sync](
      identityScope: String,
      idGenerator: TxPipelineIdGenerator[F],
  ): TxPipelineIdentityStrategy[F] =
    TxPipelineIdentityStrategy: normalized =>
      Sync[F]
        .delay:
          TxPipelineIdentityStrategy.v1CanonicalPayloadHash(
            normalized,
            identityScope,
          )
        .flatMap: canonicalPayloadHash =>
          TxPipelineIdGenerator
            .nextPipelineIdForLegacy(
              idGenerator,
              normalized,
              identityScope,
              canonicalPayloadHash,
            )
            .map: pipelineId =>
              TxPipelineIdentity(canonicalPayloadHash, pipelineId)

  def canonicalPayloadHash(
      normalized: TxPipelineNormalizedRequest,
      identityScope: String,
  ): TxPipelineCanonicalPayloadHash =
    pipelineIdentityHash(normalized, identityScope)

  def pipelineIdentityHash(
      normalized: TxPipelineNormalizedRequest,
      identityScope: String,
  ): TxPipelineCanonicalPayloadHash =
    TxPipelineIdentityStrategy.v1CanonicalPayloadHash(
      normalized,
      identityScope,
    )
