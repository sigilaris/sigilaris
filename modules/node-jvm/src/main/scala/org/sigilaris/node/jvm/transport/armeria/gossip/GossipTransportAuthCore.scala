package org.sigilaris.node.jvm.transport.armeria.gossip

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import scodec.bits.ByteVector

import org.sigilaris.node.gossip.{DirectionalSessionId, PeerIdentity, StaticPeerTransportAuth}

private[gossip] object GossipTransportAuthCore:
  private val TransportProofInfo      = "sigilaris.transport-proof.v1"
  private val BootstrapCapabilityInfo = "sigilaris.bootstrap-capability.v1"
  private val Utf8                    = StandardCharsets.UTF_8
  private val HmacAlgorithm: String   = "HmacSHA256"
  private val Sha256Algorithm: String = "SHA-256"
  private[gossip] val MacLength: Int  = 32

  def issueTransportProof(
      transportAuth: StaticPeerTransportAuth,
      authenticatedPeer: PeerIdentity,
      httpMethod: String,
      requestPath: String,
      requestBodyBytes: Array[Byte],
  ): Either[String, String] =
    transportAuth
      .secretFor(authenticatedPeer)
      .map: secret =>
        encodeMac(
          mac(
            key = deriveKey(secret.bytes, TransportProofInfo),
            message = transportProofInput(
              authenticatedPeer = authenticatedPeer,
              httpMethod = httpMethod,
              requestPath = requestPath,
              requestBodyBytes = requestBodyBytes,
            ),
          ),
        )

  def issueBootstrapCapability(
      transportAuth: StaticPeerTransportAuth,
      authenticatedPeer: PeerIdentity,
      targetPeer: PeerIdentity,
      sessionId: DirectionalSessionId,
      httpMethod: String,
      requestPath: String,
      requestBodyBytes: Array[Byte],
  ): Either[String, String] =
    transportAuth
      .secretFor(authenticatedPeer)
      .map: secret =>
        encodeMac(
          mac(
            key = deriveKey(secret.bytes, BootstrapCapabilityInfo),
            message = bootstrapCapabilityInput(
              authenticatedPeer = authenticatedPeer,
              targetPeer = targetPeer,
              sessionId = sessionId,
              httpMethod = httpMethod,
              requestPath = requestPath,
              requestBodyBytes = requestBodyBytes,
            ),
          ),
        )

  def decodeMac(raw: String): Option[ByteVector] =
    scala.util
      .Try(ByteVector.view(Base64.getUrlDecoder.decode(raw)))
      .toOption
      .filter(_.size == MacLength.toLong)

  def constantTimeEquals(left: String, right: String): Boolean =
    (decodeMac(left), decodeMac(right)) match
      case (Some(leftBytes), Some(rightBytes)) =>
        MessageDigest.isEqual(leftBytes.toArray, rightBytes.toArray)
      case _ =>
        false

  private def encodeMac(bytes: ByteVector): String =
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes.toArray)

  private def transportProofInput(
      authenticatedPeer: PeerIdentity,
      httpMethod: String,
      requestPath: String,
      requestBodyBytes: Array[Byte],
  ): ByteVector =
    encodeTuple(
      ByteVector.view(TransportProofInfo.getBytes(Utf8)),
      ByteVector.view(authenticatedPeer.value.getBytes(Utf8)),
      ByteVector.view(httpMethod.getBytes(Utf8)),
      ByteVector.view(requestPath.getBytes(Utf8)),
      requestBodyDigest(requestBodyBytes),
    )

  private def bootstrapCapabilityInput(
      authenticatedPeer: PeerIdentity,
      targetPeer: PeerIdentity,
      sessionId: DirectionalSessionId,
      httpMethod: String,
      requestPath: String,
      requestBodyBytes: Array[Byte],
  ): ByteVector =
    encodeTuple(
      ByteVector.view(BootstrapCapabilityInfo.getBytes(Utf8)),
      ByteVector.view(authenticatedPeer.value.getBytes(Utf8)),
      ByteVector.view(targetPeer.value.getBytes(Utf8)),
      ByteVector.view(sessionId.value.getBytes(Utf8)),
      ByteVector.view(httpMethod.getBytes(Utf8)),
      ByteVector.view(requestPath.getBytes(Utf8)),
      requestBodyDigest(requestBodyBytes),
    )

  private def requestBodyDigest(
      requestBodyBytes: Array[Byte],
  ): ByteVector =
    ByteVector.view:
      MessageDigest.getInstance(Sha256Algorithm).digest(requestBodyBytes)

  private def encodeTuple(fields: ByteVector*): ByteVector =
    fields.foldLeft(ByteVector.empty): (acc, field) =>
      acc ++ ByteVector.fromInt(field.size.toInt) ++ field

  private def deriveKey(
      secret: ByteVector,
      info: String,
  ): ByteVector =
    val prk = mac(ByteVector.empty, secret)
    mac(
      key = prk,
      message = ByteVector.view(info.getBytes(Utf8)) ++ ByteVector(1.toByte),
    )

  private def mac(
      key: ByteVector,
      message: ByteVector,
  ): ByteVector =
    val instance = Mac.getInstance(HmacAlgorithm)
    val keyBytes =
      if key.isEmpty then Array.fill[Byte](64)(0)
      else key.toArray
    instance.init(new SecretKeySpec(keyBytes, HmacAlgorithm))
    ByteVector.view(instance.doFinal(message.toArray))
