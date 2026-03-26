package org.sigilaris.core.application.feature.accounts.domain

import java.time.Instant

import cats.Eq
import scodec.bits.ByteVector

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.collection.FixedLength

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder, DecodeResult}
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.datatype.{BigNat, Utf8}
import org.sigilaris.core.failure.DecodeFailure

/** Account identifier - can be either Named or Unnamed.
  *
  * Named accounts use UTF-8 strings as identifiers and support key recovery.
  * Unnamed accounts use KeyId20 (20-byte hash) and have no recovery mechanism.
  */
enum Account:
  case Named(name: Utf8)
  case Unnamed(keyId: KeyId20)

@SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.Nothing"))
object Account:
  given accountEq: Eq[Account] = Eq.fromUniversalEquals

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  given accountByteEncoder: ByteEncoder[Account] = account =>
    account match
      case Account.Named(name) =>
        ByteVector(0x00) ++ name.toBytes
      case Account.Unnamed(keyId) =>
        ByteVector(0x01) ++ keyId.toBytes

  @SuppressWarnings(Array("org.wartremover.warts.Nothing"))
  given accountByteDecoder: ByteDecoder[Account] = bytes =>
    if bytes.isEmpty then
      Left(DecodeFailure("Empty bytes for Account"))
    else
      val (tag, remainder) = (bytes.head, bytes.tail)
      tag match
        case 0x00 =>
          ByteDecoder[Utf8].decode(remainder).map(r => DecodeResult(Account.Named(r.value), r.remainder))
        case 0x01 =>
          ByteDecoder[KeyId20].decode(remainder).map(r => DecodeResult(Account.Unnamed(r.value), r.remainder))
        case _ =>
          Left(DecodeFailure(s"Invalid Account tag: ${String.valueOf(tag)}"))

/** Public key identifier (20 bytes).
  *
  * Derived from public key using Ethereum's method:
  * Keccak256(publicKey)[12..32] (last 20 bytes of hash)
  *
  * Uses Iron constraint to guarantee 20-byte length at type level.
  * Note: FixedLength[20] uses Int, but works correctly with ByteVector.size (Long)
  * due to Scala's automatic Int-to-Long widening in the constraint check.
  */
opaque type KeyId20 = ByteVector :| FixedLength[20]

object KeyId20:
  // Import the LengthByteVector constraint from util.iron
  import org.sigilaris.core.util.iron.given

  /** Creates a KeyId20 from a ByteVector, validating it's exactly 20 bytes.
    *
    * @param bytes must be exactly 20 bytes
    * @return Right(KeyId20) if valid, Left(error) if not
    */
  def apply(bytes: ByteVector): Either[String, KeyId20] =
    bytes.refineEither[FixedLength[20]]

  /** Unsafe version that assumes bytes is exactly 20 bytes (for internal use).
    *
    * @throws IllegalArgumentException if bytes is not 20 bytes
    */
  def unsafeApply(bytes: ByteVector): KeyId20 =
    bytes.refineUnsafe[FixedLength[20]]

  extension (k: KeyId20)
    def bytes: ByteVector = k

  given keyId20Eq: Eq[KeyId20] = Eq.fromUniversalEquals

  // ByteEncoder: encode the underlying ByteVector directly (no size prefix)
  given keyId20ByteEncoder: ByteEncoder[KeyId20] = (k: KeyId20) =>
    k.bytes

  // ByteDecoder: read exactly 20 bytes and validate
  @SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.Nothing"))
  given keyId20ByteDecoder: ByteDecoder[KeyId20] = bytes =>
    if bytes.size >= 20 then
      val (keyBytes, remainder) = bytes.splitAt(20)
      apply(keyBytes) match
        case Right(keyId) => Right(DecodeResult(keyId, remainder))
        case Left(err) => Left(DecodeFailure(err))
    else
      Left(DecodeFailure(s"Insufficient bytes for KeyId20: expected 20, got ${bytes.size}"))

/** Account information stored on-chain.
  *
  * @param guardian optional guardian account for key recovery
  * @param nonce sequential number for replay attack prevention (increments by exactly 1)
  */
final case class AccountInfo(
    guardian: Option[Account],
    nonce: BigNat,
) derives ByteEncoder, ByteDecoder

object AccountInfo:
  given accountInfoEq: Eq[AccountInfo] = Eq.fromUniversalEquals

/** Key registration information for Named accounts.
  *
  * @param addedAt timestamp when the key was added
  * @param expiresAt optional expiration timestamp
  * @param description user-provided description of the key
  */
final case class KeyInfo(
    addedAt: Instant,
    expiresAt: Option[Instant],
    description: Utf8,
) derives ByteEncoder, ByteDecoder

object KeyInfo:
  given keyInfoEq: Eq[KeyInfo] = Eq.fromUniversalEquals
