package org.sigilaris.node.jvm.runtime.application

import cats.Monad
import cats.data.EitherT
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.*
import org.sigilaris.core.datatype.UInt256
import org.sigilaris.node.jvm.runtime.txpipeline.ExactTxPipelineStoreValidation
import org.sigilaris.node.txpipeline.{
  ApplicationProtocolManifestV1,
  ExactExecutionMode,
  ExactPipelineCanonical,
  ExactPipelineLifecycle,
  ExactTxPipelineRecord,
  TxPipelineId,
}

final case class ExactCertifiedApplication(
    certificate: CertifiedEffectCertificate,
    candidateHeight: InclusionHeight,
    blockId: UInt256,
)

trait ApplicationLockCertificateAuthenticator:
  def verify(
      certificate: ApplicationLockCertificate,
  ): Either[ApplicationSafetyRuntimeFailure, Unit]

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Nothing",
  ),
)
object ApplicationLockCertificateAuthenticator:
  def historical(
      validatorSet: HistoricalApplicationValidatorSet,
  )(
      verifySignature: (
          ApplicationValidatorId,
          ByteVector,
          ByteVector,
      ) => Boolean,
  ): ApplicationLockCertificateAuthenticator =
    new ApplicationLockCertificateAuthenticator:
      override def verify(
          certificate: ApplicationLockCertificate,
      ): Either[ApplicationSafetyRuntimeFailure, Unit] =
        ApplicationLockCertificate
          .verify(certificate, validatorSet)(verifySignature)
          .left
          .map(_ =>
            ApplicationSafetyRuntimeFailure(
              "lockCertificateInvalid",
              "historical validator-set quorum verification failed",
            ),
          )

