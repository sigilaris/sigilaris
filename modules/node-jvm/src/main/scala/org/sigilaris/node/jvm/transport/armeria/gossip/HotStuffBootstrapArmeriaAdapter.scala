package org.sigilaris.node.jvm.transport.armeria.gossip

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64

import scala.util.Try

import cats.effect.Async
import cats.effect.syntax.all.*
import cats.syntax.all.*
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*
import io.circe.parser.decode
import io.circe.syntax.*
import scodec.bits.ByteVector
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.codec.byte.ByteDecoder.ops.*
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.datatype.{BigNat, UInt256}
import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.jvm.runtime.block.{BlockHeight, BlockId, StateRoot}
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.given
import org.sigilaris.node.jvm.runtime.gossip.*
import org.sigilaris.node.jvm.runtime.gossip.tx.TxGossipRuntime

final case class FinalizedSuggestionResponseWire(
    suggestionBase64Url: Option[String],
)
object FinalizedSuggestionResponseWire:
  given Decoder[FinalizedSuggestionResponseWire] = deriveDecoder
  given Encoder[FinalizedSuggestionResponseWire] = deriveEncoder

final case class SnapshotNodeFetchRequestWire(
    stateRoot: String,
    hashes: Vector[String],
)
object SnapshotNodeFetchRequestWire:
  given Decoder[SnapshotNodeFetchRequestWire] = deriveDecoder
  given Encoder[SnapshotNodeFetchRequestWire] = deriveEncoder

final case class SnapshotNodeWire(
    hash: String,
    nodeBase64Url: String,
)
object SnapshotNodeWire:
  given Decoder[SnapshotNodeWire] = deriveDecoder
  given Encoder[SnapshotNodeWire] = deriveEncoder

final case class SnapshotNodeFetchResponseWire(
    nodes: Vector[SnapshotNodeWire],
)
object SnapshotNodeFetchResponseWire:
  given Decoder[SnapshotNodeFetchResponseWire] = deriveDecoder
  given Encoder[SnapshotNodeFetchResponseWire] = deriveEncoder

final case class ProposalPageRequestWire(
    blockId: String,
    height: String,
    limit: Int,
)
object ProposalPageRequestWire:
  given Decoder[ProposalPageRequestWire] = deriveDecoder
  given Encoder[ProposalPageRequestWire] = deriveEncoder

final case class ProposalBatchResponseWire(
    proposalsBase64Url: Vector[String],
)
object ProposalBatchResponseWire:
  given Decoder[ProposalBatchResponseWire] = deriveDecoder
  given Encoder[ProposalBatchResponseWire] = deriveEncoder

object HotStuffBootstrapTransportLimits:
  val MaxSnapshotNodeHashes: Int = 256
  val MaxProposalPageLimit: Int  = 256

