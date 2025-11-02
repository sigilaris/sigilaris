package org.sigilaris.core.application.security

import java.time.Instant

import cats.Monad
import cats.syntax.eq.*

import org.sigilaris.core.application.feature.accounts.domain.{Account, KeyId20, KeyInfo}
import org.sigilaris.core.application.state.StoreF
import org.sigilaris.core.application.transactions.model.{AccountSignature, Signed, Tx}
import org.sigilaris.core.crypto.{Hash, PublicKey, Recover}
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.core.failure.CryptoFailure
import org.sigilaris.core.util.SafeStringInterp.*

object SignatureVerifier:
  def recoverKeyId[F[_]: Monad, T <: Tx](
      signedTx: Signed[T],
      context: Option[String],
  )(using hashT: Hash[T], recoverT: Recover[T]): StoreF[F][KeyId20] =
    val tx = signedTx.value
    val signature = signedTx.sig
    val txHash = hashT(tx)

    recoverT.fromHash(txHash, signature.sig) match
      case Left(err) =>
        val prefix: String = context match
          case Some(ctx) => ss"Signature recovery failed for ${ctx}"
          case None      => "Signature recovery failed"
        StoreF.raise[F, KeyId20](CryptoFailure(ss"${prefix}: ${err.msg}"))
      case Right(recoveredPubKey) =>
        StoreF.pure[F, KeyId20](deriveKeyId20(recoveredPubKey))

  /** Verify that the recovered key belongs to the claimed signer and is not expired. */
  def verifyKeyOwnership[F[_]: Monad](
      accountSig: AccountSignature,
      recoveredKeyId: KeyId20,
      envelopeTimestamp: Instant,
  )(
      lookup: (Utf8, KeyId20) => StoreF[F][Option[KeyInfo]],
  ): StoreF[F][Unit] =
    accountSig.account match
      case named: Account.Named =>
        val signerName = named.name
        lookup(signerName, recoveredKeyId).flatMap {
          case None =>
            StoreF.raise[F, Unit](
              CryptoFailure(
                ss"Key ${recoveredKeyId.bytes.toHex} not registered for account ${signerName.asString}"
              )
            )
          case Some(keyInfo) =>
            keyInfo.expiresAt match
              case Some(expiresAt) if envelopeTimestamp.isAfter(expiresAt) =>
                StoreF.raise[F, Unit](
                  CryptoFailure(
                    ss"Key expired at ${expiresAt.toString}, transaction timestamp: ${envelopeTimestamp.toString}"
                  )
                )
              case _ =>
                StoreF.pure[F, Unit](())
        }

      case Account.Unnamed(keyId) =>
        if recoveredKeyId === keyId then
          StoreF.pure[F, Unit](())
        else
          StoreF.raise[F, Unit](
            CryptoFailure(
              ss"Recovered key ${recoveredKeyId.bytes.toHex} does not match unnamed account ${keyId.bytes.toHex}"
            )
          )

  /** Recover the signer key and verify ownership in a single step. */
  def verifySignature[F[_]: Monad, T <: Tx](
      signedTx: Signed[T],
      envelopeTimestamp: Instant,
      context: Option[String],
  )(
      lookup: (Utf8, KeyId20) => StoreF[F][Option[KeyInfo]],
  )(using Hash[T], Recover[T]): StoreF[F][KeyId20] =
    for
      recoveredKeyId <- recoverKeyId[F, T](signedTx, context)
      _ <- verifyKeyOwnership[F](signedTx.sig, recoveredKeyId, envelopeTimestamp)(lookup)
    yield recoveredKeyId

  /** Derive KeyId20 from a recovered public key following Ethereum convention. */
  private def deriveKeyId20(pubKey: PublicKey): KeyId20 =
    val pubKeyBytes = pubKey.toBytes
    val hash = org.sigilaris.core.crypto.CryptoOps.keccak256(pubKeyBytes.toArray)
    KeyId20.unsafeApply(scodec.bits.ByteVector.view(hash).takeRight(20))
