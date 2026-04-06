package org.sigilaris.node.jvm.storage.swaydb

import java.nio.file.{Files, Path}
import java.time.Instant

import scala.jdk.CollectionConverters.*
import scala.util.Using

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import munit.CatsEffectSuite
import scodec.bits.ByteVector

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder, DecodeResult}
import org.sigilaris.core.datatype.{UInt256, Utf8}
import org.sigilaris.core.failure.DecodeFailure
import org.sigilaris.core.crypto.CryptoOps
import org.sigilaris.node.jvm.runtime.block.{
  BlockHeader,
  BlockHeight,
  BlockId,
  BlockTimestamp,
  BodyRoot,
  StateRoot,
}
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
import org.sigilaris.node.jvm.runtime.gossip.ChainId

final class HotStuffHistoricalArchiveSuite extends CatsEffectSuite:
  private given Bag.Async[IO] = Bag.global

  private val chainId   = ChainId.unsafe("chain-main")
  private val otherChainId = ChainId.unsafe("chain-other")
  private val startedAt = Instant.parse("2026-04-07T02:00:00Z")
  private val validators = Vector.fill(4)(CryptoOps.generate())
  private val validatorSet = ValidatorSet.unsafe(
    validators.zipWithIndex.map: (keyPair, index) =>
      ValidatorMember(
        id = ValidatorId.unsafe(s"validator-${index + 1}"),
        publicKey = keyPair.publicKey,
      ),
  )

  private final case class ArchiveKeyWire(
      chainId: ChainId,
      proposalId: ProposalId,
  )

  private given ByteEncoder[ChainId] =
    ByteEncoder[Utf8].contramap(chainId => Utf8(chainId.value))
  private given ByteDecoder[ChainId] =
    ByteDecoder[Utf8].emap: utf8 =>
      ChainId.parse(utf8.asString).left.map(DecodeFailure(_))
  private given ByteEncoder[ProposalId] =
    ByteEncoder[UInt256].contramap(_.toUInt256)
  private given ByteDecoder[ProposalId] =
    ByteDecoder[UInt256].map(ProposalId(_))
  private given ByteEncoder[ArchiveKeyWire] =
    key => ByteEncoder[ChainId].encode(key.chainId) ++ ByteEncoder[ProposalId].encode(key.proposalId)
  private given ByteDecoder[ArchiveKeyWire] =
    ByteDecoder[ChainId].flatMap: chainId =>
      ByteDecoder[ProposalId].map: proposalId =>
        ArchiveKeyWire(chainId, proposalId)
  private given ByteEncoder[ByteVector] = (value: ByteVector) => value
  private given ByteDecoder[ByteVector] = bytes =>
    DecodeResult(bytes, ByteVector.empty).asRight[DecodeFailure]

  private def tempDirResource: Resource[IO, Path] =
    Resource.make(IO.blocking(Files.createTempDirectory("sigilaris-historical-archive"))) { dir =>
      IO.blocking(deleteRecursively(dir))
    }

  test("durable archive persists proposals across reopen, dedupes duplicates, and reorders list output canonically"):
    val initialStoredAt = startedAt.minusSeconds(5L)
    val bootstrap       = bootstrapQc()
    val proposalView2   = proposal(height = 1L, view = 2L, seed = "10", justify = bootstrap)
    val proposalView1   = proposal(height = 1L, view = 1L, seed = "11", justify = bootstrap)
    val proposalHeight2 =
      proposal(
        height = 2L,
        view = 1L,
        seed = "12",
        justify = qcFor(proposalView1),
        parentBlockId = Some(proposalView1.targetBlockId),
      )

    tempDirResource.use: root =>
      val layout = StorageLayout.fromRoot(root)
      val reopenLayout =
        StorageLayout.fromRoot(root.resolve("reopen"))
      for
        archive1 <- HistoricalProposalArchive.swaydb[IO](layout)
        appended <- archive1.putAll(
          chainId = chainId,
          proposals = Vector(
            proposalHeight2,
            proposalView2,
            proposalView1,
            proposalView2,
          ),
          source = HistoricalArchiveSource.BackgroundBackfill,
          storedAt = initialStoredAt,
        )
        _ <- archive1.putAll(
          chainId = otherChainId,
          proposals = Vector(proposal(height = 9L, view = 1L, seed = "99", justify = bootstrap)),
          source = HistoricalArchiveSource.BackgroundBackfill,
          storedAt = initialStoredAt,
        )
        listedBeforeReopen <- archive1.list(chainId)
        containsBefore <- archive1.contains(chainId, proposalView1.proposalId)
        _ <- archive1.close
        _ <- IO.blocking(copyRecursively(layout.state.historicalArchive, reopenLayout.state.historicalArchive))
        archive2 <- HistoricalProposalArchive.swaydb[IO](reopenLayout)
        listedAfterReopen <- archive2.list(chainId)
        removed <- archive2.removeAll(
          chainId,
          Vector(proposalView1.proposalId, proposalView1.proposalId, ProposalId(hex("ff"))),
        )
        containsAfterRemove <- archive2.contains(chainId, proposalView1.proposalId)
        remaining <- archive2.list(chainId)
        _ <- archive2.close
      yield
        assertEquals(
          appended,
          Vector(
            proposalHeight2.proposalId,
            proposalView2.proposalId,
            proposalView1.proposalId,
          ),
        )
        assertEquals(
          listedBeforeReopen.map(_.proposal.proposalId),
          Vector(
            proposalView1.proposalId,
            proposalView2.proposalId,
            proposalHeight2.proposalId,
          ),
        )
        assert(listedBeforeReopen.forall(_.proposal.window.chainId === chainId))
        assertEquals(
          listedBeforeReopen.map(_.source),
          Vector.fill(3)(HistoricalArchiveSource.BackgroundBackfill),
        )
        assertEquals(
          listedBeforeReopen.map(_.storedAt),
          Vector.fill(3)(initialStoredAt),
        )
        assertEquals(containsBefore, true)
        assertEquals(
          listedAfterReopen.map(_.proposal.proposalId),
          Vector(
            proposalView1.proposalId,
            proposalView2.proposalId,
            proposalHeight2.proposalId,
          ),
        )
        assert(listedAfterReopen.forall(_.proposal.window.chainId === chainId))
        assertEquals(
          listedAfterReopen.map(_.source),
          Vector.fill(3)(HistoricalArchiveSource.ArchiveSync),
        )
        assertEquals(
          listedAfterReopen.map(_.storedAt),
          Vector.fill(3)(Instant.EPOCH),
        )
        assertEquals(removed, 1)
        assertEquals(containsAfterRemove, false)
        assertEquals(
          remaining.map(_.proposal.proposalId),
          Vector(
            proposalView2.proposalId,
            proposalHeight2.proposalId,
          ),
        )

  test("durable archive fails fast when the archive path cannot be opened as a store"):
    tempDirResource.use: root =>
      val layout = StorageLayout.fromRoot(root)
      for
        _ <- IO.blocking(Files.createDirectories(layout.state.historicalArchive.getParent))
        _ <- IO.blocking(Files.writeString(layout.state.historicalArchive, "blocked"))
        result <- HistoricalProposalArchive.swaydb[IO](layout).attempt
      yield
        assert(result.isLeft)
        assert(
          result.leftMap(_.getMessage).swap.exists(_.contains("historical-archive")),
        )

  test("durable archive supports concurrent open on the same backing path"):
    val bootstrap = bootstrapQc()
    val stored    = proposal(height = 1L, view = 1L, seed = "15", justify = bootstrap)

    tempDirResource.use: root =>
      val layout = StorageLayout.fromRoot(root)
      for
        archives <- (
          HistoricalProposalArchive.swaydb[IO](layout),
          HistoricalProposalArchive.swaydb[IO](layout),
        ).parTupled
        (archiveA, archiveB) = archives
        _ <- archiveA.putAll(
          chainId = chainId,
          proposals = Vector(stored),
          source = HistoricalArchiveSource.BackgroundBackfill,
          storedAt = startedAt,
        )
        listed <- archiveB.list(chainId)
        _ <- archiveA.close
        _ <- archiveB.close
      yield
        assertEquals(
          listed.map(_.proposal.proposalId),
          Vector(stored.proposalId),
        )

  test("durable archive close is idempotent and closed handles reject further operations clearly"):
    val bootstrap = bootstrapQc()
    val stored    = proposal(height = 1L, view = 1L, seed = "20", justify = bootstrap)

    tempDirResource.use: root =>
      val layout = StorageLayout.fromRoot(root)
      for
        archive <- HistoricalProposalArchive.swaydb[IO](layout)
        _ <- archive.putAll(
          chainId = chainId,
          proposals = Vector(stored),
          source = HistoricalArchiveSource.BackgroundBackfill,
          storedAt = startedAt,
        )
        _ <- archive.close
        _ <- archive.close
        listResult <- archive.list(chainId).attempt
        putResult <- archive
          .putAll(
            chainId = chainId,
            proposals = Vector(stored),
            source = HistoricalArchiveSource.BackgroundBackfill,
            storedAt = startedAt,
          )
          .attempt
      yield
        assert(listResult.isLeft)
        assert(
          listResult.leftMap(_.getMessage).swap.exists(
            _.contains("historicalArchiveClosed"),
          ),
        )
        assert(putResult.isLeft)
        assert(
          putResult.leftMap(_.getMessage).swap.exists(
            _.contains("historicalArchiveClosed"),
          ),
        )

  test("resetting shared archive stores invalidates live handles cleanly"):
    val bootstrap = bootstrapQc()
    val stored    = proposal(height = 1L, view = 1L, seed = "21", justify = bootstrap)

    tempDirResource.use: root =>
      val layout = StorageLayout.fromRoot(root)
      for
        archive <- HistoricalProposalArchive.swaydb[IO](layout)
        _ <- archive.putAll(
          chainId = chainId,
          proposals = Vector(stored),
          source = HistoricalArchiveSource.BackgroundBackfill,
          storedAt = startedAt,
        )
        _ <- HistoricalProposalArchive.resetSharedStoresForTesting
        result <- archive.list(chainId).attempt
        _ <- archive.close
      yield
        assert(result.isLeft)
        assert(
          result.leftMap(_.getMessage).swap.exists(
            _.contains("historicalArchiveClosed"),
          ),
        )

  test("durable archive treats unknown schema versions as fatal archive incompatibility"):
    val bootstrap = bootstrapQc()
    val stored     = proposal(height = 1L, view = 1L, seed = "30", justify = bootstrap)

    tempDirResource.use: root =>
      val corruptLayout =
        StorageLayout.fromRoot(root.resolve("corrupt"))
      val reopenLayout =
        StorageLayout.fromRoot(root.resolve("reopen"))
      for
        _ <- SwayStores
          .storeIndex[ArchiveKeyWire, ByteVector](corruptLayout.state.historicalArchive)
          .use: store =>
            store.put(
              ArchiveKeyWire(chainId, stored.proposalId),
              ByteVector(0x02.toByte) ++ ByteEncoder[Proposal].encode(stored),
            )
        _ <- IO.blocking(
          copyRecursively(corruptLayout.state.historicalArchive, reopenLayout.state.historicalArchive),
        )
        archive <- HistoricalProposalArchive.swaydb[IO](reopenLayout)
        result <- archive.list(chainId).attempt
        _ <- archive.close
      yield
        assert(result.isLeft)
        assert(
          result.leftMap(_.getMessage).swap.exists(
            _.contains("historicalArchiveUnknownSchemaVersion"),
          ),
        )

  private def deleteRecursively(
      path: Path,
  ): Unit =
    if Files.exists(path) then
      Using.resource(Files.walk(path)): stream =>
        stream.iterator.asScala.toList.reverse.foreach(Files.deleteIfExists)

  override def afterAll(): Unit =
    val _ = HistoricalProposalArchive.resetSharedStoresForTesting.attempt.unsafeRunSync()
    super.afterAll()

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

  private def bootstrapQc(): QuorumCertificate =
    val subject =
      QuorumCertificateSubject(
        window = HotStuffWindow(chainId, 0L, 0L, validatorSet.hash),
        proposalId = ProposalId(hex("01")),
        blockId = BlockId(hex("02")),
      )
    QuorumCertificate(subject, quorumVotes(subject.window, subject.proposalId))

  private def qcFor(
      proposal: Proposal,
  ): QuorumCertificate =
    val subject =
      QuorumCertificateSubject(
        window = proposal.window,
        proposalId = proposal.proposalId,
        blockId = proposal.targetBlockId,
      )
    QuorumCertificate(subject, quorumVotes(proposal.window, proposal.proposalId))

  private def proposal(
      height: Long,
      view: Long,
      seed: String,
      justify: QuorumCertificate,
      parentBlockId: Option[BlockId] = None,
  ): Proposal =
    Proposal
      .sign(
        UnsignedProposal(
          window = HotStuffWindow(chainId, height, view, validatorSet.hash),
          proposer = validatorSet.members(0).id,
          targetBlockId = BlockId(hex(seed + "f0")),
          block = block(
            parent = parentBlockId.orElse(Some(justify.subject.blockId)),
            height = height,
            stateRoot = StateRoot(hex(seed + "a1")),
            bodyHex = seed + "b2",
          ),
          txSet = ProposalTxSet.empty,
          justify = justify,
        ),
        validators(0),
      )
      .toOption
      .get

  private def quorumVotes(
      window: HotStuffWindow,
      proposalId: ProposalId,
  ): Vector[Vote] =
    Vector(0, 1, 2).map: validatorIndex =>
      Vote
        .sign(
          UnsignedVote(
            window = window,
            voter = validatorSet.members(validatorIndex).id,
            targetProposalId = proposalId,
          ),
          validators(validatorIndex),
        )
        .toOption
        .get

  private def block(
      parent: Option[BlockId],
      height: Long,
      stateRoot: StateRoot,
      bodyHex: String,
  ): BlockHeader =
    BlockHeader(
      parent = parent,
      height = BlockHeight.unsafeFromLong(height),
      stateRoot = stateRoot,
      bodyRoot = BodyRoot(hex(bodyHex)),
      timestamp = BlockTimestamp.unsafeFromEpochMillis(startedAt.toEpochMilli + height),
    )

  private def hex(
      value: String,
  ): UInt256 =
    UInt256.fromHex(value).toOption.get
