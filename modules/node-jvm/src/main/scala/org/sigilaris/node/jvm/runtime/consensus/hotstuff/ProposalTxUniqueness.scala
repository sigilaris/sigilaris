package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import scala.annotation.tailrec

import cats.syntax.all.*

import org.sigilaris.node.gossip.{ChainId, StableArtifactId}

/** Runtime policy for HotStuff proposal tx uniqueness. */
enum HotStuffProposalTxUniquenessPolicy:
  /** Enforce branch-local uniqueness across unfinalized ancestors. */
  case EnforceUnfinalizedAncestors

  /** Explicit unsafe compatibility mode for legacy runtimes. */
  case UnsafeAllowAncestorTxConflicts

/** Bounds applied while walking unfinalized ancestors. */
final case class HotStuffProposalTxUniquenessBounds(
    maxTraversalDepth: Int,
    maxExcludedTxIds: Int,
)

object HotStuffProposalTxUniquenessBounds:
  val default: HotStuffProposalTxUniquenessBounds =
    HotStuffProposalTxUniquenessBounds(
      maxTraversalDepth = 1024,
      maxExcludedTxIds = 65536,
    )

/** Runtime configuration for proposal tx uniqueness. */
final case class HotStuffProposalTxUniquenessRuntimeConfig(
    policy: HotStuffProposalTxUniquenessPolicy,
    bounds: HotStuffProposalTxUniquenessBounds,
)

object HotStuffProposalTxUniquenessRuntimeConfig:
  val UnsafeAllowAncestorTxConflictsReason: String =
    "proposalTxUniquenessUnsafeAllowAncestorTxConflicts"

  val enforceUnfinalizedAncestors: HotStuffProposalTxUniquenessRuntimeConfig =
    HotStuffProposalTxUniquenessRuntimeConfig(
      policy = HotStuffProposalTxUniquenessPolicy.EnforceUnfinalizedAncestors,
      bounds = HotStuffProposalTxUniquenessBounds.default,
    )

  val unsafeAllowAncestorTxConflicts
      : HotStuffProposalTxUniquenessRuntimeConfig =
    HotStuffProposalTxUniquenessRuntimeConfig(
      policy =
        HotStuffProposalTxUniquenessPolicy.UnsafeAllowAncestorTxConflicts,
      bounds = HotStuffProposalTxUniquenessBounds.default,
    )

  def validateForAutomaticConsensus(
      config: HotStuffProposalTxUniquenessRuntimeConfig,
  ): Either[HotStuffPolicyViolation, Unit] =
    if config.bounds.maxTraversalDepth <= 0 then
      HotStuffPolicyViolation(
        reason = "proposalTxUniquenessBoundsInvalid",
        detail = Some("maxTraversalDepth must be positive"),
      ).asLeft[Unit]
    else if config.bounds.maxExcludedTxIds <= 0 then
      HotStuffPolicyViolation(
        reason = "proposalTxUniquenessBoundsInvalid",
        detail = Some("maxExcludedTxIds must be positive"),
      ).asLeft[Unit]
    else ().asRight[HotStuffPolicyViolation]

/** Cache key for a branch-local exclusion set. */
final case class HotStuffProposalTxUniquenessCacheKey(
    chainId: ChainId,
    parentBlockId: Option[BlockId],
    bestFinalizedBlockId: Option[BlockId],
)

/** Bounded cache for successful ancestor exclusion computations. */
final case class HotStuffProposalTxUniquenessCache(
    entries: Map[
      HotStuffProposalTxUniquenessCacheKey,
      HotStuffProposalTxExclusion,
    ],
):
  def get(
      key: HotStuffProposalTxUniquenessCacheKey,
  ): Option[HotStuffProposalTxExclusion] =
    entries.get(key)

  def updated(
      key: HotStuffProposalTxUniquenessCacheKey,
      exclusion: HotStuffProposalTxExclusion,
  ): HotStuffProposalTxUniquenessCache =
    copy(entries = entries.updated(key, exclusion))

