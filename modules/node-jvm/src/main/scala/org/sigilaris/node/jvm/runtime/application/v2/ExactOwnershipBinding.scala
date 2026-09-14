package org.sigilaris.node.jvm.runtime.application.v2

import cats.syntax.all.*

import org.sigilaris.core.application.protocol.ExecutionId
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.node.txpipeline.v2.{
  ExactIdentityBinding,
  ExactPipelineRecord,
  StageIntent,
}

/** Connects authenticated source material to the authoritative admission in the
  * same immutable safety projection. A separately retained signed outer plan is
  * not evidence that its stage/output ownership was durably acquired.
  *
  * This checks ownership and signed source fields. It does not authorize an
  * exact profile outcome, ordering mode, ancestry, or a signature publication.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
private[v2] object ExactOwnershipBinding:
  private final case class OwnedStage(
      record: ExactPipelineRecord,
      stage: StageIntent,
      executionId: ExecutionId,
  )

  private def check(
      condition: Boolean,
      detail: String,
  ): Either[V2RuntimeFailure, Unit] =
    RuntimeCheck.require(condition, RuntimeFailureCode.InvalidRequest, detail)

  private def inventory(
      state: SafetyState,
  ): Either[V2RuntimeFailure, Vector[OwnedStage]] =
    ExactSafetyJournalReduction
      .records(state)
      .map(
        _.flatMap(record =>
          record.request.signedPlan.intent.stages
            .zip(record.binding.executionIds)
            .map((stage, execution) => OwnedStage(record, stage, execution)),
        ),
      )

  private def owner(
      retained: Vector[OwnedStage],
      context: DomainContext,
      executionId: ExecutionId,
      txId: Hash,
      binding: Option[Hash],
  ): Either[V2RuntimeFailure, Option[OwnedStage]] =
    val matching = retained.filter(value =>
      value.record.binding.context == context &&
        (value.stage.txId == txId || value.executionId == executionId),
    )
    binding match
      case None =>
        check(
          matching.isEmpty,
          "generic source cannot reuse a durably owned exact stage transaction",
        ).as(None)
      case Some(expected) =>
        for
          _ <- check(
            matching.sizeIs == 1,
            "exact source has no unique authoritative stage registration",
          )
          selected <- matching.headOption.toRight(
            V2RuntimeFailure.at(
              RuntimeFailureCode.InvalidRequest,
              "exact source has no authoritative stage registration",
            ),
          )
          actual <- RuntimeCheck.core(
            ExactIdentityBinding.digest(selected.record.binding),
          )
          _ <- check(
            actual == expected && selected.executionId == executionId &&
              selected.stage.txId == txId && selected.record.admissionJournalSequence.nonEmpty,
            "exact source differs from its durable binding or stage ownership",
          )
        yield Some(selected)

  private def lockSource(
      retained: Vector[OwnedStage],
      request: VerifiedLockRequest,
  ): Either[V2RuntimeFailure, Unit] =
    val subject = request.subject
    owner(
      retained,
      subject.context,
      subject.executionId,
      subject.txId,
      request.exactBinding,
    ).flatMap(
      _.traverse_(selected =>
        check(
          request.signedTransaction == selected.stage.signedTransaction &&
            subject.manifestDigest == selected.stage.manifestDigest &&
            subject.fullInputCommitment == selected.stage.fullInputCommitment &&
            subject.lockSubsetCommitment == selected.stage.lockSubsetCommitment &&
            subject.dependencyPlanDigest == selected.record.binding.signedPlanDigest &&
            subject.lastInclusionHeight == selected.record.request.signedPlan.intent.lastInclusionHeight,
          "lock source differs from its immutable signed exact stage",
        ),
      ),
    )

  def lock(
      state: SafetyState,
      request: VerifiedLockRequest,
  ): Either[V2RuntimeFailure, Unit] =
    inventory(state).flatMap(lockSource(_, request))

  def effect(
      state: SafetyState,
      request: VerifiedEffectRequest,
  ): Either[V2RuntimeFailure, Unit] =
    for
      retained <- inventory(state)
      _        <- lockSource(retained, request.sourceLock.request)
      _        <- check(
        request.exactBinding == request.sourceLock.request.exactBinding,
        "effect and input-lock exact bindings differ",
      )
      selected <- owner(
        retained,
        request.subject.context,
        request.subject.executionId,
        request.subject.txId,
        request.exactBinding,
      )
      _ <- selected.traverse_(value =>
        check(
          value.stage.sourceKind == 2.toByte &&
            request.subject.manifestDigest == value.stage.manifestDigest &&
            request.subject.dependencyPlanDigest == value.record.binding.signedPlanDigest &&
            request.subject.lastInclusionHeight == value.record.request.signedPlan.intent.lastInclusionHeight,
          "effect source is not its registered fast exact stage",
        ),
      )
    yield ()

  def consensus(
      state: SafetyState,
      request: VerifiedConsensusProposal,
  ): Either[V2RuntimeFailure, Unit] =
    for
      retained <- inventory(state)
      entries = request.plan.waves.flatMap(_.entries)
      _ <- check(
        entries.map(_.executionId) == request.reservations.map(
          _.owner.executionId,
        ),
        "verified proposal reservations differ from its ordered entries",
      )
      _ <- entries
        .zip(request.reservations)
        .traverse_((entry, reservation) =>
          owner(
            retained,
            request.context,
            entry.executionId,
            entry.source.txId,
            reservation.exactBinding,
          ).flatMap(
            _.traverse_(selected =>
              ExactLifecycleProjection.verifyEntry(selected.record, entry),
            ),
          ),
        )
    yield ()
