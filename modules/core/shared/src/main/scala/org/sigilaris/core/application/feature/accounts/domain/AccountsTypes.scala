package org.sigilaris.core.application.feature.accounts.domain

import java.time.Instant

import scala.annotation.targetName

import cats.Eq
import scodec.bits.ByteVector

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder, DecodeResult}
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.datatype.{
  BigNat,
  FixedSizeByteValueCompanion,
  OpaqueValueCompanion,
  Utf8,
  ValidatedOpaqueValueCompanion,
}
import org.sigilaris.core.failure.DecodeFailure
import org.sigilaris.core.crypto.{CryptoOps, PublicKey}

/** Account identifier - can be either Named or Unnamed.
  *
  * Named accounts use UTF-8 strings as identifiers and support key recovery.
  * Unnamed accounts use KeyId20 (20-byte hash) and have no recovery mechanism.
  */
enum Account:
  /** A named account identified by a UTF-8 string, supporting key recovery.
    *
    * @param name the account name
    */
  case Named(name: Utf8)

  /** An unnamed account identified only by a KeyId20, with no recovery mechanism.
    *
    * @param keyId the 20-byte key identifier
    */
  case Unnamed(keyId: KeyId20)

/** Companion for [[Account]], providing codec instances and equality. */
@SuppressWarnings(
  Array("org.wartremover.warts.Any", "org.wartremover.warts.Nothing"),
)
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
    if bytes.isEmpty then Left(DecodeFailure("Empty bytes for Account"))
    else
      val (tag, remainder) = (bytes.head, bytes.tail)
      tag match
        case 0x00 =>
          ByteDecoder[Utf8]
            .decode(remainder)
            .map(r => DecodeResult(Account.Named(r.value), r.remainder))
        case 0x01 =>
          ByteDecoder[KeyId20]
            .decode(remainder)
            .map(r => DecodeResult(Account.Unnamed(r.value), r.remainder))
        case _ =>
          Left(DecodeFailure(s"Invalid Account tag: ${String.valueOf(tag)}"))

/** Public key identifier (20 bytes).
  *
  * Derived from public key using Ethereum's method:
  * Keccak256(publicKey)[12..32] (last 20 bytes of hash)
  */
opaque type KeyId20 = ByteVector

/** Companion for [[KeyId20]], providing construction, codec instances, and equality. */
object KeyId20 extends FixedSizeByteValueCompanion[KeyId20]:
  override protected val size: Int    = 20
  override protected val label: String = "KeyId20"

  override protected def wrap(bytes: ByteVector): KeyId20 = bytes

  override protected def unwrap(value: KeyId20): ByteVector = value

  /** Deterministically derives the key identifier for a public key. */
  def fromPublicKey(publicKey: PublicKey): KeyId20 =
    unsafe:
      ByteVector.view(
        CryptoOps.keccak256(publicKey.toBytes.toArray),
      ).takeRight(20)

  /** Unsafe version that assumes bytes is exactly 20 bytes (for internal use). */
  def unsafeApply(bytes: ByteVector): KeyId20 = unsafe(bytes)

/** Account nonce stored on-chain and used for replay protection. */
opaque type AccountNonce = BigNat

/** Companion for [[AccountNonce]]. */
object AccountNonce extends OpaqueValueCompanion[AccountNonce, BigNat]:
  inline def apply(nonce: BigNat): AccountNonce = nonce

  val Zero: AccountNonce = apply(BigNat.Zero)

  def unsafeFromLong(value: Long): AccountNonce =
    apply(BigNat.unsafeFromLong(value))

  protected def wrap(repr: BigNat): AccountNonce = repr

  protected def unwrap(value: AccountNonce): BigNat = value

  extension (nonce: AccountNonce)
    inline def toBigNat: BigNat = nonce

    def next: AccountNonce =
      apply(BigNat.add(nonce.toBigNat, BigNat.One))

/** Non-empty map of key identifiers to descriptions used by AddKeyIds. */
opaque type NonEmptyKeyIdDescriptions = Map[KeyId20, Utf8]