object HotStuffProposalTxUniquenessCache:
  val empty: HotStuffProposalTxUniquenessCache =
    HotStuffProposalTxUniquenessCache(Map.empty)

/** Metadata safe to surface through diagnostics. */
final case class HotStuffProposalTxUniquenessMetadata(
    chainId: ChainId,
    parentBlockId: Option[BlockId],
    bestFinalizedBlockId: Option[BlockId],
    traversedAncestorCount: Int,
    excludedTxIdCount: Int,
    fromCache: Boolean,
)

object HotStuffProposalTxUniquenessMetadata:
  val empty: HotStuffProposalTxUniquenessMetadata =
    HotStuffProposalTxUniquenessMetadata(
      chainId = ChainId.unsafe("unknown"),
      parentBlockId = None,
      bestFinalizedBlockId = None,
      traversedAncestorCount = 0,
      excludedTxIdCount = 0,
      fromCache = false,
    )

/** Canonical ancestor tx-id exclusion view for proposal input. */
final case class HotStuffProposalTxExclusion(
    excludedTxIds: ProposalTxSet,
    metadata: HotStuffProposalTxUniquenessMetadata,
)

object HotStuffProposalTxExclusion:
  val empty: HotStuffProposalTxExclusion =
    HotStuffProposalTxExclusion(
      excludedTxIds = ProposalTxSet.empty,
      metadata = HotStuffProposalTxUniquenessMetadata.empty,
    )

/** Result of checking a proposal against unfinalized ancestor tx ids. */
enum HotStuffProposalTxUniquenessResult:
  case Accepted(exclusion: HotStuffProposalTxExclusion)
  case Conflict(
      conflicts: ProposalTxSet,
      exclusion: HotStuffProposalTxExclusion,
  )
  case Unavailable(
      reason: String,
      detail: Option[String],
      metadata: HotStuffProposalTxUniquenessMetadata,
  )

object HotStuffProposalTxUniquenessResult:
  extension (result: HotStuffProposalTxUniquenessResult)
    def metadata: HotStuffProposalTxUniquenessMetadata =
      result match
        case Accepted(exclusion)     => exclusion.metadata
        case Conflict(_, exclusion)  => exclusion.metadata
        case Unavailable(_, _, data) => data

    def conflictCount: Int =
      result match
        case Conflict(conflicts, _) => conflicts.txIds.size
        case _                      => 0

