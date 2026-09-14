package org.sigilaris.node.jvm.runtime.application.v2

import cats.data.EitherT
import cats.effect.kernel.Async
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.ExecutionId
import org.sigilaris.core.application.protocol.v2.*

trait RecoverableApplicationStore[F[_]]:
  def prepare(batch: VerifiedApplicationBatch): Result[F, PreparedApplication]
  def commit(
      prepared: PreparedApplication,
      block: VerifiedCandidateBlock,
  ): Result[F, ApplicationDecision]
  def recover: Result[F, ApplicationRecovery]
  def applied(
      context: DomainContext,
      execution: ExecutionId,
  ): Result[F, Option[AppliedIndexRecord]]
  def expire(
      proof: VerifiedFinalityAndNonapplication,
  ): Result[F, Vector[TerminalResolution]]

  /** The application root and bytes come exclusively from one committed
    * decision. Inactive preparations are never canonical reads.
    */
  def canonicalPayload: Result[F, Option[(ApplicationDecision, Bytes)]]

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object RecoverableApplicationStore:
  def journaled[F[_]: Async](
      safety: JournalSafetyStore[F],
  ): RecoverableApplicationStore[F] = new RecoverableApplicationStore[F]:
    private def pure[A](value: Either[V2RuntimeFailure, A]): Result[F, A] =
      EitherT.fromEither[F](value)
    private def core[A](value: Either[CoreFailure, A]): Result[F, A] = pure(
      RuntimeCheck.core(value),
    )
    private def check(condition: Boolean, detail: String): Result[F, Unit] =
      pure(
        RuntimeCheck.require(
          condition,
          RuntimeFailureCode.InvalidRequest,
          detail,
        ),
      )
    private def requireDomain(context: DomainContext): Result[F, Unit] = pure(
      RuntimeCheck.require(
        context == safety.context,
        RuntimeFailureCode.DomainMismatch,
        "application request differs from installed domain",
      ),
    )

    private def currentParent(
        current: SafetyState,
        batch: ApplicationBatch,
    ): Result[F, Unit] =
      for
        prior <- current.canonical.traverse(decision =>
          EitherT.fromOption[F](
            current.preparations.get(decision.batchDigest),
            V2RuntimeFailure.at(
              RuntimeFailureCode.JournalCorrupt,
              "canonical decision has no preparation",
            ),
          ),
        )
        parent = current.canonical.fold(safety.anchor.blockId)(_.blockId)
        root   = prior.fold(safety.anchor.stateRoot)(_.batch.nextStateRoot)
        height = prior.fold(safety.anchor.height)(_.batch.candidateHeight)
        _ <- check(
          batch.parentBlockId == parent && batch.priorStateRoot == root && batch.candidateHeight.toBigNat.toBigInt == height.toBigNat.toBigInt + 1,
          "application batch does not extend the current canonical parent/root/height",
        )
      yield ()

    def prepare(
        verified: VerifiedApplicationBatch,
    ): Result[F, PreparedApplication] =
      verified.request.lockCertificates
        .filter(_.request.exactBinding.nonEmpty)
        .traverse_(safety.importLock) *>
        verified.request.effectCertificates
          .filter(_.request.exactBinding.nonEmpty)
          .traverse_(safety.importEffect) *>
        safety.transaction(current =>
          for
            _ <- requireDomain(verified.batch.context)
            _ <- pure(
              ExactOwnershipBinding.consensus(current, verified.request),
            )
            digest   <- core(ApplicationBatch.digest(verified.batch))
            prepared <- current.preparations.get(digest) match
              case Some(existing) =>
                check(
                  existing.batch == verified.batch && existing.preparedStateInventory == verified.inventoryDigest,
                  "application retry changed its immutable preparation",
                ).as(existing)
              case None =>
                for
                  _ <- currentParent(current, verified.batch)
                  _ <- check(
                    current.sequence < Long.MaxValue,
                    "application preparation sequence exhausted",
                  )
                  _ <- check(
                    !verified.batch.entries.exists(entry =>
                      current.appliedEntries.contains(entry.executionId),
                    ),
                    "application preparation includes an already applied execution",
                  )
                  _ <- safety.retainBlob(
                    ApplicationBlobStorage.stateNamespace,
                    verified.batch.statePayloadDigest,
                    verified.statePayload,
                  )
                  _ <- safety.retainBlob(
                    ApplicationBlobStorage.evidenceNamespace,
                    verified.candidate.evidenceDigest,
                    verified.candidate.evidenceBytes,
                  )
                  _ <- safety.retainBlob(
                    ApplicationBlobStorage.inventoryNamespace,
                    verified.inventoryDigest,
                    verified.inventoryBytes,
                  )
                  preparation = PreparedApplication(
                    2L,
                    digest,
                    verified.batch,
                    verified.inventoryDigest,
                    current.sequence + 1L,
                  )
                  _ <- safety.prepareApplication(current, preparation, verified)
                yield preparation
          yield prepared,
        )

    def commit(
        prepared: PreparedApplication,
        block: VerifiedCandidateBlock,
    ): Result[F, ApplicationDecision] = safety.transaction(current =>
      for
        _ <- requireDomain(block.context)
        _ <- requireDomain(prepared.batch.context)
        _ <- check(
          current.preparations.get(prepared.batchDigest).contains(prepared),
          "application commit has no identical durable preparation",
        )
        proposal = block.finalized.proposal
        _ <- check(
          prepared.batch.candidateHeight == block.height && proposal.block.parent
            .map(_.toUInt256)
            .contains(
              prepared.batch.parentBlockId,
            ) && proposal.block.stateRoot.toUInt256 == prepared.batch.nextStateRoot && proposal.block.bodyRoot.toUInt256 == prepared.batch.bodyRoot && proposal.block.executionPlanRoot
            .map(_.toUInt256)
            .contains(prepared.batch.planRoot),
          "committed block header differs from prepared parent/height/state/body/plan",
        )
        result <- current.decisions.get(prepared.batchDigest) match
          case Some(existing) =>
            check(
              existing.blockId == block.blockId,
              "committed application retry changed block identity",
            ).as(existing)
          case None =>
            for
              _ <- currentParent(current, prepared.batch)
              _ <- check(
                current.sequence < Long.MaxValue,
                "application decision sequence exhausted",
              )
              _ <- safety.retainBlob(
                ApplicationBlobStorage.evidenceNamespace,
                block.evidenceDigest,
                block.evidenceBytes,
              )
              executions = prepared.batch.entries.map(_.executionId).toSet
              selected   = prepared.batch.entries.map(_.ownerDigest)
              extra      = current.claims.toVector
                .collect {
                  case (id, claim)
                      if claim.lifecycle == ClaimLifecycle.Live && executions
                        .contains(claim.owner.executionId) && !selected
                        .contains(id) =>
                    id
                }
                .sortBy(_.bytes.toHex)
              owners     = selected ++ extra
              resolution = TerminalResolution(
                ResolutionKind.Applied,
                block.evidenceDigest,
                block.height,
                Some(prepared.batchDigest),
              )
              claims <- owners.traverse(id =>
                EitherT
                  .fromOption[F](
                    current.claims.get(id),
                    V2RuntimeFailure.at(
                      RuntimeFailureCode.JournalCorrupt,
                      "selected application owner is absent",
                    ),
                  )
                  .flatMap(claim =>
                    check(
                      claim.lifecycle == ClaimLifecycle.Live,
                      "selected application owner is already terminal",
                    ).as(
                      claim.copy(
                        lifecycle = ClaimLifecycle.Applied,
                        terminal = Some(resolution),
                      ),
                    ),
                  ),
              )
              locks = current.locks.values.toVector
                .filter(lock =>
                  lock.lifecycle == ClaimLifecycle.Live && executions.contains(
                    lock.executionId,
                  ),
                )
                .map(
                  _.copy(
                    lifecycle = ClaimLifecycle.Applied,
                    terminal = Some(resolution),
                  ),
                )
                .sortBy(_.executionId.toUInt256.bytes.toHex)
              keyed <- claims.traverse(claim =>
                core(Owner.digest(claim.owner)).map(_ -> claim),
              )
              decision = ApplicationDecision(
                2L,
                prepared.batchDigest,
                block.blockId,
                current.sequence + 1L,
                owners,
              )
              inventoryBytes <- safety.journal.readBlob(
                ApplicationBlobStorage.inventoryNamespace,
                prepared.preparedStateInventory,
              )
              inventory <- core(
                PreparedStateInventory.codec.decode(inventoryBytes),
              )
              exactUpdates <- pure(
                ExactLifecycleProjection.application(
                  current,
                  prepared.batch,
                  decision,
                  block.evidenceDigest,
                  inventory.plan,
                ),
              )
              resolutions = Option
                .when(claims.nonEmpty || locks.nonEmpty)(resolution)
                .toList
                .toVector
              payload = JournalPayload.empty.copy(
                applicationDecision = Some(decision),
                claims = keyed.sortBy(_._1.bytes.toHex).map(_._2),
                locks = locks,
                terminalResolutions = resolutions,
                exactRecordUpdates = exactUpdates,
              )
              _ <- safety.persist(
                current,
                JournalOperation.ApplicationCommit,
                payload,
              )
            yield decision
      yield result,
    )

    def recover: Result[F, ApplicationRecovery] =
      safety.recover.flatMap(_ =>
        safety.transaction(current =>
          for
            head  <- pure(SafetyJournalReduction.head(current))
            index <- core(ConflictIndexRow.inventoryDigest(current.index))
          yield ApplicationRecovery(
            current.canonical,
            current.sequence,
            SafetyInventory.digest(head, index),
          ),
        ),
      )

    def applied(
        context: DomainContext,
        execution: ExecutionId,
    ): Result[F, Option[AppliedIndexRecord]] = safety.transaction(current =>
      requireDomain(context).as(current.appliedEntries.get(execution)),
    )

    def canonicalPayload: Result[F, Option[(ApplicationDecision, Bytes)]] =
      safety.transaction(current =>
        current.canonical.traverse(decision =>
          for
            prepared <- EitherT.fromOption[F](
              current.preparations.get(decision.batchDigest),
              V2RuntimeFailure.at(
                RuntimeFailureCode.JournalCorrupt,
                "canonical decision lacks preparation",
              ),
            )
            bytes <- safety.journal
              .readBlob(
                ApplicationBlobStorage.stateNamespace,
                prepared.batch.statePayloadDigest,
              )
              .leftMap(error =>
                if error.code == RuntimeFailureCode.ProofUnavailable then
                  V2RuntimeFailure.at(
                    RuntimeFailureCode.JournalCorrupt,
                    "canonical state payload is missing",
                  )
                else error,
              )
            digest <- core(ApplicationBlobStorage.stateDigest(bytes))
            _      <- pure(
              RuntimeCheck.require(
                digest == prepared.batch.statePayloadDigest,
                RuntimeFailureCode.JournalCorrupt,
                "canonical state payload commitment mismatch",
              ),
            )
          yield decision -> bytes,
        ),
      )

    def expire(
        proof: VerifiedFinalityAndNonapplication,
    ): Result[F, Vector[TerminalResolution]] = safety.transaction(current =>
      for
        _ <- requireDomain(proof.candidate.context)
        executions = proof.executions.toSet
        _ <- check(
          !executions.exists(current.appliedEntries.contains),
          "nonapplication proof targets an already applied execution",
        )
        claims = current.claims.values.toVector.filter(claim =>
          claim.lifecycle == ClaimLifecycle.Live && executions.contains(
            claim.owner.executionId,
          ),
        )
        locks = current.locks.values.toVector.filter(lock =>
          lock.lifecycle == ClaimLifecycle.Live && executions.contains(
            lock.executionId,
          ),
        )
        _ <- check(
          (claims.map(_.lastInclusionHeight) ++ locks.map(
            _.lastInclusionHeight,
          )).forall(deadline =>
            proof.candidate.height.toBigNat.toBigInt > deadline.toBigNat.toBigInt,
          ),
          "finalized checkpoint must be strictly beyond every signed inclusion deadline",
        )
        resolution = TerminalResolution(
          ResolutionKind.ExpiredUnapplied,
          proof.evidenceDigest,
          proof.candidate.height,
          None,
        )
        exactUpdates <- pure(
          ExactLifecycleProjection.expiry(current, resolution, proof.executions),
        )
        result <-
          if claims.isEmpty && locks.isEmpty && exactUpdates.isEmpty then
            EitherT.rightT[F, V2RuntimeFailure](
              Vector.empty[TerminalResolution],
            )
          else
            for
              _ <- safety.retainBlob(
                ApplicationBlobStorage.evidenceNamespace,
                proof.evidenceDigest,
                proof.evidenceBytes,
              )
              updatedClaims <- claims.traverse(claim =>
                core(Owner.digest(claim.owner)).map(
                  _ -> claim.copy(
                    lifecycle = ClaimLifecycle.ExpiredUnapplied,
                    terminal = Some(resolution),
                  ),
                ),
              )
              updatedLocks = locks
                .map(
                  _.copy(
                    lifecycle = ClaimLifecycle.ExpiredUnapplied,
                    terminal = Some(resolution),
                  ),
                )
                .sortBy(_.executionId.toUInt256.bytes.toHex)
              payload = JournalPayload.empty.copy(
                claims = updatedClaims.sortBy(_._1.bytes.toHex).map(_._2),
                locks = updatedLocks,
                terminalResolutions = Vector(resolution),
                exactRecordUpdates = exactUpdates,
              )
              _ <- safety.persist(current, JournalOperation.Expiry, payload)
            yield Vector(resolution)
      yield result,
    )
