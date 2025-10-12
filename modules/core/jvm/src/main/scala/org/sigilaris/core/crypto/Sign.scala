package org.sigilaris.core.crypto

trait Sign[A]:
  def apply(a: A, keyPair: KeyPair)(using
      hash: Hash[A],
  ): Either[String, Signature] = byHash(hash(a), keyPair)

  def byHash(
      hashValue: Hash.Value[A],
      keyPair: KeyPair,
  ): Either[String, Signature]

object Sign:
  def apply[A: Sign]: Sign[A] = summon

  def build[A]: Sign[A] = (hashValue: Hash.Value[A], keyPair: KeyPair) =>
    CryptoOps.sign(keyPair, hashValue.toUInt256.bytes.toArray)

  given [A](using @annotation.unused h: Hash[A]): Sign[A] = build

  object ops:
    extension (keyPair: KeyPair)
      def sign[A: Hash: Sign](a: A): Either[String, Signature] =
        Sign[A].apply(a, keyPair)

    extension [A](hashValue: Hash.Value[A])
      def signBy(keyPair: KeyPair)(using
          sign: Sign[A],
      ): Either[String, Signature] = sign.byHash(hashValue, keyPair)
