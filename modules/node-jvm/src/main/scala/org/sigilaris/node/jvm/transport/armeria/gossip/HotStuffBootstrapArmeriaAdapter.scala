package org.sigilaris.node.jvm.transport.armeria.gossip

import java.nio.charset.StandardCharsets
import java.util.Base64

import scala.util.Try

import cats.effect.Async
import cats.syntax.all.*
import io.circe.Decoder
import io.circe.parser.decode
import io.circe.syntax.*
import scodec.bits.ByteVector
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.codec.byte.ByteDecoder.ops.*
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.node.jvm.runtime.block.{BlockHeight, BlockId, StateRoot}
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.given
import org.sigilaris.node.gossip.*
import org.sigilaris.node.gossip.tx.TxGossipRuntime
/** Server-side Armeria/Tapir adapter for HotStuff bootstrap protocol endpoints.
  *
  * Exposes finalized suggestion, snapshot fetch, proposal replay, and historical backfill
  * endpoints with transport authentication and bootstrap capability verification.
  */
@SuppressWarnings(Array("org.wartremover.warts.Any"))
object HotStuffBootstrapArmeriaAdapter:
  /** Creates the list of Tapir server endpoints for the HotStuff bootstrap protocol.
    *
    * @tparam F
    *   the effect type
    * @tparam A
    *   the gossip artifact type
    * @param sessionRuntime
    *   runtime for authorizing gossip sessions
    * @param bootstrapServices
    *   services providing finalized suggestions, snapshots, replay, and backfill
    * @param transportAuth
    *   transport authentication for verifying peer requests
    * @return
    *   list of server endpoints
    */
  def endpoints[F[_]: Async, A](
      sessionRuntime: TxGossipRuntime[F, A],
      bootstrapServices: HotStuffBootstrapServices[F],
      transportAuth: StaticPeerTransportAuth,
  ): List[ServerEndpoint[Any, F]] =
    List(
      finalizedSuggestionEndpoint(
        sessionRuntime,
        bootstrapServices,
        transportAuth,
      ),
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
      .in(
        "gossip" / "bootstrap" / "finalized" / path[String]("sessionId") / path[
          String,
        ]("chainId"),
      )
      .in:
        header[Option[String]](GossipTransportAuth.AuthenticatedPeerHeaderName)
      .in:
        header[Option[String]](GossipTransportAuth.TransportProofHeaderName)
      .in:
        header[Option[String]](
          GossipTransportAuth.BootstrapCapabilityHeaderName,
        )
      .errorOut(stringBody)
      .out(stringBody)
      .serverLogic:
        (
            sessionIdRaw,
            chainIdRaw,
            authenticatedPeerRaw,
            transportProofRaw,
            capabilityRaw,
        ) =>
          val requestPath =
            s"/gossip/bootstrap/finalized/${sessionIdRaw}/${chainIdRaw}"
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
      .in(
        "gossip" / "bootstrap" / "snapshot" / path[String]("sessionId") / path[
          String,
        ]("chainId"),
      )
      .in:
        header[Option[String]](GossipTransportAuth.AuthenticatedPeerHeaderName)
      .in:
        header[Option[String]](GossipTransportAuth.TransportProofHeaderName)
      .in:
        header[Option[String]](
          GossipTransportAuth.BootstrapCapabilityHeaderName,
        )
      .in(stringBody)
      .errorOut(stringBody)
      .out(stringBody)
      .serverLogic:
        (
            sessionIdRaw,
            chainIdRaw,
            authenticatedPeerRaw,
            transportProofRaw,
            capabilityRaw,
            raw,
        ) =>
          val requestPath =
            s"/gossip/bootstrap/snapshot/${sessionIdRaw}/${chainIdRaw}"
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
            (
              parseChainId(chainIdRaw),
              decodeOrBootstrapReject[SnapshotNodeFetchRequestWire](
                raw,
                "invalidSnapshotNodeFetchRequest",
              ),
            ) match
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
      .in(
        "gossip" / "bootstrap" / "replay" / path[String]("sessionId") / path[
          String,
        ]("chainId"),
      )
      .in:
        header[Option[String]](GossipTransportAuth.AuthenticatedPeerHeaderName)
      .in:
        header[Option[String]](GossipTransportAuth.TransportProofHeaderName)
      .in:
        header[Option[String]](
          GossipTransportAuth.BootstrapCapabilityHeaderName,
        )
      .in(stringBody)
      .errorOut(stringBody)
      .out(stringBody)
      .serverLogic:
        (
            sessionIdRaw,
            chainIdRaw,
            authenticatedPeerRaw,
            transportProofRaw,
            capabilityRaw,
            raw,
        ) =>
          val requestPath =
            s"/gossip/bootstrap/replay/${sessionIdRaw}/${chainIdRaw}"
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
      .in(
        "gossip" / "bootstrap" / "backfill" / path[String]("sessionId") / path[
          String,
        ]("chainId"),
      )
      .in:
        header[Option[String]](GossipTransportAuth.AuthenticatedPeerHeaderName)
      .in:
        header[Option[String]](GossipTransportAuth.TransportProofHeaderName)
      .in:
        header[Option[String]](
          GossipTransportAuth.BootstrapCapabilityHeaderName,
        )
      .in(stringBody)
      .errorOut(stringBody)
      .out(stringBody)
      .serverLogic:
        (
            sessionIdRaw,
            chainIdRaw,
            authenticatedPeerRaw,
            transportProofRaw,
            capabilityRaw,
            raw,
        ) =>
          val requestPath =
            s"/gossip/bootstrap/backfill/${sessionIdRaw}/${chainIdRaw}"
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
    (
      parseChainId(chainIdRaw),
      decodeOrBootstrapReject[ProposalPageRequestWire](raw, invalidReason),
    ) match
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
  ): Either[
    CanonicalRejection,
    (StateRoot, Vector[org.sigilaris.core.merkle.MerkleTrieNode.MerkleHash]),
  ] =
    SnapshotNodeFetchRequestWire
      .parse(request)
      .map(parsed => parsed.stateRoot -> parsed.hashes)

  private def parseProposalReplayRequest(
      request: ProposalPageRequestWire,
  ): Either[CanonicalRejection, (BlockId, BlockHeight, Int)] =
    ProposalPageRequestWire
      .parseReplay(request)
      .map(parsed => (parsed.blockId, parsed.height, parsed.limit))

  private def parseHistoricalBackfillRequest(
      request: ProposalPageRequestWire,
  ): Either[CanonicalRejection, (BlockId, BlockHeight, Int)] =
    ProposalPageRequestWire
      .parseHistoricalBackfill(request)
      .map(parsed => (parsed.blockId, parsed.height, parsed.limit))

  private def encodeValue[A: ByteEncoder](
      value: A,
  ): String =
    Base64.getUrlEncoder.withoutPadding().encodeToString(value.toBytes.toArray)

  private[gossip] def renderRejection(
      rejection: CanonicalRejection,
  ): String =
    RejectionWire.fromCanonical(rejection).asJson.noSpaces

  private[gossip] def decodeRejection(
      raw: String,
  ): Either[String, CanonicalRejection] =
    decode[RejectionWire](raw)
      .leftMap(_.getMessage)
      .map(RejectionWire.toCanonical)

  private[gossip] def decodeValue[A: ByteDecoder](
      encoded: String,
      reason: String,
  ): Either[CanonicalRejection, A] =
    for
      bytes <- Try(
        ByteVector.view(Base64.getUrlDecoder.decode(encoded)),
      ).toEither.leftMap(_ => bootstrapRejected(reason, encoded))
      value <- bytes
        .to[A]
        .leftMap(failure => bootstrapRejected(reason, failure.msg))
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
/** Convenience adapter combining transaction gossip and HotStuff bootstrap endpoints. */
object HotStuffGossipArmeriaAdapter:
  /** Creates all server endpoints for both transaction gossip and HotStuff bootstrap protocols.
    *
    * @tparam F
    *   the effect type
    * @param bootstrap
    *   the HotStuff runtime bootstrap containing runtime, auth, and consensus services
    * @return
    *   combined list of gossip and bootstrap server endpoints
    */
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
