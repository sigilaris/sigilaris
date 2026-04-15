package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Duration

import cats.Eq
import cats.syntax.all.*

import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.datatype.{BigNat, UInt256}
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.core.failure.{
  FailureDiagnosticFamily,
  StructuredFailureDiagnostic,
}
import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.gossip.{
  ChainId,
  GossipFieldValidation,
  PeerIdentity,
}

/** Unique identifier for a consensus proposal, represented as a 256-bit hash. */
opaque type ProposalId = UInt256

/** Companion for `ProposalId`. */
object ProposalId:
  private def renderHex(
      value: UInt256,
  ): String =
    value.bytes.toHex

  def apply(value: UInt256): ProposalId = value

  def fromHex(
      value: String,
  ): Either[String, ProposalId] =
    UInt256.fromHex(value).left.map(_.toString).map(apply)

  extension (proposalId: ProposalId)
    def toUInt256: UInt256 = proposalId
    def toHexLower: String = renderHex(proposalId)

  given ByteEncoder[ProposalId] = ByteEncoder[UInt256].contramap(_.toUInt256)
  given Eq[ProposalId]          = Eq.by(_.toUInt256)

/** Unique identifier for a consensus vote, represented as a 256-bit hash. */
opaque type VoteId = UInt256

/** Companion for `VoteId`. */
object VoteId:
  private def renderHex(
      value: UInt256,
  ): String =
    value.bytes.toHex

  def apply(value: UInt256): VoteId = value

  def fromHex(
      value: String,
  ): Either[String, VoteId] =
    UInt256.fromHex(value).left.map(_.toString).map(apply)

  extension (voteId: VoteId)
    def toUInt256: UInt256 = voteId
    def toHexLower: String = renderHex(voteId)

  given ByteEncoder[VoteId] = ByteEncoder[UInt256].contramap(_.toUInt256)
  given Eq[VoteId]          = Eq.by(_.toUInt256)

/** A validator's unique identity within the consensus protocol. */
opaque type ValidatorId = String

/** Companion for `ValidatorId`. */
@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object ValidatorId:
  /** Parses a validator ID from a string, validating the format. */
  def parse(
      value: String,
  ): Either[String, ValidatorId] =
    GossipFieldValidation
      .validateLowerAsciiToken("validatorId", value)
      .map(_ => value)

  /** Parses a validator ID, throwing on invalid input. Intended for tests. */
  def unsafe(
      value: String,
  ): ValidatorId =
    parse(value) match
      case Right(validatorId) => validatorId
      case Left(error)        => throw new IllegalArgumentException(error)

  extension (validatorId: ValidatorId) def value: String = validatorId

  given ByteEncoder[ValidatorId] =
    ByteEncoder[Utf8].contramap(id => Utf8(id.value))
  given Eq[ValidatorId] = Eq.by(_.value)

/** The canonical hash of a validator set, used for cross-referencing between windows and sets. */
opaque type ValidatorSetHash = UInt256

/** Companion for `ValidatorSetHash`. */
object ValidatorSetHash:
  private def renderHex(
      value: UInt256,
  ): String =
    value.bytes.toHex

  def apply(value: UInt256): ValidatorSetHash = value

  def fromHex(
      value: String,
  ): Either[String, ValidatorSetHash] =
    UInt256.fromHex(value).left.map(_.toString).map(apply)

  extension (validatorSetHash: ValidatorSetHash)
    def toUInt256: UInt256 = validatorSetHash
    def toHexLower: String = renderHex(validatorSetHash)

  given ByteEncoder[ValidatorSetHash] =
    ByteEncoder[UInt256].contramap(_.toUInt256)
  given Eq[ValidatorSetHash] = Eq.by(_.toUInt256)

/** The block height within HotStuff consensus, represented as a non-negative big integer. */
opaque type HotStuffHeight = BigNat

