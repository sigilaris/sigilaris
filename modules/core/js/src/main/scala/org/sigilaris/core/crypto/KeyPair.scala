package org.sigilaris.core
package crypto

import org.sigilaris.core.util.SafeStringInterp.*
final case class KeyPair(privateKey: UInt256BigInt, publicKey: PublicKey):
  override lazy val toString: String =
    ss"KeyPair(${privateKey.toBytes.toHex}, ${publicKey.toString})"
