package org.sigilaris.node.jvm.runtime.application.v2

import cats.syntax.all.*

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.datatype.UInt256

/** The same checked projection is used before durable writes and on restart. */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
private[v2] object SafetyJournalReduction:
  val zero: Hash = UInt256.unsafeFromBigIntUnsigned(BigInt(0))

  def head(state: SafetyState): Either[V2RuntimeFailure, Hash] =
    state.committed.lastOption
      .traverse(record => RuntimeCheck.core(JournalRecord.digest(record)))
      .map(_.getOrElse(zero))

  private def check(
      condition: Boolean,
      detail: String,
  ): Either[V2RuntimeFailure, Unit] =
    RuntimeCheck.require(condition, RuntimeFailureCode.JournalCorrupt, detail)

  def apply(
      state: SafetyState,
      record: JournalRecord,
      anchor: ApplicationAnchor,
  ): Either[V2RuntimeFailure, SafetyState] =
    for
      _        <- RuntimeCheck.core(JournalRecord.validate(record))
      previous <- head(state)
      _        <- check(
        state.sequence < Long.MaxValue && record.sequence == state.sequence + 1L &&
          record.previousDigest == previous && record.status == JournalStatus.Committed,
        "journal projection sequence, status or predecessor mismatch",
      )
      payload <- RuntimeCheck.core(JournalPayload.codec.decode(record.payload))
      _       <- check(
        payload.intents.forall(_.context == anchor.context) && payload.locks
          .forall(_.context == anchor.context) &&
          payload.claims.forall(
            _.owner.context == anchor.context,
          ) && payload.consensusIntent.forall(_.context == anchor.context) &&
          payload.applicationPreparation.forall(
            _.batch.context == anchor.context,
          ) && payload.importedCertificates.forall {
            case ImportedCertificate.Lock(value) =>
              value.subject.context == anchor.context
            case ImportedCertificate.Effect(value) =>
              value.subject.context == anchor.context
          },
        "journal crosses the installed application context",
      )
      exactTerminals <- (payload.locks.map(_.executionId) ++ payload.claims.map(
        _.owner.executionId,
      )).distinct
        .traverse(execution =>
          ExactTerminalEvidence
            .resolution(state, anchor.context, execution)
            .map(execution -> _),
        )
        .map(_.toMap)
      _ <- payload.locks
        .traverse_(next =>
          state.locks
            .get(next.executionId)
            .fold(
              check(
                (next.lifecycle == ClaimLifecycle.Live && !state.appliedEntries
                  .contains(next.executionId)) ||
                  (record.operation == JournalOperation.CertificateImport &&
                    next.lifecycle != ClaimLifecycle.Live &&
                    (state.claims.valuesIterator.exists(claim =>
                      claim.owner.context == next.context && claim.owner.executionId == next.executionId &&
                        claim.lastInclusionHeight == next.lastInclusionHeight && claim.lifecycle == next.lifecycle &&
                        claim.terminal == next.terminal,
                    ) || (next.lifecycle == ClaimLifecycle.ExpiredUnapplied && exactTerminals
                      .get(next.executionId)
                      .flatten
                      .exists(resolution =>
                        next.terminal.contains(resolution),
                      )))),
                "terminal lock has no retained subject or matching terminal execution evidence",
              ),
            )(old =>
              check(
                old.copy(
                  lifecycle = next.lifecycle,
                  terminal = next.terminal,
                ) == next &&
                  (old.lifecycle == ClaimLifecycle.Live || old == next) &&
                  (terminalOperation(record.operation) || old == next),
                "lock subject changed or terminal lock was resurrected",
              ),
            ),
        )
      _ <- payload.locks.traverse_(next =>
        check(
          next.lifecycle != ClaimLifecycle.Live || (exactTerminals
            .get(next.executionId)
            .flatten
            .isEmpty && !terminalExecution(
            state,
            next.executionId,
          )),
          "a terminal execution cannot acquire another live eligible claim",
        ),
      )
      ownerPairs <- payload.claims.traverse(claim =>
        RuntimeCheck.core(Owner.digest(claim.owner)).map(_ -> claim),
      )
      _ <- ownerPairs.traverse_((id, next) =>
        state.claims
          .get(id)
          .fold(
            check(
              next.lifecycle == ClaimLifecycle.Live && !state.appliedEntries
                .contains(next.owner.executionId),
              "terminal owner has no retained reservation",
            ),
          )(old =>
            check(
              old.copy(
                lifecycle = next.lifecycle,
                terminal = next.terminal,
              ) == next &&
                (old.lifecycle == ClaimLifecycle.Live || old == next) &&
                (terminalOperation(record.operation) || old == next),
              "reservation witness/deadline changed or owner was resurrected",
            ),
          ),
      )
      _ <- ownerPairs.traverse_((_, next) =>
        check(
          next.lifecycle != ClaimLifecycle.Live || (exactTerminals
            .get(next.owner.executionId)
            .flatten
            .isEmpty && !terminalExecution(
            state,
            next.owner.executionId,
          )),
          "a terminal execution cannot acquire another live owner scope",
        ),
      )
      _ <- payload.witnesses.traverse_(ref =>
        check(
          state.witnesses.get(ref.witnessDigest).forall(_ == ref),
          "witness metadata changed",
        ),
      )
      refs = state.witnesses ++ payload.witnesses.map(ref =>
        ref.witnessDigest -> ref,
      )
      _ <- ownerPairs.traverse_((_, claim) =>
        check(
          refs.get(claim.witness.witnessDigest).contains(claim.witness),
          "owner has no complete retained witness reference",
        ),
      )
      _ <- check(
        record.operation != JournalOperation.Expiry ||
          (payload.claims.forall(claim =>
            !state.appliedEntries.contains(claim.owner.executionId),
          ) &&
            payload.locks.forall(lock =>
              !state.appliedEntries.contains(lock.executionId),
            )),
        "nonapplication expiry targets an already applied execution",
      )
      locks = state.locks ++ payload.locks.map(lock => lock.executionId -> lock)
      claims = state.claims ++ ownerPairs
      intentPairs <- payload.intents.traverse(intent =>
        RuntimeCheck.core(VoteIntent.digest(intent)).map(_ -> intent),
      )
      _ <- payload.intents.traverse_(intent =>
        val matching = state.intents.valuesIterator
          .filter(old =>
            old.context == intent.context &&
              old.validatorId == intent.validatorId && old.kind == intent.kind && old.executionId == intent.executionId,
          )
          .toVector
        for
          _ <- check(
            matching.forall(_ == intent),
            "an execution has incompatible durable vote subjects",
          )
          _ <- intent.kind match
            case VoteIntentKind.Lock =>
              check(
                locks
                  .get(intent.executionId)
                  .exists(lock =>
                    lock.context == intent.context && lock.subjectDigest == intent.subjectDigest && lock.lastInclusionHeight == intent.lastInclusionHeight,
                  ),
                "lock intent has no matching retained eligible claim",
              )
            case VoteIntentKind.Effect =>
              intent.owner
                .toRight(
                  V2RuntimeFailure.at(
                    RuntimeFailureCode.JournalCorrupt,
                    "effect intent has no owner",
                  ),
                )
                .flatMap(owner =>
                  RuntimeCheck
                    .core(Owner.digest(owner))
                    .flatMap(id =>
                      check(
                        claims
                          .get(id)
                          .exists(claim =>
                            intent.witness.contains(
                              claim.witness,
                            ) && claim.lastInclusionHeight == intent.lastInclusionHeight,
                          ),
                        "effect intent has no complete matching reservation",
                      ),
                    ),
                )
        yield (),
      )
      consensusPair <- payload.consensusIntent.traverse(intent =>
        for
          fields    <- ConsensusSignFields.validateIntent(intent)
          oldFields <- state.consensusIntents.values.toVector
            .traverse(ConsensusSignFields.validateIntent)
          _ <- check(
            !oldFields.exists(old =>
              old.equivocationKey == fields.equivocationKey && old != fields,
            ),
            "conflicting HotStuff vote subjects share one durable equivocation key",
          )
          _ <- check(
            intent.ownerDigests.forall(claims.contains),
            "consensus intent omits its authoritative reservation",
          )
          id <- RuntimeCheck.core(ConsensusVoteIntent.digest(intent))
        yield id -> intent,
      )
      afterVotes = state.copy(
        intents = state.intents ++ intentPairs,
        consensusIntents = state.consensusIntents ++ consensusPair,
        locks = locks,
        claims = claims,
        witnesses = refs,
      )
      _        <- validateExecutionDeadlines(afterVotes)
      prepared <- payload.applicationPreparation
        .fold[Either[V2RuntimeFailure, SafetyState]](Right(afterVotes))(next =>
          for
            _ <- check(
              afterVotes.preparations.get(next.batchDigest).forall(_ == next),
              "application preparation was rebound",
            )
            _ <- next.batch.entries.zipWithIndex.traverse_((entry, index) =>
              check(
                claims
                  .get(entry.ownerDigest)
                  .exists(claim =>
                    claim.owner.executionId == entry.executionId && claim.owner.context == next.batch.context &&
                      claim.lastInclusionHeight == entry.lastInclusionHeight && claim.lifecycle == ClaimLifecycle.Live &&
                      claim.owner.scope.kind != ScopeKind.FastAdmission && claim.owner.scope.parentBlockId == next.batch.parentBlockId &&
                      claim.owner.scope.candidateHeight == next.batch.candidateHeight && claim.owner.scope.planRoot == next.batch.planRoot &&
                      claim.owner.scope.entryIndex == index.toLong,
                  ),
                "application preparation lacks a live complete reservation",
              ),
            )
          yield afterVotes.copy(preparations =
            afterVotes.preparations.updated(next.batchDigest, next),
          ),
        )
      applied <- payload.applicationDecision
        .fold[Either[V2RuntimeFailure, SafetyState]](Right(prepared))(
          decision => applyDecision(state, prepared, payload, decision, anchor),
        )
      imported <- payload.importedCertificates
        .foldLeft[Either[V2RuntimeFailure, SafetyState]](Right(applied))(
          (acc, certificate) =>
            acc.flatMap(current =>
              certificate match
                case ImportedCertificate.Lock(value) =>
                  validateImportedLock(current, value).flatMap(_ =>
                    RuntimeCheck
                      .core(LockCertificate.id(value))
                      .flatMap(id =>
                        check(
                          current.lockCertificates.get(id).forall(_ == value),
                          "imported lock certificate changed",
                        ).map(_ =>
                          current.copy(lockCertificates =
                            current.lockCertificates.updated(id, value),
                          ),
                        ),
                      ),
                  )
                case ImportedCertificate.Effect(value) =>
                  validateImportedEffect(current, value).flatMap(_ =>
                    RuntimeCheck
                      .core(EffectCertificate.id(value))
                      .flatMap(id =>
                        check(
                          current.effectCertificates.get(id).forall(_ == value),
                          "imported effect certificate changed",
                        ).map(_ =>
                          current.copy(effectCertificates =
                            current.effectCertificates.updated(id, value),
                          ),
                        ),
                      ),
                  ),
            ),
        )
      exact <- ExactSafetyJournalReduction(
        state,
        imported,
        record,
        payload,
        anchor.context,
      )
      transitioned <- TransitionJournalReduction(state, exact, record, anchor)
    yield transitioned.copy(committed = state.committed :+ record)

  private def terminalOperation(operation: JournalOperation): Boolean =
    operation == JournalOperation.ApplicationCommit || operation == JournalOperation.Expiry

  private def terminalExecution(
      state: SafetyState,
      execution: org.sigilaris.core.application.protocol.ExecutionId,
  ): Boolean =
    state.appliedEntries.contains(execution) || state.locks
      .get(execution)
      .exists(_.lifecycle != ClaimLifecycle.Live) ||
      state.claims.valuesIterator.exists(claim =>
        claim.owner.executionId == execution && claim.lifecycle != ClaimLifecycle.Live,
      )

  private def validateExecutionDeadlines(
      state: SafetyState,
  ): Either[V2RuntimeFailure, Unit] =
    val values = state.claims.valuesIterator
      .map(claim => claim.owner.executionId -> claim.lastInclusionHeight)
      .toVector ++
      state.locks.valuesIterator
        .map(claim => claim.executionId -> claim.lastInclusionHeight)
        .toVector ++
      state.intents.valuesIterator
        .map(intent => intent.executionId -> intent.lastInclusionHeight)
        .toVector
    check(
      values
        .groupBy(_._1)
        .valuesIterator
        .forall(_.map(_._2).distinct.sizeCompare(1) == 0),
      "one signed execution has inconsistent deadlines across retained owners, locks or intents",
    )

  private def validateImportedLock(
      state: SafetyState,
      value: LockCertificate,
  ): Either[V2RuntimeFailure, Unit] =
    for
      bytes <- RuntimeCheck.core(LockSubject.codec.encode(value.subject))
      digest = Commitment.hash(LockSubject.Domain, bytes)
      _ <- check(
        state.locks
          .get(value.subject.executionId)
          .exists(claim =>
            claim.context == value.subject.context && claim.subjectDigest == digest && claim.inputIds == value.subject.inputs &&
              claim.lastInclusionHeight == value.subject.lastInclusionHeight,
          ),
        "imported lock certificate has no matching retained claim",
      )
      _ <- check(
        state.intents.valuesIterator
          .filter(intent =>
            intent.kind == VoteIntentKind.Lock && intent.context == value.subject.context &&
              intent.executionId == value.subject.executionId,
          )
          .forall(_.subject == bytes),
        "imported lock contradicts retained local vote subject",
      )
      _ <- check(
        state.lockCertificates.valuesIterator
          .filter(old =>
            old.subject.context == value.subject.context &&
              old.subject.executionId == value.subject.executionId,
          )
          .forall(_.subject == value.subject),
        "imported lock contradicts retained certificate subject",
      )
    yield ()

  private def validateImportedEffect(
      state: SafetyState,
      value: EffectCertificate,
  ): Either[V2RuntimeFailure, Unit] =
    for
      lock <- state.lockCertificates
        .get(value.subject.inputLockCertificateId)
        .toRight(
          V2RuntimeFailure.at(
            RuntimeFailureCode.JournalCorrupt,
            "imported effect lacks its complete retained lock certificate",
          ),
        )
      _ <- validateImportedLock(state, lock)
      subject = value.subject
      _ <- check(
        subject.context == lock.subject.context && subject.txId == lock.subject.txId && subject.executionId == lock.subject.executionId &&
          subject.manifestDigest == lock.subject.manifestDigest && subject.dependencyPlanDigest == lock.subject.dependencyPlanDigest &&
          subject.lastInclusionHeight == lock.subject.lastInclusionHeight,
        "imported effect differs from its complete referenced lock subject",
      )
      bytes <- RuntimeCheck.core(EffectSubject.codec.encode(subject))
      _     <- check(
        state.intents.valuesIterator
          .filter(intent =>
            intent.kind == VoteIntentKind.Effect && intent.context == subject.context &&
              intent.executionId == subject.executionId,
          )
          .forall(_.subject == bytes),
        "imported effect contradicts retained local vote subject",
      )
      _ <- check(
        state.effectCertificates.valuesIterator
          .filter(old =>
            old.subject.context == subject.context &&
              old.subject.executionId == subject.executionId,
          )
          .forall(_.subject == subject),
        "imported effect contradicts retained certificate subject",
      )
      _ <- state.appliedEntries
        .get(subject.executionId)
        .traverse_(applied =>
          for _ <- check(
              applied.context == subject.context && applied.resultDigest == subject.resultDigest.toUInt256,
              "imported effect result contradicts canonical application",
            )
          yield (),
        )
    yield ()

  private def applyDecision(
      before: SafetyState,
      state: SafetyState,
      payload: JournalPayload,
      decision: ApplicationDecision,
      anchor: ApplicationAnchor,
  ): Either[V2RuntimeFailure, SafetyState] =
    for
      prepared <- state.preparations
        .get(decision.batchDigest)
        .toRight(
          V2RuntimeFailure.at(
            RuntimeFailureCode.JournalCorrupt,
            "application decision lacks retained preparation",
          ),
        )
      batch = prepared.batch
      prior <- state.canonical.traverse(value =>
        state.preparations
          .get(value.batchDigest)
          .toRight(
            V2RuntimeFailure.at(
              RuntimeFailureCode.JournalCorrupt,
              "canonical decision lacks its retained batch",
            ),
          ),
      )
      currentParent = state.canonical.fold(anchor.blockId)(_.blockId)
      currentHeight = prior.fold(anchor.height)(_.batch.candidateHeight)
      currentRoot   = prior.fold(anchor.stateRoot)(_.batch.nextStateRoot)
      _ <- check(
        batch.parentBlockId == currentParent && batch.priorStateRoot == currentRoot &&
          batch.candidateHeight.toBigNat.toBigInt == currentHeight.toBigNat.toBigInt + 1,
        "application decision does not extend the complete canonical parent state",
      )
      executions     = batch.entries.map(_.executionId).toSet
      selectedOwners = batch.entries.map(_.ownerDigest)
      selectedSet    = selectedOwners.toSet
      extraOwners    = before.claims.toVector
        .collect {
          case (id, claim)
              if claim.lifecycle == ClaimLifecycle.Live && executions.contains(
                claim.owner.executionId,
              ) && !selectedSet.contains(id) =>
            id
        }
        .sortBy(_.bytes.toHex)
      expectedOwners = selectedOwners ++ extraOwners
      _ <- check(
        decision.terminalOwners == expectedOwners,
        "application decision must resolve selected owners and every additional live owner of its executions",
      )
      changedOwners <- payload.claims.traverse(claim =>
        RuntimeCheck.core(Owner.digest(claim.owner)),
      )
      _ <- check(
        changedOwners.toSet == expectedOwners.toSet,
        "application decision resolves an unrelated owner or omits a live owner",
      )
      expectedLocks = before.locks.valuesIterator
        .filter(lock =>
          lock.lifecycle == ClaimLifecycle.Live && executions.contains(
            lock.executionId,
          ),
        )
        .map(_.executionId)
        .toSet
      _ <- check(
        payload.locks.map(_.executionId).toSet == expectedLocks,
        "application decision resolves an unrelated lock or omits a live eligible lock",
      )
      _ <- check(
        payload.terminalResolutions
          .map(_.evidenceDigest)
          .distinct
          .sizeCompare(1) <= 0,
        "one application decision has inconsistent application evidence",
      )
      indexes = batch.entries.map(entry =>
        AppliedIndexRecord(
          2L,
          batch.context,
          entry.executionId,
          decision.batchDigest,
          decision.blockId,
          batch.candidateHeight,
          entry.resultDigest,
        ),
      )
      _ <- indexes.traverse_(entry =>
        check(
          !state.appliedEntries.contains(entry.executionId),
          "execution was already applied by another decision",
        ),
      )
      _ <- expectedOwners.traverse_(ownerId =>
        check(
          state.claims
            .get(ownerId)
            .exists(claim =>
              executions.contains(
                claim.owner.executionId,
              ) && claim.lifecycle == ClaimLifecycle.Applied && claim.terminal
                .exists(resolution =>
                  resolution.kind == ResolutionKind.Applied &&
                    resolution.applicationBatchDigest.contains(
                      decision.batchDigest,
                    ) && resolution.resolvedHeight == batch.candidateHeight,
                ),
            ),
          "application decision did not atomically resolve its owner",
        ),
      )
      _ <- payload.locks.traverse_(lock =>
        check(
          lock.lifecycle == ClaimLifecycle.Applied && lock.terminal.exists(
            resolution =>
              resolution.kind == ResolutionKind.Applied && resolution.applicationBatchDigest
                .contains(decision.batchDigest) &&
                resolution.resolvedHeight == batch.candidateHeight,
          ),
          "application decision did not atomically resolve its eligible lock",
        ),
      )
    yield state.copy(
      decisions = state.decisions.updated(decision.batchDigest, decision),
      appliedEntries = state.appliedEntries ++ indexes.map(value =>
        value.executionId -> value,
      ),
      canonical = Some(decision),
    )

  def index(
      state: SafetyState,
      witnesses: Map[Hash, ReservationWitness],
  ): Either[V2RuntimeFailure, Vector[ConflictIndexRow]] =
    for
      coverage <- state.claims.toVector
        .filter(_._2.lifecycle == ClaimLifecycle.Live)
        .traverse((ownerId, claim) =>
          for
            witness <- witnesses
              .get(claim.witness.witnessDigest)
              .toRight(
                V2RuntimeFailure.at(
                  RuntimeFailureCode.IncompleteWitness,
                  "live owner witness is unavailable during index reconstruction",
                ),
              )
            _ <- RuntimeCheck.core(WitnessRef.verify(claim.witness, witness))
            digest <- RuntimeCheck.core(ReservationClaim.digest(claim))
          yield witness.entries.map(entry =>
            entry.identity -> IndexedOwner(
              ownerId,
              digest,
              entry.access,
              claim.lastInclusionHeight,
            ),
          ),
        )
      rows = coverage.flatten
        .groupBy(_._1)
        .toVector
        .map((identity, owners) =>
          ConflictIndexRow(
            2L,
            identity,
            owners.map(_._2).sortBy(_.ownerDigest.bytes.toHex),
          ),
        )
        .sortBy(_.identity.toHex)
      _ <- RuntimeCheck.core(ConflictIndexRow.inventoryDigest(rows))
    yield rows
