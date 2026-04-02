package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Duration

import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.datatype.{BigNat, UInt256}
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.jvm.runtime.gossip.{ChainId, GossipFieldValidation, PeerIdentity}

opaque type BlockId = UInt256

object BlockId:
  private def renderHex(
      value: UInt256,
  ): String =
    value.bytes.toHex

  def apply(value: UInt256): BlockId = value

  def fromHex(
      value: String,
  ): Either[String, BlockId] =
    UInt256.fromHex(value).left.map(_.toString).map(apply)

  extension (blockId: BlockId)
    def toUInt256: UInt256 = blockId
    def toHexLower: String = renderHex(blockId)

  given ByteEncoder[BlockId] = ByteEncoder[UInt256].contramap(_.toUInt256)

opaque type ProposalId = UInt256

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

opaque type VoteId = UInt256

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

opaque type ValidatorId = String

object ValidatorId:
  def parse(
      value: String,
  ): Either[String, ValidatorId] =
    GossipFieldValidation.validateLowerAsciiToken("validatorId", value).map(_ => value)

  def unsafe(
      value: String,
  ): ValidatorId =
    parse(value) match
      case Right(validatorId) => validatorId
      case Left(error)        => throw new IllegalArgumentException(error)

  extension (validatorId: ValidatorId)
    def value: String = validatorId

  given ByteEncoder[ValidatorId] = ByteEncoder[Utf8].contramap(id => Utf8(id.value))

opaque type ValidatorSetHash = UInt256

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

  given ByteEncoder[ValidatorSetHash] = ByteEncoder[UInt256].contramap(_.toUInt256)

opaque type HotStuffHeight = BigNat

object HotStuffHeight:
  val Genesis: HotStuffHeight = BigNat.One

  private def compareValues(
      left: HotStuffHeight,
      right: HotStuffHeight,
  ): Int =
    left.toBigNat.toBigInt.compare(right.toBigNat.toBigInt)

  private def unsafeFromBigNat(
      value: BigNat,
  ): HotStuffHeight =
    fromBigNat(value) match
      case Right(height) => height
      case Left(error)   => throw new IllegalArgumentException(error)

  def fromBigNat(
      value: BigNat,
  ): Either[String, HotStuffHeight] =
    if value == BigNat.Zero then Left("height must be positive")
    else Right(value)

  def fromLong(
      value: Long,
  ): Either[String, HotStuffHeight] =
    BigNat.fromBigInt(BigInt(value)) match
      case Left(error)    => Left(error)
      case Right(bignat)  => fromBigNat(bignat)

  def unsafeFromLong(
      value: Long,
  ): HotStuffHeight =
    fromLong(value) match
      case Right(height) => height
      case Left(error)   => throw new IllegalArgumentException(error)

  extension (height: HotStuffHeight)
    def toBigNat: BigNat = height
    def render: String = height.toBigNat.toBigInt.toString
    def <(other: HotStuffHeight): Boolean = compareValues(height, other) < 0
    def <=(other: HotStuffHeight): Boolean = compareValues(height, other) <= 0
    def >(other: HotStuffHeight): Boolean = compareValues(height, other) > 0
    def >=(other: HotStuffHeight): Boolean = compareValues(height, other) >= 0
    def next: HotStuffHeight = unsafeFromBigNat(BigNat.add(height.toBigNat, BigNat.One))
    def +(delta: Long): HotStuffHeight =
      if delta < 0L then throw new IllegalArgumentException("height delta must be non-negative")
      else unsafeFromBigNat(BigNat.add(height.toBigNat, BigNat.unsafeFromLong(delta)))

  given ByteEncoder[HotStuffHeight] = ByteEncoder[BigNat].contramap(_.toBigNat)
  given Ordering[HotStuffHeight] with
    override def compare(x: HotStuffHeight, y: HotStuffHeight): Int =
      compareValues(x, y)

opaque type HotStuffView = BigNat

object HotStuffView:
  val Zero: HotStuffView = BigNat.Zero
  val One: HotStuffView = BigNat.One

  private def compareValues(
      left: HotStuffView,
      right: HotStuffView,
  ): Int =
    left.toBigNat.toBigInt.compare(right.toBigNat.toBigInt)

  private def unsafeFromBigNat(
      value: BigNat,
  ): HotStuffView =
    fromBigNat(value) match
      case Right(view) => view
      case Left(error) => throw new IllegalArgumentException(error)

  def fromBigNat(
      value: BigNat,
  ): Either[String, HotStuffView] =
    Right(value)

  def fromLong(
      value: Long,
  ): Either[String, HotStuffView] =
    BigNat.fromBigInt(BigInt(value)) match
      case Left(error)   => Left(error)
      case Right(bignat) => fromBigNat(bignat)

  def unsafeFromLong(
      value: Long,
  ): HotStuffView =
    fromLong(value) match
      case Right(view) => view
      case Left(error) => throw new IllegalArgumentException(error)

  extension (view: HotStuffView)
    def toBigNat: BigNat = view
    def render: String = view.toBigNat.toBigInt.toString
    def <(other: HotStuffView): Boolean = compareValues(view, other) < 0
    def <=(other: HotStuffView): Boolean = compareValues(view, other) <= 0
    def >(other: HotStuffView): Boolean = compareValues(view, other) > 0
    def >=(other: HotStuffView): Boolean = compareValues(view, other) >= 0
    def next: HotStuffView = unsafeFromBigNat(BigNat.add(view.toBigNat, BigNat.One))
    def +(delta: Long): HotStuffView =
      if delta < 0L then throw new IllegalArgumentException("view delta must be non-negative")
      else unsafeFromBigNat(BigNat.add(view.toBigNat, BigNat.unsafeFromLong(delta)))

  given ByteEncoder[HotStuffView] = ByteEncoder[BigNat].contramap(_.toBigNat)
  given Ordering[HotStuffView] with
    override def compare(x: HotStuffView, y: HotStuffView): Int =
      compareValues(x, y)

