package org.sigilaris.core.crypto

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import java.security.SecureRandom
import scala.compiletime.uninitialized

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = Array("-Xms2g","-Xmx2g"))
@Warmup(iterations = 3)
@Measurement(iterations = 5)
class CryptoOpsBenchmark:
  private var rnd: SecureRandom      = uninitialized
  private var kp: KeyPair            = uninitialized
  private var msg: Array[Byte]       = uninitialized
  private var hash: Array[Byte]      = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    rnd = new SecureRandom()
    // Deterministic, provider-free keypair for stable benchmarks
    kp = CryptoOps.fromPrivate(BigInt(1))
    msg =
      val arr = new Array[Byte](32)
      rnd.nextBytes(arr)
      arr
    hash = CryptoOps.keccak256(msg)

  @Benchmark
  def sign(): AnyRef = CryptoOps.sign(kp, hash)

  @Benchmark
  def recover(): AnyRef =
    val sig = CryptoOps.sign(kp, hash).toOption.get
    CryptoOps.recover(sig, hash)

  @Benchmark
  def fromPrivate(): AnyRef =
    CryptoOps.fromPrivate(kp.privateKey.toBigInt)

  @Benchmark
  def keccak256(): Array[Byte] =
    CryptoOps.keccak256(msg)


