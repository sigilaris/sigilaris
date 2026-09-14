package org.sigilaris.conformance

import scodec.bits.ByteVector
import org.sigilaris.core.application.protocol.{
  ApplicationInputId,
  ApplicationResultDigest,
  ApplicationStateRoot,
  InclusionHeight,
  NormalizedApplicationResult,
}
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.crypto.{CryptoOps, Signature}
import org.sigilaris.core.datatype.{BigNat, UInt256, Utf8}

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.OptionPartial",
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Throw",
    "org.wartremover.warts.DefaultArguments",
    "org.wartremover.warts.IterableOps",
  ),
)
private object V2OpeningFixture:
  def hash(value: Long): Hash = UInt256.unsafeFromBigIntUnsigned(BigInt(value))
  def height(value: Long): Height = InclusionHeight(
    BigNat.unsafeFromLong(value),
  )
  def identity(value: Int): InputId =
    ApplicationInputId.fromBytes(ByteVector(value.toByte)).toOption.get

  val keys = Vector(1, 2, 3, 4).map(index =>
    Utf8("validator-" + index.toString) -> CryptoOps.fromPrivate(BigInt(index)),
  )
  val base: AdmissionBase =
    AdmissionBase.Finalized(hash(5), height(10), hash(6))
  val family: InputManifest = InputManifest(
    2L,
    Utf8("neutral-certificate"),
    1L,
    Vector(
      FieldManifest(
        Utf8("counter"),
        FieldRole.ExactMutate,
        hash(11),
        Some(hash(12)),
      ),
    ),
    hash(13),
    hash(14),
    hash(15),
    Some(hash(16)),
  )
  def manifestFor(value: InputManifest): ProtocolManifest = ProtocolManifest(
    2L,
    2L,
    Utf8("neutral-chain"),
    1L,
    hash(19),
    64L,
    SubstrateVersions.V2,
    ProtocolLimits.V2,
    Vector(value),
    Vector.empty,
  )
  val manifest: ProtocolManifest = manifestFor(family)
  val context: DomainContext = ProtocolManifest.context(manifest).toOption.get
  def descriptor(authority: Authority): InputDescriptor = InputDerivation
    .derive(
      family,
      Vector(
        ResolvedField(
          Utf8("counter"),
          FieldRole.ExactMutate,
          ByteVector(1.toByte),
          Some(identity(1)),
          Some(ExactPrecondition(hash(11), ByteVector(1.toByte))),
          Some(authority),
        ),
      ),
    )
    .toOption
    .get
  val input: InputDescriptor = descriptor(Authority.LockEligible)
  val footprint: Footprint   =
    Footprint.canonical(Vector.empty, Vector(identity(1))).toOption.get

  def entryFor(
      descriptor: InputDescriptor,
      active: ProtocolManifest = manifest,
      signed: Bytes = ByteVector(42.toByte),
      declaration: Declaration =
        Declaration.Exact(Footprint.declaredCommitment(footprint).toOption.get),
      actual: Footprint = footprint,
      deadline: Height = height(20),
      preState: Hash = hash(6),
  ): PlanEntry =
    val manifestDigest = descriptor.manifestDigest
    val ctx            = ProtocolManifest.context(active).toOption.get
    val txId           = Commitment.hash(Utf8("neutral.transaction.id"), signed)
    val declarationDigest = Declaration.digest(declaration).toOption.get
    val executionId       = ExecutionIdentity
      .compute(
        ExecutionIdentityInput(
          ctx,
          manifestDigest,
          txId,
          signed,
          descriptor.fullInputCommitment,
          descriptor.lockSubsetCommitment,
          declarationDigest,
          hash(50),
          deadline,
        ),
      )
      .toOption
      .get
    PlanEntry(
      executionId,
      PlanSource.ConsensusTransaction(txId, signed, None),
      manifestDigest,
      declaration,
      declarationDigest,
      descriptor.fullInputCommitment,
      descriptor.lockSubsetCommitment,
      Footprint.actualCommitment(actual).toOption.get,
      hash(50),
      deadline,
      preState,
      None,
    )

  def signatureBytes(value: Signature): Bytes =
    ByteEncoder[Long].encode(value.v.toLong) ++ value.r.bytes ++ value.s.bytes

  def sign(preimage: Bytes, count: Int = 3): Vector[ValidatorSignature] = keys
    .take(count)
    .map: (id, pair) =>
      val signature =
        CryptoOps.sign(pair, CryptoOps.keccak256(preimage.toArray)).toOption.get
      ValidatorSignature(id, signatureBytes(signature))

  def lockFor(
      entry: PlanEntry,
      descriptor: InputDescriptor = input,
      active: ProtocolManifest = manifest,
      admissionBase: AdmissionBase = base,
  ): LockCertificate =
    val family = active.families
      .find(value => InputManifest.digest(value).contains(entry.manifestDigest))
      .get
    val subject = LockSubject(
      ProtocolManifest.context(active).toOption.get,
      entry.source.txId,
      entry.executionId,
      entry.manifestDigest,
      admissionBase,
      entry.fullInputCommitment,
      entry.lockSubsetCommitment,
      entry.dependencyPlanDigest,
      entry.lastInclusionHeight,
      InputDerivation
        .lockInputs(family, descriptor)
        .toOption
        .get
        .map(_.stableId),
    )
    LockCertificate(
      subject,
      sign(LockSubject.signingPreimage(subject).toOption.get),
    )

  def withLock(entry: PlanEntry, certificate: LockCertificate): PlanEntry =
    entry.copy(
      source = PlanSource.ConsensusTransaction(
        entry.source.txId,
        entry.source.signedTransaction,
        Some(LockCertificate.id(certificate).toOption.get),
      ),
    )

  def authentication(
      expectedContext: DomainContext,
      expectedBase: AdmissionBase = base,
  ): ArtifactAuthentication = new ArtifactAuthentication:
    def historicalValidators(
        context: DomainContext,
    ): Either[CoreFailure, Vector[Text]] =
      V2Validation
        .require(
          context == expectedContext,
          FailureCode.ProofInvalid,
          "historicalContext",
        )
        .map(_ => keys.map(_._1))
    def verifyFinalizedBase(
        context: DomainContext,
        base: AdmissionBase,
    ): Either[CoreFailure, Unit] =
      V2Validation.require(
        context == expectedContext && base == expectedBase,
        FailureCode.ProofInvalid,
        "base",
      )
    def verifySignature(
        context: DomainContext,
        signer: Text,
        preimage: Bytes,
        signature: Bytes,
    ): Either[CoreFailure, Unit] =
      val v      = BigInt(1, signature.take(8).toArray).toInt
      val signed = Signature(
        v,
        UInt256.unsafeFromBytesBE(signature.slice(8, 40)),
        UInt256.unsafeFromBytesBE(signature.drop(40)),
      )
      val expected = keys.find(_._1 == signer).map(_._2.publicKey)
      V2Validation.require(
        context == expectedContext && CryptoOps
          .recover(signed, CryptoOps.keccak256(preimage.toArray))
          .toOption == expected,
        FailureCode.InvalidSignature,
        "signature",
      )

