package org.sigilaris.node.jvm.storage.swaydb

import java.nio.charset.StandardCharsets

import cats.data.EitherT
import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*

import io.circe.{Decoder, Encoder}
import io.circe.parser.decode
import io.circe.syntax.*
import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.{
  ApplicationInputId,
  ExecutionId,
  InclusionHeight,
}
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.core.failure.DecodeFailure
import org.sigilaris.node.jvm.runtime.application.*
import org.sigilaris.node.jvm.storage.StoreIndex
import org.sigilaris.node.txpipeline.{
  ApplicationProtocolManifestV1,
  ExactTxPipelineRecord,
  TxPipelineId,
}

enum ApplicationStoreFaultPoint:
  case AfterPrepared
  case AfterLocks
  case AfterReservations
  case AfterApplied
  case AfterTerminal
  case AfterExactPipelines
  case AfterControl
  case AfterCommitted

trait ApplicationStoreFaultInjector:
  def after(point: ApplicationStoreFaultPoint, sequence: Long): IO[Unit]

object ApplicationStoreFaultInjector:
  val none: ApplicationStoreFaultInjector = new ApplicationStoreFaultInjector:
    override def after(
        point: ApplicationStoreFaultPoint,
        sequence: Long,
    ): IO[Unit] = IO.unit

final case class ApplicationStoreLegacyDrainRepair(
    sequence: Long,
    previousPhase: ApplicationDrainPhase,
    repairedPhase: ApplicationDrainPhase,
    previousWatermark: Option[InclusionHeight],
    repairedWatermark: Option[InclusionHeight],
)

trait ApplicationStoreRecoveryObserver:
  def legacyDrainStateRepaired(
      diagnostic: ApplicationStoreLegacyDrainRepair,
  ): IO[Unit]

