package org.sigilaris.conformance

import java.nio.file.Files
import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.*
import scodec.bits.ByteVector
import org.sigilaris.core.application.protocol.ExecutionId
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.Proposal

/** Ordinary lock source that is unchanged by the original certified suffix. F3
  * is its finalized admission base; P5 is only its working execution state.
  */
@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object V2HistoricalLockFixture:
  import V2HistoricalFixture.{accepted, check, core}
  import V2RequestConformance.{Fixture, height, uint, unavailable}

  final class Configuration private[V2HistoricalLockFixture] (
      val source: Fixture,
      val subject: LockSubject,
      val artifacts: ArtifactAuthentication,
      configured: ApplicationRequestVerifier[IO],
      finalized: ApplicationAnchor,
      working: ApplicationAnchor,
      currentSource: () => Result[IO, Unit],
  ):
    def verify(base: HandoverInstalledBase): Result[IO, Unit] =
      currentSource() *> check(
        base.canonicalStart == finalized && base.workingParent == working && base.targetContext == source.context,
        "ordinary lock source differs from installed original F3 and working P5",
      )
    def wrap(
        base: ApplicationRequestVerifier[IO],
    ): ApplicationRequestVerifier[IO] =
      new ApplicationRequestVerifier[IO]:
        def verifyLock(
            s: LockSubject,
            d: InputDescriptor,
            t: Bytes,
            p: Vector[ResolutionEvidence],
        ): Result[IO, VerifiedLockRequest] =
          if s.executionId == source.executionId then
            currentSource() *> configured.verifyLock(s, d, t, p)
          else base.verifyLock(s, d, t, p)
        def verifyEffect(
            s: EffectSubject,
            o: Owner,
            w: ReservationWitness,
        ): Result[IO, VerifiedEffectRequest] =
          if s.executionId == source.executionId then
            currentSource() *> configured.verifyEffect(s, o, w)
          else base.verifyEffect(s, o, w)
        def verifyProposal(
            p: Proposal,
            plan: ExecutionPlan,
        ): Result[IO, VerifiedConsensusProposal] = base.verifyProposal(p, plan)
        def verifyLockCertificate(
            c: LockCertificate,
        ): Result[IO, VerifiedLockCertificate] =
          if c.subject.executionId == source.executionId then
            currentSource() *> configured.verifyLockCertificate(c)
          else base.verifyLockCertificate(c)
        def verifyEffectCertificate(
            c: EffectCertificate,
        ): Result[IO, VerifiedEffectCertificate] =
          if c.subject.executionId == source.executionId then
            currentSource() *> configured.verifyEffectCertificate(c)
          else base.verifyEffectCertificate(c)

  private def derive(
      material: V2HistoricalFixture.Material,
  ): Result[IO, Configuration] = for
    finality <- material.finalized(3L)
    original <- material.consensus.finalized(finality)
    first = original.ordered.head
    rows   <- EitherT.liftF(material.retained.get)
    certs  <- EitherT.liftF(material.certificates.get)
    parent <- EitherT.fromOption[IO](
      rows.values.find(_.block.height.toBigNat.toBigInt == 5),
      V2RuntimeFailure.at(
        RuntimeFailureCode.EvidenceMissing,
        "ordinary lock source lost certified working P5",
      ),
    )
    qc <- EitherT.fromOption[IO](
      certs.get(parent.proposalId),
      V2RuntimeFailure.at(
        RuntimeFailureCode.EvidenceMissing,
        "ordinary lock source lost working P5 quorum",
      ),
    )
    certified <- material.consensus.certified(parent, qc)
    _         <- check(
      first.selected.context == material.old && first.selected.profile.release == HistoricalApplicationRelease.LegacyM2,
      "ordinary lock admission requires the immutable old M2 profile at actual F3",
    )
    state <- material.decodeState(first.replay.material.canonicalStatePayload)
    workingState <- material.decodeState(
      certified.execution.replay.material.canonicalStatePayload,
    )
    source = new Fixture(
      1800L,
      Authority.LockEligible,
      10,
      Vector.empty,
      Some(state),
      None,
      height(10L),
    )
    execution = new Fixture(
      1800L,
      Authority.LockEligible,
      10,
      Vector.empty,
      Some(workingState),
      None,
      height(10L),
    )
    _ <- check(
      source.context == material.target && source.signedTransaction == execution.signedTransaction &&
        source.descriptor == execution.descriptor && source.preStateRoot == first.replay.nextStateRoot &&
        execution.preStateRoot == certified.execution.replay.nextStateRoot,
      "original certified suffix must preserve the exact ordinary lock-bearing source and descriptor",
    )
    _ <- core(
      InputDerivation.validate(
        execution.family,
        source.signedTransaction,
        execution.preStateRoot,
        source.descriptor,
        execution.proofs,
        execution.inputs,
      ),
    )
    finalized = ApplicationAnchor(
      material.old,
      first.proposal.targetBlockId.toUInt256,
      height(3L),
      first.replay.nextStateRoot,
    )
    working = ApplicationAnchor(
      material.old,
      parent.targetBlockId.toUInt256,
      height(5L),
      certified.execution.replay.nextStateRoot,
    )
    base = AdmissionBase.Finalized(
      finalized.blockId,
      finalized.height,
      finalized.stateRoot,
    )
    artifacts = new ArtifactAuthentication:
      def historicalValidators(
          context: DomainContext,
      ): Either[CoreFailure, Vector[Text]] =
        source.artifacts.historicalValidators(context)
      def verifySignature(
          context: DomainContext,
          signer: Text,
          preimage: Bytes,
          signature: Bytes,
      ): Either[CoreFailure, Unit] =
        source.artifacts.verifySignature(context, signer, preimage, signature)
      def verifyFinalizedBase(
          context: DomainContext,
          supplied: AdmissionBase,
      ): Either[CoreFailure, Unit] =
        Either.cond(
          context == material.target && supplied == base,
          (),
          CoreFailure.at(
            FailureCode.ProofInvalid,
            "actual authenticated original F3 is the only admitted base",
          ),
        )
    repository = new ApplicationExecutionRepository[IO]:
      def lockSource(
          context: DomainContext,
          executionId: ExecutionId,
      ): Result[IO, LockSourceMaterial] =
        check(
          context == source.context && executionId == source.executionId,
          "registered original lock source identity",
        )
          .as(
            LockSourceMaterial(
              source.descriptor,
              source.signedTransaction,
              source.proofs,
            ),
          )
      def lockCertificate(id: Hash): Result[IO, LockCertificate] = unavailable(
        "completed lock certificate must come from its real durable quorum archive",
      )
      def effectCertificate(id: Hash): Result[IO, EffectCertificate] =
        unavailable("no effect quorum was issued for this original source")
      def executeEffect(
          context: DomainContext,
          executionId: ExecutionId,
      ): Result[IO, ExecutedEffect] = unavailable(
        "no fast scope is authorized for this source",
      )
      def verifyExactBinding(
          context: DomainContext,
          executionId: ExecutionId,
          binding: Option[Hash],
      ): Result[IO, Unit] =
        check(
          context == source.context && executionId == source.executionId && binding.isEmpty,
          "original ordinary lock is not an exact pipeline",
        )
    configured = ApplicationRequestVerifier.authenticated(
      source.manifest,
      source.inputs,
      source.declarations,
      source.creations,
      source.transactions,
      artifacts,
      source.scopes,
      repository,
      source.unavailableProposals,
    )
  yield new Configuration(
    source,
    source.lockSubject.copy(admissionBase = base),
    artifacts,
    configured,
    finalized,
    working,
    () => originalSource(material, source.signedTransaction),
  )

  private def originalSource(
      material: V2HistoricalFixture.Material,
      expected: Bytes,
  ): Result[IO, Unit] = for
    original <- EitherT(
      IO.blocking(
        ByteVector.view(
          Files.readAllBytes(material.root.resolve("target-lock-source")),
        ),
      ).attempt
        .map(
          _.leftMap(_ =>
            V2RuntimeFailure.at(
              RuntimeFailureCode.EvidenceMissing,
              "original signed target lock source missing",
            ),
          ),
        ),
    )
    _ <- check(
      original == expected,
      "retained original signed target lock source changed",
    )
  yield ()

  /** Called once before opening the first target journal. */
  def initialize(material: V2HistoricalFixture.Material): Result[IO, Unit] = for
    configured <- derive(material)
    path = material.root.resolve("target-lock-source")
    exists <- EitherT.liftF(IO.blocking(Files.exists(path)))
    _      <-
      if exists then configuration(material).void
      else
        for
          started <- EitherT.liftF(
            IO.blocking(
              (0 to 3).exists(i =>
                Files.exists(material.root.resolve("target-" + i.toString)),
              ),
            ),
          )
          _ <- check(
            !started,
            "cannot backfill a missing signed lock source after target activation starts",
          )
          _ <- EitherT.liftF(
            V2HistoricalFixture
              .durable(path, configured.source.signedTransaction),
          )
        yield ()
  yield ()

  def configuration(
      material: V2HistoricalFixture.Material,
  ): Result[IO, Configuration] = for
    configured <- derive(material)
    _          <- originalSource(material, configured.source.signedTransaction)
  yield configured

  def verify(cluster: V2HistoricalRuntimeFixture.Cluster): Result[IO, Unit] =
    for
      configured <- configuration(cluster.source)
      _          <- configured.verify(cluster.nodes.head.historical.base)
      source  = configured.source
      subject = configured.subject
      request <- configured
        .wrap(cluster.nodes.head.requests)
        .verifyLock(
          subject,
          source.descriptor,
          source.signedTransaction,
          source.proofs,
        )
      votes <- cluster.nodes.traverse(node =>
        node.finalizer.voteLock(node.voting, request),
      )
      certificate = LockCertificate(subject, votes.take(3).map(_.vote))
      verified <- configured
        .wrap(cluster.nodes.head.requests)
        .verifyLockCertificate(certificate)
      _ <- cluster.nodes.traverse_(_.voting.importLock(verified))
      _ <- core(
        LockCertificate.verify(
          certificate,
          source.manifest,
          configured.artifacts,
        ),
      )
      _ <- EitherT.liftF(
        Vector(
          AdmissionBase.Finalized(
            cluster.nodes.head.historical.base.workingParent.blockId,
            height(5L),
            cluster.nodes.head.historical.base.workingParent.stateRoot,
          ),
          AdmissionBase.Finalized(
            cluster.nodes.head.historical.base.canonicalStart.blockId,
            height(3L),
            uint(999999L),
          ),
        ).traverse_(substituted =>
          configured
            .wrap(cluster.nodes.head.requests)
            .verifyLock(
              subject.copy(admissionBase = substituted),
              source.descriptor,
              source.signedTransaction,
              source.proofs,
            )
            .value
            .flatMap(result =>
              accepted(
                check(
                  result.isLeft,
                  "working P5 or changed original root cannot become finalized admission base",
                ),
              ),
            ),
        ),
      )
      oldContext <- EitherT.liftF(
        configured
          .wrap(cluster.nodes.head.requests)
          .verifyLock(
            subject.copy(context = cluster.source.old),
            source.descriptor,
            source.signedTransaction,
            source.proofs,
          )
          .value,
      )
      _ <- check(
        oldContext.isLeft,
        "new lock subject cannot be relabeled into the old historical protocol",
      )
    yield ()

  /** Active and already prepared requests must not hide lost original source
    * bytes.
    */
  def rejectSourceLoss(
      cluster: V2HistoricalRuntimeFixture.Cluster,
  ): Result[IO, Unit] = for
    node <- EitherT.fromOption[IO](
      cluster.nodes.headOption,
      V2RuntimeFailure.at(
        RuntimeFailureCode.InvalidRequest,
        "missing actual node",
      ),
    )
    source  = node.lockConfiguration.source
    subject = node.lockConfiguration.subject
    prepared <- node.requests.verifyLock(
      subject,
      source.descriptor,
      source.signedTransaction,
      source.proofs,
    )
    _ <- EitherT.liftF(
      IO.blocking(
        Files.delete(cluster.source.root.resolve("target-lock-source")),
      ),
    )
    fresh <- EitherT.liftF(
      node.requests
        .verifyLock(
          subject,
          source.descriptor,
          source.signedTransaction,
          source.proofs,
        )
        .value,
    )
    _ <- check(
      fresh.left.exists(_.code == RuntimeFailureCode.EvidenceMissing),
      "active verifier must recheck original lock source availability",
    )
    retry <- EitherT.liftF(node.finalizer.voteLock(node.voting, prepared).value)
    _     <- check(
      retry.isLeft,
      "prepared or known lock signature cannot bypass loss of its original source closure",
    )
  yield ()
