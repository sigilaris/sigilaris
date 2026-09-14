package org.sigilaris.conformance

import cats.data.EitherT
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.ApplicationValidatorId
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{
  HotStuffValidator,
  Proposal,
  Vote,
}
import org.sigilaris.node.txpipeline.v2.*

/** Exported end-to-end exact admission and candidate execution. Finalized
  * application/expiry is tested by the separate application conformance path.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.OptionPartial",
  ),
)
object V2ExactConformance:
  import V2RequestConformance.*
  import V2VotingConformance.{accepted, rejected}
  import V2ExactFixture.*

  final class Environment(
      val f: Material,
      val journal: DurableJournal[IO],
      val approved: Ref[IO, Option[Proposal]],
      val retained: Ref[IO, Map[Hash, AncestorHistoryEntry]],
      val backfilled: Ref[IO, Map[Hash, AncestorHistoryEntry]],
      val finalized: Ref[IO, Height],
      val publication: SafetyPublication[IO],
      val safety: JournalSafetyStore[IO],
  ):
    val store    = JournalExactPlanStore.journaled(safety, f.plans, f.manifest)
    val ancestry = f.ancestry(approved, retained, backfilled)
    val requests = ExactExecutionRequestVerifier.authenticated(
      store,
      f.plans,
      f.manifest,
      f.requests,
      f.candidateRepository,
      ancestry,
    )
    val runtime =
      ExactConsensusExecutionRuntime.journaled(store, requests, f.keys.head._1)
    val voting = DurableApplicationVoting.fromStore(
      safety,
      ApplicationVoteSigner
        .secp256k1[IO](ApplicationValidatorId(f.keys.head._1), f.keys.head._2),
      f.artifacts,
    )
    def admit: IO[ExactPipelineRecord] =
      accepted(runtime.admit(f.request)).flatTap(f.retainAdmission)
    def approve(candidate: Proposal): IO[Unit] = approved.set(Some(candidate))
    def reopen: IO[Environment]                =
      open(f, journal, approved, retained, backfilled, finalized, publication)
    def vote(request: VerifiedConsensusProposal): IO[Vote] =
      accepted(voting.prepareConsensusVote(request)).flatMap(prepared =>
        accepted(voting.signConsensusVote(prepared)),
      )

  private def open(
      f: Material,
      journal: DurableJournal[IO],
      approved: Ref[IO, Option[Proposal]],
      retained: Ref[IO, Map[Hash, AncestorHistoryEntry]],
      backfilled: Ref[IO, Map[Hash, AncestorHistoryEntry]],
      finalized: Ref[IO, Height],
      publication: SafetyPublication[IO],
  ): IO[Environment] = accepted(
    JournalSafetyStore.open(
      f.anchor,
      journal,
      publication,
      SafetyProfile(f.manifest, f.artifacts),
      ExactRecoveryAuthentication.voting(f.requests, f.plans, f.manifest),
      ReservationOrdering.isolated[IO],
      SafetyCapacity.unbounded,
    ),
  ).map(store =>
    new Environment(
      f,
      journal,
      approved,
      retained,
      backfilled,
      finalized,
      publication,
      store,
    ),
  )

  def environment(f: Material, journal: DurableJournal[IO]): IO[Environment] =
    for
      approved <- Ref.of[IO, Option[Proposal]](None)
      retained <- Ref.of[IO, Map[Hash, AncestorHistoryEntry]](
        f.allCandidates
          .map(candidate =>
            candidate.proposal.targetBlockId.toUInt256 -> candidate.history,
          )
          .toMap,
      )
      backfilled <- Ref.of[IO, Map[Hash, AncestorHistoryEntry]](Map.empty)
      finalized  <- Ref.of[IO, Height](height(5L))
      publication = new SafetyPublication[IO]:
        def finalizedHeight(actual: DomainContext): Result[IO, Height] =
          if actual == f.context then EitherT.liftF(finalized.get)
          else unavailable("exact finalized domain")
        def verifyEffectState(
            request: VerifiedEffectRequest,
        ): Result[IO, Unit] = unavailable(
          "consensus exact fixture has no effect votes",
        )
        def verifyConsensusState(
            request: VerifiedConsensusProposal,
        ): Result[IO, Unit] = EitherT(
          approved.get.map(current =>
            Either.cond(
              request.context == f.context && current.contains(
                request.proposal,
              ) && f.allCandidates.exists(candidate =>
                candidate.proposal == request.proposal && candidate.plan == request.plan && candidate.parentStateRoot == request.parentStateRoot &&
                  HotStuffValidator
                    .validateProposal(candidate.proposal, f.validators)
                    .isRight,
              ),
              (),
              V2RuntimeFailure.at(
                RuntimeFailureCode.ProofInvalid,
                "actual approved exact candidate or retained parent changed",
              ),
            ),
          ),
        )
      result <- open(
        f,
        journal,
        approved,
        retained,
        backfilled,
        finalized,
        publication,
      )
    yield result

  def memory(f: Material): IO[Environment] =
    MemoryDurableJournal.create[IO].flatMap(environment(f, _))

  def mask(mode: ExactMode, mask: Int): IO[Unit] =
    for
      f <- material(
        mode,
        (mask & 2) != 0,
        (mask & 1) != 0,
        900L + mode.tag.toLong * 10L + mask,
        height(10L),
        false,
        false,
      )
      env    <- memory(f)
      record <- env.admit
      _      <- IO(
        assert(
          record.stages.forall(_.lifecycle == ExactStageLifecycle.Accepted),
        ),
      )
      prepared <- mode match
        case ExactMode.OrderedAtomic =>
          for
            _        <- env.approve(f.ordered.proposal)
            verified <- accepted(
              env.requests
                .ordered(record.binding.nodePipelineId, f.ordered.evidence),
            )
            _ <- IO(
              assert(
                verified.lockCertificates.size == Integer.bitCount(
                  mask,
                ) && verified.effectCertificates.isEmpty,
              ),
            )
            prepared <- accepted(env.runtime.executeOrdered(verified))
          yield prepared
        case ExactMode.CertifiedAncestor =>
          for
            _        <- env.approve(f.producerCandidate.proposal)
            producer <- accepted(
              env.requests.producer(
                record.binding.nodePipelineId,
                f.producerCandidate.evidence,
              ),
            )
            _ <- IO(
              assert(
                producer.effectCertificates.isEmpty && producer.lockCertificates.size == (if f.producerEligible
                                                                                          then
                                                                                            1
                                                                                          else
                                                                                            0),
              ),
            )
            prepared <- accepted(env.runtime.executeProducer(producer))
            _        <- env.vote(prepared.verifiedProposal)
            consumerCandidate =
              if (mask & 1) == 0 then f.adjacentConsumer else f.distantConsumer
            _        <- env.approve(consumerCandidate.proposal)
            consumer <- accepted(
              env.requests.consumer(
                record.binding.nodePipelineId,
                consumerCandidate.evidence,
              ),
            )
            _ <- IO(
              assert(
                consumer.ancestor.certifiedPath.size == (if (mask & 1) == 0 then
                                                           1
                                                         else 3),
              ),
            )
            _ <- IO(
              assert(
                consumer.lockCertificates.size == (if f.consumerEligible then 1
                                                   else
                                                     0) && consumer.effectCertificates.isEmpty,
              ),
            )
            prepared <- accepted(env.runtime.executeConsumer(consumer))
          yield prepared
      vote <- env.vote(prepared.verifiedProposal)
      _    <- IO(
        assert(HotStuffValidator.validateVote(vote, f.validators).isRight),
      )
      before  <- accepted(env.safety.snapshot)
      current <- accepted(env.store.get(record.binding.nodePipelineId))
      _       <- IO {
        assert(before.claims.size == 2)
        assert(before.locks.size == Integer.bitCount(mask))
        assert(before.effectCertificates.isEmpty)
        assert(env.safety.anchor == f.anchor)
        assert(
          before.canonical.isEmpty && before.decisions.isEmpty && before.appliedEntries.isEmpty,
        )
        assert(
          current.stages.forall(_.lifecycle == ExactStageLifecycle.Reserved),
        )
        assert(current.stages.forall(_.effectCertificateId.isEmpty))
        assert(
          current.stages.map(_.inputLockCertificateId.nonEmpty) == Vector(
            f.producerEligible,
            f.consumerEligible,
          ),
        )
        assert(prepared.workingStateRoot == f.consumerExecution.afterRoot)
      }
      reopened  <- env.reopen
      recovered <- accepted(reopened.safety.snapshot)
      _         <- IO(assert(recovered == before))
      staged    <- accepted(
        reopened.voting.prepareConsensusVote(prepared.verifiedProposal),
      )
      _ <- rejected(reopened.voting.signConsensusVote(staged))
      _ <- mode match
        case ExactMode.OrderedAtomic =>
          accepted(
            reopened.requests
              .ordered(record.binding.nodePipelineId, f.ordered.evidence),
          ).flatMap(request =>
            accepted(reopened.runtime.executeOrdered(request)),
          )
        case ExactMode.CertifiedAncestor =>
          val candidate =
            if (mask & 1) == 0 then f.adjacentConsumer else f.distantConsumer
          accepted(
            reopened.requests
              .consumer(record.binding.nodePipelineId, candidate.evidence),
          ).flatMap(request =>
            accepted(reopened.runtime.executeConsumer(request)),
          )
      retried <- reopened.vote(prepared.verifiedProposal)
      _       <- IO(assert(retried == vote))
    yield ()

  def signedPlan(f: Material, intent: ExactPlanIntent): SignedExactPlan =
    SignedExactPlan(
      intent,
      signed(f.outerKey, value(ExactPlanIntent.signingBytes(intent))),
    )

  def admission(): IO[Unit] =
    for
      f <- material(
        ExactMode.OrderedAtomic,
        false,
        false,
        950L,
        height(10L),
        false,
        false,
      )
      env <- memory(f)
      _   <- rejected(
        f.requests.verifyProposal(f.ordered.proposal, f.ordered.plan),
      )
      before <- accepted(env.safety.snapshot)
      bad = Vector(
        f.signedPlan.copy(authorization = ByteVector.fill(72L)(0.toByte)),
        signedPlan(f, f.intent.copy(laneId = Utf8("lane-b"))),
        signedPlan(f, f.intent.copy(referenceSchemaDigest = uint(709))),
        signedPlan(f, f.intent.copy(consumerReference = bytes("ff"))),
        signedPlan(f, f.intent.copy(lastInclusionHeight = height(11L))),
      )
      _ <- bad.traverse_(plan =>
        rejected(env.runtime.admit(f.request.copy(signedPlan = plan))),
      )
      unchanged <- accepted(env.safety.snapshot)
      _         <- IO(assert(unchanged == before))
      record    <- env.admit
      same      <- accepted(env.runtime.admit(f.request))
      alias     <- accepted(
        env.runtime.admit(
          f.request.copy(idempotencyKey = Some(Utf8("neutral-alias"))),
        ),
      )
      _ <- IO(assert(same == record && alias == record))
      changed = f.request.copy(signedPlan =
        signedPlan(
          f,
          f.intent.copy(applicationPipelineId = Utf8("another-pipeline")),
        ),
      )
      _ <- rejected(env.runtime.admit(changed))
      _ <- rejected(env.runtime.admit(changed.copy(idempotencyKey = None)))
      snapshot <- accepted(env.store.snapshot)
      _        <- IO(
        assert(
          snapshot.records.size == 1 && snapshot.stageOwners.size == 2 && snapshot.outputOwners.size == 1 && snapshot.idempotency.size == 2,
        ),
      )
      reopened  <- env.reopen
      recovered <- accepted(reopened.store.snapshot)
      _         <- IO(assert(recovered == snapshot))
      _         <- env.approve(f.ordered.proposal)
      verified  <- accepted(
        f.requests.verifyProposal(f.ordered.proposal, f.ordered.plan),
      )
      prepared <- accepted(env.voting.prepareConsensusVote(verified))
      _        <- rejected(env.voting.signConsensusVote(prepared))
      exact    <- accepted(
        env.requests.ordered(record.binding.nodePipelineId, f.ordered.evidence),
      )
      _ <- accepted(env.runtime.executeOrdered(exact))
      _ <- env.vote(verified)
    yield ()

  def logicalFailure(producerFailure: Boolean): IO[Unit] =
    for
      f <- material(
        ExactMode.OrderedAtomic,
        false,
        false,
        if producerFailure then 960L else 961L,
        height(10L),
        producerFailure,
        !producerFailure,
      )
      env      <- memory(f)
      record   <- env.admit
      _        <- env.approve(f.ordered.proposal)
      verified <- accepted(
        env.requests.ordered(record.binding.nodePipelineId, f.ordered.evidence),
      )
      _        <- IO(assert(verified.outcome.isLeft))
      _        <- rejected(env.runtime.executeOrdered(verified))
      snapshot <- accepted(env.safety.snapshot)
      current  <- accepted(env.store.get(record.binding.nodePipelineId))
      _        <- IO {
        assert(snapshot.claims.size == 2 && snapshot.consensusIntents.size == 1)
        assert(env.safety.anchor == f.anchor)
        assert(
          snapshot.canonical.isEmpty && snapshot.decisions.isEmpty && snapshot.appliedEntries.isEmpty,
        )
        assert(current.stages.forall(_.lifecycle == ExactStageLifecycle.Failed))
        assert(
          snapshot.claims.values.forall(_.lifecycle == ClaimLifecycle.Live),
        )
        assert(
          verified.verifiedProposal.reservations.map(
            _.actualFootprint,
          ) == f.executions.map(_.footprint),
        )
      }
      prepared <- accepted(
        env.voting.prepareConsensusVote(verified.verifiedProposal),
      )
      _         <- rejected(env.voting.signConsensusVote(prepared))
      reopened  <- env.reopen
      recovered <- accepted(reopened.safety.snapshot)
      _         <- IO(assert(recovered == snapshot))
      again     <- accepted(
        reopened.voting.prepareConsensusVote(verified.verifiedProposal),
      )
      _ <- rejected(reopened.voting.signConsensusVote(again))
    yield ()

  def unavailableAndDeadline(): IO[Unit] =
    for
      f <- material(
        ExactMode.CertifiedAncestor,
        true,
        true,
        970L,
        height(10L),
        false,
        false,
      )
      env    <- memory(f)
      record <- env.admit
      _      <- env.approve(f.distantConsumer.proposal)
      missing = f.verifier(Set.empty)
      _ <- rejected(
        missing.verifyProposal(
          f.distantConsumer.proposal,
          f.distantConsumer.plan,
        ),
      )
      complete <- env.retained.get
      _        <- env.retained.set(Map.empty)
      _        <- rejected(
        env.requests
          .consumer(record.binding.nodePipelineId, f.distantConsumer.evidence),
      )
      _     <- env.backfilled.set(complete)
      proof <- accepted(
        env.requests
          .consumer(record.binding.nodePipelineId, f.distantConsumer.evidence),
      )
      _ <- IO(assert(proof.ancestor.certifiedPath.size == 3))
      _ <- env.approve(f.adjacentConsumer.proposal)
      _ <- rejected(env.runtime.executeConsumer(proof))
      _ <- env.approve(f.distantConsumer.proposal)
      _ <- rejected(
        env.requests
          .producer(record.binding.nodePipelineId, f.distantConsumer.evidence),
      )
      _ <- rejected(
        env.requests.ordered(record.binding.nodePipelineId, f.ordered.evidence),
      )
      before <- accepted(env.safety.snapshot)
      _      <- env.finalized.set(height(10L))
      _      <- rejected(env.runtime.executeConsumer(proof))
      after  <- accepted(env.safety.snapshot)
      _      <- IO(
        assert(
          after.claims.isEmpty && after.consensusIntents.isEmpty && after.sequence >= before.sequence,
        ),
      )
      expired <- material(
        ExactMode.OrderedAtomic,
        false,
        false,
        971L,
        height(5L),
        false,
        false,
      )
      _ <- IO(
        assert(
          expired.plans.verify(expired.signedPlan, expired.manifest).isLeft,
        ),
      )
      tooLong <- material(
        ExactMode.OrderedAtomic,
        false,
        false,
        972L,
        height(1000L),
        false,
        false,
      )
      _ <- IO(
        assert(
          tooLong.plans.verify(tooLong.signedPlan, tooLong.manifest).isLeft,
        ),
      )
    yield ()

  def run(): IO[Unit] =
    (Vector(ExactMode.OrderedAtomic, ExactMode.CertifiedAncestor)
      .flatMap(mode => (0 to 3).map(mode -> _)))
      .traverse_((mode, mask) => V2ExactConformance.mask(mode, mask)) *>
      admission() *> logicalFailure(false) *> logicalFailure(
        true,
      ) *> unavailableAndDeadline()
