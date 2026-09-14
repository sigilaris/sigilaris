package org.sigilaris.node.jvm.runtime.application.v2

import cats.Monad
import cats.data.EitherT
import cats.effect.kernel.{Async, Outcome, Ref}
import cats.effect.std.Semaphore
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.node.gossip.ChainId
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{
  FinalizationTrackerSnapshot,
  FinalizedAnchorSuggestion,
  FinalizedAnchorSafetyFault,
  Vote,
}

/** Untrusted retained/backfilled finality, full plan, and complete state bytes.
  * Source/input/execution evidence is independently resolved by the configured
  * ApplicationRequestVerifier when this material is verified.
  */
final case class FinalizedApplicationMaterial(
    finalized: FinalizedAnchorSuggestion,
    plan: ExecutionPlan,
    statePayload: Bytes,
)

trait FinalizedApplicationHistory[F[_]]:
  def anchorAncestor(
      anchor: ApplicationAnchor,
      finalized: FinalizedAnchorSuggestion,
  ): Result[F, VerifiedInitialAnchorAncestor]

  def retained(
      context: DomainContext,
      blockId: Hash,
  ): Result[F, Option[FinalizedApplicationMaterial]]
  def backfill(
      context: DomainContext,
      blockId: Hash,
  ): Result[F, Option[FinalizedApplicationMaterial]]

  /** Resolve the actual current finalization tracker/history after restart.
    * Missing proof is unavailable; conflicting finality must return a failure.
    * This target remains separate from the application's selected frontier.
    */
  def latestFinalized(
      context: DomainContext,
  ): Result[F, Option[FinalizedAnchorSuggestion]]

final case class FinalizedApplicationCapacity(maximumCatchUpBlocks: Long)
final case class FinalizedApplicationProgress(
    targetBlockId: Hash,
    canonical: ApplicationAnchor,
    applied: Vector[ApplicationDecision],
)

/** Observation only. Neither a target id nor these status fields are a voting
  * capability. canonical is absent until an actual journal read/recovery.
  */
final case class FinalizedApplicationStatus(
    context: DomainContext,
    canonical: Option[ApplicationAnchor],
    lastRequestedBlockId: Option[Hash],
    failure: Option[V2RuntimeFailure],
)

