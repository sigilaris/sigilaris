package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Instant

import cats.effect.IO
import munit.CatsEffectSuite

import org.sigilaris.core.datatype.UInt256
import org.sigilaris.node.gossip.{ChainId, StableArtifactId}
import org.sigilaris.node.jvm.runtime.block.{
  BlockHeight,
  BlockId,
  BlockTimestamp,
  BodyRoot,
  StateRoot,
}

final class HotStuffProposalInputProviderSuite extends CatsEffectSuite:
  private val chainId   = ChainId.unsafe("chain-main")
  private val startedAt = Instant.parse("2026-05-16T00:00:00Z")
  private val window =
    HotStuffWindow.unsafe(chainId, 1L, 0L, ValidatorSetHash(hex("10")))
  private val timestamp =
    BlockTimestamp.unsafeFromEpochMillis(startedAt.toEpochMilli)
  private val parent = Some(BlockId(hex("21")))
  private val request =
    HotStuffProposalInputRequest(
      window = window,
      proposer = ValidatorId.unsafe("validator-1"),
      parent = parent,
      height = BlockHeight.unsafeFromLong(1L),
      justify = QuorumCertificate(
        subject = QuorumCertificateSubject(
          window = HotStuffWindow.unsafe(
            chainId,
            0L,
            0L,
            ValidatorSetHash(hex("10")),
          ),
          proposalId = ProposalId(hex("22")),
          blockId = BlockId(hex("23")),
        ),
        votes = Vector.empty,
      ),
      now = startedAt,
      timestamp = timestamp,
      bounds = HotStuffProposalInputBounds(maxTxIds = Some(2)),
    )

  test("legacy empty provider supplies the request block context and empty tx set"):
    val stateRoot = StateRoot(hex("31"))
    val bodyRoot  = BodyRoot(hex("32"))
    val provider =
      LegacyEmptyHotStuffProposalInputProvider.const[IO](stateRoot, bodyRoot)

    provider.nextProposalInput(request).map:
      case HotStuffProposalInputProviderResult.Supplied(input) =>
        assertEquals(input.parent, request.parent)
        assertEquals(input.height, request.height)
        assertEquals(input.timestamp, request.timestamp)
        assertEquals(input.stateRoot, stateRoot)
        assertEquals(input.bodyRoot, bodyRoot)
        assertEquals(input.txSet, ProposalTxSet.empty)
        assertEquals(input.blockHeader.stateRoot, stateRoot)
      case other =>
        fail(s"expected supplied legacy input, got ${other.toString}")

  test("fallback policy preserves provider input and controls non-supplied results"):
    val suppliedInput =
      HotStuffProposalInput(
        parent = request.parent,
        height = request.height,
        stateRoot = StateRoot(hex("41")),
        bodyRoot = BodyRoot(hex("42")),
        timestamp = request.timestamp,
        txSet = ProposalTxSet(Vector(txId("43"))),
      )

    assertEquals(
      HotStuffProposalInputDecision.fromProviderResult(
        HotStuffProposalInputProviderResult.Supplied(suppliedInput),
        HotStuffProposalInputFallbackPolicy.RequireProviderInput,
      ),
      HotStuffProposalInputDecision.UseProviderInput(suppliedInput),
    )
    assertEquals(
      HotStuffProposalInputDecision.fromProviderResult(
        HotStuffProposalInputProviderResult.NoWork("queueEmpty", None),
        HotStuffProposalInputFallbackPolicy.AllowLegacyEmpty,
      ),
      HotStuffProposalInputDecision.UseLegacyEmpty("queueEmpty", None),
    )
    assertEquals(
      HotStuffProposalInputDecision.fromProviderResult(
        HotStuffProposalInputProviderResult.Rejected(
          "viewRejected",
          Some("local admission not ready"),
        ),
        HotStuffProposalInputFallbackPolicy.RequireProviderInput,
      ),
      HotStuffProposalInputDecision.Suppress(
        "viewRejected",
        Some("local admission not ready"),
      ),
    )

  test("provider input validation rejects non-canonical and over-limit tx sets"):
    val txA = txId("51")
    val txB = txId("52")
    val txC = txId("53")
    val nonCanonical =
      validInput.copy(txSet = ProposalTxSet(Vector(txB, txA)))
    val overLimit =
      validInput.copy(txSet = ProposalTxSet(Vector(txA, txB, txC)))

    assertEquals(
      HotStuffProposalInputValidator.validate(request, validInput),
      Right(validInput),
    )
    assertEquals(
      HotStuffProposalInputValidator
        .validate(request, nonCanonical)
        .left
        .map(_.reason),
      Left("proposalInputTxSetNotCanonical"),
    )
    assertEquals(
      HotStuffProposalInputValidator.validate(request, overLimit).left.map(
        _.reason,
      ),
      Left("proposalInputTxLimitExceeded"),
    )

  private def validInput: HotStuffProposalInput =
    HotStuffProposalInput(
      parent = request.parent,
      height = request.height,
      stateRoot = StateRoot(hex("61")),
      bodyRoot = BodyRoot(hex("62")),
      timestamp = request.timestamp,
      txSet = ProposalTxSet(Vector(txId("51"), txId("52"))),
    )

  private def txId(
      value: String,
  ): StableArtifactId =
    StableArtifactId.unsafeFromBytes(hex(value).bytes)

  private def hex(
      value: String,
  ): UInt256 =
    UInt256.fromHex(value).toOption.get
