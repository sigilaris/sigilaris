package org.sigilaris.core.application.protocol.v2

import org.sigilaris.core.application.protocol.ExecutionId
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.datatype.Utf8

import V2Codecs.given

private[v2] object RecordValidation:
  def schema(value: Long): Either[CoreFailure, Unit] =
    V2Validation.format(value, 2L, "schema")
  def nonnegative(value: Long, field: String): Either[CoreFailure, Unit] =
    V2Validation.require(value >= 0L, FailureCode.InvalidLength, field)
  def sequence(value: Long, field: String): Either[CoreFailure, Unit] =
    V2Validation.require(value > 0L, FailureCode.InvalidLength, field)
  def check(value: Boolean, field: String): Either[CoreFailure, Unit] =
    V2Validation.require(value, FailureCode.MembershipMismatch, field)
  def traverse[A, B](
      values: Vector[A],
  )(f: A => Either[CoreFailure, B]): Either[CoreFailure, Vector[B]] =
    values.foldLeft[Either[CoreFailure, Vector[B]]](
      Right[CoreFailure, Vector[B]](Vector.empty[B]),
    )((acc, value) =>
      for
        result <- acc
        next   <- f(value)
      yield result :+ next,
    )
  def keys(values: Vector[Hash], field: String): Either[CoreFailure, Unit] =
    V2Validation.sortedUnique(values.map(_.bytes.toHex), field)
  def encodedKeys[A](values: Vector[A], field: String)(
      encode: A => Either[CoreFailure, Bytes],
  ): Either[CoreFailure, Unit] =
    traverse(values)(encode).flatMap(bytes =>
      V2Validation.sortedUnique(bytes.map(_.toHex), field),
    )
  def digest[A](
      domain: String,
      codec: CanonicalCodec[A],
      value: A,
  ): Either[CoreFailure, Hash] =
    codec.encode(value).map(Commitment.hash(Utf8(domain), _))

enum VoteIntentKind(val tag: Byte):
  case Lock   extends VoteIntentKind(1.toByte)
  case Effect extends VoteIntentKind(2.toByte)
object VoteIntentKind:
  given ByteEncoder[VoteIntentKind] = ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[VoteIntentKind] = V2Codecs.enumDecoder(
    "vote intent kind",
    Vector(1.toByte -> Lock, 2.toByte -> Effect),
  )
  val codec: CanonicalCodec[VoteIntentKind] =
    CanonicalCodec.derived(_ => Right[CoreFailure, Unit](()))

enum ScopeKind(val tag: Byte):
  case FastAdmission          extends ScopeKind(1.toByte)
  case ConsensusOrdered       extends ScopeKind(2.toByte)
  case ConsensusConflictFree  extends ScopeKind(3.toByte)
  case CompatibilitySingleton extends ScopeKind(4.toByte)
object ScopeKind:
  given ByteEncoder[ScopeKind] = ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[ScopeKind] = V2Codecs.enumDecoder(
    "scope kind",
    Vector(
      FastAdmission,
      ConsensusOrdered,
      ConsensusConflictFree,
      CompatibilitySingleton,
    ).map(v => v.tag -> v),
  )
  val codec: CanonicalCodec[ScopeKind] =
    CanonicalCodec.derived(_ => Right[CoreFailure, Unit](()))

enum ClaimLifecycle(val tag: Byte):
  case Live             extends ClaimLifecycle(1.toByte)
  case Applied          extends ClaimLifecycle(2.toByte)
  case ExpiredUnapplied extends ClaimLifecycle(3.toByte)
object ClaimLifecycle:
  given ByteEncoder[ClaimLifecycle] = ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[ClaimLifecycle] = V2Codecs.enumDecoder(
    "claim lifecycle",
    Vector(Live, Applied, ExpiredUnapplied).map(v => v.tag -> v),
  )
  val codec: CanonicalCodec[ClaimLifecycle] =
    CanonicalCodec.derived(_ => Right[CoreFailure, Unit](()))

