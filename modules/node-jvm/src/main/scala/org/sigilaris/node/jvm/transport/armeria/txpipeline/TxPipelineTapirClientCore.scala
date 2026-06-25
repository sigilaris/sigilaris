package org.sigilaris.node.jvm.transport.armeria.txpipeline

import java.net.URI
import java.nio.charset.Charset
import java.nio.file.Files
import java.time.Duration

import scala.jdk.DurationConverters.*
import scala.util.Try

import cats.syntax.all.*
import sttp.client4.*
import sttp.model.{StatusCode, Uri}
import sttp.tapir.DecodeResult
import sttp.tapir.client.sttp4.SttpClientInterpreter

import org.sigilaris.node.txpipeline.{TxPipelineId, TxPipelineIdempotencyKey}

final case class TxPipelineClientPreparationFailure(
    reason: String,
    detail: Option[String],
)

private[txpipeline] object TxPipelineTapirClientCore:
  private val Interpreter = SttpClientInterpreter()

  final case class PreparedRequest[T](
      request: Request[T],
      method: String,
      path: String,
      bodyBytes: Array[Byte],
  )

  type DecodedStringResponse =
    DecodeResult[Either[(StatusCode, String), (StatusCode, String)]]

  def baseUri(uri: URI): Uri =
    Uri.unsafeParse(uri.toString)

  def submitRequest(
      baseUri: Uri,
      body: String,
      idempotencyKey: Option[TxPipelineIdempotencyKey],
      requestTimeout: Duration,
  ): Either[TxPipelineClientPreparationFailure, PreparedRequest[
    DecodedStringResponse,
  ]] =
    prepare(
      Interpreter
        .toRequest(TxPipelineTapirEndpoints.submit, Some(baseUri))(
          (idempotencyKey.map(_.value), body),
        ),
      requestTimeout,
    )

  def queryRequest(
      baseUri: Uri,
      pipelineId: TxPipelineId,
      requestTimeout: Duration,
  ): Either[TxPipelineClientPreparationFailure, PreparedRequest[
    DecodedStringResponse,
  ]] =
    prepare(
      Interpreter
        .toRequest(TxPipelineTapirEndpoints.query, Some(baseUri))(
          pipelineId.value,
        ),
      requestTimeout,
    )

  private def prepare[T](
      request: Request[T],
      requestTimeout: Duration,
  ): Either[TxPipelineClientPreparationFailure, PreparedRequest[T]] =
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
    val path  = uri.pathToString
    val query = uri.queryToString
    if query.isEmpty then path else path + "?" + query

  private[txpipeline] def bodyBytes(
      body: BasicBody,
  ): Either[TxPipelineClientPreparationFailure, Array[Byte]] =
    body match
      case NoBody =>
        Array.emptyByteArray.asRight[TxPipelineClientPreparationFailure]
      case StringBody(value, encoding, _) =>
        Try(Charset.forName(encoding)).toEither
          .leftMap(error =>
            TxPipelineClientPreparationFailure(
              reason = "unsupportedRequestBodyEncoding",
              detail = Some(error.getMessage),
            ),
          )
          .map(charset => value.getBytes(charset))
      case ByteArrayBody(value, _) =>
        value.asRight[TxPipelineClientPreparationFailure]
      case ByteBufferBody(value, _) =>
        val duplicate = value.asReadOnlyBuffer()
        val bytes     = Array.ofDim[Byte](duplicate.remaining())
        duplicate.get(bytes)
        bytes.asRight[TxPipelineClientPreparationFailure]
      case InputStreamBody(_, _) =>
        TxPipelineClientPreparationFailure(
          reason = "unsupportedStreamingRequestBody",
          detail = Some("InputStreamBody cannot be prepared safely"),
        ).asLeft[Array[Byte]]
      case FileBody(value, _) =>
        Try(Files.readAllBytes(value.toPath)).toEither.leftMap(error =>
          TxPipelineClientPreparationFailure(
            reason = "unsupportedRequestBody",
            detail = Some(error.getMessage),
          ),
        )
      case _ =>
        TxPipelineClientPreparationFailure(
          reason = "unsupportedRequestBody",
          detail = Some(body.show),
        ).asLeft[Array[Byte]]