trait ApplicationEffectCertificateAuthenticator:
  def verify(
      certificate: CertifiedEffectCertificate,
  ): Either[ApplicationSafetyRuntimeFailure, Unit]

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Nothing",
  ),
)
object ApplicationEffectCertificateAuthenticator:
  def historical(
      validatorSet: HistoricalApplicationValidatorSet,
  )(
      verifySignature: (
          ApplicationValidatorId,
          ByteVector,
          ByteVector,
      ) => Boolean,
  ): ApplicationEffectCertificateAuthenticator =
    new ApplicationEffectCertificateAuthenticator:
      override def verify(
          certificate: CertifiedEffectCertificate,
      ): Either[ApplicationSafetyRuntimeFailure, Unit] =
        CertifiedEffectCertificate
          .verify(certificate, validatorSet)(verifySignature)
          .left
          .map(_ =>
            ApplicationSafetyRuntimeFailure(
              "effectCertificateInvalid",
              "historical validator-set quorum verification failed",
            ),
          )

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
  ),
)
final class ApplicationSafetyRuntime[F[_]: Monad](
    store: ApplicationSafetyStore[F],
    lockCertificateAuthenticator: ApplicationLockCertificateAuthenticator,
    effectCertificateAuthenticator: ApplicationEffectCertificateAuthenticator,
):
  def snapshot: F[ApplicationSafetySnapshot] = store.snapshot

  def diagnostics: F[ApplicationSafetyDiagnostics] =
    snapshot.map(ApplicationSafetyDiagnostics.fromSnapshot)

  def registerExactPipeline(
      record: ExactTxPipelineRecord,
  ): EitherT[F, ApplicationSafetyRuntimeFailure, ExactTxPipelineRecord] =
    persistExactPipeline(
      record,
      requireOpenAdmission = true,
      operation = "registerExactPipeline",
    )

  /** Journals an exact row whose admission store write has already committed.
    *
    * The transport and startup reconciler use this boundary to close the
    * drain-check/store-write race. A closed drain rejects new admission before
    * persistence, but must not orphan work that durably crossed that boundary
    * immediately before the close.
    */
  private[jvm] def journalAdmittedExactPipeline(
      record: ExactTxPipelineRecord,
  ): EitherT[F, ApplicationSafetyRuntimeFailure, ExactTxPipelineRecord] =
    persistExactPipeline(
      record,
      requireOpenAdmission = false,
      operation = "recoverAdmittedExactPipeline",
    )

  private def persistExactPipeline(
      record: ExactTxPipelineRecord,
      requireOpenAdmission: Boolean,
      operation: String,
  ): EitherT[F, ApplicationSafetyRuntimeFailure, ExactTxPipelineRecord] =
    store.transact(operation): snapshot =>
      for
        _ <- ExactTxPipelineStoreValidation
          .validateDescriptor(record)
          .leftMap(value =>
            failure(value.reason, "exact pipeline descriptor is invalid"),
          )
        existing = snapshot.exactPipelines.get(
          record.genericProjection.pipelineId,
        )
        _ <- Either.cond(
          existing.forall(
            ExactTxPipelineStoreValidation.sameSubmission(_, record),
          ),
          (),
          failure(
            "exactPipelineBindingMismatch",
            "application safety store contains a different exact descriptor",
          ),
        )
        _ <- existing.fold(
          Either.cond(
            !requireOpenAdmission ||
              snapshot.drain.phase == ApplicationDrainPhase.Open,
            (),
            failure(
              "applicationAdmissionClosed",
              "application admission is not open",
            ),
          ),
        )(_ => Right(()))
        effective = existing.getOrElse(record)
        _ <-
          if existing.exists(value =>
              ExactPipelineLifecycle.isTerminal(value.lifecycle),
            )
          then Right(())
          else validateExactProfile(snapshot, record)
        nextWatermark =
          if ExactPipelineLifecycle.isTerminal(effective.lifecycle) then
            snapshot.drain.greatestAdmittedDeadline
          else
            Some(
              maximumHeight(
                snapshot.drain.greatestAdmittedDeadline,
                effective.verifiedPlan.lastInclusionHeight,
              ),
            )
        next = snapshot.copy(
          exactPipelines = existing.fold(
            snapshot.exactPipelines.updated(
              record.genericProjection.pipelineId,
              record,
            ),
          )(_ => snapshot.exactPipelines),
          drain = snapshot.drain.copy(
            greatestAdmittedDeadline = nextWatermark,
          ),
        )
      yield next -> effective

  def markExactLockCertified(
      pipelineId: TxPipelineId,
  ): EitherT[F, ApplicationSafetyRuntimeFailure, ExactTxPipelineRecord] =
    store.transact("markExactLockCertified"): snapshot =>
      for
        record         <- exactPipeline(snapshot, pipelineId)
        expectedDigest <- exactDependencyPlanDigest(record)
        lockRecords    <- record.verifiedPlan.orderedExecutionIds.traverse(
          oneLiveLock(snapshot, _),
        )
        _ <- Either.cond(
          lockRecords.forall(lock =>
            lock.context.dependencyPlanDigest == expectedDigest &&
              lock.lock.lastInclusionHeight ==
              record.verifiedPlan.lastInclusionHeight,
          ),
          (),
          failure(
            "exactPipelineBindingMismatch",
            "live locks do not match the exact plan digest and deadline",
          ),
        )
        updated <- transition(record, ExactPipelineLifecycle.LockCertified)
        next = snapshot.copy(exactPipelines =
          snapshot.exactPipelines.updated(pipelineId, updated),
        )
      yield next -> updated

  def markExactEffectCertified(
      pipelineId: TxPipelineId,
      certificates: Vector[CertifiedEffectCertificate],
  ): EitherT[F, ApplicationSafetyRuntimeFailure, ExactTxPipelineRecord] =
    EitherT
      .fromEither[F](
        certificates.traverse_(effectCertificateAuthenticator.verify),
      )
      .flatMap: _ =>
        store.transact("markExactEffectCertified"): snapshot =>
          for
            record <- exactPipeline(snapshot, pipelineId)
            expectedIds = record.verifiedPlan.mode match
              case ExactExecutionMode.OrderedAtomic =>
                record.verifiedPlan.orderedExecutionIds
              case ExactExecutionMode.CertifiedAncestor =>
                record.verifiedPlan.orderedExecutionIds.take(1)
            _ <- Either.cond(
              certificates.map(_.subject.executionId) == expectedIds,
              (),
              failure(
                "executionOrderMismatch",
                "effect certificates do not match the mode-specific exact placement",
              ),
            )
            _ <- certificates.traverse_(certificate =>
              validateExactSubject(snapshot, pipelineId, certificate.subject) >>
                validateEffect(
                  snapshot,
                  certificate.subject,
                  record.verifiedPlan.lastInclusionHeight,
                ),
            )
            updated <- transition(
              record,
              ExactPipelineLifecycle.EffectCertified,
            )
            next = snapshot.copy(exactPipelines =
              snapshot.exactPipelines.updated(pipelineId, updated),
            )
          yield next -> updated

  def failExactPipeline(
      pipelineId: TxPipelineId,
      cause: ApplicationSafetyRuntimeFailure,
  ): EitherT[F, ApplicationSafetyRuntimeFailure, ExactTxPipelineRecord] =
    updateExactPipelineLifecycle(
      pipelineId,
      ExactPipelineLifecycle.Failed,
      Some(
        org.sigilaris.node.txpipeline.TxPipelineValidationFailure(
          cause.reason,
          cause.detail,
        ),
      ),
    )

  def finalizeExactPipeline(
      pipelineId: TxPipelineId,
  ): EitherT[F, ApplicationSafetyRuntimeFailure, ExactTxPipelineRecord] =
    updateExactPipelineLifecycle(
      pipelineId,
      ExactPipelineLifecycle.Finalized,
      None,
    )

  def materializeExactPipeline(
      pipelineId: TxPipelineId,
  ): EitherT[F, ApplicationSafetyRuntimeFailure, ExactTxPipelineRecord] =
    updateExactPipelineLifecycle(
      pipelineId,
      ExactPipelineLifecycle.Materialized,
      None,
    )

  private def updateExactPipelineLifecycle(
      pipelineId: TxPipelineId,
      lifecycle: ExactPipelineLifecycle,
      terminalFailure: Option[
        org.sigilaris.node.txpipeline.TxPipelineValidationFailure,
      ],
  ): EitherT[F, ApplicationSafetyRuntimeFailure, ExactTxPipelineRecord] =
    store.transact(s"updateExactPipelineLifecycle.${lifecycle.wire}"):
      snapshot =>
        for
          record  <- exactPipeline(snapshot, pipelineId)
          changed <- transition(record, lifecycle)
          updated = changed.copy(terminalFailure =
            changed.terminalFailure.orElse(terminalFailure),
          )
          next = snapshot.copy(exactPipelines =
            snapshot.exactPipelines.updated(pipelineId, updated),
          )
        yield next -> updated

  def recordExactApplicationsAtomically(
      pipelineId: TxPipelineId,
      applications: Vector[ExactCertifiedApplication],
      nextLifecycle: Option[ExactPipelineLifecycle],
  ): EitherT[
    F,
    ApplicationSafetyRuntimeFailure,
    Vector[AppliedExecutionRecord],
  ] =
    EitherT
      .fromEither[F] {
        applications.traverse_(value =>
          effectCertificateAuthenticator.verify(value.certificate),
        )
      }
      .flatMap: _ =>
        store.transact("recordExactApplicationsAtomically"): snapshot =>
          for
            pipeline <- snapshot.exactPipelines
              .get(pipelineId)
              .toRight(
                failure(
                  "exactPipelineBindingMismatch",
                  s"exact pipeline ${pipelineId.value} is not journaled",
                ),
              )
            _ <- validateExactApplicationSet(
              snapshot,
              pipeline,
              applications,
              nextLifecycle,
            )
            accumulated <- applications.foldLeft(
              Right(
                snapshot -> Vector.empty[AppliedExecutionRecord],
              ): Either[
                ApplicationSafetyRuntimeFailure,
                (ApplicationSafetySnapshot, Vector[AppliedExecutionRecord]),
              ],
            ):
              case (Right((current, records)), application) =>
                recordApplicationUpdate(
                  current,
                  application.certificate.subject,
                  application.candidateHeight,
                  application.blockId,
                ).map((next, record) => next -> (records :+ record))
              case (left @ Left(_), _) => left
            (appliedSnapshot, records) = accumulated
            updatedPipeline <- nextLifecycle
              .traverse(value => transitionAfterReplay(pipeline, value))
            next = updatedPipeline.fold(appliedSnapshot)(updated =>
              appliedSnapshot.copy(exactPipelines =
                appliedSnapshot.exactPipelines.updated(pipelineId, updated),
              ),
            )
          yield next -> records

  def validateLockVote(
      subject: ApplicationLockVoteSubject,
      baseHeight: InclusionHeight,
  ): EitherT[F, ApplicationSafetyRuntimeFailure, Unit] =
    inspect { snapshot => validateAdmission(snapshot, subject, baseHeight) }

  def recordLockCertificate(
      certificate: ApplicationLockCertificate,
      baseHeight: InclusionHeight,
  ): EitherT[F, ApplicationSafetyRuntimeFailure, ApplicationLockRecord] =
    val subject = ApplicationLockVoteSubject.canonical(certificate.subject)
    store.transact("recordLockCertificate"): snapshot =>
      for
        _ <- lockCertificateAuthenticator.verify(certificate)
        _ <- validateAdmission(snapshot, subject, baseHeight)
        context = ApplicationArtifactContext.fromLockSubject(subject)
        lock    = ApplicationLock(
          subject.executionId,
          subject.inputIds,
          subject.lastInclusionHeight,
          ApplicationEntryLifecycle.Live,
        )
        record <- ApplicationLockRecord.create(context, lock)
        _      <- ensureExecutionNotTerminal(snapshot, subject.executionId)
        _      <- validateLockConflicts(snapshot, record)
        existing = recordsForExecution(snapshot, subject.executionId)
        _ <- Either.cond(
          existing.isEmpty || existing.forall(_ == record),
          (),
          failure(
            "inputAlreadyLocked",
            "execution already owns a different lock descriptor",
          ),
        )
        greatest = maximumHeight(
          snapshot.drain.greatestAdmittedDeadline,
          subject.lastInclusionHeight,
        )
        next = snapshot.copy(
          locks = snapshot.locks ++ subject.inputIds.map(_ -> record),
          drain = snapshot.drain.copy(
            greatestAdmittedDeadline = Some(greatest),
          ),
        )
      yield next -> record

  def reserveProposal(
      subject: ApplicationLockVoteSubject,
      candidateHeight: InclusionHeight,
  ): EitherT[F, ApplicationSafetyRuntimeFailure, ProposalReservationRecord] =
    val canonical = ApplicationLockVoteSubject.canonical(subject)
    store.transact("reserveProposal"): snapshot =>
      for
        _ <- Either.cond(
          snapshot.drain.phase == ApplicationDrainPhase.Open,
          (),
          failure(
            "applicationAdmissionClosed",
            "new proposal reservations are closed",
          ),
        )
        _ <- validateCandidateHeight(
          candidateHeight,
          canonical.lastInclusionHeight,
        )
        context = ApplicationArtifactContext.fromLockSubject(canonical)
        _ <- validateContext(snapshot, context)
        _ <- ensureExecutionNotTerminal(snapshot, canonical.executionId)
        reservation = ProposalReservation(
          canonical.executionId,
          canonical.inputIds,
          canonical.lastInclusionHeight,
          ApplicationEntryLifecycle.Live,
        )
        record <- ProposalReservationRecord.create(context, reservation)
        _      <- validateReciprocalLocks(snapshot, record)
        _      <- validateReservationConflicts(snapshot, record)
        next = snapshot.copy(
          reservations = snapshot.reservations ++
            canonical.inputIds.map(_ -> record),
        )
      yield next -> record

  def validateProposal(
      executionId: ExecutionId,
      candidateHeight: InclusionHeight,
  ): EitherT[F, ApplicationSafetyRuntimeFailure, Unit] =
    inspect(snapshot =>
      for
        lock <- oneLiveLock(snapshot, executionId)
        _    <- validateCandidateHeight(
          candidateHeight,
          lock.lock.lastInclusionHeight,
        )
        _ <- validateAllReciprocalReservations(snapshot, lock)
      yield (),
    )

  def validateEffectVote(
      subject: CertifiedEffectVoteSubject,
      candidateHeight: InclusionHeight,
  ): EitherT[F, ApplicationSafetyRuntimeFailure, Unit] =
    inspect(snapshot =>
      ensureGenericExecution(snapshot, subject.executionId) >>
        validateEffect(snapshot, subject, candidateHeight),
    )

  def validateExactEffectVote(
      pipelineId: TxPipelineId,
      subject: CertifiedEffectVoteSubject,
      candidateHeight: InclusionHeight,
  ): EitherT[F, ApplicationSafetyRuntimeFailure, Unit] =
    inspect(snapshot =>
      validateExactSubject(snapshot, pipelineId, subject) >>
        validateEffect(snapshot, subject, candidateHeight),
    )

  def recordApplication(
      certificate: CertifiedEffectCertificate,
      candidateHeight: InclusionHeight,
      blockId: UInt256,
  ): EitherT[F, ApplicationSafetyRuntimeFailure, AppliedExecutionRecord] =
    val subject = certificate.subject
    EitherT
      .fromEither[F](effectCertificateAuthenticator.verify(certificate))
      .flatMap(_ =>
        store.transact("recordApplication")(snapshot =>
          ensureGenericExecution(snapshot, subject.executionId).flatMap(_ =>
            recordApplicationUpdate(
              snapshot,
              subject,
              candidateHeight,
              blockId,
            ),
          ),
        ),
      )

  def replayApplication(
      certificate: CertifiedEffectCertificate,
      candidateHeight: InclusionHeight,
      blockId: UInt256,
  ): EitherT[F, ApplicationSafetyRuntimeFailure, AppliedExecutionRecord] =
    recordApplication(certificate, candidateHeight, blockId)

  def expireFinalized(
      finalizedCanonicalHeight: InclusionHeight,
  ): EitherT[F, ApplicationSafetyRuntimeFailure, Vector[
    ApplicationTerminalOutcome,
  ]] =
    store.transact("expireFinalized"): snapshot =>
      val (next, outcomes) = expireFinalizedUpdate(
        snapshot,
        finalizedCanonicalHeight,
      )
      Right(next -> outcomes)

  private def expireFinalizedUpdate(
      snapshot: ApplicationSafetySnapshot,
      finalizedCanonicalHeight: InclusionHeight,
  ): (ApplicationSafetySnapshot, Vector[ApplicationTerminalOutcome]) =
    val eligible = snapshot.locks.valuesIterator
      .filter(_.lock.lifecycle == ApplicationEntryLifecycle.Live)
      .filter(record =>
        height(finalizedCanonicalHeight) > height(
          record.lock.lastInclusionHeight,
        ) &&
          !snapshot.applied.contains(record.lock.executionId),
      )
      .toVector
      .groupBy(_.lock.executionId)
      .valuesIterator
      .flatMap(_.headOption)
      .toVector
      .sortBy(_.lock.executionId.toHexLower)

    val outcomes = eligible.map(record =>
      ApplicationTerminalOutcome.expiredUnapplied(
        record.context,
        record.lock.executionId,
        record.lock.lastInclusionHeight,
        finalizedCanonicalHeight,
      ),
    )
    val expiredIds      = outcomes.iterator.map(_.executionId).toSet
    val finalizedHeight = height(finalizedCanonicalHeight)
    val nextLocks       = snapshot.locks.map:
      case (inputId, record) if expiredIds.contains(record.lock.executionId) =>
        inputId -> record.copy(lock =
          record.lock
            .copy(lifecycle = ApplicationEntryLifecycle.ExpiredUnapplied),
        )
      case entry => entry
    val nextReservations = snapshot.reservations.map:
      case (inputId, record)
          if expiredIds.contains(record.reservation.executionId) =>
        inputId -> record.copy(reservation =
          record.reservation.copy(
            lifecycle = ApplicationEntryLifecycle.ExpiredUnapplied,
          ),
        )
      case entry => entry
    val next = snapshot.copy(
      locks = nextLocks,
      reservations = nextReservations,
      terminal =
        snapshot.terminal ++ outcomes.map(value => value.executionId -> value),
      exactPipelines = snapshot.exactPipelines.map:
        case (pipelineId, record)
            if (
              record.lifecycle == ExactPipelineLifecycle.Accepted ||
                record.lifecycle == ExactPipelineLifecycle.LockCertified ||
                record.lifecycle == ExactPipelineLifecycle.EffectCertified
            ) && (
              record.verifiedPlan.orderedExecutionIds.exists(
                expiredIds.contains,
              ) ||
                finalizedHeight >
                height(record.verifiedPlan.lastInclusionHeight)
            ) =>
          pipelineId -> record.copy(
            lifecycle = ExactPipelineLifecycle.ExpiredUnapplied,
          )
        case entry => entry,
    )
    next -> outcomes

  def closeAdmission
      : EitherT[F, ApplicationSafetyRuntimeFailure, ApplicationDrainState] =
    store.transact("closeAdmission"): snapshot =>
      snapshot.drain.phase match
        case ApplicationDrainPhase.Open =>
          val next = snapshot.drain.copy(phase = ApplicationDrainPhase.Closing)
          Right(snapshot.copy(drain = next) -> next)
        case ApplicationDrainPhase.Closing |
            ApplicationDrainPhase.WaitingFinality =>
          Right(snapshot -> snapshot.drain)
        case other =>
          Left(
            failure(
              "applicationAdmissionClosed",
              s"cannot close admission from ${other.wire}",
            ),
          )

  def advanceDrain(
      finalizedCanonicalHeight: InclusionHeight,
  ): EitherT[F, ApplicationSafetyRuntimeFailure, ApplicationDrainState] =
    store.transact("advanceDrain"): snapshot =>
      snapshot.drain.phase match
        case ApplicationDrainPhase.Closing |
            ApplicationDrainPhase.WaitingFinality =>
          val (expired, _) = expireFinalizedUpdate(
            snapshot,
            finalizedCanonicalHeight,
          )
          val finalityPastGreatest =
            expired.drain.greatestAdmittedDeadline.forall(deadline =>
              height(finalizedCanonicalHeight) > height(deadline),
            )
          val phase =
            if finalityPastGreatest && !expired.hasLiveEntries then
              ApplicationDrainPhase.Ready
            else ApplicationDrainPhase.WaitingFinality
          val next = expired.drain.copy(phase = phase)
          Right(expired.copy(drain = next) -> next)
        case ApplicationDrainPhase.Ready => Right(snapshot -> snapshot.drain)
        case other                       =>
          Left(
            failure(
              "applicationAdmissionClosed",
              s"cannot advance drain from ${other.wire}",
            ),
          )

  def beginActivation
      : EitherT[F, ApplicationSafetyRuntimeFailure, ApplicationDrainState] =
    store.transact("beginActivation"): snapshot =>
      if snapshot.drain.phase != ApplicationDrainPhase.Ready then
        Left(failure("liveEntriesRemain", "drain is not ready for activation"))
      else if snapshot.hasLiveEntries then
        Left(failure("liveEntriesRemain", "live locks or reservations remain"))
      else
        val next = snapshot.drain.copy(phase = ApplicationDrainPhase.Activating)
        Right(snapshot.copy(drain = next) -> next)

  def activateManifest(
      manifest: ApplicationProtocolManifestV1,
  ): EitherT[F, ApplicationSafetyRuntimeFailure, ApplicationDrainState] =
    store.transact("activateManifest"): snapshot =>
      for
        _ <- ApplicationProtocolManifestV1
          .validate(manifest)
          .left
          .map(value =>
            failure(value.reason, "new application manifest is invalid"),
          )
        _ <- Either.cond(
          snapshot.drain.phase == ApplicationDrainPhase.Activating,
          (),
          failure(
            "liveEntriesRemain",
            "configuration activation was not started",
          ),
        )
        _ <- Either.cond(
          !snapshot.hasLiveEntries,
          (),
          failure("liveEntriesRemain", "live locks or reservations remain"),
        )
        _ <- Either.cond(
          manifest.configurationDigest !=
            snapshot.drain.activeManifest.configurationDigest,
          (),
          failure(
            "manifestMismatch",
            "configuration rotation must activate a different manifest digest",
          ),
        )
        next = ApplicationDrainState.open(manifest)
      yield snapshot.copy(drain = next) -> next

  private def recordApplicationUpdate(
      snapshot: ApplicationSafetySnapshot,
      subject: CertifiedEffectVoteSubject,
      candidateHeight: InclusionHeight,
      blockId: UInt256,
  ): Either[
    ApplicationSafetyRuntimeFailure,
    (ApplicationSafetySnapshot, AppliedExecutionRecord),
  ] =
    snapshot.applied.get(subject.executionId) match
      case Some(existing) =>
        val context  = ApplicationArtifactContext.fromEffectSubject(subject)
        val expected = AppliedExecution(
          subject.executionId,
          candidateHeight,
          blockId,
          subject.resultDigest,
          subject.protocolVersion,
        )
        val expectedRecord = AppliedExecutionRecord.create(
          context,
          expected,
          subject.stateRoot,
        )
        val terminalMatches = snapshot.terminal
          .get(subject.executionId)
          .exists(outcome =>
            outcome.context == context &&
              outcome.lastInclusionHeight == subject.lastInclusionHeight &&
              outcome.applied.contains(expected),
          )
        Either.cond(
          existing == expectedRecord && terminalMatches,
          snapshot -> existing,
          failure(
            "appliedExecutionConflict",
            s"execution ${subject.executionId.toHexLower} was already applied differently",
          ),
        )
      case None =>
        for
          _ <- validateEffect(snapshot, subject, candidateHeight)
          context = ApplicationArtifactContext.fromEffectSubject(subject)
          applied = AppliedExecution(
            subject.executionId,
            candidateHeight,
            blockId,
            subject.resultDigest,
            subject.protocolVersion,
          )
          record = AppliedExecutionRecord.create(
            context,
            applied,
            subject.stateRoot,
          )
          terminal = ApplicationTerminalOutcome.applied(
            context,
            applied,
            subject.lastInclusionHeight,
          )
          nextLocks = snapshot.locks.map:
            case (inputId, value)
                if value.lock.executionId == subject.executionId =>
              inputId -> value.copy(lock =
                value.lock.copy(
                  lifecycle = ApplicationEntryLifecycle.Applied,
                ),
              )
            case entry => entry
          nextReservations = snapshot.reservations.map:
            case (inputId, value)
                if value.reservation.executionId == subject.executionId =>
              inputId -> value.copy(reservation =
                value.reservation.copy(
                  lifecycle = ApplicationEntryLifecycle.Applied,
                ),
              )
            case entry => entry
          next = snapshot.copy(
            locks = nextLocks,
            reservations = nextReservations,
            applied = snapshot.applied.updated(subject.executionId, record),
            terminal = snapshot.terminal.updated(subject.executionId, terminal),
          )
        yield next -> record

  private def validateExactProfile(
      snapshot: ApplicationSafetySnapshot,
      record: ExactTxPipelineRecord,
  ): Either[ApplicationSafetyRuntimeFailure, Unit] =
    val plan = record.verifiedPlan
    Either.cond(
      snapshot.drain.activeManifest.profiles.exists(profile =>
        profile.profileId == plan.profileId &&
          profile.profileVersion == plan.profileVersion &&
          profile.verifierSlot == plan.verifierSlot &&
          profile.verifierManifestDigest == plan.verifierManifestDigest,
      ),
      (),
      failure(
        "inactiveDependencyProfile",
        "exact descriptor is not bound to an active manifest profile",
      ),
    )

  private def exactPipeline(
      snapshot: ApplicationSafetySnapshot,
      pipelineId: TxPipelineId,
  ): Either[ApplicationSafetyRuntimeFailure, ExactTxPipelineRecord] =
    snapshot.exactPipelines
      .get(pipelineId)
      .toRight(
        failure(
          "exactPipelineBindingMismatch",
          s"exact pipeline ${pipelineId.value} is not journaled",
        ),
      )

  private def exactDependencyPlanDigest(
      record: ExactTxPipelineRecord,
  ): Either[ApplicationSafetyRuntimeFailure, DependencyPlanDigest] =
    UInt256
      .fromHex(ExactPipelineCanonical.verifiedPlanDigest(record.verifiedPlan))
      .leftMap(error => failure("exactPipelineBindingMismatch", error.toString))
      .map(DependencyPlanDigest(_))

  private def validateExactApplicationSet(
      snapshot: ApplicationSafetySnapshot,
      pipeline: ExactTxPipelineRecord,
      applications: Vector[ExactCertifiedApplication],
      nextLifecycle: Option[ExactPipelineLifecycle],
  ): Either[ApplicationSafetyRuntimeFailure, Unit] =
    val plan        = pipeline.verifiedPlan
    val actualIds   = applications.map(_.certificate.subject.executionId)
    val expectedIds = plan.mode match
      case ExactExecutionMode.OrderedAtomic     => plan.orderedExecutionIds
      case ExactExecutionMode.CertifiedAncestor =>
        nextLifecycle match
          case Some(ExactPipelineLifecycle.Included) =>
            plan.orderedExecutionIds.drop(1)
          case None => plan.orderedExecutionIds.take(1)
          case _    => Vector.empty
    for
      _ <- Either.cond(
        applications.nonEmpty && actualIds == expectedIds,
        (),
        failure(
          "executionOrderMismatch",
          "certified applications do not match the exact execution placement",
        ),
      )
      replayed = applications.forall(application =>
        snapshot.applied.contains(
          application.certificate.subject.executionId,
        ),
      )
      _ <- Either.cond(
        pipeline.lifecycle == ExactPipelineLifecycle.EffectCertified ||
          (replayed && (
            pipeline.lifecycle == ExactPipelineLifecycle.Included ||
              pipeline.lifecycle == ExactPipelineLifecycle.Finalized ||
              pipeline.lifecycle == ExactPipelineLifecycle.Materialized
          )),
        (),
        failure(
          "exactPipelineBindingMismatch",
          "exact application requires effect certification or an exact terminal replay",
        ),
      )
      _ <- Either.cond(
        plan.mode != ExactExecutionMode.CertifiedAncestor ||
          nextLifecycle.isEmpty ||
          plan.orderedExecutionIds.headOption.exists(snapshot.applied.contains),
        (),
        failure(
          "producerResultUnavailable",
          "certified producer must be applied before the consumer",
        ),
      )
      expectedDigest <- UInt256
        .fromHex(ExactPipelineCanonical.verifiedPlanDigest(plan))
        .leftMap(error =>
          failure("exactPipelineBindingMismatch", error.toString),
        )
        .map(DependencyPlanDigest(_))
      _ <- applications.traverse_ { application =>
        val subject = application.certificate.subject
        Either.cond(
          subject.lastInclusionHeight == plan.lastInclusionHeight &&
            subject.dependencyPlanDigest == expectedDigest,
          (),
          failure(
            "exactPipelineBindingMismatch",
            "effect certificate changed the exact plan digest or deadline",
          ),
        )
      }
      _ <- (plan.mode match
        case ExactExecutionMode.OrderedAtomic =>
          Either.cond(
            nextLifecycle.contains(ExactPipelineLifecycle.Included),
            (),
            failure(
              "executionPlanMismatch",
              "ordered-atomic application must include the complete pipeline",
            ),
          )
        case ExactExecutionMode.CertifiedAncestor => Right(())
      )
    yield ()

  private def transitionAfterReplay(
      record: ExactTxPipelineRecord,
      next: ExactPipelineLifecycle,
  ): Either[ApplicationSafetyRuntimeFailure, ExactTxPipelineRecord] =
    if next == ExactPipelineLifecycle.Included && (
        record.lifecycle == ExactPipelineLifecycle.Finalized ||
          record.lifecycle == ExactPipelineLifecycle.Materialized
      )
    then Right(record)
    else transition(record, next)

  private def ensureGenericExecution(
      snapshot: ApplicationSafetySnapshot,
      executionId: ExecutionId,
  ): Either[ApplicationSafetyRuntimeFailure, Unit] =
    Either.cond(
      !snapshot.exactPipelines.valuesIterator.exists(
        _.verifiedPlan.orderedExecutionIds.contains(executionId),
      ),
      (),
      failure(
        "exactPipelineBindingMismatch",
        "exact pipeline execution must use the exact runtime boundary",
      ),
    )

  private def validateExactSubject(
      snapshot: ApplicationSafetySnapshot,
      pipelineId: TxPipelineId,
      subject: CertifiedEffectVoteSubject,
  ): Either[ApplicationSafetyRuntimeFailure, Unit] =
    for
      pipeline <- snapshot.exactPipelines
        .get(pipelineId)
        .toRight(
          failure(
            "exactPipelineBindingMismatch",
            s"exact pipeline ${pipelineId.value} is not journaled",
          ),
        )
      expectedDigest <- UInt256
        .fromHex(
          ExactPipelineCanonical.verifiedPlanDigest(
            pipeline.verifiedPlan,
          ),
        )
        .leftMap(error =>
          failure("exactPipelineBindingMismatch", error.toString),
        )
        .map(DependencyPlanDigest(_))
      _ <- Either.cond(
        pipeline.verifiedPlan.orderedExecutionIds.contains(
          subject.executionId,
        ) &&
          subject.dependencyPlanDigest == expectedDigest &&
          subject.lastInclusionHeight ==
          pipeline.verifiedPlan.lastInclusionHeight,
        (),
        failure(
          "exactPipelineBindingMismatch",
          "effect vote is not owned by the exact pipeline descriptor",
        ),
      )
    yield ()

  private def transition(
      record: ExactTxPipelineRecord,
      next: ExactPipelineLifecycle,
  ): Either[ApplicationSafetyRuntimeFailure, ExactTxPipelineRecord] =
    val current = record.lifecycle
    val allowed = current == next || ((current, next) match
      case (
            ExactPipelineLifecycle.Accepted,
            ExactPipelineLifecycle.LockCertified |
            ExactPipelineLifecycle.Failed |
            ExactPipelineLifecycle.ExpiredUnapplied,
          ) =>
        true
      case (
            ExactPipelineLifecycle.LockCertified,
            ExactPipelineLifecycle.EffectCertified |
            ExactPipelineLifecycle.Failed |
            ExactPipelineLifecycle.ExpiredUnapplied,
          ) =>
        true
      case (
            ExactPipelineLifecycle.EffectCertified,
            ExactPipelineLifecycle.Included | ExactPipelineLifecycle.Failed |
            ExactPipelineLifecycle.ExpiredUnapplied,
          ) =>
        true
      case (
            ExactPipelineLifecycle.Included,
            ExactPipelineLifecycle.Finalized,
          ) =>
        true
      case (
            ExactPipelineLifecycle.Finalized,
            ExactPipelineLifecycle.Materialized,
          ) =>
        true
      case _ => false)
    Either.cond(
      allowed,
      record.copy(lifecycle = next),
      failure(
        "exactPipelineBindingMismatch",
        s"invalid exact lifecycle transition ${current.wire} -> ${next.wire}",
      ),
    )

  private def validateAdmission(
      snapshot: ApplicationSafetySnapshot,
      subject: ApplicationLockVoteSubject,
      baseHeight: InclusionHeight,
  ): Either[ApplicationSafetyRuntimeFailure, Unit] =
    for
      _ <- Either.cond(
        snapshot.drain.phase == ApplicationDrainPhase.Open,
        (),
        failure(
          "applicationAdmissionClosed",
          "application admission is not open",
        ),
      )
      _ <- validateContext(
        snapshot,
        ApplicationArtifactContext.fromLockSubject(subject),
      )
      _ <- validateAdmissionHeight(
        baseHeight,
        subject.lastInclusionHeight,
        snapshot.drain.activeManifest.maxLockLifetimeBlocks,
      )
      _ <- Either.cond(
        subject.inputIds.nonEmpty,
        (),
        failure("inputCommitmentMismatch", "lock inputIds must be non-empty"),
      )
      _ <- Either.cond(
        subject.inputIds == subject.inputIds.sortBy(_.toHex) &&
          subject.inputIds
            .distinctBy(_.toHex)
            .sizeCompare(subject.inputIds.size) == 0,
        (),
        failure(
          "inputCommitmentMismatch",
          "lock inputIds must be unique and canonical",
        ),
      )
      context = ApplicationArtifactContext.fromLockSubject(subject)
      candidate <- ApplicationLockRecord.create(
        context,
        ApplicationLock(
          subject.executionId,
          subject.inputIds,
          subject.lastInclusionHeight,
          ApplicationEntryLifecycle.Live,
        ),
      )
      _ <- ensureExecutionNotTerminal(snapshot, subject.executionId)
      _ <- validateLockConflicts(snapshot, candidate)
      existing = recordsForExecution(snapshot, subject.executionId)
      _ <- Either.cond(
        existing.isEmpty || existing.forall(_ == candidate),
        (),
        failure(
          "inputAlreadyLocked",
          "execution already owns a different lock descriptor",
        ),
      )
    yield ()

  private def validateEffect(
      snapshot: ApplicationSafetySnapshot,
      subject: CertifiedEffectVoteSubject,
      candidateHeight: InclusionHeight,
  ): Either[ApplicationSafetyRuntimeFailure, Unit] =
    for
      _    <- ensureExecutionNotExpired(snapshot, subject.executionId)
      lock <- oneLiveLock(snapshot, subject.executionId)
      context = ApplicationArtifactContext.fromEffectSubject(subject)
      _ <- validateContext(snapshot, context)
      _ <- Either.cond(
        lock.context == context,
        (),
        failure(
          "manifestMismatch",
          "effect context does not match the live lock",
        ),
      )
      _ <- Either.cond(
        lock.lock.lastInclusionHeight == subject.lastInclusionHeight,
        (),
        failure("deadlineMismatch", "effect deadline does not match the lock"),
      )
      _ <- validateCandidateHeight(candidateHeight, subject.lastInclusionHeight)
      _ <- validateAllReciprocalReservations(snapshot, lock)
    yield ()

  private def validateAdmissionHeight(
      baseHeight: InclusionHeight,
      deadline: InclusionHeight,
      maximumLifetime: Long,
  ): Either[ApplicationSafetyRuntimeFailure, Unit] =
    if height(baseHeight) >= height(deadline) then
      Left(
        failure(
          "deadlineNotAfterBase",
          "lastInclusionHeight must be greater than base height",
        ),
      )
    else if height(deadline) > height(baseHeight) + BigInt(maximumLifetime) then
      Left(
        failure(
          "deadlineExceedsMaximum",
          "lastInclusionHeight exceeds the committed maximum",
        ),
      )
    else Right(())

  private def validateCandidateHeight(
      candidateHeight: InclusionHeight,
      deadline: InclusionHeight,
  ): Either[ApplicationSafetyRuntimeFailure, Unit] =
    Either.cond(
      height(candidateHeight) <= height(deadline),
      (),
      failure(
        "deadlineExceeded",
        "candidate height is above lastInclusionHeight",
      ),
    )

  private def validateContext(
      snapshot: ApplicationSafetySnapshot,
      context: ApplicationArtifactContext,
  ): Either[ApplicationSafetyRuntimeFailure, Unit] =
    Either.cond(
      ApplicationArtifactContext.matchesManifest(
        context,
        snapshot.drain.activeManifest,
      ),
      (),
      failure(
        "manifestMismatch",
        "artifact context does not match the active manifest",
      ),
    )

  private def validateLockConflicts(
      snapshot: ApplicationSafetySnapshot,
      candidate: ApplicationLockRecord,
  ): Either[ApplicationSafetyRuntimeFailure, Unit] =
    candidate.lock.inputIds.collectFirst:
      case inputId
          if snapshot.locks
            .get(inputId)
            .exists(existing =>
              existing.lock.lifecycle == ApplicationEntryLifecycle.Live &&
                existing.lock.executionId != candidate.lock.executionId,
            ) =>
        failure(
          "inputAlreadyLocked",
          s"input ${inputId.toHex} is already locked",
        )
    match
      case Some(value) => Left(value)
      case None        => Right(())

  private def validateReciprocalLocks(
      snapshot: ApplicationSafetySnapshot,
      reservation: ProposalReservationRecord,
  ): Either[ApplicationSafetyRuntimeFailure, Unit] =
    reservation.reservation.inputIds.collectFirst:
      case inputId
          if !snapshot.locks
            .get(inputId)
            .exists(lock =>
              lock.lock.lifecycle == ApplicationEntryLifecycle.Live &&
                lock.lock.executionId == reservation.reservation.executionId &&
                lock.context == reservation.context &&
                lock.lock.inputIds == reservation.reservation.inputIds &&
                lock.lock.lastInclusionHeight ==
                reservation.reservation.lastInclusionHeight,
            ) =>
        failure(
          "reservationMissing",
          s"input ${inputId.toHex} has no reciprocal live lock",
        )
    match
      case Some(value) => Left(value)
      case None        => Right(())

  private def validateReservationConflicts(
      snapshot: ApplicationSafetySnapshot,
      candidate: ProposalReservationRecord,
  ): Either[ApplicationSafetyRuntimeFailure, Unit] =
    candidate.reservation.inputIds.collectFirst:
      case inputId
          if snapshot.reservations
            .get(inputId)
            .exists(existing =>
              existing.reservation.lifecycle == ApplicationEntryLifecycle.Live &&
                existing.reservation.executionId != candidate.reservation.executionId,
            ) =>
        failure(
          "reservationConflict",
          s"input ${inputId.toHex} is already reserved",
        )
    match
      case Some(value) => Left(value)
      case None        => Right(())

  private def validateAllReciprocalReservations(
      snapshot: ApplicationSafetySnapshot,
      lock: ApplicationLockRecord,
  ): Either[ApplicationSafetyRuntimeFailure, Unit] =
    lock.lock.inputIds.collectFirst:
      case inputId
          if !snapshot.reservations
            .get(inputId)
            .exists(reservation =>
              reservation.reservation.lifecycle == ApplicationEntryLifecycle.Live &&
                reservation.reservation.executionId == lock.lock.executionId &&
                reservation.context == lock.context &&
                reservation.reservation.inputIds == lock.lock.inputIds &&
                reservation.reservation.lastInclusionHeight ==
                lock.lock.lastInclusionHeight,
            ) =>
        failure(
          "reservationMissing",
          s"input ${inputId.toHex} has no reciprocal reservation",
        )
    match
      case Some(value) => Left(value)
      case None        => Right(())

  private def oneLiveLock(
      snapshot: ApplicationSafetySnapshot,
      executionId: ExecutionId,
  ): Either[ApplicationSafetyRuntimeFailure, ApplicationLockRecord] =
    recordsForExecution(snapshot, executionId)
      .find(_.lock.lifecycle == ApplicationEntryLifecycle.Live)
      .toRight(
        failure(
          "lockCertificateInvalid",
          s"execution ${executionId.toHexLower} has no live lock",
        ),
      )

  private def recordsForExecution(
      snapshot: ApplicationSafetySnapshot,
      executionId: ExecutionId,
  ): Vector[ApplicationLockRecord] =
    snapshot.locks.valuesIterator
      .filter(_.lock.executionId == executionId)
      .toVector
      .distinct

  private def ensureExecutionNotTerminal(
      snapshot: ApplicationSafetySnapshot,
      executionId: ExecutionId,
  ): Either[ApplicationSafetyRuntimeFailure, Unit] =
    snapshot.terminal.get(executionId) match
      case Some(value)
          if value.lifecycle == ApplicationEntryLifecycle.ExpiredUnapplied =>
        Left(
          failure(
            "expiredExecution",
            "expired execution cannot be admitted again",
          ),
        )
      case Some(_) =>
        Left(
          failure(
            "appliedExecutionConflict",
            "applied execution cannot acquire a new lock",
          ),
        )
      case None => Right(())

  private def ensureExecutionNotExpired(
      snapshot: ApplicationSafetySnapshot,
      executionId: ExecutionId,
  ): Either[ApplicationSafetyRuntimeFailure, Unit] =
    Either.cond(
      !snapshot.terminal
        .get(executionId)
        .exists(
          _.lifecycle == ApplicationEntryLifecycle.ExpiredUnapplied,
        ),
      (),
      failure("expiredExecution", "execution expired unapplied"),
    )

  private def maximumHeight(
      current: Option[InclusionHeight],
      candidate: InclusionHeight,
  ): InclusionHeight =
    current.fold(candidate)(value =>
      if height(value) >= height(candidate) then value else candidate,
    )

  private def inspect[A](
      validate: ApplicationSafetySnapshot => Either[
        ApplicationSafetyRuntimeFailure,
        A,
      ],
  ): EitherT[F, ApplicationSafetyRuntimeFailure, A] =
    EitherT(store.snapshot.map(validate))

  private def height(value: InclusionHeight): BigInt = value.toBigNat.toBigInt

  private def failure(
      reason: String,
      detail: String,
  ): ApplicationSafetyRuntimeFailure =
    ApplicationSafetyRuntimeFailure(reason, detail)
