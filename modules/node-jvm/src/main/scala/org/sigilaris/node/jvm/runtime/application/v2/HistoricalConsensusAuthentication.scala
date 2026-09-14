package org.sigilaris.node.jvm.runtime.application.v2

import cats.Monad
import cats.data.EitherT
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.ExecutionPlanRoot
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{
  FinalizedAnchorSuggestion,
  HotStuffFinalizedAnchorVerifier,
  HotStuffValidator,
  HotStuffValidationFailure,
  Proposal,
  ProposalId,
  QuorumCertificate,
  ValidatorSetLookup,
  ValidatorMember,
}

/** Original wrappers are addressed by ProposalId. A BlockId is insufficient:
  * two valid wrappers for one header can carry different justify signer sets.
  */
trait HistoricalConsensusRepository[F[_]]:
  def retained(proposalId: ProposalId): Result[F, Option[Proposal]]
  def backfill(proposalId: ProposalId): Result[F, Option[Proposal]]

/** Recomputed facts from complete original state/source/plan/result evidence.
  * These values are data, not capabilities. Implementations must execute the
  * interpreter selected by release provenance; the M2 parser/duplicate-input
  * validator must not be substituted for the distinct M1 language. For a V2
  * range, use its registered ApplicationRequestVerifier and full state payload
  * authentication. A callback that returns the proposal's requested roots is
  * not an implementation of this contract.
  */
final case class HistoricalReplayResult(
    priorStateRoot: Hash,
    nextStateRoot: Hash,
    bodyRoot: Hash,
    executionPlanRoot: Option[ExecutionPlanRoot],
    resultInventoryDigest: Hash,
    replayEvidenceDigest: Hash,
    material: HistoricalExecutionMaterial,
)

/** Verifies actual signatures, exact parent wrapper/QC membership, contiguous
  * heights, independently pinned historical profile and the full application
  * replay. No caller-provided Boolean can manufacture the returned capability.
  * Cryptographic validation alone is insufficient for a retained old suffix.
  */
