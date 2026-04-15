package org.sigilaris.core.application.support.encoding

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder, DecodeResult}

/** Shared deterministic codecs for pair-shaped table keys. */
object TupleKeyCodecs:
  def pairEncoder[A: ByteEncoder, B: ByteEncoder]: ByteEncoder[(A, B)] =
    (pair: (A, B)) =>
      ByteEncoder[A].encode(pair._1) ++ ByteEncoder[B].encode(pair._2)

  def pairDecoder[A: ByteDecoder, B: ByteDecoder]: ByteDecoder[(A, B)] =
    bytes =>
      for
        left  <- ByteDecoder[A].decode(bytes)
        right <- ByteDecoder[B].decode(left.remainder)
      yield DecodeResult((left.value, right.value), right.remainder)
