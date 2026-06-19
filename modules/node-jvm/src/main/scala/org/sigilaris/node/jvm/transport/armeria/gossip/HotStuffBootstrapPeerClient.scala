package org.sigilaris.node.jvm.transport.armeria.gossip

import java.net.URI
import java.time.Duration

import cats.effect.{Async, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import sttp.client4.Backend
import sttp.client4.armeria.cats.ArmeriaCatsBackend

import org.sigilaris.core.datatype.UInt256
import org.sigilaris.node.gossip.*
import org.sigilaris.node.jvm.runtime.block.{BlockHeight, BlockId, StateRoot}
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.given

/** Endpoint-derived Armeria client for HotStuff bootstrap peer services. */
final class HotStuffBootstrapPeerClient[F[_]: Async] private (
    peerBaseUris: Map[PeerIdentity, URI],
    transportAuth: StaticPeerTransportAuth,
    backend: Backend[F],
    requestTimeout: Duration,
    requestPermit: Resource[F, Unit],
):
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def services(
      proposalCatchUpReadiness: Option[ProposalCatchUpReadiness[F]] = None,
  ): HotStuffBootstrapTransportServices[F] =
    HotStuffBootstrapTransportServices(
      finalizedAnchorSuggestions = new FinalizedAnchorSuggestionService[F]:
        override def bestFinalized(
            session: BootstrapSessionBinding,
            chainId: ChainId,
        ): F[Either[CanonicalRejection, Option[FinalizedAnchorSuggestion]]] =
          execute(session)(baseUri =>
            GossipTapirClientCore.bootstrapFinalizedRequest(
              baseUri,
              session,
              chainId,
              requestTimeout,
            ),
          ).flatMap:
            case Left(rejection) =>
              rejection.asLeft[Option[FinalizedAnchorSuggestion]].pure[F]
            case Right(raw) =>
              decode[FinalizedSuggestionResponseWire](raw)
                .leftMap(error =>
                  bootstrapRejected(
                    "invalidBootstrapResponse",
                    error.getMessage,
                  ),
                )
                .flatMap(response =>
                  response.suggestionBase64Url.traverse(
                    HotStuffBootstrapArmeriaAdapter.decodeValue[
                      FinalizedAnchorSuggestion,
                    ](_, "invalidFinalizedSuggestionPayload"),
                  ),
                )
                .pure[F]
      ,
      snapshotNodeFetch = new SnapshotNodeFetchService[F]:
        override def fetchNodes(
            session: BootstrapSessionBinding,
            chainId: ChainId,
            stateRoot: StateRoot,
            hashes: Vector[org.sigilaris.core.merkle.MerkleTrieNode.MerkleHash],
        ): F[Either[CanonicalRejection, Vector[SnapshotTrieNode]]] =
          val body =
            SnapshotNodeFetchRequestWire(
              stateRoot = stateRoot.toHexLower,
              hashes = hashes.map(_.toUInt256.toHexLower),
            ).asJson.noSpaces
          execute(session)(baseUri =>
            GossipTapirClientCore.bootstrapSnapshotRequest(
              baseUri,
              session,
              chainId,
              body,
              requestTimeout,
            ),
          ).flatMap:
            case Left(rejection) =>
              rejection.asLeft[Vector[SnapshotTrieNode]].pure[F]
            case Right(raw) =>
              decode[SnapshotNodeFetchResponseWire](raw)
                .leftMap(error =>
                  bootstrapRejected(
                    "invalidBootstrapResponse",
                    error.getMessage,
                  ),
                )
                .flatMap: response =>
                  response.nodes.traverse: wire =>
                    for
                      hash <- UInt256
                        .fromHex(wire.hash)
                        .leftMap(error =>
                          bootstrapRejected(
                            "invalidSnapshotNodeHash",
                            error.msg,
                          ),
                        )
                        .map(
                          org.sigilaris.core.crypto.Hash
                            .Value[org.sigilaris.core.merkle.MerkleTrieNode](_),
                        )
                      node <- HotStuffBootstrapArmeriaAdapter
                        .decodeValue[org.sigilaris.core.merkle.MerkleTrieNode](
                          wire.nodeBase64Url,
                          "invalidSnapshotNodePayload",
                        )
                    yield SnapshotTrieNode(hash = hash, node = node)
                .pure[F]
      ,
      proposalReplay = new ProposalReplayService[F]:
        override def readNext(
            session: BootstrapSessionBinding,
            chainId: ChainId,
            anchorBlockId: BlockId,
            nextHeight: BlockHeight,
            limit: Int,
        ): F[Either[CanonicalRejection, Vector[Proposal]]] =
          proposalPage(
            session = session,
            chainId = chainId,
            pageRequest = ProposalPageRequestWire(
              blockId = anchorBlockId.toHexLower,
              height = nextHeight.render,
              limit = limit,
            ),
            prepare = GossipTapirClientCore.bootstrapReplayRequest,
          )
      ,
      historicalBackfill = new HistoricalBackfillService[F]:
        override def readPrevious(
            session: BootstrapSessionBinding,
            chainId: ChainId,
            beforeBlockId: BlockId,
            beforeHeight: BlockHeight,
            limit: Int,
        ): F[Either[CanonicalRejection, Vector[Proposal]]] =
          proposalPage(
            session = session,
            chainId = chainId,
            pageRequest = ProposalPageRequestWire(
              blockId = beforeBlockId.toHexLower,
              height = beforeHeight.render,
              limit = limit,
            ),
            prepare = GossipTapirClientCore.bootstrapBackfillRequest,
          )
      ,
      proposalCatchUpReadiness = proposalCatchUpReadiness,
    )

  private def proposalPage(
      session: BootstrapSessionBinding,
      chainId: ChainId,
      pageRequest: ProposalPageRequestWire,
      prepare: (
          sttp.model.Uri,
          BootstrapSessionBinding,
          ChainId,
          String,
          Duration,
      ) => Either[
        GossipPeerClientError,
        GossipTapirClientCore.PreparedRequest[
          sttp.tapir.DecodeResult[Either[String, String]],
        ],
      ],
  ): F[Either[CanonicalRejection, Vector[Proposal]]] =
    execute(session)(baseUri =>
      prepare(
        baseUri,
        session,
        chainId,
        pageRequest.asJson.noSpaces,
        requestTimeout,
      ),
    ).flatMap:
      case Left(rejection) =>
        rejection.asLeft[Vector[Proposal]].pure[F]
      case Right(raw) =>
        decode[ProposalBatchResponseWire](raw)
          .leftMap(error =>
            bootstrapRejected("invalidBootstrapResponse", error.getMessage),
          )
          .flatMap(response =>
            response.proposalsBase64Url.traverse(
              HotStuffBootstrapArmeriaAdapter.decodeValue[Proposal](
                _,
                "invalidProposalPayload",
              ),
            ),
          )
          .pure[F]

  private def execute(
      session: BootstrapSessionBinding,
  )(
      prepare: sttp.model.Uri => Either[
        GossipPeerClientError,
        GossipTapirClientCore.PreparedRequest[
          sttp.tapir.DecodeResult[Either[String, String]],
        ],
      ],
  ): F[Either[CanonicalRejection, String]] =
    peerBaseUris.get(session.peer) match
      case None =>
        bootstrapRejected(
          "bootstrapPeerEndpointUnavailable",
          session.peer.value,
        ).asLeft[String].pure[F]
      case Some(baseUri) =>
        val preparedEither =
          for
            prepared <- prepare(GossipTapirClientCore.baseUri(baseUri))
            signed <- GossipTapirClientCore.withBootstrapAuth(
              prepared,
              transportAuth,
              session,
            )
          yield signed

        preparedEither.fold(
          error =>
            clientErrorToBootstrapRejection(error).asLeft[String].pure[F],
          signed =>
            requestPermit.use: _ =>
              GossipTapirClientCore
                .sendStringEndpoint[F](backend, signed.request)
                .map:
                  case Left(error) =>
                    clientErrorToBootstrapRejection(error).asLeft[String]
                  case Right(Left(rejection)) =>
                    rejection.asLeft[String]
                  case Right(Right(raw)) =>
                    raw.asRight[CanonicalRejection],
        )

  private def clientErrorToBootstrapRejection(
      error: GossipPeerClientError,
  ): CanonicalRejection.BackfillUnavailable =
    val reason =
      error match
        case _: GossipPeerClientError.TransportFailure =>
          error.reason match
            case "transportAuthUnavailable" =>
              "bootstrapTransportAuthUnavailable"
            case "bootstrapCapabilityUnavailable" =>
              "bootstrapCapabilityUnavailable"
            case "requestFailed" =>
              "bootstrapTransportFailed"
            case other =>
              other
        case _: GossipPeerClientError.ResponseDecodeFailure =>
          error.reason
        case _: GossipPeerClientError.HttpStatusFailure =>
          error.reason
    bootstrapRejected(reason, error.detail.getOrElse(""))

  private def bootstrapRejected(
      reason: String,
      detail: String,
  ): CanonicalRejection.BackfillUnavailable =
    CanonicalRejection.BackfillUnavailable(
      reason = reason,
      detail = Some(detail),
    )

object HotStuffBootstrapPeerClient:
  val DefaultRequestTimeout: Duration   = Duration.ofSeconds(10L)
  val DefaultMaxConcurrentRequests: Int = 16

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def resource[F[_]: Async](
      peerBaseUris: Map[PeerIdentity, URI],
      transportAuth: StaticPeerTransportAuth,
      requestTimeout: Duration = DefaultRequestTimeout,
      maxConcurrentRequests: Int = DefaultMaxConcurrentRequests,
  ): Resource[F, HotStuffBootstrapPeerClient[F]] =
    Resource
      .eval(validateConfig[F](requestTimeout, maxConcurrentRequests))
      .productR(
        ArmeriaCatsBackend
          .resource[F]()
          .evalMap(backend =>
            Semaphore[F](maxConcurrentRequests.toLong).map(semaphore =>
              new HotStuffBootstrapPeerClient[F](
                peerBaseUris = peerBaseUris,
                transportAuth = transportAuth,
                backend = backend,
                requestTimeout = requestTimeout,
                requestPermit = semaphore.permit,
              ),
            ),
          ),
      )

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def servicesWithBackend[F[_]: Async](
      peerBaseUris: Map[PeerIdentity, URI],
      transportAuth: StaticPeerTransportAuth,
      backend: Backend[F],
      requestTimeout: Duration = DefaultRequestTimeout,
      maxConcurrentRequests: Int = DefaultMaxConcurrentRequests,
      proposalCatchUpReadiness: Option[ProposalCatchUpReadiness[F]] = None,
  ): HotStuffBootstrapTransportServices[F] =
    validateConfigSync(requestTimeout, maxConcurrentRequests)
    val requestGate =
      new java.util.concurrent.Semaphore(maxConcurrentRequests, true)
    new HotStuffBootstrapPeerClient[F](
      peerBaseUris = peerBaseUris,
      transportAuth = transportAuth,
      backend = backend,
      requestTimeout = requestTimeout,
      requestPermit = Resource.make(
        Async[F].blocking(requestGate.acquire()),
      )(_ => Async[F].delay(requestGate.release()).void),
    ).services(proposalCatchUpReadiness)

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def servicesResource[F[_]: Async](
      peerBaseUris: Map[PeerIdentity, URI],
      transportAuth: StaticPeerTransportAuth,
      requestTimeout: Duration = DefaultRequestTimeout,
      maxConcurrentRequests: Int = DefaultMaxConcurrentRequests,
      proposalCatchUpReadiness: Option[ProposalCatchUpReadiness[F]] = None,
  ): Resource[F, HotStuffBootstrapTransportServices[F]] =
    resource(
      peerBaseUris = peerBaseUris,
      transportAuth = transportAuth,
      requestTimeout = requestTimeout,
      maxConcurrentRequests = maxConcurrentRequests,
    ).map(_.services(proposalCatchUpReadiness))

  private def validateConfig[F[_]: Async](
      requestTimeout: Duration,
      maxConcurrentRequests: Int,
  ): F[Unit] =
    Async[F].delay(validateConfigSync(requestTimeout, maxConcurrentRequests))

  private def validateConfigSync(
      requestTimeout: Duration,
      maxConcurrentRequests: Int,
  ): Unit =
    require(
      requestTimeout.compareTo(Duration.ZERO) > 0,
      "requestTimeout must be positive",
    )
    require(
      maxConcurrentRequests > 0,
      "maxConcurrentRequests must be positive",
    )
