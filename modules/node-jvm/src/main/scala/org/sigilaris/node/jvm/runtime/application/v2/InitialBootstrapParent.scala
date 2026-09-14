package org.sigilaris.node.jvm.runtime.application.v2

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.node.jvm.runtime.block.BlockId
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{
  HotStuffWindow,
  QuorumCertificate,
}

/** Installed G is a header and initial-only quorum, never a synthetic Proposal.
  * The private installation capability is required at every initial-parent
  * branch; an ordinary QC with height zero does not select this branch.
  */
@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object InitialBootstrapParent:
  def validate(
      initial: VerifiedInitialParent,
      window: HotStuffWindow,
      parent: Option[BlockId],
      justify: QuorumCertificate,
  ): Either[V2RuntimeFailure, Unit] = for
    _ <- RuntimeCheck.require(
      window.chainId.value == initial.context.chainId.asString &&
        window.validatorSetHash.toUInt256 == initial.context.validatorSetHash &&
        window.height.toBigNat.toBigInt == 1 && parent.contains(
          initial.genesisBlockId,
        ) &&
        justify.subject == initial.quorum.subject && justify.subject.window == initial.window &&
        initial.record.phase == BootstrapPhase.Opened,
      RuntimeFailureCode.ProofInvalid,
      "initial parent requires the installed bundle/G subject at the new domain's first height",
    )
    // Initial quorums are certificates over a subject, not an agreed vote set.
    // Reauthenticate all supplied votes against the installed bundle's roster.
    _ <- InitialBootstrapConsensus.authenticate(
      initial.bootstrap,
      initial.certificate
        .copy(quorum = ByteEncoder[QuorumCertificate].encode(justify)),
    )
  yield ()

  def opening(plan: ExecutionPlan): Either[V2RuntimeFailure, Unit] =
    RuntimeCheck
      .core(ExecutionPlan.validateShape(plan))
      .flatMap(_ =>
        RuntimeCheck.require(
          plan.waves.sizeIs == 1 && plan.waves.forall(wave =>
            wave.kind == WaveKind.CompatibilitySingleton && wave.entries.sizeIs == 1 && wave.entries
              .forall(entry =>
                (entry.source match
                  case PlanSource.ConsensusTransaction(_, _, None) => true
                  case _                                           => false
                ),
              ),
          ),
          RuntimeFailureCode.InvalidRequest,
          "the first ordinary block must contain exactly its authenticated lock-free compatibility opening",
        ),
      )

  /** Original controller history may be replayed before its live installer and
    * SafetyStore exist. This reads the already forced Opened startup/QC from
    * the same journal. It grants no live signing capability.
    */
  def original(
      bootstrap: VerifiedBootstrap,
      journal: DurableJournal[cats.effect.IO],
      window: HotStuffWindow,
      parent: Option[BlockId],
      justify: QuorumCertificate,
  ): Result[cats.effect.IO, Unit] =
    import cats.data.EitherT
    import cats.effect.IO
    for
      genesis <- EitherT.fromEither[IO](
        InitialBootstrapConsensus.genesis(bootstrap),
      )
      raw     <- journal.recover
      history <- EitherT.fromEither[IO](JournalHistory.validate(raw))
      anchor  <- EitherT.fromEither[IO](
        BootstrapInstallationEvidence.anchor(bootstrap),
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
          "original initial installation is missing",
        ),
      )
      _ <- EitherT.fromEither[IO](
        RuntimeCheck.require(
          startup.phase == BootstrapPhase.Opened && history.pending.isEmpty,
          RuntimeFailureCode.RecoveryRequired,
          "original initial parent requires the committed Opened installation",
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
      certificate <- EitherT.fromEither[IO](
        InitialBootstrapConsensus.authenticate(
          bootstrap,
          BootstrapCertificate(
            1L,
            genesis.bundleDigest,
            genesis.blockId.toUInt256,
            org.sigilaris.core.codec.byte
              .ByteEncoder[QuorumCertificate]
              .encode(justify),
          ),
        ),
      )
      _ <- EitherT.fromEither[IO](
        RuntimeCheck.require(
          window.chainId == genesis.quorumSubject.window.chainId &&
            window.validatorSetHash == genesis.quorumSubject.window.validatorSetHash && window.height.toBigNat.toBigInt == 1 &&
            parent.contains(genesis.blockId) && certificate.quorum == justify,
          RuntimeFailureCode.ProofInvalid,
          "original initial parent differs from the installed G/context/height/QC",
        ),
      )
    yield ()
