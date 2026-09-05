package org.sigilaris.node.jvm.runtime.application

import io.circe.{Decoder, DecodingFailure, Encoder, HCursor, Json}
import io.circe.generic.semiauto.*
import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.*
import org.sigilaris.core.datatype.{BigNat, UInt256}
import org.sigilaris.node.txpipeline.{
  ApplicationProtocolManifestV1,
  ExactPipelineLifecycle,
  ExactTxPipelineRecord,
  TxPipelineId,
}

final case class ApplicationArtifactContext(
    protocolVersion: ProtocolVersion,
    configurationDigest: ApplicationConfigurationDigest,
    epoch: ApplicationEpoch,
    validatorSetHash: ApplicationValidatorSetHash,
    dependencyPlanDigest: DependencyPlanDigest,
)

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object ApplicationArtifactContext:
  def fromLockSubject(
      subject: ApplicationLockVoteSubject,
  ): ApplicationArtifactContext =
    ApplicationArtifactContext(
      subject.protocolVersion,
      subject.configurationDigest,
      subject.epoch,
      subject.validatorSetHash,
      subject.dependencyPlanDigest,
    )

  def fromEffectSubject(
      subject: CertifiedEffectVoteSubject,
  ): ApplicationArtifactContext =
    ApplicationArtifactContext(
      subject.protocolVersion,
      subject.configurationDigest,
      subject.epoch,
      subject.validatorSetHash,
      subject.dependencyPlanDigest,
    )

  def matchesManifest(
      context: ApplicationArtifactContext,
      manifest: ApplicationProtocolManifestV1,
  ): Boolean =
    context.protocolVersion == ProtocolVersion.M1 &&
      context.configurationDigest.toUInt256.toHexLower == manifest.configurationDigest &&
      context.epoch.value == manifest.epoch &&
      context.validatorSetHash.toUInt256.toHexLower == manifest.validatorSetHash

  import ApplicationSafetyJsonCodecs.given
  given Encoder[ApplicationArtifactContext] = deriveEncoder
  given Decoder[ApplicationArtifactContext] = deriveDecoder

final case class ApplicationLockRecord(
    schemaVersion: Int,
    context: ApplicationArtifactContext,
    lock: ApplicationLock,
)

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
  ),
)
object ApplicationLockRecord:
  val SchemaVersion: Int = 1

  def create(
      context: ApplicationArtifactContext,
      lock: ApplicationLock,
  ): Either[ApplicationSafetyRuntimeFailure, ApplicationLockRecord] =
    validate(ApplicationLockRecord(SchemaVersion, context, lock))

  def validate(
      value: ApplicationLockRecord,
  ): Either[ApplicationSafetyRuntimeFailure, ApplicationLockRecord] =
    if value.schemaVersion != SchemaVersion then
      Left(
        ApplicationSafetyRuntimeFailure(
          "incompatibleStoreSchema",
          s"unsupported application lock schema ${value.schemaVersion}",
        ),
      )
    else if value.lock.inputIds.isEmpty then
      Left(
        ApplicationSafetyRuntimeFailure(
          "inputCommitmentMismatch",
          "lock inputIds must be non-empty",
        ),
      )
    else if hasDuplicateInputs(value.lock.inputIds) then
      Left(
        ApplicationSafetyRuntimeFailure(
          "inputCommitmentMismatch",
          "lock inputIds contain duplicates",
        ),
      )
    else if value.lock.inputIds != value.lock.inputIds.sortBy(_.toHex) then
      Left(
        ApplicationSafetyRuntimeFailure(
          "inputCommitmentMismatch",
          "lock inputIds are not canonical",
        ),
      )
    else Right(value)

  private def hasDuplicateInputs(values: Vector[ApplicationInputId]): Boolean =
    values.groupBy(_.toHex).exists(_._2.sizeCompare(1) > 0)

  import ApplicationSafetyJsonCodecs.given
  given Encoder[ApplicationLockRecord] = deriveEncoder
  given Decoder[ApplicationLockRecord] = Decoder.instance: cursor =>
    decodeSchema(cursor, "application lock").flatMap: _ =>
      for
        context <- cursor.get[ApplicationArtifactContext]("context")
        lock    <- cursor.get[ApplicationLock]("lock")
        value   <- decodeValidated(
          cursor,
          validate(ApplicationLockRecord(SchemaVersion, context, lock)),
        )
      yield value

  private def decodeSchema(
      cursor: HCursor,
      label: String,
  ): Decoder.Result[Unit] =
    cursor
      .get[Int]("schemaVersion")
      .flatMap: schemaVersion =>
        Either.cond(
          schemaVersion == SchemaVersion,
          (),
          DecodingFailure(
            s"unsupported $label schema $schemaVersion",
            cursor.history,
          ),
        )

  private def decodeValidated[A](
      cursor: HCursor,
      value: Either[ApplicationSafetyRuntimeFailure, A],
  ): Decoder.Result[A] =
    value.left.map(error => DecodingFailure(error.detail, cursor.history))

