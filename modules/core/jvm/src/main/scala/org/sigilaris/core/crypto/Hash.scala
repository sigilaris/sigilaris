package org.sigilaris.core
package crypto

import cats.{Contravariant, Eq}

import io.circe.{KeyEncoder}
import scodec.bits.ByteVector

import codec.byte.{ByteDecoder, ByteEncoder}
import codec.byte.ByteEncoder.ops.*
import codec.json.{JsonDecoder, JsonEncoder, JsonKeyCodec}
import datatype.{UInt256, Utf8}

trait Hash[A]:
  def apply(a: A): Hash.Value[A]
  def contramap[B](f: B => A): Hash[B] = (b: B) =>
    Hash.Value[B](apply(f(b)).toUInt256)

object Hash:
  def apply[A: Hash]: Hash[A] = summon

  opaque type Value[A] = UInt256
  object Value:
    def apply[A](uint256: UInt256): Value[A] = uint256

    given jsonValueDecoder[A]: JsonDecoder[Value[A]] =
      UInt256.uint256JsonDecoder.map(Value[A](_))

    given jsonValueEncoder[A]: JsonEncoder[Value[A]] =
      UInt256.uint256JsonEncoder.contramap[Value[A]](_.toUInt256)

    @SuppressWarnings(Array("org.wartremover.warts.Any"))
    given jsonKeyCodec[A]: JsonKeyCodec[Value[A]] =
      UInt256.uint256JsonKeyCodec.imap(Value[A](_), _.toUInt256)

    @SuppressWarnings(Array("org.wartremover.warts.Any"))
    given circeKeyEncoder[A]: KeyEncoder[Value[A]] =
      KeyEncoder.encodeKeyString.contramap[Value[A]]:
        _.toUInt256.toBytes.toHex

    given byteValueDecoder[A]: ByteDecoder[Value[A]] =
      UInt256.uint256ByteDecoder.map(Value[A](_))

    given byteValueEncoder[A]: ByteEncoder[Value[A]] =
      UInt256.uint256ByteEncoder.contramap[Value[A]](_.toUInt256)

    given eqValue[A]: Eq[Value[A]] = UInt256.eq

    extension [A](value: Value[A]) def toUInt256: UInt256 = value

  object ops:
    extension [A](a: A) def toHash(using h: Hash[A]): Value[A] = h(a)

  given contravariant: Contravariant[Hash] = new Contravariant[Hash]:
    override def contramap[A, B](fa: Hash[A])(f: B => A): Hash[B] =
      fa.contramap(f)

  @SuppressWarnings(
    Array("org.wartremover.warts.AsInstanceOf", "org.wartremover.warts.Nothing"),
  )
  def build[A: ByteEncoder]: Hash[A] = (a: A) =>
    val h     = CryptoOps.keccak256(a.toBytes.toArray)
    ByteVector.view(h).asInstanceOf[Value[A]]

  given Hash[Utf8] = build
