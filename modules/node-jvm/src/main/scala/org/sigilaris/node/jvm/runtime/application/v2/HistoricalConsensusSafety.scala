package org.sigilaris.node.jvm.runtime.application.v2

import cats.Monad
import cats.data.EitherT
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.datatype.{UInt256, Utf8}
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.given

/** Explicit supported historical deployment format. This is NOT a claim that
  * unmodified M1/M2 nodes retained these facts. Such deployments remain
  * unsupported for handover without their actual original safety adapter. The
  * profile's key is exclusively held by this durable signing controller.
  */
final case class HistoricalSafetyProfile(
    format: Long,
    context: DomainContext,
    voter: Text,
    initialHighQc: QuorumCertificate,
    initialLockedQc: QuorumCertificate,
    originalDeploymentEvidenceDigest: Hash,
)
@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object HistoricalSafetyProfile:
  import V2Codecs.given
  given ByteEncoder[HistoricalSafetyProfile]         = ByteEncoder.derived
  given ByteDecoder[HistoricalSafetyProfile]         = ByteDecoder.derived
  val codec: CanonicalCodec[HistoricalSafetyProfile] =
    CanonicalCodec.derived(value =>
      for
        _ <- V2Validation.format(
          value.format,
          1L,
          "historicalSafetyProfile.format",
        )
        _ <- DomainContext.validate(value.context)
        _ <- V2Validation.identifier(
          value.voter,
          "historicalSafetyProfile.voter",
        )
        _ <- V2Validation.require(
          value.context.protocolVersion == 1L,
          FailureCode.UnsupportedTuple,
          "historicalSafetyProfile.protocol",
        )
      yield (),
    )
  def digest(value: HistoricalSafetyProfile): Either[CoreFailure, Hash] = codec
    .encode(value)
    .map(
      Commitment
        .hash(Utf8("sigilaris.hotstuff.historical-safety.profile.v1"), _),
    )

enum HistoricalSafetyOperation(val tag: Byte):
  case VoteIntent extends HistoricalSafetyOperation(1.toByte)
  case ObserveQc  extends HistoricalSafetyOperation(2.toByte)
  case Fence      extends HistoricalSafetyOperation(3.toByte)
object HistoricalSafetyOperation:
  given ByteEncoder[HistoricalSafetyOperation] =
    ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[HistoricalSafetyOperation] = V2Codecs.enumDecoder(
    "historical safety operation",
    values.toVector.map(value => value.tag -> value),
  )

/** The complete pre-sign decision is appended and forced before Vote.sign is
  * invoked. A signer failure leaves this row and its watermark intact.
  * ObserveQc retains an actual certified proposal without issuing a new vote.
  * Fence is monotonic and changes neither highQC/lockedQC nor the watermark.
  */
final case class HistoricalSafetyRecord(
    format: Long,
    sequence: Long,
    previousDigest: Hash,
    profileDigest: Hash,
    operation: HistoricalSafetyOperation,
    proposal: Option[Proposal],
    certificate: Option[QuorumCertificate],
    fenceBoundary: Option[Height],
    signBytes: Bytes,
)
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object HistoricalSafetyRecord:
  import V2Codecs.given
  given ByteEncoder[HistoricalSafetyRecord] = ByteEncoder.derived
  given ByteDecoder[HistoricalSafetyRecord] = ByteDecoder.derived
  val codec
      : CanonicalCodec[HistoricalSafetyRecord] = CanonicalCodec.derived(value =>
    for
      _ <- V2Validation.format(
        value.format,
        1L,
        "historicalSafetyRecord.format",
      )
      _ <- V2Validation.require(
        value.sequence > 0L,
        FailureCode.InvalidLength,
        "historicalSafetyRecord.sequence",
      )
      _ <- V2Validation.require(
        (value.operation match
          case HistoricalSafetyOperation.VoteIntent =>
            value.proposal.nonEmpty && value.certificate.isEmpty && value.fenceBoundary.isEmpty && value.signBytes.nonEmpty
          case HistoricalSafetyOperation.ObserveQc =>
            value.proposal.nonEmpty && value.certificate.nonEmpty && value.fenceBoundary.isEmpty && value.signBytes.isEmpty
          case HistoricalSafetyOperation.Fence =>
            value.proposal.isEmpty && value.certificate.isEmpty && value.fenceBoundary.nonEmpty && value.signBytes.isEmpty
        ),
        FailureCode.MembershipMismatch,
        "historicalSafetyRecord.closedOperation",
      )
    yield (),
  )
  def digest(value: HistoricalSafetyRecord): Either[CoreFailure, Hash] = codec
    .encode(value)
    .map(
      Commitment.hash(Utf8("sigilaris.hotstuff.historical-safety.record.v1"), _),
    )