/** Companion for [[NonEmptyKeyIdDescriptions]]. */
object NonEmptyKeyIdDescriptions
    extends ValidatedOpaqueValueCompanion[
      NonEmptyKeyIdDescriptions,
      Map[KeyId20, Utf8],
    ]:
  def apply(
      entries: Map[KeyId20, Utf8],
  ): Either[String, NonEmptyKeyIdDescriptions] =
    Either.cond(entries.nonEmpty, wrap(entries), "keyIds must be non-empty")

  protected def wrap(
      repr: Map[KeyId20, Utf8],
  ): NonEmptyKeyIdDescriptions = repr

  protected def unwrap(
      value: NonEmptyKeyIdDescriptions,
  ): Map[KeyId20, Utf8] = value

  // Byte decoding stays backward-compatible with legacy empty payloads;
  // constructor-validated entry points still reject them for new values.
  override protected def decodeByteRepr(
      repr: Map[KeyId20, Utf8],
  ): Either[DecodeFailure, NonEmptyKeyIdDescriptions] =
    Right[DecodeFailure, NonEmptyKeyIdDescriptions](wrap(repr))

  override protected def decodeJsonRepr(
      repr: Map[KeyId20, Utf8],
  ): Either[DecodeFailure, NonEmptyKeyIdDescriptions] =
    Right[DecodeFailure, NonEmptyKeyIdDescriptions](wrap(repr))

  extension (entries: NonEmptyKeyIdDescriptions)
    inline def toMap: Map[KeyId20, Utf8] = entries

    inline def keySet: Set[KeyId20] = entries.toMap.keySet

/** Non-empty set of key identifiers used by RemoveKeyIds. */
opaque type NonEmptyKeyIds = Set[KeyId20]

/** Companion for [[NonEmptyKeyIds]]. */
object NonEmptyKeyIds
    extends ValidatedOpaqueValueCompanion[NonEmptyKeyIds, Set[KeyId20]]:
  def apply(
      entries: Set[KeyId20],
  ): Either[String, NonEmptyKeyIds] =
    Either.cond(entries.nonEmpty, wrap(entries), "keyIds must be non-empty")

  protected def wrap(repr: Set[KeyId20]): NonEmptyKeyIds = repr

  protected def unwrap(value: NonEmptyKeyIds): Set[KeyId20] = value

  // Byte decoding stays backward-compatible with legacy empty payloads;
  // constructor-validated entry points still reject them for new values.
  override protected def decodeByteRepr(
      repr: Set[KeyId20],
  ): Either[DecodeFailure, NonEmptyKeyIds] =
    Right[DecodeFailure, NonEmptyKeyIds](wrap(repr))

  override protected def decodeJsonRepr(
      repr: Set[KeyId20],
  ): Either[DecodeFailure, NonEmptyKeyIds] =
    Right[DecodeFailure, NonEmptyKeyIds](wrap(repr))

  extension (entries: NonEmptyKeyIds)
    inline def toSet: Set[KeyId20] = entries

/** Account information stored on-chain.
  *
  * @param guardian
  *   optional guardian account for key recovery
  * @param nonce
  *   sequential number for replay attack prevention (increments by exactly 1)
  */
final case class AccountInfo(
    guardian: Option[Account],
    nonce: AccountNonce,
) derives ByteEncoder,
      ByteDecoder

/** Companion for [[AccountInfo]], providing equality. */
object AccountInfo:
  @targetName("applyBigNat")
  def apply(
      guardian: Option[Account],
      nonce: BigNat,
  ): AccountInfo =
    AccountInfo(guardian = guardian, nonce = AccountNonce(nonce))

  given accountInfoEq: Eq[AccountInfo] = Eq.fromUniversalEquals

/** Key registration information for Named accounts.
  *
  * @param addedAt
  *   timestamp when the key was added
  * @param expiresAt
  *   optional expiration timestamp
  * @param description
  *   user-provided description of the key
  */
final case class KeyInfo(
    addedAt: Instant,
    expiresAt: Option[Instant],
    description: Utf8,
) derives ByteEncoder,
      ByteDecoder

/** Companion for [[KeyInfo]], providing equality. */
object KeyInfo:
  given keyInfoEq: Eq[KeyInfo] = Eq.fromUniversalEquals
