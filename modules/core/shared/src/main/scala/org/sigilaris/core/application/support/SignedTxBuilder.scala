package org.sigilaris.core.application.support

import org.sigilaris.core.application.accounts.domain.Account
import org.sigilaris.core.application.transactions.{AccountSignature, Signed, Tx}
import org.sigilaris.core.crypto.{Hash, KeyPair, Sign}
import org.sigilaris.core.failure.SigilarisFailure

/** Helper utilities for building [[Signed]] transactions.
  *
  * Tests and integration code frequently need to sign transactions with real
  * key pairs. Repeating the same `Hash`/`Sign` workflow across the codebase is
  * error-prone and obscures intent. This builder centralises the logic and
  * exposes a single safe API that returns failures as [[Either]].
  */
object SignedTxBuilder:
  /** Signs a transaction using the supplied account and key pair.
    *
    * @param tx
    *   transaction payload to sign
    * @param account
    *   logical account that owns the key pair
    * @param keyPair
    *   key material used during signing
    * @tparam A
    *   transaction type
    * @return
    *   either a [[Signed]] transaction or the signing failure
    */
  def sign[A <: Tx: Hash: Sign](
      tx: A,
      account: Account,
      keyPair: KeyPair,
  ): Either[SigilarisFailure, Signed[A]] =
    Sign[A].apply(tx, keyPair).map(sig => Signed(AccountSignature(account, sig), tx))

  /** Creates a reusable builder bound to a specific account/key pair. */
  def forAccount(account: Account, keyPair: KeyPair): ForAccount =
    new ForAccount(account, keyPair)

  /** Builder that caches the signing context. */
  final class ForAccount private[SignedTxBuilder] (
      account: Account,
      keyPair: KeyPair,
  ):
    def sign[A <: Tx: Hash: Sign](tx: A): Either[SigilarisFailure, Signed[A]] =
      SignedTxBuilder.sign(tx, account, keyPair)
