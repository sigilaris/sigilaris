package org.sigilaris.node.jvm.runtime.txpipeline

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

import cats.data.EitherT
import cats.effect.kernel.{Concurrent, Sync}
import cats.effect.std.Semaphore
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.InclusionHeight
import org.sigilaris.node.jvm.runtime.application.{
  ApplicationDrainPhase,
  ApplicationSafetyRuntime,
}
import org.sigilaris.node.txpipeline.*

trait ApplicationProtocolManifestProvider[F[_]]:
  def active: F[ApplicationProtocolManifestV1]

object ApplicationProtocolManifestProvider:
  def fixed[F[_]: Sync](
      manifest: ApplicationProtocolManifestV1,
  ): ApplicationProtocolManifestProvider[F] =
    new ApplicationProtocolManifestProvider[F]:
      override def active: F[ApplicationProtocolManifestV1] =
        Sync[F].pure(manifest)

final case class ExactPipelineAdmissionOutcome(
    snapshot: ExactTxPipelineSnapshot,
    replayed: Boolean,
)

enum ExactPipelineAdmissionFailure:
  case ValidationRejected(failure: TxPipelineValidationFailure)
  case StoreRejected(failure: ExactTxPipelineStoreFailure)

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
  ),
)
final class ExactPipelineAdmissionService[F[_]: Concurrent] private (
    store: ExactTxPipelineStore[F],
    safety: ApplicationSafetyRuntime[F],
    registry: ApplicationVerifierRegistry[F],
    manifestProvider: ApplicationProtocolManifestProvider[F],
    clock: TxPipelineAdmissionClock[F],
    hasher: TxPipelineTransactionHasher[F],
    workNotifier: TxPipelineWorkNotifier[F],
    limits: TxPipelineShapeLimits,
    createGate: Semaphore[F],
):
  def submit(
      request: ExactPipelineSubmitRequest,
      idempotencyKey: Option[TxPipelineIdempotencyKey],
      baseHeight: InclusionHeight,
  ): F[Either[ExactPipelineAdmissionFailure, ExactPipelineAdmissionOutcome]] =
    val result = for
      normalized <- EitherT.fromEither[F](normalize(request))
      historical <- historicalReplay(normalized, idempotencyKey)
      outcome    <- historical match
        case Some(record) =>
          EitherT.rightT[F, ExactPipelineAdmissionFailure](
            ExactPipelineAdmissionOutcome(
              ExactTxPipelineSnapshot.fromRecord(record),
              replayed = true,
            ),
          )
        case None => admitNew(normalized, idempotencyKey, baseHeight)
    yield outcome
    result.value

  def replay(
      request: ExactPipelineSubmitRequest,
      idempotencyKey: Option[TxPipelineIdempotencyKey],
  ): F[
    Either[
      ExactPipelineAdmissionFailure,
      Option[ExactPipelineAdmissionOutcome],
    ],
  ] =
    (for
      normalized <- EitherT.fromEither[F](normalize(request))
      historical <- historicalReplay(normalized, idempotencyKey)
    yield historical.map(record =>
      ExactPipelineAdmissionOutcome(
        ExactTxPipelineSnapshot.fromRecord(record),
        replayed = true,
      ),
    )).value

  private def admitNew(
      normalized: ExactPipelineNormalizedRequest,
      idempotencyKey: Option[TxPipelineIdempotencyKey],
      baseHeight: InclusionHeight,
  ): EitherT[
    F,
    ExactPipelineAdmissionFailure,
    ExactPipelineAdmissionOutcome,
  ] =
    for
      manifest <- EitherT.right[ExactPipelineAdmissionFailure](
        manifestProvider.active,
      )
      verified <- registry
        .verify(manifest, normalized, baseHeight)
        .leftMap(ExactPipelineAdmissionFailure.ValidationRejected.apply)
      identity = ExactPipelineAdmissionIdentity.from(normalized, verified)
      hashes <- EitherT.right[ExactPipelineAdmissionFailure](
        normalized.transactions.traverse(value => hasher.hash(value.payload)),
      )
      acceptedAt <- EitherT.right[ExactPipelineAdmissionFailure](clock.now)
      record     <- EitherT.fromEither[F](
        createRecord(
          normalized,
          verified,
          identity,
          hashes,
          idempotencyKey,
          acceptedAt,
        ),
      )
      stored <- createWithLimit(record)
      _      <-
        if stored.created then
          EitherT.right[ExactPipelineAdmissionFailure](
            workNotifier.notifyApplicationWorkAvailable,
          )
        else EitherT.rightT[F, ExactPipelineAdmissionFailure](())
    yield ExactPipelineAdmissionOutcome(
      ExactTxPipelineSnapshot.fromRecord(stored.record),
      replayed = !stored.created,
    )

  private def createWithLimit(
      record: ExactTxPipelineRecord,
  ): EitherT[
    F,
    ExactPipelineAdmissionFailure,
    ExactTxPipelineCreateOutcome,
  ] =
    EitherT:
      createGate.permit.use: _ =>
        store
          .get(record.genericProjection.pipelineId)
          .leftMap(ExactPipelineAdmissionFailure.StoreRejected.apply)
          .flatMap:
            case Some(_) => createOrReplay(record)
            case None    =>
              enforceAcceptedNonterminalLimit >> createOrReplay(record)
          .value

  private def createOrReplay(
      record: ExactTxPipelineRecord,
  ): EitherT[
    F,
    ExactPipelineAdmissionFailure,
    ExactTxPipelineCreateOutcome,
  ] =
    store
      .createOrReplay(record)
      .leftMap(ExactPipelineAdmissionFailure.StoreRejected.apply)

  private def enforceAcceptedNonterminalLimit
      : EitherT[F, ExactPipelineAdmissionFailure, Unit] =
    val limit = limits.maxAcceptedNonterminalPipelines
    if limit <= 0 then EitherT.leftT(limitFailure(0))
    else
      EitherT
        .right[ExactPipelineAdmissionFailure](safety.snapshot)
        .flatMap: snapshot =>
          if snapshot.drain.phase != ApplicationDrainPhase.Open then
            EitherT.leftT(
              ExactPipelineAdmissionFailure.ValidationRejected(
                TxPipelineValidationFailure(
                  "applicationAdmissionClosed",
                  "application admission is not open",
                ),
              ),
            )
          else
            val journaledIds  = snapshot.exactPipelines.keySet
            val journaledLive =
              snapshot.exactPipelines.valuesIterator.count(record =>
                !ExactPipelineLifecycle.isTerminal(record.lifecycle),
              )
            if journaledLive >= limit then
              EitherT.leftT(limitFailure(journaledLive))
            else
              store
                .countUnjournaled(journaledIds, limit - journaledLive)
                .leftMap(ExactPipelineAdmissionFailure.StoreRejected.apply)
                .flatMap: unjournaled =>
                  val live = journaledLive + unjournaled
                  if live < limit then EitherT.rightT(())
                  else EitherT.leftT(limitFailure(live))

  private def limitFailure(live: Int): ExactPipelineAdmissionFailure =
    ExactPipelineAdmissionFailure.ValidationRejected(
      TxPipelineValidationFailure(
        "acceptedNonterminalPipelineLimitExceeded",
        s"accepted nonterminal exact pipeline limit exceeded: live=$live,max=${limits.maxAcceptedNonterminalPipelines}",
      ),
    )

  private def historicalReplay(
      request: ExactPipelineNormalizedRequest,
      idempotencyKey: Option[TxPipelineIdempotencyKey],
  ): EitherT[
    F,
    ExactPipelineAdmissionFailure,
    Option[ExactTxPipelineRecord],
  ] =
    idempotencyKey match
      case Some(key) =>
        store
          .getByIdempotencyKey(key)
          .leftMap(ExactPipelineAdmissionFailure.StoreRejected.apply)
          .flatMap:
            case Some(record) if matchesHistoricalRequest(record, request) =>
              EitherT.rightT(Some(record))
            case Some(record) =>
              EitherT.leftT(
                ExactPipelineAdmissionFailure.StoreRejected(
                  ExactTxPipelineStoreFailure.IdempotencyConflict(
                    key,
                    record.genericProjection.pipelineId,
                  ),
                ),
              )
            case None =>
              findHistoricalRequest(request)
                .flatMap:
                  case Some(record) =>
                    repairHistoricalAlias(record, key).map(Some(_))
                  case None => EitherT.rightT(None)
      case None => findHistoricalRequest(request)

  private def repairHistoricalAlias(
      record: ExactTxPipelineRecord,
      key: TxPipelineIdempotencyKey,
  ): EitherT[F, ExactPipelineAdmissionFailure, ExactTxPipelineRecord] =
    val candidate = record.copy(genericProjection =
      record.genericProjection.copy(idempotencyKey = Some(key)),
    )
    store
      .createOrReplay(candidate)
      .leftMap(ExactPipelineAdmissionFailure.StoreRejected.apply)
      .map(_.record)

  private def findHistoricalRequest(
      request: ExactPipelineNormalizedRequest,
  ): EitherT[
    F,
    ExactPipelineAdmissionFailure,
    Option[ExactTxPipelineRecord],
  ] =
    store
      .getByRequestIdentity(ExactPipelineRequestIdentity.fromRequest(request))
      .leftMap(ExactPipelineAdmissionFailure.StoreRejected.apply)
      .flatMap:
        case None => EitherT.rightT(None)
        case Some(record) if matchesHistoricalRequest(record, request) =>
          EitherT.rightT(Some(record))
        case Some(_) =>
          EitherT.leftT(
            ExactPipelineAdmissionFailure.StoreRejected(
              ExactTxPipelineStoreFailure.DecodeFailed(
                "exact request identity index does not match its primary record",
              ),
            ),
          )

  private def matchesHistoricalRequest(
      record: ExactTxPipelineRecord,
      request: ExactPipelineNormalizedRequest,
  ): Boolean =
    val payloads = record.genericProjection.stages
      .flatMap(_.transactions)
      .map(_.payload)
    record.verifiedPlan.profileId == request.profileId &&
    record.verifiedPlan.profileVersion == request.profileVersion &&
    record.verifiedPlan.signedPlanDigest ==
      ExactPipelineCanonical.signedPlanDigest(request.signedApplicationPlan) &&
      payloads == request.transactions.map(_.payload)

  private def normalize(
      request: ExactPipelineSubmitRequest,
  ): Either[ExactPipelineAdmissionFailure, ExactPipelineNormalizedRequest] =
    val generic = TxPipelineSubmitRequest(
      stages = Vector(request.transactions),
      waitFor = request.waitFor,
    )
    for
      _ <- Either.cond(
        request.transactions.sizeCompare(2) == 0,
        (),
        ExactPipelineAdmissionFailure.ValidationRejected(
          TxPipelineValidationFailure(
            "exactPipelineStageCountMismatch",
            "M1 exact pipelines require exactly two ordered transactions",
          ),
        ),
      )
      _ <- TxPipelineRequestNormalizer
        .normalize(generic, limits)
        .leftMap(ExactPipelineAdmissionFailure.ValidationRejected.apply)
      normalized <- ExactPipelineNormalizedRequest
        .fromSubmit(request)
        .leftMap(detail =>
          ExactPipelineAdmissionFailure.ValidationRejected(
            TxPipelineValidationFailure("invalidExactPipelineRequest", detail),
          ),
        )
    yield normalized

  private def createRecord(
      normalized: ExactPipelineNormalizedRequest,
      verified: VerifiedExactDependencyPlan,
      identity: ExactPipelineAdmissionIdentity,
      hashes: Vector[TxPipelineTxHash],
      idempotencyKey: Option[TxPipelineIdempotencyKey],
      acceptedAt: Instant,
  ): Either[ExactPipelineAdmissionFailure, ExactTxPipelineRecord] =
    val genericNormalized = TxPipelineNormalizedRequest(
      stages = Vector(
        TxPipelineNormalizedStage(
          stageIndex = 0,
          transactions = normalized.transactions,
        ),
      ),
      waitFor = normalized.waitFor,
    )
    for
      generic <- TxPipelineRecord
        .accepted(
          pipelineId = identity.pipelineId,
          normalized = genericNormalized,
          txHashes = Vector(hashes),
          idempotencyKey = idempotencyKey,
          canonicalPayloadHash = identity.canonicalPayloadHash,
          acceptedAt = acceptedAt,
        )
        .leftMap(value =>
          ExactPipelineAdmissionFailure.ValidationRejected(value),
        )
      binding <- ExactPipelineIdentityBinding
        .create(
          nodePipelineId = identity.pipelineId,
          applicationPipelineId = verified.applicationPipelineId,
          identityStrategy = ExactPipelineAdmissionIdentity.Strategy,
          verifiedPlanDigest = ExactPipelineCanonical.verifiedPlanDigest(
            verified,
          ),
        )
        .leftMap(detail =>
          ExactPipelineAdmissionFailure.ValidationRejected(
            TxPipelineValidationFailure("exactPipelineBindingMismatch", detail),
          ),
        )
      record = ExactTxPipelineRecord(
        schemaVersion = ExactTxPipelineRecord.SchemaVersion,
        genericProjection = generic,
        verifiedPlan = verified,
        identityBinding = binding,
        lifecycle = ExactPipelineLifecycle.Accepted,
        terminalFailure = None,
      )
      validated <- ExactTxPipelineRecord
        .validate(record)
        .leftMap(detail =>
          ExactPipelineAdmissionFailure.ValidationRejected(
            TxPipelineValidationFailure("exactPipelineBindingMismatch", detail),
          ),
        )
    yield validated

