package org.sigilaris.core
package codec.byte

import scodec.bits.ByteVector
import failure.DecodeFailure

trait ByteCodec[A] extends ByteDecoder[A] with ByteEncoder[A]

object ByteCodec:

  def apply[A: ByteCodec]: ByteCodec[A] = summon

  given [A: ByteDecoder: ByteEncoder]: ByteCodec[A] with
    def decode(bytes: ByteVector): Either[DecodeFailure, DecodeResult[A]] =
      ByteDecoder[A].decode(bytes)
    def encode(a: A): ByteVector = ByteEncoder[A].encode(a)
