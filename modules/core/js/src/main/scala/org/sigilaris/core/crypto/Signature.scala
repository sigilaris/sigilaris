package org.sigilaris.core
package crypto

import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.core.datatype.UInt256

final case class Signature(
    v: Int,
    r: UInt256,
    s: UInt256,
):
  override lazy val toString: String =
    ss"Signature(${v.toString}, ${r.toHexLower}, ${s.toHexLower})"
