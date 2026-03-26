package org.sigilaris.core
package crypto

/** Cross-platform minimal CryptoOps surface to unify JS/JVM APIs.
  *
  * Provides cryptographic primitives for secp256k1 elliptic curve operations
  * including hashing, key pair generation, signing, and signature recovery.
  * Platform-specific implementations exist for JS (via elliptic.js) and JVM
  * (via BouncyCastle).
  *
  * @see [[org.sigilaris.core.crypto.CryptoOps]] for platform-specific
  *      implementations
  */
trait CryptoOpsLike:
  /** Computes Keccak-256 hash of input bytes.
    *
    * @param input
    *   message bytes to hash
    * @return
    *   32-byte hash digest
    *
    * @note
    *   Thread-safe on JVM (uses thread-local pool); safe on JS (single-
    *   threaded)
    */
  def keccak256(input: Array[Byte]): Array[Byte]

  /** Generates a new random secp256k1 key pair.
    *
    * @return
    *   fresh [[KeyPair]] with random private key
    *
    * @note
    *   Uses cryptographically secure random source (SecureRandom on JVM,
    *   crypto.getRandomValues on JS)
    */
  def generate(): KeyPair

  /** Derives a key pair from an existing private key.
    *
    * @param privateKey
    *   private key as BigInt, must be in range [1, n-1] where n is curve order
    * @return
    *   [[KeyPair]] with the given private key and derived public key
    *
    * @note
    *   Validates that privateKey is within valid range; throws on invalid input
    */
  def fromPrivate(privateKey: BigInt): KeyPair

  /** Signs a message hash with ECDSA.
    *
    * @param keyPair
    *   key pair to sign with
    * @param transactionHash
    *   32-byte message hash to sign
    * @return
    *   Right([[Signature]]) on success, Left(failure) if signing fails
    *
    * @note
    *   - Uses deterministic k-generation (RFC 6979)
    *   - Normalizes signatures to Low-S form
    *   - Includes recovery parameter (v = 27 + recId)
    */
  def sign(keyPair: KeyPair, transactionHash: Array[Byte]): Either[failure.SigilarisFailure, Signature]

  /** Recovers public key from signature and message hash.
    *
    * @param signature
    *   ECDSA signature with recovery parameter
    * @param hashArray
    *   32-byte message hash that was signed
    * @return
    *   Right([[PublicKey]]) on success, Left(failure) if recovery fails
    *
    * @note
    *   - Accepts both High-S and Low-S signatures
    *   - Recovery parameter (v) must be 27, 28, 29, or 30
    */
  def recover(signature: Signature, hashArray: Array[Byte]): Either[failure.SigilarisFailure, PublicKey]


