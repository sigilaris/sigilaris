package org.sigilaris.conformance

import java.io.IOException
import java.nio.file.{Files, Path}
import java.time.Instant
import scala.jdk.CollectionConverters.*

import cats.data.EitherT
import cats.effect.{IO, Outcome, Ref, Resource}
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.{
  ApplicationValidatorId,
  InclusionHeight,
}
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.node.gossip.GossipClock
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.node.jvm.runtime.block.BlockId
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
import org.sigilaris.node.jvm.storage.file.FileApplicationJournal
import org.sigilaris.node.txpipeline.v2.*

/** Public integration: real signed proposals/three-chain tracker, actual sink,
  * complete state backfill, and the same recoverable application journal. Four
  * independently voting runtimes are exercised in a separate fixture.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.OptionPartial",
  ),
)
object V2FinalizedApplicationConformance:
  import V2RequestConformance.*
  import V2VotingConformance.{accepted, rejected}

  private def check(condition: Boolean, message: String): IO[Unit] =
    IO.raiseUnless(condition)(new IllegalStateException(message))

  final class History(
      val f: V2ExactFixture.Material,
      val retainedValues: Ref[IO, Map[Hash, FinalizedApplicationMaterial]],
      val backfillValues: Ref[IO, Map[Hash, FinalizedApplicationMaterial]],
      val selected: Ref[IO, Option[FinalizedAnchorSuggestion]],
      val calls: Ref[IO, Long],
      val latestReads: Ref[IO, Long],
  ) extends FinalizedApplicationHistory[IO]:
    def anchorAncestor(
        anchor: ApplicationAnchor,
        finalized: FinalizedAnchorSuggestion,
    ): Result[IO, VerifiedInitialAnchorAncestor] = unavailable(
      "this fixture has no authenticated pre-anchor history",
    )
    def retained(
        context: DomainContext,
        block: Hash,
    ): Result[IO, Option[FinalizedApplicationMaterial]] =
      if context == f.context then
        EitherT.liftF(retainedValues.get.map(_.get(block)))
      else unavailable("finalized retained context")
    def backfill(
        context: DomainContext,
        block: Hash,
    ): Result[IO, Option[FinalizedApplicationMaterial]] =
      if context == f.context then
        EitherT.liftF(
          calls.update(_ + 1L) *> backfillValues.get.map(_.get(block)),
        )
      else unavailable("finalized backfill context")
    def latestFinalized(
        context: DomainContext,
    ): Result[IO, Option[FinalizedAnchorSuggestion]] =
      if context == f.context then
        EitherT.liftF(latestReads.update(_ + 1L) *> selected.get)
      else unavailable("finalized selected context")
    val branch = Vector(f.ordered) ++ f.orderedTail
    val materials: Vector[FinalizedApplicationMaterial] = branch.indices
      .dropRight(2)
      .map(index =>
        FinalizedApplicationMaterial(
          HotStuffFinalizationTracker
            .track(branch.slice(index, index + 3).map(_.proposal))
            .bestFinalized
            .get,
          branch(index).plan,
          branch(index).statePayload,
        ),
      )
      .toVector
    val all: Map[Hash, FinalizedApplicationMaterial] = materials
      .map(material => material.finalized.anchorBlockId.toUInt256 -> material)
      .toMap
    val tip: FinalizedAnchorSuggestion = HotStuffFinalizationTracker
      .track(branch.map(_.proposal))
      .bestFinalized
      .get
    def available: IO[Unit] = backfillValues.set(all)
    def choose: IO[Unit]    = selected.set(Some(tip))

  private def history(f: V2ExactFixture.Material): IO[History] = for
    local    <- Ref.of[IO, Map[Hash, FinalizedApplicationMaterial]](Map.empty)
    archive  <- Ref.of[IO, Map[Hash, FinalizedApplicationMaterial]](Map.empty)
    selected <- Ref.of[IO, Option[FinalizedAnchorSuggestion]](None)
    calls    <- Ref.of[IO, Long](0L)
    latestReads <- Ref.of[IO, Long](0L)
  yield new History(f, local, archive, selected, calls, latestReads)

  final case class Environment(
      f: V2ExactFixture.Material,
      history: History,
      safety: JournalSafetyStore[IO],
      runtime: FinalizedApplicationRuntime[IO],
      canonical: Ref[IO, (Hash, Hash)],
  ):
    def admit: IO[ExactPipelineRecord] = accepted(
      JournalExactPlanStore
        .journaled(safety, f.plans, f.manifest)
        .admit(f.request),
    ).flatTap(f.retainAdmission)
    def materialized: IO[Unit] = for
      state <- accepted(safety.snapshot)
      tip   = state.canonical.get
      batch = state.preparations(tip.batchDigest).batch
      _ <- check(
        tip.blockId == history.tip.anchorBlockId.toUInt256 && batch.candidateHeight == height(
          10L,
        ) && batch.nextStateRoot == f.consumerExecution.afterRoot,
        "actual canonical catch-up ends at height ten and complete state",
      )
      _ <- check(
        state.decisions.size == 5 && state.appliedEntries.size == 2 && state.intents.isEmpty && state.consensusIntents.isEmpty,
        "nonvoting node materializes entries once and empty blocks without a vote",
      )
      pipeline <- accepted(
        JournalExactPlanStore
          .journaled(safety, f.plans, f.manifest)
          .snapshot,
      )
      _ <- check(
        pipeline.records.size == 1 && pipeline.records.forall(
          _.stages.forall(_.lifecycle == ExactStageLifecycle.Materialized),
        ),
        "actual canonical exact stages materialized",
      )
    yield ()

  private def environment(
      f: V2ExactFixture.Material,
      history: History,
      journal: DurableJournal[IO],
      capacity: Long = 16L,
  ): IO[Environment] = for
    canonical <- Ref.of[IO, (Hash, Hash)](
      f.anchor.blockId -> f.anchor.stateRoot,
    )
    verifier = ApplicationCommitVerifier.authenticated(
      f.anchor,
      f.requests,
      f.validatorLookup,
      f.stateAuthentication,
    )
    publication = new SafetyPublication[IO]:
      def finalizedHeight(context: DomainContext): Result[IO, Height] =
        if context == f.context then
          EitherT.liftF(
            history.selected.get.map(
              _.fold(f.anchor.height)(suggestion =>
                InclusionHeight(suggestion.anchorHeight.toBigNat),
              ),
            ),
          )
        else unavailable("finalized publication context")
      def verifyEffectState(request: VerifiedEffectRequest): Result[IO, Unit] =
        unavailable("nonvoting finalization fixture has no effect votes")
      def verifyConsensusState(
          request: VerifiedConsensusProposal,
      ): Result[IO, Unit] = EitherT(
        canonical.get.map((parent, root) =>
          Either.cond(
            request.context == f.context && request.parentBlockId == parent && request.parentStateRoot == root && f.allCandidates
              .exists(candidate =>
                candidate.proposal == request.proposal && candidate.plan == request.plan,
              ),
            (),
            V2RuntimeFailure.at(
              RuntimeFailureCode.ProofInvalid,
              "actual materialized parent changed",
            ),
          ),
        ),
      )
    safety <- accepted(
      JournalSafetyStore.open(
        f.anchor,
        journal,
        publication,
        SafetyProfile(f.manifest, f.artifacts),
        ExactRecoveryAuthentication
          .application(f.requests, verifier, f.plans, f.manifest),
        ReservationOrdering.isolated[IO],
        SafetyCapacity.unbounded,
      ),
    )
    runtime <- FinalizedApplicationRuntime.authenticated(
      safety,
      f.requests,
      verifier,
      history,
      FinalizedApplicationCapacity(capacity),
    )
  yield Environment(f, history, safety, runtime, canonical)

  private def fixture(nonce: Long): IO[V2ExactFixture.Material] =
    V2ExactFixture.material(
      ExactMode.OrderedAtomic,
      false,
      false,
      nonce,
      height(6L),
      false,
      false,
    )

  def actualSinkBackfill(): IO[Unit] =
    val timestamp         = Instant.parse("2026-09-11T07:00:00Z")
    given GossipClock[IO] = GossipClock.constant[IO](timestamp)
    for
      f       <- fixture(401L)
      history <- history(f)
      journal <- MemoryDurableJournal.create[IO]
      env     <- environment(f, history, journal)
      _       <- env.admit
      _       <- accepted(env.runtime.recover)
      source  <- InMemoryHotStuffArtifactSource.create[IO]
      sink    <- InMemoryHotStuffArtifactSink
        .createWithValidationAndFinalizationObserver[IO](
          f.validators,
          HotStuffRelayPolicy.testLocalOnly,
          source,
          HotStuffArtifactSinkRetention.default,
          proposal =>
            f.allCandidates
              .find(_.proposal == proposal)
              .fold(
                IO.pure(
                  Left[HotStuffValidationFailure, Unit](
                    HotStuffValidationFailure("unknown actual proposal", None),
                  ),
                ),
              )(candidate =>
                f.requests
                  .verifyProposal(proposal, candidate.plan)
                  .value
                  .map(
                    _.bimap(
                      error => HotStuffValidationFailure(error.message, None),
                      _ => (),
                    ),
                  ),
              ),
          observed =>
            history.selected.set(
              observed.get(f.chain).flatMap(_.bestFinalized),
            ) *> env.runtime.observe(observed),
        )
      _ <- history.branch.traverse_(candidate =>
        source
          .append(
            HotStuffGossipArtifact.ProposalArtifact(candidate.proposal),
            timestamp,
          )
          .flatMap(sink.applyEvent)
          .flatMap(result =>
            check(
              result.isRight,
              "actual consensus sink accepts valid proposal while application material is unavailable",
            ),
          ),
      )
      snapshot <- sink.snapshot
      status   <- env.runtime.status
      before   <- accepted(env.safety.snapshot)
      _        <- check(
        snapshot.finalization
          .get(f.chain)
          .flatMap(_.bestFinalized)
          .contains(history.tip) && status.failure.exists(
          _.code == RuntimeFailureCode.ProofUnavailable,
        ) && before.decisions.isEmpty,
        "accepted consensus finality is retained separately from unavailable materialization",
      )
      _ <- history.available
      _ <- source
        .append(
          HotStuffGossipArtifact.ProposalArtifact(history.branch.last.proposal),
          timestamp,
        )
        .flatMap(sink.applyEvent)
        .flatMap(result =>
          check(result.isRight, "duplicate accepted event retries backfill"),
        )
      _     <- env.materialized
      after <- env.runtime.status
      calls <- history.calls.get
      _     <- check(
        after.failure.isEmpty && after.canonical.exists(
          _.height == height(10L),
        ) && calls > 0L,
        "duplicate observer completes real backfill and clears failure",
      )
    yield ()

  def orderAndReplay(): IO[Unit] = for
    f       <- fixture(402L)
    history <- history(f)
    journal <- MemoryDurableJournal.create[IO]
    env     <- environment(f, history, journal)
    _       <- env.admit
    _       <- history.available *> history.choose
    result  <- accepted(env.runtime.finalized(history.tip))
    _       <- check(
      result.applied.map(_.blockId) == history.materials.map(
        _.finalized.anchorBlockId.toUInt256,
      ),
      "materialization commits canonical height order",
    )
    _        <- env.materialized
    original <- accepted(env.safety.snapshot)
    same     <- accepted(env.runtime.finalized(history.tip))
    old <- accepted(env.runtime.finalized(history.materials.head.finalized))
    fork = HotStuffFinalizationTracker
      .track((Vector(f.emptyCandidate) ++ f.emptyTail).take(3).map(_.proposal))
      .bestFinalized
      .get
    conflict <- rejected(env.runtime.finalized(fork))
    after    <- accepted(env.safety.snapshot)
    _        <- check(
      same.applied.isEmpty && old.applied.isEmpty && original == after && conflict.code == RuntimeFailureCode.ProofInvalid,
      "same and older canonical targets are idempotent and real fork finality is rejected",
    )
  yield ()

  def missingInvalidAndCapacity(): IO[Unit] = for
    f       <- fixture(403L)
    history <- history(f)
    journal <- MemoryDurableJournal.create[IO]
    env     <- environment(f, history, journal)
    _       <- env.admit
    missing <- rejected(env.runtime.finalized(history.tip))
    _       <- history.available *> history.choose
    earliest = history.materials.head
    _ <- history.backfillValues.update(
      _.updated(
        earliest.finalized.anchorBlockId.toUInt256,
        earliest.copy(statePayload = f.emptyCandidate.statePayload),
      ),
    )
    invalid     <- rejected(env.runtime.finalized(history.tip))
    uncommitted <- accepted(env.safety.snapshot)
    _           <- check(
      missing.code == RuntimeFailureCode.ProofUnavailable && invalid.code == RuntimeFailureCode.ProofInvalid && uncommitted.decisions.isEmpty && uncommitted.preparations.isEmpty,
      "missing or malformed full historical state is rejected before any new application write",
    )
    bounded  <- environment(f, history, journal, 2L)
    capacity <- rejected(bounded.runtime.finalized(history.tip))
    _        <- check(
      capacity.code == RuntimeFailureCode.CapacityUnavailable,
      "catch-up capacity is an explicit local failure",
    )
    _ <- history.available *> history.choose
    _ <- accepted(env.runtime.recover)
    _ <- env.materialized
  yield ()

  def concurrentTargets(): IO[Unit] = for
    f       <- fixture(404L)
    history <- history(f)
    journal <- MemoryDurableJournal.create[IO]
    env     <- environment(f, history, journal)
    _       <- env.admit
    _       <- history.available *> history.choose
    results <- (
      env.runtime.finalized(history.materials(2).finalized).value,
      env.runtime.finalized(history.tip).value,
    ).parTupled
    _ <- check(
      results._1.isRight && results._2.isRight,
      "concurrent canonical targets serialize and allow an older retry",
    )
    _ <- env.materialized
  yield ()

  def duplicatePreservesPreparedVote(): IO[Unit] = for
    f       <- fixture(405L)
    history <- history(f)
    journal <- MemoryDurableJournal.create[IO]
    env     <- environment(f, history, journal)
    _       <- env.admit
    _       <- history.available
    selected = history.materials.head.finalized
    _       <- history.selected.set(Some(selected))
    applied <- accepted(env.runtime.finalized(selected))
    _       <- env.canonical.set(
      applied.canonical.blockId -> applied.canonical.stateRoot,
    )
    candidate = f.orderedTail.head
    request <- accepted(
      f.requests.verifyProposal(candidate.proposal, candidate.plan),
    )
    voting = DurableApplicationVoting.fromStore(
      env.safety,
      ApplicationVoteSigner
        .secp256k1[IO](ApplicationValidatorId(f.keys.head._1), f.keys.head._2),
      f.artifacts,
    )
    prepared <- accepted(voting.prepareConsensusVote(request))
    _        <- env.runtime.observe(
      HotStuffFinalizationTracker.trackAll(
        history.branch.take(3).map(_.proposal),
      ),
    )
    vote <- accepted(voting.signConsensusVote(prepared))
    _    <- check(
      vote.targetProposalId == candidate.proposal.proposalId,
      "duplicate canonical finality preserves actual prepared voting permission",
    )
  yield ()

  def completedObservationDeduplication(): IO[Unit] = for
    f       <- fixture(421L)
    history <- history(f)
    journal <- MemoryDurableJournal.create[IO]
    env     <- environment(f, history, journal)
    _       <- env.admit
    _       <- history.available *> history.choose
    observation = HotStuffFinalizationTracker.trackAll(
      history.branch.map(_.proposal),
    )
    _      <- env.runtime.observe(observation)
    _      <- env.materialized
    before <- history.latestReads.get
    _      <- Vector.fill(8)(env.runtime.observe(observation)).parSequence_
    after  <- history.latestReads.get
    _      <- check(
      before > 0 && after == before,
      "identical successful observations do not repeat the finality drive",
    )
    child   = history.tip.finalizedProof.child
    changed = history.tip.copy(finalizedProof =
      history.tip.finalizedProof.copy(
        child = child.copy(signature = child.signature.copy(s = uint(0))),
      ),
    )
    _ <- env.runtime.observe(
      Map(f.chain -> FinalizationTrackerSnapshot(Some(changed), Vector.empty)),
    )
    rejected <- env.runtime.status
    _        <- check(
      rejected.failure.exists(_.code == RuntimeFailureCode.ProofInvalid),
      "same anchor with changed invalid proof cannot reuse a successful observation",
    )
    _         <- env.runtime.observe(observation)
    retried   <- history.latestReads.get
    recovered <- env.runtime.status
    _         <- check(
      retried > after && recovered.failure.isEmpty,
      "an observer failure invalidates reuse and retries the full drive",
    )
    _          <- accepted(env.runtime.recover)
    recovering <- history.latestReads.get
    _          <- check(
      recovering > retried,
      "explicit recovery always rereads current finality",
    )
    _             <- env.runtime.observe(observation)
    observedAgain <- history.latestReads.get
    _             <- check(
      observedAgain > recovering,
      "explicit recovery invalidates the observation memo",
    )
  yield ()

  private def ordinaryVoting(
      env: Environment,
  ): IO[HotStuffApplicationVoting[IO]] =
    val schedule = new HotStuffApplicationProfileSchedule[IO]:
      def at(
          window: HotStuffWindow,
          parent: Option[BlockId],
      ): Result[IO, HotStuffHistoricalApplicationProfile] = EitherT.pure(
        HotStuffHistoricalApplicationProfile.ApplicationV2(env.f.manifest),
      )
    val contexts = new HotStuffApplicationVotingContexts[IO]:
      def resolve(
          manifest: ProtocolManifest,
          localVoter: ValidatorId,
      ): Result[IO, HotStuffApplicationVotingContext[IO]] = EitherT.pure(
        HotStuffApplicationVotingContext(
          env.f.manifest,
          env.safety,
          env.f.requests,
          env.f.artifacts,
          None,
        ),
      )
    val preimages = HotStuffExecutionPlanPreimages.journaled(
      env.safety,
      _ => EitherT.pure(None),
    )
    accepted(preimages.retain(ExecutionPlan.empty)).as(
      HotStuffApplicationVoting.journaled(
        schedule,
        preimages,
        contexts,
        HotStuffLegacyApplicationAuthentication.unsupported[IO],
      ),
    )

  def pendingHighestAndStoreBinding(): IO[Unit] = for
    f       <- fixture(406L)
    history <- history(f)
    journal <- MemoryDurableJournal.create[IO]
    env     <- environment(f, history, journal)
    _       <- env.admit
    _       <- accepted(env.runtime.recover)
    early = history.materials.head
    _ <- history.backfillValues.set(
      Map(early.finalized.anchorBlockId.toUInt256 -> early),
    )
    _       <- history.selected.set(Some(early.finalized))
    applied <- accepted(env.runtime.finalized(early.finalized))
    earlyObservation = Map(
      f.chain -> FinalizationTrackerSnapshot(
        Some(early.finalized),
        Vector.empty,
      ),
    )
    _ <- env.runtime.observe(earlyObservation)
    _ <- env.canonical.set(
      applied.canonical.blockId -> applied.canonical.stateRoot,
    )
    voting <- ordinaryVoting(env)
    _      <- history.choose
    gap    <- rejected(
      voting.vote(
        env.runtime,
        ValidatorId.unsafe(f.keys.head._1.asString),
        f.orderedTail.head.proposal,
        f.keys.head._2,
      ),
    )
    _ <- check(
      gap.code == RuntimeFailureCode.RecoveryRequired,
      "a newly accepted actual target blocks a new vote before the observer acquires its gate",
    )
    beforePendingDrive <- history.latestReads.get
    _                  <- env.runtime.observe(earlyObservation)
    afterPendingDrive  <- history.latestReads.get
    pendingStatus      <- env.runtime.status
    _                  <- check(
      afterPendingDrive > beforePendingDrive && pendingStatus.failure.exists(
        _.code == RuntimeFailureCode.ProofUnavailable,
      ),
      "a pending target discovered by signing invalidates an otherwise identical successful observation",
    )
    _ <- rejected(env.runtime.finalized(history.tip))
    // A delayed source view must not erase an already authenticated higher target.
    _       <- history.selected.set(Some(early.finalized))
    delayed <- rejected(env.runtime.finalized(early.finalized))
    held    <- env.runtime.status
    _       <- rejected(
      voting.vote(
        env.runtime,
        ValidatorId.unsafe(f.keys.head._1.asString),
        f.orderedTail.head.proposal,
        f.keys.head._2,
      ),
    )
    before <- accepted(env.safety.snapshot)
    _      <- check(
      delayed.code == RuntimeFailureCode.ProofUnavailable && held.failure.nonEmpty && before.consensusIntents.isEmpty,
      "late lower target cannot clear pending authenticated higher finality or emit an ordinary vote",
    )
    _         <- history.available *> history.choose
    recovered <- accepted(env.runtime.finalized(early.finalized))
    _         <- env.canonical.set(
      recovered.canonical.blockId -> recovered.canonical.stateRoot,
    )
    _ <- env.materialized
    candidate = f.orderedTail(4)
    vote <- accepted(
      voting.vote(
        env.runtime,
        ValidatorId.unsafe(f.keys.head._1.asString),
        candidate.proposal,
        f.keys.head._2,
      ),
    )
    _ <- check(
      vote.targetProposalId == candidate.proposal.proposalId,
      "backfilled highest target permits an actual ordinary vote above the materialized frontier",
    )
    otherJournal <- MemoryDurableJournal.create[IO]
    other        <- environment(f, history, otherJournal)
    otherVoting  <- ordinaryVoting(other)
    wrong        <- rejected(
      otherVoting.vote(
        env.runtime,
        ValidatorId.unsafe(f.keys.head._1.asString),
        candidate.proposal,
        f.keys.head._2,
      ),
    )
    separate <- accepted(other.safety.snapshot)
    _        <- check(
      wrong.code == RuntimeFailureCode.UnsafeBoundary && separate.consensusIntents.isEmpty,
      "a separate journal with identical context cannot borrow finalization readiness",
    )
  yield ()

  def fatalFinalityPreserved(): IO[Unit] = Vector(false, true).traverse_ {
    viaObserver =>
      for
        f       <- fixture(if viaObserver then 408L else 407L)
        history <- history(f)
        journal <- MemoryDurableJournal.create[IO]
        env     <- environment(f, history, journal)
        _       <- env.admit
        _       <- history.available
        selected = history.materials.head.finalized
        _ <- history.selected.set(Some(selected))
        _ <- env.runtime.observe(
          Map(
            f.chain -> FinalizationTrackerSnapshot(Some(selected), Vector.empty),
          ),
        )
        initialStatus <- env.runtime.status
        _             <- check(
          initialStatus.failure.isEmpty,
          "selected finality succeeds before injecting a conflict",
        )
        before <- accepted(env.safety.snapshot)
        forkBranch = (Vector(f.emptyCandidate) ++ f.emptyTail)
          .take(3)
          .map(_.proposal)
        fork = HotStuffFinalizationTracker.track(forkBranch).bestFinalized.get
        _ <-
          if viaObserver then
            env.runtime.observe(
              HotStuffFinalizationTracker.trackAll(
                history.branch.take(3).map(_.proposal) ++ forkBranch,
              ),
            )
          else rejected(env.runtime.finalized(fork)).void
        replay   <- rejected(env.runtime.finalized(selected))
        recovery <- rejected(env.runtime.recover)
        after    <- accepted(env.safety.snapshot)
        _        <- check(
          replay.code == RuntimeFailureCode.ProofInvalid && recovery.code == RuntimeFailureCode.ProofInvalid && before == after,
          "actual conflicting finality cannot be cleared by replaying the preferred target or ordinary recovery",
        )
      yield ()
  }

  def pastForkAtSigning(): IO[Unit] = for
    f       <- fixture(409L)
    history <- history(f)
    journal <- MemoryDurableJournal.create[IO]
    env     <- environment(f, history, journal)
    _       <- env.admit
    _       <- history.available *> history.choose
    applied <- accepted(env.runtime.finalized(history.tip))
    _       <- env.canonical.set(
      applied.canonical.blockId -> applied.canonical.stateRoot,
    )
    voting <- ordinaryVoting(env)
    fork = HotStuffFinalizationTracker
      .track((Vector(f.emptyCandidate) ++ f.emptyTail).take(6).map(_.proposal))
      .bestFinalized
      .get
    _ <- check(
      fork.anchorHeight.toBigNat.toBigInt == BigInt(9),
      "actual older fork fixture is height nine",
    )
    _      <- history.selected.set(Some(fork))
    denied <- rejected(
      voting.vote(
        env.runtime,
        ValidatorId.unsafe(f.keys.head._1.asString),
        f.orderedTail(4).proposal,
        f.keys.head._2,
      ),
    )
    _        <- history.choose
    retained <- rejected(
      voting.vote(
        env.runtime,
        ValidatorId.unsafe(f.keys.head._1.asString),
        f.orderedTail(4).proposal,
        f.keys.head._2,
      ),
    )
    state <- accepted(env.safety.snapshot)
    _     <- check(
      denied.code == RuntimeFailureCode.ProofInvalid && retained.code == RuntimeFailureCode.ProofInvalid && state.consensusIntents.isEmpty,
      "fresh signed older fork is rejected against canonical decisions before a vote and cannot be forgotten",
    )
  yield ()

  def pendingTargetsBeforeCommit(): IO[Unit] = Vector(0, 1, 2).traverse_ {
    scenario =>
      for
        f       <- fixture(430L + scenario.toLong)
        history <- history(f)
        journal <- MemoryDurableJournal.create[IO]
        env     <- environment(f, history, journal)
        _       <- env.admit
        first = history.materials(3).finalized
        _       <- history.selected.set(Some(first))
        initial <- rejected(env.runtime.finalized(first))
        _       <- check(
          initial.code == RuntimeFailureCode.ProofUnavailable,
          "height nine authentic target waits for complete material",
        )
        _ <-
          if scenario == 2 then
            history.available *> history.choose *> accepted(
              env.runtime.finalized(history.tip),
            ) *> env.materialized
          else
            val branch       = Vector(f.emptyCandidate) ++ f.emptyTail
            val length       = if scenario == 0 then 5 else 3
            val alternatives = branch.indices
              .take(length)
              .map(index =>
                FinalizedApplicationMaterial(
                  HotStuffFinalizationTracker
                    .track(branch.slice(index, index + 3).map(_.proposal))
                    .bestFinalized
                    .get,
                  branch(index).plan,
                  branch(index).statePayload,
                ),
              )
              .toVector
            val alternate = alternatives.last.finalized
            val material  = alternatives
              .map(value => value.finalized.anchorBlockId.toUInt256 -> value)
              .toMap
            for
              _ <- history.backfillValues
                .set(material) *> history.selected.set(Some(alternate))
              denied <- rejected(env.runtime.finalized(alternate))
              before <- accepted(env.safety.snapshot)
              _      <- check(
                denied.code == (if scenario == 0 then
                                  RuntimeFailureCode.ProofInvalid
                                else
                                  RuntimeFailureCode.ProofUnavailable) && before.canonical.isEmpty && before.preparations.isEmpty && before.decisions.isEmpty,
                "a different-height pending target is checked before committing even a lower complete branch",
              )
              _ <-
                if scenario == 1 then
                  history.backfillValues.set(
                    history.all ++ material,
                  ) *> history.choose *> rejected(
                    env.runtime.finalized(history.tip),
                  ).flatMap(error =>
                    check(
                      error.code == RuntimeFailureCode.ProofInvalid,
                      "later complete high path must contain every earlier authenticated pending target",
                    ),
                  )
                else IO.unit
              after <- accepted(env.safety.snapshot)
              _     <- check(
                after == before,
                "contradictory pending finality retains zero partial canonical application",
              )
            yield ()
      yield ()
  }

  private def directory: Resource[IO, Path] = Resource.make(
    IO.blocking(Files.createTempDirectory("sigilaris-finalized-application-")),
  )(path =>
    IO.blocking {
      val stream = Files.walk(path)
      try
        stream
          .iterator()
          .asScala
          .toVector
          .sortBy(_.getNameCount)
          .reverse
          .foreach(Files.delete)
      finally stream.close()
    },
  )

  private final class Faults(target: Ref[IO, Option[(Long, Int)]])
      extends JournalFaultInjector[IO]:
    def arm(sequence: Long, occurrence: Int): IO[Unit] =
      target.set(Some(sequence -> occurrence))
    def after(point: JournalFaultPoint, sequence: Long): IO[Unit] =
      if point != JournalFaultPoint.AfterFrameForce then IO.unit
      else
        target
          .modify {
            case Some((selected, remaining))
                if selected == sequence && remaining == 1 =>
              None -> true
            case Some((selected, remaining)) if selected == sequence =>
              Some(selected -> (remaining - 1)) -> false
            case current => current -> false
          }
          .flatMap(fail =>
            IO.raiseWhen(fail)(
              new IOException("injected finalized application durable boundary"),
            ),
          )

  def unknownWriteObserverRetry(): IO[Unit] = for
    f       <- fixture(420L)
    history <- history(f)
    target  <- Ref.of[IO, Option[(Long, Int)]](None)
    faults = new Faults(target)
    journal <- MemoryDurableJournal.createWithFaults[IO](faults)
    env     <- environment(f, history, journal)
    _       <- env.admit
    _       <- history.available *> history.choose
    _       <- faults.arm(2L, 2)
    _       <- env.runtime.observe(
      HotStuffFinalizationTracker.trackAll(history.branch.map(_.proposal)),
    )
    failure <- env.runtime.status
    _       <- check(
      failure.failure.exists(_.code == RuntimeFailureCode.StorageUnknown),
      "unknown write is recorded after consensus acceptance",
    )
    _ <- env.runtime.observe(
      HotStuffFinalizationTracker.trackAll(history.branch.map(_.proposal)),
    )
    _         <- env.materialized
    recovered <- env.runtime.status
    _         <- check(
      recovered.failure.isEmpty,
      "duplicate observer performs forward recovery only when the safety store requires it",
    )
  yield ()

  def failedVotingObservationRetry(): IO[Unit] = for
    f       <- fixture(422L)
    history <- history(f)
    target  <- Ref.of[IO, Option[(Long, Int)]](None)
    faults = new Faults(target)
    journal <- MemoryDurableJournal.createWithFaults[IO](faults)
    env     <- environment(f, history, journal)
    _       <- env.admit
    _       <- history.available
    selected    = history.materials.head.finalized
    observation = Map(
      f.chain -> FinalizationTrackerSnapshot(Some(selected), Vector.empty),
    )
    _      <- history.selected.set(Some(selected))
    _      <- env.runtime.observe(observation)
    status <- env.runtime.status
    _      <- env.canonical.set(
      status.canonical.get.blockId -> status.canonical.get.stateRoot,
    )
    candidate = f.orderedTail.head
    request <- accepted(
      f.requests.verifyProposal(candidate.proposal, candidate.plan),
    )
    calls <- Ref.of[IO, Int](0)
    original = ApplicationVoteSigner.secp256k1[IO](
      ApplicationValidatorId(f.keys.head._1),
      f.keys.head._2,
    )
    signer = new ApplicationVoteSigner[IO]:
      def validatorId: ApplicationValidatorId = original.validatorId
      def sign(context: DomainContext, preimage: Bytes): IO[Bytes] =
        calls.update(_ + 1) *> original.sign(context, preimage)
    voting = DurableApplicationVoting.fromStore(env.safety, signer, f.artifacts)
    records <- accepted(journal.recover)
    _ <- faults.arm(records.map(_.sequence).maxOption.getOrElse(0L) + 1L, 2)
    failure <- rejected(env.runtime.voteConsensus(voting, request))
    armed   <- target.get
    signed  <- calls.get
    _       <- check(
      failure.code == RuntimeFailureCode.StorageUnknown && armed.isEmpty && signed == 0,
      "the armed durable vote failure fires before actual key use",
    )
    before <- history.latestReads.get
    _      <- env.runtime.observe(observation)
    after  <- history.latestReads.get
    _      <- check(
      after > before,
      "an unknown vote write invalidates the completed observation and retries recovery",
    )
    _    <- accepted(env.safety.snapshot)
    vote <- accepted(env.runtime.voteConsensus(voting, request))
    _    <- check(
      vote.targetProposalId == candidate.proposal.proposalId,
      "the identical vote succeeds after observer-driven durable recovery",
    )
    _ <- Vector(false, true).traverse_ { cancel =>
      for
        _       <- env.runtime.observe(observation)
        entered <- Ref.of[IO, Int](0)
        failing = new ApplicationVoteSigner[IO]:
          def validatorId: ApplicationValidatorId = original.validatorId
          def sign(context: DomainContext, preimage: Bytes): IO[Bytes] =
            entered.update(_ + 1) *> (
              if cancel then IO.canceled *> IO.never[Bytes]
              else
                IO.raiseError[Bytes](
                  new IOException("injected key-use failure"),
                )
            )
        failedVoting = DurableApplicationVoting.fromStore(
          env.safety,
          failing,
          f.artifacts,
        )
        outcome <- env.runtime
          .voteConsensus(failedVoting, request)
          .value
          .start
          .flatMap(_.join)
        used <- entered.get
        _    <- check(
          used == 1,
          "the failing or cancelled key-use callback was entered",
        )
        _ <- outcome match
          case Outcome.Canceled() =>
            check(cancel, "only the cancellation case cancels")
          case Outcome.Succeeded(result) =>
            result.flatMap(value =>
              check(
                !cancel && value.left.exists(error =>
                  error.code == RuntimeFailureCode.SignerFailure && error.detail == "injected key-use failure",
                ),
                "a thrown key-use failure retains the exact signer failure",
              ),
            )
          case Outcome.Errored(error) => IO.raiseError(error)
        before <- history.latestReads.get
        _      <- env.runtime.observe(observation)
        after  <- history.latestReads.get
        _      <- check(
          after > before,
          "failed and cancelled key use invalidate observer reuse",
        )
        _ <- accepted(env.safety.snapshot)
        _ <- accepted(env.runtime.voteConsensus(voting, request))
      yield ()
    }
  yield ()

  def fileRestartBoundaries(): IO[Unit] =
    Vector(2L -> 1, 2L -> 2, 3L -> 1, 3L -> 2).traverse_ {
      (sequence, occurrence) =>
        for
          f       <- fixture(410L + sequence * 2L + occurrence.toLong)
          history <- history(f)
          target  <- Ref.of[IO, Option[(Long, Int)]](None)
          faults = new Faults(target)
          _ <- directory.use(path =>
            FileApplicationJournal
              .resourceWithFaults(path, faults)
              .use(journal =>
                for
                  env      <- environment(f, history, journal)
                  _        <- env.admit
                  _        <- history.available *> history.choose
                  _        <- faults.arm(sequence, occurrence)
                  failure  <- rejected(env.runtime.finalized(history.tip))
                  status   <- env.runtime.status
                  retained <- history.selected.get
                  _        <- check(
                    failure.code == RuntimeFailureCode.StorageUnknown && status.failure.nonEmpty && retained
                      .contains(history.tip),
                    "durable ambiguity retains the genuine consensus target without claiming materialization",
                  )
                yield (),
              ) *> FileApplicationJournal
              .resource(path)
              .use(journal =>
                for
                  env <- environment(f, history, journal)
                  _   <-
                    if sequence == 2L then
                      history.selected.set(None) *> rejected(
                        env.runtime.recover,
                      ).flatMap(error =>
                        check(
                          error.code == RuntimeFailureCode.ProofUnavailable,
                          "durable prepared finality prevents readiness when latest history is lost",
                        ),
                      ) *> history.choose
                    else IO.unit
                  _      <- accepted(env.runtime.recover)
                  _      <- env.materialized
                  status <- env.runtime.status
                  _      <- check(
                    status.failure.isEmpty,
                    "reopen recovers prepared/committed frames and resumes original canonical catch-up",
                  )
                yield (),
              ),
          )
        yield ()
    }

  def run(): IO[Unit] =
    actualSinkBackfill() *> orderAndReplay() *> missingInvalidAndCapacity() *> concurrentTargets() *> duplicatePreservesPreparedVote() *> completedObservationDeduplication() *> pendingHighestAndStoreBinding() *> fatalFinalityPreserved() *> pastForkAtSigning() *> pendingTargetsBeforeCommit() *> unknownWriteObserverRetry() *> failedVotingObservationRetry() *> fileRestartBoundaries()
