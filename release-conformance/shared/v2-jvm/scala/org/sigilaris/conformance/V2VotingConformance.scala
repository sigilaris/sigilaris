package org.sigilaris.conformance

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import cats.data.EitherT
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.ApplicationValidatorId
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.node.jvm.storage.file.FileApplicationJournal
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{
  HotStuffValidator,
  Vote,
}

/** Four independent honest-voter stores and real signer messages. The one
  * Byzantine signer deliberately bypasses durable admission; no single honest
  * runtime owns multiple validator keys.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Throw",
    "org.wartremover.warts.Nothing",
  ),
)
object V2VotingConformance:
  import V2RequestConformance.*

  def accepted[A](result: Result[IO, A]): IO[A] = result.value.map(
    _.fold(
      error => throw new IllegalStateException(error.message),
      resolved => resolved,
    ),
  )
  def rejected[A](result: Result[IO, A]): IO[V2RuntimeFailure] =
    result.value.map(
      _.fold(
        error => error,
        _ =>
          throw new IllegalStateException(
            "unexpected successful safety operation",
          ),
      ),
    )

  final class Publication(
      fixture: Fixture,
      currentRoot: Ref[IO, Hash],
      currentHeight: Ref[IO, Height],
  ) extends SafetyPublication[IO]:
    def finalizedHeight(context: DomainContext): Result[IO, Height] =
      if context == fixture.context then EitherT.liftF(currentHeight.get)
      else unavailable("canonical domain")
    def verifyEffectState(request: VerifiedEffectRequest): Result[IO, Unit] =
      EitherT(
        currentRoot.get.map(root =>
          Either.cond(
            request.subject.context == fixture.context && request.entryPreStateRoot == root,
            (),
            V2RuntimeFailure.at(
              RuntimeFailureCode.ProofInvalid,
              "effect state changed since execution",
            ),
          ),
        ),
      )
    def verifyConsensusState(
        request: VerifiedConsensusProposal,
    ): Result[IO, Unit] = EitherT(
      currentRoot.get.map(root =>
        Either.cond(
          request.context == fixture.context && request.parentBlockId == fixture.baseBlockId.toUInt256 && request.parentStateRoot == root,
          (),
          V2RuntimeFailure.at(
            RuntimeFailureCode.ProofInvalid,
            "canonical consensus parent changed",
          ),
        ),
      ),
    )

  final case class Environment(
      fixture: Fixture,
      journal: DurableJournal[IO],
      publication: Publication,
      currentRoot: Ref[IO, Hash],
      currentHeight: Ref[IO, Height],
      store: JournalSafetyStore[IO],
      recoveryRequests: ApplicationRequestVerifier[IO],
  ):
    def runtime(index: Int): DurableApplicationVoting[IO] =
      val (id, key) = fixture.keys(index)
      DurableApplicationVoting.fromStore(
        store,
        ApplicationVoteSigner.secp256k1[IO](ApplicationValidatorId(id), key),
        fixture.artifacts,
      )
    def reopen: IO[Environment] = accepted(
      JournalSafetyStore.open(
        ApplicationAnchor(
          fixture.context,
          fixture.baseBlockId.toUInt256,
          height(5L),
          fixture.preStateRoot,
        ),
        journal,
        publication,
        SafetyProfile(fixture.manifest, fixture.artifacts),
        SafetyRecoveryAuthentication.voting(recoveryRequests),
        ReservationOrdering.isolated[IO],
        SafetyCapacity.unbounded,
      ),
    ).map(reopened => copy(store = reopened))

  def environment(
      fixture: Fixture,
      journal: DurableJournal[IO],
  ): IO[Environment] = environment(fixture, journal, Vector.empty)

  def environment(
      fixture: Fixture,
      journal: DurableJournal[IO],
      additionalSources: Vector[Fixture],
  ): IO[Environment] =
    environmentWithVerifier(
      fixture,
      journal,
      recoveryVerifier(fixture +: additionalSources),
    )

  def environmentWithVerifier(
      fixture: Fixture,
      journal: DurableJournal[IO],
      recoveryRequests: ApplicationRequestVerifier[IO],
  ): IO[Environment] =
    for
      root          <- Ref.of[IO, Hash](fixture.preStateRoot)
      currentHeight <- Ref.of[IO, Height](height(5L))
      publication = new Publication(fixture, root, currentHeight)
      store <- accepted(
        JournalSafetyStore.open(
          ApplicationAnchor(
            fixture.context,
            fixture.baseBlockId.toUInt256,
            height(5L),
            fixture.preStateRoot,
          ),
          journal,
          publication,
          SafetyProfile(fixture.manifest, fixture.artifacts),
          SafetyRecoveryAuthentication.voting(recoveryRequests),
          ReservationOrdering.isolated[IO],
          SafetyCapacity.unbounded,
        ),
      )
    yield Environment(
      fixture,
      journal,
      publication,
      root,
      currentHeight,
      store,
      recoveryRequests,
    )

  def memory(fixture: Fixture): IO[Environment] =
    MemoryDurableJournal.create[IO].flatMap(environment(fixture, _))
  def memory(
      fixture: Fixture,
      additionalSources: Vector[Fixture],
  ): IO[Environment] =
    MemoryDurableJournal
      .create[IO]
      .flatMap(environment(fixture, _, additionalSources))
  def lock(f: Fixture): IO[VerifiedLockRequest] = accepted(
    f.verifier
      .verifyLock(f.lockSubject, f.descriptor, f.signedTransaction, f.proofs),
  )
  def effect(f: Fixture): IO[VerifiedEffectRequest] = accepted(
    f.verifier.verifyEffect(f.effectSubject, f.owner, f.witness),
  )

  def concurrentVotes(): IO[Unit] =
    val first  = new Fixture(11L, Authority.LockEligible)
    val second = new Fixture(12L, Authority.LockEligible)
    for
      env      <- memory(first, Vector(second))
      a        <- lock(first)
      b        <- lock(second)
      start    <- Deferred[IO, Unit]
      left     <- (start.get *> env.runtime(0).voteLock(a).value).start
      right    <- (start.get *> env.runtime(0).voteLock(b).value).start
      _        <- start.complete(())
      result   <- (left.joinWithNever, right.joinWithNever).tupled
      _        <- IO(assert(Vector(result._1, result._2).count(_.isRight) == 1))
      snapshot <- accepted(env.store.snapshot)
      _ <- IO(assert(snapshot.intents.size == 1 && snapshot.locks.size == 1))
      winner = if result._1.isRight then a else b
      retried <- accepted(env.runtime(0).voteLock(winner))
      _       <- IO(
        assert(result._1.toOption.orElse(result._2.toOption).contains(retried)),
      )
      same     <- accepted(env.store.snapshot)
      _        <- IO(assert(same.sequence == snapshot.sequence))
      reopened <- env.reopen
      _        <- rejected(
        reopened.runtime(0).voteLock(if result._1.isRight then b else a),
      )
      _ <- accepted(reopened.runtime(0).voteLock(winner))
    yield ()

  def signerFailureAndCancellation(): IO[Unit] =
    val f         = new Fixture(21L, Authority.LockEligible)
    val competing = new Fixture(22L, Authority.LockEligible)
    for
      env       <- memory(f)
      request   <- lock(f)
      alternate <- lock(competing)
      failing = new ApplicationVoteSigner[IO]:
        def validatorId: ApplicationValidatorId = ApplicationValidatorId(
          f.keys(0)._1,
        )
        def sign(context: DomainContext, preimage: Bytes): IO[Bytes] =
          IO.raiseError(new IllegalStateException("injected signer failure"))
      failure <- rejected(
        DurableApplicationVoting
          .fromStore(env.store, failing, f.artifacts)
          .voteLock(request),
      )
      _        <- IO(assert(failure.code == RuntimeFailureCode.SignerFailure))
      snapshot <- accepted(env.store.snapshot)
      _ <- IO(assert(snapshot.intents.size == 1 && snapshot.locks.size == 1))
      restarted <- env.reopen
      _         <- rejected(restarted.runtime(0).voteLock(alternate))
      _         <- accepted(restarted.runtime(0).voteLock(request))
      cancelEnv <- memory(f)
      entered   <- Deferred[IO, Unit]
      release   <- Deferred[IO, Unit]
      original = ApplicationVoteSigner
        .secp256k1[IO](ApplicationValidatorId(f.keys(0)._1), f.keys(0)._2)
      delayed = new ApplicationVoteSigner[IO]:
        def validatorId: ApplicationValidatorId = original.validatorId
        def sign(context: DomainContext, preimage: Bytes): IO[Bytes] =
          entered.complete(()).void *> release.get *> original.sign(
            context,
            preimage,
          )
      fiber <- DurableApplicationVoting
        .fromStore(cancelEnv.store, delayed, f.artifacts)
        .voteLock(request)
        .value
        .start
      _            <- entered.get
      cancellation <- fiber.cancel.start
      _            <- release.complete(())
      _            <- cancellation.joinWithNever
      recovered    <- cancelEnv.reopen
      retained     <- accepted(recovered.store.snapshot)
      _ <- IO(assert(retained.locks.size == 1 && retained.intents.size == 1))
      _ <- rejected(recovered.runtime(0).voteLock(alternate))
    yield ()

  def unknownWriteBeforeSigner(): IO[Unit] =
    val f         = new Fixture(31L, Authority.LockEligible)
    val competing = new Fixture(32L, Authority.LockEligible)
    for
      armed <- Ref.of[IO, Boolean](false)
      calls <- Ref.of[IO, Int](0)
      faults = new JournalFaultInjector[IO]:
        def after(point: JournalFaultPoint, sequence: Long): IO[Unit] =
          if point == JournalFaultPoint.AfterFrameWrite then
            armed
              .getAndSet(false)
              .flatMap(active =>
                if active then
                  IO.raiseError(
                    new IllegalStateException("unknown durable write"),
                  )
                else IO.unit,
              )
          else IO.unit
      journal   <- MemoryDurableJournal.createWithFaults[IO](faults)
      env       <- environment(f, journal)
      request   <- lock(f)
      alternate <- lock(competing)
      original = ApplicationVoteSigner
        .secp256k1[IO](ApplicationValidatorId(f.keys(0)._1), f.keys(0)._2)
      counted = new ApplicationVoteSigner[IO]:
        def validatorId: ApplicationValidatorId = original.validatorId
        def sign(context: DomainContext, preimage: Bytes): IO[Bytes] =
          calls.update(_ + 1) *> original.sign(context, preimage)
      _ <- armed.set(true)
      _ <- rejected(
        DurableApplicationVoting
          .fromStore(env.store, counted, f.artifacts)
          .voteLock(request),
      )
      invoked   <- calls.get
      _         <- IO(assert(invoked == 0))
      _         <- rejected(env.store.snapshot)
      recovered <- env.reopen
      _         <- rejected(recovered.runtime(0).voteLock(alternate))
      _         <- accepted(recovered.runtime(0).voteLock(request))
    yield ()

  def splitQuorums(): IO[Unit] =
    val a = new Fixture(41L, Authority.LockEligible)
    val b = new Fixture(42L, Authority.LockEligible)
    for
      environments <- Vector.fill(4)(()).traverse(_ => memory(a, Vector(b)))
      first        <- lock(a)
      second       <- lock(b)
      vote0        <- accepted(environments(0).runtime(0).voteLock(first))
      vote1        <- accepted(environments(1).runtime(1).voteLock(first))
      vote2        <- accepted(environments(2).runtime(2).voteLock(second))
      byzantine = ApplicationVoteSigner
        .secp256k1[IO](ApplicationValidatorId(a.keys(3)._1), a.keys(3)._2)
      badA <- byzantine.sign(
        a.context,
        value(LockSubject.signingPreimage(a.lockSubject)),
      )
      badB <- byzantine.sign(
        b.context,
        value(LockSubject.signingPreimage(b.lockSubject)),
      )
      quorumA = LockCertificate(
        a.lockSubject,
        Vector(vote0.vote, vote1.vote, ValidatorSignature(a.keys(3)._1, badA)),
      )
      partialB = LockCertificate(
        b.lockSubject,
        Vector(vote2.vote, ValidatorSignature(b.keys(3)._1, badB)),
      )
      _ <- IO(
        assert(
          LockCertificate
            .verify(quorumA, a.manifest, a.artifacts)
            .isRight && LockCertificate
            .verify(partialB, b.manifest, b.artifacts)
            .left
            .exists(_.code == FailureCode.QuorumNotReached),
        ),
      )
      _         <- rejected(environments(0).runtime(0).voteLock(second))
      _         <- rejected(environments(1).runtime(1).voteLock(second))
      _         <- rejected(environments(2).runtime(2).voteLock(first))
      restarted <- environments.traverse(_.reopen)
      _         <- rejected(restarted(0).runtime(0).voteLock(second))
      _         <- rejected(restarted(1).runtime(1).voteLock(second))
    yield ()

  def certificateAndReservationInterlocks(): IO[Unit] =
    val first      = new Fixture(51L, Authority.LockEligible)
    val sharedRead = new Fixture(52L, Authority.LockEligible, 10)
    val conflict   = new Fixture(53L, Authority.LockEligible)
    for
      env         <- memory(first, Vector(sharedRead))
      certificate <- accepted(
        first.verifier.verifyLockCertificate(first.lockCertificate),
      )
      _        <- accepted(env.runtime(0).importLock(certificate))
      imported <- accepted(env.store.snapshot)
      _        <- IO(
        assert(
          imported.intents.isEmpty && imported.locks.size == 1 && imported.lockCertificates.size == 1,
        ),
      )
      firstEffect <- effect(first)
      _           <- accepted(env.runtime(0).voteEffect(firstEffect))
      reserved    <- accepted(env.store.snapshot)
      _ <- IO(assert(reserved.claims.size == 1 && reserved.index.size == 4))
      otherCertificate <- accepted(
        sharedRead.verifier.verifyLockCertificate(sharedRead.lockCertificate),
      )
      _           <- accepted(env.runtime(0).importLock(otherCertificate))
      readSharing <- effect(sharedRead)
      _           <- accepted(env.runtime(0).voteEffect(readSharing))
      shared      <- accepted(env.store.snapshot)
      _           <- IO(
        assert(
          shared.index
            .find(_.identity == first.readIdentity)
            .exists(_.owners.size == 2),
        ),
      )
      conflictRequest <- lock(conflict)
      _               <- rejected(env.runtime(0).voteLock(conflictRequest))
      _               <- env.currentRoot.set(uint(999))
      _               <- rejected(env.runtime(0).voteEffect(firstEffect))
      _               <- env.currentRoot.set(first.preStateRoot)
      reopened        <- env.reopen
      rebuilt         <- accepted(reopened.store.snapshot)
      _               <- IO(
        assert(rebuilt.index == shared.index && rebuilt.claims == shared.claims),
      )
      _        <- rejected(reopened.runtime(0).voteLock(conflictRequest))
      _        <- env.currentHeight.set(height(11L))
      _        <- rejected(reopened.runtime(0).voteEffect(firstEffect))
      retained <- accepted(reopened.store.snapshot)
      _        <- IO(
        assert(
          retained.claims.values.forall(_.lifecycle == ClaimLifecycle.Live),
        ),
      )
    yield ()

  def reciprocalConsensusInterlock(): IO[Unit] =
    val consensus = new Fixture(71L, Authority.ConsensusOnly)
    val fast      = new Fixture(72L, Authority.LockEligible)
    for
      reservation <- accepted(
        consensus
          .proposalVerifier(consensus.executedCandidate)
          .verifyProposal(consensus.candidate, consensus.consensusPlan),
      )
      lockRequest      <- lock(fast)
      reservationFirst <- memory(consensus)
      _                <- accepted(
        reservationFirst.runtime(0).prepareConsensusVote(reservation),
      )
      before <- accepted(reservationFirst.store.snapshot)
      _      <- IO(assert(before.locks.isEmpty && before.claims.nonEmpty))
      _      <- rejected(reservationFirst.runtime(0).voteLock(lockRequest))
      firstRecovery <- reservationFirst.reopen
      _             <- rejected(firstRecovery.runtime(0).voteLock(lockRequest))
      lockFirst     <- memory(fast)
      _             <- accepted(lockFirst.runtime(0).voteLock(lockRequest))
      _ <- rejected(lockFirst.runtime(0).prepareConsensusVote(reservation))
      secondRecovery <- lockFirst.reopen
      _ <- rejected(secondRecovery.runtime(0).prepareConsensusVote(reservation))
    yield ()

  private def temporaryDirectory: Resource[IO, Path] = Resource.make(
    IO.blocking(Files.createTempDirectory("sigilaris-v2-voting-")),
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
          .foreach(item =>
            Files.delete(item); (),
          )
      finally stream.close()
    },
  )

  def persistentVoting(): IO[Unit] = temporaryDirectory.use { directory =>
    val f         = new Fixture(81L, Authority.LockEligible)
    val competing = new Fixture(82L, Authority.LockEligible)
    for
      request        <- lock(f)
      verifiedEffect <- effect(f)
      alternate      <- lock(competing)
      before <- FileApplicationJournal.resource(directory).use { journal =>
        for
          env         <- environment(f, journal)
          vote        <- accepted(env.runtime(0).voteLock(request))
          certificate <- accepted(
            f.verifier.verifyLockCertificate(f.lockCertificate),
          )
          _          <- accepted(env.runtime(0).importLock(certificate))
          effectVote <- accepted(env.runtime(0).voteEffect(verifiedEffect))
          snapshot   <- accepted(env.store.snapshot)
        yield (vote, effectVote, snapshot)
      }
      _ <- FileApplicationJournal.resource(directory).use { journal =>
        for
          env      <- environment(f, journal)
          snapshot <- accepted(env.store.snapshot)
          _        <- IO(
            assert(
              snapshot.locks == before._3.locks && snapshot.claims == before._3.claims && snapshot.index == before._3.index && snapshot.intents == before._3.intents,
            ),
          )
          same       <- accepted(env.runtime(0).voteLock(request))
          effectVote <- accepted(env.runtime(0).voteEffect(verifiedEffect))
          _          <- IO(assert(same == before._1 && effectVote == before._2))
          _          <- rejected(env.runtime(0).voteLock(alternate))
        yield ()
      }
    yield ()
  }

  def preparedConsensus(): IO[Unit] =
    val f = new Fixture(61L, Authority.ConsensusOnly)
    for
      env     <- memory(f)
      request <- accepted(
        f.proposalVerifier(f.executedCandidate)
          .verifyProposal(f.candidate, f.consensusPlan),
      )
      prepared <- accepted(env.runtime(0).prepareConsensusVote(request))
      snapshot <- accepted(env.store.snapshot)
      _        <- IO(
        assert(
          snapshot.consensusIntents.size == 1 && snapshot.claims.size == 1 && snapshot.intents.isEmpty && snapshot.locks.isEmpty,
        ),
      )
      vote <- accepted(env.runtime(0).signConsensusVote(prepared))
      _    <- IO(
        assert(
          Vote.signBytes(
            prepared.unsignedVote,
          ) == prepared.intent.unsignedVoteSignBytes && HotStuffValidator
            .validateVote(vote, f.validators)
            .isRight,
        ),
      )
      _        <- env.currentRoot.set(uint(999))
      _        <- rejected(env.runtime(0).signConsensusVote(prepared))
      _        <- env.currentRoot.set(f.preStateRoot)
      reopened <- env.reopen
      _        <- rejected(reopened.runtime(0).signConsensusVote(prepared))
      fresh    <- accepted(reopened.runtime(0).prepareConsensusVote(request))
      _        <- accepted(reopened.runtime(0).signConsensusVote(fresh))
    yield ()

  def authenticatedRecovery(): IO[Unit] =
    val f = new Fixture(101L, Authority.ConsensusOnly)
    for
      env     <- memory(f)
      request <- accepted(
        f.proposalVerifier(f.executedCandidate)
          .verifyProposal(f.candidate, f.consensusPlan),
      )
      _     <- accepted(env.runtime(0).prepareConsensusVote(request))
      state <- accepted(env.store.snapshot)
      authentication = VotingRecoveryAuthentication.authenticated(
        env.recoveryRequests,
      )
      _ <- accepted(authentication.verify(state, env.journal))
      omitted = ReservationWitness(
        1L,
        f.witness.entries.filterNot(_.identity == f.readIdentity),
      )
      replacement <- accepted(env.store.putInactive(omitted))
      ownerId     = value(Owner.digest(request.reservations(0).owner))
      forgedClaim = state.claims(ownerId).copy(witness = replacement)
      // Both the reduced reference and its complete bytes are valid. Only the
      // retained full plan plus independent replay exposes the missing read.
      forged = state.copy(
        claims = state.claims.updated(ownerId, forgedClaim),
        witnesses =
          state.witnesses.updated(replacement.witnessDigest, replacement),
      )
      _ <- rejected(authentication.verify(forged, env.journal))
      _ <- rejected(
        VotingRecoveryAuthentication
          .authenticated(recoveryVerifier(Vector.empty))
          .verify(state, env.journal),
      )
      filtered = new DurableJournal[IO]:
        def recover: Result[IO, Vector[JournalRecord]] = env.journal.recover
        def append(record: JournalRecord): Result[IO, Unit] =
          env.journal.append(record)
        def putBlob(
            namespace: Text,
            digest: Hash,
            data: Bytes,
        ): Result[IO, Unit] = env.journal.putBlob(namespace, digest, data)
        def readBlob(namespace: Text, digest: Hash): Result[IO, Bytes] =
          if namespace.asString == "voting-evidence" then
            unavailable("original full voting request")
          else env.journal.readBlob(namespace, digest)
      _ <- rejected(authentication.verify(state, filtered))
      corrupted = new DurableJournal[IO]:
        def recover: Result[IO, Vector[JournalRecord]] = env.journal.recover
        def append(record: JournalRecord): Result[IO, Unit] =
          env.journal.append(record)
        def putBlob(
            namespace: Text,
            digest: Hash,
            data: Bytes,
        ): Result[IO, Unit] = env.journal.putBlob(namespace, digest, data)
        def readBlob(namespace: Text, digest: Hash): Result[IO, Bytes] =
          env.journal
            .readBlob(namespace, digest)
            .map(data =>
              if namespace.asString == "voting-evidence" then
                data ++ bytes("00")
              else data,
            )
      _        <- rejected(authentication.verify(state, corrupted))
      reopened <- env.reopen
      _        <- accepted(reopened.store.snapshot)
      fast = new Fixture(102L, Authority.LockEligible)
      imported    <- memory(fast)
      certificate <- accepted(
        fast.verifier.verifyEffectCertificate(
          EffectCertificate(
            fast.effectSubject,
            fast.quorum(
              value(EffectSubject.signingPreimage(fast.effectSubject)),
            ),
          ),
        ),
      )
      _             <- accepted(imported.runtime(0).importEffect(certificate))
      importedState <- accepted(imported.store.snapshot)
      _             <- IO(
        assert(
          importedState.intents.isEmpty && importedState.claims.isEmpty && importedState.index.isEmpty && importedState.locks.size == 1,
        ),
      )
      _              <- imported.reopen
      verifiedEffect <- effect(fast)
      _              <- accepted(imported.runtime(0).voteEffect(verifiedEffect))
      locallyReserved <- accepted(imported.store.snapshot)
      reduced         <- accepted(
        imported.store.putInactive(
          ReservationWitness(
            1L,
            fast.witness.entries.filterNot(_.identity == fast.readIdentity),
          ),
        ),
      )
      importedOwner = value(Owner.digest(fast.owner))
      altered       = locallyReserved.copy(claims =
        locallyReserved.claims.updated(
          importedOwner,
          locallyReserved.claims(importedOwner).copy(witness = reduced),
        ),
      )
      _ <- rejected(
        VotingRecoveryAuthentication
          .authenticated(imported.recoveryRequests)
          .verify(altered, imported.journal),
      )
    yield ()

  def certifiedMinorityReconciliation(): IO[Unit] =
    val minority  = new Fixture(103L, Authority.LockEligible)
    val certified = new Fixture(104L, Authority.LockEligible)
    for
      env             <- memory(minority, Vector(certified))
      original        <- lock(minority)
      incoming        <- lock(certified)
      originalVote    <- accepted(env.runtime(3).voteLock(original))
      before          <- accepted(env.store.snapshot)
      lockCertificate <- accepted(
        certified.verifier.verifyLockCertificate(certified.lockCertificate),
      )
      _                 <- accepted(env.runtime(3).importLock(lockCertificate))
      effectCertificate <- accepted(
        certified.verifier.verifyEffectCertificate(
          EffectCertificate(
            certified.effectSubject,
            certified.quorum(
              value(EffectSubject.signingPreimage(certified.effectSubject)),
            ),
          ),
        ),
      )
      _        <- accepted(env.runtime(3).importEffect(effectCertificate))
      archived <- accepted(env.store.snapshot)
      _        <- IO(
        assert(
          archived.intents == before.intents && archived.claims.isEmpty && archived.index.isEmpty &&
            archived.locks.size == 2 && archived.locks.get(
              minority.executionId,
            ) == before.locks.get(minority.executionId) &&
            archived.lockCertificates.size == 1 && archived.effectCertificates.size == 1,
        ),
      )
      retried   <- accepted(env.runtime(3).voteLock(original))
      _         <- IO(assert(retried == originalVote))
      newEffect <- effect(minority)
      _         <- rejected(env.runtime(3).voteEffect(newEffect))
      _         <- rejected(env.runtime(3).voteLock(incoming))
      recovered <- env.reopen
      reopened  <- accepted(recovered.store.snapshot)
      _         <- IO(
        assert(
          reopened.intents == archived.intents && reopened.locks == archived.locks && reopened.claims.isEmpty && reopened.index.isEmpty,
        ),
      )
      same         <- accepted(recovered.runtime(3).voteLock(original))
      _            <- IO(assert(same == originalVote))
      _            <- rejected(recovered.runtime(3).voteEffect(newEffect))
      _            <- rejected(recovered.runtime(3).voteLock(incoming))
      late         <- memory(certified)
      _            <- late.currentHeight.set(height(11L))
      _            <- accepted(late.runtime(3).importEffect(effectCertificate))
      conservative <- accepted(late.store.snapshot)
      _            <- IO(
        assert(
          conservative.intents.isEmpty && conservative.claims.isEmpty && conservative.locks.size == 1 &&
            conservative.locks.values.forall(claim =>
              claim.lifecycle == ClaimLifecycle.Live && claim.lastInclusionHeight == height(
                10L,
              ),
            ),
        ),
      )
      _           <- rejected(late.runtime(3).voteLock(incoming))
      lateRestart <- late.reopen
      retained    <- accepted(lateRestart.store.snapshot)
      _           <- IO(
        assert(
          retained.locks == conservative.locks && retained.intents.isEmpty && retained.claims.isEmpty,
        ),
      )
    yield ()

  def run(): IO[Unit] =
    concurrentVotes() *> signerFailureAndCancellation() *> unknownWriteBeforeSigner() *> splitQuorums() *> certificateAndReservationInterlocks() *> reciprocalConsensusInterlock() *> persistentVoting() *> preparedConsensus() *> authenticatedRecovery() *> certifiedMinorityReconciliation() *> V2OrderedVotingConformance
      .run() *> IO(
      println(
        "V2VotingConformance PASS: durable races, signer/cancel/unknown-write recovery, four independent validators, certificate interlocks, shared reads and prepared consensus",
      ),
    )