/** Companion for `HotStuffHeight`. */
@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object HotStuffHeight:
  /** The genesis height (zero). */
  val Genesis: HotStuffHeight = BigNat.Zero

  def apply(
      value: BigNat,
  ): HotStuffHeight = value

  def fromLong(
      value: Long,
  ): Either[String, HotStuffHeight] =
    BigNat.fromBigInt(BigInt(value)) match
      case Left(error)   => error.asLeft[HotStuffHeight]
      case Right(bignat) => apply(bignat).asRight[String]

  def unsafeFromLong(
      value: Long,
  ): HotStuffHeight =
    fromLong(value) match
      case Right(height) => height
      case Left(error)   => throw new IllegalArgumentException(error)

  extension (height: HotStuffHeight)
    def toBigNat: BigNat = height
    def render: String   = height.toBigNat.toBigInt.toString
    def <(other: HotStuffHeight): Boolean =
      BigNat.bignatOrdering.lt(height.toBigNat, other.toBigNat)
    def <=(other: HotStuffHeight): Boolean =
      BigNat.bignatOrdering.lteq(height.toBigNat, other.toBigNat)
    def >(other: HotStuffHeight): Boolean =
      BigNat.bignatOrdering.gt(height.toBigNat, other.toBigNat)
    def >=(other: HotStuffHeight): Boolean =
      BigNat.bignatOrdering.gteq(height.toBigNat, other.toBigNat)
    def next: HotStuffHeight = apply:
      BigNat.add(height.toBigNat, BigNat.One)
    def +(delta: Long): Either[String, HotStuffHeight] =
      Either
        .cond(
          delta >= 0L,
          (),
          "height delta must be non-negative",
        )
        .map: _ =>
          apply:
            BigNat.add(height.toBigNat, BigNat.unsafeFromLong(delta))

    def unsafeAdd(delta: Long): HotStuffHeight =
      height.+(delta) match
        case Right(updated) => updated
        case Left(error)    => throw new IllegalArgumentException(error)

  given ByteEncoder[HotStuffHeight] = ByteEncoder[BigNat].contramap(_.toBigNat)
  given Eq[HotStuffHeight]          = Eq.by(_.toBigNat)
  given Ordering[HotStuffHeight] =
    Ordering.by[HotStuffHeight, BigNat](_.toBigNat)(using BigNat.bignatOrdering)

/** The view number within a HotStuff consensus height, represented as a non-negative big integer. */
opaque type HotStuffView = BigNat

/** Companion for `HotStuffView`. */
@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object HotStuffView:
  val Zero: HotStuffView = BigNat.Zero
  val One: HotStuffView  = BigNat.One

  def apply(
      value: BigNat,
  ): HotStuffView = value

  def fromLong(
      value: Long,
  ): Either[String, HotStuffView] =
    BigNat.fromBigInt(BigInt(value)) match
      case Left(error)   => error.asLeft[HotStuffView]
      case Right(bignat) => apply(bignat).asRight[String]

  def unsafeFromLong(
      value: Long,
  ): HotStuffView =
    fromLong(value) match
      case Right(view) => view
      case Left(error) => throw new IllegalArgumentException(error)

  extension (view: HotStuffView)
    def toBigNat: BigNat = view
    def render: String   = view.toBigNat.toBigInt.toString
    def <(other: HotStuffView): Boolean =
      BigNat.bignatOrdering.lt(view.toBigNat, other.toBigNat)
    def <=(other: HotStuffView): Boolean =
      BigNat.bignatOrdering.lteq(view.toBigNat, other.toBigNat)
    def >(other: HotStuffView): Boolean =
      BigNat.bignatOrdering.gt(view.toBigNat, other.toBigNat)
    def >=(other: HotStuffView): Boolean =
      BigNat.bignatOrdering.gteq(view.toBigNat, other.toBigNat)
    def next: HotStuffView = apply:
      BigNat.add(view.toBigNat, BigNat.One)
    def +(delta: Long): Either[String, HotStuffView] =
      Either
        .cond(
          delta >= 0L,
          (),
          "view delta must be non-negative",
        )
        .map: _ =>
          apply:
            BigNat.add(view.toBigNat, BigNat.unsafeFromLong(delta))

    def unsafeAdd(delta: Long): HotStuffView =
      view.+(delta) match
        case Right(updated) => updated
        case Left(error)    => throw new IllegalArgumentException(error)

  given ByteEncoder[HotStuffView] = ByteEncoder[BigNat].contramap(_.toBigNat)
  given Eq[HotStuffView]          = Eq.by(_.toBigNat)
  given Ordering[HotStuffView] =
    Ordering.by[HotStuffView, BigNat](_.toBigNat)(using BigNat.bignatOrdering)

