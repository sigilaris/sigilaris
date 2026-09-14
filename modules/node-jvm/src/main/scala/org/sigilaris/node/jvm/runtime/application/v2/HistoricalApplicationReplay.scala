package org.sigilaris.node.jvm.runtime.application.v2

import cats.Monad
import cats.data.EitherT
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.Proposal

trait HistoricalApplicationReplay[F[_]]:
  def replay(
      selected: HistoricalProfileRange,
      proposal: Proposal,
      parent: Proposal,
  ): Result[F, HistoricalReplayResult]

final case class HistoricalV2ExecutionMaterial(
    plan: ExecutionPlan,
    statePayload: Bytes,
    originalSourceAndStateProof: Bytes,
)
trait HistoricalV2ExecutionRepository[F[_]]:
  def material(proposal: Proposal): Result[F, HistoricalV2ExecutionMaterial]
  def verifyOriginalProof(
      request: VerifiedConsensusProposal,
      originalProof: Bytes,
  ): Result[F, Unit]
final case class HistoricalV2Verifiers[F[_]](
    manifest: ProtocolManifest,
    requests: ApplicationRequestVerifier[F],
    state: ApplicationStateAuthentication[F],
)
trait HistoricalV2VerifierRegistry[F[_]]:
  def resolve(manifest: ProtocolManifest): Result[F, HistoricalV2Verifiers[F]]

/** The application-specific historical interpreter is mandatory. M1 and M2
  * remain different selections even where their wire and results coincide.
  * Missing old source/state material must return ProofUnavailable, not an empty
  * identity replay. A deployment requiring an unavailable historical parser or
  * verifier cannot be handed over through the V2 fallback.
  */
trait LegacyHistoricalApplicationReplay[F[_]]:
  def legacyM1(
      range: HistoricalProfileRange,
      proposal: Proposal,
      parent: Proposal,
  ): Result[F, HistoricalReplayResult]
  def legacyM2(
      range: HistoricalProfileRange,
      proposal: Proposal,
      parent: Proposal,
  ): Result[F, HistoricalReplayResult]

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object HistoricalApplicationReplay:
  def resultsDigest(results: Vector[Bytes]): Hash =
    import V2Codecs.given
    Commitment.hash(
      Utf8("sigilaris.application.historical-result.inventory.v1"),
      ByteEncoder[Vector[Bytes]].encode(results),
    )

  /** V2 replay always uses the real independent request and complete state
    * verifiers. The registry selects original historical manifest instances; it
    * is not a mutable current-profile shortcut.
    */
  def authenticated[F[_]: Monad](
      original: HistoricalV2ExecutionRepository[F],
      registry: HistoricalV2VerifierRegistry[F],
      legacy: LegacyHistoricalApplicationReplay[F],
  ): HistoricalApplicationReplay[F] = new HistoricalApplicationReplay[F]:
    def replay(
        range: HistoricalProfileRange,
        proposal: Proposal,
        parent: Proposal,
    ): Result[F, HistoricalReplayResult] =
      range.profile.release match
        case HistoricalApplicationRelease.LegacyM1 =>
          legacy.legacyM1(range, proposal, parent)
        case HistoricalApplicationRelease.LegacyM2 =>
          legacy.legacyM2(range, proposal, parent)
        case HistoricalApplicationRelease.ApplicationV2 =>
          for
            manifest <- EitherT.fromOption[F](
              range.profile.manifest,
              V2RuntimeFailure.at(
                RuntimeFailureCode.ProofInvalid,
                "historical V2 manifest missing",
              ),
            )
            verifiers <- registry.resolve(manifest)
            _         <- EitherT.fromEither[F](
              RuntimeCheck.require(
                verifiers.manifest == manifest,
                RuntimeFailureCode.DomainMismatch,
                "historical registry returned a different manifest",
              ),
            )
            material <- original.material(proposal)
            request  <- verifiers.requests.verifyProposal(
              proposal,
              material.plan,
            )
            _ <- EitherT.fromEither[F](
              RuntimeCheck.require(
                request.proposal == proposal && request.plan == material.plan && request.context == range.context &&
                  request.parentBlockId == parent.targetBlockId.toUInt256 && request.parentStateRoot == parent.block.stateRoot.toUInt256,
                RuntimeFailureCode.DomainMismatch,
                "historical V2 request differs from its source, context or actual parent",
              ),
            )
            _ <- original.verifyOriginalProof(
              request,
              material.originalSourceAndStateProof,
            )
            state <- verifiers.state.authenticate(
              request,
              material.statePayload,
            )
            _ <- EitherT.fromEither[F](
              RuntimeCheck.require(
                state.canonicalPayload == material.statePayload && state.stateRoot == request.validatedStateRoot && state.normalizedResults == request.normalizedResults,
                RuntimeFailureCode.ProofInvalid,
                "historical V2 state data or ordered results differ from independent execution",
              ),
            )
            planRoot <- EitherT.fromEither[F](
              ExecutionPlan
                .computeRoot(material.plan)
                .leftMap(V2RuntimeFailure.fromCore),
            )
            stateDigest <- EitherT.fromEither[F](
              ApplicationBlobStorage
                .stateDigest(state.canonicalPayload)
                .leftMap(V2RuntimeFailure.fromCore),
            )
            resultDigest = resultsDigest(state.normalizedResults)
            replayDigest = Commitment.hash(
              Utf8("sigilaris.application.historical-replay.evidence.v1"),
              HistoricalConsensusAuthentication
                .proposalDigest(proposal)
                .bytes ++ stateDigest.bytes ++ resultDigest.bytes ++ planRoot.toUInt256.bytes,
            )
          yield HistoricalReplayResult(
            request.parentStateRoot,
            state.stateRoot,
            request.bodyRoot,
            Some(planRoot),
            resultDigest,
            replayDigest,
            HistoricalExecutionMaterial(
              ByteEncoder[ExecutionPlan].encode(material.plan),
              material.originalSourceAndStateProof,
              state.canonicalPayload,
              state.normalizedResults,
            ),
          )
