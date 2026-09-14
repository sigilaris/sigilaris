package org.sigilaris.node.jvm.runtime.application.v2

import java.util.UUID

import cats.data.EitherT
import cats.effect.{IO, Ref}
import cats.effect.std.Semaphore
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.node.jvm.runtime.block.{BlockHeader, BlockId}
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{
  HotStuffWindow,
  QuorumCertificate,
  Vote,
}

type PreparedBootstrapVote        = InitialStateInstaller.PreparedBootstrapVote
type VerifiedBootstrapCertificate =
  InitialStateInstaller.VerifiedBootstrapCertificate
type VerifiedInitialParent = InitialStateInstaller.VerifiedBootstrapCertificate

sealed trait InitialStateInstaller[F[_]]:
  def bind(
      source: VerifiedBootstrapSource,
      transition: VerifiedBootstrap,
  ): Result[F, BootstrapStartupRecord]
  def install(record: BootstrapStartupRecord): Result[F, BootstrapStartupRecord]
  def prepareInitialVote(
      record: BootstrapStartupRecord,
  ): Result[F, PreparedBootstrapVote]
  def signInitialVote(prepared: PreparedBootstrapVote): Result[F, Vote]
  def verifyInitialCertificate(
      certificate: BootstrapCertificate,
  ): Result[F, VerifiedBootstrapCertificate]
  def recover: Result[F, BootstrapStartupRecord]
  private[v2] def verifyActiveSigning(
      request: Bytes,
      signerId: Text,
      publicKey: Bytes,
  ): Result[F, Unit]