/** Identifies a specific consensus round by chain, height, view, and validator set.
  *
  * @param chainId the chain this window belongs to
  * @param height the block height
  * @param view the view number within this height
  * @param validatorSetHash the hash of the active validator set
  */
final case class HotStuffWindow(
    chainId: ChainId,
    height: HotStuffHeight,
    view: HotStuffView,
    validatorSetHash: ValidatorSetHash,
)

/** Companion for `HotStuffWindow`. */
@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object HotStuffWindow:
  given Eq[HotStuffWindow] = Eq.fromUniversalEquals

  def fromLongs(
      chainId: ChainId,
      height: Long,
      view: Long,
      validatorSetHash: ValidatorSetHash,
  ): Either[String, HotStuffWindow] =
    for
      validatedHeight <- HotStuffHeight.fromLong(height)
      validatedView   <- HotStuffView.fromLong(view)
    yield HotStuffWindow(
      chainId = chainId,
      height = validatedHeight,
      view = validatedView,
      validatorSetHash = validatorSetHash,
    )

  def unsafe(
      chainId: ChainId,
      height: Long,
      view: Long,
      validatorSetHash: ValidatorSetHash,
  ): HotStuffWindow =
    fromLongs(
      chainId = chainId,
      height = height,
      view = view,
      validatorSetHash = validatorSetHash,
    ) match
      case Right(window) => window
      case Left(error)   => throw new IllegalArgumentException(error)

/** Key for detecting equivocation (double-voting) by a validator in a specific window. */
final case class EquivocationKey(
    chainId: ChainId,
    validatorId: ValidatorId,
    height: HotStuffHeight,
    view: HotStuffView,
)

/** Companion for `EquivocationKey`. */
@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object EquivocationKey:
  def fromLongs(
      chainId: ChainId,
      validatorId: ValidatorId,
      height: Long,
      view: Long,
  ): Either[String, EquivocationKey] =
    for
      validatedHeight <- HotStuffHeight.fromLong(height)
      validatedView   <- HotStuffView.fromLong(view)
    yield EquivocationKey(
      chainId = chainId,
      validatorId = validatorId,
      height = validatedHeight,
      view = validatedView,
    )

  def unsafe(
      chainId: ChainId,
      validatorId: ValidatorId,
      height: Long,
      view: Long,
  ): EquivocationKey =
    fromLongs(
      chainId = chainId,
      validatorId = validatorId,
      height = height,
      view = view,
    ) match
      case Right(key)   => key
      case Left(error)  => throw new IllegalArgumentException(error)

/** The role of the local node in the consensus network. */
enum LocalNodeRole:
  /** A validator that actively participates in consensus. */
  case Validator
  /** An audit node that observes without voting. */
  case Audit

/** Companion for `LocalNodeRole`. */
object LocalNodeRole:
  given Eq[LocalNodeRole] = Eq.fromUniversalEquals

/** The operational status of a validator key holder. */
enum ValidatorKeyHolderStatus:
  /** The key holder is active and allowed to sign. */
  case Active
  /** The key holder is fenced and signing is blocked. */
  case Fenced

/** Companion for `ValidatorKeyHolderStatus`. */
object ValidatorKeyHolderStatus:
  given Eq[ValidatorKeyHolderStatus] = Eq.fromUniversalEquals

/** Binds a validator ID to a peer that holds its signing key, with a status flag.
  *
  * @param validatorId the validator whose key is held
  * @param holder the peer that holds the key
  * @param status the current status (active or fenced)
  */
final case class ValidatorKeyHolder(
    validatorId: ValidatorId,
    holder: PeerIdentity,
    status: ValidatorKeyHolderStatus,
)

