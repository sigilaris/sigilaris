package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import cats.Eq
import scodec.bits.ByteVector

import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.crypto.{KeyPair, Signature}
import org.sigilaris.core.datatype.{UInt256, Utf8}
import org.sigilaris.node.gossip.ChainId

/** Unique identifier for a timeout vote, represented as a 256-bit hash. */
opaque type TimeoutVoteId = UInt256

/** Companion for `TimeoutVoteId`. */
object TimeoutVoteId:
  private def renderHex(
      value: UInt256,
  ): String =
    value.bytes.toHex

  def apply(
      value: UInt256,
  ): TimeoutVoteId = value

  def fromHex(
      value: String,
  ): Either[String, TimeoutVoteId] =
    UInt256.fromHex(value).left.map(_.toString).map(apply)

  extension (timeoutVoteId: TimeoutVoteId)
    def toUInt256: UInt256 = timeoutVoteId
    def toHexLower: String = renderHex(timeoutVoteId)

  given ByteEncoder[TimeoutVoteId] =
    ByteEncoder[UInt256].contramap(_.toUInt256)
  given Eq[TimeoutVoteId] = Eq.by(_.toUInt256)

/** Unique identifier for a new-view message, represented as a 256-bit hash. */
opaque type NewViewId = UInt256

/** Companion for `NewViewId`. */
object NewViewId:
  private def renderHex(
      value: UInt256,
  ): String =
    value.bytes.toHex

  def apply(
      value: UInt256,
  ): NewViewId = value

  def fromHex(
      value: String,
  ): Either[String, NewViewId] =
    UInt256.fromHex(value).left.map(_.toString).map(apply)

  extension (newViewId: NewViewId)
    def toUInt256: UInt256 = newViewId
    def toHexLower: String = renderHex(newViewId)

  given ByteEncoder[NewViewId] =
    ByteEncoder[UInt256].contramap(_.toUInt256)
  given Eq[NewViewId] = Eq.by(_.toUInt256)

/** The subject of a timeout vote, combining the consensus window and the voter's highest known QC. */
final case class TimeoutVoteSubject(
    window: HotStuffWindow,
    highestKnownQc: QuorumCertificateSubject,
) derives ByteEncoder

/** Companion for `TimeoutVoteSubject`. */
object TimeoutVoteSubject:
  given Eq[TimeoutVoteSubject] = Eq.fromUniversalEquals

/** An unsigned timeout vote before signing. */
final case class UnsignedTimeoutVote(
    subject: TimeoutVoteSubject,
    voter: ValidatorId,
)

/** A signed timeout vote emitted when a validator's view timer expires. */
final case class TimeoutVote(
    timeoutVoteId: TimeoutVoteId,
    subject: TimeoutVoteSubject,
    voter: ValidatorId,
    signature: Signature,
) derives ByteEncoder:
  /** Returns the equivocation key for detecting conflicting timeout votes. */
  def equivocationKey: EquivocationKey =
    EquivocationKey(
      chainId = subject.window.chainId,
      validatorId = voter,
      height = subject.window.height,
      view = subject.window.view,
    )

/** Companion for `TimeoutVote`, providing signing and identity operations. */
object TimeoutVote:
  def sign(
      unsigned: UnsignedTimeoutVote,
      keyPair: KeyPair,
  ): Either[HotStuffValidationFailure, TimeoutVote] =
    HotStuffPacemakerCanonicalEncoding
      .sign(
        HotStuffPacemakerCanonicalEncoding.timeoutVoteSignBytes(unsigned),
        keyPair,
      )
      .map: signature =>
        val timeoutVoteId =
          TimeoutVoteId:
            HotStuffPacemakerCanonicalEncoding.timeoutVoteId(
              subject = unsigned.subject,
              voter = unsigned.voter,
              signature = signature,
            )
        TimeoutVote(
          timeoutVoteId = timeoutVoteId,
          subject = unsigned.subject,
          voter = unsigned.voter,
          signature = signature,
        )

  def signBytes(
      unsigned: UnsignedTimeoutVote,
  ): ByteVector =
    HotStuffPacemakerCanonicalEncoding.timeoutVoteSignBytes(unsigned)

  def recomputeId(
      timeoutVote: TimeoutVote,
  ): TimeoutVoteId =
    TimeoutVoteId:
      HotStuffPacemakerCanonicalEncoding.timeoutVoteId(
        subject = timeoutVote.subject,
        voter = timeoutVote.voter,
        signature = timeoutVote.signature,
      )

/** A certificate aggregating sufficient timeout votes to trigger a view change. */
final case class TimeoutCertificate(
    subject: TimeoutVoteSubject,
    votes: Vector[TimeoutVote],
) derives ByteEncoder

