package org.sigilaris.core
package codec.byte

import scodec.bits.ByteVector
import failure.DecodeFailure

/** Bidirectional byte codec combining encoder and decoder.
  *
  * ByteCodec extends both ByteEncoder and ByteDecoder, providing a single type
  * class for types that support both encoding and decoding.
  *
  * Most types have both encoder and decoder instances, so ByteCodec is
  * automatically derived when both are available.
  *
  * @see
  *   [[ByteEncoder]] for encoding operations
  * @see
  *   [[ByteDecoder]] for decoding operations
  * @see
  *   types.md for encoding/decoding rules per type
  */
trait ByteCodec[A] extends ByteDecoder[A] with ByteEncoder[A]

object ByteCodec:

  /** Summons a ByteCodec instance for type A. */
  def apply[A: ByteCodec]: ByteCodec[A] = summon

  /** Automatic derivation when both encoder and decoder are available.
    *
    * For any type A with both ByteEncoder[A] and ByteDecoder[A], a
    * ByteCodec[A] is automatically available.
    *
    * @example
    *   {{{
    * case class User(id: Long, name: String)
    * // ByteCodec[User] is automatically available via derivation
    * val codec = ByteCodec[User]
    *   }}}
    */
  given [A: ByteDecoder: ByteEncoder]: ByteCodec[A] with
    def decode(bytes: ByteVector): Either[DecodeFailure, DecodeResult[A]] =
      ByteDecoder[A].decode(bytes)
    def encode(a: A): ByteVector = ByteEncoder[A].encode(a)
