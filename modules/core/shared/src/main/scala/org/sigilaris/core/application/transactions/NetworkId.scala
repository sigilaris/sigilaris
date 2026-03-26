package org.sigilaris.core.application.transactions

import cats.Eq

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.codec.json.{JsonDecoder, JsonEncoder}
import org.sigilaris.core.datatype.BigNat

/** Opaque identifier for a blockchain network.
  *
  * The value is represented as a non-negative arbitrary precision integer so it
  * can be derived from configuration files or on-chain governance decisions
  * without lossy conversions. By modelling the identifier as an opaque type we
  * keep the implementation details private to the `transactions` package while
  * still reusing the existing [[org.sigilaris.core.datatype.BigNat]] codecs.
  *
  * Prefer constructing instances through the helpers in the companion object so
  * future validation rules can be introduced without touching call sites.
  */
opaque type NetworkId = BigNat

object NetworkId:
  /** Construct a `NetworkId` from a validated `BigNat`. */
  inline def apply(value: BigNat): NetworkId = value

  /** Alias for `apply` to emphasize validated input. */
  inline def fromBigNat(value: BigNat): NetworkId = value

  /** Unsafe helper for tests and constants.
    *
    * Only intended for fixtures and hard coded examples where the literal value
    * is already guaranteed to be non-negative.
    */
  inline def unsafeFromLong(value: Long): NetworkId =
    apply(BigNat.unsafeFromLong(value))

  extension (id: NetworkId)
    /** Access the underlying [[org.sigilaris.core.datatype.BigNat]] value.
      *
      * Use this sparingly when integrating with external systems that expect
      * the raw representation.
      */
    inline def toBigNat: BigNat = id

  given Eq[NetworkId] = Eq.fromUniversalEquals

  given ByteEncoder[NetworkId] =
    ByteEncoder[BigNat].contramap(_.toBigNat)

  given ByteDecoder[NetworkId] =
    ByteDecoder[BigNat].map(apply)

  given JsonEncoder[NetworkId] =
    JsonEncoder[BigNat].contramap(_.toBigNat)

  given JsonDecoder[NetworkId] =
    JsonDecoder[BigNat].map(apply)