final case class ProposalReservationRecord(
    schemaVersion: Int,
    context: ApplicationArtifactContext,
    reservation: ProposalReservation,
)

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
  ),
)
object ProposalReservationRecord:
  val SchemaVersion: Int = 1

  def create(
      context: ApplicationArtifactContext,
      reservation: ProposalReservation,
  ): Either[ApplicationSafetyRuntimeFailure, ProposalReservationRecord] =
    validate(ProposalReservationRecord(SchemaVersion, context, reservation))

  def validate(
      value: ProposalReservationRecord,
  ): Either[ApplicationSafetyRuntimeFailure, ProposalReservationRecord] =
    if value.schemaVersion != SchemaVersion then
      Left(
        ApplicationSafetyRuntimeFailure(
          "incompatibleStoreSchema",
          s"unsupported proposal reservation schema ${value.schemaVersion}",
        ),
      )
    else if value.reservation.inputIds.isEmpty then
      Left(
        ApplicationSafetyRuntimeFailure(
          "reservationMissing",
          "reservation inputIds must be non-empty",
        ),
      )
    else if hasDuplicateInputs(value.reservation.inputIds) then
      Left(
        ApplicationSafetyRuntimeFailure(
          "reservationConflict",
          "reservation inputIds contain duplicates",
        ),
      )
    else if value.reservation.inputIds != value.reservation.inputIds.sortBy(
        _.toHex,
      )
    then
      Left(
        ApplicationSafetyRuntimeFailure(
          "reservationConflict",
          "reservation inputIds are not canonical",
        ),
      )
    else Right(value)

  private def hasDuplicateInputs(values: Vector[ApplicationInputId]): Boolean =
    values.groupBy(_.toHex).exists(_._2.sizeCompare(1) > 0)

  import ApplicationSafetyJsonCodecs.given
  given Encoder[ProposalReservationRecord] = deriveEncoder
  given Decoder[ProposalReservationRecord] = Decoder.instance: cursor =>
    decodeSchema(cursor).flatMap: _ =>
      for
        context     <- cursor.get[ApplicationArtifactContext]("context")
        reservation <- cursor.get[ProposalReservation]("reservation")
        value       <- decodeValidated(
          cursor,
          validate(
            ProposalReservationRecord(
              SchemaVersion,
              context,
              reservation,
            ),
          ),
        )
      yield value

  private def decodeSchema(cursor: HCursor): Decoder.Result[Unit] =
    cursor
      .get[Int]("schemaVersion")
      .flatMap: schemaVersion =>
        Either.cond(
          schemaVersion == SchemaVersion,
          (),
          DecodingFailure(
            s"unsupported proposal reservation schema $schemaVersion",
            cursor.history,
          ),
        )

  private def decodeValidated[A](
      cursor: HCursor,
      value: Either[ApplicationSafetyRuntimeFailure, A],
  ): Decoder.Result[A] =
    value.left.map(error => DecodingFailure(error.detail, cursor.history))

