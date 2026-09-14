package org.sigilaris.node.jvm.runtime.application.v2

import cats.Monad
import cats.data.EitherT
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.{
  ExecutionId,
  InclusionHeight,
  NormalizedApplicationResult,
}
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{
  FinalizedAnchorSuggestion,
  HotStuffFinalizedAnchorVerifier,
  Proposal,
  ValidatorSetLookup,
}

/** The configured state implementation decodes the complete payload, recomputes
  * its root, and independently derives ordered results against authenticated
  * parent state. Returning caller-supplied roots or results is not
  * verification.
  */
trait ApplicationStateAuthentication[F[_]]:
  def authenticate(
      request: VerifiedConsensusProposal,
      payload: Bytes,
  ): Result[F, AuthenticatedApplicationState]

final case class AuthenticatedApplicationState(
    canonicalPayload: Bytes,
    stateRoot: Hash,
    normalizedResults: Vector[Bytes],
)
final case class ApplicationHistoryEntry(
    proposal: Proposal,
    plan: ExecutionPlan,
)

type VerifiedApplicationBatch =
  ApplicationCommitVerifier.VerifiedApplicationBatch
type VerifiedCandidateBlock = ApplicationCommitVerifier.VerifiedCandidateBlock
type VerifiedFinalityAndNonapplication =
  ApplicationCommitVerifier.VerifiedFinalityAndNonapplication

trait ApplicationCommitVerifier[F[_]] extends SafetyRecoveryAuthentication[F]:
  private[v2] def verifyApplicationHistory(
      state: SafetyState,
      journal: DurableJournal[F],
  ): Result[F, Unit]
  def verifyBatch(
      request: VerifiedConsensusProposal,
      finalized: FinalizedAnchorSuggestion,
      statePayload: Bytes,
  ): Result[F, VerifiedApplicationBatch]
  def verifyCandidate(
      finalized: FinalizedAnchorSuggestion,
  ): Result[F, VerifiedCandidateBlock]
  def verifyNonapplication(
      finalized: FinalizedAnchorSuggestion,
      history: Vector[ApplicationHistoryEntry],
      executions: Vector[ExecutionId],
  ): Result[F, VerifiedFinalityAndNonapplication]

private[v2] final case class RetainedApplicationHistory(
    proposal: Bytes,
    plan: ExecutionPlan,
)
private[v2] object RetainedApplicationHistory:
  import V2Codecs.given
  given ByteEncoder[RetainedApplicationHistory] = ByteEncoder.derived
  given ByteDecoder[RetainedApplicationHistory] = ByteDecoder.derived

private[v2] final case class ApplicationEvidence(
    format: Long,
    kind: Long,
    context: DomainContext,
    finalized: Bytes,
    history: Vector[RetainedApplicationHistory],
    executions: Vector[ExecutionId],
)
private[v2] object ApplicationEvidence:
  import V2Codecs.given
  given ByteEncoder[ApplicationEvidence] = ByteEncoder.derived
  given ByteDecoder[ApplicationEvidence] = ByteDecoder.derived
  val codec
      : CanonicalCodec[ApplicationEvidence] = CanonicalCodec.derived(value =>
    for
      _ <- DomainContext.validateActive(value.context)
      _ <- V2Validation.require(
        value.format == 2L && (value.kind == 1L || value.kind == 2L),
        FailureCode.UnsupportedFormat,
        "applicationEvidence",
      )
      _ <- V2Validation.require(
        value.kind != 1L || (value.history.isEmpty && value.executions.isEmpty),
        FailureCode.MembershipMismatch,
        "applicationEvidence.finality",
      )
      _ <- V2Validation.sortedUnique(
        value.executions.map(_.toUInt256.bytes.toHex),
        "applicationEvidence.executions",
      )
    yield (),
  )

