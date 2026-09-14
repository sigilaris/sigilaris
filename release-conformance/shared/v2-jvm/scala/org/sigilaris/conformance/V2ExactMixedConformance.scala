package org.sigilaris.conformance

import cats.data.EitherT
import cats.effect.IO
import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.{
  ApplicationValidatorId,
  ExecutionId,
  NormalizedApplicationResult,
}
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.application.protocol.v2.V2Codecs.given
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.node.gossip.StableArtifactId
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.node.jvm.runtime.block.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
import org.sigilaris.node.txpipeline.v2.*

/** Two independently signed/admitted pipelines share one actual four-entry
  * working-state replay. Each exact profile must succeed before any signature
  * for the common candidate can be published.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.OptionPartial",
  ),
)
object V2ExactMixedConformance:
  import V2RequestConformance.*
  import V2VotingConformance.{accepted, rejected}
  import V2ExactFixture.*

  final case class Step(
      entry: PlanEntry,
      family: InputManifest,
      descriptor: InputDescriptor,
      before: Map[InputId, Long],
      after: Map[InputId, Long],
      accesses: Vector[ActualAccess],
      absent: (InputId, Bytes),
      result: NormalizedApplicationResult,
      footprint: Footprint,
      proofs: Hash => Vector[ResolutionEvidence],
      absentProof: Hash => Bytes,
      signature: Bytes => Boolean,
  )

  final class Group(val first: Material, val second: Material):
    require(
      first.manifest == second.manifest && first.initialState == second.initialState,
    )
    val context   = first.context
    val manifest  = first.manifest
    val materials = Vector(first, second)
    def step(
        f: Material,
        index: Int,
        before: Map[InputId, Long],
        output: Bytes,
    ): Step =
      val source = f.sources(index)
      val actual = f.execute(source, before, output)
      Step(
        f.planEntry(actual),
        source.family,
        source.descriptor,
        before,
        actual.trace.state,
        actual.trace.accesses,
        source.created -> source.absence(actual.beforeRoot),
        actual.result,
        actual.footprint,
        source.proofs,
        source.absence,
        source.verifySignature,
      )
    def replay(): Vector[Step] =
      val a       = step(first, 0, first.initialState, ByteVector.empty)
      val aOutput = value(ResultBytes.codec.decode(a.result.bytes)).output
      val b       = step(first, 1, a.after, aOutput)
      val c       = step(second, 0, b.after, ByteVector.empty)
      val cOutput = value(ResultBytes.codec.decode(c.result.bytes)).output
      val d       = step(second, 1, c.after, cOutput)
      Vector(a, b, c, d)
    val steps   = replay()
    val entries = steps.map(_.entry)
    val plan    = ExecutionPlan(
      2L,
      Vector(ExecutionWave(WaveKind.Ordered, entries)),
      Vector.empty,
    )
    val root     = value(ExecutionPlan.computeRoot(plan))
    val nextRoot = first.stateRoot(steps.last.after)
    val body     = BlockBody[Hash, Hash, Bytes](
      steps
        .map(step =>
          BlockRecord(
            step.entry.source.txId,
            Some(step.result.digest.toUInt256),
            Vector.empty[Bytes],
          ),
        )
        .toSet,
    )
    val bodyRoot = BlockBody.computeBodyRoot(body).toOption.get
    val window   = HotStuffWindow
      .fromLongs(first.chain, 6L, 0L, first.validators.hash)
      .toOption
      .get
    val header = BlockHeader(
      Some(first.baseBlockId),
      BlockHeight.unsafeFromLong(6L),
      StateRoot(nextRoot),
      bodyRoot,
      BlockTimestamp.unsafeFromEpochMillis(3000L),
      BlockHeaderVersion.V2,
      Some(root),
    )
    val candidate = Proposal
      .sign(
        UnsignedProposal(
          window,
          ValidatorId.unsafe("v1"),
          BlockHeader.computeId(header),
          header,
          ProposalTxSet(
            entries.map(entry =>
              StableArtifactId.fromBytes(entry.source.txId.bytes).toOption.get,
            ),
          ),
          first.baseCertificate,
        ),
        first.keys.head._2,
      )
      .toOption
      .get
    def evidence(material: Material): ExactCandidateEvidence =
      ExactCandidateEvidence(
        context,
        first.baseBlockId.toUInt256,
        height(6L),
        entries.filter(entry =>
          material.executionIds.contains(entry.executionId),
        ),
        Vector.empty,
      )
    def owner(entry: PlanEntry, index: Int): Owner = Owner(
      context,
      entry.executionId,
      Scope(
        ScopeKind.ConsensusOrdered,
        first.baseBlockId.toUInt256,
        height(6L),
        root.toUInt256,
        index.toLong,
        hash(
          first.baseCertificate.toBytes ++ value(
            PlanEntry.codec.encode(entry),
          ) ++ root.toBytes,
        ),
      ),
    )
    val states = (Vector(first.initialState) ++ steps.map(_.after))
      .map(state => first.stateRoot(state) -> state)
      .toMap
    def source(raw: Bytes): Option[Step] =
      steps.find(_.entry.source.signedTransaction == raw)
    val inputs = new InputAuthentication:
      def verify(
          family: InputManifest,
          raw: Bytes,
          stateRoot: Hash,
          field: ResolvedField,
          proof: ResolutionEvidence,
      ): Either[CoreFailure, Unit] =
        Either.cond(
          source(raw).exists(step =>
            step.family == family && step.signature(
              raw,
            ) && step.descriptor.fields.contains(field) &&
              states
                .get(stateRoot)
                .exists(state =>
                  field.stableId.forall(id =>
                    state.get(id).exists(_.toBytes == field.value),
                  ),
                ) &&
              (if field.role == FieldRole.Opaque then
                 proof == ResolutionEvidence(field.fieldId, ByteVector.empty)
               else step.proofs(stateRoot).contains(proof)),
          ),
          (),
          CoreFailure.at(FailureCode.ProofInvalid, "mixed.actualSignedField"),
        )
      def verifyAbsentCreation(
          family: InputManifest,
          raw: Bytes,
          stateRoot: Hash,
          identity: InputId,
          proof: Bytes,
      ): Either[CoreFailure, Unit] =
        Either.cond(
          source(raw).exists(step =>
            step.family == family && step.signature(
              raw,
            ) && step.absent._1 == identity &&
              states.get(stateRoot).exists(!_.contains(identity)) && step
                .absentProof(stateRoot) == proof,
          ),
          (),
          CoreFailure.at(FailureCode.ProofInvalid, "mixed.actualAbsence"),
        )
    val declarations = new DeclaredFootprintAuthentication:
      def derive(
          family: InputManifest,
          raw: Bytes,
          stateRoot: Hash,
          descriptor: InputDescriptor,
      ): Either[CoreFailure, Option[Footprint]] =
        source(raw)
          .filter(step =>
            step.family == family && step.signature(
              raw,
            ) && step.descriptor == descriptor && states.contains(stateRoot),
          )
          .map(step => Some(step.footprint))
          .toRight(
            CoreFailure.at(FailureCode.ProofInvalid, "mixed.declaration"),
          )
    val creations = new DeclaredCreationAuthentication:
      def derive(
          family: InputManifest,
          raw: Bytes,
          stateRoot: Hash,
          descriptor: InputDescriptor,
      ): Either[CoreFailure, Vector[(InputId, Bytes)]] =
        source(raw)
          .filter(step =>
            step.family == family && step.signature(
              raw,
            ) && step.descriptor == descriptor && states.contains(stateRoot),
          )
          .map(step => Vector(step.absent._1 -> step.absentProof(stateRoot)))
          .toRight(CoreFailure.at(FailureCode.ProofInvalid, "mixed.creation"))
    val plans = new ExactPlanAuthentication:
      def verify(
          signed: SignedExactPlan,
          manifest: ProtocolManifest,
      ): Either[CoreFailure, VerifiedExactPlan] =
        materials
          .find(_.signedPlan == signed)
          .toRight(
            CoreFailure.at(FailureCode.ProofUnavailable, "mixed.signedPlan"),
          )
          .flatMap(_.plans.verify(signed, manifest))
    val transactions = new TransactionAuthentication[IO]:
      def authenticate(
          context: DomainContext,
          family: InputManifest,
          raw: Bytes,
      ): Result[IO, SignedApplicationBinding] =
        materials
          .find(_.sources.exists(_.signedTransaction == raw))
          .fold(unavailable[SignedApplicationBinding]("mixed source"))(
            _.transactions.authenticate(context, family, raw),
          )
    val repository = new ApplicationExecutionRepository[IO]:
      def lockSource(
          context: DomainContext,
          execution: ExecutionId,
      ): Result[IO, LockSourceMaterial] =
        materials
          .find(_.executionIds.contains(execution))
          .fold(unavailable[LockSourceMaterial]("mixed lock source"))(
            _.repository(Set.empty).lockSource(context, execution),
          )
      def lockCertificate(id: Hash): Result[IO, LockCertificate] = unavailable(
        "mixed 00 has no input locks",
      )
      def effectCertificate(id: Hash): Result[IO, EffectCertificate] =
        unavailable("mixed consensus has no effects")
      def executeEffect(
          context: DomainContext,
          execution: ExecutionId,
      ): Result[IO, ExecutedEffect] = unavailable(
        "mixed consensus has no fast source",
      )
      def verifyExactBinding(
          context: DomainContext,
          execution: ExecutionId,
          binding: Option[Hash],
      ): Result[IO, Unit] =
        materials
          .find(_.executionIds.contains(execution))
          .fold(unavailable[Unit]("mixed exact ownership"))(
            _.repository(Set.empty)
              .verifyExactBinding(context, execution, binding),
          )
    val scopes = new ReservationScopeAuthentication[IO]:
      def verifyFast(
          owner: Owner,
          base: AdmissionBase,
          source: SignedApplicationBinding,
      ): Result[IO, Unit] = unavailable("no mixed fast scope")
      def verifyConsensus(
          owner: Owner,
          proposal: Proposal,
          plan: ExecutionPlan,
          entry: PlanEntry,
      ): Result[IO, Unit] =
        EitherT.fromEither[IO](
          Either.cond(
            proposal == candidate && plan == Group.this.plan && entries.zipWithIndex
              .exists((expected, index) =>
                expected == entry && Group.this.owner(expected, index) == owner,
              ) && HotStuffValidator
              .validateProposal(proposal, first.validators)
              .isRight &&
              first.artifacts.verifyFinalizedBase(context, first.base).isRight,
            (),
            V2RuntimeFailure.at(
              RuntimeFailureCode.ProofInvalid,
              "mixed entry/plan/branch scope",
            ),
          ),
        )
    val proposals = new ProposalExecutionRepository[IO]:
      def historicalValidators(
          window: HotStuffWindow,
      ): Result[IO, ValidatorSet] =
        if window.chainId == first.chain && window.validatorSetHash == first.validators.hash
        then EitherT.pure(first.validators)
        else unavailable("mixed validator history")
      def executeSequential(
          proposal: Proposal,
          plan: ExecutionPlan,
      ): Result[IO, ExecutedProposal] =
        if proposal != candidate || plan != Group.this.plan || HotStuffValidator
            .validateProposal(proposal, first.validators)
            .isLeft
        then unavailable("mixed actual candidate")
        else
          val actual = replay()
          EitherT.pure(
            ExecutedProposal(
              candidate,
              UnsignedVote(
                window,
                ValidatorId.unsafe("v1"),
                candidate.proposalId,
              ),
              first.initialRoot,
              actual.map(step =>
                BodyMember(step.entry.source.txId, step.entry.executionId),
              ),
              bodyRoot.toUInt256,
              actual.zipWithIndex.map((step, index) =>
                ExecutedApplication(
                  step.entry,
                  step.descriptor,
                  step.proofs(step.entry.entryPreStateRoot),
                  step.accesses,
                  Vector(step.absent),
                  step.result.bytes,
                  first.stateRoot(step.after),
                  owner(step.entry, index),
                  None,
                ),
              ),
            ),
          )
    val requests = ApplicationRequestVerifier.authenticated(
      manifest,
      inputs,
      declarations,
      creations,
      transactions,
      first.artifacts,
      scopes,
      repository,
      proposals,
    )
    val candidates = new ExactCandidateRepository[IO]:
      def resolve(
          supplied: ExactCandidateEvidence,
      ): Result[IO, ResolvedExactCandidate] =
        if materials.exists(material => evidence(material) == supplied) then
          EitherT.pure(ResolvedExactCandidate(candidate, plan, None))
        else unavailable("mixed candidate stage membership")
      def lockCertificate(id: Hash): Result[IO, LockCertificate] =
        repository.lockCertificate(id)
      def effectCertificate(id: Hash): Result[IO, EffectCertificate] =
        repository.effectCertificate(id)
    val ancestry = new CanonicalAncestorLookup[IO]:
      def lookup(request: AncestorRequest): IO[AncestorLookupResult] = IO.pure(
        AncestorLookupResult.Unavailable(
          V2RuntimeFailure.at(
            RuntimeFailureCode.ProofUnavailable,
            "mixed fixture selects OrderedAtomic",
          ),
        ),
      )
    val publication = new SafetyPublication[IO]:
      def finalizedHeight(actual: DomainContext): Result[IO, Height] =
        if actual == context then EitherT.pure(first.anchor.height)
        else unavailable("mixed installed checkpoint")
      def verifyEffectState(request: VerifiedEffectRequest): Result[IO, Unit] =
        unavailable("no mixed fast effect")
      def verifyConsensusState(
          request: VerifiedConsensusProposal,
      ): Result[IO, Unit] =
        EitherT.fromEither[IO](
          Either.cond(
            request.context == context && request.proposal == candidate && request.parentBlockId == first.anchor.blockId &&
              request.parentStateRoot == first.anchor.stateRoot && HotStuffValidator
                .validateProposal(candidate, first.validators)
                .isRight,
            (),
            V2RuntimeFailure.at(
              RuntimeFailureCode.ProofInvalid,
              "mixed current branch",
            ),
          ),
        )

  final class Environment(
      val group: Group,
      val journal: DurableJournal[IO],
      val safety: JournalSafetyStore[IO],
  ):
    val store =
      JournalExactPlanStore.journaled(safety, group.plans, group.manifest)
    val requests = ExactExecutionRequestVerifier.authenticated(
      store,
      group.plans,
      group.manifest,
      group.requests,
      group.candidates,
      group.ancestry,
    )
    val runtime = ExactConsensusExecutionRuntime.journaled(
      store,
      requests,
      group.first.keys.head._1,
    )
    val voting = DurableApplicationVoting.fromStore(
      safety,
      ApplicationVoteSigner.secp256k1[IO](
        ApplicationValidatorId(group.first.keys.head._1),
        group.first.keys.head._2,
      ),
      group.first.artifacts,
    )
    def reopen: IO[Environment] = open(group, journal)
  private def open(group: Group, journal: DurableJournal[IO]): IO[Environment] =
    accepted(
      JournalSafetyStore.open(
        group.first.anchor,
        journal,
        group.publication,
        SafetyProfile(group.manifest, group.first.artifacts),
        ExactRecoveryAuthentication.voting(
          group.requests,
          group.plans,
          group.manifest,
        ),
        ReservationOrdering.isolated[IO],
        SafetyCapacity.unbounded,
      ),
    ).map(store => new Environment(group, journal, store))

  def run(secondFails: Boolean): IO[Unit] =
    val initial = Vector(0, 10, 20, 30)
      .flatMap(offset =>
        Vector(
          inputId(ByteVector((offset + 1).toByte)) -> 10L,
          inputId(ByteVector((offset + 2).toByte)) -> 0L,
        ),
      )
      .toMap +
      (inputId(bytes("03")) -> 7L)
    for
      f <- materialAt(
        ExactMode.OrderedAtomic,
        false,
        false,
        990L,
        height(10L),
        false,
        false,
        0,
        initial,
      )
      g <- materialAt(
        ExactMode.OrderedAtomic,
        false,
        false,
        991L,
        height(10L),
        false,
        secondFails,
        20,
        initial,
      )
      group = new Group(f, g)
      journal <- MemoryDurableJournal.create[IO]
      env     <- open(group, journal)
      a     <- accepted(env.runtime.admit(f.request)).flatTap(f.retainAdmission)
      b     <- accepted(env.runtime.admit(g.request)).flatTap(g.retainAdmission)
      first <- accepted(
        env.requests.ordered(a.binding.nodePipelineId, group.evidence(f)),
      )
      second <- accepted(
        env.requests.ordered(b.binding.nodePipelineId, group.evidence(g)),
      )
      _ <- IO(
        assert(first.outcome.isRight && second.outcome.isLeft == secondFails),
      )
      _        <- accepted(env.runtime.executeOrdered(first))
      prepared <- accepted(
        env.voting.prepareConsensusVote(first.verifiedProposal),
      )
      _ <- rejected(env.voting.signConsensusVote(prepared))
      _ <-
        if secondFails then rejected(env.runtime.executeOrdered(second)).void
        else accepted(env.runtime.executeOrdered(second)).void
      _ <-
        if secondFails then
          rejected(env.voting.signConsensusVote(prepared)).void
        else accepted(env.voting.signConsensusVote(prepared)).void
      snapshot <- accepted(env.safety.snapshot)
      recordA  <- accepted(env.store.get(a.binding.nodePipelineId))
      recordB  <- accepted(env.store.get(b.binding.nodePipelineId))
      _        <- IO {
        assert(
          snapshot.claims.size == 4 && snapshot.consensusIntents.size == 1 && snapshot.canonical.isEmpty,
        )
        assert(
          recordA.stages.forall(_.lifecycle == ExactStageLifecycle.Reserved),
        )
        assert(
          recordB.stages.forall(_.lifecycle == (if secondFails then
                                                  ExactStageLifecycle.Failed
                                                else
                                                  ExactStageLifecycle.Reserved)),
        )
      }
      reopened <- env.reopen
      again    <- accepted(
        reopened.voting.prepareConsensusVote(first.verifiedProposal),
      )
      _      <- rejected(reopened.voting.signConsensusVote(again))
      aAgain <- accepted(
        reopened.requests.ordered(a.binding.nodePipelineId, group.evidence(f)),
      )
      bAgain <- accepted(
        reopened.requests.ordered(b.binding.nodePipelineId, group.evidence(g)),
      )
      _ <- accepted(reopened.runtime.executeOrdered(aAgain))
      _ <- rejected(reopened.voting.signConsensusVote(again))
      _ <-
        if secondFails then
          rejected(reopened.runtime.executeOrdered(bAgain)).void
        else accepted(reopened.runtime.executeOrdered(bAgain)).void
      _ <-
        if secondFails then
          rejected(reopened.voting.signConsensusVote(again)).void
        else accepted(reopened.voting.signConsensusVote(again)).void
    yield ()