final case class AppliedExecutionRecord(
    schemaVersion: Int,
    context: ApplicationArtifactContext,
    applied: AppliedExecution,
    stateRoot: ApplicationStateRoot,
)

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
  ),
)
object AppliedExecutionRecord:
  val SchemaVersion: Int = 1

  def create(
      context: ApplicationArtifactContext,
      applied: AppliedExecution,
      stateRoot: ApplicationStateRoot,
  ): AppliedExecutionRecord =
    AppliedExecutionRecord(SchemaVersion, context, applied, stateRoot)

  def validate(
      value: AppliedExecutionRecord,
  ): Either[ApplicationSafetyRuntimeFailure, AppliedExecutionRecord] =
    if value.schemaVersion != SchemaVersion then
      Left(
        ApplicationSafetyRuntimeFailure(
          "incompatibleStoreSchema",
          s"unsupported applied execution schema ${value.schemaVersion}",
        ),
      )
    else
      Either.cond(
        value.context.protocolVersion == value.applied.protocolVersion,
        value,
        ApplicationSafetyRuntimeFailure(
          "applicationJournalIncomplete",
          "applied execution protocol version disagrees with its artifact context",
        ),
      )

  import ApplicationSafetyJsonCodecs.given
  given Encoder[AppliedExecutionRecord] = deriveEncoder
  given Decoder[AppliedExecutionRecord] = Decoder.instance: cursor =>
    decodeSchema(cursor).flatMap: _ =>
      for
        context   <- cursor.get[ApplicationArtifactContext]("context")
        applied   <- cursor.get[AppliedExecution]("applied")
        stateRoot <- cursor.get[ApplicationStateRoot]("stateRoot")
        value     <- validate(
          AppliedExecutionRecord(SchemaVersion, context, applied, stateRoot),
        ).left.map(error => DecodingFailure(error.detail, cursor.history))
      yield value

  private def decodeSchema(cursor: HCursor): Decoder.Result[Unit] =
    cursor
      .get[Int]("schemaVersion")
      .flatMap: schemaVersion =>
        Either.cond(
          schemaVersion == SchemaVersion,
          (),
          DecodingFailure(
            s"unsupported applied execution schema $schemaVersion",
            cursor.history,
          ),
        )

