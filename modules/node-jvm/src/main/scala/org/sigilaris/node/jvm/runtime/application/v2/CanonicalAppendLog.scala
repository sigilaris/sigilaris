package org.sigilaris.node.jvm.runtime.application.v2

import java.nio.ByteBuffer
import java.nio.channels.{FileChannel, FileLock}
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{
  Files,
  LinkOption,
  Path,
  StandardCopyOption,
  StandardOpenOption,
}
import java.util.UUID

import cats.data.EitherT
import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.datatype.{UInt256, Utf8}

/** Physical framing only. The owning historical materializer authenticates
  * canonical records and their original execution/finality before readiness.
  * Each append is forced before its atomic HEAD; complete tails are retained.
  */
private[v2] trait CanonicalAppendLog:
  def recover: Result[IO, Vector[Bytes]]
  def append(bytes: Bytes): Result[IO, Unit]

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.Any",
  ),
)
private[v2] object CanonicalAppendLog:
  private val zero = UInt256.unsafeFromBigIntUnsigned(BigInt(0))
  private final case class Contents(
      frames: Vector[Bytes],
      digests: Vector[Hash],
  ):
    def head: Hash = digests.lastOption.getOrElse(zero)
  private final case class State(
      contents: Contents,
      poisoned: Boolean,
      closed: Boolean,
  )
  private final case class Handles(
      root: Path,
      lockChannel: FileChannel,
      lock: FileLock,
      records: FileChannel,
      recordsIdentity: Object,
  )

  private def failure(
      code: RuntimeFailureCode,
      message: String,
  ): V2RuntimeFailure =
    V2RuntimeFailure.at(code, message)
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def raised(code: RuntimeFailureCode, message: String): Nothing =
    throw new JournalOpenException(failure(code, message))
  private def error(value: Throwable): V2RuntimeFailure = value match
    case known: JournalOpenException => known.failure
    case _                           =>
      failure(
        RuntimeFailureCode.StorageUnknown,
        "historical canonical journal storage failed: " + value.getClass.getName,
      )

  private def contentHash(bytes: Bytes): Hash =
    Commitment.hash(
      Utf8("sigilaris.application.historical-canonical.frame.v1"),
      bytes,
    )
  private def nextHash(previous: Hash, bytes: Bytes): Hash =
    Commitment.hash(
      Utf8("sigilaris.application.historical-canonical.chain.v1"),
      previous.bytes ++ contentHash(bytes).bytes,
    )
  private def longBytes(value: Long): Bytes =
    ByteVector.view(ByteBuffer.allocate(8).putLong(value).array())
  @SuppressWarnings(Array("org.wartremover.warts.While"))
  private def writeAll(channel: FileChannel, bytes: Bytes): Unit =
    val buffer = ByteBuffer.wrap(bytes.toArray)
    while buffer.hasRemaining do
      val _ = channel.write(buffer)
  @SuppressWarnings(Array("org.wartremover.warts.While"))
  private def readAll(channel: FileChannel, size: Int): Bytes =
    val buffer = ByteBuffer.allocate(size)
    while buffer.hasRemaining do
      if channel.read(buffer) < 0 then
        raised(
          RuntimeFailureCode.JournalCorrupt,
          "historical canonical frame is incomplete",
        )
    ByteVector.view(buffer.array())
  private def regular(path: Path, required: Boolean): Unit =
    if Files.exists(path, LinkOption.NOFOLLOW_LINKS) then
      if Files.isSymbolicLink(path) || !Files.isRegularFile(
          path,
          LinkOption.NOFOLLOW_LINKS,
        )
      then
        raised(
          RuntimeFailureCode.JournalCorrupt,
          "historical canonical storage contains a nonregular file",
        )
    else if required then
      raised(
        RuntimeFailureCode.EvidenceMissing,
        "historical canonical storage file is missing",
      )
  private def forceDirectory(path: Path): Unit =
    val channel = FileChannel.open(path, StandardOpenOption.READ)
    try channel.force(true)
    finally channel.close()

  private def fileIdentity(path: Path): Object =
    regular(path, true)
    Option(
      Files
        .readAttributes(
          path,
          classOf[BasicFileAttributes],
          LinkOption.NOFOLLOW_LINKS,
        )
        .fileKey(),
    )
      .fold[Object](
        raised(
          RuntimeFailureCode.UnsafeBoundary,
          "historical canonical filesystem has no stable file identity",
        ),
      )(identity)

  @SuppressWarnings(
    Array("org.wartremover.warts.Var", "org.wartremover.warts.While"),
  )
  private def readContents(handles: Handles, maximumBytes: Long): Contents =
    if fileIdentity(handles.root.resolve("records")) != handles.recordsIdentity
    then
      raised(
        RuntimeFailureCode.JournalCorrupt,
        "historical canonical record file was replaced outside its writer",
      )
    val channel = handles.records
    val length  = channel.size()
    if length > maximumBytes then
      raised(
        RuntimeFailureCode.CapacityUnavailable,
        "historical canonical history exceeds configured local capacity",
      )
    val _        = channel.position(0L)
    var frames   = Vector.empty[Bytes]
    var digests  = Vector.empty[Hash]
    var previous = zero
    while channel.position() < length do
      if length - channel.position() < 8L then
        raised(
          RuntimeFailureCode.JournalCorrupt,
          "historical canonical frame length is incomplete",
        )
      val count = ByteBuffer.wrap(readAll(channel, 8).toArray).getLong()
      if count < 0L || count > Int.MaxValue.toLong || count > maximumBytes - 40L
      then
        raised(
          RuntimeFailureCode.CapacityUnavailable,
          "historical canonical frame exceeds configured local capacity",
        )
      if length - channel.position() < count + 32L then
        raised(
          RuntimeFailureCode.JournalCorrupt,
          "historical canonical frame payload is incomplete",
        )
      val payload  = readAll(channel, count.toInt)
      val checksum = readAll(channel, 32)
      if checksum != contentHash(payload).bytes then
        raised(
          RuntimeFailureCode.JournalCorrupt,
          "historical canonical frame checksum differs",
        )
      previous = nextHash(previous, payload)
      frames = frames :+ payload
      digests = digests :+ previous
    Contents(frames, digests)

  private def readHead(root: Path, contents: Contents): Unit =
    val path = root.resolve("HEAD")
    regular(path, true)
    val bytes = Files.readAllBytes(path)
    if bytes.length != 40 then
      raised(
        RuntimeFailureCode.JournalCorrupt,
        "historical canonical HEAD shape differs",
      )
    val count  = ByteBuffer.wrap(bytes.take(8)).getLong()
    val digest = ByteVector.view(bytes.drop(8))
    if count < 0L || count > contents.frames.size.toLong then
      raised(
        RuntimeFailureCode.JournalCorrupt,
        "historical canonical HEAD names missing history",
      )
    val expected =
      if count == 0L then zero else contents.digests((count - 1L).toInt)
    if digest != expected.bytes then
      raised(
        RuntimeFailureCode.JournalCorrupt,
        "historical canonical HEAD checksum differs",
      )

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def resource(
      path: Path,
      identity: Bytes,
      maximumBytes: Long,
      faults: JournalFaultInjector[IO],
  ): Resource[IO, CanonicalAppendLog] =
    val handles = Resource.make(
      IO.blocking {
        if identity.isEmpty || maximumBytes < 40L then
          raised(
            RuntimeFailureCode.InvalidRequest,
            "historical canonical storage identity or capacity is invalid",
          )
        Files.createDirectories(path)
        if Files.isSymbolicLink(path) || !Files
            .isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        then
          raised(
            RuntimeFailureCode.JournalCorrupt,
            "historical canonical root is not a trusted directory",
          )
        val root = path.toRealPath()
        regular(root.resolve("LOCK"), false)
        val lockChannel = FileChannel.open(
          root.resolve("LOCK"),
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE,
          LinkOption.NOFOLLOW_LINKS,
        )
        try
          val lock = lockChannel.tryLock()
          if lock == null then
            raised(
              RuntimeFailureCode.RecoveryRequired,
              "historical canonical journal already has a writer",
            )
          try
            val identityFile = root.resolve("IDENTITY")
            regular(identityFile, false)
            regular(root.resolve("records"), false)
            regular(root.resolve("HEAD"), false)
            if !Files.exists(identityFile, LinkOption.NOFOLLOW_LINKS) then
              if Files.exists(
                  root.resolve("records"),
                  LinkOption.NOFOLLOW_LINKS,
                ) || Files
                  .exists(root.resolve("HEAD"), LinkOption.NOFOLLOW_LINKS)
              then
                raised(
                  RuntimeFailureCode.EvidenceMissing,
                  "historical canonical identity was lost",
                )
              val id = FileChannel.open(
                identityFile,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
              )
              try
                writeAll(id, identity)
                id.force(true)
              finally id.close()
              forceDirectory(root)
            if ByteVector.view(Files.readAllBytes(identityFile)) != identity
            then
              raised(
                RuntimeFailureCode.StartupIdentityMismatch,
                "historical canonical journal belongs to another installed handover",
              )
            if Files.exists(root.resolve("HEAD"), LinkOption.NOFOLLOW_LINKS)
            then regular(root.resolve("records"), true)
            val records = FileChannel.open(
              root.resolve("records"),
              StandardOpenOption.CREATE,
              StandardOpenOption.READ,
              StandardOpenOption.WRITE,
              LinkOption.NOFOLLOW_LINKS,
            )
            try
              if !Files.exists(root.resolve("HEAD"), LinkOption.NOFOLLOW_LINKS)
              then
                if records.size() != 0L then
                  raised(
                    RuntimeFailureCode.EvidenceMissing,
                    "historical canonical HEAD was lost",
                  )
                val head = FileChannel.open(
                  root.resolve("HEAD"),
                  StandardOpenOption.CREATE_NEW,
                  StandardOpenOption.WRITE,
                  LinkOption.NOFOLLOW_LINKS,
                )
                try
                  writeAll(head, longBytes(0L) ++ zero.bytes)
                  head.force(true)
                finally head.close()
                records.force(true)
                forceDirectory(root)
              Handles(
                root,
                lockChannel,
                lock,
                records,
                fileIdentity(root.resolve("records")),
              )
            catch
              case problem: Throwable =>
                records.close()
                throw problem
          catch
            case problem: Throwable =>
              lock.release()
              throw problem
        catch
          case problem: Throwable =>
            lockChannel.close()
            throw problem
      }.adaptError { case problem => new JournalOpenException(error(problem)) },
    )(handles =>
      IO.blocking {
        try handles.records.close()
        finally
          try handles.lock.release()
          finally handles.lockChannel.close()
      },
    )

    handles.flatMap { handles =>
      Resource
        .eval(
          (
            Semaphore[IO](1L),
            Ref.of[IO, State](
              State(Contents(Vector.empty, Vector.empty), true, false),
            ),
          ).tupled,
        )
        .flatMap { (gate, state) =>
          val store = new CanonicalAppendLog:
            private def poison: IO[Unit] = state.update(_.copy(poisoned = true))
            private def underGate[A](
                recovering: Boolean,
            )(run: State => Result[IO, A]): Result[IO, A] = EitherT(
              gate.permit.use(_ =>
                IO.uncancelable(_ =>
                  state.get.flatMap { current =>
                    if current.closed || (current.poisoned && !recovering) then
                      IO.pure(
                        Left(
                          failure(
                            RuntimeFailureCode.RecoveryRequired,
                            "historical canonical journal requires recovery",
                          ),
                        ),
                      )
                    else
                      run(current).value.attempt.flatMap {
                        case Left(problem) => poison.as(Left(error(problem)))
                        case Right(result) => IO.pure(result)
                      }
                  },
                ),
              ),
            )
            private def head(contents: Contents, inject: Boolean): IO[Unit] =
              val temporary =
                handles.root.resolve("head-" + UUID.randomUUID().toString)
              val sequence = contents.frames.size.toLong
              def after(point: JournalFaultPoint): IO[Unit] =
                if inject then faults.after(point, sequence) else IO.unit
              Resource
                .fromAutoCloseable(
                  IO.blocking(
                    FileChannel.open(
                      temporary,
                      StandardOpenOption.CREATE_NEW,
                      StandardOpenOption.WRITE,
                      LinkOption.NOFOLLOW_LINKS,
                    ),
                  ),
                )
                .use { output =>
                  IO.blocking {
                    writeAll(output, longBytes(sequence) ++ contents.head.bytes)
                    output.force(true)
                  } *> after(JournalFaultPoint.AfterHeadWrite)
                } *> IO.blocking {
                regular(handles.root.resolve("HEAD"), true)
                val _ = Files.move(
                  temporary,
                  handles.root.resolve("HEAD"),
                  StandardCopyOption.ATOMIC_MOVE,
                  StandardCopyOption.REPLACE_EXISTING,
                )
              } *> after(JournalFaultPoint.AfterHeadMove) *> IO.blocking(
                forceDirectory(handles.root),
              ) *> after(JournalFaultPoint.AfterHeadForce)

            def recover: Result[IO, Vector[Bytes]] = underGate(true) {
              observed =>
                for
                  _        <- EitherT.liftF(poison)
                  contents <- EitherT(
                    IO.blocking {
                      regular(handles.root.resolve("IDENTITY"), true)
                      if ByteVector.view(
                          Files.readAllBytes(handles.root.resolve("IDENTITY")),
                        ) != identity
                      then
                        raised(
                          RuntimeFailureCode.StartupIdentityMismatch,
                          "historical canonical identity changed",
                        )
                      val contents = readContents(handles, maximumBytes)
                      readHead(handles.root, contents)
                      if !contents.frames.startsWith(observed.contents.frames)
                      then
                        raised(
                          RuntimeFailureCode.JournalCorrupt,
                          "historical canonical recovery lost already observed history",
                        )
                      handles.records.force(true)
                      contents
                    }.attempt
                      .map(_.left.map(error)),
                  )
                  _ <- EitherT(
                    head(contents, false).attempt.map(_.left.map(error)),
                  )
                  _ <- EitherT.liftF(state.set(State(contents, false, false)))
                yield contents.frames
            }

            def append(bytes: Bytes): Result[IO, Unit] = underGate(false) {
              before =>
                for
                  _ <- EitherT.fromEither[IO](
                    RuntimeCheck.require(
                      bytes.size <= Int.MaxValue.toLong,
                      RuntimeFailureCode.CapacityUnavailable,
                      "historical canonical record exceeds local byte capacity",
                    ),
                  )
                  _ <- EitherT(
                    IO.blocking {
                      val physical = readContents(handles, maximumBytes)
                      readHead(handles.root, physical)
                      if physical != before.contents then
                        raised(
                          RuntimeFailureCode.JournalCorrupt,
                          "historical canonical history changed outside its writer",
                        )
                      if bytes.size + 40L > maximumBytes - handles.records
                          .size()
                      then
                        raised(
                          RuntimeFailureCode.CapacityUnavailable,
                          "historical canonical journal exceeds configured local capacity",
                        )
                    }.attempt
                      .map(_.left.map(error)),
                  ).leftSemiflatTap(_ => poison)
                  next = Contents(
                    before.contents.frames :+ bytes,
                    before.contents.digests :+ nextHash(
                      before.contents.head,
                      bytes,
                    ),
                  )
                  _ <- EitherT.liftF(poison)
                  _ <- EitherT(
                    (IO.blocking {
                      val _ = handles.records.position(handles.records.size())
                      writeAll(
                        handles.records,
                        longBytes(bytes.size) ++ bytes ++ contentHash(
                          bytes,
                        ).bytes,
                      )
                    } *> state.set(State(next, true, false)) *> faults.after(
                      JournalFaultPoint.AfterFrameWrite,
                      next.frames.size.toLong,
                    ) *>
                      IO.blocking(handles.records.force(true)) *> faults.after(
                        JournalFaultPoint.AfterFrameForce,
                        next.frames.size.toLong,
                      ) *>
                      head(next, true)).attempt.map(_.left.map(error)),
                  )
                  _ <- EitherT.liftF(state.set(State(next, false, false)))
                yield ()
            }
          Resource.make(IO.pure(store))(_ =>
            gate.permit
              .use(_ => state.update(_.copy(closed = true, poisoned = true))),
          )
        }
    }
