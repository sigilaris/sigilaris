package org.sigilaris.conformance

import java.io.IOException
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

import cats.data.EitherT
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
import org.sigilaris.node.jvm.storage.file.FileApplicationJournal
import org.sigilaris.node.txpipeline.v2.*

/** Actual signed neutral executions, three-chain finality, and the public file
  * journal/application APIs. The pure exact projection grants no proof here.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.Throw",
    "org.wartremover.warts.OptionPartial",
  ),
)
object V2ExactApplicationConformance:
  import V2RequestConformance.*
  import V2VotingConformance.{accepted, rejected}

  private def check(condition: Boolean, message: String): IO[Unit] =
    IO.raiseUnless(condition)(new IllegalStateException(message))

  private def directory: Resource[IO, Path] = Resource.make(
    IO.blocking(Files.createTempDirectory("sigilaris-exact-application-")),
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

  final class Publication(
      val material: V2ExactFixture.Material,
      val canonical: Ref[IO, (Hash, Hash)],
      val finalized: Ref[IO, Height],
  ) extends SafetyPublication[IO]:
    def finalizedHeight(context: DomainContext): Result[IO, Height] =
      if context == material.context then EitherT.liftF(finalized.get)
      else unavailable("exact application finality context")
    def verifyEffectState(request: VerifiedEffectRequest): Result[IO, Unit] =
      unavailable("this exact application fixture has consensus sources")
    def verifyConsensusState(
        request: VerifiedConsensusProposal,
    ): Result[IO, Unit] =
      EitherT(
        canonical.get.map((block, root) =>
          Either.cond(
            request.context == material.context && request.parentBlockId == block && request.parentStateRoot == root,
            (),
            V2RuntimeFailure.at(
              RuntimeFailureCode.ProofInvalid,
              "exact application canonical parent changed",
            ),
          ),
        ),
      )

  final case class Environment(
      material: V2ExactFixture.Material,
      safety: JournalSafetyStore[IO],
      exact: JournalExactPlanStore[IO],
      application: RecoverableApplicationStore[IO],
      verifier: ApplicationCommitVerifier[IO],
      publication: Publication,
  )

  private def publication(f: V2ExactFixture.Material): IO[Publication] = for
    canonical <- Ref.of[IO, (Hash, Hash)](
      f.anchor.blockId -> f.anchor.stateRoot,
    )
    finalized <- Ref.of[IO, Height](f.anchor.height)
  yield new Publication(f, canonical, finalized)

  private def environment(
      f: V2ExactFixture.Material,
      publication: Publication,
      path: Path,
      faults: JournalFaultInjector[IO] = JournalFaultInjector.none[IO],
  ): Resource[IO, Environment] = FileApplicationJournal
    .resourceWithFaults(path, faults)
    .evalMap(journal =>
      val verifier = ApplicationCommitVerifier.authenticated(
        f.anchor,
        f.requests,
        f.validatorLookup,
        f.stateAuthentication,
      )
      accepted(
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
      ).map(safety =>
        Environment(
          f,
          safety,
          JournalExactPlanStore.journaled(safety, f.plans, f.manifest),
          RecoverableApplicationStore.journaled(safety),
          verifier,
          publication,
        ),
      ),
    )

  private def admit(env: Environment): IO[ExactPipelineRecord] =
    accepted(env.exact.admit(env.material.request))
      .flatTap(env.material.retainAdmission)

  private def finality(
      proposal: Proposal,
      descendants: Vector[Proposal],
  ): FinalizedAnchorSuggestion =
    FinalizedAnchorSuggestion(
      proposal,
      FinalizedProof(descendants(0), descendants(1)),
    )

  private def verifiedBatch(
      env: Environment,
      proposal: Proposal,
      plan: ExecutionPlan,
      payload: Bytes,
      descendants: Vector[Proposal],
  ): IO[VerifiedApplicationBatch] = for
    request  <- accepted(env.material.requests.verifyProposal(proposal, plan))
    verified <- accepted(
      env.verifier
        .verifyBatch(request, finality(proposal, descendants), payload),
    )
    _ <- env.publication.finalized.set(verified.candidate.height)
  yield verified

  private def commit(
      env: Environment,
      verified: VerifiedApplicationBatch,
  ): IO[ApplicationDecision] = for
    prepared <- accepted(env.application.prepare(verified))
    decision <- accepted(env.application.commit(prepared, verified.candidate))
    _        <- env.publication.canonical.set(
      decision.blockId -> verified.batch.nextStateRoot,
    )
  yield decision

  private def assertMaterialized(
      env: Environment,
      expected: ExactPipelineRecord,
      block: Hash,
      payload: Bytes,
  ): IO[Unit] = for
    stored    <- accepted(env.exact.get(expected.binding.nodePipelineId))
    snapshot  <- accepted(env.safety.snapshot)
    canonical <- accepted(env.application.canonicalPayload)
    _         <- check(
      stored.binding == expected.binding && stored.request == expected.request && stored.stages
        .forall(stage =>
          stage.lifecycle == ExactStageLifecycle.Materialized && stage.firstApplicationBlock
            .contains(block),
        ),
      "exact materialization lost immutable identity or first application",
    )
    _ <- check(
      snapshot.appliedEntries.keySet == expected.binding.executionIds.toSet && snapshot.claims.values
        .forall(_.lifecycle == ClaimLifecycle.Applied) && snapshot.locks.values
        .forall(
          _.lifecycle == ClaimLifecycle.Applied,
        ) && snapshot.index.isEmpty,
      "exact application and complete owner/lock release disagree",
    )
    _ <- check(
      canonical.exists(_._2 == payload),
      "canonical application payload differs after exact materialization",
    )
  yield ()

  def nonvotingApplicationMasks()
      : IO[Unit] = Vector(false, true).traverse_(producer =>
    Vector(false, true).traverse_(consumer =>
      for
        f <- V2ExactFixture.material(
          ExactMode.OrderedAtomic,
          producer,
          consumer,
          201L,
          height(6),
          false,
          false,
        )
        publication <- publication(f)
        _           <- directory.use(path =>
          for
            admitted <- environment(f, publication, path).use(env =>
              for
                record <- admit(env)
                before <- accepted(env.safety.snapshot)
                _      <- check(
                  before.intents.isEmpty && before.consensusIntents.isEmpty && before.claims.isEmpty && record.stages
                    .forall(_.lifecycle == ExactStageLifecycle.Accepted),
                  "nonvoting fixture already contains local execution promises",
                )
                verified <- verifiedBatch(
                  env,
                  f.ordered.proposal,
                  f.ordered.plan,
                  f.ordered.statePayload,
                  f.orderedTail.map(_.proposal),
                )
                decision <- commit(env, verified)
                _        <- check(
                  verified.batch.candidateHeight == f.deadline,
                  "application fixture does not exercise the inclusive deadline",
                )
                _ <- assertMaterialized(
                  env,
                  record,
                  decision.blockId,
                  f.ordered.statePayload,
                )
              yield record,
            )
            _ <- environment(f, publication, path).use(env =>
              for
                _ <- assertMaterialized(
                  env,
                  admitted,
                  f.ordered.proposal.targetBlockId.toUInt256,
                  f.ordered.statePayload,
                )
                recovered <- accepted(env.exact.snapshot)
                _         <- check(
                  recovered.stageOwners.sizeCompare(
                    2,
                  ) == 0 && recovered.outputOwners.sizeCompare(1) == 0,
                  "terminal application discarded permanent exact ownership",
                )
              yield (),
            )
          yield (),
        )
      yield (),
    ),
  )

  def certifiedAncestorMaterialization(): IO[Unit] = for
    f <- V2ExactFixture.material(
      ExactMode.CertifiedAncestor,
      false,
      false,
      202L,
      height(10),
      false,
      false,
    )
    publication <- publication(f)
    _           <- directory.use(path =>
      for
        admitted <- environment(f, publication, path).use(env =>
          for
            record   <- admit(env)
            producer <- verifiedBatch(
              env,
              f.producerCandidate.proposal,
              f.producerCandidate.plan,
              f.producerCandidate.statePayload,
              Vector(f.adjacentConsumer.proposal, f.adjacentTail.head.proposal),
            )
            _       <- commit(env, producer)
            halfway <- accepted(env.exact.get(record.binding.nodePipelineId))
            _       <- check(
              halfway
                .stages(0)
                .lifecycle == ExactStageLifecycle.Materialized && halfway
                .stages(1)
                .lifecycle == ExactStageLifecycle.Accepted,
              "producer-only canonical application inferred consumer application",
            )
            consumer <- verifiedBatch(
              env,
              f.adjacentConsumer.proposal,
              f.adjacentConsumer.plan,
              f.adjacentConsumer.statePayload,
              f.adjacentTail.map(_.proposal),
            )
            _      <- commit(env, consumer)
            stored <- accepted(env.exact.get(record.binding.nodePipelineId))
            state  <- accepted(env.safety.snapshot)
            _      <- check(
              stored.stages.map(_.firstApplicationBlock) == Vector(
                Some(f.producerCandidate.proposal.targetBlockId.toUInt256),
                Some(f.adjacentConsumer.proposal.targetBlockId.toUInt256),
              ) && stored.stages.forall(
                _.lifecycle == ExactStageLifecycle.Materialized,
              ),
              "certified ancestor first application facts were replaced or conflated",
            )
            _ <- check(
              state.index.isEmpty && state.appliedEntries.sizeCompare(2) == 0,
              "ancestor materialization left live owner protection",
            )
          yield record,
        )
        _ <- environment(f, publication, path).use(env =>
          for
            record  <- accepted(env.exact.get(admitted.binding.nodePipelineId))
            payload <- accepted(env.application.canonicalPayload)
            _       <- check(
              record.stages.forall(
                _.lifecycle == ExactStageLifecycle.Materialized,
              ) && payload.exists(_._2 == f.adjacentConsumer.statePayload),
              "ancestor application changed across file-journal restart",
            )
          yield (),
        )
      yield (),
    )
  yield ()

  def ownerlessExpiry(): IO[Unit] = for
    f <- V2ExactFixture.material(
      ExactMode.OrderedAtomic,
      true,
      true,
      203L,
      height(9),
      false,
      false,
    )
    publication <- publication(f)
    _           <- directory.use(path =>
      for
        admitted <- environment(f, publication, path).use(env =>
          for
            record <- admit(env)
            chain      = f.emptyCandidate +: f.emptyTail
            atDeadline = chain.indexWhere(_.blockHeight == 9L)
            boundary <- accepted(
              env.verifier.verifyNonapplication(
                finality(
                  chain(atDeadline).proposal,
                  chain.drop(atDeadline + 1).map(_.proposal),
                ),
                chain
                  .take(atDeadline + 1)
                  .map(candidate =>
                    ApplicationHistoryEntry(candidate.proposal, candidate.plan),
                  ),
                record.binding.executionIds.sortBy(_.toUInt256.bytes.toHex),
              ),
            )
            _         <- rejected(env.application.expire(boundary))
            unchanged <- accepted(env.exact.get(record.binding.nodePipelineId))
            _         <- check(
              unchanged.stages.forall(
                _.lifecycle == ExactStageLifecycle.Accepted,
              ),
              "exact ownerless expiry released at inclusive deadline",
            )
            after = atDeadline + 1
            proof <- accepted(
              env.verifier.verifyNonapplication(
                finality(
                  chain(after).proposal,
                  chain.drop(after + 1).map(_.proposal),
                ),
                chain
                  .take(after + 1)
                  .map(candidate =>
                    ApplicationHistoryEntry(candidate.proposal, candidate.plan),
                  ),
                record.binding.executionIds.sortBy(_.toUInt256.bytes.toHex),
              ),
            )
            _           <- publication.finalized.set(proof.candidate.height)
            resolutions <- accepted(env.application.expire(proof))
            expired <- accepted(env.exact.get(record.binding.nodePipelineId))
            state   <- accepted(env.safety.snapshot)
            _       <- check(
              resolutions.sizeCompare(1) == 0 && expired.stages.forall(stage =>
                stage.lifecycle == ExactStageLifecycle.ExpiredUnapplied && stage.firstApplicationBlock.isEmpty && stage.terminalEvidenceDigest
                  .contains(proof.evidenceDigest),
              ),
              "ownerless exact stage did not retain actual nonapplication resolution",
            )
            _ <- check(
              state.claims.isEmpty && state.locks.isEmpty && state.appliedEntries.isEmpty && state.canonical.isEmpty,
              "ownerless expiry fabricated reservations or application",
            )
            _ <- f.lockCertificates.traverse_(certificate =>
              for
                verified <- accepted(
                  f.requests.verifyLockCertificate(certificate),
                )
                _ <- accepted(env.safety.importLock(verified))
                _ <- rejected(
                  env.safety.claimLock(
                    verified.request,
                    org.sigilaris.core.datatype.Utf8("v1"),
                  ),
                )
              yield (),
            )
            archived <- accepted(env.safety.snapshot)
            _        <- check(
              archived.lockCertificates.sizeCompare(
                2,
              ) == 0 && archived.locks.values.forall(
                _.lifecycle == ClaimLifecycle.ExpiredUnapplied,
              ) && archived.index.isEmpty && archived.intents.isEmpty,
              "late certificate resurrected an ownerless expired exact lock or vote",
            )
          yield record,
        )
        _ <- environment(f, publication, path).use(env =>
          for
            record   <- accepted(env.exact.get(admitted.binding.nodePipelineId))
            snapshot <- accepted(env.exact.snapshot)
            _        <- check(
              record.stages.forall(
                _.lifecycle == ExactStageLifecycle.ExpiredUnapplied,
              ) && snapshot.stageOwners.sizeCompare(
                2,
              ) == 0 && snapshot.outputOwners.sizeCompare(1) == 0,
              "ownerless expiry or permanent output ownership disappeared at restart",
            )
            retry <- accepted(env.exact.admit(f.request))
            _     <- check(
              retry == record,
              "same signed expired request allocated another durable identity",
            )
          yield (),
        )
      yield (),
    )
  yield ()

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
              new IOException("injected exact application durable boundary"),
            ),
          )

  def applicationCrashBoundaries(): IO[Unit] = for
    f <- V2ExactFixture.material(
      ExactMode.OrderedAtomic,
      false,
      false,
      204L,
      height(10),
      false,
      false,
    )
    _ <- Vector(false, true).traverse_(duringCommit =>
      Vector(1, 2).traverse_(occurrence =>
        for
          publication <- publication(f)
          target      <- Ref.of[IO, Option[(Long, Int)]](None)
          faults = new Faults(target)
          _ <- directory.use(path =>
            for
              retained <- environment(f, publication, path, faults).use(env =>
                for
                  record   <- admit(env)
                  verified <- verifiedBatch(
                    env,
                    f.ordered.proposal,
                    f.ordered.plan,
                    f.ordered.statePayload,
                    f.orderedTail.map(_.proposal),
                  )
                  _ <-
                    if duringCommit then
                      for
                        prepared <- accepted(env.application.prepare(verified))
                        state    <- accepted(env.safety.snapshot)
                        _        <- faults.arm(state.sequence + 1L, occurrence)
                        _        <- rejected(
                          env.application.commit(prepared, verified.candidate),
                        )
                      yield ()
                    else
                      for
                        state <- accepted(env.safety.snapshot)
                        _     <- faults.arm(state.sequence + 1L, occurrence)
                        _     <- rejected(env.application.prepare(verified))
                      yield ()
                yield record -> verified,
              )
              _ <- environment(f, publication, path).use(env =>
                for
                  state  <- accepted(env.safety.snapshot)
                  record <- accepted(
                    env.exact.get(retained._1.binding.nodePipelineId),
                  )
                  _ <-
                    if duringCommit then
                      assertMaterialized(
                        env,
                        retained._1,
                        f.ordered.proposal.targetBlockId.toUInt256,
                        f.ordered.statePayload,
                      )
                    else
                      for
                        _ <- check(
                          state.canonical.isEmpty && record.stages.forall(
                            _.lifecycle == ExactStageLifecycle.Accepted,
                          ) && state.claims.values
                            .forall(_.lifecycle == ClaimLifecycle.Live),
                          "application preparation exposed speculative exact application facts",
                        )
                        prepared <- IO.fromOption(
                          state.preparations.values.headOption,
                        )(
                          new IllegalStateException(
                            "durable exact preparation missing after restart",
                          ),
                        )
                        _ <- accepted(
                          env.application
                            .commit(prepared, retained._2.candidate),
                        )
                        _ <- assertMaterialized(
                          env,
                          retained._1,
                          f.ordered.proposal.targetBlockId.toUInt256,
                          f.ordered.statePayload,
                        )
                      yield ()
                yield (),
              )
            yield (),
          )
        yield (),
      ),
    )
  yield ()

  def ownerlessExpiryCrashBoundaries(): IO[Unit] = for
    f <- V2ExactFixture.material(
      ExactMode.OrderedAtomic,
      false,
      false,
      205L,
      height(9),
      false,
      false,
    )
    _ <- Vector(1, 2).traverse_(occurrence =>
      for
        publication <- publication(f)
        target      <- Ref.of[IO, Option[(Long, Int)]](None)
        faults = new Faults(target)
        _ <- directory.use(path =>
          for
            admitted <- environment(f, publication, path, faults).use(env =>
              for
                record <- admit(env)
                chain      = f.emptyCandidate +: f.emptyTail
                checkpoint = chain.indexWhere(_.blockHeight == 10L)
                proof <- accepted(
                  env.verifier.verifyNonapplication(
                    finality(
                      chain(checkpoint).proposal,
                      chain.drop(checkpoint + 1).map(_.proposal),
                    ),
                    chain
                      .take(checkpoint + 1)
                      .map(candidate =>
                        ApplicationHistoryEntry(
                          candidate.proposal,
                          candidate.plan,
                        ),
                      ),
                    record.binding.executionIds.sortBy(_.toUInt256.bytes.toHex),
                  ),
                )
                _      <- publication.finalized.set(proof.candidate.height)
                before <- accepted(env.safety.snapshot)
                _      <- faults.arm(before.sequence + 1L, occurrence)
                _      <- rejected(env.application.expire(proof))
              yield record,
            )
            _ <- environment(f, publication, path).use(env =>
              for
                record <- accepted(
                  env.exact.get(admitted.binding.nodePipelineId),
                )
                state    <- accepted(env.safety.snapshot)
                snapshot <- accepted(env.exact.snapshot)
                _        <- check(
                  record.stages.forall(
                    _.lifecycle == ExactStageLifecycle.ExpiredUnapplied,
                  ) && record.stages.forall(_.firstApplicationBlock.isEmpty),
                  "durable ownerless expiry did not reconcile exact terminal records",
                )
                _ <- check(
                  state.claims.isEmpty && state.locks.isEmpty && state.appliedEntries.isEmpty && state.committed.lastOption
                    .exists(_.operation == JournalOperation.Expiry),
                  "ownerless expiry recovery fabricated owner/application state or omitted its commit",
                )
                _ <- check(
                  snapshot.stageOwners.sizeCompare(
                    2,
                  ) == 0 && snapshot.outputOwners.sizeCompare(1) == 0,
                  "expiry fault recovery released permanent exact admission ownership",
                )
              yield (),
            )
          yield (),
        )
      yield (),
    )
  yield ()

  def rejectedCanonicalOutcomes(): IO[Unit] =
    Vector(
      (ExactMode.OrderedAtomic, true, false),
      (ExactMode.OrderedAtomic, false, true),
      (ExactMode.CertifiedAncestor, true, false),
    ).zipWithIndex.traverse_ {
      case ((mode, rejectProducer, rejectConsumer), index) =>
        for
          f <- V2ExactFixture.material(
            mode,
            false,
            false,
            206L + index.toLong,
            height(10),
            rejectProducer,
            rejectConsumer,
          )
          publication <- publication(f)
          _           <- directory.use(path =>
            for
              admitted <- environment(f, publication, path).use(env =>
                for
                  record <- admit(env)
                  candidate =
                    if mode == ExactMode.OrderedAtomic then f.ordered
                    else f.producerCandidate
                  descendants =
                    if mode == ExactMode.OrderedAtomic then f.orderedTail
                    else f.producerTail
                  verified <- verifiedBatch(
                    env,
                    candidate.proposal,
                    candidate.plan,
                    candidate.statePayload,
                    descendants.map(_.proposal),
                  )
                  _         <- rejected(env.application.prepare(verified))
                  _         <- accepted(env.safety.recover)
                  state     <- accepted(env.safety.snapshot)
                  unchanged <- accepted(
                    env.exact.get(record.binding.nodePipelineId),
                  )
                  _ <- check(
                    state.canonical.isEmpty && state.decisions.isEmpty && state.appliedEntries.isEmpty && state.preparations.isEmpty && state.claims.isEmpty && state.index.isEmpty,
                    "rejected exact outcome became a prepared or canonical application",
                  )
                  _ <- check(
                    unchanged == record,
                    "canonical outcome rejection changed durable exact lifecycle",
                  )
                yield record,
              )
              _ <- environment(f, publication, path).use(env =>
                for
                  record <- accepted(
                    env.exact.get(admitted.binding.nodePipelineId),
                  )
                  payload <- accepted(env.application.canonicalPayload)
                  _       <- check(
                    record == admitted && payload.isEmpty,
                    "failed nonvoting materialization became canonical after restart",
                  )
                yield (),
              )
            yield (),
          )
        yield ()
    }

  def run(): IO[Unit] =
    nonvotingApplicationMasks() *> certifiedAncestorMaterialization() *> ownerlessExpiry() *> applicationCrashBoundaries() *> ownerlessExpiryCrashBoundaries() *> rejectedCanonicalOutcomes()