@SuppressWarnings(Array("org.wartremover.warts.Any"))
object ApplicationStoreRecoveryObserver:
  private val logger = System.getLogger(
    "org.sigilaris.node.jvm.storage.swaydb.SwayDbApplicationSafetyStore",
  )

  val logging: ApplicationStoreRecoveryObserver =
    new ApplicationStoreRecoveryObserver:
      override def legacyDrainStateRepaired(
          diagnostic: ApplicationStoreLegacyDrainRepair,
      ): IO[Unit] =
        IO.delay:
          val previousWatermark = diagnostic.previousWatermark
            .map(_.toBigNat.toBigInt.toString)
            .getOrElse("none")
          val repairedWatermark = diagnostic.repairedWatermark
            .map(_.toBigNat.toBigInt.toString)
            .getOrElse("none")
          logger.log(
            System.Logger.Level.WARNING,
            s"journaled legacy application drain repair at sequence ${diagnostic.sequence}: phase ${diagnostic.previousPhase.wire} -> ${diagnostic.repairedPhase.wire}, watermark $previousWatermark -> $repairedWatermark",
          )

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
  ),
)
final class SwayDbApplicationSafetyStore private (
    locks: StoreIndex[IO, Utf8, Utf8],
    reservations: StoreIndex[IO, Utf8, Utf8],
    applied: StoreIndex[IO, Utf8, Utf8],
    terminal: StoreIndex[IO, Utf8, Utf8],
    exactPipelines: StoreIndex[IO, Utf8, Utf8],
    journalStore: StoreIndex[IO, Utf8, Utf8],
    control: StoreIndex[IO, Utf8, Utf8],
    expectedManifest: Ref[IO, ApplicationProtocolManifestV1],
    faultInjector: ApplicationStoreFaultInjector,
    recoveryObserver: ApplicationStoreRecoveryObserver,
    gate: Semaphore[IO],
    ref: Ref[IO, SwayDbApplicationSafetyStore.State],
) extends ApplicationSafetyStore[IO]:

  override def snapshot: IO[ApplicationSafetySnapshot] = ref.get.map(_.snapshot)

  override def journal: IO[Vector[ApplicationJournalEntry]] =
    ref.get.map(_.journal)

  override def transact[A](
      operation: String,
  )(
      update: ApplicationSafetySnapshot => Either[
        ApplicationSafetyRuntimeFailure,
        (ApplicationSafetySnapshot, A),
      ],
  ): EitherT[IO, ApplicationSafetyRuntimeFailure, A] =
    EitherT:
      gate.permit.use: _ =>
        ref.get.flatMap: state =>
          if state.recoveryRequired then
            IO.pure(
              Left(
                failure(
                  "applicationJournalIncomplete",
                  "application store requires journal recovery before another mutation",
                ),
              ),
            )
          else transactReady(operation, state, update)

  override def recover: EitherT[
    IO,
    ApplicationSafetyRuntimeFailure,
    ApplicationSafetySnapshot,
  ] =
    EitherT:
      gate.permit.use(_ => recoverUnderGate)

  private def transactReady[A](
      operation: String,
      state: SwayDbApplicationSafetyStore.State,
      update: ApplicationSafetySnapshot => Either[
        ApplicationSafetyRuntimeFailure,
        (ApplicationSafetySnapshot, A),
      ],
  ): IO[Either[ApplicationSafetyRuntimeFailure, A]] =
    val prepared = for
      updated <- update(state.snapshot)
      (candidate, value) = updated
      _ <- ApplicationSafetyReconciliation.validate(candidate)
    yield candidate -> value

    prepared match
      case Left(error) => IO.pure(Left(error))
      case Right((candidate, value)) if candidate == state.snapshot =>
        IO.pure(Right(value))
      case Right((candidate, value)) =>
        ApplicationJournalDelta.between(state.snapshot, candidate) match
          case Left(error)  => IO.pure(Left(error))
          case Right(delta) =>
            val sequence = state.snapshot.lastJournalSequence + 1L
            val entry    = ApplicationJournalEntry(
              ApplicationJournalEntry.SchemaVersion,
              sequence,
              operation,
              delta,
              ApplicationJournalStatus.Prepared,
            )
            val next = candidate.copy(lastJournalSequence = sequence)
            persistTransaction(entry).attempt.flatMap:
              case Left(error) =>
                ref
                  .update(_.copy(recoveryRequired = true))
                  .as(
                    Left(
                      failure(
                        "applicationJournalIncomplete",
                        s"application journal write interrupted: ${error.getMessage}",
                      ),
                    ),
                  )
              case Right(committed) =>
                val nextState = SwayDbApplicationSafetyStore.State(
                  next,
                  state.journal :+ committed,
                  recoveryRequired = false,
                )
                expectedManifest
                  .set(next.drain.activeManifest) >>
                  ref.set(nextState).as(Right(value))

  private def persistTransaction(
      prepared: ApplicationJournalEntry,
  ): IO[ApplicationJournalEntry] =
    val sequence = prepared.sequence
    for
      _ <- writeJournal(prepared)
      _ <- faultInjector.after(
        ApplicationStoreFaultPoint.AfterPrepared,
        sequence,
      )
      _ <- persistDelta(prepared.delta, sequence, injectFaults = true)
      committed = prepared.copy(status = ApplicationJournalStatus.Committed)
      _ <- writeJournal(committed)
      _ <- faultInjector.after(
        ApplicationStoreFaultPoint.AfterCommitted,
        sequence,
      )
    yield committed

  private def persistDelta(
      delta: ApplicationJournalDelta,
      sequence: Long,
      injectFaults: Boolean,
  ): IO[Unit] =
    val after = (point: ApplicationStoreFaultPoint) =>
      if injectFaults then faultInjector.after(point, sequence) else IO.unit
    for
      _ <- delta.locks.traverse_(record =>
        record.lock.inputIds.traverse_(inputId =>
          locks.put(Utf8(inputId.toHex), encodeValue(record)),
        ),
      )
      _ <- after(ApplicationStoreFaultPoint.AfterLocks)
      _ <- delta.reservations.traverse_(record =>
        record.reservation.inputIds.traverse_(inputId =>
          reservations.put(Utf8(inputId.toHex), encodeValue(record)),
        ),
      )
      _ <- after(ApplicationStoreFaultPoint.AfterReservations)
      _ <- delta.applied.traverse_(record =>
        applied.put(
          Utf8(record.applied.executionId.toHexLower),
          encodeValue(record),
        ),
      )
      _ <- after(ApplicationStoreFaultPoint.AfterApplied)
      _ <- delta.terminal.traverse_(record =>
        terminal.put(Utf8(record.executionId.toHexLower), encodeValue(record)),
      )
      _ <- after(ApplicationStoreFaultPoint.AfterTerminal)
      _ <- delta.exactPipelines.traverse_(record =>
        exactPipelines.put(
          pipelineKey(record.genericProjection.pipelineId),
          encodeValue(record),
        ),
      )
      _ <- after(ApplicationStoreFaultPoint.AfterExactPipelines)
      _ <- delta.drain.traverse_(value =>
        control
          .put(SwayDbApplicationSafetyStore.ControlKey, encodeValue(value)),
      )
      _ <- after(ApplicationStoreFaultPoint.AfterControl)
    yield ()

  private def recoverUnderGate
      : IO[Either[ApplicationSafetyRuntimeFailure, ApplicationSafetySnapshot]] =
    loadJournalEntries.value.flatMap:
      case Left(error)    => IO.pure(Left(error))
      case Right(entries) =>
        validateRecoveryManifest(entries).value.flatMap:
          case Left(error) => IO.pure(Left(error))
          case Right(_)    =>
            entries
              .filter(_.status == ApplicationJournalStatus.Prepared)
              .traverse_(entry =>
                persistDelta(
                  entry.delta,
                  entry.sequence,
                  injectFaults = false,
                ) >>
                  writeJournal(
                    entry.copy(status = ApplicationJournalStatus.Committed),
                  ),
              )
              .attempt
              .flatMap:
                case Left(error) =>
                  IO.pure(
                    Left(
                      failure(
                        "applicationJournalIncomplete",
                        s"application journal recovery failed: ${error.getMessage}",
                      ),
                    ),
                  )
                case Right(_) => loadSnapshotAfterRecovery

  private def validateRecoveryManifest(
      entries: Vector[ApplicationJournalEntry],
  ): EitherT[IO, ApplicationSafetyRuntimeFailure, Unit] =
    EitherT
      .liftF[
        IO,
        ApplicationSafetyRuntimeFailure,
        ApplicationProtocolManifestV1,
      ](
        expectedManifest.get,
      )
      .flatMap: expected =>
        control
          .get(SwayDbApplicationSafetyStore.ControlKey)
          .leftMap(error => failure("incompatibleStoreSchema", error.msg))
          .flatMap: persisted =>
            val decodedControl = persisted.traverse(
              decodeValue[ApplicationDrainState],
            )
            val journalDrain = entries.reverseIterator
              .flatMap(_.delta.drain)
              .nextOption
            EitherT.fromEither[IO]:
              decodedControl.flatMap: controlDrain =>
                val effectiveDrain = journalDrain.orElse(controlDrain)
                Either.cond(
                  effectiveDrain.forall(
                    _.activeManifest.configurationDigest ==
                      expected.configurationDigest,
                  ),
                  (),
                  failure(
                    "manifestMismatch",
                    "persisted application manifest differs from startup configuration",
                  ),
                )

  private def loadSnapshotAfterRecovery
      : IO[Either[ApplicationSafetyRuntimeFailure, ApplicationSafetySnapshot]] =
    val loaded = for
      journalEntries <- loadJournalEntries
      lockEntries    <- loadEntries(
        locks,
        decodeLock,
        _.lock.inputIds.map(_.toHex),
      )
      reservationEntries <- loadEntries(
        reservations,
        decodeReservation,
        _.reservation.inputIds.map(_.toHex),
      )
      appliedEntries <- loadEntries(
        applied,
        decodeApplied,
        value => Vector(value.applied.executionId.toHexLower),
      )
      terminalEntries <- loadEntries(
        terminal,
        decodeTerminal,
        value => Vector(value.executionId.toHexLower),
      )
      exactPipelineEntries <- loadEntries(
        exactPipelines,
        decodeExactPipeline,
        value =>
          Vector(
            pipelineKey(value.genericProjection.pipelineId).asString,
          ),
      )
      drain    <- loadDrain
      expected <- EitherT.liftF[
        IO,
        ApplicationSafetyRuntimeFailure,
        ApplicationProtocolManifestV1,
      ](
        expectedManifest.get,
      )
      _ <- EitherT.cond[IO](
        drain.activeManifest.configurationDigest == expected.configurationDigest,
        (),
        failure(
          "manifestMismatch",
          "persisted application manifest differs from startup configuration",
        ),
      )
      sequence = journalEntries.lastOption.fold(0L)(_.sequence)
      lockMap <- EitherT.fromEither[IO](
        lockEntries
          .traverse((key, value) =>
            ApplicationSafetyStoreCodecs.inputId(key).map(_ -> value),
          )
          .map(_.toMap),
      )
      reservationMap <- EitherT.fromEither[IO](
        reservationEntries
          .traverse((key, value) =>
            ApplicationSafetyStoreCodecs.inputId(key).map(_ -> value),
          )
          .map(_.toMap),
      )
      appliedMap <- EitherT.fromEither[IO](
        appliedEntries
          .traverse((key, value) =>
            ApplicationSafetyStoreCodecs.executionId(key).map(_ -> value),
          )
          .map(_.toMap),
      )
      terminalMap <- EitherT.fromEither[IO](
        terminalEntries
          .traverse((key, value) =>
            ApplicationSafetyStoreCodecs.executionId(key).map(_ -> value),
          )
          .map(_.toMap),
      )
      exactPipelineMap <- EitherT.fromEither[IO](
        exactPipelineEntries
          .traverse((key, value) =>
            ApplicationSafetyStoreCodecs.pipelineId(key).map(_ -> value),
          )
          .map(_.toMap),
      )
      candidate = ApplicationSafetySnapshot(
        lockMap,
        reservationMap,
        appliedMap,
        terminalMap,
        exactPipelineMap,
        drain,
        sequence,
      )
      replayed = journalEntries.foldLeft(
        ApplicationSafetySnapshot.initial(drain.activeManifest),
      )((snapshot, entry) =>
        ApplicationJournalDelta.applyTo(
          snapshot,
          entry.delta,
          entry.sequence,
        ),
      )
      _ <- EitherT.cond[IO](
        replayed == candidate,
        (),
        failure(
          "applicationJournalIncomplete",
          "committed application journal does not reproduce the physical stores",
        ),
      )
      repaired = ApplicationSafetyReconciliation.repairLegacyDrainState(
        candidate,
      )
      valid <- EitherT.fromEither[IO](
        ApplicationSafetyReconciliation.validate(repaired),
      )
      state <- persistLegacyDrainRepair(candidate, valid, journalEntries)
    yield state

    loaded.value.flatMap:
      case Left(error)  => IO.pure(Left(error))
      case Right(state) => ref.set(state).as(Right(state.snapshot))

  private def persistLegacyDrainRepair(
      previous: ApplicationSafetySnapshot,
      repaired: ApplicationSafetySnapshot,
      journalEntries: Vector[ApplicationJournalEntry],
  ): EitherT[
    IO,
    ApplicationSafetyRuntimeFailure,
    SwayDbApplicationSafetyStore.State,
  ] =
    if previous == repaired then
      EitherT.rightT(
        SwayDbApplicationSafetyStore.State(
          repaired,
          journalEntries,
          recoveryRequired = false,
        ),
      )
    else
      for
        delta <- EitherT.fromEither[IO](
          ApplicationJournalDelta.between(previous, repaired),
        )
        sequence = previous.lastJournalSequence + 1L
        prepared = ApplicationJournalEntry(
          ApplicationJournalEntry.SchemaVersion,
          sequence,
          "repairLegacyDrainState",
          delta,
          ApplicationJournalStatus.Prepared,
        )
        committed <- EitherT(
          persistTransaction(prepared).attempt.map:
            case Right(value) => Right(value)
            case Left(error)  =>
              Left(
                failure(
                  "applicationJournalIncomplete",
                  s"legacy drain repair was interrupted: ${error.getMessage}",
                ),
              ),
        )
        next     = repaired.copy(lastJournalSequence = sequence)
        replayed = ApplicationJournalDelta.applyTo(
          previous,
          committed.delta,
          sequence,
        )
        _ <- EitherT.cond[IO](
          replayed == next,
          (),
          failure(
            "applicationJournalIncomplete",
            "legacy drain repair journal does not reproduce the repaired state",
          ),
        )
        _ <- EitherT.liftF[IO, ApplicationSafetyRuntimeFailure, Unit](
          recoveryObserver
            .legacyDrainStateRepaired(
              ApplicationStoreLegacyDrainRepair(
                sequence,
                previous.drain.phase,
                repaired.drain.phase,
                previous.drain.greatestAdmittedDeadline,
                repaired.drain.greatestAdmittedDeadline,
              ),
            )
            .handleError(_ => ()),
        )
      yield SwayDbApplicationSafetyStore.State(
        next,
        journalEntries :+ committed,
        recoveryRequired = false,
      )

  private def loadJournalEntries
      : EitherT[IO, ApplicationSafetyRuntimeFailure, Vector[
        ApplicationJournalEntry,
      ]] =
    journalStore
      .from(Utf8(""), 0, Int.MaxValue)
      .leftMap(error => failure("applicationJournalIncomplete", error.msg))
      .flatMap: entries =>
        entries.toVector
          .traverse: (key, value) =>
            EitherT.fromEither[IO]:
              decodeValue[ApplicationJournalEntry](value).flatMap: entry =>
                Either.cond(
                  key == journalKey(entry.sequence),
                  entry,
                  failure(
                    "applicationJournalIncomplete",
                    s"journal key ${key.asString} does not match sequence ${entry.sequence}",
                  ),
                )
          .flatMap(entries =>
            EitherT.fromEither[IO](validateJournalContinuity(entries)),
          )

  private def validateJournalContinuity(
      entries: Vector[ApplicationJournalEntry],
  ): Either[ApplicationSafetyRuntimeFailure, Vector[ApplicationJournalEntry]] =
    entries.zipWithIndex.collectFirst:
      case (entry, index) if entry.sequence != index.toLong + 1L =>
        failure(
          "applicationJournalIncomplete",
          s"journal sequence ${entry.sequence} is not contiguous at position ${index + 1}",
        )
    match
      case Some(value) => Left(value)
      case None        => Right(entries)

  private def loadEntries[A](
      store: StoreIndex[IO, Utf8, Utf8],
      decoder: Utf8 => Either[ApplicationSafetyRuntimeFailure, A],
      validKeys: A => Vector[String],
  ): EitherT[IO, ApplicationSafetyRuntimeFailure, Vector[(String, A)]] =
    store
      .from(Utf8(""), 0, Int.MaxValue)
      .leftMap(error => failure("incompatibleStoreSchema", error.msg))
      .flatMap(entries =>
        entries.toVector.traverse: (key, value) =>
          EitherT.fromEither[IO]:
            decoder(value).flatMap: decoded =>
              Either.cond(
                validKeys(decoded).contains(key.asString),
                key.asString -> decoded,
                failure(
                  "applicationJournalIncomplete",
                  s"store key ${key.asString} does not match its record",
                ),
              ),
      )

  private def loadDrain
      : EitherT[IO, ApplicationSafetyRuntimeFailure, ApplicationDrainState] =
    control
      .get(SwayDbApplicationSafetyStore.ControlKey)
      .leftMap(error => failure("incompatibleStoreSchema", error.msg))
      .flatMap:
        case Some(value) =>
          EitherT.fromEither[IO](decodeValue[ApplicationDrainState](value))
        case None =>
          EitherT.right[ApplicationSafetyRuntimeFailure](
            expectedManifest.get.flatMap: manifest =>
              val initial = ApplicationDrainState.open(manifest)
              control
                .put(
                  SwayDbApplicationSafetyStore.ControlKey,
                  encodeValue(initial),
                )
                .as(initial),
          )

  private def writeJournal(entry: ApplicationJournalEntry): IO[Unit] =
    journalStore.put(journalKey(entry.sequence), encodeValue(entry))

  private def decodeLock(value: Utf8) =
    decodeValue[ApplicationLockRecord](value)
  private def decodeReservation(value: Utf8) =
    decodeValue[ProposalReservationRecord](value)
  private def decodeApplied(value: Utf8) =
    decodeValue[AppliedExecutionRecord](value)
  private def decodeTerminal(value: Utf8) =
    decodeValue[ApplicationTerminalOutcome](value)
  private def decodeExactPipeline(value: Utf8) =
    decodeValue[ExactTxPipelineRecord](value)

  private def decodeValue[A: Decoder](
      value: Utf8,
  ): Either[ApplicationSafetyRuntimeFailure, A] =
    decode[A](value.asString).leftMap(error =>
      failure("incompatibleStoreSchema", error.getMessage),
    )

  private def encodeValue[A: Encoder](value: A): Utf8 = Utf8(
    value.asJson.noSpaces,
  )

  private def journalKey(sequence: Long): Utf8 = Utf8(f"$sequence%020d")

  private def pipelineKey(pipelineId: TxPipelineId): Utf8 =
    Utf8(
      ByteVector
        .view(pipelineId.value.getBytes(StandardCharsets.UTF_8))
        .toHex,
    )

  private def failure(reason: String, detail: String) =
    ApplicationSafetyRuntimeFailure(reason, detail)

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Nothing",
  ),
)
object SwayDbApplicationSafetyStore:
  private final case class State(
      snapshot: ApplicationSafetySnapshot,
      journal: Vector[ApplicationJournalEntry],
      recoveryRequired: Boolean,
  )

  private val ControlKey = Utf8("drain")

  def resource(
      layout: StorageLayout.Application,
      expectedManifest: ApplicationProtocolManifestV1,
  )(using Bag.Async[IO]): Resource[IO, SwayDbApplicationSafetyStore] =
    resourceWithFaults(
      layout,
      expectedManifest,
      ApplicationStoreFaultInjector.none,
    )

  def resourceWithFaults(
      layout: StorageLayout.Application,
      expectedManifest: ApplicationProtocolManifestV1,
      faultInjector: ApplicationStoreFaultInjector,
  )(using Bag.Async[IO]): Resource[IO, SwayDbApplicationSafetyStore] =
    for
      database <- SwayStores.storeIndex[Utf8, Utf8](layout.database)
      store    <- Resource.eval(
        openSessionWithObserver(
          database,
          expectedManifest,
          faultInjector,
          ApplicationStoreRecoveryObserver.logging,
        ),
      )
    yield store

  private[swaydb] def openSession(
      database: StoreIndex[IO, Utf8, Utf8],
      expectedManifest: ApplicationProtocolManifestV1,
      faultInjector: ApplicationStoreFaultInjector,
  ): IO[SwayDbApplicationSafetyStore] =
    openSessionWithObserver(
      database,
      expectedManifest,
      faultInjector,
      ApplicationStoreRecoveryObserver.logging,
    )

  private[swaydb] def openSessionWithObserver(
      database: StoreIndex[IO, Utf8, Utf8],
      expectedManifest: ApplicationProtocolManifestV1,
      faultInjector: ApplicationStoreFaultInjector,
      recoveryObserver: ApplicationStoreRecoveryObserver,
  ): IO[SwayDbApplicationSafetyStore] =
    for
      validatedManifest <- IO.fromEither(
        ApplicationProtocolManifestV1
          .validate(expectedManifest)
          .left
          .map(error =>
            new IllegalArgumentException(
              s"${error.reason}: invalid startup application manifest",
            ),
          ),
      )
      _    <- validatePhysicalKeyspace(database)
      gate <- Semaphore[IO](1L)
      ref  <- Ref.of[IO, State](
        State(
          ApplicationSafetySnapshot.initial(validatedManifest),
          Vector.empty,
          recoveryRequired = false,
        ),
      )
      expectedManifestRef <- Ref.of[IO, ApplicationProtocolManifestV1](
        validatedManifest,
      )
      store = new SwayDbApplicationSafetyStore(
        ApplicationStoreNamespace(database, "locks/"),
        ApplicationStoreNamespace(database, "reservations/"),
        ApplicationStoreNamespace(database, "applied/"),
        ApplicationStoreNamespace(database, "terminal/"),
        ApplicationStoreNamespace(database, "exact-pipelines/"),
        ApplicationStoreNamespace(database, "journal/"),
        ApplicationStoreNamespace(database, "control/"),
        expectedManifestRef,
        faultInjector,
        recoveryObserver,
        gate,
        ref,
      )
      _ <- store.recover.value.flatMap:
        case Right(_)    => IO.unit
        case Left(error) =>
          IO.raiseError(
            new IllegalStateException(s"${error.reason}: ${error.detail}"),
          )
    yield store

  private def validatePhysicalKeyspace(
      database: StoreIndex[IO, Utf8, Utf8],
  ): IO[Unit] =
    database
      .from(Utf8(""), 0, Int.MaxValue)
      .value
      .flatMap:
        case Left(error) =>
          IO.raiseError(
            new IllegalStateException(
              s"incompatibleStoreSchema: ${error.msg}",
            ),
          )
        case Right(entries) =>
          entries.iterator
            .map(_._1.asString)
            .find(key => !isKnownPhysicalKey(key)) match
            case Some(key) =>
              IO.raiseError(
                new IllegalStateException(
                  s"incompatibleStoreSchema: unknown application safety key $key",
                ),
              )
            case None => IO.unit

  private def isKnownPhysicalKey(key: String): Boolean =
    key === "control/drain" ||
      key.matches("locks/(?:[0-9a-f]{2})+") ||
      key.matches("reservations/(?:[0-9a-f]{2})+") ||
      key.matches("applied/[0-9a-f]{64}") ||
      key.matches("terminal/[0-9a-f]{64}") ||
      key.matches("exact-pipelines/(?:[0-9a-f]{2})+") ||
      key.matches("journal/[0-9]{20}")

