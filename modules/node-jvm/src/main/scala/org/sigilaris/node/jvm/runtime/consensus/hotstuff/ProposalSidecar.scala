package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.{Duration, Instant}

import cats.effect.kernel.Sync
import cats.syntax.all.*

import org.sigilaris.node.gossip.*
import org.sigilaris.node.gossip.tx.*

/** Producer-side policy for proposal dependency sidecar scheduling. */
final case class HotStuffProposalSidecarPolicy(
    enabled: Boolean,
    lowLatencySmallProposalAlways: Boolean,
    smallProposalTxLimit: Int,
    maxSidecarArtifactsPerProposal: Int,
    maxSidecarBytesPerPeerPoll: Long,
    maxAmbiguousFalsePositiveRisk: BigDecimal,
    holdProposalWhenRequiredSidecarSkipped: Boolean,
    maxRequiredSidecarHold: Duration,
    maxRequiredSidecarHoldAttempts: Int,
):
  require(
    smallProposalTxLimit >= 0,
    "smallProposalTxLimit must be non-negative",
  )
  require(
    maxSidecarArtifactsPerProposal >= 0,
    "maxSidecarArtifactsPerProposal must be non-negative",
  )
  require(
    maxSidecarBytesPerPeerPoll >= 0L,
    "maxSidecarBytesPerPeerPoll must be non-negative",
  )
  require(
    maxAmbiguousFalsePositiveRisk >= BigDecimal(0),
    "maxAmbiguousFalsePositiveRisk must be non-negative",
  )
  require(
    !maxRequiredSidecarHold.isNegative,
    "maxRequiredSidecarHold must be non-negative",
  )
  require(
    maxRequiredSidecarHoldAttempts >= 0,
    "maxRequiredSidecarHoldAttempts must be non-negative",
  )

/** Companion defaults for proposal dependency sidecar scheduling. */
object HotStuffProposalSidecarPolicy:
  val disabled: HotStuffProposalSidecarPolicy =
    HotStuffProposalSidecarPolicy(
      enabled = false,
      lowLatencySmallProposalAlways = false,
      smallProposalTxLimit = 0,
      maxSidecarArtifactsPerProposal = 0,
      maxSidecarBytesPerPeerPoll = 0L,
      maxAmbiguousFalsePositiveRisk = BigDecimal(0),
      holdProposalWhenRequiredSidecarSkipped = false,
      maxRequiredSidecarHold = Duration.ZERO,
      maxRequiredSidecarHoldAttempts = 0,
    )

  val lowLatencyWarmStaticCluster: HotStuffProposalSidecarPolicy =
    HotStuffProposalSidecarPolicy(
      enabled = true,
      lowLatencySmallProposalAlways = true,
      smallProposalTxLimit = 16,
      maxSidecarArtifactsPerProposal = 128,
      maxSidecarBytesPerPeerPoll = 1024L * 1024L,
      maxAmbiguousFalsePositiveRisk = BigDecimal("0.001"),
      holdProposalWhenRequiredSidecarSkipped = true,
      maxRequiredSidecarHold = Duration.ofMillis(50),
      maxRequiredSidecarHoldAttempts = 1,
    )

/** HotStuff proposal dependency sidecar planner for the composite
  * consensus-plus-application peer artifact stream.
  */
