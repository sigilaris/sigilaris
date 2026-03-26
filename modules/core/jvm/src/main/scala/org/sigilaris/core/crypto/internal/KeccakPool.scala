package org.sigilaris.core
package crypto
package internal

import org.bouncycastle.jcajce.provider.digest.Keccak

/** Thread-local pool of Keccak-256 digest instances for efficient reuse.
  *
  * Provides thread-safe access to Keccak-256 digest instances without
  * requiring allocation on every hash operation. Each thread maintains its own
  * instance, and the digest is reset before each use.
  *
  * @note
  *   This is an internal optimization. Public API should use
  *   [[org.sigilaris.core.crypto.CryptoOps.keccak256]] instead.
  */
object KeccakPool:
  /** Thread-local storage for Keccak-256 digest instances. */
  private val tl: ThreadLocal[Keccak.Digest256] = new ThreadLocal[Keccak.Digest256]():
    override def initialValue(): Keccak.Digest256 = new Keccak.Digest256()

  /** Acquires a reset Keccak-256 digest instance for the current thread.
    *
    * @return
    *   a reset [[org.bouncycastle.jcajce.provider.digest.Keccak.Digest256]]
    *   instance
    *
    * @note
    *   The returned digest is automatically reset and ready to use. Caller
    *   should not store the instance beyond the current operation.
    */
  def acquire(): Keccak.Digest256 =
    val d = tl.get()
    d.reset()
    d


