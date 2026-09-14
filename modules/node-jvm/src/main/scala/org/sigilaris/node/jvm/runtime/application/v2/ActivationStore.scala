package org.sigilaris.node.jvm.runtime.application.v2

import cats.data.EitherT
import cats.effect.{IO, Ref}
import cats.effect.std.Semaphore
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.datatype.Utf8

/** Original forced material selected by the frozen ActivationPreparation. The
  * auxiliary reference binds the complete inactive schema output as well as the
  * original handover; it cannot turn preparation into an active decision.
  */
final case class ActivationOriginalEvidence(
    format: Long,
    handover: HandoverEvidence,
    preparation: ActivationPreparation,
    preparedGroupDigest: Hash,
)
object ActivationOriginalEvidence:
  given ByteEncoder[ActivationOriginalEvidence]         = ByteEncoder.derived
  given ByteDecoder[ActivationOriginalEvidence]         = ByteDecoder.derived
  val codec: CanonicalCodec[ActivationOriginalEvidence] =
    CanonicalCodec.derived(value =>
      for
        _ <- V2Validation
          .format(value.format, 1L, "activationOriginalEvidence.format")
        _ <- HandoverEvidence.validate(value.handover)
        _ <- ActivationPreparation.validate(value.preparation)
      yield (),
    )
  val namespace: Text = Utf8("activation-original")

type VerifiedActiveGroup = ActivationStore.VerifiedActiveGroup
final case class ActivationRecovery(
    decision: Option[ActivationDecision],
    highestSequence: Long,
    verifiedInventoryDigest: Hash,
    activeGroup: Option[VerifiedActiveGroup],
)
final case class RestoreEligibility(
    completeGroupDigest: Hash,
    preservedSafetyInventoryDigest: Hash,
    restoreEvidenceDigest: Hash,
)

trait ActivationStore[F[_]]:
  def prepare(
      transition: VerifiedHandover,
      group: VerifiedConsistencyGroup,
  ): Result[F, ActivationPreparation]
  def commit(prepared: ActivationPreparation): Result[F, ActivationDecision]
  def recover: Result[F, ActivationRecovery]
  def verifyRestore(evidence: VerifiedRestore): Result[F, RestoreEligibility]

