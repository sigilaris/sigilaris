package org.sigilaris.conformance

import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.{
  ApplicationInputId,
  ExecutionId,
  InclusionHeight,
}
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.datatype.{BigNat, UInt256, Utf8}
import org.sigilaris.core.crypto.{CryptoOps, Signature}

/** Public core contract checks; authentication hooks below are neutral trusted
  * test adapters.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.OptionPartial",
    "org.wartremover.warts.Throw",
    "org.wartremover.warts.Any",
  ),
)
object V2CoreConformance:
  private def value[A](result: Either[CoreFailure, A]): A =
    result.fold(error => throw new AssertionError(error.message), identity)
  private def uint(hex: String): UInt256 = UInt256.fromHex(hex).toOption.get
  private def id(hex: String): ApplicationInputId =
    ApplicationInputId.fromBytes(ByteVector.fromValidHex(hex)).toOption.get
  private def height(value: Long): InclusionHeight = InclusionHeight(
    BigNat.unsafeFromLong(value),
  )
  private val schema       = uint("11")
  private val policy       = uint("22")
  private val transaction  = ByteVector.fromValidHex("010203")
  private val stateRoot    = uint("33")
  private val precondition =
    ExactPrecondition(schema, ByteVector.fromValidHex("01"))
  private val family = InputManifest(
    2L,
    Utf8("neutral.conformance"),
    1L,
    Vector(
      FieldManifest(
        Utf8("consensus"),
        FieldRole.ExactMutate,
        schema,
        Some(policy),
      ),
      FieldManifest(
        Utf8("mutable"),
        FieldRole.ExactMutate,
        schema,
        Some(policy),
      ),
      FieldManifest(Utf8("read"), FieldRole.ExactRead, schema, Some(policy)),
      FieldManifest(Utf8("static"), FieldRole.Immutable, schema, None),
    ),
    uint("44"),
    uint("55"),
    uint("66"),
    None,
  )
  private val fields = Vector(
    ResolvedField(
      Utf8("consensus"),
      FieldRole.ExactMutate,
      ByteVector.fromValidHex("01"),
      Some(id("01")),
      Some(precondition),
      Some(Authority.ConsensusOnly),
    ),
    ResolvedField(
      Utf8("mutable"),
      FieldRole.ExactMutate,
      ByteVector.fromValidHex("02"),
      Some(id("02")),
      Some(precondition),
      Some(Authority.LockEligible),
    ),
    ResolvedField(
      Utf8("read"),
      FieldRole.ExactRead,
      ByteVector.fromValidHex("03"),
      Some(id("03")),
      Some(precondition),
      Some(Authority.LockEligible),
    ),
    ResolvedField(
      Utf8("static"),
      FieldRole.Immutable,
      ByteVector.fromValidHex("04"),
      None,
      None,
      None,
    ),
  )
  private val evidence = fields
    .take(3)
    .map(field =>
      ResolutionEvidence(field.fieldId, ByteVector.fromValidHex("aa")),
    )
  private val authentication = new InputAuthentication:
    def verify(
        manifest: InputManifest,
        signedTransaction: Bytes,
        entryPreStateRoot: Hash,
        field: ResolvedField,
        proof: ResolutionEvidence,
    ): Either[CoreFailure, Unit] =
      Either.cond(
        manifest == family && signedTransaction == transaction && entryPreStateRoot == stateRoot &&
          fields.contains(
            field,
          ) && proof.fieldId == field.fieldId && proof.artifact == (if field.stableId.nonEmpty
                                                                    then
                                                                      ByteVector
                                                                        .fromValidHex(
                                                                          "aa",
                                                                        )
                                                                    else
                                                                      ByteVector.empty),
        (),
        CoreFailure.at(FailureCode.ProofInvalid, "neutralResolution"),
      )
    def verifyAbsentCreation(
        manifest: InputManifest,
        signedTransaction: Bytes,
        entryPreStateRoot: Hash,
        identity: InputId,
        evidence: Bytes,
    ): Either[CoreFailure, Unit] =
      Either.cond(
        manifest == family && signedTransaction == transaction && entryPreStateRoot == stateRoot && identity == id(
          "04",
        ) && evidence.nonEmpty,
        (),
        CoreFailure.at(FailureCode.ProofInvalid, "neutralAbsence"),
      )

  /** Representative bytes for the JVM exporter and shared golden assertions. */
  def canonicalVectors(): Vector[(String, Bytes)] =
    val descriptor = value(InputDerivation.derive(family, fields))
    val footprint  = value(
      Footprint.canonical(
        Vector(id("03")),
        Vector(id("01"), id("02"), id("04")),
      ),
    )
    val declaration =
      Declaration.Exact(value(Footprint.declaredCommitment(footprint)))
    val entry = PlanEntry(
      ExecutionId(uint("01")),
      PlanSource
        .ConsensusTransaction(uint("01"), transaction, Some(uint("aa"))),
      descriptor.manifestDigest,
      declaration,
      value(Declaration.digest(declaration)),
      descriptor.fullInputCommitment,
      descriptor.lockSubsetCommitment,
      value(Footprint.actualCommitment(footprint)),
      uint("44"),
      height(10L),
      stateRoot,
      None,
    )
    val statement = ClassificationStatement(
      2L,
      entry.manifestDigest,
      entry.source.txId,
      1.toByte,
      entry.entryPreStateRoot,
      entry.declarationDigest,
      Vector(ReferencedValue(id("02"), precondition)),
      Vector(ClassificationPurpose.Footprint),
    )
    val bound = entry.copy(classificationStatementCommitment =
      Some(value(ClassificationStatement.commitment(statement))),
    )
    val plan = ExecutionPlan(
      2L,
      Vector(ExecutionWave(WaveKind.Ordered, Vector(bound))),
      Vector(statement),
    )
    val witness = value(ReservationWitness.fromFootprint(footprint))
    val chunk   = value(
      ReservationWitness.chunks(witness, ProtocolLimits.V2),
    ).head
    Vector(
      "input-manifest"   -> value(InputManifest.codec.encode(family)),
      "input-descriptor" -> value(InputDescriptor.codec.encode(descriptor)),
      "footprint"        -> value(Footprint.codec.encode(footprint)),
      "classification-statement" -> value(
        ClassificationStatement.codec.encode(statement),
      ),
      "plan-entry"    -> value(PlanEntry.codec.encode(bound)),
      "nonempty-plan" -> value(ExecutionPlan.codec.encode(plan)),
      "empty-plan"    -> value(ExecutionPlan.codec.encode(ExecutionPlan.empty)),
      "reservation-witness" -> value(ReservationWitness.codec.encode(witness)),
      "witness-chunk"       -> value(WitnessChunk.codec.encode(chunk)),
    )

  def run(): Unit =
    strictAndEmptyVectors()
    malformedSignatureRecovery()
    independentInputCommitments()
    sourceAndWaveValidation()
    completeWitnessChunks()
    V2CertificateConformance.run()
    V2GoldenVectors.validate(
      canonicalVectors() ++ V2CertificateConformance.canonicalVectors(),
    )
    println(
      "V2CoreConformance PASS: independent inputs, strict codecs, source/declaration/classification plans, conflict rules and complete witness chunks",
    )

  def malformedSignatureRecovery(): Unit =
    val key   = CryptoOps.fromPrivate(BigInt(1))
    val one   = uint("01")
    val hash  = one.bytes.toArray
    val order = uint(
      "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141",
    )
    val malformed = Vector(
      Signature(27, uint("05"), one),
      Signature(28, uint("05"), one),
      Signature(27, key.publicKey.x, one),
    )
    malformed.foreach { signature =>
      val bytes =
        signature.v.toLong.toBytes ++ signature.r.bytes ++ signature.s.bytes
      assert(
        ValidatorSignature.validate(
          ValidatorSignature(Utf8("neutral-validator"), bytes),
        ) == Right(()),
      )
      assert(CryptoOps.recover(signature, hash).isLeft)
    }
    val valid = CryptoOps.sign(key, hash).toOption.get
    val high  = valid.copy(
      v = 27 + ((valid.v - 27) ^ 1),
      s = UInt256.unsafeFromBigIntUnsigned(
        BigInt(1, order.bytes.toArray) - BigInt(1, valid.s.bytes.toArray),
      ),
    )
    assert(CryptoOps.recover(valid, hash) == Right(key.publicKey))
    assert(CryptoOps.recover(high, hash) == Right(key.publicKey))
    Vector(
      valid.copy(v = 283),
      valid.copy(r = uint("00")),
      valid.copy(r = order),
      valid.copy(s = uint("00")),
      valid.copy(s = order),
    ).foreach { signature =>
      val bytes =
        signature.v.toLong.toBytes ++ signature.r.bytes ++ signature.s.bytes
      assert(
        ValidatorSignature
          .validate(ValidatorSignature(Utf8("v2-validator"), bytes))
          .isLeft,
      )
    }

  def strictAndEmptyVectors(): Unit =
    val emptyBytes = ByteVector.fromValidHex("00000000000000020000")
    val emptyRoot  =
      "f57b8db122206b5093612bebea2ce59ce4c8822f2a003bf6bc506c4751d43f3b"
    assert(ExecutionPlan.codec.encode(ExecutionPlan.empty) == Right(emptyBytes))
    assert(ExecutionPlan.codec.decode(emptyBytes) == Right(ExecutionPlan.empty))
    assert(
      value(
        ExecutionPlan.computeRoot(ExecutionPlan.empty),
      ).toBytes.toHex == emptyRoot,
    )
    val expectedPreimage =
      "2c736967696c617269732e6170706c69636174696f6e2e657865637574696f6e2d706c616e2e726f6f742e76320a00000000000000020000"
    assert(
      Commitment
        .preimage(
          Utf8("sigilaris.application.execution-plan.root.v2"),
          emptyBytes,
        )
        .toHex == expectedPreimage,
    )
    assert(
      ExecutionPlan.codec
        .decode(emptyBytes ++ ByteVector(0.toByte))
        .left
        .exists(_.code == FailureCode.TrailingBytes),
    )
    assert(
      ExecutionPlan.codec.encode(ExecutionPlan.empty.copy(format = 1L)).isLeft,
    )
    assert(WaveKind.codec.decode(ByteVector(0xff.toByte)).isLeft)
    assert(FieldRole.codec.decode(ByteVector(0xff.toByte)).isLeft)
    assert(
      ExecutionWave.codec
        .encode(ExecutionWave(WaveKind.Ordered, Vector.empty))
        .isLeft,
    )
    val unavailable = new EntryAuthentication:
      def verify(
          entry: PlanEntry,
          statement: Option[ClassificationStatement],
      ): Either[CoreFailure, VerifiedEntry] =
        Left(
          CoreFailure.at(FailureCode.ProofUnavailable, "unexpectedEmptyEntry"),
        )
    assert(
      ExecutionPlan.validate(
        ExecutionPlan.empty,
        Vector.empty,
        unavailable,
      ) == Right(Vector.empty),
    )
    assert(
      ExecutionPlan
        .validate(
          ExecutionPlan.empty,
          Vector(BodyMember(uint("01"), ExecutionId(uint("01")))),
          unavailable,
        )
        .isLeft,
    )
    assert(
      InputManifest.codec
        .encode(family.copy(familyId = Utf8(" neutral.conformance")))
        .isLeft,
    )
    assert(
      InputManifest.codec
        .encode(family.copy(fields = family.fields.reverse))
        .isLeft,
    )
    assert(
      InputManifest.codec
        .encode(family.copy(fields = family.fields ++ family.fields))
        .isLeft,
    )
    val context =
      DomainContext(2L, Utf8("neutral-conformance"), uint("01"), 0L, uint("02"))
    assert(
      DomainContext.codec.decode(
        value(DomainContext.codec.encode(context)),
      ) == Right(context),
    )
    assert(
      DomainContext.codec
        .encode(context.copy(chainId = Utf8("Neutral-Conformance")))
        .isLeft,
    )
    assert(
      ProtocolLimits.codec
        .encode(ProtocolLimits.V2.copy(maxIdentities = 100001L))
        .isLeft,
    )
    println(
      s"v2-empty-plan bytes=${emptyBytes.toHex} preimage=$expectedPreimage root=$emptyRoot",
    )

  def independentInputCommitments(): Unit =
    val descriptor = value(InputDerivation.derive(family, fields))
    val locks      = value(InputDerivation.lockInputs(family, descriptor))
    assert(locks.map(_.stableId) == Vector(id("02")))
    assert(
      InputDerivation.validate(
        family,
        transaction,
        stateRoot,
        descriptor,
        evidence,
        authentication,
      ) == Right(()),
    )
    assert(
      InputDerivation
        .validate(
          family,
          transaction,
          stateRoot,
          descriptor,
          evidence.drop(1),
          authentication,
        )
        .isLeft,
    )
    assert(
      InputDerivation
        .validate(
          family,
          transaction,
          uint("ff"),
          descriptor,
          evidence,
          authentication,
        )
        .isLeft,
    )
    assert(
      InputDescriptor.codec.decode(
        value(InputDescriptor.codec.encode(descriptor)),
      ) == Right(descriptor),
    )
    val changedStatic = value(
      InputDerivation.derive(
        family,
        fields.updated(3, fields(3).copy(value = ByteVector.fromValidHex("ff"))),
      ),
    )
    assert(
      InputDerivation
        .validate(
          family,
          transaction,
          stateRoot,
          changedStatic,
          evidence,
          authentication,
        )
        .isLeft,
    )
    val opaqueFamily = family.copy(fields =
      Vector(FieldManifest(Utf8("opaque"), FieldRole.Opaque, schema, None)),
    )
    val opaqueField = ResolvedField(
      Utf8("opaque"),
      FieldRole.Opaque,
      transaction,
      None,
      None,
      None,
    )
    val opaqueInput = value(
      InputDerivation.derive(opaqueFamily, Vector(opaqueField)),
    )
    assert(value(InputDerivation.lockInputs(opaqueFamily, opaqueInput)).isEmpty)
    assert(
      InputDescriptor.codec.decode(
        value(InputDescriptor.codec.encode(opaqueInput)),
      ) == Right(opaqueInput),
    )
    assert(
      InputDerivation
        .derive(
          opaqueFamily,
          Vector(opaqueField.copy(authority = Some(Authority.LockEligible))),
        )
        .isLeft,
    )
    val changedRead =
      fields.updated(2, fields(2).copy(value = ByteVector.fromValidHex("99")))
    val changed = value(InputDerivation.derive(family, changedRead))
    assert(changed.fullInputCommitment != descriptor.fullInputCommitment)
    assert(changed.lockSubsetCommitment == descriptor.lockSubsetCommitment)
    val forgedFields = fields.updated(
      0,
      fields.head.copy(authority = Some(Authority.LockEligible)),
    )
    val forged = value(InputDerivation.derive(family, forgedFields))
    assert(
      InputDerivation
        .validate(
          family,
          transaction,
          stateRoot,
          forged,
          evidence,
          authentication,
        )
        .isLeft,
    )
    assert(
      InputDerivation
        .derive(family, fields.updated(1, fields(1).copy(precondition = None)))
        .isLeft,
    )
    assert(
      InputDerivation
        .derive(
          family,
          fields.updated(1, fields(1).copy(stableId = Some(id("01")))),
        )
        .isLeft,
    )
    val unlocked = value(
      InputDerivation.derive(
        family,
        fields.updated(
          1,
          fields(1).copy(authority = Some(Authority.ConsensusOnly)),
        ),
      ),
    )
    assert(value(InputDerivation.lockInputs(family, unlocked)).isEmpty)
    val footprint = value(
      Footprint.canonical(
        Vector(id("03")),
        Vector(id("01"), id("02"), id("04")),
      ),
    )
    val actual = Vector(
      ActualAccess(
        id("01"),
        AccessKind.MutateExisting,
        Some(Authority.ConsensusOnly),
      ),
      ActualAccess(
        id("02"),
        AccessKind.MutateExisting,
        Some(Authority.LockEligible),
      ),
      ActualAccess(
        id("03"),
        AccessKind.ReadExisting,
        Some(Authority.LockEligible),
      ),
      ActualAccess(id("04"), AccessKind.CreateAbsent, None),
    )
    assert(
      InputDerivation.validateActual(
        descriptor,
        Some(footprint),
        actual,
      ) == Right(footprint),
    )
    assert(
      InputDerivation.validateActual(descriptor, None, actual) == Right(
        footprint,
      ),
    )
    val omitted = value(
      Footprint.canonical(Vector(id("03")), Vector(id("01"), id("02"))),
    )
    assert(
      InputDerivation.validateActual(descriptor, Some(omitted), actual).isLeft,
    )
    assert(
      InputDerivation
        .validateActual(
          descriptor,
          None,
          actual :+ ActualAccess(
            id("05"),
            AccessKind.MutateExisting,
            Some(Authority.LockEligible),
          ),
        )
        .isLeft,
    )
    assert(
      InputDerivation
        .validateActual(
          descriptor,
          None,
          actual :+ ActualAccess(
            id("04"),
            AccessKind.ReadExisting,
            Some(Authority.ConsensusOnly),
          ),
        )
        .isLeft,
    )
    assert(Footprint.canonical(Vector(id("03"), id("03")), Vector.empty).isLeft)
    val normalized = value(
      Footprint.canonical(Vector(id("01"), id("03")), Vector(id("01"))),
    )
    assert(normalized.reads == Vector(id("03")))
    assert(
      Footprint.codec.encode(normalized.copy(reads = Vector(id("01")))).isLeft,
    )
    assert(
      value(Footprint.declaredCommitment(footprint)) != value(
        Footprint.actualCommitment(footprint),
      ),
    )
    assert(
      descriptor.fullInputCommitment.toBytes.toHex == "ea962a53058167685725f7e5848eb26249a1bfc1b59528b484183268d0709402",
    )
    assert(
      descriptor.lockSubsetCommitment.toBytes.toHex == "bc5e424402649e253e145f8dbc2e05a2323e6cb63f7c8f0d86bd9eca28bc315a",
    )
    assert(
      value(
        Footprint.actualCommitment(footprint),
      ).toBytes.toHex == "9f64c60855e490013525082afda649630e7f4bf7554934638073523c2f4a50bc",
    )
    println(
      s"v2-input full=${descriptor.fullInputCommitment.toBytes.toHex} lock=${descriptor.lockSubsetCommitment.toBytes.toHex} actual=${value(Footprint.actualCommitment(footprint)).toBytes.toHex}",
    )

  def sourceAndWaveValidation(): Unit =
    val descriptor  = value(InputDerivation.derive(family, fields))
    val footprint   = value(Footprint.canonical(Vector.empty, Vector(id("02"))))
    val declaration =
      Declaration.Exact(value(Footprint.declaredCommitment(footprint)))
    def entry(index: Int): PlanEntry =
      PlanEntry(
        ExecutionId(uint(index.toHexString)),
        PlanSource.ConsensusTransaction(
          uint(index.toHexString),
          transaction,
          Some(uint("aa")),
        ),
        descriptor.manifestDigest,
        declaration,
        value(Declaration.digest(declaration)),
        descriptor.fullInputCommitment,
        descriptor.lockSubsetCommitment,
        value(Footprint.actualCommitment(footprint)),
        uint("44"),
        height(10L),
        stateRoot,
        None,
      )
    val first  = entry(1)
    val second = entry(2)
    val auth   = new EntryAuthentication:
      def verify(
          entry: PlanEntry,
          statement: Option[ClassificationStatement],
      ): Either[CoreFailure, VerifiedEntry] =
        Right(VerifiedEntry(entry, Some(footprint), footprint))
    def plan(kind: WaveKind): ExecutionPlan = ExecutionPlan(
      2L,
      Vector(
        ExecutionWave(kind, Vector(first)),
        ExecutionWave(WaveKind.Ordered, Vector(second)),
      ),
      Vector.empty,
    )
    val body = Vector(
      BodyMember(first.source.txId, first.executionId),
      BodyMember(second.source.txId, second.executionId),
    )
    assert(ExecutionPlan.validate(plan(WaveKind.Ordered), body, auth).isRight)
    assert(
      ExecutionPlan
        .validate(plan(WaveKind.ConflictFree), body, auth)
        .left
        .exists(_.code == FailureCode.ForbiddenOverlap),
    )
    assert(
      ExecutionPlan.validate(plan(WaveKind.Ordered), body.take(1), auth).isLeft,
    )
    val changedActual = first.copy(actualFootprintCommitment = uint("ff"))
    assert(
      ExecutionPlan
        .validate(
          ExecutionPlan(
            2L,
            Vector(ExecutionWave(WaveKind.Ordered, Vector(changedActual))),
            Vector.empty,
          ),
          body.take(1),
          auth,
        )
        .isLeft,
    )
    assert(
      PlanEntry.codec.encode(first.copy(declarationDigest = uint("ff"))).isLeft,
    )
    val fast = first.copy(source =
      PlanSource.CertifiedFastExecution(
        first.source.txId,
        transaction,
        uint("aa"),
        uint("bb"),
      ),
    )
    assert(
      value(PlanEntry.codec.encode(first)) != value(
        PlanEntry.codec.encode(fast),
      ),
    )
    val statement = ClassificationStatement(
      2L,
      first.manifestDigest,
      first.source.txId,
      1.toByte,
      first.entryPreStateRoot,
      first.declarationDigest,
      Vector.empty,
      Vector(ClassificationPurpose.Footprint),
    )
    val bound = first.copy(classificationStatementCommitment =
      Some(value(ClassificationStatement.commitment(statement))),
    )
    val classified = ExecutionPlan(
      2L,
      Vector(ExecutionWave(WaveKind.Ordered, Vector(bound))),
      Vector(statement),
    )
    assert(ExecutionPlan.validate(classified, body.take(1), auth).isRight)
    assert(
      ExecutionPlan.codec
        .encode(classified.copy(statements = Vector.empty))
        .isLeft,
    )
    assert(
      ExecutionPlan.codec
        .encode(
          classified.copy(waves =
            Vector(ExecutionWave(WaveKind.Ordered, Vector(first))),
          ),
        )
        .isLeft,
    )
    assert(
      ClassificationStatement.codec
        .encode(
          statement.copy(purposes =
            Vector(
              ClassificationPurpose.Footprint,
              ClassificationPurpose.Admission,
            ),
          ),
        )
        .isLeft,
    )
    assert(
      ExecutionPlan.codec
        .encode(
          classified.copy(waves =
            Vector(
              ExecutionWave(
                WaveKind.Ordered,
                Vector(bound.copy(source = fast.source)),
              ),
            ),
          ),
        )
        .isLeft,
    )
    val compatibility = Declaration.Compatibility(uint("ee"))
    val opening       = first.copy(
      source =
        PlanSource.ConsensusTransaction(first.source.txId, transaction, None),
      declaration = compatibility,
      declarationDigest = value(Declaration.digest(compatibility)),
    )
    val singleton =
      ExecutionWave(WaveKind.CompatibilitySingleton, Vector(opening))
    assert(
      ExecutionPlan.codec
        .encode(ExecutionPlan(2L, Vector(singleton), Vector.empty))
        .isRight,
    )
    assert(
      ExecutionPlan.codec
        .encode(
          ExecutionPlan(
            2L,
            Vector(singleton, ExecutionWave(WaveKind.Ordered, Vector(second))),
            Vector.empty,
          ),
        )
        .isLeft,
    )
    assert(
      ExecutionWave.codec
        .encode(ExecutionWave(WaveKind.Ordered, Vector(opening)))
        .isLeft,
    )
    assert(
      value(
        ExecutionPlan.computeRoot(plan(WaveKind.Ordered)),
      ).toBytes.toHex == "5b186d4c235b3fa831c9f29b2f476f59c4cc0ef80c64cdeb730e1b5f2ad3b5e0",
    )
    assert(
      value(
        ExecutionPlan.computeRoot(classified),
      ).toBytes.toHex == "e4b271a76776c348817ce43270577e3a0905105ae314d42044de072cd501638d",
    )
    println(
      s"v2-plan orderedRoot=${value(ExecutionPlan.computeRoot(plan(WaveKind.Ordered))).toBytes.toHex} classifiedRoot=${value(ExecutionPlan.computeRoot(classified)).toBytes.toHex}",
    )

  def completeWitnessChunks(): Unit =
    val ids = Vector.tabulate(2500)(index =>
      ApplicationInputId.fromBytes(uint(index.toHexString).toBytes).toOption.get,
    )
    val footprint = value(Footprint.canonical(Vector.empty, ids))
    val witness   = value(ReservationWitness.fromFootprint(footprint))
    val chunks    = value(ReservationWitness.chunks(witness, ProtocolLimits.V2))
    assert(chunks.size > 1)
    assert(
      ReservationWitness.reassemble(chunks, ProtocolLimits.V2) == Right(witness),
    )
    assert(
      ReservationWitness.reassemble(chunks.reverse, ProtocolLimits.V2).isLeft,
    )
    assert(
      ReservationWitness.reassemble(chunks.drop(1), ProtocolLimits.V2).isLeft,
    )
    val corrupted =
      chunks.updated(0, chunks.head.copy(bytes = chunks.head.bytes.drop(1)))
    assert(ReservationWitness.reassemble(corrupted, ProtocolLimits.V2).isLeft)
    val empty = value(
      ReservationWitness.fromFootprint(
        value(Footprint.canonical(Vector.empty, Vector.empty)),
      ),
    )
    assert(
      value(
        ReservationWitness.codec.encode(empty),
      ).toHex == "000000000000000100",
    )
    assert(value(ReservationWitness.chunks(empty, ProtocolLimits.V2)).size == 1)
    assert(
      value(
        ReservationWitness.digest(witness),
      ).toBytes.toHex == "3b60a9374962e8f69496991580f0590573a250f181190c10f71063039f911474",
    )
    println(
      s"v2-witness count=${chunks.size} digest=${value(ReservationWitness.digest(witness)).toBytes.toHex}",
    )
