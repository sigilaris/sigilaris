package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Instant

import cats.{Applicative, Monad}
import cats.effect.{IO, LiftIO, Ref}
import cats.effect.kernel.{Async, Deferred, Sync}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder, DecodeResult}
import org.sigilaris.core.failure.DecodeFailure
import org.sigilaris.node.jvm.runtime.block.BlockHeight
import org.sigilaris.node.gossip.{ChainId, ControlBatch}
import org.sigilaris.node.jvm.storage.swaydb.{Bag, StorageLayout, SwayStores}

/** Captures the materialized state of a forward catch-up for persistence or replay. */
final case class ForwardCatchUpMaterialization(
    chainId: ChainId,
    anchor: SnapshotAnchor,
    applied: Vector[Proposal],
    queued: Vector[Proposal],
    controlBatches: Vector[ControlBatch],
    frontierBlockId: BlockId,
    frontierHeight: BlockHeight,
    voteReadiness: BootstrapVoteReadiness,
    lastUpdatedAt: Instant,
)

/** Stores forward catch-up materializations keyed by chain ID. */
trait ForwardCatchUpStore[F[_]]:
  /** Retrieves the current forward catch-up materialization for the given chain. */
  def current(
      chainId: ChainId,
  ): F[Option[ForwardCatchUpMaterialization]]

  /** Stores a forward catch-up materialization. */
  def put(
      materialization: ForwardCatchUpMaterialization,
  ): F[Unit]

/** Companion for `ForwardCatchUpStore`. */
object ForwardCatchUpStore:
  /** Creates a no-op store that discards all writes. */
  def noop[F[_]: Applicative]: ForwardCatchUpStore[F] =
    new ForwardCatchUpStore[F]:
      override def current(
          chainId: ChainId,
      ): F[Option[ForwardCatchUpMaterialization]] =
        none[ForwardCatchUpMaterialization].pure[F]

      override def put(
          materialization: ForwardCatchUpMaterialization,
      ): F[Unit] =
        Applicative[F].unit

  /** Creates an in-memory store backed by a Ref. */
  def inMemory[F[_]: Sync]: F[ForwardCatchUpStore[F]] =
    Ref
      .of[F, Map[ChainId, ForwardCatchUpMaterialization]](Map.empty)
      .map: ref =>
        new ForwardCatchUpStore[F]:
          override def current(
              chainId: ChainId,
          ): F[Option[ForwardCatchUpMaterialization]] =
            ref.get.map(_.get(chainId))

          override def put(
              materialization: ForwardCatchUpMaterialization,
          ): F[Unit] =
            ref.update(
              _.updated(materialization.chainId, materialization),
            )

/** The source that produced a historical archive entry. */
enum HistoricalArchiveSource:
  /** Stored during background historical backfill. */
  case BackgroundBackfill
  /** Stored during archive synchronization. */
  case ArchiveSync

/** An entry in the historical proposal archive.
  *
  * @param proposal the archived proposal
  * @param source the source that produced this entry
  * @param storedAt the time this entry was stored
  */
final case class HistoricalArchiveEntry(
    proposal: Proposal,
    source: HistoricalArchiveSource,
    storedAt: Instant,
)

/** Persistent archive for historical proposals, supporting list, put, remove, and deduplication. */
trait HistoricalProposalArchive[F[_]]:
  /** Releases resources held by the archive. */
  def close: F[Unit]

  /** Lists all archived entries for the given chain. */
  def list(
      chainId: ChainId,
  ): F[Vector[HistoricalArchiveEntry]]

  /** Checks whether a proposal is already archived. */
  def contains(
      chainId: ChainId,
      proposalId: ProposalId,
  ): F[Boolean]

  /** Archives proposals, returning the IDs of newly stored ones (skipping duplicates). */
  def putAll(
      chainId: ChainId,
      proposals: Vector[Proposal],
      source: HistoricalArchiveSource,
      storedAt: Instant,
  ): F[Vector[ProposalId]]

  /** Removes the given proposals from the archive, returning the count actually removed. */
  def removeAll(
      chainId: ChainId,
      proposalIds: Vector[ProposalId],
  ): F[Int]

