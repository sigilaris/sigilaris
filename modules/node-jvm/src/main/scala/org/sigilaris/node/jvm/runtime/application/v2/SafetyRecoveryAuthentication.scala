package org.sigilaris.node.jvm.runtime.application.v2

import cats.{Applicative, Monad}
import cats.data.EitherT
import org.sigilaris.core.application.protocol.v2.JournalOperation

/** Authenticates retained application/terminal and later exact/transition
  * evidence before the shared store can publish a projection or permit votes.
  * Implementations must not call the same store: its safety gate is held.
  */
trait SafetyRecoveryAuthentication[F[_]]:
  def verify(state: SafetyState, journal: DurableJournal[F]): Result[F, Unit]

object SafetyRecoveryAuthentication:
  def voting[F[_]: Monad](
      requests: ApplicationRequestVerifier[F],
  ): SafetyRecoveryAuthentication[F] =
    combine(VotingRecoveryAuthentication.authenticated(requests), votesOnly[F])

  def combine[F[_]: Monad](
      first: SafetyRecoveryAuthentication[F],
      second: SafetyRecoveryAuthentication[F],
  ): SafetyRecoveryAuthentication[F] =
    new SafetyRecoveryAuthentication[F]:
      def verify(
          state: SafetyState,
          journal: DurableJournal[F],
      ): Result[F, Unit] =
        first.verify(state, journal).flatMap(_ => second.verify(state, journal))

  /** Explicitly limited to vote-owned reservations. It cannot open a journal
    * containing an application, expiry, exact or transition decision.
    */
  private[v2] def votesOnly[F[_]: Applicative]
      : SafetyRecoveryAuthentication[F] =
    new SafetyRecoveryAuthentication[F]:
      def verify(
          state: SafetyState,
          journal: DurableJournal[F],
      ): Result[F, Unit] =
        val effectOwners = state.intents.valuesIterator.flatMap(_.owner).toSet
        val consensusOwners =
          state.consensusIntents.valuesIterator.flatMap(_.ownerDigests).toSet
        EitherT.fromEither[F](
          RuntimeCheck.require(
            state.committed.forall(record =>
              Set(
                JournalOperation.VoteIntent,
                JournalOperation.Reservation,
                JournalOperation.IndexRebuild,
                JournalOperation.CertificateImport,
              ).contains(record.operation),
            ) && state.claims.forall((id, claim) =>
              effectOwners.contains(claim.owner) || consensusOwners.contains(id),
            ),
            RuntimeFailureCode.EvidenceMissing,
            "journal requires authenticated application/exact/transition evidence",
          ),
        )
