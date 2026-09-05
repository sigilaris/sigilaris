package org.sigilaris.node.jvm.runtime.txpipeline

import cats.Monad
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.InclusionHeight
import org.sigilaris.node.jvm.runtime.application.{
  ApplicationDrainPhase,
  ApplicationSafetyDiagnostics,
  ApplicationSafetyRuntime,
  ApplicationSafetyRuntimeFailure,
}
import org.sigilaris.node.txpipeline.*

trait ApplicationBaseHeightProvider[F[_]]:
  def current: F[InclusionHeight]

object ApplicationBaseHeightProvider:
  def fixed[F[_]: Monad](
      value: InclusionHeight,
  ): ApplicationBaseHeightProvider[F] =
    new ApplicationBaseHeightProvider[F]:
      override def current: F[InclusionHeight] = value.pure[F]

final case class ExactPipelineTransportOutcome(
    snapshot: ExactTxPipelineSnapshot,
    replayed: Boolean,
)

enum ExactPipelineTransportFailure:
  case Admission(failure: ExactPipelineAdmissionFailure)
  case Store(failure: ExactTxPipelineStoreFailure)
  case Safety(failure: ApplicationSafetyRuntimeFailure)
  case Missing(pipelineId: TxPipelineId)
  case ProjectionMismatch(pipelineId: TxPipelineId, detail: String)

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
  ),
)
final class ExactPipelineTransportService[F[_]: Monad](
    store: ExactTxPipelineStore[F],
    admission: ExactPipelineAdmissionService[F],
    safety: ApplicationSafetyRuntime[F],
    baseHeight: ApplicationBaseHeightProvider[F],
):
  def submit(
      request: ExactPipelineSubmitRequest,
      idempotencyKey: Option[TxPipelineIdempotencyKey],
  ): F[Either[ExactPipelineTransportFailure, ExactPipelineTransportOutcome]] =
    admission
      .replay(request, idempotencyKey)
      .flatMap:
        case Left(failure) =>
          ExactPipelineTransportFailure.Admission(failure).asLeft.pure[F]
        case Right(Some(outcome)) =>
          journal(outcome)
        case Right(None) =>
          safety.snapshot.flatMap: applicationSnapshot =>
            if applicationSnapshot.drain.phase != ApplicationDrainPhase.Open
            then
              ExactPipelineTransportFailure
                .Safety(
                  ApplicationSafetyRuntimeFailure(
                    "applicationAdmissionClosed",
                    "application admission is not open",
                  ),
                )
                .asLeft
                .pure[F]
            else
              baseHeight.current
                .flatMap(height =>
                  admission.submit(request, idempotencyKey, height),
                )
                .flatMap:
                  case Left(failure) =>
                    ExactPipelineTransportFailure
                      .Admission(failure)
                      .asLeft
                      .pure[F]
                  case Right(outcome) => journal(outcome)

  private def journal(
      outcome: ExactPipelineAdmissionOutcome,
  ): F[Either[ExactPipelineTransportFailure, ExactPipelineTransportOutcome]] =
    val pipelineId = outcome.snapshot.genericProjection.pipelineId
    store
      .get(pipelineId)
      .value
      .flatMap:
        case Left(failure) =>
          ExactPipelineTransportFailure.Store(failure).asLeft.pure[F]
        case Right(None) =>
          ExactPipelineTransportFailure
            .ProjectionMismatch(
              pipelineId,
              "admission succeeded without a durable exact descriptor",
            )
            .asLeft
            .pure[F]
        case Right(Some(record)) =>
          safety
            .journalAdmittedExactPipeline(record)
            .value
            .map:
              case Left(failure) =>
                Left(ExactPipelineTransportFailure.Safety(failure))
              case Right(journaled) =>
                Right(
                  ExactPipelineTransportOutcome(
                    ExactTxPipelineSnapshot.fromRecord(journaled),
                    outcome.replayed,
                  ),
                )

  def query(
      pipelineId: TxPipelineId,
  ): F[Either[ExactPipelineTransportFailure, ExactTxPipelineSnapshot]] =
    (store.get(pipelineId).value, safety.snapshot).mapN:
      case (Left(failure), _) =>
        Left(ExactPipelineTransportFailure.Store(failure))
      case (Right(stored), snapshot) =>
        (stored, snapshot.exactPipelines.get(pipelineId)) match
          case (None, None) =>
            Left(ExactPipelineTransportFailure.Missing(pipelineId))
          case (Some(admitted), Some(journaled))
              if ExactTxPipelineStoreValidation.sameSubmission(
                admitted,
                journaled,
              ) =>
            Right(ExactTxPipelineSnapshot.fromRecord(journaled))
          case (Some(_), None) =>
            Left(
              ExactPipelineTransportFailure.ProjectionMismatch(
                pipelineId,
                "exact admission row is absent from the application journal",
              ),
            )
          case (None, Some(_)) =>
            Left(
              ExactPipelineTransportFailure.ProjectionMismatch(
                pipelineId,
                "application journal row is absent from the exact admission store",
              ),
            )
          case (Some(_), Some(_)) =>
            Left(
              ExactPipelineTransportFailure.ProjectionMismatch(
                pipelineId,
                "exact admission and application journal descriptors disagree",
              ),
            )

  def diagnostics: F[ApplicationSafetyDiagnostics] = safety.diagnostics

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Nothing",
  ),
)
object ExactPipelineStartupGate:
  def reconcile[F[_]: Monad](
      manifest: ApplicationProtocolManifestV1,
      registry: ApplicationVerifierRegistry[F],
      store: ExactTxPipelineStore[F],
      safety: ApplicationSafetyRuntime[F],
  ): F[Either[ApplicationSafetyRuntimeFailure, Unit]] =
    registry.validateActivation(manifest) match
      case Left(failure) =>
        ApplicationSafetyRuntimeFailure(
          failure.reason,
          failure.detail.getOrElse("application verifier activation failed"),
        ).asLeft.pure[F]
      case Right(_) =>
        store
          .list(0, Int.MaxValue)
          .value
          .flatMap:
            case Left(failure) =>
              ApplicationSafetyRuntimeFailure(
                failure.reason,
                "exact admission store could not be reconciled",
              ).asLeft.pure[F]
            case Right(records) =>
              safety.snapshot.flatMap: snapshot =>
                val admitted = records
                  .map(record => record.genericProjection.pipelineId -> record)
                  .toMap
                val mismatch =
                  if snapshot.drain.activeManifest.configurationDigest =!=
                      manifest.configurationDigest
                  then
                    Some(
                      ApplicationSafetyRuntimeFailure(
                        "manifestMismatch",
                        "startup manifest differs from the application journal manifest",
                      ),
                    )
                  else
                    snapshot.exactPipelines.collectFirst:
                      case (pipelineId, journaled)
                          if !admitted
                            .get(pipelineId)
                            .exists(stored =>
                              ExactTxPipelineStoreValidation.sameSubmission(
                                stored,
                                journaled,
                              ),
                            ) =>
                        ApplicationSafetyRuntimeFailure(
                          "applicationJournalIncomplete",
                          s"journaled exact pipeline ${pipelineId.value} is missing or changed in the admission store",
                        )
                mismatch match
                  case Some(failure) => failure.asLeft.pure[F]
                  case None          =>
                    records.foldLeft(
                      ().asRight[ApplicationSafetyRuntimeFailure].pure[F],
                    ): (accumulated, record) =>
                      accumulated.flatMap:
                        case left @ Left(_) => left.pure[F]
                        case Right(_)       =>
                          safety
                            .journalAdmittedExactPipeline(record)
                            .value
                            .map(_.void)
