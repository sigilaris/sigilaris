package org.sigilaris.core.application.protocol.v2

import scodec.bits.ByteVector
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.failure.DecodeFailure

import V2Codecs.given

enum JournalOperation(val tag: Byte):
  case VoteIntent         extends JournalOperation(1.toByte)
  case Reservation        extends JournalOperation(2.toByte)
  case ApplicationPrepare extends JournalOperation(3.toByte)
  case ApplicationCommit  extends JournalOperation(4.toByte)
  case Expiry             extends JournalOperation(5.toByte)
  case ActivationPrepare  extends JournalOperation(6.toByte)
  case ActivationCommit   extends JournalOperation(7.toByte)
  case BootstrapBind      extends JournalOperation(8.toByte)
  case Fence              extends JournalOperation(9.toByte)
  case IndexRebuild       extends JournalOperation(10.toByte)
  case CertificateImport  extends JournalOperation(11.toByte)
  case BootstrapVote      extends JournalOperation(12.toByte)
  case ExactRegistration  extends JournalOperation(13.toByte)
  case ExactLifecycle     extends JournalOperation(14.toByte)
object JournalOperation:
  val all: Vector[JournalOperation] = Vector(
    VoteIntent,
    Reservation,
    ApplicationPrepare,
    ApplicationCommit,
    Expiry,
    ActivationPrepare,
    ActivationCommit,
    BootstrapBind,
    Fence,
    IndexRebuild,
    CertificateImport,
    BootstrapVote,
    ExactRegistration,
    ExactLifecycle,
  )
  given ByteEncoder[JournalOperation] = ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[JournalOperation] =
    V2Codecs.enumDecoder("journal operation", all.map(v => v.tag -> v))
  val codec: CanonicalCodec[JournalOperation] =
    CanonicalCodec.derived(_ => Right[CoreFailure, Unit](()))

enum JournalStatus(val tag: Byte):
  case Prepared  extends JournalStatus(1.toByte)
  case Committed extends JournalStatus(2.toByte)
object JournalStatus:
  given ByteEncoder[JournalStatus] = ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[JournalStatus] = V2Codecs.enumDecoder(
    "journal status",
    Vector(1.toByte -> Prepared, 2.toByte -> Committed),
  )
  val codec: CanonicalCodec[JournalStatus] =
    CanonicalCodec.derived(_ => Right[CoreFailure, Unit](()))

enum ImportedCertificate:
  case Lock(certificate: LockCertificate)
  case Effect(certificate: EffectCertificate)
object ImportedCertificate:
  given ByteEncoder[ImportedCertificate] =
    case Lock(value) =>
      ByteVector(1.toByte) ++ ByteEncoder[LockCertificate].encode(value)
    case Effect(value) =>
      ByteVector(2.toByte) ++ ByteEncoder[EffectCertificate].encode(value)
  given ByteDecoder[ImportedCertificate] = ByteDecoder[Byte].flatMap:
    case 1 => ByteDecoder[LockCertificate].map(Lock(_))
    case 2 => ByteDecoder[EffectCertificate].map(Effect(_))
    case _ =>
      _ =>
        Left[
          DecodeFailure,
          org.sigilaris.core.codec.byte.DecodeResult[ImportedCertificate],
        ](DecodeFailure("unsupported imported certificate tag"))
  val codec: CanonicalCodec[ImportedCertificate] =
    CanonicalCodec.derived(validate)
  def validate(value: ImportedCertificate): Either[CoreFailure, Unit] =
    value match
      case Lock(certificate) =>
        LockCertificate.codec.encode(certificate).map(_ => ())
      case Effect(certificate) =>
        EffectCertificate.codec.encode(certificate).map(_ => ())
  def key(value: ImportedCertificate): Either[CoreFailure, Bytes] = value match
    case Lock(certificate) =>
      LockCertificate
        .id(certificate)
        .map(id => ByteVector(1.toByte) ++ id.bytes)
    case Effect(certificate) =>
      EffectCertificate
        .id(certificate)
        .map(id => ByteVector(2.toByte) ++ id.bytes)

