package org.sigilaris.node.jvm.transport.armeria.gossip

import java.nio.charset.Charset
import java.nio.file.Files
import java.time.Duration

import scala.jdk.DurationConverters.*
import scala.util.Try

import cats.effect.Async
import cats.syntax.all.*
import sttp.client4.*
import sttp.model.Header
import sttp.model.Uri
import sttp.tapir.DecodeResult
import sttp.tapir.client.sttp4.SttpClientInterpreter

import org.sigilaris.node.gossip.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.BootstrapSessionBinding

/** Failures produced by endpoint-derived gossip clients before a canonical peer
  * rejection is available.
  */
sealed trait GossipPeerClientError extends Product with Serializable:
  def reason: String
  def detail: Option[String]

object GossipPeerClientError:
  final case class TransportFailure(
      reason: String,
      detail: Option[String],
  ) extends GossipPeerClientError

  final case class ResponseDecodeFailure(
      reason: String,
      detail: Option[String],
  ) extends GossipPeerClientError

  final case class HttpStatusFailure(
      statusCode: Int,
      reason: String,
      detail: Option[String],
  ) extends GossipPeerClientError

private[gossip] object GossipTapirClientCore:
  private val Interpreter = SttpClientInterpreter()

  private[gossip] final case class PreparedRequest[T](
      request: Request[T],
      method: String,
      path: String,
      bodyBytes: Array[Byte],
  )

  private final case class DecodedResponse[E, O](
      statusCode: Int,
      value: Either[E, O],
  )

  def txSessionOpenRequest(
      baseUri: Uri,
      rawProposal: String,
      requestTimeout: Duration,
  ): Either[GossipPeerClientError, PreparedRequest[
    DecodeResult[Either[String, String]],
  ]] =
    prepare(
      Interpreter
        .toRequest(TxGossipTapirEndpoints.sessionOpen, Some(baseUri))(
          (None, None, rawProposal),
        ),
      requestTimeout,
    )

  def txEventStreamRequest(
      baseUri: Uri,
      sessionId: DirectionalSessionId,
      rawRequest: String,
      requestTimeout: Duration,
  ): Either[GossipPeerClientError, PreparedRequest[
    DecodeResult[Either[Unit, Array[Byte]]],
  ]] =
    prepare(
      Interpreter
        .toRequest(TxGossipTapirEndpoints.eventStream, Some(baseUri))(
          (sessionId.value, None, None, rawRequest),
        ),
      requestTimeout,
    )

  def txControlRequest(
      baseUri: Uri,
      sessionId: DirectionalSessionId,
      rawRequest: String,
      requestTimeout: Duration,
  ): Either[GossipPeerClientError, PreparedRequest[
    DecodeResult[Either[String, String]],
  ]] =
    prepare(
      Interpreter
        .toRequest(TxGossipTapirEndpoints.control, Some(baseUri))(
          (sessionId.value, None, None, rawRequest),
        ),
      requestTimeout,
    )

  def txDisconnectRequest(
      baseUri: Uri,
      sessionId: DirectionalSessionId,
      requestTimeout: Duration,
  ): Either[GossipPeerClientError, PreparedRequest[
    DecodeResult[Either[String, String]],
  ]] =
    prepare(
      Interpreter
        .toRequest(TxGossipTapirEndpoints.disconnect, Some(baseUri))(
          (sessionId.value, None, None),
        ),
      requestTimeout,
    )

  def bootstrapFinalizedRequest(
      baseUri: Uri,
      session: BootstrapSessionBinding,
      chainId: ChainId,
      requestTimeout: Duration,
  ): Either[GossipPeerClientError, PreparedRequest[
    DecodeResult[Either[String, String]],
  ]] =
    prepare(
      Interpreter
        .toRequest(
          HotStuffBootstrapTapirEndpoints.finalizedSuggestion,
          Some(baseUri),
        )(
          (
            session.sessionId.value,
            chainId.value,
            None,
            None,
            None,
          ),
        ),
      requestTimeout,
    )

  def bootstrapSnapshotRequest(
      baseUri: Uri,
      session: BootstrapSessionBinding,
      chainId: ChainId,
      body: String,
      requestTimeout: Duration,
  ): Either[GossipPeerClientError, PreparedRequest[
    DecodeResult[Either[String, String]],
  ]] =
    prepare(
      Interpreter
        .toRequest(HotStuffBootstrapTapirEndpoints.snapshotFetch, Some(baseUri))(
          (
            session.sessionId.value,
            chainId.value,
            None,
            None,
            None,
            body,
          ),
        ),
      requestTimeout,
    )

  def bootstrapReplayRequest(
      baseUri: Uri,
      session: BootstrapSessionBinding,
      chainId: ChainId,
      body: String,
      requestTimeout: Duration,
  ): Either[GossipPeerClientError, PreparedRequest[
    DecodeResult[Either[String, String]],
  ]] =
    prepare(
      Interpreter
        .toRequest(HotStuffBootstrapTapirEndpoints.replay, Some(baseUri))(
          (
            session.sessionId.value,
            chainId.value,
            None,
            None,
            None,
            body,
          ),
        ),
      requestTimeout,
    )

  def bootstrapBackfillRequest(
      baseUri: Uri,
      session: BootstrapSessionBinding,
      chainId: ChainId,
      body: String,
      requestTimeout: Duration,
  ): Either[GossipPeerClientError, PreparedRequest[
    DecodeResult[Either[String, String]],
  ]] =
    prepare(
      Interpreter
        .toRequest(HotStuffBootstrapTapirEndpoints.backfill, Some(baseUri))(
          (
            session.sessionId.value,
            chainId.value,
            None,
            None,
            None,
            body,
          ),
        ),
      requestTimeout,
    )

  def baseUri(javaUri: java.net.URI): Uri =
    Uri(javaUri)

  def withTransportAuth[T](
      prepared: PreparedRequest[T],
      transportAuth: StaticPeerTransportAuth,
      authenticatedPeer: PeerIdentity,
  ): Either[GossipPeerClientError, PreparedRequest[T]] =
    GossipTransportAuth
      .issueTransportProof(
        transportAuth = transportAuth,
        authenticatedPeer = authenticatedPeer,
        httpMethod = prepared.method,
        requestPath = prepared.path,
        requestBodyBytes = prepared.bodyBytes,
      )
      .leftMap(error =>
        GossipPeerClientError.TransportFailure(
          reason = "transportAuthUnavailable",
          detail = Some(error),
        ),
      )
      .map: proof =>
        prepared.copy(
          request = prepared.request.withHeaders(
            prepared.request.headers ++ List(
              Header(
                GossipTransportAuth.AuthenticatedPeerHeaderName,
                authenticatedPeer.value,
              ),
              Header(GossipTransportAuth.TransportProofHeaderName, proof),
            ),
          ),
        )

  def withBootstrapAuth[T](
      prepared: PreparedRequest[T],
      transportAuth: StaticPeerTransportAuth,
      session: BootstrapSessionBinding,
  ): Either[GossipPeerClientError, PreparedRequest[T]] =
    for
      transportPrepared <- withTransportAuth(
        prepared,
        transportAuth,
        session.authenticatedPeer,
      )
      capability <- GossipTransportAuth
        .issueBootstrapCapability(
          transportAuth = transportAuth,
          authenticatedPeer = session.authenticatedPeer,
          targetPeer = session.peer,
          sessionId = session.sessionId,
          httpMethod = transportPrepared.method,
          requestPath = transportPrepared.path,
          requestBodyBytes = transportPrepared.bodyBytes,
        )
        .leftMap(error =>
          GossipPeerClientError.TransportFailure(
            reason = "bootstrapCapabilityUnavailable",
            detail = Some(error),
          ),
        )
    yield transportPrepared.copy(
      request = transportPrepared.request.withHeaders(
        transportPrepared.request.headers ++ List(
          Header(GossipTransportAuth.BootstrapCapabilityHeaderName, capability),
        ),
      ),
    )

  def sendStringEndpoint[F[_]: Async](
      backend: Backend[F],
      request: Request[DecodeResult[Either[String, String]]],
  ): F[Either[GossipPeerClientError, Either[CanonicalRejection, String]]] =
    sendDecodeResult(backend, request, classifyEventFailures = false).map:
      case Left(error) =>
        error.asLeft[Either[CanonicalRejection, String]]
      case Right(decoded) =>
        decoded.value match
          case Left(rawRejection) =>
            HotStuffBootstrapArmeriaAdapter
              .decodeRejection(rawRejection)
              .leftMap: error =>
                if isHttpFailureStatus(decoded.statusCode) then
                  GossipPeerClientError.HttpStatusFailure(
                    statusCode = decoded.statusCode,
                    reason = "peerHttpFailure",
                    detail = Some(error),
                  )
                else
                  GossipPeerClientError.ResponseDecodeFailure(
                    reason = "invalidPeerRejection",
                    detail = Some(error),
                  )
              .map(_.asLeft[String])
          case Right(rawResponse) =>
            rawResponse.asRight[CanonicalRejection].asRight[GossipPeerClientError]

  def sendEventEndpoint[F[_]: Async, A: org.sigilaris.core.codec.byte.ByteDecoder](
      backend: Backend[F],
      request: Request[DecodeResult[Either[Unit, Array[Byte]]]],
  ): F[Either[GossipPeerClientError, Vector[EventEnvelopeWire[A]]]] =
    sendDecodeResult(backend, request, classifyEventFailures = true).map:
      case Left(error) =>
        error.asLeft[Vector[EventEnvelopeWire[A]]]
      case Right(decoded) =>
        decoded.value match
          case Left(_) =>
            GossipPeerClientError.HttpStatusFailure(
              statusCode = decoded.statusCode,
              reason = "eventEndpointReturnedError",
              detail = None,
            ).asLeft[Vector[EventEnvelopeWire[A]]]
          case Right(body) =>
            BinaryEventStreamCodec
              .decode[A](body)
              .leftMap(error =>
                GossipPeerClientError.ResponseDecodeFailure(
                  reason = "invalidEventStream",
                  detail = Some(error),
                ),
              )

  private def sendDecodeResult[F[_]: Async, E, O](
      backend: Backend[F],
      request: Request[DecodeResult[Either[E, O]]],
      classifyEventFailures: Boolean,
  ): F[Either[GossipPeerClientError, DecodedResponse[E, O]]] =
    request
      .send(backend)
      .attempt
      .map:
        case Left(error) =>
          GossipPeerClientError.TransportFailure(
            reason = "requestFailed",
            detail = Option(error.getMessage),
          ).asLeft[DecodedResponse[E, O]]
        case Right(response) if classifyEventFailures && !response.isSuccess =>
          GossipPeerClientError.HttpStatusFailure(
            statusCode = response.code.code,
            reason = "eventHttpFailure",
            detail = eventHttpFailureDetail(response.body),
          ).asLeft[DecodedResponse[E, O]]
        case Right(response) =>
          response.body match
            case DecodeResult.Value(value) =>
              DecodedResponse(response.code.code, value)
                .asRight[GossipPeerClientError]
            case failure =>
              GossipPeerClientError.ResponseDecodeFailure(
                reason = "responseDecodeFailed",
                detail = Some(decodeFailureDetail(failure)),
              ).asLeft[DecodedResponse[E, O]]

  private def prepare[T](
      request: Request[T],
      requestTimeout: Duration,
  ): Either[GossipPeerClientError, PreparedRequest[T]] =
    bodyBytes(request.body).map: bytes =>
      val timedRequest =
        request.withOptions(
          request.options.copy(readTimeout = requestTimeout.toScala),
        )
      PreparedRequest(
        request = timedRequest,
        method = request.method.method,
        path = signedPath(request.uri),
        bodyBytes = bytes,
      )

  private def signedPath(
      uri: Uri,
  ): String =
    // Server-side auth rebuilds paths from Tapir captures. Current captures are
    // URL-safe identifiers; update both sides if future captures allow reserved
    // characters where encoded and decoded path forms can differ.
    val path  = uri.pathToString
    val query = uri.queryToString
    if query.isEmpty then path else path + "?" + query

  private def decodeFailureDetail(
      failure: DecodeResult[?],
  ): String =
    failure match
      case DecodeResult.Mismatch(expected, actual) =>
        "mismatch expected=" + expected + " actual=" + actual
      case DecodeResult.Error(original, error) =>
        "error original=" + original + " message=" + error.getMessage
      case DecodeResult.InvalidValue(errors) =>
        "invalidValue count=" + errors.size.toString
      case DecodeResult.Multiple(values) =>
        "multiple count=" + values.size.toString
      case _ =>
        "unknown decode failure"

  private def eventHttpFailureDetail(
      body: DecodeResult[?],
  ): Option[String] =
    body match
      case DecodeResult.Value(_) =>
        None
      case failure =>
        Some(decodeFailureDetail(failure))

  private def isHttpFailureStatus(
      statusCode: Int,
  ): Boolean =
    statusCode < 200 || statusCode >= 300

  private def bodyBytes(
      body: BasicBody,
  ): Either[GossipPeerClientError, Array[Byte]] =
    body match
      case NoBody =>
        Array.emptyByteArray.asRight[GossipPeerClientError]
      case StringBody(value, encoding, _) =>
        Try(Charset.forName(encoding)).toEither
          .leftMap(error =>
            GossipPeerClientError.TransportFailure(
              reason = "unsupportedRequestBodyEncoding",
              detail = Some(error.getMessage),
            ),
          )
          .map(value.getBytes)
      case ByteArrayBody(value, _) =>
        value.asRight[GossipPeerClientError]
      case ByteBufferBody(value, _) =>
        val duplicate = value.asReadOnlyBuffer()
        val bytes     = Array.ofDim[Byte](duplicate.remaining())
        duplicate.get(bytes)
        bytes.asRight[GossipPeerClientError]
      case InputStreamBody(_, _) =>
        GossipPeerClientError.TransportFailure(
          reason = "unsupportedStreamingRequestBody",
          detail = Some("InputStreamBody cannot be signed and sent safely"),
        ).asLeft[Array[Byte]]
      case FileBody(value, _) =>
        Try(Files.readAllBytes(value.toPath)).toEither.leftMap(error =>
          GossipPeerClientError.TransportFailure(
            reason = "unsupportedRequestBody",
            detail = Some(error.getMessage),
          ),
        )
      case _ =>
        GossipPeerClientError.TransportFailure(
          reason = "unsupportedRequestBody",
          detail = Some(body.show),
        ).asLeft[Array[Byte]]
