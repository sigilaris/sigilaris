package org.sigilaris.node.jvm.runtime.application.v2

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.InclusionHeight
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.gossip.ChainId
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*

/** A concrete continuation interpreter for the supported original safety
  * deployment. The constructor receives the complete independently installed
  * historical schedule and actual live key controllers. It does not accept a
  * caller's summary of signed heights, a copied audit, or a claimed safety map.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
final class HistoricalContinuationAuthentication(
    consensus: HistoricalConsensusAuthentication[IO],
    history: HistoricalConsensusRepository[IO],
    validators: ValidatorSetLookup[IO],
    original: HistoricalContinuationRepository[IO],
    controllers: Vector[FenceController],
    safety: Vector[HistoricalConsensusSafetyStore],
    authorizedScopeDigest: Hash,
):
  private def core[A](value: Either[CoreFailure, A]): Result[IO, A] =
    EitherT.fromEither[IO](RuntimeCheck.core(value))
  private def check(condition: Boolean, detail: String): Result[IO, Unit] =
    EitherT.fromEither[IO](
      RuntimeCheck.require(condition, RuntimeFailureCode.ProofInvalid, detail),
    )
  private def required[A](value: Option[A], detail: String): Result[IO, A] =
    EitherT.fromOption[IO](
      value,
      V2RuntimeFailure.at(RuntimeFailureCode.ProofUnavailable, detail),
    )
  private def selected(
      value: HistoricalConsensusAuthentication.VerifiedCertifiedProposal,
  ): Result[IO, CertifiedSuffixEntry] = for profile <- core(
      HistoricalProfileRange.digest(value.execution.selected),
    )
  yield CertifiedSuffixEntry(
    InclusionHeight(value.execution.proposal.block.height.toBigNat),
    value.execution.proposal.targetBlockId.toUInt256,
    value.execution.parent.targetBlockId.toUInt256,
    profile,
    value.proposalDigest,
    value.quorumDigest,
    value.execution.replay.resultInventoryDigest,
  )

  def continuation(record: HandoverEvidence): Result[IO, ContinuationAudit] =
    for
      bytes  <- original.read(record.continuationProofDigest)
      proof  <- core(HistoricalContinuationProof.codec.decode(bytes))
      digest <- core(HistoricalContinuationProof.digest(proof))
      _      <- check(
        digest == record.continuationProofDigest,
        "continuation proof changed its immutable content digest",
      )
      drain <- consensus.finalized(proof.drain)
      first <- required(
        drain.ordered.headOption,
        "original drain block missing",
      )
      suffix <- proof.suffix.traverse(value =>
        consensus.certified(value.proposal, value.certificate),
      )
      _ <- (first +: suffix.map(_.execution))
        .zip(suffix.map(_.execution))
        .traverse_ { (parent, child) =>
          check(
            child.parent.targetBlockId == parent.proposal.targetBlockId && child.proposal.block.height.toBigNat.toBigInt == parent.proposal.block.height.toBigNat.toBigInt + 1 &&
              child.replay.priorStateRoot == parent.replay.nextStateRoot && child.selected.context == record.source,
            "actual old suffix changed parent, height, context or replayed state",
          )
        }
      last = suffix.lastOption.fold(first)(_.execution)
      _ <- check(
        first.selected.context == record.source && last.proposal.block.height.toBigNat.toBigInt + 1 == record.boundaryHeight.toBigNat.toBigInt,
        "original F/P provenance or future boundary is inconsistent",
      )
      oldRanges = consensus.profiles.configuration.ranges.filter(range =>
        range.context.chainId == record.source.chainId && range.context.protocolVersion == 1L && range.firstHeight.toBigNat.toBigInt < record.boundaryHeight.toBigNat.toBigInt,
      )
      _ <- check(
        oldRanges.nonEmpty,
        "complete historical profile inventory is unavailable",
      )
      historical <- oldRanges.traverse { range =>
        val window = HotStuffWindow(
          ChainId.unsafe(range.context.chainId.asString),
          HotStuffHeight(range.firstHeight.toBigNat),
          HotStuffView.Zero,
          ValidatorSetHash(range.context.validatorSetHash),
        )
        EitherT(validators.validatorSetFor(window))
          .leftMap(error =>
            V2RuntimeFailure
              .at(RuntimeFailureCode.ProofUnavailable, error.reason),
          )
          .flatMap { set =>
            check(
              set.hash.toUInt256 == range.context.validatorSetHash,
              "historical set differs from its independently pinned range",
            ).as(
              HistoricalFenceSet(
                range.context,
                set.members
                  .map(member =>
                    InitialValidator(
                      Utf8(member.id.value),
                      ByteEncoder[org.sigilaris.core.crypto.PublicKey]
                        .encode(member.publicKey),
                    ),
                  )
                  .sortBy(value => V2Validation.textKey(value.validatorId)),
              ),
            )
          }
      }
      sets = historical.distinct.sortBy(value =>
        DomainContext.codec.encode(value.context).toOption.fold("")(_.toHex),
      )
      requiredKeys = sets
        .flatMap(_.validators)
        .map(value => (value.validatorId, value.publicKey))
        .distinct
      actualKeys = controllers.map(value => (value.signerId, value.publicKey))
      _ <- check(
        actualKeys.distinct.sizeCompare(
          actualKeys.size,
        ) == 0 && requiredKeys.toSet == actualKeys.toSet,
        "actual controller roster does not cover every still-certifiable historical key",
      )
      audits    <- controllers.traverse(_.audit)
      sourceSet <- required(
        sets.find(_.context == record.source),
        "source validator configuration missing",
      )
      expectedVoters = sourceSet.validators.map(_.validatorId).toSet
      _ <- check(
        safety.map(_.profile.voter).toSet == expectedVoters && safety
          .sizeCompare(expectedVoters.size) == 0 && safety.forall(
          _.profile.context == record.source,
        ),
        "actual original safety stores do not cover the source validator roster",
      )
      current <- safety
        .traverse(_.archive)
        .map(_.sortBy(value => V2Validation.textKey(value.profile.voter)))
      _ <- check(
        current == proof.safety,
        "fixed continuation safety archive is not the complete current original journal",
      )
      verified <- current.traverse(value =>
        HistoricalConsensusSafety.verify(
          value.profile,
          value.originalRecords,
          consensus,
          history,
          validators,
        ),
      )
      _ <- verified.traverse_ { value =>
        check(
          value.state.highQc.subject.window.height.toBigNat.toBigInt <= last.proposal.window.height.toBigNat.toBigInt && value.state.lockedQc.subject.window.height.toBigNat.toBigInt <= last.proposal.window.height.toBigNat.toBigInt,
          "continuation parent discards a higher retained original safety QC",
        )
      }
      // Re-run the actual documented safe-vote branch from each retained lock.
      _ <- verified.traverse_ { value =>
        cats
          .Monad[[A] =>> Result[IO, A]]
          .tailRecM((last.proposal, Set.empty[ProposalId])) { (cursor, seen) =>
            if cursor.targetBlockId == value.state.lockedQc.subject.blockId then
              check(
                cursor.window == value.state.lockedQc.subject.window,
                "continuation lock has another signed window",
              ).as(Right[(Proposal, Set[ProposalId]), Unit](()))
            else
              for
                _ <- check(
                  !seen.contains(
                    cursor.proposalId,
                  ) && cursor.block.height.toBigNat.toBigInt > value.state.lockedQc.subject.window.height.toBigNat.toBigInt,
                  "selected continuation does not extend the actual original lock",
                )
                previous <- consensus.proposal(cursor)
              yield Left[(Proposal, Set[ProposalId]), Unit](
                (previous.parent, seen + cursor.proposalId),
              )
          }
      }
      entries <- suffix.traverse(selected)
      signedHeights = audits
        .flatMap(_.possibleSigningIntents)
        .map(_.material)
        .filter(value =>
          value.kind == ControllerSigningKind.Consensus && sets.exists(
            _.context == value.context,
          ),
        )
        .flatMap(_.height)
        .distinct
        .sortBy(_.toBigNat.toBigInt)
    yield ContinuationAudit(
      record.source,
      record.target,
      first.proposal.targetBlockId.toUInt256,
      InclusionHeight(first.proposal.block.height.toBigNat),
      first.replay.nextStateRoot,
      last.proposal.targetBlockId.toUInt256,
      InclusionHeight(last.proposal.block.height.toBigNat),
      last.replay.nextStateRoot,
      entries,
      verified
        .map(_.binding)
        .sortBy(value => V2Validation.textKey(value.signerId)),
      sets,
      signedHeights,
    )

  /** Queries the current exclusive controller. Recovery of that controller has
    * already reauthenticated every original operation, seed, fence and write.
    * The promise is then checked against the complete current possible key-use
    * inventory, including forced intents whose key result was uncertain.
    */
  def fence(
      promise: SignedFencePromise,
      intent: TransitionIntent,
  ): Result[IO, EnforcedFenceAudit] = for
    controller <- required(
      controllers.find(_.signerId == promise.record.signerId),
      "actual enforcing key controller missing",
    )
    audit   <- controller.audit
    binding <- core(TransitionIntent.digest(intent))
    _       <- check(
      intent.sourceAuthorityScopeDigest == authorizedScopeDigest && promise.record.transitionBinding == binding &&
        audit.enforcements.exists(value =>
          value.intent == intent && value.promise == promise.record,
        ) && audit.signedFences.contains(promise),
      "fence is not actively enforced by the original authorized controller",
    )
    prefixIndex <- required(
      audit.snapshot.records.zipWithIndex
        .find { (record, _) =>
          record.kind == ControllerEventKind.Enforcement && ControllerEnforcement.codec
            .decode(record.payload)
            .toOption
            .exists(value =>
              value.intent == intent && value.promise == promise.record,
            )
        }
        .map(_._2),
      "original pre-enforcement controller prefix is unavailable",
    )
    prefix <- EitherT.fromEither[IO](
      ControllerHistory.recover(
        audit.snapshot.copy(records = audit.snapshot.records.take(prefixIndex)),
      ),
    )
    originalDigest <- EitherT.fromEither[IO](
      ControllerHistory.signingHistoryDigest(
        prefix,
        promise.record.context,
        promise.record.scope,
      ),
    )
    _ <- check(
      originalDigest == promise.record.signingHistoryDigest && ControllerHistory
        .watermark(
          prefix,
          promise.record.context,
          promise.record.scope,
        ) == promise.record.greatestPreviouslySignedHeight,
      "fence changed its original before-enforcement signing prefix",
    )
    material = prefix.signing.valuesIterator.map(_.material).toVector.filter {
      value =>
        promise.record.scope match
          case FenceScope.ApplicationIssuance =>
            value.context == promise.record.context && value.kind != ControllerSigningKind.Consensus
          case FenceScope.ConsensusProfileAtOrAbove =>
            value.context == promise.record.context && value.kind == ControllerSigningKind.Consensus
          case FenceScope.SourceOrRetiredDomainWritesAndSigning =>
            value.context.chainId == promise.record.context.chainId
    }
  yield EnforcedFenceAudit(
    promise,
    controller.publicKey,
    authorizedScopeDigest,
    material.flatMap(_.height),
    audit.inventory,
  )
