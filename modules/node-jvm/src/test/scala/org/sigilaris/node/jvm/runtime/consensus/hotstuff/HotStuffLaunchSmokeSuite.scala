package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.{Duration, Instant}
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Try
import scala.util.Using

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite
import scodec.bits.ByteVector

import com.typesafe.config.{Config, ConfigFactory}

import org.sigilaris.core.codec.byte.{ByteDecoder, DecodeResult}
import org.sigilaris.core.crypto.CryptoOps
import org.sigilaris.core.crypto.Hash.ops.*
import org.sigilaris.core.datatype.UInt256
import org.sigilaris.core.failure.DecodeFailure
import org.sigilaris.core.merkle.MerkleTrieNode
import org.sigilaris.core.merkle.Nibbles.*
import org.sigilaris.node.jvm.runtime.block.{
  BlockHeader,
  BlockHeight,
  BlockId,
  BlockTimestamp,
  BodyRoot,
  StateRoot,
}
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.given
import org.sigilaris.node.jvm.runtime.gossip.*
import org.sigilaris.node.jvm.storage.swaydb.StorageLayout
import org.sigilaris.node.jvm.transport.armeria.{ArmeriaServer, ArmeriaServerConfig}
import org.sigilaris.node.jvm.transport.armeria.gossip.*