final case class JournalPayload(
    format: Long,
    intents: Vector[VoteIntent],
    locks: Vector[LiveLockClaim],
    consensusIntent: Option[ConsensusVoteIntent],
    claims: Vector[ReservationClaim],
    witnesses: Vector[WitnessRef],
    applicationPreparation: Option[PreparedApplication],
    applicationDecision: Option[ApplicationDecision],
    terminalResolutions: Vector[TerminalResolution],
    activationPreparation: Option[ActivationPreparation],
    activationDecision: Option[ActivationDecision],
    bootstrapStartup: Option[BootstrapStartupRecord],
    fences: Vector[SignedFencePromise],
    rebuiltIndexDigest: Option[Hash],
    importedCertificates: Vector[ImportedCertificate],
    bootstrapVoteIntent: Option[BootstrapVoteIntent],
    exactRegistration: Option[ExactRegistration],
    exactRecordUpdates: Vector[ExactRecordUpdate],
)

/** Structural validation never substitutes for trusted evidence, current store
  * reconciliation, or HotStuff/exact schema-3 verification in the runtime.
  */
@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object JournalPayload:
  given ByteEncoder[JournalPayload]         = ByteEncoder.derived
  given ByteDecoder[JournalPayload]         = ByteDecoder.derived
  val codec: CanonicalCodec[JournalPayload] = CanonicalCodec.derived(validate)
  val empty: JournalPayload                 = JournalPayload(
    2L,
    Vector.empty,
    Vector.empty,
    None,
    Vector.empty,
    Vector.empty,
    None,
    None,
    Vector.empty,
    None,
    None,
    None,
    Vector.empty,
    None,
    Vector.empty,
    None,
    None,
    Vector.empty,
  )

  def validate(value: JournalPayload): Either[CoreFailure, Unit] =
    for
      _ <- V2Validation.format(value.format, 2L, "journalPayload.format")
      _ <- V2Validation.all(value.intents.map(VoteIntent.validate))
      _ <- V2Validation.all(value.locks.map(LiveLockClaim.validate))
      _ <- V2Validation.all(
        value.consensusIntent.toList.toVector.map(ConsensusVoteIntent.validate),
      )
      _ <- V2Validation.all(value.claims.map(ReservationClaim.validate))
      _ <- V2Validation.all(value.witnesses.map(WitnessRef.validate))
      _ <- V2Validation.all(
        value.applicationPreparation.toList.toVector
          .map(PreparedApplication.validate),
      )
      _ <- V2Validation.all(
        value.applicationDecision.toList.toVector
          .map(ApplicationDecision.validate),
      )
      _ <- V2Validation.all(
        value.terminalResolutions.map(TerminalResolution.validate),
      )
      _ <- V2Validation.all(
        value.activationPreparation.toList.toVector
          .map(ActivationPreparation.validate),
      )
      _ <- V2Validation.all(
        value.activationDecision.toList.toVector
          .map(ActivationDecision.validate),
      )
      _ <- V2Validation.all(
        value.bootstrapStartup.toList.toVector
          .map(BootstrapStartupRecord.validate),
      )
      _ <- V2Validation.all(value.fences.map(SignedFencePromise.validate))
      _ <- V2Validation.all(
        value.importedCertificates.map(ImportedCertificate.validate),
      )
      _ <- V2Validation.all(
        value.bootstrapVoteIntent.toList.toVector
          .map(BootstrapVoteIntent.validate),
      )
      _ <- V2Validation.all(
        value.exactRegistration.toList.toVector.map(ExactRegistration.validate),
      )
      _ <- V2Validation.all(
        value.exactRecordUpdates.map(ExactRecordUpdate.validate),
      )
      intentKeys <- RecordValidation.traverse(value.intents)(VoteIntent.digest)
      _          <- RecordValidation.keys(intentKeys, "journal.intents")
      _          <- RecordValidation.encodedKeys(value.locks, "journal.locks")(
        LiveLockClaim.key,
      )
      ownerKeys <- RecordValidation.traverse(value.claims)(claim =>
        Owner.digest(claim.owner),
      )
      _ <- RecordValidation.keys(ownerKeys, "journal.claims")
      _ <- RecordValidation.keys(
        value.witnesses.map(_.witnessDigest),
        "journal.witnesses",
      )
      _ <- RecordValidation.encodedKeys(
        value.terminalResolutions,
        "journal.terminalResolutions",
      )(TerminalResolution.codec.encode)
      fenceKeys <- RecordValidation.traverse(value.fences)(
        SignedFencePromise.digest,
      )
      _ <- RecordValidation.keys(fenceKeys, "journal.fences")
      _ <- RecordValidation.encodedKeys(
        value.importedCertificates,
        "journal.certificates",
      )(ImportedCertificate.key)
      _ <- RecordValidation.encodedKeys(
        value.exactRecordUpdates,
        "journal.exactRecordUpdates",
      )(ExactRecordUpdate.key)
    yield ()

  def digest(value: JournalPayload): Either[CoreFailure, Hash] =
    RecordValidation.digest(
      "sigilaris.application.journal.payload.v2",
      codec,
      value,
    )

  private def populated(value: JournalPayload): Set[String] = Vector(
    "intents"                -> value.intents.nonEmpty,
    "locks"                  -> value.locks.nonEmpty,
    "consensus"              -> value.consensusIntent.nonEmpty,
    "claims"                 -> value.claims.nonEmpty,
    "witnesses"              -> value.witnesses.nonEmpty,
    "applicationPreparation" -> value.applicationPreparation.nonEmpty,
    "applicationDecision"    -> value.applicationDecision.nonEmpty,
    "terminal"               -> value.terminalResolutions.nonEmpty,
    "activationPreparation"  -> value.activationPreparation.nonEmpty,
    "activationDecision"     -> value.activationDecision.nonEmpty,
    "bootstrapStartup"       -> value.bootstrapStartup.nonEmpty,
    "fences"                 -> value.fences.nonEmpty,
    "index"                  -> value.rebuiltIndexDigest.nonEmpty,
    "certificates"           -> value.importedCertificates.nonEmpty,
    "bootstrapVote"          -> value.bootstrapVoteIntent.nonEmpty,
    "exactRegistration"      -> value.exactRegistration.nonEmpty,
    "exactUpdates"           -> value.exactRecordUpdates.nonEmpty,
  ).collect { case (field, true) => field }.toSet

  private def allowed(operation: JournalOperation): Set[String] =
    operation match
      case JournalOperation.VoteIntent =>
        Set(
          "intents",
          "locks",
          "consensus",
          "claims",
          "witnesses",
          "exactUpdates",
        )
      case JournalOperation.Reservation =>
        Set("claims", "witnesses", "exactUpdates")
      case JournalOperation.ApplicationPrepare =>
        Set("applicationPreparation", "claims", "witnesses")
      case JournalOperation.ApplicationCommit =>
        Set(
          "applicationDecision",
          "locks",
          "claims",
          "terminal",
          "exactUpdates",
        )
      case JournalOperation.Expiry =>
        Set("locks", "claims", "terminal", "exactUpdates")
      case JournalOperation.ActivationPrepare => Set("activationPreparation")
      case JournalOperation.ActivationCommit  => Set("activationDecision")
      case JournalOperation.BootstrapBind     => Set("bootstrapStartup")
      case JournalOperation.Fence             => Set("fences")
      case JournalOperation.IndexRebuild      => Set("index")
      case JournalOperation.CertificateImport =>
        Set("certificates", "locks", "exactUpdates")
      case JournalOperation.BootstrapVote =>
        Set("bootstrapVote", "bootstrapStartup")
      case JournalOperation.ExactRegistration => Set("exactRegistration")
      case JournalOperation.ExactLifecycle    => Set("exactUpdates")

  def validateOperation(
      value: JournalPayload,
      operation: JournalOperation,
  ): Either[CoreFailure, Unit] =
    for
      _ <- validate(value)
      _ <- RecordValidation.check(
        populated(value).subsetOf(allowed(operation)),
        "journal.operationFields",
      )
      _ <- operation match
        case JournalOperation.VoteIntent  => validateVote(value)
        case JournalOperation.Reservation =>
          RecordValidation
            .check(value.claims.nonEmpty, "journal.reservationClaims")
            .flatMap(_ => validateLiveReservations(value))
        case JournalOperation.ApplicationPrepare => validatePreparation(value)
        case JournalOperation.ApplicationCommit  =>
          validateApplicationCommit(value)
        case JournalOperation.Expiry =>
          RecordValidation
            .check(
              value.terminalResolutions.nonEmpty || value.exactRecordUpdates.nonEmpty,
              "journal.expiryResolution",
            )
            .flatMap(_ =>
              validateTerminal(
                value,
                ClaimLifecycle.ExpiredUnapplied,
                ResolutionKind.ExpiredUnapplied,
              ),
            )
        case JournalOperation.ActivationPrepare =>
          RecordValidation.check(
            value.activationPreparation.nonEmpty,
            "journal.activationPreparation",
          )
        case JournalOperation.ActivationCommit =>
          RecordValidation.check(
            value.activationDecision.nonEmpty,
            "journal.activationDecision",
          )
        case JournalOperation.BootstrapBind =>
          RecordValidation.check(
            value.bootstrapStartup.nonEmpty,
            "journal.bootstrapStartup",
          )
        case JournalOperation.Fence =>
          RecordValidation.check(value.fences.nonEmpty, "journal.fences")
        case JournalOperation.IndexRebuild =>
          RecordValidation.check(
            value.rebuiltIndexDigest.nonEmpty,
            "journal.rebuiltIndex",
          )
        case JournalOperation.CertificateImport => validateImport(value)
        case JournalOperation.BootstrapVote     => validateBootstrapVote(value)
        case JournalOperation.ExactRegistration =>
          RecordValidation.check(
            value.exactRegistration.nonEmpty,
            "journal.exactRegistration",
          )
        case JournalOperation.ExactLifecycle =>
          RecordValidation.check(
            value.exactRecordUpdates.nonEmpty,
            "journal.exactRecordUpdates",
          )
    yield ()

  private def validateLiveReservations(
      value: JournalPayload,
  ): Either[CoreFailure, Unit] =
    RecordValidation.check(
      value.claims.forall(
        _.lifecycle == ClaimLifecycle.Live,
      ) && value.witnesses.toSet == value.claims.map(_.witness).toSet,
      "journal.liveReservations",
    )

  private def validatePreparation(
      value: JournalPayload,
  ): Either[CoreFailure, Unit] =
    for
      prepared <- value.applicationPreparation.toRight(
        CoreFailure.at(
          FailureCode.MembershipMismatch,
          "journal.applicationPreparation",
        ),
      )
      _ <- validateLiveReservations(value)
      _ <-
        if value.claims.isEmpty then Right[CoreFailure, Unit](())
        else
          for
            owners <- RecordValidation.traverse(value.claims)(claim =>
              Owner.digest(claim.owner).map(_ -> claim),
            )
            expected = prepared.batch.entries.map(_.ownerDigest)
            _ <- RecordValidation.check(
              owners.map(_._1).toSet == expected.toSet && owners.sizeCompare(
                expected.size,
              ) == 0,
              "journal.preparationOwners",
            )
            lookup = owners.toMap
            _ <- RecordValidation.check(
              prepared.batch.entries.zipWithIndex.forall((entry, index) =>
                lookup
                  .get(entry.ownerDigest)
                  .exists(claim =>
                    claim.owner.executionId == entry.executionId && claim.owner.context == prepared.batch.context && claim.lastInclusionHeight == entry.lastInclusionHeight &&
                      claim.owner.scope.kind != ScopeKind.FastAdmission && claim.owner.scope.parentBlockId == prepared.batch.parentBlockId &&
                      claim.owner.scope.candidateHeight == prepared.batch.candidateHeight && claim.owner.scope.planRoot == prepared.batch.planRoot &&
                      claim.owner.scope.entryIndex == index.toLong,
                  ),
              ),
              "journal.preparationOwnerBindings",
            )
          yield ()
    yield ()

  private def matchingLock(
      claim: LiveLockClaim,
      subject: LockSubject,
      subjectDigest: Hash,
  ): Boolean =
    claim.context == subject.context && claim.executionId == subject.executionId && claim.subjectDigest == subjectDigest &&
      claim.inputIds == subject.inputs && claim.lastInclusionHeight == subject.lastInclusionHeight

  private def validateVote(value: JournalPayload): Either[CoreFailure, Unit] =
    (value.intents.toList, value.consensusIntent) match
      case (intent :: Nil, None) if intent.kind == VoteIntentKind.Lock =>
        for
          subject <- LockSubject.codec.decode(intent.subject)
          _       <- RecordValidation.check(
            value.claims.isEmpty && value.witnesses.isEmpty && value.locks
              .sizeCompare(1) == 0 &&
              value.locks.forall(claim =>
                claim.lifecycle == ClaimLifecycle.Live && matchingLock(
                  claim,
                  subject,
                  intent.subjectDigest,
                ),
              ),
            "journal.lockVote",
          )
        yield ()
      case (intent :: Nil, None) if intent.kind == VoteIntentKind.Effect =>
        for
          _ <- RecordValidation.check(
            value.locks.isEmpty && value.claims.sizeCompare(
              1,
            ) == 0 && value.claims.forall(claim =>
              intent.owner.contains(claim.owner) && intent.witness.contains(
                claim.witness,
              ) && intent.lastInclusionHeight == claim.lastInclusionHeight,
            ),
            "journal.effectVote",
          )
          _ <- validateLiveReservations(value)
        yield ()
      case (Nil, Some(intent)) =>
        for
          _ <- RecordValidation.check(
            value.locks.isEmpty,
            "journal.consensusLocks",
          )
          owners <- RecordValidation.traverse(value.claims)(claim =>
            Owner.digest(claim.owner).map(_ -> claim.owner),
          )
          _ <- RecordValidation.check(
            owners.map(_._1).toSet == intent.ownerDigests.toSet && owners
              .sizeCompare(intent.ownerDigests.size) == 0,
            "journal.consensusOwners",
          )
          lookup = owners.toMap
          _ <- RecordValidation.check(
            intent.ownerDigests.zipWithIndex.forall((digest, index) =>
              lookup
                .get(digest)
                .exists(owner =>
                  owner.context == intent.context && owner.scope.kind != ScopeKind.FastAdmission && owner.scope.planRoot == intent.planRoot && owner.scope.entryIndex == index.toLong,
                ),
            ),
            "journal.consensusScope",
          )
          _ <- RecordValidation.check(
            owners
              .map(_._2.scope.parentBlockId)
              .distinct
              .sizeCompare(1) <= 0 && owners
              .map(_._2.scope.candidateHeight)
              .distinct
              .sizeCompare(1) <= 0,
            "journal.consensusCandidate",
          )
          _ <- validateLiveReservations(value)
        yield ()
      case _ =>
        Left[CoreFailure, Unit](
          CoreFailure.at(
            FailureCode.MembershipMismatch,
            "journal.voteAlternative",
          ),
        )

  private def validateTerminal(
      value: JournalPayload,
      lifecycle: ClaimLifecycle,
      kind: ResolutionKind,
  ): Either[CoreFailure, Unit] =
    val embedded = (value.claims.flatMap(_.terminal) ++ value.locks.flatMap(
      _.terminal,
    )).toSet
    val resolutions = value.terminalResolutions.toSet
    for
      _ <- RecordValidation.check(
        value.claims.forall(_.lifecycle == lifecycle) && value.locks.forall(
          _.lifecycle == lifecycle,
        ) && value.terminalResolutions.forall(_.kind == kind),
        "journal.terminalKind",
      )
      _ <- RecordValidation.check(
        embedded.subsetOf(
          resolutions,
        ) && (value.exactRecordUpdates.nonEmpty || embedded == resolutions),
        "journal.terminalMembership",
      )
    yield ()

  private def validateApplicationCommit(
      value: JournalPayload,
  ): Either[CoreFailure, Unit] =
    for
      decision <- value.applicationDecision.toRight(
        CoreFailure.at(
          FailureCode.MembershipMismatch,
          "journal.applicationDecision",
        ),
      )
      _ <- validateTerminal(
        value,
        ClaimLifecycle.Applied,
        ResolutionKind.Applied,
      )
      _ <- RecordValidation.check(
        value.terminalResolutions.forall(
          _.applicationBatchDigest.contains(decision.batchDigest),
        ),
        "journal.terminalBatch",
      )
      owners <- RecordValidation.traverse(value.claims)(claim =>
        Owner.digest(claim.owner),
      )
      _ <- RecordValidation.check(
        owners.toSet == decision.terminalOwners.toSet && owners.sizeCompare(
          decision.terminalOwners.size,
        ) == 0,
        "journal.terminalOwners",
      )
    yield ()

  private def validateImport(value: JournalPayload): Either[CoreFailure, Unit] =
    val subjects = value.importedCertificates.collect {
      case ImportedCertificate.Lock(certificate) => certificate.subject
    }
    for
      _ <- RecordValidation.check(
        value.importedCertificates.nonEmpty,
        "journal.importedCertificates",
      )
      encoded <- RecordValidation.traverse(subjects)(subject =>
        LockSubject.codec
          .encode(subject)
          .map(bytes => subject -> Commitment.hash(LockSubject.Domain, bytes)),
      )
      _ <- RecordValidation.check(
        encoded.forall((subject, digest) =>
          value.locks.exists(matchingLock(_, subject, digest)),
        ) &&
          value.locks.forall(claim =>
            encoded.exists((subject, digest) =>
              matchingLock(claim, subject, digest),
            ),
          ),
        "journal.importedLocks",
      )
    yield ()

  private def validateBootstrapVote(
      value: JournalPayload,
  ): Either[CoreFailure, Unit] =
    for
      startup <- value.bootstrapStartup.toRight(
        CoreFailure.at(
          FailureCode.MembershipMismatch,
          "journal.bootstrapStartup",
        ),
      )
      intent <- value.bootstrapVoteIntent.toRight(
        CoreFailure.at(
          FailureCode.MembershipMismatch,
          "journal.bootstrapVoteIntent",
        ),
      )
      digest <- BootstrapVoteIntent.digest(intent)
      _      <- RecordValidation.check(
        startup.phase.tag >= BootstrapPhase.Signing.tag && startup.issuedVoteIntents
          .contains(digest) &&
          startup.bundleDigest == intent.bundleDigest && startup.genesisBlockId == intent.genesisBlockId &&
          startup.initializedSafetyInventory == intent.initializedSafetyInventory && startup.baselineDigest == intent.baselineDigest,
        "journal.bootstrapVoteBinding",
      )
    yield ()

