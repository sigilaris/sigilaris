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
                  replay = outcome.replayed,
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
      request <- decode[TxPipelineSubmitRequest](raw)
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
      replay: Boolean,
      waitCoordinator: TxPipelineWaitCoordinator[F],
      waitTimeout: Option[FiniteDuration],
  ): F[Either[TxPipelineProjectionFailure, TxPipelineSnapshot]] =
    if replay then Async[F].pure(Right(snapshot))
    else
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

  private def successStatus(
      snapshot: TxPipelineSnapshot,
      waitFor: TxPipelineWaitMode,
      replay: Boolean,
  ): StatusCode =
    if replay then StatusCode.Ok
    else
      waitFor match
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

  private def storeFailureDetail(
      failure: TxPipelineStoreFailure,
  ): String =
    failure match
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

  private def renderError(response: TxPipelineErrorResponse): String =
    response.asJson.noSpaces
