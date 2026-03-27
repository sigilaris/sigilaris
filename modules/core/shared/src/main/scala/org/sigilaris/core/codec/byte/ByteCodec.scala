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
trait ByteCodec[A] extends ByteDecoder[A] with ByteEncoder[A]:
  self =>

  /** Maps a codec through an isomorphism.
    *
    * Useful for opaque wrappers whose representation already has a codec.
    */
  def imap[B](to: A => B, from: B => A): ByteCodec[B] = new ByteCodec[B]:
    def decode(bytes: ByteVector): Either[DecodeFailure, DecodeResult[B]] =
      self.decode(bytes).map:
        case DecodeResult(value, remainder) =>
          DecodeResult(to(value), remainder)

    def encode(value: B): ByteVector =
      self.encode(from(value))

object ByteCodec:

  /** Summons a ByteCodec instance for type A. */
  def apply[A: ByteCodec]: ByteCodec[A] = summon

  /** Helper for opaque wrappers backed by a product representation.
    *
    * The representation's codec remains the single source of truth while the
    * wrapper supplies only the wrapping and unwrapping functions.
    */
  def opaqueProduct[A, Repr](
      wrap: Repr => A,
      unwrap: A => Repr,
  )(using codec: ByteCodec[Repr]): ByteCodec[A] =
    codec.imap(wrap, unwrap)

  /** Automatic derivation when both encoder and decoder are available.
    *
    * For any type A with both ByteEncoder[A] and ByteDecoder[A], a
    * ByteCodec[A] is automatically available.
    *
    * @example
    * ```scala
    * case class User(id: Long, name: String)
    * // ByteCodec[User] is automatically available via derivation
    * val codec = ByteCodec[User]
    * ```
    */
  given [A: ByteDecoder: ByteEncoder]: ByteCodec[A] with
    def decode(bytes: ByteVector): Either[DecodeFailure, DecodeResult[A]] =
      ByteDecoder[A].decode(bytes)
    def encode(a: A): ByteVector = ByteEncoder[A].encode(a)