/** Public maintenance-opening consumer: real neutral signatures and actual core
  * source/access validation. A fixed trusted historical base is supplied here;
  * physical transition and live-source authority remain separate JVM evidence.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.OptionPartial",
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Throw",
    "org.wartremover.warts.DefaultArguments",
    "org.wartremover.warts.IterableOps",
  ),
)
object V2OpeningConformance:
  private def assertEquals[A](actual: A, expected: A): Unit = assert(
    actual == expected,
  )

  import V2OpeningFixture.*

  private val openingFamily = family.copy(
    familyId = Utf8("neutral-opening"),
    fields = Vector(
      FieldManifest(
        Utf8("counter"),
        FieldRole.ExactMutate,
        hash(11),
        Some(hash(12)),
      ),
      FieldManifest(Utf8("read"), FieldRole.ExactRead, hash(21), Some(hash(22))),
    ),
  )
  private val active        = manifestFor(openingFamily)
  private val activeContext = ProtocolManifest.context(active).toOption.get
  private def openingInput(
      authority: Authority = Authority.ConsensusOnly,
  ): InputDescriptor = InputDerivation
    .derive(
      openingFamily,
      Vector(
        ResolvedField(
          Utf8("counter"),
          FieldRole.ExactMutate,
          ByteVector(4.toByte),
          Some(identity(1)),
          Some(ExactPrecondition(hash(11), ByteVector(4.toByte))),
          Some(authority),
        ),
        ResolvedField(
          Utf8("read"),
          FieldRole.ExactRead,
          ByteVector(7.toByte),
          Some(identity(2)),
          Some(ExactPrecondition(hash(21), ByteVector(7.toByte))),
          Some(Authority.ConsensusOnly),
        ),
      ),
    )
    .toOption
    .get
  private val actualFootprint = Footprint
    .canonical(Vector(identity(2)), Vector(identity(1), identity(3)))
    .toOption
    .get
  private val initial = OpeningEnvelope(
    1L,
    OpeningKind.InitialBootstrap,
    Utf8("legacy-source"),
    height(77),
    hash(6),
    hash(7),
    activeContext,
    activeContext.configurationDigest,
    hash(5),
    height(1),
    AdmissionBase.InitialAnchor(hash(5), height(0), hash(6), hash(8)),
    height(8),
    ByteVector(42.toByte),
    hash(70),
    Utf8("maintenance"),
  )
  private def signed(
      envelope: OpeningEnvelope,
      signerIndex: Int = 0,
  ): SignedOpening =
    val bytes = OpeningEnvelope.signingPreimage(envelope).toOption.get
    SignedOpening(
      envelope,
      signatureBytes(
        CryptoOps
          .sign(keys(signerIndex)._2, CryptoOps.keccak256(bytes.toArray))
          .toOption
          .get,
      ),
    )

  private val authorization = new MaintenanceAuthorizationVerifier:
    def verify(
        manifest: InputManifest,
        opening: SignedOpening,
        preimage: Bytes,
    ): Either[CoreFailure, Unit] =
      val bytes = opening.signature
      for
        _ <- V2Validation.require(
          manifest.maintenanceVerifierDigest.contains(
            hash(16),
          ) && opening.envelope.authorityId == Utf8("maintenance"),
          FailureCode.AuthorityMismatch,
          "maintenance.authority",
        )
        _ <- ValidatorSignature.validate(
          ValidatorSignature(Utf8("maintenance"), bytes),
        )
        signature = Signature(
          BigInt(1, bytes.take(8).toArray).toInt,
          UInt256.unsafeFromBytesBE(bytes.slice(8, 40)),
          UInt256.unsafeFromBytesBE(bytes.drop(40)),
        )
        _ <- V2Validation.require(
          CryptoOps
            .recover(signature, CryptoOps.keccak256(preimage.toArray))
            .toOption
            .contains(keys.head._2.publicKey),
          FailureCode.InvalidSignature,
          "maintenance.signature",
        )
      yield ()

  private def openingEntry(
      opening: SignedOpening,
      descriptor: InputDescriptor,
  ): PlanEntry = entryFor(
    descriptor,
    active,
    SignedOpening.codec.encode(opening).toOption.get,
    Declaration.Compatibility(opening.envelope.reasonDigest),
    actualFootprint,
    opening.envelope.lastInclusionHeight,
    opening.envelope.sourceRoot,
  )

  private val executor = new OpeningExecutor[Map[Int, Int]]:
    def execute(
        parentState: Map[Int, Int],
        opening: SignedOpening,
    ): Either[CoreFailure, OpeningExecution[Map[Int, Int]]] =
      val next =
        parentState.updated(1, parentState(1) + 1).updated(3, parentState(2))
      Right(
        OpeningExecution(
          next,
          ApplicationStateRoot(hash(99)),
          NormalizedApplicationResult.fromBytes(ByteVector(5.toByte, 7.toByte)),
          Vector(
            ActualAccess(
              identity(1),
              AccessKind.MutateExisting,
              Some(Authority.ConsensusOnly),
            ),
            ActualAccess(
              identity(2),
              AccessKind.ReadExisting,
              Some(Authority.ConsensusOnly),
            ),
            ActualAccess(identity(3), AccessKind.CreateAbsent, None),
          ),
        ),
      )

  def signedBootstrapExecution(): Unit =
    val opening    = signed(initial)
    val descriptor = openingInput()
    val entry      = openingEntry(opening, descriptor)
    assertEquals(
      OpeningValidation.validate(
        opening,
        entry,
        descriptor,
        active,
        openingFamily,
        authorization,
      ),
      Right(()),
    )
    assertEquals(
      AdmissionValidation.validateSource(
        entry,
        descriptor,
        None,
        None,
        active,
        authentication(activeContext),
      ),
      Right(()),
    )
    val parent   = Map(1 -> 4, 2 -> 7)
    val executed = OpeningValidation.executeValidated(
      parent,
      opening,
      entry,
      descriptor,
      active,
      openingFamily,
      authorization,
      executor,
    )
    assertEquals(executed.map(_.nextState), Right(Map(1 -> 5, 2 -> 7, 3 -> 7)))
    assertEquals(
      executed,
      OpeningValidation.executeValidated(
        parent,
        opening,
        entry,
        descriptor,
        active,
        openingFamily,
        authorization,
        executor,
      ),
    )
    assertEquals(parent, Map(1 -> 4, 2 -> 7))
    assertEquals(
      executed.map(_.actualAccesses.map(_.identity).toSet),
      Right(Set(identity(1), identity(2), identity(3))),
    )
    assertEquals(
      SignedOpening.codec.decode(
        SignedOpening.codec.encode(opening).toOption.get,
      ),
      Right(opening),
    )

  def signedEnvelopeBindings(): Unit =
    val opening         = signed(initial)
    val descriptor      = openingInput()
    val changedDeadline =
      opening.copy(envelope = initial.copy(lastInclusionHeight = height(9)))
    assertEquals(
      OpeningValidation
        .validate(
          changedDeadline,
          openingEntry(changedDeadline, descriptor),
          descriptor,
          active,
          openingFamily,
          authorization,
        )
        .left
        .map(_.code),
      Left(FailureCode.InvalidSignature),
    )
    val changedPayload = opening.copy(envelope =
      initial.copy(conversionPayload = ByteVector(43.toByte)),
    )
    assertEquals(
      OpeningValidation
        .validate(
          changedPayload,
          openingEntry(changedPayload, descriptor),
          descriptor,
          active,
          openingFamily,
          authorization,
        )
        .left
        .map(_.code),
      Left(FailureCode.InvalidSignature),
    )
    val otherSigner = signed(initial, 1)
    assertEquals(
      OpeningValidation
        .validate(
          otherSigner,
          openingEntry(otherSigner, descriptor),
          descriptor,
          active,
          openingFamily,
          authorization,
        )
        .left
        .map(_.code),
      Left(FailureCode.InvalidSignature),
    )
    val otherAuthority = signed(initial.copy(authorityId = Utf8("untrusted")))
    assertEquals(
      OpeningValidation
        .validate(
          otherAuthority,
          openingEntry(otherAuthority, descriptor),
          descriptor,
          active,
          openingFamily,
          authorization,
        )
        .left
        .map(_.code),
      Left(FailureCode.AuthorityMismatch),
    )
    assert(
      OpeningValidation
        .validate(
          opening,
          openingEntry(opening, descriptor),
          descriptor,
          active,
          openingFamily.copy(maintenanceVerifierDigest = None),
          authorization,
        )
        .isLeft,
    )

  def bootstrapLockExclusion(): Unit =
    val invalidEnvelopes = Vector(
      initial.copy(firstHeight = height(2)),
      initial.copy(parentId = hash(500)),
      initial.copy(sourceRoot = hash(600)),
      initial.copy(admissionBase =
        AdmissionBase.InitialAnchor(hash(5), height(1), hash(6), hash(8)),
      ),
      initial.copy(admissionBase = base),
      initial.copy(targetManifestDigest = hash(700)),
    )
    invalidEnvelopes.foreach(value =>
      assert(OpeningEnvelope.codec.encode(value).isLeft),
    )
    val opening  = signed(initial)
    val eligible = openingInput(Authority.LockEligible)
    val entry    = openingEntry(opening, eligible)
    assert(
      OpeningValidation
        .validate(
          opening,
          entry,
          eligible,
          active,
          openingFamily,
          authorization,
        )
        .isLeft,
    )
    val claimedLock = entry.copy(source =
      PlanSource.ConsensusTransaction(
        entry.source.txId,
        entry.source.signedTransaction,
        Some(hash(900)),
      ),
    )
    assertEquals(
      OpeningValidation
        .validate(
          opening,
          claimedLock,
          eligible,
          active,
          openingFamily,
          authorization,
        )
        .left
        .map(_.code),
      Left(FailureCode.OpeningMismatch),
    )
    val descriptor = openingInput()
    assertEquals(
      OpeningValidation
        .validate(
          opening,
          openingEntry(opening, descriptor).copy(entryPreStateRoot = hash(999)),
          descriptor,
          active,
          openingFamily,
          authorization,
        )
        .left
        .map(_.code),
      Left(FailureCode.OpeningMismatch),
    )

  def handoverHistoricalBaseAndLocks(): Unit =
    val envelope = initial.copy(
      kind = OpeningKind.Handover,
      admissionBase = base,
      firstHeight = height(11),
      lastInclusionHeight = height(20),
      parentId = hash(80),
    )
    val opening    = signed(envelope)
    val descriptor = openingInput(Authority.LockEligible)
    val entry      =
      openingEntry(opening, descriptor).copy(entryPreStateRoot = hash(81))
    val certificate = lockFor(entry, descriptor, active)
    val certified   = withLock(entry, certificate)
    assertEquals(
      OpeningValidation.validate(
        opening,
        certified,
        descriptor,
        active,
        openingFamily,
        authorization,
      ),
      Right(()),
    )
    assertEquals(
      AdmissionValidation.validateSource(
        certified,
        descriptor,
        Some(certificate),
        None,
        active,
        authentication(activeContext),
      ),
      Right(()),
    )
    assertEquals(
      AdmissionValidation
        .validateSource(
          certified,
          descriptor,
          None,
          None,
          active,
          authentication(activeContext),
        )
        .left
        .map(_.code),
      Left(FailureCode.MissingCertificate),
    )
    assertEquals(
      AdmissionValidation
        .validateSource(
          certified,
          descriptor,
          Some(certificate),
          None,
          active,
          authentication(
            activeContext,
            AdmissionBase.Finalized(hash(800), height(10), hash(6)),
          ),
        )
        .left
        .map(_.code),
      Left(FailureCode.ProofInvalid),
    )
    assert(
      OpeningEnvelope.codec
        .encode(envelope.copy(admissionBase = initial.admissionBase))
        .isLeft,
    )
    assert(
      OpeningEnvelope.codec
        .encode(envelope.copy(firstHeight = height(10)))
        .isLeft,
    )
    val expired = signed(envelope.copy(lastInclusionHeight = height(75)))
    assertEquals(
      OpeningValidation
        .validate(
          expired,
          openingEntry(expired, descriptor),
          descriptor,
          active,
          openingFamily,
          authorization,
        )
        .left
        .map(_.code),
      Left(FailureCode.InvalidDeadline),
    )

  def sourceManifestAndLifetime(): Unit =
    val opening    = signed(initial)
    val descriptor = openingInput()
    val entry      = openingEntry(opening, descriptor)
    val fast       = entry.copy(source =
      PlanSource.CertifiedFastExecution(
        entry.source.txId,
        entry.source.signedTransaction,
        hash(1),
        hash(2),
      ),
    )
    assertEquals(
      OpeningValidation
        .validate(
          opening,
          fast,
          descriptor,
          active,
          openingFamily,
          authorization,
        )
        .left
        .map(_.code),
      Left(FailureCode.OpeningMismatch),
    )
    val differentBytes = entry.copy(source =
      PlanSource.ConsensusTransaction(
        entry.source.txId,
        ByteVector(99.toByte),
        None,
      ),
    )
    assertEquals(
      OpeningValidation
        .validate(
          opening,
          differentBytes,
          descriptor,
          active,
          openingFamily,
          authorization,
        )
        .left
        .map(_.code),
      Left(FailureCode.OpeningMismatch),
    )
    assertEquals(
      OpeningValidation
        .validate(
          opening,
          entry.copy(declaration = Declaration.Compatibility(hash(999))),
          descriptor,
          active,
          openingFamily,
          authorization,
        )
        .left
        .map(_.code),
      Left(FailureCode.ClassificationMismatch),
    )
    assertEquals(
      OpeningValidation
        .validate(
          opening,
          entry.copy(lastInclusionHeight = height(9)),
          descriptor,
          active,
          openingFamily,
          authorization,
        )
        .left
        .map(_.code),
      Left(FailureCode.InvalidDeadline),
    )

  def completeActualExecution(): Unit =
    val opening    = signed(initial)
    val descriptor = openingInput()
    val entry      = openingEntry(opening, descriptor)
    val parent     = Map(1 -> 4, 2 -> 7)
    assertEquals(
      OpeningValidation
        .executeValidated(
          parent,
          opening,
          entry.copy(actualFootprintCommitment = hash(999)),
          descriptor,
          active,
          openingFamily,
          authorization,
          executor,
        )
        .left
        .map(_.code),
      Left(FailureCode.CommitmentMismatch),
    )
    val extraEligible = new OpeningExecutor[Map[Int, Int]]:
      def execute(parentState: Map[Int, Int], opening: SignedOpening) = executor
        .execute(parentState, opening)
        .map(value =>
          value.copy(actualAccesses =
            value.actualAccesses :+ ActualAccess(
              identity(4),
              AccessKind.MutateExisting,
              Some(Authority.LockEligible),
            ),
          ),
        )
    assertEquals(
      OpeningValidation
        .executeValidated(
          parent,
          opening,
          entry,
          descriptor,
          active,
          openingFamily,
          authorization,
          extraEligible,
        )
        .left
        .map(_.code),
      Left(FailureCode.AccessOmission),
    )
    val unnormalized = new OpeningExecutor[Map[Int, Int]]:
      def execute(parentState: Map[Int, Int], opening: SignedOpening) = executor
        .execute(parentState, opening)
        .map(value =>
          value.copy(result =
            value.result.copy(digest = ApplicationResultDigest(hash(999))),
          ),
        )
    assertEquals(
      OpeningValidation
        .executeValidated(
          parent,
          opening,
          entry,
          descriptor,
          active,
          openingFamily,
          authorization,
          unnormalized,
        )
        .left
        .map(_.code),
      Left(FailureCode.CommitmentMismatch),
    )

  def canonicalOpeningCodecs(): Unit =
    val opening = signed(initial)
    val bytes   = SignedOpening.codec.encode(opening).toOption.get
    assertEquals(
      SignedOpening.codec
        .decode(bytes ++ ByteVector(0.toByte))
        .left
        .map(_.code),
      Left(FailureCode.TrailingBytes),
    )
    assert(
      SignedOpening.codec
        .decode(bytes.take(8) ++ ByteVector(99.toByte) ++ bytes.drop(9))
        .isLeft,
    )
    assert(
      SignedOpening.codec
        .decode(
          ByteEncoder[SignedOpening]
            .encode(opening.copy(envelope = initial.copy(format = 2L))),
        )
        .isLeft,
    )
    assert(
      SignedOpening.codec
        .encode(opening.copy(signature = ByteVector.empty))
        .isLeft,
    )

  def run(): Unit =
    signedBootstrapExecution()
    signedEnvelopeBindings()
    bootstrapLockExclusion()
    handoverHistoricalBaseAndLocks()
    sourceManifestAndLifetime()
    completeActualExecution()
    canonicalOpeningCodecs()
    println(
      "V2OpeningConformance PASS: 7 real signed opening/source/lock/actual-access/codec cases",
    )
