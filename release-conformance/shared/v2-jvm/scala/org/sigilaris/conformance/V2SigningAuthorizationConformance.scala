package org.sigilaris.conformance

import cats.data.EitherT
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import org.sigilaris.core.application.protocol.ApplicationValidatorId
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
import scodec.bits.ByteVector

/** Public observers exercise actual authorization immediately before the real
  * neutral key is used. No private lease API or constructed permission is used.
  */
@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object V2SigningAuthorizationConformance:
  import V2RequestConformance.*
  import V2VotingConformance.{
    accepted,
    rejected,
    memory,
    lock,
    effect,
    Environment,
  }

  private def finalizer(env: Environment): IO[FinalizedApplicationRuntime[IO]] =
    val f       = env.fixture
    val history = new FinalizedApplicationHistory[IO]:
      def anchorAncestor(
          anchor: ApplicationAnchor,
          finalized: FinalizedAnchorSuggestion,
      ): Result[IO, VerifiedInitialAnchorAncestor] = unavailable(
        "no prior branch",
      )
      def retained(
          context: DomainContext,
          blockId: Hash,
      ): Result[IO, Option[FinalizedApplicationMaterial]] = EitherT.pure(None)
      def backfill(
          context: DomainContext,
          blockId: Hash,
      ): Result[IO, Option[FinalizedApplicationMaterial]] = EitherT.pure(None)
      def latestFinalized(
          context: DomainContext,
      ): Result[IO, Option[FinalizedAnchorSuggestion]] = EitherT.pure(None)
    val state = new ApplicationStateAuthentication[IO]:
      def authenticate(
          request: VerifiedConsensusProposal,
          payload: Bytes,
      ): Result[IO, AuthenticatedApplicationState] = unavailable(
        "no finalized candidate",
      )
    FinalizedApplicationRuntime.authenticated(
      env.store,
      env.recoveryRequests,
      ApplicationCommitVerifier.authenticated(
        env.store.anchor,
        env.recoveryRequests,
        ValidatorSetLookup.static(
          BootstrapTrustRoot.staticValidatorSet(f.validators),
        ),
        state,
      ),
      history,
      FinalizedApplicationCapacity(8L),
    )

  private def authentication(
      env: Environment,
      app: FinalizedApplicationRuntime[IO],
  ): ControllerApplicationSigning =
    ControllerApplicationSigning.authenticated(
      env.store,
      app,
      env.recoveryRequests,
      env.publication,
      None,
    )

  private def signing(
      env: Environment,
      run: (DomainContext, Bytes) => IO[Bytes],
  ): DurableApplicationVoting[IO] =
    val signer = new ApplicationVoteSigner[IO]:
      val validatorId: ApplicationValidatorId = ApplicationValidatorId(
        env.fixture.keys.head._1,
      )
      def sign(context: DomainContext, preimage: Bytes): IO[Bytes] =
        run(context, preimage)
    DurableApplicationVoting.fromStore(env.store, signer, env.fixture.artifacts)

  private def keyUse(
      env: Environment,
      uses: Ref[IO, Int],
      context: DomainContext,
      preimage: Bytes,
  ): IO[Bytes] =
    uses.update(_ + 1) *> ApplicationVoteSigner
      .secp256k1[IO](
        ApplicationValidatorId(env.fixture.keys.head._1),
        env.fixture.keys.head._2,
      )
      .sign(context, preimage)

  private def recordAuthorization(
      auth: ControllerApplicationSigning,
      request: ControllerApplicationVote,
      signer: Text,
      originalPassed: Ref[IO, Boolean],
      authorization: Ref[IO, Option[Either[V2RuntimeFailure, Unit]]],
  ): IO[Unit] = for
    _      <- accepted(auth.authenticate(request, signer))
    _      <- originalPassed.set(true)
    result <- auth.authorize(request, signer).value
    _      <- authorization.set(Some(result))
    _      <- accepted(EitherT.fromEither[IO](result))
  yield ()

  def exactPermission(): IO[Unit] =
    val f     = new Fixture(42111L, Authority.LockEligible)
    val other = new Fixture(42112L, Authority.LockEligible, 10)
    for
      env         <- memory(f, Vector(other))
      app         <- finalizer(env)
      _           <- accepted(app.recover)
      request     <- lock(f)
      alternative <- lock(other)
      _           <- accepted(env.store.claimLock(alternative, f.keys.head._1))
      uses        <- Ref.of[IO, Int](0)
      originalPassed <- Ref.of[IO, Boolean](false)
      authorization  <- Ref.of[IO, Option[Either[V2RuntimeFailure, Unit]]](None)
      auth   = authentication(env, app)
      wanted = ControllerApplicationVote(
        f.context,
        value(LockSubject.signingPreimage(request.subject)),
      )
      alternate = ControllerApplicationVote(
        f.context,
        value(LockSubject.signingPreimage(alternative.subject)),
      )
      guarded = signing(
        env,
        (context, bytes) =>
          recordAuthorization(
            auth,
            ControllerApplicationVote(context, bytes),
            f.keys.head._1,
            originalPassed,
            authorization,
          ) *>
            keyUse(env, uses, context, bytes),
      )
      _                 <- rejected(guarded.voteLock(request))
      noApplicationGate <- uses.get
      originalWasValid  <- originalPassed.get
      refused           <- authorization.get
      _                 <- IO(
        assert(
          noApplicationGate == 0 && originalWasValid && refused.exists(
            _.left.exists(_.code == RuntimeFailureCode.RecoveryRequired),
          ),
        ),
      )
      _ <- accepted(auth.authenticate(wanted, f.keys.head._1))
      _ <- rejected(auth.authorize(wanted, f.keys.head._1))
      observer = signing(
        env,
        (context, bytes) =>
          for
            _ <- accepted(auth.authenticate(wanted, f.keys.head._1))
            _ <- accepted(auth.authorize(wanted, f.keys.head._1))
            _ <- accepted(auth.authenticate(alternate, f.keys.head._1))
            _ <- rejected(auth.authorize(alternate, f.keys.head._1))
            _ <- rejected(auth.authorize(wanted, f.keys(1)._1))
            _ <- rejected(
              auth.authorize(
                wanted.copy(context = wanted.context.copy(epoch = 9L)),
                f.keys.head._1,
              ),
            )
            _ <- rejected(
              auth.authorize(
                wanted.copy(canonicalPreimage = bytes ++ ByteVector(0.toByte)),
                f.keys.head._1,
              ),
            )
            result <- keyUse(env, uses, context, bytes)
          yield result,
      )
      _     <- accepted(app.voteLock(observer, request))
      count <- uses.get
      _     <- IO(assert(count == 1))
      _     <- accepted(auth.authenticate(wanted, f.keys.head._1))
      _     <- rejected(auth.authorize(wanted, f.keys.head._1))
      originalOnly = ControllerApplicationSigning.recovery(
        env.store.anchor,
        env.journal,
        env.recoveryRequests,
      )
      _ <- accepted(originalOnly.authenticate(wanted, f.keys.head._1))
      _ <- rejected(originalOnly.authorize(wanted, f.keys.head._1))
    yield ()

  private def changedJournalHistory(): IO[Unit] =
    val f = new Fixture(42116L, Authority.LockEligible)
    for
      env     <- memory(f)
      app     <- finalizer(env)
      _       <- accepted(app.recover)
      request <- lock(f)
      auth = authentication(env, app)
      uses           <- Ref.of[IO, Int](0)
      originalPassed <- Ref.of[IO, Boolean](false)
      authorization  <- Ref.of[IO, Option[Either[V2RuntimeFailure, Unit]]](None)
      injected = signing(
        env,
        (context, bytes) =>
          for
            wanted   <- IO.pure(ControllerApplicationVote(context, bytes))
            _        <- accepted(auth.authorize(wanted, f.keys.head._1))
            original <- accepted(env.journal.recover)
            last = original.last
            next = last.copy(
              sequence = last.sequence + 1L,
              previousDigest = value(JournalRecord.digest(last)),
              status = JournalStatus.Prepared,
            )
            _ <- accepted(env.journal.append(next))
            _ <- accepted(
              env.journal.append(next.copy(status = JournalStatus.Committed)),
            )
            // The new canonical physical history retains the same valid source and
            // intent; only its mismatch with the held signing lease rejects key use.
            _ <- recordAuthorization(
              auth,
              wanted,
              f.keys.head._1,
              originalPassed,
              authorization,
            )
            result <- keyUse(env, uses, context, bytes)
          yield result,
      )
      _                <- rejected(app.voteLock(injected, request))
      count            <- uses.get
      recovered        <- env.reopen
      state            <- accepted(recovered.store.snapshot)
      originalWasValid <- originalPassed.get
      refused          <- authorization.get
      _                <- IO(
        assert(
          count == 0 && state.intents.size == 1 && state.sequence == 2L &&
            originalWasValid && refused.exists(
              _.left.exists(_.code == RuntimeFailureCode.RecoveryRequired),
            ),
        ),
      )
    yield ()

  def failedKeyUse(): IO[Unit] =
    val f = new Fixture(42113L, Authority.LockEligible)
    for
      env     <- memory(f)
      app     <- finalizer(env)
      _       <- accepted(app.recover)
      request <- lock(f)
      auth   = authentication(env, app)
      wanted = ControllerApplicationVote(
        f.context,
        value(LockSubject.signingPreimage(request.subject)),
      )
      uses       <- Ref.of[IO, Int](0)
      reachedKey <- Ref.of[IO, Boolean](false)
      failing = signing(
        env,
        (_, _) =>
          accepted(auth.authorize(wanted, f.keys.head._1)) *> reachedKey.set(
            true,
          ) *>
            IO.raiseError[Bytes](new IllegalStateException("key unavailable")),
      )
      _       <- rejected(app.voteLock(failing, request))
      reached <- reachedKey.get
      _       <- IO(assert(reached))
      state   <- accepted(env.store.snapshot)
      _       <- IO(assert(state.intents.size == 1 && state.locks.size == 1))
      _       <- accepted(auth.authenticate(wanted, f.keys.head._1))
      _       <- rejected(auth.authorize(wanted, f.keys.head._1))
      retry = signing(
        env,
        (context, bytes) =>
          accepted(auth.authorize(wanted, f.keys.head._1)) *> keyUse(
            env,
            uses,
            context,
            bytes,
          ),
      )
      _     <- accepted(app.voteLock(retry, request))
      count <- uses.get
      _     <- IO(assert(count == 1))
    yield ()

  def cancelledKeyUse(): IO[Unit] =
    val f = new Fixture(42114L, Authority.LockEligible)
    for
      env     <- memory(f)
      app     <- finalizer(env)
      _       <- accepted(app.recover)
      request <- lock(f)
      auth   = authentication(env, app)
      wanted = ControllerApplicationVote(
        f.context,
        value(LockSubject.signingPreimage(request.subject)),
      )
      entered <- Deferred[IO, Unit]
      uses    <- Ref.of[IO, Int](0)
      suspended = signing(
        env,
        (_, _) =>
          accepted(auth.authorize(wanted, f.keys.head._1)) *>
            entered.complete(()).void *> IO.never[Bytes],
      )
      fiber <- app.voteLock(suspended, request).value.start
      _     <- entered.get
      _     <- fiber.cancel
      _     <- fiber.join
      state <- accepted(env.store.snapshot)
      count <- uses.get
      _     <- IO(
        assert(count == 0 && state.intents.size == 1 && state.locks.size == 1),
      )
      _ <- accepted(auth.authenticate(wanted, f.keys.head._1))
      _ <- rejected(auth.authorize(wanted, f.keys.head._1))
      retry = signing(
        env,
        (context, bytes) =>
          accepted(auth.authorize(wanted, f.keys.head._1)) *> keyUse(
            env,
            uses,
            context,
            bytes,
          ),
      )
      _     <- accepted(app.voteLock(retry, request))
      after <- uses.get
      _     <- IO(assert(after == 1))
    yield ()

  def controlCannotIssueApplicationVotes(): IO[Unit] =
    val f      = new Fixture(42115L, Authority.ConsensusOnly)
    val config = HistoricalProfileConfiguration(
      1L,
      Vector(
        HistoricalProfileRange(
          height(0L),
          None,
          f.context,
          HistoricalArtifactProfile(
            HistoricalApplicationRelease.ApplicationV2,
            2L,
            Some(f.manifest),
          ),
          uint(42115L),
        ),
      ),
    )
    val profiles = AuthenticatedHistoricalProfiles
      .pinned(
        value(HistoricalProfileConfiguration.digest(config)),
        value(HistoricalProfileConfiguration.codec.encode(config)),
      )
      .toOption
      .get
    for
      env <- memory(f)
      app <- finalizer(env)
      _   <- accepted(app.recover)
      auth      = authentication(env, app)
      artifacts = new HotStuffControllerArtifacts:
        def proposal(subject: QuorumCertificateSubject): Result[IO, Proposal] =
          unavailable("no control proposal parent")
        def quorum(
            subject: QuorumCertificateSubject,
        ): Result[IO, QuorumCertificate] =
          if subject == f.baseCertificate.subject then
            EitherT.pure(f.baseCertificate)
          else unavailable("unknown quorum")
      historical = new HistoricalControllerVoteAuthentication:
        def authenticate(
            proposal: Proposal,
            voter: ValidatorId,
            key: Bytes,
        ): Result[IO, ControllerSigningMaterial] = unavailable(
          "historical votes are unavailable",
        )
        def authorize(
            proposal: Proposal,
            voter: ValidatorId,
            key: Bytes,
        ): Result[IO, Unit] = unavailable("historical votes are unavailable")
      controller = HotStuffControllerAuthentication.authenticated(
        profiles,
        ValidatorSetLookup.static(
          BootstrapTrustRoot.staticValidatorSet(f.validators),
        ),
        artifacts,
        historical,
        auth,
        None,
        None,
      )
      vote = UnsignedVote(
        f.candidate.window,
        f.candidate.proposer,
        f.candidate.proposalId,
      )
      raw = value(
        HotStuffControllerRequests.application(f.context, Vote.signBytes(vote)),
      )
      historicalRaw = value(
        HotStuffControllerRequests.vote(f.candidate.proposer, f.candidate),
      )
      uses <- Ref.of[IO, Int](0)
      _    <- Vector(raw, historicalRaw).traverse_(request =>
        rejected(
          controller
            .signing(request, f.keys.head._1, f.keys.head._2.publicKey.toBytes)
            .flatMap(_ =>
              controller.authorizeSigning(
                request,
                f.keys.head._1,
                f.keys.head._2.publicKey.toBytes,
              ),
            )
            .flatMap(_ =>
              EitherT.liftF(keyUse(env, uses, f.context, Vote.signBytes(vote))),
            ),
        ).void,
      )
      count <- uses.get
      state <- accepted(env.store.snapshot)
      _     <- IO(assert(count == 0 && state.committed.isEmpty))
    yield ()

  def currentDeadline(kind: String): IO[Unit] =
    Vector(9L, 10L).zipWithIndex.traverse_ { (current, index) =>
      val f = new Fixture(
        42220L + index.toLong + (if kind == "lock" then 0L
                                 else if kind == "effect" then 10L
                                 else 20L),
        if kind == "consensus" then Authority.ConsensusOnly
        else Authority.LockEligible,
      )
      for
        env            <- memory(f)
        app            <- finalizer(env)
        _              <- accepted(app.recover)
        uses           <- Ref.of[IO, Int](0)
        originalPassed <- Ref.of[IO, Boolean](false)
        authorization  <- Ref.of[IO, Option[Either[V2RuntimeFailure, Unit]]](
          None,
        )
        auth  = authentication(env, app)
        voter = signing(
          env,
          (context, bytes) =>
            for
              _ <- env.currentHeight.set(height(current))
              request = ControllerApplicationVote(context, bytes)
              _ <- recordAuthorization(
                auth,
                request,
                f.keys.head._1,
                originalPassed,
                authorization,
              )
              result <- keyUse(env, uses, context, bytes)
            yield result,
        )
        outcome <- (kind match
          case "lock" =>
            lock(f).flatMap(request =>
              app.voteLock(voter, request).value.map(_.map(_ => ())),
            )
          case "effect" =>
            effect(f).flatMap(request =>
              app.voteEffect(voter, request).value.map(_.map(_ => ())),
            )
          case _ =>
            accepted(
              f.proposalVerifier(f.executedCandidate)
                .verifyProposal(f.candidate, f.consensusPlan),
            )
              .flatMap(request =>
                app.voteConsensus(voter, request).value.map(_.map(_ => ())),
              )
        )
        count                 <- uses.get
        state                 <- accepted(env.store.snapshot)
        originalWasValid      <- originalPassed.get
        observedAuthorization <- authorization.get
        _                     <- IO {
          assert(originalWasValid)
          assert(
            observedAuthorization.exists(result =>
              if current == 9L then result.isRight
              else
                result.left.exists(error =>
                  error.code == RuntimeFailureCode.ProofInvalid &&
                    error.detail.contains("signed deadline"),
                ),
            ),
          )
          assert(outcome.isRight == (current == 9L))
          assert(count == (if current == 9L then 1 else 0))
          assert(state.intents.nonEmpty || state.consensusIntents.nonEmpty)
        }
      yield ()
    }

  def cases: Vector[(String, () => IO[Unit])] = Vector(
    "exact public key permission and original-only rejection" -> (() =>
      exactPermission() *> changedJournalHistory()
    ),
    "failed actual key use releases current permission" -> (() =>
      failedKeyUse()
    ),
    "cancelled actual key use releases current permission" -> (() =>
      cancelledKeyUse()
    ),
    "control request kinds cannot bypass application vote intents" -> (() =>
      controlCannotIssueApplicationVotes()
    ),
  ) ++ Vector("lock", "effect", "consensus").map(kind =>
    s"$kind final key use rejects finality reaching the deadline" -> (() =>
      currentDeadline(kind)
    ),
  )

  def run(): IO[Unit] = cases.traverse_ { (name, run) =>
    IO.println("V2SigningAuthorizationConformance: " + name) *> IO.defer(run())
  }
