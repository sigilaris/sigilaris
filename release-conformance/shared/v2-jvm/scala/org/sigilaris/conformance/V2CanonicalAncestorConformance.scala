package org.sigilaris.conformance

import java.time.Instant

import cats.data.EitherT
import cats.effect.{IO, Ref}
import cats.syntax.all.*

import org.sigilaris.conformance.V2RequestConformance.*
import org.sigilaris.conformance.V2VotingConformance.accepted
import org.sigilaris.core.application.protocol.{
  ApplicationValidatorId,
  ExecutionId,
}
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.application.protocol.v2.V2Codecs.bytesEncoder
import org.sigilaris.node.jvm.runtime.block.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*

/** Each of four validator runtimes owns one key and an independent durable
  * safety journal. QCs below are assembled from their actual prepared/signed
  * consensus votes; ancestry never accepts a fabricated Verified* wrapper.
  */
object V2CanonicalAncestorConformance:
  def cases: Vector[(String, () => IO[Unit])] = new Scenarios().cases
  def run(): IO[Unit]                         = cases.traverse_ { (name, run) =>
    IO.println("V2CanonicalAncestorConformance: " + name) *> IO.defer(run())
  }
  private final class Scenarios:
    private val registered =
      scala.collection.mutable.ArrayBuffer.empty[(String, () => IO[Unit])]
    def cases: Vector[(String, () => IO[Unit])] = registered.toVector
    private def scenario(name: String)(run: => IO[Unit]): Unit =
      registered.addOne(name -> (() => run)): Unit
    private def fail(message: String): Nothing = throw new AssertionError(
      message,
    )
    private def assertEquals[A, B](actual: A, expected: B): Unit =
      assert(
        actual == expected,
        "expected " + expected.toString + "; actual " + actual.toString,
      )
    private def available(result: AncestorLookupResult): VerifiedAncestorProof =
      result match
        case AncestorLookupResult.Available(proof) => proof
        case other                                 =>
          fail("expected verified ancestry, received " + other.toString)
    private def invalid(result: AncestorLookupResult): V2RuntimeFailure =
      result match
        case AncestorLookupResult.Invalid(reason) => reason
        case other                                =>
          fail("expected invalid ancestry, received " + other.toString)
    private def unavailableResult(
        result: AncestorLookupResult,
    ): V2RuntimeFailure = result match
      case AncestorLookupResult.Unavailable(reason) => reason
      case other                                    =>
        fail("expected unavailable ancestry, received " + other.toString)

    private final case class Voter(
        verifier: ApplicationRequestVerifier[IO],
        store: JournalSafetyStore[IO],
        runtime: DurableApplicationVoting[IO],
    )

    private final class Environment(
        val f: org.sigilaris.conformance.V2RequestConformance.Fixture,
        val proposals: Ref[IO, Map[Hash, (Proposal, ExecutionPlan)]],
        val approvedCandidate: Ref[IO, Option[Proposal]],
        val retained: Ref[IO, Map[Hash, AncestorHistoryEntry]],
        val archive: Ref[IO, Map[Hash, AncestorHistoryEntry]],
        val state: Ref[IO, Option[HotStuffPacemakerState]],
        val voters: Vector[Voter],
        val executionReads: Ref[IO, Int],
    ):
      val empty         = ExecutionPlan.empty
      val emptyRoot     = value(ExecutionPlan.computeRoot(empty))
      val emptyBodyRoot = BlockBody
        .computeBodyRoot(BlockBody[Hash, Hash, Bytes](Set.empty))
        .toOption
        .get
      val history = new AncestorHistoryRepository[IO]:
        def retained(
            context: DomainContext,
            blockId: Hash,
        ): Result[IO, Option[AncestorHistoryEntry]] =
          EitherT.liftF(Environment.this.retained.get.map(_.get(blockId)))
        def backfill(
            context: DomainContext,
            blockId: Hash,
        ): Result[IO, Option[AncestorHistoryEntry]] =
          EitherT.liftF(archive.get.map(_.get(blockId)))
      val profiles = new AncestorProfileRepository[IO]:
        def historical(
            context: DomainContext,
            window: HotStuffWindow,
        ): Result[IO, AncestorApplicationProfile[IO]] =
          if context == f.context && window.chainId == f.chain then
            EitherT.pure(
              AncestorApplicationProfile(f.manifest, voters.head.verifier),
            )
          else unavailable("authenticated historical manifest unavailable")
      val validators = ValidatorSetLookup.static[IO](
        BootstrapTrustRoot.staticValidatorSet(f.validators),
      )
      val approval = new ApprovedAncestorParentSource[IO]:
        def current(
            context: DomainContext,
        ): Result[IO, HotStuffPacemakerState] =
          EitherT(
            state.get.map(
              _.toRight(
                V2RuntimeFailure.at(
                  RuntimeFailureCode.RecoveryRequired,
                  "local pacemaker not recovered",
                ),
              ),
            ),
          )

      def lookup(maximumBlocks: Long): CanonicalAncestorLookup[IO] =
        CanonicalAncestorLookup.authenticated(
          f.context,
          history,
          profiles,
          validators,
          approval,
          AncestorLookupCapacity(maximumBlocks),
        )

      def certify(
          proposal: Proposal,
          plan: ExecutionPlan,
      ): IO[AncestorHistoryEntry] =
        for
          _ <- proposals.update(
            _.updated(proposal.targetBlockId.toUInt256, proposal -> plan),
          )
          _     <- approvedCandidate.set(Some(proposal))
          votes <- voters.parTraverse(voter =>
            for
              request <- accepted(voter.verifier.verifyProposal(proposal, plan))
              prepared <- accepted(voter.runtime.prepareConsensusVote(request))
              vote     <- accepted(voter.runtime.signConsensusVote(prepared))
            yield vote,
          )
          qc = QuorumCertificateAssembler
            .assemble(
              QuorumCertificateSubject(
                proposal.window,
                proposal.proposalId,
                proposal.targetBlockId,
              ),
              votes,
              f.validators,
            )
            .fold(error => fail(error.reason), identity)
          entry = AncestorHistoryEntry(f.context, proposal, plan, qc)
          _ <- retained.update(
            _.updated(proposal.targetBlockId.toUInt256, entry),
          )
        yield entry

      def emptyProposal(
          parent: QuorumCertificate,
          parentStateRoot: Hash,
          nextHeight: Long,
          view: Long,
      ): Proposal =
        val header = BlockHeader(
          Some(parent.subject.blockId),
          BlockHeight.unsafeFromLong(nextHeight),
          StateRoot(parentStateRoot),
          emptyBodyRoot,
          BlockTimestamp.unsafeFromEpochMillis(nextHeight * 1000L + view),
          BlockHeaderVersion.V2,
          Some(emptyRoot),
        )
        val window = HotStuffWindow
          .fromLongs(f.chain, nextHeight, view, f.validators.hash)
          .toOption
          .get
        Proposal
          .sign(
            UnsignedProposal(
              window,
              ValidatorId.unsafe("v1"),
              BlockHeader.computeId(header),
              header,
              ProposalTxSet(Vector.empty),
              parent,
            ),
            f.keys.head._2,
          )
          .fold(error => fail(error.reason), identity)

      def child(parent: AncestorHistoryEntry): IO[AncestorHistoryEntry] =
        certify(
          emptyProposal(
            parent.certificate,
            parent.proposal.block.stateRoot.toUInt256,
            parent.proposal.block.height.toBigNat.toBigInt.toLong + 1L,
            0L,
          ),
          empty,
        )

      def approve(parent: AncestorHistoryEntry): IO[Unit] =
        val active = HotStuffWindow
          .fromLongs(
            f.chain,
            parent.proposal.block.height.toBigNat.toBigInt.toLong + 1L,
            0L,
            f.validators.hash,
          )
          .toOption
          .get
        val selected = HotStuffPacemakerRuntime(
          ValidatorId.unsafe("v1"),
          f.validators,
          HotStuffPacemakerPolicy.default,
        ).start(active, parent.certificate, Instant.EPOCH, None).state
        state.set(Some(selected))

      def request(parent: AncestorHistoryEntry): AncestorRequest =
        AncestorRequest(
          f.context,
          parent.proposal.targetBlockId.toUInt256,
          f.candidate.targetBlockId.toUInt256,
          f.executionId,
          f.consensusEntry.dependencyPlanDigest,
          f.normalizedResult.digest.toUInt256,
          f.deadline,
        )

    private def environment: IO[Environment] =
      val f = new org.sigilaris.conformance.V2RequestConformance.Fixture(
        1L,
        Authority.ConsensusOnly,
      )
      for
        proposals <- Ref.of[IO, Map[Hash, (Proposal, ExecutionPlan)]](Map.empty)
        candidate <- Ref.of[IO, Option[Proposal]](None)
        retained  <- Ref.of[IO, Map[Hash, AncestorHistoryEntry]](Map.empty)
        archive   <- Ref.of[IO, Map[Hash, AncestorHistoryEntry]](Map.empty)
        state     <- Ref.of[IO, Option[HotStuffPacemakerState]](None)
        reads     <- Ref.of[IO, Int](0)
        voters    <- f.keys.traverse((id, key) =>
          val executions = new ProposalExecutionRepository[IO]:
            def historicalValidators(
                window: HotStuffWindow,
            ): Result[IO, ValidatorSet] =
              if window.chainId == f.chain && window.validatorSetHash == f.validators.hash
              then EitherT.pure(f.validators)
              else unavailable("historical validators unavailable")
            def executeSequential(
                proposal: Proposal,
                plan: ExecutionPlan,
            ): Result[IO, ExecutedProposal] =
              EitherT(
                reads.update(_ + 1) *> proposals.get.map(known =>
                  if !known
                      .get(proposal.targetBlockId.toUInt256)
                      .contains(proposal -> plan)
                  then
                    Left(
                      V2RuntimeFailure.at(
                        RuntimeFailureCode.ProofUnavailable,
                        "historical source execution unavailable",
                      ),
                    )
                  else if proposal == f.candidate && plan == f.consensusPlan
                  then
                    Right(
                      f.executedCandidate.copy(unsignedVote =
                        f.executedCandidate.unsignedVote
                          .copy(voter = ValidatorId.unsafe(id.asString)),
                      ),
                    )
                  else
                    val parentRoot = proposal.block.parent.flatMap(parent =>
                      if parent == f.baseBlockId then Some(f.preStateRoot)
                      else
                        known
                          .get(parent.toUInt256)
                          .map(_._1.block.stateRoot.toUInt256),
                    )
                    parentRoot
                      .filter(_ => plan == ExecutionPlan.empty)
                      .map(root =>
                        ExecutedProposal(
                          proposal,
                          UnsignedVote(
                            proposal.window,
                            ValidatorId.unsafe(id.asString),
                            proposal.proposalId,
                          ),
                          root,
                          Vector.empty,
                          BlockBody
                            .computeBodyRoot(
                              BlockBody[Hash, Hash, Bytes](Set.empty),
                            )
                            .toOption
                            .get
                            .toUInt256,
                          Vector.empty,
                        ),
                      )
                      .toRight(
                        V2RuntimeFailure.at(
                          RuntimeFailureCode.ProofUnavailable,
                          "complete empty-block parent state unavailable",
                        ),
                      ),
                ),
              )
          val verifier = ApplicationRequestVerifier.authenticated(
            f.manifest,
            f.inputs,
            f.declarations,
            f.creations,
            f.transactions,
            f.artifacts,
            f.scopes,
            f.repository(f.effect),
            executions,
          )
          val publication = new SafetyPublication[IO]:
            def finalizedHeight(context: DomainContext): Result[IO, Height] =
              EitherT.pure(height(5))
            def verifyEffectState(
                request: VerifiedEffectRequest,
            ): Result[IO, Unit] = unavailable("fixture uses consensus sources")
            def verifyConsensusState(
                request: VerifiedConsensusProposal,
            ): Result[IO, Unit] = EitherT(
              candidate.get.map(selected =>
                Either.cond(
                  selected.contains(
                    request.proposal,
                  ) && request.context == f.context,
                  (),
                  V2RuntimeFailure.at(
                    RuntimeFailureCode.ProofInvalid,
                    "proposal is not currently selected by this voter",
                  ),
                ),
              ),
            )
          for
            journal <- MemoryDurableJournal.create[IO]
            store   <- accepted(
              JournalSafetyStore.open(
                ApplicationAnchor(
                  f.context,
                  f.baseBlockId.toUInt256,
                  height(5),
                  f.preStateRoot,
                ),
                journal,
                publication,
                SafetyProfile(f.manifest, f.artifacts),
                SafetyRecoveryAuthentication.voting(verifier),
                ReservationOrdering.isolated[IO],
                SafetyCapacity.unbounded,
              ),
            )
            signer = ApplicationVoteSigner
              .secp256k1[IO](ApplicationValidatorId(id), key)
          yield Voter(
            verifier,
            store,
            DurableApplicationVoting.fromStore(store, signer, f.artifacts),
          ),
        )
      yield new Environment(
        f,
        proposals,
        candidate,
        retained,
        archive,
        state,
        voters,
        reads,
      )

    private def chain(env: Environment): IO[Vector[AncestorHistoryEntry]] = for
      producer <- env.certify(env.f.candidate, env.f.consensusPlan)
      child1   <- env.child(producer)
      child2   <- env.child(child1)
      child3   <- env.child(child2)
      _        <- env.approve(child3)
    yield Vector(producer, child1, child2, child3)

    scenario(
      "a genuine four-voter certified history proves an older producer and complete normalized output",
    ):
      for
        env    <- environment
        blocks <- chain(env)
        result <- env.lookup(4L).lookup(env.request(blocks.last))
        proof = available(result)
        _     = assertEquals(
          proof.certifiedPath.map(_.proposal.targetBlockId),
          blocks.reverse.map(_.proposal.targetBlockId),
        )
        _ = assertEquals(proof.normalizedOutput, env.f.normalizedResult)
        _ = assertEquals(proof.producerEntry.executionId, env.f.executionId)
        _ = assertEquals(
          proof.producerEntry.dependencyPlanDigest,
          env.f.consensusEntry.dependencyPlanDigest,
        )
        _ = assert(
          blocks.forall(_.certificate.votes.map(_.voter).distinct.size == 4),
        )
        _ <- env.voters.traverse_(voter => accepted(voter.store.recover))
        restarted <- env.lookup(4L).lookup(env.request(blocks.last))
      yield assertEquals(
        available(restarted).normalizedOutput,
        proof.normalizedOutput,
      )

    scenario(
      "a producer older than a genuinely finalized tip remains provable after archive backfill and restart",
    ):
      for
        env    <- environment
        blocks <- chain(env)
        finalized = FinalizedAnchorSuggestion(
          blocks(1).proposal,
          FinalizedProof(blocks(2).proposal, blocks(3).proposal),
        )
        verifiedFinality <- HotStuffFinalizedAnchorVerifier.verify(
          finalized,
          env.validators,
        )
        _ = assertEquals(verifiedFinality, Right(finalized))
        _ = assert(
          finalized.anchorHeight.toBigNat.toBigInt > blocks.head.proposal.block.height.toBigNat.toBigInt,
        )
        retained <- env.retained.get
        _        <- env.archive.set(retained)
        _        <- env.retained.update(
          _ - blocks.head.proposal.targetBlockId.toUInt256,
        )
        proof <- env.lookup(4L).lookup(env.request(blocks.last))
        _ = assertEquals(
          available(proof).producerEntry.executionId,
          env.f.executionId,
        )
        _ <- env.voters.traverse_(voter => accepted(voter.store.recover))
        restarted <- env.lookup(4L).lookup(env.request(blocks.last))
      yield assertEquals(
        available(restarted).certifiedPath,
        available(proof).certifiedPath,
      )

    scenario(
      "a certified unfinalized producer equal to the candidate parent still needs source execution and a real QC",
    ):
      for
        env      <- environment
        producer <- env.certify(env.f.candidate, env.f.consensusPlan)
        _        <- env.approve(producer)
        before   <- env.executionReads.get
        result   <- env.lookup(1L).lookup(env.request(producer))
        after    <- env.executionReads.get
        _ = assertEquals(available(result).certifiedPath.size, 1)
        _ = assert(after > before)
        _ <- env.retained.update(
          _.updated(
            producer.proposal.targetBlockId.toUInt256,
            producer.copy(certificate =
              producer.certificate.copy(votes =
                producer.certificate.votes.take(2),
              ),
            ),
          ),
        )
        bad <- env.lookup(1L).lookup(env.request(producer))
      yield assertEquals(invalid(bad).code, RuntimeFailureCode.ProofInvalid)

    scenario(
      "missing history is unavailable, genuine backfill and a restarted lookup recover it",
    ):
      for
        env    <- environment
        blocks <- chain(env)
        missing = blocks(1)
        key     = missing.proposal.targetBlockId.toUInt256
        _      <- env.retained.update(_ - key)
        absent <- env.lookup(8L).lookup(env.request(blocks.last))
        _ = assertEquals(
          unavailableResult(absent).code,
          RuntimeFailureCode.ProofUnavailable,
        )
        _      <- env.archive.update(_.updated(key, missing))
        filled <- env.lookup(8L).lookup(env.request(blocks.last))
      yield assertEquals(available(filled).certifiedPath.size, 4)

    scenario(
      "local capacity never converts a valid long branch into protocol invalidity",
    ):
      for
        env       <- environment
        blocks    <- chain(env)
        exhausted <- env.lookup(3L).lookup(env.request(blocks.last))
        _ = assertEquals(
          unavailableResult(exhausted).code,
          RuntimeFailureCode.CapacityUnavailable,
        )
        sufficient <- env.lookup(4L).lookup(env.request(blocks.last))
      yield assertEquals(available(sufficient).certifiedPath.size, 4)

    scenario(
      "wrong pipeline, output, deadline, execution, domain and unapproved parent are rejected",
    ):
      for
        env    <- environment
        blocks <- chain(env)
        request = env.request(blocks.last)
        changed = Vector(
          (
            request.copy(pipelineDigest = uint(77)),
            RuntimeFailureCode.CommitmentMismatch,
            "producer belongs to another signed pipeline",
          ),
          (
            request.copy(outputDigest = uint(78)),
            RuntimeFailureCode.CommitmentMismatch,
            "producer normalized output differs from the registered output digest",
          ),
          (
            request.copy(deadline = height(11)),
            RuntimeFailureCode.DeadlineMismatch,
            "producer pipeline binding or common signed deadline differs",
          ),
          (
            request.copy(deadline = height(9)),
            RuntimeFailureCode.DeadlineMismatch,
            "no consumer inclusion remains at the shared deadline",
          ),
          (
            request.copy(producerExecution = ExecutionId(uint(79))),
            RuntimeFailureCode.ProofInvalid,
            "producer execution is absent or repeated in the certified block",
          ),
          (
            request.copy(context = request.context.copy(epoch = 1L)),
            RuntimeFailureCode.DomainMismatch,
            "ancestor request differs from the installed domain",
          ),
          (
            request.copy(candidateParent =
              blocks.head.proposal.targetBlockId.toUInt256,
            ),
            RuntimeFailureCode.ProofInvalid,
            "candidate parent is not the current approved high-QC parent",
          ),
        )
        _ <- changed.traverse_ { (mutation, code, detail) =>
          env.lookup(8L).lookup(mutation).map { result =>
            assertEquals(invalid(result).code, code)
            assertEquals(invalid(result).detail, detail)
          }
        }
      yield ()

    scenario(
      "a fully signed certified height gap and a certified unrelated fork do not prove ancestry",
    ):
      for
        env      <- environment
        producer <- env.certify(env.f.candidate, env.f.consensusPlan)
        gap      <- env.certify(
          env.emptyProposal(
            producer.certificate,
            producer.proposal.block.stateRoot.toUInt256,
            8L,
            0L,
          ),
          env.empty,
        )
        _      <- env.approve(gap)
        gapped <- env.lookup(8L).lookup(env.request(gap))
        _ = assert(invalid(gapped).detail.contains("continuity"))
        fork <- env.certify(
          env.emptyProposal(env.f.baseCertificate, env.f.preStateRoot, 6L, 1L),
          env.empty,
        )
        forkChild <- env.child(fork)
        _         <- env.approve(forkChild)
        unrelated <- env.lookup(8L).lookup(env.request(forkChild))
      yield assert(invalid(unrelated).detail.contains("another branch"))

    scenario(
      "contradictory historical profiles, lookup substitution and malformed cyclic material are rejected",
    ):
      for
        env    <- environment
        blocks <- chain(env)
        producer    = blocks.head
        badProfiles = new AncestorProfileRepository[IO]:
          def historical(
              context: DomainContext,
              window: HotStuffWindow,
          ): Result[IO, AncestorApplicationProfile[IO]] = EitherT.pure(
            AncestorApplicationProfile(
              env.f.manifest.copy(epoch = 1L),
              env.voters.head.verifier,
            ),
          )
        profileLookup = CanonicalAncestorLookup.authenticated(
          env.f.context,
          env.history,
          badProfiles,
          env.validators,
          env.approval,
          AncestorLookupCapacity(8L),
        )
        profile <- profileLookup.lookup(env.request(blocks.last))
        _ = assertEquals(
          invalid(profile).code,
          RuntimeFailureCode.DomainMismatch,
        )
        _ <- env.retained.update(
          _.updated(producer.proposal.targetBlockId.toUInt256, blocks(1)),
        )
        substituted <- env.lookup(8L).lookup(env.request(blocks.last))
        _ = assertEquals(
          invalid(substituted).code,
          RuntimeFailureCode.ProofInvalid,
        )
        cyclic = producer.copy(proposal =
          producer.proposal.copy(block =
            producer.proposal.block
              .copy(parent = Some(producer.proposal.targetBlockId)),
          ),
        )
        _ <- env.retained.update(
          _.updated(producer.proposal.targetBlockId.toUInt256, cyclic),
        )
        cycle <- env.lookup(8L).lookup(env.request(blocks.last))
      yield assertEquals(invalid(cycle).code, RuntimeFailureCode.ProofInvalid)

    scenario(
      "unrecovered local approval and missing historical execution preserve an unavailable result",
    ):
      for
        env       <- environment
        producer  <- env.certify(env.f.candidate, env.f.consensusPlan)
        _         <- env.approve(producer)
        goodState <- env.state.get
        _         <- env.state.set(None)
        held      <- env.lookup(1L).lookup(env.request(producer))
        _ = assertEquals(
          unavailableResult(held).code,
          RuntimeFailureCode.RecoveryRequired,
        )
        _ <- env.state.set(goodState)
        _ <- env.proposals.update(_ - producer.proposal.targetBlockId.toUInt256)
        source <- env.lookup(1L).lookup(env.request(producer))
      yield assertEquals(
        unavailableResult(source).code,
        RuntimeFailureCode.ProofUnavailable,
      )

    scenario(
      "the approval source must supply a genuine QC and remain approved through the end of lookup",
    ):
      for
        env    <- environment
        blocks <- chain(env)
        reads  <- Ref.of[IO, Int](0)
        changing = new ApprovedAncestorParentSource[IO]:
          def current(
              context: DomainContext,
          ): Result[IO, HotStuffPacemakerState] = for
            count <- EitherT.liftF(reads.getAndUpdate(_ + 1))
            state <- env.approval.current(context)
          yield
            if count == 0 then state
            else state.copy(highestKnownQc = blocks(2).certificate)
        changedLookup = CanonicalAncestorLookup.authenticated(
          env.f.context,
          env.history,
          env.profiles,
          env.validators,
          changing,
          AncestorLookupCapacity(8L),
        )
        changed <- changedLookup.lookup(env.request(blocks.last))
        _     = assert(invalid(changed).detail.contains("current approved"))
        badQc = new ApprovedAncestorParentSource[IO]:
          def current(
              context: DomainContext,
          ): Result[IO, HotStuffPacemakerState] = env.approval
            .current(context)
            .map(state =>
              state.copy(highestKnownQc =
                state.highestKnownQc
                  .copy(votes = state.highestKnownQc.votes.take(2)),
              ),
            )
        badLookup = CanonicalAncestorLookup.authenticated(
          env.f.context,
          env.history,
          env.profiles,
          env.validators,
          badQc,
          AncestorLookupCapacity(8L),
        )
        uncertified <- badLookup.lookup(env.request(blocks.last))
      yield assertEquals(
        invalid(uncertified).code,
        RuntimeFailureCode.ProofInvalid,
      )
