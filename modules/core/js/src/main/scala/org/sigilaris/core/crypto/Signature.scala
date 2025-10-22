package org.sigilaris.core
package crypto

import org.sigilaris.core.util.SafeStringInterp.*

final case class Signature(
    v: Int,
    r: UInt256BigInt,
    s: UInt256BigInt,
):
  override lazy val toString: String =
    ss"Signature(${v.toString}, ${r.toBytes.toHex}, ${s.toBytes.toHex})"
