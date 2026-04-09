package org.sigilaris.core
package crypto

import cats.{Contravariant, Eq}

import io.circe.{KeyEncoder}
import scodec.bits.ByteVector

import codec.byte.{ByteDecoder, ByteEncoder}
import codec.byte.ByteEncoder.ops.*
import codec.json.{JsonDecoder, JsonEncoder, JsonKeyCodec}
import datatype.{UInt256, Utf8}

/** Type class for Keccak-256 hashing.
  *
  * Provides a type-safe way to hash values of type A, returning a tagged hash
  * value that remembers its source type. The hash is computed using Keccak-256
  * (32 bytes).
  *
  * @tparam A
  *   the type of value to hash
  *
  * @example
  *   ```scala
  *   import Hash.ops.*
  *
  *   // Hash a UTF-8 string
  *   val utf8Hash: Hash.Value[Utf8] = Utf8("hello").toHash
  *
  *   // Build custom hash instance
  *   given Hash[MyType] = Hash.build[MyType]
  *   ```
  *
  * @see
  *   [[CryptoOps.keccak256]] for the underlying hash function
  */
trait Hash[A]:
  /** Computes the Keccak-256 hash of a value.
    *
    * @param a
    *   value to hash
    * @return
    *   32-byte hash as [[Hash.Value]]
    */
  def apply(a: A): Hash.Value[A]

  /** Derives a hash instance for type B via a conversion function.
    *
    * @param f
    *   conversion function from B to A
    * @return
    *   hash instance for type B
    */
  def contramap[B](f: B => A): Hash[B] = (b: B) =>
    Hash.Value[B](apply(f(b)).toUInt256)

object Hash:
  /** Summons an implicit [[Hash]] instance for type A.
    *
    * @tparam A
    *   type with an available Hash instance
    * @return
    *   the Hash instance for A
    */
  def apply[A: Hash]: Hash[A] = summon

  /** Opaque type wrapping [[org.sigilaris.core.datatype.UInt256]] to track the
    * source type of the hash.
    *
    * @tparam A
    *   the type that was hashed to produce this value
    */
  opaque type Value[A] = UInt256

  object Value:
    /** Constructs a hash value from a [[org.sigilaris.core.datatype.UInt256]].
      *
      * @param uint256
      *   32-byte hash value
      * @return
      *   tagged hash value
      */
    def apply[A](uint256: UInt256): Value[A] = uint256

    /** JSON decoder for hash values, delegating to [[datatype.UInt256]] decoding. */
    given jsonValueDecoder[A]: JsonDecoder[Value[A]] =
      UInt256.uint256JsonDecoder.map(Value[A](_))

    /** JSON encoder for hash values, delegating to [[datatype.UInt256]] encoding. */
    given jsonValueEncoder[A]: JsonEncoder[Value[A]] =
      UInt256.uint256JsonEncoder.contramap[Value[A]](_.toUInt256)

    /** JSON key codec for hash values, enabling use as JSON object keys. */
    @SuppressWarnings(Array("org.wartremover.warts.Any"))
    given jsonKeyCodec[A]: JsonKeyCodec[Value[A]] =
      UInt256.uint256JsonKeyCodec.imap(Value[A](_), _.toUInt256)

    /** Circe key encoder for hash values, encoding as hex strings. */
    @SuppressWarnings(Array("org.wartremover.warts.Any"))
    given circeKeyEncoder[A]: KeyEncoder[Value[A]] =
      KeyEncoder.encodeKeyString.contramap[Value[A]]:
        _.toUInt256.toBytes.toHex

    /** Binary decoder for hash values, reading 32 bytes as [[datatype.UInt256]]. */
    given byteValueDecoder[A]: ByteDecoder[Value[A]] =
      UInt256.uint256ByteDecoder.map(Value[A](_))

    /** Binary encoder for hash values, writing 32 bytes from [[datatype.UInt256]]. */
    given byteValueEncoder[A]: ByteEncoder[Value[A]] =
      UInt256.uint256ByteEncoder.contramap[Value[A]](_.toUInt256)

    /** [[cats.Eq]] instance for hash values, comparing by underlying [[datatype.UInt256]]. */
    given eqValue[A]: Eq[Value[A]] = UInt256.eq

    extension [A](value: Value[A])
      /** Unwraps the hash value to its underlying [[datatype.UInt256]].
        *
        * @return
        *   the raw 32-byte hash as [[datatype.UInt256]]
        */
      def toUInt256: UInt256 = value

      /** Returns the hash value as a lowercase hexadecimal string.
        *
        * @return
        *   64-character hex string representation of the hash
        */
      def hex: String        = toUInt256.toHexLower

  /** Extension methods for hashing. */
  object ops:
    /** Hashes a value using its [[Hash]] instance.
      *
      * @param a
      *   value to hash
      * @param h
      *   implicit Hash instance
      * @return
      *   hash value
      */
    extension [A](a: A) def toHash(using h: Hash[A]): Value[A] = h(a)

  /** [[cats.Contravariant]] instance for [[Hash]]. */
  given contravariant: Contravariant[Hash] = new Contravariant[Hash]:
    override def contramap[A, B](fa: Hash[A])(f: B => A): Hash[B] =
      fa.contramap(f)

  /** Builds a [[Hash]] instance for any type with a [[codec.byte.ByteEncoder]].
    *
    * Encodes the value to bytes and computes Keccak-256 hash.
    *
    * @tparam A
    *   type to hash, must have a [[codec.byte.ByteEncoder]]
    * @return
    *   Hash instance using Keccak-256
    */
  @SuppressWarnings(
    Array("org.wartremover.warts.AsInstanceOf", "org.wartremover.warts.Nothing"),
  )
  def build[A: ByteEncoder]: Hash[A] = (a: A) =>
    val h = CryptoOps.keccak256(a.toBytes.toArray)
    ByteVector.view(h).asInstanceOf[Value[A]]

  /** Default [[Hash]] instance for [[datatype.Utf8]] strings. */
  given Hash[Utf8] = build
