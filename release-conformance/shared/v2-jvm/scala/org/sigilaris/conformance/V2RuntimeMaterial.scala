package org.sigilaris.conformance

import cats.data.EitherT
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.NormalizedApplicationResult
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.application.protocol.v2.V2Codecs.bytesEncoder
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.node.jvm.runtime.block.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*

/** Per-node proof material for the ordinary runtime fixture. Every candidate is
  * authenticated and independently executed against its actual parent; there is
  * no table of pre-signed future proposals or completed future QCs.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.OptionPartial",
  ),
)
final class V2RuntimeMaterial(
    val source: V2RequestConformance.Fixture,
    val voter: ValidatorId,
    val retained: Ref[IO, Map[ProposalId, Proposal]],
    val finalization: Ref[IO, FinalizationTrackerSnapshot],
    val live: Ref[IO, Option[HotStuffNodeRuntime[IO]]],
):
  import V2RequestConformance.*
  val manifest         = source.manifest
  val context          = source.context
  val initialHistory   = V2RuntimeMaterial.initialHistory(source)
  val genesis          = initialHistory.head
  val checkpointHeader =
    source.baseHeader.copy(
      parent = Some(initialHistory.last.targetBlockId),
      bodyRoot = initialHistory.last.block.bodyRoot,
    )
  val checkpointBlock = BlockHeader.computeId(checkpointHeader)
  val anchor          = ApplicationAnchor(
    context,
    checkpointBlock.toUInt256,
    height(5L),
    source.preStateRoot,
  )
  val validators: ValidatorSetLookup[IO] = ValidatorSetLookup.static(
    BootstrapTrustRoot.staticValidatorSet(source.validators),
  )

  private def check(condition: Boolean, message: String): Result[IO, Unit] =
    EitherT.fromEither[IO](
      Either.cond(
        condition,
        (),
        V2RuntimeFailure.at(RuntimeFailureCode.ProofInvalid, message),
      ),
    )

  def allProposals: IO[Map[ProposalId, Proposal]] = for
    archived <- retained.get
    runtime  <- live.get
    current  <- runtime.flatMap(_.inMemorySink).traverse(_.snapshot)
  yield current.fold(Map.empty[ProposalId, Proposal])(_.proposals) ++ archived

  def history: IO[Map[BlockId, Proposal]] = allProposals.map(
    _.values.toVector
      .sortBy(p => (p.window.view.toBigNat.toBigInt, p.proposalId.toHexLower))
      .map(p => p.targetBlockId -> p)
      .toMap,
  )

  def finalized: IO[FinalizationTrackerSnapshot] = for
    archived <- finalization.get
    runtime  <- live.get
    current  <- runtime.flatMap(_.inMemorySink).traverse(_.snapshot)
    actual = current
      .flatMap(_.finalization.get(source.chain))
      .getOrElse(FinalizationTrackerSnapshot.empty)
    best = (archived.bestFinalized.toVector ++ actual.bestFinalized.toVector)
      .sortBy(_.anchorHeight.toBigNat.toBigInt)
      .lastOption
  yield FinalizationTrackerSnapshot(
    best,
    (archived.safetyFaults ++ actual.safetyFaults).distinct,
  )

  def retain(proposal: Proposal): Result[IO, Unit] = for
    _ <- check(
      HotStuffValidator.validateProposal(proposal, source.validators).isRight,
      "retained proposal signatures and QC",
    )
    _ <- EitherT.liftF(
      retained.update(_.updated(proposal.proposalId, proposal)),
    )
  yield ()

  def parent(proposal: Proposal): Result[IO, Proposal] = for
    rows     <- EitherT.liftF(allProposals)
    selected <- EitherT.fromOption[IO](
      rows.get(proposal.justify.subject.proposalId),
      V2RuntimeFailure.at(
        RuntimeFailureCode.ProofUnavailable,
        "actual proposal parent missing",
      ),
    )
    _ <- check(
      HotStuffValidator.validateProposal(selected, source.validators).isRight &&
        HotStuffValidator
          .validateQuorumCertificate(proposal.justify, source.validators)
          .isRight &&
        proposal.justify.subject.proposalId == selected.proposalId &&
        proposal.justify.subject.blockId == selected.targetBlockId &&
        proposal.justify.subject.window == selected.window &&
        proposal.window.chainId == selected.window.chainId &&
        proposal.block.height.toBigNat.toBigInt == selected.block.height.toBigNat.toBigInt + 1,
      "actual parent/QC/height relation",
    )
  yield selected

  val schedule: HotStuffApplicationProfileSchedule[IO] =
    new HotStuffApplicationProfileSchedule[IO]:
      def at(
          window: HotStuffWindow,
          parent: Option[BlockId],
      ): Result[IO, HotStuffHistoricalApplicationProfile] =
        check(
          window.chainId == source.chain && window.validatorSetHash == source.validators.hash,
          "installed profile context",
        ).as(
          if window.height.toBigNat.toBigInt <= 5 then
            HotStuffHistoricalApplicationProfile.LegacyM2(BlockHeaderVersion.V1)
          else HotStuffHistoricalApplicationProfile.ApplicationV2(manifest),
        )

  val parents: HotStuffProposalParentRepository[IO] =
    new HotStuffProposalParentRepository[IO]:
      def parent(request: HotStuffProposalInputRequest): Result[IO, Proposal] =
        EitherT(
          allProposals.map(rows =>
            rows
              .get(request.justify.subject.proposalId)
              .toRight(
                V2RuntimeFailure.at(
                  RuntimeFailureCode.ProofUnavailable,
                  "ordinary builder parent",
                ),
              ),
          ),
        )

  val execution: HotStuffApplicationCandidateExecution[IO] =
    new HotStuffApplicationCandidateExecution[IO]:
      def execute(
          request: HotStuffProposalInputRequest,
          selected: ProtocolManifest,
          parent: Proposal,
      ): Result[IO, Option[HotStuffExecutedApplicationCandidate]] =
        check(selected == manifest, "execution profile").flatMap: _ =>
          if request.height.toBigNat.toBigInt == 6 then
            check(
              parent.targetBlockId == checkpointBlock && parent.block.stateRoot.toUInt256 == source.preStateRoot,
              "signed source initial state",
            )
              .as(
                Some(
                  HotStuffExecutedApplicationCandidate(
                    source.consensusPlan,
                    Vector(source.normalizedResult.bytes),
                    Vector(source.nextStateRoot),
                  ),
                ),
              )
          else
            check(
              parent.block.stateRoot.toUInt256 == source.nextStateRoot,
              "empty working state",
            ).as(None)

  private def owner(
      proposal: Proposal,
      plan: ExecutionPlan,
      entry: PlanEntry,
  ): Owner =
    val root = value(ExecutionPlan.computeRoot(plan))
    Owner(
      context,
      entry.executionId,
      Scope(
        ScopeKind.ConsensusOrdered,
        proposal.block.parent.get.toUInt256,
        height(proposal.block.height.toBigNat.toBigInt.longValue),
        root.toUInt256,
        0L,
        hash(
          proposal.justify.toBytes ++ value(
            PlanEntry.codec.encode(entry),
          ) ++ root.toBytes,
        ),
      ),
    )

  val scopes: ReservationScopeAuthentication[IO] =
    new ReservationScopeAuthentication[IO]:
      def verifyFast(
          actual: Owner,
          base: AdmissionBase,
          signed: SignedApplicationBinding,
      ): Result[IO, Unit] =
        source.scopes.verifyFast(actual, base, signed)
      def verifyConsensus(
          actual: Owner,
          proposal: Proposal,
          plan: ExecutionPlan,
          entry: PlanEntry,
      ): Result[IO, Unit] =
        parent(proposal) *> check(
          actual == owner(
            proposal,
            plan,
            entry,
          ) && entry == source.consensusEntry && plan == source.consensusPlan,
          "signed branch/order authorization",
        )

  val proposals: ProposalExecutionRepository[IO] =
    new ProposalExecutionRepository[IO]:
      def historicalValidators(
          window: HotStuffWindow,
      ): Result[IO, ValidatorSet] =
        source.unavailableProposals.historicalValidators(window)
      def executeSequential(
          proposal: Proposal,
          plan: ExecutionPlan,
      ): Result[IO, ExecutedProposal] = for
        actualParent <- parent(proposal)
        _            <- check(
          HotStuffValidator
            .validateProposal(proposal, source.validators)
            .isRight,
          "candidate signature",
        )
        executed <-
          if plan.waves.isEmpty then
            for
              _ <- check(
                plan == ExecutionPlan.empty && proposal.block.stateRoot == actualParent.block.stateRoot,
                "canonical empty identity transition",
              )
              root = BlockBody
                .computeBodyRoot(BlockBody[Hash, Hash, Bytes](Set.empty))
                .toOption
                .get
            yield ExecutedProposal(
              proposal,
              UnsignedVote(proposal.window, voter, proposal.proposalId),
              actualParent.block.stateRoot.toUInt256,
              Vector.empty,
              root.toUInt256,
              Vector.empty,
            )
          else
            for _ <- check(
                plan == source.consensusPlan && actualParent.block.stateRoot.toUInt256 == source.preStateRoot && proposal.block.height.toBigNat.toBigInt == 6,
                "signed transaction sequential execution",
              )
            yield ExecutedProposal(
              proposal,
              UnsignedVote(proposal.window, voter, proposal.proposalId),
              source.preStateRoot,
              Vector(BodyMember(source.txId, source.executionId)),
              source.bodyRoot.toUInt256,
              Vector(
                ExecutedApplication(
                  source.consensusEntry,
                  source.descriptor,
                  source.proofs,
                  source.access,
                  Vector(source.createIdentity -> source.creationProof),
                  source.normalizedResult.bytes,
                  source.nextStateRoot,
                  owner(proposal, plan, source.consensusEntry),
                  None,
                ),
              ),
            )
      yield executed

  private val consensusRequests: ApplicationRequestVerifier[IO] =
    ApplicationRequestVerifier.authenticated(
      manifest,
      source.inputs,
      source.declarations,
      source.creations,
      source.transactions,
      source.artifacts,
      scopes,
      source.repository(source.effect),
      proposals,
    )

  val fastSources: Vector[V2RequestConformance.Fixture] = Vector(1L, 2L).map(
    delta => new Fixture(source.nonce + delta, Authority.LockEligible),
  )
  def fastSubject(index: Int): LockSubject =
    fastSources(index).lockSubject.copy(admissionBase =
      AdmissionBase.Finalized(
        checkpointBlock.toUInt256,
        anchor.height,
        anchor.stateRoot,
      ),
    )
  val artifacts: ArtifactAuthentication = new ArtifactAuthentication:
    def historicalValidators(
        actual: DomainContext,
    ): Either[CoreFailure, Vector[Text]] =
      source.artifacts.historicalValidators(actual)
    def verifySignature(
        actual: DomainContext,
        signer: Text,
        preimage: Bytes,
        raw: Bytes,
    ): Either[CoreFailure, Unit] =
      source.artifacts.verifySignature(actual, signer, preimage, raw)
    def verifyFinalizedBase(
        actual: DomainContext,
        base: AdmissionBase,
    ): Either[CoreFailure, Unit] = Either.cond(
      actual == context && base == AdmissionBase
        .Finalized(checkpointBlock.toUInt256, anchor.height, anchor.stateRoot),
      (),
      CoreFailure.at(
        FailureCode.ProofInvalid,
        "configured inherited checkpoint identity",
      ),
    )
  private val fastRequests: Vector[ApplicationRequestVerifier[IO]] =
    fastSources.map { fixture =>
      ApplicationRequestVerifier.authenticated(
        manifest,
        fixture.inputs,
        fixture.declarations,
        fixture.creations,
        fixture.transactions,
        artifacts,
        fixture.scopes,
        fixture.repository(fixture.effect),
        proposals,
      )
    }
  private def forExecution(
      execution: org.sigilaris.core.application.protocol.ExecutionId,
  ): ApplicationRequestVerifier[IO] =
    fastSources
      .zip(fastRequests)
      .find(_._1.executionId == execution)
      .fold(consensusRequests)(_._2)
  val requests: ApplicationRequestVerifier[IO] =
    new ApplicationRequestVerifier[IO]:
      def verifyLock(
          subject: LockSubject,
          descriptor: InputDescriptor,
          signedTransaction: Bytes,
          proofs: Vector[ResolutionEvidence],
      ): Result[IO, VerifiedLockRequest] = forExecution(subject.executionId)
        .verifyLock(subject, descriptor, signedTransaction, proofs)
      def verifyEffect(
          subject: EffectSubject,
          owner: Owner,
          witness: ReservationWitness,
      ): Result[IO, VerifiedEffectRequest] =
        forExecution(subject.executionId).verifyEffect(subject, owner, witness)
      def verifyProposal(
          proposal: Proposal,
          plan: ExecutionPlan,
      ): Result[IO, VerifiedConsensusProposal] =
        consensusRequests.verifyProposal(proposal, plan)
      def verifyLockCertificate(
          certificate: LockCertificate,
      ): Result[IO, VerifiedLockCertificate] = forExecution(
        certificate.subject.executionId,
      ).verifyLockCertificate(certificate)
      def verifyEffectCertificate(
          certificate: EffectCertificate,
      ): Result[IO, VerifiedEffectCertificate] = forExecution(
        certificate.subject.executionId,
      ).verifyEffectCertificate(certificate)
  def fastRequest(index: Int): Result[IO, VerifiedLockRequest] =
    val fixture = fastSources(index)
    requests.verifyLock(
      fastSubject(index),
      fixture.descriptor,
      fixture.signedTransaction,
      fixture.proofs,
    )

  def payload(root: Hash): Result[IO, Bytes] =
    val state = if root == source.preStateRoot then Some(source.state)
    else Option.when(root == source.nextStateRoot)(source.nextState)
    EitherT.fromOption[IO](
      state.map(_.toVector.sortBy(_._1.toHex).foldLeft(ByteVector.empty) {
        case (bytes, (identity, amount)) =>
          bytes ++ identity.bytes ++ amount.toBytes
      }),
      V2RuntimeFailure.at(
        RuntimeFailureCode.ProofUnavailable,
        "complete retained state payload",
      ),
    )

  val states: ApplicationStateAuthentication[IO] =
    new ApplicationStateAuthentication[IO]:
      def authenticate(
          request: VerifiedConsensusProposal,
          bytes: Bytes,
      ): Result[IO, AuthenticatedApplicationState] = for
        independentlyExecuted <- proposals.executeSequential(
          request.proposal,
          request.plan,
        )
        expected <- payload(request.proposal.block.stateRoot.toUInt256)
        _        <- check(
          bytes == expected && hash(
            bytes,
          ) == request.proposal.block.stateRoot.toUInt256,
          "full canonical state reconstruction",
        )
        _ <- check(
          independentlyExecuted.entries.map(entry =>
            NormalizedApplicationResult.fromBytes(entry.normalizedResult).digest,
          ) == request.normalizedResults.map(bytes =>
            NormalizedApplicationResult.fromBytes(bytes).digest,
          ),
          "independent ordered state results",
        )
      yield AuthenticatedApplicationState(
        expected,
        hash(expected),
        independentlyExecuted.entries.map(_.normalizedResult),
      )

  val applications: ApplicationCommitVerifier[IO] = ApplicationCommitVerifier
    .authenticated(anchor, requests, validators, states)

  val publication: SafetyPublication[IO] = new SafetyPublication[IO]:
    def finalizedHeight(actual: DomainContext): Result[IO, Height] =
      check(actual == context, "finality context") *> EitherT(finalized.map {
        snapshot =>
          if snapshot.safetyFaults.nonEmpty then
            Left(
              V2RuntimeFailure.at(
                RuntimeFailureCode.ProofInvalid,
                "actual conflicting finality",
              ),
            )
          else
            Right(
              snapshot.bestFinalized.fold(anchor.height)(value =>
                if value.anchorHeight.toBigNat.toBigInt > anchor.height.toBigNat.toBigInt
                then height(value.anchorHeight.toBigNat.toBigInt.longValue)
                else anchor.height,
              ),
            )
      })
    def verifyEffectState(request: VerifiedEffectRequest): Result[IO, Unit] =
      unavailable("ordinary fixture does not publish fast effects")
    def verifyConsensusState(
        request: VerifiedConsensusProposal,
    ): Result[IO, Unit] =
      parent(request.proposal).flatMap(actual =>
        check(
          request.context == context && request.parentBlockId == actual.targetBlockId.toUInt256 && request.parentStateRoot == actual.block.stateRoot.toUInt256,
          "actual certified publication parent",
        ),
      )

object V2RuntimeMaterial:
  def genesis(source: V2RequestConformance.Fixture): Proposal =
    import V2RequestConformance.*
    val window =
      HotStuffWindow.unsafe(source.chain, 0L, 0L, source.validators.hash)
    val subject =
      QuorumCertificateSubject(window, ProposalId(uint(65)), BlockId(uint(66)))
    val qc = QuorumCertificate(
      subject,
      source.keys
        .take(3)
        .map((id, key) =>
          Vote
            .sign(
              UnsignedVote(
                window,
                ValidatorId.unsafe(id.asString),
                subject.proposalId,
              ),
              key,
            )
            .toOption
            .get,
        ),
    )
    val header = source.baseHeader.copy(
      parent = None,
      height = BlockHeight.unsafeFromLong(0L),
      bodyRoot = BlockBody
        .computeBodyRoot(BlockBody[Hash, Hash, Bytes](Set.empty))
        .toOption
        .get,
    )
    Proposal
      .sign(
        UnsignedProposal(
          window,
          source.validators.members.head.id,
          BlockHeader.computeId(header),
          header,
          ProposalTxSet.empty,
          qc,
        ),
        source.keys.head._2,
      )
      .toOption
      .get

  def initialHistory(source: V2RequestConformance.Fixture): Vector[Proposal] =
    val first = genesis(source)
    (1L to 4L).foldLeft(Vector(first)) { (previous, height) =>
      val parent = previous.last
      val window =
        HotStuffWindow.unsafe(source.chain, height, 0L, source.validators.hash)
      val qc = QuorumCertificate(
        QuorumCertificateSubject(
          parent.window,
          parent.proposalId,
          parent.targetBlockId,
        ),
        source.keys
          .take(3)
          .map((id, key) =>
            Vote
              .sign(
                UnsignedVote(
                  parent.window,
                  ValidatorId.unsafe(id.asString),
                  parent.proposalId,
                ),
                key,
              )
              .toOption
              .get,
          ),
      )
      val header = parent.block.copy(
        parent = Some(parent.targetBlockId),
        height = BlockHeight.unsafeFromLong(height),
      )
      previous :+ Proposal
        .sign(
          UnsignedProposal(
            window,
            source.validators.members.head.id,
            BlockHeader.computeId(header),
            header,
            ProposalTxSet.empty,
            qc,
          ),
          source.keys.head._2,
        )
        .toOption
        .get
    }

  def create(
      source: V2RequestConformance.Fixture,
      voter: ValidatorId,
  ): IO[V2RuntimeMaterial] = for
    retained  <- Ref.of[IO, Map[ProposalId, Proposal]](Map.empty)
    finalized <- Ref.of[IO, FinalizationTrackerSnapshot](
      FinalizationTrackerSnapshot.empty,
    )
    live <- Ref.of[IO, Option[HotStuffNodeRuntime[IO]]](None)
  yield new V2RuntimeMaterial(source, voter, retained, finalized, live)
