package org.sigilaris.core
package crypto
package internal

import org.bouncycastle.jcajce.provider.digest.Keccak

object KeccakPool:
  private val tl: ThreadLocal[Keccak.Digest256] = new ThreadLocal[Keccak.Digest256]():
    override def initialValue(): Keccak.Digest256 = new Keccak.Digest256()

  def acquire(): Keccak.Digest256 =
    val d = tl.get()
    d.reset()
    d


