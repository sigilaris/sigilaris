package org.sigilaris.conformance

import java.nio.file.Path

import cats.data.EitherT
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.application.protocol.v2.V2Codecs.given
import org.sigilaris.node.jvm.runtime.application.v2.*
import org.sigilaris.node.jvm.runtime.block.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
import org.sigilaris.node.jvm.storage.file.FileApplicationJournal

/** Actual source F3/P5, complete activation and independent target journals.
  * Every target vote traverses the installed controller and both live signing
  * leases. The old certified suffix is never a target application batch.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
    "org.wartremover.warts.OptionPartial",
    "org.wartremover.warts.Throw",
  ),
)
object V2HistoricalRuntimeFixture:
  import V2HistoricalFixture.{accepted, check, core}
  import V2RequestConformance.*

  final case class Node(
      original: V2HistoricalControllerFixture.Node,
      material: V2RuntimeMaterial,
      journal: DurableJournal[IO],
      active: VerifiedActiveGroup,
      historical: HistoricalCanonicalRuntime[IO],
      safety: JournalSafetyStore[IO],
      finalizer: FinalizedApplicationRuntime[IO],
      controlled: HotStuffControlledSigning[IO],
      voting: DurableApplicationVoting[IO],
      requests: ApplicationRequestVerifier[IO],
      lockConfiguration: V2HistoricalLockFixture.Configuration,
  ):
    def vote(proposal: Proposal, plan: ExecutionPlan): Result[IO, Vote] = for
      request <- requests.verifyProposal(proposal, plan)
      vote    <- finalizer.voteConsensus(voting, request)
    yield vote

  final case class Cluster(
      source: V2HistoricalFixture.Material,
      transition: V2HistoricalTransitionFixture.Transition,
      nodes: Vector[Node],
  ):
    def canonical: Result[IO, Vector[ApplicationAnchor]] =
      nodes.traverse(node =>
        EitherT
          .liftF(node.finalizer.status)
          .flatMap(value =>
            EitherT.fromOption[IO](
              value.canonical,
              V2RuntimeFailure.at(
                RuntimeFailureCode.RecoveryRequired,
                "canonical materialization is not ready",
              ),
            ),
          ),
      )
    def append(heightValue: Long): Result[IO, Proposal] = for
      rows   <- EitherT.liftF(source.retained.get)
      parent <- EitherT.fromOption[IO](
        rows.values.find(_.block.height.toBigNat.toBigInt == heightValue - 1L),
        V2RuntimeFailure.at(
          RuntimeFailureCode.ProofUnavailable,
          "actual target parent missing",
        ),
      )
      certs   <- EitherT.liftF(source.certificates.get)
      justify <- EitherT.fromOption[IO](
        certs.get(parent.proposalId),
        V2RuntimeFailure.at(
          RuntimeFailureCode.ProofUnavailable,
          "actual target parent QC missing",
        ),
      )
      plan =
        if heightValue == 6L then source.source.consensusPlan
        else ExecutionPlan.empty
      planRoot <- core(ExecutionPlan.computeRoot(plan))
      bodyRoot =
        if heightValue == 6L then source.source.bodyRoot
        else
          BlockBody
            .computeBodyRoot(BlockBody[Hash, Hash, Bytes](Set.empty))
            .toOption
            .get
      header = BlockHeader(
        Some(parent.targetBlockId),
        BlockHeight.unsafeFromLong(heightValue),
        StateRoot(source.source.nextStateRoot),
        bodyRoot,
        BlockTimestamp.unsafeFromEpochMillis(3000L + heightValue),
        BlockHeaderVersion.V2,
        Some(planRoot),
      )
      window = HotStuffWindow.unsafe(
        source.source.chain,
        heightValue,
        0L,
        source.source.validators.hash,
      )
      unsigned = UnsignedProposal(
        window,
        nodes.head.material.voter,
        BlockHeader.computeId(header),
        header,
        if heightValue == 6L then source.source.candidate.txSet
        else ProposalTxSet.empty,
        justify,
      )
      proposal <- nodes.head.controlled.proposal(unsigned)
      _        <- source.retain(proposal)
      _        <- observe
      votes    <- nodes.traverse(_.vote(proposal, plan))
      quorum = QuorumCertificate(
        QuorumCertificateSubject(
          window,
          proposal.proposalId,
          proposal.targetBlockId,
        ),
        votes.take(3),
      )
      _ <- source.retain(quorum)
    yield proposal
    def observe: Result[IO, Unit] = for
      tracker <- actualFinality(source)
      _       <- nodes.traverse_(node =>
        EitherT.liftF(node.material.finalization.set(tracker)),
      )
      latest <- EitherT.fromOption[IO](
        tracker.bestFinalized,
        V2RuntimeFailure.at(
          RuntimeFailureCode.ProofUnavailable,
          "actual finalized chain unavailable",
        ),
      )
      _ <- nodes.traverse_(_.finalizer.finalized(latest).void)
    yield ()

  private def actualFinality(
      source: V2HistoricalFixture.Material,
  ): Result[IO, FinalizationTrackerSnapshot] =
    EitherT.liftF(source.retained.get).flatMap { values =>
      val tracker = HotStuffFinalizationTracker.track(values.values.toVector)
      check(
        tracker.safetyFaults.isEmpty,
        "actual retained history has conflicting finality",
      ).as(tracker)
    }

  private def currentMaterial(
      source: V2HistoricalFixture.Material,
      voter: ValidatorId,
  ): IO[V2RuntimeMaterial] = for
    tracker  <- accepted(actualFinality(source))
    finality <- Ref.of[IO, FinalizationTrackerSnapshot](tracker)
    live     <- Ref.of[IO, Option[HotStuffNodeRuntime[IO]]](None)
  yield new V2RuntimeMaterial(
    source.source,
    voter,
    source.retained,
    finality,
    live,
  )

  private def applicationHistory(
      source: V2HistoricalFixture.Material,
      material: V2RuntimeMaterial,
  ): FinalizedApplicationHistory[IO] =
    new FinalizedApplicationHistory[IO]:
      def anchorAncestor(
          anchor: ApplicationAnchor,
          finalized: FinalizedAnchorSuggestion,
      ): Result[IO, VerifiedInitialAnchorAncestor] =
        unavailable(
          "nonempty original ancestors require the handover historical materializer",
        )
      def retained(
          context: DomainContext,
          blockId: Hash,
      ): Result[IO, Option[FinalizedApplicationMaterial]] = for
        _ <- check(context == source.target, "target history context changed")
        rows     <- EitherT.liftF(source.retained.get)
        proposal <- EitherT.fromOption[IO](
          rows.values.find(_.targetBlockId.toUInt256 == blockId),
          V2RuntimeFailure.at(
            RuntimeFailureCode.ProofUnavailable,
            "actual target finalized block missing",
          ),
        )
        _ <- check(
          proposal.block.height.toBigNat.toBigInt >= 6,
          "old profile material cannot be relabelled as a target application",
        )
        finalized <- source.finalized(
          proposal.block.height.toBigNat.toBigInt.longValue,
        )
        plan =
          if proposal.block.height.toBigNat.toBigInt == 6 then
            source.source.consensusPlan
          else ExecutionPlan.empty
        payload <- material.payload(proposal.block.stateRoot.toUInt256)
      yield Some(FinalizedApplicationMaterial(finalized, plan, payload))
      def backfill(
          context: DomainContext,
          blockId: Hash,
      ): Result[IO, Option[FinalizedApplicationMaterial]] =
        retained(context, blockId)
      def latestFinalized(
          context: DomainContext,
      ): Result[IO, Option[FinalizedAnchorSuggestion]] =
        check(
          context == source.target,
          "latest historical finality context changed",
        ) *> actualFinality(source).map(_.bestFinalized)

  private def publication(
      source: V2HistoricalFixture.Material,
      material: V2RuntimeMaterial,
  ): SafetyPublication[IO] =
    new SafetyPublication[IO]:
      def finalizedHeight(context: DomainContext): Result[IO, Height] = for
        _ <- check(
          context == source.target,
          "target safety publication context changed",
        )
        tracker   <- actualFinality(source)
        finalized <- EitherT.fromOption[IO](
          tracker.bestFinalized,
          V2RuntimeFailure.at(
            RuntimeFailureCode.ProofUnavailable,
            "actual source finality missing",
          ),
        )
      yield height(finalized.anchorHeight.toBigNat.toBigInt.longValue)
      def verifyEffectState(request: VerifiedEffectRequest): Result[IO, Unit] =
        unavailable(
          "historical target fixture has consensus-only application sources",
        )
      def verifyConsensusState(
          request: VerifiedConsensusProposal,
      ): Result[IO, Unit] = material.publication.verifyConsensusState(request)

  private def opened(
      source: V2HistoricalFixture.Material,
      original: V2HistoricalControllerFixture.Node,
      transition: V2HistoricalTransitionFixture.Transition,
      journal: DurableJournal[IO],
      material: V2RuntimeMaterial,
      fresh: Boolean,
      faults: JournalFaultInjector[IO],
  ): Resource[IO, Node] =
    val anchor = ApplicationAnchor(
      source.target,
      transition.evidence.continuationParentId,
      height(5L),
      transition.evidence.continuationParentRoot,
    )
    Resource
      .eval(accepted(for
        _ <-
          if fresh then
            V2HistoricalGroupFixture
              .retainOriginal(source, original, transition, original.lifecycle)
          else EitherT.pure[IO, V2RuntimeFailure](())
        groups <- V2HistoricalGroupFixture
          .groups(source, original, transition, original.lifecycle, journal)
        _ <-
          if fresh then
            for
              group      <- groups.capture(transition.verified)
              activation <- EitherT.liftF(
                ActivationStore
                  .authenticated(anchor, transition.verifier, groups, journal),
              )
              prepared <- activation.prepare(transition.verified, group)
              _        <- activation.commit(prepared)
            yield ()
          else EitherT.pure[IO, V2RuntimeFailure](())
        active <- ActivationStore
          .selectedGroup(anchor, transition.verifier, groups, journal)
      yield (groups, active)))
      .flatMap { (groups, active) =>
        val originals = new HistoricalContinuationRepository[IO]:
          def read(digest: Hash): Result[IO, Bytes] =
            transition.original.read(digest)
          def installedGroup(digest: Hash): Result[IO, VerifiedActiveGroup] =
            check(
              digest == transition.verified.digest,
              "installed original handover digest changed",
            ) *> ActivationStore.selectedGroup(
              anchor,
              transition.verifier,
              groups,
              journal,
            )
        val historicalVerifier = HistoricalCanonicalVerifier.authenticated(
          source.consensus,
          originals,
          HistoricalCanonicalCapacity(100L),
        )
        HistoricalCanonicalRuntime
          .resource(
            source.root
              .resolve("historical-canonical-" + original.index.toString),
            transition.verified,
            transition.verifier,
            historicalVerifier,
            16777216L,
            faults,
          )
          .evalMap { historical =>
            accepted(
              for
                lockConfiguration <- V2HistoricalLockFixture
                  .configuration(source)
                _ <- lockConfiguration.verify(historical.base)
                requests     = lockConfiguration.wrap(material.requests)
                applications = ApplicationCommitVerifier.historical(
                  anchor,
                  requests,
                  source.validators,
                  material.states,
                  source.consensus,
                )
                current  = publication(source, material)
                recovery = TransitionRecoveryAuthentication.application(
                  anchor,
                  requests,
                  applications,
                  ActivationStore.historyAuthentication(
                    anchor,
                    transition.verifier,
                    groups,
                    historical,
                  ),
                  None,
                )
                safety <- JournalSafetyStore.open(
                  anchor,
                  journal,
                  current,
                  SafetyProfile(
                    source.source.manifest,
                    lockConfiguration.artifacts,
                  ),
                  recovery,
                  ReservationOrdering.isolated[IO],
                  SafetyCapacity.unbounded,
                )
                finalizer <- EitherT.liftF(
                  FinalizedApplicationRuntime.handover(
                    safety,
                    requests,
                    applications,
                    applicationHistory(source, material),
                    FinalizedApplicationCapacity(100L),
                    historical,
                  ),
                )
                _          <- finalizer.recover
                controlled <- original.target
                  .activate(active, safety, finalizer, current, requests)
                signer <- controlled.applicationSigner(material.voter)
                voting = DurableApplicationVoting
                  .fromStore(safety, signer, lockConfiguration.artifacts)
              yield Node(
                original,
                material,
                journal,
                active,
                historical,
                safety,
                finalizer,
                controlled,
                voting,
                requests,
                lockConfiguration,
              ),
            )
          }
      }

  def fresh(root: Path): Resource[IO, Cluster] =
    fresh(root, JournalFaultInjector.none[IO])

  /** Faults affect the first node's original canonical ledger only. */
  def fresh(
      root: Path,
      firstHistoricalFaults: JournalFaultInjector[IO],
  ): Resource[IO, Cluster] = V2HistoricalFixture
    .material(root)
    .evalTap(source => accepted(V2HistoricalGroupFixture.initialize(source)))
    .flatMap { source =>
      val publishers = V2HistoricalGroupFixture.publishers(source)
      V2HistoricalControllerFixture.nodes(source, publishers).flatMap {
        originals =>
          Resource
            .eval(accepted(for
              _ <- source.buildOld(originals.map(_.signer))
              _ <- V2HistoricalGroupFixture
                .publish(source, originals, publishers)
              transition <- V2HistoricalTransitionFixture
                .prepare(source, originals)
              _ <- V2HistoricalLockFixture.initialize(source)
            yield transition))
            .flatMap { transition =>
              originals
                .traverse { original =>
                  FileApplicationJournal
                    .resource(root.resolve("target-" + original.index.toString))
                    .flatMap { journal =>
                      Resource
                        .eval(
                          accepted(journal.recover) *> currentMaterial(
                            source,
                            source.source.validators.members(original.index).id,
                          ),
                        )
                        .flatMap { material =>
                          opened(
                            source,
                            original,
                            transition,
                            journal,
                            material,
                            true,
                            if original.index == 0 then firstHistoricalFaults
                            else JournalFaultInjector.none[IO],
                          )
                        }
                    }
                }
                .map(nodes => Cluster(source, transition, nodes))
            }
      }
    }

  def reopen(root: Path): Resource[IO, Cluster] =
    reopen(root, JournalFaultInjector.none[IO])

  def reopen(
      root: Path,
      firstHistoricalFaults: JournalFaultInjector[IO],
  ): Resource[IO, Cluster] =
    V2HistoricalFixture.material(root).flatMap { source =>
      val publishers = V2HistoricalGroupFixture.publishers(source)
      (0 to 3).toVector
        .traverse { index =>
          FileApplicationJournal
            .resource(root.resolve("target-" + index.toString))
            .flatMap { journal =>
              Resource
                .eval(
                  accepted(journal.recover) *> currentMaterial(
                    source,
                    source.source.validators.members(index).id,
                  ),
                )
                .flatMap { material =>
                  Resource
                    .eval(
                      accepted(
                        for
                          rows   <- EitherT.liftF(source.retained.get)
                          parent <- EitherT.fromOption[IO](
                            rows.values
                              .find(_.block.height.toBigNat.toBigInt == 5),
                            V2RuntimeFailure.at(
                              RuntimeFailureCode.ProofUnavailable,
                              "original P5 unavailable during controller recovery",
                            ),
                          )
                          configuration <- V2HistoricalLockFixture
                            .configuration(source)
                        yield (
                          ApplicationAnchor(
                            source.target,
                            parent.targetBlockId.toUInt256,
                            height(5L),
                            parent.block.stateRoot.toUInt256,
                          ),
                          configuration.wrap(material.requests),
                        ),
                      ),
                    )
                    .flatMap { (anchor, requests) =>
                      val originalApplication = ControllerApplicationSigning
                        .recovery(anchor, journal, requests)
                      V2HistoricalControllerFixture
                        .node(
                          source,
                          index,
                          ControllerFaultInjector.none,
                          publishers(index),
                          Some(originalApplication),
                        )
                        .map(original => (original, journal, material))
                    }
                }
            }
        }
        .flatMap { recovered =>
          Resource
            .eval(
              accepted(
                V2HistoricalTransitionFixture
                  .prepare(source, recovered.map(_._1)),
              ),
            )
            .flatMap { transition =>
              recovered
                .traverse { (original, journal, material) =>
                  opened(
                    source,
                    original,
                    transition,
                    journal,
                    material,
                    false,
                    if original.index == 0 then firstHistoricalFaults
                    else JournalFaultInjector.none[IO],
                  )
                }
                .map(nodes => Cluster(source, transition, nodes))
            }
        }
    }

  /** Public executable boundary: real keys/QCs, original replay, both physical
    * journals and the same installed controller are used on reopen.
    */
  def run(root: Path): IO[Unit] = for
    _ <- fresh(root).use { cluster =>
      accepted(for
        start <- cluster.canonical
        _     <- check(
          start.forall(value =>
            value.context == cluster.source.old && value.height == height(3L),
          ),
          "activation must publish actual old F3, never certified working P5",
        )
        _ <- V2HistoricalLockFixture.verify(cluster)
        _ <- cluster.nodes.traverse_(node =>
          node.safety.snapshot.flatMap(state =>
            check(
              state.canonical.isEmpty && node.historical.base.workingParent.height == height(
                5L,
              ),
              "target safety is Ready at P5 working base without canonical application",
            ),
          ),
        )
        _    <- cluster.append(6L)
        four <- cluster.canonical
        _    <- check(
          four.forall(_.height == height(4L)),
          "actual old block 4 finality must precede its canonical publication",
        )
        _    <- cluster.append(7L)
        five <- cluster.canonical
        _    <- check(
          five.forall(value =>
            value.context == cluster.source.old && value.height == height(5L),
          ),
          "actual P5/B6/B7 finality must advance the original canonical ledger to P5",
        )
        _ <- cluster.nodes.traverse_(node =>
          node.safety.snapshot.flatMap(state =>
            check(
              state.preparations.isEmpty && state.decisions.isEmpty,
              "old F3-to-P5 must never create target application batches",
            ),
          ),
        )
        _   <- cluster.append(8L)
        six <- cluster.canonical
        _   <- check(
          six.forall(value =>
            value.context == cluster.source.target && value.height == height(
              6L,
            ) && value.stateRoot == cluster.source.source.nextStateRoot,
          ),
          "first target B6 application requires actual cross-boundary finality and complete original P5 publication",
        )
        _ <- cluster.nodes.traverse_(node =>
          node.finalizer.canonicalPayload.flatMap(value =>
            check(
              value.exists((anchor, bytes) =>
                anchor.height == height(6L) && hash(bytes) == anchor.stateRoot,
              ),
              "global canonical payload must match actual target application state",
            ),
          ),
        )
      yield ())
    }
    _ <- reopen(root).use { cluster =>
      accepted(for
        six <- cluster.canonical
        _   <- check(
          six.forall(value =>
            value.context == cluster.source.target && value.height == height(6L),
          ),
          "four original controllers and both journals must recover the same target canonical B6",
        )
        _ <- V2HistoricalLockFixture.verify(cluster)
        _ <- cluster.nodes.traverse_(node =>
          node.safety.snapshot.flatMap(state =>
            check(
              state.locks
                .get(node.lockConfiguration.source.executionId)
                .exists(claim =>
                  claim.lifecycle == ClaimLifecycle.Live && claim.lastInclusionHeight == height(
                    10L,
                  ),
                ),
              "original F3 admitted lock must remain live with its same signed deadline after complete target restart",
            ),
          ),
        )
        _     <- cluster.append(9L)
        seven <- cluster.canonical
        _     <- check(
          seven.forall(value =>
            value.height == height(
              7L,
            ) && value.stateRoot == cluster.source.source.nextStateRoot,
          ),
          "ordinary identity application must progress after full controller/activation/canonical reopen",
        )
        _ <- V2HistoricalLockFixture.rejectSourceLoss(cluster)
      yield ())
    }
  yield ()