final case class ApplicationTerminalOutcome(
    schemaVersion: Int,
    context: ApplicationArtifactContext,
    executionId: ExecutionId,
    lastInclusionHeight: InclusionHeight,
    lifecycle: ApplicationEntryLifecycle,
    applied: Option[AppliedExecution],
    finalizedCanonicalHeight: Option[InclusionHeight],
)

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
  ),
)
object ApplicationTerminalOutcome:
  val SchemaVersion: Int = 1

  def applied(
      context: ApplicationArtifactContext,
      value: AppliedExecution,
      lastInclusionHeight: InclusionHeight,
  ): ApplicationTerminalOutcome =
    ApplicationTerminalOutcome(
      SchemaVersion,
      context,
      value.executionId,
      lastInclusionHeight,
      ApplicationEntryLifecycle.Applied,
      Some(value),
      None,
    )

  def expiredUnapplied(
      context: ApplicationArtifactContext,
      executionId: ExecutionId,
      lastInclusionHeight: InclusionHeight,
      finalizedCanonicalHeight: InclusionHeight,
  ): ApplicationTerminalOutcome =
    ApplicationTerminalOutcome(
      SchemaVersion,
      context,
      executionId,
      lastInclusionHeight,
      ApplicationEntryLifecycle.ExpiredUnapplied,
      None,
      Some(finalizedCanonicalHeight),
    )

  def validate(
      value: ApplicationTerminalOutcome,
  ): Either[ApplicationSafetyRuntimeFailure, ApplicationTerminalOutcome] =
    if value.schemaVersion != SchemaVersion then
      Left(
        ApplicationSafetyRuntimeFailure(
          "incompatibleStoreSchema",
          s"unsupported terminal outcome schema ${value.schemaVersion}",
        ),
      )
    else
      value.lifecycle match
        case ApplicationEntryLifecycle.Live =>
          Left(
            ApplicationSafetyRuntimeFailure(
              "applicationJournalIncomplete",
              "terminal outcome cannot be live",
            ),
          )
        case ApplicationEntryLifecycle.Applied =>
          Either.cond(
            value.applied.exists(applied =>
              applied.executionId == value.executionId &&
                applied.protocolVersion == value.context.protocolVersion &&
                applied.firstApplicationHeight.toBigNat.toBigInt <=
                value.lastInclusionHeight.toBigNat.toBigInt,
            ) && value.finalizedCanonicalHeight.isEmpty,
            value,
            ApplicationSafetyRuntimeFailure(
              "applicationJournalIncomplete",
              "applied terminal outcome has inconsistent application or expiry evidence",
            ),
          )
        case ApplicationEntryLifecycle.ExpiredUnapplied =>
          Either.cond(
            value.applied.isEmpty && value.finalizedCanonicalHeight.exists(
              _.toBigNat.toBigInt > value.lastInclusionHeight.toBigNat.toBigInt,
            ),
            value,
            ApplicationSafetyRuntimeFailure(
              "applicationJournalIncomplete",
              "expired-unapplied outcome requires finality strictly past its deadline",
            ),
          )

  import ApplicationSafetyJsonCodecs.given
  given Encoder[ApplicationTerminalOutcome] = deriveEncoder
  given Decoder[ApplicationTerminalOutcome] = Decoder.instance: cursor =>
    decodeSchema(cursor).flatMap: _ =>
      for
        context             <- cursor.get[ApplicationArtifactContext]("context")
        executionId         <- cursor.get[ExecutionId]("executionId")
        lastInclusionHeight <- cursor.get[InclusionHeight](
          "lastInclusionHeight",
        )
        lifecycle <- cursor.get[ApplicationEntryLifecycle]("lifecycle")
        _         <- requireField(cursor, "applied")
        applied   <- cursor.get[Option[AppliedExecution]]("applied")
        _         <- requireField(cursor, "finalizedCanonicalHeight")
        finalizedCanonicalHeight <- cursor.get[Option[InclusionHeight]](
          "finalizedCanonicalHeight",
        )
        value <- validate(
          ApplicationTerminalOutcome(
            SchemaVersion,
            context,
            executionId,
            lastInclusionHeight,
            lifecycle,
            applied,
            finalizedCanonicalHeight,
          ),
        ).left.map(error => DecodingFailure(error.detail, cursor.history))
      yield value

  private def decodeSchema(cursor: HCursor): Decoder.Result[Unit] =
    cursor
      .get[Int]("schemaVersion")
      .flatMap: schemaVersion =>
        Either.cond(
          schemaVersion == SchemaVersion,
          (),
          DecodingFailure(
            s"unsupported terminal outcome schema $schemaVersion",
            cursor.history,
          ),
        )

  private def requireField(
      cursor: HCursor,
      field: String,
  ): Decoder.Result[Unit] =
    Either.cond(
      cursor.downField(field).succeeded,
      (),
      DecodingFailure(s"missing required field $field", cursor.history),
    )

enum ApplicationDrainPhase(val wire: String):
  case Open            extends ApplicationDrainPhase("open")
  case Closing         extends ApplicationDrainPhase("closing")
  case WaitingFinality extends ApplicationDrainPhase("waitingFinality")
  case Ready           extends ApplicationDrainPhase("ready")
  case Activating      extends ApplicationDrainPhase("activating")

@SuppressWarnings(
  Array("org.wartremover.warts.Any", "org.wartremover.warts.Equals"),
)
object ApplicationDrainPhase:
  private val all = Vector(Open, Closing, WaitingFinality, Ready, Activating)
  given Encoder[ApplicationDrainPhase] = Encoder.encodeString.contramap(_.wire)
  given Decoder[ApplicationDrainPhase] = Decoder.decodeString.emap: value =>
    all.find(_.wire == value).toRight(s"unsupported drain phase: $value")

