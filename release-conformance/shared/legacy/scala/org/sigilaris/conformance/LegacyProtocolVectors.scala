package org.sigilaris.conformance

import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.*
import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.crypto.CryptoOps
import org.sigilaris.core.datatype.{BigNat, UInt256, Utf8}

enum LegacyProfile:
  case M1, M2

/** Historical protocol-1 vectors. Artifact provenance selects the old validator
  * behavior.
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
object LegacyProtocolVectors:
  private given ByteEncoder[ByteVector] = bytes =>
    BigNat.unsafeFromLong(bytes.size).toBytes ++ bytes
  private given [A: ByteEncoder]: ByteEncoder[Vector[A]] =
    ByteEncoder[List[A]].contramap(_.toList)
  private case class FullInput(
      family: Utf8,
      version: ProtocolVersion,
      fields: Vector[ApplicationFieldDescriptor],
  )
  private object FullInput:
    given ByteEncoder[FullInput] = ByteEncoder.derived
  private case class LockEntry(
      fieldId: Utf8,
      role: ApplicationFieldRole,
      inputId: ApplicationInputId,
  )
  private object LockEntry:
    given ByteEncoder[LockEntry] = ByteEncoder.derived
  private case class LockInput(
      family: Utf8,
      version: ProtocolVersion,
      entries: Vector[LockEntry],
  )
  private object LockInput:
    given ByteEncoder[LockInput] = ByteEncoder.derived
  private case class FootprintEntry(
      inputId: ApplicationInputId,
      writes: Boolean,
  )
  private object FootprintEntry:
    given ByteEncoder[FootprintEntry] = ByteEncoder.derived
  private case class FootprintInput(
      family: Utf8,
      version: ProtocolVersion,
      entries: Vector[FootprintEntry],
  )
  private object FootprintInput:
    given ByteEncoder[FootprintInput] = ByteEncoder.derived

  private def hash(domain: String, payload: ByteVector): UInt256 =
    UInt256.unsafeFromBytesBE(
      ByteVector.view(
        CryptoOps.keccak256((Utf8(domain).toBytes ++ payload.toBytes).toArray),
      ),
    )
  private def uint(value: String): UInt256 = UInt256.fromHex(value).toOption.get
  private def input(value: String): ApplicationInputId =
    ApplicationInputId.fromBytes(ByteVector.fromValidHex(value)).toOption.get
  private def execution(value: String): ExecutionId = ExecutionId(uint(value))

  def run(profile: LegacyProfile): Unit =
    familyAndDuplicateBehavior(profile)
    descriptorVectors()
    planVectors()
    certificateVectors()
    println(s"LegacyProtocolVectors $profile PASS")

  def familyAndDuplicateBehavior(profile: LegacyProfile): Unit =
    val raw    = "  neutral.family  "
    val family = ApplicationFamilyId.parse(raw).toOption.get
    // Preserve the original bytes independently of the newer parser's normalization.
    val m1Bytes =
      ByteVector.fromValidHex("1220206e65757472616c2e66616d696c792020")
    val m2Bytes = ByteVector.fromValidHex("0e6e65757472616c2e66616d696c79")
    assert(Utf8(raw).toBytes == m1Bytes)
    assert(Utf8(raw.trim).toBytes == m2Bytes)
    assert(family.toBytes == (if profile == LegacyProfile.M1 then m1Bytes
                              else m2Bytes))
    val fields = Vector(
      ApplicationFieldDescriptor(
        Utf8("read"),
        ApplicationFieldRole.MutableRead,
        ByteVector.fromValidHex("01"),
        Some(input("aa")),
      ),
      ApplicationFieldDescriptor(
        Utf8("write"),
        ApplicationFieldRole.MutableWrite,
        ByteVector.fromValidHex("02"),
        Some(input("aa")),
      ),
    )
    val manifest = ApplicationFamilyManifest(
      ProtocolVersion.M1,
      family,
      fields.map(f => ApplicationFieldManifest(f.fieldId, f.role)),
    )
    val result = ApplicationInputDescriptor.validate(
      manifest,
      ApplicationInputDescriptor.normalize(family, ProtocolVersion.M1, fields),
    )
    if profile == LegacyProfile.M1 then assert(result == Right(()))
    else
      assert(
        result.left.exists(_.toString.startsWith("DuplicateStableConflictId(")),
      )
    println(
      s"legacy-family $profile bytes=${family.toBytes.toHex} duplicateStableIdentity=${
          if result.isRight then "accepted" else "rejected"
        }",
    )

  def descriptorVectors(): Unit =
    val family = ApplicationFamilyId.parse("neutral.baseline").toOption.get
    val fields = Vector(
      ApplicationFieldDescriptor(
        Utf8("read"),
        ApplicationFieldRole.MutableRead,
        ByteVector.fromValidHex("01"),
        Some(input("aa")),
      ),
      ApplicationFieldDescriptor(
        Utf8("write"),
        ApplicationFieldRole.MutableWrite,
        ByteVector.fromValidHex("02"),
        Some(input("bb")),
      ),
    )
    val descriptor =
      ApplicationInputDescriptor.normalize(family, ProtocolVersion.M1, fields)
    val full =
      FullInput(Utf8(family.asString), ProtocolVersion.M1, fields).toBytes
    val locks = LockInput(
      Utf8(family.asString),
      ProtocolVersion.M1,
      Vector(
        LockEntry(Utf8("read"), ApplicationFieldRole.MutableRead, input("aa")),
        LockEntry(Utf8("write"), ApplicationFieldRole.MutableWrite, input("bb")),
      ),
    ).toBytes
    val footprint = FootprintInput(
      Utf8(family.asString),
      ProtocolVersion.M1,
      Vector(
        FootprintEntry(input("aa"), false),
        FootprintEntry(input("bb"), true),
      ),
    ).toBytes
    assert(
      descriptor == ApplicationInputDescriptor.normalize(
        family,
        ProtocolVersion.M1,
        fields.reverse,
      ),
    )
    assert(
      hash(
        "sigilaris.application.input.full.v1",
        full,
      ) == descriptor.fullInputCommitment,
    )
    assert(
      hash(
        "sigilaris.application.input.lock-subset.v1",
        locks,
      ) == descriptor.lockSubsetCommitment,
    )
    assert(
      hash(
        "sigilaris.application.input.footprint.v1",
        footprint,
      ) == descriptor.footprintCommitment,
    )
    assert(
      full.toHex == "106e65757472616c2e626173656c696e6500000000000000010204726561640201010101aa0577726974650301020101bb",
    )
    assert(
      locks.toHex == "106e65757472616c2e626173656c696e6500000000000000010204726561640201aa0577726974650301bb",
    )
    assert(
      footprint.toHex == "106e65757472616c2e626173656c696e6500000000000000010201aa0001bb01",
    )
    assert(
      descriptor.fullInputCommitment.toBytes.toHex == "1f75759b1a09fbfd53e272070320894ca124b03b3f80e3d95347bf2f7b840748",
    )
    assert(
      descriptor.lockSubsetCommitment.toBytes.toHex == "0f1a270021fbf1582ac9388f75449f8aad654f46533bb97597e5356a801142ef",
    )
    assert(
      descriptor.footprintCommitment.toBytes.toHex == "aa2b6dd77263e934041ad770ead2f6a708ed6c944ac133047784ee89575d804a",
    )
    println(
      s"legacy-descriptor full=${full.toHex} locks=${locks.toHex} footprint=${footprint.toHex}",
    )

  def planVectors(): Unit =
    val empty = ExecutionPlan(ExecutionPlanVersion.V1, Vector.empty)
    assert(empty.toBytes.toHex == "000000000000000100")
    assert(
      ExecutionPlan
        .computeRoot(empty)
        .toBytes
        .toHex == "a57ba92e8039cd7cdafc2efb59ec67938254cf872a46f3accb3fb958e526ac4c",
    )
    assert(
      ExecutionPlan.validate(empty, Vector.empty) == Left(
        ExecutionPlanValidationFailure.EmptyPlan,
      ),
    )
    val ids     = Vector(execution("01"), execution("02"))
    val ordered = empty.copy(waves = Vector(ExecutionWave.Ordered(ids)))
    assert(ExecutionPlan.validate(ordered, ids) == Right(()))
    assert(
      ExecutionPlan
        .computeRoot(ordered)
        .toBytes
        .toHex == "a06a14cbe90a0894d9c09de125960bfc059740aeaad0914c245ae83b6d2ecbf3",
    )
    assert(
      ExecutionPlan.computeRoot(ordered) != ExecutionPlan.computeRoot(
        empty.copy(waves = Vector(ExecutionWave.Ordered(ids.reverse))),
      ),
    )
    assert(
      ExecutionPlan.validate(
        empty.copy(waves = Vector(ExecutionWave.ConflictFree(ids.reverse))),
        ids,
      ) == Left(ExecutionPlanValidationFailure.ConflictFreeOrderMismatch(0)),
    )
    assert(ExecutionPlan.validate(ordered, ids.take(1)).isLeft)
    assert(
      ExecutionPlan
        .validate(
          empty.copy(waves = Vector(ExecutionWave.Ordered(ids ++ ids))),
          ids,
        )
        .isLeft,
    )
    val singleton = ExecutionPlan.compatibilitySingleton(ids.head)
    assert(
      singleton.toBytes.toHex == "00000000000000010102010000000000000000000000000000000000000000000000000000000000000001",
    )
    assert(
      ExecutionPlan
        .computeRoot(singleton)
        .toBytes
        .toHex == "1992e415b633263a7e62eaf030617ce98851b1d50070c4465361b2e229a20a02",
    )
    println(
      s"legacy-plan ordered=${ordered.toBytes.toHex} root=${ExecutionPlan.computeRoot(ordered).toBytes.toHex}",
    )

  def certificateVectors(): Unit =
    val validators = Vector("v1", "v2", "v3", "v4").map(v =>
      ApplicationValidatorId.parse(v).toOption.get,
    )
    val set = HistoricalApplicationValidatorSet(
      ApplicationEpoch(3L),
      ApplicationValidatorSetHash(uint("91")),
      validators,
    )
    val subject = ApplicationLockVoteSubject(
      ProtocolVersion.M1,
      ApplicationConfigurationDigest(uint("90")),
      ApplicationEpoch(3L),
      ApplicationValidatorSetHash(uint("91")),
      execution("11"),
      DependencyPlanDigest(uint("92")),
      InclusionHeight(BigNat.unsafeFromLong(10L)),
      Vector(input("aa")),
    )
    val preimage = ApplicationLockVoteSubject.signingPreimage(subject)
    assert(
      preimage.toHex == "22736967696c617269732e6170706c69636174696f6e2e6c6f636b2e766f74652e76310000000000000001000000000000000000000000000000000000000000000000000000000000009000000000000000030000000000000000000000000000000000000000000000000000000000000091000000000000000000000000000000000000000000000000000000000000001100000000000000000000000000000000000000000000000000000000000000920a0101aa",
    )
    val votes =
      validators.take(3).map(v => ApplicationLockVote(subject, v, preimage))
    val certificate = ApplicationLockCertificate(subject, votes)
    val verify      = (
        _: ApplicationValidatorId,
        expected: ByteVector,
        signature: ByteVector,
    ) => expected == signature
    assert(
      ApplicationLockCertificate.verify(certificate, set)(verify) == Right(()),
    )
    assert(
      certificate.toBytes == certificate.copy(votes = votes.reverse).toBytes,
    )
    assert(
      ApplicationLockCertificate
        .verify(certificate.copy(votes = votes.take(2)), set)(verify)
        .isLeft,
    )
    assert(
      ApplicationLockCertificate
        .verify(
          certificate.copy(votes = Vector(votes(0), votes(0), votes(1))),
          set,
        )(verify)
        .isLeft,
    )
    assert(
      ApplicationLockCertificate.verify(
        certificate
          .copy(subject = subject.copy(inputIds = Vector.empty), votes = votes),
        set,
      )(verify) == Left(ApplicationCertificateFailure.EmptyLockInputs),
    )
    val effect = CertifiedEffectVoteSubject(
      subject.protocolVersion,
      subject.configurationDigest,
      subject.epoch,
      subject.validatorSetHash,
      execution("12"),
      subject.dependencyPlanDigest,
      subject.lastInclusionHeight,
      ApplicationResultDigest(uint("93")),
      ApplicationStateRoot(uint("94")),
    )
    val effectPreimage = CertifiedEffectVoteSubject.signingPreimage(effect)
    assert(
      effectPreimage.toHex == "24736967696c617269732e6170706c69636174696f6e2e6566666563742e766f74652e76310000000000000001000000000000000000000000000000000000000000000000000000000000009000000000000000030000000000000000000000000000000000000000000000000000000000000091000000000000000000000000000000000000000000000000000000000000001200000000000000000000000000000000000000000000000000000000000000920a00000000000000000000000000000000000000000000000000000000000000930000000000000000000000000000000000000000000000000000000000000094",
    )
    val effects = validators
      .take(3)
      .map(v => CertifiedEffectVote(effect, v, effectPreimage))
    val effectCertificate = CertifiedEffectCertificate(effect, effects)
    assert(
      CertifiedEffectCertificate.verify(effectCertificate, set)(
        verify,
      ) == Right(()),
    )
    assert(
      effectCertificate.toBytes == effectCertificate
        .copy(votes = effects.reverse)
        .toBytes,
    )
    println(s"legacy-lock-signing ${preimage.toHex}")
    println(s"legacy-effect-signing ${effectPreimage.toHex}")
    assert(
      ByteVector
        .view(CryptoOps.keccak256(certificate.toBytes.toArray))
        .toHex == "2c4cc13676a4f172f925fe29ce57de7468b3f235054bfc513b7df30c8dc8c9cc",
    )
    assert(
      ByteVector
        .view(CryptoOps.keccak256(effectCertificate.toBytes.toArray))
        .toHex == "375f651f59f3969a7ef1dd4e5f577e20bae9b61472be3abbcdfd7c8d1b661fb1",
    )
    println(
      s"legacy-certificate-digests lock=${ByteVector.view(CryptoOps.keccak256(certificate.toBytes.toArray)).toHex} effect=${ByteVector.view(CryptoOps.keccak256(effectCertificate.toBytes.toArray)).toHex}",
    )
