package org.sigilaris.node.jvm.runtime.application.v2

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{Proposal, ValidatorId}

/** The ordinary controlled runtime's concrete original before-vote boundary.
  * Recovery authenticates immutable original rows. New/unfinished key use also
  * checks the current persisted safe-vote watermark, without reacquiring the
  * key controller gate. Each supplied store is an actual exclusively opened
  * original journal, not a caller-constructed safety summary.
  */
object HistoricalControlledVoting:
  def authentication(
      stores: Map[ValidatorId, HistoricalConsensusSafetyStore],
  ): HistoricalControllerVoteAuthentication =
    new HistoricalControllerVoteAuthentication:
      private def store(
          voter: ValidatorId,
      ): Result[IO, HistoricalConsensusSafetyStore] = EitherT.fromOption[IO](
        stores.get(voter),
        V2RuntimeFailure.at(
          RuntimeFailureCode.ProofUnavailable,
          "original voter safety store is unavailable",
        ),
      )
      def authenticate(
          proposal: Proposal,
          voter: ValidatorId,
          key: Bytes,
      ): Result[IO, ControllerSigningMaterial] = store(voter).flatMap(
        _.authenticateProposal(proposal, Utf8(voter.value), key),
      )
      def authorize(
          proposal: Proposal,
          voter: ValidatorId,
          key: Bytes,
      ): Result[IO, Unit] = store(voter).flatMap(
        _.authorizeProposal(proposal, Utf8(voter.value), key),
      )
  def preparation(
      stores: Map[ValidatorId, HistoricalConsensusSafetyStore],
  ): HotStuffControllerVotePreparation[IO] =
    new HotStuffControllerVotePreparation[IO]:
      def prepare(voter: ValidatorId, proposal: Proposal): Result[IO, Unit] =
        for
          store <- EitherT.fromOption[IO](
            stores.get(voter),
            V2RuntimeFailure.at(
              RuntimeFailureCode.ProofUnavailable,
              "original voter safety store is unavailable",
            ),
          )
          _ <- EitherT.fromEither[IO](
            RuntimeCheck.require(
              store.profile.voter.asString === voter.value,
              RuntimeFailureCode.DomainMismatch,
              "voter and original safety owner differ",
            ),
          )
          _ <- store.beforeVote(proposal)
        yield ()