final case class ApplicationDrainState(
    schemaVersion: Int,
    phase: ApplicationDrainPhase,
    activeManifest: ApplicationProtocolManifestV1,
    greatestAdmittedDeadline: Option[InclusionHeight],
)

@SuppressWarnings(
  Array("org.wartremover.warts.Any", "org.wartremover.warts.Nothing"),
)
object ApplicationDrainState:
  val SchemaVersion: Int = 1

  def open(manifest: ApplicationProtocolManifestV1): ApplicationDrainState =
    ApplicationDrainState(
      SchemaVersion,
      ApplicationDrainPhase.Open,
      manifest,
      None,
    )

  def validate(
      value: ApplicationDrainState,
  ): Either[ApplicationSafetyRuntimeFailure, ApplicationDrainState] =
    for
      _ <- Either.cond(
        value.schemaVersion == SchemaVersion,
        (),
        ApplicationSafetyRuntimeFailure(
          "incompatibleStoreSchema",
          s"unsupported drain state schema ${value.schemaVersion}",
        ),
      )

      _ <- ApplicationProtocolManifestV1
        .validate(value.activeManifest)
        .left
        .map(error =>
          ApplicationSafetyRuntimeFailure(
            error.reason,
            "active application manifest is invalid",
          ),
        )
    yield value

  import ApplicationSafetyJsonCodecs.given
  given Encoder[ApplicationDrainState] = deriveEncoder
  given Decoder[ApplicationDrainState] = Decoder.instance: cursor =>
    decodeSchema(cursor).flatMap: _ =>
      for
        phase          <- cursor.get[ApplicationDrainPhase]("phase")
        activeManifest <- cursor.get[ApplicationProtocolManifestV1](
          "activeManifest",
        )
        _ <- requireField(cursor, "greatestAdmittedDeadline")
        greatestAdmittedDeadline <- cursor.get[Option[InclusionHeight]](
          "greatestAdmittedDeadline",
        )
        value <- validate(
          ApplicationDrainState(
            SchemaVersion,
            phase,
            activeManifest,
            greatestAdmittedDeadline,
          ),
        ).left.map(error => DecodingFailure(error.detail, cursor.history))
      yield value

  private def decodeSchema(cursor: HCursor): Decoder.Result[Unit] =
    cursor
      .get[Int]("schemaVersion")
      .flatMap: schemaVersion =>
        Either.cond(
          schemaVersion == SchemaVersion,
          (),
          DecodingFailure(
            s"unsupported drain state schema $schemaVersion",
            cursor.history,
          ),
        )

  private def requireField(
      cursor: HCursor,
      field: String,
  ): Decoder.Result[Unit] =
    Either.cond(
      cursor.downField(field).succeeded,
      (),
      DecodingFailure(s"missing required field $field", cursor.history),
    )

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
final case class ApplicationSafetySnapshot(
    locks: Map[ApplicationInputId, ApplicationLockRecord],
    reservations: Map[ApplicationInputId, ProposalReservationRecord],
    applied: Map[ExecutionId, AppliedExecutionRecord],
    terminal: Map[ExecutionId, ApplicationTerminalOutcome],
    exactPipelines: Map[TxPipelineId, ExactTxPipelineRecord],
    drain: ApplicationDrainState,
    lastJournalSequence: Long,
):
  def liveLockCount: Int =
    locks.valuesIterator
      .filter(_.lock.lifecycle == ApplicationEntryLifecycle.Live)
      .map(_.lock.executionId.toHexLower)
      .toSet
      .size

  def liveReservationCount: Int =
    reservations.valuesIterator
      .filter(_.reservation.lifecycle == ApplicationEntryLifecycle.Live)
      .map(_.reservation.executionId.toHexLower)
      .toSet
      .size

  def liveExactPipelineCount: Int =
    exactPipelines.valuesIterator.count(record =>
      !ExactPipelineLifecycle.isTerminal(record.lifecycle),
    )

  def hasLiveEntries: Boolean =
    liveLockCount > 0 || liveReservationCount > 0 ||
      liveExactPipelineCount > 0

