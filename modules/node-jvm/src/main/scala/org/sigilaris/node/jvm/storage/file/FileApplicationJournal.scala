package org.sigilaris.node.jvm.storage.file

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{
  Files,
  LinkOption,
  Path,
  StandardCopyOption,
  StandardOpenOption,
}
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*
import scala.util.Using

import cats.data.EitherT
import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Semaphore

import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.datatype.{UInt256, Utf8}
import org.sigilaris.node.jvm.runtime.application.v2.*

/** Authoritative local-filesystem journal. Every successful write includes the
  * file/directory durability barriers; SwayDB or other indexes may be rebuilt
  * from this history. Unsupported atomic moves or directory force fail closed.
  * Deployment requires a trusted local filesystem that honors those JDK
  * durability operations; this backend cannot attest remote filesystem
  * behavior.
  *
  * Opening acquires an exclusive process lock but leaves the backend fenced.
  * Call recover before any write or ordinary blob read. A truncated frame is
  * retained unchanged and never treated as evidence that a claim was absent.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.Any",
  ),
)
final class FileApplicationJournal private (
    root: Path,
    channel: FileChannel,
    identity: FileApplicationJournal.PhysicalIdentity,
    gate: Semaphore[IO],
    state: Ref[IO, FileApplicationJournal.State],
    faults: JournalFaultInjector[IO],
) extends DurableJournal[IO]:
  import FileApplicationJournal.*

  private def underGate[A](
      run: State => IO[Either[V2RuntimeFailure, A]],
  ): Result[IO, A] =
    EitherT(
      gate.permit.use(_ =>
        IO.uncancelable(_ =>
          state.get.flatMap(current =>
            if current.closed then IO.pure(Left(recoveryRequired))
            else
              IO.blocking(validateIdentity(root, identity)).attempt.flatMap {
                case Right(_)    => run(current)
                case Left(error) =>
                  state
                    .update(_.copy(poisoned = true))
                    .as(Left(storageFailure(error)))
              },
          ),
        ),
      ),
    )

  private def closeForResource: IO[Unit] =
    gate.permit.use(_ => state.update(_.copy(poisoned = true, closed = true)))

  private def durable[A](run: IO[A]): IO[Either[V2RuntimeFailure, A]] =
    state.update(_.copy(poisoned = true)) *> run.attempt.flatMap:
      case Left(error)  => IO.pure(Left(storageFailure(error)))
      case Right(value) =>
        state.update(_.copy(poisoned = false)).as(Right(value))

  private def after(
      point: JournalFaultPoint,
      sequence: Long,
      enabled: Boolean,
  ): IO[Unit] =
    if enabled then faults.after(point, sequence) else IO.unit

  def recover: Result[IO, Vector[JournalRecord]] = underGate: current =>
    durable:
      for
        recovered <- IO.blocking:
          validateLayout(root)
          val history = readHistory(channel)
          if !history.records.startsWith(current.history.records) then
            abort(
              RuntimeFailureCode.JournalCorrupt,
              "journal recovery lost already observed authoritative history",
            )
          history
        size <- IO.blocking(channel.size())
        // Retain every complete frame actually observed, even if the next
        // force or HEAD publication has an unknown outcome.
        _ <- state.set(State(recovered, size, true, false))
        _ <- IO.blocking {
          val head = readHead(root.resolve(HeadName))
          required(JournalHistory.validateHead(recovered, head))
          validateBlobs(root)
          channel.force(true)
        }
        _ <- writeHead(recovered.head, inject = false)
      yield recovered.records

  def append(record: JournalRecord): Result[IO, Unit] = underGate: current =>
    if current.poisoned then IO.pure(Left(recoveryRequired))
    else if current.history.records.lastOption.contains(record) then
      IO.pure(Right(()))
    else
      JournalHistory.append(current.history, record) match
        case Left(error) => IO.pure(Left(error))
        case Right(next) =>
          JournalRecord.codec
            .encode(record)
            .left
            .map(V2RuntimeFailure.fromCore) match
            case Left(error)    => IO.pure(Left(error))
            case Right(encoded) =>
              durable:
                val bytes = frame(encoded)
                for
                  _ <- IO.blocking:
                    if channel.size() != current.physicalBytes then
                      abort(
                        RuntimeFailureCode.JournalCorrupt,
                        "journal size changed outside the exclusive writer",
                      )
                    channel.position(current.physicalBytes)
                    writeAll(channel, bytes)
                  _ <- state.set(
                    State(next, current.physicalBytes + bytes.size, true, false),
                  )
                  _ <- after(
                    JournalFaultPoint.AfterFrameWrite,
                    record.sequence,
                    true,
                  )
                  _ <- IO.blocking(channel.force(true))
                  _ <- after(
                    JournalFaultPoint.AfterFrameForce,
                    record.sequence,
                    true,
                  )
                  _ <-
                    if record.status == JournalStatus.Committed then
                      writeHead(next.head, inject = true)
                    else IO.unit
                  _ <- state.set(
                    State(next, current.physicalBytes + bytes.size, true, false),
                  )
                yield ()

  def putBlob(namespace: Text, digest: Hash, bytes: Bytes): Result[IO, Unit] =
    underGate: current =>
      if current.poisoned then IO.pure(Left(recoveryRequired))
      else
        JournalHistory.namespace(namespace) match
          case Left(error) => IO.pure(Left(error))
          case Right(name) =>
            val directory = root.resolve(BlobsName).resolve(name)
            val target    = directory.resolve(digest.bytes.toHex + ".blob")
            IO.blocking(Files.exists(target, LinkOption.NOFOLLOW_LINKS))
              .attempt
              .flatMap:
                case Left(error) =>
                  state
                    .update(_.copy(poisoned = true))
                    .as(Left(storageFailure(error)))
                case Right(true) =>
                  readExistingBlob(target, name, digest).map(
                    _.flatMap(existing =>
                      Either.cond(
                        existing == bytes,
                        (),
                        V2RuntimeFailure.at(
                          RuntimeFailureCode.CommitmentMismatch,
                          "blob identifier is already bound to different bytes",
                        ),
                      ),
                    ),
                  )
                case Right(false) =>
                  durable:
                    for
                      _ <- IO.blocking:
                        if !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                        then
                          Files.createDirectory(directory)
                          forceDirectory(root.resolve(BlobsName))
                        ensureDirectory(directory)
                      temporary <- IO.blocking(
                        directory.resolve(
                          ".blob-" + UUID.randomUUID().toString + ".tmp",
                        ),
                      )
                      _ <- writable(temporary).use: output =>
                        IO.blocking(
                          writeAll(output, blobFrame(name, digest, bytes)),
                        ) *>
                          after(
                            JournalFaultPoint.AfterBlobWrite,
                            current.history.head.sequence,
                            true,
                          ) *>
                          IO.blocking(output.force(true)) *>
                          after(
                            JournalFaultPoint.AfterBlobForce,
                            current.history.head.sequence,
                            true,
                          )
                      _ <- IO.blocking:
                        Files.move(
                          temporary,
                          target,
                          StandardCopyOption.ATOMIC_MOVE,
                        )
                        ()
                      _ <- after(
                        JournalFaultPoint.AfterBlobMove,
                        current.history.head.sequence,
                        true,
                      )
                      _ <- IO.blocking(forceDirectory(directory))
                      _ <- after(
                        JournalFaultPoint.AfterBlobDirectoryForce,
                        current.history.head.sequence,
                        true,
                      )
                    yield ()

  def readBlob(namespace: Text, digest: Hash): Result[IO, Bytes] = underGate:
    current =>
      if current.poisoned then IO.pure(Left(recoveryRequired))
      else
        JournalHistory.namespace(namespace) match
          case Left(error) => IO.pure(Left(error))
          case Right(name) =>
            val path = root
              .resolve(BlobsName)
              .resolve(name)
              .resolve(digest.bytes.toHex + ".blob")
            readExistingBlob(path, name, digest)

  private def readExistingBlob(
      path: Path,
      namespace: String,
      digest: Hash,
  ): IO[Either[V2RuntimeFailure, Bytes]] =
    IO.blocking:
      if !Files.exists(path, LinkOption.NOFOLLOW_LINKS) then
        abort(
          RuntimeFailureCode.ProofUnavailable,
          "immutable blob is unavailable",
        )
      ensureDirectory(path.getParent)
      readBlobFile(path, namespace, digest)
    .attempt
      .flatMap:
        case Right(bytes) => IO.pure(Right(bytes))
        case Left(error)  =>
          val failure = storageFailure(error)
          if failure.code == RuntimeFailureCode.ProofUnavailable then
            IO.pure(Left(failure))
          else state.update(_.copy(poisoned = true)).as(Left(failure))

  private def writeHead(head: JournalHead, inject: Boolean): IO[Unit] =
    for
      temporary <- IO.blocking(
        root.resolve(".HEAD-" + UUID.randomUUID().toString + ".tmp"),
      )
      _ <- writable(temporary).use: output =>
        IO.blocking(writeAll(output, headFrame(head))) *>
          after(JournalFaultPoint.AfterHeadWrite, head.sequence, inject) *>
          IO.blocking(output.force(true))
      _ <- IO.blocking:
        Files.move(
          temporary,
          root.resolve(HeadName),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
        )
        ()
      _ <- after(JournalFaultPoint.AfterHeadMove, head.sequence, inject)
      _ <- IO.blocking(forceDirectory(root))
      _ <- after(JournalFaultPoint.AfterHeadForce, head.sequence, inject)
    yield ()

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Throw",
    "org.wartremover.warts.Var",
    "org.wartremover.warts.While",
    "org.wartremover.warts.ToString",
  ),
)
object FileApplicationJournal:
  private final case class PhysicalIdentity(
      root: Object,
      lock: Object,
      log: Object,
  )
  private def fileIdentity(path: Path): Object =
    Option(
      Files
        .readAttributes(
          path,
          classOf[BasicFileAttributes],
          LinkOption.NOFOLLOW_LINKS,
        )
        .fileKey(),
    )
      .getOrElse(
        abort(
          RuntimeFailureCode.JournalCorrupt,
          "journal filesystem has no stable physical identity",
        ),
      )
  private def validateIdentity(root: Path, expected: PhysicalIdentity): Unit =
    ensureDirectory(root)
    ensureFile(root.resolve(LockName))
    ensureFile(root.resolve(LogName))
    if fileIdentity(root) != expected.root || fileIdentity(
        root.resolve(LockName),
      ) != expected.lock ||
      fileIdentity(root.resolve(LogName)) != expected.log
    then
      abort(
        RuntimeFailureCode.JournalCorrupt,
        "journal path no longer names the exclusively owned physical resource",
      )

  private final case class State(
      history: JournalHistory,
      physicalBytes: Long,
      poisoned: Boolean,
      closed: Boolean,
  )

  // Opening and then closing a second channel can release another channel's
  // process locks on some systems. Refuse duplicate JVM owners before opening.
  private val localOwners = ConcurrentHashMap.newKeySet[Path]()

  private val LogName    = "journal.log"
  private val HeadName   = "HEAD"
  private val LockName   = "LOCK"
  private val BlobsName  = "blobs"
  private val FrameMagic =
    ByteVector.view("SGAJNL01".getBytes(StandardCharsets.US_ASCII))
  private val FrameEnd =
    ByteVector.view("SGAJEND1".getBytes(StandardCharsets.US_ASCII))
  private val HeadMagic =
    ByteVector.view("SGAHEAD1".getBytes(StandardCharsets.US_ASCII))
  private val BlobMagic =
    ByteVector.view("SGABLOB1".getBytes(StandardCharsets.US_ASCII))
  private val FrameDomain = Utf8(
    "sigilaris.application.storage.journal-frame.v1",
  )
  private val HeadDomain = Utf8("sigilaris.application.storage.journal-head.v1")
  private val BlobDomain = Utf8("sigilaris.application.storage.blob.v1")
  private val FrameHeaderBytes  = 24L
  private val FrameTrailerBytes = 40L
  private val HeadBytes         = 88L

  def resource(root: Path): Resource[IO, FileApplicationJournal] =
    resourceWithFaults(root, JournalFaultInjector.none[IO])

  def resourceWithFaults(
      root: Path,
      faults: JournalFaultInjector[IO],
  ): Resource[IO, FileApplicationJournal] =
    val opened = for
      directory <- Resource.eval(IO.blocking:
        createDirectoriesDurably(root.toAbsolutePath.normalize())
        ensureDirectory(root)
        val directory = root.toRealPath()
        if children(directory).nonEmpty then
          ensureFile(directory.resolve(LockName))
        directory)
      _ <- Resource.make(IO.delay:
        if !localOwners.add(directory) then
          abort(
            RuntimeFailureCode.RecoveryRequired,
            "journal is already owned by this JVM",
          ))(_ => IO.delay { val _ = localOwners.remove(directory); () })
      lockChannel <- Resource.make(
        IO.blocking(
          FileChannel.open(
            directory.resolve(LockName),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
          ),
        ),
      )(value => IO.blocking(value.close()))
      _ <- Resource.make(IO.blocking:
        Option(lockChannel.tryLock()).getOrElse(
          throw new JournalOpenException(
            V2RuntimeFailure.at(
              RuntimeFailureCode.RecoveryRequired,
              "journal is already owned by another process",
            ),
          ),
        ))(value => IO.blocking(value.release()))
      _ <- Resource.eval(IO.blocking:
        lockChannel.force(true)
        forceDirectory(directory)
        initializeLayout(directory))
      channel <- Resource.make(
        IO.blocking(
          FileChannel.open(
            directory.resolve(LogName),
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
          ),
        ),
      )(value => IO.blocking(value.close()))
      identity <- Resource.eval(
        IO.blocking(
          PhysicalIdentity(
            fileIdentity(directory),
            fileIdentity(directory.resolve(LockName)),
            fileIdentity(directory.resolve(LogName)),
          ),
        ),
      )
      gate  <- Resource.eval(Semaphore[IO](1L))
      state <- Resource.eval(
        Ref.of[IO, State](State(JournalHistory.empty, 0L, true, false)),
      )
      store <- Resource.make(
        IO.pure(
          new FileApplicationJournal(
            directory,
            channel,
            identity,
            gate,
            state,
            faults,
          ),
        ),
      )(_.closeForResource)
    yield store
    opened.handleErrorWith(error =>
      Resource.eval(
        IO.raiseError(new JournalOpenException(storageFailure(error))),
      ),
    )

  private def recoveryRequired: V2RuntimeFailure =
    V2RuntimeFailure.at(
      RuntimeFailureCode.RecoveryRequired,
      "journal recovery is required",
    )

  private def storageFailure(error: Throwable): V2RuntimeFailure = error match
    case value: JournalOpenException                       => value.failure
    case _: java.nio.channels.OverlappingFileLockException =>
      V2RuntimeFailure.at(
        RuntimeFailureCode.RecoveryRequired,
        "journal is already owned by another process",
      )
    case _ =>
      V2RuntimeFailure.at(
        RuntimeFailureCode.StorageUnknown,
        Option(error.getMessage).getOrElse(error.getClass.getName),
      )

  private def abort(code: RuntimeFailureCode, detail: String): Nothing =
    throw new JournalOpenException(V2RuntimeFailure.at(code, detail))

  private def required[A](value: Either[V2RuntimeFailure, A]): A =
    value.fold(error => throw new JournalOpenException(error), identity)

  private def longBytes(value: Long): ByteVector =
    ByteVector.view(ByteBuffer.allocate(8).putLong(value).array())
  private def readLong(bytes: ByteVector): Long =
    ByteBuffer.wrap(bytes.toArray).getLong()

  private def frame(record: ByteVector): ByteVector =
    val header = FrameMagic ++ longBytes(1L) ++ longBytes(record.size)
    header ++ record ++ Commitment
      .hash(FrameDomain, header ++ record)
      .bytes ++ FrameEnd

  private def headFrame(head: JournalHead): ByteVector =
    val content = HeadMagic ++ longBytes(1L) ++ longBytes(
      head.sequence,
    ) ++ head.digest.bytes
    content ++ Commitment.hash(HeadDomain, content).bytes

  private def blobFrame(
      namespace: String,
      digest: Hash,
      bytes: Bytes,
  ): ByteVector =
    val header = BlobMagic ++ longBytes(bytes.size)
    header ++ bytes ++ blobChecksum(namespace, digest, header, bytes).bytes

  private def blobChecksum(
      namespace: String,
      digest: Hash,
      header: Bytes,
      bytes: Bytes,
  ): Hash =
    val name = ByteVector.view(namespace.getBytes(StandardCharsets.US_ASCII))
    Commitment.hash(
      BlobDomain,
      longBytes(name.size) ++ name ++ digest.bytes ++ header ++ bytes,
    )

  private def readHistory(channel: FileChannel): JournalHistory =
    val size    = channel.size()
    var offset  = 0L
    var history = JournalHistory.empty
    while offset < size do
      val remaining = size - offset
      if remaining < FrameHeaderBytes + FrameTrailerBytes then
        abort(
          RuntimeFailureCode.JournalCorrupt,
          "truncated journal frame retained at offset " + offset.toString,
        )
      val header = readExact(channel, offset, FrameHeaderBytes.toInt)
      if header.take(8L) != FrameMagic || readLong(header.slice(8L, 16L)) != 1L
      then
        abort(RuntimeFailureCode.JournalCorrupt, "unknown journal frame format")
      val length = readLong(header.drop(16L))
      if length <= 0L || length > remaining - FrameHeaderBytes - FrameTrailerBytes
      then
        abort(
          RuntimeFailureCode.JournalCorrupt,
          "invalid or truncated journal frame length",
        )
      if length > Int.MaxValue.toLong then
        abort(
          RuntimeFailureCode.CapacityUnavailable,
          "journal record exceeds addressable decoder capacity",
        )
      val recordBytes =
        readExact(channel, offset + FrameHeaderBytes, length.toInt)
      val trailer = readExact(
        channel,
        offset + FrameHeaderBytes + length,
        FrameTrailerBytes.toInt,
      )
      if trailer.take(32L) != Commitment
          .hash(FrameDomain, header ++ recordBytes)
          .bytes || trailer.drop(32L) != FrameEnd
      then
        abort(
          RuntimeFailureCode.JournalCorrupt,
          "journal frame checksum or footer differs",
        )
      val record = JournalRecord.codec
        .decode(recordBytes)
        .fold(
          error => abort(RuntimeFailureCode.JournalCorrupt, error.message),
          identity,
        )
      history = required(JournalHistory.append(history, record))
      offset += FrameHeaderBytes + length + FrameTrailerBytes
    history

  private def readHead(path: Path): JournalHead =
    ensureFile(path)
    Using.resource(
      FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
    ): input =>
      if input.size() != HeadBytes then
        abort(RuntimeFailureCode.JournalCorrupt, "invalid durable HEAD length")
      val bytes   = readExact(input, 0L, HeadBytes.toInt)
      val content = bytes.take(56L)
      if bytes.take(8L) != HeadMagic || readLong(bytes.slice(8L, 16L)) != 1L ||
        Commitment.hash(HeadDomain, content).bytes != bytes.drop(56L)
      then
        abort(
          RuntimeFailureCode.JournalCorrupt,
          "invalid durable HEAD format or checksum",
        )
      val sequence = readLong(bytes.slice(16L, 24L))
      val digest   = UInt256.unsafeFromBytesBE(bytes.slice(24L, 56L))
      if sequence < 0L || (sequence == 0L && digest != JournalHead.empty.digest)
      then
        abort(
          RuntimeFailureCode.JournalCorrupt,
          "invalid durable HEAD sequence",
        )
      JournalHead(sequence, digest)

  private def readBlobFile(path: Path, namespace: String, digest: Hash): Bytes =
    ensureFile(path)
    Using.resource(
      FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
    ): input =>
      val size = input.size()
      if size < 48L then
        abort(RuntimeFailureCode.JournalCorrupt, "truncated immutable blob")
      val header = readExact(input, 0L, 16)
      val length = readLong(header.drop(8L))
      if header.take(8L) != BlobMagic || length < 0L || length != size - 48L
      then
        abort(
          RuntimeFailureCode.JournalCorrupt,
          "invalid immutable blob format or length",
        )
      if length > Int.MaxValue.toLong then
        abort(
          RuntimeFailureCode.CapacityUnavailable,
          "blob exceeds addressable decoder capacity",
        )
      val bytes    = readExact(input, 16L, length.toInt)
      val checksum = readExact(input, 16L + length, 32)
      if checksum != blobChecksum(namespace, digest, header, bytes).bytes then
        abort(
          RuntimeFailureCode.JournalCorrupt,
          "immutable blob checksum or key binding differs",
        )
      bytes

  private def validateBlobs(root: Path): Unit =
    children(root.resolve(BlobsName)).foreach: directory =>
      ensureDirectory(directory)
      val namespace =
        required(JournalHistory.namespace(Utf8(directory.getFileName.toString)))
      children(directory).foreach: file =>
        val name = file.getFileName.toString
        ensureFile(file)
        if name.matches("[0-9a-f]{64}\\.blob") then
          val digest = UInt256
            .fromHex(name.stripSuffix(".blob"))
            .fold(
              error => abort(RuntimeFailureCode.JournalCorrupt, error.toString),
              identity,
            )
          val _ = readBlobFile(file, namespace, digest)
        else if !name.matches("\\.blob-[0-9a-f-]{36}\\.tmp") then
          abort(
            RuntimeFailureCode.JournalCorrupt,
            "unknown blob directory entry",
          )
      forceDirectory(directory)
    forceDirectory(root.resolve(BlobsName))

  private def initializeLayout(root: Path): Unit =
    val names = children(root).map(_.getFileName.toString)
    if names.toSet == Set(LockName) then
      Files.createDirectory(root.resolve(BlobsName))
      Using.resource(
        FileChannel.open(
          root.resolve(LogName),
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE,
        ),
      )(_.force(true))
      val temporary =
        root.resolve(".HEAD-" + UUID.randomUUID().toString + ".tmp")
      Using.resource(
        FileChannel.open(
          temporary,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE,
        ),
      ): output =>
        writeAll(output, headFrame(JournalHead.empty))
        output.force(true)
      Files.move(
        temporary,
        root.resolve(HeadName),
        StandardCopyOption.ATOMIC_MOVE,
      )
      forceDirectory(root.resolve(BlobsName))
      forceDirectory(root)
    validateLayout(root)

  private def validateLayout(root: Path): Unit =
    ensureDirectory(root)
    ensureFile(root.resolve(LockName))
    ensureFile(root.resolve(LogName))
    ensureFile(root.resolve(HeadName))
    ensureDirectory(root.resolve(BlobsName))
    children(root).foreach: path =>
      val name = path.getFileName.toString
      if !Set(LockName, LogName, HeadName, BlobsName).contains(name) && !name
          .matches("\\.HEAD-[0-9a-f-]{36}\\.tmp")
      then
        abort(
          RuntimeFailureCode.JournalCorrupt,
          "unknown application journal directory entry",
        )
      if name.startsWith(".HEAD-") then ensureFile(path)

  private def ensureFile(path: Path): Unit =
    if !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) then
      abort(
        RuntimeFailureCode.JournalCorrupt,
        "missing or nonregular durable journal file",
      )

  private def ensureDirectory(path: Path): Unit =
    if !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) then
      abort(
        RuntimeFailureCode.JournalCorrupt,
        "missing or non-directory durable journal namespace",
      )

  private def children(path: Path): Vector[Path] =
    Using.resource(Files.list(path))(_.iterator().asScala.toVector)

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def createDirectoriesDurably(path: Path): Unit =
    if !Files.exists(path, LinkOption.NOFOLLOW_LINKS) then
      Option(path.getParent).foreach(createDirectoriesDurably)
      Files.createDirectory(path)
      Option(path.getParent).foreach(forceDirectory)
    ensureDirectory(path)

  private def forceDirectory(path: Path): Unit =
    Using.resource(
      FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
    )(_.force(true))

  private def writable(path: Path): Resource[IO, FileChannel] =
    Resource.make(
      IO.blocking(
        FileChannel.open(
          path,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE,
          LinkOption.NOFOLLOW_LINKS,
        ),
      ),
    )(value => IO.blocking(value.close()))

  private def writeAll(channel: FileChannel, bytes: Bytes): Unit =
    val buffer = ByteBuffer.wrap(bytes.toArray)
    while buffer.hasRemaining do
      if channel.write(buffer) <= 0 then
        throw new IOException("durable file write made no progress")

  private def readExact(
      channel: FileChannel,
      offset: Long,
      length: Int,
  ): ByteVector =
    val buffer   = ByteBuffer.allocate(length)
    var position = offset
    while buffer.hasRemaining do
      val count = channel.read(buffer, position)
      if count <= 0 then
        abort(
          RuntimeFailureCode.JournalCorrupt,
          "durable file ended during a complete-record read",
        )
      position += count.toLong
    ByteVector.view(buffer.array())
