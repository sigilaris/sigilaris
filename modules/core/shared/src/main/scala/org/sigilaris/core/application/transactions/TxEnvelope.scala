package org.sigilaris.core.application.transactions

import java.time.Instant

import cats.Eq

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.datatype.Utf8

/** Common metadata attached to every transaction payload.
  *
  * The envelope travels alongside the domain specific body so reducers can make
  * consistent decisions about replay protection, auditing and cross-network
  * routing without the individual transaction types having to duplicate those
  * concerns.
  *
  * @param networkId
  *   blockchain/network identifier encoded as an opaque [[NetworkId]]
  * @param createdAt
  *   client supplied timestamp indicating when the transaction was authored
  * @param memo
  *   optional free form note persisted for operational auditing
  */
final case class TxEnvelope(
    networkId: NetworkId,
    createdAt: Instant,
    memo: Option[Utf8],
) derives ByteEncoder, ByteDecoder

object TxEnvelope:
  given Eq[TxEnvelope] = Eq.fromUniversalEquals