object ApplicationSafetySnapshot:
  def initial(
      manifest: ApplicationProtocolManifestV1,
  ): ApplicationSafetySnapshot =
    ApplicationSafetySnapshot(
      Map.empty,
      Map.empty,
      Map.empty,
      Map.empty,
      Map.empty,
      ApplicationDrainState.open(manifest),
      0L,
    )

enum ApplicationJournalStatus(val wire: String):
  case Prepared  extends ApplicationJournalStatus("prepared")
  case Committed extends ApplicationJournalStatus("committed")

@SuppressWarnings(
  Array("org.wartremover.warts.Any", "org.wartremover.warts.Equals"),
)
object ApplicationJournalStatus:
  private val all                         = Vector(Prepared, Committed)
  given Encoder[ApplicationJournalStatus] =
    Encoder.encodeString.contramap(_.wire)
  given Decoder[ApplicationJournalStatus] = Decoder.decodeString.emap: value =>
    all.find(_.wire == value).toRight(s"unsupported journal status: $value")

final case class ApplicationJournalDelta(
    locks: Vector[ApplicationLockRecord],
    reservations: Vector[ProposalReservationRecord],
    applied: Vector[AppliedExecutionRecord],
    terminal: Vector[ApplicationTerminalOutcome],
    exactPipelines: Vector[ExactTxPipelineRecord],
    drain: Option[ApplicationDrainState],
)

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
  ),
)
object ApplicationJournalDelta:
  val empty: ApplicationJournalDelta =
    ApplicationJournalDelta(
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      None,
    )

  def between(
      previous: ApplicationSafetySnapshot,
      next: ApplicationSafetySnapshot,
  ): Either[ApplicationSafetyRuntimeFailure, ApplicationJournalDelta] =
    if removedKeys(previous.locks, next.locks) ||
      removedKeys(previous.reservations, next.reservations) ||
      removedKeys(previous.applied, next.applied) ||
      removedKeys(previous.terminal, next.terminal) ||
      removedKeys(previous.exactPipelines, next.exactPipelines)
    then
      Left(
        ApplicationSafetyRuntimeFailure(
          "applicationJournalIncomplete",
          "application journal mutations may not delete audit records",
        ),
      )
    else
      Right(
        ApplicationJournalDelta(
          changed(previous.locks, next.locks),
          changed(previous.reservations, next.reservations),
          changed(previous.applied, next.applied),
          changed(previous.terminal, next.terminal),
          changed(previous.exactPipelines, next.exactPipelines),
          Option.when(previous.drain != next.drain)(next.drain),
        ),
      )

  def applyTo(
      snapshot: ApplicationSafetySnapshot,
      delta: ApplicationJournalDelta,
      sequence: Long,
  ): ApplicationSafetySnapshot =
    snapshot.copy(
      locks = snapshot.locks ++ delta.locks.flatMap(record =>
        record.lock.inputIds.map(_ -> record),
      ),
      reservations = snapshot.reservations ++ delta.reservations.flatMap(
        record => record.reservation.inputIds.map(_ -> record),
      ),
      applied = snapshot.applied ++ delta.applied.map(record =>
        record.applied.executionId -> record,
      ),
      terminal = snapshot.terminal ++ delta.terminal.map(record =>
        record.executionId -> record,
      ),
      exactPipelines = snapshot.exactPipelines ++ delta.exactPipelines.map(
        record => record.genericProjection.pipelineId -> record,
      ),
      drain = delta.drain.getOrElse(snapshot.drain),
      lastJournalSequence = sequence.max(snapshot.lastJournalSequence),
    )

  private def removedKeys[K, V](previous: Map[K, V], next: Map[K, V]): Boolean =
    previous.keysIterator.exists(key => !next.contains(key))

  private def changed[K, V](previous: Map[K, V], next: Map[K, V]): Vector[V] =
    next.iterator
      .collect:
        case (key, value) if previous.get(key).forall(_ != value) => value
      .toVector
      .distinct

  given Encoder[ApplicationJournalDelta] = deriveEncoder
  given Decoder[ApplicationJournalDelta] = Decoder.instance: cursor =>
    for
      locks        <- cursor.get[Vector[ApplicationLockRecord]]("locks")
      reservations <- cursor.get[Vector[ProposalReservationRecord]](
        "reservations",
      )
      applied  <- cursor.get[Vector[AppliedExecutionRecord]]("applied")
      terminal <- cursor.get[Vector[ApplicationTerminalOutcome]]("terminal")
      exactPipelines <-
        if cursor.downField("exactPipelines").succeeded then
          cursor.get[Vector[ExactTxPipelineRecord]]("exactPipelines")
        else Right(Vector.empty)
      _ <- Either.cond(
        cursor.downField("drain").succeeded,
        (),
        DecodingFailure("missing required field drain", cursor.history),
      )
      drain <- cursor.get[Option[ApplicationDrainState]]("drain")
    yield ApplicationJournalDelta(
      locks,
      reservations,
      applied,
      terminal,
      exactPipelines,
      drain,
    )

