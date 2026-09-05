package org.sigilaris.node.jvm.runtime.application

import cats.data.EitherT
import cats.effect.kernel.{Ref, Sync}
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.{
  ApplicationEntryLifecycle,
  DependencyPlanDigest,
  InclusionHeight,
}
import org.sigilaris.core.datatype.UInt256
import org.sigilaris.node.jvm.runtime.txpipeline.ExactTxPipelineStoreValidation
import org.sigilaris.node.txpipeline.{
  ExactExecutionMode,
  ExactPipelineCanonical,
  ExactPipelineLifecycle,
  ExactTxPipelineRecord,
}

trait ApplicationSafetyStore[F[_]]:
  def snapshot: F[ApplicationSafetySnapshot]

  def journal: F[Vector[ApplicationJournalEntry]]

  def transact[A](
      operation: String,
  )(
      update: ApplicationSafetySnapshot => Either[
        ApplicationSafetyRuntimeFailure,
        (ApplicationSafetySnapshot, A),
      ],
  ): EitherT[F, ApplicationSafetyRuntimeFailure, A]

  def recover
      : EitherT[F, ApplicationSafetyRuntimeFailure, ApplicationSafetySnapshot]

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
  ),
)
object ApplicationSafetyReconciliation:
  def repairLegacyDrainState(
      snapshot: ApplicationSafetySnapshot,
  ): ApplicationSafetySnapshot =
    val greatestObserved = activeDeadlines(snapshot).maxByOption(
      _.toBigNat.toBigInt,
    )
    val repairedWatermark =
      (snapshot.drain.greatestAdmittedDeadline, greatestObserved) match
        case (None, observed)                 => observed
        case (recorded, None)                 => recorded
        case (Some(recorded), Some(observed)) =>
          Some(
            if recorded.toBigNat.toBigInt >= observed.toBigNat.toBigInt then
              recorded
            else observed,
          )
    val repairedPhase = snapshot.drain.phase match
      case ApplicationDrainPhase.Ready | ApplicationDrainPhase.Activating
          if snapshot.hasLiveEntries =>
        ApplicationDrainPhase.WaitingFinality
      case phase => phase
    snapshot.copy(drain =
      snapshot.drain.copy(
        phase = repairedPhase,
        greatestAdmittedDeadline = repairedWatermark,
      ),
    )

  def validate(
      snapshot: ApplicationSafetySnapshot,
  ): Either[ApplicationSafetyRuntimeFailure, ApplicationSafetySnapshot] =
    for
      _ <- ApplicationDrainState.validate(snapshot.drain)
      _ <- validateLockKeys(snapshot)
      _ <- validateReservationKeys(snapshot)
      _ <- validateDescriptorUniqueness(snapshot)
      _ <- validateLiveContexts(snapshot)
      _ <- validateReservations(snapshot)
      _ <- validateApplied(snapshot)
      _ <- validateTerminal(snapshot)
      _ <- validateTerminalReferences(snapshot)
      _ <- validateExactPipelines(snapshot)
      _ <- validateDrainWatermark(snapshot)
    yield snapshot

  private def validateExactPipelines(
      snapshot: ApplicationSafetySnapshot,
  ): Either[ApplicationSafetyRuntimeFailure, Unit] =
    val invalid = snapshot.exactPipelines.iterator.collectFirst:
      case (pipelineId, record)
          if record.genericProjection.pipelineId != pipelineId =>
        failure(
          "applicationJournalIncomplete",
          s"exact pipeline key ${pipelineId.value} does not match its descriptor",
        )
      case (_, record)
          if ExactTxPipelineStoreValidation.validateDescriptor(record).isLeft =>
        failure(
          "incompatibleStoreSchema",
          s"exact pipeline ${record.genericProjection.pipelineId.value} descriptor is invalid",
        )
    invalid match
      case Some(value) => Left(value)
      case None        =>
        val duplicates = snapshot.exactPipelines.valuesIterator
          .flatMap(_.verifiedPlan.orderedExecutionIds)
          .toVector
          .groupBy(_.toHexLower)
          .collectFirst:
            case (executionId, entries) if entries.sizeCompare(1) > 0 =>
              executionId
        duplicates match
          case Some(executionId) =>
            Left(
              failure(
                "crossPipelineDependencyReuse",
                s"execution $executionId belongs to multiple exact pipelines",
              ),
            )
          case None =>
            snapshot.exactPipelines.valuesIterator.toVector
              .traverse_(validateExactPipelineState(snapshot, _))

  private def validateExactPipelineState(
      snapshot: ApplicationSafetySnapshot,
      record: ExactTxPipelineRecord,
  ): Either[ApplicationSafetyRuntimeFailure, Unit] =
    val plan          = record.verifiedPlan
    val expectedIds   = plan.orderedExecutionIds
    val appliedIds    = expectedIds.filter(snapshot.applied.contains)
    val coverageValid = record.lifecycle match
      case ExactPipelineLifecycle.Accepted |
          ExactPipelineLifecycle.LockCertified =>
        appliedIds.isEmpty
      case ExactPipelineLifecycle.EffectCertified |
          ExactPipelineLifecycle.ExpiredUnapplied |
          ExactPipelineLifecycle.Failed =>
        plan.mode match
          case ExactExecutionMode.OrderedAtomic     => appliedIds.isEmpty
          case ExactExecutionMode.CertifiedAncestor =>
            appliedIds.isEmpty || appliedIds == expectedIds.take(1)
      case ExactPipelineLifecycle.Included | ExactPipelineLifecycle.Finalized |
          ExactPipelineLifecycle.Materialized =>
        appliedIds == expectedIds
    for
      _ <- Either.cond(
        coverageValid,
        (),
        failure(
          "applicationJournalIncomplete",
          s"exact pipeline ${record.genericProjection.pipelineId.value} lifecycle disagrees with its applied index",
        ),
      )
      expectedDigest <- UInt256
        .fromHex(ExactPipelineCanonical.verifiedPlanDigest(plan))
        .leftMap(error => failure("incompatibleStoreSchema", error.toString))
        .map(DependencyPlanDigest(_))
      _ <- appliedIds.traverse_ { executionId =>
        val applied  = snapshot.applied(executionId)
        val terminal = snapshot.terminal.get(executionId)
        Either.cond(
          applied.context.dependencyPlanDigest == expectedDigest &&
            terminal.exists(outcome =>
              outcome.context == applied.context &&
                outcome.lastInclusionHeight == plan.lastInclusionHeight &&
                outcome.applied.contains(applied.applied),
            ),
          (),
          failure(
            "applicationJournalIncomplete",
            s"exact execution ${executionId.toHexLower} is not bound to its plan digest and deadline",
          ),
        )
      }
    yield ()

  private def validateLockKeys(
      snapshot: ApplicationSafetySnapshot,
  ): Either[ApplicationSafetyRuntimeFailure, Unit] =
    snapshot.locks.iterator
      .flatMap:
        case (key, record) if !record.lock.inputIds.contains(key) =>
          Iterator.single(
            failure(
              "applicationJournalIncomplete",
              s"lock key ${key.toHex} is absent from its lock inputIds",
            ),
          )
        case (_, record)
            if record.lock.inputIds.exists(inputId =>
              !snapshot.locks.get(inputId).contains(record),
            ) =>
          Iterator.single(
            failure(
              "applicationJournalIncomplete",
              s"lock ${record.lock.executionId.toHexLower} is not stored under every inputId",
            ),
          )
        case (_, record) =>
          ApplicationLockRecord.validate(record).left.toOption.iterator
      .nextOption() match
      case Some(value) => Left(value)
      case None        => Right(())

  private def validateReservationKeys(
      snapshot: ApplicationSafetySnapshot,
  ): Either[ApplicationSafetyRuntimeFailure, Unit] =
    snapshot.reservations.iterator
      .flatMap:
        case (key, record) if !record.reservation.inputIds.contains(key) =>
          Iterator.single(
            failure(
              "applicationJournalIncomplete",
              s"reservation key ${key.toHex} is absent from its inputIds",
            ),
          )
        case (_, record)
            if record.reservation.inputIds.exists(inputId =>
              !snapshot.reservations.get(inputId).contains(record),
            ) =>
          Iterator.single(
            failure(
              "applicationJournalIncomplete",
              s"reservation ${record.reservation.executionId.toHexLower} is not stored under every inputId",
            ),
          )
        case (_, record) =>
          ProposalReservationRecord.validate(record).left.toOption.iterator
      .nextOption() match
      case Some(value) => Left(value)
      case None        => Right(())

  private def validateLiveContexts(
      snapshot: ApplicationSafetySnapshot,
  ): Either[ApplicationSafetyRuntimeFailure, Unit] =
    val manifest    = snapshot.drain.activeManifest
    val invalidLock = snapshot.locks.valuesIterator.find(record =>
      record.lock.lifecycle == ApplicationEntryLifecycle.Live &&
        !ApplicationArtifactContext.matchesManifest(record.context, manifest),
    )
    val invalidReservation = snapshot.reservations.valuesIterator.find(record =>
      record.reservation.lifecycle == ApplicationEntryLifecycle.Live &&
        !ApplicationArtifactContext.matchesManifest(record.context, manifest),
    )
    (invalidLock, invalidReservation) match
      case (Some(_), _) =>
        Left(
          failure("manifestMismatch", "live lock belongs to another manifest"),
        )
      case (_, Some(_)) =>
        Left(
          failure(
            "manifestMismatch",
            "live reservation belongs to another manifest",
          ),
        )
      case _ => Right(())

  private def validateDescriptorUniqueness(
      snapshot: ApplicationSafetySnapshot,
  ): Either[ApplicationSafetyRuntimeFailure, Unit] =
    val ambiguousLock = snapshot.locks.valuesIterator.toVector.distinct
      .groupBy(_.lock.executionId)
      .collectFirst:
        case (executionId, records) if records.sizeCompare(1) > 0 =>
          executionId
    val ambiguousReservation =
      snapshot.reservations.valuesIterator.toVector.distinct
        .groupBy(_.reservation.executionId)
        .collectFirst:
          case (executionId, records) if records.sizeCompare(1) > 0 =>
            executionId
    ambiguousLock.orElse(ambiguousReservation) match
      case Some(executionId) =>
        Left(
          failure(
            "applicationJournalIncomplete",
            s"execution ${executionId.toHexLower} has multiple durable descriptors",
          ),
        )
      case None => Right(())

  private def validateReservations(
      snapshot: ApplicationSafetySnapshot,
  ): Either[ApplicationSafetyRuntimeFailure, Unit] =
    snapshot.reservations.iterator.collectFirst:
      case (inputId, reservation)
          if reservation.reservation.lifecycle == ApplicationEntryLifecycle.Live &&
            !snapshot.locks
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
          s"live reservation ${inputId.toHex} has no reciprocal live lock",
        )
    match
      case Some(value) => Left(value)
      case None        => Right(())

  private def validateDrainWatermark(
      snapshot: ApplicationSafetySnapshot,
  ): Either[ApplicationSafetyRuntimeFailure, Unit] =
    val greatestRecorded = activeDeadlines(snapshot).maxByOption(
      _.toBigNat.toBigInt,
    )
    val watermarkCoversRecords = greatestRecorded.forall(deadline =>
      snapshot.drain.greatestAdmittedDeadline.exists(
        _.toBigNat.toBigInt >= deadline.toBigNat.toBigInt,
      ),
    )
    val terminalPhaseHasNoLive = snapshot.drain.phase match
      case ApplicationDrainPhase.Ready | ApplicationDrainPhase.Activating =>
        !snapshot.hasLiveEntries
      case _ => true
    if !watermarkCoversRecords then
      Left(
        failure(
          "applicationJournalIncomplete",
          "greatest admitted deadline does not cover the active ownership audit rows",
        ),
      )
    else if !terminalPhaseHasNoLive then
      Left(
        failure(
          "liveEntriesRemain",
          "ready or activating drain state still contains live ownership rows",
        ),
      )
    else Right(())

  private def activeDeadlines(
      snapshot: ApplicationSafetySnapshot,
  ): Vector[InclusionHeight] =
    val activeLockDeadlines = snapshot.locks.valuesIterator
      .filter(record =>
        ApplicationArtifactContext.matchesManifest(
          record.context,
          snapshot.drain.activeManifest,
        ),
      )
      .map(_.lock.lastInclusionHeight)
      .toVector
    val activeExactDeadlines = snapshot.exactPipelines.valuesIterator
      .filter(record => !ExactPipelineLifecycle.isTerminal(record.lifecycle))
      .map(_.verifiedPlan.lastInclusionHeight)
      .toVector
    activeLockDeadlines ++ activeExactDeadlines

  private def validateApplied(
      snapshot: ApplicationSafetySnapshot,
  ): Either[ApplicationSafetyRuntimeFailure, Unit] =
    snapshot.applied.iterator
      .flatMap:
        case (executionId, record)
            if record.applied.executionId != executionId =>
          Iterator.single(
            failure(
              "appliedExecutionConflict",
              s"applied map key ${executionId.toHexLower} does not match its value",
            ),
          )
        case (executionId, record)
            if !snapshot.terminal
              .get(executionId)
              .exists(outcome =>
                outcome.lifecycle == ApplicationEntryLifecycle.Applied &&
                  outcome.context == record.context &&
                  outcome.applied.contains(record.applied),
              ) =>
          Iterator.single(
            failure(
              "applicationJournalIncomplete",
              s"applied execution ${executionId.toHexLower} has no matching terminal outcome",
            ),
          )
        case (_, record) =>
          AppliedExecutionRecord.validate(record).left.toOption.iterator
      .nextOption() match
      case Some(value) => Left(value)
      case None        => Right(())

  private def validateTerminal(
      snapshot: ApplicationSafetySnapshot,
  ): Either[ApplicationSafetyRuntimeFailure, Unit] =
    snapshot.terminal.iterator
      .flatMap:
        case (executionId, outcome) if outcome.executionId != executionId =>
          Iterator.single(
            failure(
              "applicationJournalIncomplete",
              s"terminal key ${executionId.toHexLower} does not match its value",
            ),
          )
        case (executionId, outcome)
            if outcome.lifecycle == ApplicationEntryLifecycle.ExpiredUnapplied &&
              snapshot.applied.contains(executionId) =>
          Iterator.single(
            failure(
              "appliedExecutionConflict",
              s"expired execution ${executionId.toHexLower} has an applied index",
            ),
          )
        case (_, outcome) =>
          ApplicationTerminalOutcome.validate(outcome).left.toOption.iterator
      .nextOption() match
      case Some(value) => Left(value)
      case None        => Right(())

  private def validateTerminalReferences(
      snapshot: ApplicationSafetySnapshot,
  ): Either[ApplicationSafetyRuntimeFailure, Unit] =
    val terminalOutcomeFailure = snapshot.terminal.valuesIterator.flatMap:
      outcome =>
        val locks = snapshot.locks.valuesIterator
          .filter(_.lock.executionId == outcome.executionId)
          .toVector
          .distinct
        val reservations = snapshot.reservations.valuesIterator
          .filter(_.reservation.executionId == outcome.executionId)
          .toVector
          .distinct
        if locks.isEmpty then
          Iterator.single(
            failure(
              "applicationJournalIncomplete",
              s"terminal execution ${outcome.executionId.toHexLower} has no lock audit rows",
            ),
          )
        else if locks.exists(record =>
            record.context != outcome.context ||
              record.lock.lifecycle != outcome.lifecycle ||
              record.lock.lastInclusionHeight != outcome.lastInclusionHeight,
          )
        then
          Iterator.single(
            failure(
              "applicationJournalIncomplete",
              s"terminal execution ${outcome.executionId.toHexLower} disagrees with its lock audit rows",
            ),
          )
        else if reservations.exists(record =>
            record.context != outcome.context ||
              record.reservation.lifecycle != outcome.lifecycle ||
              record.reservation.lastInclusionHeight !=
              outcome.lastInclusionHeight ||
              !locks.exists(
                _.lock.inputIds == record.reservation.inputIds,
              ),
          )
        then
          Iterator.single(
            failure(
              "applicationJournalIncomplete",
              s"terminal execution ${outcome.executionId.toHexLower} disagrees with its reservation audit rows",
            ),
          )
        else if outcome.lifecycle == ApplicationEntryLifecycle.Applied &&
          locks.exists(lock =>
            lock.lock.inputIds.exists(inputId =>
              !snapshot.reservations
                .get(inputId)
                .exists(reservation =>
                  reservation.context == outcome.context &&
                    reservation.reservation.executionId == outcome.executionId &&
                    reservation.reservation.inputIds == lock.lock.inputIds &&
                    reservation.reservation.lastInclusionHeight ==
                    outcome.lastInclusionHeight &&
                    reservation.reservation.lifecycle ==
                    ApplicationEntryLifecycle.Applied,
                ),
            ),
          )
        then
          Iterator.single(
            failure(
              "applicationJournalIncomplete",
              s"applied terminal execution ${outcome.executionId.toHexLower} lacks reciprocal reservation audit rows",
            ),
          )
        else if outcome.lifecycle == ApplicationEntryLifecycle.Applied &&
          !snapshot.applied.contains(outcome.executionId)
        then
          Iterator.single(
            failure(
              "applicationJournalIncomplete",
              s"applied terminal execution ${outcome.executionId.toHexLower} has no applied index",
            ),
          )
        else Iterator.empty

    val orphanedLockFailure = snapshot.locks.valuesIterator.collectFirst:
      case record
          if record.lock.lifecycle != ApplicationEntryLifecycle.Live &&
            !snapshot.terminal
              .get(record.lock.executionId)
              .exists(outcome =>
                outcome.context == record.context &&
                  outcome.lifecycle == record.lock.lifecycle,
              ) =>
        failure(
          "applicationJournalIncomplete",
          s"terminal lock ${record.lock.executionId.toHexLower} has no matching outcome",
        )

    val orphanedReservationFailure =
      snapshot.reservations.valuesIterator.collectFirst:
        case record
            if record.reservation.lifecycle != ApplicationEntryLifecycle.Live &&
              !snapshot.terminal
                .get(record.reservation.executionId)
                .exists(outcome =>
                  outcome.context == record.context &&
                    outcome.lifecycle == record.reservation.lifecycle,
                ) =>
          failure(
            "applicationJournalIncomplete",
            s"terminal reservation ${record.reservation.executionId.toHexLower} has no matching outcome",
          )

    terminalOutcomeFailure
      .nextOption()
      .orElse(orphanedLockFailure)
      .orElse(orphanedReservationFailure) match
      case Some(value) => Left(value)
      case None        => Right(())

  private def failure(
      reason: String,
      detail: String,
  ): ApplicationSafetyRuntimeFailure =
    ApplicationSafetyRuntimeFailure(reason, detail)

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
final class InMemoryApplicationSafetyStore[F[_]: Sync] private (
    ref: Ref[F, InMemoryApplicationSafetyStore.State],
) extends ApplicationSafetyStore[F]:

  override def snapshot: F[ApplicationSafetySnapshot] = ref.get.map(_.snapshot)

  override def journal: F[Vector[ApplicationJournalEntry]] =
    ref.get.map(_.journal)

  override def transact[A](
      operation: String,
  )(
      update: ApplicationSafetySnapshot => Either[
        ApplicationSafetyRuntimeFailure,
        (ApplicationSafetySnapshot, A),
      ],
  ): EitherT[F, ApplicationSafetyRuntimeFailure, A] =
    EitherT:
      ref.modify: state =>
        val result = for
          updated <- update(state.snapshot)
          (candidate, value) = updated
          _ <- ApplicationSafetyReconciliation.validate(candidate)
        yield candidate -> value

        result match
          case Left(error) => state -> Left(error)
          case Right((candidate, value)) if candidate == state.snapshot =>
            state -> Right(value)
          case Right((candidate, value)) =>
            val persisted = for
              delta <- ApplicationJournalDelta.between(
                state.snapshot,
                candidate,
              )
              sequence = state.snapshot.lastJournalSequence + 1L
              prepared = ApplicationJournalEntry(
                ApplicationJournalEntry.SchemaVersion,
                sequence,
                operation,
                delta,
                ApplicationJournalStatus.Prepared,
              )
              committed = prepared.copy(
                status = ApplicationJournalStatus.Committed,
              )
              next     = candidate.copy(lastJournalSequence = sequence)
              replayed = ApplicationJournalDelta.applyTo(
                state.snapshot,
                delta,
                sequence,
              )
              _ <- Either.cond(
                replayed == next,
                (),
                ApplicationSafetyRuntimeFailure(
                  "applicationJournalIncomplete",
                  "journal replay does not reproduce the proposed application state",
                ),
              )
            yield next -> committed
            persisted match
              case Left(error)          => state -> Left(error)
              case Right((next, entry)) =>
                InMemoryApplicationSafetyStore.State(
                  next,
                  state.journal :+ entry,
                ) -> Right(value)

  override def recover
      : EitherT[F, ApplicationSafetyRuntimeFailure, ApplicationSafetySnapshot] =
    EitherT:
      ref.modify: state =>
        ApplicationSafetyReconciliation.validate(state.snapshot) match
          case Left(error)  => state -> Left(error)
          case Right(valid) =>
            val next = state.copy(snapshot = valid)
            next -> Right(valid)

@SuppressWarnings(Array("org.wartremover.warts.Any"))
object InMemoryApplicationSafetyStore:
  private final case class State(
      snapshot: ApplicationSafetySnapshot,
      journal: Vector[ApplicationJournalEntry],
  )

  def create[F[_]: Sync](
      manifest: org.sigilaris.node.txpipeline.ApplicationProtocolManifestV1,
  ): F[InMemoryApplicationSafetyStore[F]] =
    Sync[F]
      .fromEither(
        org.sigilaris.node.txpipeline.ApplicationProtocolManifestV1
          .validate(manifest)
          .left
          .map(error =>
            new IllegalArgumentException(
              s"${error.reason}: invalid initial application manifest",
            ),
          ),
      )
      .flatMap(validated =>
        Ref
          .of[F, State](
            State(ApplicationSafetySnapshot.initial(validated), Vector.empty),
          )
          .map(new InMemoryApplicationSafetyStore[F](_)),
      )
