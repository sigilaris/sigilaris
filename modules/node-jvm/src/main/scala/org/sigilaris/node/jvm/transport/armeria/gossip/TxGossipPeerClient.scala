package org.sigilaris.node.jvm.transport.armeria.gossip

import java.net.URI
import java.time.Duration

import cats.effect.{Async, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import sttp.client4.Backend
import sttp.client4.armeria.cats.ArmeriaCatsBackend

import org.sigilaris.core.codec.byte.ByteDecoder
import org.sigilaris.node.gossip.*

/** Endpoint-derived HTTP client for transaction gossip peer endpoints. */
trait TxGossipPeerClient[F[_], A]:
  def openSession(
      rawProposal: String,
  ): F[Either[GossipPeerClientError, Either[CanonicalRejection, String]]]

  def pollEvents(
      sessionId: DirectionalSessionId,
      rawRequest: String,
  ): F[Either[GossipPeerClientError, Vector[EventEnvelopeWire[A]]]]

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
  val DefaultMaxConcurrentRequests: Int =
    HotStuffBootstrapPeerClient.DefaultMaxConcurrentRequests
  private val DisconnectAck: String = "ok"

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def resource[F[_]: Async, A: ByteDecoder](
      baseUri: URI,
      transportAuth: StaticPeerTransportAuth,
      authenticatedPeer: PeerIdentity,
      requestTimeout: Duration = DefaultRequestTimeout,
      maxConcurrentRequests: Int = DefaultMaxConcurrentRequests,
  ): Resource[F, TxGossipPeerClient[F, A]] =
    Resource
      .eval(validateConfig[F](requestTimeout, maxConcurrentRequests))
      .productR(
        ArmeriaCatsBackend
          .resource[F]()
          .evalMap(backend =>
            Semaphore[F](maxConcurrentRequests.toLong).map(requestGate =>
              apply(
                baseUri = baseUri,
                transportAuth = transportAuth,
                authenticatedPeer = authenticatedPeer,
                backend = backend,
                requestTimeout = requestTimeout,
                requestGate = requestGate,
              ),
            ),
          ),
      )

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def apply[F[_]: Async, A: ByteDecoder](
      baseUri: URI,
      transportAuth: StaticPeerTransportAuth,
      authenticatedPeer: PeerIdentity,
      backend: Backend[F],
      requestGate: Semaphore[F],
      requestTimeout: Duration = DefaultRequestTimeout,
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

      override def pollEvents(
          sessionId: DirectionalSessionId,
          rawRequest: String,
      ): F[Either[GossipPeerClientError, Vector[EventEnvelopeWire[A]]]] =
        (
          for
            prepared <- GossipTapirClientCore.txEventStreamRequest(
              sttpBaseUri,
              sessionId,
              rawRequest,
              requestTimeout,
            )
            signed <- GossipTapirClientCore.withTransportAuth(
              prepared,
              transportAuth,
              authenticatedPeer,
            )
          yield signed
        ).fold(
          error => error.asLeft[Vector[EventEnvelopeWire[A]]].pure[F],
          signed =>
            requestGate.permit.use: _ =>
              GossipTapirClientCore
                .sendEventEndpoint[F, A](backend, signed.request),
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
      maxConcurrentRequests: Int,
  ): F[Unit] =
    Async[F].delay:
      require(
        requestTimeout.compareTo(Duration.ZERO) > 0,
        "requestTimeout must be positive",
      )
      require(
        maxConcurrentRequests > 0,
        "maxConcurrentRequests must be positive",
      )