final case class ApplicationJournalEntry(
    schemaVersion: Int,
    sequence: Long,
    operation: String,
    delta: ApplicationJournalDelta,
    status: ApplicationJournalStatus,
)

@SuppressWarnings(
  Array("org.wartremover.warts.Any", "org.wartremover.warts.Nothing"),
)
object ApplicationJournalEntry:
  val SchemaVersion: Int = 1

  def validate(
      value: ApplicationJournalEntry,
  ): Either[ApplicationSafetyRuntimeFailure, ApplicationJournalEntry] =
    if value.schemaVersion != SchemaVersion then
      Left(
        ApplicationSafetyRuntimeFailure(
          "incompatibleStoreSchema",
          s"unsupported application journal schema ${value.schemaVersion}",
        ),
      )
    else if value.sequence <= 0L then
      Left(
        ApplicationSafetyRuntimeFailure(
          "applicationJournalIncomplete",
          "journal sequence must be positive",
        ),
      )
    else if value.operation.trim.isEmpty then
      Left(
        ApplicationSafetyRuntimeFailure(
          "applicationJournalIncomplete",
          "journal operation must be non-empty",
        ),
      )
    else Right(value)

  given Encoder[ApplicationJournalEntry] = deriveEncoder
  given Decoder[ApplicationJournalEntry] = Decoder.instance: cursor =>
    decodeSchema(cursor).flatMap: _ =>
      for
        sequence  <- cursor.get[Long]("sequence")
        operation <- cursor.get[String]("operation")
        delta     <- cursor.get[ApplicationJournalDelta]("delta")
        status    <- cursor.get[ApplicationJournalStatus]("status")
        value     <- validate(
          ApplicationJournalEntry(
            SchemaVersion,
            sequence,
            operation,
            delta,
            status,
          ),
        ).left.map(error => DecodingFailure(error.detail, cursor.history))
      yield value

  private def decodeSchema(cursor: HCursor): Decoder.Result[Unit] =
    cursor
      .get[Int]("schemaVersion")
      .flatMap: schemaVersion =>
        Either.cond(
          schemaVersion == SchemaVersion,
          (),
          DecodingFailure(
            s"unsupported application journal schema $schemaVersion",
            cursor.history,
          ),
        )

final case class ApplicationSafetyRuntimeFailure(reason: String, detail: String)

