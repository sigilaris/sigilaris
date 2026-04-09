package org.sigilaris.core.application.feature.group.domain

import java.time.Instant

import cats.Eq

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.datatype.{BigNat, KeyLikeOpaqueValueCompanion, Utf8}
import org.sigilaris.core.application.feature.accounts.domain.Account

/** Group identifier using UTF-8 string (no format constraints).
  *
  * Groups are identified by arbitrary UTF-8 strings for operational purposes.
  * No length constraints or normalization policies are enforced.
  */
opaque type GroupId = Utf8

/** Companion for [[GroupId]], providing construction, codecs, and conversions. */
object GroupId extends KeyLikeOpaqueValueCompanion[GroupId, Utf8]:
  /** Creates a GroupId from a UTF-8 string.
    *
    * @param utf8 the identifier string
    * @return a new GroupId
    */
  inline def apply(utf8: Utf8): GroupId = utf8

  protected def wrap(repr: Utf8): GroupId = repr

  protected def unwrap(value: GroupId): Utf8 = value

  extension (g: GroupId)
    /** Converts this GroupId back to its underlying Utf8 representation.
      *
      * @return the UTF-8 string
      */
    inline def toUtf8: Utf8 = g

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
    nonce: BigNat,
    memberCount: BigNat,
    createdAt: Instant,
) derives ByteEncoder,
      ByteDecoder

/** Companion for [[GroupData]], providing equality. */
object GroupData:
  given groupDataEq: Eq[GroupData] = Eq.fromUniversalEquals
