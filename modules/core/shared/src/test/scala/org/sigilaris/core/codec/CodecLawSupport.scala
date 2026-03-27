package org.sigilaris.core.codec

import hedgehog.*
import hedgehog.Result

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder, DecodeResult}
import org.sigilaris.core.codec.json.{JsonDecoder, JsonEncoder}

/** Internal-only reusable codec law helpers for shared regression suites. */
object CodecLawSupport:
  object ByteLaws:
    def roundTrip[A](value: A)(using ByteEncoder[A], ByteDecoder[A]): Result =
      ByteDecoder[A].decode(ByteEncoder[A].encode(value)) match
        case Right(DecodeResult(decoded, remainder)) =>
          Result.all(
            List(
              decoded ==== value,
              Result.assert(remainder.isEmpty),
            ),
          )
        case Left(_) => Result.failure

    def deterministicEncoding[A](value: A)(using ByteEncoder[A], ByteDecoder[A]): Result =
      val encoded = ByteEncoder[A].encode(value)
      ByteDecoder[A].decode(encoded) match
        case Right(DecodeResult(decoded, remainder)) =>
          Result.all(
            List(
              Result.assert(remainder.isEmpty),
              ByteEncoder[A].encode(decoded) ==== encoded,
            ),
          )
        case Left(_) => Result.failure

  object JsonLaws:
    def roundTrip[A](value: A)(using JsonEncoder[A], JsonDecoder[A]): Result =
      JsonDecoder[A].decode(JsonEncoder[A].encode(value)) match
        case Right(decoded) => decoded ==== value
        case Left(_)        => Result.failure

    def deterministicEncoding[A](value: A)(using JsonEncoder[A], JsonDecoder[A]): Result =
      val encoded = JsonEncoder[A].encode(value)
      JsonDecoder[A].decode(encoded) match
        case Right(decoded) => JsonEncoder[A].encode(decoded) ==== encoded
        case Left(_)        => Result.failure

  object OrderedLaws:
    def preservesOrdering[A](x: A, y: A)(using OrderedCodec[A]): Result =
      Result.assert(OrderedCodec[A].satisfiesLaw(x, y))
