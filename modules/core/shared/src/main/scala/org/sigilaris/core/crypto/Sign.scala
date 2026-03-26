package org.sigilaris.core.crypto

import org.sigilaris.core.failure.SigilarisFailure

/** Type class for ECDSA signing operations.
  *
  * Provides a type-safe interface for signing values of type A. The signing
  * process first hashes the value using [[Hash]], then signs the hash with
  * ECDSA using the provided key pair.
  *
  * @tparam A
  *   the type of value to sign
  *
  * @example
  *   ```scala
  *   import Sign.ops.*
  *
  *   val keyPair = CryptoOps.generate()
  *   val message = Utf8("hello")
  *
  *   // Sign a message
  *   val signature: Either[SigilarisFailure, Signature] =
  *     keyPair.sign(message)
  *
  *   // Sign a pre-computed hash
  *   val hash = message.toHash
  *   val sig2 = hash.signBy(keyPair)
  *   ```
  *
  * @see [[Hash]] for hashing values
  * @see [[Signature]] for the signature representation
  * @see [[CryptoOps.sign]] for the underlying signing operation
  */
trait Sign[A]:
  /** Signs a value after hashing it.
    *
    * @param a
    *   value to sign
    * @param keyPair
    *   key pair to sign with
    * @param hash
    *   implicit Hash instance for type A
    * @return
    *   Right([[Signature]]) on success, Left(failure) on error
    */
  def apply(a: A, keyPair: KeyPair)(using
      hash: Hash[A],
  ): Either[SigilarisFailure, Signature] = byHash(hash(a), keyPair)

  /** Signs a pre-computed hash value.
    *
    * @param hashValue
    *   hash value to sign
    * @param keyPair
    *   key pair to sign with
    * @return
    *   Right([[Signature]]) on success, Left(failure) on error
    */
  def byHash(
      hashValue: Hash.Value[A],
      keyPair: KeyPair,
  ): Either[SigilarisFailure, Signature]

object Sign:
  /** Summons an implicit [[Sign]] instance for type A.
    *
    * @tparam A
    *   type with an available Sign instance
    * @return
    *   the Sign instance for A
    */
  def apply[A: Sign]: Sign[A] = summon

  /** Builds a [[Sign]] instance for any type.
    *
    * @tparam A
    *   type to sign
    * @return
    *   Sign instance using [[CryptoOps.sign]]
    */
  def build[A]: Sign[A] = (hashValue: Hash.Value[A], keyPair: KeyPair) =>
    CryptoOps.sign(keyPair, hashValue.toUInt256.bytes.toArray)

  /** Default [[Sign]] instance for any type with a [[Hash]] instance.
    *
    * @tparam A
    *   type to sign
    * @param h
    *   implicit Hash instance (unused in implementation but required for
    *   scoping)
    * @return
    *   Sign instance for type A
    */
  given [A](using @annotation.unused h: Hash[A]): Sign[A] = build

  /** Extension methods for signing. */
  object ops:
    /** Signs a value using a key pair.
      *
      * @param keyPair
      *   key pair to sign with
      * @param a
      *   value to sign
      * @return
      *   Right([[Signature]]) on success, Left(failure) on error
      */
    extension (keyPair: KeyPair)
      def sign[A: Hash: Sign](a: A): Either[SigilarisFailure, Signature] =
        Sign[A].apply(a, keyPair)

    /** Signs a hash value using a key pair.
      *
      * @param hashValue
      *   hash value to sign
      * @param keyPair
      *   key pair to sign with
      * @param sign
      *   implicit Sign instance
      * @return
      *   Right([[Signature]]) on success, Left(failure) on error
      */
    extension [A](hashValue: Hash.Value[A])
      def signBy(keyPair: KeyPair)(using
          sign: Sign[A],
      ): Either[SigilarisFailure, Signature] = sign.byHash(hashValue, keyPair)


