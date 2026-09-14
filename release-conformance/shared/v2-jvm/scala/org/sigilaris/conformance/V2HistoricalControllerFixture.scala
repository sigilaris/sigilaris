package org.sigilaris.conformance

import cats.data.EitherT
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.InclusionHeight
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.application.protocol.v2.V2Codecs.given
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.crypto.CryptoOps
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.node.jvm.runtime.block.BlockHeader
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*

/** Actual independently owned original key controllers. The complete immutable
  * birth inventory contains only real genesis signatures; later votes require
  * the separate forced original safety record before key use. This fixture does
  * not reconstruct that history at the transition boundary.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.OptionPartial",
    "org.wartremover.warts.Throw",
  ),
)
object V2HistoricalControllerFixture:
  import V2RequestConformance.*
  import V2HistoricalFixture.{accepted, check, core}

  final case class Authorized(payload: Bytes, signature: Bytes)
  object Authorized:
    given ByteEncoder[Authorized] = ByteEncoder.derived
    given ByteDecoder[Authorized] = ByteDecoder.derived
    val codec                     =
      CanonicalCodec.derived[Authorized](_ => Right[CoreFailure, Unit](()))
  final case class Birth(
      history: ControllerInitialHistory,
      issuancePolicy: HistoricalIssuancePolicy,
  )
  object Birth:
    given ByteEncoder[Birth] = ByteEncoder.derived
    given ByteDecoder[Birth] = ByteDecoder.derived
    val codec = CanonicalCodec.derived[Birth](_ => Right[CoreFailure, Unit](()))
  val AuthorityKey = CryptoOps.fromPrivate(BigInt(1717))
  val SeedDomain   = Utf8("neutral.historical-controller.birth.v1")
  val ScopeDigest  = uint(1720)
  val Layout: ConsistencyLayoutRecord = ConsistencyLayoutRecord(
    1L,
    Vector(
      "canonical-state" -> Vector(
        ConsistencyRole.ApplicationState,
        ConsistencyRole.ApplicationResults,
        ConsistencyRole.ApplicationReplay,
      ),
      "original-blocks"    -> Vector(ConsistencyRole.Blocks),
      "original-safety"    -> Vector(ConsistencyRole.ConsensusSafety),
      "application-safety" -> Vector(ConsistencyRole.ApplicationSafety),
      "exact-admission"    -> Vector(ConsistencyRole.ExactAdmission),
      "exact-idempotency"  -> Vector(ConsistencyRole.ExactIdempotency),
      "generic-pipelines"  -> Vector(ConsistencyRole.GenericPipelines),
      "configuration"      -> Vector(ConsistencyRole.Configuration),
      "controller"         -> Vector(ConsistencyRole.FenceHistory),
      "fixed-evidence"     -> Vector(ConsistencyRole.FixedEvidence),
    ).map((name, roles) =>
      ConsistencyNamespace(Utf8(name), Utf8(name), 1L, roles),
    ).sortBy(value => V2Validation.textKey(value.name)),
  )
  val SchemaDigest = value(ConsistencyLayoutRecord.digest(Layout))
  val PolicyDigest = uint(1722)
  def transition(material: V2HistoricalFixture.Material): TransitionIntent =
    TransitionIntent(
      1L,
      TransitionKind.Handover,
      material.old.chainId,
      material.target.chainId,
      material.target.configurationDigest,
      material.target.validatorSetHash,
      Some(height(6L)),
      ScopeDigest,
      SchemaDigest,
      PolicyDigest,
    )
  def unsigned(proposal: Proposal): UnsignedProposal = UnsignedProposal(
    proposal.window,
    proposal.proposer,
    proposal.targetBlockId,
    proposal.block,
    proposal.txSet,
    proposal.justify,
  )

  final case class Node(
      index: Int,
      controller: FileFenceController,
      safety: HistoricalConsensusSafetyStore,
      signer: V2HistoricalFixture.Signer,
      originalBirth: Bytes,
      lifecycle: ConsistencyMutationGate,
      target: TargetActivation,
      issuance: HistoricalIssuanceSafetyStore,
      issuanceInstallation: V2HistoricalIssuanceFixture.Installation,
  )

  final class TargetActivation private[V2HistoricalControllerFixture] (
      install: (
          VerifiedActiveGroup,
          JournalSafetyStore[IO],
          FinalizedApplicationRuntime[IO],
          SafetyPublication[IO],
          ApplicationRequestVerifier[IO],
      ) => Result[IO, HotStuffControlledSigning[IO]],
  ):
    def activate(
        active: VerifiedActiveGroup,
        safety: JournalSafetyStore[IO],
        finalizer: FinalizedApplicationRuntime[IO],
        publication: SafetyPublication[IO],
        requests: ApplicationRequestVerifier[IO],
    ): Result[IO, HotStuffControlledSigning[IO]] =
      install(active, safety, finalizer, publication, requests)

  trait OriginalCanonicalPublication:
    def authenticateWrite(request: Bytes): Result[IO, ControllerWriteMaterial]
    def writer: ControllerCanonicalWriter
  val noPublication: OriginalCanonicalPublication =
    new OriginalCanonicalPublication:
      def authenticateWrite(
          request: Bytes,
      ): Result[IO, ControllerWriteMaterial] = unavailable(
        "original canonical publication is not configured",
      )
      val writer: ControllerCanonicalWriter =
        ControllerCanonicalWriter.unavailable

  def node(
      material: V2HistoricalFixture.Material,
      index: Int,
      faults: ControllerFaultInjector,
  ): Resource[IO, Node] = node(material, index, faults, noPublication)
  def node(
      material: V2HistoricalFixture.Material,
      index: Int,
      faults: ControllerFaultInjector,
      publication: OriginalCanonicalPublication,
  ): Resource[IO, Node] = node(material, index, faults, publication, None)
  def node(
      material: V2HistoricalFixture.Material,
      index: Int,
      faults: ControllerFaultInjector,
      publication: OriginalCanonicalPublication,
      originalApplication: Option[ControllerApplicationSigning],
  ): Resource[IO, Node] = node(
    material,
    index,
    faults,
    publication,
    originalApplication,
    IssuanceCapability.ContinuouslyDisabled,
  )

  def node(
      material: V2HistoricalFixture.Material,
      index: Int,
      faults: ControllerFaultInjector,
      publication: OriginalCanonicalPublication,
      originalApplication: Option[ControllerApplicationSigning],
      lockIssuance: IssuanceCapability,
  ): Resource[IO, Node] =
    Resource
      .eval(
        V2HistoricalIssuanceFixture.installation(material, index, lockIssuance),
      )
      .flatMap(installation =>
        installedNode(
          material,
          index,
          faults,
          publication,
          originalApplication,
          installation,
        ),
      )

  private def installedNode(
      material: V2HistoricalFixture.Material,
      index: Int,
      faults: ControllerFaultInjector,
      publication: OriginalCanonicalPublication,
      originalApplication: Option[ControllerApplicationSigning],
      installation: V2HistoricalIssuanceFixture.Installation,
  ): Resource[IO, Node] =
    val (id, key)     = material.source.keys(index)
    val voter         = ValidatorId.unsafe(id.asString)
    val sourceContext = material.old
    val birthVotes    =
      (material.genesis.justify.votes ++ material.initialQc.votes)
        .filter(_.voter == voter)
        .map { vote =>
          val signBytes = Vote.signBytes(
            UnsignedVote(vote.window, vote.voter, vote.targetProposalId),
          )
          val request = ByteVector(112.toByte) ++ signBytes
          ControllerObservedSignature(
            ControllerSigningIntent(
              request,
              ControllerSigningMaterial(
                sourceContext,
                ControllerSigningKind.Consensus,
                Some(height(0L)),
                signBytes,
              ),
            ),
            signatureBytes(vote.signature),
          )
        }
    val birthProposal = Option
      .when(material.genesis.proposer == voter) {
        val bytes =
          value(HotStuffControllerRequests.proposal(unsigned(material.genesis)))
        ControllerObservedSignature(
          ControllerSigningIntent(
            bytes,
            ControllerSigningMaterial(
              sourceContext,
              ControllerSigningKind.Consensus,
              Some(height(0L)),
              Proposal.signBytes(unsigned(material.genesis)),
            ),
          ),
          signatureBytes(material.genesis.signature),
        )
      }
      .toVector
    val birth = ControllerInitialHistory(
      Vector(sourceContext),
      birthVotes ++ birthProposal,
      Vector.empty,
      Vector.empty,
      Vector.empty,
    )
    val birthBytes = value(
      Birth.codec.encode(Birth(birth, installation.policy)),
    )
    val signedBirth = value(
      Authorized.codec.encode(
        Authorized(
          birthBytes,
          signed(AuthorityKey, Commitment.preimage(SeedDomain, birthBytes)),
        ),
      ),
    )
    val profile = HistoricalSafetyProfile(
      1L,
      sourceContext,
      id,
      material.initialQc,
      material.initialQc,
      Commitment.hash(SeedDomain, signedBirth),
    )
    HistoricalIssuanceSafetyStore
      .resource(
        material.root
          .resolve("node-" + index.toString)
          .resolve("application-safety"),
        installation.policy,
        installation.authentication,
        16777216L,
        JournalFaultInjector.none[IO],
      )
      .flatMap { issuance =>
        HistoricalConsensusSafetyStore
          .resource(
            material.root
              .resolve("node-" + index.toString)
              .resolve("original-safety"),
            profile,
            material.consensus,
            material.history,
            material.validators,
            16777216L,
            JournalFaultInjector.none[IO],
          )
          .evalMap(safety =>
            Ref
              .of[IO, Option[HotStuffControllerAuthentication]](None)
              .map(safety -> _),
          )
          .flatMap { (safety, targetAuthentication) =>
            def configured(
                application: ControllerApplicationSigning,
            ): HotStuffControllerAuthentication =
              HotStuffControllerAuthentication.authenticated(
                material.profiles,
                material.validators,
                new HotStuffControllerArtifacts:
                  def proposal(
                      subject: QuorumCertificateSubject,
                  ): Result[IO, Proposal] =
                    EitherT
                      .liftF(material.retained.get)
                      .flatMap(values =>
                        EitherT.fromOption[IO](
                          values.get(subject.proposalId),
                          V2RuntimeFailure.at(
                            RuntimeFailureCode.ProofUnavailable,
                            "actual controller parent proposal unavailable",
                          ),
                        ),
                      )
                  def quorum(
                      subject: QuorumCertificateSubject,
                  ): Result[IO, QuorumCertificate] =
                    EitherT
                      .liftF(material.certificates.get)
                      .flatMap(values =>
                        EitherT.fromOption[IO](
                          values
                            .get(subject.proposalId)
                            .filter(_.subject == subject),
                          V2RuntimeFailure.at(
                            RuntimeFailureCode.ProofUnavailable,
                            "actual controller parent quorum unavailable",
                          ),
                        ),
                      )
                ,
                HistoricalControlledVoting.authentication(Map(voter -> safety)),
                application,
                None,
                None,
              )
            def isTarget(request: Bytes): Boolean =
              HotStuffControllerRequest.codec.decode(request).toOption.exists {
                envelope =>
                  envelope.kind match
                    case HotStuffControllerKind.Proposal =>
                      HotStuffControllerRequests.proposalCodec
                        .decode(envelope.payload)
                        .toOption
                        .exists(_.window.height.toBigNat.toBigInt >= 6)
                    case HotStuffControllerKind.Application |
                        HotStuffControllerKind.Timeout |
                        HotStuffControllerKind.NewView =>
                      true
                    case HotStuffControllerKind.Vote => false
              }
            def target: Result[IO, HotStuffControllerAuthentication] =
              EitherT
                .liftF(targetAuthentication.get)
                .flatMap(value =>
                  EitherT.fromOption[IO](
                    value,
                    V2RuntimeFailure.at(
                      RuntimeFailureCode.RecoveryRequired,
                      "target signing authority has not been installed",
                    ),
                  ),
                )
            val authentication = new ControllerOperationAuthentication:
              def initialHistory(
                  evidence: Bytes,
                  signerId: Text,
                  publicKey: Bytes,
              ): Result[IO, ControllerInitialHistory] = for
                original <- core(Authorized.codec.decode(evidence))
                decoded  <- core(
                  Birth.codec.decode(original.payload),
                )
                _ <- check(
                  signerId == id && publicKey == key.publicKey.toBytes && recovered(
                    original.signature,
                    Commitment.preimage(SeedDomain, original.payload),
                    AuthorityKey,
                  ) && decoded.history == birth && decoded.issuancePolicy == installation.policy,
                  "independently installed complete original birth key-use proof",
                )
                _ <- installation.authentication.policy(decoded.issuancePolicy)
              yield decoded.history
              private def originalSigning(
                  request: Bytes,
                  signerId: Text,
                  publicKey: Bytes,
              ): Result[IO, ControllerSigningMaterial] =
                birth.signatures.find(_.intent.request == request) match
                  case Some(original) =>
                    check(
                      signerId == id && publicKey == key.publicKey.toBytes && recovered(
                        original.signature,
                        original.intent.material.canonicalPreimage,
                        key,
                      ),
                      "original genesis key-use inventory",
                    ).as(original.intent.material)
                  case None =>
                    for
                      _ <- check(
                        signerId == id && publicKey == key.publicKey.toBytes,
                        "actual historical controller key identity",
                      )
                      envelope <- core(
                        HotStuffControllerRequest.codec.decode(request),
                      )
                      material <- envelope.kind match
                        case HotStuffControllerKind.Vote =>
                          for
                            vote <- core(
                              ControllerConsensusVote.codec
                                .decode(envelope.payload),
                            )
                            _ <- check(
                              vote.voter == voter,
                              "original voter differs from controller",
                            )
                            material <- safety.authenticateProposal(
                              vote.proposal,
                              signerId,
                              publicKey,
                            )
                          yield material
                        case HotStuffControllerKind.Proposal =>
                          for
                            proposal <- core(
                              HotStuffControllerRequests.proposalCodec
                                .decode(envelope.payload),
                            )
                            _ <- check(
                              proposal.proposer == voter && proposal.window.height.toBigNat.toBigInt > 0 && proposal.window.height.toBigNat.toBigInt < 6 && proposal.targetBlockId == BlockHeader
                                .computeId(proposal.block),
                              "original proposal signer, height or header",
                            )
                            selected <- EitherT.fromEither[IO](
                              material.profiles.at(proposal.window),
                            )
                            _ <- check(
                              selected.context == sourceContext,
                              "proposal signing selected another historical context",
                            )
                            parent <- EitherT(
                              material.retained.get.map(
                                _.get(proposal.justify.subject.proposalId)
                                  .toRight(
                                    V2RuntimeFailure.at(
                                      RuntimeFailureCode.ProofUnavailable,
                                      "actual old parent missing",
                                    ),
                                  ),
                              ),
                            )
                            _ <- check(
                              parent.targetBlockId == proposal.justify.subject.blockId && parent.window == proposal.justify.subject.window && proposal.block.parent
                                .contains(
                                  parent.targetBlockId,
                                ) && proposal.block.height.toBigNat.toBigInt == parent.block.height.toBigNat.toBigInt + 1 && HotStuffValidator
                                .validateQuorumCertificate(
                                  proposal.justify,
                                  material.source.validators,
                                )
                                .isRight,
                              "actual old proposal parent and quorum",
                            )
                            trial = Proposal(
                              ProposalId(uint(0)),
                              proposal.window,
                              proposal.proposer,
                              proposal.targetBlockId,
                              proposal.block,
                              proposal.txSet,
                              proposal.justify,
                              material.genesis.signature,
                            )
                            replay <- material.replay.replay(
                              selected,
                              trial,
                              parent,
                            )
                            _ <- check(
                              replay.priorStateRoot == parent.block.stateRoot.toUInt256 && replay.nextStateRoot == proposal.block.stateRoot.toUInt256 && replay.bodyRoot == proposal.block.bodyRoot.toUInt256 && replay.executionPlanRoot == proposal.block.executionPlanRoot,
                              "original unsigned proposal full source execution",
                            )
                          yield ControllerSigningMaterial(
                            sourceContext,
                            ControllerSigningKind.Consensus,
                            Some(
                              InclusionHeight(proposal.window.height.toBigNat),
                            ),
                            Proposal.signBytes(proposal),
                          )
                        case _ =>
                          unavailable(
                            "this original deployment enables only proposal and durable vote signing",
                          )
                    yield material
              def signing(
                  request: Bytes,
                  signerId: Text,
                  publicKey: Bytes,
              ): Result[IO, ControllerSigningMaterial] =
                if HistoricalIssuanceRequest.codec.decode(request).isRight then
                  issuance.authenticateLock(request, signerId, publicKey)
                else if isTarget(request) then
                  target.flatMap(_.signing(request, signerId, publicKey))
                else originalSigning(request, signerId, publicKey)
              def authorizeSigning(
                  request: Bytes,
                  signerId: Text,
                  publicKey: Bytes,
              ): Result[IO, Unit] =
                if HistoricalIssuanceRequest.codec.decode(request).isRight then
                  issuance.authorizeLock(request, signerId, publicKey)
                else if isTarget(request) then
                  target.flatMap(
                    _.authorizeSigning(request, signerId, publicKey),
                  )
                else authorizeOriginal(request, signerId, publicKey)
              private def authorizeOriginal(
                  request: Bytes,
                  signerId: Text,
                  publicKey: Bytes,
              ): Result[IO, Unit] = for
                value <- signing(request, signerId, publicKey)
                _     <- check(
                  value.height.exists(_.toBigNat.toBigInt > 0),
                  "genesis birth signatures cannot be reissued by the live controller",
                )
                _ <- HotStuffControllerRequest.codec.decode(request) match
                  case Right(envelope)
                      if envelope.kind == HotStuffControllerKind.Vote =>
                    core(ControllerConsensusVote.codec.decode(envelope.payload))
                      .flatMap(vote =>
                        safety
                          .authorizeProposal(vote.proposal, signerId, publicKey),
                      )
                  case _ => EitherT.pure[IO, V2RuntimeFailure](())
              yield ()
              def canonicalWrite(
                  request: Bytes,
              ): Result[IO, ControllerWriteMaterial] =
                publication.authenticateWrite(request)
              def enforce(
                  intent: TransitionIntent,
                  promise: FencePromise,
                  signerId: Text,
                  publicKey: Bytes,
              ): Result[IO, Unit] = check(
                intent == transition(
                  material,
                ) && promise.signerId == id && signerId == id && publicKey == key.publicKey.toBytes && promise.context == sourceContext && promise.boundary == height(
                  6L,
                ) && Set(
                  FenceScope.ApplicationIssuance,
                  FenceScope.ConsensusProfileAtOrAbove,
                ).contains(promise.scope),
                "original immutable transition fence/key policy",
              )
              def closeWrites(
                  closure: ControllerWriteClosure,
                  signerId: Text,
                  publicKey: Bytes,
              ): Result[IO, Unit] = check(
                closure.transition == transition(
                  material,
                ) && closure.context == sourceContext && signerId == id && publicKey == key.publicKey.toBytes,
                "actual old source write retirement policy",
              )
            Resource
              .eval(
                targetAuthentication.set(originalApplication.map(configured)),
              )
              .flatMap(_ =>
                FileFenceController
                  .resource(
                    material.root
                      .resolve("node-" + index.toString)
                      .resolve("controller"),
                    key,
                    id,
                    signedBirth,
                    authentication,
                    publication.writer,
                    faults,
                  )
                  .evalMap { controller =>
                    accepted(
                      for
                        lifecycle <- ConsistencyMutationGate
                          .create(controller, sourceContext)
                        coordinated <- HistoricalConsensusSafetyStore
                          .coordinated(safety, controller, lifecycle)
                        coordinatedIssuance <- HistoricalIssuanceSafetyStore
                          .coordinated(issuance, controller, lifecycle)
                      yield (
                        controller,
                        coordinated,
                        lifecycle,
                        coordinatedIssuance,
                      ),
                    )
                  }
                  .map {
                    (controller, coordinated, lifecycle, coordinatedIssuance) =>
                      val signer = new V2HistoricalFixture.Signer:
                        def proposal(
                            unsigned: UnsignedProposal,
                        ): Result[IO, Proposal] =
                          for
                            request <- core(
                              HotStuffControllerRequests.proposal(unsigned),
                            )
                            signed <- controller.sign(request)
                            sig    <- EitherT.fromOption[IO](
                              signature(signed.signature),
                              V2RuntimeFailure.at(
                                RuntimeFailureCode.InvalidSignature,
                                "controller returned invalid proposal signature encoding",
                              ),
                            )
                            proposal = Proposal(
                              ProposalId(
                                HotStuffCanonicalEncoding.proposalId(
                                  unsigned.window,
                                  unsigned.proposer,
                                  unsigned.targetBlockId,
                                  unsigned.block,
                                  unsigned.txSet,
                                  unsigned.justify,
                                  sig,
                                ),
                              ),
                              unsigned.window,
                              unsigned.proposer,
                              unsigned.targetBlockId,
                              unsigned.block,
                              unsigned.txSet,
                              unsigned.justify,
                              sig,
                            )
                            _ <- check(
                              HotStuffValidator
                                .validateProposal(
                                  proposal,
                                  material.source.validators,
                                )
                                .isRight,
                              "actual controller proposal signature",
                            )
                          yield proposal
                        def vote(proposal: Proposal): Result[IO, Vote] = for
                          _       <- coordinated.beforeVote(proposal)
                          request <- core(
                            HotStuffControllerRequests.vote(voter, proposal),
                          )
                          signed <- controller.sign(request)
                          sig    <- EitherT.fromOption[IO](
                            signature(signed.signature),
                            V2RuntimeFailure.at(
                              RuntimeFailureCode.InvalidSignature,
                              "controller returned invalid vote signature encoding",
                            ),
                          )
                          vote = Vote(
                            VoteId(
                              HotStuffCanonicalEncoding
                                .voteId(
                                  proposal.window,
                                  voter,
                                  proposal.proposalId,
                                  sig,
                                ),
                            ),
                            proposal.window,
                            voter,
                            proposal.proposalId,
                            sig,
                          )
                        yield vote
                      val activation = new TargetActivation(
                        (
                            active,
                            liveSafety,
                            finalizer,
                            publication,
                            requests,
                        ) =>
                          for
                            controlled <- HotStuffControlledSigning.handover(
                              active,
                              liveSafety,
                              finalizer,
                              material.profiles,
                              HistoricalControlledVoting
                                .preparation(Map(voter -> coordinated)),
                            )
                            _ <- check(
                              controlled.publicKeys
                                .get(voter)
                                .contains(key.publicKey.toBytes),
                              "activation selected another actual local key controller",
                            )
                            _ <- EitherT.liftF(
                              targetAuthentication.set(
                                Some(
                                  configured(
                                    ControllerApplicationSigning.authenticated(
                                      liveSafety,
                                      finalizer,
                                      requests,
                                      publication,
                                      None,
                                    ),
                                  ),
                                ),
                              ),
                            )
                          yield controlled,
                      )
                      Node(
                        index,
                        controller,
                        coordinated,
                        signer,
                        signedBirth,
                        lifecycle,
                        activation,
                        coordinatedIssuance,
                        installation,
                      )
                  },
              )
          }
      }
  def nodes(
      material: V2HistoricalFixture.Material,
  ): Resource[IO, Vector[Node]] = (0 to 3).toVector.traverse(index =>
    node(material, index, ControllerFaultInjector.none),
  )

  def nodes(
      material: V2HistoricalFixture.Material,
      publications: Vector[OriginalCanonicalPublication],
  ): Resource[IO, Vector[Node]] =
    Resource
      .eval(
        accepted(
          check(
            publications.sizeCompare(4) == 0,
            "four independently installed canonical writers required",
          ),
        ),
      )
      .flatMap { _ =>
        publications.zipWithIndex.traverse((publication, index) =>
          node(material, index, ControllerFaultInjector.none, publication),
        )
      }