final class HistoricalConsensusAuthentication[F[_]] private (
    val profiles: AuthenticatedHistoricalProfiles,
    run: Proposal => Result[
      F,
      HistoricalConsensusAuthentication.VerifiedProposal,
    ],
    certify: (Proposal, QuorumCertificate) => Result[
      F,
      HistoricalConsensusAuthentication.VerifiedCertifiedProposal,
    ],
    finality: FinalizedAnchorSuggestion => Result[
      F,
      HistoricalConsensusAuthentication.VerifiedFinality,
    ],
    pastFinality: (Proposal, FinalizedAnchorSuggestion, Long) => Result[F, Unit],
):
  def proposal(
      value: Proposal,
  ): Result[F, HistoricalConsensusAuthentication.VerifiedProposal] = run(value)
  def certified(
      value: Proposal,
      certificate: QuorumCertificate,
  ): Result[F, HistoricalConsensusAuthentication.VerifiedCertifiedProposal] =
    certify(value, certificate)
  def finalized(
      value: FinalizedAnchorSuggestion,
  ): Result[F, HistoricalConsensusAuthentication.VerifiedFinality] = finality(
    value,
  )

  /** Past-only ancestry verification. Genesis has no invented parent; its
    * actual state is authenticated by the first child's original replay and the
    * complete branch descending from the installed canonical source tip.
    */
  def verifyPastFinality(
      installedTip: Proposal,
      value: FinalizedAnchorSuggestion,
      maximumBlocks: Long,
  ): Result[F, Unit] = pastFinality(installedTip, value, maximumBlocks)

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object HistoricalConsensusAuthentication:
  /** Pure successful signature validation only. Keys contain complete immutable
    * artifacts and complete member/key vectors. Profile lookups, original data,
    * application execution and live authority are deliberately outside this
    * cache. Saturation merely recomputes verification; it cannot reject a
    * protocol input.
    */
  private final class PositiveCryptographicCache[K <: AnyRef]:
    private val values = new java.util.HashSet[K]()
    def verify(key: K)(
        run: => Either[HotStuffValidationFailure, Unit],
    ): Either[HotStuffValidationFailure, Unit] =
      if values.synchronized(values.contains(key)) then Right(())
      else
        run.map { _ =>
          values.synchronized {
            if values.size() < 4096 then
              val _ = values.add(key)
            ()
          }
        }
  final class VerifiedProposal private[HistoricalConsensusAuthentication] (
      val selected: HistoricalProfileRange,
      val proposal: Proposal,
      val parent: Proposal,
      val replay: HistoricalReplayResult,
  )
  final class VerifiedCertifiedProposal private[HistoricalConsensusAuthentication] (
      val execution: VerifiedProposal,
      val certificate: QuorumCertificate,
      val proposalDigest: Hash,
      val quorumDigest: Hash,
  )
  final class VerifiedFinality private[HistoricalConsensusAuthentication] (
      val finalized: FinalizedAnchorSuggestion,
      val ordered: Vector[VerifiedProposal],
  )

  def proposalDigest(proposal: Proposal): Hash =
    Commitment.hash(
      Utf8("sigilaris.application.historical-proposal.evidence.v1"),
      ByteEncoder[Proposal].encode(proposal),
    )
  def quorumDigest(certificate: QuorumCertificate): Hash =
    Commitment.hash(
      Utf8("sigilaris.application.historical-quorum.evidence.v1"),
      ByteEncoder[QuorumCertificate].encode(certificate),
    )

  def authenticated[F[_]: Monad](
      profiles: AuthenticatedHistoricalProfiles,
      validators: ValidatorSetLookup[F],
      history: HistoricalConsensusRepository[F],
      application: HistoricalApplicationReplay[F],
  ): HistoricalConsensusAuthentication[F] =
    val quorumCrypto = new PositiveCryptographicCache[
      (QuorumCertificate, Vector[ValidatorMember]),
    ]
    val proposalCrypto = new PositiveCryptographicCache[
      (Proposal, Vector[ValidatorMember], Vector[ValidatorMember]),
    ]
    def check(condition: Boolean, detail: String): Result[F, Unit] =
      EitherT.fromEither[F](
        RuntimeCheck.require(condition, RuntimeFailureCode.ProofInvalid, detail),
      )
    def validation[A](
        value: Either[
          org.sigilaris.node.jvm.runtime.consensus.hotstuff.HotStuffValidationFailure,
          A,
        ],
    ): Result[F, A] =
      EitherT.fromEither[F](
        value.leftMap(error =>
          V2RuntimeFailure.at(RuntimeFailureCode.ProofInvalid, error.reason),
        ),
      )
    def selected(proposal: Proposal): Result[F, HistoricalProfileRange] =
      EitherT.fromEither[F](profiles.proposal(proposal))
    def quorum(certificate: QuorumCertificate): Result[F, Unit] = for
      _   <- EitherT.fromEither[F](profiles.at(certificate.subject.window)).void
      set <- EitherT(validators.validatorSetFor(certificate.subject.window))
        .leftMap(error =>
          V2RuntimeFailure.at(RuntimeFailureCode.ProofUnavailable, error.reason),
        )
      _ <- validation(
        quorumCrypto.verify((certificate, set.members))(
          HotStuffValidator.validateQuorumCertificate(certificate, set),
        ),
      )
    yield ()
    def cryptographic(proposal: Proposal): Result[F, Unit] = for
      _   <- selected(proposal)
      set <- EitherT(validators.validatorSetFor(proposal.window))
        .leftMap(error =>
          V2RuntimeFailure.at(RuntimeFailureCode.ProofUnavailable, error.reason),
        )
      parentSet <- EitherT(
        validators.validatorSetFor(proposal.justify.subject.window),
      )
        .leftMap(error =>
          V2RuntimeFailure.at(RuntimeFailureCode.ProofUnavailable, error.reason),
        )
      _ <- quorum(proposal.justify)
      _ <- validation(
        proposalCrypto.verify((proposal, set.members, parentSet.members))(
          HotStuffValidator.validateProposal(proposal, set, Some(parentSet)),
        ),
      )
    yield ()
    def parent(proposal: Proposal): Result[F, Proposal] = for
      retained <- history.retained(proposal.justify.subject.proposalId)
      resolved <- retained.fold(
        history.backfill(proposal.justify.subject.proposalId),
      )(value => EitherT.pure[F, V2RuntimeFailure](Some(value)))
      value <- EitherT.fromOption[F](
        resolved,
        V2RuntimeFailure.at(
          RuntimeFailureCode.ProofUnavailable,
          "complete original historical parent proposal is missing",
        ),
      )
      _ <- cryptographic(value)
      _ <- check(
        value.proposalId == proposal.justify.subject.proposalId && value.window == proposal.justify.subject.window &&
          value.targetBlockId == proposal.justify.subject.blockId && proposal.block.parent
            .contains(value.targetBlockId) &&
          proposal.block.height.toBigNat.toBigInt == value.block.height.toBigNat.toBigInt + 1 &&
          proposal.window.chainId == value.window.chainId,
        "historical proposal does not extend its actual certified parent wrapper and height",
      )
    yield value
    def verify(value: Proposal): Result[F, VerifiedProposal] = for
      profile  <- selected(value)
      _        <- cryptographic(value)
      previous <- parent(value)
      replay   <- application.replay(profile, value, previous)
      _        <- check(
        replay.priorStateRoot == previous.block.stateRoot.toUInt256 && replay.nextStateRoot == value.block.stateRoot.toUInt256 &&
          replay.bodyRoot == value.block.bodyRoot.toUInt256 && replay.executionPlanRoot == value.block.executionPlanRoot,
        "historical replay differs from original parent state, result/body root or selected plan commitment",
      )
    yield new VerifiedProposal(profile, value, previous, replay)
    def certify(
        value: Proposal,
        certificate: QuorumCertificate,
    ): Result[F, VerifiedCertifiedProposal] = for
      verified <- verify(value)
      _        <- quorum(certificate)
      _        <- check(
        certificate.subject.window == value.window && certificate.subject.proposalId == value.proposalId && certificate.subject.blockId == value.targetBlockId,
        "historical QC certifies another complete proposal",
      )
    yield new VerifiedCertifiedProposal(
      verified,
      certificate,
      proposalDigest(value),
      quorumDigest(certificate),
    )
    def finalized(
        value: FinalizedAnchorSuggestion,
    ): Result[F, VerifiedFinality] = for
      _ <- EitherT(HotStuffFinalizedAnchorVerifier.verify(value, validators))
        .leftMap(error =>
          V2RuntimeFailure.at(RuntimeFailureCode.ProofInvalid, error.reason),
        )
      ordered <- Vector(
        value.proposal,
        value.finalizedProof.child,
        value.finalizedProof.grandchild,
      ).traverse(verify)
      _ <- check(
        ordered
          .zip(ordered.drop(1))
          .forall((parent, child) => child.parent == parent.proposal),
        "mixed-range finality has a parent-wrapper gap",
      )
    yield new VerifiedFinality(value, ordered)
    def past(
        installedTip: Proposal,
        value: FinalizedAnchorSuggestion,
        maximumBlocks: Long,
    ): Result[F, Unit] = for
      _ <- EitherT(HotStuffFinalizedAnchorVerifier.verify(value, validators))
        .leftMap(error =>
          V2RuntimeFailure.at(RuntimeFailureCode.ProofInvalid, error.reason),
        )
      _ <- cryptographic(value.proposal)
      _ <- check(
        value.proposal.block.height.toBigNat.toBigInt > 0 || value.proposal.block.parent.isEmpty,
        "historical genesis cannot have an invented parent",
      )
      children <- Vector(
        value.finalizedProof.child,
        value.finalizedProof.grandchild,
      ).traverse(verify)
      _ <- check(
        children.headOption.exists(_.parent == value.proposal) && children
          .zip(children.drop(1))
          .forall((left, right) => right.parent == left.proposal),
        "past finality changed its actual original parent wrappers",
      )
      _ <- Monad[[A] =>> Result[F, A]]
        .tailRecM((installedTip, Set.empty[ProposalId])) { (current, seen) =>
          if current.block.height == value.proposal.block.height then
            check(
              current.block == value.proposal.block && current.targetBlockId == value.proposal.targetBlockId && current.window == value.proposal.window,
              "past finalized target is not an ancestor of installed canonical F",
            ).as(Right[(Proposal, Set[ProposalId]), Unit](()))
          else
            for
              _ <- check(
                current.block.height.toBigNat.toBigInt > value.proposal.block.height.toBigNat.toBigInt && !seen
                  .contains(current.proposalId),
                "past finality ancestry has a gap, fork or cycle",
              )
              _ <- EitherT.fromEither[F](
                RuntimeCheck.require(
                  seen.size.toLong < maximumBlocks,
                  RuntimeFailureCode.CapacityUnavailable,
                  "past finality ancestry local capacity exhausted",
                ),
              )
              checked <- verify(current)
            yield Left[(Proposal, Set[ProposalId]), Unit](
              (checked.parent, seen + current.proposalId),
            )
        }
    yield ()
    new HistoricalConsensusAuthentication(
      profiles,
      verify,
      certify,
      finalized,
      past,
    )
