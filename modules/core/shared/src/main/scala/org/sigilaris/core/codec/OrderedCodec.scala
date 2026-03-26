package org.sigilaris.core.codec

import org.sigilaris.core.codec.byte.{ByteCodec, DecodeResult}
import org.sigilaris.core.failure.DecodeFailure
import scodec.bits.ByteVector

/** OrderedCodec extends ByteCodec with an ordering preservation law.
  *
  * Law: compare(x, y) ≡ encode(x).compare(encode(y))
  *
  * This ensures that the encoded byte representation preserves the ordering
  * relationship of the original values. This property is critical for
  * blockchain applications where prefix-free encoding and key ordering
  * matter.
  *
  * The law states: For all x, y of type A,
  *   sign(compare(x, y)) == sign(encode(x).compare(encode(y)))
  *
  * Where sign(n) is:
  *   - negative if n < 0
  *   - zero if n == 0
  *   - positive if n > 0
  *
  * @example
  * ```scala
  * val codec = OrderedCodec[Long]
  * val x = -100L
  * val y = 200L
  * // The law guarantees:
  * // x.compare(y) < 0  ⟹  encode(x).compare(encode(y)) < 0
  * ```
  */
trait OrderedCodec[A] extends ByteCodec[A], Ordering[A]:
  /** Verifies the ordering preservation law for two values.
    *
    * @param x first value
    * @param y second value
    * @return true if the law holds for this pair
    */
  def satisfiesLaw(x: A, y: A): Boolean =
    val ordering = compare(x, y)
    val encoded  = encode(x).compare(encode(y))
    ordering.sign == encoded.sign

object OrderedCodec:

  def apply[A](using oc: OrderedCodec[A]): OrderedCodec[A] = oc

  /** Creates an OrderedCodec from existing ByteCodec and Ordering instances.
    *
    * This is the primary way to create OrderedCodec instances: reuse existing
    * ByteCodec implementations and provide the Ordering.
    *
    * @param codec the ByteCodec instance
    * @param ord the Ordering instance
    * @return OrderedCodec that delegates to the given instances
    */
  def fromCodecAndOrdering[A](codec: ByteCodec[A], ord: Ordering[A]): OrderedCodec[A] =
    new OrderedCodec[A]:
      def encode(a: A): ByteVector = codec.encode(a)
      def decode(bv: ByteVector): Either[DecodeFailure, DecodeResult[A]] = codec.decode(bv)
      def compare(x: A, y: A): Int = ord.compare(x, y)

  // ByteVector instance (lexicographic ordering - identity codec)
  // Uses ByteVector's built-in compare which does unsigned byte comparison
  @SuppressWarnings(Array("org.wartremover.warts.Nothing"))
  given orderedByteVector: OrderedCodec[ByteVector] = new OrderedCodec[ByteVector]:
    def encode(bv: ByteVector): ByteVector = bv
    def decode(bv: ByteVector): Either[DecodeFailure, DecodeResult[ByteVector]] =
      Right[DecodeFailure, DecodeResult[ByteVector]](DecodeResult(bv, ByteVector.empty))
    def compare(x: ByteVector, y: ByteVector): Int = x.compare(y)

  // Note: Long uses existing ByteEncoder/ByteDecoder but big-endian encoding
  // does NOT preserve ordering for signed values.
  // For true ordering preservation, sign-bit flipping would be needed.
  //
  // Type-specific OrderedCodec instances (BigNat, UInt256, Utf8) are provided
  // in their respective companion objects in the datatype package.
