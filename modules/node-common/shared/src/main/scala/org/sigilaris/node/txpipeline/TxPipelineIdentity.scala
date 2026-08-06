package org.sigilaris.node.txpipeline

import java.nio.charset.StandardCharsets

import cats.effect.kernel.Sync

/** Pipeline identity calculated once from a normalized submit request.
  *
  * The canonical payload hash is the durable replay/collision identity. The
  * pipeline id is the lookup identity stored on the accepted record. Keeping
  * them in one result prevents admission from deriving the two values through
  * separate, potentially divergent paths.
  */
final case class TxPipelineIdentity(
    canonicalPayloadHash: TxPipelineCanonicalPayloadHash,
    pipelineId: TxPipelineId,
)

/** Embedder-owned strategy for deriving durable pipeline identity.
  *
  * Implementations are invoked exactly once for each successfully normalized
  * submission. The returned identity is then reused for the initial replay
  * lookup, record creation, collision convergence, and idempotency alias
  * binding. `canonicalPayloadHash` must be deterministic and
  * collision-resistant over the normalized request and identity scope:
  * admission treats equal hashes as proof that two submissions carry the same
  * canonical request. The pipeline id must be deterministic and the returned
  * hash/id pair must remain stable across every writer and reader that shares
  * durable pipeline state.
  */
trait TxPipelineIdentityStrategy[F[_]]:
  def identify(
      normalized: TxPipelineNormalizedRequest,
  ): F[TxPipelineIdentity]

object TxPipelineIdentityStrategy:
  /** Constructs a strategy from one identity function. */
  def apply[F[_]](
      identifyIdentity: TxPipelineNormalizedRequest => F[TxPipelineIdentity],
  ): TxPipelineIdentityStrategy[F] =
    normalized => identifyIdentity(normalized)

  /** Default v1 identity strategy.
    *
    * This preserves the existing `bbgo.tx-pipeline.id.v1` preimage and SHA-256
    * output. The pipeline id is `txp_` followed by the same lowercase hash.
    */
  def v1[F[_]: Sync](identityScope: String): TxPipelineIdentityStrategy[F] =
    normalized =>
      Sync[F].delay:
        v1Identity(normalized, identityScope)

  /** Pure v1 identity calculation, shared by JVM and Scala.js. */
  def v1Identity(
      normalized: TxPipelineNormalizedRequest,
      identityScope: String,
  ): TxPipelineIdentity =
    val hash = v1CanonicalPayloadHash(normalized, identityScope)
    TxPipelineIdentity(
      canonicalPayloadHash = hash,
      pipelineId = v1PipelineId(hash),
    )

  /** Pure v1 canonical payload hash calculation. */
  def v1CanonicalPayloadHash(
      normalized: TxPipelineNormalizedRequest,
      identityScope: String,
  ): TxPipelineCanonicalPayloadHash =
    TxPipelineCanonicalPayloadHash(
      TxPipelineSha256Platform.digestHex(
        normalized
          .identityPayload(identityScope)
          .value
          .getBytes(StandardCharsets.UTF_8),
      ),
    )

  /** Derives the v1 pipeline id from an already calculated canonical hash. */
  def v1PipelineId(
      canonicalPayloadHash: TxPipelineCanonicalPayloadHash,
  ): TxPipelineId =
    TxPipelineId("txp_" + canonicalPayloadHash.value)

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.ArrayEquals",
    "org.wartremover.warts.NonUnitStatements",
    "org.wartremover.warts.Var",
    "org.wartremover.warts.While",
  ),
)
private[node] object TxPipelineSha256:
  private val InitialHash: Array[Int] = Array(
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c,
    0x1f83d9ab, 0x5be0cd19,
  )

  private val RoundConstants: Array[Int] = Array(
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1,
    0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
    0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786,
    0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
    0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
    0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
    0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
    0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
  )

  def digestHex(bytes: Array[Byte]): String =
    hex(digest(bytes))

  def hex(bytes: Array[Byte]): String =
    bytes.map(byte => f"${byte & 0xff}%02x").mkString

  private def digest(bytes: Array[Byte]): Array[Byte] =
    val maxSupportedInputBytes =
      (Int.MaxValue.toLong / 64L) * 64L - 9L
    require(
      bytes.length.toLong <= maxSupportedInputBytes,
      "SHA-256 input is too large for an in-memory padded byte array",
    )
    val bitLength = bytes.length.toLong * 8L
    val paddedLength =
      (((bytes.length.toLong + 9L + 63L) / 64L) * 64L).toInt
    val padded = Array.fill[Byte](paddedLength)(0x00.toByte)
    Array.copy(bytes, 0, padded, 0, bytes.length)
    padded(bytes.length) = 0x80.toByte

    var lengthIndex = 0
    while lengthIndex < 8 do
      padded(paddedLength - 1 - lengthIndex) =
        (bitLength >>> (lengthIndex * 8)).toByte
      lengthIndex += 1

    val hash     = InitialHash.clone()
    val schedule = Array.ofDim[Int](64)
    var offset   = 0
    while offset < paddedLength do
      var wordIndex = 0
      while wordIndex < 16 do
        val index = offset + wordIndex * 4
        schedule(wordIndex) = ((padded(index) & 0xff) << 24) |
          ((padded(index + 1) & 0xff) << 16) |
          ((padded(index + 2) & 0xff) << 8) |
          (padded(index + 3) & 0xff)
        wordIndex += 1

      while wordIndex < 64 do
        val s0 =
          rotateRight(schedule(wordIndex - 15), 7) ^
            rotateRight(schedule(wordIndex - 15), 18) ^
            (schedule(wordIndex - 15) >>> 3)
        val s1 =
          rotateRight(schedule(wordIndex - 2), 17) ^
            rotateRight(schedule(wordIndex - 2), 19) ^
            (schedule(wordIndex - 2) >>> 10)
        schedule(wordIndex) =
          schedule(wordIndex - 16) + s0 + schedule(wordIndex - 7) + s1
        wordIndex += 1

      var a = hash(0)
      var b = hash(1)
      var c = hash(2)
      var d = hash(3)
      var e = hash(4)
      var f = hash(5)
      var g = hash(6)
      var h = hash(7)

      var round = 0
      while round < 64 do
        val sigma1 =
          rotateRight(e, 6) ^ rotateRight(e, 11) ^ rotateRight(e, 25)
        val choose = (e & f) ^ (~e & g)
        val temporary1 =
          h + sigma1 + choose + RoundConstants(round) + schedule(round)
        val sigma0 =
          rotateRight(a, 2) ^ rotateRight(a, 13) ^ rotateRight(a, 22)
        val majority   = (a & b) ^ (a & c) ^ (b & c)
        val temporary2 = sigma0 + majority

        h = g
        g = f
        f = e
        e = d + temporary1
        d = c
        c = b
        b = a
        a = temporary1 + temporary2
        round += 1

      hash(0) += a
      hash(1) += b
      hash(2) += c
      hash(3) += d
      hash(4) += e
      hash(5) += f
      hash(6) += g
      hash(7) += h
      offset += 64

    val result = Array.ofDim[Byte](32)
    var index  = 0
    while index < hash.length do
      result(index * 4) = (hash(index) >>> 24).toByte
      result(index * 4 + 1) = (hash(index) >>> 16).toByte
      result(index * 4 + 2) = (hash(index) >>> 8).toByte
      result(index * 4 + 3) = hash(index).toByte
      index += 1
    result

  private def rotateRight(value: Int, distance: Int): Int =
    (value >>> distance) | (value << (32 - distance))
