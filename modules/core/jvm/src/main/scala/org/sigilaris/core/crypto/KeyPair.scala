package org.sigilaris.core
package crypto

final case class KeyPair(privateKey: UInt256BigInt, publicKey: PublicKey):

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  override lazy val toString: String =
    s"KeyPair(${privateKey.toBytes.toHex}, $publicKey)"
