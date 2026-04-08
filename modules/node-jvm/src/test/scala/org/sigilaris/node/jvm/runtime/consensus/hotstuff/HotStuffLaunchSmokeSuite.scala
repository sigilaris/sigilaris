package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.io.IOException
import java.net.{ConnectException, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.channels.ClosedChannelException
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
import cats.effect.kernel.Ref
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
                .leftMap(error =>
                  DecodeFailure("proposal artifact decode failed: " + error.msg),
                )
                .map(result =>
                  DecodeResult(
                    HotStuffGossipArtifact.ProposalArtifact(result.value),
                    result.remainder,
                  ),
                )
            case 0x02 =>
              ByteDecoder[Vote]
                .decode(remainder)
                .leftMap(error =>
                  DecodeFailure("vote artifact decode failed: " + error.msg),
                )
                .map(result =>
                  DecodeResult(
                    HotStuffGossipArtifact.VoteArtifact(result.value),
                    result.remainder,
                  ),
                )
            case 0x03 =>
              ByteDecoder[TimeoutVote]
                .decode(remainder)
                .leftMap(error =>
                  DecodeFailure(
                    "timeout vote artifact decode failed: " + error.msg,
                  ),
                )
                .map(result =>
                  DecodeResult(
                    HotStuffGossipArtifact.TimeoutVoteArtifact(result.value),
                    result.remainder,
                  ),
                )
            case 0x04 =>
              ByteDecoder[NewView]
                .decode(remainder)
                .leftMap(error =>
                  DecodeFailure("new-view artifact decode failed: " + error.msg),
                )
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
    "reference launch smoke automatically forms sessions, advances consensus across a stalled leader, bootstraps a newcomer, and preserves archive state across restart",
  ):
    tempDirResource.use: root =>
      val graph = snapshotGraph("31")
      val genesis = genesisProposal("41")
      val round1 = signedProposal(
        proposerIndex = 1,
        parentBlockId = Some(genesis.targetBlockId),
        height = 1L,
        view = 1L,
        stateRoot = StateRoot(graph.rootHash.toUInt256),
        txSeeds = Vector("5101"),
        justify = qcFor(genesis, Vector(0, 1, 2)),
        at = tsAt(10L),
      )
      val round2 = signedProposal(
        proposerIndex = 2,
        parentBlockId = Some(round1.targetBlockId),
        height = 2L,
        view = 2L,
        stateRoot = StateRoot(graph.rootHash.toUInt256),
        txSeeds = Vector("5202"),
        justify = qcFor(round1, Vector(0, 1, 2)),
        at = tsAt(20L),
      )
      val stalledWindow =
        HotStuffWindow(chainId, 3L, 3L, validatorSet.hash)
      val recoveredWindow =
        HotStuffWindow(chainId, 3L, 4L, validatorSet.hash)
      val holders = initialHolders

      validatorNodesResource(root, holders, validatorNodeSpecs.take(3)).use: validators =>
        for
          _ <- validators.traverse_(
            seedBootstrapLocal(
              _,
              Vector(genesis, round1, round2),
              Vector(graph.rootNode, graph.leftNode, graph.rightNode),
            ),
          )
          result <- automaticMeshResource(validators).use: validatorMesh =>
            for
              validatorLinkCount <- validatorMesh.links.map(_.size)
              automatic <- awaitAutomaticConsensusProgress(
                validators = validators,
                mesh = validatorMesh,
                expectedProposalHeights = Set(0L, 1L, 2L, 3L, 4L, 5L),
                stalledWindow = stalledWindow,
                recoveredWindow = recoveredWindow,
              )
              snapshots = automatic.snapshots
              proposalHeights =
                snapshots.map(snapshot =>
                  snapshot.proposals.valuesIterator.map(proposalHeight).toSet,
                )
              qcHeights =
                snapshots.map(snapshot =>
                  snapshot.qcs.valuesIterator.map(qcHeight).toSet,
                )
              primarySnapshot <- IO.fromOption(snapshots.headOption)(
                new IllegalStateException("missing validator snapshot"),
              )
              height3Proposal <- IO.fromOption(
                proposalAtWindow(primarySnapshot, recoveredWindow),
              )(new IllegalStateException("missing recovered height-3 proposal"))
              height4Proposal <- IO.fromOption(
                proposalAtHeight(primarySnapshot, 4L),
              )(new IllegalStateException("missing automatic height-4 proposal"))
              height5Proposal <- IO.fromOption(
                proposalAtHeight(primarySnapshot, 5L),
              )(new IllegalStateException("missing automatic height-5 proposal"))
              auditRoot = root.resolve(nodeE)
              auditRestartRoot = root.resolve("node-e-restart")
              auditBootstrapResult <- launchedNodeResource(
                localNodeId = nodeE,
                role = LocalNodeRole.Audit,
                holders = holders,
                localKeys = Map.empty,
                storageRoot = auditRoot,
                bootstrapPeerBaseUris = bootstrapPeerBaseUris(validators),
              ).use: audit =>
                for
                  validatorNow <- latestClockInstant(validators)
                  _ <- alignClockTo(audit, validatorNow)
                  _ <- validatorMesh.registerNodes(Vector(audit))
                  auditSessions <- validatorMesh.bindingsFrom(audit, validators)
                  _ <- awaitObservedProposalHeights(
                    node = audit,
                    expectedProposalHeights = Set(3L, 4L, 5L),
                  )
                  bootstrapReady <- bootstrapUntilReady(
                    node = audit,
                    sessions = auditSessions,
                    startedAt = startedAt.plusSeconds(90L),
                  )
                yield (auditSessions, bootstrapReady)
              _ <- IO.blocking(copyRecursively(auditRoot, auditRestartRoot))
              restartedArchive <- launchedNodeResource(
                localNodeId = nodeE,
                role = LocalNodeRole.Audit,
                holders = holders,
                localKeys = Map.empty,
                storageRoot = auditRestartRoot,
                bootstrapPeerBaseUris = bootstrapPeerBaseUris(validators),
              ).use: _ =>
                withArchive(StorageLayout.fromRoot(auditRestartRoot))(_.list(chainId))
            yield (
              validatorLinkCount,
              snapshots,
              proposalHeights,
              qcHeights,
              height3Proposal,
              height4Proposal,
              height5Proposal,
              auditBootstrapResult,
              restartedArchive,
            )
        yield
          val (
            validatorLinkCount,
            snapshots,
            proposalHeights,
            qcHeights,
            height3Proposal,
            height4Proposal,
            height5Proposal,
            auditBootstrapResult,
            restartedArchive,
          ) = result
          val (auditSessions, bootstrapReady) =
            auditBootstrapResult
          val bootstrapResult = bootstrapReady.result
          val readyDiagnostics = bootstrapReady.diagnostics
          val expectedAutomaticHeights = Set(0L, 1L, 2L, 3L, 4L, 5L)
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
          assertEquals(validatorLinkCount, 6)
          assertEquals(auditSessions.size, 3)
          assert(
            proposalHeights.forall(expectedAutomaticHeights.subsetOf),
            s"unexpected proposal heights: $proposalHeights",
          )
          assert(
            qcHeights.forall(Set(1L, 2L, 3L, 4L).subsetOf),
            s"unexpected QC heights: $qcHeights",
          )
          assertEquals(height3Proposal.block.height, BlockHeight.unsafeFromLong(3L))
          assertEquals(height3Proposal.window, recoveredWindow)
          assertEquals(height3Proposal.block.parent, Some(round2.targetBlockId))
          assertEquals(height4Proposal.block.height, BlockHeight.unsafeFromLong(4L))
          assertEquals(height5Proposal.block.height, BlockHeight.unsafeFromLong(5L))
          assert(snapshots.forall(_.proposals.contains(round1.proposalId)))
          assert(snapshots.forall(_.proposals.contains(round2.proposalId)))
          assert(snapshots.forall(_.proposals.contains(height3Proposal.proposalId)))
          assert(snapshots.forall(_.proposals.contains(height4Proposal.proposalId)))
          assert(snapshots.forall(_.proposals.contains(height5Proposal.proposalId)))
          assert(snapshots.forall(_.timeoutCertificates.keySet.exists(_.window === stalledWindow)))
          assert(snapshots.forall(_.newViews.valuesIterator.exists(_.window === recoveredWindow)))
          assert(
            snapshots.forall(
              _.newViews.valuesIterator
                .find(_.window === recoveredWindow)
                .exists(_.nextLeader === validatorSet.members(0).id),
            ),
            s"unexpected recovered new-view leaders: ${snapshots.map(_.newViews.valuesIterator.toVector)}",
          )
          assert(
            proposalHeight(bootstrapResult.anchor.proposal) >= 1L &&
              bootstrapResult.forwardCatchUp.applied.nonEmpty &&
              bootstrapResult.forwardCatchUp.applied.forall(proposal =>
                proposalHeight(proposal) > proposalHeight(bootstrapResult.anchor.proposal),
              ) &&
              bootstrapResult.forwardCatchUp.applied.map(proposalHeight).max >= 5L,
            s"unexpected bootstrap result: $bootstrapResult",
          )
          assertEquals(
            bootstrapResult.forwardCatchUp.voteReadiness,
            BootstrapVoteReadiness.Ready,
          )
          assertEquals(readyDiagnostics.phase, BootstrapPhase.Ready)
          assert(bootstrapReady.attempts <= 3)
          assert(readyDiagnostics.retryAttempts <= 3)
          assert(restartedArchive.nonEmpty)
          assert(restartedArchive.exists(_.proposal.proposalId === genesis.proposalId))
          assert(restartedArchive.exists(_.proposal.proposalId === round1.proposalId))
          assert(restartedArchive.exists(_.proposal.proposalId === round2.proposalId))

  test(
    "same-validator relocation smoke fences the old holder and resumes quorum on the pre-provisioned audit node",
  ):
    tempDirResource.use: root =>
      val genesis = genesisProposal("61")
      val round1 = signedProposal(
        proposerIndex = 1,
        parentBlockId = Some(genesis.targetBlockId),
        height = 1L,
        view = 1L,
        stateRoot = StateRoot(hex("6201")),
        txSeeds = Vector("6201"),
        justify = qcFor(genesis, Vector(0, 1, 2)),
        at = tsAt(10L),
      )
      val round2 = signedProposal(
        proposerIndex = 2,
        parentBlockId = Some(round1.targetBlockId),
        height = 2L,
        view = 2L,
        stateRoot = StateRoot(hex("6202")),
        txSeeds = Vector("6202"),
        justify = qcFor(round1, Vector(0, 1, 2)),
        at = tsAt(20L),
      )

      validatorNodesResource(root, initialHolders, validatorNodeSpecs.take(3)).use: stableNodes =>
        val nodeDRoot = root.resolve(nodeD)
        val nodeERoot = root.resolve(nodeE)
        val fencedNodeDRoot = root.resolve("node-d-fenced")
        val relocatedNodeERoot = root.resolve("node-e-relocated")
        for
          _ <- launchedNodeResource(
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
              seedBootstrapLocal(
                initialNodeD,
                Vector(genesis, round1, round2),
                Vector.empty,
              )
          _ <- stableNodes.traverse_(
            seedBootstrapLocal(_, Vector(genesis, round1, round2), Vector.empty),
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
              for
                fencedVoteAttempt <- fencedNodeD.bootstrap.consensus.emitVote(
                  validatorSet.members(3).id,
                  round2,
                  tsAt(30L),
                )
                _ <- seedBootstrapLocal(
                  relocatedNodeE,
                  Vector(genesis, round1, round2),
                  Vector.empty,
                )
                result <- automaticMeshResource(stableNodes :+ relocatedNodeE).use: mesh =>
                  for
                    linkCount <- mesh.links.map(_.size)
                    automatic <- awaitAutomaticProposalProgress(
                      validators = stableNodes :+ relocatedNodeE,
                      mesh = mesh,
                      expectedProposalHeights = Set(0L, 1L, 2L, 3L, 4L),
                    )
                    primarySnapshot <- IO.fromOption(automatic.snapshots.headOption)(
                      new IllegalStateException("missing relocation snapshot"),
                    )
                    height3Proposal <- IO.fromOption(
                      proposalAtWindow(
                        primarySnapshot,
                        HotStuffWindow(chainId, 3L, 3L, validatorSet.hash),
                      ),
                    )(new IllegalStateException("missing relocated height-3 proposal"))
                    height4Proposal <- IO.fromOption(
                      proposalAtHeight(primarySnapshot, 4L),
                    )(new IllegalStateException("missing resumed height-4 proposal"))
                    height3Qc <- IO.fromOption(
                      primarySnapshot.qcs.get(height3Proposal.proposalId),
                    )(new IllegalStateException("missing QC for relocated height-3 proposal"))
                  yield (linkCount, automatic, height3Proposal, height4Proposal, height3Qc)
              yield (fencedVoteAttempt, result)
        yield
          val (fencedVoteAttempt, (linkCount, automatic, height3Proposal, height4Proposal, height3Qc)) =
            relocationAssertions
          val proposalHeights =
            automatic.snapshots.map(snapshot =>
              snapshot.proposals.valuesIterator.map(proposalHeight).toSet,
            )
          assertEquals(
            fencedVoteAttempt.leftMap(_.reason),
            Left("validatorKeyFenced"),
          )
          assertEquals(linkCount, 12)
          assert(
            proposalHeights.forall(Set(0L, 1L, 2L, 3L, 4L).subsetOf),
            s"unexpected proposal heights after relocation: $proposalHeights",
          )
          assert(automatic.snapshots.forall(_.proposals.contains(height3Proposal.proposalId)))
          assert(automatic.snapshots.forall(_.proposals.contains(height4Proposal.proposalId)))
          assert(automatic.snapshots.forall(_.qcs.contains(height3Proposal.proposalId)))
          assertEquals(height3Proposal.proposer, validatorSet.members(3).id)
          assertEquals(height3Proposal.window.view, HotStuffView.unsafeFromLong(3L))
          assertEquals(height4Proposal.block.height, BlockHeight.unsafeFromLong(4L))
          assert(height3Qc.votes.exists(_.voter === validatorSet.members(3).id))

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
      bootstrapPeerBaseUris: Map[String, String] = Map.empty,
      historicalSyncEnabled: Boolean = true,
  ): Resource[IO, LaunchedNode] =
    val layout = StorageLayout.fromRoot(storageRoot)
    val config = withTestTransportPeerSecrets(
      configFor(
        localNodeId = localNodeId,
        role = role,
        holders = holders,
        localKeys = localKeys,
        bootstrapPeerBaseUris = bootstrapPeerBaseUris,
        historicalSyncEnabled = historicalSyncEnabled,
      ),
    )

    Resource
      .eval(TestClock.create(startedAt))
      .flatMap: clock =>
        HotStuffRuntimeBootstrap
          .fromConfig[IO](
            config = config,
            clock = clock,
            storageLayout = layout,
          )
          .evalMap: either =>
            IO.fromEither(either.leftMap(new IllegalArgumentException(_)))
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
                  clock = clock,
                )

  private def ensureDirectedLinks(
      fromNodes: Vector[LaunchedNode],
      toNodes: Vector[LaunchedNode],
      existingLinks: Vector[MeshLink],
  ): IO[Vector[MeshLink]] =
    val existingPairs =
      existingLinks.iterator
        .map(link => (link.from.localNodeId, link.to.localNodeId))
        .toSet
    val missingPairs =
      for
        from <- fromNodes
        to <- toNodes
        if from.localNodeId =!= to.localNodeId
        if !existingPairs.contains((from.localNodeId, to.localNodeId))
      yield (from, to)
    missingPairs
      .traverse: (from, to) =>
        openOutboundViaHttp(from, to)
      .map(existingLinks ++ _)

  private def automaticMeshResource(
      initialNodes: Vector[LaunchedNode],
  ): Resource[IO, AutomaticMesh] =
    Resource
      .make(
        for
          nodesRef <- Ref.of[IO, Vector[LaunchedNode]](Vector.empty)
          linksRef <- Ref.of[IO, Vector[MeshLink]](Vector.empty)
          failureRef <- Ref.of[IO, Option[Throwable]](None)
          mesh = AutomaticMesh(nodesRef, linksRef, failureRef)
          _ <- mesh.registerNodes(initialNodes)
          fiber <- meshBackgroundLoop(mesh).start
        yield (mesh, fiber),
      ) { case (_, fiber) => fiber.cancel }
      .map(_._1)

  private def meshBackgroundLoop(
      mesh: AutomaticMesh,
  ): IO[Unit] =
    def loop: IO[Unit] =
      mesh.pollOnce.attempt.flatMap:
        case Left(error) if isBenignMeshShutdownError(error) =>
          IO.unit
        case Left(error) =>
          mesh.recordFailure(error) *> IO.raiseError(error)
        case Right(_) =>
          IO.sleep(20.millis) *> loop

    loop

  private def isBenignMeshShutdownError(
      error: Throwable,
  ): Boolean =
    Option(error).exists:
      case _: ConnectException       => true
      case _: ClosedChannelException => true
      case io: IOException if Option(io.getMessage).exists(_.contains("GOAWAY received")) =>
        true
      case other                     => isBenignMeshShutdownError(other.getCause)

  private def awaitAutomaticConsensusProgress(
      validators: Vector[LaunchedNode],
      mesh: AutomaticMesh,
      expectedProposalHeights: Set[Long],
      stalledWindow: HotStuffWindow,
      recoveredWindow: HotStuffWindow,
      maxTicks: Int = 12,
  ): IO[AutomaticLaunchProgress] =
    val timeoutStep =
      HotStuffPacemakerPolicy.default.baseTimeout.plusSeconds(1L)

    def progressReached(
        snapshots: Vector[InMemoryHotStuffSinkSnapshot],
    ): Boolean =
      snapshots.forall: snapshot =>
        val heights =
          snapshot.proposals.valuesIterator.map(proposalHeight).toSet
        expectedProposalHeights.subsetOf(heights) &&
          snapshot.timeoutCertificates.keySet.exists(_.window === stalledWindow) &&
          snapshot.newViews.valuesIterator.exists(_.window === recoveredWindow)

    def describeSnapshots(
        snapshots: Vector[InMemoryHotStuffSinkSnapshot],
    ): String =
      snapshots.zipWithIndex
        .map: (snapshot, index) =>
          val heights =
            snapshot.proposals.valuesIterator.map(proposalHeight).toVector.sorted.mkString("[", ",", "]")
          val timeouts =
            snapshot.timeoutCertificates.keySet.iterator
              .map(_.window)
              .toVector
              .sortBy(window =>
                (
                  window.height.toBigNat.toBigInt.longValue,
                  window.view.toBigNat.toBigInt.longValue,
                ),
              )
              .mkString("[", ",", "]")
          val newViews =
            snapshot.newViews.valuesIterator
              .map(_.window)
              .toVector
              .sortBy(window =>
                (
                  window.height.toBigNat.toBigInt.longValue,
                  window.view.toBigNat.toBigInt.longValue,
                ),
              )
              .mkString("[", ",", "]")
          s"node-${index + 1}: proposals=$heights timeoutCertificates=$timeouts newViews=$newViews"
        .mkString("; ")

    def loop(
        ticksRemaining: Int,
    ): IO[AutomaticLaunchProgress] =
      for
        _ <- mesh.assertHealthy
        snapshots <- validators.traverse(sinkSnapshot)
        result <-
          if progressReached(snapshots) then
            IO.pure(AutomaticLaunchProgress(snapshots))
          else if ticksRemaining <= 0 then
            IO.raiseError(
              new IllegalStateException(
                "automatic consensus progress not reached: " + describeSnapshots(snapshots),
              ),
            )
          else
            validators.traverse_(_.clock.advance(timeoutStep)) *>
              IO.sleep(50.millis) *>
              loop(ticksRemaining - 1)
      yield result

    loop(maxTicks)

  private def awaitAutomaticProposalProgress(
      validators: Vector[LaunchedNode],
      mesh: AutomaticMesh,
      expectedProposalHeights: Set[Long],
      attempts: Int = 24,
  ): IO[AutomaticLaunchProgress] =
    def describeSnapshots(
        snapshots: Vector[InMemoryHotStuffSinkSnapshot],
    ): String =
      snapshots.zipWithIndex
        .map: (snapshot, index) =>
          val heights =
            snapshot.proposals.valuesIterator.map(proposalHeight).toVector.sorted.mkString("[", ",", "]")
          s"node-${index + 1}: proposals=$heights"
        .mkString("; ")

    def loop(
        remainingAttempts: Int,
    ): IO[AutomaticLaunchProgress] =
      for
        _ <- mesh.assertHealthy
        snapshots <- validators.traverse(sinkSnapshot)
        result <-
          if snapshots.forall(snapshot =>
              expectedProposalHeights.subsetOf(
                snapshot.proposals.valuesIterator.map(proposalHeight).toSet,
              ),
            )
          then
            IO.pure(AutomaticLaunchProgress(snapshots))
          else if remainingAttempts <= 1 then
            IO.raiseError(
              new IllegalStateException(
                "automatic proposal progress not reached: " + describeSnapshots(snapshots),
              ),
            )
          else
            IO.sleep(50.millis) *> loop(remainingAttempts - 1)
      yield result

    loop(attempts)

  private def awaitObservedProposalHeights(
      node: LaunchedNode,
      expectedProposalHeights: Set[Long],
      attempts: Int = 24,
  ): IO[InMemoryHotStuffSinkSnapshot] =
    def loop(
        remainingAttempts: Int,
    ): IO[InMemoryHotStuffSinkSnapshot] =
      sinkSnapshot(node).flatMap: snapshot =>
        val observedHeights =
          snapshot.proposals.valuesIterator.map(proposalHeight).toSet
        if expectedProposalHeights.subsetOf(observedHeights) then
          IO.pure(snapshot)
        else if remainingAttempts <= 1 then
          IO.raiseError(
            new IllegalStateException(
              s"observed proposal heights not reached for ${node.localNodeId}: $observedHeights",
            ),
          )
        else
          IO.sleep(50.millis) *> loop(remainingAttempts - 1)

    loop(attempts)

  private def bootstrapUntilReady(
      node: LaunchedNode,
      sessions: Vector[BootstrapSessionBinding],
      startedAt: Instant,
      maxAttempts: Int = 3,
  ): IO[BootstrapReadyResult] =
    def loop(
        attempt: Int,
    ): IO[BootstrapReadyResult] =
      for
        liveSnapshot <- sinkSnapshot(node)
        liveProposals = canonicalProposalChain(liveSnapshot.proposals.valuesIterator.toVector)
        bootstrapResult <- node.bootstrap.consensus.bootstrap(
          chainId = chainId,
          sessions = sessions,
          startedAt = startedAt.plusSeconds(attempt.toLong - 1L),
          liveProposals = liveProposals,
        )
        bootstrapReady <- IO.fromEither(
          bootstrapResult.leftMap(error =>
            new IllegalStateException(
              s"bootstrap failed on attempt $attempt: ${error.reason}:${error.detail.getOrElse("")}",
            ),
          ),
        )
        diagnostics <- node.bootstrap.consensus.currentBootstrapDiagnostics
        result <-
          if bootstrapReady.forwardCatchUp.voteReadiness == BootstrapVoteReadiness.Ready &&
              diagnostics.phase == BootstrapPhase.Ready
          then
            IO.pure(
              BootstrapReadyResult(
                result = bootstrapReady,
                diagnostics = diagnostics,
                attempts = attempt,
              ),
            )
          else if attempt >= maxAttempts then
            IO.raiseError(
              new IllegalStateException(
                s"bootstrap did not become ready within $maxAttempts attempts: " +
                  s"readiness=${bootstrapReady.forwardCatchUp.voteReadiness}, " +
                  s"phase=${diagnostics.phase}, " +
                  s"liveProposalHeights=${liveProposals.map(proposalHeight)}, " +
                  s"allObservedHeights=${orderedProposals(liveSnapshot.proposals.valuesIterator.toVector).map(proposalHeight)}",
              ),
            )
          else
            IO.sleep(100.millis) *> loop(attempt + 1)
      yield result

    loop(attempt = 1)

  private def latestClockInstant(
      nodes: Vector[LaunchedNode],
  ): IO[Instant] =
    nodes.traverse(_.clock.now).flatMap: instants =>
      IO.fromOption(instants.sortBy(_.toEpochMilli).lastOption)(
        new IllegalStateException("missing clock source"),
      )

  private def alignClockTo(
      node: LaunchedNode,
      instant: Instant,
  ): IO[Unit] =
    node.clock.now.flatMap: current =>
      if current.isBefore(instant) then
        node.clock.advance(Duration.between(current, instant))
      else IO.unit

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
      maxRounds: Int,
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
      messages <- decodeBinaryMessages(
        body = response.bodyBytes,
        responseStatus = response.status,
        contentType = response.contentType,
        link = link,
      )
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
      responseStatus: Int,
      contentType: Option[String],
      link: MeshLink,
  ): IO[Vector[EventStreamMessage[HotStuffGossipArtifact]]] =
    if body.isEmpty then
      contentType match
        case Some(value)
            if value.startsWith(BinaryEventStreamCodec.MediaType) &&
              responseStatus == 200 =>
          // Poll responses legitimately encode "no pending events" as an empty
          // octet-stream body because BinaryEventStreamCodec emits zero frames
          // as zero bytes.
          Vector.empty[EventStreamMessage[HotStuffGossipArtifact]].pure[IO]
        case _ =>
          IO.raiseError(
            new IllegalStateException(
              s"unexpected empty event poll body for ${link.from.localNodeId}->${link.to.localNodeId}: " +
                s"status=$responseStatus " +
                s"contentType=${contentType.getOrElse("<missing>")}",
            ),
          )
    else
      IO.fromEither(
        BinaryEventStreamCodec
          .decode[HotStuffGossipArtifact](body)
          .leftMap(error =>
            new IllegalStateException(
                s"failed to decode event poll body for ${link.from.localNodeId}->${link.to.localNodeId}: " +
                s"status=$responseStatus " +
                s"contentType=${contentType.getOrElse("<missing>")} " +
                s"bytes=${body.length} " +
                s"hexPreview=${ByteVector.view(body).take(32).toHex} " +
                s"error=$error",
            ),
          ),
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
    ).tupled.flatMap: (source, _, lifecycle) =>
      proposals.zipWithIndex.traverse_ { case (proposal, index) =>
        source
          .append(
            HotStuffGossipArtifact.ProposalArtifact(proposal),
            tsAt(index.toLong),
          )
          .flatMap: event =>
            node.bootstrap.consensus.sink.applyEvent(event).flatMap:
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

  private def bootstrapPeerBaseUris(
      remotes: Vector[LaunchedNode],
  ): Map[String, String] =
    remotes.map(node => node.localNodeId -> node.baseUri).toMap

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
      bootstrapPeerBaseUris: Map[String, String],
      historicalSyncEnabled: Boolean,
  ): Config =
    val roleValue =
      role match
        case LocalNodeRole.Validator => "validator"
        case LocalNodeRole.Audit     => "audit"
    val bootstrapPeerBaseUriEntries =
      bootstrapPeerBaseUris.toVector
        .sortBy(_._1)
        .map: (peer, baseUri) =>
          s"""      "$peer" = "$baseUri""""
        .mkString("\n")
    val bootstrapSection =
      if bootstrapPeerBaseUris.isEmpty then ""
      else
        s"""
           |  bootstrap {
           |    peer-base-uris {
           |$bootstrapPeerBaseUriEntries
           |    }
           |  }
           |""".stripMargin

    ConfigFactory.parseString(
      s"""
         |sigilaris.node.gossip.peers {
         |  local-node-identity = "$localNodeId"
         |  known-peers = [${allPeers.filterNot(_ === localNodeId).map(peer => s""""$peer"""").mkString(", ")}]
         |  direct-neighbors = [${allPeers.filterNot(_ === localNodeId).map(peer => s""""$peer"""").mkString(", ")}]
         |$bootstrapSection
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
    val txIds = Vector.empty[StableArtifactId]
    val genesisBlock =
      block(
        parent = None,
        height = 0L,
        stateRoot = StateRoot(hex(seed + "10")),
        bodyRoot =
          requireEither(
            ApplicationNeutralProposalView.bodyRoot(txIds),
            s"failed to compute genesis body root for seed $seed",
          ),
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

  private def signedProposal(
      proposerIndex: Int,
      parentBlockId: Option[BlockId],
      height: Long,
      view: Long,
      stateRoot: StateRoot,
      txSeeds: Vector[String],
      justify: QuorumCertificate,
      at: Instant,
  ): Proposal =
    val proposer = validatorSet.members(proposerIndex).id
    val window = HotStuffWindow(chainId, height, view, validatorSet.hash)
    val txIds = txSeeds.map(stableTxId)
    require(
      HotStuffPacemaker.deterministicLeader(window, validatorSet) === proposer,
      s"unexpected deterministic leader for $window",
    )
    val proposalBlock =
      block(
        parent = parentBlockId,
        height = height,
        stateRoot = stateRoot,
        bodyRoot =
          requireEither(
            ApplicationNeutralProposalView.bodyRoot(txIds),
            s"failed to compute body root at height $height view $view",
          ),
        at = at,
      )
    requireEither(
      Proposal.sign(
        UnsignedProposal(
          window = window,
          proposer = proposer,
          targetBlockId = BlockHeader.computeId(proposalBlock),
          block = proposalBlock,
          txSet = ApplicationNeutralProposalView.proposalTxSet(txIds),
          justify = justify,
        ),
        validatorKeys(proposerIndex),
      ),
      s"failed to sign proposal at height $height view $view",
    )

  private def stableTxId(
      seed: String,
  ): StableArtifactId =
    requireEither(
      ApplicationNeutralProposalView.txIdFromBytes(
        ByteVector.view(
          CryptoOps.keccak256(seed.getBytes(StandardCharsets.UTF_8)),
        ),
      ),
      "failed to derive application-neutral stable tx id",
    )

  private def proposalHeight(
      proposal: Proposal,
  ): Long =
    proposal.block.height.toBigNat.toBigInt.longValue

  private def qcHeight(
      qc: QuorumCertificate,
  ): Long =
    qc.subject.window.height.toBigNat.toBigInt.longValue

  private def proposalAtHeight(
      snapshot: InMemoryHotStuffSinkSnapshot,
      height: Long,
  ): Option[Proposal] =
    orderedProposals(
      snapshot.proposals.valuesIterator
        .filter(proposal => proposalHeight(proposal) === height)
        .toVector,
    )
      .headOption

  private def proposalAtWindow(
      snapshot: InMemoryHotStuffSinkSnapshot,
      window: HotStuffWindow,
  ): Option[Proposal] =
    orderedProposals(
      snapshot.proposals.valuesIterator
        .filter(_.window === window)
        .toVector,
    ).headOption

  private def orderedProposals(
      proposals: Vector[Proposal],
  ): Vector[Proposal] =
    proposals.sortBy(proposal =>
      (
        proposal.block.height.toBigNat.toBigInt.longValue,
        proposal.window.view.toBigNat.toBigInt.longValue,
        proposal.proposalId.toHexLower,
      ),
    )

  private def canonicalProposalChain(
      proposals: Vector[Proposal],
  ): Vector[Proposal] =
    val ordered = orderedProposals(proposals)
    val byBlockId =
      ordered.iterator
        .map(proposal => proposal.targetBlockId -> proposal)
        .toMap
    val bestLeaf =
      ordered.lastOption
    bestLeaf.fold(Vector.empty[Proposal]): leaf =>
      @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
      def loop(
          current: Proposal,
          acc: Vector[Proposal],
      ): Vector[Proposal] =
        current.block.parent.flatMap(byBlockId.get) match
          case Some(parentProposal)
              if proposalHeight(parentProposal) + 1L == proposalHeight(current) =>
            loop(parentProposal, parentProposal +: acc)
          case _ =>
            acc

      loop(leaf, Vector(leaf))

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

  private final case class AutomaticLaunchProgress(
      snapshots: Vector[InMemoryHotStuffSinkSnapshot],
  )

  private final case class BootstrapReadyResult(
      result: BootstrapCoordinatorResult,
      diagnostics: BootstrapDiagnostics,
      attempts: Int,
  )

  private final case class LaunchedNode(
      localNodeId: String,
      bootstrap: HotStuffRuntimeBootstrap[IO],
      storageLayout: StorageLayout,
      baseUri: String,
      clock: TestClock,
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

  private final class TestClock private (ref: Ref[IO, Instant]) extends GossipClock[IO]:
    override def now: IO[Instant] =
      ref.get

    def advance(duration: Duration): IO[Unit] =
      ref.update(_.plus(duration))

  private object TestClock:
    def create(instant: Instant): IO[TestClock] =
      Ref.of[IO, Instant](instant).map(new TestClock(_))

  private final case class AutomaticMesh(
      nodesRef: Ref[IO, Vector[LaunchedNode]],
      linksRef: Ref[IO, Vector[MeshLink]],
      failureRef: Ref[IO, Option[Throwable]],
  ):
    def registerNodes(
        newNodes: Vector[LaunchedNode],
    ): IO[Unit] =
      for
        currentNodes <- nodesRef.get
        currentLinks <- linksRef.get
        updatedNodes =
          (currentNodes ++ newNodes)
            .groupBy(_.localNodeId)
            .values
            .map(_.last)
            .toVector
            .sortBy(_.localNodeId)
        updatedLinks <- ensureDirectedLinks(updatedNodes, updatedNodes, currentLinks)
        _ <- nodesRef.set(updatedNodes)
        _ <- linksRef.set(updatedLinks)
      yield ()

    def links: IO[Vector[MeshLink]] =
      linksRef.get

    def bindingsFrom(
        from: LaunchedNode,
        peers: Vector[LaunchedNode],
    ): IO[Vector[BootstrapSessionBinding]] =
      links.map(
        _.collect:
          case link
              if link.from.localNodeId === from.localNodeId &&
                peers.exists(_.localNodeId === link.to.localNodeId) =>
            link.binding,
      )

    def pollOnce: IO[Unit] =
      linksRef.get.flatMap: links =>
        if links.isEmpty then IO.unit
        else drainNetwork(links, maxRounds = 24)

    def assertHealthy: IO[Unit] =
      failureRef.get.flatMap:
        case Some(error) => IO.raiseError(error)
        case None        => IO.unit

    def recordFailure(
        error: Throwable,
    ): IO[Unit] =
      failureRef.set(Some(error))

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