/** Policy limits for gossip request batching in the HotStuff protocol. */
final case class HotStuffRequestPolicy private (
    maxProposalRequestIds: Int,
    maxVoteRequestIds: Int,
    maxRetryAttemptsPerWindow: Int,
)

/** Companion for `HotStuffRequestPolicy`. */
@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object HotStuffRequestPolicy:
  def apply(
      maxProposalRequestIds: Int,
      maxVoteRequestIds: Int,
      maxRetryAttemptsPerWindow: Int,
  ): Either[String, HotStuffRequestPolicy] =
    Either
      .cond(
        maxProposalRequestIds > 0,
        (),
        "maxProposalRequestIds must be positive",
      )
      .flatMap: _ =>
        Either.cond(
          maxVoteRequestIds > 0,
          (),
          "maxVoteRequestIds must be positive",
        )
      .flatMap: _ =>
        Either.cond(
          maxRetryAttemptsPerWindow >= 0,
          new HotStuffRequestPolicy(
            maxProposalRequestIds = maxProposalRequestIds,
            maxVoteRequestIds = maxVoteRequestIds,
            maxRetryAttemptsPerWindow = maxRetryAttemptsPerWindow,
          ),
          "maxRetryAttemptsPerWindow must be non-negative",
        )

  def unsafe(
      maxProposalRequestIds: Int,
      maxVoteRequestIds: Int,
      maxRetryAttemptsPerWindow: Int,
  ): HotStuffRequestPolicy =
    apply(
      maxProposalRequestIds = maxProposalRequestIds,
      maxVoteRequestIds = maxVoteRequestIds,
      maxRetryAttemptsPerWindow = maxRetryAttemptsPerWindow,
    ) match
      case Right(policy) => policy
      case Left(error)   => throw new IllegalArgumentException(error)

  /** The default request policy. */
  val default: HotStuffRequestPolicy =
    unsafe(
      maxProposalRequestIds = 128,
      maxVoteRequestIds = 512,
      maxRetryAttemptsPerWindow = 2,
    )

/** Deployment target parameters for block production timing. */
final case class HotStuffDeploymentTarget private (
    blockProductionInterval: Duration,
)

/** Companion for `HotStuffDeploymentTarget`. */
@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object HotStuffDeploymentTarget:
  def apply(
      blockProductionInterval: Duration,
  ): Either[String, HotStuffDeploymentTarget] =
    Either
      .cond(
        !blockProductionInterval.isNegative,
        (),
        "blockProductionInterval must be non-negative",
      )
      .flatMap: _ =>
        Either.cond(
          !blockProductionInterval.isZero,
          new HotStuffDeploymentTarget(
            blockProductionInterval = blockProductionInterval,
          ),
          "blockProductionInterval must be positive",
        )

  def unsafe(
      blockProductionInterval: Duration,
  ): HotStuffDeploymentTarget =
    apply(blockProductionInterval = blockProductionInterval) match
      case Right(target) => target
      case Left(error)   => throw new IllegalArgumentException(error)

  /** The default deployment target (100ms block production interval). */
  val default: HotStuffDeploymentTarget =
    unsafe(blockProductionInterval = Duration.ofMillis(100))

/** Controls whether validated artifacts are relayed to the gossip source for re-broadcast. */
final case class HotStuffRelayPolicy(
    relayValidatedArtifacts: Boolean,
)

/** Companion for `HotStuffRelayPolicy`. */
object HotStuffRelayPolicy:
  /** Returns the relay policy appropriate for the given node role. */
  def forRole(
      role: LocalNodeRole,
  ): HotStuffRelayPolicy =
    HotStuffRelayPolicy(
      relayValidatedArtifacts = role === LocalNodeRole.Audit,
    )

/** Represents a policy violation during HotStuff consensus operations. */
final case class HotStuffPolicyViolation(
    reason: String,
    detail: Option[String],
) extends StructuredFailureDiagnostic:
  override val diagnosticFamily: FailureDiagnosticFamily =
    FailureDiagnosticFamily.HotStuffPolicyViolation

