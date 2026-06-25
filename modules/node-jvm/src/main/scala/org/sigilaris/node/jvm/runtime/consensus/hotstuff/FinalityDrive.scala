package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Duration

import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.jvm.runtime.block.{BlockHeight, BlockId}
import org.sigilaris.node.gossip.ChainId

/** Runtime-owned bounded descendant drive policy.
  *
  * The policy is liveness-only. It never changes vote safety, QC acceptance,
  * finalization rules, or branch validation.
  */
final case class HotStuffFinalityDrivePolicy private (
    enabled: Boolean,
    maxDescendantDepth: Int,
    maxAttemptsPerAnchor: Int,
    maxElapsed: Duration,
)

/** Certified tx-bearing anchor selected for bounded descendant finality drive.
  */
final case class HotStuffFinalityDriveAnchor(
    chainId: ChainId,
    proposalId: ProposalId,
    blockId: BlockId,
    height: BlockHeight,
    window: HotStuffWindow,
    txIdCount: Int,
)

/** Provider-visible finality-drive hint for a proposal input request. */
final case class HotStuffProposalInputFinalityDrive(
    anchor: HotStuffFinalityDriveAnchor,
    descendantDepthAfterProposal: Int,
    maxDescendantDepth: Int,
    attempt: Int,
    maxAttemptsPerAnchor: Int,
    elapsed: Duration,
)

private[hotstuff] final case class HotStuffFinalityDriveCandidate(
    anchor: HotStuffFinalityDriveAnchor,
    descendantDepthAfterProposal: Int,
)

private[hotstuff] object HotStuffFinalityDriveCandidate:
  def fromRequest(
      request: HotStuffProposalInputRequest,
      maxDescendantDepth: Int,
  ): Option[HotStuffFinalityDriveCandidate] =
    fromBranch(
      branchContext = request.branchContext,
      requestHeight = request.height,
      maxDescendantDepth = maxDescendantDepth,
    )

  def fromBranch(
      branchContext: HotStuffProposalInputBranchContext,
      requestHeight: BlockHeight,
      maxDescendantDepth: Int,
  ): Option[HotStuffFinalityDriveCandidate] =
    if branchContext.complete then
      branchContext.ancestors
        .flatMap: ancestor =>
          descendantDepthAfterProposal(
            requestHeight = requestHeight,
            ancestorHeight = ancestor.height,
          ).filter(depth =>
            depth <= maxDescendantDepth &&
              ancestor.txSet.txIds.nonEmpty,
          ).map: depth =>
            HotStuffFinalityDriveCandidate(
              anchor = HotStuffFinalityDriveAnchor(
                chainId = ancestor.chainId,
                proposalId = ancestor.proposalId,
                blockId = ancestor.blockId,
                height = ancestor.height,
                window = ancestor.window,
                txIdCount = ancestor.txSet.txIds.length,
              ),
              descendantDepthAfterProposal = depth,
            )
        .headOption
    else None

  private[hotstuff] def descendantDepthAfterProposal(
      requestHeight: BlockHeight,
      ancestorHeight: BlockHeight,
  ): Option[Int] =
    val delta =
      requestHeight.toBigNat.toBigInt - ancestorHeight.toBigNat.toBigInt
    Option.when(delta > BigInt(0) && delta.isValidInt)(delta.toInt)

/** Companion for `HotStuffFinalityDrivePolicy`. */
@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object HotStuffFinalityDrivePolicy:
  val ConsensusFinalizationDescendantDepth: Int = 2

  val disabled: HotStuffFinalityDrivePolicy =
    new HotStuffFinalityDrivePolicy(
      enabled = false,
      maxDescendantDepth = ConsensusFinalizationDescendantDepth,
      maxAttemptsPerAnchor = 0,
      maxElapsed = Duration.ZERO,
    )

  def enabled(
      maxDescendantDepth: Int,
      maxAttemptsPerAnchor: Int,
      maxElapsed: Duration,
  ): Either[String, HotStuffFinalityDrivePolicy] =
    Either
      .cond(
        maxDescendantDepth >= ConsensusFinalizationDescendantDepth,
        (),
        ss"maxDescendantDepth must be at least ${ConsensusFinalizationDescendantDepth.toString}",
      )
      .flatMap(_ =>
        Either.cond(
          maxAttemptsPerAnchor > 0,
          (),
          "maxAttemptsPerAnchor must be positive",
        ),
      )
      .flatMap(_ =>
        Either.cond(
          !maxElapsed.isNegative && !maxElapsed.isZero,
          (),
          "maxElapsed must be positive",
        ),
      )
      .map: _ =>
        new HotStuffFinalityDrivePolicy(
          enabled = true,
          maxDescendantDepth = maxDescendantDepth,
          maxAttemptsPerAnchor = maxAttemptsPerAnchor,
          maxElapsed = maxElapsed,
        )

  def unsafeEnabled(
      maxDescendantDepth: Int,
      maxAttemptsPerAnchor: Int,
      maxElapsed: Duration,
  ): HotStuffFinalityDrivePolicy =
    enabled(
      maxDescendantDepth = maxDescendantDepth,
      maxAttemptsPerAnchor = maxAttemptsPerAnchor,
      maxElapsed = maxElapsed,
    ) match
      case Right(policy) => policy
      case Left(error)   => throw new IllegalArgumentException(error)

  val lowLatencyWarmStaticCluster: HotStuffFinalityDrivePolicy =
    unsafeEnabled(
      maxDescendantDepth = ConsensusFinalizationDescendantDepth,
      maxAttemptsPerAnchor = 4,
      maxElapsed = Duration.ofMillis(500),
    )
