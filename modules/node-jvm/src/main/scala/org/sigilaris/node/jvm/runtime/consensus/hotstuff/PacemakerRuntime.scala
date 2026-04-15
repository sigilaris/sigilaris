package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.{Duration, Instant}

import cats.syntax.all.*

import org.sigilaris.core.util.SafeStringInterp.*

/** Configuration for pacemaker timeout behavior.
  *
  * @param baseTimeout the base timeout duration for a view
  * @param maxBackoffExponent the maximum exponential backoff exponent
  * @param jitterStep the per-slot jitter step duration
  * @param maxJitterSlots the maximum number of jitter slots
  * @param elevatedTimeoutAlertThreshold consecutive timeouts before alerting
  */
final case class HotStuffPacemakerPolicy private (
    baseTimeout: Duration,
    maxBackoffExponent: Int,
    jitterStep: Duration,
    maxJitterSlots: Int,
    elevatedTimeoutAlertThreshold: Int,
)

/** Companion for `HotStuffPacemakerPolicy`. */
@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object HotStuffPacemakerPolicy:
  def apply(
      baseTimeout: Duration,
      maxBackoffExponent: Int,
      jitterStep: Duration,
      maxJitterSlots: Int,
      elevatedTimeoutAlertThreshold: Int,
  ): Either[String, HotStuffPacemakerPolicy] =
    Either
      .cond(!baseTimeout.isNegative, (), "baseTimeout must be non-negative")
      .flatMap(_ =>
        Either.cond(!baseTimeout.isZero, (), "baseTimeout must be positive"),
      )
      .flatMap(_ =>
        Either.cond(
          maxBackoffExponent >= 0,
          (),
          "maxBackoffExponent must be non-negative",
        ),
      )
      .flatMap(_ =>
        Either.cond(
          !jitterStep.isNegative,
          (),
          "jitterStep must be non-negative",
        ),
      )
      .flatMap(_ =>
        Either.cond(
          maxJitterSlots >= 0,
          (),
          "maxJitterSlots must be non-negative",
        ),
      )
      .flatMap: _ =>
        Either.cond(
          elevatedTimeoutAlertThreshold > 0,
          new HotStuffPacemakerPolicy(
            baseTimeout = baseTimeout,
            maxBackoffExponent = maxBackoffExponent,
            jitterStep = jitterStep,
            maxJitterSlots = maxJitterSlots,
            elevatedTimeoutAlertThreshold = elevatedTimeoutAlertThreshold,
          ),
          "elevatedTimeoutAlertThreshold must be positive",
        )

  def unsafe(
      baseTimeout: Duration,
      maxBackoffExponent: Int,
      jitterStep: Duration,
      maxJitterSlots: Int,
      elevatedTimeoutAlertThreshold: Int,
  ): HotStuffPacemakerPolicy =
    apply(
      baseTimeout = baseTimeout,
      maxBackoffExponent = maxBackoffExponent,
      jitterStep = jitterStep,
      maxJitterSlots = maxJitterSlots,
      elevatedTimeoutAlertThreshold = elevatedTimeoutAlertThreshold,
    ) match
      case Right(policy) => policy
      case Left(error)   => throw new IllegalArgumentException(error)

  /** The default pacemaker policy. */
  val default: HotStuffPacemakerPolicy =
    unsafe(
      baseTimeout = HotStuffPolicy.deploymentTarget.blockProductionInterval,
      maxBackoffExponent = 3,
      jitterStep = Duration.ofMillis(10),
      maxJitterSlots = 3,
      elevatedTimeoutAlertThreshold = 3,
    )

/** Commands emitted by the pacemaker runtime to be executed by the integration driver. */
enum HotStuffPacemakerCommand:
  case ActivateLeader(window: HotStuffWindow, leader: ValidatorId)
  case EmitTimeoutVote(
      voter: ValidatorId,
      window: HotStuffWindow,
      highestKnownQc: QuorumCertificate,
  )
  case EmitNewView(
      sender: ValidatorId,
      highestKnownQc: QuorumCertificate,
      timeoutCertificate: TimeoutCertificate,
  )

/** Describes the local validator's eligibility to propose in the current window. */
enum HotStuffPacemakerProposalEligibility:
  case EligibleAsLeader(leader: ValidatorId)
  case Follower(expectedLeader: ValidatorId)
  case BootstrapHeld(reason: String, expectedLeader: ValidatorId)
  case TimeoutInProgress(expectedLeader: ValidatorId)

/** The outcome of a pacemaker state transition step. */
enum HotStuffPacemakerStepOutcome:
  case Started, Applied, AdvancedWindow, Duplicate, Stale, NoOp