@SuppressWarnings(
  Array("org.wartremover.warts.Any", "org.wartremover.warts.Nothing"),
)
private object ApplicationSafetyJsonCodecs:
  given Encoder[ExecutionId] = Encoder.encodeString.contramap(_.toHexLower)
  given Decoder[ExecutionId] = decodeUInt256("executionId").map(ExecutionId(_))

  given Encoder[ApplicationInputId] = Encoder.encodeString.contramap(_.toHex)
  given Decoder[ApplicationInputId] = Decoder.decodeString.emap: value =>
    for
      _ <- Either.cond(
        value.matches("(?:[0-9a-f]{2})+"),
        (),
        "applicationInputId must be non-empty lowercase hexadecimal bytes",
      )
      bytes   <- ByteVector.fromHexDescriptive(value)
      inputId <- ApplicationInputId.fromBytes(bytes)
    yield inputId

  given Encoder[InclusionHeight] =
    Encoder.instance { value => Json.fromBigInt(value.toBigNat.toBigInt) }
  given Decoder[InclusionHeight] = Decoder.decodeBigInt.emap(value =>
    BigNat.fromBigInt(value).map(InclusionHeight(_)),
  )

  given Encoder[ApplicationResultDigest] =
    Encoder.encodeString.contramap(_.toHexLower)
  given Decoder[ApplicationResultDigest] =
    decodeUInt256("applicationResultDigest").map(ApplicationResultDigest(_))

  given Encoder[ApplicationStateRoot] =
    Encoder.encodeString.contramap(_.toHexLower)
  given Decoder[ApplicationStateRoot] =
    decodeUInt256("applicationStateRoot").map(ApplicationStateRoot(_))

  given Encoder[ProtocolVersion] = Encoder.encodeInt.contramap(_.value)
  given Decoder[ProtocolVersion] =
    Decoder.decodeInt.emap(ProtocolVersion.fromInt)

  given Encoder[ApplicationEpoch] = Encoder.encodeLong.contramap(_.value)
  given Decoder[ApplicationEpoch] =
    Decoder.decodeLong.emap { value => ApplicationEpoch.fromLong(value) }

  given Encoder[ApplicationConfigurationDigest] =
    Encoder.encodeString.contramap(_.toUInt256.toHexLower)
  given Decoder[ApplicationConfigurationDigest] =
    decodeUInt256("configurationDigest").map(ApplicationConfigurationDigest(_))

  given Encoder[ApplicationValidatorSetHash] =
    Encoder.encodeString.contramap(_.toUInt256.toHexLower)
  given Decoder[ApplicationValidatorSetHash] =
    decodeUInt256("validatorSetHash").map(ApplicationValidatorSetHash(_))

  given Encoder[DependencyPlanDigest] =
    Encoder.encodeString.contramap(_.toUInt256.toHexLower)
  given Decoder[DependencyPlanDigest] =
    decodeUInt256("dependencyPlanDigest").map(DependencyPlanDigest(_))

  given Encoder[UInt256] = Encoder.encodeString.contramap(_.toHexLower)
  given Decoder[UInt256] = decodeUInt256("uint256")

  given Encoder[ApplicationEntryLifecycle] = Encoder.encodeString.contramap:
    case ApplicationEntryLifecycle.Live             => "live"
    case ApplicationEntryLifecycle.Applied          => "applied"
    case ApplicationEntryLifecycle.ExpiredUnapplied => "expiredUnapplied"
  given Decoder[ApplicationEntryLifecycle] = Decoder.decodeString.emap:
    case "live"             => Right(ApplicationEntryLifecycle.Live)
    case "applied"          => Right(ApplicationEntryLifecycle.Applied)
    case "expiredUnapplied" => Right(ApplicationEntryLifecycle.ExpiredUnapplied)
    case other => Left(s"unsupported application entry lifecycle: $other")

  given Encoder[ApplicationLock]     = deriveEncoder
  given Decoder[ApplicationLock]     = deriveDecoder
  given Encoder[ProposalReservation] = deriveEncoder
  given Decoder[ProposalReservation] = deriveDecoder
  given Encoder[AppliedExecution]    = deriveEncoder
  given Decoder[AppliedExecution]    = deriveDecoder

  private def decodeUInt256(field: String): Decoder[UInt256] =
    Decoder.decodeString.emap: value =>
      Either
        .cond(
          value.matches("[0-9a-f]{64}"),
          value,
          s"$field must be 32-byte lowercase hex",
        )
        .flatMap(UInt256.fromHex(_).left.map(_.toString))
