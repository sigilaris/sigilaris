package org.sigilaris.core.application.protocol

import cats.Eq
import scodec.bits.ByteVector

import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.crypto.CryptoOps
import org.sigilaris.core.datatype.{BigNat, UInt256, Utf8}

private[protocol] given applicationProtocolByteVectorEncoder
    : ByteEncoder[ByteVector] = bytes =>
  ByteEncoder[BigNat].encode(BigNat.unsafeFromLong(bytes.size)) ++ bytes

private[protocol] given applicationProtocolVectorEncoder[A: ByteEncoder]
    : ByteEncoder[Vector[A]] =
  ByteEncoder[List[A]].contramap(_.toList)

private[protocol] object ApplicationProtocolHash:
  def hash(domain: String, payload: ByteVector): UInt256 =
    val preimage =
      ByteEncoder[Utf8].encode(Utf8(domain)) ++
        ByteEncoder[ByteVector].encode(payload)
    UInt256.unsafeFromBytesBE:
      ByteVector.view(CryptoOps.keccak256(preimage.toArray))

opaque type ExecutionId = UInt256

object ExecutionId:
  def apply(value: UInt256): ExecutionId = value
  extension (value: ExecutionId)
    def toUInt256: UInt256 = value
    def toHexLower: String = value.bytes.toHex
  given ByteEncoder[ExecutionId] = ByteEncoder[UInt256].contramap(_.toUInt256)
  given Eq[ExecutionId]          = Eq.by(_.toUInt256)

opaque type ApplicationFamilyId = String

object ApplicationFamilyId:
  def parse(value: String): Either[String, ApplicationFamilyId] =
    val normalized = value.trim
    Either.cond(
      normalized.nonEmpty,
      normalized,
      "application family id must be non-blank",
    )
  extension (value: ApplicationFamilyId) def asString: String = value
  given ByteEncoder[ApplicationFamilyId]                      =
    ByteEncoder[Utf8].contramap(value => Utf8(value.asString))

opaque type ApplicationInputId = ByteVector

object ApplicationInputId:
  def fromBytes(value: ByteVector): Either[String, ApplicationInputId] =
    Either.cond(value.nonEmpty, value, "application input id must be non-empty")
  extension (value: ApplicationInputId)
    def bytes: ByteVector = value
    def toHex: String     = value.toHex
  given ByteEncoder[ApplicationInputId] =
    ByteEncoder[ByteVector].contramap(_.bytes)

opaque type ApplicationResultDigest = UInt256

object ApplicationResultDigest:
  def apply(value: UInt256): ApplicationResultDigest = value
  extension (value: ApplicationResultDigest)
    def toUInt256: UInt256 = value
    def toHexLower: String = value.bytes.toHex
  given ByteEncoder[ApplicationResultDigest] =
    ByteEncoder[UInt256].contramap(_.toUInt256)

opaque type ApplicationStateRoot = UInt256

object ApplicationStateRoot:
  def apply(value: UInt256): ApplicationStateRoot = value
  extension (value: ApplicationStateRoot)
    def toUInt256: UInt256 = value
    def toHexLower: String = value.bytes.toHex
  given ByteEncoder[ApplicationStateRoot] =
    ByteEncoder[UInt256].contramap(_.toUInt256)

opaque type ExecutionPlanRoot = UInt256

object ExecutionPlanRoot:
  def apply(value: UInt256): ExecutionPlanRoot = value
  extension (value: ExecutionPlanRoot)
    def toUInt256: UInt256 = value
    def toHexLower: String = value.bytes.toHex
  given ByteEncoder[ExecutionPlanRoot] =
    ByteEncoder[UInt256].contramap(_.toUInt256)
  given Eq[ExecutionPlanRoot] = Eq.by(_.toUInt256)

opaque type ApplicationConfigurationDigest = UInt256

object ApplicationConfigurationDigest:
  def apply(value: UInt256): ApplicationConfigurationDigest = value
  extension (value: ApplicationConfigurationDigest)
    def toUInt256: UInt256                          = value
  given ByteEncoder[ApplicationConfigurationDigest] =
    ByteEncoder[UInt256].contramap(_.toUInt256)

opaque type ApplicationValidatorSetHash = UInt256

object ApplicationValidatorSetHash:
  def apply(value: UInt256): ApplicationValidatorSetHash                = value
  extension (value: ApplicationValidatorSetHash) def toUInt256: UInt256 = value
  given ByteEncoder[ApplicationValidatorSetHash]                        =
    ByteEncoder[UInt256].contramap(_.toUInt256)

opaque type DependencyPlanDigest = UInt256

object DependencyPlanDigest:
  def apply(value: UInt256): DependencyPlanDigest                = value
  extension (value: DependencyPlanDigest) def toUInt256: UInt256 = value
  given ByteEncoder[DependencyPlanDigest]                        =
    ByteEncoder[UInt256].contramap(_.toUInt256)

opaque type InclusionHeight = BigNat

object InclusionHeight:
  def apply(value: BigNat): InclusionHeight = value
  extension (value: InclusionHeight)
    def toBigNat: BigNat = value
    def render: String   = value.toBigInt.toString
  given ByteEncoder[InclusionHeight] = ByteEncoder[BigNat].contramap(_.toBigNat)
  given Ordering[InclusionHeight]    =
    Ordering.by[InclusionHeight, BigNat](_.toBigNat)(using
      BigNat.bignatOrdering,
    )

final case class ApplicationEpoch(value: Long)

object ApplicationEpoch:
  def fromLong(value: Long): Either[String, ApplicationEpoch] =
    Either.cond(
      value >= 0L,
      ApplicationEpoch(value),
      "application epoch must be non-negative",
    )
  given ByteEncoder[ApplicationEpoch] = ByteEncoder[Long].contramap(_.value)

final case class ProtocolVersion(value: Int)

object ProtocolVersion:
  val M1: ProtocolVersion                                  = ProtocolVersion(1)
  def fromInt(value: Int): Either[String, ProtocolVersion] =
    Either.cond(
      value > 0,
      ProtocolVersion(value),
      "protocol version must be positive",
    )
  given ByteEncoder[ProtocolVersion] =
    ByteEncoder[Long].contramap(_.value.toLong)
