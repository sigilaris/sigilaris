package org.sigilaris.node.jvm.runtime.application.v2

import cats.syntax.all.*

import org.sigilaris.core.application.protocol.ExecutionId
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.node.txpipeline.v2.*

/** Structural projection of the exact consistency-group member. This does not
  * verify transaction signatures, profile authorization, canonical history,
  * alias blobs, or materialization/failure evidence. The shared store must run
  * its configured exact recovery authenticator before append/publication.
  *
  * `before` is the fully replayed preceding journal projection. `projected` is
  * the same record after ordinary lock/owner/application/certificate reduction.
  * The caller appends the current committed record to the returned projection.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
private[v2] object ExactSafetyJournalReduction:
  private def core[A](
      value: Either[CoreFailure, A],
  ): Either[V2RuntimeFailure, A] = RuntimeCheck.core(value)
  private def check(
      condition: Boolean,
      detail: String,
  ): Either[V2RuntimeFailure, Unit] =
    RuntimeCheck.require(condition, RuntimeFailureCode.JournalCorrupt, detail)

  /** Derived in-process map key, not another persistent or signing format. */
  def key(
      context: DomainContext,
      nodePipelineId: Text,
  ): Either[V2RuntimeFailure, String] =
    core(DomainContext.codec.encode(context)).map(bytes =>
      (bytes ++ ByteEncoder[Text].encode(nodePipelineId)).toHex,
    )

  /** Reads the latest already-validated projection. Monotonic prior-record
    * links are established by replaying apply; a latest snapshot alone cannot
    * prove a transition or recover the historical idempotency aliases.
    */
  def records(
      state: SafetyState,
  ): Either[V2RuntimeFailure, Vector[ExactPipelineRecord]] =
    keyedRecords(state).map(
      _.values.toVector.sortBy(record =>
        V2Validation.textKey(record.binding.nodePipelineId),
      ),
    )

  private def keyedRecords(
      state: SafetyState,
  ): Either[V2RuntimeFailure, Map[String, ExactPipelineRecord]] =
    for
      _ <- check(
        state.exactUpdates.keySet.subsetOf(state.exactRegistrations.keySet),
        "exact update has no authoritative registration",
      )
      decoded <- state.exactRegistrations.toVector
        .sortBy(_._1)
        .traverse((storedKey, registration) =>
          for
            initial <- core(
              ExactPipelineRecord.codec.decode(registration.canonicalRecord),
            )
            sequence <- initial.admissionJournalSequence.toRight(
              V2RuntimeFailure.at(
                RuntimeFailureCode.JournalCorrupt,
                "registered exact record lacks its admission sequence",
              ),
            )
            _ <- core(
              ExactJournalRecords.decodeRegistration(registration, sequence),
            )
            expectedKey <- key(
              initial.binding.context,
              initial.binding.nodePipelineId,
            )
            _ <- check(
              expectedKey == storedKey,
              "exact registration projection key changed",
            )
            current <- state.exactUpdates
              .get(storedKey)
              .traverse(update =>
                for
                  next <- core(
                    ExactPipelineRecord.codec.decode(update.canonicalNextRecord),
                  )
                  _ <- core(ExactRecordUpdate.validate(update))
                  _ <- core(ExactPipelineRecord.validateUpdate(initial, next))
                  binding <- core(ExactIdentityBinding.digest(next.binding))
                  _       <- check(
                    update.context == next.binding.context && update.nodePipelineId == next.binding.nodePipelineId && update.bindingDigest == binding,
                    "latest exact update differs from immutable registered identity",
                  )
                yield next,
              )
          yield storedKey -> current.getOrElse(initial),
        )
    yield decoded.toMap

  def apply(
      before: SafetyState,
      projected: SafetyState,
      record: JournalRecord,
      payload: JournalPayload,
      context: DomainContext,
  ): Either[V2RuntimeFailure, SafetyState] =
    for
      _ <- check(
        record.status == JournalStatus.Committed,
        "exact reduction requires the proposed committed record",
      )
      _     <- core(JournalPayload.validateOperation(payload, record.operation))
      prior <- keyedRecords(before)
      _     <- ownership(prior.values.toVector, context)
      registration <- payload.exactRegistration.traverse(value =>
        for
          _ <- check(
            record.operation == JournalOperation.ExactRegistration,
            "exact registration uses another operation",
          )
          decoded <- core(
            ExactJournalRecords.decodeRegistration(value, record.sequence),
          )
          _ <- check(
            decoded.binding.context == context,
            "exact admission crosses the installed domain",
          )
          id <- key(decoded.binding.context, decoded.binding.nodePipelineId)
          _  <- check(
            !prior.contains(id),
            "exact admission cannot replace an immutable registered submission",
          )
          executions = decoded.binding.executionIds.toSet
          _ <- check(
            !executions
              .exists(before.appliedEntries.contains) && !executions.exists(
              before.locks.contains,
            ) && !before.claims.valuesIterator.exists(claim =>
              executions.contains(claim.owner.executionId),
            ) && !before.lockCertificates.valuesIterator.exists(certificate =>
              executions.contains(certificate.subject.executionId),
            ) && !before.effectCertificates.valuesIterator.exists(certificate =>
              executions.contains(certificate.subject.executionId),
            ),
            "exact admission must precede every stage claim, certificate and application",
          )
        yield (id, value, decoded),
      )
      registered = prior ++ registration
        .map(value => value._1 -> value._3)
        .toList
      _       <- ownership(registered.values.toVector, context)
      changed <- payload.exactRecordUpdates.traverse(update =>
        for
          id  <- key(update.context, update.nodePipelineId)
          old <- registered
            .get(id)
            .toRight(
              V2RuntimeFailure.at(
                RuntimeFailureCode.JournalCorrupt,
                "exact lifecycle update has no registered submission",
              ),
            )
          next <- core(ExactJournalRecords.decodeUpdate(old, update))
          _    <- check(
            next.binding.context == context,
            "exact lifecycle update crosses the installed domain",
          )
          _ <- transition(
            old,
            next,
            update,
            record.operation,
            payload,
            before,
            projected,
          )
        yield (id, update, next),
      )
      _ <- check(
        changed.map(_._1).distinct.sizeCompare(changed.size) == 0,
        "one journal operation updates an exact submission twice",
      )
      updated = registered ++ changed.map(value => value._1 -> value._3)
      _ <- ownership(updated.values.toVector, context)
      _ <- requiredTerminalProjection(
        prior,
        updated,
        changed.map(_._1).toSet,
        payload,
        record.operation,
        projected,
      )
    yield projected.copy(
      exactRegistrations = before.exactRegistrations ++ registration
        .map(value => value._1 -> value._2)
        .toList,
      exactUpdates =
        before.exactUpdates ++ changed.map(value => value._1 -> value._2),
    )

  private def ownership(
      records: Vector[ExactPipelineRecord],
      context: DomainContext,
  ): Either[V2RuntimeFailure, Unit] =
    for
      _ <- check(
        records.forall(_.binding.context == context),
        "exact ownership spans another installed domain",
      )
      _        <- core(ExactPipelineSnapshot.ownership(records))
      requests <- records.traverse(record =>
        core(ExactSubmitRequest.digest(record.request)),
      )
      _ <- check(
        requests.distinct.sizeCompare(requests.size) == 0,
        "signed work already belongs to another node submission identity",
      )
      initialKeys = records
        .flatMap(_.request.idempotencyKey)
        .map(V2Validation.textKey)
      _ <- check(
        initialKeys.distinct.sizeCompare(initialKeys.size) == 0,
        "initial idempotency key already belongs to another submission",
      )
    yield ()

  private def transition(
      previous: ExactPipelineRecord,
      next: ExactPipelineRecord,
      update: ExactRecordUpdate,
      operation: JournalOperation,
      payload: JournalPayload,
      before: SafetyState,
      projected: SafetyState,
  ): Either[V2RuntimeFailure, Unit] =
    for
      _ <- core(ExactPipelineRecord.validateUpdate(previous, next))
      _ <- check(
        previous != next || operation == JournalOperation.ExactLifecycle,
        "identical exact record updates are reserved for authenticated alias evidence",
      )
      _ <- previous.stages
        .zip(next.stages)
        .traverse_((old, stage) =>
          if old == stage then Right[V2RuntimeFailure, Unit](())
          else
            for
              _ <- lifecycle(old.lifecycle, stage.lifecycle)
              _ <- operationFor(old, stage, operation)
              _ <- certificates(next, stage, projected)
              _ <- check(
                old.firstApplicationBlock.nonEmpty || stage.firstApplicationBlock.isEmpty || operation == JournalOperation.ApplicationCommit,
                "first application facts require the actual canonical application decision",
              )
              _ <-
                if certificateOnly(old, stage) then
                  check(
                    operation == JournalOperation.CertificateImport || operation == JournalOperation.ExactLifecycle,
                    "certificate fact has no authenticated import/lifecycle evidence",
                  )
                    .flatMap(_ =>
                      check(
                        stage.inputLockCertificateId.contains(
                          update.evidenceDigest,
                        ) || stage.effectCertificateId
                          .contains(update.evidenceDigest),
                        "certificate projection evidence differs from its retained certificate",
                      ),
                    )
                else if old.firstApplicationBlock.isEmpty && stage.firstApplicationBlock.nonEmpty
                then
                  included(
                    next,
                    old,
                    stage,
                    update,
                    operation,
                    payload,
                    projected,
                  )
                else
                  stage.lifecycle match
                    case ExactStageLifecycle.LockCertified =>
                      check(
                        stage.inputLockCertificateId
                          .contains(update.evidenceDigest),
                        "lock-certified projection lacks its retained certificate evidence",
                      )
                    case ExactStageLifecycle.Reserved |
                        ExactStageLifecycle.Failed =>
                      candidateEvidence(
                        next,
                        stage,
                        update.evidenceDigest,
                        projected,
                      )
                    case ExactStageLifecycle.ExpiredUnapplied =>
                      expired(next, stage, update, payload, before)
                    case ExactStageLifecycle.Included |
                        ExactStageLifecycle.Finalized |
                        ExactStageLifecycle.Materialized =>
                      check(
                        stage.terminalEvidenceDigest.contains(
                          update.evidenceDigest,
                        ) && old.terminalEvidenceDigest == stage.terminalEvidenceDigest && projected.appliedEntries
                          .get(stage.executionId)
                          .exists(applied =>
                            stage.firstApplicationBlock.contains(
                              applied.blockId,
                            ) && stage.firstApplicationHeight.contains(
                              applied.candidateHeight,
                            ) && stage.resultDigest
                              .contains(applied.resultDigest),
                          ),
                        "exact finalization/materialization lacks its immutable canonical application",
                      )
                    case ExactStageLifecycle.Accepted =>
                      check(
                        false,
                        "an accepted exact stage cannot acquire lifecycle evidence without advancing",
                      )
            yield (),
        )
      _ <-
        if operation == JournalOperation.ApplicationCommit then
          atomicMode(previous, next, payload, projected)
        else Right[V2RuntimeFailure, Unit](())
    yield ()

  private def lifecycle(
      previous: ExactStageLifecycle,
      next: ExactStageLifecycle,
  ): Either[V2RuntimeFailure, Unit] =
    val allowed = previous match
      case ExactStageLifecycle.Accepted =>
        Set(
          ExactStageLifecycle.LockCertified,
          ExactStageLifecycle.Reserved,
          ExactStageLifecycle.Included,
          ExactStageLifecycle.Materialized,
          ExactStageLifecycle.ExpiredUnapplied,
          ExactStageLifecycle.Failed,
        )
      case ExactStageLifecycle.LockCertified =>
        Set(
          ExactStageLifecycle.LockCertified,
          ExactStageLifecycle.Reserved,
          ExactStageLifecycle.Included,
          ExactStageLifecycle.Materialized,
          ExactStageLifecycle.ExpiredUnapplied,
          ExactStageLifecycle.Failed,
        )
      case ExactStageLifecycle.Reserved =>
        Set(
          ExactStageLifecycle.Reserved,
          ExactStageLifecycle.Included,
          ExactStageLifecycle.Materialized,
          ExactStageLifecycle.ExpiredUnapplied,
          ExactStageLifecycle.Failed,
        )
      case ExactStageLifecycle.Included =>
        Set(
          ExactStageLifecycle.Included,
          ExactStageLifecycle.Finalized,
          ExactStageLifecycle.Materialized,
        )
      case ExactStageLifecycle.Finalized =>
        Set(ExactStageLifecycle.Finalized, ExactStageLifecycle.Materialized)
      case ExactStageLifecycle.Materialized =>
        Set(ExactStageLifecycle.Materialized)
      case ExactStageLifecycle.ExpiredUnapplied =>
        Set(ExactStageLifecycle.ExpiredUnapplied)
      // Failed is a non-releasing candidate diagnostic. An independently
      // verified retry may reserve again; restart never infers signing
      // permission from this lifecycle projection.
      case ExactStageLifecycle.Failed =>
        Set(
          ExactStageLifecycle.Failed,
          ExactStageLifecycle.Reserved,
          ExactStageLifecycle.Included,
          ExactStageLifecycle.Materialized,
          ExactStageLifecycle.ExpiredUnapplied,
        )
    check(
      allowed.contains(next),
      "exact stage lifecycle regressed or a terminal stage was resurrected",
    )

  private def certificateOnly(
      previous: ExactStageRecord,
      next: ExactStageRecord,
  ): Boolean =
    previous != next && previous.copy(
      inputLockCertificateId = next.inputLockCertificateId,
      effectCertificateId = next.effectCertificateId,
    ) == next

  private def operationFor(
      previous: ExactStageRecord,
      next: ExactStageRecord,
      operation: JournalOperation,
  ): Either[V2RuntimeFailure, Unit] =
    val allowed =
      if certificateOnly(previous, next) then
        operation == JournalOperation.CertificateImport || operation == JournalOperation.ExactLifecycle
      else if previous.firstApplicationBlock.isEmpty && next.firstApplicationBlock.nonEmpty
      then operation == JournalOperation.ApplicationCommit
      else
        next.lifecycle match
          case ExactStageLifecycle.Accepted      => false
          case ExactStageLifecycle.LockCertified =>
            operation == JournalOperation.CertificateImport || operation == JournalOperation.ExactLifecycle
          case ExactStageLifecycle.Reserved =>
            operation == JournalOperation.Reservation || operation == JournalOperation.VoteIntent || operation == JournalOperation.ExactLifecycle
          case ExactStageLifecycle.Included =>
            operation == JournalOperation.ApplicationCommit
          case ExactStageLifecycle.ExpiredUnapplied =>
            operation == JournalOperation.Expiry
          case ExactStageLifecycle.Finalized |
              ExactStageLifecycle.Materialized | ExactStageLifecycle.Failed =>
            operation == JournalOperation.ExactLifecycle
    check(
      allowed,
      "exact lifecycle transition is attached to an unrelated journal operation",
    )

  private def candidateEvidence(
      record: ExactPipelineRecord,
      stage: ExactStageRecord,
      evidence: Hash,
      state: SafetyState,
  ): Either[V2RuntimeFailure, Unit] =
    for
      intent <- state.consensusIntents
        .get(evidence)
        .toRight(
          V2RuntimeFailure.at(
            RuntimeFailureCode.JournalCorrupt,
            "exact candidate lifecycle has no durable original consensus intent",
          ),
        )
      _ <- check(
        intent.context == record.binding.context && !state.appliedEntries
          .contains(
            stage.executionId,
          ) && stage.firstApplicationBlock.isEmpty && stage.terminalEvidenceDigest
          .forall(_ == evidence),
        "candidate lifecycle contradicts canonical application or domain",
      )
      _ <- check(
        intent.ownerDigests.exists(id =>
          state.claims
            .get(id)
            .exists(claim =>
              claim.owner.context == record.binding.context && claim.owner.executionId == stage.executionId && claim.lifecycle == ClaimLifecycle.Live &&
                claim.lastInclusionHeight == record.request.signedPlan.intent.lastInclusionHeight &&
                claim.owner.scope.kind != ScopeKind.FastAdmission && claim.owner.scope.planRoot == intent.planRoot,
            ),
        ),
        "candidate lifecycle has no matching complete live stage reservation in its original consensus plan",
      )
    yield ()

  private def certificates(
      record: ExactPipelineRecord,
      stage: ExactStageRecord,
      state: SafetyState,
  ): Either[V2RuntimeFailure, Unit] =
    for
      index <- record.binding.executionIds.indexOf(stage.executionId) match
        case index if index >= 0 => Right[V2RuntimeFailure, Int](index)
        case _                   =>
          Left[V2RuntimeFailure, Int](
            V2RuntimeFailure.at(
              RuntimeFailureCode.JournalCorrupt,
              "exact stage is absent from its registered identity",
            ),
          )
      intent <- record.request.signedPlan.intent.stages
        .lift(index)
        .toRight(
          V2RuntimeFailure.at(
            RuntimeFailureCode.JournalCorrupt,
            "exact signed stage is absent",
          ),
        )
      _ <- stage.inputLockCertificateId.traverse_(id =>
        state.lockCertificates
          .get(id)
          .toRight(
            V2RuntimeFailure.at(
              RuntimeFailureCode.JournalCorrupt,
              "exact stage references an unavailable input lock certificate",
            ),
          )
          .flatMap(certificate =>
            val subject = certificate.subject
            check(
              subject.context == record.binding.context && subject.executionId == stage.executionId && subject.txId == intent.txId && subject.manifestDigest == intent.manifestDigest && subject.fullInputCommitment == intent.fullInputCommitment && subject.lockSubsetCommitment == intent.lockSubsetCommitment && subject.dependencyPlanDigest == record.binding.signedPlanDigest && subject.lastInclusionHeight == record.request.signedPlan.intent.lastInclusionHeight,
              "exact stage input lock certificate contradicts its signed stage",
            ),
          ),
      )
      _ <- stage.effectCertificateId.traverse_(id =>
        state.effectCertificates
          .get(id)
          .toRight(
            V2RuntimeFailure.at(
              RuntimeFailureCode.JournalCorrupt,
              "exact stage references an unavailable effect certificate",
            ),
          )
          .flatMap(certificate =>
            val subject = certificate.subject
            check(
              stage.sourceKind == 2.toByte && subject.context == record.binding.context && subject.executionId == stage.executionId && subject.txId == intent.txId && subject.manifestDigest == intent.manifestDigest && stage.inputLockCertificateId
                .flatMap(state.lockCertificates.get)
                .exists(archived =>
                  state.lockCertificates
                    .get(subject.inputLockCertificateId)
                    .exists(actual => actual.subject == archived.subject),
                ) && subject.dependencyPlanDigest == record.binding.signedPlanDigest && subject.lastInclusionHeight == record.request.signedPlan.intent.lastInclusionHeight && stage.resultDigest
                .forall(_ == subject.resultDigest.toUInt256),
              "exact fast certificate contradicts its signed stage or input lock",
            ),
          ),
      )
    yield ()

  private def included(
      record: ExactPipelineRecord,
      previous: ExactStageRecord,
      stage: ExactStageRecord,
      update: ExactRecordUpdate,
      operation: JournalOperation,
      payload: JournalPayload,
      state: SafetyState,
  ): Either[V2RuntimeFailure, Unit] =
    for
      decision <- payload.applicationDecision.toRight(
        V2RuntimeFailure.at(
          RuntimeFailureCode.JournalCorrupt,
          "exact inclusion lacks its application decision",
        ),
      )
      applied <- state.appliedEntries
        .get(stage.executionId)
        .toRight(
          V2RuntimeFailure.at(
            RuntimeFailureCode.JournalCorrupt,
            "exact inclusion lacks an actual applied entry",
          ),
        )
      _ <- check(
        operation == JournalOperation.ApplicationCommit && previous.firstApplicationBlock.isEmpty && stage.firstApplicationBlock
          .contains(decision.blockId) && stage.firstApplicationBlock.contains(
          applied.blockId,
        ) && stage.firstApplicationHeight.contains(
          applied.candidateHeight,
        ) && stage.resultDigest.contains(
          applied.resultDigest,
        ) && applied.batchDigest == decision.batchDigest && stage.terminalEvidenceDigest
          .contains(update.evidenceDigest),
        "first exact application facts do not match the canonical batch decision",
      )
      _ <- check(
        payload.terminalResolutions.exists(resolution =>
          resolution.kind == ResolutionKind.Applied && resolution.evidenceDigest == update.evidenceDigest && resolution.applicationBatchDigest
            .contains(
              decision.batchDigest,
            ) && resolution.resolvedHeight == applied.candidateHeight,
        ),
        "exact inclusion lacks its actual batch finality resolution",
      )
      _ <- check(
        applied.context == record.binding.context && applied.candidateHeight.toBigNat.toBigInt <= record.request.signedPlan.intent.lastInclusionHeight.toBigNat.toBigInt,
        "exact application differs from its signed domain or inclusive deadline",
      )
    yield ()

  private def expired(
      record: ExactPipelineRecord,
      stage: ExactStageRecord,
      update: ExactRecordUpdate,
      payload: JournalPayload,
      before: SafetyState,
  ): Either[V2RuntimeFailure, Unit] =
    check(
      !before.appliedEntries.contains(
        stage.executionId,
      ) && stage.firstApplicationBlock.isEmpty && stage.terminalEvidenceDigest
        .contains(update.evidenceDigest) && payload.terminalResolutions.exists(
        resolution =>
          resolution.kind == ResolutionKind.ExpiredUnapplied && resolution.evidenceDigest == update.evidenceDigest && resolution.applicationBatchDigest.isEmpty && resolution.resolvedHeight.toBigNat.toBigInt > record.request.signedPlan.intent.lastInclusionHeight.toBigNat.toBigInt,
      ),
      "exact expiry requires same-operation nonapplication evidence strictly after its signed deadline",
    )

  private def atomicMode(
      previous: ExactPipelineRecord,
      next: ExactPipelineRecord,
      payload: JournalPayload,
      state: SafetyState,
  ): Either[V2RuntimeFailure, Unit] =
    for
      decision <- payload.applicationDecision.toRight(
        V2RuntimeFailure.at(
          RuntimeFailureCode.JournalCorrupt,
          "exact application update lacks decision",
        ),
      )
      prepared <- state.preparations
        .get(decision.batchDigest)
        .toRight(
          V2RuntimeFailure.at(
            RuntimeFailureCode.JournalCorrupt,
            "exact application update lacks prepared batch",
          ),
        )
      selected = prepared.batch.entries.map(_.executionId)
      included = next.stages
        .filter(stage => stage.firstApplicationBlock.contains(decision.blockId))
        .map(_.executionId)
      _ <- check(
        included.nonEmpty,
        "exact application update changes an unrelated pipeline",
      )
      _ <- previous.request.signedPlan.intent.mode match
        case ExactMode.OrderedAtomic =>
          val producerPosition =
            previous.binding.executionIds.headOption.fold(-1)(selected.indexOf)
          check(
            included == previous.binding.executionIds && producerPosition >= 0 && selected
              .slice(
                producerPosition,
                producerPosition + 2,
              ) == previous.binding.executionIds,
            "ordered exact application omitted or reordered one atomic stage",
          )
        case ExactMode.CertifiedAncestor =>
          val consumer         = previous.stages.lift(1)
          val applyingConsumer =
            consumer.exists(stage => included.contains(stage.executionId))
          check(
            !applyingConsumer || previous.stages.headOption.exists(stage =>
              stage.firstApplicationBlock.nonEmpty && stage.firstApplicationHeight
                .exists(height =>
                  height.toBigNat.toBigInt < prepared.batch.candidateHeight.toBigNat.toBigInt,
                ),
            ),
            "canonical exact consumer has no previously applied ancestor producer",
          )
    yield ()

  private def requiredTerminalProjection(
      before: Map[String, ExactPipelineRecord],
      after: Map[String, ExactPipelineRecord],
      changed: Set[String],
      payload: JournalPayload,
      operation: JournalOperation,
      state: SafetyState,
  ): Either[V2RuntimeFailure, Unit] =
    val appliedExecutions = payload.applicationDecision.toList
      .flatMap(decision =>
        state.preparations
          .get(decision.batchDigest)
          .toList
          .flatMap(_.batch.entries.map(_.executionId)),
      )
      .toSet
    val expiredExecutions = if operation == JournalOperation.Expiry then
      payload.claims.map(_.owner.executionId).toSet ++ payload.locks.map(
        _.executionId,
      )
    else Set.empty[ExecutionId]
    before.toVector.traverse_((id, record) =>
      record.stages.traverse_(stage =>
        if appliedExecutions.contains(stage.executionId) || expiredExecutions
            .contains(stage.executionId)
        then
          check(
            changed.contains(id) && after
              .get(id)
              .exists(
                _.stages.exists(next =>
                  next.executionId == stage.executionId &&
                    (if operation == JournalOperation.ApplicationCommit then
                       next.firstApplicationBlock.nonEmpty && Set(
                         ExactStageLifecycle.Included,
                         ExactStageLifecycle.Finalized,
                         ExactStageLifecycle.Materialized,
                       ).contains(next.lifecycle)
                     else
                       next.lifecycle == ExactStageLifecycle.ExpiredUnapplied),
                ),
              ),
            "canonical application/expiry omitted its registered exact lifecycle update",
          )
        else Right[V2RuntimeFailure, Unit](()),
      ),
    )
