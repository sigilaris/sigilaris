package org.sigilaris.node.jvm.transport.armeria.txpipeline

import scala.concurrent.duration.*

import cats.effect.Async
import cats.effect.syntax.all.*
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import sttp.model.StatusCode
import sttp.tapir.server.ServerEndpoint

import org.sigilaris.node.jvm.runtime.txpipeline.*
import org.sigilaris.node.txpipeline.*

/** Server-side Armeria/Tapir adapter for transaction pipeline endpoints. */
@SuppressWarnings(
  Array("org.wartremover.warts.Any", "org.wartremover.warts.Nothing"),
)
object TxPipelineArmeriaAdapter:
  val DefaultWaitTimeout: Option[FiniteDuration] =
    Some(30.seconds)
  val MaximumExactWaitTimeout: FiniteDuration = 5.minutes

  private val ExactInitialPollDelay: FiniteDuration = 25.millis
  private val ExactMaximumPollDelay: FiniteDuration = 1.second

  private[txpipeline] def effectiveExactWaitTimeout(
      configured: Option[FiniteDuration],
  ): FiniteDuration =
    configured.fold(MaximumExactWaitTimeout)(_.min(MaximumExactWaitTimeout))

  def endpoints[F[_]: Async](
      store: TxPipelineStore[F],
      admission: TxPipelineAdmissionService[F],
      waitCoordinator: TxPipelineWaitCoordinator[F],
  ): List[ServerEndpoint[Any, F]] =
    endpointsWithWaitTimeout(
      store = store,
      admission = admission,
      waitCoordinator = waitCoordinator,
      waitTimeout = DefaultWaitTimeout,
    )

  def endpointsWithWaitTimeout[F[_]: Async](
      store: TxPipelineStore[F],
      admission: TxPipelineAdmissionService[F],
      waitCoordinator: TxPipelineWaitCoordinator[F],
      waitTimeout: Option[FiniteDuration],
  ): List[ServerEndpoint[Any, F]] =
    List(
      submitEndpoint(store, admission, waitCoordinator, waitTimeout),
      queryEndpoint(store),
    )

  def endpointsWithExact[F[_]: Async](
      store: TxPipelineStore[F],
      admission: TxPipelineAdmissionService[F],
      waitCoordinator: TxPipelineWaitCoordinator[F],
      exact: ExactPipelineTransportService[F],
  ): List[ServerEndpoint[Any, F]] =
    endpointsWithExactWaitTimeout(
      store,
      admission,
      waitCoordinator,
      exact,
      DefaultWaitTimeout,
    )

  def endpointsWithExactWaitTimeout[F[_]: Async](
      store: TxPipelineStore[F],
      admission: TxPipelineAdmissionService[F],
      waitCoordinator: TxPipelineWaitCoordinator[F],
      exact: ExactPipelineTransportService[F],
      waitTimeout: Option[FiniteDuration],
  ): List[ServerEndpoint[Any, F]] =
    List(
      exactAwareSubmitEndpoint(
        store,
        admission,
        waitCoordinator,
        exact,
        waitTimeout,
      ),
      exactAwareQueryEndpoint(store, exact),
    )

  /** Builds the unauthenticated application diagnostics endpoint.
    *
    * This endpoint is intentionally excluded from [[endpointsWithExact]] and
    * [[endpointsWithExactWaitTimeout]]. Embedders must mount it only on an
    * authenticated operator plane.
    */
  def applicationStatusEndpoint[F[_]: Async](
      exact: ExactPipelineTransportService[F],
  ): ServerEndpoint[Any, F] =
    TxPipelineTapirEndpoints.applicationStatus.serverLogic: _ =>
      exact.diagnostics.map(value =>
        Right(StatusCode.Ok -> value.asJson.noSpaces),
      )

  private def exactAwareSubmitEndpoint[F[_]: Async](
      store: TxPipelineStore[F],
      admission: TxPipelineAdmissionService[F],
      waitCoordinator: TxPipelineWaitCoordinator[F],
      exact: ExactPipelineTransportService[F],
      waitTimeout: Option[FiniteDuration],
  ): ServerEndpoint[Any, F] =
    TxPipelineTapirEndpoints.submit.serverLogic: (idempotencyRaw, raw) =>
      parseEnvelopeInputs(idempotencyRaw, raw) match
        case Left(response) =>
          Async[F].pure(Left(StatusCode.BadRequest -> renderError(response)))
        case Right(
              (idempotencyKey, TxPipelineSubmitEnvelope.GenericV1(request)),
            ) =>
          admission
            .submitOutcome(request, idempotencyKey)
            .flatMap:
              case Left(failure) =>
                Async[F].pure(Left(renderAdmissionFailure(failure)))
              case Right(outcome) =>
                awaitRequestedBoundary(
                  store,
                  outcome.snapshot,
                  request.waitFor,
                  waitCoordinator,
                  waitTimeout,
                ).map:
                  case Left(failure) => Left(renderProjectionFailure(failure))
                  case Right(completed) =>
                    Right(
                      successStatus(
                        completed,
                        request.waitFor,
                        outcome.replayed,
                      ) -> renderSnapshotEnvelope(
                        TxPipelineSnapshotEnvelope.GenericV1(completed),
                      ),
                    )
        case Right(
              (idempotencyKey, TxPipelineSubmitEnvelope.ExactV2(request)),
            ) =>
          exact
            .submit(request, idempotencyKey)
            .flatMap:
              case Left(failure) =>
                Async[F].pure(Left(renderExactFailure(failure)))
              case Right(outcome) =>
                awaitExactBoundary(
                  exact,
                  outcome.snapshot,
                  request.waitFor,
                  waitTimeout,
                ).map:
                  case Left(failure)    => Left(renderExactFailure(failure))
                  case Right(completed) =>
                    Right(
                      exactSuccessStatus(
                        completed,
                        request.waitFor,
                        outcome.replayed,
                      ) -> renderSnapshotEnvelope(
                        TxPipelineSnapshotEnvelope.ExactV2(completed),
                      ),
                    )

  private def exactAwareQueryEndpoint[F[_]: Async](
      store: TxPipelineStore[F],
      exact: ExactPipelineTransportService[F],
  ): ServerEndpoint[Any, F] =
    TxPipelineTapirEndpoints.query.serverLogic: pipelineIdRaw =>
      TxPipelineId.parse(pipelineIdRaw) match
        case Left(error) =>
          Async[F].pure(
            Left(StatusCode.BadRequest -> renderError(invalidPipelineId(error))),
          )
        case Right(pipelineId) =>
          (store.get(pipelineId).value, exact.query(pipelineId)).mapN:
            case (Left(failure), _)         => Left(renderStoreFailure(failure))
            case (Right(Some(_)), Right(_)) =>
              Left(
                renderExactFailure(
                  ExactPipelineTransportFailure.ProjectionMismatch(
                    pipelineId,
                    "pipeline id is present in both generic and exact namespaces",
                  ),
                ),
              )
            case (
                  Right(Some(record)),
                  Left(
                    ExactPipelineTransportFailure.Missing(_),
                  ),
                ) =>
              Right(
                StatusCode.Ok -> renderSnapshotEnvelope(
                  TxPipelineSnapshotEnvelope.GenericV1(record.snapshot),
                ),
              )
            case (Right(Some(_)), Left(failure)) =>
              Left(renderExactFailure(failure))
            case (Right(None), Left(failure)) =>
              Left(renderExactFailure(failure))
            case (Right(None), Right(snapshot)) =>
              Right(
                StatusCode.Ok -> renderSnapshotEnvelope(
                  TxPipelineSnapshotEnvelope.ExactV2(snapshot),
                ),
              )

  private def submitEndpoint[F[_]: Async](
      store: TxPipelineStore[F],
      admission: TxPipelineAdmissionService[F],
      waitCoordinator: TxPipelineWaitCoordinator[F],
      waitTimeout: Option[FiniteDuration],
  ): ServerEndpoint[Any, F] =
    TxPipelineTapirEndpoints.submit.serverLogic: (idempotencyRaw, raw) =>
      parseSubmitInputs(idempotencyRaw, raw) match
        case Left(response) =>
          Async[F].pure(
            Left(StatusCode.BadRequest -> renderError(response)),
          )
        case Right((idempotencyKey, request)) =>
          admission
            .submitOutcome(request, idempotencyKey)
            .flatMap:
              case Left(failure) =>
                Async[F].pure(Left(renderAdmissionFailure(failure)))
              case Right(outcome) =>
                awaitRequestedBoundary(
                  store = store,
                  snapshot = outcome.snapshot,
                  waitFor = request.waitFor,
                  waitCoordinator = waitCoordinator,
                  waitTimeout = waitTimeout,
                ).map:
                  case Left(failure) =>
                    Left(renderProjectionFailure(failure))
                  case Right(completed) =>
                    Right(
                      successStatus(
                        completed,
                        request.waitFor,
                        outcome.replayed,
                      ) ->
                        renderSnapshot(completed),
                    )

  private def queryEndpoint[F[_]: Async](
      store: TxPipelineStore[F],
  ): ServerEndpoint[Any, F] =
    TxPipelineTapirEndpoints.query.serverLogic: pipelineIdRaw =>
      TxPipelineId.parse(pipelineIdRaw) match
        case Left(error) =>
          Async[F].pure:
            Left(
              StatusCode.BadRequest ->
                renderError(
                  TxPipelineErrorResponse.validation(
                    TxPipelineValidationFailure(
                      reason = "invalidPipelineId",
                      detail = Some(error),
                      stageIndex = None,
                      transactionIndex = None,
                    ),
                  ),
                ),
            )
        case Right(pipelineId) =>
          store
            .get(pipelineId)
            .value
            .map:
              case Left(failure) =>
                Left(renderStoreFailure(failure))
              case Right(None) =>
                Left(
                  StatusCode.NotFound ->
                    renderError(TxPipelineErrorResponse.notFound(pipelineId)),
                )
              case Right(Some(record)) =>
                Right(StatusCode.Ok -> renderSnapshot(record.snapshot))

  private def parseSubmitInputs(
      idempotencyRaw: Option[String],
      raw: String,
  ): Either[
    TxPipelineErrorResponse,
    (
        Option[TxPipelineIdempotencyKey],
        TxPipelineSubmitRequest,
    ),
  ] =
    for
      idempotencyKey <- parseIdempotencyKey(idempotencyRaw)
      request        <- decode[TxPipelineSubmitRequest](raw)
        .leftMap(error =>
          TxPipelineErrorResponse.validation(
            TxPipelineValidationFailure(
              reason = "invalidSubmitRequest",
              detail = Some(error.getMessage),
              stageIndex = None,
              transactionIndex = None,
            ),
          ),
        )
    yield idempotencyKey -> request

  private def parseEnvelopeInputs(
      idempotencyRaw: Option[String],
      raw: String,
  ): Either[
    TxPipelineErrorResponse,
    (Option[TxPipelineIdempotencyKey], TxPipelineSubmitEnvelope),
  ] =
    for
      idempotencyKey <- parseIdempotencyKey(idempotencyRaw)
      request        <- decode[TxPipelineSubmitEnvelope](raw).leftMap(error =>
        TxPipelineErrorResponse.validation(
          TxPipelineValidationFailure(
            reason = "invalidSubmitRequest",
            detail = Some(error.getMessage),
            stageIndex = None,
            transactionIndex = None,
          ),
        ),
      )
    yield idempotencyKey -> request

  private def parseIdempotencyKey(
      raw: Option[String],
  ): Either[TxPipelineErrorResponse, Option[TxPipelineIdempotencyKey]] =
    raw.traverse: value =>
      TxPipelineIdempotencyKey
        .parse(value)
        .leftMap(error =>
          TxPipelineErrorResponse.validation(
            TxPipelineValidationFailure(
              reason = "invalidIdempotencyKey",
              detail = Some(error),
              stageIndex = None,
              transactionIndex = None,
            ),
          ),
        )

  private def awaitRequestedBoundary[F[_]: Async](
      store: TxPipelineStore[F],
      snapshot: TxPipelineSnapshot,
      waitFor: TxPipelineWaitMode,
      waitCoordinator: TxPipelineWaitCoordinator[F],
      waitTimeout: Option[FiniteDuration],
  ): F[Either[TxPipelineProjectionFailure, TxPipelineSnapshot]] =
    waitFor match
      case TxPipelineWaitMode.Accepted =>
        Async[F].pure(Right(snapshot))
      case mode =>
        val wait =
          waitCoordinator.waitFor(snapshot.pipelineId, mode)
        waitTimeout match
          case None =>
            wait
          case Some(timeout) =>
            wait.timeoutTo(timeout, loadSnapshot(store, snapshot.pipelineId))

  private def loadSnapshot[F[_]: Async](
      store: TxPipelineStore[F],
      pipelineId: TxPipelineId,
  ): F[Either[TxPipelineProjectionFailure, TxPipelineSnapshot]] =
    store
      .get(pipelineId)
      .value
      .map:
        case Left(failure) =>
          Left(TxPipelineProjectionFailure.StoreRejected(failure))
        case Right(Some(record)) =>
          Right(record.snapshot)
        case Right(None) =>
          Left(TxPipelineProjectionFailure.PipelineMissing(pipelineId))

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def awaitExactBoundary[F[_]: Async](
      exact: ExactPipelineTransportService[F],
      snapshot: ExactTxPipelineSnapshot,
      waitFor: TxPipelineWaitMode,
      waitTimeout: Option[FiniteDuration],
  ): F[Either[ExactPipelineTransportFailure, ExactTxPipelineSnapshot]] =
    if exactBoundaryReached(snapshot, waitFor) then
      Async[F].pure(Right(snapshot))
    else
      awaitExactBoundaryPoll(
        exact,
        snapshot.genericProjection.pipelineId,
        waitFor,
        ExactInitialPollDelay,
      ).timeoutTo(
        effectiveExactWaitTimeout(waitTimeout),
        exact.query(snapshot.genericProjection.pipelineId),
      )

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def awaitExactBoundaryPoll[F[_]: Async](
      exact: ExactPipelineTransportService[F],
      pipelineId: TxPipelineId,
      waitFor: TxPipelineWaitMode,
      pollDelay: FiniteDuration,
  ): F[Either[ExactPipelineTransportFailure, ExactTxPipelineSnapshot]] =
    Async[F].sleep(pollDelay) >>
      exact
        .query(pipelineId)
        .flatMap:
          case right @ Right(current)
              if exactBoundaryReached(current, waitFor) =>
            Async[F].pure(right)
          case Right(_) =>
            val nextDelay = (pollDelay * 2).min(ExactMaximumPollDelay)
            awaitExactBoundaryPoll(exact, pipelineId, waitFor, nextDelay)
          case left @ Left(_) => Async[F].pure(left)

  private def exactBoundaryReached(
      snapshot: ExactTxPipelineSnapshot,
      waitFor: TxPipelineWaitMode,
  ): Boolean =
    waitFor match
      case TxPipelineWaitMode.Accepted  => true
      case TxPipelineWaitMode.Certified =>
        snapshot.lifecycle match
          case ExactPipelineLifecycle.EffectCertified |
              ExactPipelineLifecycle.Included |
              ExactPipelineLifecycle.Finalized |
              ExactPipelineLifecycle.Materialized |
              ExactPipelineLifecycle.ExpiredUnapplied |
              ExactPipelineLifecycle.Failed =>
            true
          case _ => false
      case TxPipelineWaitMode.Finalized =>
        snapshot.lifecycle match
          case ExactPipelineLifecycle.Finalized |
              ExactPipelineLifecycle.Materialized |
              ExactPipelineLifecycle.ExpiredUnapplied |
              ExactPipelineLifecycle.Failed =>
            true
          case _ => false

  private def successStatus(
      snapshot: TxPipelineSnapshot,
      waitFor: TxPipelineWaitMode,
      replay: Boolean,
  ): StatusCode =
    waitFor match
      case TxPipelineWaitMode.Accepted if replay =>
        StatusCode.Ok
      case TxPipelineWaitMode.Accepted =>
        StatusCode.Accepted
      case TxPipelineWaitMode.Certified
          if boundaryReachedOrTerminal(snapshot, certified = true) =>
        StatusCode.Ok
      case TxPipelineWaitMode.Finalized
          if boundaryReachedOrTerminal(snapshot, certified = false) =>
        StatusCode.Ok
      case _ =>
        StatusCode.Accepted

  private def exactSuccessStatus(
      snapshot: ExactTxPipelineSnapshot,
      waitFor: TxPipelineWaitMode,
      replay: Boolean,
  ): StatusCode =
    waitFor match
      case TxPipelineWaitMode.Accepted if replay        => StatusCode.Ok
      case TxPipelineWaitMode.Accepted                  => StatusCode.Accepted
      case _ if exactBoundaryReached(snapshot, waitFor) => StatusCode.Ok
      case _                                            => StatusCode.Accepted

  private def boundaryReachedOrTerminal(
      snapshot: TxPipelineSnapshot,
      certified: Boolean,
  ): Boolean =
    snapshot.status match
      case TxPipelineStatus.Finalized | TxPipelineStatus.Failed |
          TxPipelineStatus.PartiallyFailed =>
        true
      case TxPipelineStatus.Certified if certified =>
        true
      case _ =>
        snapshot.stages.exists(stageUnavailable)

  private def stageUnavailable(
      stage: TxPipelineStageSnapshot,
  ): Boolean =
    stage.status match
      case TxPipelineStageStatus.Unavailable =>
        true
      case _ =>
        stage.transactions.exists(transactionUnavailable)

  private def transactionUnavailable(
      transaction: TxPipelineTransactionSnapshot,
  ): Boolean =
    transaction.pipelineState match
      case TxPipelineTransactionState.Unavailable => true
      case _                                      => false

  private def renderAdmissionFailure(
      failure: TxPipelineAdmissionFailure,
  ): (StatusCode, String) =
    failure match
      case TxPipelineAdmissionFailure.ValidationRejected(validation) =>
        StatusCode.BadRequest -> renderError(
          TxPipelineErrorResponse.validation(validation),
        )
      case TxPipelineAdmissionFailure.IdempotencyConflict(
            idempotencyKey,
            existingPipelineId,
          ) =>
        StatusCode.Conflict ->
          renderError(
            TxPipelineErrorResponse.idempotencyConflict(
              idempotencyKey,
              existingPipelineId,
            ),
          )
      case TxPipelineAdmissionFailure.StoreRejected(storeFailure) =>
        renderStoreFailure(storeFailure)

  private def renderProjectionFailure(
      failure: TxPipelineProjectionFailure,
  ): (StatusCode, String) =
    failure match
      case TxPipelineProjectionFailure.PipelineMissing(pipelineId) =>
        StatusCode.NotFound ->
          renderError(TxPipelineErrorResponse.notFound(pipelineId))
      case TxPipelineProjectionFailure.StoreRejected(storeFailure) =>
        renderStoreFailure(storeFailure)

  private def renderStoreFailure(
      failure: TxPipelineStoreFailure,
  ): (StatusCode, String) =
    StatusCode.InternalServerError ->
      renderError(
        TxPipelineErrorResponse.storeRejected(storeFailureDetail(failure)),
      )

  private def renderExactFailure(
      failure: ExactPipelineTransportFailure,
  ): (StatusCode, String) =
    failure match
      case ExactPipelineTransportFailure.Admission(
            ExactPipelineAdmissionFailure.ValidationRejected(validation),
          ) =>
        StatusCode.BadRequest -> renderError(
          TxPipelineErrorResponse.validation(validation),
        )
      case ExactPipelineTransportFailure.Admission(
            ExactPipelineAdmissionFailure.StoreRejected(storeFailure),
          ) =>
        renderExactStoreFailure(storeFailure)
      case ExactPipelineTransportFailure.Store(storeFailure) =>
        renderExactStoreFailure(storeFailure)
      case ExactPipelineTransportFailure.Safety(safetyFailure) =>
        val response = TxPipelineErrorResponse(
          safetyFailure.reason,
          Some(safetyFailure.detail),
          None,
          None,
          None,
        )
        val status =
          if ExactPipelineTransportValidationReasons.contains(
              safetyFailure.reason,
            )
          then StatusCode.BadRequest
          else StatusCode.InternalServerError
        status -> renderError(response)
      case ExactPipelineTransportFailure.Missing(pipelineId) =>
        StatusCode.NotFound -> renderError(
          TxPipelineErrorResponse.notFound(pipelineId),
        )
      case ExactPipelineTransportFailure.ProjectionMismatch(
            pipelineId,
            detail,
          ) =>
        StatusCode.InternalServerError -> renderError(
          TxPipelineErrorResponse(
            "applicationJournalIncomplete",
            Some(detail),
            Some(pipelineId),
            None,
            None,
          ),
        )

  private def renderExactStoreFailure(
      failure: ExactTxPipelineStoreFailure,
  ): (StatusCode, String) =
    val (status, reason, pipelineId, idempotencyKey, detail) = failure match
      case ExactTxPipelineStoreFailure.IdempotencyConflict(key, existing) =>
        (
          StatusCode.Conflict,
          "idempotencyConflict",
          Some(existing),
          Some(key),
          "idempotency key is already bound to another exact pipeline",
        )
      case ExactTxPipelineStoreFailure.PipelineConflict(pipelineId) =>
        (
          StatusCode.Conflict,
          failure.reason,
          Some(pipelineId),
          None,
          "exact pipeline identity is already bound differently",
        )
      case ExactTxPipelineStoreFailure.ApplicationPipelineConflict(_) |
          ExactTxPipelineStoreFailure.ExecutionConflict(_) |
          ExactTxPipelineStoreFailure.ReferenceConflict(_) =>
        (StatusCode.Conflict, failure.reason, None, None, failure.reason)
      case ExactTxPipelineStoreFailure.DecodeFailed(value) =>
        (
          StatusCode.InternalServerError,
          failure.reason,
          None,
          None,
          value,
        )
    status -> renderError(
      TxPipelineErrorResponse(
        reason,
        Some(detail),
        pipelineId,
        idempotencyKey,
        None,
      ),
    )

  private def storeFailureDetail(
      failure: TxPipelineStoreFailure,
  ): String =
    failure match
      case TxPipelineStoreFailure.PipelineMissing(pipelineId) =>
        s"pipeline missing: ${pipelineId.value}"
      case TxPipelineStoreFailure.PipelineAlreadyExists(pipelineId) =>
        s"pipeline already exists: ${pipelineId.value}"
      case TxPipelineStoreFailure.IdempotencyKeyAlreadyExists(
            idempotencyKey,
            pipelineId,
          ) =>
        s"idempotency key ${idempotencyKey.value} already exists for ${pipelineId.value}"
      case TxPipelineStoreFailure.DecodeFailed(detail) =>
        detail

  private def renderSnapshot(snapshot: TxPipelineSnapshot): String =
    snapshot.asJson.noSpaces

  private def renderSnapshotEnvelope(
      snapshot: TxPipelineSnapshotEnvelope,
  ): String = snapshot.asJson.noSpaces

  private def renderError(response: TxPipelineErrorResponse): String =
    response.asJson.noSpaces

  private def invalidPipelineId(error: String): TxPipelineErrorResponse =
    TxPipelineErrorResponse.validation(
      TxPipelineValidationFailure(
        reason = "invalidPipelineId",
        detail = Some(error),
        stageIndex = None,
        transactionIndex = None,
      ),
    )

  private val ExactPipelineTransportValidationReasons = Set(
    "applicationAdmissionClosed",
    "inactiveDependencyProfile",
    "exactPipelineBindingMismatch",
    "executionOrderMismatch",
    "deadlineExceeded",
    "deadlineMismatch",
    "producerResultUnavailable",
  )