private final case class ExactPipelineAdmissionIdentity(
    pipelineId: TxPipelineId,
    canonicalPayloadHash: TxPipelineCanonicalPayloadHash,
)

@SuppressWarnings(Array("org.wartremover.warts.Any"))
private object ExactPipelineAdmissionIdentity:
  val Strategy: String = "exact-v2-sha256"
  private val Domain   = "sigilaris.tx-pipeline.exact.node-identity.v2"

  def from(
      request: ExactPipelineNormalizedRequest,
      verified: VerifiedExactDependencyPlan,
  ): ExactPipelineAdmissionIdentity =
    val canonical = canonicalBytes(request, verified)
    val digest    = TxPipelineSha256.hex(
      MessageDigest.getInstance("SHA-256").digest(canonical),
    )
    ExactPipelineAdmissionIdentity(
      TxPipelineId(s"txp_$digest"),
      TxPipelineCanonicalPayloadHash(digest),
    )

  private def canonicalBytes(
      request: ExactPipelineNormalizedRequest,
      verified: VerifiedExactDependencyPlan,
  ): Array[Byte] =
    val fields = Vector(
      Domain,
      request.profileId.value,
      request.profileVersion.value.toString,
      request.signedApplicationPlan.value,
    ) ++ request.transactions.map(_.payload.value) ++ Vector(
      ExactPipelineCanonical.verifiedPlanDigest(verified),
    )
    fields
      .map: value =>
        val bytes = value.getBytes(StandardCharsets.UTF_8)
        s"${bytes.length}:$value"
      .mkString("|")
      .getBytes(StandardCharsets.UTF_8)

object ExactPipelineAdmissionService:
  def create[F[_]: Concurrent](
      store: ExactTxPipelineStore[F],
      safety: ApplicationSafetyRuntime[F],
      registry: ApplicationVerifierRegistry[F],
      manifestProvider: ApplicationProtocolManifestProvider[F],
      clock: TxPipelineAdmissionClock[F],
      hasher: TxPipelineTransactionHasher[F],
      workNotifier: TxPipelineWorkNotifier[F],
      limits: TxPipelineShapeLimits,
  ): F[ExactPipelineAdmissionService[F]] =
    Semaphore[F](1L).map: gate =>
      new ExactPipelineAdmissionService[F](
        store,
        safety,
        registry,
        manifestProvider,
        clock,
        hasher,
        workNotifier,
        limits,
        gate,
      )
