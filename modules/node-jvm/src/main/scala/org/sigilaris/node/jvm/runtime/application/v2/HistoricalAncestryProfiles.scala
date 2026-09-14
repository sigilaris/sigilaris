package org.sigilaris.node.jvm.runtime.application.v2

import cats.Monad
import cats.data.EitherT
import cats.effect.kernel.Async
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*

/** Height and original-configuration dispatch for exact ancestry. A producer
  * remains in the consumer's exact application context; M1/M2 entries never
  * become V2 producers because they share header version 2. The producer's old
  * justify QC is still authenticated in its own pinned historical range.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object HistoricalAncestryProfiles:
  def profiles[F[_]: Monad](
      schedule: AuthenticatedHistoricalProfiles,
      registry: HistoricalV2VerifierRegistry[F],
  ): AncestorProfileRepository[F] = new AncestorProfileRepository[F]:
    def historical(
        context: DomainContext,
        window: HotStuffWindow,
    ): Result[F, AncestorApplicationProfile[F]] = for
      range <- EitherT.fromEither[F](schedule.at(window))
      _     <- EitherT.fromEither[F](
        RuntimeCheck.require(
          range.context == context && range.profile.release == HistoricalApplicationRelease.ApplicationV2,
          RuntimeFailureCode.DomainMismatch,
          "exact ancestry cannot reinterpret an old release or another exact configuration",
        ),
      )
      manifest <- EitherT.fromOption[F](
        range.profile.manifest,
        V2RuntimeFailure.at(
          RuntimeFailureCode.ProofInvalid,
          "historical V2 profile has no manifest",
        ),
      )
      actual <- registry.resolve(manifest)
      _      <- EitherT.fromEither[F](
        RuntimeCheck.require(
          actual.manifest == manifest,
          RuntimeFailureCode.DomainMismatch,
          "ancestry registry changed the pinned historical manifest",
        ),
      )
    yield AncestorApplicationProfile(manifest, actual.requests)

  def validators[F[_]: Monad](
      schedule: AuthenticatedHistoricalProfiles,
      original: ValidatorSetLookup[F],
  ): ValidatorSetLookup[F] = new ValidatorSetLookup[F]:
    val trustRoot: BootstrapTrustRoot = original.trustRoot
    def validatorSetFor(
        window: HotStuffWindow,
    ): F[Either[HotStuffValidationFailure, ValidatorSet]] =
      schedule.at(window) match
        case Left(error) =>
          Monad[F].pure(
            Left(
              HotStuffValidationFailure(
                "historicalApplicationProfileUnavailable",
                Some(error.message),
              ),
            ),
          )
        case Right(range) =>
          original
            .validatorSetFor(window)
            .map(_.flatMap { set =>
              Either.cond(
                set.hash.toUInt256 == range.context.validatorSetHash,
                set,
                HotStuffValidationFailure(
                  "historicalApplicationValidatorSetMismatch",
                  None,
                ),
              )
            })

  def lookup[F[_]: Async](
      installed: DomainContext,
      history: AncestorHistoryRepository[F],
      schedule: AuthenticatedHistoricalProfiles,
      registry: HistoricalV2VerifierRegistry[F],
      originalValidators: ValidatorSetLookup[F],
      approvedParents: ApprovedAncestorParentSource[F],
      capacity: AncestorLookupCapacity,
  ): CanonicalAncestorLookup[F] = CanonicalAncestorLookup.authenticated(
    installed,
    history,
    profiles(schedule, registry),
    validators(schedule, originalValidators),
    approvedParents,
    capacity,
  )
