package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import cats.effect.IO
import munit.CatsEffectSuite

import org.sigilaris.core.crypto.CryptoOps
import org.sigilaris.core.datatype.UInt256
import org.sigilaris.node.jvm.runtime.block.{BlockHeight, BlockId, StateRoot}
import org.sigilaris.node.jvm.runtime.gossip.{ChainId, DirectionalSessionId, PeerIdentity}

final class HotStuffBootstrapContractsSuite extends CatsEffectSuite:

  private val chainId = ChainId.unsafe("chain-main")
  private val validatorKeys = Vector.fill(4)(CryptoOps.generate())
  private val validatorSet = ValidatorSet.unsafe(
    validatorKeys.zipWithIndex.map: (keyPair, index) =>
      ValidatorMember(
        id = ValidatorId.unsafe(s"validator-${index + 1}"),
        publicKey = keyPair.publicKey,
      )
  )
  private val otherValidatorSet = ValidatorSet.unsafe(
    Vector.fill(4)(CryptoOps.generate()).zipWithIndex.map: (keyPair, index) =>
      ValidatorMember(
        id = ValidatorId.unsafe(s"validator-alt-${index + 1}"),
        publicKey = keyPair.publicKey,
      )
  )
  private val session =
    BootstrapSessionBinding(
      peer = PeerIdentity.unsafe("node-b"),
      sessionId =
        DirectionalSessionId
          .parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
          .toOption
          .get,
    )

  test("static validator-set lookup accepts matching validatorSetHash and rejects unknown historical sets"):
    val lookup = ValidatorSetLookup.static[IO](BootstrapTrustRoot.staticValidatorSet(validatorSet))

    for
      matching <- lookup.validatorSetFor(HotStuffWindow(chainId, 1L, 1L, validatorSet.hash))
      missing <- lookup.validatorSetFor(HotStuffWindow(chainId, 1L, 1L, otherValidatorSet.hash))
    yield
      assertEquals(matching.map(_.hash), Right(validatorSet.hash))
      assertEquals(missing.left.map(_.reason), Left("validatorSetUnavailable"))

  test("static bootstrap services expose empty session-bound skeleton implementations"):
    val services = HotStuffBootstrapServices.static[IO](validatorSet)

    for
      suggestion <- services.finalizedAnchorSuggestions.bestFinalized(session, chainId)
      nodes <- services.snapshotNodeFetch.fetchNodes(
        session = session,
        chainId = chainId,
        stateRoot = StateRoot(hex("aa")),
        hashes = Vector.empty,
      )
      replay <- services.proposalReplay.readNext(
        session = session,
        chainId = chainId,
        anchorBlockId = BlockId(hex("bb")),
        nextHeight = BlockHeight.unsafeFromLong(1L),
        limit = 32,
      )
      backfill <- services.historicalBackfill.readPrevious(
        session = session,
        chainId = chainId,
        beforeBlockId = BlockId(hex("cc")),
        beforeHeight = BlockHeight.unsafeFromLong(10L),
        limit = 32,
      )
      diagnostics <- services.diagnostics.current
    yield
      assertEquals(suggestion, Right(None))
      assertEquals(nodes, Right(Vector.empty))
      assertEquals(replay, Right(Vector.empty))
      assertEquals(backfill, Right(Vector.empty))
      assertEquals(diagnostics.voteReadiness, BootstrapVoteReadiness.Held("bootstrapPending"))
      assertEquals(diagnostics.historicalBackfill, HistoricalBackfillStatus.Idle)

  private def hex(
      value: String,
  ): UInt256 =
    UInt256.fromHex(value).toOption.get
