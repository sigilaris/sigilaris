package org.sigilaris.node.jvm.transport.armeria.gossip

import java.net.URI
import java.time.Duration

import cats.effect.{Async, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import fs2.Stream
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.Backend
import sttp.client4.StreamBackend
import sttp.client4.armeria.cats.ArmeriaCatsBackend
import sttp.client4.armeria.fs2.ArmeriaFs2Backend

import org.sigilaris.core.codec.byte.ByteDecoder
import org.sigilaris.node.gossip.*

/** Endpoint-derived HTTP client for transaction gossip peer endpoints. */
trait TxGossipPeerClient[F[_], A]:
  def openSession(
      rawProposal: String,
  ): F[Either[GossipPeerClientError, Either[CanonicalRejection, String]]]

  def streamEvents(
      sessionId: DirectionalSessionId,
      rawRequest: String,
  ): Stream[F, Either[GossipPeerClientError, EventEnvelopeWire[A]]]

  def sendControl(
      sessionId: DirectionalSessionId,
      rawRequest: String,
  ): F[Either[GossipPeerClientError, Either[CanonicalRejection, String]]]

  def disconnect(
      sessionId: DirectionalSessionId,
  ): F[Either[GossipPeerClientError, Either[CanonicalRejection, String]]]

object TxGossipPeerClient:
  val DefaultRequestTimeout: Duration =
    HotStuffBootstrapPeerClient.DefaultRequestTimeout
  val DefaultStreamRequestTimeout: Duration =
    Duration.ZERO
  val DefaultStreamIdleTimeout: Duration =
    Duration.ofSeconds(90)
  val DefaultMaxConcurrentRequests: Int =
    HotStuffBootstrapPeerClient.DefaultMaxConcurrentRequests
  private val DisconnectAck: String = "ok"

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def resource[F[_]: Async, A: ByteDecoder](
      baseUri: URI,
      transportAuth: StaticPeerTransportAuth,
      authenticatedPeer: PeerIdentity,
      requestTimeout: Duration = DefaultRequestTimeout,
      streamRequestTimeout: Duration = DefaultStreamRequestTimeout,
      streamIdleTimeout: Duration = DefaultStreamIdleTimeout,
      maxConcurrentRequests: Int = DefaultMaxConcurrentRequests,
  ): Resource[F, TxGossipPeerClient[F, A]] =
    Resource
      .eval(
        validateConfig[F](
          requestTimeout = requestTimeout,
          streamRequestTimeout = streamRequestTimeout,
          streamIdleTimeout = streamIdleTimeout,
          maxConcurrentRequests = maxConcurrentRequests,
        ),
      )
      .productR(
        ArmeriaCatsBackend
          .resource[F]()
          .flatMap: backend =>
            ArmeriaFs2Backend
              .resource[F]()
              .evalMap: streamBackend =>
                (
                  Semaphore[F](maxConcurrentRequests.toLong),
                  Semaphore[F](maxConcurrentRequests.toLong),
                ).mapN: (requestGate, streamGate) =>
                  apply(
                    baseUri = baseUri,
                    transportAuth = transportAuth,
                    authenticatedPeer = authenticatedPeer,
                    backend = backend,
                    streamBackend = streamBackend,
                    requestTimeout = requestTimeout,
                    streamRequestTimeout = streamRequestTimeout,
                    streamIdleTimeout = streamIdleTimeout,
                    requestGate = requestGate,
                    streamGate = streamGate,
                  ),
      )

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def apply[F[_]: Async, A: ByteDecoder](
      baseUri: URI,
      transportAuth: StaticPeerTransportAuth,
      authenticatedPeer: PeerIdentity,
      backend: Backend[F],
      streamBackend: StreamBackend[F, Fs2Streams[F]],
      requestGate: Semaphore[F],
      streamGate: Semaphore[F],
      requestTimeout: Duration = DefaultRequestTimeout,
      streamRequestTimeout: Duration = DefaultStreamRequestTimeout,
      streamIdleTimeout: Duration = DefaultStreamIdleTimeout,
  ): TxGossipPeerClient[F, A] =
    val sttpBaseUri = GossipTapirClientCore.baseUri(baseUri)
    new TxGossipPeerClient[F, A]:
      override def openSession(
          rawProposal: String,
      ): F[Either[GossipPeerClientError, Either[CanonicalRejection, String]]] =
        prepareAndSendString(
          GossipTapirClientCore.txSessionOpenRequest(
            sttpBaseUri,
            rawProposal,
            requestTimeout,
          ),
        )

      override def streamEvents(
          sessionId: DirectionalSessionId,
          rawRequest: String,
      ): Stream[F, Either[GossipPeerClientError, EventEnvelopeWire[A]]] =
        (
          for
            prepared <- GossipTapirClientCore.txEventStreamOpenRequest[F](
              sttpBaseUri,
              sessionId,
              rawRequest,
              streamRequestTimeout,
            )
            signed <- GossipTapirClientCore.withStreamTransportAuth(
              prepared,
              transportAuth,
              authenticatedPeer,
            )
          yield signed
        ).fold(
          error => Stream.emit(error.asLeft[EventEnvelopeWire[A]]),
          signed =>
            Stream
              .resource(streamGate.permit)
              .flatMap(_ =>
                GossipTapirClientCore
                  .sendEventStreamEndpoint[F, A](
                    streamBackend,
                    signed.request,
                    streamIdleTimeout,
                  ),
              ),
        )

      override def sendControl(
          sessionId: DirectionalSessionId,
          rawRequest: String,
      ): F[Either[GossipPeerClientError, Either[CanonicalRejection, String]]] =
        prepareAndSendString(
          GossipTapirClientCore.txControlRequest(
            sttpBaseUri,
            sessionId,
            rawRequest,
            requestTimeout,
          ),
        )

      override def disconnect(
          sessionId: DirectionalSessionId,
      ): F[Either[GossipPeerClientError, Either[CanonicalRejection, String]]] =
        prepareAndSendString(
          GossipTapirClientCore.txDisconnectRequest(
            sttpBaseUri,
            sessionId,
            requestTimeout,
          ),
        ).map(validateDisconnectAck)

      private def prepareAndSendString(
          preparedEither: Either[
            GossipPeerClientError,
            GossipTapirClientCore.PreparedRequest[
              sttp.tapir.DecodeResult[Either[String, String]],
            ],
          ],
      ): F[Either[GossipPeerClientError, Either[CanonicalRejection, String]]] =
        (
          for
            prepared <- preparedEither
            signed <- GossipTapirClientCore.withTransportAuth(
              prepared,
              transportAuth,
              authenticatedPeer,
            )
          yield signed
        ).fold(
          error => error.asLeft[Either[CanonicalRejection, String]].pure[F],
          signed =>
            requestGate.permit.use: _ =>
              GossipTapirClientCore.sendStringEndpoint[F](
                backend,
                signed.request,
              ),
        )

      private def validateDisconnectAck(
          result: Either[
            GossipPeerClientError,
            Either[CanonicalRejection, String],
          ],
      ): Either[GossipPeerClientError, Either[CanonicalRejection, String]] =
        result.flatMap:
          case Left(rejection) =>
            rejection.asLeft[String].asRight[GossipPeerClientError]
          case Right(DisconnectAck) =>
            DisconnectAck
              .asRight[CanonicalRejection]
              .asRight[GossipPeerClientError]
          case Right(raw) =>
            GossipPeerClientError
              .ResponseDecodeFailure(
                reason = "invalidDisconnectResponse",
                detail = Some(raw),
              )
              .asLeft[Either[CanonicalRejection, String]]

  private def validateConfig[F[_]: Async](
      requestTimeout: Duration,
      streamRequestTimeout: Duration,
      streamIdleTimeout: Duration,
      maxConcurrentRequests: Int,
  ): F[Unit] =
    Async[F].delay:
      require(
        requestTimeout.compareTo(Duration.ZERO) > 0,
        "requestTimeout must be positive",
      )
      require(
        !streamRequestTimeout.isNegative,
        "streamRequestTimeout must be zero or positive",
      )
      require(
        !streamIdleTimeout.isNegative,
        "streamIdleTimeout must be zero or positive",
      )
      require(
        maxConcurrentRequests > 0,
        "maxConcurrentRequests must be positive",
      )
