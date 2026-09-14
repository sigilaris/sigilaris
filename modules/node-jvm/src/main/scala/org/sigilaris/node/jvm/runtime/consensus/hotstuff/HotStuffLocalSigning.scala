package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import cats.effect.kernel.Sync
import cats.syntax.all.*
import org.sigilaris.core.crypto.KeyPair
import org.sigilaris.node.jvm.runtime.application.v2.HotStuffControlledSigning

/** The ordinary runtime selects a closed controller before considering a legacy
  * key. Supported installations pass an empty raw-key map.
  */
private[hotstuff] final class HotStuffLocalSigning[F[_]: Sync] private (
    val source: Either[KeyPair, HotStuffControlledSigning[F]],
):
  private def legacy[A](
      result: => Either[HotStuffValidationFailure, A],
  ): F[Either[HotStuffPolicyViolation, A]] =
    Sync[F].delay(
      result.leftMap(error =>
        HotStuffPolicyViolation("localSigningFailed", Some(error.reason)),
      ),
    )
  private def controlled[A](
      result: org.sigilaris.node.jvm.runtime.application.v2.Result[F, A],
  ): F[Either[HotStuffPolicyViolation, A]] =
    result.value.map(
      _.leftMap(error =>
        HotStuffPolicyViolation(
          "controlledSigningRejected",
          Some(error.message),
        ),
      ),
    )
  def proposal(
      value: UnsignedProposal,
  ): F[Either[HotStuffPolicyViolation, Proposal]] = source match
    case Left(key)     => legacy(Proposal.sign(value, key))
    case Right(signer) => controlled(signer.proposal(value))
  def timeout(
      value: UnsignedTimeoutVote,
  ): F[Either[HotStuffPolicyViolation, TimeoutVote]] = source match
    case Left(key)     => legacy(TimeoutVote.sign(value, key))
    case Right(signer) => controlled(signer.timeout(value))
  def newView(
      value: UnsignedNewView,
  ): F[Either[HotStuffPolicyViolation, NewView]] = source match
    case Left(key)     => legacy(NewView.sign(value, key))
    case Right(signer) => controlled(signer.newView(value))
  def vote(
      config: HotStuffProposalValidationRuntimeConfig[F],
      voter: ValidatorId,
      proposal: Proposal,
  ): F[Either[HotStuffPolicyViolation, Vote]] = source match
    case Left(key) =>
      HotStuffApplicationVoteEmission.sign(config, voter, proposal, key)
    case Right(signer) =>
      HotStuffApplicationVoteEmission.signControlled(
        config,
        voter,
        proposal,
        signer,
      )

private[hotstuff] object HotStuffLocalSigning:
  def resolve[F[_]: Sync](
      config: HotStuffProposalValidationRuntimeConfig[F],
      keys: Map[ValidatorId, KeyPair],
      voter: ValidatorId,
  ): Either[HotStuffPolicyViolation, HotStuffLocalSigning[F]] =
    val missing =
      HotStuffPolicyViolation("localValidatorKeyUnavailable", Some(voter.value))
    config.controlledSigning match
      case Some(signer) =>
        Either.cond(
          signer.validators.contains(voter),
          new HotStuffLocalSigning(
            Right[KeyPair, HotStuffControlledSigning[F]](signer),
          ),
          missing,
        )
      case None =>
        keys
          .get(voter)
          .map(key =>
            new HotStuffLocalSigning[F](
              Left[KeyPair, HotStuffControlledSigning[F]](key),
            ),
          )
          .toRight(missing)
