package org.sigilaris.conformance

import scala.util.Try
import org.sigilaris.core.crypto.CryptoOps
import org.sigilaris.core.datatype.UInt256

object PlatformConformance:
  def run(): Unit = verify(requireTypedFailure = false)

  def runV2(): Unit = verify(requireTypedFailure = true)

  private def verify(requireTypedFailure: Boolean): Unit =
    assert(
      org.sigilaris.node.gossip.ChainId.parse("neutral-conformance").isRight,
    )
    val key       = CryptoOps.fromPrivate(BigInt(1))
    val hash      = UInt256.unsafeFromBigIntUnsigned(BigInt(3)).bytes.toArray
    val signature = CryptoOps.sign(key, hash).toOption.get
    assert(
      CryptoOps.recover(signature, hash).map(_.toBytes).contains(key.publicKey.toBytes),
    )
    // M1/M2 may throw; V2 must return Left without an exception.
    Vector(283, 26).foreach { recovery =>
      if requireTypedFailure then
        assert(CryptoOps.recover(signature.copy(v = recovery), hash).isLeft)
      else
        assert(
          Try(CryptoOps.recover(signature.copy(v = recovery), hash))
            .fold(_ => true, _.isLeft),
        )
    }
    if requireTypedFailure then
      println("Scala.js V2 recovery parameters PASS: 283 and 26 return typed Left")
    else
      println("Scala.js historical recovery parameters PASS: 283 and 26 rejected")
