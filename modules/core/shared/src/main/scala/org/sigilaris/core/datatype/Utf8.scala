package org.sigilaris.core
package datatype

import java.nio.charset.StandardCharsets

import cats.Eq
import cats.syntax.either.*

import scodec.bits.ByteVector

import codec.byte.{ByteDecoder, ByteEncoder, DecodeResult}
import codec.json.{JsonDecoder, JsonEncoder, JsonKeyCodec}
import failure.DecodeFailure
 

opaque type Utf8 = String

object Utf8:
  def apply(value: String): Utf8 = value

  extension (u: Utf8)
    inline def asString: String = u

  given utf8Eq: Eq[Utf8] = Eq.fromUniversalEquals

  // Byte codec: [size: BigNat][raw UTF-8 bytes]
  given utf8ByteEncoder: ByteEncoder[Utf8] = (u: Utf8) =>
    val data = ByteVector.view(u.getBytes(StandardCharsets.UTF_8))
    val sizeNat: BigNat = BigNat.unsafeFromLong(data.size)
    ByteEncoder[BigNat].encode(sizeNat) ++ data

  given utf8ByteDecoder: ByteDecoder[Utf8] = bytes =>
    for
      sizeResult <- ByteDecoder[BigNat].decode(bytes)
      len = sizeResult.value.toBigInt.toLong
      remainder = sizeResult.remainder
      res <-
        if remainder.size >= len then
          val (front, back) = remainder.splitAt(len)
          front
            .decodeUtf8
            .leftMap(_ => DecodeFailure("Invalid UTF-8 bytes"))
            .map(u => DecodeResult[Utf8](u, back))
        else DecodeFailure("Insufficient bytes for Utf8 payload").asLeft[DecodeResult[Utf8]]
    yield res

  // JSON codecs delegate to String
  given utf8JsonEncoder: JsonEncoder[Utf8] =
    JsonEncoder[String].contramap(identity)

  given utf8JsonDecoder: JsonDecoder[Utf8] =
    JsonDecoder[String].map(Utf8(_))

  // JSON key codec for map keys
  given utf8JsonKeyCodec: JsonKeyCodec[Utf8] =
    JsonKeyCodec[String].imap(Utf8(_), _.asString)
