package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import cats.effect.kernel.Sync
import cats.syntax.all.*

import org.sigilaris.core.application.scheduling.SchedulingClassification
import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.crypto.Hash
import org.sigilaris.node.jvm.runtime.block.{
  BlockQuery,
  BlockValidationFailure,
  BlockView,
}

/** A rejection from the HotStuff runtime, wrapping either a policy or validation failure. */
enum HotStuffRuntimeRejection:
  case Policy(rejection: HotStuffPolicyViolation)
  case Validation(rejection: HotStuffValidationFailure)

/** Companion for `HotStuffRuntimeRejection`. */
object HotStuffRuntimeRejection:
  extension (rejection: HotStuffRuntimeRejection)
    def reason: String =
      rejection match
        case HotStuffRuntimeRejection.Policy(policy) => policy.reason
        case HotStuffRuntimeRejection.Validation(validation) =>
          validation.reason

    def detail: Option[String] =
      rejection match
        case HotStuffRuntimeRejection.Policy(policy) => policy.detail
        case HotStuffRuntimeRejection.Validation(validation) =>
          validation.detail

/** The result of successfully emitting a proposal, including the conflict-free selection and block view. */
final case class HotStuffProposalEmission[TxRef, ResultRef, Event](
    selection: ConflictFreeBlockBodySelection[TxRef, ResultRef, Event],
    view: BlockView[TxRef, ResultRef, Event],
    event: org.sigilaris.node.gossip.GossipEvent[
      HotStuffGossipArtifact,
    ],
)

/** Runtime scheduling utilities for validating proposal views against block queries. */
object HotStuffRuntimeScheduling:
  private[hotstuff] def fromBlockValidationFailure(
      failure: BlockValidationFailure,
  ): HotStuffValidationFailure =
    HotStuffValidationFailure(
      reason = failure.reason,
      detail = failure.detail,
    )

  /** Validates a proposal's block view by loading it from the block query and checking scheduling compliance. */
  def validateProposalViewFromBlockQuery[F[_]
    : Sync, TxRef: ByteEncoder: Hash, ResultRef: ByteEncoder, Event: ByteEncoder](
      proposal: Proposal,
      validatorSet: ValidatorSet,
      blockQuery: BlockQuery[F, TxRef, ResultRef, Event],
  )(
      classifyTx: TxRef => SchedulingClassification,
  ): F[Either[HotStuffValidationFailure, BlockView[TxRef, ResultRef, Event]]] =
    blockQuery
      .getView(proposal.targetBlockId)
      .leftMap(fromBlockValidationFailure)
      .value
      .map:
        case Left(failure) =>
          failure.asLeft[BlockView[TxRef, ResultRef, Event]]
        case Right(None) =>
          HotStuffValidationFailure(
            reason = "proposalBlockViewUnavailable",
            detail = Some(proposal.targetBlockId.toHexLower),
          ).asLeft[BlockView[TxRef, ResultRef, Event]]
        case Right(Some(view)) =>
          HotStuffProposalViewValidator
            .validateProposalView(
              proposal = proposal,
              view = view,
              validatorSet = validatorSet,
            )(classifyTx)
            .map(_ => view)

  /** Creates a proposal validation function backed by a block query. */
  def proposalValidationFromBlockQuery[F[_]
    : Sync, TxRef: ByteEncoder: Hash, ResultRef: ByteEncoder, Event: ByteEncoder](
      validatorSet: ValidatorSet,
      blockQuery: BlockQuery[F, TxRef, ResultRef, Event],
  )(
      classifyTx: TxRef => SchedulingClassification,
  ): Proposal => F[Either[HotStuffValidationFailure, Unit]] =
    proposal =>
      validateProposalViewFromBlockQuery(
        proposal = proposal,
        validatorSet = validatorSet,
        blockQuery = blockQuery,
      )(classifyTx).map(_.void)

  /** Creates a proposal validation function that accepts all proposals without checks. */
  def allowAll[F[_]: Sync]
      : Proposal => F[Either[HotStuffValidationFailure, Unit]] =
    _ => Sync[F].pure(().asRight[HotStuffValidationFailure])
