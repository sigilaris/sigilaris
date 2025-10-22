package org.sigilaris.core
package crypto

import datatype.UInt256
import util.SafeStringInterp.*

/** An ECDSA secp256k1 key pair consisting of a private and public key.
  *
  * The key pair enables cryptographic operations such as signing transactions
  * and recovering public keys from signatures.
  *
  * @param privateKey
  *   32-byte private key as [[UInt256]], must be in range [1, n-1] where n is
  *   the secp256k1 curve order
  * @param publicKey
  *   corresponding 64-byte uncompressed public key (x||y coordinates)
  *
  * @example
  *   ```scala
  *   // Generate a new key pair
  *   val keyPair = CryptoOps.generate()
  *
  *   // Derive from existing private key
  *   val privateKey = UInt256.fromHex("0123...").toOption.get
  *   val keyPair2 = CryptoOps.fromPrivate(privateKey.toBigInt)
  *   ```
  *
  * @see [[CryptoOps.generate]] for key pair generation
  * @see [[CryptoOps.fromPrivate]] for deriving from private key
  */
final case class KeyPair(privateKey: UInt256, publicKey: PublicKey):
  override lazy val toString: String =
    ss"KeyPair(${privateKey.toHexLower}, ${publicKey.toString})"