final case class HotStuffWindow(
    chainId: ChainId,
    height: HotStuffHeight,
    view: HotStuffView,
    validatorSetHash: ValidatorSetHash,
)

object HotStuffWindow:
  def apply(
      chainId: ChainId,
      height: Long,
      view: Long,
      validatorSetHash: ValidatorSetHash,
  ): HotStuffWindow =
    new HotStuffWindow(
      chainId = chainId,
      height = HotStuffHeight.unsafeFromLong(height),
      view = HotStuffView.unsafeFromLong(view),
      validatorSetHash = validatorSetHash,
    )

final case class EquivocationKey(
    chainId: ChainId,
    validatorId: ValidatorId,
    height: HotStuffHeight,
    view: HotStuffView,
)

object EquivocationKey:
  def apply(
      chainId: ChainId,
      validatorId: ValidatorId,
      height: Long,
      view: Long,
  ): EquivocationKey =
    new EquivocationKey(
      chainId = chainId,
      validatorId = validatorId,
      height = HotStuffHeight.unsafeFromLong(height),
      view = HotStuffView.unsafeFromLong(view),
    )

enum LocalNodeRole:
  case Validator, Audit

enum ValidatorKeyHolderStatus:
  case Active, Fenced

final case class ValidatorKeyHolder(
    validatorId: ValidatorId,
    holder: PeerIdentity,
    status: ValidatorKeyHolderStatus,
)

final case class HotStuffRequestPolicy(
    maxProposalRequestIds: Int = 128,
    maxVoteRequestIds: Int = 512,
    maxRetryAttemptsPerWindow: Int = 2,
):
  require(maxProposalRequestIds > 0, "maxProposalRequestIds must be positive")
  require(maxVoteRequestIds > 0, "maxVoteRequestIds must be positive")
  require(maxRetryAttemptsPerWindow >= 0, "maxRetryAttemptsPerWindow must be non-negative")

final case class HotStuffDeploymentTarget(
    blockProductionInterval: Duration = Duration.ofMillis(100),
):
  require(!blockProductionInterval.isNegative, "blockProductionInterval must be non-negative")
  require(!blockProductionInterval.isZero, "blockProductionInterval must be positive")

final case class HotStuffRelayPolicy(
    relayValidatedArtifacts: Boolean,
)

object HotStuffRelayPolicy:
  def forRole(
      role: LocalNodeRole,
  ): HotStuffRelayPolicy =
    HotStuffRelayPolicy(
      relayValidatedArtifacts = role == LocalNodeRole.Audit,
    )

final case class HotStuffPolicyViolation(
    reason: String,
    detail: Option[String] = None,
)

object HotStuffPolicy:
  val requestPolicy: HotStuffRequestPolicy = HotStuffRequestPolicy()
  val deploymentTarget: HotStuffDeploymentTarget = HotStuffDeploymentTarget()

  def quorumSize(
      activeValidatorCount: Int,
  ): Int =
    require(activeValidatorCount > 0, "activeValidatorCount must be positive")
    val toleratedFaults = (activeValidatorCount - 1) / 3
    activeValidatorCount - toleratedFaults

  def ensureDistinctActiveKeyHolders(
      holders: Vector[ValidatorKeyHolder],
  ): Either[HotStuffPolicyViolation, Vector[ValidatorKeyHolder]] =
    val duplicateActive =
      holders
        .filter(_.status == ValidatorKeyHolderStatus.Active)
        .groupBy(_.validatorId)
        .collect:
          case (validatorId, activeHolders) if activeHolders.map(_.holder).distinct.size > 1 =>
            validatorId -> activeHolders.map(_.holder.value).sorted.mkString(",")
        .toVector
        .sortBy((validatorId, _) => validatorId.value)

    duplicateActive match
      case _ +: _ =>
        val detail =
          duplicateActive
            .map: (validatorId, holderList) =>
              s"${validatorId.value}:$holderList"
            .mkString(";")
        Left(
          HotStuffPolicyViolation(
            reason = "dualActiveKeyHolder",
            detail = Some(detail),
          )
        )
      case _ =>
        Right(holders)

  def canEmitLocally(
      role: LocalNodeRole,
      localPeer: PeerIdentity,
      validatorId: ValidatorId,
      holders: Vector[ValidatorKeyHolder],
  ): Either[HotStuffPolicyViolation, Unit] =
    ensureDistinctActiveKeyHolders(holders.filter(_.validatorId == validatorId)).flatMap: _ =>
      role match
        case LocalNodeRole.Audit =>
          Left(
            HotStuffPolicyViolation(
              reason = "auditNodeCannotEmit",
              detail = Some(localPeer.value),
            )
          )
        case LocalNodeRole.Validator =>
          holders.find(holder => holder.validatorId == validatorId && holder.holder == localPeer) match
            case Some(ValidatorKeyHolder(_, _, ValidatorKeyHolderStatus.Active)) =>
              Right(())
            case Some(ValidatorKeyHolder(_, _, ValidatorKeyHolderStatus.Fenced)) =>
              Left(
                HotStuffPolicyViolation(
                  reason = "validatorKeyFenced",
                  detail = Some(s"${validatorId.value}@${localPeer.value}"),
                )
              )
            case None =>
              Left(
                HotStuffPolicyViolation(
                  reason = "localValidatorKeyUnavailable",
                  detail = Some(s"${validatorId.value}@${localPeer.value}"),
                )
              )