enum ResolutionKind(val tag: Byte):
  case Applied          extends ResolutionKind(1.toByte)
  case ExpiredUnapplied extends ResolutionKind(2.toByte)
object ResolutionKind:
  given ByteEncoder[ResolutionKind] = ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[ResolutionKind] = V2Codecs.enumDecoder(
    "resolution kind",
    Vector(1.toByte -> Applied, 2.toByte -> ExpiredUnapplied),
  )
  val codec: CanonicalCodec[ResolutionKind] =
    CanonicalCodec.derived(_ => Right[CoreFailure, Unit](()))

final case class Scope(
    kind: ScopeKind,
    parentBlockId: Hash,
    candidateHeight: Height,
    planRoot: Hash,
    entryIndex: Long,
    authorizationDigest: Hash,
)
@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object Scope:
  given ByteEncoder[Scope]         = ByteEncoder.derived
  given ByteDecoder[Scope]         = ByteDecoder.derived
  val codec: CanonicalCodec[Scope] = CanonicalCodec.derived(validate)
  def validate(value: Scope): Either[CoreFailure, Unit] =
    for
      _ <- RecordValidation.nonnegative(value.entryIndex, "scope.entryIndex")
      _ <-
        if value.kind == ScopeKind.FastAdmission then
          RecordValidation.check(
            value.parentBlockId.toBigIntUnsigned == 0 && value.candidateHeight.toBigNat.toBigInt == 0 &&
              value.planRoot.toBigIntUnsigned == 0 && value.entryIndex == 0L,
            "scope.fastAdmissionZeros",
          )
        else Right[CoreFailure, Unit](())
    yield ()

final case class Owner(
    context: DomainContext,
    executionId: ExecutionId,
    scope: Scope,
)
object Owner:
  given ByteEncoder[Owner]         = ByteEncoder.derived
  given ByteDecoder[Owner]         = ByteDecoder.derived
  val codec: CanonicalCodec[Owner] = CanonicalCodec.derived(validate)
  def validate(value: Owner): Either[CoreFailure, Unit] =
    DomainContext
      .validateActive(value.context)
      .flatMap(_ => Scope.validate(value.scope))
  def digest(value: Owner): Either[CoreFailure, Hash] =
    RecordValidation.digest(
      "sigilaris.application.reservation.owner.v2",
      codec,
      value,
    )

final case class WitnessRef(
    format: Long,
    witnessDigest: Hash,
    identityCount: Long,
    encodedBytes: Long,
    chunkCount: Long,
)
@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object WitnessRef:
  given ByteEncoder[WitnessRef]         = ByteEncoder.derived
  given ByteDecoder[WitnessRef]         = ByteDecoder.derived
  val codec: CanonicalCodec[WitnessRef] = CanonicalCodec.derived(validate)
  def validate(value: WitnessRef): Either[CoreFailure, Unit] =
    for
      _ <- V2Validation.format(value.format, 1L, "witnessRef.format")
      _ <- V2Validation.require(
        value.identityCount >= 0L && value.identityCount <= ProtocolLimits.V2.maxIdentities &&
          value.encodedBytes >= 9L && value.encodedBytes <= ProtocolLimits.V2.maxWitnessBytes &&
          value.chunkCount > 0L && value.chunkCount <= ProtocolLimits.V2.maxChunks,
        FailureCode.ProtocolLimitExceeded,
        "witnessRef.limits",
      )
      _ <- RecordValidation.check(
        value.chunkCount == ((value.encodedBytes - 1L) / ProtocolLimits.V2.chunkBytes) + 1L,
        "witnessRef.chunkCount",
      )
      _ <- RecordValidation.check(
        if value.identityCount == 0L then value.encodedBytes == 9L
        else value.encodedBytes >= 9L + 3L * value.identityCount,
        "witnessRef.length",
      )
    yield ()
  def fromWitness(value: ReservationWitness): Either[CoreFailure, WitnessRef] =
    for
      bytes  <- ReservationWitness.codec.encode(value)
      digest <- ReservationWitness.digest(value)
    yield WitnessRef(
      1L,
      digest,
      value.entries.size.toLong,
      bytes.size,
      ((bytes.size - 1L) / ProtocolLimits.V2.chunkBytes) + 1L,
    )
  def verify(
      value: WitnessRef,
      witness: ReservationWitness,
  ): Either[CoreFailure, Unit] =
    validate(value)
      .flatMap(_ => fromWitness(witness))
      .flatMap(expected =>
        RecordValidation.check(value == expected, "witnessRef.content"),
      )

