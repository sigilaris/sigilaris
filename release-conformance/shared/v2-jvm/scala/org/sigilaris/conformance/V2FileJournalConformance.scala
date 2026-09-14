package org.sigilaris.conformance

import org.sigilaris.node.jvm.storage.file.*

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardOpenOption}

import scala.jdk.CollectionConverters.*
import scala.util.Using

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.datatype.{UInt256, Utf8}
import org.sigilaris.node.jvm.runtime.application.v2.*

object V2FileJournalConformance:
  def cases: Vector[(String, () => IO[Unit])] = new Scenarios().cases
  def run(): IO[Unit]                         = cases.traverse_ { (name, run) =>
    IO.println("V2FileJournalConformance: " + name) *> IO.defer(run())
  }
  private final class Scenarios:
    private def assertNotEquals[A, B](actual: A, expected: B): Unit =
      assert(actual != expected, "unexpected equality: " + actual.toString)
    private val registered =
      scala.collection.mutable.ArrayBuffer.empty[(String, () => IO[Unit])]
    def cases: Vector[(String, () => IO[Unit])] = registered.toVector
    private def scenario(name: String)(run: => IO[Unit]): Unit =
      registered.addOne(name -> (() => run)): Unit
    private def assertEquals[A, B](actual: A, expected: B): Unit =
      assert(
        actual == expected,
        "expected " + expected.toString + "; actual " + actual.toString,
      )
    private def hash(value: Long): Hash =
      UInt256.unsafeFromBigIntUnsigned(BigInt(value))

    private def prepared(
        sequence: Long = 1L,
        previous: Hash = hash(0),
        value: Long = 42L,
    ): JournalRecord =
      val payload = JournalPayload.codec
        .encode(
          JournalPayload.empty.copy(rebuiltIndexDigest = Some(hash(value))),
        )
        .toOption
        .get
      JournalRecord(
        2L,
        sequence,
        JournalOperation.IndexRebuild,
        previous,
        payload,
        Commitment
          .hash(Utf8("sigilaris.application.journal.payload.v2"), payload),
        JournalStatus.Prepared,
      )

    private def committed(record: JournalRecord): JournalRecord =
      record.copy(status = JournalStatus.Committed)
    private def digest(record: JournalRecord): Hash =
      JournalRecord.digest(record).toOption.get

    private def oneShot(
        point: JournalFaultPoint,
        armed: Ref[IO, Boolean],
    ): JournalFaultInjector[IO] = new JournalFaultInjector[IO]:
      def after(observed: JournalFaultPoint, sequence: Long): IO[Unit] =
        if observed != point then IO.unit
        else
          armed
            .getAndSet(false)
            .flatMap(value =>
              if value then
                IO.raiseError(
                  new IOExceptionForTest(
                    point.toString + ":" + sequence.toString,
                  ),
                )
              else IO.unit,
            )

    private final class IOExceptionForTest(message: String)
        extends RuntimeException(message)

    scenario(
      "fresh journal is fenced until recovery and canonical prepared/committed history survives reopen",
    ):
      temporary.use: root =>
        val first = prepared()
        for
          _ <- FileApplicationJournal
            .resource(root)
            .use: journal =>
              for
                blocked   <- journal.append(first).value
                recovered <- journal.recover.value
                _         <- journal
                  .append(first)
                  .value
                  .flatMap(value => IO(assertEquals(value, Right(()))))
                repeated   <- journal.append(first).value
                mismatched <- journal
                  .append(committed(first).copy(payloadDigest = hash(999)))
                  .value
                complete  <- journal.append(committed(first)).value
                duplicate <- journal.append(committed(first)).value
              yield
                assertEquals(
                  blocked.left.map(_.code),
                  Left(RuntimeFailureCode.RecoveryRequired),
                )
                assertEquals(recovered, Right(Vector.empty))
                assertEquals(repeated, Right(()))
                assert(mismatched.isLeft)
                assertEquals(complete, Right(()))
                assertEquals(duplicate, Right(()))
          _ <- FileApplicationJournal
            .resource(root)
            .use: journal =>
              for
                recovered <- journal.recover.value
                next = prepared(2L, digest(committed(first)))
                outOfOrder <- journal
                  .append(prepared(3L, digest(committed(first))))
                  .value
                appended <- journal.append(next).value
              yield
                assertEquals(recovered, Right(Vector(first, committed(first))))
                assert(outOfOrder.isLeft)
                assertEquals(appended, Right(()))
        yield ()

    private val frameFaults =
      Vector(
        JournalFaultPoint.AfterFrameWrite,
        JournalFaultPoint.AfterFrameForce,
      )
    private val headFaults = Vector(
      JournalFaultPoint.AfterHeadWrite,
      JournalFaultPoint.AfterHeadMove,
      JournalFaultPoint.AfterHeadForce,
    )

    (frameFaults.flatMap(point =>
      Vector(point -> false, point -> true),
    ) ++ headFaults.map(_ -> true)).foreach: (point, atCommit) =>
      scenario(s"$point during ${
          if atCommit then "commit" else "prepare"
        } fences further writes and reopens without losing the complete record"):
        temporary.use: root =>
          val first = prepared()
          for
            armed <- Ref.of[IO, Boolean](false)
            _     <- FileApplicationJournal
              .resourceWithFaults(root, oneShot(point, armed))
              .use: journal =>
                for
                  _ <- journal.recover.value
                  _ <-
                    if atCommit then journal.append(first).value.void
                    else IO.unit
                  _      <- armed.set(true)
                  failed <- journal
                    .append(if atCommit then committed(first) else first)
                    .value
                  blocked     <- journal.append(first).value
                  blobBlocked <- journal
                    .putBlob(Utf8("witness"), hash(55), ByteVector.empty)
                    .value
                yield
                  assertEquals(
                    failed.left.map(_.code),
                    Left(RuntimeFailureCode.StorageUnknown),
                  )
                  assertEquals(
                    blocked.left.map(_.code),
                    Left(RuntimeFailureCode.RecoveryRequired),
                  )
                  assertEquals(
                    blobBlocked.left.map(_.code),
                    Left(RuntimeFailureCode.RecoveryRequired),
                  )
            _ <- FileApplicationJournal
              .resource(root)
              .use: journal =>
                for
                  recovered    <- journal.recover.value
                  complete     <- journal.append(committed(first)).value
                  finalHistory <- journal.recover.value
                yield
                  assertEquals(
                    recovered,
                    Right(if atCommit then Vector(first, committed(first))
                    else Vector(first)),
                  )
                  assertEquals(complete, Right(()))
                  assertEquals(
                    finalHistory,
                    Right(Vector(first, committed(first))),
                  )
          yield ()

    scenario(
      "a complete prepared tail may only be completed with the exact original payload",
    ):
      temporary.use: root =>
        val first = prepared()
        for
          _ <- FileApplicationJournal
            .resource(root)
            .use(journal =>
              journal.recover.value *> journal.append(first).value.void,
            )
          _ <- FileApplicationJournal
            .resource(root)
            .use: journal =>
              for
                recovered <- journal.recover.value
                different <- journal
                  .append(committed(prepared(value = 43L)))
                  .value
                interleaved <- journal.append(prepared(2L, hash(99))).value
                matched     <- journal.append(committed(first)).value
              yield
                assertEquals(recovered, Right(Vector(first)))
                assertEquals(
                  different.left.map(_.code),
                  Left(RuntimeFailureCode.JournalCorrupt),
                )
                assertEquals(
                  interleaved.left.map(_.code),
                  Left(RuntimeFailureCode.JournalCorrupt),
                )
                assertEquals(matched, Right(()))
        yield ()

    Vector("truncated", "checksum", "length").foreach: damage =>
      scenario(
        s"$damage journal tail is retained unchanged and recovery cannot authorize new writes",
      ):
        temporary.use: root =>
          val first = prepared()
          val path  = root.resolve("journal.log")
          for
            _       <- completeJournal(root, first)
            damaged <- IO.blocking:
              val bytes = Files.readAllBytes(path)
              val next  = damage match
                case "truncated" => bytes.dropRight(1)
                case "checksum"  =>
                  bytes(30) = (bytes(30) ^ 1).toByte
                  bytes
                case _ =>
                  ByteBuffer.wrap(bytes).putLong(16, Long.MaxValue)
                  bytes
              overwrite(path, next)
              ByteVector.view(next)
            _ <- FileApplicationJournal
              .resource(root)
              .use: journal =>
                for
                  recovered <- journal.recover.value
                  append    <- journal.append(first).value
                  retry     <- journal.recover.value
                yield
                  assertEquals(
                    recovered.left.map(_.code),
                    Left(RuntimeFailureCode.JournalCorrupt),
                  )
                  assertEquals(
                    append.left.map(_.code),
                    Left(RuntimeFailureCode.RecoveryRequired),
                  )
                  assertEquals(
                    retry.left.map(_.code),
                    Left(RuntimeFailureCode.JournalCorrupt),
                  )
            unchanged <- IO.blocking(ByteVector.view(Files.readAllBytes(path)))
          yield assertEquals(unchanged, damaged)

    scenario(
      "durable HEAD prevents accepting a clean-boundary truncation that lost its committed record",
    ):
      temporary.use: root =>
        val first = prepared()
        for
          boundary <- FileApplicationJournal
            .resource(root)
            .use: journal =>
              for
                _      <- journal.recover.value
                _      <- journal.append(first).value
                length <- IO.blocking(Files.size(root.resolve("journal.log")))
                _      <- journal.append(committed(first)).value
              yield length
          _ <- IO.blocking:
            Using.resource(
              FileChannel.open(
                root.resolve("journal.log"),
                StandardOpenOption.WRITE,
              ),
            ): output =>
              output.truncate(boundary)
              output.force(true)
          _ <- FileApplicationJournal
            .resource(root)
            .use: journal =>
              journal.recover.value.map(value =>
                assertEquals(
                  value.left.map(_.code),
                  Left(RuntimeFailureCode.JournalCorrupt),
                ),
              )
        yield ()

    scenario(
      "a valid durable HEAD that lags a complete journal is advanced during recovery",
    ):
      temporary.use: root =>
        val first = prepared()
        for
          initialHead <- FileApplicationJournal
            .resource(root)
            .use: journal =>
              for
                _       <- journal.recover.value
                initial <- IO.blocking(Files.readAllBytes(root.resolve("HEAD")))
                _       <- journal.append(first).value
                _       <- journal.append(committed(first)).value
              yield initial
          _ <- IO.blocking(overwrite(root.resolve("HEAD"), initialHead))
          _ <- FileApplicationJournal
            .resource(root)
            .use: journal =>
              journal.recover.value.map(value =>
                assertEquals(value, Right(Vector(first, committed(first)))),
              )
          advanced <- IO.blocking(Files.readAllBytes(root.resolve("HEAD")))
        yield assertNotEquals(
          ByteVector.view(initialHead),
          ByteVector.view(advanced),
        )

    scenario(
      "a valid HEAD from another history is not accepted just because its sequence matches",
    ):
      (temporary, temporary).tupled.use: (left, right) =>
        for
          _ <- completeJournal(left, prepared(value = 42L))
          _ <- completeJournal(right, prepared(value = 43L))
          _ <- IO.blocking(
            overwrite(
              left.resolve("HEAD"),
              Files.readAllBytes(right.resolve("HEAD")),
            ),
          )
          _ <- FileApplicationJournal
            .resource(left)
            .use: journal =>
              journal.recover.value.map(value =>
                assertEquals(
                  value.left.map(_.code),
                  Left(RuntimeFailureCode.JournalCorrupt),
                ),
              )
        yield ()

    scenario(
      "immutable blobs bind namespace and caller digest to exact bytes without reinterpreting the logical digest",
    ):
      temporary.use: root =>
        val bytes     = ByteVector(1.toByte, 2.toByte, 3.toByte)
        val namespace = Utf8("witness")
        for
          _ <- FileApplicationJournal
            .resource(root)
            .use: journal =>
              for
                _        <- journal.recover.value
                written  <- journal.putBlob(namespace, hash(99), bytes).value
                repeated <- journal.putBlob(namespace, hash(99), bytes).value
                conflict <- journal
                  .putBlob(namespace, hash(99), bytes.reverse)
                  .value
                traversal <- journal
                  .putBlob(Utf8("../escape"), hash(99), bytes)
                  .value
                caseAlias <- journal
                  .putBlob(Utf8("Witness"), hash(99), bytes)
                  .value
                empty <- journal
                  .putBlob(Utf8("state"), hash(100), ByteVector.empty)
                  .value
                appended <- journal.append(prepared()).value
              yield
                assertEquals(written, Right(()))
                assertEquals(repeated, Right(()))
                assertEquals(
                  conflict.left.map(_.code),
                  Left(RuntimeFailureCode.CommitmentMismatch),
                )
                assertEquals(
                  traversal.left.map(_.code),
                  Left(RuntimeFailureCode.InvalidRequest),
                )
                assertEquals(
                  caseAlias.left.map(_.code),
                  Left(RuntimeFailureCode.InvalidRequest),
                )
                assertEquals(empty, Right(()))
                assertEquals(appended, Right(()))
          _ <- FileApplicationJournal
            .resource(root)
            .use: journal =>
              for
                _      <- journal.recover.value
                loaded <- journal.readBlob(namespace, hash(99)).value
                empty  <- journal.readBlob(Utf8("state"), hash(100)).value
              yield
                assertEquals(loaded, Right(bytes))
                assertEquals(empty, Right(ByteVector.empty))
        yield ()

    private val blobFaults = Vector(
      JournalFaultPoint.AfterBlobWrite,
      JournalFaultPoint.AfterBlobForce,
      JournalFaultPoint.AfterBlobMove,
      JournalFaultPoint.AfterBlobDirectoryForce,
    )

    blobFaults.foreach: point =>
      scenario(
        s"$point retains inactive outputs and fences writes until verified recovery",
      ):
        temporary.use: root =>
          val bytes = ByteVector(9.toByte, 8.toByte)
          for
            armed <- Ref.of[IO, Boolean](true)
            _     <- FileApplicationJournal
              .resourceWithFaults(root, oneShot(point, armed))
              .use: journal =>
                for
                  _      <- journal.recover.value
                  failed <- journal
                    .putBlob(Utf8("state"), hash(99), bytes)
                    .value
                  blocked <- journal.append(prepared()).value
                yield
                  assertEquals(
                    failed.left.map(_.code),
                    Left(RuntimeFailureCode.StorageUnknown),
                  )
                  assertEquals(
                    blocked.left.map(_.code),
                    Left(RuntimeFailureCode.RecoveryRequired),
                  )
            _ <- FileApplicationJournal
              .resource(root)
              .use: journal =>
                for
                  recovered <- journal.recover.value
                  retried   <- journal
                    .putBlob(Utf8("state"), hash(99), bytes)
                    .value
                  loaded <- journal.readBlob(Utf8("state"), hash(99)).value
                yield
                  assertEquals(recovered, Right(Vector.empty))
                  assertEquals(retried, Right(()))
                  assertEquals(loaded, Right(bytes))
          yield ()

    Vector(false, true).foreach: rename =>
      scenario(s"blob ${
          if rename then "key substitution" else "content corruption"
        } is detected during full recovery"):
        temporary.use: root =>
          val namespace = Utf8("state")
          for
            _ <- FileApplicationJournal
              .resource(root)
              .use(journal =>
                journal.recover.value *> journal
                  .putBlob(namespace, hash(99), ByteVector(9.toByte))
                  .value
                  .void,
              )
            _ <- IO.blocking:
              val original = root
                .resolve("blobs")
                .resolve("state")
                .resolve(hash(99).bytes.toHex + ".blob")
              if rename then
                Files.move(
                  original,
                  original.resolveSibling(hash(100).bytes.toHex + ".blob"),
                )
                ()
              else
                val bytes = Files.readAllBytes(original)
                bytes(16) = 8.toByte
                overwrite(original, bytes)
            _ <- FileApplicationJournal
              .resource(root)
              .use: journal =>
                journal.recover.value.map(value =>
                  assertEquals(
                    value.left.map(_.code),
                    Left(RuntimeFailureCode.JournalCorrupt),
                  ),
                )
          yield ()

    scenario(
      "process lock prevents a second writer and is released by Resource",
    ):
      temporary.use: root =>
        for
          _ <- FileApplicationJournal
            .resource(root)
            .use: journal =>
              for
                _      <- journal.recover.value
                second <- FileApplicationJournal
                  .resource(root)
                  .use(_ => IO.unit)
                  .attempt
                written <- journal.append(prepared()).value
              yield
                assert(second.left.exists {
                  case failure: JournalOpenException =>
                    failure.failure.code == RuntimeFailureCode.RecoveryRequired
                  case _ => false
                })
                assertEquals(written, Right(()))
          _ <- FileApplicationJournal
            .resource(root)
            .use: journal =>
              journal.recover.value.map(value =>
                assertEquals(value, Right(Vector(prepared()))),
              )
        yield ()

    scenario(
      "a released resource cannot write blobs or recover through a stale handle",
    ):
      temporary.use: root =>
        for
          allocated <- FileApplicationJournal.resource(root).allocated
          (journal, close) = allocated
          _    <- journal.recover.value
          _    <- close
          blob <- journal
            .putBlob(Utf8("state"), hash(99), ByteVector.empty)
            .value
          recovered <- journal.recover.value
          _ <- FileApplicationJournal.resource(root).use(_.recover.value.void)
        yield
          assertEquals(
            blob.left.map(_.code),
            Left(RuntimeFailureCode.RecoveryRequired),
          )
          assertEquals(
            recovered.left.map(_.code),
            Left(RuntimeFailureCode.RecoveryRequired),
          )

    scenario(
      "an incomplete existing directory is not silently reinitialized as empty",
    ):
      temporary.use: root =>
        for
          _ <- FileApplicationJournal.resource(root).use(_.recover.value.void)
          _ <- IO.blocking(Files.delete(root.resolve("HEAD")))
          rejected <- FileApplicationJournal
            .resource(root)
            .use(_ => IO.unit)
            .attempt
        yield assert(rejected.left.exists {
          case value: JournalOpenException =>
            value.failure.code == RuntimeFailureCode.JournalCorrupt
          case _ => false
        })

    private def completeJournal(root: Path, record: JournalRecord): IO[Unit] =
      FileApplicationJournal
        .resource(root)
        .use(journal =>
          journal.recover.value *> journal
            .append(record)
            .value *> journal.append(committed(record)).value.void,
        )

    private def overwrite(path: Path, bytes: Array[Byte]): Unit =
      Using.resource(
        FileChannel.open(
          path,
          StandardOpenOption.WRITE,
          StandardOpenOption.TRUNCATE_EXISTING,
        ),
      ): output =>
        val buffer = ByteBuffer.wrap(bytes)
        while buffer.hasRemaining do
          val _ = output.write(buffer)
        output.force(true)

    private def temporary: Resource[IO, Path] =
      Resource.make(
        IO.blocking(Files.createTempDirectory("sigilaris-v2-journal")),
      )(path =>
        IO.blocking:
          Using.resource(Files.walk(path))(
            _.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists),
          )
          (),
      )

    scenario(
      "same live journal cannot forget completed history after its files are replaced by a valid shorter prefix",
    ) {
      temporary.use { root =>
        FileApplicationJournal.resource(root).use { journal =>
          def replace(path: Path, bytes: Bytes): IO[Unit] = IO.blocking {
            Using.resource(
              FileChannel.open(
                path,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
              ),
            ) { out =>
              val buffer = ByteBuffer.wrap(bytes.toArray)
              while buffer.hasRemaining do
                val _ = out.write(buffer)
              out.force(true)
            }
          }
          val first  = prepared()
          val second = prepared(2L, digest(committed(first)), 43L)
          for
            _ <- journal.recover.value
            _ <- journal
              .append(first)
              .value
              .flatMap(v => IO(assertEquals(v, Right(()))))
            _ <- journal
              .append(committed(first))
              .value
              .flatMap(v => IO(assertEquals(v, Right(()))))
            oldLog <- IO.blocking(
              ByteVector.view(Files.readAllBytes(root.resolve("journal.log"))),
            )
            oldHead <- IO.blocking(
              ByteVector.view(Files.readAllBytes(root.resolve("HEAD"))),
            )
            _ <- journal
              .append(second)
              .value
              .flatMap(v => IO(assertEquals(v, Right(()))))
            _ <- journal
              .append(committed(second))
              .value
              .flatMap(v => IO(assertEquals(v, Right(()))))
            _         <- replace(root.resolve("journal.log"), oldLog)
            _         <- replace(root.resolve("HEAD"), oldHead)
            recovered <- journal.recover.value
            append    <- journal.append(second).value
            _         <- IO {
              assertEquals(
                recovered.left.map(_.code),
                Left(RuntimeFailureCode.JournalCorrupt),
              )
              assertEquals(
                append.left.map(_.code),
                Left(RuntimeFailureCode.RecoveryRequired),
              )
            }
          yield ()
        }
      }
    }

    scenario(
      "a completely observed frame cannot be forgotten after failure before its first force",
    ) {
      temporary.use { root =>
        for
          armed <- Ref.of[IO, Boolean](false)
          _     <- FileApplicationJournal
            .resourceWithFaults(
              root,
              oneShot(JournalFaultPoint.AfterFrameWrite, armed),
            )
            .use { journal =>
              for
                _      <- journal.recover.value
                oldLog <- IO.blocking(
                  ByteVector
                    .view(Files.readAllBytes(root.resolve("journal.log"))),
                )
                _       <- armed.set(true)
                written <- journal.append(prepared()).value
                _       <- IO(assert(written.isLeft))
                _       <- IO.blocking(
                  Using.resource(
                    FileChannel.open(
                      root.resolve("journal.log"),
                      StandardOpenOption.WRITE,
                      StandardOpenOption.TRUNCATE_EXISTING,
                    ),
                  ) { out =>
                    val buffer = ByteBuffer.wrap(oldLog.toArray)
                    while buffer.hasRemaining do
                      val _ = out.write(buffer)
                    out.force(true)
                  },
                )
                recovered <- journal.recover.value
                _         <- IO(
                  assertEquals(
                    recovered.left.map(_.code),
                    Left(RuntimeFailureCode.JournalCorrupt),
                  ),
                )
              yield ()
            }
        yield ()
      }
    }

    for replaced <- Vector("journal.log", "LOCK") do
      scenario(
        s"replacing $replaced with identical bytes cannot detach the owned file resource",
      ) {
        temporary.use { root =>
          FileApplicationJournal.resource(root).use { journal =>
            for
              _ <- journal.recover.value
              _ <- journal.append(prepared()).value
              _ <- journal.append(committed(prepared())).value
              path = root.resolve(replaced)
              original <- IO.blocking(Files.readAllBytes(path))
              _        <- IO.blocking {
                val _ = Files.move(path, root.resolve("detached-" + replaced))
                val _ = Files.write(
                  path,
                  original,
                  StandardOpenOption.CREATE_NEW,
                  StandardOpenOption.WRITE,
                )
              }
              append <- journal
                .append(prepared(2L, digest(committed(prepared()))))
                .value
              recovered <- journal.recover.value
              blob      <- journal
                .putBlob(Utf8("logical"), hash(99L), ByteVector(1.toByte))
                .value
              _ <- IO {
                assertEquals(
                  append.left.map(_.code),
                  Left(RuntimeFailureCode.JournalCorrupt),
                )
                assertEquals(
                  recovered.left.map(_.code),
                  Left(RuntimeFailureCode.JournalCorrupt),
                )
                assert(blob.isLeft)
              }
            yield ()
          }
        }
      }
