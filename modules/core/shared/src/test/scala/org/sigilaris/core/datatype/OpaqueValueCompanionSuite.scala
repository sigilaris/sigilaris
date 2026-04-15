package org.sigilaris.core
package datatype

import cats.Eq
import munit.FunSuite

import org.sigilaris.core.application.feature.group.domain.GroupId
import org.sigilaris.core.application.transactions.NetworkId
import org.sigilaris.core.codec.byte.{
  ByteCodec,
  ByteDecoder,
  ByteEncoder,
  DecodeResult,
}
import org.sigilaris.core.codec.json.{JsonDecoder, JsonEncoder, JsonValue}
import scodec.bits.ByteVector

final class OpaqueValueCompanionSuite extends FunSuite:
  final case class WrappedPairRepr(name: Utf8, nonce: BigNat)
      derives ByteEncoder,
        ByteDecoder

  opaque type WrappedPair = WrappedPairRepr

  given wrappedPairReprByteCodec: ByteCodec[WrappedPairRepr] with
    def encode(value: WrappedPairRepr): ByteVector =
      ByteEncoder.derived[WrappedPairRepr].encode(value)

    def decode(
        bytes: ByteVector,
    ): Either[org.sigilaris.core.failure.DecodeFailure, DecodeResult[
      WrappedPairRepr,
    ]] =
      ByteDecoder.derived[WrappedPairRepr].decode(bytes)

  object WrappedPair extends OpaqueValueCompanion[WrappedPair, WrappedPairRepr]:
    def apply(name: Utf8, nonce: BigNat): WrappedPair =
      WrappedPairRepr(name, nonce)

    val byteCodec: ByteCodec[WrappedPair] =
      ByteCodec.opaqueProduct(wrap, unwrap)

    protected def wrap(repr: WrappedPairRepr): WrappedPair = repr

    protected def unwrap(value: WrappedPair): WrappedPairRepr = value

    extension (value: WrappedPair)
      inline def name: Utf8    = value.repr.name
      inline def nonce: BigNat = value.repr.nonce

  test("GroupId forwards byte/json codecs and JsonKeyCodec from Utf8"):
    val groupId   = GroupId.unsafe(Utf8("developers"))
    val bytes     = ByteEncoder[GroupId].encode(groupId)
    val json      = JsonEncoder[Map[GroupId, Int]].encode(Map(groupId -> 1))
    val eqGroupId = summon[Eq[GroupId]]
    val decodedWithRemainder =
      ByteDecoder[GroupId].decode(bytes ++ ByteVector(0x01.toByte))

    assertEquals(
      ByteDecoder[GroupId].decode(bytes).map(_.value),
      Right(groupId),
    )
    assertEquals(
      decodedWithRemainder,
      Right(DecodeResult(groupId, ByteVector(0x01.toByte))),
    )
    assertEquals(
      json,
      JsonValue.obj("developers" -> JsonValue.JNumber(BigDecimal(1))),
    )
    assertEquals(
      JsonDecoder[Map[GroupId, Int]].decode(json),
      Right(Map(groupId -> 1)),
    )
    assertEquals(eqGroupId.eqv(groupId, GroupId.unsafe(Utf8("developers"))), true)
    assertEquals(eqGroupId.eqv(groupId, GroupId.unsafe(Utf8("ops"))), false)
    assertEquals(groupId.toUtf8, Utf8("developers"))

  test("NetworkId forwards byte and json codecs from BigNat"):
    val networkId   = NetworkId.unsafeFromLong(42)
    val bytes       = ByteEncoder[NetworkId].encode(networkId)
    val json        = JsonEncoder[NetworkId].encode(networkId)
    val eqNetworkId = summon[Eq[NetworkId]]

    assertEquals(
      ByteDecoder[NetworkId].decode(bytes).map(_.value),
      Right(networkId),
    )
    assertEquals(JsonDecoder[NetworkId].decode(json), Right(networkId))
    assertEquals(eqNetworkId.eqv(networkId, NetworkId.unsafeFromLong(42)), true)
    assertEquals(eqNetworkId.eqv(networkId, NetworkId.unsafeFromLong(7)), false)

  test(
    "ByteCodec.opaqueProduct derives a codec for opaque wrappers over product repr",
  ):
    val value = WrappedPair(Utf8("box"), BigNat.One)
    val bytes = WrappedPair.byteCodec.encode(value)

    assertEquals(
      WrappedPair.byteCodec.decode(bytes),
      Right(
        org.sigilaris.core.codec.byte
          .DecodeResult(value, scodec.bits.ByteVector.empty),
      ),
    )