/** Diagnostic events emitted by the pacemaker for monitoring and alerting. */
enum HotStuffPacemakerDiagnostic:
  case BootstrapHoldBlockedTimeout(window: HotStuffWindow, reason: String)
  case DivergentTimeoutSubjects(
      window: HotStuffWindow,
      subjects: Set[QuorumCertificateSubject],
  )
  case ElevatedTimeoutAlert(
      window: HotStuffWindow,
      consecutiveTimeoutWindows: Int,
  )

/** The mutable state of a pacemaker entry, tracking the active window, timeout, and local vote state. */
final case class HotStuffPacemakerState(
    activeWindow: HotStuffWindow,
    currentLeader: ValidatorId,
    highestKnownQc: QuorumCertificate,
    timeoutDeadline: Instant,
    timeoutVotes: TimeoutVoteAccumulator,
    timeoutCertificate: Option[TimeoutCertificate],
    localTimeoutVoteRequested: Boolean,
    localTimeoutVote: Option[TimeoutVote],
    localNewViewRequested: Boolean,
    bootstrapHoldReason: Option[String],
    consecutiveTimeoutWindows: Int,
)

/** The result of a pacemaker state transition, including new state, commands, and diagnostics. */
final case class HotStuffPacemakerStep(
    state: HotStuffPacemakerState,
    outcome: HotStuffPacemakerStepOutcome,
    commands: Vector[HotStuffPacemakerCommand],
    diagnostics: Vector[HotStuffPacemakerDiagnostic],
    proposalEligibility: HotStuffPacemakerProposalEligibility,
)

