package org.sigilaris.core.util
package iron

import io.github.iltotore.iron.Constraint
import io.github.iltotore.iron.constraint.collection.Length
import scodec.bits.{BitVector, ByteVector}

import SafeStringInterp.*

@SuppressWarnings(Array("org.wartremover.warts.ImplicitParameter"))
class LengthBitVector[C, Impl <: Constraint[Long, C]](using impl: Impl)
    extends Constraint[BitVector, Length[C]]:
  override inline def test(inline value: BitVector): Boolean =
    impl.test(value.size)
  override inline def message: String =
    ss"Length64: (${impl.message})"

@SuppressWarnings(Array("org.wartremover.warts.ImplicitParameter"))
inline given [C, Impl <: Constraint[Long, C]](using
    inline impl: Impl,
): LengthBitVector[C, Impl] = new LengthBitVector[C, Impl]

@SuppressWarnings(Array("org.wartremover.warts.ImplicitParameter"))
class LengthByteVector[C, Impl <: Constraint[Long, C]](using impl: Impl)
    extends Constraint[ByteVector, Length[C]]:
  override inline def test(inline value: ByteVector): Boolean =
    impl.test(value.size)
  override inline def message: String =
    ss"Length64: (${impl.message})"

@SuppressWarnings(Array("org.wartremover.warts.ImplicitParameter"))
inline given [C, Impl <: Constraint[Long, C]](using
    inline impl: Impl,
): LengthByteVector[C, Impl] = new LengthByteVector[C, Impl]
