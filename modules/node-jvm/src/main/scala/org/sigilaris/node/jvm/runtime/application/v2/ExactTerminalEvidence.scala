package org.sigilaris.node.jvm.runtime.application.v2

import cats.syntax.all.*

import org.sigilaris.core.application.protocol.ExecutionId
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.node.txpipeline.v2.*

/** Resolves ownerless exact expiry from the authoritative historical operation.
  * The application recovery authenticator separately verifies the referenced
  * finality/nonapplication bytes. A latest lifecycle tag or digest alone is not
  * enough to turn a newly received certificate into a terminal lock claim.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
private[v2] object ExactTerminalEvidence:
  private def check(
      condition: Boolean,
      detail: String,
  ): Either[V2RuntimeFailure, Unit] =
    RuntimeCheck.require(condition, RuntimeFailureCode.JournalCorrupt, detail)

  def resolution(
      state: SafetyState,
      context: DomainContext,
      execution: ExecutionId,
  ): Either[V2RuntimeFailure, Option[TerminalResolution]] =
    for
      _       <- RuntimeCheck.core(DomainContext.validateActive(context))
      records <- ExactSafetyJournalReduction.records(state)
      stages = records
        .filter(_.binding.context == context)
        .flatMap(record =>
          record.stages.filter(_.executionId == execution).map(record -> _),
        )
      _ <- check(
        stages.sizeCompare(1) <= 0,
        "one execution has multiple retained exact admissions",
      )
      result <- stages.headOption match
        case Some((record, stage))
            if stage.lifecycle == ExactStageLifecycle.ExpiredUnapplied =>
          expired(state, record, stage).map(Some(_))
        case _ => Right[V2RuntimeFailure, Option[TerminalResolution]](None)
    yield result

  private def expired(
      state: SafetyState,
      selected: ExactPipelineRecord,
      stage: ExactStageRecord,
  ): Either[V2RuntimeFailure, TerminalResolution] =
    for
      evidence <- stage.terminalEvidenceDigest.toRight(
        V2RuntimeFailure.at(
          RuntimeFailureCode.JournalCorrupt,
          "expired exact stage has no terminal evidence digest",
        ),
      )
      _ <- check(
        stage.firstApplicationBlock.isEmpty && !state.appliedEntries.contains(
          stage.executionId,
        ),
        "expired exact stage contradicts canonical application",
      )
      binding <- RuntimeCheck.core(
        ExactIdentityBinding.digest(selected.binding),
      )
      rows <- state.committed
        .filter(_.operation == JournalOperation.Expiry)
        .traverse(record =>
          for
            _ <- check(
              record.status == JournalStatus.Committed,
              "exact expiry evidence is not selected by a committed journal operation",
            )
            payload <- RuntimeCheck
              .core(JournalPayload.codec.decode(record.payload))
            _ <- RuntimeCheck
              .core(JournalPayload.validateOperation(payload, record.operation))
            linked <- payload.exactRecordUpdates
              .filter(update =>
                update.context == selected.binding.context && update.nodePipelineId == selected.binding.nodePipelineId && update.bindingDigest == binding,
              )
              .traverse(update =>
                RuntimeCheck
                  .core(
                    ExactPipelineRecord.codec.decode(update.canonicalNextRecord),
                  )
                  .map(next =>
                    next.binding == selected.binding && next.request == selected.request && next.stages
                      .exists(previous =>
                        previous.executionId == stage.executionId && previous.lifecycle == ExactStageLifecycle.ExpiredUnapplied && previous.terminalEvidenceDigest
                          .contains(evidence),
                      ),
                  ),
              )
          yield
            if linked.contains(true) then
              payload.terminalResolutions.filter(_.evidenceDigest == evidence)
            else Vector.empty,
        )
      distinct = rows.flatten.distinct
      _ <- check(
        distinct.sizeCompare(1) == 0,
        "expired exact stage has missing or contradictory committed terminal resolution",
      )
      resolution <- distinct.headOption.toRight(
        V2RuntimeFailure.at(
          RuntimeFailureCode.JournalCorrupt,
          "expired exact terminal resolution unavailable",
        ),
      )
      _ <- RuntimeCheck.core(TerminalResolution.validate(resolution))
      _ <- check(
        resolution.kind == ResolutionKind.ExpiredUnapplied && resolution.applicationBatchDigest.isEmpty && resolution.resolvedHeight.toBigNat.toBigInt > selected.request.signedPlan.intent.lastInclusionHeight.toBigNat.toBigInt,
        "exact terminal resolution is not nonapplication strictly beyond its signed deadline",
      )
    yield resolution