@SuppressWarnings(Array("org.wartremover.warts.Any"))
object HotStuffBootstrapArmeriaAdapter:
  def endpoints[F[_]: Async, A](
      sessionRuntime: TxGossipRuntime[F, A],
      bootstrapServices: HotStuffBootstrapServices[F],
      transportAuth: StaticPeerTransportAuth,
  ): List[ServerEndpoint[Any, F]] =
    List(
      finalizedSuggestionEndpoint(sessionRuntime, bootstrapServices, transportAuth),
      snapshotFetchEndpoint(sessionRuntime, bootstrapServices, transportAuth),
      replayEndpoint(sessionRuntime, bootstrapServices, transportAuth),
      backfillEndpoint(sessionRuntime, bootstrapServices, transportAuth),
    )

  private def finalizedSuggestionEndpoint[F[_]: Async, A](
      sessionRuntime: TxGossipRuntime[F, A],
      bootstrapServices: HotStuffBootstrapServices[F],
      transportAuth: StaticPeerTransportAuth,
  ): ServerEndpoint[Any, F] =
    endpoint.post
      .in("gossip" / "bootstrap" / "finalized" / path[String]("sessionId") / path[String]("chainId"))
      .in(header[Option[String]](GossipTransportAuth.AuthenticatedPeerHeaderName))
      .in(header[Option[String]](GossipTransportAuth.TransportProofHeaderName))
      .in(header[Option[String]](GossipTransportAuth.BootstrapCapabilityHeaderName))
      .errorOut(stringBody)
      .out(stringBody)
      .serverLogic: (sessionIdRaw, chainIdRaw, authenticatedPeerRaw, transportProofRaw, capabilityRaw) =>
        val requestPath = s"/gossip/bootstrap/finalized/${sessionIdRaw}/${chainIdRaw}"
        withAuthorizedBinding(
          sessionRuntime,
          transportAuth,
          sessionIdRaw,
          authenticatedPeerRaw,
          transportProofRaw,
          capabilityRaw,
          requestPath = requestPath,
          requestBodyBytes = Array.emptyByteArray,
        ): binding =>
          parseChainId(chainIdRaw) match
            case Left(rejection) =>
              renderRejection(rejection).asLeft[String].pure[F]
            case Right(chainId) =>
              bootstrapServices.finalizedAnchorSuggestions
                .bestFinalized(binding, chainId)
                .map:
                  _.leftMap(renderRejection).map: suggestion =>
                    FinalizedSuggestionResponseWire(
                      suggestionBase64Url = suggestion.map(encodeValue(_)),
                    ).asJson.noSpaces

  private def snapshotFetchEndpoint[F[_]: Async, A](
      sessionRuntime: TxGossipRuntime[F, A],
      bootstrapServices: HotStuffBootstrapServices[F],
      transportAuth: StaticPeerTransportAuth,
  ): ServerEndpoint[Any, F] =
    endpoint.post
      .in("gossip" / "bootstrap" / "snapshot" / path[String]("sessionId") / path[String]("chainId"))
      .in(header[Option[String]](GossipTransportAuth.AuthenticatedPeerHeaderName))
      .in(header[Option[String]](GossipTransportAuth.TransportProofHeaderName))
      .in(header[Option[String]](GossipTransportAuth.BootstrapCapabilityHeaderName))
      .in(stringBody)
      .errorOut(stringBody)
      .out(stringBody)
      .serverLogic: (sessionIdRaw, chainIdRaw, authenticatedPeerRaw, transportProofRaw, capabilityRaw, raw) =>
        val requestPath = s"/gossip/bootstrap/snapshot/${sessionIdRaw}/${chainIdRaw}"
        withAuthorizedBinding(
          sessionRuntime,
          transportAuth,
          sessionIdRaw,
          authenticatedPeerRaw,
          transportProofRaw,
          capabilityRaw,
          requestPath = requestPath,
          requestBodyBytes = raw.getBytes(StandardCharsets.UTF_8),
        ): binding =>
          (parseChainId(chainIdRaw), decodeOrBootstrapReject[SnapshotNodeFetchRequestWire](raw, "invalidSnapshotNodeFetchRequest")) match
            case (Left(rejection), _) =>
              renderRejection(rejection).asLeft[String].pure[F]
            case (_, Left(rendered)) =>
              rendered.asLeft[String].pure[F]
            case (Right(chainId), Right(request)) =>
              parseSnapshotRequest(request).fold(
                rejection =>
                  renderRejection(rejection).asLeft[String].pure[F],
                { case (stateRoot, hashes) =>
                  bootstrapServices.snapshotNodeFetch
                    .fetchNodes(binding, chainId, stateRoot, hashes)
                    .map:
                      _.leftMap(renderRejection).map: nodes =>
                        SnapshotNodeFetchResponseWire(
                          nodes = nodes.map: node =>
                            SnapshotNodeWire(
                              hash = node.hash.toUInt256.toHexLower,
                              nodeBase64Url = encodeValue(node.node),
                            ),
                        ).asJson.noSpaces
                },
              )

  private def replayEndpoint[F[_]: Async, A](
      sessionRuntime: TxGossipRuntime[F, A],
      bootstrapServices: HotStuffBootstrapServices[F],
      transportAuth: StaticPeerTransportAuth,
  ): ServerEndpoint[Any, F] =
    endpoint.post
      .in("gossip" / "bootstrap" / "replay" / path[String]("sessionId") / path[String]("chainId"))
      .in(header[Option[String]](GossipTransportAuth.AuthenticatedPeerHeaderName))
      .in(header[Option[String]](GossipTransportAuth.TransportProofHeaderName))
      .in(header[Option[String]](GossipTransportAuth.BootstrapCapabilityHeaderName))
      .in(stringBody)
      .errorOut(stringBody)
      .out(stringBody)
      .serverLogic: (sessionIdRaw, chainIdRaw, authenticatedPeerRaw, transportProofRaw, capabilityRaw, raw) =>
        val requestPath = s"/gossip/bootstrap/replay/${sessionIdRaw}/${chainIdRaw}"
        withAuthorizedBinding(
          sessionRuntime,
          transportAuth,
          sessionIdRaw,
          authenticatedPeerRaw,
          transportProofRaw,
          capabilityRaw,
          requestPath = requestPath,
          requestBodyBytes = raw.getBytes(StandardCharsets.UTF_8),
        ): binding =>
          handleProposalPageRequest(
            raw = raw,
            chainIdRaw = chainIdRaw,
            invalidReason = "invalidProposalReplayRequest",
            parsePage = parseProposalReplayRequest,
          ): (chainId, blockId, height, limit) =>
            bootstrapServices.proposalReplay
              .readNext(
                session = binding,
                chainId = chainId,
                anchorBlockId = blockId,
                nextHeight = height,
                limit = limit,
              )

  private def backfillEndpoint[F[_]: Async, A](
      sessionRuntime: TxGossipRuntime[F, A],
      bootstrapServices: HotStuffBootstrapServices[F],
      transportAuth: StaticPeerTransportAuth,
  ): ServerEndpoint[Any, F] =
    endpoint.post
      .in("gossip" / "bootstrap" / "backfill" / path[String]("sessionId") / path[String]("chainId"))
      .in(header[Option[String]](GossipTransportAuth.AuthenticatedPeerHeaderName))
      .in(header[Option[String]](GossipTransportAuth.TransportProofHeaderName))
      .in(header[Option[String]](GossipTransportAuth.BootstrapCapabilityHeaderName))
      .in(stringBody)
      .errorOut(stringBody)
      .out(stringBody)
      .serverLogic: (sessionIdRaw, chainIdRaw, authenticatedPeerRaw, transportProofRaw, capabilityRaw, raw) =>
        val requestPath = s"/gossip/bootstrap/backfill/${sessionIdRaw}/${chainIdRaw}"
        withAuthorizedBinding(
          sessionRuntime,
          transportAuth,
          sessionIdRaw,
          authenticatedPeerRaw,
          transportProofRaw,
          capabilityRaw,
          requestPath = requestPath,
          requestBodyBytes = raw.getBytes(StandardCharsets.UTF_8),
        ): binding =>
          handleProposalPageRequest(
            raw = raw,
            chainIdRaw = chainIdRaw,
            invalidReason = "invalidHistoricalBackfillRequest",
            parsePage = parseHistoricalBackfillRequest,
          ): (chainId, blockId, height, limit) =>
            bootstrapServices.historicalBackfill
              .readPrevious(
                session = binding,
                chainId = chainId,
                beforeBlockId = blockId,
                beforeHeight = height,
                limit = limit,
              )

  private def handleProposalPageRequest[F[_]: Async](
      raw: String,
      chainIdRaw: String,
      invalidReason: String,
      parsePage: ProposalPageRequestWire => Either[
        CanonicalRejection,
        (BlockId, BlockHeight, Int),
      ],
  )(
      fetch: (ChainId, BlockId, BlockHeight, Int) => F[Either[
        CanonicalRejection,
        Vector[Proposal],
      ]],
  ): F[Either[String, String]] =
    (parseChainId(chainIdRaw), decodeOrBootstrapReject[ProposalPageRequestWire](raw, invalidReason)) match
      case (Left(rejection), _) =>
        renderRejection(rejection).asLeft[String].pure[F]
      case (_, Left(rendered)) =>
        rendered.asLeft[String].pure[F]
      case (Right(chainId), Right(request)) =>
        parsePage(request).fold(
          rejection => renderRejection(rejection).asLeft[String].pure[F],
          { case (blockId, height, limit) =>
            fetch(chainId, blockId, height, limit).map:
              _.leftMap(renderRejection).map: proposals =>
                ProposalBatchResponseWire(
                  proposalsBase64Url = proposals.map(encodeValue(_)),
                ).asJson.noSpaces
          },
        )

  private def withAuthorizedBinding[F[_]: Async, A](
      sessionRuntime: TxGossipRuntime[F, A],
      transportAuth: StaticPeerTransportAuth,
      sessionIdRaw: String,
      authenticatedPeerRaw: Option[String],
      transportProofRaw: Option[String],
      capabilityRaw: Option[String],
      requestPath: String,
      requestBodyBytes: Array[Byte],
  )(
      f: BootstrapSessionBinding => F[Either[String, String]],
  ): F[Either[String, String]] =
    DirectionalSessionId.parse(sessionIdRaw) match
      case Left(error) =>
        renderRejection(handshakeRejected("invalidSessionId", error))
          .asLeft[String]
          .pure[F]
      case Right(sessionId) =>
        GossipTransportAuth.authenticateRequest(
          transportAuth = transportAuth,
          authenticatedPeerRaw = authenticatedPeerRaw,
          transportProofRaw = transportProofRaw,
          httpMethod = "POST",
          requestPath = requestPath,
          requestBodyBytes = requestBodyBytes,
        ) match
          case Left(rejection) =>
            renderRejection(rejection).asLeft[String].pure[F]
          case Right(authenticatedPeer) =>
            sessionRuntime
              .authorizeOpenSessionForPeer(sessionId, authenticatedPeer)
              .flatMap:
                case Left(rejection) =>
                  renderRejection(rejection).asLeft[String].pure[F]
                case Right(session) =>
                  GossipTransportAuth.verifyBootstrapCapability(
                    transportAuth = transportAuth,
                    raw = capabilityRaw,
                    authenticatedPeer = authenticatedPeer,
                    targetPeer = transportAuth.localPeer,
                    sessionId = session.sessionId,
                    httpMethod = "POST",
                    requestPath = requestPath,
                    requestBodyBytes = requestBodyBytes,
                  ) match
                    case Left(rejection) =>
                      renderRejection(rejection).asLeft[String].pure[F]
                    case Right(_) =>
                      f(
                        BootstrapSessionBinding(
                          peer = session.peer,
                          sessionId = session.sessionId,
                          authenticatedPeer = authenticatedPeer,
                        ),
                      )

  private def decodeOrBootstrapReject[A: Decoder](
      raw: String,
      reason: String,
  ): Either[String, A] =
    decode[A](raw).leftMap(error =>
      renderRejection(
        bootstrapRejected(reason, error.getMessage),
      ),
    )

  private def parseChainId(
      chainIdRaw: String,
  ): Either[CanonicalRejection, ChainId] =
    ChainId
      .parse(chainIdRaw)
      .leftMap(bootstrapRejected("invalidChainId", _))

  private def parseSnapshotRequest(
      request: SnapshotNodeFetchRequestWire,
  ): Either[CanonicalRejection, (StateRoot, Vector[org.sigilaris.core.merkle.MerkleTrieNode.MerkleHash])] =
    for
      stateRoot <- StateRoot
        .fromHex(request.stateRoot)
        .leftMap(bootstrapRejected("invalidStateRoot", _))
      _ <- Either.cond(
        request.hashes.sizeIs <= HotStuffBootstrapTransportLimits.MaxSnapshotNodeHashes,
        (),
        bootstrapRejected(
          "bootstrapRequestTooLarge",
          ss"max=${HotStuffBootstrapTransportLimits.MaxSnapshotNodeHashes.toString} actual=${request.hashes.size.toString}",
        ),
      )
      hashes <- request.hashes.traverse: hash =>
        UInt256
          .fromHex(hash)
          .leftMap(error =>
            bootstrapRejected("invalidSnapshotNodeHash", error.toString),
          )
          .map(org.sigilaris.core.crypto.Hash.Value[org.sigilaris.core.merkle.MerkleTrieNode](_))
    yield stateRoot -> hashes

  private def parseProposalReplayRequest(
      request: ProposalPageRequestWire,
  ): Either[CanonicalRejection, (BlockId, BlockHeight, Int)] =
    parseProposalPageRequest(
      request = request,
      blockIdReason = "invalidAnchorBlockId",
      heightReason = "invalidReplayHeight",
    )

  private def parseHistoricalBackfillRequest(
      request: ProposalPageRequestWire,
  ): Either[CanonicalRejection, (BlockId, BlockHeight, Int)] =
    parseProposalPageRequest(
      request = request,
      blockIdReason = "invalidBeforeBlockId",
      heightReason = "invalidBeforeHeight",
    )

  private def parseProposalPageRequest(
      request: ProposalPageRequestWire,
      blockIdReason: String,
      heightReason: String,
  ): Either[CanonicalRejection, (BlockId, BlockHeight, Int)] =
    for
      blockId <- BlockId
        .fromHex(request.blockId)
        .leftMap(bootstrapRejected(blockIdReason, _))
      height <- parseBlockHeight(request.height, heightReason)
      _ <- Either.cond(
        request.limit >= 0 &&
          request.limit <= HotStuffBootstrapTransportLimits.MaxProposalPageLimit,
        (),
        bootstrapRejected(
          "bootstrapRequestTooLarge",
          ss"max=${HotStuffBootstrapTransportLimits.MaxProposalPageLimit.toString} actual=${request.limit.toString}",
        ),
      )
    yield (blockId, height, request.limit)

  private def parseBlockHeight(
      value: String,
      reason: String,
  ): Either[CanonicalRejection, BlockHeight] =
    Either
      .catchNonFatal(BigInt(value))
      .leftMap(_ => bootstrapRejected(reason, value))
      .flatMap(bigInt =>
        BigNat
          .fromBigInt(bigInt)
          .leftMap(bootstrapRejected(reason, _))
          .map(BlockHeight(_)),
      )

  private def encodeValue[A: ByteEncoder](
      value: A,
  ): String =
    Base64.getUrlEncoder.withoutPadding().encodeToString(value.toBytes.toArray)

  private[gossip] def renderRejection(
      rejection: CanonicalRejection,
  ): String =
    RejectionWire(
      rejectionClass = rejection.rejectionClass,
      reason = rejection.reason,
      detail = rejection.detail,
    ).asJson.noSpaces

  private[gossip] def decodeRejection(
      raw: String,
  ): Either[String, CanonicalRejection] =
    decode[RejectionWire](raw)
      .leftMap(_.getMessage)
      .map: wire =>
        wire.rejectionClass match
          case "handshakeRejected" =>
            CanonicalRejection.HandshakeRejected(
              reason = wire.reason,
              detail = wire.detail,
            )
          case "controlBatchRejected" =>
            CanonicalRejection.ControlBatchRejected(
              reason = wire.reason,
              detail = wire.detail,
            )
          case "artifactContractRejected" =>
            CanonicalRejection.ArtifactContractRejected(
              reason = wire.reason,
              detail = wire.detail,
            )
          case "staleCursor" =>
            CanonicalRejection.StaleCursor(
              reason = wire.reason,
              detail = wire.detail,
            )
          case _ =>
            CanonicalRejection.BackfillUnavailable(
              reason = wire.reason,
              detail = wire.detail,
            )

  private[gossip] def decodeValue[A: ByteDecoder](
      encoded: String,
      reason: String,
  ): Either[CanonicalRejection, A] =
    for
      bytes <- Try(
        ByteVector.view(Base64.getUrlDecoder.decode(encoded)),
      ).toEither.leftMap(_ => bootstrapRejected(reason, encoded))
      value <- bytes.to[A].leftMap(failure =>
        bootstrapRejected(reason, failure.msg),
      )
    yield value

  private def bootstrapRejected(
      reason: String,
      detail: String,
  ): CanonicalRejection.BackfillUnavailable =
    CanonicalRejection.BackfillUnavailable(
      reason = reason,
      detail = Some(detail),
    )

  private def handshakeRejected(
      reason: String,
      detail: String,
  ): CanonicalRejection.HandshakeRejected =
    CanonicalRejection.HandshakeRejected(
      reason = reason,
      detail = Some(detail),
    )