/** This backend contains original records from BEFORE vote issuance. An
  * implementation must atomically append complete bytes and force durable data
  * and ordering; an ambiguous write must fail closed and require reopen.
  */
trait HistoricalConsensusJournal[F[_]]:
  def records: Result[F, Vector[Bytes]]
  def appendAndForce(canonicalRecord: Bytes): Result[F, Unit]

final case class HistoricalSafetyState(
    sequence: Long,
    digest: Hash,
    highQc: QuorumCertificate,
    lockedQc: QuorumCertificate,
    voterWatermark: Option[HotStuffWindow],
    fenceBoundary: Option[Height],
    voteIntents: Vector[HistoricalSafetyRecord],
    originalRecords: Vector[HistoricalSafetyRecord],
)

/** A deliberately conservative documented safe-vote profile: every new vote
  * extends the current locked block through original parent/QC evidence; window
  * watermarks increase lexicographically (height, view). It does not invent or
  * relax the safe-vote rule of an unknown deployment.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object HistoricalConsensusSafety:
  private def core[A](
      value: Either[CoreFailure, A],
  ): Either[V2RuntimeFailure, A] = value.leftMap(V2RuntimeFailure.fromCore)
  private def position(window: HotStuffWindow): (BigInt, BigInt) =
    (window.height.toBigNat.toBigInt, window.view.toBigNat.toBigInt)
  private def fail(
      condition: Boolean,
      detail: String,
  ): Either[V2RuntimeFailure, Unit] =
    RuntimeCheck.require(condition, RuntimeFailureCode.ProofInvalid, detail)

  final class VerifiedSafety private[HistoricalConsensusSafety] (
      val profile: HistoricalSafetyProfile,
      val profileDigest: Hash,
      val state: HistoricalSafetyState,
      val inventoryDigest: Hash,
      val watermarkDigest: Hash,
  ):
    def binding: ConsensusSafetyBinding = ConsensusSafetyBinding(
      profile.voter,
      HistoricalConsensusAuthentication.quorumDigest(state.highQc),
      HistoricalConsensusAuthentication.quorumDigest(state.lockedQc),
      watermarkDigest,
      inventoryDigest,
    )

  /** No runtime snapshot can substitute for these complete original rows. The
    * initial trust profile is independently retained deployment birth evidence.
    */
  def verify[F[_]: Monad](
      profile: HistoricalSafetyProfile,
      originalBytes: Vector[Bytes],
      consensus: HistoricalConsensusAuthentication[F],
      history: HistoricalConsensusRepository[F],
      validators: ValidatorSetLookup[F],
  ): Result[F, VerifiedSafety] =
    def quorum(value: QuorumCertificate): Result[F, Unit] = for
      selected <- EitherT.fromEither[F](
        consensus.profiles.at(value.subject.window),
      )
      _ <- EitherT.fromEither[F](
        fail(
          selected.context == profile.context,
          "original historical safety QC has another configuration",
        ),
      )
      set <- EitherT(validators.validatorSetFor(value.subject.window)).leftMap(
        error =>
          V2RuntimeFailure.at(RuntimeFailureCode.ProofUnavailable, error.reason),
      )
      _ <- EitherT.fromEither[F](
        HotStuffValidator
          .validateQuorumCertificate(value, set)
          .leftMap(error =>
            V2RuntimeFailure.at(RuntimeFailureCode.ProofInvalid, error.reason),
          ),
      )
    yield ()
    def extendsLock(
        proposal: Proposal,
        locked: QuorumCertificate,
    ): Result[F, Unit] =
      Monad[[A] =>> Result[F, A]].tailRecM((proposal, Set.empty[ProposalId])) {
        (current, seen) =>
          if current.targetBlockId == locked.subject.blockId then
            EitherT
              .fromEither[F](
                fail(
                  current.window == locked.subject.window,
                  "locked block has another historical window",
                ),
              )
              .as(Right[(Proposal, Set[ProposalId]), Unit](()))
          else
            for
              _ <- EitherT.fromEither[F](
                fail(
                  !seen.contains(
                    current.proposalId,
                  ) && current.block.height.toBigNat.toBigInt > locked.subject.window.height.toBigNat.toBigInt,
                  "historical safe-vote branch conflicts with the retained lock",
                ),
              )
              verified <- consensus.proposal(current)
            yield Left[(Proposal, Set[ProposalId]), Unit](
              (verified.parent, seen + current.proposalId),
            )
      }
    def step(
        state: HistoricalSafetyState,
        record: HistoricalSafetyRecord,
        profileDigest: Hash,
    ): Result[F, HistoricalSafetyState] = for
      _ <- EitherT.fromEither[F](
        fail(
          state.sequence < Long.MaxValue && record.sequence == state.sequence + 1L && record.previousDigest == state.digest && record.profileDigest == profileDigest,
          "historical safety journal sequence, previous digest or profile changed",
        ),
      )
      digest <- EitherT.fromEither[F](
        core(HistoricalSafetyRecord.digest(record)),
      )
      next <- record.operation match
        case HistoricalSafetyOperation.Fence =>
          for
            boundary <- EitherT.fromOption[F](
              record.fenceBoundary,
              V2RuntimeFailure.at(
                RuntimeFailureCode.ProofInvalid,
                "historical fence boundary missing",
              ),
            )
            _ <- EitherT.fromEither[F](
              fail(
                state.fenceBoundary.forall(
                  _.toBigNat.toBigInt >= boundary.toBigNat.toBigInt,
                ) && state.voteIntents.forall(
                  _.proposal.forall(
                    _.window.height.toBigNat.toBigInt < boundary.toBigNat.toBigInt,
                  ),
                ),
                "historical fence was loosened or placed below an already signed window",
              ),
            )
          yield state.copy(fenceBoundary = Some(boundary))
        case HistoricalSafetyOperation.ObserveQc =>
          for
            proposal <- EitherT.fromOption[F](
              record.proposal,
              V2RuntimeFailure.at(
                RuntimeFailureCode.ProofInvalid,
                "observed historical proposal missing",
              ),
            )
            certificate <- EitherT.fromOption[F](
              record.certificate,
              V2RuntimeFailure.at(
                RuntimeFailureCode.ProofInvalid,
                "observed historical certificate missing",
              ),
            )
            verified <- consensus.certified(proposal, certificate)
            _        <- EitherT.fromEither[F](
              fail(
                verified.execution.selected.context == profile.context,
                "observed QC belongs to another historical configuration",
              ),
            )
            _ <- extendsLock(proposal, state.lockedQc)
          yield state.copy(highQc =
            if position(certificate.subject.window) > position(
                state.highQc.subject.window,
              )
            then certificate
            else state.highQc,
          )
        case HistoricalSafetyOperation.VoteIntent =>
          for
            proposal <- EitherT.fromOption[F](
              record.proposal,
              V2RuntimeFailure.at(
                RuntimeFailureCode.ProofInvalid,
                "historical vote proposal missing",
              ),
            )
            verified <- consensus.proposal(proposal)
            _        <- EitherT.fromEither[F](
              fail(
                verified.selected.context == profile.context,
                "historical vote belongs to another original configuration",
              ),
            )
            _ <- EitherT.fromEither[F](
              fail(
                state.voterWatermark.forall(window =>
                  position(proposal.window) > position(window),
                ) && state.fenceBoundary.forall(
                  _.toBigNat.toBigInt > proposal.window.height.toBigNat.toBigInt,
                ),
                "historical vote violates a durable watermark or profile fence",
              ),
            )
            _ <- extendsLock(proposal, state.lockedQc)
            unsigned = UnsignedVote(
              proposal.window,
              ValidatorId.unsafe(profile.voter.asString),
              proposal.proposalId,
            )
            _ <- EitherT.fromEither[F](
              fail(
                record.signBytes == Vote.signBytes(unsigned),
                "historical vote intent changed canonical signing bytes",
              ),
            )
            _ <- EitherT.fromEither[F](
              fail(
                position(proposal.justify.subject.window) >= position(
                  state.lockedQc.subject.window,
                ),
                "historical vote would lower the retained lock",
              ),
            )
          yield state.copy(
            highQc =
              if position(proposal.justify.subject.window) > position(
                  state.highQc.subject.window,
                )
              then proposal.justify
              else state.highQc,
            lockedQc = proposal.justify,
            voterWatermark = Some(proposal.window),
            voteIntents = state.voteIntents :+ record,
          )
    yield next.copy(
      sequence = record.sequence,
      digest = digest,
      originalRecords = state.originalRecords :+ record,
    )

    for
      profileDigest <- EitherT.fromEither[F](
        core(HistoricalSafetyProfile.digest(profile)),
      )
      records <- EitherT.fromEither[F](
        originalBytes.traverse(bytes =>
          core(HistoricalSafetyRecord.codec.decode(bytes)),
        ),
      )
      _    <- quorum(profile.initialHighQc)
      _    <- quorum(profile.initialLockedQc)
      high <- history
        .retained(profile.initialHighQc.subject.proposalId)
        .flatMap(
          _.fold(history.backfill(profile.initialHighQc.subject.proposalId))(
            value => EitherT.pure[F, V2RuntimeFailure](Some(value)),
          ),
        )
      highProposal <- EitherT.fromOption[F](
        high,
        V2RuntimeFailure.at(
          RuntimeFailureCode.ProofUnavailable,
          "original initial highQC proposal missing",
        ),
      )
      _ <- EitherT.fromEither[F](
        fail(
          highProposal.proposalId == profile.initialHighQc.subject.proposalId && highProposal.window == profile.initialHighQc.subject.window && highProposal.targetBlockId == profile.initialHighQc.subject.blockId,
          "initial highQC and retained original proposal differ",
        ),
      )
      proposalSet <- EitherT(validators.validatorSetFor(highProposal.window))
        .leftMap(error =>
          V2RuntimeFailure.at(RuntimeFailureCode.ProofUnavailable, error.reason),
        )
      justifySet <- EitherT(
        validators.validatorSetFor(highProposal.justify.subject.window),
      )
        .leftMap(error =>
          V2RuntimeFailure.at(RuntimeFailureCode.ProofUnavailable, error.reason),
        )
      _ <- EitherT.fromEither[F](
        HotStuffValidator
          .validateProposal(highProposal, proposalSet, Some(justifySet))
          .leftMap(error =>
            V2RuntimeFailure.at(RuntimeFailureCode.ProofInvalid, error.reason),
          ),
      )
      _ <- extendsLock(highProposal, profile.initialLockedQc)
      initial = HistoricalSafetyState(
        0L,
        UInt256.unsafeFromBigIntUnsigned(BigInt(0)),
        profile.initialHighQc,
        profile.initialLockedQc,
        None,
        None,
        Vector.empty,
        Vector.empty,
      )
      state <- records.foldLeftM(initial)((state, record) =>
        step(state, record, profileDigest),
      )
      watermarkBytes = state.voterWatermark.fold(
        scodec.bits.ByteVector(0.toByte),
      )(window =>
        scodec.bits.ByteVector(1.toByte) ++ ByteEncoder[HotStuffWindow].encode(
          window,
        ),
      )
      watermark = Commitment.hash(
        Utf8("sigilaris.hotstuff.historical-safety.watermark.v1"),
        watermarkBytes,
      )
      inventory = Commitment.hash(
        Utf8("sigilaris.hotstuff.historical-safety.inventory.v1"),
        profileDigest.bytes ++ state.digest.bytes ++ watermark.bytes,
      )
    yield new VerifiedSafety(
      profile,
      profileDigest,
      state,
      inventory,
      watermark,
    )