/** Companion for `HotStuffPolicyViolation`. */
object HotStuffPolicyViolation:
  /** Creates a policy violation without detail. */
  def withoutDetail(
      reason: String,
  ): HotStuffPolicyViolation =
    HotStuffPolicyViolation(reason = reason, detail = None)

/** Central policy constants and utility functions for the HotStuff consensus protocol. */
object HotStuffPolicy:
  /** The default request policy. */
  val requestPolicy: HotStuffRequestPolicy = HotStuffRequestPolicy.default
  /** The default deployment target. */
  val deploymentTarget: HotStuffDeploymentTarget =
    HotStuffDeploymentTarget.default

  /** Computes the BFT quorum size (n - f where f = (n-1)/3) for the given validator count. */
  def quorumSize(
      activeValidatorCount: Int,
  ): Either[String, Int] =
    Either.cond(
      activeValidatorCount > 0,
      {
        val toleratedFaults = (activeValidatorCount - 1) / 3
        activeValidatorCount - toleratedFaults
      },
      "activeValidatorCount must be positive",
    )

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private[hotstuff] def validatedQuorumSize(
      activeValidatorCount: Int,
  ): Int =
    quorumSize(activeValidatorCount) match
      case Right(quorum) => quorum
      case Left(error)   => throw new IllegalArgumentException(error)


  /** Validates that no validator has multiple active key holders on different peers. */
  def ensureDistinctActiveKeyHolders(
      holders: Vector[ValidatorKeyHolder],
  ): Either[HotStuffPolicyViolation, Vector[ValidatorKeyHolder]] =
    val duplicateActive =
      holders
        .filter(holder => holder.status === ValidatorKeyHolderStatus.Active)
        .groupBy(_.validatorId)
        .collect:
          case (validatorId, activeHolders)
              if activeHolders.map(_.holder).distinct.sizeCompare(1) > 0 =>
            validatorId -> activeHolders
              .map(_.holder.value)
              .sorted
              .mkString(",")
        .toVector
        .sortBy((validatorId, _) => validatorId.value)

    duplicateActive match
      case _ +: _ =>
        val detail =
          duplicateActive
            .map: (validatorId, holderList) =>
              ss"${validatorId.value}:${holderList}"
            .mkString(";")
        HotStuffPolicyViolation(
          reason = "dualActiveKeyHolder",
          detail = Some(detail),
        ).asLeft[Vector[ValidatorKeyHolder]]
      case _ =>
        holders.asRight[HotStuffPolicyViolation]

  /** Checks whether the local peer is allowed to emit consensus artifacts for the given validator. */
  def canEmitLocally(
      role: LocalNodeRole,
      localPeer: PeerIdentity,
      validatorId: ValidatorId,
      holders: Vector[ValidatorKeyHolder],
  ): Either[HotStuffPolicyViolation, Unit] =
    ensureDistinctActiveKeyHolders(
      holders.filter(holder => holder.validatorId === validatorId),
    ).flatMap: _ =>
      role match
        case LocalNodeRole.Audit =>
          HotStuffPolicyViolation(
            reason = "auditNodeCannotEmit",
            detail = Some(localPeer.value),
          ).asLeft[Unit]
        case LocalNodeRole.Validator =>
          holders.find(holder =>
            holder.validatorId === validatorId && holder.holder === localPeer,
          ) match
            case Some(
                  ValidatorKeyHolder(_, _, ValidatorKeyHolderStatus.Active),
                ) =>
              ().asRight[HotStuffPolicyViolation]
            case Some(
                  ValidatorKeyHolder(_, _, ValidatorKeyHolderStatus.Fenced),
                ) =>
              HotStuffPolicyViolation(
                reason = "validatorKeyFenced",
                detail = Some(ss"${validatorId.value}@${localPeer.value}"),
              ).asLeft[Unit]
            case None =>
              HotStuffPolicyViolation(
                reason = "localValidatorKeyUnavailable",
                detail = Some(ss"${validatorId.value}@${localPeer.value}"),
              ).asLeft[Unit]
