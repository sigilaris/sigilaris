package org.sigilaris.node.jvm.runtime.application.v2

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.application.protocol.v2.V2Codecs.{
  bytesDecoder,
  bytesEncoder,
}
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.given

/** Internal controller request language. Payloads preserve existing HotStuff
  * canonical framing; these tags never alter consensus wire/signature bytes.
  */
enum HotStuffControllerKind(val tag: Byte):
  case Proposal    extends HotStuffControllerKind(1.toByte)
  case Vote        extends HotStuffControllerKind(2.toByte)
  case Timeout     extends HotStuffControllerKind(3.toByte)
  case NewView     extends HotStuffControllerKind(4.toByte)
  case Application extends HotStuffControllerKind(5.toByte)
object HotStuffControllerKind:
  given ByteEncoder[HotStuffControllerKind] = ByteEncoder[Byte].contramap(_.tag)
  given ByteDecoder[HotStuffControllerKind] = V2Codecs.enumDecoder(
    "hotstuffController.kind",
    values.toVector.map(v => v.tag -> v),
  )

final case class HotStuffControllerRequest(
    format: Long,
    kind: HotStuffControllerKind,
    payload: Bytes,
)
object HotStuffControllerRequest:
  given ByteEncoder[HotStuffControllerRequest] = ByteEncoder.derived
  given ByteDecoder[HotStuffControllerRequest] = ByteDecoder.derived
  val codec = CanonicalCodec.derived[HotStuffControllerRequest](v =>
    V2Validation.format(v.format, 1L, "hotstuffController.format"),
  )

final case class ControllerConsensusVote(voter: ValidatorId, proposal: Proposal)
object ControllerConsensusVote:
  given ByteEncoder[ControllerConsensusVote] = ByteEncoder.derived
  given ByteDecoder[ControllerConsensusVote] = ByteDecoder.derived
  val codec = CanonicalCodec.derived[ControllerConsensusVote](_ =>
    Right[CoreFailure, Unit](()),
  )

final case class ControllerApplicationVote(
    context: DomainContext,
    canonicalPreimage: Bytes,
)
object ControllerApplicationVote:
  given ByteEncoder[ControllerApplicationVote] = ByteEncoder.derived
  given ByteDecoder[ControllerApplicationVote] = ByteDecoder.derived
  val codec = CanonicalCodec.derived[ControllerApplicationVote](v =>
    DomainContext.validateActive(v.context),
  )

object HotStuffControllerRequests:
  private given ByteEncoder[UnsignedProposal]         = ByteEncoder.derived
  private given ByteDecoder[UnsignedProposal]         = ByteDecoder.derived
  private given ByteEncoder[UnsignedTimeoutVote]      = ByteEncoder.derived
  private given ByteDecoder[UnsignedTimeoutVote]      = ByteDecoder.derived
  private given ByteEncoder[UnsignedNewView]          = ByteEncoder.derived
  private given ByteDecoder[UnsignedNewView]          = ByteDecoder.derived
  val proposalCodec: CanonicalCodec[UnsignedProposal] =
    CanonicalCodec.derived(_ => Right[CoreFailure, Unit](()))
  val timeoutCodec: CanonicalCodec[UnsignedTimeoutVote] =
    CanonicalCodec.derived(_ => Right[CoreFailure, Unit](()))
  val newViewCodec: CanonicalCodec[UnsignedNewView] =
    CanonicalCodec.derived(_ => Right[CoreFailure, Unit](()))
  private def wrap(
      kind: HotStuffControllerKind,
      bytes: Either[CoreFailure, Bytes],
  ): Either[CoreFailure, Bytes] =
    bytes.flatMap(payload =>
      HotStuffControllerRequest.codec.encode(
        HotStuffControllerRequest(1L, kind, payload),
      ),
    )
  def proposal(value: UnsignedProposal): Either[CoreFailure, Bytes] =
    wrap(HotStuffControllerKind.Proposal, proposalCodec.encode(value))
  def vote(voter: ValidatorId, proposal: Proposal): Either[CoreFailure, Bytes] =
    wrap(
      HotStuffControllerKind.Vote,
      ControllerConsensusVote.codec.encode(
        ControllerConsensusVote(voter, proposal),
      ),
    )
  def timeout(value: UnsignedTimeoutVote): Either[CoreFailure, Bytes] =
    wrap(HotStuffControllerKind.Timeout, timeoutCodec.encode(value))
  def newView(value: UnsignedNewView): Either[CoreFailure, Bytes] =
    wrap(HotStuffControllerKind.NewView, newViewCodec.encode(value))
  def application(
      context: DomainContext,
      preimage: Bytes,
  ): Either[CoreFailure, Bytes] =
    wrap(
      HotStuffControllerKind.Application,
      ControllerApplicationVote.codec.encode(
        ControllerApplicationVote(context, preimage),
      ),
    )