/** An unsigned new-view message before signing. */
final case class UnsignedNewView(
    window: HotStuffWindow,
    sender: ValidatorId,
    nextLeader: ValidatorId,
    highestKnownQc: QuorumCertificate,
    timeoutCertificate: TimeoutCertificate,
)

/** A signed new-view message sent to the next leader after a timeout certificate is formed. */
final case class NewView(
    newViewId: NewViewId,
    window: HotStuffWindow,
    sender: ValidatorId,
    nextLeader: ValidatorId,
    highestKnownQc: QuorumCertificate,
    timeoutCertificate: TimeoutCertificate,
    signature: Signature,
) derives ByteEncoder

/** Companion for `NewView`, providing signing and identity operations. */
object NewView:
  def sign(
      unsigned: UnsignedNewView,
      keyPair: KeyPair,
  ): Either[HotStuffValidationFailure, NewView] =
    HotStuffPacemakerCanonicalEncoding
      .sign(
        HotStuffPacemakerCanonicalEncoding.newViewSignBytes(unsigned),
        keyPair,
      )
      .map: signature =>
        val newViewId =
          NewViewId:
            HotStuffPacemakerCanonicalEncoding.newViewId(
              window = unsigned.window,
              sender = unsigned.sender,
              nextLeader = unsigned.nextLeader,
              highestKnownQc = unsigned.highestKnownQc,
              timeoutCertificate = unsigned.timeoutCertificate,
              signature = signature,
            )
        NewView(
          newViewId = newViewId,
          window = unsigned.window,
          sender = unsigned.sender,
          nextLeader = unsigned.nextLeader,
          highestKnownQc = unsigned.highestKnownQc,
          timeoutCertificate = unsigned.timeoutCertificate,
          signature = signature,
        )

  def signBytes(
      unsigned: UnsignedNewView,
  ): ByteVector =
    HotStuffPacemakerCanonicalEncoding.newViewSignBytes(unsigned)

  def recomputeId(
      newView: NewView,
  ): NewViewId =
    NewViewId:
      HotStuffPacemakerCanonicalEncoding.newViewId(
        window = newView.window,
        sender = newView.sender,
        nextLeader = newView.nextLeader,
        highestKnownQc = newView.highestKnownQc,
        timeoutCertificate = newView.timeoutCertificate,
        signature = newView.signature,
      )

/** Core pacemaker utilities for deterministic leader election and window advancement. */
object HotStuffPacemaker:
  /** Selects the leader for the given window using a deterministic round-robin scheme. */
  def deterministicLeader(
      window: HotStuffWindow,
      validatorSet: ValidatorSet,
  ): ValidatorId =
    val members = validatorSet.members
    val index =
      (
        window.view.toBigNat.toBigInt %
          BigInt(members.size.toLong)
      ).toInt
    members(index).id

  /** Returns the next consensus window by incrementing the view number. */
  def nextWindowAfter(
      window: HotStuffWindow,
  ): HotStuffWindow =
    window.copy(view = window.view.next)

