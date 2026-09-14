package org.sigilaris.node.jvm.runtime.application.v2

import cats.syntax.all.*

import org.sigilaris.core.application.protocol.ExecutionId
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.node.txpipeline.v2.*

/** Builds the exact member of an already authenticated application/expiry
  * transaction. These pure products grant no finality or nonapplication
  * authority: the caller must supply its actual verified capability and retain
  * the corresponding evidence before the shared journal append.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
private[v2] object ExactLifecycleProjection:
  private def check(
      condition: Boolean,
      detail: String,
  ): Either[V2RuntimeFailure, Unit] =
    RuntimeCheck.require(condition, RuntimeFailureCode.InvalidRequest, detail)

  /** Complete source linkage is also required when replaying original voting or
    * application evidence. Execution ids alone do not authenticate source kind,
    * which is a separate signed-plan contract field.
    */
  def verifyEntry(
      record: ExactPipelineRecord,
      entry: PlanEntry,
  ): Either[V2RuntimeFailure, Unit] =
    for
      _     <- RuntimeCheck.core(PlanEntry.validate(entry))
      index <- record.binding.executionIds.indexOf(entry.executionId) match
        case value if value >= 0 => Right[V2RuntimeFailure, Int](value)
        case _                   =>
          Left[V2RuntimeFailure, Int](
            V2RuntimeFailure.at(
              RuntimeFailureCode.InvalidRequest,
              "plan entry is not a registered exact stage",
            ),
          )
      stage <- record.request.signedPlan.intent.stages
        .lift(index)
        .toRight(
          V2RuntimeFailure.at(
            RuntimeFailureCode.InvalidRequest,
            "signed exact stage is unavailable",
          ),
        )
      _ <- check(
        entry.source.txId == stage.txId && entry.source.signedTransaction == stage.signedTransaction && entry.source.tag == stage.sourceKind &&
          entry.manifestDigest == stage.manifestDigest && entry.declaration == stage.declaration && entry.fullInputCommitment == stage.fullInputCommitment &&
          entry.lockSubsetCommitment == stage.lockSubsetCommitment && entry.dependencyPlanDigest == record.binding.signedPlanDigest &&
          entry.lastInclusionHeight == record.request.signedPlan.intent.lastInclusionHeight,
        "actual plan entry differs from the immutable signed exact stage",
      )
    yield ()

  def application(
      state: SafetyState,
      batch: ApplicationBatch,
      decision: ApplicationDecision,
      evidenceDigest: Hash,
      plan: ExecutionPlan,
  ): Either[V2RuntimeFailure, Vector[ExactRecordUpdate]] =
    for
      _      <- RuntimeCheck.core(ApplicationBatch.validate(batch))
      _      <- RuntimeCheck.core(ApplicationDecision.validate(decision))
      digest <- RuntimeCheck.core(ApplicationBatch.digest(batch))
      root   <- RuntimeCheck.core(ExecutionPlan.computeRoot(plan))
      _      <- check(
        decision.batchDigest == digest && root.toUInt256 == batch.planRoot,
        "exact projection names another canonical application batch or execution plan",
      )
      planEntries = plan.waves.flatMap(_.entries)
      _ <- check(
        planEntries.map(_.executionId) == batch.entries.map(_.executionId),
        "exact materialization plan differs from selected batch order",
      )
      records <- ExactSafetyJournalReduction.records(state)
      entries = batch.entries.map(entry => entry.executionId -> entry).toMap
      sources = planEntries.map(entry => entry.executionId -> entry).toMap
      updates <- records.traverse(record =>
        val selected =
          record.stages.filter(stage => entries.contains(stage.executionId))
        if selected.isEmpty then
          Right[V2RuntimeFailure, Option[ExactRecordUpdate]](None)
        else
          for
            _ <- check(
              record.binding.context == batch.context && selected.forall(
                stage =>
                  stage.firstApplicationBlock.isEmpty && stage.lifecycle != ExactStageLifecycle.ExpiredUnapplied,
              ),
              "exact first application cannot replace terminal evidence",
            )
            _ <- check(
              batch.candidateHeight.toBigNat.toBigInt <= record.request.signedPlan.intent.lastInclusionHeight.toBigNat.toBigInt,
              "exact application exceeds its signed inclusive deadline",
            )
            stages <- record.stages.traverse(stage =>
              entries.get(stage.executionId) match
                case None => Right[V2RuntimeFailure, ExactStageRecord](stage)
                case Some(entry) =>
                  for
                    source <- sources
                      .get(stage.executionId)
                      .toRight(
                        V2RuntimeFailure.at(
                          RuntimeFailureCode.InvalidRequest,
                          "selected exact source is unavailable",
                        ),
                      )
                    _ <- verifyEntry(record, source)
                    refs = source.source match
                      case PlanSource.ConsensusTransaction(_, _, lock) =>
                        (lock, Option.empty[Hash])
                      case PlanSource
                            .CertifiedFastExecution(_, _, lock, effect) =>
                        (Some(lock), Some(effect))
                    _ <- check(
                      refs._1.forall(state.lockCertificates.contains) && refs._2
                        .forall(state.effectCertificates.contains),
                      "actual selected exact certificates must be authenticated and durably retained before materialization",
                    )
                  yield stage.copy(
                    lifecycle = ExactStageLifecycle.Materialized,
                    inputLockCertificateId =
                      stage.inputLockCertificateId.orElse(refs._1),
                    effectCertificateId =
                      stage.effectCertificateId.orElse(refs._2),
                    firstApplicationBlock = Some(decision.blockId),
                    firstApplicationHeight = Some(batch.candidateHeight),
                    resultDigest = Some(entry.resultDigest),
                    terminalEvidenceDigest = Some(evidenceDigest),
                  ),
            )
            update <- RuntimeCheck.core(
              ExactJournalRecords.update(
                record,
                record.copy(stages = stages),
                evidenceDigest,
              ),
            )
          yield Some(update),
      )
    yield updates.flatten

  def expiry(
      state: SafetyState,
      resolution: TerminalResolution,
      executions: Vector[ExecutionId],
  ): Either[V2RuntimeFailure, Vector[ExactRecordUpdate]] =
    for
      _ <- RuntimeCheck.core(TerminalResolution.validate(resolution))
      _ <- check(
        resolution.kind == ResolutionKind.ExpiredUnapplied && resolution.applicationBatchDigest.isEmpty,
        "exact expiry requires an authenticated nonapplication resolution",
      )
      _ <- check(
        executions.distinct.sizeCompare(executions.size) == 0,
        "exact expiry repeats an execution",
      )
      records <- ExactSafetyJournalReduction.records(state)
      selected = executions.toSet
      updates <- records.traverse(record =>
        val touched =
          record.stages.filter(stage => selected.contains(stage.executionId))
        if touched.isEmpty || touched.forall(
            _.lifecycle == ExactStageLifecycle.ExpiredUnapplied,
          )
        then Right[V2RuntimeFailure, Option[ExactRecordUpdate]](None)
        else
          for
            _ <- check(
              touched.forall(stage =>
                stage.firstApplicationBlock.isEmpty && !state.appliedEntries
                  .contains(stage.executionId),
              ),
              "nonapplication expiry targets an already applied exact stage",
            )
            _ <- check(
              resolution.resolvedHeight.toBigNat.toBigInt > record.request.signedPlan.intent.lastInclusionHeight.toBigNat.toBigInt,
              "exact expiry must be strictly after the immutable signed deadline",
            )
            stages = record.stages.map(stage =>
              if selected.contains(
                  stage.executionId,
                ) && stage.lifecycle != ExactStageLifecycle.ExpiredUnapplied
              then
                stage.copy(
                  lifecycle = ExactStageLifecycle.ExpiredUnapplied,
                  terminalEvidenceDigest = Some(resolution.evidenceDigest),
                )
              else stage,
            )
            update <- RuntimeCheck.core(
              ExactJournalRecords.update(
                record,
                record.copy(stages = stages),
                resolution.evidenceDigest,
              ),
            )
          yield Some(update),
      )
    yield updates.flatten