private object ApplicationSafetyStoreCodecs:
  import org.sigilaris.core.application.protocol.ApplicationInputId
  import org.sigilaris.core.datatype.UInt256

  def inputId(
      value: String,
  ): Either[ApplicationSafetyRuntimeFailure, ApplicationInputId] =
    for
      bytes <- ByteVector
        .fromHexDescriptive(value)
        .leftMap(error =>
          ApplicationSafetyRuntimeFailure("incompatibleStoreSchema", error),
        )
      input <- ApplicationInputId
        .fromBytes(bytes)
        .leftMap(error =>
          ApplicationSafetyRuntimeFailure("incompatibleStoreSchema", error),
        )
    yield input

  def executionId(
      value: String,
  ): Either[ApplicationSafetyRuntimeFailure, ExecutionId] =
    UInt256
      .fromHex(value)
      .leftMap(error =>
        ApplicationSafetyRuntimeFailure(
          "incompatibleStoreSchema",
          error.toString,
        ),
      )
      .map(ExecutionId(_))

  def pipelineId(
      value: String,
  ): Either[ApplicationSafetyRuntimeFailure, TxPipelineId] =
    for
      bytes <- ByteVector
        .fromHexDescriptive(value)
        .leftMap(error =>
          ApplicationSafetyRuntimeFailure("incompatibleStoreSchema", error),
        )
      decoded <- bytes.decodeUtf8.leftMap(error =>
        ApplicationSafetyRuntimeFailure(
          "incompatibleStoreSchema",
          error.getMessage,
        ),
      )
      pipelineId <- TxPipelineId
        .parse(decoded)
        .leftMap(error =>
          ApplicationSafetyRuntimeFailure("incompatibleStoreSchema", error),
        )
    yield pipelineId

