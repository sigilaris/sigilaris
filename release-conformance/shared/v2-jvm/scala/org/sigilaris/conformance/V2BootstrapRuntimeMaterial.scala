package org.sigilaris.conformance

import cats.data.EitherT
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import org.sigilaris.core.application.protocol.NormalizedApplicationResult
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.application.protocol.v2.V2Codecs.bytesEncoder
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.gossip.ChainId
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.node.jvm.runtime.block.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.given

/** The actual installed source payload is read and independently decoded on
  * every opening execution. No future proposal, vote, QC or finality is seeded.
  * The maintenance transaction authenticates the inherited cells and leaves
  * their state unchanged; its complete three-cell read witness is retained.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.OptionPartial",
    "org.wartremover.warts.Throw",
  ),
)
final class V2BootstrapRuntimeMaterial(
    val bootstrap: VerifiedBootstrap,
    val manifest: ProtocolManifest,
    val initial: BootstrapCertificate,
    val voter: ValidatorId,
    val retained: Ref[IO, Map[ProposalId, Proposal]],
    val live: Ref[IO, Option[HotStuffNodeRuntime[IO]]],
):
  import V2RequestConformance.*
  val genesis =
    InitialBootstrapConsensus.authenticate(bootstrap, initial).toOption.get
  val context    = genesis.genesis.context
  val chain      = ChainId.unsafe(context.chainId.asString)
  val members    = genesis.genesis.validators
  val validators = ValidatorSetLookup.static[IO](
    BootstrapTrustRoot.staticValidatorSet(members),
  )
  val anchor = ApplicationAnchor(
    context,
    genesis.genesis.blockId.toUInt256,
    height(0L),
    genesis.genesis.header.stateRoot.toUInt256,
  )
  val payload      = bootstrap.source.closure.statePayload
  val family       = manifest.families.head
  val familyDigest = value(InputManifest.digest(family))
  val config       = HistoricalProfileConfiguration(
    1L,
    Vector(
      HistoricalProfileRange(
        height(0L),
        None,
        context,
        HistoricalArtifactProfile(
          HistoricalApplicationRelease.ApplicationV2,
          2L,
          Some(manifest),
        ),
        bootstrap.digest,
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
  val base = AdmissionBase.InitialAnchor(
    anchor.blockId,
    height(0L),
    anchor.stateRoot,
    bootstrap.digest,
  )
  val cells  = ByteDecoder[Vector[Long]].decode(payload).toOption.get.value
  val fields = Vector(
    ResolvedField(
      Utf8("balance"),
      FieldRole.ExactMutate,
      cells(0).toBytes,
      Some(inputId(bytes("01"))),
      Some(ExactPrecondition(uint(1L), cells(0).toBytes)),
      Some(Authority.ConsensusOnly),
    ),
    ResolvedField(
      Utf8("counter"),
      FieldRole.ExactMutate,
      cells(1).toBytes,
      Some(inputId(bytes("02"))),
      Some(ExactPrecondition(uint(1L), cells(1).toBytes)),
      Some(Authority.ConsensusOnly),
    ),
    ResolvedField(
      Utf8("read"),
      FieldRole.ExactRead,
      cells.sum.toBytes,
      Some(inputId(bytes("03"))),
      Some(ExactPrecondition(uint(1L), cells.sum.toBytes)),
      Some(Authority.ConsensusOnly),
    ),
  )
  val descriptor = value(InputDerivation.derive(family, fields))
  val accesses   =
    fields
      .flatMap(_.stableId)
      .map(
        ActualAccess(_, AccessKind.ReadExisting, Some(Authority.ConsensusOnly)),
      )
  val footprint = value(
    Footprint.canonical(fields.flatMap(_.stableId), Vector.empty),
  )
  val envelope = OpeningEnvelope(
    1L,
    OpeningKind.InitialBootstrap,
    bootstrap.source.closure.source.domain,
    bootstrap.source.closure.source.checkpointHeight,
    anchor.stateRoot,
    bootstrap.source.closure.source.stateSchemaDigest,
    context,
    context.configurationDigest,
    anchor.blockId,
    height(1L),
    base,
    height(12L),
    payload,
    uint(992L),
    V2BootstrapFixture.authorityId,
  )
  val opening = SignedOpening(
    envelope,
    signed(
      V2BootstrapFixture.authority,
      value(OpeningEnvelope.signingPreimage(envelope)),
    ),
  )
  val signedTransaction = value(SignedOpening.codec.encode(opening))
  val txId              = hash(signedTransaction)
  val declaration       = Declaration.Compatibility(envelope.reasonDigest)
  val executionId       = value(
    ExecutionIdentity.compute(
      ExecutionIdentityInput(
        context,
        familyDigest,
        txId,
        signedTransaction,
        descriptor.fullInputCommitment,
        descriptor.lockSubsetCommitment,
        value(Declaration.digest(declaration)),
        uint(0L),
        envelope.lastInclusionHeight,
      ),
    ),
  )
  val statement = ClassificationStatement(
    2L,
    familyDigest,
    txId,
    1.toByte,
    anchor.stateRoot,
    value(Declaration.digest(declaration)),
    Vector.empty,
    Vector(ClassificationPurpose.MaintenanceOpening),
  )
  val entry = PlanEntry(
    executionId,
    PlanSource.ConsensusTransaction(txId, signedTransaction, None),
    familyDigest,
    declaration,
    value(Declaration.digest(declaration)),
    descriptor.fullInputCommitment,
    descriptor.lockSubsetCommitment,
    value(Footprint.actualCommitment(footprint)),
    uint(0L),
    envelope.lastInclusionHeight,
    anchor.stateRoot,
    Some(value(ClassificationStatement.commitment(statement))),
  )
  val plan = ExecutionPlan(
    2L,
    Vector(ExecutionWave(WaveKind.CompatibilitySingleton, Vector(entry))),
    Vector(statement),
  )
  val result = NormalizedApplicationResult.fromBytes(
    Utf8("initial-opened").toBytes ++ anchor.stateRoot.toBytes,
  )
  val proofs = fields.map(field =>
    ResolutionEvidence(
      field.fieldId,
      hash(value(ResolvedField.codec.encode(field)) ++ payload).bytes,
    ),
  )
  val authorization = new MaintenanceAuthorizationVerifier:
    def verify(
        selected: InputManifest,
        candidate: SignedOpening,
        preimage: Bytes,
    ): Either[CoreFailure, Unit] =
      Either.cond(
        selected == family && selected.maintenanceVerifierDigest.contains(
          uint(991L),
        ) && candidate == opening &&
          preimage == value(
            OpeningEnvelope.signingPreimage(envelope),
          ) && recovered(
            candidate.signature,
            preimage,
            V2BootstrapFixture.authority,
          ),
        (),
        CoreFailure.at(
          FailureCode.InvalidSignature,
          "actual installed maintenance authority",
        ),
      )
  def check(ok: Boolean, detail: String): Result[IO, Unit] = EitherT.cond[IO](
    ok,
    (),
    V2RuntimeFailure.at(RuntimeFailureCode.ProofInvalid, detail),
  )
  private def core[A](v: Either[CoreFailure, A]): Result[IO, A] =
    EitherT.fromEither[IO](v.leftMap(V2RuntimeFailure.fromCore))
  def all: IO[Map[ProposalId, Proposal]] = for
    archive  <- retained.get
    node     <- live.get
    snapshot <- node.flatMap(_.inMemorySink).traverse(_.snapshot)
  yield archive ++ snapshot.fold(Map.empty[ProposalId, Proposal])(_.proposals)
  def finalization: IO[FinalizationTrackerSnapshot] =
    all.map(rows => HotStuffFinalizationTracker.track(rows.values.toVector))
  def parent(proposal: Proposal): Result[IO, BlockHeader] =
    if proposal.block.parent.contains(genesis.genesis.blockId) then
      check(
        InitialBootstrapConsensus
          .authenticate(
            bootstrap,
            initial.copy(quorum =
              ByteEncoder[QuorumCertificate].encode(proposal.justify),
            ),
          )
          .isRight && proposal.window.height.toBigNat.toBigInt == 1 &&
          proposal.window.chainId == chain,
        "actual initial G/QC first-height relation",
      ).as(genesis.genesis.header)
    else
      for
        rows     <- EitherT.liftF(all)
        previous <- EitherT.fromOption[IO](
          rows.get(proposal.justify.subject.proposalId),
          V2RuntimeFailure.at(
            RuntimeFailureCode.ProofUnavailable,
            "actual certified parent unavailable",
          ),
        )
        _ <- check(
          HotStuffValidator.validateProposal(previous, members).isRight &&
            HotStuffValidator
              .validateQuorumCertificate(proposal.justify, members)
              .isRight &&
            previous.targetBlockId == proposal.justify.subject.blockId && previous.window == proposal.justify.subject.window &&
            proposal.block.parent.contains(
              previous.targetBlockId,
            ) && proposal.block.height.toBigNat.toBigInt == previous.block.height.toBigNat.toBigInt + 1,
          "actual retained ordinary parent/QC relation",
        )
      yield previous.block
  def selected(proposal: Proposal): ExecutionPlan =
    if proposal.block.height.toBigNat.toBigInt == 1 then plan
    else ExecutionPlan.empty
  def owner(proposal: Proposal, current: ExecutionPlan): Owner = Owner(
    context,
    executionId,
    Scope(
      ScopeKind.CompatibilitySingleton,
      proposal.block.parent.get.toUInt256,
      height(proposal.window.height.toBigNat.toBigInt.longValue),
      value(ExecutionPlan.computeRoot(current)).toUInt256,
      0L,
      hash(proposal.justify.toBytes ++ value(PlanEntry.codec.encode(entry))),
    ),
  )
  val inputs = new InputAuthentication:
    def verify(
        selected: InputManifest,
        raw: Bytes,
        root: Hash,
        field: ResolvedField,
        proof: ResolutionEvidence,
    ): Either[CoreFailure, Unit] =
      Either.cond(
        selected == family && raw == signedTransaction && root == hash(
          payload,
        ) && root == anchor.stateRoot &&
          fields.contains(field) && proofs.contains(
            proof,
          ) && proof.fieldId == field.fieldId &&
          authorization
            .verify(
              family,
              opening,
              value(OpeningEnvelope.signingPreimage(envelope)),
            )
            .isRight,
        (),
        CoreFailure.at(
          FailureCode.ProofInvalid,
          "installed signed state/input proof",
        ),
      )
    def verifyAbsentCreation(
        selected: InputManifest,
        raw: Bytes,
        root: Hash,
        id: InputId,
        proof: Bytes,
    ): Either[CoreFailure, Unit] =
      Left(
        CoreFailure.at(
          FailureCode.ProofInvalid,
          "opening creates no absent cells",
        ),
      )
  val declarations = new DeclaredFootprintAuthentication:
    def derive(
        selected: InputManifest,
        raw: Bytes,
        root: Hash,
        input: InputDescriptor,
    ): Either[CoreFailure, Option[Footprint]] =
      Either.cond(
        selected == family && raw == signedTransaction && root == anchor.stateRoot && input == descriptor,
        None,
        CoreFailure.at(FailureCode.ProofInvalid, "signed opening declaration"),
      )
  val creations = new DeclaredCreationAuthentication:
    def derive(
        selected: InputManifest,
        raw: Bytes,
        root: Hash,
        input: InputDescriptor,
    ): Either[CoreFailure, Vector[(InputId, Bytes)]] =
      declarations.derive(selected, raw, root, input).as(Vector.empty)
  val transactions = new TransactionAuthentication[IO]:
    def authenticate(
        actual: DomainContext,
        selected: InputManifest,
        raw: Bytes,
    ): Result[IO, SignedApplicationBinding] = for
      decoded <- core(SignedOpening.codec.decode(raw))
      _       <- check(
        bootstrap.source.closure.source.replayPolicyDigest == V2BootstrapFixture.ReplayPolicyDigest &&
          actual == context && selected == family && decoded == opening,
        "actual signed opening binding",
      )
      _ <- core(
        OpeningValidation
          .validate(decoded, entry, descriptor, manifest, family, authorization),
      )
    yield SignedApplicationBinding(
      txId,
      familyDigest,
      declaration,
      uint(0L),
      envelope.lastInclusionHeight,
      None,
    )
  val artifacts = new ArtifactAuthentication:
    def historicalValidators(
        actual: DomainContext,
    ): Either[CoreFailure, Vector[Text]] =
      Either.cond(
        actual == context,
        members.members.map(m => Utf8(m.id.value)),
        CoreFailure.at(
          FailureCode.ManifestMismatch,
          "initial validator context",
        ),
      )
    def verifySignature(
        actual: DomainContext,
        signer: Text,
        preimage: Bytes,
        raw: Bytes,
    ): Either[CoreFailure, Unit] =
      Either.cond(
        actual == context && members
          .member(ValidatorId.unsafe(signer.asString))
          .exists(member =>
            signature(raw)
              .flatMap(sig =>
                org.sigilaris.core.crypto.CryptoOps
                  .recover(
                    sig,
                    org.sigilaris.core.crypto.CryptoOps
                      .keccak256(preimage.toArray),
                  )
                  .toOption,
              )
              .contains(member.publicKey),
          ),
        (),
        CoreFailure.at(FailureCode.InvalidSignature, "actual target validator"),
      )
    def verifyFinalizedBase(
        actual: DomainContext,
        supplied: AdmissionBase,
    ): Either[CoreFailure, Unit] =
      Either.cond(
        actual == context && supplied == base && InitialBootstrapConsensus
          .authenticate(bootstrap, initial)
          .isRight,
        (),
        CoreFailure.at(FailureCode.ProofInvalid, "actual initial anchor quorum"),
      )
  val sources = new ApplicationExecutionRepository[IO]:
    def lockSource(
        actual: DomainContext,
        id: org.sigilaris.core.application.protocol.ExecutionId,
    ): Result[IO, LockSourceMaterial] = unavailable("lock-free opening")
    def lockCertificate(id: Hash): Result[IO, LockCertificate] = unavailable(
      "initial opening has no lock certificate",
    )
    def effectCertificate(id: Hash): Result[IO, EffectCertificate] =
      unavailable("initial opening has no effect certificate")
    def executeEffect(
        actual: DomainContext,
        id: org.sigilaris.core.application.protocol.ExecutionId,
    ): Result[IO, ExecutedEffect] = unavailable(
      "initial opening is consensus only",
    )
    def verifyExactBinding(
        actual: DomainContext,
        id: org.sigilaris.core.application.protocol.ExecutionId,
        binding: Option[Hash],
    ): Result[IO, Unit] =
      check(
        actual == context && id == executionId && binding.isEmpty,
        "initial generic opening binding",
      )
  val scopes = new ReservationScopeAuthentication[IO]:
    def verifyFast(
        value: Owner,
        base: AdmissionBase,
        source: SignedApplicationBinding,
    ): Result[IO, Unit] = unavailable("initial opening cannot fast vote")
    def verifyConsensus(
        value: Owner,
        proposal: Proposal,
        current: ExecutionPlan,
        observed: PlanEntry,
    ): Result[IO, Unit] =
      parent(proposal) *> check(
        value == owner(
          proposal,
          current,
        ) && current == plan && observed == entry,
        "actual initial branch/entry authorization",
      )
  val proposals = new ProposalExecutionRepository[IO]:
    def historicalValidators(window: HotStuffWindow): Result[IO, ValidatorSet] =
      check(
        profiles.at(window).isRight,
        "pinned initial domain validator profile",
      ).as(members)
    def executeSequential(
        proposal: Proposal,
        current: ExecutionPlan,
    ): Result[IO, ExecutedProposal] = for
      previous <- parent(proposal)
      _        <- check(
        HotStuffValidator
          .validateProposal(proposal, members)
          .isRight && current == selected(proposal) &&
          previous.stateRoot.toUInt256 == anchor.stateRoot && proposal.block.stateRoot.toUInt256 == anchor.stateRoot,
        "actual unchanged installed state/selected execution plan",
      )
      _ <-
        if current.waves.nonEmpty then
          transactions.authenticate(context, family, signedTransaction).void
        else EitherT.pure[IO, V2RuntimeFailure](())
      rows =
        if current.waves.nonEmpty then
          Vector(
            ExecutedApplication(
              entry,
              descriptor,
              proofs,
              accesses,
              Vector.empty,
              result.bytes,
              anchor.stateRoot,
              owner(proposal, current),
              Some(statement),
            ),
          )
        else Vector.empty
      body = BlockBody[Hash, Hash, Bytes](
        rows
          .map(row =>
            BlockRecord(
              row.entry.source.txId,
              Some(
                NormalizedApplicationResult
                  .fromBytes(row.normalizedResult)
                  .digest
                  .toUInt256,
              ),
              Vector.empty[Bytes],
            ),
          )
          .toSet,
      )
      bodyRoot <- EitherT.fromEither[IO](
        BlockBody
          .computeBodyRoot(body)
          .leftMap(e =>
            V2RuntimeFailure.at(RuntimeFailureCode.ProofInvalid, e.reason),
          ),
      )
    yield ExecutedProposal(
      proposal,
      UnsignedVote(proposal.window, voter, proposal.proposalId),
      previous.stateRoot.toUInt256,
      rows.map(row => BodyMember(row.entry.source.txId, row.entry.executionId)),
      bodyRoot.toUInt256,
      rows,
    )
  val requests = ApplicationRequestVerifier.authenticated(
    manifest,
    inputs,
    declarations,
    creations,
    transactions,
    artifacts,
    scopes,
    sources,
    proposals,
  )
  val states = new ApplicationStateAuthentication[IO]:
    def authenticate(
        request: VerifiedConsensusProposal,
        supplied: Bytes,
    ): Result[IO, AuthenticatedApplicationState] = for
      decoded <- EitherT.fromEither[IO](
        ByteDecoder[Vector[Long]]
          .decode(supplied)
          .leftMap(e =>
            V2RuntimeFailure.at(RuntimeFailureCode.ProofInvalid, e.msg),
          ),
      )
      _ <- check(
        decoded.remainder.isEmpty && decoded.value == cells && supplied == payload && hash(
          supplied,
        ) == request.validatedStateRoot,
        "actual installed state payload readback/reconstruction",
      )
      executed <- proposals.executeSequential(request.proposal, request.plan)
      _        <- check(
        executed.entries.map(_.normalizedResult) == request.normalizedResults,
        "actual canonical opening normalized results",
      )
    yield AuthenticatedApplicationState(
      supplied,
      hash(supplied),
      request.normalizedResults,
    )
  val applications = ApplicationCommitVerifier.authenticated(
    anchor,
    requests,
    validators,
    states,
  )
  val publication = new SafetyPublication[IO]:
    def finalizedHeight(actual: DomainContext): Result[IO, Height] = for
      _       <- check(actual == context, "actual finalized context")
      current <- EitherT.liftF(finalization)
      _       <- check(
        current.safetyFaults.isEmpty,
        "actual conflicting initial finality",
      )
    yield current.bestFinalized.fold(height(0L))(v =>
      height(v.anchorHeight.toBigNat.toBigInt.longValue),
    )
    def verifyEffectState(request: VerifiedEffectRequest): Result[IO, Unit] =
      unavailable("initial opening has no fast effects")
    def verifyConsensusState(
        request: VerifiedConsensusProposal,
    ): Result[IO, Unit] = parent(request.proposal).flatMap(value =>
      check(
        request.context == context && request.parentStateRoot == value.stateRoot.toUInt256 && request.parentBlockId == request.proposal.block.parent.get.toUInt256,
        "actual certified initial/ordinary parent",
      ),
    )
  val schedule = new HotStuffApplicationProfileSchedule[IO]:
    def at(
        window: HotStuffWindow,
        parent: Option[BlockId],
    ): Result[IO, HotStuffHistoricalApplicationProfile] =
      EitherT
        .fromEither[IO](profiles.at(window))
        .as(HotStuffHistoricalApplicationProfile.ApplicationV2(manifest))
  def history: FinalizedApplicationHistory[IO] =
    new FinalizedApplicationHistory[IO]:
      def anchorAncestor(
          anchor: ApplicationAnchor,
          target: FinalizedAnchorSuggestion,
      ): Result[IO, VerifiedInitialAnchorAncestor] = unavailable(
        "initial G has no earlier ordinary finality",
      )
      def latestFinalized(
          actual: DomainContext,
      ): Result[IO, Option[FinalizedAnchorSuggestion]] = for
        _        <- check(actual == context, "initial finality history context")
        observed <- EitherT.liftF(finalization)
        _        <- check(
          observed.safetyFaults.isEmpty,
          "actual initial finality conflict",
        )
      yield observed.bestFinalized
      def retained(
          actual: DomainContext,
          id: Hash,
      ): Result[IO, Option[FinalizedApplicationMaterial]] = for
        _   <- check(actual == context, "retained initial finality context")
        all <- EitherT.liftF(V2BootstrapRuntimeMaterial.this.all)
        proofs = all.values.toVector.flatMap(proposal =>
          all.values.toVector
            .filter(_.justify.subject.proposalId == proposal.proposalId)
            .flatMap(child =>
              all.values.toVector
                .filter(_.justify.subject.proposalId == child.proposalId)
                .map(grandchild =>
                  FinalizedAnchorSuggestion(
                    proposal,
                    FinalizedProof(child, grandchild),
                  ),
                ),
            ),
        )
        proof <- EitherT.fromOption[IO](
          proofs.find(_.anchorBlockId.toUInt256 == id),
          V2RuntimeFailure.at(
            RuntimeFailureCode.ProofUnavailable,
            "actual initial three-chain unavailable",
          ),
        )
        verified <- EitherT(
          HotStuffFinalizedAnchorVerifier
            .verify(proof, validators)
            .map(
              _.leftMap(e =>
                V2RuntimeFailure.at(RuntimeFailureCode.ProofInvalid, e.reason),
              ),
            ),
        )
      yield Some(
        FinalizedApplicationMaterial(
          verified,
          selected(verified.proposal),
          payload,
        ),
      )
      def backfill(
          actual: DomainContext,
          id: Hash,
      ): Result[IO, Option[FinalizedApplicationMaterial]] = retained(actual, id)
  def parentRepository(
      initial: VerifiedInitialParent,
  ): HotStuffProposalParentRepository[IO] =
    HotStuffProposalParentRepository.initial(
      initial,
      new HotStuffProposalParentRepository[IO]:
        def parent(
            request: HotStuffProposalInputRequest,
        ): Result[IO, Proposal] = EitherT(
          all.map(
            _.get(request.justify.subject.proposalId)
              .toRight(
                V2RuntimeFailure.at(
                  RuntimeFailureCode.ProofUnavailable,
                  "ordinary initial-domain parent",
                ),
              ),
          ),
        ),
    )
  val execution = new HotStuffApplicationCandidateExecution[IO]:
    def execute(
        request: HotStuffProposalInputRequest,
        selected: ProtocolManifest,
        parent: Proposal,
    ): Result[IO, Option[HotStuffExecutedApplicationCandidate]] =
      for
        current <- EitherT.liftF(finalization)
        _       <- check(
          current.bestFinalized.nonEmpty || (request.branchContext.complete && request.branchContext.bestFinalizedBlockId.isEmpty),
          "installed G terminates actual ancestry without inventing finalized status",
        )
        _ <- check(
          selected == manifest && request.height.toBigNat.toBigInt > 1 && parent.block.stateRoot.toUInt256 == anchor.stateRoot,
          "ordinary initial-domain unchanged working state",
        )
      yield None
    override def executeInitial(
        request: HotStuffProposalInputRequest,
        selected: ProtocolManifest,
        parent: VerifiedInitialParent,
    )(using
        cats.Monad[IO],
    ): Result[IO, Option[HotStuffExecutedApplicationCandidate]] =
      check(
        selected == manifest && parent.context == context && parent.statePayload == payload && parent.genesis.stateRoot.toUInt256 == hash(
          payload,
        ),
        "installed original source payload consumed by opening executor",
      )
        .as(
          Some(
            HotStuffExecutedApplicationCandidate(
              plan,
              Vector(result.bytes),
              Vector(anchor.stateRoot),
            ),
          ),
        )
  val controllerArtifacts = new HotStuffControllerArtifacts:
    def proposal(subject: QuorumCertificateSubject): Result[IO, Proposal] =
      EitherT(
        all.map(
          _.get(subject.proposalId)
            .filter(_.targetBlockId == subject.blockId)
            .toRight(
              V2RuntimeFailure.at(
                RuntimeFailureCode.ProofUnavailable,
                "actual controlled proposal parent",
              ),
            ),
        ),
      )
    def quorum(
        subject: QuorumCertificateSubject,
    ): Result[IO, QuorumCertificate] =
      if subject == genesis.quorum.subject then EitherT.pure(genesis.quorum)
      else
        EitherT(
          all.map(
            _.valuesIterator
              .map(_.justify)
              .find(_.subject == subject)
              .toRight(
                V2RuntimeFailure.at(
                  RuntimeFailureCode.ProofUnavailable,
                  "actual controlled highest QC",
                ),
              ),
          ),
        )
