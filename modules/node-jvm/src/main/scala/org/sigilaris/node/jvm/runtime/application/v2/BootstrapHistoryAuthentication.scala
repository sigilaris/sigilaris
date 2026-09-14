package org.sigilaris.node.jvm.runtime.application.v2

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.application.protocol.InclusionHeight
import org.sigilaris.core.datatype.BigNat
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.Vote

/** Re-authenticate original source and fixed evidence plus the CURRENT durable
  * key controller. Called with immutable SafetyState under the safety gate;
  * never invokes the installer or that SafetyStore again.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object BootstrapHistoryAuthentication:
  def authenticated(
      bootstrap: VerifiedBootstrap,
      verifier: TransitionEvidenceVerifier[IO],
      repository: TransitionEvidenceRepository[IO],
      controller: FenceController,
  ): TransitionHistoryAuthentication[IO] =
    new TransitionHistoryAuthentication[IO]:
      def verifyTransitionHistory(
          state: SafetyState,
          journal: DurableJournal[IO],
      ): Result[IO, Unit] =
        verifyRecords(
          bootstrap,
          verifier,
          repository,
          controller,
          journal,
          state.committed,
          requireOpened = true,
        ).void

  private[v2] def verifyRecords(
      bootstrap: VerifiedBootstrap,
      verifier: TransitionEvidenceVerifier[IO],
      repository: TransitionEvidenceRepository[IO],
      controller: FenceController,
      journal: DurableJournal[IO],
      records: Vector[JournalRecord],
      requireOpened: Boolean,
  ): Result[IO, TransitionJournalState] = for
    anchor <- EitherT.fromEither[IO](
      BootstrapInstallationEvidence.anchor(bootstrap),
    )
    projection <- EitherT.fromEither[IO](
      TransitionJournalReduction.projection(records, anchor),
    )
    startup <- EitherT.fromOption[IO](
      projection.startup,
      V2RuntimeFailure.at(
        RuntimeFailureCode.EvidenceMissing,
        "original bootstrap startup record is missing",
      ),
    )
    evidence <- BootstrapInstallationEvidence.load(journal, startup)
    current  <- BootstrapInstallationEvidence.authenticate(
      evidence,
      bootstrap,
      startup,
      verifier,
      repository,
    )
    _ <- EitherT.fromEither[IO](
      verifyIntents(current, startup, projection.bootstrapIntents),
    )
    original <- EitherT.fromEither[IO](
      RuntimeCheck.core(
        ControllerSnapshot.codec.decode(evidence.initialController),
      ),
    )
    _ <- controller.preserveAfterRestore(original)
    _ <- EitherT.fromEither[IO](
      RuntimeCheck.require(
        !requireOpened || startup.phase == BootstrapPhase.Opened,
        RuntimeFailureCode.RecoveryRequired,
        "initial quorum must be durably opened before ordinary runtime startup",
      ),
    )
    _ <- startup.phase match
      case BootstrapPhase.Opened =>
        for
          initial <- EitherT.fromEither[IO](
            InitialBootstrapConsensus.genesis(current),
          )
          key <- EitherT.fromEither[IO](
            RuntimeCheck.core(BootstrapSubject.digest(initial.subject)),
          )
          raw <- journal.readBlob(
            BootstrapInstallationEvidence.certificateNamespace,
            key,
          )
          certificate <- EitherT.fromEither[IO](
            RuntimeCheck.core(BootstrapCertificate.codec.decode(raw)),
          )
          _ <- EitherT.fromEither[IO](
            InitialBootstrapConsensus.authenticate(current, certificate),
          )
        yield ()
      case _ => EitherT.rightT[IO, V2RuntimeFailure](())
  yield projection

  private[v2] def verifyIntents(
      bootstrap: VerifiedBootstrap,
      startup: BootstrapStartupRecord,
      intents: Map[Hash, BootstrapVoteIntent],
  ): Either[V2RuntimeFailure, Unit] = for
    _ <- RuntimeCheck.require(
      startup.issuedVoteIntents.toSet == intents.keySet,
      RuntimeFailureCode.JournalCorrupt,
      "bootstrap startup omitted a retained initial signing intent",
    )
    _ <- intents.toVector.traverse_ { case (digest, intent) =>
      for
        actual   <- RuntimeCheck.core(BootstrapVoteIntent.digest(intent))
        unsigned <- InitialBootstrapConsensus.unsigned(
          bootstrap,
          intent.validatorId,
        )
        _ <- RuntimeCheck.require(
          actual == digest && intent.bundleDigest == startup.bundleDigest &&
            intent.genesisBlockId == startup.genesisBlockId && intent.initializedSafetyInventory == startup.initializedSafetyInventory &&
            intent.baselineDigest == startup.baselineDigest && intent.unsignedVoteSignBytes == Vote
              .signBytes(unsigned),
          RuntimeFailureCode.JournalCorrupt,
          "bootstrap intent changed its installed source/safety or original HotStuff preimage",
        )
      yield ()
    }
  yield ()

/** Compose this parser into the controller's installed signing interpreter. It
  * requires a previously authenticated immutable bootstrap, the actual forced
  * target journal and exact validator key. It does not call the outer
  * transition verifier/controller recursively. The installer rechecks current
  * source fences before entry, and the key-owning controller enforces its own
  * monotonic target fences under the signing gate.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object InitialBootstrapSigningAuthentication:
  /** Fresh key use requires the actual closed installer holding its fully
    * authenticated source/journal gate for these exact bytes. Historical
    * recovery uses signing and never invents this temporary permission.
    */
  def authorize(
      installer: InitialStateInstaller[IO],
      request: Bytes,
      signerId: Text,
      publicKey: Bytes,
  ): Result[IO, Unit] =
    installer.verifyActiveSigning(request, signerId, publicKey)

  def signing(
      bootstrap: VerifiedBootstrap,
      journal: DurableJournal[IO],
      request: Bytes,
      signerId: Text,
      publicKey: Bytes,
  ): Result[IO, ControllerSigningMaterial] = for
    parsed <- EitherT.fromEither[IO](
      RuntimeCheck.core(InitialBootstrapSigningRequest.codec.decode(request)),
    )
    anchor <- EitherT.fromEither[IO](
      BootstrapInstallationEvidence.anchor(bootstrap),
    )
    raw     <- journal.recover
    history <- EitherT.fromEither[IO](JournalHistory.validate(raw))
    _       <- EitherT.fromEither[IO](
      RuntimeCheck.require(
        history.pending.isEmpty,
        RuntimeFailureCode.RecoveryRequired,
        "initial controller signing requires a committed target vote intent",
      ),
    )
    projection <- EitherT.fromEither[IO](
      TransitionJournalReduction.projection(
        history.records.filter(_.status == JournalStatus.Committed),
        anchor,
      ),
    )
    startup <- EitherT.fromOption[IO](
      projection.startup,
      V2RuntimeFailure.at(
        RuntimeFailureCode.EvidenceMissing,
        "initial signing has no installed bootstrap",
      ),
    )
    evidence <- BootstrapInstallationEvidence.load(journal, startup)
    _        <- EitherT.fromEither[IO](
      BootstrapInstallationEvidence.verifyStructure(
        evidence,
        bootstrap,
        startup,
      ),
    )
    _ <- EitherT.fromEither[IO](
      BootstrapHistoryAuthentication.verifyIntents(
        bootstrap,
        startup,
        projection.bootstrapIntents,
      ),
    )
    intent <- EitherT.fromOption[IO](
      projection.bootstrapIntents.get(parsed.intentDigest),
      V2RuntimeFailure.at(
        RuntimeFailureCode.EvidenceMissing,
        "initial signing intent was not forced in the installed journal",
      ),
    )
    _ <- EitherT.fromEither[IO](
      RuntimeCheck.require(
        parsed.bundleDigest == bootstrap.digest &&
          intent.validatorId == signerId && bootstrap.source.bundle.bundle.validators
            .exists(v =>
              v.validatorId == signerId && v.publicKey == publicKey,
            ) &&
          (startup.phase == BootstrapPhase.Signing || startup.phase == BootstrapPhase.Opened),
        RuntimeFailureCode.StartupIdentityMismatch,
        "initial signing request changed its bundle, voter/key or durable installation phase",
      ),
    )
  yield ControllerSigningMaterial(
    anchor.context,
    ControllerSigningKind.Consensus,
    Some(InclusionHeight(BigNat.unsafeFromBigInt(BigInt(0)))),
    intent.unsignedVoteSignBytes,
  )