/** Exclusive bootstrap owner of the SAME target application journal that is
  * handed to JournalSafetyStore after Opened. The controller retains the key;
  * this installer receives no signing callback. Bound fixes identity before
  * installation; Installed fixes complete source/initial safety before any vote
  * intent; Opened fixes an actual bundle-bound initial quorum.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object InitialStateInstaller:
  private final case class ActiveSigning(
      request: Bytes,
      signerId: Text,
      publicKey: Bytes,
      records: Vector[JournalRecord],
  )
  final class PreparedBootstrapVote private[InitialStateInstaller] (
      val record: BootstrapStartupRecord,
      val intent: BootstrapVoteIntent,
      private[InitialStateInstaller] val lease: UUID,
  )
  final class VerifiedBootstrapCertificate private[InitialStateInstaller] (
      val context: DomainContext,
      val bundleDigest: Hash,
      val genesis: BlockHeader,
      val genesisBlockId: BlockId,
      val certificate: BootstrapCertificate,
      val quorum: QuorumCertificate,
      val window: HotStuffWindow,
      val record: BootstrapStartupRecord,
      val statePayload: Bytes,
      val sourceInventory: Vector[InventoryEntry],
      private[jvm] val journal: DurableJournal[IO],
      private[jvm] val controller: FenceController,
      private[v2] val bootstrap: VerifiedBootstrap,
  )

  def authenticated(
      bootstrap: VerifiedBootstrap,
      verifier: TransitionEvidenceVerifier[IO],
      repository: TransitionEvidenceRepository[IO],
      controller: FenceController,
      journal: DurableJournal[IO],
  ): IO[InitialStateInstaller[IO]] =
    (
      Semaphore[IO](1L),
      IO(UUID.randomUUID()),
      Ref.of[IO, Option[ActiveSigning]](None),
    ).mapN((gate, lease, activeSigning) =>
      new Live(
        bootstrap,
        verifier,
        repository,
        controller,
        journal,
        gate,
        lease,
        activeSigning,
      ),
    )

  private final class Live(
      bootstrap: VerifiedBootstrap,
      verifier: TransitionEvidenceVerifier[IO],
      repository: TransitionEvidenceRepository[IO],
      controller: FenceController,
      journal: DurableJournal[IO],
      gate: Semaphore[IO],
      lease: UUID,
      activeSigning: Ref[IO, Option[ActiveSigning]],
  ) extends InitialStateInstaller[IO]:
    private[v2] def verifyActiveSigning(
        request: Bytes,
        signerId: Text,
        publicKey: Bytes,
    ): Result[IO, Unit] = for
      active <- EitherT.liftF(activeSigning.get)
      actual <- physical
      _      <- check(
        actual.pending.isEmpty && active.contains(
          ActiveSigning(request, signerId, publicKey, actual.records),
        ),
        "actual initial key use has no matching complete-source installer signing lease",
      )
    yield ()

    private def under[A](run: Result[IO, A]): Result[IO, A] =
      EitherT(gate.permit.use(_ => IO.uncancelable(_ => run.value)))
    private def pure[A](value: Either[V2RuntimeFailure, A]): Result[IO, A] =
      EitherT.fromEither[IO](value)
    private def check(condition: Boolean, detail: String): Result[IO, Unit] =
      pure(
        RuntimeCheck.require(
          condition,
          RuntimeFailureCode.StartupIdentityMismatch,
          detail,
        ),
      )
    private def original(
        source: VerifiedBootstrapSource,
        transition: VerifiedBootstrap,
    ): Result[IO, VerifiedBootstrap] = for
      current <- verifier.verifyBootstrap(transition.source.bundle)
      _       <- check(
        current.digest == bootstrap.digest && source.bundleDigest == current.digest &&
          source.bundle == current.source.bundle && source.closure == current.source.closure && transition.source.closure == current.source.closure,
        "initial installation cannot change its configured original source or signed bootstrap bundle",
      )
    yield current

    private def physical: Result[IO, JournalHistory] =
      journal.recover.flatMap(raw => pure(JournalHistory.validate(raw)))
    private def logical(history: JournalHistory): Vector[JournalRecord] =
      history.records.filter(_.status == JournalStatus.Committed)
    private def verify(
        history: JournalHistory,
    ): Result[IO, TransitionJournalState] =
      BootstrapHistoryAuthentication.verifyRecords(
        bootstrap,
        verifier,
        repository,
        controller,
        journal,
        logical(history),
        requireOpened = false,
      )
    private def startup(
        state: TransitionJournalState,
    ): Result[IO, BootstrapStartupRecord] =
      EitherT.fromOption[IO](
        state.startup,
        V2RuntimeFailure.at(
          RuntimeFailureCode.EvidenceMissing,
          "the original bootstrap startup record is missing",
        ),
      )

    /** Authenticate a complete prepared tail before completing its identical
      * commit. A changed bundle can never replace an uncertain Bound write.
      */
    private def recovered
        : Result[IO, (JournalHistory, TransitionJournalState)] = for
      history <- physical
      result  <- history.pending match
        case None          => verify(history).map(state => history -> state)
        case Some(pending) =>
          for
            _ <- check(
              pending.operation == JournalOperation.BootstrapBind || pending.operation == JournalOperation.BootstrapVote,
              "ordinary pending application history must recover through the application runtime",
            )
            committed = pending.copy(status = JournalStatus.Committed)
            candidate <- pure(JournalHistory.append(history, committed))
            state     <- verify(candidate)
            _         <- journal.append(committed)
          yield candidate -> state
    yield result

    private def persist(
        history: JournalHistory,
        operation: JournalOperation,
        record: BootstrapStartupRecord,
        intent: Option[BootstrapVoteIntent],
    ): Result[IO, JournalHistory] = for
      _ <- check(
        history.pending.isEmpty && history.head.sequence < Long.MaxValue,
        "bootstrap append requires a recovered journal with sequence capacity",
      )
      payload = JournalPayload.empty.copy(
        bootstrapStartup = Some(record),
        bootstrapVoteIntent = intent,
      )
      raw    <- pure(RuntimeCheck.core(JournalPayload.codec.encode(payload)))
      digest <- pure(RuntimeCheck.core(JournalPayload.digest(payload)))
      prepared = JournalRecord(
        2L,
        history.head.sequence + 1L,
        operation,
        history.head.digest,
        raw,
        digest,
        JournalStatus.Prepared,
      )
      committed = prepared.copy(status = JournalStatus.Committed)
      next <- pure(
        JournalHistory
          .append(history, prepared)
          .flatMap(JournalHistory.append(_, committed)),
      )
      anchor <- pure(BootstrapInstallationEvidence.anchor(bootstrap))
      _ <- pure(TransitionJournalReduction.projection(logical(next), anchor))
      _ <- journal.append(prepared)
      _ <- journal.append(committed)
    yield next

    def bind(
        source: VerifiedBootstrapSource,
        transition: VerifiedBootstrap,
    ): Result[IO, BootstrapStartupRecord] = under(for
      current <- original(source, transition)
      history <- physical
      references =
        (current.baseline.entries ++ current.source.bundle.bundle.retiredEvidence).distinct
          .sortBy(r => (r.kind.tag, r.digest.bytes.toHex))
      record <-
        if history.records.nonEmpty then
          recovered.flatMap(pair => startup(pair._2))
        else
          for
            artifacts <- references.traverse(ref =>
              repository.read(ref).map(BootstrapOriginalArtifact(ref, _)),
            )
            inventory <- pure(
              RuntimeCheck.core(
                TransitionInventory.encode(current.source.closure.dataInventory),
              ),
            )
            bound <- controller.withStopped { stopped =>
              for
                snapshot <- stopped.snapshot
                _        <- pure(
                  BootstrapInstallationEvidence.noTargetHistory(
                    snapshot,
                    current.source.bundle.bundle.target,
                  ),
                )
                encoded <- pure(
                  RuntimeCheck.core(ControllerSnapshot.codec.encode(snapshot)),
                )
                evidence = BootstrapInstallationEvidence(
                  1L,
                  current.source.bundle,
                  current.baseline,
                  inventory,
                  current.source.closure.statePayload,
                  current.source.closure.sourceFence,
                  encoded,
                  artifacts,
                )
                selected <- pure(
                  BootstrapInstallationEvidence.startup(evidence, current),
                )
                _ <- pure(
                  BootstrapInstallationEvidence
                    .verifyStructure(evidence, current, selected),
                )
                raw <- pure(
                  RuntimeCheck.core(
                    BootstrapInstallationEvidence.codec.encode(evidence),
                  ),
                )
                _ <- journal.putBlob(
                  BootstrapInstallationEvidence.namespace,
                  selected.installedStateInventory,
                  raw,
                )
                _ <- persist(
                  history,
                  JournalOperation.BootstrapBind,
                  selected,
                  None,
                )
              yield selected
            }
          yield bound
    yield record)

    private def same(
        supplied: BootstrapStartupRecord,
        actual: BootstrapStartupRecord,
    ): Result[IO, Unit] =
      check(
        supplied.copy(
          phase = actual.phase,
          issuedVoteIntents = actual.issuedVoteIntents,
        ) == actual,
        "supplied bootstrap startup does not match the original durable installed identity",
      )

    def install(
        record: BootstrapStartupRecord,
    ): Result[IO, BootstrapStartupRecord] = under(for
      pair      <- recovered
      actual    <- startup(pair._2)
      _         <- same(record, actual)
      installed <- actual.phase match
        case BootstrapPhase.Bound =>
          val next = actual.copy(phase = BootstrapPhase.Installed)
          persist(pair._1, JournalOperation.BootstrapBind, next, None).as(next)
        case _ => EitherT.rightT[IO, V2RuntimeFailure](actual)
    yield installed)

    def prepareInitialVote(
        record: BootstrapStartupRecord,
    ): Result[IO, PreparedBootstrapVote] = under(for
      pair     <- recovered
      actual   <- startup(pair._2)
      _        <- same(record, actual)
      unsigned <- pure(
        InitialBootstrapConsensus.unsigned(bootstrap, controller.signerId),
      )
      intent = BootstrapVoteIntent(
        2L,
        actual.bundleDigest,
        actual.genesisBlockId,
        controller.signerId,
        Vote.signBytes(unsigned),
        actual.initializedSafetyInventory,
        actual.baselineDigest,
      )
      digest   <- pure(RuntimeCheck.core(BootstrapVoteIntent.digest(intent)))
      prepared <- pair._2.bootstrapIntents.get(digest) match
        case Some(previous) =>
          check(
            previous == intent,
            "initial vote retry changed the original intent",
          )
            .as(new PreparedBootstrapVote(actual, previous, lease))
        case None =>
          for
            _ <- check(
              actual.phase == BootstrapPhase.Installed && !pair._2.ordinaryRecords,
              "a new initial vote is permitted only after installation and before opening",
            )
            next = actual.copy(
              phase = BootstrapPhase.Signing,
              issuedVoteIntents =
                (actual.issuedVoteIntents :+ digest).sortBy(_.bytes.toHex),
            )
            _ <- persist(
              pair._1,
              JournalOperation.BootstrapVote,
              next,
              Some(intent),
            )
          yield new PreparedBootstrapVote(next, intent, lease)
    yield prepared)

    def signInitialVote(prepared: PreparedBootstrapVote): Result[IO, Vote] =
      under(for
        _ <- check(
          prepared.lease == lease,
          "prepared initial vote belongs to another installer lifetime",
        )
        pair   <- recovered
        actual <- startup(pair._2)
        _      <- same(prepared.record, actual)
        digest <- pure(
          RuntimeCheck.core(BootstrapVoteIntent.digest(prepared.intent)),
        )
        _ <- check(
          pair._2.bootstrapIntents.get(digest).contains(prepared.intent),
          "initial vote has no identical committed intent in this installed journal",
        )
        request <- pure(
          RuntimeCheck.core(
            InitialBootstrapSigningRequest.codec.encode(
              InitialBootstrapSigningRequest(1L, actual.bundleDigest, digest),
            ),
          ),
        )
        material <- InitialBootstrapSigningAuthentication.signing(
          bootstrap,
          journal,
          request,
          controller.signerId,
          controller.publicKey,
        )
        expected <- pure(
          RuntimeCheck.core(
            ControllerSigningIntent.digest(
              ControllerSigningIntent(request, material),
            ),
          ),
        )
        permission = ActiveSigning(
          request,
          controller.signerId,
          controller.publicKey,
          pair._1.records,
        )
        signature <- EitherT(
          (activeSigning
            .set(Some(permission)) *> controller.sign(request).value)
            .guarantee(activeSigning.set(None)),
        )
        _ <- check(
          signature.intentDigest == expected,
          "key controller returned a signature for a different initial request",
        )
        vote <- pure(
          InitialBootstrapConsensus.attachSignature(
            bootstrap,
            controller.signerId,
            signature.signature,
          ),
        )
      yield vote)

    def verifyInitialCertificate(
        certificate: BootstrapCertificate,
    ): Result[IO, VerifiedBootstrapCertificate] = under(
      for
        pair          <- recovered
        actual        <- startup(pair._2)
        authenticated <- pure(
          InitialBootstrapConsensus.authenticate(bootstrap, certificate),
        )
        _ <- check(
          actual.phase != BootstrapPhase.Bound,
          "an initial certificate cannot open an uninstalled source state",
        )
        opened <- actual.phase match
          case BootstrapPhase.Opened =>
            EitherT.rightT[IO, V2RuntimeFailure](actual)
          case _ =>
            for
              key <- pure(
                RuntimeCheck.core(
                  BootstrapSubject.digest(authenticated.genesis.subject),
                ),
              )
              raw <- pure(
                RuntimeCheck.core(
                  BootstrapCertificate.codec.encode(certificate),
                ),
              )
              existing <- EitherT.liftF(
                journal
                  .readBlob(
                    BootstrapInstallationEvidence.certificateNamespace,
                    key,
                  )
                  .value,
              )
              _ <- existing match
                case Right(original) =>
                  for
                    retained <- pure(
                      RuntimeCheck.core(
                        BootstrapCertificate.codec.decode(original),
                      ),
                    )
                    _ <- pure(
                      InitialBootstrapConsensus
                        .authenticate(bootstrap, retained),
                    )
                  yield ()
                case Left(error)
                    if error.code == RuntimeFailureCode.ProofUnavailable =>
                  journal.putBlob(
                    BootstrapInstallationEvidence.certificateNamespace,
                    key,
                    raw,
                  )
                case Left(error) => EitherT.leftT[IO, Unit](error)
              next = actual.copy(phase = BootstrapPhase.Opened)
              _ <- persist(pair._1, JournalOperation.BootstrapBind, next, None)
            yield next
        installed <- BootstrapInstallationEvidence.load(journal, opened)
        inventory <- pure(
          RuntimeCheck.core(
            TransitionInventory.decode(installed.sourceInventory),
          ),
        )
      yield new VerifiedBootstrapCertificate(
        authenticated.genesis.context,
        actual.bundleDigest,
        authenticated.genesis.header,
        authenticated.genesis.blockId,
        authenticated.certificate,
        authenticated.quorum,
        authenticated.quorum.subject.window,
        opened,
        installed.statePayload,
        inventory,
        journal,
        controller,
        bootstrap,
      ),
    )

    def recover: Result[IO, BootstrapStartupRecord] = under(
      recovered.flatMap(pair => startup(pair._2)),
    )
