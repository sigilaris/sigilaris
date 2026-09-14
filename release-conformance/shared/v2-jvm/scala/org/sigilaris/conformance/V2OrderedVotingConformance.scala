package org.sigilaris.conformance

import cats.data.EitherT
import cats.effect.IO

import org.sigilaris.core.application.protocol.ExecutionId
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.application.protocol.v2.V2Codecs.bytesEncoder
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.node.gossip.StableArtifactId
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.node.jvm.runtime.block.{
  BlockBody,
  BlockRecord,
  BlockHeader,
  BlockHeaderVersion,
  BlockHeight,
  BlockTimestamp,
  StateRoot,
}
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{
  Proposal,
  ProposalTxSet,
  UnsignedProposal,
  UnsignedVote,
  ValidatorId,
  HotStuffWindow,
  ValidatorSet,
  HotStuffValidator,
}

/** Reusable neutral two-stage working-state execution. Both signed descriptors
  * exist before proposal construction; the second reads the first stage's
  * actual counter output from the immutable intermediate state.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.OptionPartial",
    "org.wartremover.warts.Nothing",
  ),
)
object V2OrderedVotingConformance:
  import V2RequestConformance.*
  import V2VotingConformance.{accepted, rejected, environmentWithVerifier}

  final class SequentialFixture:
    val first  = new Fixture(111L, Authority.ConsensusOnly)
    val second = new Fixture(
      112L,
      Authority.ConsensusOnly,
      2,
      Vector.empty,
      Some(first.nextState),
      Some(first.counterIdentity),
    )
    val sources = Vector(first, second)
    val entries = sources.map(_.consensusEntry)
    val plan    = ExecutionPlan(
      2L,
      Vector(ExecutionWave(WaveKind.Ordered, entries)),
      Vector.empty,
    )
    val planRoot = value(ExecutionPlan.computeRoot(plan))
    val body     = BlockBody[Hash, Hash, Bytes](
      sources
        .map(source =>
          BlockRecord(
            source.txId,
            Some(source.normalizedResult.digest.toUInt256),
            Vector.empty[Bytes],
          ),
        )
        .toSet,
    )
    val bodyRoot = BlockBody.computeBodyRoot(body).toOption.get
    val header   = BlockHeader(
      Some(first.baseBlockId),
      BlockHeight.unsafeFromLong(6L),
      StateRoot(second.nextStateRoot),
      bodyRoot,
      BlockTimestamp.unsafeFromEpochMillis(2000L),
      BlockHeaderVersion.V2,
      Some(planRoot),
    )
    val candidate = Proposal
      .sign(
        UnsignedProposal(
          first.candidateWindow,
          ValidatorId.unsafe("v1"),
          BlockHeader.computeId(header),
          header,
          ProposalTxSet(
            sources.map(source =>
              StableArtifactId.fromBytes(source.txId.bytes).toOption.get,
            ),
          ),
          first.baseCertificate,
        ),
        first.keys(0)._2,
      )
      .toOption
      .get
    val owners = sources.zipWithIndex.map((source, index) =>
      Owner(
        first.context,
        source.executionId,
        Scope(
          ScopeKind.ConsensusOrdered,
          first.baseBlockId.toUInt256,
          height(6L),
          planRoot.toUInt256,
          index.toLong,
          hash(
            first.baseCertificate.toBytes ++ value(
              PlanEntry.codec.encode(entries(index)),
            ) ++ planRoot.toBytes,
          ),
        ),
      ),
    )
    val observed = ExecutedProposal(
      candidate,
      UnsignedVote(
        first.candidateWindow,
        ValidatorId.unsafe("v1"),
        candidate.proposalId,
      ),
      first.preStateRoot,
      sources.map(source => BodyMember(source.txId, source.executionId)),
      bodyRoot.toUInt256,
      sources.zipWithIndex.map((source, index) =>
        ExecutedApplication(
          entries(index),
          source.descriptor,
          source.proofs,
          source.access,
          Vector(source.createIdentity -> source.creationProof),
          source.normalizedResult.bytes,
          source.nextStateRoot,
          owners(index),
          None,
        ),
      ),
    )

    private def selected(raw: Bytes): Either[CoreFailure, Fixture] = sources
      .find(_.signedTransaction == raw)
      .toRight(
        CoreFailure.at(FailureCode.ProofUnavailable, "retainedSequentialSource"),
      )
    val inputs = new InputAuthentication:
      def verify(
          family: InputManifest,
          raw: Bytes,
          root: Hash,
          field: ResolvedField,
          proof: ResolutionEvidence,
      ): Either[CoreFailure, Unit] =
        selected(raw).flatMap(_.inputs.verify(family, raw, root, field, proof))
      def verifyAbsentCreation(
          family: InputManifest,
          raw: Bytes,
          root: Hash,
          id: InputId,
          proof: Bytes,
      ): Either[CoreFailure, Unit] = selected(raw).flatMap(
        _.inputs.verifyAbsentCreation(family, raw, root, id, proof),
      )
    val declarations = new DeclaredFootprintAuthentication:
      def derive(
          family: InputManifest,
          raw: Bytes,
          root: Hash,
          descriptor: InputDescriptor,
      ): Either[CoreFailure, Option[Footprint]] =
        selected(raw).flatMap(
          _.declarations.derive(family, raw, root, descriptor),
        )
    val creations = new DeclaredCreationAuthentication:
      def derive(
          family: InputManifest,
          raw: Bytes,
          root: Hash,
          descriptor: InputDescriptor,
      ): Either[CoreFailure, Vector[(InputId, Bytes)]] =
        selected(raw).flatMap(_.creations.derive(family, raw, root, descriptor))
    val transactions = new TransactionAuthentication[IO]:
      def authenticate(
          context: DomainContext,
          family: InputManifest,
          raw: Bytes,
      ): Result[IO, SignedApplicationBinding] =
        EitherT
          .fromEither[IO](selected(raw).left.map(V2RuntimeFailure.fromCore))
          .flatMap(_.transactions.authenticate(context, family, raw))
    val executionRepository = new ApplicationExecutionRepository[IO]:
      def lockSource(
          context: DomainContext,
          executionId: ExecutionId,
      ): Result[IO, LockSourceMaterial] = unavailable("no eligible input locks")
      def lockCertificate(id: Hash): Result[IO, LockCertificate] = unavailable(
        "no eligible input locks",
      )
      def effectCertificate(id: Hash): Result[IO, EffectCertificate] =
        unavailable("consensus sources have no effect certificate")
      def executeEffect(
          context: DomainContext,
          executionId: ExecutionId,
      ): Result[IO, ExecutedEffect] = unavailable("consensus source")
      def verifyExactBinding(
          context: DomainContext,
          executionId: ExecutionId,
          binding: Option[Hash],
      ): Result[IO, Unit] =
        if context == first.context && sources.exists(
            _.executionId == executionId,
          ) && binding.isEmpty
        then EitherT.pure(())
        else unavailable("generic sequential source binding")

    def verifierWith(
        observation: ExecutedProposal,
        branchArtifact: Option[Bytes],
    ): ApplicationRequestVerifier[IO] =
      val scopes = new ReservationScopeAuthentication[IO]:
        def verifyFast(
            owner: Owner,
            base: AdmissionBase,
            source: SignedApplicationBinding,
        ): Result[IO, Unit] = unavailable("consensus owner")
        def verifyConsensus(
            owner: Owner,
            supplied: Proposal,
            suppliedPlan: ExecutionPlan,
            entry: PlanEntry,
        ): Result[IO, Unit] =
          if branchArtifact.contains(
              first.baseCertificate.toBytes,
            ) && supplied == candidate && suppliedPlan == plan &&
            entries.contains(entry) && owners.contains(
              owner,
            ) && owner.executionId == entry.executionId &&
            owner.scope.authorizationDigest == hash(
              first.baseCertificate.toBytes ++ value(
                PlanEntry.codec.encode(entry),
              ) ++ planRoot.toBytes,
            ) &&
            HotStuffValidator
              .validateQuorumCertificate(
                first.baseCertificate,
                first.validators,
              )
              .isRight &&
            HotStuffValidator
              .validateProposal(supplied, first.validators)
              .isRight && supplied.block.parent.contains(first.baseBlockId)
          then EitherT.pure(())
          else unavailable("complete signed canonical branch artifact")
      val proposals = new ProposalExecutionRepository[IO]:
        def historicalValidators(
            window: HotStuffWindow,
        ): Result[IO, ValidatorSet] =
          first.unavailableProposals.historicalValidators(window)
        def executeSequential(
            supplied: Proposal,
            suppliedPlan: ExecutionPlan,
        ): Result[IO, ExecutedProposal] =
          if supplied == candidate && suppliedPlan == plan && second.preStateRoot == first.nextStateRoot && second.state == first.nextState
          then EitherT.pure(observation)
          else unavailable("actual sequential working-state execution")
      ApplicationRequestVerifier.authenticated(
        first.manifest,
        inputs,
        declarations,
        creations,
        transactions,
        first.artifacts,
        scopes,
        executionRepository,
        proposals,
      )
    val verifier = verifierWith(observed, Some(first.baseCertificate.toBytes))

  def run(): IO[Unit] =
    val f = new SequentialFixture
    for
      request <- accepted(f.verifier.verifyProposal(f.candidate, f.plan))
      _       <- IO(
        assert(
          request.reservations.size == 2 && f.second.state(
            f.first.counterIdentity,
          ) == 1L &&
            f.first.footprint.writes.contains(
              f.first.counterIdentity,
            ) && f.second.footprint.reads.contains(f.first.counterIdentity) &&
            f.first.footprint.reads.contains(
              f.first.readIdentity,
            ) && f.second.footprint.writes.contains(f.first.readIdentity) &&
            f.owners(0).scope.authorizationDigest != f
              .owners(1)
              .scope
              .authorizationDigest,
        ),
      )
      journal  <- MemoryDurableJournal.create[IO]
      env      <- environmentWithVerifier(f.first, journal, f.verifier)
      prepared <- accepted(env.runtime(0).prepareConsensusVote(request))
      _        <- accepted(env.runtime(0).signConsensusVote(prepared))
      stored   <- accepted(env.store.snapshot)
      _        <- IO(
        assert(
          stored.intents.isEmpty && stored.locks.isEmpty && stored.consensusIntents.size == 1 && stored.claims.size == 2 &&
            stored.index
              .find(_.identity == f.first.counterIdentity)
              .exists(_.owners.size == 2),
        ),
      )
      recovered <- env.reopen
      state     <- accepted(recovered.store.snapshot)
      _         <- IO(
        assert(
          state.claims == stored.claims && state.index == stored.index && state.consensusIntents == stored.consensusIntents,
        ),
      )
      _ <- rejected(
        f.verifierWith(f.observed, None).verifyProposal(f.candidate, f.plan),
      )
      misclassified = f.observed.copy(entries =
        f.observed.entries.map(entry =>
          entry.copy(owner =
            entry.owner.copy(scope =
              entry.owner.scope.copy(kind = ScopeKind.ConsensusConflictFree),
            ),
          ),
        ),
      )
      _ <- rejected(
        f.verifierWith(misclassified, Some(f.first.baseCertificate.toBytes))
          .verifyProposal(f.candidate, f.plan),
      )
      missingWorkingState = f.observed.copy(entries =
        f.observed.entries.updated(
          1,
          f.observed
            .entries(1)
            .copy(entry =
              f.entries(1).copy(entryPreStateRoot = f.first.preStateRoot),
            ),
        ),
      )
      _ <- rejected(
        f.verifierWith(
          missingWorkingState,
          Some(f.first.baseCertificate.toBytes),
        ).verifyProposal(f.candidate, f.plan),
      )
      _ <- IO(
        println(
          "V2OrderedVotingConformance PASS: two signed sequential entries, different authenticated entry scopes, complete overlapping reservations and restart",
        ),
      )
    yield ()