/** Canonical encoding and hashing operations for pacemaker artifacts (timeout votes and new views). */
object HotStuffPacemakerCanonicalEncoding:
  private val TimeoutVoteSignDomain: Utf8 =
    Utf8("sigilaris.hotstuff.timeout-vote.sign.v1")
  private val TimeoutVoteIdentityDomain: Utf8 =
    Utf8("sigilaris.hotstuff.timeout-vote.id.v1")
  private val NewViewSignDomain: Utf8 =
    Utf8("sigilaris.hotstuff.new-view.sign.v1")
  private val NewViewIdentityDomain: Utf8 =
    Utf8("sigilaris.hotstuff.new-view.id.v1")

  private final case class TimeoutVoteSignInput(
      domain: Utf8,
      chainId: ChainId,
      height: HotStuffHeight,
      view: HotStuffView,
      validatorSetHash: ValidatorSetHash,
      voter: ValidatorId,
      highestKnownQc: QuorumCertificateSubject,
  ) derives ByteEncoder

  private final case class TimeoutVoteIdentityInput(
      domain: Utf8,
      chainId: ChainId,
      height: HotStuffHeight,
      view: HotStuffView,
      validatorSetHash: ValidatorSetHash,
      voter: ValidatorId,
      highestKnownQc: QuorumCertificateSubject,
      signature: Signature,
  ) derives ByteEncoder

  private final case class NewViewSignInput(
      domain: Utf8,
      chainId: ChainId,
      height: HotStuffHeight,
      view: HotStuffView,
      validatorSetHash: ValidatorSetHash,
      sender: ValidatorId,
      nextLeader: ValidatorId,
      highestKnownQc: QuorumCertificate,
      timeoutCertificate: TimeoutCertificate,
  ) derives ByteEncoder

  private final case class NewViewIdentityInput(
      domain: Utf8,
      chainId: ChainId,
      height: HotStuffHeight,
      view: HotStuffView,
      validatorSetHash: ValidatorSetHash,
      sender: ValidatorId,
      nextLeader: ValidatorId,
      highestKnownQc: QuorumCertificate,
      timeoutCertificate: TimeoutCertificate,
      signature: Signature,
  ) derives ByteEncoder

  def timeoutVoteSignBytes(
      unsigned: UnsignedTimeoutVote,
  ): ByteVector =
    TimeoutVoteSignInput(
      domain = TimeoutVoteSignDomain,
      chainId = unsigned.subject.window.chainId,
      height = unsigned.subject.window.height,
      view = unsigned.subject.window.view,
      validatorSetHash = unsigned.subject.window.validatorSetHash,
      voter = unsigned.voter,
      highestKnownQc = unsigned.subject.highestKnownQc,
    ).toBytes

  def timeoutVoteId(
      subject: TimeoutVoteSubject,
      voter: ValidatorId,
      signature: Signature,
  ): UInt256 =
    HotStuffCanonicalEncoding.hashEncoded:
      TimeoutVoteIdentityInput(
        domain = TimeoutVoteIdentityDomain,
        chainId = subject.window.chainId,
        height = subject.window.height,
        view = subject.window.view,
        validatorSetHash = subject.window.validatorSetHash,
        voter = voter,
        highestKnownQc = subject.highestKnownQc,
        signature = signature,
      )

  def newViewSignBytes(
      unsigned: UnsignedNewView,
  ): ByteVector =
    NewViewSignInput(
      domain = NewViewSignDomain,
      chainId = unsigned.window.chainId,
      height = unsigned.window.height,
      view = unsigned.window.view,
      validatorSetHash = unsigned.window.validatorSetHash,
      sender = unsigned.sender,
      nextLeader = unsigned.nextLeader,
      highestKnownQc = canonicalizeQuorumCertificate(unsigned.highestKnownQc),
      timeoutCertificate =
        canonicalizeTimeoutCertificate(unsigned.timeoutCertificate),
    ).toBytes

  def newViewId(
      window: HotStuffWindow,
      sender: ValidatorId,
      nextLeader: ValidatorId,
      highestKnownQc: QuorumCertificate,
      timeoutCertificate: TimeoutCertificate,
      signature: Signature,
  ): UInt256 =
    HotStuffCanonicalEncoding.hashEncoded:
      NewViewIdentityInput(
        domain = NewViewIdentityDomain,
        chainId = window.chainId,
        height = window.height,
        view = window.view,
        validatorSetHash = window.validatorSetHash,
        sender = sender,
        nextLeader = nextLeader,
        highestKnownQc = canonicalizeQuorumCertificate(highestKnownQc),
        timeoutCertificate = canonicalizeTimeoutCertificate(timeoutCertificate),
        signature = signature,
      )

  def sign(
      signBytes: ByteVector,
      keyPair: KeyPair,
  ): Either[HotStuffValidationFailure, Signature] =
    HotStuffCanonicalEncoding.sign(signBytes, keyPair)

  private def canonicalizeQuorumCertificate(
      quorumCertificate: QuorumCertificate,
  ): QuorumCertificate =
    quorumCertificate.copy(
      votes = deduplicateVotesByValidator(quorumCertificate.votes)(
        validatorOf = _.voter,
      )(
        stableIdOf = _.voteId.toHexLower,
      ),
    )

  private def canonicalizeTimeoutCertificate(
      timeoutCertificate: TimeoutCertificate,
  ): TimeoutCertificate =
    timeoutCertificate.copy(
      votes = deduplicateVotesByValidator(timeoutCertificate.votes)(
        validatorOf = _.voter,
      )(
        stableIdOf = _.timeoutVoteId.toHexLower,
      ),
    )

  private def deduplicateVotesByValidator[A](
      votes: Vector[A],
  )(
      validatorOf: A => ValidatorId,
  )(
      stableIdOf: A => String,
  ): Vector[A] =
    votes
      .sortBy(vote => (validatorOf(vote).value, stableIdOf(vote)))
      .foldLeft((Set.empty[ValidatorId], Vector.empty[A])):
        case ((seen, acc), vote) =>
          val validatorId = validatorOf(vote)
          if seen.contains(validatorId) then (seen, acc)
          else (seen + validatorId, acc :+ vote)
      ._2