final case class TerminalResolution(
    kind: ResolutionKind,
    evidenceDigest: Hash,
    resolvedHeight: Height,
    applicationBatchDigest: Option[Hash],
)
@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object TerminalResolution:
  given ByteEncoder[TerminalResolution]         = ByteEncoder.derived
  given ByteDecoder[TerminalResolution]         = ByteDecoder.derived
  val codec: CanonicalCodec[TerminalResolution] =
    CanonicalCodec.derived(validate)
  def validate(value: TerminalResolution): Either[CoreFailure, Unit] =
    RecordValidation.check(
      value.applicationBatchDigest.nonEmpty == (value.kind == ResolutionKind.Applied),
      "terminal.applicationBatch",
    )
  def validateClaim(
      lifecycle: ClaimLifecycle,
      terminal: Option[TerminalResolution],
      deadline: Height,
  ): Either[CoreFailure, Unit] =
    (lifecycle, terminal) match
      case (ClaimLifecycle.Live, None)           => Right[CoreFailure, Unit](())
      case (ClaimLifecycle.Applied, Some(value)) =>
        validate(value).flatMap(_ =>
          RecordValidation.check(
            value.kind == ResolutionKind.Applied && value.resolvedHeight.toBigNat.toBigInt <= deadline.toBigNat.toBigInt,
            "terminal.applied",
          ),
        )
      case (ClaimLifecycle.ExpiredUnapplied, Some(value)) =>
        validate(value).flatMap(_ =>
          RecordValidation.check(
            value.kind == ResolutionKind.ExpiredUnapplied && value.resolvedHeight.toBigNat.toBigInt > deadline.toBigNat.toBigInt,
            "terminal.expired",
          ),
        )
      case _ =>
        Left[CoreFailure, Unit](
          CoreFailure.at(FailureCode.MembershipMismatch, "terminal.lifecycle"),
        )

final case class LiveLockClaim(
    schema: Long,
    context: DomainContext,
    executionId: ExecutionId,
    subjectDigest: Hash,
    inputIds: Vector[InputId],
    lastInclusionHeight: Height,
    lifecycle: ClaimLifecycle,
    terminal: Option[TerminalResolution],
)
object LiveLockClaim:
  given ByteEncoder[LiveLockClaim]         = ByteEncoder.derived
  given ByteDecoder[LiveLockClaim]         = ByteDecoder.derived
  val codec: CanonicalCodec[LiveLockClaim] = CanonicalCodec.derived(validate)
  def validate(value: LiveLockClaim): Either[CoreFailure, Unit] =
    for
      _ <- RecordValidation.schema(value.schema)
      _ <- DomainContext.validateActive(value.context)
      _ <- RecordValidation.check(value.inputIds.nonEmpty, "lock.inputIds")
      _ <- V2Validation.all(
        value.inputIds.map(V2Validation.inputId(_, "lock.inputId")),
      )
      _ <- V2Validation.sortedUnique(
        value.inputIds.map(_.toHex),
        "lock.inputIds",
      )
      _ <- TerminalResolution.validateClaim(
        value.lifecycle,
        value.terminal,
        value.lastInclusionHeight,
      )
    yield ()
  def digest(value: LiveLockClaim): Either[CoreFailure, Hash] =
    RecordValidation.digest("sigilaris.application.live-lock.v2", codec, value)
  def key(value: LiveLockClaim): Either[CoreFailure, Bytes] =
    DomainContext.codec
      .encode(value.context)
      .map(_ ++ ByteEncoder[ExecutionId].encode(value.executionId))

