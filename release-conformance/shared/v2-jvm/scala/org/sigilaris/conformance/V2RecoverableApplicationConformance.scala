package org.sigilaris.conformance

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

import cats.data.EitherT
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.conformance.V2RequestConformance.*
import org.sigilaris.conformance.V2VotingConformance.{
  Publication,
  accepted,
  rejected,
}
import org.sigilaris.core.application.protocol.ApplicationInputId
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.application.protocol.v2.V2Codecs.bytesEncoder
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.node.jvm.runtime.block.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
import org.sigilaris.node.jvm.storage.file.FileApplicationJournal

object V2RecoverableApplicationConformance:
  def cases: Vector[(String, () => IO[Unit])] = new Scenarios().cases
  def run(): IO[Unit]                         = cases.traverse_ { (name, run) =>
    IO.println("V2RecoverableApplicationConformance: " + name) *> IO.defer(
      run(),
    )
  }
  private final class Scenarios:
    private val registered =
      scala.collection.mutable.ArrayBuffer.empty[(String, () => IO[Unit])]
    def cases: Vector[(String, () => IO[Unit])] = registered.toVector
    private def scenario(name: String)(run: => IO[Unit]): Unit =
      registered.addOne(name -> (() => run)): Unit
    private def assertEquals[A, B](actual: A, expected: B): Unit =
      assert(
        actual == expected,
        "expected " + expected.toString + "; actual " + actual.toString,
      )
    private final class Material(
        authority: Authority = Authority.ConsensusOnly,
    ):
      val f =
        new org.sigilaris.conformance.V2RequestConformance.Fixture(
          1L,
          authority,
        )
      val anchor = ApplicationAnchor(
        f.context,
        f.baseBlockId.toUInt256,
        height(5),
        f.preStateRoot,
      )
      val emptyPlan     = ExecutionPlan.empty
      val emptyRoot     = value(ExecutionPlan.computeRoot(emptyPlan))
      val emptyBodyRoot = BlockBody
        .computeBodyRoot(BlockBody[Hash, Hash, Bytes](Set.empty))
        .toOption
        .get
      def certificate(proposal: Proposal): QuorumCertificate =
        QuorumCertificate(
          QuorumCertificateSubject(
            proposal.window,
            proposal.proposalId,
            proposal.targetBlockId,
          ),
          f.keys
            .take(3)
            .map((id, key) =>
              Vote
                .sign(
                  UnsignedVote(
                    proposal.window,
                    ValidatorId.unsafe(id.asString),
                    proposal.proposalId,
                  ),
                  key,
                )
                .toOption
                .get,
            ),
        )
      def child(parent: Proposal): Proposal =
        val nextHeight = parent.block.height.toBigNat.toBigInt.toLong + 1L
        val header     = BlockHeader(
          Some(parent.targetBlockId),
          BlockHeight.unsafeFromLong(nextHeight),
          parent.block.stateRoot,
          emptyBodyRoot,
          BlockTimestamp.unsafeFromEpochMillis(nextHeight * 1000),
          BlockHeaderVersion.V2,
          Some(emptyRoot),
        )
        val window = HotStuffWindow
          .fromLongs(f.chain, nextHeight, 0L, f.validators.hash)
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
              certificate(parent),
            ),
            f.keys.head._2,
          )
          .toOption
          .get
      val chain = (7L to 13L).foldLeft(Vector(f.candidate))((all, _) =>
        all :+ child(all.last),
      )
      def finality(index: Int): FinalizedAnchorSuggestion =
        FinalizedAnchorSuggestion(
          chain(index),
          FinalizedProof(chain(index + 1), chain(index + 2)),
        )
      val lookup = ValidatorSetLookup.static[IO](
        BootstrapTrustRoot.staticValidatorSet(f.validators),
      )
      val proposals = new ProposalExecutionRepository[IO]:
        def historicalValidators(
            window: HotStuffWindow,
        ): Result[IO, ValidatorSet] =
          if window.chainId == f.chain && window.validatorSetHash == f.validators.hash
          then EitherT.pure(f.validators)
          else unavailable("historical validator domain")
        def executeSequential(
            proposal: Proposal,
            plan: ExecutionPlan,
        ): Result[IO, ExecutedProposal] =
          if proposal == f.candidate && plan == f.consensusPlan then
            EitherT.pure(f.executedCandidate)
          else if chain.drop(1).contains(proposal) && plan == emptyPlan then
            EitherT.pure(
              ExecutedProposal(
                proposal,
                UnsignedVote(
                  proposal.window,
                  ValidatorId.unsafe("v1"),
                  proposal.proposalId,
                ),
                f.nextStateRoot,
                Vector.empty,
                emptyBodyRoot.toUInt256,
                Vector.empty,
              ),
            )
          else unavailable("complete canonical execution")
      val requests = ApplicationRequestVerifier.authenticated(
        f.manifest,
        f.inputs,
        f.declarations,
        f.creations,
        f.transactions,
        f.artifacts,
        f.scopes,
        f.repository(f.effect),
        proposals,
      )
      val minority = new org.sigilaris.conformance.V2RequestConformance.Fixture(
        2L,
        Authority.LockEligible,
      )
      private def source(
          execution: org.sigilaris.core.application.protocol.ExecutionId,
      ): ApplicationRequestVerifier[IO] =
        if execution == f.executionId then f.verifier else minority.verifier
      val recoveryRequests = new ApplicationRequestVerifier[IO]:
        def verifyLock(
            subject: LockSubject,
            descriptor: InputDescriptor,
            signed: Bytes,
            proofs: Vector[ResolutionEvidence],
        ): Result[IO, VerifiedLockRequest] = source(subject.executionId)
          .verifyLock(subject, descriptor, signed, proofs)
        def verifyEffect(
            subject: EffectSubject,
            owner: Owner,
            witness: ReservationWitness,
        ): Result[IO, VerifiedEffectRequest] =
          source(subject.executionId).verifyEffect(subject, owner, witness)
        def verifyProposal(
            proposal: Proposal,
            plan: ExecutionPlan,
        ): Result[IO, VerifiedConsensusProposal] =
          requests.verifyProposal(proposal, plan)
        def verifyLockCertificate(
            certificate: LockCertificate,
        ): Result[IO, VerifiedLockCertificate] = source(
          certificate.subject.executionId,
        ).verifyLockCertificate(certificate)
        def verifyEffectCertificate(
            certificate: EffectCertificate,
        ): Result[IO, VerifiedEffectCertificate] = source(
          certificate.subject.executionId,
        ).verifyEffectCertificate(certificate)
      val payload = f.nextState.toVector
        .sortBy(_._1.toHex)
        .foldLeft(ByteVector.empty)((acc, entry) =>
          acc ++ entry._1.bytes ++ entry._2.toBytes,
        )
      val states = new ApplicationStateAuthentication[IO]:
        def authenticate(
            request: VerifiedConsensusProposal,
            bytes: Bytes,
        ): Result[IO, AuthenticatedApplicationState] =
          val parts    = bytes.grouped(9L).toVector
          val complete = parts.forall(_.size == 9L)
          val rows     = parts
            .filter(_.size == 9L)
            .map(part =>
              ApplicationInputId
                .fromBytes(part.take(1L))
                .toOption
                .get -> BigInt(
                part.drop(1L).toArray,
              ).toLong,
            )
          val restored  = rows.toMap
          val canonical = restored.toVector
            .sortBy(_._1.toHex)
            .foldLeft(ByteVector.empty)((acc, row) =>
              acc ++ row._1.bytes ++ row._2.toBytes,
            )
          if complete && rows.sizeCompare(
              restored.size,
            ) == 0 && bytes == canonical && restored == f.nextState && request.proposal == f.candidate
          then
            val result = restored(f.lockIdentity).toBytes ++ restored(
              f.counterIdentity,
            ).toBytes ++ restored(f.createIdentity).toBytes
            EitherT.pure(
              AuthenticatedApplicationState(
                canonical,
                hash(canonical),
                Vector(result),
              ),
            )
          else unavailable("state decoder/reducer mismatch")
      val verifier =
        ApplicationCommitVerifier.authenticated(
          anchor,
          requests,
          lookup,
          states,
        )
      def batch: IO[VerifiedApplicationBatch] = for
        request <- accepted(
          requests.verifyProposal(f.candidate, f.consensusPlan),
        )
        verified <- accepted(
          verifier.verifyBatch(request, finality(0), payload),
        )
      yield verified
      def open(journal: DurableJournal[IO]): IO[JournalSafetyStore[IO]] = for
        root          <- Ref.of[IO, Hash](f.preStateRoot)
        currentHeight <- Ref.of[IO, Height](height(5))
        store         <- accepted(
          JournalSafetyStore.open(
            anchor,
            journal,
            new Publication(f, root, currentHeight),
            SafetyProfile(f.manifest, f.artifacts),
            SafetyRecoveryAuthentication.combine(
              VotingRecoveryAuthentication.authenticated(recoveryRequests),
              verifier,
            ),
            ReservationOrdering.isolated[IO],
            SafetyCapacity.unbounded,
          ),
        )
      yield store
      def history(through: Int): Vector[ApplicationHistoryEntry] = chain
        .take(through + 1)
        .zipWithIndex
        .map((proposal, index) =>
          ApplicationHistoryEntry(
            proposal,
            if index == 0 then f.consensusPlan else emptyPlan,
          ),
        )

    private final class EmptyHistory(material: Material):
      val f           = material.f
      val firstHeader = f.candidateHeader.copy(
        stateRoot = StateRoot(f.preStateRoot),
        bodyRoot = material.emptyBodyRoot,
        executionPlanRoot = Some(material.emptyRoot),
      )
      val first = Proposal
        .sign(
          UnsignedProposal(
            f.candidateWindow,
            ValidatorId.unsafe("v1"),
            BlockHeader.computeId(firstHeader),
            firstHeader,
            ProposalTxSet(Vector.empty),
            f.baseCertificate,
          ),
          f.keys.head._2,
        )
        .toOption
        .get
      val chain = (7L to 13L).foldLeft(Vector(first))((all, _) =>
        all :+ material.child(all.last),
      )
      val repository = new ProposalExecutionRepository[IO]:
        def historicalValidators(
            window: HotStuffWindow,
        ): Result[IO, ValidatorSet] =
          material.proposals.historicalValidators(window)
        def executeSequential(
            proposal: Proposal,
            plan: ExecutionPlan,
        ): Result[IO, ExecutedProposal] =
          if chain.contains(proposal) && plan == material.emptyPlan then
            EitherT.pure(
              ExecutedProposal(
                proposal,
                UnsignedVote(
                  proposal.window,
                  ValidatorId.unsafe("v1"),
                  proposal.proposalId,
                ),
                f.preStateRoot,
                Vector.empty,
                material.emptyBodyRoot.toUInt256,
                Vector.empty,
              ),
            )
          else unavailable("complete empty canonical history")
      val requests = ApplicationRequestVerifier.authenticated(
        f.manifest,
        f.inputs,
        f.declarations,
        f.creations,
        f.transactions,
        f.artifacts,
        f.scopes,
        f.repository(f.effect),
        repository,
      )
      val states = new ApplicationStateAuthentication[IO]:
        def authenticate(
            request: VerifiedConsensusProposal,
            payload: Bytes,
        ): Result[IO, AuthenticatedApplicationState] = unavailable(
          "history-only fixture has no application payload decoder",
        )
      val verifier = ApplicationCommitVerifier.authenticated(
        material.anchor,
        requests,
        material.lookup,
        states,
      )
      val finalized =
        FinalizedAnchorSuggestion(chain(5), FinalizedProof(chain(6), chain(7)))
      val history = chain
        .take(6)
        .map(proposal => ApplicationHistoryEntry(proposal, material.emptyPlan))

    scenario(
      "real finalized application publishes only the committed payload and survives recovery",
    ):
      val material = new Material
      for
        journal <- MemoryDurableJournal.create[IO]
        store   <- material.open(journal)
        app = RecoverableApplicationStore.journaled(store)
        batch    <- material.batch
        prepared <- accepted(app.prepare(batch))
        before   <- accepted(app.canonicalPayload)
        _ = assertEquals(before, None)
        decision <- accepted(app.commit(prepared, batch.candidate))
        repeated <- accepted(app.commit(prepared, batch.candidate))
        _ = assertEquals(repeated, decision)
        visible <- accepted(app.canonicalPayload)
        _ = assertEquals(visible, Some(decision -> material.payload))
        snapshot <- accepted(store.snapshot)
        _ = assertEquals(
          snapshot.claims.values.map(_.lifecycle).toSet,
          Set(ClaimLifecycle.Applied),
        )
        reopened  <- material.open(journal)
        recovered <- accepted(
          RecoverableApplicationStore.journaled(reopened).canonicalPayload,
        )
        _ = assertEquals(recovered, visible)
        applied <- accepted(
          RecoverableApplicationStore
            .journaled(reopened)
            .applied(material.f.context, material.f.executionId),
        )
        _ = assert(applied.exists(_.blockId == decision.blockId))
      yield ()

    scenario(
      "finality, state payload and authenticated history tampering cannot produce capabilities",
    ):
      val material = new Material
      for
        request <- accepted(
          material.requests
            .verifyProposal(material.f.candidate, material.f.consensusPlan),
        )
        _ <- rejected(
          material.verifier
            .verifyBatch(
              request,
              material.finality(0),
              material.payload.reverse,
            ),
        )
        forged = material
          .finality(0)
          .copy(finalizedProof =
            FinalizedProof(material.chain(2), material.chain(3)),
          )
        _ <- rejected(material.verifier.verifyCandidate(forged))
        _ <- rejected(
          material.verifier.verifyNonapplication(
            material.finality(4),
            material.history(4).drop(1),
            Vector(material.f.executionId),
          ),
        )
        _ <- rejected(
          material.verifier.verifyNonapplication(
            material.finality(4),
            material.history(4),
            Vector(material.f.executionId),
          ),
        )
      yield ()

    scenario(
      "expiry requires complete real finality strictly after the common deadline and retains evidence across restart",
    ):
      val material = new Material
      val other    = new org.sigilaris.conformance.V2RequestConformance.Fixture(
        2L,
        Authority.LockEligible,
      )
      for
        journal <- MemoryDurableJournal.create[IO]
        store   <- material.open(journal)
        app = RecoverableApplicationStore.journaled(store)
        lock <- accepted(
          other.verifier.verifyLock(
            other.lockSubject,
            other.descriptor,
            other.signedTransaction,
            other.proofs,
          ),
        )
        _          <- accepted(store.claimLock(lock, other.keys.last._1))
        atDeadline <- accepted(
          material.verifier.verifyNonapplication(
            material.finality(4),
            material.history(4),
            Vector(other.executionId),
          ),
        )
        _      <- rejected(app.expire(atDeadline))
        beyond <- accepted(
          material.verifier.verifyNonapplication(
            material.finality(5),
            material.history(5),
            Vector(other.executionId),
          ),
        )
        resolutions <- accepted(app.expire(beyond))
        _ = assertEquals(resolutions.map(_.resolvedHeight), Vector(height(11)))
        reopened <- material.open(journal)
        state    <- accepted(reopened.snapshot)
        _ = assertEquals(
          state.locks(other.executionId).lifecycle,
          ClaimLifecycle.ExpiredUnapplied,
        )
        _ <- rejected(reopened.claimLock(lock, other.keys.last._1))
      yield ()

    scenario(
      "canonical finalized application can coexist with an unrelated minority lock without clearing it",
    ):
      val material = new Material(Authority.LockEligible)
      val other    = new org.sigilaris.conformance.V2RequestConformance.Fixture(
        2L,
        Authority.LockEligible,
      )
      for
        journal <- MemoryDurableJournal.create[IO]
        store   <- material.open(journal)
        lock    <- accepted(
          other.verifier.verifyLock(
            other.lockSubject,
            other.descriptor,
            other.signedTransaction,
            other.proofs,
          ),
        )
        _     <- accepted(store.claimLock(lock, other.keys.last._1))
        batch <- material.batch
        app = RecoverableApplicationStore.journaled(store)
        prepared <- accepted(app.prepare(batch))
        _        <- accepted(app.commit(prepared, batch.candidate))
        reopened <- material.open(journal)
        state    <- accepted(reopened.snapshot)
        _ = assertEquals(
          state.locks(other.executionId).lifecycle,
          ClaimLifecycle.Live,
        )
        _ = assert(state.appliedEntries.contains(material.f.executionId))
      yield ()

    for
      operation <- Vector("prepare", "commit")
      point     <- Vector(
        JournalFaultPoint.AfterFrameWrite,
        JournalFaultPoint.AfterFrameForce,
        JournalFaultPoint.AfterHeadWrite,
        JournalFaultPoint.AfterHeadMove,
        JournalFaultPoint.AfterHeadForce,
      )
    do
      scenario(
        s"physical $operation interruption at $point recovers one complete decision and exact claims",
      ):
        val material = new Material
        temporary.use(root =>
          for
            armed <- Ref.of[IO, Boolean](false)
            faults = new JournalFaultInjector[IO]:
              def after(observed: JournalFaultPoint, sequence: Long): IO[Unit] =
                if observed != point then IO.unit
                else
                  armed
                    .getAndSet(false)
                    .flatMap(shouldFail =>
                      if shouldFail then
                        IO.raiseError(
                          new java.io.IOException(
                            "injected application write interruption",
                          ),
                        )
                      else IO.unit,
                    )
            batch <- material.batch
            _     <- FileApplicationJournal
              .resourceWithFaults(root, faults)
              .use(journal =>
                for
                  store <- material.open(journal)
                  app = RecoverableApplicationStore.journaled(store)
                  _ <-
                    if operation == "prepare" then
                      armed.set(true) *> rejected(app.prepare(batch)).void
                    else
                      for
                        prepared <- accepted(app.prepare(batch))
                        _        <- armed.set(true)
                        _ <- rejected(app.commit(prepared, batch.candidate))
                      yield ()
                  _ <- rejected(app.canonicalPayload)
                yield (),
              )
            _ <- FileApplicationJournal
              .resource(root)
              .use(journal =>
                for
                  reopened <- material.open(journal)
                  app = RecoverableApplicationStore.journaled(reopened)
                  prepared <- accepted(app.prepare(batch))
                  decision <- accepted(app.commit(prepared, batch.candidate))
                  snapshot <- accepted(reopened.snapshot)
                  _ = assertEquals(snapshot.decisions.size, 1)
                  _ = assertEquals(
                    snapshot.claims.values.map(_.lifecycle).toSet,
                    Set(ClaimLifecycle.Applied),
                  )
                  payload <- accepted(app.canonicalPayload)
                  _ = assertEquals(payload, Some(decision -> material.payload))
                yield (),
              )
          yield (),
        )

    for missing <- Vector(
        "application-state",
        "application-inventory",
        "application-evidence",
      )
    do
      scenario(
        s"missing referenced $missing blob fences startup despite a complete journal",
      ):
        val material = new Material
        temporary.use(root =>
          for
            batch <- material.batch
            _     <- FileApplicationJournal
              .resource(root)
              .use(journal =>
                for
                  store <- material.open(journal)
                  app = RecoverableApplicationStore.journaled(store)
                  prepared <- accepted(app.prepare(batch))
                  _        <- accepted(app.commit(prepared, batch.candidate))
                yield (),
              )
            digest = missing match
              case "application-state"     => batch.batch.statePayloadDigest
              case "application-inventory" => batch.inventoryDigest
              case _                       => batch.candidate.evidenceDigest
            _ <- IO.blocking(
              Files.delete(
                root
                  .resolve("blobs")
                  .resolve(missing)
                  .resolve(digest.bytes.toHex + ".blob"),
              ),
            )
            result <- FileApplicationJournal
              .resource(root)
              .use(material.open)
              .attempt
            _ = assert(result.isLeft)
          yield (),
        )

    scenario(
      "loss during a canonical read fences the same shared signing gate",
    ):
      val material = new Material
      temporary.use(root =>
        FileApplicationJournal
          .resource(root)
          .use(journal =>
            for
              store <- material.open(journal)
              app = RecoverableApplicationStore.journaled(store)
              batch    <- material.batch
              prepared <- accepted(app.prepare(batch))
              _        <- accepted(app.commit(prepared, batch.candidate))
              _        <- IO.blocking(
                Files.delete(
                  root
                    .resolve("blobs")
                    .resolve("application-state")
                    .resolve(
                      batch.batch.statePayloadDigest.bytes.toHex + ".blob",
                    ),
                ),
              )
              failure <- rejected(app.canonicalPayload)
              _ = assertEquals(failure.code, RuntimeFailureCode.JournalCorrupt)
              fenced <- rejected(store.snapshot)
              _ = assertEquals(fenced.code, RuntimeFailureCode.RecoveryRequired)
            yield (),
          ),
      )

    scenario(
      "application recovery rejects a shortened retained witness even when owner and batch are unchanged",
    ):
      val material = new Material
      for
        journal <- MemoryDurableJournal.create[IO]
        store   <- material.open(journal)
        batch   <- material.batch
        _       <- accepted(
          RecoverableApplicationStore.journaled(store).prepare(batch),
        )
        snapshot <- accepted(store.snapshot)
        empty = value(
          ReservationWitness.fromFootprint(
            value(Footprint.canonical(Vector.empty, Vector.empty)),
          ),
        )
        ref = value(WitnessRef.fromWitness(empty))
        _ <- accepted(
          journal.putBlob(
            org.sigilaris.core.datatype.Utf8("witness"),
            ref.witnessDigest,
            value(ReservationWitness.codec.encode(empty)),
          ),
        )
        changed = snapshot.copy(claims =
          snapshot.claims.view.mapValues(_.copy(witness = ref)).toMap,
        )
        failure <- rejected(material.verifier.verify(changed, journal))
        _ = assertEquals(failure.code, RuntimeFailureCode.ProofInvalid)
      yield ()

    scenario(
      "late lock certificates preserve terminal application after a local consensus vote and restart",
    ):
      val material = new Material(Authority.LockEligible)
      for
        journal <- MemoryDurableJournal.create[IO]
        store   <- material.open(journal)
        batch   <- material.batch
        _       <- accepted(
          store.claimConsensus(batch.request, material.f.keys.head._1),
        )
        before <- accepted(store.snapshot)
        _   = assertEquals(before.locks.size, 0)
        app = RecoverableApplicationStore.journaled(store)
        prepared  <- accepted(app.prepare(batch))
        _         <- accepted(app.commit(prepared, batch.candidate))
        certified <- accepted(
          material.f.verifier.verifyLockCertificate(material.f.lockCertificate),
        )
        _        <- accepted(store.importLock(certified))
        imported <- accepted(store.snapshot)
        _ = assertEquals(
          imported.locks(material.f.executionId).lifecycle,
          ClaimLifecycle.Applied,
        )
        _ = assert(imported.index.isEmpty)
        lock <- accepted(
          material.f.verifier.verifyLock(
            material.f.lockSubject,
            material.f.descriptor,
            material.f.signedTransaction,
            material.f.proofs,
          ),
        )
        runtime = DurableApplicationVoting.fromStore(
          store,
          ApplicationVoteSigner.secp256k1[IO](
            org.sigilaris.core.application.protocol
              .ApplicationValidatorId(material.f.keys.head._1),
            material.f.keys.head._2,
          ),
          material.f.artifacts,
        )
        rejectedVote <- rejected(runtime.voteLock(lock))
        _ = assertEquals(rejectedVote.code, RuntimeFailureCode.AlreadyApplied)
        reopened  <- material.open(journal)
        recovered <- accepted(reopened.snapshot)
        _ = assertEquals(
          recovered.locks(material.f.executionId).lifecycle,
          ClaimLifecycle.Applied,
        )
        _ = assert(recovered.index.isEmpty)
      yield ()

    scenario(
      "verified height 11 expires only the deadline 10 reader while the deadline 12 reader stays protected across file restart",
    ):
      val material = new Material
      val history  = new EmptyHistory(material)
      val first    = new org.sigilaris.conformance.V2RequestConformance.Fixture(
        201L,
        Authority.LockEligible,
      )
      val second = new org.sigilaris.conformance.V2RequestConformance.Fixture(
        202L,
        Authority.LockEligible,
        10,
        Vector.empty,
        height(12L),
      )
      val sources = org.sigilaris.conformance.V2RequestConformance
        .recoveryVerifier(Vector(first, second))
      temporary.use(root =>
        for
          currentRoot   <- Ref.of[IO, Hash](first.preStateRoot)
          currentHeight <- Ref.of[IO, Height](height(5))
          publication    = new Publication(first, currentRoot, currentHeight)
          authentication = SafetyRecoveryAuthentication.combine(
            VotingRecoveryAuthentication.authenticated(sources),
            history.verifier,
          )
          proof <- accepted(
            history.verifier.verifyNonapplication(
              history.finalized,
              history.history,
              Vector(first.executionId),
            ),
          )
          firstRequest <- accepted(
            first.verifier
              .verifyEffect(first.effectSubject, first.owner, first.witness),
          )
          secondRequest <- accepted(
            second.verifier
              .verifyEffect(second.effectSubject, second.owner, second.witness),
          )
          remainingOwner = value(Owner.digest(second.owner))
          retained <- FileApplicationJournal
            .resource(root)
            .use(journal =>
              for
                store <- accepted(
                  JournalSafetyStore.open(
                    material.anchor,
                    journal,
                    publication,
                    SafetyProfile(first.manifest, first.artifacts),
                    authentication,
                    ReservationOrdering.isolated[IO],
                    SafetyCapacity.unbounded,
                  ),
                )
                _ <- accepted(
                  store.claimEffect(firstRequest, first.keys.head._1),
                )
                _ <- accepted(
                  store.claimEffect(secondRequest, second.keys.head._1),
                )
                before <- accepted(store.snapshot)
                _ = assertEquals(
                  before.index
                    .find(_.identity == first.readIdentity)
                    .map(_.owners.size),
                  Some(2),
                )
                _           <- currentHeight.set(height(11))
                resolutions <- accepted(
                  RecoverableApplicationStore.journaled(store).expire(proof),
                )
                _ = assertEquals(
                  resolutions.map(_.resolvedHeight),
                  Vector(height(11)),
                )
                after <- accepted(store.snapshot)
                _ = assertEquals(
                  after.claims(value(Owner.digest(first.owner))).lifecycle,
                  ClaimLifecycle.ExpiredUnapplied,
                )
                _ = assertEquals(
                  after.claims(remainingOwner).lifecycle,
                  ClaimLifecycle.Live,
                )
                _ = assertEquals(
                  after.claims(remainingOwner).lastInclusionHeight,
                  height(12),
                )
                _ = assertEquals(
                  after.index
                    .find(_.identity == first.readIdentity)
                    .map(_.owners.map(_.ownerDigest)),
                  Some(Vector(remainingOwner)),
                )
                _ = assertEquals(after.witnesses, before.witnesses)
              yield after,
            )
          _ <- FileApplicationJournal
            .resource(root)
            .use(journal =>
              for
                store <- accepted(
                  JournalSafetyStore.open(
                    material.anchor,
                    journal,
                    publication,
                    SafetyProfile(first.manifest, first.artifacts),
                    authentication,
                    ReservationOrdering.isolated[IO],
                    SafetyCapacity.unbounded,
                  ),
                )
                recovered <- accepted(store.snapshot)
                _ = assertEquals(recovered.claims, retained.claims)
                _ = assertEquals(recovered.index, retained.index)
                _ = assertEquals(recovered.locks, retained.locks)
                _ <- accepted(
                  store.claimEffect(secondRequest, second.keys.head._1),
                )
                _ <- rejected(
                  store.claimEffect(firstRequest, first.keys.head._1),
                )
              yield (),
            )
        yield (),
      )

    private def temporary: Resource[IO, Path] = Resource.make(
      IO.blocking(Files.createTempDirectory("sigilaris-v2-application")),
    )(path =>
      IO.blocking {
        Using.resource(Files.walk(path))(
          _.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists),
        )
        ()
      },
    )
