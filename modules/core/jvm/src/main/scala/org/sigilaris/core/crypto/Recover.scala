package org.sigilaris.core
package crypto

import org.sigilaris.core.failure.SigilarisFailure

trait Recover[A]:
  def apply(a: A, signature: Signature)(implicit
      hash: Hash[A],
  ): Either[SigilarisFailure, PublicKey] = fromHash(hash(a), signature)

  def fromHash(
      hashValue: Hash.Value[A],
      signature: Signature,
  ): Either[SigilarisFailure, PublicKey]

object Recover:
  def apply[A: Recover]: Recover[A] = summon

  def build[A]: Recover[A] =
    (hashValue: Hash.Value[A], signature: Signature) =>
      CryptoOps.recover(signature, hashValue.toUInt256.bytes.toArray)

  given [A](using @annotation.unused h: Hash[A]): Recover[A] = build

  object ops:
    extension [A](hashValue: Hash.Value[A])
      def recover(signature: Signature)(using
          r: Recover[A],
      ): Either[SigilarisFailure, PublicKey] = r.fromHash(hashValue, signature)
