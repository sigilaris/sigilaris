package org.sigilaris.node.jvm.runtime.txpipeline

import cats.Monad
import cats.data.EitherT
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.*
import org.sigilaris.core.datatype.UInt256
import org.sigilaris.node.jvm.runtime.application.{
  AppliedExecutionRecord,
  ApplicationSafetyRuntime,
  ApplicationSafetyRuntimeFailure,
  ExactCertifiedApplication,
}
import org.sigilaris.node.jvm.runtime.block.BlockId
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.HotStuffProposalInputBranchContext
import org.sigilaris.node.txpipeline.*

final case class ExactPipelineReducerResult[S](
    nextState: S,
    execution: VerifiedApplicationExecution,
)

final case class ExactOrderedAtomicResult[S](
    nextState: S,
    executionPlan: ExecutionPlan,
    applied: Vector[AppliedExecutionRecord],
)

private final case class ExactExecutionStep(
    executionId: ExecutionId,
    payload: TxPipelineTransactionPayload,
)

private final case class ExactAtomicAccumulator[S](
    state: S,
    executions: Vector[VerifiedApplicationExecution],
)

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
  ),
)
final class ExactPipelineExecutionRuntime[F[_]: Monad](
    exactStore: ExactTxPipelineStore[F],
    safety: ApplicationSafetyRuntime[F],
):
  def register(
      pipelineId: TxPipelineId,
  ): EitherT[F, ApplicationSafetyRuntimeFailure, ExactTxPipelineRecord] =
    for
      record <- exactStore
        .get(pipelineId)
        .leftMap(value => storeFailure(value.reason))
        .flatMap:
          case Some(value) =>
            EitherT.rightT[F, ApplicationSafetyRuntimeFailure](value)
          case None =>
            EitherT.leftT[F, ExactTxPipelineRecord](
              failure(
                "exactPipelineBindingMismatch",
                s"exact pipeline ${pipelineId.value} is missing",
              ),
            )
      registered <- safety.registerExactPipeline(record)
    yield registered

  def orderedAtomicPlan(
      pipelineId: TxPipelineId,
  ): EitherT[F, ApplicationSafetyRuntimeFailure, ExecutionPlan] =
    journaled(pipelineId).flatMap: record =>
      if record.verifiedPlan.mode != ExactExecutionMode.OrderedAtomic then
        EitherT.leftT(
          failure(
            "executionPlanMismatch",
            "exact pipeline is not orderedAtomic",
          ),
        )
      else
        val plan = ExecutionPlan(
          ExecutionPlanVersion.V1,
          Vector(ExecutionWave.Ordered(record.verifiedPlan.orderedExecutionIds)),
        )
        EitherT.fromEither[F](
          ExecutionPlan
            .validate(plan, record.verifiedPlan.orderedExecutionIds)
            .leftMap(value =>
              failure("executionPlanMismatch", value.productPrefix),
            )
            .as(plan),
        )

  def executeOrderedAtomic[S](
      pipelineId: TxPipelineId,
      initialState: S,
      certificates: Vector[CertifiedEffectCertificate],
      candidateHeight: InclusionHeight,
      blockId: UInt256,
  )(
      execute: (
          S,
          ExecutionId,
          TxPipelineTransactionPayload,
      ) => Either[ApplicationSafetyRuntimeFailure, ExactPipelineReducerResult[
        S,
      ]],
  ): EitherT[F, ApplicationSafetyRuntimeFailure, ExactOrderedAtomicResult[S]] =
    for
      pipeline <- journaled(pipelineId)
      _        <- EitherT.cond[F](
        pipeline.verifiedPlan.mode == ExactExecutionMode.OrderedAtomic,
        (),
        failure(
          "executionPlanMismatch",
          "exact pipeline is not orderedAtomic",
        ),
      )
      plan  <- orderedAtomicPlan(pipelineId)
      steps <- EitherT.fromEither[F](stepsFor(pipeline))
      executed = OrderedWaveExecutor.executeAtomically(
        ExactAtomicAccumulator(initialState, Vector.empty),
        steps,
      ): (accumulator, step) =>
        execute(accumulator.state, step.executionId, step.payload)
          .flatMap(result =>
            validateReducerExecution(pipeline, step, result.execution).as(
              ExactAtomicAccumulator(
                result.nextState,
                accumulator.executions :+ result.execution,
              ),
            ),
          )
      accumulated <- executed match
        case Right(value) =>
          EitherT.rightT[F, ApplicationSafetyRuntimeFailure](value.nextState)
        case Left(OrderedExecutionFailure.EmptyOrderedWave) =>
          EitherT.leftT[F, ExactAtomicAccumulator[S]](
            failure("executionPlanMismatch", "ordered wave is empty"),
          )
        case Left(OrderedExecutionFailure.ExecutionFailed(_, cause)) =>
          val executionFailure = failure("orderedWaveFailed", cause.detail)
          safety
            .failExactPipeline(pipelineId, executionFailure)
            .flatMap(_ =>
              EitherT.leftT[F, ExactAtomicAccumulator[S]](
                executionFailure,
              ),
            )
      _ <- EitherT.fromEither[F](
        validateCertificates(accumulated.executions, certificates),
      )
      applications = certificates.map(certificate =>
        ExactCertifiedApplication(certificate, candidateHeight, blockId),
      )
      applied <- safety.recordExactApplicationsAtomically(
        pipelineId,
        applications,
        Some(ExactPipelineLifecycle.Included),
      )
    yield ExactOrderedAtomicResult(accumulated.state, plan, applied)

  def recordCertifiedAncestorProducer(
      pipelineId: TxPipelineId,
      application: ExactCertifiedApplication,
  ): EitherT[F, ApplicationSafetyRuntimeFailure, AppliedExecutionRecord] =
    for
      pipeline <- journaled(pipelineId)
      _        <- validateCertifiedPlacement(
        pipeline,
        application.certificate.subject.executionId,
        producer = true,
      )
      records <- safety.recordExactApplicationsAtomically(
        pipelineId,
        Vector(application),
        None,
      )
      record <- records.headOption
        .toRight(
          failure("appliedExecutionConflict", "producer application is missing"),
        )
        .toEitherT[F]
    yield record

  def validateCertifiedAncestorConsumerVote(
      pipelineId: TxPipelineId,
      subject: CertifiedEffectVoteSubject,
      candidateHeight: InclusionHeight,
      branchContext: HotStuffProposalInputBranchContext,
  ): EitherT[F, ApplicationSafetyRuntimeFailure, Unit] =
    for
      pipeline <- journaled(pipelineId)
      _        <- validateCertifiedPlacement(
        pipeline,
        subject.executionId,
        producer = false,
      )
      snapshot <- EitherT.liftF[
        F,
        ApplicationSafetyRuntimeFailure,
        org.sigilaris.node.jvm.runtime.application.ApplicationSafetySnapshot,
      ](
        safety.snapshot,
      )
      producerId = pipeline.verifiedPlan.orderedExecutionIds.headOption
      producer <- producerId
        .flatMap(snapshot.applied.get)
        .toRight(
          failure(
            "producerResultUnavailable",
            "certified producer result is not available",
          ),
        )
        .toEitherT[F]
      producerBlock = BlockId(producer.applied.blockId)
      onBranch      = branchContext.containsCertifiedBlock(producerBlock) ||
        branchContext.bestFinalizedBlockId.contains(producerBlock)
      _ <- EitherT.cond[F](
        onBranch,
        (),
        if branchContext.complete then
          failure(
            "producerNotAncestor",
            "certified producer is not on the candidate parent branch",
          )
        else
          failure(
            "producerAncestorUnavailable",
            "candidate parent ancestry is incomplete",
          ),
      )
      _ <- safety.validateExactEffectVote(
        pipelineId,
        subject,
        candidateHeight,
      )
    yield ()

  def recordCertifiedAncestorConsumer(
      pipelineId: TxPipelineId,
      application: ExactCertifiedApplication,
      branchContext: HotStuffProposalInputBranchContext,
  ): EitherT[F, ApplicationSafetyRuntimeFailure, AppliedExecutionRecord] =
    for
      _ <- validateCertifiedAncestorConsumerVote(
        pipelineId,
        application.certificate.subject,
        application.candidateHeight,
        branchContext,
      )
      records <- safety.recordExactApplicationsAtomically(
        pipelineId,
        Vector(application),
        Some(ExactPipelineLifecycle.Included),
      )
      record <- records.headOption
        .toRight(
          failure("appliedExecutionConflict", "consumer application is missing"),
        )
        .toEitherT[F]
    yield record

  def finalizeConsumer(
      pipelineId: TxPipelineId,
  ): EitherT[F, ApplicationSafetyRuntimeFailure, ExactTxPipelineRecord] =
    for
      pipeline <- journaled(pipelineId)
      snapshot <- EitherT.liftF[
        F,
        ApplicationSafetyRuntimeFailure,
        org.sigilaris.node.jvm.runtime.application.ApplicationSafetySnapshot,
      ](
        safety.snapshot,
      )
      consumerId = pipeline.verifiedPlan.orderedExecutionIds.lastOption
      _ <- EitherT.cond[F](
        consumerId.exists(snapshot.applied.contains),
        (),
        failure(
          "producerResultUnavailable",
          "consumer has not been canonically applied",
        ),
      )
      updated <- safety.finalizeExactPipeline(pipelineId)
    yield updated

  def materialize(
      pipelineId: TxPipelineId,
  ): EitherT[F, ApplicationSafetyRuntimeFailure, ExactTxPipelineRecord] =
    safety.materializeExactPipeline(pipelineId)

  private def journaled(
      pipelineId: TxPipelineId,
  ): EitherT[F, ApplicationSafetyRuntimeFailure, ExactTxPipelineRecord] =
    EitherT
      .liftF[
        F,
        ApplicationSafetyRuntimeFailure,
        org.sigilaris.node.jvm.runtime.application.ApplicationSafetySnapshot,
      ](
        safety.snapshot,
      )
      .flatMap(snapshot =>
        snapshot.exactPipelines
          .get(pipelineId)
          .toRight(
            failure(
              "exactPipelineBindingMismatch",
              s"exact pipeline ${pipelineId.value} is not journaled",
            ),
          )
          .toEitherT[F],
      )

  private def stepsFor(
      pipeline: ExactTxPipelineRecord,
  ): Either[ApplicationSafetyRuntimeFailure, Vector[ExactExecutionStep]] =
    val payloads = pipeline.genericProjection.stages
      .flatMap(_.transactions)
      .map(_.payload)
    Either.cond(
      payloads.sizeCompare(2) == 0 &&
        pipeline.verifiedPlan.orderedExecutionIds.sizeCompare(2) == 0,
      pipeline.verifiedPlan.orderedExecutionIds
        .zip(payloads)
        .map(ExactExecutionStep.apply),
      failure(
        "executionPlanMismatch",
        "exact descriptor does not contain two execution steps",
      ),
    )

  private def validateReducerExecution(
      pipeline: ExactTxPipelineRecord,
      step: ExactExecutionStep,
      execution: VerifiedApplicationExecution,
  ): Either[ApplicationSafetyRuntimeFailure, Unit] =
    for
      _ <- NormalizedApplicationResult
        .validate(execution.normalizedResult)
        .leftMap(_ =>
          failure(
            "resultCommitmentMismatch",
            "reducer returned a non-canonical result commitment",
          ),
        )
      expectedDigest <- dependencyPlanDigest(pipeline)
      _              <- Either.cond(
        execution.executionId == step.executionId &&
          execution.dependencyPlanDigest == expectedDigest &&
          execution.lastInclusionHeight ==
          pipeline.verifiedPlan.lastInclusionHeight,
        (),
        failure(
          "exactPipelineBindingMismatch",
          "reducer execution changed identity, plan digest, or deadline",
        ),
      )
    yield ()

  private def validateCertificates(
      executions: Vector[VerifiedApplicationExecution],
      certificates: Vector[CertifiedEffectCertificate],
  ): Either[ApplicationSafetyRuntimeFailure, Unit] =
    Either.cond(
      executions.sizeCompare(certificates.size) == 0 &&
        executions
          .zip(certificates)
          .forall: (execution, certificate) =>
            val subject = certificate.subject
            subject.executionId == execution.executionId &&
            subject.dependencyPlanDigest == execution.dependencyPlanDigest &&
            subject.lastInclusionHeight == execution.lastInclusionHeight &&
            subject.resultDigest == execution.normalizedResult.digest &&
            subject.stateRoot == execution.stateRoot
      ,
      (),
      failure(
        "resultCommitmentMismatch",
        "certified effects do not match the atomic reducer outputs",
      ),
    )

  private def validateCertifiedPlacement(
      pipeline: ExactTxPipelineRecord,
      executionId: ExecutionId,
      producer: Boolean,
  ): EitherT[F, ApplicationSafetyRuntimeFailure, Unit] =
    val expected =
      if producer then pipeline.verifiedPlan.orderedExecutionIds.headOption
      else pipeline.verifiedPlan.orderedExecutionIds.lastOption
    EitherT.cond[F](
      pipeline.verifiedPlan.mode == ExactExecutionMode.CertifiedAncestor &&
        expected.contains(executionId),
      (),
      failure(
        "executionOrderMismatch",
        "certified-ancestor placement does not match the exact descriptor",
      ),
    )

  private def dependencyPlanDigest(
      pipeline: ExactTxPipelineRecord,
  ): Either[ApplicationSafetyRuntimeFailure, DependencyPlanDigest] =
    UInt256
      .fromHex(
        ExactPipelineCanonical.verifiedPlanDigest(pipeline.verifiedPlan),
      )
      .leftMap(error => failure("exactPipelineBindingMismatch", error.toString))
      .map(DependencyPlanDigest(_))

  private def storeFailure(reason: String): ApplicationSafetyRuntimeFailure =
    failure(reason, "exact pipeline store rejected the operation")

  private def failure(
      reason: String,
      detail: String,
  ): ApplicationSafetyRuntimeFailure =
    ApplicationSafetyRuntimeFailure(reason, detail)