final case class JournalRecord(
    schema: Long,
    sequence: Long,
    operation: JournalOperation,
    previousDigest: Hash,
    payload: Bytes,
    payloadDigest: Hash,
    status: JournalStatus,
)
@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object JournalRecord:
  given ByteEncoder[JournalRecord]         = ByteEncoder.derived
  given ByteDecoder[JournalRecord]         = ByteDecoder.derived
  val codec: CanonicalCodec[JournalRecord] = CanonicalCodec.derived(validate)
  def validate(value: JournalRecord): Either[CoreFailure, Unit] =
    for
      _       <- RecordValidation.schema(value.schema)
      _       <- RecordValidation.sequence(value.sequence, "journal.sequence")
      payload <- JournalPayload.codec.decode(value.payload)
      digest  <- JournalPayload.digest(payload)
      _       <- RecordValidation.check(
        value.payloadDigest == digest,
        "journal.payloadDigest",
      )
      _ <- JournalPayload.validateOperation(payload, value.operation)
      _ <- RecordValidation.check(
        payload.applicationPreparation.forall(
          _.preparedAtSequence == value.sequence,
        ) &&
          payload.applicationDecision.forall(
            _.decisionSequence == value.sequence,
          ) && payload.activationDecision.forall(
            _.decisionSequence == value.sequence,
          ),
        "journal.boundSequence",
      )
      _ <- RecordValidation.check(
        value.sequence != 1L || value.previousDigest.toBigIntUnsigned == 0,
        "journal.firstPreviousDigest",
      )
    yield ()
  def digest(value: JournalRecord): Either[CoreFailure, Hash] =
    RecordValidation.digest(
      "sigilaris.application.journal.record.v2",
      codec,
      value,
    )
