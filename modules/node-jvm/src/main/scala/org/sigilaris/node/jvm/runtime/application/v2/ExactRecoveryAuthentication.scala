package org.sigilaris.node.jvm.runtime.application.v2

import cats.Monad
import cats.data.EitherT
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.v2.*

/** Exact, voting and application evidence are authenticated against the same
  * original immutable safety projection. Only the final operation-scope check
  * uses a view excluding already authenticated exact records.
  */
@SuppressWarnings(Array("org.wartremover.warts.Equals"))
object ExactRecoveryAuthentication:
  def voting[F[_]: Monad](
      requests: ApplicationRequestVerifier[F],
      authentication: ExactPlanAuthentication,
      manifest: ProtocolManifest,
  ): SafetyRecoveryAuthentication[F] =
    configured(requests, authentication, manifest, None)

  def application[F[_]: Monad](
      requests: ApplicationRequestVerifier[F],
      application: ApplicationCommitVerifier[F],
      authentication: ExactPlanAuthentication,
      manifest: ProtocolManifest,
  ): SafetyRecoveryAuthentication[F] =
    configured(requests, authentication, manifest, Some(application))

  /** Reused by transition recovery without stripping original records before
    * source, ownership, application and exact outcome authentication.
    */
  private[v2] def verifyOriginal[F[_]: Monad](
      requests: ApplicationRequestVerifier[F],
      authentication: ExactPlanAuthentication,
      manifest: ProtocolManifest,
      application: Option[ApplicationCommitVerifier[F]],
      state: SafetyState,
      journal: DurableJournal[F],
  ): Result[F, Unit] =
    for
      context <- EitherT.fromEither[F](
        RuntimeCheck.core(ProtocolManifest.context(manifest)),
      )
      snapshot <- ExactJournalView.snapshot(context, state, journal)
      _        <- snapshot.records.traverse_(record =>
        EitherT
          .fromEither[F](
            RuntimeCheck.core(
              authentication.verify(record.request.signedPlan, manifest),
            ),
          )
          .flatMap(verified =>
            EitherT.fromEither[F](
              RuntimeCheck.require(
                verified.signedPlan == record.request.signedPlan && verified.signedPlanDigest == record.binding.signedPlanDigest,
                RuntimeFailureCode.JournalCorrupt,
                "recovery authentication returned another signed exact plan",
              ),
            ),
          ),
      )
      _ <- VotingRecoveryAuthentication
        .authenticated(requests)
        .verify(state, journal)
      _ <- application.traverse_(_.verifyApplicationHistory(state, journal))
      _ <- application.traverse_(_ =>
        ExactApplicationAuthentication.verify(
          state,
          journal,
          authentication,
          manifest,
        ),
      )
    yield ()

  private def configured[F[_]: Monad](
      requests: ApplicationRequestVerifier[F],
      authentication: ExactPlanAuthentication,
      manifest: ProtocolManifest,
      application: Option[ApplicationCommitVerifier[F]],
  ): SafetyRecoveryAuthentication[F] = new SafetyRecoveryAuthentication[F]:
    def verify(
        state: SafetyState,
        journal: DurableJournal[F],
    ): Result[F, Unit] =
      for
        _ <- verifyOriginal(
          requests,
          authentication,
          manifest,
          application,
          state,
          journal,
        )
        scope = state.copy(
          exactRegistrations = Map.empty[String, ExactRegistration],
          exactUpdates = Map.empty[String, ExactRecordUpdate],
          committed = state.committed.filterNot(record =>
            Set(
              JournalOperation.ExactRegistration,
              JournalOperation.ExactLifecycle,
            ).contains(record.operation),
          ),
        )
        _ <- application match
          case None =>
            SafetyRecoveryAuthentication.votesOnly[F].verify(scope, journal)
          case Some(_) =>
            EitherT.fromEither[F](
              RuntimeCheck.require(
                scope.committed.forall(record =>
                  Set(
                    JournalOperation.VoteIntent,
                    JournalOperation.Reservation,
                    JournalOperation.ApplicationPrepare,
                    JournalOperation.ApplicationCommit,
                    JournalOperation.Expiry,
                    JournalOperation.IndexRebuild,
                    JournalOperation.CertificateImport,
                  ).contains(record.operation),
                ) && scope.fences.isEmpty,
                RuntimeFailureCode.EvidenceMissing,
                "exact application recovery requires an authenticated transition handler for these operations",
              ),
            )
      yield ()
