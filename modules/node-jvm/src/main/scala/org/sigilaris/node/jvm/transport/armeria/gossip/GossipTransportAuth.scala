package org.sigilaris.node.jvm.transport.armeria.gossip

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.jvm.runtime.gossip.{
  CanonicalRejection,
  DirectionalSessionId,
  PeerIdentity,
  StaticPeerTransportAuth,
}

private[gossip] object GossipTransportAuth:
  val AuthenticatedPeerHeaderName: String = "x-sigilaris-peer-identity"
  val TransportProofHeaderName: String    = "x-sigilaris-transport-proof"
  val BootstrapCapabilityHeaderName: String =
    "x-sigilaris-bootstrap-capability"

  private val TransportProofInfo      = "sigilaris.transport-proof.v1"
  private val BootstrapCapabilityInfo = "sigilaris.bootstrap-capability.v1"
  private val Utf8                    = StandardCharsets.UTF_8
  private val HmacAlgorithm: String   = "HmacSHA256"
  private val Sha256Algorithm: String = "SHA-256"
  private val MacLength: Int          = 32

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

  def authenticateRequest(
      transportAuth: StaticPeerTransportAuth,
      authenticatedPeerRaw: Option[String],
      transportProofRaw: Option[String],
      httpMethod: String,
      requestPath: String,
      requestBodyBytes: Array[Byte],
  ): Either[CanonicalRejection.HandshakeRejected, PeerIdentity] =
    for
      authenticatedPeer <- parseAuthenticatedPeer(authenticatedPeerRaw)
      proof             <- parseProofHeader(transportProofRaw)
      expected <- issueTransportProof(
        transportAuth = transportAuth,
        authenticatedPeer = authenticatedPeer,
        httpMethod = httpMethod,
        requestPath = requestPath,
        requestBodyBytes = requestBodyBytes,
      ).left.map(handshakeRejected("unknownAuthenticatedPeer", _))
      _ <- Either.cond(
        constantTimeEquals(proof, expected),
        (),
        handshakeRejected(
          "authenticatedPeerProofMismatch",
          ss"peer=${authenticatedPeer.value}",
        ),
      )
    yield authenticatedPeer

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

  def verifyBootstrapCapability(
      transportAuth: StaticPeerTransportAuth,
      raw: Option[String],
      authenticatedPeer: PeerIdentity,
      targetPeer: PeerIdentity,
      sessionId: DirectionalSessionId,
      httpMethod: String,
      requestPath: String,
      requestBodyBytes: Array[Byte],
  ): Either[CanonicalRejection.HandshakeRejected, Unit] =
    for
      presented <- parseCapabilityHeader(raw)
      expected <- issueBootstrapCapability(
        transportAuth = transportAuth,
        authenticatedPeer = authenticatedPeer,
        targetPeer = targetPeer,
        sessionId = sessionId,
        httpMethod = httpMethod,
        requestPath = requestPath,
        requestBodyBytes = requestBodyBytes,
      ).left.map(handshakeRejected("unknownAuthenticatedPeer", _))
      _ <- Either.cond(
        constantTimeEquals(presented, expected),
        (),
        handshakeRejected(
          "bootstrapCapabilityMismatch",
          ss"peer=${authenticatedPeer.value} sessionId=${sessionId.value}",
        ),
      )
    yield ()

  private def parseAuthenticatedPeer(
      raw: Option[String],
  ): Either[CanonicalRejection.HandshakeRejected, PeerIdentity] =
    raw
      .toRight:
        handshakeRejected(
          "missingAuthenticatedPeer",
          AuthenticatedPeerHeaderName,
        )
      .flatMap: value =>
        PeerIdentity
          .parse(value)
          .leftMap(handshakeRejected("invalidAuthenticatedPeer", _))

  private def parseProofHeader(
      raw: Option[String],
  ): Either[CanonicalRejection.HandshakeRejected, String] =
    parseMacHeader(
      raw = raw,
      missingReason = "missingTransportProof",
      invalidReason = "invalidTransportProof",
      headerName = TransportProofHeaderName,
    )

  private def parseCapabilityHeader(
      raw: Option[String],
  ): Either[CanonicalRejection.HandshakeRejected, String] =
    parseMacHeader(
      raw = raw,
      missingReason = "missingBootstrapCapability",
      invalidReason = "invalidBootstrapCapability",
      headerName = BootstrapCapabilityHeaderName,
    )

  private def parseMacHeader(
      raw: Option[String],
      missingReason: String,
      invalidReason: String,
      headerName: String,
  ): Either[CanonicalRejection.HandshakeRejected, String] =
    raw
      .toRight:
        handshakeRejected(
          missingReason,
          headerName,
        )
      .flatMap: value =>
        decodeMac(value)
          .toRight(handshakeRejected(invalidReason, value))
          .map(_ => value)

  private def decodeMac(
      raw: String,
  ): Option[ByteVector] =
    scala.util
      .Try(ByteVector.view(Base64.getUrlDecoder.decode(raw)))
      .toOption
      .filter(_.size == MacLength.toLong)

  private def encodeMac(
      bytes: ByteVector,
  ): String =
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

  private def encodeTuple(
      fields: ByteVector*,
  ): ByteVector =
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

  private def constantTimeEquals(
      left: String,
      right: String,
  ): Boolean =
    (decodeMac(left), decodeMac(right)) match
      case (Some(leftBytes), Some(rightBytes)) =>
        MessageDigest.isEqual(leftBytes.toArray, rightBytes.toArray)
      case _ =>
        false

  private def handshakeRejected(
      reason: String,
      detail: String,
  ): CanonicalRejection.HandshakeRejected =
    CanonicalRejection.HandshakeRejected(
      reason = reason,
      detail = Some(detail),
    )
