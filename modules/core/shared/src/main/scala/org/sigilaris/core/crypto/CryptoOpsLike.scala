package org.sigilaris.core
package crypto

/** Cross-platform minimal CryptoOps surface to unify JS/JVM APIs. */
trait CryptoOpsLike:
  def keccak256(input: Array[Byte]): Array[Byte]
  def generate(): KeyPair
  def fromPrivate(privateKey: BigInt): KeyPair
  def sign(keyPair: KeyPair, transactionHash: Array[Byte]): Either[failure.SigilarisFailure, Signature]
  def recover(signature: Signature, hashArray: Array[Byte]): Either[failure.SigilarisFailure, PublicKey]


