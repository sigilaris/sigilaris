package org.sigilaris.core.application.protocol

import org.sigilaris.core.datatype.UInt256

enum ApplicationEntryLifecycle:
  case Live
  case Applied
  case ExpiredUnapplied

final case class ApplicationLock(
    executionId: ExecutionId,
    inputIds: Vector[ApplicationInputId],
    lastInclusionHeight: InclusionHeight,
    lifecycle: ApplicationEntryLifecycle,
)

final case class ProposalReservation(
    executionId: ExecutionId,
    inputIds: Vector[ApplicationInputId],
    lastInclusionHeight: InclusionHeight,
    lifecycle: ApplicationEntryLifecycle,
)

final case class AppliedExecution(
    executionId: ExecutionId,
    firstApplicationHeight: InclusionHeight,
    blockId: UInt256,
    resultDigest: ApplicationResultDigest,
    protocolVersion: ProtocolVersion,
)

enum ApplicationSafetyFailure:
  case EmptyLockSubset
  case DuplicateLockInput(inputId: ApplicationInputId)
  case InvalidLockLifecycle(lifecycle: ApplicationEntryLifecycle)
  case InvalidReservationLifecycle(lifecycle: ApplicationEntryLifecycle)
  case ExecutionAlreadyApplied(executionId: ExecutionId)
  case ExecutionAlreadyTerminal(executionId: ExecutionId)
  case ExecutionLockDescriptorConflict(executionId: ExecutionId)
  case ExecutionReservationDescriptorConflict(executionId: ExecutionId)
  case InputAlreadyLocked(
      inputId: ApplicationInputId,
      existingExecutionId: ExecutionId,
  )
  case LockMissing(inputId: ApplicationInputId)
  case LockOwnerMismatch(
      inputId: ApplicationInputId,
      existingExecutionId: ExecutionId,
  )
  case ReservationConflict(
      inputId: ApplicationInputId,
      existingExecutionId: ExecutionId,
  )
  case ReciprocalLockMismatch(inputId: ApplicationInputId)
  case ExecutionLockMissing(executionId: ExecutionId)
  case ExecutionReservationMissing(inputId: ApplicationInputId)
  case ExecutionLockNotLive(inputId: ApplicationInputId)
  case ApplicationAfterLockDeadline(
      executionId: ExecutionId,
      lastInclusionHeight: InclusionHeight,
  )
  case AppliedExecutionConflict(executionId: ExecutionId)

