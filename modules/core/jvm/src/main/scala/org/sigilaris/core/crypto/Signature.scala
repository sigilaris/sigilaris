package org.sigilaris.core.crypto

final case class Signature(
    v: Int,
    r: UInt256BigInt,
    s: UInt256BigInt,
)