private[v2] final case class PreparedStateInventory(
    format: Long,
    batchDigest: Hash,
    statePayloadDigest: Hash,
    evidenceDigest: Hash,
    plan: ExecutionPlan,
)
private[v2] object PreparedStateInventory:
  given ByteEncoder[PreparedStateInventory]         = ByteEncoder.derived
  given ByteDecoder[PreparedStateInventory]         = ByteDecoder.derived
  val codec: CanonicalCodec[PreparedStateInventory] =
    CanonicalCodec.derived(value =>
      V2Validation.require(
        value.format == 2L,
        FailureCode.UnsupportedFormat,
        "preparedStateInventory",
      ),
    )

/** Blob domains are independent of the physical journal checksum. Inventory
  * points to payload and evidence; neither points back to inventory or batch.
  */
private[v2] object ApplicationBlobStorage:
  val stateNamespace: Text     = Utf8("application-state")
  val inventoryNamespace: Text = Utf8("application-inventory")
  val evidenceNamespace: Text  = Utf8("application-evidence")
  def stateDigest(bytes: Bytes): Either[CoreFailure, Hash] =
    Right[CoreFailure, Hash](
      Commitment.hash(Utf8("sigilaris.application.state-payload.v2"), bytes),
    )
  def inventoryDigest(bytes: Bytes): Either[CoreFailure, Hash] =
    Right[CoreFailure, Hash](
      Commitment.hash(
        Utf8("sigilaris.application.prepared-inventory.v2"),
        bytes,
      ),
    )
  def evidenceDigest(bytes: Bytes): Either[CoreFailure, Hash] =
    Right[CoreFailure, Hash](
      Commitment.hash(Utf8("sigilaris.application.finality-evidence.v2"), bytes),
    )

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object ApplicationCommitVerifier:
  final class VerifiedCandidateBlock private[ApplicationCommitVerifier] (
      val context: DomainContext,
      val finalized: FinalizedAnchorSuggestion,
      val evidenceDigest: Hash,
      private[v2] val evidenceBytes: Bytes,
  ):
    def blockId: Hash  = finalized.anchorBlockId.toUInt256
    def height: Height = InclusionHeight(finalized.anchorHeight.toBigNat)

  final class VerifiedApplicationBatch private[ApplicationCommitVerifier] (
      val request: VerifiedConsensusProposal,
      val batch: ApplicationBatch,
      val candidate: VerifiedCandidateBlock,
      val statePayload: Bytes,
      private[v2] val inventory: PreparedStateInventory,
      private[v2] val inventoryBytes: Bytes,
      val inventoryDigest: Hash,
  )

  final class VerifiedFinalityAndNonapplication private[ApplicationCommitVerifier] (
      val candidate: VerifiedCandidateBlock,
      val executions: Vector[ExecutionId],
      val evidenceDigest: Hash,
      private[v2] val evidenceBytes: Bytes,
  )

  private def encodeFinalized(value: FinalizedAnchorSuggestion): Bytes =
    ByteEncoder[FinalizedAnchorSuggestion].encode(value)
  private def encodeProposal(value: Proposal): Bytes =
    ByteEncoder[Proposal].encode(value)
  private def decodeFinalized(
      bytes: Bytes,
  ): Either[V2RuntimeFailure, FinalizedAnchorSuggestion] =
    import org.sigilaris.node.jvm.runtime.consensus.hotstuff.given
    ByteDecoder[FinalizedAnchorSuggestion]
      .decode(bytes)
      .leftMap(error =>
        V2RuntimeFailure.at(RuntimeFailureCode.NonCanonicalEncoding, error.msg),
      )
      .flatMap(decoded =>
        RuntimeCheck
          .require(
            decoded.remainder.isEmpty && encodeFinalized(
              decoded.value,
            ) == bytes,
            RuntimeFailureCode.NonCanonicalEncoding,
            "noncanonical retained finality proof",
          )
          .as(decoded.value),
      )
  private def decodeProposal(bytes: Bytes): Either[V2RuntimeFailure, Proposal] =
    import org.sigilaris.node.jvm.runtime.consensus.hotstuff.given
    ByteDecoder[Proposal]
      .decode(bytes)
      .leftMap(error =>
        V2RuntimeFailure.at(RuntimeFailureCode.NonCanonicalEncoding, error.msg),
      )
      .flatMap(decoded =>
        RuntimeCheck
          .require(
            decoded.remainder.isEmpty && encodeProposal(decoded.value) == bytes,
            RuntimeFailureCode.NonCanonicalEncoding,
            "noncanonical retained history proposal",
          )
          .as(decoded.value),
      )

  def authenticated[F[_]: Monad](
      anchor: ApplicationAnchor,
      requests: ApplicationRequestVerifier[F],
      validators: ValidatorSetLookup[F],
      stateAuthentication: ApplicationStateAuthentication[F],
  ): ApplicationCommitVerifier[F] =
    configured(anchor, requests, validators, stateAuthentication, None)

  /** Mixed-range descendants retain their own authenticated profile. The
    * candidate itself still belongs to this installed application context; old
    * F-to-P application uses HistoricalCanonicalVerifier instead.
    */
  def historical[F[_]: Monad](
      anchor: ApplicationAnchor,
      requests: ApplicationRequestVerifier[F],
      validators: ValidatorSetLookup[F],
      stateAuthentication: ApplicationStateAuthentication[F],
      history: HistoricalConsensusAuthentication[F],
  ): ApplicationCommitVerifier[F] =
    configured(anchor, requests, validators, stateAuthentication, Some(history))

  private def configured[F[_]: Monad](
      anchor: ApplicationAnchor,
      requests: ApplicationRequestVerifier[F],
      validators: ValidatorSetLookup[F],
      stateAuthentication: ApplicationStateAuthentication[F],
      history: Option[HistoricalConsensusAuthentication[F]],
  ): ApplicationCommitVerifier[F] = new ApplicationCommitVerifier[F]:
    private def core[A](value: Either[CoreFailure, A]): Result[F, A] =
      EitherT.fromEither[F](RuntimeCheck.core(value))
    private def pure[A](value: Either[V2RuntimeFailure, A]): Result[F, A] =
      EitherT.fromEither[F](value)
    private def check(condition: Boolean, detail: String): Result[F, Unit] =
      pure(
        RuntimeCheck.require(condition, RuntimeFailureCode.ProofInvalid, detail),
      )

    def verifyCandidate(
        finalized: FinalizedAnchorSuggestion,
    ): Result[F, VerifiedCandidateBlock] =
      val proposals = Vector(
        finalized.proposal,
        finalized.finalizedProof.child,
        finalized.finalizedProof.grandchild,
      )
      val windows =
        proposals.map(_.window) ++ proposals.map(_.justify.subject.window)
      for
        _ <- core(DomainContext.validateActive(anchor.context))
        _ <- history match
          case None =>
            check(
              windows.forall(window =>
                window.chainId.value == anchor.context.chainId.asString && window.validatorSetHash.toUInt256 == anchor.context.validatorSetHash,
              ),
              "finality proof crosses the installed chain or validator domain",
            )
          case Some(selected) =>
            selected
              .finalized(finalized)
              .flatMap(proof =>
                check(
                  proof.ordered.headOption
                    .exists(_.selected.context == anchor.context),
                  "mixed-range finality candidate differs from its original installed application context",
                ),
              )
        _ <- EitherT(
          HotStuffFinalizedAnchorVerifier.verify(finalized, validators),
        ).leftMap(error =>
          V2RuntimeFailure.at(RuntimeFailureCode.ProofInvalid, error.reason),
        )
        _ <- check(
          proposals
            .zip(proposals.drop(1))
            .forall((parent, child) =>
              child.block.parent.contains(parent.targetBlockId) &&
                child.block.height.toBigNat.toBigInt == parent.block.height.toBigNat.toBigInt + 1,
            ),
          "finality proof does not contain a contiguous parent-linked block chain",
        )
        evidence = ApplicationEvidence(
          2L,
          1L,
          anchor.context,
          encodeFinalized(finalized),
          Vector.empty,
          Vector.empty,
        )
        bytes  <- core(ApplicationEvidence.codec.encode(evidence))
        digest <- core(ApplicationBlobStorage.evidenceDigest(bytes))
      yield new VerifiedCandidateBlock(anchor.context, finalized, digest, bytes)

    def verifyBatch(
        request: VerifiedConsensusProposal,
        finalized: FinalizedAnchorSuggestion,
        statePayload: Bytes,
    ): Result[F, VerifiedApplicationBatch] =
      for
        candidate <- verifyCandidate(finalized)
        _         <- check(
          request.context == anchor.context && request.proposal == finalized.proposal,
          "application request differs from the actual finalized block",
        )
        authenticated <- stateAuthentication.authenticate(request, statePayload)
        _             <- check(
          authenticated.canonicalPayload == statePayload && authenticated.stateRoot == request.validatedStateRoot && authenticated.normalizedResults == request.normalizedResults && authenticated.normalizedResults
            .sizeCompare(request.reservations.size) == 0,
          "state payload, root or complete ordered result inventory mismatch",
        )
        entries <- request.reservations
          .zip(authenticated.normalizedResults)
          .traverse((reservation, result) =>
            core(Owner.digest(reservation.owner)).map(owner =>
              AppliedEntry(
                reservation.owner.executionId,
                NormalizedApplicationResult.fromBytes(result).digest.toUInt256,
                result,
                owner,
                reservation.lastInclusionHeight,
              ),
            ),
          )
        payloadDigest <- core(ApplicationBlobStorage.stateDigest(statePayload))
        planRoot      <- core(ExecutionPlan.computeRoot(request.plan))
        batch = ApplicationBatch(
          2L,
          request.context,
          request.parentBlockId,
          request.candidateHeight,
          request.parentStateRoot,
          request.validatedStateRoot,
          planRoot.toUInt256,
          request.bodyRoot,
          entries,
          payloadDigest,
        )
        digest <- core(ApplicationBatch.digest(batch))
        inventory = PreparedStateInventory(
          2L,
          digest,
          payloadDigest,
          candidate.evidenceDigest,
          request.plan,
        )
        inventoryBytes  <- core(PreparedStateInventory.codec.encode(inventory))
        inventoryDigest <- core(
          ApplicationBlobStorage.inventoryDigest(inventoryBytes),
        )
      yield new VerifiedApplicationBatch(
        request,
        batch,
        candidate,
        statePayload,
        inventory,
        inventoryBytes,
        inventoryDigest,
      )

    def verifyNonapplication(
        finalized: FinalizedAnchorSuggestion,
        history: Vector[ApplicationHistoryEntry],
        executions: Vector[ExecutionId],
    ): Result[F, VerifiedFinalityAndNonapplication] =
      for
        candidate <- verifyCandidate(finalized)
        _         <- core(
          V2Validation.sortedUnique(
            executions.map(_.toUInt256.bytes.toHex),
            "nonapplication.executions",
          ),
        )
        _ <- check(
          executions.nonEmpty,
          "nonapplication evidence has no executions",
        )
        verified <- history.traverse(entry =>
          requests.verifyProposal(entry.proposal, entry.plan),
        )
        previousIds = anchor.blockId +: verified.map(
          _.proposal.targetBlockId.toUInt256,
        )
        previousRoots   = anchor.stateRoot +: verified.map(_.validatedStateRoot)
        previousHeights = anchor.height +: verified.map(_.candidateHeight)
        _ <- check(
          verified.zipWithIndex.forall((entry, index) =>
            entry.context == anchor.context && previousIds
              .lift(index)
              .contains(entry.parentBlockId) && previousRoots
              .lift(index)
              .contains(entry.parentStateRoot) && previousHeights
              .lift(index)
              .exists(height =>
                entry.candidateHeight.toBigNat.toBigInt == height.toBigNat.toBigInt + 1,
              ),
          ),
          "nonapplication history is incomplete or does not extend the installed anchor",
        )
        _ <- check(
          previousIds.lastOption.contains(
            candidate.blockId,
          ) && previousHeights.lastOption.contains(
            candidate.height,
          ) && previousRoots.lastOption.contains(finalized.stateRoot.toUInt256),
          "nonapplication history does not end at the finalized checkpoint",
        )
        _ <- check(
          history.lastOption.forall(_.proposal == finalized.proposal),
          "nonapplication endpoint proposal differs from the finalized checkpoint",
        )
        _ <- check(
          verified.forall(
            _.plan.waves
              .flatMap(_.entries)
              .forall(entry => !executions.contains(entry.executionId)),
          ),
          "execution occurs in the authenticated canonical history",
        )
        evidence = ApplicationEvidence(
          2L,
          2L,
          anchor.context,
          encodeFinalized(finalized),
          history.map(entry =>
            RetainedApplicationHistory(
              encodeProposal(entry.proposal),
              entry.plan,
            ),
          ),
          executions,
        )
        bytes  <- core(ApplicationEvidence.codec.encode(evidence))
        digest <- core(ApplicationBlobStorage.evidenceDigest(bytes))
      yield new VerifiedFinalityAndNonapplication(
        candidate,
        executions,
        digest,
        bytes,
      )

    private def readEvidence(
        journal: DurableJournal[F],
        digest: Hash,
    ): Result[F, ApplicationEvidence] =
      for
        bytes <- journal.readBlob(
          ApplicationBlobStorage.evidenceNamespace,
          digest,
        )
        actual <- core(ApplicationBlobStorage.evidenceDigest(bytes))
        _      <- check(
          actual == digest,
          "retained evidence content identifier mismatch",
        )
        evidence <- core(ApplicationEvidence.codec.decode(bytes))
        _        <- check(
          evidence.context == anchor.context,
          "retained evidence domain mismatch",
        )
      yield evidence

    def verify(
        state: SafetyState,
        journal: DurableJournal[F],
    ): Result[F, Unit] =
      for
        _ <- check(
          state.committed.forall(record =>
            Set(
              JournalOperation.VoteIntent,
              JournalOperation.Reservation,
              JournalOperation.ApplicationPrepare,
              JournalOperation.ApplicationCommit,
              JournalOperation.Expiry,
              JournalOperation.IndexRebuild,
              JournalOperation.CertificateImport,
            ).contains(record.operation),
          ) && state.exactRegistrations.isEmpty && state.exactUpdates.isEmpty && state.fences.isEmpty,
          "application recovery requires a configured exact/transition authenticator for these rows",
        )
        _ <- verifyApplicationHistory(state, journal)
      yield ()

    private[v2] def verifyApplicationHistory(
        state: SafetyState,
        journal: DurableJournal[F],
    ): Result[F, Unit] =
      for
        _ <- state.preparations.toVector.traverse_((digest, prepared) =>
          for
            bytes <- journal.readBlob(
              ApplicationBlobStorage.inventoryNamespace,
              prepared.preparedStateInventory,
            )
            inventoryId <- core(ApplicationBlobStorage.inventoryDigest(bytes))
            _           <- check(
              inventoryId == prepared.preparedStateInventory,
              "prepared inventory content identifier mismatch",
            )
            inventory <- core(PreparedStateInventory.codec.decode(bytes))
            _         <- check(
              inventory.batchDigest == digest && digest == prepared.batchDigest && inventory.statePayloadDigest == prepared.batch.statePayloadDigest,
              "prepared inventory references another batch or payload",
            )
            payload <- journal.readBlob(
              ApplicationBlobStorage.stateNamespace,
              inventory.statePayloadDigest,
            )
            evidence <- readEvidence(journal, inventory.evidenceDigest)
            _        <- check(
              evidence.kind == 1L,
              "prepared application requires finality evidence",
            )
            finalized <- pure(decodeFinalized(evidence.finalized))
            request   <- requests.verifyProposal(
              finalized.proposal,
              inventory.plan,
            )
            verified <- verifyBatch(request, finalized, payload)
            _        <- check(
              verified.batch == prepared.batch && verified.inventoryDigest == prepared.preparedStateInventory,
              "recovered application does not reproduce the complete prepared batch",
            )
            _ <- pure(ExactOwnershipBinding.consensus(state, verified.request))
            _ <- verified.request.reservations.traverse_(reservation =>
              for
                ownerId         <- core(Owner.digest(reservation.owner))
                expectedWitness <- core(
                  WitnessRef.fromWitness(reservation.witness),
                )
                _ <- check(
                  state.claims
                    .get(ownerId)
                    .exists(claim =>
                      claim.owner == reservation.owner && claim.witness == expectedWitness &&
                        claim.lastInclusionHeight == reservation.lastInclusionHeight,
                    ),
                  "retained application owner does not preserve the independently verified complete witness",
                )
              yield (),
            )
            _ <- state.decisions
              .get(digest)
              .traverse_(decision =>
                check(
                  decision.blockId == verified.candidate.blockId,
                  "application decision names a block outside its verified proof",
                ),
              )
          yield (),
        )
        exactExpiry <- state.committed
          .filter(_.operation == JournalOperation.Expiry)
          .traverse(record =>
            for
              payload  <- core(JournalPayload.codec.decode(record.payload))
              selected <- payload.exactRecordUpdates.traverse(update =>
                core(
                  org.sigilaris.node.txpipeline.v2.ExactPipelineRecord.codec
                    .decode(update.canonicalNextRecord),
                ).map(next =>
                  next.stages
                    .filter(
                      _.lifecycle == org.sigilaris.node.txpipeline.v2.ExactStageLifecycle.ExpiredUnapplied,
                    )
                    .flatMap(stage =>
                      payload.terminalResolutions
                        .filter(resolution =>
                          stage.terminalEvidenceDigest
                            .contains(resolution.evidenceDigest),
                        )
                        .map(_ -> stage.executionId),
                    ),
                ),
              )
            yield selected.flatten,
          )
        resolutions = (state.claims.values.toVector.flatMap(claim =>
          claim.terminal.map(_ -> claim.owner.executionId),
        ) ++ state.locks.values.toVector.flatMap(lock =>
          lock.terminal.map(_ -> lock.executionId),
        ) ++ exactExpiry.flatten).groupMap(_._1)(_._2)
        _ <- resolutions.toVector.traverse_((resolution, executions) =>
          for
            evidence  <- readEvidence(journal, resolution.evidenceDigest)
            finalized <- pure(decodeFinalized(evidence.finalized))
            _         <- resolution.kind match
              case ResolutionKind.Applied =>
                for
                  candidate <- verifyCandidate(finalized)
                  _         <- check(
                    evidence.kind == 1L && candidate.height == resolution.resolvedHeight && resolution.applicationBatchDigest
                      .exists(batch =>
                        state.decisions
                          .get(batch)
                          .exists(_.blockId == candidate.blockId),
                      ),
                    "applied terminal evidence differs from canonical application",
                  )
                yield ()
              case ResolutionKind.ExpiredUnapplied =>
                for
                  _ <- check(
                    evidence.kind == 2L,
                    "expiry requires complete nonapplication evidence",
                  )
                  history <- evidence.history.traverse(entry =>
                    pure(decodeProposal(entry.proposal))
                      .map(ApplicationHistoryEntry(_, entry.plan)),
                  )
                  verified <- verifyNonapplication(
                    finalized,
                    history,
                    evidence.executions,
                  )
                  _ <- check(
                    verified.evidenceDigest == resolution.evidenceDigest && verified.candidate.height == resolution.resolvedHeight && executions
                      .forall(verified.executions.contains),
                    "expiry evidence does not cover every resolved execution",
                  )
                yield ()
          yield (),
        )
      yield ()
