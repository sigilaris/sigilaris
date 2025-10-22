package org.sigilaris.core
package crypto

import datatype.UInt256
import util.SafeStringInterp.*

final case class KeyPair(privateKey: UInt256, publicKey: PublicKey):
  override lazy val toString: String =
    ss"KeyPair(${privateKey.toHexLower}, ${publicKey.toString})"
