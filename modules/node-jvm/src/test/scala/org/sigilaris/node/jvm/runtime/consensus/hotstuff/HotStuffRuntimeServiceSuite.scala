package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.nio.file.{Files, Path}
import java.time.Instant

import scala.jdk.CollectionConverters.*
import scala.util.Using

import cats.effect.{IO, Resource}
import cats.effect.kernel.Ref
import cats.syntax.all.*
import munit.CatsEffectSuite
import scodec.bits.ByteVector

import org.sigilaris.core.crypto.CryptoOps
import org.sigilaris.core.datatype.UInt256
import org.sigilaris.node.jvm.runtime.block.{
  BlockHeader,
  BlockHeight,
  BlockTimestamp,
  BodyRoot,
  StateRoot,
}
import org.sigilaris.node.gossip.*
import org.sigilaris.node.jvm.storage.swaydb.StorageLayout

final class HotStuffRuntimeServiceSuite extends CatsEffectSuite:

  private val chainId       = ChainId.unsafe("chain-main")
  private val startedAt     = Instant.parse("2026-04-02T02:00:00Z")
  private val validatorKeys = Vector.fill(4)(CryptoOps.generate())
  private val validatorSet = ValidatorSet.unsafe(
    validatorKeys.zipWithIndex.map: (keyPair, index) =>
      ValidatorMember(
        ValidatorId.unsafe(s"validator-${index + 1}"),
        keyPair.publicKey,
      ),
  )

  private val holders = Vector(
    ValidatorKeyHolder(
      validatorSet.members(0).id,
      PeerIdentity.unsafe("node-a"),
      ValidatorKeyHolderStatus.Active,
    ),
    ValidatorKeyHolder(
      validatorSet.members(1).id,
      PeerIdentity.unsafe("node-a"),
      ValidatorKeyHolderStatus.Active,
    ),
    ValidatorKeyHolder(
      validatorSet.members(2).id,
      PeerIdentity.unsafe("node-a"),
      ValidatorKeyHolderStatus.Active,
    ),
    ValidatorKeyHolder(
      validatorSet.members(3).id,
      PeerIdentity.unsafe("node-b"),
      ValidatorKeyHolderStatus.Active,
    ),
  )

  test(
    "runtime-owned service contract can back HotStuffNodeRuntime without the in-memory assembly",
  ):
    val publisher = RecordingPublisher.create
    val source = new GossipArtifactSource[IO, HotStuffGossipArtifact]:
      override def readAfter(
          chainId: ChainId,
          topic: GossipTopic,
          cursor: Option[CursorToken],
      ): IO[Either[CanonicalRejection, Vector[
        AvailableGossipEvent[HotStuffGossipArtifact],
      ]]] =
        IO.pure(Right(Vector.empty))

      override def readByIds(
          chainId: ChainId,
          topic: GossipTopic,
          ids: Vector[StableArtifactId],
      ): IO[Vector[AvailableGossipEvent[HotStuffGossipArtifact]]] =
        IO.pure(Vector.empty)

    val sink = new GossipArtifactSink[IO, HotStuffGossipArtifact]:
      override def applyEvent(
          event: GossipEvent[HotStuffGossipArtifact],
      ): IO[
        Either[CanonicalRejection.ArtifactContractRejected, ArtifactApplyResult],
      ] =
        IO.pure(Right(ArtifactApplyResult(applied = true, duplicate = false)))

    val services =
      HotStuffRuntimeServices[IO](
        publisher = publisher,
        source = source,
        sink = sink,
        topicContracts = HotStuffTopic.registry(),
        bootstrap = HotStuffBootstrapServices.static[IO](validatorSet),
      )

    val input =
      HotStuffRuntimeBootstrapInput(
        localPeer = PeerIdentity.unsafe("node-a"),
        role = LocalNodeRole.Validator,
        holders = holders,
        validatorSet = validatorSet,
        localKeys = Map(
          validatorSet.members(0).id -> validatorKeys(0),
          validatorSet.members(1).id -> validatorKeys(1),
          validatorSet.members(2).id -> validatorKeys(2),
        ),
      )

    val runtimeEither = HotStuffNodeRuntime.fromServices[IO](input, services)

    for
      runtime <- IO.fromEither(
        runtimeEither.leftMap(rejection =>
          new IllegalArgumentException(rejection.reason),
        ),
      )
      emitted <- runtime.emitProposal(
        proposer = validatorSet.members(0).id,
        block = block(
          parent = Some(bootstrapQc().subject.blockId),
          height = 2L,
          rootHex = "91",
        ),
        txSet = ProposalTxSet.empty,
        window = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash),
        justify = bootstrapQc(),
        ts = startedAt,
      )
      event <- IO.fromEither(
        emitted.leftMap(rejection =>
          new IllegalStateException(rejection.reason),
        ),
      )
      published <- publisher.snapshot
      visibleFromSource <- runtime.source.readAfter(
        chainId,
        GossipTopic.consensusProposal,
        None,
      )
      lookup <- runtime.bootstrapServices.validatorSetLookup
        .validatorSetFor(HotStuffWindow(chainId, 2L, 1L, validatorSet.hash))
      diagnostics <- runtime.bootstrapServices.diagnostics.current
    yield
      assertEquals(runtime.bootstrapInput, input)
      assert(runtime.inMemorySink.isEmpty)
      assert(runtime.inMemorySource.isEmpty)
      assert(runtime.bootstrapLifecycle.isEmpty)
      assert(runtime.source eq source)
      assert(runtime.sink eq sink)
      assertEquals(
        runtime.bootstrapTrustRoot.validatorSetHash,
        validatorSet.hash,
      )
      assertEquals(
        runtime.bootstrapServices.trustRoot.validatorSetHash,
        validatorSet.hash,
      )
      assert(
        runtime.topicContracts
          .contractFor(GossipTopic.consensusProposal)
          .isRight,
      )
      assert(
        runtime.topicContracts
          .contractFor(GossipTopic.consensusVote)
          .isRight,
      )
      assert(
        runtime.topicContracts
          .contractFor(GossipTopic.consensusTimeoutVote)
          .isRight,
      )
      assert(
        runtime.topicContracts
          .contractFor(GossipTopic.consensusNewView)
          .isRight,
      )
      assertEquals(event.topic, GossipTopic.consensusProposal)
      assertEquals(
        published.map(_.topic),
        Vector(GossipTopic.consensusProposal),
      )
      assertEquals(visibleFromSource, Right(Vector.empty))
      assertEquals(lookup.map(_.hash), Right(validatorSet.hash))
      assertEquals(diagnostics.phase, BootstrapPhase.Discovery)

  test(
    "fromServices rejects dual-active holders before constructing the runtime",
  ):
    val dualActive = Vector(
      ValidatorKeyHolder(
        validatorSet.members(0).id,
        PeerIdentity.unsafe("node-a"),
        ValidatorKeyHolderStatus.Active,
      ),
      ValidatorKeyHolder(
        validatorSet.members(0).id,
        PeerIdentity.unsafe("node-b"),
        ValidatorKeyHolderStatus.Active,
      ),
    )

    val services =
      HotStuffRuntimeServices[IO](
        publisher = RecordingPublisher.create,
        source = new GossipArtifactSource[IO, HotStuffGossipArtifact]:
          override def readAfter(
              chainId: ChainId,
              topic: GossipTopic,
              cursor: Option[CursorToken],
          ): IO[Either[CanonicalRejection, Vector[
            AvailableGossipEvent[HotStuffGossipArtifact],
          ]]] =
            IO.pure(Right(Vector.empty))

          override def readByIds(
              chainId: ChainId,
              topic: GossipTopic,
              ids: Vector[StableArtifactId],
          ): IO[Vector[AvailableGossipEvent[HotStuffGossipArtifact]]] =
            IO.pure(Vector.empty)
        ,
        sink = new GossipArtifactSink[IO, HotStuffGossipArtifact]:
          override def applyEvent(
              event: GossipEvent[HotStuffGossipArtifact],
          ): IO[Either[
            CanonicalRejection.ArtifactContractRejected,
            ArtifactApplyResult,
          ]] =
            IO.pure(
              Right(ArtifactApplyResult(applied = true, duplicate = false)),
            )
        ,
        topicContracts = HotStuffTopic.registry(),
        bootstrap = HotStuffBootstrapServices.static[IO](validatorSet),
      )

    val runtime =
      HotStuffNodeRuntime.fromServices[IO](
        bootstrapInput = HotStuffRuntimeBootstrapInput(
          localPeer = PeerIdentity.unsafe("node-a"),
          role = LocalNodeRole.Validator,
          holders = dualActive,
          validatorSet = validatorSet,
          localKeys = Map(validatorSet.members(0).id -> validatorKeys(0)),
        ),
        services = services,
      )

    assertEquals(runtime.left.map(_.reason), Left("dualActiveKeyHolder"))

  test(
    "runtime bootstrap surface reports an unavailable lifecycle when newcomer bootstrap is not assembled",
  ):
    given GossipClock[IO] = GossipClock.constant[IO](startedAt)

    val runtimeResult =
      HotStuffNodeRuntime.create[IO](
        localPeer = PeerIdentity.unsafe("node-a"),
        role = LocalNodeRole.Validator,
        validatorSet = validatorSet,
        holders = holders,
        localKeys = Map(
          validatorSet.members(0).id -> validatorKeys(0),
          validatorSet.members(1).id -> validatorKeys(1),
          validatorSet.members(2).id -> validatorKeys(2),
        ),
      )

    for
      runtimeEither <- runtimeResult
      runtime <- IO.fromEither(
        runtimeEither.leftMap(rejection =>
          new IllegalArgumentException(rejection.reason),
        ),
      )
      result <- runtime.bootstrap(
        chainId = chainId,
        sessions = Vector.empty,
        startedAt = startedAt,
        liveProposals = Vector.empty,
      )
    yield
      assert(runtime.bootstrapLifecycle.isEmpty)
      assertEquals(
        result.left.map(_.reason),
        Left("bootstrapLifecycleUnavailable"),
      )

  test(
    "concrete bootstrap surface exposes bootstrap input and runtime services explicitly",
  ):
    val topology =
      StaticPeerTopology
        .parse(
          localNodeIdentity = "node-a",
          knownPeers = List("node-b"),
          directNeighbors = List("node-b"),
        )
        .toOption
        .get

    val config =
      HotStuffBootstrapConfig(
        role = LocalNodeRole.Validator,
        validatorSet = validatorSet,
        holders = holders,
        localKeys = Map(
          validatorSet.members(0).id -> validatorKeys(0),
          validatorSet.members(1).id -> validatorKeys(1),
          validatorSet.members(2).id -> validatorKeys(2),
        ),
      )

    tempStorageLayoutResource.use: storageLayout =>
      HotStuffRuntimeBootstrap
        .fromTopology[IO](
          topology = topology,
          transportAuth = StaticPeerTransportAuth.testing(topology),
          consensusConfig = config,
          clock = GossipClock.constant[IO](startedAt),
          storageLayout = storageLayout,
        )
        .use: bootstrapEither =>
          for bootstrap <- IO.fromEither(
              bootstrapEither.leftMap(new IllegalArgumentException(_)),
            )
          yield
            assertEquals(
              bootstrap.consensus.bootstrapInput.localPeer,
              topology.localNodeIdentity,
            )
            assertEquals(
              bootstrap.consensus.bootstrapInput.role,
              LocalNodeRole.Validator,
            )
            assertEquals(
              bootstrap.consensus.bootstrapInput.validatorSet.hash,
              validatorSet.hash,
            )
            assertEquals(bootstrap.consensus.bootstrapInput.holders, holders)
            assertEquals(
              bootstrap.consensus.bootstrapInput.localKeys.keySet,
              config.localKeys.keySet,
            )
            assertEquals(
              bootstrap.consensus.bootstrapTrustRoot.validatorSetHash,
              validatorSet.hash,
            )
            assertEquals(
              bootstrap.consensus.bootstrapServices.trustRoot.validatorSetHash,
              validatorSet.hash,
            )
            assert(
              bootstrap.consensus.topicContracts
                .contractFor(GossipTopic.consensusProposal)
                .isRight,
            )
            assert(
              bootstrap.consensus.topicContracts
                .contractFor(GossipTopic.consensusVote)
                .isRight,
            )
            assert(
              bootstrap.consensus.topicContracts
                .contractFor(GossipTopic.consensusTimeoutVote)
                .isRight,
            )
            assert(
              bootstrap.consensus.topicContracts
                .contractFor(GossipTopic.consensusNewView)
                .isRight,
            )
            assert(bootstrap.consensus.inMemorySource.nonEmpty)
            assert(bootstrap.consensus.inMemorySink.nonEmpty)
            assert(bootstrap.consensus.bootstrapLifecycle.nonEmpty)
            assertEquals(
              bootstrap.registry.localPeer,
              bootstrap.consensus.bootstrapInput.localPeer,
            )

  private def bootstrapQc(): QuorumCertificate =
    val window = HotStuffWindow(chainId, 0L, 0L, validatorSet.hash)
    val subject = QuorumCertificateSubject(
      window = window,
      proposalId = ProposalId(hex("70")),
      blockId = BlockId(hex("71")),
    )
    QuorumCertificateAssembler
      .assemble(
        subject,
        Vector(
          signedVoteFor(window, subject.proposalId, 0),
          signedVoteFor(window, subject.proposalId, 1),
          signedVoteFor(window, subject.proposalId, 2),
        ),
        validatorSet,
      )
      .toOption
      .get

  private def signedVoteFor(
      window: HotStuffWindow,
      proposalId: ProposalId,
      index: Int,
  ): Vote =
    Vote
      .sign(
        UnsignedVote(window, validatorSet.members(index).id, proposalId),
        validatorKeys(index),
      )
      .toOption
      .get

  private def hex(
      value: String,
  ): UInt256 =
    UInt256.fromHex(value).toOption.get

  private def block(
      parent: Option[BlockId],
      height: Long,
      rootHex: String,
  ): BlockHeader =
    BlockHeader(
      parent = parent,
      height = BlockHeight.unsafeFromLong(height),
      stateRoot = StateRoot(hex(rootHex)),
      bodyRoot = BodyRoot(hex(rootHex)),
      timestamp = BlockTimestamp.unsafeFromEpochMillis(startedAt.toEpochMilli),
    )

  private def tempStorageLayoutResource: Resource[IO, StorageLayout] =
    Resource
      .make(
        IO.blocking(
          Files.createTempDirectory("sigilaris-runtime-service-bootstrap"),
        ),
      ) { root =>
        IO.blocking(deleteRecursively(root))
      }
      .map(StorageLayout.fromRoot)

  private def deleteRecursively(
      path: Path,
  ): Unit =
    if Files.exists(path) then
      Using.resource(Files.walk(path)): stream =>
        stream.iterator.asScala.toList.reverse.foreach(Files.deleteIfExists)

  private final class RecordingPublisher private (
      ref: Ref[IO, Vector[GossipEvent[HotStuffGossipArtifact]]],
  ) extends HotStuffArtifactPublisher[IO]:
    override def append(
        artifact: HotStuffGossipArtifact,
        ts: Instant,
    ): IO[GossipEvent[HotStuffGossipArtifact]] =
      ref.modify: existing =>
        val sequence = existing.size.toLong + 1L
        val chainId = artifact match
          case HotStuffGossipArtifact.ProposalArtifact(proposal) =>
            proposal.window.chainId
          case HotStuffGossipArtifact.VoteArtifact(vote) => vote.window.chainId
          case HotStuffGossipArtifact.TimeoutVoteArtifact(timeoutVote) =>
            timeoutVote.subject.window.chainId
          case HotStuffGossipArtifact.NewViewArtifact(newView) =>
            newView.window.chainId
        val event =
          GossipEvent(
            chainId = chainId,
            topic = HotStuffGossipArtifact.topicOf(artifact),
            id = HotStuffGossipArtifact.stableIdOf(artifact),
            cursor = CursorToken.unsafeIssue(ByteVector.fromLong(sequence)),
            ts = ts,
            payload = artifact,
          )
        (existing :+ event) -> event

    def snapshot: IO[Vector[GossipEvent[HotStuffGossipArtifact]]] =
      ref.get

  private object RecordingPublisher:
    def create: RecordingPublisher =
      new RecordingPublisher(Ref.unsafe(Vector.empty))