final class HotStuffLaunchSmokeSuite extends CatsEffectSuite:

  override def munitIOTimeout: scala.concurrent.duration.Duration =
    120.seconds

  private val chainId = ChainId.unsafe("chain-main")
  private val startedAt = Instant.parse("2026-04-07T09:00:00Z")
  private val validatorKeys = Vector.fill(4)(CryptoOps.generate())
  private val validatorSet = ValidatorSet.unsafe(
    validatorKeys.zipWithIndex.map: (keyPair, index) =>
      ValidatorMember(
        id = ValidatorId.unsafe(s"validator-${index + 1}"),
        publicKey = keyPair.publicKey,
      ),
  )

  private val nodeA = "node-a"
  private val nodeB = "node-b"
  private val nodeC = "node-c"
  private val nodeD = "node-d"
  private val nodeE = "node-e"

  private val validatorPeers = Vector(nodeA, nodeB, nodeC, nodeD)
  private val allPeers = validatorPeers :+ nodeE
  private val authenticatedPeerHeaderName = "x-sigilaris-peer-identity"
  private val transportProofHeaderName = "x-sigilaris-transport-proof"
  private val bootstrapCapabilityHeaderName = "x-sigilaris-bootstrap-capability"
  private val transportProofInfo = "sigilaris.transport-proof.v1"
  private val transportProofHmacAlgorithm = "HmacSHA256"
  private val transportProofSha256 = "SHA-256"
  private val sharedHttpClient = HttpClient.newHttpClient()
  private val validatorNodeSpecs =
    Vector(nodeA -> 0, nodeB -> 1, nodeC -> 2, nodeD -> 3)

  private val subscription = SessionSubscription.unsafe(
    ChainTopic(chainId, GossipTopic.consensusProposal),
    ChainTopic(chainId, GossipTopic.consensusVote),
    ChainTopic(chainId, GossipTopic.consensusTimeoutVote),
    ChainTopic(chainId, GossipTopic.consensusNewView),
  )

  private given ByteDecoder[TimeoutVoteId] =
    ByteDecoder[UInt256].map(TimeoutVoteId(_))
  private given ByteDecoder[NewViewId] =
    ByteDecoder[UInt256].map(NewViewId(_))
  private given ByteDecoder[TimeoutVoteSubject] = ByteDecoder.derived
  private given ByteDecoder[TimeoutVote] = ByteDecoder.derived
  private given ByteDecoder[TimeoutCertificate] = ByteDecoder.derived
  private given ByteDecoder[NewView] = ByteDecoder.derived
  private given ByteDecoder[HotStuffGossipArtifact] =
    bytes =>
      ByteDecoder[Byte].decode(bytes).flatMap:
        case DecodeResult(tag, remainder) =>
          tag match
            case 0x01 =>
              ByteDecoder[Proposal]
                .decode(remainder)
                .map(result =>
                  DecodeResult(
                    HotStuffGossipArtifact.ProposalArtifact(result.value),
                    result.remainder,
                  ),
                )
            case 0x02 =>
              ByteDecoder[Vote]
                .decode(remainder)
                .map(result =>
                  DecodeResult(
                    HotStuffGossipArtifact.VoteArtifact(result.value),
                    result.remainder,
                  ),
                )
            case 0x03 =>
              ByteDecoder[TimeoutVote]
                .decode(remainder)
                .map(result =>
                  DecodeResult(
                    HotStuffGossipArtifact.TimeoutVoteArtifact(result.value),
                    result.remainder,
                  ),
                )
            case 0x04 =>
              ByteDecoder[NewView]
                .decode(remainder)
                .map(result =>
                  DecodeResult(
                    HotStuffGossipArtifact.NewViewArtifact(result.value),
                    result.remainder,
                  ),
                )
            case other =>
              DecodeFailure(
                "unknown HotStuff gossip artifact tag: " + (other.toInt & 0xff).toString,
              ).asLeft[DecodeResult[HotStuffGossipArtifact]]

  test(
    "reference launch smoke drives static validators, timeout recovery, newcomer bootstrap, and archive restart persistence",
  ):
    tempDirResource.use: root =>
      val graph = snapshotGraph("31")
      val genesis = genesisProposal("41")
      val holders = initialHolders

      validatorNodesResource(root, holders, validatorNodeSpecs).use: validators =>
        val validatorByNode = validators.map(node => node.localNodeId -> node).toMap
        val leader1 = validatorByNode(nodeB)
        val leader2 = validatorByNode(nodeC)
        val leaderAfterTimeout = validatorByNode(nodeA)
        val timeoutLeader = validatorByNode(nodeD)

        for
          _ <- validators.traverse_(seedBootstrapLocal(_, Vector(genesis), Vector(graph.rootNode, graph.leftNode, graph.rightNode)))
          links <- openDirectedMesh(validators)
          _ <- drainNetwork(links)
          genesisQc = qcFor(genesis, Vector(0, 1, 2))
          round1 <- driveProposalRound(
            links = links,
            leader = leader1,
            proposer = validatorSet.members(1).id,
            justify = genesisQc,
            parentBlockId = Some(genesis.targetBlockId),
            height = 1L,
            view = 1L,
            stateRoot = StateRoot(graph.rootHash.toUInt256),
            bodySeed = "5101",
            voters = Vector(
              validatorByNode(nodeA) -> validatorSet.members(0).id,
              validatorByNode(nodeB) -> validatorSet.members(1).id,
              validatorByNode(nodeC) -> validatorSet.members(2).id,
            ),
            emitOffsetSeconds = 10L,
          )
          round2 <- driveProposalRound(
            links = links,
            leader = leader2,
            proposer = validatorSet.members(2).id,
            justify = round1.qc,
            parentBlockId = Some(round1.proposal.targetBlockId),
            height = 2L,
            view = 2L,
            stateRoot = StateRoot(hex("5202")),
            bodySeed = "5202",
            voters = Vector(
              validatorByNode(nodeA) -> validatorSet.members(0).id,
              validatorByNode(nodeB) -> validatorSet.members(1).id,
              timeoutLeader -> validatorSet.members(3).id,
            ),
            emitOffsetSeconds = 20L,
          )
          turnover <- driveTimeoutTurnover(
            links = links,
            voters = Vector(
              validatorByNode(nodeA) -> validatorSet.members(0).id,
              validatorByNode(nodeB) -> validatorSet.members(1).id,
              validatorByNode(nodeC) -> validatorSet.members(2).id,
            ),
            newViewEmitter = validatorByNode(nodeA) -> validatorSet.members(0).id,
            height = 3L,
            timeoutView = 3L,
            highestKnownQc = round2.qc,
            emitOffsetSeconds = 30L,
          )
          round3 <- driveProposalRound(
            links = links,
            leader = leaderAfterTimeout,
            proposer = validatorSet.members(0).id,
            justify = round2.qc,
            parentBlockId = Some(round2.proposal.targetBlockId),
            height = 3L,
            view = 4L,
            stateRoot = StateRoot(hex("5303")),
            bodySeed = "5303",
            voters = Vector(
              validatorByNode(nodeA) -> validatorSet.members(0).id,
              validatorByNode(nodeB) -> validatorSet.members(1).id,
              validatorByNode(nodeC) -> validatorSet.members(2).id,
            ),
            emitOffsetSeconds = 40L,
          )
          snapshots <- validators.traverse(sinkSnapshot)
          bootstrapTransport = bootstrapTransportFor(nodeE, validators)
          auditRoot = root.resolve(nodeE)
          auditRestartRoot = root.resolve("node-e-restart")
          auditBootstrapResult <- launchedNodeResource(
            localNodeId = nodeE,
            role = LocalNodeRole.Audit,
            holders = holders,
            localKeys = Map.empty,
            storageRoot = auditRoot,
            bootstrapTransport = Some(bootstrapTransport),
          ).use: audit =>
            for
              auditSessions <- validators.traverse(openOutboundViaHttp(audit, _))
              bootstrapResult <- audit.bootstrap.consensus.bootstrap(
                chainId = chainId,
                sessions = auditSessions.map(_.binding),
                startedAt = startedAt.plusSeconds(60L),
                liveProposals = Vector.empty,
              )
              readyDiagnostics <- audit.bootstrap.consensus.currentBootstrapDiagnostics
              completedBackfill <- awaitValue(
                audit.bootstrap.consensus.currentBootstrapDiagnostics,
                attempts = 120,
                delay = 50.millis,
              ):
                diagnostics =>
                  diagnostics.historicalBackfill match
                    case HistoricalBackfillStatus.Completed(
                          "genesisReached",
                          progress,
                        ) if progress.fetchedProposalCount >= 1L =>
                      true
                    case _ =>
                      false
              archived <- withArchive(StorageLayout.fromRoot(auditRoot))(_.list(chainId))
            yield (bootstrapResult, readyDiagnostics, completedBackfill, archived)
          _ <- IO.blocking(copyRecursively(auditRoot, auditRestartRoot))
          restartedArchive <- launchedNodeResource(
            localNodeId = nodeE,
            role = LocalNodeRole.Audit,
            holders = holders,
            localKeys = Map.empty,
            storageRoot = auditRestartRoot,
            bootstrapTransport = Some(bootstrapTransport),
          ).use: _ =>
            withArchive(StorageLayout.fromRoot(auditRestartRoot))(_.list(chainId))
        yield
          val (bootstrapResult, readyDiagnostics, completedBackfill, archivedBeforeRestart) =
            auditBootstrapResult
          assertEquals(
            HotStuffPacemaker.deterministicLeader(
              HotStuffWindow(chainId, 1L, 1L, validatorSet.hash),
              validatorSet,
            ),
            validatorSet.members(1).id,
          )
          assertEquals(
            HotStuffPacemaker.deterministicLeader(
              HotStuffWindow(chainId, 2L, 2L, validatorSet.hash),
              validatorSet,
            ),
            validatorSet.members(2).id,
          )
          assertEquals(
            HotStuffPacemaker.deterministicLeader(
              HotStuffWindow(chainId, 3L, 3L, validatorSet.hash),
              validatorSet,
            ),
            validatorSet.members(3).id,
          )
          assertEquals(turnover.newView.window, HotStuffWindow(chainId, 3L, 4L, validatorSet.hash))
          assertEquals(turnover.newView.nextLeader, validatorSet.members(0).id)
          assertEquals(round1.proposal.block.height, BlockHeight.unsafeFromLong(1L))
          assertEquals(round2.proposal.block.height, BlockHeight.unsafeFromLong(2L))
          assertEquals(round3.proposal.block.height, BlockHeight.unsafeFromLong(3L))
          assertEquals(round3.proposal.window.view, HotStuffView.unsafeFromLong(4L))
          assert(snapshots.forall(_.proposals.contains(round1.proposal.proposalId)))
          assert(snapshots.forall(_.proposals.contains(round2.proposal.proposalId)))
          assert(snapshots.forall(_.proposals.contains(round3.proposal.proposalId)))
          assert(snapshots.forall(_.timeoutCertificates.contains(turnover.timeoutCertificate.subject)))
          assert(snapshots.forall(_.newViews.contains(turnover.newView.newViewId)))
          assertEquals(
            bootstrapResult.map(_.anchor.proposal.proposalId),
            Right(round1.proposal.proposalId),
          )
          assertEquals(
            bootstrapResult.map(_.forwardCatchUp.applied.map(_.proposalId)),
            Right(Vector(round2.proposal.proposalId, round3.proposal.proposalId)),
          )
          assertEquals(
            bootstrapResult.map(_.forwardCatchUp.voteReadiness),
            Right(BootstrapVoteReadiness.Ready),
          )
          assertEquals(readyDiagnostics.phase, BootstrapPhase.Ready)
          assert(readyDiagnostics.retryAttempts <= 3)
          completedBackfill.historicalBackfill match
            case HistoricalBackfillStatus.Completed(reason, progress) =>
              assertEquals(reason, "genesisReached")
              assertEquals(progress.nextBeforeHeight, BlockHeight.Genesis)
            case other =>
              fail("expected completed historical backfill but saw " + other.toString)
          assertEquals(
            archivedBeforeRestart.map(_.proposal.proposalId),
            Vector(genesis.proposalId),
          )
          assertEquals(
            restartedArchive.map(_.proposal.proposalId),
            Vector(genesis.proposalId),
          )

  test(
    "same-validator relocation smoke fences the old holder and resumes quorum on the pre-provisioned audit node",
  ):
    tempDirResource.use: root =>
      val genesis = genesisProposal("61")

      validatorNodesResource(root, initialHolders, validatorNodeSpecs.take(3)).use: stableNodes =>
        val nodeById = stableNodes.map(node => node.localNodeId -> node).toMap
        val nodeDRoot = root.resolve(nodeD)
        val nodeERoot = root.resolve(nodeE)
        val fencedNodeDRoot = root.resolve("node-d-fenced")
        val relocatedNodeERoot = root.resolve("node-e-relocated")
        for
          initialState <- launchedNodeResource(
            localNodeId = nodeE,
            role = LocalNodeRole.Audit,
            holders = initialHolders,
            localKeys = Map.empty,
            storageRoot = nodeERoot,
          ).use: _ =>
            launchedNodeResource(
              localNodeId = nodeD,
              role = LocalNodeRole.Validator,
              holders = initialHolders,
              localKeys = Map(validatorSet.members(3).id -> validatorKeys(3)),
              storageRoot = nodeDRoot,
            ).use: initialNodeD =>
              val initialNodes = stableNodes :+ initialNodeD
              for
                _ <- initialNodes.traverse_(seedBootstrapLocal(_, Vector(genesis), Vector.empty))
                initialLinks <- openDirectedMesh(initialNodes)
                _ <- drainNetwork(initialLinks)
                genesisQc = qcFor(genesis, Vector(0, 1, 2))
                round1 <- driveProposalRound(
                  links = initialLinks,
                  leader = nodeById(nodeB),
                  proposer = validatorSet.members(1).id,
                  justify = genesisQc,
                  parentBlockId = Some(genesis.targetBlockId),
                  height = 1L,
                  view = 1L,
                  stateRoot = StateRoot(hex("6201")),
                  bodySeed = "6201",
                  voters = Vector(
                    nodeById(nodeA) -> validatorSet.members(0).id,
                    nodeById(nodeB) -> validatorSet.members(1).id,
                    nodeById(nodeC) -> validatorSet.members(2).id,
                  ),
                  emitOffsetSeconds = 10L,
                )
                round2 <- driveProposalRound(
                  links = initialLinks,
                  leader = nodeById(nodeC),
                  proposer = validatorSet.members(2).id,
                  justify = round1.qc,
                  parentBlockId = Some(round1.proposal.targetBlockId),
                  height = 2L,
                  view = 2L,
                  stateRoot = StateRoot(hex("6202")),
                  bodySeed = "6202",
                  voters = Vector(
                    nodeById(nodeA) -> validatorSet.members(0).id,
                    nodeById(nodeB) -> validatorSet.members(1).id,
                    initialNodeD -> validatorSet.members(3).id,
                  ),
                  emitOffsetSeconds = 20L,
                )
              yield (
                round2,
                initialLinks.filter(link =>
                  link.from.localNodeId =!= nodeD && link.to.localNodeId =!= nodeD,
                ),
              )
          _ <- IO.blocking(copyRecursively(nodeERoot, relocatedNodeERoot))
          relocationAssertions <- launchedNodeResource(
            localNodeId = nodeD,
            role = LocalNodeRole.Validator,
            holders = relocatedHolders,
            localKeys = Map(validatorSet.members(3).id -> validatorKeys(3)),
            storageRoot = fencedNodeDRoot,
          ).use: fencedNodeD =>
            launchedNodeResource(
              localNodeId = nodeE,
              role = LocalNodeRole.Validator,
              holders = relocatedHolders,
              localKeys = Map(validatorSet.members(3).id -> validatorKeys(3)),
              storageRoot = relocatedNodeERoot,
            ).use: relocatedNodeE =>
              val (round2, survivingLinks) = initialState
              for
                fencedVoteAttempt <- fencedNodeD.bootstrap.consensus.emitVote(
                  validatorSet.members(3).id,
                  round2.proposal,
                  tsAt(30L),
                )
                relocationLinks <- openDirectedLinks(stableNodes, Vector(relocatedNodeE))
                relocatedOutbound <- openDirectedLinks(Vector(relocatedNodeE), stableNodes)
                allLinks = survivingLinks ++ relocationLinks ++ relocatedOutbound
                round3 <- driveProposalRound(
                  links = allLinks,
                  leader = relocatedNodeE,
                  proposer = validatorSet.members(3).id,
                  justify = round2.qc,
                  parentBlockId = Some(round2.proposal.targetBlockId),
                  height = 3L,
                  view = 3L,
                  stateRoot = StateRoot(hex("6303")),
                  bodySeed = "6303",
                  voters = Vector(
                    nodeById(nodeA) -> validatorSet.members(0).id,
                    nodeById(nodeB) -> validatorSet.members(1).id,
                    relocatedNodeE -> validatorSet.members(3).id,
                  ),
                  emitOffsetSeconds = 40L,
                )
                round4 <- driveProposalRound(
                  links = allLinks,
                  leader = nodeById(nodeA),
                  proposer = validatorSet.members(0).id,
                  justify = round3.qc,
                  parentBlockId = Some(round3.proposal.targetBlockId),
                  height = 4L,
                  view = 4L,
                  stateRoot = StateRoot(hex("6404")),
                  bodySeed = "6404",
                  voters = Vector(
                    nodeById(nodeA) -> validatorSet.members(0).id,
                    nodeById(nodeB) -> validatorSet.members(1).id,
                    relocatedNodeE -> validatorSet.members(3).id,
                  ),
                  emitOffsetSeconds = 50L,
                )
              yield (fencedVoteAttempt, round3, round4)
        yield
          val (fencedVoteAttempt, round3, round4) = relocationAssertions
          assertEquals(
            fencedVoteAttempt.leftMap(_.reason),
            Left("validatorKeyFenced"),
          )
          assertEquals(round3.proposal.proposer, validatorSet.members(3).id)
          assertEquals(round3.proposal.window.view, HotStuffView.unsafeFromLong(3L))
          assertEquals(round4.proposal.block.height, BlockHeight.unsafeFromLong(4L))
          assert(round4.qc.votes.exists(_.voter === validatorSet.members(3).id))

  private def driveProposalRound(
      links: Vector[MeshLink],
      leader: LaunchedNode,
      proposer: ValidatorId,
      justify: QuorumCertificate,
      parentBlockId: Option[BlockId],
      height: Long,
      view: Long,
      stateRoot: StateRoot,
      bodySeed: String,
      voters: Vector[(LaunchedNode, ValidatorId)],
      emitOffsetSeconds: Long,
  ): IO[RoundResult] =
    val window = HotStuffWindow(chainId, height, view, validatorSet.hash)

    for
      _ <- IO.raiseUnless(
        HotStuffPacemaker.deterministicLeader(window, validatorSet) === proposer,
      )(new IllegalStateException("unexpected deterministic leader"))
      proposalEvent <- leader.bootstrap.consensus.emitProposal(
        proposer = proposer,
        block = block(
          parent = parentBlockId,
          height = height,
          stateRoot = stateRoot,
          bodyRoot = BodyRoot(hex(bodySeed)),
          at = tsAt(emitOffsetSeconds),
        ),
        txSet = ProposalTxSet.empty,
        window = window,
        justify = justify,
        ts = tsAt(emitOffsetSeconds),
      ).flatMap(unwrapPolicy)
      _ <- applyLocalEvent(leader, proposalEvent)
      proposal = proposalPayload(proposalEvent)
      _ <- drainNetwork(links)
      _ <- voters.zipWithIndex.traverse_ { case ((node, voter), index) =>
        node.bootstrap.consensus
          .emitVote(voter, proposal, tsAt(emitOffsetSeconds + index.toLong + 1L))
          .flatMap(unwrapPolicy)
          .flatMap(applyLocalEvent(node, _))
          .void
      }
      _ <- drainNetwork(links)
      qc <- lookupQc(leader, proposal.proposalId)
    yield RoundResult(proposal = proposal, qc = qc)

  private def driveTimeoutTurnover(
      links: Vector[MeshLink],
      voters: Vector[(LaunchedNode, ValidatorId)],
      newViewEmitter: (LaunchedNode, ValidatorId),
      height: Long,
      timeoutView: Long,
      highestKnownQc: QuorumCertificate,
      emitOffsetSeconds: Long,
  ): IO[TimeoutTurnover] =
    val timeoutWindow =
      HotStuffWindow(chainId, height, timeoutView, validatorSet.hash)

    for
      emittedTimeoutVotes <- voters.zipWithIndex.traverse { case ((node, voter), index) =>
        node.bootstrap.consensus
          .emitTimeoutVote(
            voter = voter,
            window = timeoutWindow,
            highestKnownQc = highestKnownQc,
            ts = tsAt(emitOffsetSeconds + index.toLong),
          )
          .flatMap(unwrapPolicy)
          .flatTap(applyLocalEvent(node, _))
          .map(timeoutVotePayload)
      }
      _ <- drainNetwork(links)
      timeoutCertificate <- IO.fromEither(
        TimeoutCertificateAssembler
          .assemble(
            TimeoutVoteSubject(timeoutWindow, highestKnownQc.subject),
            emittedTimeoutVotes,
            validatorSet,
          )
          .leftMap(failure => new IllegalStateException(failure.reason)),
      )
      (emitterNode, emitterId) = newViewEmitter
      newViewEvent <- emitterNode.bootstrap.consensus.emitNewView(
        sender = emitterId,
        highestKnownQc = highestKnownQc,
        timeoutCertificate = timeoutCertificate,
        ts = tsAt(emitOffsetSeconds + 10L),
      ).flatMap(unwrapPolicy)
      _ <- applyLocalEvent(emitterNode, newViewEvent)
      newView = newViewPayload(newViewEvent)
      _ <- drainNetwork(links)
    yield
      TimeoutTurnover(
        timeoutVotes = emittedTimeoutVotes,
        timeoutCertificate = timeoutCertificate,
        newView = newView,
      )

  private def validatorNodesResource(
      root: Path,
      holders: Vector[ValidatorKeyHolder],
      nodeSpecs: Vector[(String, Int)],
  ): Resource[IO, Vector[LaunchedNode]] =
    nodeSpecs.traverse: (localNodeId, validatorIndex) =>
      launchedNodeResource(
        localNodeId = localNodeId,
        role = LocalNodeRole.Validator,
        holders = holders,
        localKeys =
          Map(validatorSet.members(validatorIndex).id -> validatorKeys(validatorIndex)),
        storageRoot = root.resolve(localNodeId),
      )

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  private def launchedNodeResource(
      localNodeId: String,
      role: LocalNodeRole,
      holders: Vector[ValidatorKeyHolder],
      localKeys: Map[ValidatorId, org.sigilaris.core.crypto.KeyPair],
      storageRoot: Path,
      bootstrapTransport: Option[HotStuffBootstrapTransportServices[IO]] = None,
      historicalSyncEnabled: Boolean = true,
  ): Resource[IO, LaunchedNode] =
    val layout = StorageLayout.fromRoot(storageRoot)
    val config = withTestTransportPeerSecrets(
      configFor(
        localNodeId = localNodeId,
        role = role,
        holders = holders,
        localKeys = localKeys,
        historicalSyncEnabled = historicalSyncEnabled,
      ),
    )

    HotStuffRuntimeBootstrap
      .fromConfig[IO](
        config = config,
        clock = GossipClock.constant[IO](startedAt),
        storageLayout = layout,
        bootstrapTransport = bootstrapTransport,
      )
      .evalMap(either =>
        IO.fromEither(either.leftMap(new IllegalArgumentException(_))),
      )
      .flatMap: bootstrap =>
        ArmeriaServer
          .resource[IO](
            ArmeriaServerConfig(port = 0),
            HotStuffGossipArmeriaAdapter.endpoints[IO](bootstrap),
          )
          .map: server =>
            LaunchedNode(
              localNodeId = bootstrap.topology.localNodeIdentity.value,
              bootstrap = bootstrap,
              storageLayout = layout,
              baseUri = s"http://127.0.0.1:${server.activeLocalPort()}",
            )

  private def openDirectedMesh(
      nodes: Vector[LaunchedNode],
  ): IO[Vector[MeshLink]] =
    openDirectedLinks(nodes, nodes)

  private def openDirectedLinks(
      fromNodes: Vector[LaunchedNode],
      toNodes: Vector[LaunchedNode],
  ): IO[Vector[MeshLink]] =
    fromNodes.traverse: from =>
      toNodes
        .filter(_.localNodeId =!= from.localNodeId)
        .traverse(openOutboundViaHttp(from, _))
    .map(_.flatten)

  private def openOutboundViaHttp(
      from: LaunchedNode,
      to: LaunchedNode,
  ): IO[MeshLink] =
    for
      proposalEither <- from.bootstrap.runtime.startOutbound(
        PeerIdentity.unsafe(to.localNodeId),
        subscription,
      )
      proposal <- IO.fromEither(
        proposalEither.leftMap(rejection =>
          new IllegalStateException(rejection.reason),
        ),
      )
      response <- to.postJson(
        "/gossip/session/open",
        toProposalWire(proposal).asJson.noSpaces,
        authenticatedPeer = Some(from.localNodeId),
      )
      ackWire <- IO.fromEither(
        decode[SessionOpenAckWire](response.body).leftMap(new IllegalStateException(_)),
      )
      ack <- IO.fromEither(toAck(ackWire).leftMap(new IllegalArgumentException(_)))
      applied <- from.bootstrap.runtime.applyHandshakeAck(ack)
      _ <- IO.fromEither(
        applied.leftMap(rejection => new IllegalStateException(rejection.reason)),
      )
    yield
      MeshLink(
        from = from,
        to = to,
        binding =
          BootstrapSessionBinding(
            peer = PeerIdentity.unsafe(to.localNodeId),
            sessionId = proposal.sessionId,
            authenticatedPeer = proposal.initiator,
          ),
      )

  private def drainNetwork(
      links: Vector[MeshLink],
      maxRounds: Int = 40,
  ): IO[Unit] =
    def loop(
        roundsRemaining: Int,
        pending: Int,
    ): IO[Unit] =
      if roundsRemaining <= 0 || pending <= 0 then
        IO.unit
      else
        links
          .traverse(pollAndApply)
          .map(_.sum)
          .flatMap(total => loop(roundsRemaining - 1, total))

    links.traverse(pollAndApply).map(_.sum).flatMap(total => loop(maxRounds - 1, total))

  private def pollAndApply(
      link: MeshLink,
  ): IO[Int] =
    for
      response <- link.from.postJson(
        s"/gossip/events/${link.binding.sessionId.value}",
        EventRequestWire("poll").asJson.noSpaces,
        authenticatedPeer = Some(link.to.localNodeId),
      )
      _ <- if response.status == 200 then IO.unit
      else
        IO.raiseError(
          new IllegalStateException(
            s"unexpected event poll status for ${link.from.localNodeId}->${link.to.localNodeId}: ${response.status}",
          ),
        )
      messages <- decodeBinaryMessages(response.bodyBytes)
      receiveResult <- link.to.bootstrap.runtime.receiveEvents(
        link.binding.sessionId,
        messages,
      )
      _ <- IO.fromEither(
        receiveResult.leftMap(rejection =>
          new IllegalStateException(rejection.reason),
        ),
      )
    yield messages.collect { case EventStreamMessage.Event(_) => 1 }.sum

  private def decodeBinaryMessages(
      body: Array[Byte],
  ): IO[Vector[EventStreamMessage[HotStuffGossipArtifact]]] =
    IO.fromEither(
      BinaryEventStreamCodec
        .decode[HotStuffGossipArtifact](body)
        .leftMap(new IllegalStateException(_)),
    ).flatMap(_.traverse(toEventStreamMessage))

  private def toEventStreamMessage(
      envelope: EventEnvelopeWire[HotStuffGossipArtifact],
  ): IO[EventStreamMessage[HotStuffGossipArtifact]] =
    envelope.kind match
      case "event" =>
        IO.fromEither:
          envelope.event
            .toRight(new IllegalStateException("missing event payload"))
            .flatMap: wire =>
              for
                chain <- ChainId.parse(wire.chainId).leftMap(new IllegalStateException(_))
                topic <- GossipTopic.parse(wire.topic).leftMap(new IllegalStateException(_))
                id <- StableArtifactId.fromHex(wire.id).leftMap(new IllegalStateException(_))
                cursor <- CursorToken.decodeBase64Url(wire.cursor).leftMap(new IllegalStateException(_))
              yield EventStreamMessage.Event(
                GossipEvent(
                  chainId = chain,
                  topic = topic,
                  id = id,
                  cursor = cursor,
                  ts = Instant.ofEpochMilli(wire.ts),
                  payload = wire.payload,
                ),
              )
      case "keepAlive" =>
        IO.fromEither:
          for
            sessionId <- DirectionalSessionId
              .parse(envelope.sessionId)
              .leftMap(new IllegalStateException(_))
            at <- envelope.atEpochMs.toRight(
              new IllegalStateException("keepAlive missing timestamp"),
            )
          yield EventStreamMessage.KeepAlive(sessionId, Instant.ofEpochMilli(at))
      case "rejection" =>
        val detail =
          envelope.rejection
            .map(rejection => s"${rejection.rejectionClass}:${rejection.reason}")
            .getOrElse("unknown")
        IO.raiseError(new IllegalStateException("unexpected event-stream rejection: " + detail))
      case other =>
        IO.raiseError(new IllegalStateException("unknown event envelope kind: " + other))

  private def seedBootstrapLocal(
      node: LaunchedNode,
      proposals: Vector[Proposal],
      snapshotNodes: Vector[SnapshotTrieNode],
  ): IO[Unit] =
    (
      IO.fromOption(node.bootstrap.consensus.inMemorySource)(
        new IllegalStateException("missing in-memory source"),
      ),
      IO.fromOption(node.bootstrap.consensus.inMemorySink)(
        new IllegalStateException("missing in-memory sink"),
      ),
      IO.fromOption(node.bootstrap.consensus.bootstrapLifecycle)(
        new IllegalStateException("missing bootstrap lifecycle"),
      ),
    ).tupled.flatMap: (source, sink, lifecycle) =>
      proposals.zipWithIndex.traverse_ { case (proposal, index) =>
        source
          .append(
            HotStuffGossipArtifact.ProposalArtifact(proposal),
            tsAt(index.toLong),
          )
          .flatMap: event =>
            sink.applyEvent(event).flatMap:
              case Left(rejection) =>
                IO.raiseError(new IllegalStateException(rejection.reason))
              case Right(_) =>
                IO.unit
      } *> lifecycle.nodeStore.putAll(snapshotNodes)

  private def sinkSnapshot(
      node: LaunchedNode,
  ): IO[InMemoryHotStuffSinkSnapshot] =
    IO
      .fromOption(node.bootstrap.consensus.inMemorySink)(
        new IllegalStateException("missing in-memory sink"),
      )
      .flatMap(_.snapshot)

  private def applyLocalEvent(
      node: LaunchedNode,
      event: GossipEvent[HotStuffGossipArtifact],
  ): IO[Unit] =
    IO
      .fromOption(node.bootstrap.consensus.inMemorySink)(
        new IllegalStateException("missing in-memory sink"),
      )
      .flatMap(_.applyEvent(event))
      .flatMap:
        case Left(rejection) =>
          IO.raiseError(new IllegalStateException(rejection.reason))
        case Right(_) =>
          IO.unit

  private def lookupQc(
      node: LaunchedNode,
      proposalId: ProposalId,
  ): IO[QuorumCertificate] =
    sinkSnapshot(node).flatMap(snapshot =>
      IO.fromOption(snapshot.qcs.get(proposalId))(
        new IllegalStateException("missing QC for " + proposalId.toHexLower),
      ),
    )

  private def bootstrapTransportFor(
      localNodeId: String,
      remotes: Vector[LaunchedNode],
  ): HotStuffBootstrapTransportServices[IO] =
    HotStuffBootstrapHttpTransport.services[IO](
      peerBaseUris =
        remotes
          .map(node => PeerIdentity.unsafe(node.localNodeId) -> node.baseUri)
          .toMap,
      transportAuth = StaticPeerTransportAuth.testing(
        topologyFor(localNodeId),
      ),
      proposalCatchUpReadiness = Some(ProposalCatchUpReadiness.ready[IO]),
    )

  private def withArchive[A](
      storageLayout: StorageLayout,
  )(
      use: HistoricalProposalArchive[IO] => IO[A],
  ): IO[A] =
    Resource
      .make(HistoricalProposalArchive.swaydb[IO](storageLayout))(_.close)
      .use(use)

  private def configFor(
      localNodeId: String,
      role: LocalNodeRole,
      holders: Vector[ValidatorKeyHolder],
      localKeys: Map[ValidatorId, org.sigilaris.core.crypto.KeyPair],
      historicalSyncEnabled: Boolean,
  ): Config =
    val roleValue =
      role match
        case LocalNodeRole.Validator => "validator"
        case LocalNodeRole.Audit     => "audit"

    ConfigFactory.parseString(
      s"""
         |sigilaris.node.gossip.peers {
         |  local-node-identity = "$localNodeId"
         |  known-peers = [${allPeers.filterNot(_ === localNodeId).map(peer => s""""$peer"""").mkString(", ")}]
         |  direct-neighbors = [${allPeers.filterNot(_ === localNodeId).map(peer => s""""$peer"""").mkString(", ")}]
         |}
         |
         |sigilaris.node.consensus.hotstuff {
         |  local-role = "$roleValue"
         |  historical-sync-enabled = $historicalSyncEnabled
         |  validators = [
         |${validatorSetEntries("    ")}
         |  ]
         |  key-holders = [
         |${holderEntries(holders, "    ")}
         |  ]
         |  local-signers = [
         |${signerEntries(localKeys, "    ")}
         |  ]
         |}
         |""".stripMargin,
    )

  private def withTestTransportPeerSecrets(
      config: Config,
  ): Config =
    Try(StaticPeerTopologyConfig.load(config)).toOption match
      case Some(Right(topology)) =>
        val peerSecrets =
          (topology.knownPeers + topology.localNodeIdentity).toVector
            .sortBy(_.value)
            .map(peer =>
              s"""    "${peer.value}" = "sigilaris-test-secret:${peer.value}"""",
            )
            .mkString("\n")
        ConfigFactory
          .parseString(
            s"""
               |sigilaris.node.gossip.peers.transport-auth.peer-secrets {
               |$peerSecrets
               |}
               |""".stripMargin,
          )
          .withFallback(config)
      case _ =>
        config

  private def topologyFor(
      localNodeId: String,
  ): StaticPeerTopology =
    requireEither(
      StaticPeerTopology.parse(
        localNodeIdentity = localNodeId,
        knownPeers = allPeers.filterNot(_ === localNodeId).toList,
        directNeighbors = allPeers.filterNot(_ === localNodeId).toList,
      ),
      s"failed to build test topology for $localNodeId",
    )

  private def initialHolders: Vector[ValidatorKeyHolder] =
    Vector(
      ValidatorKeyHolder(validatorSet.members(0).id, PeerIdentity.unsafe(nodeA), ValidatorKeyHolderStatus.Active),
      ValidatorKeyHolder(validatorSet.members(1).id, PeerIdentity.unsafe(nodeB), ValidatorKeyHolderStatus.Active),
      ValidatorKeyHolder(validatorSet.members(2).id, PeerIdentity.unsafe(nodeC), ValidatorKeyHolderStatus.Active),
      ValidatorKeyHolder(validatorSet.members(3).id, PeerIdentity.unsafe(nodeD), ValidatorKeyHolderStatus.Active),
    )

  private def relocatedHolders: Vector[ValidatorKeyHolder] =
    Vector(
      ValidatorKeyHolder(validatorSet.members(0).id, PeerIdentity.unsafe(nodeA), ValidatorKeyHolderStatus.Active),
      ValidatorKeyHolder(validatorSet.members(1).id, PeerIdentity.unsafe(nodeB), ValidatorKeyHolderStatus.Active),
      ValidatorKeyHolder(validatorSet.members(2).id, PeerIdentity.unsafe(nodeC), ValidatorKeyHolderStatus.Active),
      ValidatorKeyHolder(validatorSet.members(3).id, PeerIdentity.unsafe(nodeD), ValidatorKeyHolderStatus.Fenced),
      ValidatorKeyHolder(validatorSet.members(3).id, PeerIdentity.unsafe(nodeE), ValidatorKeyHolderStatus.Active),
    )

  private def snapshotGraph(
      seed: String,
  ): SnapshotGraph =
    val leftLeaf =
      MerkleTrieNode.leaf(
        ByteVector.empty.toNibbles,
        requireEither(
          ByteVector.fromHexDescriptive(seed + "aa"),
          s"invalid snapshot left leaf hex for seed $seed",
        ),
      )
    val rightLeaf =
      MerkleTrieNode.leaf(
        ByteVector.empty.toNibbles,
        requireEither(
          ByteVector.fromHexDescriptive(seed + "bb"),
          s"invalid snapshot right leaf hex for seed $seed",
        ),
      )
    val leftHash = leftLeaf.toHash
    val rightHash = rightLeaf.toHash
    val children =
      MerkleTrieNode.Children.empty
        .updateChild(0, Some(leftHash))
        .updateChild(1, Some(rightHash))
    val root =
      MerkleTrieNode.branch(ByteVector.empty.toNibbles, children)
    val rootHash = root.toHash

    SnapshotGraph(
      rootHash = rootHash,
      rootNode = SnapshotTrieNode(rootHash, root),
      leftNode = SnapshotTrieNode(leftHash, leftLeaf),
      rightNode = SnapshotTrieNode(rightHash, rightLeaf),
    )

  private def genesisProposal(
      seed: String,
  ): Proposal =
    val bootstrap = bootstrapQc(seed)
    val genesisBlock =
      block(
        parent = None,
        height = 0L,
        stateRoot = StateRoot(hex(seed + "10")),
        bodyRoot = BodyRoot(hex(seed + "11")),
        at = startedAt,
      )
    requireEither(
      Proposal.sign(
        UnsignedProposal(
          window = HotStuffWindow(chainId, 0L, 0L, validatorSet.hash),
          proposer = validatorSet.members(0).id,
          targetBlockId = BlockHeader.computeId(genesisBlock),
          block = genesisBlock,
          txSet = ProposalTxSet.empty,
          justify = bootstrap,
        ),
        validatorKeys(0),
      ),
      s"failed to sign genesis proposal for seed $seed",
    )

  private def bootstrapQc(
      seed: String,
  ): QuorumCertificate =
    val subject = QuorumCertificateSubject(
      window = HotStuffWindow(chainId, 0L, 0L, validatorSet.hash),
      proposalId = ProposalId(hex(seed + "01")),
      blockId = BlockId(hex(seed + "02")),
    )
    requireEither(
      QuorumCertificateAssembler.assemble(
        subject,
        quorumVotes(subject.window, subject.proposalId, Vector(0, 1, 2)),
        validatorSet,
      ),
      s"failed to assemble bootstrap QC for seed $seed",
    )

  private def qcFor(
      proposal: Proposal,
      voters: Vector[Int],
  ): QuorumCertificate =
    requireEither(
      QuorumCertificateAssembler.assemble(
        QuorumCertificateSubject(
          window = proposal.window,
          proposalId = proposal.proposalId,
          blockId = proposal.targetBlockId,
        ),
        quorumVotes(proposal.window, proposal.proposalId, voters),
        validatorSet,
      ),
      s"failed to assemble QC for proposal ${proposal.proposalId.toHexLower}",
    )

  private def quorumVotes(
      window: HotStuffWindow,
      proposalId: ProposalId,
      voters: Vector[Int],
  ): Vector[Vote] =
    voters.map: index =>
      requireEither(
        Vote.sign(
          UnsignedVote(
            window = window,
            voter = validatorSet.members(index).id,
            targetProposalId = proposalId,
          ),
          validatorKeys(index),
        ),
        s"failed to sign vote for ${validatorSet.members(index).id.value} on ${proposalId.toHexLower}",
      )

  private def block(
      parent: Option[BlockId],
      height: Long,
      stateRoot: StateRoot,
      bodyRoot: BodyRoot,
      at: Instant,
  ): BlockHeader =
    BlockHeader(
      parent = parent,
      height = BlockHeight.unsafeFromLong(height),
      stateRoot = stateRoot,
      bodyRoot = bodyRoot,
      timestamp = BlockTimestamp.unsafeFromEpochMillis(at.toEpochMilli),
    )

  private def proposalPayload(
      event: GossipEvent[HotStuffGossipArtifact],
  ): Proposal =
    event.payload match
      case HotStuffGossipArtifact.ProposalArtifact(proposal) => proposal
      case _ =>
        throw new IllegalStateException("expected proposal payload")

  private def timeoutVotePayload(
      event: GossipEvent[HotStuffGossipArtifact],
  ): TimeoutVote =
    event.payload match
      case HotStuffGossipArtifact.TimeoutVoteArtifact(timeoutVote) =>
        timeoutVote
      case _ =>
        throw new IllegalStateException("expected timeout vote payload")

  private def newViewPayload(
      event: GossipEvent[HotStuffGossipArtifact],
  ): NewView =
    event.payload match
      case HotStuffGossipArtifact.NewViewArtifact(newView) => newView
      case _ =>
        throw new IllegalStateException("expected new-view payload")

  private def unwrapPolicy[A](
      result: Either[HotStuffPolicyViolation, A],
  ): IO[A] =
    IO.fromEither(
      result.leftMap(rejection => new IllegalStateException(rejection.reason)),
    )

  private def tsAt(
      offsetSeconds: Long,
  ): Instant =
    startedAt.plusSeconds(offsetSeconds)

  private def hex(
      value: String,
  ): UInt256 =
    requireEither(UInt256.fromHex(value.padTo(64, '0')), s"invalid test hex value: $value")

  private def requireEither[A](
      result: Either[?, A],
      context: String,
  ): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"$context: $error")

  private def validatorSetEntries(
      indent: String,
  ): String =
    validatorSet.members
      .map(member =>
        s"""$indent{ id = "${member.id.value}", public-key = "${member.publicKey.toBytes.toHex}" }""",
      )
      .mkString(",\n")

  private def holderEntries(
      holders: Vector[ValidatorKeyHolder],
      indent: String,
  ): String =
    holders
      .map: holder =>
        val status =
          holder.status match
            case ValidatorKeyHolderStatus.Active => "active"
            case ValidatorKeyHolderStatus.Fenced => "fenced"
        s"""$indent{ validator-id = "${holder.validatorId.value}", holder = "${holder.holder.value}", status = "$status" }"""
      .mkString(",\n")

  private def signerEntries(
      localKeys: Map[ValidatorId, org.sigilaris.core.crypto.KeyPair],
      indent: String,
  ): String =
    localKeys.toVector
      .sortBy(_._1.value)
      .map: (validatorId, keyPair) =>
        s"""$indent{ validator-id = "${validatorId.value}", private-key = "${keyPair.privateKey.toHexLower}" }"""
      .mkString(",\n")

  private def toProposalWire(
      proposal: SessionOpenProposal,
  ): SessionOpenProposalWire =
    SessionOpenProposalWire(
      sessionId = proposal.sessionId.value,
      peerCorrelationId = proposal.peerCorrelationId.value,
      initiator = proposal.initiator.value,
      acceptor = proposal.acceptor.value,
      subscriptions =
        proposal.subscriptions.values.toVector.map(entry =>
          ChainTopicWire(entry.chainId.value, entry.topic.value),
        ),
      heartbeatIntervalMs = proposal.heartbeatInterval.map(_.toMillis),
      livenessTimeoutMs = proposal.livenessTimeout.map(_.toMillis),
      maxControlRetryIntervalMs = proposal.maxControlRetryInterval.map(_.toMillis),
    )

  private def toAck(
      wire: SessionOpenAckWire,
  ): Either[String, SessionOpenAck] =
    for
      sessionId <- DirectionalSessionId.parse(wire.sessionId)
      peerCorrelationId <- PeerCorrelationId.parse(wire.peerCorrelationId)
      initiator <- PeerIdentity.parse(wire.initiator)
      acceptor <- PeerIdentity.parse(wire.acceptor)
      subscriptions <- wire.subscriptions.toVector
        .traverse: entry =>
          for
            chainId <- ChainId.parse(entry.chainId)
            topic <- GossipTopic.parse(entry.topic)
          yield ChainTopic(chainId, topic)
        .map(_.toSet)
        .flatMap(SessionSubscription.fromSet)
    yield SessionOpenAck(
      sessionId = sessionId,
      peerCorrelationId = peerCorrelationId,
      initiator = initiator,
      acceptor = acceptor,
      subscriptions = subscriptions,
      negotiated = NegotiatedSessionParameters(
        heartbeatInterval = Duration.ofMillis(wire.heartbeatIntervalMs),
        livenessTimeout = Duration.ofMillis(wire.livenessTimeoutMs),
        maxControlRetryInterval = Duration.ofMillis(wire.maxControlRetryIntervalMs),
      ),
    )

  private def awaitValue[A](
      effect: IO[A],
      attempts: Int,
      delay: scala.concurrent.duration.FiniteDuration,
  )(
      predicate: A => Boolean,
  ): IO[A] =
    effect.flatMap: value =>
      if predicate(value) then
        IO.pure(value)
      else if attempts <= 1 then
        IO.raiseError(new IllegalStateException("condition not satisfied before timeout"))
      else
        IO.sleep(delay) *> awaitValue(effect, attempts - 1, delay)(predicate)

  private def tempDirResource: Resource[IO, Path] =
    Resource.make(IO.blocking(Files.createTempDirectory("sigilaris-launch-smoke"))) { root =>
      IO.blocking(deleteRecursively(root))
    }

  private def deleteRecursively(
      path: Path,
  ): Unit =
    if Files.exists(path) then
      Using.resource(Files.walk(path)): stream =>
        stream.iterator.asScala.toList.reverse.foreach(Files.deleteIfExists)

  private def copyRecursively(
      source: Path,
      destination: Path,
  ): Unit =
    if Files.exists(destination) then
      deleteRecursively(destination)
    Files.createDirectories(destination)
    Using.resource(Files.walk(source)): stream =>
      stream.iterator.asScala.foreach: current =>
        val relative = source.relativize(current)
        val target   = destination.resolve(relative.toString)
        if Files.isDirectory(current) then
          Files.createDirectories(target)
        else
          Files.createDirectories(target.getParent)
          Files.copy(current, target)

  override def afterAll(): Unit =
    val _ = HistoricalProposalArchive.resetSharedStoresForTesting.attempt.unsafeRunSync()
    super.afterAll()

  private final case class Response(
      status: Int,
      bodyBytes: Array[Byte],
      contentType: Option[String],
  ):
    def body: String =
      String(bodyBytes, StandardCharsets.UTF_8)

  private final case class SnapshotGraph(
      rootHash: MerkleTrieNode.MerkleHash,
      rootNode: SnapshotTrieNode,
      leftNode: SnapshotTrieNode,
      rightNode: SnapshotTrieNode,
  )

  private final case class MeshLink(
      from: LaunchedNode,
      to: LaunchedNode,
      binding: BootstrapSessionBinding,
  )

  private final case class RoundResult(
      proposal: Proposal,
      qc: QuorumCertificate,
  )

  private final case class TimeoutTurnover(
      timeoutVotes: Vector[TimeoutVote],
      timeoutCertificate: TimeoutCertificate,
      newView: NewView,
  )

  private final case class LaunchedNode(
      localNodeId: String,
      bootstrap: HotStuffRuntimeBootstrap[IO],
      storageLayout: StorageLayout,
      baseUri: String,
  ):
    def postJson(
        path: String,
        body: String,
        authenticatedPeer: Option[String] = None,
        transportProof: Option[String] = None,
        autoSignTransportProof: Boolean = true,
        bootstrapCapability: Option[String] = None,
    ): IO[Response] =
      IO.blocking:
        val builder = HttpRequest
          .newBuilder(URI.create(s"$baseUri$path"))
          .header("content-type", "application/json")
        val resolvedProof =
          transportProof.orElse:
            authenticatedPeer
              .filter(_ => autoSignTransportProof)
              .map(peer =>
                signedTransportProof(
                  transportAuth = bootstrap.transportAuth,
                  authenticatedPeer = peer,
                  path = path,
                  body = body,
                ),
              )
        authenticatedPeer.foreach(value =>
          builder.header(authenticatedPeerHeaderName, value),
        )
        resolvedProof.foreach(value =>
          builder.header(transportProofHeaderName, value),
        )
        bootstrapCapability.foreach(value =>
          builder.header(bootstrapCapabilityHeaderName, value),
        )
        val request =
          builder
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = sharedHttpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        Response(
          status = response.statusCode(),
          bodyBytes = response.body(),
          contentType = response.headers().firstValue("content-type").toScala,
        )

  private def signedTransportProof(
      transportAuth: StaticPeerTransportAuth,
      authenticatedPeer: String,
      path: String,
      body: String,
  ): String =
    val peer = PeerIdentity.unsafe(authenticatedPeer)
    requireEither(
      transportAuth
        .secretFor(peer)
        .orElse(StaticPeerTransportAuth.testing(topologyFor(authenticatedPeer)).secretFor(peer))
        .map: secret =>
        val derivedKey =
          deriveTransportProofKey(secret.bytes)
        val message =
          encodeTransportTuple(
            ByteVector.view(transportProofInfo.getBytes(StandardCharsets.UTF_8)),
            ByteVector.view(authenticatedPeer.getBytes(StandardCharsets.UTF_8)),
            ByteVector.view("POST".getBytes(StandardCharsets.UTF_8)),
            ByteVector.view(path.getBytes(StandardCharsets.UTF_8)),
            ByteVector.view(
              MessageDigest
                .getInstance(transportProofSha256)
                .digest(body.getBytes(StandardCharsets.UTF_8)),
            ),
          )
        Base64
          .getUrlEncoder
          .withoutPadding()
          .encodeToString(hmac(derivedKey, message).toArray),
      s"failed to sign transport proof for $authenticatedPeer",
    )

  private def deriveTransportProofKey(
      secret: ByteVector,
  ): ByteVector =
    val prk = hmac(ByteVector.empty, secret)
    hmac(
      key = prk,
      message =
        ByteVector.view(transportProofInfo.getBytes(StandardCharsets.UTF_8)) ++
          ByteVector(1.toByte),
    )

  private def encodeTransportTuple(
      fields: ByteVector*,
  ): ByteVector =
    fields.foldLeft(ByteVector.empty): (acc, field) =>
      acc ++ ByteVector.fromInt(field.size.toInt) ++ field

  private def hmac(
      key: ByteVector,
      message: ByteVector,
  ): ByteVector =
    val instance = Mac.getInstance(transportProofHmacAlgorithm)
    val keyBytes =
      if key.isEmpty then Array.fill[Byte](64)(0)
      else key.toArray
    instance.init(new SecretKeySpec(keyBytes, transportProofHmacAlgorithm))
    ByteVector.view(instance.doFinal(message.toArray))
