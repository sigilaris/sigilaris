package org.sigilaris.conformance

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.ExecutionId
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.node.txpipeline.v2.*

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.OptionPartial",
  ),
)
object V2ExactBoundaryConformance:
  import V2RequestConformance.*
  import V2VotingConformance.{accepted, rejected}
  import V2ExactFixture.*

  def artifacts(): IO[Unit] =
    for
      f <- material(
        ExactMode.OrderedAtomic,
        true,
        true,
        980L,
        height(10L),
        false,
        false,
      )
      env    <- V2ExactConformance.memory(f)
      record <- env.admit
      _      <- env.approve(f.ordered.proposal)
      // This lookup uses hints only to locate actual artifacts. The factory
      // must independently reject unused ids and noncanonical preimages.
      lookup = new ExactCandidateRepository[IO]:
        def resolve(
            evidence: ExactCandidateEvidence,
        ): Result[IO, ResolvedExactCandidate] =
          f.candidateRepository.resolve(
            evidence.copy(availableArtifacts = Vector.empty),
          )
        def lockCertificate(id: Hash): Result[IO, LockCertificate] =
          f.candidateRepository.lockCertificate(id)
        def effectCertificate(id: Hash): Result[IO, EffectCertificate] =
          f.candidateRepository.effectCertificate(id)
      requests = ExactExecutionRequestVerifier.authenticated(
        env.store,
        f.plans,
        f.manifest,
        f.requests,
        lookup,
        env.ancestry,
      )
      certificate = f.lockCertificates.head
      id          = value(LockCertificate.id(certificate))
      raw         = value(LockCertificate.codec.encode(certificate))
      artifact    = ReferencedArtifact(id, raw)
      positive <- accepted(
        requests.ordered(
          record.binding.nodePipelineId,
          f.ordered.evidence.copy(availableArtifacts = Vector(artifact)),
        ),
      )
      _ <- IO(assert(positive.lockCertificates.size == 2))
      bad = Vector(
        Vector(artifact.copy(canonicalBytes = raw ++ bytes("00"))),
        Vector(artifact.copy(canonicalBytes = raw.dropRight(1L))),
        Vector(artifact.copy(digest = uint(999))),
        Vector(artifact, artifact),
        Vector(ReferencedArtifact(uint(999), ByteVector.empty)),
      )
      _ <- bad.traverse_(hints =>
        rejected(
          requests.ordered(
            record.binding.nodePipelineId,
            f.ordered.evidence.copy(availableArtifacts = hints),
          ),
        ),
      )
      state <- accepted(env.safety.snapshot)
      _     <- IO(
        assert(
          state.claims.isEmpty && state.intents.isEmpty && state.consensusIntents.isEmpty,
        ),
      )
      lockFree <- material(
        ExactMode.OrderedAtomic,
        false,
        false,
        981L,
        height(10L),
        false,
        false,
      )
      fast = lockFree.intent.copy(stages =
        lockFree.intent.stages
          .updated(0, lockFree.intent.stages.head.copy(sourceKind = 2.toByte)),
      )
      _ <- IO(
        assert(
          lockFree.plans
            .verify(
              V2ExactConformance.signedPlan(lockFree, fast),
              lockFree.manifest,
            )
            .left
            .toOption
            .exists(_.code == FailureCode.InvalidLength),
        ),
      )
      badMode = lockFree.intent.copy(stages =
        lockFree.intent.stages.updated(
          0,
          lockFree.intent.stages.head
            .copy(declaration = Declaration.Compatibility(uint(8))),
        ),
      )
      _ <- IO(
        assert(
          lockFree.plans
            .verify(
              V2ExactConformance.signedPlan(lockFree, badMode),
              lockFree.manifest,
            )
            .left
            .toOption
            .exists(_.code == FailureCode.ClassificationMismatch),
        ),
      )
    yield ()

  /** Deliberately faulty exact-ownership adapters model a downstream caller
    * retaining an actual valid source/candidate while dropping or substituting
    * its exact binding. All source/input/execution/signature verification still
    * runs; the authoritative shared journal must reject the forged ownership.
    */
  private def alteredOwnership(
      f: Material,
      selected: Option[Hash],
  ): ApplicationRequestVerifier[IO] =
    val real = f.repository(
      f.lockCertificates
        .map(certificate => value(LockCertificate.id(certificate)))
        .toSet,
    )
    val transactions = new TransactionAuthentication[IO]:
      def authenticate(
          context: DomainContext,
          family: InputManifest,
          raw: Bytes,
      ): Result[IO, SignedApplicationBinding] =
        f.transactions
          .authenticate(context, family, raw)
          .map(_.copy(exactBinding = selected))
    val repository = new ApplicationExecutionRepository[IO]:
      def lockSource(
          context: DomainContext,
          execution: ExecutionId,
      ): Result[IO, LockSourceMaterial] = real.lockSource(context, execution)
      def lockCertificate(id: Hash): Result[IO, LockCertificate] =
        real.lockCertificate(id)
      def effectCertificate(id: Hash): Result[IO, EffectCertificate] =
        real.effectCertificate(id)
      def executeEffect(
          context: DomainContext,
          execution: ExecutionId,
      ): Result[IO, ExecutedEffect] = real.executeEffect(context, execution)
      def verifyExactBinding(
          context: DomainContext,
          execution: ExecutionId,
          binding: Option[Hash],
      ): Result[IO, Unit] =
        f.registered.flatMap(record =>
          EitherT.fromEither[IO](
            Either.cond(
              context == f.context && record.binding.executionIds
                .contains(execution) && binding == selected,
              (),
              V2RuntimeFailure.at(
                RuntimeFailureCode.ProofInvalid,
                "faulty test adapter cannot authenticate another source",
              ),
            ),
          ),
        )
    ApplicationRequestVerifier.authenticated(
      f.manifest,
      f.inputs,
      f.declarations,
      f.creations,
      transactions,
      f.artifacts,
      f.scopes,
      repository,
      f.proposalRepository,
    )

  def ownership(): IO[Unit] =
    for
      f <- material(
        ExactMode.OrderedAtomic,
        true,
        true,
        982L,
        height(10L),
        false,
        false,
      )
      env    <- V2ExactConformance.memory(f)
      record <- env.admit
      source  = f.producer
      subject = f.lockSubject(source)
      valid <- accepted(
        f.requests.verifyLock(
          subject,
          source.descriptor,
          source.signedTransaction,
          source.proofs(f.initialRoot),
        ),
      )
      other   <- V2ExactConformance.memory(f)
      _       <- rejected(other.voting.voteLock(valid))
      initial <- accepted(other.safety.snapshot)
      _       <- IO(assert(initial.sequence == 0L && initial.intents.isEmpty))
      _       <- env.approve(f.ordered.proposal)
      before  <- accepted(env.safety.snapshot)
      _ <- Vector(Option.empty[Hash], Some(uint(999))).traverse_(binding =>
        val verifier = alteredOwnership(f, binding)
        for
          lock <- accepted(
            verifier.verifyLock(
              subject,
              source.descriptor,
              source.signedTransaction,
              source.proofs(f.initialRoot),
            ),
          )
          _        <- rejected(env.voting.voteLock(lock))
          proposal <- accepted(
            verifier.verifyProposal(f.ordered.proposal, f.ordered.plan),
          )
          _ <- rejected(env.voting.prepareConsensusVote(proposal))
        yield (),
      )
      after <- accepted(env.safety.snapshot)
      _     <- IO(assert(after == before))
      _     <- accepted(env.voting.voteLock(valid))
      exact <- accepted(
        env.requests.ordered(record.binding.nodePipelineId, f.ordered.evidence),
      )
      ready    <- accepted(env.runtime.executeOrdered(exact))
      _        <- env.vote(ready.verifiedProposal)
      reopened <- env.reopen
      snapshot <- accepted(reopened.store.snapshot)
      _        <- IO(
        assert(
          snapshot.stageOwners.map(_.bindingDigest).distinct == Vector(
            value(ExactIdentityBinding.digest(record.binding)),
          ),
        ),
      )
    yield ()

  def certificateVariants(): IO[Unit] =
    for
      f <- material(
        ExactMode.OrderedAtomic,
        true,
        true,
        983L,
        height(10L),
        false,
        false,
      )
      env <- V2ExactConformance.memory(f)
      _   <- env.admit
      first       = f.lockCertificates.head
      preimage    = value(LockSubject.signingPreimage(first.subject))
      alternative = LockCertificate(
        first.subject,
        Vector(f.keys(0), f.keys(1), f.keys(3)).map((id, key) =>
          ValidatorSignature(id, signed(key, preimage)),
        ),
      )
      _ <- IO(
        assert(
          value(LockCertificate.id(first)) != value(
            LockCertificate.id(alternative),
          ),
        ),
      )
      a      <- accepted(f.requests.verifyLockCertificate(first))
      b      <- accepted(f.requests.verifyLockCertificate(alternative))
      _      <- accepted(env.voting.importLock(a))
      _      <- accepted(env.voting.importLock(b))
      before <- accepted(env.safety.snapshot)
      _      <- IO(
        assert(
          before.lockCertificates.size == 2 && before.locks.size == 1 && before.intents.isEmpty && before.claims.isEmpty,
        ),
      )
      changed   = first.subject.copy(lastInclusionHeight = height(11L))
      malformed = LockCertificate(
        changed,
        f.quorum(value(LockSubject.signingPreimage(changed))),
      )
      _ <- rejected(f.requests.verifyLockCertificate(malformed))
      _ <- rejected(
        f.requests.verifyLockCertificate(
          first.copy(votes =
            Vector(
              first.votes.head,
              first.votes.head,
              first.votes.last,
            ),
          ),
        ),
      )
      after     <- accepted(env.safety.snapshot)
      _         <- IO(assert(before == after))
      reopened  <- env.reopen
      recovered <- accepted(reopened.safety.snapshot)
      _         <- IO(assert(recovered == before))
    yield ()

  def capabilityBinding(): IO[Unit] =
    for
      f <- material(
        ExactMode.OrderedAtomic,
        false,
        false,
        984L,
        height(10L),
        false,
        false,
      )
      env <- V2ExactConformance.memory(f)
      genuine = value(f.plans.verify(f.signedPlan, f.manifest))
      stale   = new ExactPlanAuthentication:
        def verify(
            signed: SignedExactPlan,
            manifest: ProtocolManifest,
        ): Either[CoreFailure, VerifiedExactPlan] = Right(genuine)
      alternate = f.request.copy(signedPlan =
        V2ExactConformance.signedPlan(
          f,
          f.intent.copy(applicationPipelineId =
            org.sigilaris.core.datatype.Utf8("another-authorized-plan"),
          ),
        ),
      )
      faulty = JournalExactPlanStore.journaled(env.safety, stale, f.manifest)
      _      <- rejected(faulty.admit(alternate))
      before <- accepted(env.safety.snapshot)
      _      <- IO(assert(before.sequence == 0L))
      _      <- env.admit
      otherCap = value(f.plans.verify(alternate.signedPlan, f.manifest))
      wrong    = new ExactPlanAuthentication:
        def verify(
            signed: SignedExactPlan,
            manifest: ProtocolManifest,
        ): Either[CoreFailure, VerifiedExactPlan] = Right(otherCap)
      _ <- rejected(
        JournalSafetyStore.open(
          f.anchor,
          env.journal,
          env.publication,
          SafetyProfile(f.manifest, f.artifacts),
          ExactRecoveryAuthentication.voting(f.requests, wrong, f.manifest),
          ReservationOrdering.isolated[IO],
          SafetyCapacity.unbounded,
        ),
      )
    yield ()

  def run(): IO[Unit] =
    artifacts() *> ownership() *> certificateVariants() *> capabilityBinding()
