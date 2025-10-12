package org.sigilaris.core
package crypto

trait Recover[A]:
  def apply(a: A, signature: Signature)(implicit
      hash: Hash[A],
  ): Option[PublicKey] = fromHash(hash(a), signature)

  def fromHash(
      hashValue: Hash.Value[A],
      signature: Signature,
  ): Option[PublicKey]

object Recover:
  def apply[A: Recover]: Recover[A] = summon

  def build[A]: Recover[A] =
    (hashValue: Hash.Value[A], signature: Signature) =>
      CryptoOps.recover(signature, hashValue.toUInt256.bytes.toArray).toOption

  given [A](using @annotation.unused h: Hash[A]): Recover[A] = build

  object ops:
    extension [A](hashValue: Hash.Value[A])
      def recover(signature: Signature)(using
          r: Recover[A],
      ): Option[PublicKey] = r.fromHash(hashValue, signature)
