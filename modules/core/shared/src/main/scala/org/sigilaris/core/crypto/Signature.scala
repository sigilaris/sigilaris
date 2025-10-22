package org.sigilaris.core
package crypto

import util.SafeStringInterp.*
import datatype.UInt256

final case class Signature(
    v: Int,
    r: UInt256,
    s: UInt256,
):
  override lazy val toString: String =
    ss"Signature(${v.toString}, ${r.toHexLower}, ${s.toHexLower})"