object HotStuffProposalSidecarPlanner:
  def forPeerArtifacts[F[_]: Sync, A](
      config: HotStuffProposalApplicationDependencyRuntimeConfig[F],
      source: GossipArtifactSource[F, HotStuffPeerArtifact[A]],
  ): GossipSidecarPlanner[F, HotStuffPeerArtifact[A]] =
    config.resolver match
      case None =>
        GossipSidecarPlanner.disabled[F, HotStuffPeerArtifact[A]]
      case Some(_) if !config.sidecarPolicy.enabled =>
        GossipSidecarPlanner.disabled[F, HotStuffPeerArtifact[A]]
      case Some(_) =>
        new PeerArtifactPlanner[F, A](config, source)

  private final case class ClassifiedDependency(
      dependency: HotStuffProposalApplicationDependency,
      knownState: GossipArtifactKnownState,
  )

  private final case class ReadDependency[A](
      dependency: HotStuffProposalApplicationDependency,
      available: Option[AvailableGossipEvent[HotStuffPeerArtifact[A]]],
  )

  private final case class DependencyReadKey(
      chainId: ChainId,
      topic: GossipTopic,
  )

  private final class PeerArtifactPlanner[F[_]: Sync, A](
      config: HotStuffProposalApplicationDependencyRuntimeConfig[F],
      source: GossipArtifactSource[F, HotStuffPeerArtifact[A]],
  ) extends GossipSidecarPlanner[F, HotStuffPeerArtifact[A]]:
    private val policy = config.sidecarPolicy

    override def plan(
        now: Instant,
        sessionState: TxProducerSessionState,
        chainTopic: ChainTopic,
        candidates: Vector[AvailableGossipEvent[HotStuffPeerArtifact[A]]],
        remainingCapacity: Int,
        existingHold: Option[GossipSidecarHoldState],
    ): F[GossipSidecarDecision[HotStuffPeerArtifact[A]]] =
      if chainTopic.topic =!= GossipTopic.consensusProposal ||
        remainingCapacity <= 0
      then
        GossipSidecarDecision
          .PassThrough[HotStuffPeerArtifact[A]]()
          .pure[F]
      else
        candidates.headOption match
          case None =>
            GossipSidecarDecision
              .PassThrough[HotStuffPeerArtifact[A]]()
              .pure[F]
          case Some(candidate) =>
            candidate.event.payload match
              case HotStuffPeerArtifact.Consensus(
                    HotStuffGossipArtifact.ProposalArtifact(proposal),
                  ) =>
                planProposal(
                  now = now,
                  sessionState = sessionState,
                  chainTopic = chainTopic,
                  candidate = candidate,
                  proposal = proposal,
                  remainingCapacity = remainingCapacity,
                )(
                  existingHold = existingHold,
                )
              case _ =>
                GossipSidecarDecision
                  .Fallback(candidate.event, Vector.empty)
                  .pure[F]

    private def planProposal(
        now: Instant,
        sessionState: TxProducerSessionState,
        chainTopic: ChainTopic,
        candidate: AvailableGossipEvent[HotStuffPeerArtifact[A]],
        proposal: Proposal,
        remainingCapacity: Int,
    )(
        existingHold: Option[GossipSidecarHoldState],
    ): F[GossipSidecarDecision[HotStuffPeerArtifact[A]]] =
      HotStuffProposalApplicationDependencyRuntimeConfig
        .resolveForProducer(config, proposal)
        .flatMap:
          case HotStuffProposalApplicationDependencyResolution.Resolved(
                dependencies,
              ) =>
            planResolved(
              now = now,
              sessionState = sessionState,
              chainTopic = chainTopic,
              proposalEvent = candidate.event,
              proposal = proposal,
              dependencies = dependencies,
              remainingCapacity = remainingCapacity,
              existingHold = existingHold,
            )
          case HotStuffProposalApplicationDependencyResolution.ResolverRejected(
                _,
                reason,
                detail,
              ) =>
            val diagnostic = diagnosticFor(
              reason =
                GossipSidecarDiagnosticReason.ProposalDependencyResolverRejected,
              chainTopic = chainTopic,
              proposalId = candidate.event.id,
              detail = renderResolverDetail(reason, detail),
            )
            GossipSidecarDecision
              .Fallback(candidate.event, Vector(diagnostic))
              .pure[F]
          case HotStuffProposalApplicationDependencyResolution.ResolverFailed(
                _,
                detail,
              ) =>
            val diagnostic = diagnosticFor(
              reason =
                GossipSidecarDiagnosticReason.ProposalDependencyResolverFailed,
              chainTopic = chainTopic,
              proposalId = candidate.event.id,
              detail = detail,
            )
            GossipSidecarDecision
              .Fallback(candidate.event, Vector(diagnostic))
              .pure[F]

    private def planResolved(
        now: Instant,
        sessionState: TxProducerSessionState,
        chainTopic: ChainTopic,
        proposalEvent: GossipEvent[HotStuffPeerArtifact[A]],
        proposal: Proposal,
        dependencies: Vector[HotStuffProposalApplicationDependency],
        remainingCapacity: Int,
        existingHold: Option[GossipSidecarHoldState],
    ): F[GossipSidecarDecision[HotStuffPeerArtifact[A]]] =
      val proposalIsSmall =
        proposal.txSet.txIds.sizeCompare(policy.smallProposalTxLimit) <= 0
      val selected =
        dependencies
          .map(dependency =>
            ClassifiedDependency(
              dependency = dependency,
              knownState = classify(sessionState, dependency, now),
            ),
          )
          .filter(shouldSend(sessionState, _, proposalIsSmall))
          .sortBy(entry => criticalityRank(entry.dependency.criticality))
          .foldLeft(Vector.empty[ClassifiedDependency]): (acc, entry) =>
            if acc.exists(existing =>
                sameDependencyArtifact(existing.dependency, entry.dependency),
              )
            then acc
            else acc :+ entry

      if selected.isEmpty then
        GossipSidecarDecision
          .Fallback(proposalEvent, Vector.empty)
          .pure[F]
      else
        selectWithinCaps(
          selected = selected,
          chainTopic = chainTopic,
          proposalId = proposalEvent.id,
          remainingCapacity = remainingCapacity,
        ) match
          case Left((reason, diagnostics)) =>
            holdOrFallback(
              now = now,
              chainTopic = chainTopic,
              proposalEvent = proposalEvent,
              reason = reason,
              diagnostics = diagnostics,
              existingHold = existingHold,
            ).pure[F]
          case Right((capped, capDiagnostics)) =>
            readDependencies(capped.map(_.dependency)).map: read =>
              val (
                sizeChecked,
                actualByteDiagnostics,
                requiredActualByteExceeded,
              ) =
                enforceActualByteCap(
                  read = read,
                  chainTopic = chainTopic,
                  proposalId = proposalEvent.id,
                )
              val diagnosticsBase = capDiagnostics ++ actualByteDiagnostics
              if requiredActualByteExceeded then
                holdOrFallback(
                  now = now,
                  chainTopic = chainTopic,
                  proposalEvent = proposalEvent,
                  reason = GossipSidecarDiagnosticReason.SidecarCapExceeded,
                  diagnostics = diagnosticsBase,
                  existingHold = existingHold,
                )
              else
                val missingRequired =
                  sizeChecked.filter(entry =>
                    entry.available.isEmpty && isRequired(entry.dependency),
                  )
                if missingRequired.nonEmpty then
                  val diagnostics =
                    diagnosticsBase :+
                      diagnosticFor(
                        reason =
                          GossipSidecarDiagnosticReason.SidecarLocalArtifactUnavailable,
                        chainTopic = chainTopic,
                        proposalId = proposalEvent.id,
                        detail = Some(
                          missingRequired
                            .map(_.dependency.artifactId.toHexLower)
                            .mkString(","),
                        ),
                      )
                  holdOrFallback(
                    now = now,
                    chainTopic = chainTopic,
                    proposalEvent = proposalEvent,
                    reason =
                      GossipSidecarDiagnosticReason.SidecarLocalArtifactUnavailable,
                    diagnostics = diagnostics,
                    existingHold = existingHold,
                  )
                else
                  val sidecars = sizeChecked.flatMap(_.available.map(_.event))
                  if sidecars.isEmpty then
                    val diagnostics =
                      diagnosticsBase :+
                        diagnosticFor(
                          reason =
                            GossipSidecarDiagnosticReason.SidecarFallback,
                          chainTopic = chainTopic,
                          proposalId = proposalEvent.id,
                          detail = Some("noSidecarsAvailable"),
                        )
                    GossipSidecarDecision.Fallback(proposalEvent, diagnostics)
                  else
                    val diagnostics =
                      diagnosticsBase :+
                        diagnosticFor(
                          reason =
                            GossipSidecarDiagnosticReason.SidecarSelected,
                          chainTopic = chainTopic,
                          proposalId = proposalEvent.id,
                          detail = Some("count=" + sidecars.size.toString),
                        )
                    GossipSidecarDecision.Emit(
                      sidecars = sidecars,
                      proposal = proposalEvent,
                      diagnostics = diagnostics,
                    )

    private def classify(
        sessionState: TxProducerSessionState,
        dependency: HotStuffProposalApplicationDependency,
        now: Instant,
    ): GossipArtifactKnownState =
      val chainTopic =
        ChainTopic(dependency.exactScope.chainId, dependency.topic)
      GossipArtifactKnownStateClassifier.classify(
        artifactId = dependency.artifactId,
        exactKnownIds = sessionState.exactKnownScopeIds.getOrElse(
          dependency.exactScope,
          Set.empty[StableArtifactId],
        ),
        bloomFilter = sessionState.filters.get(chainTopic),
        bloomReceivedAt = sessionState.filterReceiptTimes.get(chainTopic),
        now = now,
        policy = config.knownStatePolicy,
      )

    private def shouldSend(
        sessionState: TxProducerSessionState,
        entry: ClassifiedDependency,
        proposalIsSmall: Boolean,
    ): Boolean =
      entry.knownState match
        case GossipArtifactKnownState.Known   => false
        case GossipArtifactKnownState.Missing => true
        case GossipArtifactKnownState.Ambiguous(
              GossipArtifactAmbiguityReason.BloomMatched,
            ) =>
          policy.lowLatencySmallProposalAlways && proposalIsSmall ||
          ambiguousRisk(sessionState, entry.dependency) >
            policy.maxAmbiguousFalsePositiveRisk
        case GossipArtifactKnownState.Ambiguous(_) =>
          true

    private def ambiguousRisk(
        sessionState: TxProducerSessionState,
        dependency: HotStuffProposalApplicationDependency,
    ): BigDecimal =
      val chainTopic =
        ChainTopic(dependency.exactScope.chainId, dependency.topic)
      sessionState.filters.get(chainTopic) match
        case Some(filter) if filter.bitset.nonEmpty && filter.numHashes > 0 =>
          BigDecimal(
            Math.pow(
              TxBloomFilterSupport.bitDensity(filter),
              filter.numHashes.toDouble,
            ),
          )
        case _ =>
          BigDecimal(1)

    private def selectWithinCaps(
        selected: Vector[ClassifiedDependency],
        chainTopic: ChainTopic,
        proposalId: StableArtifactId,
        remainingCapacity: Int,
    ): Either[
      (GossipSidecarDiagnosticReason, Vector[GossipSidecarDiagnostic]),
      (Vector[ClassifiedDependency], Vector[GossipSidecarDiagnostic]),
    ] =
      val sidecarCapacity =
        (remainingCapacity - 1).max(0)
      val artifactLimit =
        policy.maxSidecarArtifactsPerProposal.min(sidecarCapacity)
      val countCapped =
        selected.take(artifactLimit)
      val countSkipped =
        selected.drop(artifactLimit)
      val countDiagnostics =
        if countSkipped.isEmpty then Vector.empty[GossipSidecarDiagnostic]
        else
          Vector(
            diagnosticFor(
              reason = GossipSidecarDiagnosticReason.SidecarCapExceeded,
              chainTopic = chainTopic,
              proposalId = proposalId,
              detail = Some(
                "artifactCount=" + selected.size.toString +
                  " max=" + artifactLimit.toString +
                  " remainingCapacity=" + remainingCapacity.toString,
              ),
            ),
          )

      if countSkipped.exists(entry => isRequired(entry.dependency)) then
        (
          GossipSidecarDiagnosticReason.SidecarCapExceeded,
          countDiagnostics,
        ).asLeft[
          (
              Vector[ClassifiedDependency],
              Vector[
                GossipSidecarDiagnostic,
              ],
          ),
        ]
      else
        val (byteCapped, _, byteSkipped) =
          countCapped.foldLeft(
            (
              Vector.empty[ClassifiedDependency],
              0L,
              Vector.empty[ClassifiedDependency],
            ),
          ): (acc, entry) =>
            val (included, usedBytes, skipped) = acc
            val estimatedBytes = estimatedBytesOf(entry.dependency)
            val fits =
              estimatedBytes <= policy.maxSidecarBytesPerPeerPoll - usedBytes
            if fits then
              (included :+ entry, usedBytes + estimatedBytes, skipped)
            else (included, usedBytes, skipped :+ entry)
        val byteDiagnostics =
          if byteSkipped.isEmpty then Vector.empty[GossipSidecarDiagnostic]
          else
            Vector(
              diagnosticFor(
                reason = GossipSidecarDiagnosticReason.SidecarCapExceeded,
                chainTopic = chainTopic,
                proposalId = proposalId,
                detail = Some(
                  "byteCap=" + policy.maxSidecarBytesPerPeerPoll.toString,
                ),
              ),
            )
        if byteSkipped.exists(entry => isRequired(entry.dependency)) then
          (
            GossipSidecarDiagnosticReason.SidecarCapExceeded,
            countDiagnostics ++ byteDiagnostics,
          ).asLeft[
            (
                Vector[ClassifiedDependency],
                Vector[
                  GossipSidecarDiagnostic,
                ],
            ),
          ]
        else (byteCapped -> (countDiagnostics ++ byteDiagnostics)).asRight

    private def readDependencies(
        dependencies: Vector[HotStuffProposalApplicationDependency],
    ): F[Vector[ReadDependency[A]]] =
      val batchedIds =
        dependencies.foldLeft(
          Map.empty[DependencyReadKey, Vector[StableArtifactId]],
        ): (acc, dependency) =>
          val key = DependencyReadKey(
            chainId = dependency.exactScope.chainId,
            topic = dependency.topic,
          )
          acc.updated(
            key,
            acc.getOrElse(key, Vector.empty[StableArtifactId]) :+
              dependency.artifactId,
          )

      batchedIds.toVector
        .traverse: (key, ids) =>
          source
            .readByIds(
              chainId = key.chainId,
              topic = key.topic,
              ids = ids.distinct,
            )
            .map: events =>
              key -> events.iterator.map(event => event.event.id -> event).toMap
        .map: batches =>
          val byBatch = batches.toMap
          dependencies.map: dependency =>
            val key = DependencyReadKey(
              chainId = dependency.exactScope.chainId,
              topic = dependency.topic,
            )
            ReadDependency(
              dependency = dependency,
              available = byBatch.get(key).flatMap(_.get(dependency.artifactId)),
            )

    private def enforceActualByteCap(
        read: Vector[ReadDependency[A]],
        chainTopic: ChainTopic,
        proposalId: StableArtifactId,
    ): (
        Vector[ReadDependency[A]],
        Vector[GossipSidecarDiagnostic],
        Boolean,
    ) =
      val (included, _, skipped) =
        read.foldLeft(
          (
            Vector.empty[ReadDependency[A]],
            0L,
            Vector.empty[(ReadDependency[A], Option[Long])],
          ),
        ): (acc, entry) =>
          val (kept, usedBytes, skippedAcc) = acc
          entry.available match
            case None => (kept :+ entry, usedBytes, skippedAcc)
            case Some(available) =>
              val actualBytes = actualBytesOf(entry.dependency, available)
              actualBytes match
                case Some(bytes)
                    if bytes <= policy.maxSidecarBytesPerPeerPoll - usedBytes =>
                  (kept :+ entry, usedBytes + bytes, skippedAcc)
                case _ =>
                  (
                    kept,
                    usedBytes,
                    skippedAcc :+ (entry -> actualBytes),
                  )
      val diagnostics =
        if skipped.isEmpty then Vector.empty[GossipSidecarDiagnostic]
        else
          Vector(
            diagnosticFor(
              reason = GossipSidecarDiagnosticReason.SidecarCapExceeded,
              chainTopic = chainTopic,
              proposalId = proposalId,
              detail = Some(
                "byteCap=" + policy.maxSidecarBytesPerPeerPoll.toString +
                  " skipped=" + skipped
                    .map: (entry, actualBytes) =>
                      entry.dependency.artifactId.toHexLower + ":" +
                        actualBytes.fold("unknown")(_.toString)
                    .mkString(","),
              ),
            ),
          )
      (
        included,
        diagnostics,
        skipped.exists((entry, _) => isRequired(entry.dependency)),
      )

    private def actualBytesOf(
        dependency: HotStuffProposalApplicationDependency,
        available: AvailableGossipEvent[HotStuffPeerArtifact[A]],
    ): Option[Long] =
      available.encodedSizeBytes.orElse(dependency.estimatedBytes)

    private def holdOrFallback(
        now: Instant,
        chainTopic: ChainTopic,
        proposalEvent: GossipEvent[HotStuffPeerArtifact[A]],
        reason: GossipSidecarDiagnosticReason,
        diagnostics: Vector[GossipSidecarDiagnostic],
        existingHold: Option[GossipSidecarHoldState],
    ): GossipSidecarDecision[HotStuffPeerArtifact[A]] =
      if shouldHold(now, proposalEvent.id, existingHold) then
        val holdDiagnostic =
          diagnosticFor(
            reason = GossipSidecarDiagnosticReason.SidecarHeld,
            chainTopic = chainTopic,
            proposalId = proposalEvent.id,
            detail = Some(reasonLabel(reason)),
          )
        GossipSidecarDecision.Hold(
          proposalId = proposalEvent.id,
          reason = reason,
          diagnostics = diagnostics :+ holdDiagnostic,
        )
      else
        val fallbackDiagnostic =
          diagnosticFor(
            reason = GossipSidecarDiagnosticReason.SidecarFallback,
            chainTopic = chainTopic,
            proposalId = proposalEvent.id,
            detail = Some(reasonLabel(reason)),
          )
        GossipSidecarDecision.Fallback(
          proposal = proposalEvent,
          diagnostics = diagnostics :+ fallbackDiagnostic,
        )

    private def shouldHold(
        now: Instant,
        proposalId: StableArtifactId,
        existingHold: Option[GossipSidecarHoldState],
    ): Boolean =
      policy.holdProposalWhenRequiredSidecarSkipped &&
        policy.maxRequiredSidecarHoldAttempts > 0 &&
        existingHold.forall: hold =>
          hold.proposalId =!= proposalId ||
            (hold.attempts < policy.maxRequiredSidecarHoldAttempts &&
              now.isBefore(
                hold.firstHeldAt.plus(policy.maxRequiredSidecarHold),
              ))

    private def estimatedBytesOf(
        dependency: HotStuffProposalApplicationDependency,
    ): Long =
      dependency.estimatedBytes.getOrElse(policy.maxSidecarBytesPerPeerPoll)

    private def criticalityRank(
        criticality: HotStuffProposalDependencyCriticality,
    ): Int =
      criticality match
        case HotStuffProposalDependencyCriticality.RequiredForVote => 0
        case HotStuffProposalDependencyCriticality.HelpfulForMaterialization =>
          1

    private def isRequired(
        dependency: HotStuffProposalApplicationDependency,
    ): Boolean =
      dependency.criticality match
        case HotStuffProposalDependencyCriticality.RequiredForVote => true
        case HotStuffProposalDependencyCriticality.HelpfulForMaterialization =>
          false

    private def sameDependencyArtifact(
        left: HotStuffProposalApplicationDependency,
        right: HotStuffProposalApplicationDependency,
    ): Boolean =
      left.exactScope.chainId === right.exactScope.chainId &&
        left.topic === right.topic &&
        left.artifactId === right.artifactId

    private def diagnosticFor(
        reason: GossipSidecarDiagnosticReason,
        chainTopic: ChainTopic,
        proposalId: StableArtifactId,
        detail: Option[String],
    ): GossipSidecarDiagnostic =
      GossipSidecarDiagnostic(
        reason = reason,
        chainTopic = chainTopic,
        proposalId = proposalId,
        detail = detail,
      )

    private def reasonLabel(
        reason: GossipSidecarDiagnosticReason,
    ): String =
      reason match
        case GossipSidecarDiagnosticReason.SidecarSelected =>
          "sidecarSelected"
        case GossipSidecarDiagnosticReason.SidecarHeld =>
          "sidecarHeld"
        case GossipSidecarDiagnosticReason.SidecarFallback =>
          "sidecarFallback"
        case GossipSidecarDiagnosticReason.SidecarCapExceeded =>
          "sidecarCapExceeded"
        case GossipSidecarDiagnosticReason.SidecarLocalArtifactUnavailable =>
          "sidecarLocalArtifactUnavailable"
        case GossipSidecarDiagnosticReason.ProposalDependencyResolverRejected =>
          "proposalDependencyResolverRejected"
        case GossipSidecarDiagnosticReason.ProposalDependencyResolverFailed =>
          "proposalDependencyResolverFailed"

    private def renderResolverDetail(
        reason: String,
        detail: Option[String],
    ): Option[String] =
      detail.fold(Some(reason))(value => Some(reason + ":" + value))