/** Builds and checks branch-local unfinalized ancestor tx exclusions. */
object HotStuffProposalTxUniqueness:
  def diagnosticDetail(
      reason: String,
      detail: Option[String],
  ): Option[String] =
    detail match
      case Some(value) => Some(reason + ":" + value)
      case None        => Some(reason)

  private final case class AncestorStart(
      parentBlockId: Option[BlockId],
      parentProposalId: Option[ProposalId],
  )

  private final case class ProposalIndex(
      byProposalId: Map[ProposalId, Proposal],
      byChainAndBlockId: Map[(ChainId, BlockId), Proposal],
  )

  def exclusionForParent(
      chainId: ChainId,
      parentBlockId: Option[BlockId],
      proposals: Iterable[Proposal],
      finalization: Map[ChainId, FinalizationTrackerSnapshot],
      bounds: HotStuffProposalTxUniquenessBounds,
      cache: HotStuffProposalTxUniquenessCache,
  ): (HotStuffProposalTxUniquenessCache, HotStuffProposalTxUniquenessResult) =
    val bestFinalizedBlockId =
      finalization.get(chainId).flatMap(_.bestFinalized.map(_.anchorBlockId))
    val key =
      HotStuffProposalTxUniquenessCacheKey(
        chainId = chainId,
        parentBlockId = parentBlockId,
        bestFinalizedBlockId = bestFinalizedBlockId,
      )
    cache.get(key) match
      case Some(cached) =>
        val fromCache =
          cached.copy(metadata = cached.metadata.copy(fromCache = true))
        cache -> HotStuffProposalTxUniquenessResult.Accepted(fromCache)
      case None =>
        val result =
          computeExclusion(
            chainId = chainId,
            start = AncestorStart(
              parentBlockId = parentBlockId,
              parentProposalId = None,
            ),
            proposals = proposals,
            bestFinalizedBlockId = bestFinalizedBlockId,
            bounds = bounds,
          )
        result match
          case HotStuffProposalTxUniquenessResult.Accepted(exclusion) =>
            cache.updated(key, exclusion) -> result
          case _ =>
            cache -> result

  def checkProposal(
      proposal: Proposal,
      proposals: Iterable[Proposal],
      finalization: Map[ChainId, FinalizationTrackerSnapshot],
      bounds: HotStuffProposalTxUniquenessBounds,
      cache: HotStuffProposalTxUniquenessCache,
  ): (HotStuffProposalTxUniquenessCache, HotStuffProposalTxUniquenessResult) =
    val chainId = proposal.window.chainId
    val bestFinalizedBlockId =
      finalization.get(chainId).flatMap(_.bestFinalized.map(_.anchorBlockId))
    val key =
      HotStuffProposalTxUniquenessCacheKey(
        chainId = chainId,
        parentBlockId = proposal.block.parent,
        bestFinalizedBlockId = bestFinalizedBlockId,
      )
    proposalStart(
      proposal = proposal,
      bestFinalizedBlockId = bestFinalizedBlockId,
    ) match
      case Left(unavailable) =>
        cache -> unavailable
      case Right(start) =>
        val proposalIndex = indexProposals(proposals)
        startProposal(
          chainId = chainId,
          start = start,
          bestFinalizedBlockId = bestFinalizedBlockId,
          proposals = proposalIndex,
        ) match
          case Left(unavailable) =>
            cache -> unavailable
          case Right(_) =>
            val (updatedCache, exclusionResult) =
              cache.get(key) match
                case Some(cached) =>
                  val fromCache =
                    cached.copy(
                      metadata = cached.metadata.copy(fromCache = true),
                    )
                  cache -> HotStuffProposalTxUniquenessResult.Accepted(
                    fromCache,
                  )
                case None =>
                  val computed =
                    computeExclusionFromIndex(
                      chainId = chainId,
                      start = start,
                      proposals = proposalIndex,
                      bestFinalizedBlockId = bestFinalizedBlockId,
                      bounds = bounds,
                    )
                  computed match
                    case HotStuffProposalTxUniquenessResult.Accepted(
                          exclusion,
                        ) =>
                      cache.updated(key, exclusion) -> computed
                    case _ =>
                      cache -> computed

            updatedCache -> conflictResult(proposal.txSet, exclusionResult)

  private def computeExclusion(
      chainId: ChainId,
      start: AncestorStart,
      proposals: Iterable[Proposal],
      bestFinalizedBlockId: Option[BlockId],
      bounds: HotStuffProposalTxUniquenessBounds,
  ): HotStuffProposalTxUniquenessResult =
    computeExclusionFromIndex(
      chainId = chainId,
      start = start,
      proposals = indexProposals(proposals),
      bestFinalizedBlockId = bestFinalizedBlockId,
      bounds = bounds,
    )

  private def computeExclusionFromIndex(
      chainId: ChainId,
      start: AncestorStart,
      proposals: ProposalIndex,
      bestFinalizedBlockId: Option[BlockId],
      bounds: HotStuffProposalTxUniquenessBounds,
  ): HotStuffProposalTxUniquenessResult =
    startProposal(
      chainId = chainId,
      start = start,
      bestFinalizedBlockId = bestFinalizedBlockId,
      proposals = proposals,
    ) match
      case Left(unavailable) => unavailable
      case Right(None) =>
        HotStuffProposalTxUniquenessResult.Accepted(
          exclusion(
            chainId = chainId,
            parentBlockId = start.parentBlockId,
            bestFinalizedBlockId = bestFinalizedBlockId,
            traversedAncestorCount = 0,
            txIds = Set.empty,
            fromCache = false,
          ),
        )
      case Right(Some(parentProposal)) =>
        collectAncestors(
          chainId = chainId,
          current = parentProposal,
          startParentBlockId = start.parentBlockId,
          bestFinalizedBlockId = bestFinalizedBlockId,
          byChainAndBlockId = proposals.byChainAndBlockId,
          bounds = bounds,
          traversedAncestorCount = 0,
          txIds = Set.empty,
        )

  private def proposalStart(
      proposal: Proposal,
      bestFinalizedBlockId: Option[BlockId],
  ): Either[
    HotStuffProposalTxUniquenessResult.Unavailable,
    AncestorStart,
  ] =
    val chainId = proposal.window.chainId
    proposal.block.parent match
      case None =>
        AncestorStart(
          parentBlockId = None,
          parentProposalId = None,
        ).asRight[HotStuffProposalTxUniquenessResult.Unavailable]
      case Some(parentBlockId)
          if proposal.justify.subject.blockId === parentBlockId =>
        // Structural proposal validation enforces this direct-parent relation;
        // keep the guard here so direct callers cannot bypass it via cache.
        AncestorStart(
          parentBlockId = Some(parentBlockId),
          parentProposalId = Some(proposal.justify.subject.proposalId),
        ).asRight[HotStuffProposalTxUniquenessResult.Unavailable]
      case Some(_) =>
        unavailable(
          chainId = chainId,
          parentBlockId = proposal.block.parent,
          bestFinalizedBlockId = bestFinalizedBlockId,
          traversedAncestorCount = 0,
          excludedTxIdCount = 0,
          reason = "proposalTxAncestorMismatch",
          detail = Some(proposal.justify.subject.blockId.toHexLower),
        ).asLeft[AncestorStart]

  private def startProposal(
      chainId: ChainId,
      start: AncestorStart,
      bestFinalizedBlockId: Option[BlockId],
      proposals: ProposalIndex,
  ): Either[
    HotStuffProposalTxUniquenessResult.Unavailable,
    Option[Proposal],
  ] =
    start.parentBlockId match
      case None =>
        none[Proposal].asRight[HotStuffProposalTxUniquenessResult.Unavailable]
      case Some(parentBlockId)
          if bestFinalizedBlockId.exists(_ === parentBlockId) =>
        none[Proposal].asRight[HotStuffProposalTxUniquenessResult.Unavailable]
      case Some(parentBlockId) =>
        start.parentProposalId match
          case Some(proposalId) =>
            proposals.byProposalId.get(proposalId) match
              case Some(proposal)
                  if proposal.window.chainId === chainId &&
                    proposal.targetBlockId === parentBlockId =>
                proposal.some
                  .asRight[HotStuffProposalTxUniquenessResult.Unavailable]
              case Some(_) =>
                unavailable(
                  chainId = chainId,
                  parentBlockId = start.parentBlockId,
                  bestFinalizedBlockId = bestFinalizedBlockId,
                  traversedAncestorCount = 0,
                  excludedTxIdCount = 0,
                  reason = "proposalTxAncestorMismatch",
                  detail = Some(proposalId.toHexLower),
                ).asLeft[Option[Proposal]]
              case None =>
                unavailable(
                  chainId = chainId,
                  parentBlockId = start.parentBlockId,
                  bestFinalizedBlockId = bestFinalizedBlockId,
                  traversedAncestorCount = 0,
                  excludedTxIdCount = 0,
                  reason = "proposalTxAncestorUnavailable",
                  detail = Some(proposalId.toHexLower),
                ).asLeft[Option[Proposal]]
          case None =>
            proposals.byChainAndBlockId.get(chainId -> parentBlockId) match
              case Some(proposal) =>
                proposal.some
                  .asRight[HotStuffProposalTxUniquenessResult.Unavailable]
              case None =>
                unavailable(
                  chainId = chainId,
                  parentBlockId = start.parentBlockId,
                  bestFinalizedBlockId = bestFinalizedBlockId,
                  traversedAncestorCount = 0,
                  excludedTxIdCount = 0,
                  reason = "proposalTxAncestorUnavailable",
                  detail = Some(parentBlockId.toHexLower),
                ).asLeft[Option[Proposal]]

  @tailrec
  private def collectAncestors(
      chainId: ChainId,
      current: Proposal,
      startParentBlockId: Option[BlockId],
      bestFinalizedBlockId: Option[BlockId],
      byChainAndBlockId: Map[(ChainId, BlockId), Proposal],
      bounds: HotStuffProposalTxUniquenessBounds,
      traversedAncestorCount: Int,
      txIds: Set[StableArtifactId],
  ): HotStuffProposalTxUniquenessResult =
    if bestFinalizedBlockId.exists(_ === current.targetBlockId) then
      HotStuffProposalTxUniquenessResult.Accepted(
        exclusion(
          chainId = chainId,
          parentBlockId = startParentBlockId,
          bestFinalizedBlockId = bestFinalizedBlockId,
          traversedAncestorCount = traversedAncestorCount,
          txIds = txIds,
          fromCache = false,
        ),
      )
    else if traversedAncestorCount >= bounds.maxTraversalDepth then
      unavailable(
        chainId = chainId,
        parentBlockId = startParentBlockId,
        bestFinalizedBlockId = bestFinalizedBlockId,
        traversedAncestorCount = traversedAncestorCount,
        excludedTxIdCount = txIds.size,
        reason = "proposalTxAncestorTraversalLimitExceeded",
        detail = Some(bounds.maxTraversalDepth.toString),
      )
    else
      val nextTxIds = txIds ++ current.txSet.txIds
      if nextTxIds.sizeCompare(bounds.maxExcludedTxIds) > 0 then
        unavailable(
          chainId = chainId,
          parentBlockId = startParentBlockId,
          bestFinalizedBlockId = bestFinalizedBlockId,
          traversedAncestorCount = traversedAncestorCount + 1,
          excludedTxIdCount = nextTxIds.size,
          reason = "proposalTxAncestorExclusionLimitExceeded",
          detail = Some(bounds.maxExcludedTxIds.toString),
        )
      else
        current.block.parent match
          case None =>
            HotStuffProposalTxUniquenessResult.Accepted(
              exclusion(
                chainId = chainId,
                parentBlockId = startParentBlockId,
                bestFinalizedBlockId = bestFinalizedBlockId,
                traversedAncestorCount = traversedAncestorCount + 1,
                txIds = nextTxIds,
                fromCache = false,
              ),
            )
          case Some(nextBlockId) =>
            byChainAndBlockId.get(chainId -> nextBlockId) match
              case Some(parentProposal) =>
                collectAncestors(
                  chainId = chainId,
                  current = parentProposal,
                  startParentBlockId = startParentBlockId,
                  bestFinalizedBlockId = bestFinalizedBlockId,
                  byChainAndBlockId = byChainAndBlockId,
                  bounds = bounds,
                  traversedAncestorCount = traversedAncestorCount + 1,
                  txIds = nextTxIds,
                )
              case None if bestFinalizedBlockId.exists(_ === nextBlockId) =>
                HotStuffProposalTxUniquenessResult.Accepted(
                  exclusion(
                    chainId = chainId,
                    parentBlockId = startParentBlockId,
                    bestFinalizedBlockId = bestFinalizedBlockId,
                    traversedAncestorCount = traversedAncestorCount + 1,
                    txIds = nextTxIds,
                    fromCache = false,
                  ),
                )
              case None =>
                unavailable(
                  chainId = chainId,
                  parentBlockId = startParentBlockId,
                  bestFinalizedBlockId = bestFinalizedBlockId,
                  traversedAncestorCount = traversedAncestorCount + 1,
                  excludedTxIdCount = nextTxIds.size,
                  reason = "proposalTxAncestorUnavailable",
                  detail = Some(nextBlockId.toHexLower),
                )

  private def conflictResult(
      candidateTxSet: ProposalTxSet,
      exclusionResult: HotStuffProposalTxUniquenessResult,
  ): HotStuffProposalTxUniquenessResult =
    exclusionResult match
      case accepted @ HotStuffProposalTxUniquenessResult.Accepted(exclusion) =>
        val excluded = exclusion.excludedTxIds.txIds.toSet
        val conflicts =
          ProposalTxSet.canonical(
            ProposalTxSet(candidateTxSet.txIds.filter(excluded.contains)),
          )
        if conflicts.txIds.isEmpty then accepted
        else HotStuffProposalTxUniquenessResult.Conflict(conflicts, exclusion)
      case other => other

  private def exclusion(
      chainId: ChainId,
      parentBlockId: Option[BlockId],
      bestFinalizedBlockId: Option[BlockId],
      traversedAncestorCount: Int,
      txIds: Set[StableArtifactId],
      fromCache: Boolean,
  ): HotStuffProposalTxExclusion =
    val txSet = ProposalTxSet.canonical(ProposalTxSet(txIds.toVector))
    HotStuffProposalTxExclusion(
      excludedTxIds = txSet,
      metadata = HotStuffProposalTxUniquenessMetadata(
        chainId = chainId,
        parentBlockId = parentBlockId,
        bestFinalizedBlockId = bestFinalizedBlockId,
        traversedAncestorCount = traversedAncestorCount,
        excludedTxIdCount = txSet.txIds.size,
        fromCache = fromCache,
      ),
    )

  private def unavailable(
      chainId: ChainId,
      parentBlockId: Option[BlockId],
      bestFinalizedBlockId: Option[BlockId],
      traversedAncestorCount: Int,
      excludedTxIdCount: Int,
      reason: String,
      detail: Option[String],
  ): HotStuffProposalTxUniquenessResult.Unavailable =
    HotStuffProposalTxUniquenessResult.Unavailable(
      reason = reason,
      detail = detail,
      metadata = HotStuffProposalTxUniquenessMetadata(
        chainId = chainId,
        parentBlockId = parentBlockId,
        bestFinalizedBlockId = bestFinalizedBlockId,
        traversedAncestorCount = traversedAncestorCount,
        excludedTxIdCount = excludedTxIdCount,
        fromCache = false,
      ),
    )

  private def canonicalBlockProposal(
      proposals: Vector[Proposal],
  ): Option[Proposal] =
    proposals.foldLeft(Option.empty[Proposal]):
      case (None, next) => Some(next)
      case (Some(current), next) =>
        val currentKey = current.proposalId.toHexLower
        val nextKey    = next.proposalId.toHexLower
        if currentKey <= nextKey then Some(current)
        else Some(next)

  private def indexProposals(
      proposals: Iterable[Proposal],
  ): ProposalIndex =
    val proposalVector = proposals.iterator.toVector
    val byProposalId =
      proposalVector.iterator
        .map(proposal => proposal.proposalId -> proposal)
        .toMap
    val byChainAndBlockId =
      proposalVector
        .groupBy(proposal => proposal.window.chainId -> proposal.targetBlockId)
        .flatMap: (key, blockProposals) =>
          canonicalBlockProposal(blockProposals).map(proposal =>
            key -> proposal,
          )
        .toMap
    ProposalIndex(
      byProposalId = byProposalId,
      byChainAndBlockId = byChainAndBlockId,
    )
