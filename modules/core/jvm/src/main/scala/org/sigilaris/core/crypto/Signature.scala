package org.sigilaris.core.crypto

import org.sigilaris.core.datatype.UInt256

final case class Signature(
    v: Int,
    r: UInt256,
    s: UInt256,
)
