package org.sigilaris.core.application.feature.group.domain

import java.time.Instant
import scala.annotation.targetName

import cats.Eq

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.datatype.{
  BigNat,
  OpaqueValueCompanion,
  Utf8,
  ValidatedKeyLikeOpaqueValueCompanion,
  ValidatedOpaqueValueCompanion,
}
import org.sigilaris.core.failure.DecodeFailure
import org.sigilaris.core.application.feature.accounts.domain.Account

/** Group identifier using UTF-8 string (no format constraints).
  *
  * Groups are identified by arbitrary UTF-8 strings for operational purposes.
  * No length constraints or normalization policies are enforced.
  */
opaque type GroupId = Utf8

/** Companion for [[GroupId]], providing construction, codecs, and conversions. */
object GroupId extends ValidatedKeyLikeOpaqueValueCompanion[GroupId, Utf8]:
  def apply(utf8: Utf8): Either[String, GroupId] =
    Either.cond(utf8.asString.nonEmpty, wrap(utf8), "GroupId must be non-empty")

  def fromString(value: String): Either[String, GroupId] =
    apply(Utf8(value))

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def unsafeFromString(value: String): GroupId =
    unsafe(Utf8(value))

  protected def wrap(repr: Utf8): GroupId = repr

  protected def unwrap(value: GroupId): Utf8 = value

  // Byte decoding stays backward-compatible with legacy empty identifiers;
  // constructor-validated entry points still reject them for new values.
  override protected def decodeByteRepr(
      repr: Utf8,
  ): Either[DecodeFailure, GroupId] =
    Right[DecodeFailure, GroupId](wrap(repr))

  override protected def decodeJsonRepr(
      repr: Utf8,
  ): Either[DecodeFailure, GroupId] =
    Right[DecodeFailure, GroupId](wrap(repr))

  extension (g: GroupId)
    /** Converts this GroupId back to its underlying Utf8 representation.
      *
      * @return the UTF-8 string
      */
    inline def toUtf8: Utf8 = g

/** Replay-protection nonce for group mutations. */
opaque type GroupNonce = BigNat

/** Companion for [[GroupNonce]]. */
object GroupNonce extends OpaqueValueCompanion[GroupNonce, BigNat]:
  inline def apply(nonce: BigNat): GroupNonce = nonce

  val Zero: GroupNonce = apply(BigNat.Zero)

  def unsafeFromLong(value: Long): GroupNonce =
    apply(BigNat.unsafeFromLong(value))

  protected def wrap(repr: BigNat): GroupNonce = repr

  protected def unwrap(value: GroupNonce): BigNat = value

  extension (nonce: GroupNonce)
    inline def toBigNat: BigNat = nonce

    def next: GroupNonce =
      apply(BigNat.add(nonce.toBigNat, BigNat.One))

/** Number of accounts currently enrolled in a group. */
opaque type MemberCount = BigNat

/** Companion for [[MemberCount]]. */
object MemberCount extends OpaqueValueCompanion[MemberCount, BigNat]:
  inline def apply(count: BigNat): MemberCount = count

  val Zero: MemberCount = apply(BigNat.Zero)

  protected def wrap(repr: BigNat): MemberCount = repr

  protected def unwrap(value: MemberCount): BigNat = value

  extension (count: MemberCount)
    inline def toBigNat: BigNat = count

    def tryAdd(delta: Int): Either[String, MemberCount] =
      Either
        .cond(
          delta >= 0,
          delta,
          "MemberCount.add requires non-negative delta",
        )
        .map: nonNegativeDelta =>
          apply(
            BigNat.add(
              count.toBigNat,
              BigNat.unsafeFromLong(nonNegativeDelta.toLong),
            ),
          )

    @SuppressWarnings(Array("org.wartremover.warts.Throw"))
    def add(delta: Int): MemberCount =
      tryAdd(delta) match
        case Right(updated) => updated
        case Left(error)    => throw new IllegalArgumentException(error)

    def trySubtract(delta: Int): Either[String, MemberCount] =
      Either
        .cond(
          delta >= 0,
          BigNat.unsafeFromLong(delta.toLong),
          "MemberCount.subtract requires non-negative delta",
        )
        .flatMap: nonNegativeDelta =>
          BigNat
            .tryToSubtract(count.toBigNat, nonNegativeDelta)
            .map(apply)

    @SuppressWarnings(Array("org.wartremover.warts.Throw"))
    def unsafeSubtract(delta: Int): MemberCount =
      trySubtract(delta) match
        case Right(updated) => updated
        case Left(error)    => throw new IllegalArgumentException(error)

/** Non-empty set of group members supplied to membership mutation transactions. */
opaque type NonEmptyGroupAccounts = Set[Account]

/** Companion for [[NonEmptyGroupAccounts]]. */
object NonEmptyGroupAccounts
    extends ValidatedOpaqueValueCompanion[
      NonEmptyGroupAccounts,
      Set[Account],
    ]:
  def apply(
      accounts: Set[Account],
  ): Either[String, NonEmptyGroupAccounts] =
    Either.cond(
      accounts.nonEmpty,
      wrap(accounts),
      "accounts must be non-empty",
    )

  protected def wrap(repr: Set[Account]): NonEmptyGroupAccounts = repr

  protected def unwrap(value: NonEmptyGroupAccounts): Set[Account] = value

  // Byte decoding stays backward-compatible with legacy empty payloads;
  // constructor-validated entry points still reject them for new values.
  override protected def decodeByteRepr(
      repr: Set[Account],
  ): Either[DecodeFailure, NonEmptyGroupAccounts] =
    Right[DecodeFailure, NonEmptyGroupAccounts](wrap(repr))

  override protected def decodeJsonRepr(
      repr: Set[Account],
  ): Either[DecodeFailure, NonEmptyGroupAccounts] =
    Right[DecodeFailure, NonEmptyGroupAccounts](wrap(repr))

  extension (accounts: NonEmptyGroupAccounts)
    inline def toSet: Set[Account] = accounts

/** Group metadata stored on-chain.
  *
  * @param name
  *   human-readable group name (immutable after creation)
  * @param coordinator
  *   account with management permissions
  * @param nonce
  *   sequential number for replay attack prevention (increments by exactly 1)
  * @param memberCount
  *   number of accounts in the group (excluding coordinator)
  * @param createdAt
  *   timestamp when the group was created
  */
final case class GroupData(
    name: Utf8,
    coordinator: Account,
    nonce: GroupNonce,
    memberCount: MemberCount,
    createdAt: Instant,
) derives ByteEncoder,
      ByteDecoder

/** Companion for [[GroupData]], providing equality. */
object GroupData:
  @targetName("applyBigNat")
  def apply(
      name: Utf8,
      coordinator: Account,
      nonce: BigNat,
      memberCount: BigNat,
      createdAt: Instant,
  ): GroupData =
    GroupData(
      name = name,
      coordinator = coordinator,
      nonce = GroupNonce(nonce),
      memberCount = MemberCount(memberCount),
      createdAt = createdAt,
    )

  given groupDataEq: Eq[GroupData] = Eq.fromUniversalEquals