final case class ReservationClaim(
    schema: Long,
    owner: Owner,
    witness: WitnessRef,
    lastInclusionHeight: Height,
    lifecycle: ClaimLifecycle,
    terminal: Option[TerminalResolution],
)
object ReservationClaim:
  given ByteEncoder[ReservationClaim]         = ByteEncoder.derived
  given ByteDecoder[ReservationClaim]         = ByteDecoder.derived
  val codec: CanonicalCodec[ReservationClaim] = CanonicalCodec.derived(validate)
  def validate(value: ReservationClaim): Either[CoreFailure, Unit] =
    for
      _ <- RecordValidation.schema(value.schema)
      _ <- Owner.validate(value.owner)
      _ <- WitnessRef.validate(value.witness)
      _ <- TerminalResolution.validateClaim(
        value.lifecycle,
        value.terminal,
        value.lastInclusionHeight,
      )
    yield ()
  def digest(value: ReservationClaim): Either[CoreFailure, Hash] =
    RecordValidation.digest(
      "sigilaris.application.reservation.claim.v2",
      codec,
      value,
    )

final case class VoteIntent(
    schema: Long,
    context: DomainContext,
    validatorId: Text,
    kind: VoteIntentKind,
    executionId: ExecutionId,
    subject: Bytes,
    subjectDigest: Hash,
    owner: Option[Owner],
    witness: Option[WitnessRef],
    lastInclusionHeight: Height,
)
@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object VoteIntent:
  given ByteEncoder[VoteIntent]         = ByteEncoder.derived
  given ByteDecoder[VoteIntent]         = ByteDecoder.derived
  val codec: CanonicalCodec[VoteIntent] = CanonicalCodec.derived(validate)
  def validate(value: VoteIntent): Either[CoreFailure, Unit] =
    for
      _ <- RecordValidation.schema(value.schema)
      _ <- DomainContext.validateActive(value.context)
      _ <- V2Validation.identifier(value.validatorId, "intent.validatorId")
      _ <- value.kind match
        case VoteIntentKind.Lock =>
          for
            subject <- LockSubject.codec.decode(value.subject)
            _       <- RecordValidation.check(
              value.owner.isEmpty && value.witness.isEmpty,
              "intent.lockReservation",
            )
            _ <- RecordValidation.check(
              subject.context == value.context && subject.executionId == value.executionId && subject.lastInclusionHeight == value.lastInclusionHeight,
              "intent.lockSubject",
            )
            _ <- RecordValidation.check(
              Commitment
                .hash(LockSubject.Domain, value.subject) == value.subjectDigest,
              "intent.subjectDigest",
            )
          yield ()
        case VoteIntentKind.Effect =>
          for
            subject <- EffectSubject.codec.decode(value.subject)
            owner   <- value.owner.toRight(
              CoreFailure.at(FailureCode.MembershipMismatch, "intent.owner"),
            )
            witness <- value.witness.toRight(
              CoreFailure.at(FailureCode.MembershipMismatch, "intent.witness"),
            )
            _ <- Owner.validate(owner)
            _ <- WitnessRef.validate(witness)
            _ <- RecordValidation.check(
              subject.context == value.context && subject.executionId == value.executionId && subject.lastInclusionHeight == value.lastInclusionHeight && owner.context == value.context && owner.executionId == value.executionId,
              "intent.effectSubject",
            )
            _ <- RecordValidation.check(
              Commitment.hash(
                EffectSubject.Domain,
                value.subject,
              ) == value.subjectDigest,
              "intent.subjectDigest",
            )
          yield ()
    yield ()
  def digest(value: VoteIntent): Either[CoreFailure, Hash] =
    RecordValidation.digest(
      "sigilaris.application.vote-intent.v2",
      codec,
      value,
    )