/** Exclusive pre-start owner of the actual target DurableJournal. Only a
  * complete authenticated decision exposes an active group. All namespaces
  * remain immutable content-addressed images; there is no partial filesystem
  * rename whose visibility can precede this one journal decision.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object ActivationStore:
  final class VerifiedActiveGroup private[ActivationStore] (
      val decision: ActivationDecision,
      val preparation: ActivationPreparation,
      val handover: VerifiedHandover,
      val group: VerifiedConsistencyGroup,
      private[jvm] val journal: DurableJournal[IO],
  ):
    def workingStatePayload: Bytes               = group.workingStatePayload
    private[jvm] def controller: FenceController = group.controller

  def authenticated(
      anchor: ApplicationAnchor,
      transitions: TransitionEvidenceVerifier[IO],
      groups: ConsistencyGroupStore,
      journal: DurableJournal[IO],
  ): IO[ActivationStore[IO]] =
    (Semaphore[IO](1L), Ref.of[IO, Vector[JournalRecord]](Vector.empty)).mapN {
      (gate, observed) =>
        new ActivationStore[IO]:
          private def pure[A](
              value: Either[V2RuntimeFailure, A],
          ): Result[IO, A] = EitherT.fromEither[IO](value)
          private def core[A](value: Either[CoreFailure, A]): Result[IO, A] =
            pure(RuntimeCheck.core(value))
          private def check(
              condition: Boolean,
              detail: String,
          ): Result[IO, Unit] =
            pure(
              RuntimeCheck.require(
                condition,
                RuntimeFailureCode.StartupIdentityMismatch,
                detail,
              ),
            )
          private def under[A](run: Result[IO, A]): Result[IO, A] = EitherT(
            gate.permit.use(_ =>
              IO.uncancelable(_ =>
                run.value.attempt.map {
                  case Right(result) => result
                  case Left(_)       =>
                    Left(
                      V2RuntimeFailure.at(
                        RuntimeFailureCode.StorageUnknown,
                        "activation original proof or durable outcome is unknown",
                      ),
                    )
                },
              ),
            ),
          )
          private def committed(
              history: JournalHistory,
          ): Vector[JournalRecord] =
            history.records.filter(_.status == JournalStatus.Committed)
          private def physical: Result[IO, JournalHistory] = for
            raw    <- journal.recover
            before <- EitherT.liftF(observed.get)
            _      <- check(
              raw.startsWith(before),
              "activation recovery lost already observed transition records",
            )
            history <- pure(JournalHistory.validate(raw))
            _       <- EitherT.liftF(observed.set(raw))
          yield history
          private def original(
              prepared: ActivationPreparation,
          ): Result[IO, (VerifiedHandover, VerifiedConsistencyGroup)] = for
            digest <- core(ActivationPreparation.digest(prepared))
            raw    <- journal.readBlob(
              ActivationOriginalEvidence.namespace,
              digest,
            )
            record <- core(ActivationOriginalEvidence.codec.decode(raw))
            _      <- check(
              record.preparation == prepared,
              "activation original evidence changed its exact preparation",
            )
            transition <- transitions.verifyHandover(record.handover)
            group      <- groups.recover(
              prepared.completeOldGroupDigest,
              record.preparedGroupDigest,
              transition,
            )
            expected <- preparation(transition, group)
            _        <- check(
              expected == prepared,
              "activation preparation no longer matches complete source/safety/schema evidence",
            )
          yield transition -> group
          private def preparation(
              transition: VerifiedHandover,
              group: VerifiedConsistencyGroup,
          ): Result[IO, ActivationPreparation] = for
            intent <- core(
              TransitionIntent.digest(transition.evidence.transitionIntent),
            )
            _ <- check(
              group.original.transitionDigest == transition.digest && group.original.baseline.transitionIntentDigest == intent &&
                transition.evidence.target == anchor.context && transition.evidence.targetManifestDigest == anchor.context.configurationDigest &&
                group.original.working.context == transition.evidence.source && group.original.working.blockId == transition.evidence.continuationParentId &&
                group.original.working.stateRoot == transition.evidence.continuationParentRoot,
              "complete consistency group belongs to another transition, original profile, or working parent",
            )
            baseline <- core(EvidenceBaseline.digest(group.original.baseline))
            _        <- check(
              baseline == transition.evidence.evidenceBaselineDigest,
              "activation original fixed evidence baseline changed",
            )
            value = ActivationPreparation(
              2L,
              transition.digest,
              baseline,
              group.completeOldGroupDigest,
              group.prepared,
              transition.evidence.continuationParentId,
              transition.evidence.boundaryHeight,
              transition.evidence.targetManifestDigest,
              group.retainedSafetyInventoryDigest,
            )
            _ <- core(ActivationPreparation.codec.encode(value))
          yield value

          private def authenticate(history: JournalHistory): Result[
            IO,
            (
                TransitionJournalState,
                Map[Hash, (VerifiedHandover, VerifiedConsistencyGroup)],
            ),
          ] = for
            projection <- pure(
              TransitionJournalReduction.projection(committed(history), anchor),
            )
            _ <- check(
              projection.startup.isEmpty,
              "activation cannot adopt an initial-bootstrap journal",
            )
            verified <- projection.preparations.toVector.traverse {
              (digest, prepared) => original(prepared).map(digest -> _)
            }
          yield projection -> verified.toMap

          private def recovered: Result[
            IO,
            (
                JournalHistory,
                TransitionJournalState,
                Map[Hash, (VerifiedHandover, VerifiedConsistencyGroup)],
            ),
          ] = for
            history <- physical
            result  <- history.pending match
              case None =>
                authenticate(history).map((state, verified) =>
                  (history, state, verified),
                )
              case Some(pending) =>
                for
                  _ <- check(
                    pending.operation == JournalOperation.ActivationPrepare || pending.operation == JournalOperation.ActivationCommit,
                    "ordinary pending work must recover through its configured application runtime",
                  )
                  committedRecord = pending.copy(status =
                    JournalStatus.Committed,
                  )
                  candidate <- pure(
                    JournalHistory.append(history, committedRecord),
                  )
                  // Read every original group and current fence BEFORE forwarding an
                  // uncertain complete decision. Partial/corrupt history stays fenced.
                  authenticated <- authenticate(candidate)
                  _             <- journal.append(committedRecord)
                  _ <- EitherT.liftF(observed.set(candidate.records))
                yield (candidate, authenticated._1, authenticated._2)
          yield result

          private def persist(
              history: JournalHistory,
              operation: JournalOperation,
              payload: JournalPayload,
          ): Result[IO, JournalHistory] = for
            _ <- check(
              history.pending.isEmpty && history.head.sequence < Long.MaxValue,
              "activation append needs a recovered journal with sequence capacity",
            )
            raw    <- core(JournalPayload.codec.encode(payload))
            digest <- core(JournalPayload.digest(payload))
            prepared = JournalRecord(
              2L,
              history.head.sequence + 1L,
              operation,
              history.head.digest,
              raw,
              digest,
              JournalStatus.Prepared,
            )
            committedRecord = prepared.copy(status = JournalStatus.Committed)
            middle <- pure(JournalHistory.append(history, prepared))
            next   <- pure(JournalHistory.append(middle, committedRecord))
            _      <- pure(
              TransitionJournalReduction.projection(committed(next), anchor),
            )
            _ <- journal.append(prepared)
            _ <- EitherT.liftF(observed.set(middle.records))
            _ <- journal.append(committedRecord)
            _ <- EitherT.liftF(observed.set(next.records))
          yield next

          def prepare(
              transition: VerifiedHandover,
              group: VerifiedConsistencyGroup,
          ): Result[IO, ActivationPreparation] = under(for
            actual <- transitions.verifyHandover(transition.evidence)
            _      <- check(
              actual.digest == transition.digest,
              "handover identity changed before preparation",
            )
            current <- recovered
            _       <- check(
              current._2.decision.isEmpty && !current._2.ordinaryRecords,
              "an active or previously used target cannot prepare another transition",
            )
            prepared <- groups.withRevalidated(group, actual) { checkedGroup =>
              for
                value  <- preparation(actual, checkedGroup)
                digest <- core(ActivationPreparation.digest(value))
                result <- current._2.preparations.get(digest) match
                  case Some(prior) =>
                    for
                      _ <- check(
                        prior == value,
                        "activation retry changed original preparation",
                      )
                      _ <-
                        if current._2.latestPreparation.contains(digest) then
                          EitherT.pure[IO, V2RuntimeFailure](())
                        else
                          persist(
                            current._1,
                            JournalOperation.ActivationPrepare,
                            JournalPayload.empty
                              .copy(activationPreparation = Some(value)),
                          ).void
                    yield prior
                  case None =>
                    for
                      raw <- core(
                        ActivationOriginalEvidence.codec.encode(
                          ActivationOriginalEvidence(
                            1L,
                            actual.evidence,
                            value,
                            checkedGroup.preparedRecordDigest,
                          ),
                        ),
                      )
                      _ <- journal.putBlob(
                        ActivationOriginalEvidence.namespace,
                        digest,
                        raw,
                      )
                      retained <- journal.readBlob(
                        ActivationOriginalEvidence.namespace,
                        digest,
                      )
                      _ <- check(
                        retained == raw,
                        "forced activation evidence readback differs",
                      )
                      _ <- persist(
                        current._1,
                        JournalOperation.ActivationPrepare,
                        JournalPayload.empty.copy(activationPreparation =
                          Some(value),
                        ),
                      )
                    yield value
              yield result
            }
          yield prepared)

          def commit(
              prepared: ActivationPreparation,
          ): Result[IO, ActivationDecision] = under(for
            current <- recovered
            digest  <- core(ActivationPreparation.digest(prepared))
            _       <- check(
              current._2.preparations
                .get(digest)
                .contains(prepared) && current._2.latestPreparation
                .contains(digest),
              "activation commit must select the identical latest durable preparation",
            )
            selected <- EitherT.fromOption[IO](
              current._3.get(digest),
              V2RuntimeFailure.at(
                RuntimeFailureCode.EvidenceMissing,
                "selected original activation group is unavailable",
              ),
            )
            decision <- current._2.decision match
              case Some(existing) =>
                check(
                  existing.preparationDigest == digest,
                  "activation retry selects another group",
                ).as(existing)
              case None =>
                groups.withRevalidated(selected._2, selected._1) {
                  checkedGroup =>
                    for
                      _ <- check(
                        checkedGroup.completeOldGroupDigest == prepared.completeOldGroupDigest && checkedGroup.prepared == prepared.prepared &&
                          prepared.parentBlockId == anchor.blockId && prepared.firstHeight.toBigNat.toBigInt == anchor.height.toBigNat.toBigInt + 1 &&
                          selected._1.evidence.continuationParentRoot == anchor.stateRoot,
                        "prepared opening changed its actual parent, root, height or complete namespace selection",
                      )
                      _ <- check(
                        current._1.head.sequence < Long.MaxValue,
                        "activation sequence capacity exhausted",
                      )
                      decision = ActivationDecision(
                        2L,
                        digest,
                        current._1.head.sequence + 1L,
                        prepared.parentBlockId,
                        prepared.firstHeight,
                        prepared.targetManifestDigest,
                        prepared.retainedSafetyInventoryDigest,
                      )
                      _ <- persist(
                        current._1,
                        JournalOperation.ActivationCommit,
                        JournalPayload.empty.copy(activationDecision =
                          Some(decision),
                        ),
                      )
                    yield decision
                }
          yield decision)

          def recover: Result[IO, ActivationRecovery] = under(
            for
              current <- recovered
              active  <- current._2.decision.traverse { decision =>
                for
                  prepared <- EitherT.fromOption[IO](
                    current._2.preparations.get(decision.preparationDigest),
                    V2RuntimeFailure.at(
                      RuntimeFailureCode.EvidenceMissing,
                      "active preparation is unavailable",
                    ),
                  )
                  original <- EitherT.fromOption[IO](
                    current._3.get(decision.preparationDigest),
                    V2RuntimeFailure.at(
                      RuntimeFailureCode.EvidenceMissing,
                      "active original group is unavailable",
                    ),
                  )
                  _ <- check(
                    prepared.parentBlockId == anchor.blockId && original._1.evidence.continuationParentRoot == anchor.stateRoot,
                    "active group state does not match installed execution anchor",
                  )
                yield new VerifiedActiveGroup(
                  decision,
                  prepared,
                  original._1,
                  original._2,
                  journal,
                )
              }
              inventory <- core(
                TransitionInventory.digest(
                  current._2.preparations.toVector
                    .sortBy(_._1.bytes.toHex)
                    .map { (digest, _) =>
                      InventoryEntry(
                        Utf8("activation-preparations"),
                        Utf8(digest.bytes.toHex),
                        digest,
                      )
                    },
                ),
              )
            yield ActivationRecovery(
              current._2.decision,
              current._1.head.sequence,
              inventory,
              active,
            ),
          )

          def verifyRestore(
              evidence: VerifiedRestore,
          ): Result[IO, RestoreEligibility] = under(
            for
              actual <- transitions.verifyRestore(evidence.evidence)
              _      <- check(
                actual.digest == evidence.digest,
                "restore evidence changed while current fences were verified",
              )
              current <- recovered
              _       <- check(
                current._2.preparations.valuesIterator.exists(
                  _.completeOldGroupDigest == actual.evidence.sourceGroupDigest,
                ),
                "restore names no complete original group retained by this activation",
              )
              _ <- groups.readOriginal(actual.evidence.sourceGroupDigest)
            yield RestoreEligibility(
              actual.evidence.sourceGroupDigest,
              actual.evidence.currentSafetyInventoryDigest,
              actual.digest,
            ),
          )
    }

  private def originalSelection(
      anchor: ApplicationAnchor,
      transitions: TransitionEvidenceVerifier[IO],
      groups: ConsistencyGroupStore,
      journal: DurableJournal[IO],
      records: Vector[JournalRecord],
  ): Result[
    IO,
    (
        TransitionJournalState,
        Map[Hash, (VerifiedHandover, VerifiedConsistencyGroup)],
    ),
  ] = for
    projection <- EitherT.fromEither[IO](
      TransitionJournalReduction.projection(records, anchor),
    )
    _ <- EitherT.fromEither[IO](
      RuntimeCheck.require(
        projection.decision.nonEmpty && projection.startup.isEmpty,
        RuntimeFailureCode.RecoveryRequired,
        "target application runtime requires the original complete activation decision",
      ),
    )
    verified <- projection.preparations.toVector.traverse {
      (digest, prepared) =>
        for
          raw <- journal.readBlob(
            ActivationOriginalEvidence.namespace,
            digest,
          )
          record <- EitherT.fromEither[IO](
            RuntimeCheck.core(ActivationOriginalEvidence.codec.decode(raw)),
          )
          actualDigest <- EitherT.fromEither[IO](
            RuntimeCheck.core(
              ActivationPreparation.digest(record.preparation),
            ),
          )
          transition <- transitions.verifyHandover(record.handover)
          group      <- groups.recover(
            prepared.completeOldGroupDigest,
            record.preparedGroupDigest,
            transition,
          )
          baseline <- EitherT.fromEither[IO](
            RuntimeCheck.core(
              EvidenceBaseline.digest(group.original.baseline),
            ),
          )
          intent <- EitherT.fromEither[IO](
            RuntimeCheck.core(
              TransitionIntent.digest(transition.evidence.transitionIntent),
            ),
          )
          _ <- EitherT.fromEither[IO](
            RuntimeCheck.require(
              record.preparation == prepared && actualDigest == digest &&
                transition.digest == prepared.transitionDigest && transition.evidence.target == anchor.context && group.original.transitionDigest == transition.digest &&
                baseline == prepared.baselineDigest && baseline == transition.evidence.evidenceBaselineDigest && group.original.baseline.transitionIntentDigest == intent &&
                prepared.prepared == group.prepared && prepared.retainedSafetyInventoryDigest == group.retainedSafetyInventoryDigest &&
                prepared.parentBlockId == transition.evidence.continuationParentId && prepared.firstHeight == transition.evidence.boundaryHeight &&
                prepared.targetManifestDigest == transition.evidence.targetManifestDigest &&
                projection.decision.forall(d =>
                  d.preparationDigest != digest || (prepared.parentBlockId == anchor.blockId && transition.evidence.continuationParentRoot == anchor.stateRoot),
                ),
              RuntimeFailureCode.JournalCorrupt,
              "application history changed its original activation source, boundary, safety or complete schema group",
            ),
          )
        yield digest -> (transition -> group)
    }
  yield projection -> verified.toMap

  /** Read only the already committed activation namespace selection, including
    * during application recovery. Ordinary pending records are preserved and
    * confer no application readiness. An uncertain ActivationCommit itself
    * still requires ActivationStore.recover before this can succeed.
    */
  def selectedGroup(
      anchor: ApplicationAnchor,
      transitions: TransitionEvidenceVerifier[IO],
      groups: ConsistencyGroupStore,
      journal: DurableJournal[IO],
  ): Result[IO, VerifiedActiveGroup] = for
    raw      <- journal.recover
    physical <- EitherT.fromEither[IO](JournalHistory.validate(raw))
    verified <- originalSelection(
      anchor,
      transitions,
      groups,
      journal,
      physical.records.filter(_.status == JournalStatus.Committed),
    )
    decision <- EitherT.fromOption[IO](
      verified._1.decision,
      V2RuntimeFailure.at(
        RuntimeFailureCode.RecoveryRequired,
        "activation has no committed selected group",
      ),
    )
    preparation <- EitherT.fromOption[IO](
      verified._1.preparations.get(decision.preparationDigest),
      V2RuntimeFailure.at(
        RuntimeFailureCode.JournalCorrupt,
        "selected activation preparation is missing",
      ),
    )
    original <- EitherT.fromOption[IO](
      verified._2.get(decision.preparationDigest),
      V2RuntimeFailure.at(
        RuntimeFailureCode.EvidenceMissing,
        "selected complete activation group is unavailable",
      ),
    )
  yield new VerifiedActiveGroup(
    decision,
    preparation,
    original._1,
    original._2,
    journal,
  )

  /** Verify the provided complete immutable history before every target write
    * and during recovery. The original canonical ledger must reach P before a
    * target ApplicationPrepare, including a complete tail recovered at startup.
    * This parser never reenters the outer SafetyStore or ActivationStore gate.
    */
  def historyAuthentication(
      anchor: ApplicationAnchor,
      transitions: TransitionEvidenceVerifier[IO],
      groups: ConsistencyGroupStore,
      historical: HistoricalCanonicalRuntime[IO],
  ): TransitionHistoryAuthentication[IO] =
    new TransitionHistoryAuthentication[IO]:
      def verifyTransitionHistory(
          state: SafetyState,
          journal: DurableJournal[IO],
      ): Result[IO, Unit] = for
        _ <- originalSelection(
          anchor,
          transitions,
          groups,
          journal,
          state.committed,
        )
        _ <- EitherT.fromEither[IO](
          RuntimeCheck.require(
            historical.base.executionAnchor == anchor && (historical.base.activation.journal eq journal),
            RuntimeFailureCode.StartupIdentityMismatch,
            "historical canonical runtime belongs to another target execution base",
          ),
        )
        actual <- historical.recover
        _      <- EitherT.fromEither[IO](
          RuntimeCheck.require(
            (state.preparations.isEmpty && state.decisions.isEmpty) || actual == historical.base.workingParent,
            RuntimeFailureCode.RecoveryRequired,
            "target application history precedes complete actual old canonical F-to-P materialization",
          ),
        )
      yield ()
