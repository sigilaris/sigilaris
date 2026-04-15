package org.sigilaris.node.jvm.transport.armeria.gossip

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration

import cats.effect.Async
import cats.effect.syntax.all.*
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*

import org.sigilaris.core.datatype.UInt256
import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.jvm.runtime.block.{BlockHeight, BlockId, StateRoot}
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.given
import org.sigilaris.node.gossip.*
/** Client-side HTTP transport for HotStuff bootstrap protocol requests.
  *
  * Sends authenticated HTTP requests to peer nodes for finalized suggestions,
  * snapshot fetching, proposal replay, and historical backfill.
  */
object HotStuffBootstrapHttpTransport:
  /** Default timeout for individual bootstrap HTTP requests. */
  val DefaultRequestTimeout: Duration   = Duration.ofSeconds(10L)

  /** Default maximum number of concurrent outbound bootstrap requests. */
  val DefaultMaxConcurrentRequests: Int = 16

  /** Creates the full set of bootstrap transport service implementations backed by HTTP.
    *
    * @tparam F
    *   the effect type
    * @param peerBaseUris
    *   mapping from peer identity to HTTP base URI
    * @param transportAuth
    *   transport authentication for signing outbound requests
    * @param httpClient
    *   Java HTTP client to use for outbound requests
    * @param requestTimeout
    *   timeout per request
    * @param maxConcurrentRequests
    *   semaphore-based concurrency limit for outbound requests
    * @param proposalCatchUpReadiness
    *   optional readiness gate for proposal catch-up
    * @return
    *   assembled bootstrap transport services
    */
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def services[F[_]: Async](
      peerBaseUris: Map[PeerIdentity, URI],
      transportAuth: StaticPeerTransportAuth,
      httpClient: HttpClient = HttpClient
        .newBuilder()
        .connectTimeout(DefaultRequestTimeout)
        .build(),
      requestTimeout: Duration = DefaultRequestTimeout,
      maxConcurrentRequests: Int = DefaultMaxConcurrentRequests,
      proposalCatchUpReadiness: Option[ProposalCatchUpReadiness[F]] = None,
  ): HotStuffBootstrapTransportServices[F] =
    require(
      requestTimeout.compareTo(Duration.ZERO) > 0,
      "requestTimeout must be positive",
    )
    require(
      maxConcurrentRequests > 0,
      "maxConcurrentRequests must be positive",
    )
    val requestGate =
      new java.util.concurrent.Semaphore(maxConcurrentRequests, true)
    HotStuffBootstrapTransportServices(
      finalizedAnchorSuggestions = new FinalizedAnchorSuggestionService[F]:
        override def bestFinalized(
            session: BootstrapSessionBinding,
            chainId: ChainId,
        ): F[Either[CanonicalRejection, Option[FinalizedAnchorSuggestion]]] =
          executeRequest(
            session = session,
            path =
              ss"/gossip/bootstrap/finalized/${session.sessionId.value}/${chainId.value}",
            body = None,
            httpClient = httpClient,
            transportAuth = transportAuth,
            requestTimeout = requestTimeout,
            requestGate = requestGate,
            peerBaseUris = peerBaseUris,
          ).flatMap:
            case Left(rejection) =>
              rejection.asLeft[Option[FinalizedAnchorSuggestion]].pure[F]
            case Right(raw) =>
              decode[FinalizedSuggestionResponseWire](raw)
                .leftMap(error =>
                  CanonicalRejection.BackfillUnavailable(
                    reason = "invalidBootstrapResponse",
                    detail = Some(error.getMessage),
                  ),
                )
                .flatMap: response =>
                  response.suggestionBase64Url.traverse(
                    HotStuffBootstrapArmeriaAdapter.decodeValue[
                      FinalizedAnchorSuggestion,
                    ](_, "invalidFinalizedSuggestionPayload"),
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
          executeRequest(
            session = session,
            path =
              ss"/gossip/bootstrap/snapshot/${session.sessionId.value}/${chainId.value}",
            body = Some(body),
            httpClient = httpClient,
            transportAuth = transportAuth,
            requestTimeout = requestTimeout,
            requestGate = requestGate,
            peerBaseUris = peerBaseUris,
          ).flatMap:
            case Left(rejection) =>
              rejection.asLeft[Vector[SnapshotTrieNode]].pure[F]
            case Right(raw) =>
              decode[SnapshotNodeFetchResponseWire](raw)
                .leftMap(error =>
                  CanonicalRejection.BackfillUnavailable(
                    reason = "invalidBootstrapResponse",
                    detail = Some(error.getMessage),
                  ),
                )
                .flatMap: response =>
                  response.nodes.traverse: wire =>
                    for
                      hash <- UInt256
                        .fromHex(wire.hash)
                        .leftMap(error =>
                          CanonicalRejection.BackfillUnavailable(
                            reason = "invalidSnapshotNodeHash",
                            detail = Some(error.toString),
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
            path =
              ss"/gossip/bootstrap/replay/${session.sessionId.value}/${chainId.value}",
            pageRequest = ProposalPageRequestWire(
              blockId = anchorBlockId.toHexLower,
              height = nextHeight.render,
              limit = limit,
            ),
            httpClient = httpClient,
            transportAuth = transportAuth,
            requestTimeout = requestTimeout,
            requestGate = requestGate,
            peerBaseUris = peerBaseUris,
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
            path =
              ss"/gossip/bootstrap/backfill/${session.sessionId.value}/${chainId.value}",
            pageRequest = ProposalPageRequestWire(
              blockId = beforeBlockId.toHexLower,
              height = beforeHeight.render,
              limit = limit,
            ),
            httpClient = httpClient,
            transportAuth = transportAuth,
            requestTimeout = requestTimeout,
            requestGate = requestGate,
            peerBaseUris = peerBaseUris,
          )
      ,
      proposalCatchUpReadiness = proposalCatchUpReadiness,
    )

  private def proposalPage[F[_]: Async](
      session: BootstrapSessionBinding,
      path: String,
      pageRequest: ProposalPageRequestWire,
      httpClient: HttpClient,
      transportAuth: StaticPeerTransportAuth,
      requestTimeout: Duration,
      requestGate: java.util.concurrent.Semaphore,
      peerBaseUris: Map[PeerIdentity, URI],
  ): F[Either[CanonicalRejection, Vector[Proposal]]] =
    executeRequest(
      session = session,
      path = path,
      body = Some(pageRequest.asJson.noSpaces),
      httpClient = httpClient,
      transportAuth = transportAuth,
      requestTimeout = requestTimeout,
      requestGate = requestGate,
      peerBaseUris = peerBaseUris,
    ).flatMap:
      case Left(rejection) =>
        rejection.asLeft[Vector[Proposal]].pure[F]
      case Right(raw) =>
        decode[ProposalBatchResponseWire](raw)
          .leftMap(error =>
            CanonicalRejection.BackfillUnavailable(
              reason = "invalidBootstrapResponse",
              detail = Some(error.getMessage),
            ),
          )
          .flatMap: response =>
            response.proposalsBase64Url.traverse(
              HotStuffBootstrapArmeriaAdapter.decodeValue[Proposal](
                _,
                "invalidProposalPayload",
              ),
            )
          .pure[F]

  private def executeRequest[F[_]: Async](
      session: BootstrapSessionBinding,
      path: String,
      body: Option[String],
      httpClient: HttpClient,
      transportAuth: StaticPeerTransportAuth,
      requestTimeout: Duration,
      requestGate: java.util.concurrent.Semaphore,
      peerBaseUris: Map[PeerIdentity, URI],
  ): F[Either[CanonicalRejection, String]] =
    peerBaseUris.get(session.peer) match
      case None =>
        CanonicalRejection
          .BackfillUnavailable(
            reason = "bootstrapPeerEndpointUnavailable",
            detail = Some(session.peer.value),
          )
          .asLeft[String]
          .pure[F]
      case Some(baseUri) =>
        val bodyBytes =
          body.fold(Array.emptyByteArray)(_.getBytes(StandardCharsets.UTF_8))
        val proofEither =
          GossipTransportAuth.issueTransportProof(
            transportAuth = transportAuth,
            authenticatedPeer = session.authenticatedPeer,
            httpMethod = "POST",
            requestPath = path,
            requestBodyBytes = bodyBytes,
          )
        val capabilityEither =
          GossipTransportAuth.issueBootstrapCapability(
            transportAuth = transportAuth,
            authenticatedPeer = session.authenticatedPeer,
            targetPeer = session.peer,
            sessionId = session.sessionId,
            httpMethod = "POST",
            requestPath = path,
            requestBodyBytes = bodyBytes,
          )
        (proofEither, capabilityEither) match
          case (Left(error), _) =>
            CanonicalRejection
              .BackfillUnavailable(
                reason = "bootstrapTransportAuthUnavailable",
                detail = Some(error),
              )
              .asLeft[String]
              .pure[F]
          case (_, Left(error)) =>
            CanonicalRejection
              .BackfillUnavailable(
                reason = "bootstrapCapabilityUnavailable",
                detail = Some(error),
              )
              .asLeft[String]
              .pure[F]
          case (Right(transportProof), Right(bootstrapCapability)) =>
            Async[F]
              .blocking(requestGate.acquire())
              .bracket(_ =>
                Async[F]
                  .fromCompletableFuture(
                    Async[F].delay:
                      val builder =
                        HttpRequest
                          .newBuilder(resolveEndpointUri(baseUri, path))
                          .timeout(requestTimeout)
                          .header("content-type", "application/json")
                          .header(
                            GossipTransportAuth.AuthenticatedPeerHeaderName,
                            session.authenticatedPeer.value,
                          )
                          .header(
                            GossipTransportAuth.TransportProofHeaderName,
                            transportProof,
                          )
                          .header(
                            GossipTransportAuth.BootstrapCapabilityHeaderName,
                            bootstrapCapability,
                          )
                      val request =
                        body match
                          case Some(payload) =>
                            builder
                              .POST(
                                HttpRequest.BodyPublishers.ofString(payload),
                              )
                              .build()
                          case None =>
                            builder
                              .POST(HttpRequest.BodyPublishers.noBody())
                              .build()
                      httpClient.sendAsync(
                        request,
                        HttpResponse.BodyHandlers.ofString(),
                      ),
                  )
                  .attempt
                  .map:
                    case Left(error) =>
                      CanonicalRejection
                        .BackfillUnavailable(
                          reason = "bootstrapTransportFailed",
                          detail = Some(error.getMessage),
                        )
                        .asLeft[String]
                    case Right(response) if response.statusCode() == 200 =>
                      response.body().asRight[CanonicalRejection]
                    case Right(response) =>
                      HotStuffBootstrapArmeriaAdapter
                        .decodeRejection(response.body())
                        .leftMap(error =>
                          CanonicalRejection.BackfillUnavailable(
                            reason = "invalidBootstrapRejection",
                            detail = Some(error),
                          ),
                        )
                        .merge
                        .asLeft[String],
              )(_ => Async[F].delay(requestGate.release()).void)

  // Preserve any configured base-path prefix instead of letting URI.resolve
  // treat absolute request paths as origin-root replacements.
  private def resolveEndpointUri(
      baseUri: URI,
      path: String,
  ): URI =
    URI.create(baseUri.toString.stripSuffix("/") + path)
