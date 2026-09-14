import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.*
import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.crypto.CryptoOps
import org.sigilaris.core.datatype.{BigNat, UInt256, Utf8}

/** Immutable M2 behavior, including limitations that a future profile replaces.
  */
object M2SharedBaseline:
  private given ByteEncoder[ByteVector] = bytes =>
    BigNat.unsafeFromLong(bytes.size).toBytes ++ bytes
  private given [A: ByteEncoder]: ByteEncoder[Vector[A]] =
    ByteEncoder[List[A]].contramap(_.toList)
  private case class LockEntry(
      fieldId: Utf8,
      role: ApplicationFieldRole,
      inputId: ApplicationInputId,
  )
  private object LockEntry:
    given ByteEncoder[LockEntry] = ByteEncoder.derived
  private case class LockPreimage(
      familyId: ApplicationFamilyId,
      version: ProtocolVersion,
      entries: Vector[LockEntry],
  )
  private object LockPreimage:
    given ByteEncoder[LockPreimage] = ByteEncoder.derived
  private case class FootprintEntry(
      inputId: ApplicationInputId,
      writes: Boolean,
  )
  private object FootprintEntry:
    given ByteEncoder[FootprintEntry] = ByteEncoder.derived
  private case class FootprintPreimage(
      familyId: ApplicationFamilyId,
      version: ProtocolVersion,
      entries: Vector[FootprintEntry],
  )
  private object FootprintPreimage:
    given ByteEncoder[FootprintPreimage] = ByteEncoder.derived

  private def hash(domain: String, payload: ByteVector): UInt256 =
    UInt256.unsafeFromBytesBE(
      ByteVector.view(
        CryptoOps.keccak256((Utf8(domain).toBytes ++ payload.toBytes).toArray),
      ),
    )

  def run(): Unit =
    mixedReadWriteDescriptor()
    historicalEmptyPlan()

  def mixedReadWriteDescriptor(): Unit =
    val family = ApplicationFamilyId.parse("neutral.baseline").toOption.get
    val read   =
      ApplicationInputId.fromBytes(ByteVector.fromValidHex("aa")).toOption.get
    val write =
      ApplicationInputId.fromBytes(ByteVector.fromValidHex("bb")).toOption.get
    val fields = Vector(
      ApplicationFieldDescriptor(
        Utf8("read"),
        ApplicationFieldRole.MutableRead,
        ByteVector.fromValidHex("01"),
        Some(read),
      ),
      ApplicationFieldDescriptor(
        Utf8("write"),
        ApplicationFieldRole.MutableWrite,
        ByteVector.fromValidHex("02"),
        Some(write),
      ),
    )
    val manifest = ApplicationFamilyManifest(
      ProtocolVersion.M1,
      family,
      fields.map(f => ApplicationFieldManifest(f.fieldId, f.role)),
    )
    val descriptor =
      ApplicationInputDescriptor.normalize(family, ProtocolVersion.M1, fields)
    assert(
      ApplicationInputDescriptor.validate(manifest, descriptor) == Right(()),
    )
    val locks = LockPreimage(
      family,
      ProtocolVersion.M1,
      Vector(
        LockEntry(Utf8("read"), ApplicationFieldRole.MutableRead, read),
        LockEntry(Utf8("write"), ApplicationFieldRole.MutableWrite, write),
      ),
    ).toBytes
    val footprint = FootprintPreimage(
      family,
      ProtocolVersion.M1,
      Vector(
        FootprintEntry(read, false),
        FootprintEntry(write, true),
      ),
    ).toBytes
    assert(
      descriptor.lockSubsetCommitment == hash(
        "sigilaris.application.input.lock-subset.v1",
        locks,
      ),
    )
    assert(
      descriptor.footprintCommitment == hash(
        "sigilaris.application.input.footprint.v1",
        footprint,
      ),
    )
    assert(descriptor.lockSubsetCommitment != descriptor.footprintCommitment)
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
    val missingRead = ApplicationInputDescriptor.normalize(
      family,
      ProtocolVersion.M1,
      fields.updated(0, fields.head.copy(stableConflictId = None)),
    )
    assert(
      ApplicationInputDescriptor.validate(manifest, missingRead) == Left(
        ApplicationInputValidationFailure.StableConflictIdMissing("read"),
      ),
    )
    println(
      s"M2 mixedReadWriteDescriptor PASS locks=${locks.toHex} footprint=${footprint.toHex}",
    )
    println(
      s"M2 descriptor commitments full=${descriptor.fullInputCommitment.toBytes.toHex} lock=${descriptor.lockSubsetCommitment.toBytes.toHex} footprint=${descriptor.footprintCommitment.toBytes.toHex}",
    )

  def historicalEmptyPlan(): Unit =
    val plan = ExecutionPlan(ExecutionPlanVersion.V1, Vector.empty)
    assert(
      ExecutionPlan.validate(plan, Vector.empty) == Left(
        ExecutionPlanValidationFailure.EmptyPlan,
      ),
    )
    assert(
      ExecutionPlan.validate(
        plan.copy(waves = Vector(ExecutionWave.Ordered(Vector.empty))),
        Vector.empty,
      ) == Left(ExecutionPlanValidationFailure.EmptyWave(0)),
    )
    val singleton = ExecutionPlan.compatibilitySingleton(
      ExecutionId(UInt256.fromHex("01").toOption.get),
    )
    assert(
      singleton == ExecutionPlan(
        ExecutionPlanVersion.V1,
        Vector(
          ExecutionWave.Ordered(
            Vector(ExecutionId(UInt256.fromHex("01").toOption.get)),
          ),
        ),
      ),
    )
    assert(plan.toBytes.toHex == "000000000000000100")
    assert(
      ExecutionPlan
        .computeRoot(plan)
        .toBytes
        .toHex == "a57ba92e8039cd7cdafc2efb59ec67938254cf872a46f3accb3fb958e526ac4c",
    )
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
      s"M2 historicalEmptyPlan PASS bytes=${plan.toBytes.toHex} root=${ExecutionPlan.computeRoot(plan).toBytes.toHex}",
    )
    println(
      s"M2 compatibilitySingleton PASS bytes=${singleton.toBytes.toHex} root=${ExecutionPlan.computeRoot(singleton).toBytes.toHex}",
    )
