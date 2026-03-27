package org.sigilaris.core
package datatype

import cats.Eq

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.codec.json.{JsonDecoder, JsonEncoder, JsonKeyCodec}

/** Reusable companion mix-in for opaque values backed by a representation type.
  *
  * The companion only needs to define how to wrap the representation and how
  * to project an opaque value back to that representation. The common `Eq`,
  * byte codec, and JSON codec instances are forwarded from the representation.
  */
trait OpaqueValueCompanion[A, Repr]:
  protected def wrap(repr: Repr): A

  protected def unwrap(value: A): Repr

  extension (value: A) def repr: Repr = unwrap(value)

  given opaqueEq(using eqRepr: Eq[Repr]): Eq[A] = new Eq[A]:
    def eqv(x: A, y: A): Boolean =
      eqRepr.eqv(unwrap(x), unwrap(y))

  given opaqueByteEncoder(using ByteEncoder[Repr]): ByteEncoder[A] =
    ByteEncoder[Repr].contramap(unwrap)

  given opaqueByteDecoder(using ByteDecoder[Repr]): ByteDecoder[A] =
    ByteDecoder[Repr].map(wrap)

  given opaqueJsonEncoder(using JsonEncoder[Repr]): JsonEncoder[A] =
    JsonEncoder[Repr].contramap(unwrap)

  given opaqueJsonDecoder(using JsonDecoder[Repr]): JsonDecoder[A] =
    JsonDecoder[Repr].map(wrap)

trait KeyLikeOpaqueValueCompanion[A, Repr]
    extends OpaqueValueCompanion[A, Repr]:
  given opaqueJsonKeyCodec(using JsonKeyCodec[Repr]): JsonKeyCodec[A] =
    JsonKeyCodec[Repr].imap(wrap, unwrap)
