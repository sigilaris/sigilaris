package org.sigilaris.core
package datatype

import cats.Eq

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.codec.json.{JsonDecoder, JsonEncoder, JsonKeyCodec}

/** Reusable companion mix-in for opaque values backed by a representation type.
  *
  * The companion only needs to define how to wrap the representation and how to
  * project an opaque value back to that representation. The common `Eq`, byte
  * codec, and JSON codec instances are forwarded from the representation.
  */
/** Reusable companion mix-in for opaque values backed by a representation type.
  *
  * @tparam A
  *   the opaque type
  * @tparam Repr
  *   the underlying representation type
  */
trait OpaqueValueCompanion[A, Repr]:
  protected def wrap(repr: Repr): A

  protected def unwrap(value: A): Repr

  /** Exposes the underlying representation of the opaque value.
    *
    * @return
    *   the representation value
    */
  extension (value: A) def repr: Repr = unwrap(value)

  /** Cats Eq instance derived from the representation's Eq. */
  given opaqueEq(using eqRepr: Eq[Repr]): Eq[A] = new Eq[A]:
    def eqv(x: A, y: A): Boolean =
      eqRepr.eqv(unwrap(x), unwrap(y))

  /** Byte encoder derived from the representation's encoder. */
  given opaqueByteEncoder(using ByteEncoder[Repr]): ByteEncoder[A] =
    ByteEncoder[Repr].contramap(unwrap)

  /** Byte decoder derived from the representation's decoder. */
  given opaqueByteDecoder(using ByteDecoder[Repr]): ByteDecoder[A] =
    ByteDecoder[Repr].map(wrap)

  /** JSON encoder derived from the representation's encoder. */
  given opaqueJsonEncoder(using JsonEncoder[Repr]): JsonEncoder[A] =
    JsonEncoder[Repr].contramap(unwrap)

  /** JSON decoder derived from the representation's decoder. */
  given opaqueJsonDecoder(using JsonDecoder[Repr]): JsonDecoder[A] =
    JsonDecoder[Repr].map(wrap)

/** Extended companion mix-in that additionally provides a JSON key codec.
  *
  * Use this for opaque types that need to serve as JSON object keys.
  *
  * @tparam A
  *   the opaque type
  * @tparam Repr
  *   the underlying representation type
  */
trait KeyLikeOpaqueValueCompanion[A, Repr]
    extends OpaqueValueCompanion[A, Repr]:
  /** JSON key codec derived from the representation's key codec. */
  given opaqueJsonKeyCodec(using JsonKeyCodec[Repr]): JsonKeyCodec[A] =
    JsonKeyCodec[Repr].imap(wrap, unwrap)