final case class ConsensusVoteIntent(
    schema: Long,
    context: DomainContext,
    validatorId: Text,
    unsignedVoteSignBytes: Bytes,
    proposalId: Hash,
    targetBlockId: Hash,
    planRoot: Hash,
    bodyRoot: Hash,
    validatedStateRoot: Hash,
    ownerDigests: Vector[Hash],
)
object ConsensusVoteIntent:
  given ByteEncoder[ConsensusVoteIntent]         = ByteEncoder.derived
  given ByteDecoder[ConsensusVoteIntent]         = ByteDecoder.derived
  val codec: CanonicalCodec[ConsensusVoteIntent] =
    CanonicalCodec.derived(validate)
  def validate(value: ConsensusVoteIntent): Either[CoreFailure, Unit] =
    for
      _ <- RecordValidation.schema(value.schema)
      _ <- DomainContext.validateActive(value.context)
      _ <- V2Validation.identifier(
        value.validatorId,
        "consensusIntent.validatorId",
      )
      _ <- RecordValidation.check(
        value.unsignedVoteSignBytes.nonEmpty,
        "consensusIntent.unsignedVote",
      )
      _ <- RecordValidation.check(
        value.ownerDigests.distinct.sizeCompare(value.ownerDigests.size) == 0,
        "consensusIntent.owners",
      )
    yield ()
  def digest(value: ConsensusVoteIntent): Either[CoreFailure, Hash] =
    RecordValidation.digest(
      "sigilaris.application.consensus-vote-intent.v2",
      codec,
      value,
    )

final case class IndexedOwner(
    ownerDigest: Hash,
    claimDigest: Hash,
    mode: WitnessAccess,
    lastInclusionHeight: Height,
)
object IndexedOwner:
  given ByteEncoder[IndexedOwner]         = ByteEncoder.derived
  given ByteDecoder[IndexedOwner]         = ByteDecoder.derived
  val codec: CanonicalCodec[IndexedOwner] =
    CanonicalCodec.derived(_ => Right[CoreFailure, Unit](()))

final case class ConflictIndexRow(
    schema: Long,
    identity: InputId,
    owners: Vector[IndexedOwner],
)
object ConflictIndexRow:
  given ByteEncoder[ConflictIndexRow]         = ByteEncoder.derived
  given ByteDecoder[ConflictIndexRow]         = ByteDecoder.derived
  val codec: CanonicalCodec[ConflictIndexRow] = CanonicalCodec.derived(validate)
  def validate(value: ConflictIndexRow): Either[CoreFailure, Unit] =
    for
      _ <- RecordValidation.schema(value.schema)
      _ <- V2Validation.inputId(value.identity, "index.identity")
      _ <- RecordValidation.keys(
        value.owners.map(_.ownerDigest),
        "index.owners",
      )
    yield ()
  def inventoryDigest(
      values: Vector[ConflictIndexRow],
  ): Either[CoreFailure, Hash] =
    for
      _ <- V2Validation.all(values.map(validate))
      _ <- V2Validation.sortedUnique(values.map(_.identity.toHex), "index.rows")
    yield Commitment.hash(
      Utf8("sigilaris.application.conflict-index.v2"),
      ByteEncoder[Vector[ConflictIndexRow]].encode(values),
    )

object SafetyInventory:
  def digest(committedJournalDigest: Hash, indexDigest: Hash): Hash =
    Commitment.hash(
      Utf8("sigilaris.application.safety.inventory.v2"),
      ByteEncoder[Hash].encode(committedJournalDigest) ++ ByteEncoder[Hash]
        .encode(indexDigest),
    )