final case class ApplicationSafetyState(
    locks: Map[ApplicationInputId, ApplicationLock],
    reservations: Map[ApplicationInputId, ProposalReservation],
    applied: Map[ExecutionId, AppliedExecution],
    terminalExecutions: Set[ExecutionId],
)

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object ApplicationSafetyState:
  def apply(
      locks: Map[ApplicationInputId, ApplicationLock],
      reservations: Map[ApplicationInputId, ProposalReservation],
      applied: Map[ExecutionId, AppliedExecution],
  ): ApplicationSafetyState =
    ApplicationSafetyState(locks, reservations, applied, Set.empty)

  val empty: ApplicationSafetyState =
    ApplicationSafetyState(Map.empty, Map.empty, Map.empty, Set.empty)

  def acquireLock(
      state: ApplicationSafetyState,
      lock: ApplicationLock,
  ): Either[ApplicationSafetyFailure, ApplicationSafetyState] =
    val canonical = lock.copy(inputIds = lock.inputIds.sortBy(_.toHex))
    val duplicate = canonical.inputIds
      .groupBy(_.toHex)
      .collectFirst:
        case (_, entries) if entries.sizeCompare(1) > 0 => entries.headOption
      .flatten
    val owned = state.locks.valuesIterator
      .filter(_.executionId == lock.executionId)
      .toVector
      .distinct
    val displacedTerminalExecutions = canonical.inputIds.iterator
      .flatMap(state.locks.get)
      .filter(_.lifecycle != ApplicationEntryLifecycle.Live)
      .map(_.executionId)
      .toSet
    duplicate match
      case _ if lock.inputIds.isEmpty =>
        Left(ApplicationSafetyFailure.EmptyLockSubset)
      case Some(inputId) =>
        Left(ApplicationSafetyFailure.DuplicateLockInput(inputId))
      case None if lock.lifecycle != ApplicationEntryLifecycle.Live =>
        Left(ApplicationSafetyFailure.InvalidLockLifecycle(lock.lifecycle))
      case None if state.applied.contains(lock.executionId) =>
        Left(
          ApplicationSafetyFailure.ExecutionAlreadyApplied(lock.executionId),
        )
      case None if state.terminalExecutions.contains(lock.executionId) =>
        Left(
          ApplicationSafetyFailure.ExecutionAlreadyTerminal(
            lock.executionId,
          ),
        )
      case None if owned.exists(_ != canonical) =>
        Left(
          ApplicationSafetyFailure.ExecutionLockDescriptorConflict(
            lock.executionId,
          ),
        )
      case None =>
        lock.inputIds.collectFirst:
          case inputId
              if state.locks
                .get(inputId)
                .exists(existing =>
                  existing.lifecycle == ApplicationEntryLifecycle.Live &&
                    existing.executionId != lock.executionId,
                ) =>
            val owner = state.locks(inputId).executionId
            ApplicationSafetyFailure.InputAlreadyLocked(inputId, owner)
        match
          case Some(failure) => Left(failure)
          case None          =>
            Right:
              state.copy(
                locks = state.locks ++ canonical.inputIds.map(_ -> canonical),
                terminalExecutions = state.terminalExecutions ++
                  displacedTerminalExecutions,
              )

  def reserve(
      state: ApplicationSafetyState,
      reservation: ProposalReservation,
  ): Either[ApplicationSafetyFailure, ApplicationSafetyState] =
    val canonical = reservation.copy(
      inputIds = reservation.inputIds.sortBy(_.toHex),
    )
    val duplicate = canonical.inputIds
      .groupBy(_.toHex)
      .collectFirst:
        case (_, entries) if entries.sizeCompare(1) > 0 => entries.headOption
      .flatten
    val owned = state.reservations.valuesIterator
      .filter(_.executionId == reservation.executionId)
      .toVector
      .distinct
    val displacedTerminalExecutions = canonical.inputIds.iterator
      .flatMap(state.reservations.get)
      .filter(_.lifecycle != ApplicationEntryLifecycle.Live)
      .map(_.executionId)
      .toSet
    duplicate match
      case _ if reservation.inputIds.isEmpty =>
        Left(ApplicationSafetyFailure.EmptyLockSubset)
      case Some(inputId) =>
        Left(ApplicationSafetyFailure.DuplicateLockInput(inputId))
      case None if reservation.lifecycle != ApplicationEntryLifecycle.Live =>
        Left(
          ApplicationSafetyFailure.InvalidReservationLifecycle(
            reservation.lifecycle,
          ),
        )
      case None if state.applied.contains(reservation.executionId) =>
        Left(
          ApplicationSafetyFailure.ExecutionAlreadyApplied(
            reservation.executionId,
          ),
        )
      case None if state.terminalExecutions.contains(reservation.executionId) =>
        Left(
          ApplicationSafetyFailure.ExecutionAlreadyTerminal(
            reservation.executionId,
          ),
        )
      case None if owned.exists(_ != canonical) =>
        Left(
          ApplicationSafetyFailure.ExecutionReservationDescriptorConflict(
            reservation.executionId,
          ),
        )
      case None =>
        reservation.inputIds.collectFirst:
          case inputId if !state.locks.contains(inputId) =>
            ApplicationSafetyFailure.LockMissing(inputId)
          case inputId
              if state.locks
                .get(inputId)
                .exists(existing =>
                  existing.lifecycle != ApplicationEntryLifecycle.Live ||
                    existing.executionId != reservation.executionId,
                ) =>
            ApplicationSafetyFailure.LockOwnerMismatch(
              inputId,
              state.locks(inputId).executionId,
            )
          case inputId
              if state.locks
                .get(inputId)
                .exists(existing =>
                  existing.inputIds != canonical.inputIds ||
                    existing.lastInclusionHeight !=
                    canonical.lastInclusionHeight,
                ) =>
            ApplicationSafetyFailure.ReciprocalLockMismatch(inputId)
          case inputId
              if state.reservations
                .get(inputId)
                .exists(existing =>
                  existing.lifecycle == ApplicationEntryLifecycle.Live &&
                    existing.executionId != reservation.executionId,
                ) =>
            ApplicationSafetyFailure.ReservationConflict(
              inputId,
              state.reservations(inputId).executionId,
            )
        match
          case Some(failure) => Left(failure)
          case None          =>
            Right:
              state.copy(
                reservations = state.reservations ++
                  canonical.inputIds.map(_ -> canonical),
                terminalExecutions = state.terminalExecutions ++
                  displacedTerminalExecutions,
              )

  def expireExecution(
      state: ApplicationSafetyState,
      executionId: ExecutionId,
  ): Either[ApplicationSafetyFailure, ApplicationSafetyState] =
    if state.applied.contains(executionId) then
      Left(ApplicationSafetyFailure.ExecutionAlreadyApplied(executionId))
    else if !state.locks.valuesIterator.exists(_.executionId == executionId)
    then Left(ApplicationSafetyFailure.ExecutionLockMissing(executionId))
    else
      Right:
        state.copy(
          locks = state.locks.map:
            case (inputId, lock) if lock.executionId == executionId =>
              inputId -> lock.copy(
                lifecycle = ApplicationEntryLifecycle.ExpiredUnapplied,
              )
            case entry => entry
          ,
          reservations = state.reservations.map:
            case (inputId, reservation)
                if reservation.executionId == executionId =>
              inputId -> reservation.copy(
                lifecycle = ApplicationEntryLifecycle.ExpiredUnapplied,
              )
            case entry => entry
          ,
          terminalExecutions = state.terminalExecutions + executionId,
        )

  def recordApplication(
      state: ApplicationSafetyState,
      value: AppliedExecution,
  ): Either[ApplicationSafetyFailure, ApplicationSafetyState] =
    state.applied.get(value.executionId) match
      case Some(existing) if existing != value =>
        Left(
          ApplicationSafetyFailure.AppliedExecutionConflict(value.executionId),
        )
      case Some(_) => Right(terminalizeExecution(state, value.executionId))
      case None if state.terminalExecutions.contains(value.executionId) =>
        Left(
          ApplicationSafetyFailure.ExecutionAlreadyTerminal(
            value.executionId,
          ),
        )
      case None =>
        val executionLocks = state.locks.iterator
          .collect:
            case (inputId, lock) if lock.executionId == value.executionId =>
              inputId -> lock
          .toVector
        if executionLocks.isEmpty then
          Left(ApplicationSafetyFailure.ExecutionLockMissing(value.executionId))
        else
          val nonLiveLock = executionLocks.collectFirst:
            case (inputId, lock)
                if lock.lifecycle != ApplicationEntryLifecycle.Live =>
              inputId
          val expiredLock = executionLocks.collectFirst:
            case (_, lock)
                if summon[Ordering[InclusionHeight]].gt(
                  value.firstApplicationHeight,
                  lock.lastInclusionHeight,
                ) =>
              lock
          val missingReservation = executionLocks.find:
            case (inputId, lock) =>
              !state.reservations
                .get(inputId)
                .exists: reservation =>
                  reservation.executionId == value.executionId &&
                    reservation.lifecycle == ApplicationEntryLifecycle.Live &&
                    reservation.inputIds == lock.inputIds &&
                    reservation.lastInclusionHeight ==
                    lock.lastInclusionHeight
          val missingReservationInput = missingReservation.map(_._1)
          nonLiveLock -> expiredLock match
            case (Some(inputId), _) =>
              Left(ApplicationSafetyFailure.ExecutionLockNotLive(inputId))
            case (None, Some(lock)) =>
              Left(
                ApplicationSafetyFailure.ApplicationAfterLockDeadline(
                  value.executionId,
                  lock.lastInclusionHeight,
                ),
              )
            case (None, None) =>
              missingReservationInput match
                case Some(inputId) =>
                  Left(
                    ApplicationSafetyFailure.ExecutionReservationMissing(
                      inputId,
                    ),
                  )
                case None =>
                  Right:
                    terminalizeExecution(state, value.executionId).copy(
                      applied = state.applied.updated(value.executionId, value),
                    )

  private def terminalizeExecution(
      state: ApplicationSafetyState,
      executionId: ExecutionId,
  ): ApplicationSafetyState =
    state.copy(
      locks = state.locks.map:
        case (inputId, lock) if lock.executionId == executionId =>
          inputId -> lock.copy(lifecycle = ApplicationEntryLifecycle.Applied)
        case entry => entry
      ,
      reservations = state.reservations.map:
        case (inputId, reservation) if reservation.executionId == executionId =>
          inputId -> reservation.copy(
            lifecycle = ApplicationEntryLifecycle.Applied,
          )
        case entry => entry,
      terminalExecutions = state.terminalExecutions + executionId,
    )
