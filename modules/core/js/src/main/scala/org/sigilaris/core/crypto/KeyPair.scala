package org.sigilaris.core
package crypto

import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.core.datatype.UInt256
final case class KeyPair(privateKey: UInt256, publicKey: PublicKey):
  override lazy val toString: String =
    ss"KeyPair(${privateKey.toHexLower}, ${publicKey.toString})"