@SuppressWarnings(Array("org.wartremover.warts.Any"))
private final class ApplicationStoreNamespace(
    underlying: StoreIndex[IO, Utf8, Utf8],
    prefix: String,
) extends StoreIndex[IO, Utf8, Utf8]:
  override def get(key: Utf8): EitherT[IO, DecodeFailure, Option[Utf8]] =
    underlying.get(namespaced(key))

  override def put(key: Utf8, value: Utf8): IO[Unit] =
    underlying.put(namespaced(key), value)

  override def remove(key: Utf8): IO[Unit] = underlying.remove(namespaced(key))

  override def from(
      key: Utf8,
      offset: Int,
      limit: Int,
  ): EitherT[IO, DecodeFailure, List[(Utf8, Utf8)]] =
    underlying
      .from(Utf8(""), 0, Int.MaxValue)
      .map: entries =>
        val start = offset.max(0)
        val end   = (start.toLong + limit.max(0).toLong)
          .min(Int.MaxValue.toLong)
          .toInt
        entries
          .filter((storedKey, _) => storedKey.asString.startsWith(prefix))
          .map((storedKey, value) =>
            Utf8(storedKey.asString.stripPrefix(prefix)) -> value,
          )
          .sortBy(_._1.asString)
          .dropWhile(_._1.asString < key.asString)
          .slice(start, end)

  private def namespaced(key: Utf8): Utf8 = Utf8(s"$prefix${key.asString}")

private object ApplicationStoreNamespace:
  def apply(
      underlying: StoreIndex[IO, Utf8, Utf8],
      prefix: String,
  ): StoreIndex[IO, Utf8, Utf8] =
    new ApplicationStoreNamespace(underlying, prefix)