/** Pure pacemaker runtime that drives state transitions for a single local validator. */
final case class HotStuffPacemakerRuntime(
    localValidator: ValidatorId,
    validatorSet: ValidatorSet,
    policy: HotStuffPacemakerPolicy,
):
  /** Initializes the pacemaker for a new consensus window. */
  def start(
      activeWindow: HotStuffWindow,
      highestKnownQc: QuorumCertificate,
      now: Instant,
      bootstrapHoldReason: Option[String],
  ): HotStuffPacemakerStep =
    val currentLeader =
      HotStuffPacemaker.deterministicLeader(activeWindow, validatorSet)
    val state =
      HotStuffPacemakerState(
        activeWindow = activeWindow,
        currentLeader = currentLeader,
        highestKnownQc = highestKnownQc,
        timeoutDeadline = now.plus(timeoutFor(activeWindow, 0)),
        timeoutVotes = TimeoutVoteAccumulator.empty,
        timeoutCertificate = None,
        localTimeoutVoteRequested = false,
        localTimeoutVote = None,
        localNewViewRequested = false,
        bootstrapHoldReason = bootstrapHoldReason,
        consecutiveTimeoutWindows = 0,
      )
    step(
      state,
      HotStuffPacemakerStepOutcome.Started,
      activationCommands(state),
      Vector.empty[HotStuffPacemakerDiagnostic],
    )

  /** Updates the bootstrap hold reason, potentially triggering leader activation. */
  def updateBootstrapHold(
      state: HotStuffPacemakerState,
      bootstrapHoldReason: Option[String],
  ): HotStuffPacemakerStep =
    val updated =
      state.copy(bootstrapHoldReason = bootstrapHoldReason)
    val commands =
      if state.bootstrapHoldReason =!= bootstrapHoldReason then
        activationCommands(updated)
      else Vector.empty[HotStuffPacemakerCommand]
    step(
      updated,
      HotStuffPacemakerStepOutcome.Applied,
      commands,
      Vector.empty[HotStuffPacemakerDiagnostic],
    )

  /** Advances the pacemaker clock, potentially triggering a timeout vote. */
  def tick(
      state: HotStuffPacemakerState,
      now: Instant,
  ): HotStuffPacemakerStep =
    if now.isBefore(state.timeoutDeadline) then
      step(
        state,
        HotStuffPacemakerStepOutcome.NoOp,
        Vector.empty[HotStuffPacemakerCommand],
        Vector.empty[HotStuffPacemakerDiagnostic],
      )
    else if state.localTimeoutVoteRequested || state.localTimeoutVote.nonEmpty
    then
      step(
        state,
        HotStuffPacemakerStepOutcome.NoOp,
        Vector.empty[HotStuffPacemakerCommand],
        Vector.empty[HotStuffPacemakerDiagnostic],
      )
    else
      val nextFailures = state.consecutiveTimeoutWindows + 1
      val updated =
        state.copy(
          timeoutDeadline =
            now.plus(timeoutFor(state.activeWindow, nextFailures)),
          localTimeoutVoteRequested = state.bootstrapHoldReason.isEmpty,
          consecutiveTimeoutWindows = nextFailures,
        )
      val diagnostics =
        timeoutDiagnostics(updated)
      state.bootstrapHoldReason match
        case Some(reason) =>
          step(
            updated.copy(localTimeoutVoteRequested = false),
            HotStuffPacemakerStepOutcome.Applied,
            Vector.empty[HotStuffPacemakerCommand],
            diagnostics :+ HotStuffPacemakerDiagnostic
              .BootstrapHoldBlockedTimeout(state.activeWindow, reason),
          )
        case None =>
          step(
            updated,
            HotStuffPacemakerStepOutcome.Applied,
            Vector(
              HotStuffPacemakerCommand.EmitTimeoutVote(
                voter = localValidator,
                window = state.activeWindow,
                highestKnownQc = state.highestKnownQc,
              ),
            ),
            diagnostics,
          )

  /** Processes an observed timeout vote, potentially assembling a timeout certificate. */
  def observeTimeoutVote(
      state: HotStuffPacemakerState,
      timeoutVote: TimeoutVote,
  ): Either[HotStuffPolicyViolation, HotStuffPacemakerStep] =
    HotStuffValidator
      .validateTimeoutVote(timeoutVote, validatorSet)
      .leftMap(validationFailureToPolicyViolation)
      .flatMap: _ =>
        classifyCurrentWindow(
          timeoutVote.subject.window,
          state.activeWindow,
        ) match
          case HotStuffPacemakerWindowDisposition.Stale =>
            step(
              state,
              HotStuffPacemakerStepOutcome.Stale,
              Vector.empty[HotStuffPacemakerCommand],
              Vector.empty[HotStuffPacemakerDiagnostic],
            ).asRight[HotStuffPolicyViolation]
          case HotStuffPacemakerWindowDisposition.Expected =>
            state.timeoutVotes
              .record(timeoutVote)
              .leftMap(validationFailureToPolicyViolation)
              .map:
                case (_, TimeoutVoteRecordOutcome.Duplicate) =>
                  step(
                    state,
                    HotStuffPacemakerStepOutcome.Duplicate,
                    Vector.empty[HotStuffPacemakerCommand],
                    Vector.empty[HotStuffPacemakerDiagnostic],
                  )
                case (updatedAccumulator, TimeoutVoteRecordOutcome.Applied) =>
                  val maybeLocalTimeoutVote =
                    if timeoutVote.voter === localValidator then
                      Some(timeoutVote)
                    else state.localTimeoutVote
                  val maybeTimeoutCertificate =
                    state.timeoutCertificate.orElse:
                      TimeoutCertificateAssembler
                        .assemble(
                          timeoutVote.subject,
                          updatedAccumulator.votesFor(timeoutVote.subject),
                          validatorSet,
                        )
                        .toOption
                  val shouldEmitNewView =
                    maybeLocalTimeoutVote.nonEmpty &&
                      maybeTimeoutCertificate.nonEmpty &&
                      !state.localNewViewRequested &&
                      state.bootstrapHoldReason.isEmpty
                  val updated =
                    state.copy(
                      timeoutVotes = updatedAccumulator,
                      timeoutCertificate = maybeTimeoutCertificate,
                      localTimeoutVoteRequested =
                        if timeoutVote.voter === localValidator then false
                        else state.localTimeoutVoteRequested,
                      localTimeoutVote = maybeLocalTimeoutVote,
                      localNewViewRequested =
                        state.localNewViewRequested || shouldEmitNewView,
                    )
                  val commands =
                    if shouldEmitNewView then
                      maybeTimeoutCertificate.fold(
                        Vector.empty[HotStuffPacemakerCommand],
                      ): timeoutCertificate =>
                        Vector(
                          HotStuffPacemakerCommand.EmitNewView(
                            sender = localValidator,
                            highestKnownQc = updated.highestKnownQc,
                            timeoutCertificate = timeoutCertificate,
                          ),
                        )
                    else Vector.empty[HotStuffPacemakerCommand]
                  step(
                    updated,
                    HotStuffPacemakerStepOutcome.Applied,
                    commands,
                    divergentTimeoutDiagnostics(updated),
                  )
          case HotStuffPacemakerWindowDisposition.Invalid =>
            HotStuffPolicyViolation(
              reason = "wrongPacemakerWindow",
              detail = Some(
                ss"expected=${renderWindow(state.activeWindow)} actual=${renderWindow(timeoutVote.subject.window)}",
              ),
            ).asLeft[HotStuffPacemakerStep]

  /** Processes an observed new-view message, potentially advancing the window. */
  def observeNewView(
      state: HotStuffPacemakerState,
      newView: NewView,
      now: Instant,
  ): Either[HotStuffPolicyViolation, HotStuffPacemakerStep] =
    HotStuffValidator
      .validateNewView(newView, validatorSet)
      .leftMap(validationFailureToPolicyViolation)
      .flatMap: _ =>
        classifyNextWindow(newView.window, state.activeWindow) match
          case HotStuffPacemakerWindowDisposition.Stale =>
            step(
              state,
              HotStuffPacemakerStepOutcome.Stale,
              Vector.empty[HotStuffPacemakerCommand],
              Vector.empty[HotStuffPacemakerDiagnostic],
            ).asRight[HotStuffPolicyViolation]
          case HotStuffPacemakerWindowDisposition.Expected =>
            val expectedWindow =
              HotStuffPacemaker.nextWindowAfter(state.activeWindow)
            if newView.window === expectedWindow then
              // Timeout-driven view changes keep the failed-timeout streak
              // alive; only non-timeout progress should reset the backoff.
              val updated =
                state.copy(
                  activeWindow = newView.window,
                  currentLeader = newView.nextLeader,
                  highestKnownQc = newView.highestKnownQc,
                  timeoutDeadline = now.plus(
                    timeoutFor(
                      newView.window,
                      state.consecutiveTimeoutWindows,
                    ),
                  ),
                  timeoutVotes = TimeoutVoteAccumulator.empty,
                  timeoutCertificate = None,
                  localTimeoutVoteRequested = false,
                  localTimeoutVote = None,
                  localNewViewRequested = false,
                  consecutiveTimeoutWindows = state.consecutiveTimeoutWindows,
                )
              step(
                updated,
                HotStuffPacemakerStepOutcome.AdvancedWindow,
                activationCommands(updated),
                Vector.empty[HotStuffPacemakerDiagnostic],
              ).asRight[HotStuffPolicyViolation]
            else
              HotStuffPolicyViolation(
                reason = "wrongPacemakerWindow",
                detail = Some(
                  ss"expected=${renderWindow(expectedWindow)} actual=${renderWindow(newView.window)}",
                ),
              ).asLeft[HotStuffPacemakerStep]
          case HotStuffPacemakerWindowDisposition.Invalid =>
            HotStuffPolicyViolation(
              reason = "wrongPacemakerWindow",
              detail = Some(
                ss"expected=${renderWindow(
                    HotStuffPacemaker.nextWindowAfter(state.activeWindow),
                  )} actual=${renderWindow(newView.window)}",
              ),
            ).asLeft[HotStuffPacemakerStep]

  /** Computes the timeout duration for a given window and consecutive timeout count. */
  def timeoutFor(
      window: HotStuffWindow,
      consecutiveTimeoutWindows: Int,
  ): Duration =
    val exponent =
      math.min(consecutiveTimeoutWindows, policy.maxBackoffExponent)
    val multiplier = 1L << exponent
    val jitterSlots =
      if policy.maxJitterSlots === 0 then 0L
      else
        Math
          .floorMod(
            ss"${window.chainId.value}:${window.height.render}:${window.view.render}:${localValidator.value}".hashCode,
            policy.maxJitterSlots + 1,
          )
          .toLong
    policy.baseTimeout
      .multipliedBy(multiplier)
      .plus(policy.jitterStep.multipliedBy(jitterSlots))

  /** Determines the local validator's proposal eligibility for the current state. */
  def proposalEligibility(
      state: HotStuffPacemakerState,
  ): HotStuffPacemakerProposalEligibility =
    state.bootstrapHoldReason match
      case Some(reason) =>
        HotStuffPacemakerProposalEligibility.BootstrapHeld(
          reason = reason,
          expectedLeader = state.currentLeader,
        )
      case None
          if state.localTimeoutVoteRequested || state.localTimeoutVote.nonEmpty =>
        HotStuffPacemakerProposalEligibility.TimeoutInProgress(
          expectedLeader = state.currentLeader,
        )
      case None if localValidator === state.currentLeader =>
        HotStuffPacemakerProposalEligibility.EligibleAsLeader(
          state.currentLeader,
        )
      case None =>
        HotStuffPacemakerProposalEligibility.Follower(state.currentLeader)

  private def activationCommands(
      state: HotStuffPacemakerState,
  ): Vector[HotStuffPacemakerCommand] =
    proposalEligibility(state) match
      case HotStuffPacemakerProposalEligibility.EligibleAsLeader(leader) =>
        Vector(
          HotStuffPacemakerCommand.ActivateLeader(
            window = state.activeWindow,
            leader = leader,
          ),
        )
      case _ =>
        Vector.empty[HotStuffPacemakerCommand]

  private enum HotStuffPacemakerWindowDisposition:
    case Expected, Stale, Invalid

  private def classifyCurrentWindow(
      candidate: HotStuffWindow,
      active: HotStuffWindow,
  ): HotStuffPacemakerWindowDisposition =
    compareWindow(candidate, active) match
      case Some(0)  => HotStuffPacemakerWindowDisposition.Expected
      case Some(-1) => HotStuffPacemakerWindowDisposition.Stale
      case Some(1)  => HotStuffPacemakerWindowDisposition.Invalid
      case _        => HotStuffPacemakerWindowDisposition.Invalid

  private def classifyNextWindow(
      candidate: HotStuffWindow,
      active: HotStuffWindow,
  ): HotStuffPacemakerWindowDisposition =
    compareWindow(candidate, active) match
      case Some(compare) if compare <= 0 =>
        HotStuffPacemakerWindowDisposition.Stale
      case Some(1) =>
        HotStuffPacemakerWindowDisposition.Expected
      case _ =>
        HotStuffPacemakerWindowDisposition.Invalid

  private def compareWindow(
      candidate: HotStuffWindow,
      active: HotStuffWindow,
  ): Option[Int] =
    if candidate.chainId =!= active.chainId ||
      candidate.validatorSetHash =!= active.validatorSetHash
    then None
    else
      val heightOrdering = summon[Ordering[HotStuffHeight]]
      val viewOrdering   = summon[Ordering[HotStuffView]]
      val heightCompare =
        heightOrdering.compare(candidate.height, active.height)
      if heightCompare =!= 0 then Some(java.lang.Integer.signum(heightCompare))
      else
        val viewCompare =
          viewOrdering.compare(candidate.view, active.view)
        Some(java.lang.Integer.signum(viewCompare))

  private def divergentTimeoutDiagnostics(
      state: HotStuffPacemakerState,
  ): Vector[HotStuffPacemakerDiagnostic] =
    val subjects =
      state.timeoutVotes.votesById.valuesIterator
        .filter(_.subject.window === state.activeWindow)
        .map(_.subject.highestKnownQc)
        .toSet
    if subjects.sizeCompare(1) > 0 then
      Vector(
        HotStuffPacemakerDiagnostic.DivergentTimeoutSubjects(
          window = state.activeWindow,
          subjects = subjects,
        ),
      )
    else Vector.empty

  private def timeoutDiagnostics(
      state: HotStuffPacemakerState,
  ): Vector[HotStuffPacemakerDiagnostic] =
    if state.consecutiveTimeoutWindows >= policy.elevatedTimeoutAlertThreshold
    then
      Vector(
        HotStuffPacemakerDiagnostic.ElevatedTimeoutAlert(
          window = state.activeWindow,
          consecutiveTimeoutWindows = state.consecutiveTimeoutWindows,
        ),
      )
    else Vector.empty

  private def step(
      state: HotStuffPacemakerState,
      outcome: HotStuffPacemakerStepOutcome,
      commands: Vector[HotStuffPacemakerCommand],
      diagnostics: Vector[HotStuffPacemakerDiagnostic],
  ): HotStuffPacemakerStep =
    HotStuffPacemakerStep(
      state = state,
      outcome = outcome,
      commands = commands,
      diagnostics = diagnostics,
      proposalEligibility = proposalEligibility(state),
    )

  private def validationFailureToPolicyViolation(
      failure: HotStuffValidationFailure,
  ): HotStuffPolicyViolation =
    HotStuffPolicyViolation(
      reason = failure.reason,
      detail = failure.detail,
    )

  private def renderWindow(
      window: HotStuffWindow,
  ): String =
    ss"${window.height.render}:${window.view.render}"

/** Companion for `HotStuffPacemakerRuntime`. */
object HotStuffPacemakerRuntime:
  /** Creates a pacemaker runtime with the default policy. */
  def default(
      localValidator: ValidatorId,
      validatorSet: ValidatorSet,
  ): HotStuffPacemakerRuntime =
    HotStuffPacemakerRuntime(
      localValidator = localValidator,
      validatorSet = validatorSet,
      policy = HotStuffPacemakerPolicy.default,
    )
