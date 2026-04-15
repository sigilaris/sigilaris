package org.sigilaris.core.util
package iron

import io.github.iltotore.iron.Constraint
import io.github.iltotore.iron.constraint.collection.Length
import scodec.bits.{BitVector, ByteVector}

import SafeStringInterp.*

/** Iron `Constraint` that validates the bit-length of a `BitVector`
  * by delegating to an underlying `Long`-based constraint.
  *
  * @tparam C
  *   the constraint predicate type (e.g., `GreaterEqual[256]`)
  * @tparam Impl
  *   the concrete constraint implementation for `Long`
  */
@SuppressWarnings(Array("org.wartremover.warts.ImplicitParameter"))
class LengthBitVector[C, Impl <: Constraint[Long, C]](using impl: Impl)
    extends Constraint[BitVector, Length[C]]:
  override inline def test(inline value: BitVector): Boolean =
    impl.test(value.size)
  override inline def message: String =
    ss"BitVector bit-length: (${impl.message})"

@SuppressWarnings(Array("org.wartremover.warts.ImplicitParameter"))
inline given [C, Impl <: Constraint[Long, C]](using
    inline impl: Impl,
): LengthBitVector[C, Impl] = new LengthBitVector[C, Impl]

/** Iron `Constraint` that validates the byte-length of a `ByteVector`
  * by delegating to an underlying `Long`-based constraint.
  *
  * @tparam C
  *   the constraint predicate type (e.g., `StrictEqual[32]`)
  * @tparam Impl
  *   the concrete constraint implementation for `Long`
  */
@SuppressWarnings(Array("org.wartremover.warts.ImplicitParameter"))
class LengthByteVector[C, Impl <: Constraint[Long, C]](using impl: Impl)
    extends Constraint[ByteVector, Length[C]]:
  override inline def test(inline value: ByteVector): Boolean =
    impl.test(value.size)
  override inline def message: String =
    ss"ByteVector byte-length: (${impl.message})"

@SuppressWarnings(Array("org.wartremover.warts.ImplicitParameter"))
inline given [C, Impl <: Constraint[Long, C]](using
    inline impl: Impl,
): LengthByteVector[C, Impl] = new LengthByteVector[C, Impl]