object HotStuffBootstrapHttpTransport:
  val DefaultRequestTimeout: Duration = Duration.ofSeconds(10L)
  val DefaultMaxConcurrentRequests: Int = 16

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def services[F[_]: Async](
      peerBaseUris: Map[PeerIdentity, String],
      transportAuth: StaticPeerTransportAuth,
      httpClient: HttpClient =
        HttpClient
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
                        .map(org.sigilaris.core.crypto.Hash.Value[org.sigilaris.core.merkle.MerkleTrieNode](_))
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
      peerBaseUris: Map[PeerIdentity, String],
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
      peerBaseUris: Map[PeerIdentity, String],
  ): F[Either[CanonicalRejection, String]] =
    peerBaseUris.get(session.peer) match
      case None =>
        CanonicalRejection.BackfillUnavailable(
            reason = "bootstrapPeerEndpointUnavailable",
            detail = Some(session.peer.value),
        ).asLeft[String].pure[F]
      case Some(baseUri) =>
        val bodyBytes = body.fold(Array.emptyByteArray)(_.getBytes(StandardCharsets.UTF_8))
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
            CanonicalRejection.BackfillUnavailable(
              reason = "bootstrapTransportAuthUnavailable",
              detail = Some(error),
            ).asLeft[String].pure[F]
          case (_, Left(error)) =>
            CanonicalRejection.BackfillUnavailable(
              reason = "bootstrapCapabilityUnavailable",
              detail = Some(error),
            ).asLeft[String].pure[F]
          case (Right(transportProof), Right(bootstrapCapability)) =>
            Async[F]
              .blocking(requestGate.acquire())
              .bracket(_ =>
                Async[F]
                  .fromCompletableFuture(
                    Async[F].delay:
                      val builder =
                        HttpRequest
                          .newBuilder(URI.create(baseUri + path))
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
                              .POST(HttpRequest.BodyPublishers.ofString(payload))
                              .build()
                          case None =>
                            builder
                              .POST(HttpRequest.BodyPublishers.noBody())
                              .build()
                      httpClient.sendAsync(
                        request,
                        HttpResponse.BodyHandlers.ofString(),
                      )
                  )
                  .attempt
                  .map:
                    case Left(error) =>
                      CanonicalRejection.BackfillUnavailable(
                        reason = "bootstrapTransportFailed",
                        detail = Some(error.getMessage),
                      ).asLeft[String]
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
                        .asLeft[String]
              )(_ => Async[F].delay(requestGate.release()).void)

object HotStuffGossipArmeriaAdapter:
  def endpoints[F[_]: Async](
      bootstrap: HotStuffRuntimeBootstrap[F],
  )(using ByteEncoder[HotStuffGossipArtifact]) =
    TxGossipArmeriaAdapter.endpoints[F, HotStuffGossipArtifact](
      bootstrap.runtime,
      bootstrap.transportAuth,
    ) ++
      HotStuffBootstrapArmeriaAdapter.endpoints[F, HotStuffGossipArtifact](
        sessionRuntime = bootstrap.runtime,
        bootstrapServices = bootstrap.consensus.bootstrapServices,
        transportAuth = bootstrap.transportAuth,
      )
