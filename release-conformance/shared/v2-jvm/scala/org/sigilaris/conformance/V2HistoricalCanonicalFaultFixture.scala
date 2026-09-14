package org.sigilaris.conformance

import java.nio.file.{Files, Path}

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.node.jvm.runtime.application.v2.*

/** Public fault injection through the actual installed historical materializer.
  * File edits below model storage faults after every writer has closed.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Throw"),
)
object V2HistoricalCanonicalFaultFixture:
  import V2HistoricalFixture.{accepted, check}
  import V2RequestConformance.height

  private def fault(
      wanted: JournalFaultPoint,
      physicalSequence: Long,
      fired: Ref[IO, Boolean],
  ): JournalFaultInjector[IO] = new JournalFaultInjector[IO]:
    def after(point: JournalFaultPoint, sequence: Long): IO[Unit] =
      if point == wanted && sequence == physicalSequence then
        fired.set(true) *> IO.raiseError(
          new IllegalStateException("historical physical fault"),
        )
      else IO.unit

  private def rejected[A](value: IO[A], detail: String): IO[Unit] =
    value.attempt.flatMap(result => accepted(check(result.isLeft, detail)))

  private def replace(path: Path, bytes: ByteVector): IO[Unit] =
    V2HistoricalFixture.durable(path, bytes)

  /** A whole forced Prepared tail must be completed from its real source and
    * finality. A second crash after forcing Committed must remain recoverable.
    * Partial frames, rollback past HEAD and lost original proof never open
    * Ready.
    */
  def run(root: Path): IO[Unit] = for
    preparedFired <- Ref.of[IO, Boolean](false)
    _             <- V2HistoricalRuntimeFixture
      .fresh(
        root,
        fault(JournalFaultPoint.AfterFrameForce, 1L, preparedFired),
      )
      .use { cluster =>
        for
          result <- cluster.append(6L).value
          fired  <- preparedFired.get
          _      <- accepted(
            check(
              fired && result.left
                .exists(_.code == RuntimeFailureCode.StorageUnknown),
              "forced original Prepared tail must report storage uncertainty",
            ),
          )
          ready <- cluster.nodes.head.finalizer.status
          _     <- accepted(
            check(
              ready.failure.nonEmpty,
              "unknown original ledger write must fence ordinary materialization",
            ),
          )
          retried <- cluster.append(6L).value
          _       <- accepted(
            check(
              retried.isLeft,
              "same-instance historical unknown write must not permit another signature",
            ),
          )
        yield ()
      }
    committedFired <- Ref.of[IO, Boolean](false)
    _              <- rejected(
      V2HistoricalRuntimeFixture
        .reopen(
          root,
          fault(JournalFaultPoint.AfterHeadForce, 2L, committedFired),
        )
        .use(_ => IO.unit),
      "recovery must report uncertainty after its real pending Committed HEAD is forced",
    )
    fired <- committedFired.get
    _     <- accepted(
      check(
        fired,
        "whole Prepared tail must reach authenticated Committed recovery",
      ),
    )
    _ <- V2HistoricalRuntimeFixture.reopen(root).use { cluster =>
      accepted(for
        canonical <- cluster.canonical
        _         <- check(
          canonical.forall(value =>
            value.context == cluster.source.old && value.height == height(4L),
          ),
          "four nodes must recover actual old finality4 after Prepared/Committed crashes",
        )
        _ <- cluster.nodes.traverse_(node => node.safety.snapshot.void)
      yield ())
    }
    records = root.resolve("historical-canonical-0").resolve("records")
    original <- IO.blocking(ByteVector.view(Files.readAllBytes(records)))
    _        <- replace(records, original ++ ByteVector(1.toByte))
    _        <- rejected(
      V2HistoricalRuntimeFixture.reopen(root).use(_ => IO.unit),
      "partial canonical frame must reject runtime reopen",
    )
    _ <- replace(records, original)
    _ <- replace(records, ByteVector.empty)
    _ <- rejected(
      V2HistoricalRuntimeFixture.reopen(root).use(_ => IO.unit),
      "historical records rolled back behind forced HEAD must reject runtime reopen",
    )
    _ <- replace(records, original)
    issuance = root.resolve("original-applications")
    originalIssuance <- IO.blocking(
      ByteVector.view(Files.readAllBytes(issuance)),
    )
    _ <- IO.blocking(Files.delete(issuance))
    _ <- rejected(
      V2HistoricalRuntimeFixture.reopen(root).use(_ => IO.unit),
      "loss of original source issuance coverage must reject runtime reopen",
    )
    _ <- replace(issuance, originalIssuance)
  yield ()