/** Companion for `HistoricalProposalArchive`, providing in-memory and SwayDB-backed implementations. */
object HistoricalProposalArchive:
  private val SchemaVersionV1: Byte = 0x01.toByte
  private val ListPageSize: Int     = 256
  private val RecoveredStoredAt     = Instant.EPOCH
  private val ScanStartProposalId =
    ProposalId(
      org.sigilaris.core.datatype.UInt256.unsafeFromBigIntUnsigned(BigInt(0)),
    )

  private final case class HistoricalArchiveKey(
      chainId: ChainId,
      proposalId: ProposalId,
  ) derives ByteEncoder,
        ByteDecoder

  private final case class HistoricalArchiveMetadata(
      source: HistoricalArchiveSource,
      storedAt: Instant,
  )

  private final case class HistoricalArchiveScanState(
      cursor: HistoricalArchiveKey,
      offset: Int,
      acc: Vector[(HistoricalArchiveKey, ByteVector)],
  )

  private final case class SharedHistoricalArchiveStore(
      store: org.sigilaris.node.jvm.storage.StoreIndex[
        IO,
        HistoricalArchiveKey,
        ByteVector,
      ],
      release: IO[Unit],
      refCount: Int,
  )

  private final case class SharedHistoricalArchiveHandle(
      store: org.sigilaris.node.jvm.storage.StoreIndex[
        IO,
        HistoricalArchiveKey,
        ByteVector,
      ],
      release: IO[Unit],
      isCurrent: IO[Boolean],
  )

  private final case class SharedHistoricalArchiveOpening(
      token: Long,
      gate: Deferred[IO, Either[Throwable, Unit]],
  )

  private sealed trait SharedHistoricalArchiveSlot
  private object SharedHistoricalArchiveSlot:
    final case class Opening(
        state: SharedHistoricalArchiveOpening,
    ) extends SharedHistoricalArchiveSlot
    final case class Open(
        store: SharedHistoricalArchiveStore,
    ) extends SharedHistoricalArchiveSlot
    final case class Releasing(
        gate: Deferred[IO, Unit],
    ) extends SharedHistoricalArchiveSlot

  private sealed trait SharedHistoricalArchiveAcquire
  private object SharedHistoricalArchiveAcquire:
    final case class Reuse(
        handle: SharedHistoricalArchiveHandle,
    ) extends SharedHistoricalArchiveAcquire
    final case class AwaitOpening(
        state: SharedHistoricalArchiveOpening,
    ) extends SharedHistoricalArchiveAcquire
    final case class AwaitRelease(
        gate: Deferred[IO, Unit],
    ) extends SharedHistoricalArchiveAcquire
    final case class Create(
        state: SharedHistoricalArchiveOpening,
    ) extends SharedHistoricalArchiveAcquire

  private sealed trait SharedHistoricalArchiveInstall
  private object SharedHistoricalArchiveInstall:
    final case class Installed(
        handle: SharedHistoricalArchiveHandle,
        signalReady: IO[Unit],
    ) extends SharedHistoricalArchiveInstall
    final case class DisposeAndReuse(
        dispose: IO[Unit],
        handle: SharedHistoricalArchiveHandle,
        signalReady: IO[Unit],
    ) extends SharedHistoricalArchiveInstall
    final case class DisposeAndAwait(
        dispose: IO[Unit],
        gate: Deferred[IO, Unit],
        signalReady: IO[Unit],
    ) extends SharedHistoricalArchiveInstall
    final case class DisposeOnly(
        dispose: IO[Unit],
        signalReady: IO[Unit],
    ) extends SharedHistoricalArchiveInstall

  private given ByteEncoder[ByteVector] = (value: ByteVector) => value
  private given ByteDecoder[ByteVector] = bytes =>
    DecodeResult(bytes, ByteVector.empty).asRight[DecodeFailure]

  /** Creates a no-op archive that discards all writes. */
  def noop[F[_]: Applicative]: HistoricalProposalArchive[F] =
    new HistoricalProposalArchive[F]:
      override def close: F[Unit] =
        Applicative[F].unit

      override def list(
          chainId: ChainId,
      ): F[Vector[HistoricalArchiveEntry]] =
        Vector.empty[HistoricalArchiveEntry].pure[F]

      override def contains(
          chainId: ChainId,
          proposalId: ProposalId,
      ): F[Boolean] =
        false.pure[F]

      override def putAll(
          chainId: ChainId,
          proposals: Vector[Proposal],
          source: HistoricalArchiveSource,
          storedAt: Instant,
      ): F[Vector[ProposalId]] =
        proposals.map(_.proposalId).pure[F]

      override def removeAll(
          chainId: ChainId,
          proposalIds: Vector[ProposalId],
      ): F[Int] =
        proposalIds.size.pure[F]

  /** Creates an in-memory archive backed by a Ref. */
  def inMemory[F[_]: Sync]: F[HistoricalProposalArchive[F]] =
    Ref
      .of[F, Map[ChainId, Vector[HistoricalArchiveEntry]]](Map.empty)
      .map: ref =>
        new HistoricalProposalArchive[F]:
          override def close: F[Unit] =
            Sync[F].unit

          override def list(
              chainId: ChainId,
          ): F[Vector[HistoricalArchiveEntry]] =
            ref.get.map(
              _.getOrElse(chainId, Vector.empty[HistoricalArchiveEntry]),
            )

          override def contains(
              chainId: ChainId,
              proposalId: ProposalId,
          ): F[Boolean] =
            list(chainId).map(_.exists(_.proposal.proposalId === proposalId))

          override def putAll(
              chainId: ChainId,
              proposals: Vector[Proposal],
              source: HistoricalArchiveSource,
              storedAt: Instant,
          ): F[Vector[ProposalId]] =
            ref.modify: current =>
              val existing =
                current.getOrElse(chainId, Vector.empty[HistoricalArchiveEntry])
              val knownIds =
                existing.iterator.map(_.proposal.proposalId).toSet
              val appended =
                proposals
                  .filterNot(proposal => knownIds.contains(proposal.proposalId))
                  .map: proposal =>
                    HistoricalArchiveEntry(
                      proposal = proposal,
                      source = source,
                      storedAt = storedAt,
                    )
              current.updated(chainId, existing ++ appended) -> appended.map(
                _.proposal.proposalId,
              )

          override def removeAll(
              chainId: ChainId,
              proposalIds: Vector[ProposalId],
          ): F[Int] =
            ref.modify: current =>
              val existing =
                current.getOrElse(chainId, Vector.empty[HistoricalArchiveEntry])
              val idsToRemove = proposalIds.toSet
              val retained =
                existing.filterNot(entry =>
                  idsToRemove.contains(entry.proposal.proposalId),
                )
              current.updated(
                chainId,
                retained,
              ) -> (existing.size - retained.size)

  /** Creates a persistent archive backed by SwayDB. */
  def swaydb[F[_]: Async: LiftIO](
      layout: StorageLayout,
  ): F[HistoricalProposalArchive[F]] =
    given Bag.Async[IO] = Bag.global
    val archiveDir      = layout.state.historicalArchive
    for
      sharedStore <- LiftIO[F].liftIO(
        SharedHistoricalArchiveStores.acquire(archiveDir),
      )
      metadataRef <- Ref
        .of[F, Map[HistoricalArchiveKey, HistoricalArchiveMetadata]](Map.empty)
      closedRef     <- Ref.of[F, Boolean](false)
      lifecycleLock <- Semaphore[F](1)
    yield new HistoricalProposalArchive[F]:
      private def ensureOpen: F[Unit] =
        closedRef.get.flatMap: closed =>
          if closed then
            Async[F].raiseError[Unit](archiveFailure("historicalArchiveClosed"))
          else
            LiftIO[F]
              .liftIO(sharedStore.isCurrent)
              .flatMap: current =>
                if current then Async[F].unit
                else
                  closedRef.set(true) *>
                    Async[F].raiseError[Unit](
                      archiveFailure("historicalArchiveClosed"),
                    )

      override def close: F[Unit] =
        lifecycleLock.permit.use { _ =>
          closedRef
            .modify(closed => true -> !closed)
            .flatMap: shouldRelease =>
              if shouldRelease then LiftIO[F].liftIO(sharedStore.release)
              else Async[F].unit
        }

      override def list(
          chainId: ChainId,
      ): F[Vector[HistoricalArchiveEntry]] =
        lifecycleLock.permit.use { _ =>
          for
            _        <- ensureOpen
            metadata <- metadataRef.get
            stored <- LiftIO[F].liftIO:
              listEntries(sharedStore.store, chainId)
            decoded <- stored.traverse: (key, bytes) =>
              Async[F].fromEither:
                decodeStoredProposal(bytes).map: proposal =>
                  val recovered =
                    metadata.getOrElse(
                      key,
                      HistoricalArchiveMetadata(
                        source = HistoricalArchiveSource.ArchiveSync,
                        storedAt = RecoveredStoredAt,
                      ),
                    )
                  HistoricalArchiveEntry(
                    proposal = proposal,
                    source = recovered.source,
                    storedAt = recovered.storedAt,
                  )
          yield decoded.sortBy: entry =>
            (
              entry.proposal.window.height,
              entry.proposal.window.view,
              entry.proposal.proposalId.toHexLower,
            )
        }

      override def contains(
          chainId: ChainId,
          proposalId: ProposalId,
      ): F[Boolean] =
        lifecycleLock.permit.use { _ =>
          ensureOpen *> LiftIO[F].liftIO:
            sharedStore.store
              .get(HistoricalArchiveKey(chainId, proposalId))
              .value
              .flatMap:
                case Left(error) =>
                  failIo[Boolean](
                    "historicalArchiveCorruptOrIncompatible: " + error.msg,
                  )
                case Right(None) =>
                  IO.pure(false)
                case Right(Some(bytes)) =>
                  IO.fromEither(decodeStoredProposal(bytes)).as(true)
        }

      override def putAll(
          chainId: ChainId,
          proposals: Vector[Proposal],
          source: HistoricalArchiveSource,
          storedAt: Instant,
      ): F[Vector[ProposalId]] =
        lifecycleLock.permit.use { _ =>
          for
            _ <- ensureOpen
            writeResult <- LiftIO[F].liftIO:
              proposals
                .foldLeftM(
                  (
                    Vector.empty[ProposalId],
                    Set.empty[ProposalId],
                    Vector
                      .empty[(HistoricalArchiveKey, HistoricalArchiveMetadata)],
                  ),
                ):
                  case ((written, seen, metadataUpdates), proposal) =>
                    if seen.contains(proposal.proposalId) then
                      IO.pure((written, seen, metadataUpdates))
                    else
                      val key =
                        HistoricalArchiveKey(
                          chainId = chainId,
                          proposalId = proposal.proposalId,
                        )
                      sharedStore.store
                        .get(key)
                        .value
                        .flatMap:
                          case Left(error) =>
                            failIo[
                              (
                                  Vector[ProposalId],
                                  Set[ProposalId],
                                  Vector[
                                    (
                                        HistoricalArchiveKey,
                                        HistoricalArchiveMetadata,
                                    ),
                                  ],
                              ),
                            ](
                              "historicalArchiveCorruptOrIncompatible: " + error.msg,
                            )
                          case Right(Some(bytes)) =>
                            IO.fromEither(decodeStoredProposal(bytes))
                              .as(
                                (
                                  written,
                                  seen + proposal.proposalId,
                                  metadataUpdates,
                                ),
                              )
                          case Right(None) =>
                            sharedStore.store
                              .put(key, encodeStoredProposal(proposal))
                              .as(
                                (
                                  written :+ proposal.proposalId,
                                  seen + proposal.proposalId,
                                  metadataUpdates :+ (
                                    key -> HistoricalArchiveMetadata(
                                      source,
                                      storedAt,
                                    )
                                  ),
                                ),
                              )
            _ <- metadataRef.update(_ ++ writeResult._3)
          yield writeResult._1
        }

      override def removeAll(
          chainId: ChainId,
          proposalIds: Vector[ProposalId],
      ): F[Int] =
        val dedupedIds = proposalIds.distinct
        lifecycleLock.permit.use { _ =>
          for
            _ <- ensureOpen
            removed <- LiftIO[F].liftIO:
              dedupedIds
                .traverse: proposalId =>
                  val key = HistoricalArchiveKey(chainId, proposalId)
                  sharedStore.store
                    .get(key)
                    .value
                    .flatMap:
                      case Left(error) =>
                        failIo[Boolean](
                          "historicalArchiveCorruptOrIncompatible: " + error.msg,
                        )
                      case Right(None) =>
                        IO.pure(false)
                      case Right(Some(bytes)) =>
                        IO
                          .fromEither(decodeStoredProposal(bytes))
                          .flatMap(_ => sharedStore.store.remove(key).as(true))
                .map(_.count(identity))
            _ <- metadataRef.update: current =>
              dedupedIds.foldLeft(current): (acc, proposalId) =>
                acc - HistoricalArchiveKey(chainId, proposalId)
          yield removed
        }

  private[node] def resetSharedStoresForTesting: IO[Unit] =
    SharedHistoricalArchiveStores.reset

  private def listEntries(
      store: org.sigilaris.node.jvm.storage.StoreIndex[
        IO,
        HistoricalArchiveKey,
        ByteVector,
      ],
      chainId: ChainId,
  ): IO[Vector[(HistoricalArchiveKey, ByteVector)]] =
    type ScanStep = Either[
      HistoricalArchiveScanState,
      Vector[(HistoricalArchiveKey, ByteVector)],
    ]
    Monad[IO].tailRecM(
      HistoricalArchiveScanState(
        cursor = HistoricalArchiveKey(
          chainId = chainId,
          proposalId = ScanStartProposalId,
        ),
        offset = 0,
        acc = Vector.empty[(HistoricalArchiveKey, ByteVector)],
      ),
    ): state =>
      store
        .from(state.cursor, state.offset, ListPageSize)
        .value
        .flatMap:
          case Left(error) =>
            failIo[ScanStep](
              "historicalArchiveCorruptOrIncompatible: " + error.msg,
            )
          case Right(page) =>
            val sameChain =
              page.iterator
                .takeWhile(_._1.chainId === chainId)
                .toVector
            val updated            = state.acc ++ sameChain
            val crossedChainBounds = sameChain.sizeCompare(page) < 0
            val pageShort          = page.sizeCompare(ListPageSize) < 0
            sameChain.lastOption match
              case None =>
                IO.pure(
                  Right[HistoricalArchiveScanState, Vector[
                    (HistoricalArchiveKey, ByteVector),
                  ]](updated),
                )
              case Some((lastKey, _)) if crossedChainBounds || pageShort =>
                IO.pure(
                  Right[HistoricalArchiveScanState, Vector[
                    (HistoricalArchiveKey, ByteVector),
                  ]](updated),
                )
              case Some((lastKey, _)) =>
                IO.pure(
                  Left[
                    HistoricalArchiveScanState,
                    Vector[(HistoricalArchiveKey, ByteVector)],
                  ](
                    HistoricalArchiveScanState(
                      cursor = lastKey,
                      offset = 1,
                      acc = updated,
                    ),
                  ),
                )

  private def encodeStoredProposal(
      proposal: Proposal,
  ): ByteVector =
    ByteVector(SchemaVersionV1) ++ ByteEncoder[Proposal].encode(proposal)

  private def decodeStoredProposal(
      bytes: ByteVector,
  ): Either[Throwable, Proposal] =
    bytes.headOption match
      case None =>
        archiveFailure("historicalArchiveCorruptOrIncompatible: emptyRecord")
          .asLeft[Proposal]
      case Some(version) if version =!= SchemaVersionV1 =>
        archiveFailure(
          "historicalArchiveUnknownSchemaVersion: 0x" + renderByteHex(version),
        ).asLeft[Proposal]
      case Some(_) =>
        ByteDecoder[Proposal]
          .decode(bytes.drop(1))
          .leftMap(error =>
            archiveFailure(
              "historicalArchiveCorruptOrIncompatible: " + error.msg,
            ),
          )
          .flatMap: decoded =>
            Either.cond(
              decoded.remainder.isEmpty,
              decoded.value,
              archiveFailure(
                "historicalArchiveCorruptOrIncompatible: decoded value has remainder",
              ),
            )

  private def archiveFailure(
      message: String,
  ): Throwable =
    new IllegalStateException(message)

  private def failIo[A](
      message: String,
  ): IO[A] =
    IO.raiseError[A](archiveFailure(message))

  private def renderByteHex(
      value: Byte,
  ): String =
    val alphabet = "0123456789abcdef"
    val unsigned = value.toInt & 0xff
    String.valueOf(
      Array(alphabet.charAt(unsigned >>> 4), alphabet.charAt(unsigned & 0x0f)),
    )

  @SuppressWarnings(
    Array(
      "org.wartremover.warts.Var",
      "org.wartremover.warts.MutableDataStructures",
    ),
  )
  private object SharedHistoricalArchiveStores:
    private var stores: Map[java.nio.file.Path, SharedHistoricalArchiveSlot] =
      Map.empty
    private var generation: Long       = 0L
    private var nextOpeningToken: Long = 0L

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def acquire(
        archiveDir: java.nio.file.Path,
    )(using Bag.Async[IO]): IO[SharedHistoricalArchiveHandle] =
      Deferred[IO, Either[Throwable, Unit]].flatMap: openingGate =>
        synchronizedLookup(archiveDir, openingGate) match
          case SharedHistoricalArchiveAcquire.Reuse(handle) =>
            IO.pure(handle)
          case SharedHistoricalArchiveAcquire.AwaitOpening(opening) =>
            opening.gate.get.flatMap(IO.fromEither) *> acquire(archiveDir)
          case SharedHistoricalArchiveAcquire.AwaitRelease(gate) =>
            gate.get *> acquire(archiveDir)
          case SharedHistoricalArchiveAcquire.Create(opening) =>
            SwayStores
              .storeIndex[HistoricalArchiveKey, ByteVector](archiveDir)
              .allocated
              .attempt
              .flatMap:
                case Left(error) =>
                  synchronizedOpenFailure(archiveDir, opening, error).flatMap:
                    case true =>
                      IO.raiseError[SharedHistoricalArchiveHandle](error)
                    case false => acquire(archiveDir)
                case Right((store, release)) =>
                  synchronizedInstall(
                    archiveDir = archiveDir,
                    store = store,
                    release = release,
                    opening = opening,
                  ).flatMap:
                    case SharedHistoricalArchiveInstall.Installed(
                          handle,
                          signalReady,
                        ) =>
                      signalReady.as(handle)
                    case SharedHistoricalArchiveInstall.DisposeAndReuse(
                          dispose,
                          handle,
                          signalReady,
                        ) =>
                      dispose *> signalReady.as(handle)
                    case SharedHistoricalArchiveInstall.DisposeAndAwait(
                          dispose,
                          gate,
                          signalReady,
                        ) =>
                      dispose *> signalReady *> gate.get *> acquire(archiveDir)
                    case SharedHistoricalArchiveInstall.DisposeOnly(
                          dispose,
                          signalReady,
                        ) =>
                      dispose *> signalReady *> acquire(archiveDir)

    private def synchronizedLookup(
        archiveDir: java.nio.file.Path,
        openingGate: Deferred[IO, Either[Throwable, Unit]],
    ): SharedHistoricalArchiveAcquire =
      this.synchronized:
        stores.get(archiveDir) match
          case Some(SharedHistoricalArchiveSlot.Open(current)) =>
            val retained = current.copy(refCount = current.refCount + 1)
            stores = stores.updated(
              archiveDir,
              SharedHistoricalArchiveSlot.Open(retained),
            )
            SharedHistoricalArchiveAcquire.Reuse(
              handleFor(archiveDir, retained),
            )
          case Some(SharedHistoricalArchiveSlot.Opening(opening)) =>
            SharedHistoricalArchiveAcquire.AwaitOpening(opening)
          case Some(SharedHistoricalArchiveSlot.Releasing(gate)) =>
            SharedHistoricalArchiveAcquire.AwaitRelease(gate)
          case None =>
            val opening =
              SharedHistoricalArchiveOpening(
                token = allocateOpeningToken(),
                gate = openingGate,
              )
            stores = stores.updated(
              archiveDir,
              SharedHistoricalArchiveSlot.Opening(opening),
            )
            SharedHistoricalArchiveAcquire.Create(opening)

    private def synchronizedInstall(
        archiveDir: java.nio.file.Path,
        store: org.sigilaris.node.jvm.storage.StoreIndex[
          IO,
          HistoricalArchiveKey,
          ByteVector,
        ],
        release: IO[Unit],
        opening: SharedHistoricalArchiveOpening,
    ): IO[SharedHistoricalArchiveInstall] =
      this.synchronized:
        val notifyReady =
          opening.gate.complete(Right[Throwable, Unit](())).attempt.void
        stores.get(archiveDir) match
          case Some(SharedHistoricalArchiveSlot.Opening(currentOpening))
              if currentOpening.token == opening.token =>
            val retained =
              SharedHistoricalArchiveStore(
                store = store,
                release = release,
                refCount = 1,
              )
            stores = stores.updated(
              archiveDir,
              SharedHistoricalArchiveSlot.Open(retained),
            )
            SharedHistoricalArchiveInstall
              .Installed(
                handleFor(archiveDir, retained),
                signalReady = notifyReady,
              )
              .pure[IO]
          case Some(SharedHistoricalArchiveSlot.Open(current)) =>
            val retained = current.copy(refCount = current.refCount + 1)
            stores = stores.updated(
              archiveDir,
              SharedHistoricalArchiveSlot.Open(retained),
            )
            SharedHistoricalArchiveInstall
              .DisposeAndReuse(
                dispose = release,
                handle = handleFor(archiveDir, retained),
                signalReady = notifyReady,
              )
              .pure[IO]
          case Some(SharedHistoricalArchiveSlot.Opening(_)) =>
            SharedHistoricalArchiveInstall
              .DisposeOnly(
                dispose = release,
                signalReady = notifyReady,
              )
              .pure[IO]
          case Some(SharedHistoricalArchiveSlot.Releasing(gate)) =>
            SharedHistoricalArchiveInstall
              .DisposeAndAwait(
                dispose = release,
                gate = gate,
                signalReady = notifyReady,
              )
              .pure[IO]
          case None =>
            SharedHistoricalArchiveInstall
              .DisposeOnly(
                dispose = release,
                signalReady = notifyReady,
              )
              .pure[IO]

    private def synchronizedOpenFailure(
        archiveDir: java.nio.file.Path,
        opening: SharedHistoricalArchiveOpening,
        error: Throwable,
    ): IO[Boolean] =
      this.synchronized:
        stores.get(archiveDir) match
          case Some(SharedHistoricalArchiveSlot.Opening(currentOpening))
              if currentOpening.token == opening.token =>
            stores = stores - archiveDir
            opening.gate.complete(Left[Throwable, Unit](error)).attempt.as(true)
          case _ =>
            opening.gate.complete(Right[Throwable, Unit](())).attempt.as(false)

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    private def releaseFor(
        archiveDir: java.nio.file.Path,
    ): IO[Unit] =
      Deferred[IO, Unit].flatMap: closedGate =>
        this.synchronized:
          stores.get(archiveDir) match
            case Some(SharedHistoricalArchiveSlot.Opening(opening)) =>
              opening.gate.get.flatMap(IO.fromEither) *> releaseFor(archiveDir)
            case Some(SharedHistoricalArchiveSlot.Open(current))
                if current.refCount > 1 =>
              stores = stores.updated(
                archiveDir,
                SharedHistoricalArchiveSlot.Open(
                  current.copy(refCount = current.refCount - 1),
                ),
              )
              IO.unit
            case Some(SharedHistoricalArchiveSlot.Open(current)) =>
              stores = stores.updated(
                archiveDir,
                SharedHistoricalArchiveSlot.Releasing(closedGate),
              )
              current.release.guarantee:
                IO.delay:
                  this.synchronized:
                    stores.get(archiveDir) match
                      case Some(SharedHistoricalArchiveSlot.Releasing(_)) =>
                        stores = stores - archiveDir
                      case _ =>
                        ()
                *> closedGate.complete(()).void
            case Some(SharedHistoricalArchiveSlot.Releasing(existing)) =>
              existing.get
            case None =>
              IO.unit

    private def handleFor(
        archiveDir: java.nio.file.Path,
        current: SharedHistoricalArchiveStore,
    ): SharedHistoricalArchiveHandle =
      val generationSnapshot = currentGeneration
      SharedHistoricalArchiveHandle(
        store = current.store,
        release = releaseFor(archiveDir),
        isCurrent = IO.delay:
          this.synchronized:
            generation == generationSnapshot,
      )

    def reset: IO[Unit] =
      this.synchronized:
        val outstanding = stores.values.toVector
        generation = generation + 1L
        stores = Map.empty
        outstanding.traverse_ { slot =>
          slot match
            case SharedHistoricalArchiveSlot.Opening(gate) =>
              gate.gate.get.attempt.void
            case SharedHistoricalArchiveSlot.Open(current) =>
              current.release.attempt.void
            case SharedHistoricalArchiveSlot.Releasing(gate) =>
              gate.get
        }

    private def currentGeneration: Long =
      this.synchronized:
        generation

    private def allocateOpeningToken(): Long =
      val token = nextOpeningToken
      nextOpeningToken = nextOpeningToken + 1L
      token
