package org.sigilaris.core
package crypto

import org.sigilaris.core.failure.SigilarisFailure

/** Type class for recovering public keys from ECDSA signatures.
  *
  * Provides a type-safe interface for recovering the public key that was used
  * to create a signature. The recovery process first hashes the value using
  * [[Hash]], then uses the signature's recovery parameter to recover the
  * public key.
  *
  * @tparam A
  *   the type of value that was signed
  *
  * @example
  *   ```scala
  *   import Recover.ops.*
  *
  *   val keyPair = CryptoOps.generate()
  *   val message = Utf8("hello")
  *   val signature = keyPair.sign(message).toOption.get
  *
  *   // Recover public key from signature
  *   val hash = message.toHash
  *   val recovered: Either[SigilarisFailure, PublicKey] =
  *     hash.recover(signature)
  *
  *   assert(recovered.contains(keyPair.publicKey))
  *   ```
  *
  * @see [[Sign]] for signature creation
  * @see [[Signature]] for the signature representation
  * @see [[CryptoOps.recover]] for the underlying recovery operation
  */
trait Recover[A]:
  /** Recovers the public key from a value and its signature.
    *
    * @param a
    *   value that was signed
    * @param signature
    *   signature to recover from
    * @param hash
    *   implicit Hash instance for type A
    * @return
    *   Right([[PublicKey]]) on success, Left(failure) on error
    */
  def apply(a: A, signature: Signature)(implicit
      hash: Hash[A],
  ): Either[SigilarisFailure, PublicKey] = fromHash(hash(a), signature)

  /** Recovers the public key from a pre-computed hash and signature.
    *
    * @param hashValue
    *   hash value of the signed data
    * @param signature
    *   signature to recover from
    * @return
    *   Right([[PublicKey]]) on success, Left(failure) on error
    */
  def fromHash(
      hashValue: Hash.Value[A],
      signature: Signature,
  ): Either[SigilarisFailure, PublicKey]

object Recover:
  /** Summons an implicit [[Recover]] instance for type A.
    *
    * @tparam A
    *   type with an available Recover instance
    * @return
    *   the Recover instance for A
    */
  def apply[A: Recover]: Recover[A] = summon

  /** Builds a [[Recover]] instance for any type.
    *
    * @tparam A
    *   type of value that was signed
    * @return
    *   Recover instance using [[CryptoOps.recover]]
    */
  def build[A]: Recover[A] =
    (hashValue: Hash.Value[A], signature: Signature) =>
      CryptoOps.recover(signature, hashValue.toUInt256.bytes.toArray)

  /** Default [[Recover]] instance for any type with a [[Hash]] instance.
    *
    * @tparam A
    *   type of value that was signed
    * @param h
    *   implicit Hash instance (unused in implementation but required for
    *   scoping)
    * @return
    *   Recover instance for type A
    */
  given [A](using @annotation.unused h: Hash[A]): Recover[A] = build

  /** Extension methods for recovery. */
  object ops:
    /** Recovers the public key from a hash and signature.
      *
      * @param hashValue
      *   hash value of the signed data
      * @param signature
      *   signature to recover from
      * @param r
      *   implicit Recover instance
      * @return
      *   Right([[PublicKey]]) on success, Left(failure) on error
      */
    extension [A](hashValue: Hash.Value[A])
      def recover(signature: Signature)(using
          r: Recover[A],
      ): Either[SigilarisFailure, PublicKey] = r.fromHash(hashValue, signature)