sealed trait FinalizedApplicationRuntime[F[_]]:
  def context: DomainContext

  /** A certified installed execution parent is separate from status.canonical.
    */
  def workingParent: Option[ApplicationAnchor]
  def finalized(
      suggestion: FinalizedAnchorSuggestion,
  ): Result[F, FinalizedApplicationProgress]
  def recover: Result[F, FinalizedApplicationProgress]
  def status: F[FinalizedApplicationStatus]
  def canonicalPayload: Result[F, Option[(ApplicationAnchor, Bytes)]]
  def voteConsensus(
      voting: DurableApplicationVoting[F],
      request: VerifiedConsensusProposal,
  ): Result[F, Vote]
  def voteLock(
      voting: DurableApplicationVoting[F],
      request: VerifiedLockRequest,
  ): Result[F, LockVote]
  def voteEffect(
      voting: DurableApplicationVoting[F],
      request: VerifiedEffectRequest,
  ): Result[F, EffectVote]
  private[v2] def verifyActiveSigning(
      store: JournalSafetyStore[F],
      requestedContext: DomainContext,
      preimage: Bytes,
  ): Result[F, Unit]
  private[jvm] def withVotingPermission[A](
      store: JournalSafetyStore[F],
  )(run: Result[F, A]): Result[F, A]

  private[jvm] def withSigningPermission[A](
      store: JournalSafetyStore[F],
      requestedContext: DomainContext,
      preimage: Bytes,
  )(run: Result[F, A]): Result[F, A]

  /** Call after the consensus sink's actual Ref update, outside its update
    * closure. Application failure is retained in status; consensus finality is
    * not rolled back or relabelled as an unaccepted consensus artifact.
    */
  def observe(finalization: Map[ChainId, FinalizationTrackerSnapshot]): F[Unit]

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object FinalizedApplicationRuntime:
  private final case class ActiveSigning(
      context: DomainContext,
      preimage: Bytes,
  )
  private final case class FatalFinality(
      error: V2RuntimeFailure,
      authenticatedProofs: Vector[FinalizedAnchorSuggestion],
      trackerFaults: Vector[FinalizedAnchorSafetyFault],
  )
  private final case class Walk(
      next: Hash,
      child: Option[VerifiedApplicationBatch],
      descending: Vector[VerifiedApplicationBatch],
      visited: Set[Hash],
  )

  /** The current implementation is deliberately one installed application
    * context. Historical domain ranges require an authenticated profile/range
    * provider; no arbitrary historical manifest is accepted through this API.
    */
  def authenticated[F[_]: Async](
      safety: JournalSafetyStore[F],
      requests: ApplicationRequestVerifier[F],
      verifier: ApplicationCommitVerifier[F],
      history: FinalizedApplicationHistory[F],
      capacity: FinalizedApplicationCapacity,
  ): F[FinalizedApplicationRuntime[F]] =
    configured(safety, requests, verifier, history, capacity, None)

  /** Accepts only the closed runtime's authenticated installed handover. Its
    * original canonical ledger must reach P through actual finality before the
    * first new-context ApplicationPrepare can publish B.
    */
  def handover[F[_]: Async](
      safety: JournalSafetyStore[F],
      requests: ApplicationRequestVerifier[F],
      verifier: ApplicationCommitVerifier[F],
      history: FinalizedApplicationHistory[F],
      capacity: FinalizedApplicationCapacity,
      historical: HistoricalCanonicalRuntime[F],
  ): F[FinalizedApplicationRuntime[F]] =
    configured(safety, requests, verifier, history, capacity, Some(historical))

  private def configured[F[_]: Async](
      safety: JournalSafetyStore[F],
      requests: ApplicationRequestVerifier[F],
      verifier: ApplicationCommitVerifier[F],
      history: FinalizedApplicationHistory[F],
      capacity: FinalizedApplicationCapacity,
      historical: Option[HistoricalCanonicalRuntime[F]],
  ): F[FinalizedApplicationRuntime[F]] = for
    gate     <- Semaphore[F](1L)
    highest  <- Ref.of[F, Option[FinalizedAnchorSuggestion]](None)
    pending  <- Ref.of[F, Map[BigInt, FinalizedAnchorSuggestion]](Map.empty)
    fatal    <- Ref.of[F, Option[FatalFinality]](None)
    observed <- Ref.of[F, FinalizedApplicationStatus](
      FinalizedApplicationStatus(safety.context, None, None, None),
    )
    activeSigning         <- Ref.of[F, Option[ActiveSigning]](None)
    successfulObservation <- Ref
      .of[F, Option[(FinalizedAnchorSuggestion, FinalizedApplicationProgress)]](
        None,
      )
  yield new FinalizedApplicationRuntime[F]:
    private val application    = RecoverableApplicationStore.journaled(safety)
    def context: DomainContext = safety.context
    def workingParent: Option[ApplicationAnchor] =
      historical.map(_.base.workingParent)
    def voteConsensus(
        voting: DurableApplicationVoting[F],
        request: VerifiedConsensusProposal,
    ): Result[F, Vote] =
      withSigningPermission(
        safety,
        request.context,
        Vote.signBytes(request.unsignedVote),
      )(
        voting.prepareConsensusVote(request).flatMap(voting.signConsensusVote),
      )
    def voteLock(
        voting: DurableApplicationVoting[F],
        request: VerifiedLockRequest,
    ): Result[F, LockVote] =
      EitherT
        .fromEither[F](
          RuntimeCheck.core(LockSubject.signingPreimage(request.subject)),
        )
        .flatMap(preimage =>
          withSigningPermission(safety, request.subject.context, preimage)(
            voting.voteLock(request),
          ),
        )
    def voteEffect(
        voting: DurableApplicationVoting[F],
        request: VerifiedEffectRequest,
    ): Result[F, EffectVote] =
      EitherT
        .fromEither[F](
          RuntimeCheck.core(EffectSubject.signingPreimage(request.subject)),
        )
        .flatMap(preimage =>
          withSigningPermission(safety, request.subject.context, preimage)(
            voting.voteEffect(request),
          ),
        )
    private[v2] def verifyActiveSigning(
        store: JournalSafetyStore[F],
        requestedContext: DomainContext,
        preimage: Bytes,
    ): Result[F, Unit] =
      EitherT
        .liftF(activeSigning.get)
        .flatMap(lease =>
          check(
            lease.contains(
              ActiveSigning(requestedContext, preimage),
            ) && (store eq safety),
            RuntimeFailureCode.RecoveryRequired,
            "actual key use has no matching active finalized-application signing lease",
          ),
        )
    def status: F[FinalizedApplicationStatus] = observed.get.flatMap(previous =>
      fatal.get.map(
        _.fold(previous)(fault => previous.copy(failure = Some(fault.error))),
      ),
    )
    private def check(
        condition: Boolean,
        code: RuntimeFailureCode,
        detail: String,
    ): Result[F, Unit] =
      EitherT.fromEither[F](RuntimeCheck.require(condition, code, detail))
    private def present[A](value: Option[A], detail: String): Result[F, A] =
      EitherT.fromOption[F](
        value,
        V2RuntimeFailure.at(RuntimeFailureCode.ProofUnavailable, detail),
      )

    private def current(state: SafetyState): Result[F, ApplicationAnchor] =
      state.canonical match
        case None           => EitherT.pure(safety.anchor)
        case Some(decision) =>
          present(
            state.preparations.get(decision.batchDigest),
            "canonical decision has no original prepared batch",
          )
            .map(prepared =>
              ApplicationAnchor(
                context,
                decision.blockId,
                prepared.batch.candidateHeight,
                prepared.batch.nextStateRoot,
              ),
            )

    private def installedBoundary: Result[F, Unit] = historical.traverse_(old =>
      check(
        old.base.executionAnchor == safety.anchor && old.base.targetContext == context && (old.base.activation.journal eq safety.journal),
        RuntimeFailureCode.StartupIdentityMismatch,
        "historical working parent differs from the actual target execution store",
      ),
    )

    private def canonical(state: SafetyState): Result[F, ApplicationAnchor] =
      if state.canonical.isEmpty then
        historical.fold(current(state))(_.canonical)
      else current(state)

    private def oldTarget(suggestion: FinalizedAnchorSuggestion): Boolean =
      historical.exists(old =>
        suggestion.anchorHeight.toBigNat.toBigInt <= old.base.workingParent.height.toBigNat.toBigInt,
      )

    def canonicalPayload: Result[F, Option[(ApplicationAnchor, Bytes)]] =
      withVotingPermission(safety)(for
        _        <- historical.traverse_(_.recover)
        selected <- application.canonicalPayload
        payload  <- selected match
          case Some((_, bytes)) =>
            safety.snapshot
              .flatMap(current)
              .map(anchor => Some(anchor -> bytes))
          case None =>
            historical.traverse(old =>
              (old.canonical, old.canonicalPayload).mapN(_ -> _),
            )
      yield payload)

    private def fatalFailure: Result[F, Unit] =
      EitherT.liftF(fatal.get).flatMap {
        case None          => EitherT.pure(())
        case Some(failure) => EitherT.leftT(failure.error)
      }

    private def remember(
        suggestion: FinalizedAnchorSuggestion,
    ): Result[F, Unit] = for
      remembered <- EitherT.liftF(pending.get)
      previous = remembered.get(suggestion.anchorHeight.toBigNat.toBigInt)
      _ <- previous match
        case Some(known) if known.anchorBlockId != suggestion.anchorBlockId =>
          val error = V2RuntimeFailure.at(
            RuntimeFailureCode.ProofInvalid,
            "authenticated pending finality targets conflict at the same height",
          )
          EitherT.liftF(
            fatal.update(
              _.orElse(
                Some(
                  FatalFinality(error, Vector(known, suggestion), Vector.empty),
                ),
              ),
            ),
          ) *> fatalFailure
        case _ => EitherT.pure[F, V2RuntimeFailure](())
      _ <- EitherT.liftF(
        pending.update(rows =>
          rows.updated(
            suggestion.anchorHeight.toBigNat.toBigInt,
            previous.getOrElse(suggestion),
          ),
        ),
      )
      known <- EitherT.liftF(highest.get)
      _     <-
        if known.forall(value =>
            value.anchorHeight.toBigNat.toBigInt < suggestion.anchorHeight.toBigNat.toBigInt,
          )
        then EitherT.liftF(highest.set(Some(suggestion)))
        else EitherT.pure[F, V2RuntimeFailure](())
    yield ()

    private def pastTarget(
        state: SafetyState,
        head: ApplicationAnchor,
        target: VerifiedCandidateBlock,
    ): Result[F, Unit] =
      if target.height.toBigNat.toBigInt > head.height.toBigNat.toBigInt then
        EitherT.pure(())
      else
        val selected =
          (target.blockId == safety.anchor.blockId && target.height == safety.anchor.height && target.finalized.proposal.block.stateRoot.toUInt256 == safety.anchor.stateRoot) || state.decisions.valuesIterator
            .exists(decision =>
              decision.blockId == target.blockId && state.preparations
                .get(decision.batchDigest)
                .exists(prepared =>
                  prepared.batch.candidateHeight == target.height && prepared.batch.nextStateRoot == target.finalized.proposal.block.stateRoot.toUInt256,
                ),
            )
        if selected then EitherT.pure(())
        else
          val error = V2RuntimeFailure.at(
            RuntimeFailureCode.ProofInvalid,
            "authenticated past finality conflicts with selected canonical application history",
          )
          for
            known <- EitherT.liftF(highest.get)
            _     <- EitherT.liftF(
              fatal.update(
                _.orElse(
                  Some(
                    FatalFinality(
                      error,
                      known.toList.toVector :+ target.finalized,
                      Vector.empty,
                    ),
                  ),
                ),
              ),
            )
            _ <- fatalFailure
          yield ()

    private def notice(suggestion: FinalizedAnchorSuggestion): Result[F, Unit] =
      if oldTarget(suggestion) then
        historical.traverse_(_.validateTarget(suggestion)) *> remember(
          suggestion,
        )
      else if suggestion.anchorHeight.toBigNat.toBigInt < safety.anchor.height.toBigNat.toBigInt
      then
        history
          .anchorAncestor(safety.anchor, suggestion)
          .flatMap(proof =>
            check(
              proof.anchor == safety.anchor && proof.finalized == suggestion,
              RuntimeFailureCode.ProofInvalid,
              "historical finality capability differs from the signing context",
            ),
          )
      else
        for
          _      <- historical.traverse_(_.validateTarget(suggestion))
          target <- verifier.verifyCandidate(suggestion)
          _      <- check(
            target.context == context,
            RuntimeFailureCode.DomainMismatch,
            "latest actual finality differs from the voting context",
          )
          state <- safety.snapshot
          head  <- current(state)
          _     <- pastTarget(state, head, target)
          _     <-
            if target.height.toBigNat.toBigInt > head.height.toBigNat.toBigInt
            then remember(suggestion)
            else EitherT.pure[F, V2RuntimeFailure](())
        yield ()

    private def preparedSatisfied(
        state: SafetyState,
        head: ApplicationAnchor,
        code: RuntimeFailureCode,
    ): Result[F, Unit] =
      check(
        state.preparations.valuesIterator.forall(prepared =>
          prepared.batch.candidateHeight.toBigNat.toBigInt < head.height.toBigNat.toBigInt || (prepared.batch.candidateHeight == head.height && state.decisions
            .get(prepared.batchDigest)
            .exists(_.blockId == head.blockId)),
        ),
        code,
        "durably prepared finalized application is still awaiting its canonical decision",
      )

    private def pendingSatisfied(head: ApplicationAnchor): Result[F, Unit] = for
      known <- EitherT.liftF(highest.get)
      _     <- check(
        known.forall(target =>
          target.anchorHeight.toBigNat.toBigInt < head.height.toBigNat.toBigInt || (target.anchorHeight.toBigNat == head.height.toBigNat && target.anchorBlockId.toUInt256 == head.blockId),
        ),
        RuntimeFailureCode.RecoveryRequired,
        "authenticated finalized target is still awaiting canonical materialization",
      )
    yield ()

    private[jvm] def withVotingPermission[A](
        store: JournalSafetyStore[F],
    )(run: Result[F, A]): Result[F, A] = guarded(store, None)(run)

    private[jvm] def withSigningPermission[A](
        store: JournalSafetyStore[F],
        requestedContext: DomainContext,
        preimage: Bytes,
    )(run: Result[F, A]): Result[F, A] =
      check(
        requestedContext == context,
        RuntimeFailureCode.DomainMismatch,
        "signing lease differs from installed context",
      ) *> guarded(store, Some(ActiveSigning(requestedContext, preimage)))(run)

    private def guarded[A](
        store: JournalSafetyStore[F],
        lease: Option[ActiveSigning],
    )(run: Result[F, A]): Result[F, A] = EitherT(
      gate.permit.use(_ =>
        Async[F].guaranteeCase((for
          _ <- check(
            store eq safety,
            RuntimeFailureCode.UnsafeBoundary,
            "voting and finalized application must share the same safety store",
          )
          _             <- fatalFailure
          _             <- installedBoundary
          latest        <- history.latestFinalized(context)
          _             <- latest.traverse_(notice)
          observedState <- EitherT.liftF(observed.get)
          _ <- observedState.failure.fold[Result[F, Unit]](EitherT.pure(()))(
            error => EitherT.leftT(error),
          )
          state           <- safety.snapshot
          head            <- current(state)
          actualCanonical <- canonical(state)
          _               <- check(
            observedState.context == context && observedState.canonical
              .contains(actualCanonical),
            RuntimeFailureCode.RecoveryRequired,
            "finalized application readiness differs from actual canonical journal state",
          )
          _ <- pendingSatisfied(head)
          _ <- preparedSatisfied(
            state,
            head,
            RuntimeFailureCode.RecoveryRequired,
          )
          value <- EitherT(
            Async[F].guarantee(
              activeSigning.set(lease) *> run.value,
              activeSigning.set(None),
            ),
          )
        yield value).value) {
          // A failed/cancelled vote may leave a durable recovery obligation.
          // Clear the observer memo before releasing the shared gate, even if
          // the last finalized target and its status have not changed.
          case Outcome.Succeeded(result) =>
            result.flatMap(value =>
              if value.isLeft then successfulObservation.set(None)
              else Async[F].unit,
            )
          case _ => successfulObservation.set(None)
        },
      ),
    )

    private def underGate(
        target: Option[Hash],
        observation: Option[FinalizedAnchorSuggestion],
    )(
        operation: Result[F, FinalizedApplicationProgress],
    ): Result[F, FinalizedApplicationProgress] = EitherT(
      gate.permit.use(_ =>
        (for
          cached   <- EitherT.liftF(successfulObservation.get)
          _        <- EitherT.liftF(successfulObservation.set(None))
          _        <- fatalFailure
          previous <- EitherT.liftF(observed.get)
          waiting  <- EitherT.liftF(pending.get)
          // This only suppresses a completed, identical observer notification.
          // Explicit recovery/finalized calls and all signing checks stay fresh.
          reusable = cached.filter((suggestion, _) =>
            observation.contains(
              suggestion,
            ) && previous.failure.isEmpty && waiting.isEmpty,
          )
          progress <- reusable.fold(operation)(value =>
            EitherT.pure[F, V2RuntimeFailure](value._2),
          )
        yield progress).value.attempt.flatMap {
          case Right(result) =>
            result match
              case Right(progress) =>
                observed
                  .set(
                    FinalizedApplicationStatus(
                      context,
                      Some(progress.canonical),
                      Some(progress.targetBlockId),
                      None,
                    ),
                  )
                  .flatTap(_ =>
                    successfulObservation.set(observation.map(_ -> progress)),
                  )
                  .as(result)
              case Left(error) =>
                observed
                  .update(previous =>
                    previous.copy(
                      lastRequestedBlockId =
                        target.orElse(previous.lastRequestedBlockId),
                      failure = Some(error),
                    ),
                  )
                  .as(result)
          case Left(_) =>
            val error = V2RuntimeFailure.at(
              RuntimeFailureCode.StorageUnknown,
              "finalized application source or verifier failed",
            )
            observed
              .update(previous =>
                previous.copy(
                  lastRequestedBlockId =
                    target.orElse(previous.lastRequestedBlockId),
                  failure = Some(error),
                ),
              )
              .as(Left[V2RuntimeFailure, FinalizedApplicationProgress](error))
        },
      ),
    )

    private def material(block: Hash): Result[F, FinalizedApplicationMaterial] =
      for
        local    <- history.retained(context, block)
        resolved <- local.fold(history.backfill(context, block))(value =>
          EitherT.pure[F, V2RuntimeFailure](Some(value)),
        )
        value <- present(
          resolved,
          "complete finalized plan/source/state material is unavailable",
        )
        _ <- check(
          value.finalized.anchorBlockId.toUInt256 == block,
          RuntimeFailureCode.ProofInvalid,
          "finalized history returned another block",
        )
      yield value

    private def verifiedMaterial(
        block: Hash,
    ): Result[F, VerifiedApplicationBatch] = for
      retained <- material(block)
      request  <- requests.verifyProposal(
        retained.finalized.proposal,
        retained.plan,
      )
      verified <- verifier.verifyBatch(
        request,
        retained.finalized,
        retained.statePayload,
      )
      _ <- check(
        verified.batch.context == context && verified.candidate.context == context && verified.candidate.blockId == block &&
          verified.request.proposal == retained.finalized.proposal && verified.request.plan == retained.plan,
        RuntimeFailureCode.DomainMismatch,
        "finalized application differs from the installed context or retrieved proposal",
      )
    yield verified

    private def validatePending(
        head: ApplicationAnchor,
        tip: VerifiedCandidateBlock,
        ordered: Vector[VerifiedApplicationBatch],
    ): Result[F, Unit] = for
      known <- EitherT.liftF(pending.get)
      _     <- known.toVector.sortBy(_._1).traverse_ { (_, target) =>
        if target.anchorHeight.toBigNat.toBigInt <= head.height.toBigNat.toBigInt
        then notice(target)
        else if ordered.exists(batch =>
            batch.batch.candidateHeight.toBigNat == target.anchorHeight.toBigNat && batch.candidate.blockId == target.anchorBlockId.toUInt256,
          )
        then EitherT.pure[F, V2RuntimeFailure](())
        else
          val error = V2RuntimeFailure.at(
            RuntimeFailureCode.ProofInvalid,
            "complete catch-up path conflicts with another authenticated pending finality target",
          )
          EitherT.liftF(
            fatal.update(
              _.orElse(
                Some(
                  FatalFinality(
                    error,
                    Vector(target, tip.finalized),
                    Vector.empty,
                  ),
                ),
              ),
            ),
          ) *> fatalFailure
      }
    yield ()

    private def collect(
        head: ApplicationAnchor,
        tip: VerifiedCandidateBlock,
    ): Result[F, Vector[VerifiedApplicationBatch]] =
      for
        _ <- check(
          capacity.maximumCatchUpBlocks > 0L && tip.height.toBigNat.toBigInt - head.height.toBigNat.toBigInt <= BigInt(
            capacity.maximumCatchUpBlocks,
          ),
          RuntimeFailureCode.CapacityUnavailable,
          "local finalized application catch-up capacity is exhausted",
        )
        descending <- Monad[[A] =>> Result[F, A]]
          .tailRecM(Walk(tip.blockId, None, Vector.empty, Set.empty)) { walk =>
            for
              _ <- check(
                !walk.visited.contains(walk.next),
                RuntimeFailureCode.ProofInvalid,
                "finalized application history cycles",
              )
              _ <- check(
                walk.descending.size.toLong < capacity.maximumCatchUpBlocks,
                RuntimeFailureCode.CapacityUnavailable,
                "local finalized application catch-up capacity is exhausted",
              )
              verified <- verifiedMaterial(walk.next)
              _        <- check(
                verified.batch.candidateHeight.toBigNat.toBigInt > head.height.toBigNat.toBigInt,
                RuntimeFailureCode.ProofInvalid,
                "finalized application history does not descend to the selected canonical frontier",
              )
              _ <- walk.child match
                case None =>
                  check(
                    verified.candidate.finalized.proposal == tip.finalized.proposal,
                    RuntimeFailureCode.ProofInvalid,
                    "retrieved finality material names a different complete target proposal",
                  )
                case Some(child) =>
                  check(
                    child.batch.parentBlockId == verified.candidate.blockId && child.batch.priorStateRoot == verified.batch.nextStateRoot &&
                      child.batch.candidateHeight.toBigNat.toBigInt == verified.batch.candidateHeight.toBigNat.toBigInt + 1,
                    RuntimeFailureCode.ProofInvalid,
                    "finalized application parent, height or state-root continuity is broken",
                  )
              descending = walk.descending :+ verified
              next <-
                if verified.batch.parentBlockId == head.blockId then
                  check(
                    verified.batch.priorStateRoot == head.stateRoot && verified.batch.candidateHeight.toBigNat.toBigInt == head.height.toBigNat.toBigInt + 1,
                    RuntimeFailureCode.ProofInvalid,
                    "finalized application does not extend the actual canonical state and height",
                  )
                    .as(
                      Right[Walk, Vector[VerifiedApplicationBatch]](descending),
                    )
                else
                  EitherT.pure[F, V2RuntimeFailure](
                    Left[Walk, Vector[VerifiedApplicationBatch]](
                      Walk(
                        verified.batch.parentBlockId,
                        Some(verified),
                        descending,
                        walk.visited + walk.next,
                      ),
                    ),
                  )
            yield next
          }
        ordered = descending.reverse
        _ <- validatePending(head, tip, ordered)
      yield ordered

    private def applyTarget(
        suggestion: FinalizedAnchorSuggestion,
    ): Result[F, FinalizedApplicationProgress] =
      if oldTarget(suggestion) then
        for
          _     <- historical.traverse_(_.catchUp(suggestion))
          state <- safety.snapshot
          head  <- canonical(state)
        yield FinalizedApplicationProgress(
          suggestion.anchorBlockId.toUInt256,
          head,
          Vector.empty,
        )
      else if suggestion.anchorHeight.toBigNat.toBigInt < safety.anchor.height.toBigNat.toBigInt
      then
        for
          proof <- history.anchorAncestor(safety.anchor, suggestion)
          _     <- check(
            proof.anchor == safety.anchor && proof.finalized == suggestion,
            RuntimeFailureCode.ProofInvalid,
            "initial anchor ancestry capability names another target or anchor",
          )
          _     <- remember(suggestion)
          state <- safety.snapshot
          head  <- current(state)
        yield FinalizedApplicationProgress(
          suggestion.anchorBlockId.toUInt256,
          head,
          Vector.empty,
        )
      else applyCurrentTarget(suggestion)

    private def applyCurrentTarget(
        suggestion: FinalizedAnchorSuggestion,
    ): Result[F, FinalizedApplicationProgress] = for
      target <- verifier.verifyCandidate(suggestion)
      _      <- check(
        target.context == context,
        RuntimeFailureCode.DomainMismatch,
        "finalized target differs from installed application context",
      )
      _       <- remember(suggestion)
      state   <- safety.snapshot
      head    <- current(state)
      _       <- pastTarget(state, head, target)
      applied <-
        if target.height.toBigNat.toBigInt <= head.height.toBigNat.toBigInt then
          EitherT.pure[F, V2RuntimeFailure](Vector.empty[ApplicationDecision])
        else
          for
            ordered <- collect(head, target)
            _       <- historical.traverse_(old =>
              old
                .catchUp(suggestion)
                .flatMap(frontier =>
                  check(
                    frontier == old.base.workingParent,
                    RuntimeFailureCode.RecoveryRequired,
                    "actual old canonical history must reach P before new-profile application preparation",
                  ),
                ),
            )
            decisions <- ordered.traverse(verified =>
              for
                prepared  <- application.prepare(verified)
                committed <- application.commit(prepared, verified.candidate)
              yield committed,
            )
          yield decisions
      after         <- safety.snapshot
      canonicalHead <- canonical(after)
    yield FinalizedApplicationProgress(target.blockId, canonicalHead, applied)

    private def driveLatest: Result[F, FinalizedApplicationProgress] = for
      latest <- history.latestFinalized(context)
      _      <- latest.traverse_(notice)
      known  <- EitherT.liftF(pending.get)
      _      <- check(
        latest.nonEmpty || known.isEmpty,
        RuntimeFailureCode.ProofUnavailable,
        "actual latest finality history is missing authenticated pending targets",
      )
      state           <- safety.snapshot
      _               <- current(state)
      actualCanonical <- canonical(state)
      selected        <- EitherT.liftF(highest.get)
      progress        <- selected.fold[Result[F, FinalizedApplicationProgress]](
        EitherT.pure(
          FinalizedApplicationProgress(
            actualCanonical.blockId,
            actualCanonical,
            Vector.empty,
          ),
        ),
      )(applyTarget)
      after         <- safety.snapshot
      executionHead <- current(after)
      _             <- preparedSatisfied(
        after,
        executionHead,
        RuntimeFailureCode.ProofUnavailable,
      )
      _ <- pendingSatisfied(executionHead)
      _ <- EitherT.liftF(
        pending.update(
          _.filter((height, _) =>
            height > executionHead.height.toBigNat.toBigInt,
          ),
        ),
      )
    yield progress

    def finalized(
        suggestion: FinalizedAnchorSuggestion,
    ): Result[F, FinalizedApplicationProgress] =
      underGate(Some(suggestion.anchorBlockId.toUInt256), None)(
        finalizeTarget(suggestion),
      )

    private def finalizeTarget(
        suggestion: FinalizedAnchorSuggestion,
    ): Result[F, FinalizedApplicationProgress] =
      installedBoundary *> safety.snapshot.void.leftFlatMap { error =>
        if error.code == RuntimeFailureCode.RecoveryRequired then
          application.recover.void
        else EitherT.leftT[F, Unit](error)
      } *> notice(suggestion) *> driveLatest

    def recover: Result[F, FinalizedApplicationProgress] =
      underGate(None, None)(
        installedBoundary *> historical.traverse_(
          _.recover,
        ) *> application.recover *> driveLatest,
      )

    def observe(
        finalization: Map[ChainId, FinalizationTrackerSnapshot],
    ): F[Unit] =
      finalization.toVector
        .find(_._1.value == context.chainId.asString)
        .fold(Async[F].unit) { (_, snapshot) =>
          if snapshot.safetyFaults.nonEmpty then
            val error = V2RuntimeFailure.at(
              RuntimeFailureCode.ProofInvalid,
              "actual consensus tracker reports conflicting finality",
            )
            gate.permit.use(_ =>
              fatal.update(
                _.orElse(
                  Some(
                    FatalFinality(error, Vector.empty, snapshot.safetyFaults),
                  ),
                ),
              ) *>
                observed.update(_.copy(failure = Some(error))),
            )
          else
            snapshot.bestFinalized.fold(Async[F].unit)(target =>
              underGate(Some(target.anchorBlockId.toUInt256), Some(target))(
                finalizeTarget(target),
              ).value.void,
            )
        }
        .handleErrorWith(_ =>
          gate.permit.use(_ =>
            observed.update(
              _.copy(failure =
                Some(
                  V2RuntimeFailure.at(
                    RuntimeFailureCode.StorageUnknown,
                    "finalized application observer failed after consensus acceptance",
                  ),
                ),
              ),
            ),
          ),
        )
