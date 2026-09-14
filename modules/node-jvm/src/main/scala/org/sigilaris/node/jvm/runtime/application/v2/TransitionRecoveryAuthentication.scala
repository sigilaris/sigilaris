package org.sigilaris.node.jvm.runtime.application.v2

import cats.Monad
import cats.data.EitherT

import org.sigilaris.core.application.protocol.v2.*

/** Required original-proof authentication for one installed transition. The
  * bootstrap/activation implementation must read the entire committed history,
  * original source and namespace blobs, fixed baseline and archive evidence,
  * current enforced fences, and actual initial certificate or activation
  * decision. It must reject a record from another transition or evidence class.
  *
  * Recovery holds the application's safety gate. Read the supplied immutable
  * state and journal; do not recursively call that same JournalSafetyStore or a
  * controller gate already held by the invoking operation. A physical journal
  * digest or a caller-provided status never substitutes for these proofs.
  */
trait TransitionHistoryAuthentication[F[_]]:
  def verifyTransitionHistory(
      state: SafetyState,
      journal: DurableJournal[F],
  ): Result[F, Unit]

final case class TransitionExactRecovery(
    authentication: ExactPlanAuthentication,
    manifest: ProtocolManifest,
)

/** Compose transition proof recovery with the existing actual source and
  * application verifiers. No original operation is filtered out before its
  * ownership, outcome and historical evidence has been authenticated.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object TransitionRecoveryAuthentication:
  def application[F[_]: Monad](
      anchor: ApplicationAnchor,
      requests: ApplicationRequestVerifier[F],
      application: ApplicationCommitVerifier[F],
      history: TransitionHistoryAuthentication[F],
      exact: Option[TransitionExactRecovery],
  ): SafetyRecoveryAuthentication[F] = new SafetyRecoveryAuthentication[F]:
    def verify(
        state: SafetyState,
        journal: DurableJournal[F],
    ): Result[F, Unit] = for
      transition <- EitherT.fromEither[F](
        TransitionJournalReduction.projection(state.committed, anchor),
      )
      _ <- EitherT.fromEither[F](
        RuntimeCheck.require(
          transition.ordinaryReady && (transition.startup.nonEmpty || transition.decision.nonEmpty) &&
            transition.fences == state.fences,
          RuntimeFailureCode.RecoveryRequired,
          "the complete installed bootstrap or activation decision is required before ordinary readiness",
        ),
      )
      _ <- history.verifyTransitionHistory(state, journal)
      _ <- exact match
        case Some(profile) =>
          for
            context <- EitherT.fromEither[F](
              RuntimeCheck.core(ProtocolManifest.context(profile.manifest)),
            )
            _ <- EitherT.fromEither[F](
              RuntimeCheck.require(
                context == anchor.context,
                RuntimeFailureCode.DomainMismatch,
                "transition exact recovery differs from the installed target context",
              ),
            )
            _ <- ExactRecoveryAuthentication.verifyOriginal(
              requests,
              profile.authentication,
              profile.manifest,
              Some(application),
              state,
              journal,
            )
          yield ()
        case None =>
          for
            _ <- EitherT.fromEither[F](
              RuntimeCheck.require(
                state.exactRegistrations.isEmpty && state.exactUpdates.isEmpty &&
                  !state.committed.exists(record =>
                    record.operation == JournalOperation.ExactRegistration || record.operation == JournalOperation.ExactLifecycle,
                  ),
                RuntimeFailureCode.EvidenceMissing,
                "original exact history requires its actual configured recovery profile",
              ),
            )
            _ <- VotingRecoveryAuthentication
              .authenticated(requests)
              .verify(state, journal)
            _ <- application.verifyApplicationHistory(state, journal)
          yield ()
      _ <- EitherT.fromEither[F](
        RuntimeCheck.require(
          state.committed.forall(record =>
            (TransitionJournalReduction.operations ++ Set(
              JournalOperation.VoteIntent,
              JournalOperation.Reservation,
              JournalOperation.ApplicationPrepare,
              JournalOperation.ApplicationCommit,
              JournalOperation.Expiry,
              JournalOperation.IndexRebuild,
              JournalOperation.CertificateImport,
              JournalOperation.ExactRegistration,
              JournalOperation.ExactLifecycle,
            )).contains(record.operation),
          ),
          RuntimeFailureCode.EvidenceMissing,
          "transition recovery has no verifier for an original operation",
        ),
      )
    yield ()
