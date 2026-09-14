package org.sigilaris.conformance

import cats.data.EitherT
import cats.effect.IO
import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.{
  ApplicationInputId,
  ApplicationStateRoot,
  InclusionHeight,
  NormalizedApplicationResult,
}
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.application.protocol.v2.V2Codecs.bytesEncoder
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.crypto.{CryptoOps, KeyPair, Signature}
import org.sigilaris.core.datatype.{BigNat, UInt256, Utf8}
import org.sigilaris.node.gossip.{ChainId, StableArtifactId}
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.node.jvm.runtime.block.{
  BlockBody,
  BlockHeader,
  BlockHeaderVersion,
  BlockHeight,
  BlockId,
  BlockRecord,
  BlockTimestamp,
  BodyRoot,
  StateRoot,
}
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{
  HotStuffValidator,
  HotStuffWindow,
  Proposal,
  ProposalId,
  ProposalTxSet,
  QuorumCertificate,
  QuorumCertificateSubject,
  UnsignedProposal,
  UnsignedVote,
  ValidatorId,
  ValidatorMember,
  ValidatorSet,
  Vote,
}

/** Public JVM fixture. Its immutable source table is independently signed, and
  * its neutral execution hook calculates writes/results from retained state.
  * Missing effects are intentional: a first effect vote cannot depend on an
  * already completed effect certificate.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.OptionPartial",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.Throw",
  ),
)
object V2RequestConformance:
  def uint(value: Long): Hash = UInt256.unsafeFromBigIntUnsigned(BigInt(value))
  def height(value: Long): Height = InclusionHeight(
    BigNat.unsafeFromLong(value),
  )
  def inputId(value: Bytes): InputId =
    ApplicationInputId.fromBytes(value).toOption.get

  def bytes(value: String): Bytes = ByteVector.fromValidHex(value)
  def hash(value: Bytes): Hash    = UInt256.unsafeFromBytesBE(
    ByteVector.view(CryptoOps.keccak256(value.toArray)),
  )
  def value[A](result: Either[CoreFailure, A]): A = result.fold(
    error => throw new IllegalStateException(error.message),
    resolved => resolved,
  )
  def signatureBytes(signature: Signature): Bytes =
    signature.v.toLong.toBytes ++ signature.r.toBytes ++ signature.s.toBytes
  def signature(raw: Bytes): Option[Signature] =
    Option.when(raw.size == 72L)(
      Signature(
        BigInt(1, raw.take(8L).toArray).toInt,
        UInt256.unsafeFromBytesBE(raw.slice(8L, 40L)),
        UInt256.unsafeFromBytesBE(raw.drop(40L)),
      ),
    )
  def signed(key: KeyPair, preimage: Bytes): Bytes = signatureBytes(
    CryptoOps.sign(key, CryptoOps.keccak256(preimage.toArray)).toOption.get,
  )
  def recovered(raw: Bytes, preimage: Bytes, key: KeyPair): Boolean =
    signature(raw)
      .flatMap(sig =>
        CryptoOps.recover(sig, CryptoOps.keccak256(preimage.toArray)).toOption,
      )
      .contains(key.publicKey)
  def unavailable[A](detail: String): Result[IO, A] = EitherT.leftT[IO, A](
    V2RuntimeFailure.at(RuntimeFailureCode.ProofUnavailable, detail),
  )

  final class Fixture(
      val nonce: Long,
      val authority: Authority,
      val offset: Int,
      val extraReads: Vector[InputId],
      initialValues: Option[Map[InputId, Long]],
      selectedRead: Option[InputId],
      signedDeadline: Height,
  ):
    def this(
        nonce: Long,
        authority: Authority,
        offset: Int,
        extraReads: Vector[InputId],
        initialValues: Option[Map[InputId, Long]],
        selectedRead: Option[InputId],
    ) =
      this(
        nonce,
        authority,
        offset,
        extraReads,
        initialValues,
        selectedRead,
        height(10L),
      )
    def this(
        nonce: Long,
        authority: Authority,
        offset: Int,
        extraReads: Vector[InputId],
        signedDeadline: Height,
    ) =
      this(nonce, authority, offset, extraReads, None, None, signedDeadline)
    def this(
        nonce: Long,
        authority: Authority,
        offset: Int,
        extraReads: Vector[InputId],
    ) =
      this(nonce, authority, offset, extraReads, None, None)
    def this(nonce: Long, authority: Authority) =
      this(nonce, authority, 0, Vector.empty)
    def this(nonce: Long, authority: Authority, offset: Int) =
      this(nonce, authority, offset, Vector.empty)
    val keys: Vector[(Text, KeyPair)] = (1 to 4).toVector.map(n =>
      Utf8("v" + n.toString) -> CryptoOps.fromPrivate(BigInt(n)),
    )
    val validators = ValidatorSet.unsafe(
      keys.map((id, key) =>
        ValidatorMember(ValidatorId.unsafe(id.asString), key.publicKey),
      ),
    )
    val transactionKey  = CryptoOps.fromPrivate(BigInt(17))
    val chain           = ChainId.unsafe("neutral-v2-request")
    val schema          = uint(1)
    val lockIdentity    = inputId(ByteVector((offset + 1).toByte))
    val counterIdentity = inputId(ByteVector((offset + 2).toByte))
    val readIdentity    = selectedRead.getOrElse(inputId(bytes("03")))
    val createIdentity  = inputId(ByteVector((offset + 4).toByte))
    val family          = InputManifest(
      2L,
      Utf8("neutral.request"),
      1L,
      Vector(
        FieldManifest(
          Utf8("balance"),
          FieldRole.ExactMutate,
          schema,
          Some(uint(2)),
        ),
        FieldManifest(
          Utf8("counter"),
          FieldRole.ExactMutate,
          schema,
          Some(uint(2)),
        ),
        FieldManifest(Utf8("read"), FieldRole.ExactRead, schema, Some(uint(2))),
      ),
      uint(3),
      uint(4),
      uint(5),
      None,
    )
    val manifest = ProtocolManifest(
      2L,
      2L,
      Utf8(chain.value),
      0L,
      validators.hash.toUInt256,
      64L,
      SubstrateVersions.V2,
      ProtocolLimits.V2,
      Vector(family),
      Vector.empty,
    )
    val context = value(ProtocolManifest.context(manifest))
    val state   = initialValues.getOrElse(
      Map(
        inputId(bytes("01")) -> 10L,
        inputId(bytes("02")) -> 0L,
        inputId(bytes("0b")) -> 10L,
        inputId(bytes("0c")) -> 0L,
        readIdentity         -> 7L,
      ) ++ extraReads.map(_ -> 1L),
    )
    val preStateRoot = hash(
      state.toVector.sortBy(_._1.toHex).foldLeft(ByteVector.empty) {
        case (acc, (id, v)) => acc ++ id.bytes ++ v.toBytes
      },
    )
    val baseHeader = BlockHeader(
      Some(BlockId(uint(66))),
      BlockHeight.unsafeFromLong(5L),
      StateRoot(preStateRoot),
      BodyRoot(uint(0)),
      BlockTimestamp.unsafeFromEpochMillis(1000L),
    )
    val baseBlockId = BlockHeader.computeId(baseHeader)
    val baseWindow  =
      HotStuffWindow.fromLongs(chain, 5L, 0L, validators.hash).toOption.get
    val baseProposalId  = ProposalId(uint(77))
    val baseCertificate = QuorumCertificate(
      QuorumCertificateSubject(baseWindow, baseProposalId, baseBlockId),
      keys
        .take(3)
        .map((id, key) =>
          Vote
            .sign(
              UnsignedVote(
                baseWindow,
                ValidatorId.unsafe(id.asString),
                baseProposalId,
              ),
              key,
            )
            .toOption
            .get,
        ),
    )
    val base =
      AdmissionBase.Finalized(baseBlockId.toUInt256, height(5L), preStateRoot)
    val fields = Vector(
      ResolvedField(
        Utf8("balance"),
        FieldRole.ExactMutate,
        state(lockIdentity).toBytes,
        Some(lockIdentity),
        Some(ExactPrecondition(schema, state(lockIdentity).toBytes)),
        Some(authority),
      ),
      ResolvedField(
        Utf8("counter"),
        FieldRole.ExactMutate,
        state(counterIdentity).toBytes,
        Some(counterIdentity),
        Some(ExactPrecondition(schema, state(counterIdentity).toBytes)),
        Some(Authority.ConsensusOnly),
      ),
      ResolvedField(
        Utf8("read"),
        FieldRole.ExactRead,
        state(readIdentity).toBytes,
        Some(readIdentity),
        Some(ExactPrecondition(schema, state(readIdentity).toBytes)),
        Some(Authority.ConsensusOnly),
      ),
    )
    val descriptor = value(InputDerivation.derive(family, fields))
    val footprint  = value(
      Footprint.canonical(
        Vector(readIdentity) ++ extraReads,
        Vector(lockIdentity, counterIdentity, createIdentity),
      ),
    )
    val declaration =
      Declaration.Exact(value(Footprint.declaredCommitment(footprint)))
    val deadline = signedDeadline
    val payload  =
      nonce.toBytes ++ value(InputDescriptor.codec.encode(descriptor)) ++ value(
        Declaration.codec.encode(declaration),
      ) ++ deadline.toBytes
    val signedTransaction = payload ++ signed(transactionKey, payload)
    val txId              = hash(signedTransaction)
    val executionId       = value(
      ExecutionIdentity.compute(
        ExecutionIdentityInput(
          context,
          descriptor.manifestDigest,
          txId,
          signedTransaction,
          descriptor.fullInputCommitment,
          descriptor.lockSubsetCommitment,
          value(Declaration.digest(declaration)),
          uint(0),
          deadline,
        ),
      ),
    )
    val proofs = fields.map(field =>
      ResolutionEvidence(
        field.fieldId,
        hash(
          value(ResolvedField.codec.encode(field)) ++ preStateRoot.toBytes,
        ).toBytes,
      ),
    )
    val lockSubject = LockSubject(
      context,
      txId,
      executionId,
      descriptor.manifestDigest,
      base,
      descriptor.fullInputCommitment,
      descriptor.lockSubsetCommitment,
      uint(0),
      deadline,
      Vector(lockIdentity),
    )
    def quorum(preimage: Bytes): Vector[ValidatorSignature] = keys
      .take(3)
      .map((id, key) => ValidatorSignature(id, signed(key, preimage)))
    lazy val lockCertificate = LockCertificate(
      lockSubject,
      quorum(value(LockSubject.signingPreimage(lockSubject))),
    )
    lazy val lockId = value(LockCertificate.id(lockCertificate))
    private final case class TracedState(
        values: Map[InputId, Long],
        accesses: Vector[ActualAccess],
    ):
      private def ownerOf(id: InputId): Authority =
        if id == lockIdentity then authority else Authority.ConsensusOnly
      def read(id: InputId): (Long, TracedState) = values(id) -> copy(accesses =
        accesses :+ ActualAccess(id, AccessKind.ReadExisting, Some(ownerOf(id))),
      )
      def write(id: InputId, value: Long): TracedState =
        assert(values.contains(id))
        copy(
          values = values.updated(id, value),
          accesses = accesses :+ ActualAccess(
            id,
            AccessKind.MutateExisting,
            Some(ownerOf(id)),
          ),
        )
      def create(id: InputId, value: Long): TracedState =
        assert(!values.contains(id))
        copy(
          values = values.updated(id, value),
          accesses = accesses :+ ActualAccess(id, AccessKind.CreateAbsent, None),
        )
    private val executed =
      val initial                = TracedState(state, Vector.empty)
      val (balance, readBalance) = initial.read(lockIdentity)
      val changedBalance         = readBalance.write(lockIdentity, balance - 1L)
      val (counter, readCounter) = changedBalance.read(counterIdentity)
      val changedCounter = readCounter.write(counterIdentity, counter + 1L)
      val (copied, readSource) = changedCounter.read(readIdentity)
      val created              = readSource.create(createIdentity, copied)
      extraReads.foldLeft(created)((trace, id) => trace.read(id)._2)
    val access        = executed.accesses
    val creationProof = hash(
      preStateRoot.toBytes ++ createIdentity.bytes,
    ).toBytes
    val owner = Owner(
      context,
      executionId,
      Scope(
        ScopeKind.FastAdmission,
        uint(0),
        height(0),
        uint(0),
        0L,
        hash(baseCertificate.toBytes),
      ),
    )
    val nextState     = executed.values
    val nextStateRoot = hash(
      nextState.toVector.sortBy(_._1.toHex).foldLeft(ByteVector.empty) {
        case (acc, (id, v)) => acc ++ id.bytes ++ v.toBytes
      },
    )
    val normalizedResult = NormalizedApplicationResult.fromBytes(
      nextState(lockIdentity).toBytes ++ nextState(
        counterIdentity,
      ).toBytes ++ nextState(createIdentity).toBytes,
    )
    val witness     = value(ReservationWitness.fromFootprint(footprint))
    lazy val effect = ExecutedEffect(
      signedTransaction,
      lockId,
      preStateRoot,
      descriptor,
      proofs,
      access,
      Vector(createIdentity -> creationProof),
      normalizedResult.bytes,
      nextStateRoot,
      owner,
    )
    lazy val effectSubject = EffectSubject(
      context,
      txId,
      executionId,
      descriptor.manifestDigest,
      lockId,
      uint(0),
      deadline,
      normalizedResult.digest,
      ApplicationStateRoot(nextStateRoot),
      value(Footprint.actualCommitment(footprint)),
    )

    val artifacts: ArtifactAuthentication = new ArtifactAuthentication:
      def historicalValidators(
          actual: DomainContext,
      ): Either[CoreFailure, Vector[Text]] = Either.cond(
        actual == context,
        keys.map(_._1),
        CoreFailure.at(FailureCode.ProofInvalid, "context"),
      )
      def verifySignature(
          actual: DomainContext,
          signer: Text,
          preimage: Bytes,
          raw: Bytes,
      ): Either[CoreFailure, Unit] =
        Either.cond(
          actual == context && keys
            .find(_._1 == signer)
            .exists((_, key) => recovered(raw, preimage, key)),
          (),
          CoreFailure.at(FailureCode.InvalidSignature, "validator"),
        )
      def verifyFinalizedBase(
          actual: DomainContext,
          supplied: AdmissionBase,
      ): Either[CoreFailure, Unit] =
        Either.cond(
          actual == context && supplied == base && HotStuffValidator
            .validateQuorumCertificate(baseCertificate, validators)
            .isRight,
          (),
          CoreFailure.at(FailureCode.ProofInvalid, "retainedFinalizedBase"),
        )

    val inputs: InputAuthentication = new InputAuthentication:
      def verify(
          actualFamily: InputManifest,
          signedBytes: Bytes,
          root: Hash,
          field: ResolvedField,
          proof: ResolutionEvidence,
      ): Either[CoreFailure, Unit] =
        Either.cond(
          actualFamily == family && signedBytes == signedTransaction && recovered(
            signedBytes.takeRight(72L),
            signedBytes.dropRight(72L),
            transactionKey,
          ) && root == preStateRoot && fields.contains(field) && proofs
            .contains(proof) && proof.fieldId == field.fieldId,
          (),
          CoreFailure.at(FailureCode.ProofInvalid, "signedResolvedInput"),
        )
      def verifyAbsentCreation(
          actualFamily: InputManifest,
          signedBytes: Bytes,
          root: Hash,
          identity: InputId,
          proof: Bytes,
      ): Either[CoreFailure, Unit] =
        Either.cond(
          actualFamily == family && signedBytes == signedTransaction && root == preStateRoot && identity == createIdentity && !state
            .contains(identity) && proof == creationProof,
          (),
          CoreFailure.at(FailureCode.ProofInvalid, "absentCreation"),
        )

    val declarations: DeclaredFootprintAuthentication =
      new DeclaredFootprintAuthentication:
        def derive(
            actualFamily: InputManifest,
            signedBytes: Bytes,
            root: Hash,
            resolved: InputDescriptor,
        ): Either[CoreFailure, Option[Footprint]] =
          Either.cond(
            actualFamily == family && signedBytes == signedTransaction && root == preStateRoot && resolved == descriptor,
            Some(footprint),
            CoreFailure.at(FailureCode.ProofInvalid, "declaredFootprint"),
          )

    val creations: DeclaredCreationAuthentication =
      new DeclaredCreationAuthentication:
        def derive(
            selected: InputManifest,
            raw: Bytes,
            root: Hash,
            input: InputDescriptor,
        ): Either[CoreFailure, Vector[(InputId, Bytes)]] =
          Either.cond(
            selected == family && raw == signedTransaction && root == preStateRoot && input == descriptor,
            Vector(createIdentity -> creationProof),
            CoreFailure.at(FailureCode.ProofInvalid, "declaredCreations"),
          )

    val transactions: TransactionAuthentication[IO] =
      new TransactionAuthentication[IO]:
        def authenticate(
            actual: DomainContext,
            selected: InputManifest,
            raw: Bytes,
        ): Result[IO, SignedApplicationBinding] = EitherT.fromEither[IO](
          Either.cond(
            actual == context && selected == family && raw == signedTransaction && raw
              .dropRight(72L) == payload && recovered(
              raw.takeRight(72L),
              payload,
              transactionKey,
            ),
            SignedApplicationBinding(
              txId,
              descriptor.manifestDigest,
              declaration,
              uint(0),
              deadline,
              None,
            ),
            V2RuntimeFailure.at(
              RuntimeFailureCode.InvalidSignature,
              "neutral signed transaction verification failed",
            ),
          ),
        )

    def repository(
        observed: ExecutedEffect,
    ): ApplicationExecutionRepository[IO] =
      new ApplicationExecutionRepository[IO]:
        def lockSource(
            actual: DomainContext,
            execution: org.sigilaris.core.application.protocol.ExecutionId,
        ): Result[IO, LockSourceMaterial] =
          if actual == context && execution == executionId then
            EitherT.pure(
              LockSourceMaterial(descriptor, signedTransaction, proofs),
            )
          else unavailable("source")
        def lockCertificate(id: Hash): Result[IO, LockCertificate] =
          if id == lockId then EitherT.pure(Fixture.this.lockCertificate)
          else unavailable("lock certificate")
        def effectCertificate(id: Hash): Result[IO, EffectCertificate] =
          unavailable("effect quorum has not been formed")
        def executeEffect(
            actual: DomainContext,
            execution: org.sigilaris.core.application.protocol.ExecutionId,
        ): Result[IO, ExecutedEffect] =
          if actual == context && execution == executionId && artifacts
              .verifyFinalizedBase(actual, base)
              .isRight
          then EitherT.pure(observed)
          else unavailable("authenticated execution")
        def verifyExactBinding(
            actual: DomainContext,
            execution: org.sigilaris.core.application.protocol.ExecutionId,
            binding: Option[Hash],
        ): Result[IO, Unit] =
          EitherT.fromEither[IO](
            Either.cond(
              actual == context && execution == executionId && binding.isEmpty,
              (),
              V2RuntimeFailure.at(
                RuntimeFailureCode.ProofInvalid,
                "generic transaction cannot claim exact ownership",
              ),
            ),
          )

    val scopes: ReservationScopeAuthentication[IO] =
      new ReservationScopeAuthentication[IO]:
        def verifyFast(
            owner: Owner,
            suppliedBase: AdmissionBase,
            source: SignedApplicationBinding,
        ): Result[IO, Unit] =
          EitherT.fromEither[IO](
            Either.cond(
              owner.scope.authorizationDigest == hash(
                baseCertificate.toBytes,
              ) && owner.context == context && owner.executionId == executionId && source.txId == txId && artifacts
                .verifyFinalizedBase(context, suppliedBase)
                .isRight,
              (),
              V2RuntimeFailure.at(
                RuntimeFailureCode.ProofInvalid,
                "fast scope authentication artifact does not bind finalized admission",
              ),
            ),
          )
        def verifyConsensus(
            owner: Owner,
            supplied: Proposal,
            plan: ExecutionPlan,
            entry: PlanEntry,
        ): Result[IO, Unit] =
          val expected = hash(
            baseCertificate.toBytes ++ value(
              PlanEntry.codec.encode(entry),
            ) ++ value(ExecutionPlan.computeRoot(plan)).toBytes,
          )
          EitherT.fromEither[IO](
            Either.cond(
              supplied == candidate && entry == consensusEntry && plan == consensusPlan && owner.scope.authorizationDigest == expected && supplied.block.parent
                .contains(baseBlockId) && HotStuffValidator
                .validateProposal(supplied, validators)
                .isRight && artifacts
                .verifyFinalizedBase(context, base)
                .isRight,
              (),
              V2RuntimeFailure.at(
                RuntimeFailureCode.ProofInvalid,
                "consensus scope authentication does not bind entry/plan/branch",
              ),
            ),
          )

    val unavailableProposals: ProposalExecutionRepository[IO] =
      new ProposalExecutionRepository[IO]:
        def historicalValidators(
            window: HotStuffWindow,
        ): Result[IO, ValidatorSet] =
          if window.chainId == chain && window.validatorSetHash == validators.hash
          then EitherT.pure(validators)
          else unavailable("validators")
        def executeSequential(
            proposal: Proposal,
            plan: ExecutionPlan,
        ): Result[IO, ExecutedProposal] = unavailable("proposal not installed")

    def verifierWith(observed: ExecutedEffect): ApplicationRequestVerifier[IO] =
      verifierWithCreations(observed, creations)
    def verifierWithCreations(
        observed: ExecutedEffect,
        creationVerifier: DeclaredCreationAuthentication,
    ): ApplicationRequestVerifier[IO] =
      ApplicationRequestVerifier.authenticated(
        manifest,
        inputs,
        declarations,
        creationVerifier,
        transactions,
        artifacts,
        scopes,
        repository(observed),
        unavailableProposals,
      )
    lazy val verifier = verifierWith(effect)

    lazy val consensusEntry = PlanEntry(
      executionId,
      PlanSource.ConsensusTransaction(
        txId,
        signedTransaction,
        Option.when(authority == Authority.LockEligible)(lockId),
      ),
      descriptor.manifestDigest,
      declaration,
      value(Declaration.digest(declaration)),
      descriptor.fullInputCommitment,
      descriptor.lockSubsetCommitment,
      value(Footprint.actualCommitment(footprint)),
      uint(0),
      deadline,
      preStateRoot,
      None,
    )
    lazy val consensusPlan = ExecutionPlan(
      2L,
      Vector(ExecutionWave(WaveKind.Ordered, Vector(consensusEntry))),
      Vector.empty,
    )
    lazy val consensusPlanRoot = value(ExecutionPlan.computeRoot(consensusPlan))
    lazy val body              = BlockBody[Hash, Hash, Bytes](
      Set(
        BlockRecord(
          txId,
          Some(normalizedResult.digest.toUInt256),
          Vector.empty[Bytes],
        ),
      ),
    )
    lazy val bodyRoot        = BlockBody.computeBodyRoot(body).toOption.get
    lazy val candidateHeader = BlockHeader(
      Some(baseBlockId),
      BlockHeight.unsafeFromLong(6L),
      StateRoot(nextStateRoot),
      bodyRoot,
      BlockTimestamp.unsafeFromEpochMillis(2000L),
      BlockHeaderVersion.V2,
      Some(consensusPlanRoot),
    )
    lazy val candidateWindow =
      HotStuffWindow.fromLongs(chain, 6L, 0L, validators.hash).toOption.get
    lazy val candidate = Proposal
      .sign(
        UnsignedProposal(
          candidateWindow,
          ValidatorId.unsafe("v1"),
          BlockHeader.computeId(candidateHeader),
          candidateHeader,
          ProposalTxSet(
            Vector(StableArtifactId.fromBytes(txId.toBytes).toOption.get),
          ),
          baseCertificate,
        ),
        keys.head._2,
      )
      .toOption
      .get
    lazy val candidateOwner = Owner(
      context,
      executionId,
      Scope(
        ScopeKind.ConsensusOrdered,
        baseBlockId.toUInt256,
        height(6L),
        consensusPlanRoot.toUInt256,
        0L,
        hash(
          baseCertificate.toBytes ++ value(
            PlanEntry.codec.encode(consensusEntry),
          ) ++ consensusPlanRoot.toBytes,
        ),
      ),
    )
    lazy val executedCandidate = ExecutedProposal(
      candidate,
      UnsignedVote(
        candidateWindow,
        ValidatorId.unsafe("v1"),
        candidate.proposalId,
      ),
      preStateRoot,
      Vector(BodyMember(txId, executionId)),
      bodyRoot.toUInt256,
      Vector(
        ExecutedApplication(
          consensusEntry,
          descriptor,
          proofs,
          access,
          Vector(createIdentity -> creationProof),
          normalizedResult.bytes,
          nextStateRoot,
          candidateOwner,
          None,
        ),
      ),
    )
    def proposalVerifier(
        execution: ExecutedProposal,
    ): ApplicationRequestVerifier[IO] =
      val proposalRepository = new ProposalExecutionRepository[IO]:
        def historicalValidators(
            window: HotStuffWindow,
        ): Result[IO, ValidatorSet] =
          unavailableProposals.historicalValidators(window)
        def executeSequential(
            proposal: Proposal,
            plan: ExecutionPlan,
        ): Result[IO, ExecutedProposal] =
          if proposal == candidate && plan == consensusPlan && artifacts
              .verifyFinalizedBase(context, base)
              .isRight
          then EitherT.pure(execution)
          else unavailable("canonical execution candidate")
      ApplicationRequestVerifier.authenticated(
        manifest,
        inputs,
        declarations,
        creations,
        transactions,
        artifacts,
        scopes,
        repository(effect),
        proposalRepository,
      )

  /** The configured retained source set survives a journal reopen. Dispatch is
    * by authenticated execution identity; each selected verifier still checks
    * all source signatures, state proofs and independent execution results.
    */
  def recoveryVerifier(
      fixtures: Vector[Fixture],
  ): ApplicationRequestVerifier[IO] =
    new ApplicationRequestVerifier[IO]:
      private def source[A](
          executionId: org.sigilaris.core.application.protocol.ExecutionId,
      )(
          run: Fixture => Result[IO, A],
      ): Result[IO, A] = fixtures.find(_.executionId == executionId) match
        case Some(fixture) => run(fixture)
        case None          => unavailable("retained signed execution source")
      def verifyLock(
          subject: LockSubject,
          descriptor: InputDescriptor,
          signedTransaction: Bytes,
          proofs: Vector[ResolutionEvidence],
      ): Result[IO, VerifiedLockRequest] =
        source(subject.executionId)(
          _.verifier.verifyLock(subject, descriptor, signedTransaction, proofs),
        )
      def verifyEffect(
          subject: EffectSubject,
          owner: Owner,
          witness: ReservationWitness,
      ): Result[IO, VerifiedEffectRequest] =
        source(subject.executionId)(
          _.verifier.verifyEffect(subject, owner, witness),
        )
      def verifyProposal(
          proposal: Proposal,
          plan: ExecutionPlan,
      ): Result[IO, VerifiedConsensusProposal] =
        fixtures.find(_.candidate.proposalId == proposal.proposalId) match
          case Some(fixture) =>
            fixture
              .proposalVerifier(fixture.executedCandidate)
              .verifyProposal(proposal, plan)
          case None => unavailable("retained sequential proposal source")
      def verifyLockCertificate(
          certificate: LockCertificate,
      ): Result[IO, VerifiedLockCertificate] =
        source(certificate.subject.executionId)(
          _.verifier.verifyLockCertificate(certificate),
        )
      def verifyEffectCertificate(
          certificate: EffectCertificate,
      ): Result[IO, VerifiedEffectCertificate] =
        source(certificate.subject.executionId)(
          _.verifier.verifyEffectCertificate(certificate),
        )

  private def accepted[A](task: Result[IO, A]): IO[A] = task.value.map(
    _.fold(
      error => throw new IllegalStateException(error.message),
      resolved => resolved,
    ),
  )
  private def rejected[A](task: Result[IO, A]): IO[Unit] =
    task.value.map(result => assert(result.isLeft))

  def run(): IO[Unit] =
    val f            = new Fixture(1L, Authority.LockEligible)
    val consensus    = new Fixture(2L, Authority.ConsensusOnly)
    val unusedResult = NormalizedApplicationResult.fromBytes(bytes("0001"))
    val unusedRoot   = hash(
      f.nextState
        .removed(f.createIdentity)
        .toVector
        .sortBy(_._1.toHex)
        .foldLeft(ByteVector.empty) { case (acc, (id, v)) =>
          acc ++ id.bytes ++ v.toBytes
        },
    )
    val unusedCreation = f.effect.copy(
      actualAccesses = f.access.filterNot(_.kind == AccessKind.CreateAbsent),
      absentCreations = Vector.empty,
      normalizedResult = unusedResult.bytes,
      nextStateRoot = unusedRoot,
    )
    val unusedFootprint = value(
      Footprint.canonical(
        f.footprint.reads :+ f.createIdentity,
        f.footprint.writes.filterNot(_ == f.createIdentity),
      ),
    )
    val unusedWitness = value(ReservationWitness.fromFootprint(unusedFootprint))
    val unusedSubject = f.effectSubject.copy(
      resultDigest = unusedResult.digest,
      stateRoot = ApplicationStateRoot(unusedRoot),
      actualFootprintCommitment =
        value(Footprint.actualCommitment(unusedFootprint)),
    )
    val missingUnusedProof = new DeclaredCreationAuthentication:
      def derive(
          manifest: InputManifest,
          signed: Bytes,
          root: Hash,
          descriptor: InputDescriptor,
      ): Either[CoreFailure, Vector[(InputId, Bytes)]] = Right(
        Vector(f.createIdentity -> ByteVector.empty),
      )

    for
      lock <- accepted(
        f.verifier.verifyLock(
          f.lockSubject,
          f.descriptor,
          f.signedTransaction,
          f.proofs,
        ),
      )
      _ <- IO(assert(lock.subject.inputs == Vector(f.lockIdentity)))
      _ <- rejected(
        f.verifier.verifyLock(
          f.lockSubject.copy(lastInclusionHeight = height(11L)),
          f.descriptor,
          f.signedTransaction,
          f.proofs,
        ),
      )
      _ <- rejected(
        f.verifier.verifyLock(
          f.lockSubject,
          f.descriptor,
          f.signedTransaction.dropRight(1L) ++ bytes("00"),
          f.proofs,
        ),
      )
      _ <- rejected(
        f.verifier.verifyLock(
          f.lockSubject.copy(admissionBase =
            AdmissionBase
              .Finalized(f.baseBlockId.toUInt256, height(5L), uint(999)),
          ),
          f.descriptor,
          f.signedTransaction,
          f.proofs,
        ),
      )
      _ <- rejected(
        f.verifier.verifyLock(
          f.lockSubject.copy(inputs = Vector(f.counterIdentity)),
          f.descriptor,
          f.signedTransaction,
          f.proofs,
        ),
      )
      forgedOwner = f.owner.copy(scope =
        f.owner.scope.copy(authorizationDigest = uint(999)),
      )
      _ <- rejected(
        f.verifierWith(f.effect.copy(owner = forgedOwner))
          .verifyEffect(f.effectSubject, forgedOwner, f.witness),
      )
      verifiedEffect <- accepted(
        f.verifier.verifyEffect(f.effectSubject, f.owner, f.witness),
      )
      _ <- IO(
        assert(
          verifiedEffect.witness.entries.size == 4 && verifiedEffect.entryPreStateRoot == f.preStateRoot,
        ),
      )
      _ <- rejected(
        f.verifier.verifyEffect(
          f.effectSubject.copy(resultDigest =
            NormalizedApplicationResult.fromBytes(bytes("ff")).digest,
          ),
          f.owner,
          f.witness,
        ),
      )
      _ <- rejected(
        f.verifier.verifyEffect(
          f.effectSubject,
          f.owner
            .copy(scope = f.owner.scope.copy(authorizationDigest = uint(999))),
          f.witness,
        ),
      )
      _ <- rejected(
        f.verifier.verifyEffect(
          f.effectSubject,
          f.owner,
          value(
            ReservationWitness.fromFootprint(
              value(Footprint.canonical(Vector.empty, Vector(f.lockIdentity))),
            ),
          ),
        ),
      )
      _ <- rejected(
        f.verifierWith(
          f.effect.copy(actualAccesses =
            f.access.filterNot(_.identity == f.readIdentity),
          ),
        ).verifyEffect(f.effectSubject, f.owner, f.witness),
      )
      _ <- rejected(
        f.verifierWith(
          f.effect.copy(actualAccesses =
            f.access :+ ActualAccess(
              inputId(bytes("ff")),
              AccessKind.MutateExisting,
              Some(Authority.LockEligible),
            ),
          ),
        ).verifyEffect(f.effectSubject, f.owner, f.witness),
      )
      _ <- rejected(
        f.verifierWith(f.effect.copy(absentCreations = Vector.empty))
          .verifyEffect(f.effectSubject, f.owner, f.witness),
      )
      _ <- rejected(
        f.verifierWith(f.effect.copy(inputLockCertificateId = uint(999)))
          .verifyEffect(f.effectSubject, f.owner, f.witness),
      )
      _ <- accepted(
        f.verifier.verifyEffectCertificate(
          EffectCertificate(
            f.effectSubject,
            f.quorum(value(EffectSubject.signingPreimage(f.effectSubject))),
          ),
        ),
      )
      unused <- accepted(
        f.verifierWith(unusedCreation)
          .verifyEffect(unusedSubject, f.owner, unusedWitness),
      )
      _ <- IO(
        assert(
          unused.witness.entries.exists(entry =>
            entry.identity == f.createIdentity && entry.access == WitnessAccess.Read,
          ),
        ),
      )
      _ <- rejected(
        f.verifierWithCreations(unusedCreation, missingUnusedProof)
          .verifyEffect(unusedSubject, f.owner, unusedWitness),
      )
      _ <- rejected(
        f.verifierWith(
          unusedCreation.copy(actualAccesses =
            unusedCreation.actualAccesses :+ ActualAccess(
              f.createIdentity,
              AccessKind.ReadExisting,
              Some(Authority.ConsensusOnly),
            ),
          ),
        ).verifyEffect(unusedSubject, f.owner, unusedWitness),
      )
      legacyHeader = consensus.candidateHeader.copy(version =
        BlockHeaderVersion.V1,
      )
      unboundPlan = Proposal
        .sign(
          UnsignedProposal(
            consensus.candidateWindow,
            ValidatorId.unsafe("v1"),
            BlockHeader.computeId(legacyHeader),
            legacyHeader,
            consensus.candidate.txSet,
            consensus.baseCertificate,
          ),
          consensus.keys(0)._2,
        )
        .toOption
        .get
      unboundResult <- consensus
        .proposalVerifier(consensus.executedCandidate)
        .verifyProposal(unboundPlan, consensus.consensusPlan)
        .value
      _ <- IO(
        assert(
          unboundResult.left.exists(error =>
            error.code == RuntimeFailureCode.InvalidRequest && error.detail == "historicalHeaderHasExecutionPlanRoot",
          ),
        ),
      )
      proposal <- accepted(
        consensus
          .proposalVerifier(consensus.executedCandidate)
          .verifyProposal(consensus.candidate, consensus.consensusPlan),
      )
      _ <- IO(
        assert(
          proposal.reservations.size == 1 && proposal.reservations.head.witness.entries.size == 4 && proposal.parentStateRoot == consensus.preStateRoot,
        ),
      )
      _ <- rejected(
        consensus
          .proposalVerifier(
            consensus.executedCandidate.copy(parentStateRoot = uint(999)),
          )
          .verifyProposal(consensus.candidate, consensus.consensusPlan),
      )
      _ <- rejected(
        consensus
          .proposalVerifier(
            consensus.executedCandidate.copy(bodyRoot = uint(999)),
          )
          .verifyProposal(consensus.candidate, consensus.consensusPlan),
      )
      forgedConsensusOwner = consensus.candidateOwner.copy(scope =
        consensus.candidateOwner.scope.copy(authorizationDigest = uint(999)),
      )
      forgedExecution = consensus.executedCandidate.copy(entries =
        consensus.executedCandidate.entries.map(
          _.copy(owner = forgedConsensusOwner),
        ),
      )
      _ <- rejected(
        consensus
          .proposalVerifier(forgedExecution)
          .verifyProposal(consensus.candidate, consensus.consensusPlan),
      )
      _ <- IO(
        println(
          "V2RequestConformance PASS: real signed source, complete actual access, acyclic first effect verification and lock-free consensus proposal",
        ),
      )
    yield ()
