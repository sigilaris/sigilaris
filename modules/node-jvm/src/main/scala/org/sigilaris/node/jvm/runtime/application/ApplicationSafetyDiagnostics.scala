package org.sigilaris.node.jvm.runtime.application

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}

import org.sigilaris.core.application.protocol.ApplicationEntryLifecycle

final case class ApplicationDiagnosticCount(label: String, count: Long)

object ApplicationDiagnosticCount:
  given Decoder[ApplicationDiagnosticCount] = deriveDecoder
  given Encoder[ApplicationDiagnosticCount] = deriveEncoder

final case class ApplicationSafetyDiagnostics(
    schemaVersion: Int,
    liveLockCount: Int,
    liveReservationCount: Int,
    greatestAdmittedDeadline: Option[String],
    expiryCount: Long,
    drainPhase: String,
    activeEpoch: Long,
    configurationDigest: String,
    profileCounts: Vector[ApplicationDiagnosticCount],
    modeCounts: Vector[ApplicationDiagnosticCount],
    lifecycleCounts: Vector[ApplicationDiagnosticCount],
    rejectionReasons: Vector[ApplicationDiagnosticCount],
)

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object ApplicationSafetyDiagnostics:
  val SchemaVersion: Int = 1

  def fromSnapshot(
      snapshot: ApplicationSafetySnapshot,
  ): ApplicationSafetyDiagnostics =
    val records = snapshot.exactPipelines.valuesIterator.toVector
    ApplicationSafetyDiagnostics(
      schemaVersion = SchemaVersion,
      liveLockCount = snapshot.liveLockCount,
      liveReservationCount = snapshot.liveReservationCount,
      greatestAdmittedDeadline = snapshot.drain.greatestAdmittedDeadline.map(
        _.toBigNat.toBigInt.toString,
      ),
      expiryCount = snapshot.terminal.valuesIterator
        .count(
          _.lifecycle == ApplicationEntryLifecycle.ExpiredUnapplied,
        )
        .toLong,
      drainPhase = snapshot.drain.phase.wire,
      activeEpoch = snapshot.drain.activeManifest.epoch,
      configurationDigest = snapshot.drain.activeManifest.configurationDigest,
      profileCounts = counts(records.map(_.verifiedPlan.profileId.value)),
      modeCounts = counts(records.map(_.verifiedPlan.mode.wire)),
      lifecycleCounts = counts(records.map(_.lifecycle.wire)),
      rejectionReasons = counts(
        records.flatMap(_.terminalFailure.map(_.reason)),
      ),
    )

  private def counts(
      values: Vector[String],
  ): Vector[ApplicationDiagnosticCount] =
    values
      .groupMapReduce(identity)(_ => 1L)(_ + _)
      .toVector
      .sortBy(_._1)
      .map(ApplicationDiagnosticCount.apply)

  given Decoder[ApplicationSafetyDiagnostics] = deriveDecoder
  given Encoder[ApplicationSafetyDiagnostics] = deriveEncoder
