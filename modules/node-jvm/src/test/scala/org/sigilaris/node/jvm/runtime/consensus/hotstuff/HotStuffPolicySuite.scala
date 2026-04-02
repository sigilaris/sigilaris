package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import munit.FunSuite

import org.sigilaris.core.datatype.UInt256
import org.sigilaris.node.jvm.runtime.gossip.{ChainId, PeerIdentity}

final class HotStuffPolicySuite extends FunSuite:

  test("BlockId ProposalId and VoteId stay compile-time distinct"):
    assert(
      compileErrors("""
        import org.sigilaris.core.datatype.UInt256
        import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
        val shared = UInt256.fromHex("01").toOption.get
        val blockId: BlockId = ProposalId(shared)
      """).nonEmpty
    )
    assert(
      compileErrors("""
        import org.sigilaris.core.datatype.UInt256
        import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
        val shared = UInt256.fromHex("01").toOption.get
        val proposalId: ProposalId = BlockId(shared)
      """).nonEmpty
    )
    assert(
      compileErrors("""
        import org.sigilaris.core.datatype.UInt256
        import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
        val shared = UInt256.fromHex("01").toOption.get
        val blockId: BlockId = VoteId(shared)
      """).nonEmpty
    )
    assert(
      compileErrors("""
        import org.sigilaris.core.datatype.UInt256
        import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
        val shared = UInt256.fromHex("01").toOption.get
        val voteId: VoteId = BlockId(shared)
      """).nonEmpty
    )
    assert(
      compileErrors("""
        import org.sigilaris.core.datatype.UInt256
        import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
        val shared = UInt256.fromHex("01").toOption.get
        val proposalId: ProposalId = VoteId(shared)
      """).nonEmpty
    )
    assert(
      compileErrors("""
        import org.sigilaris.core.datatype.UInt256
        import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
        val shared = UInt256.fromHex("01").toOption.get
        val voteId: VoteId = ProposalId(shared)
      """).nonEmpty
    )

  test("window, quorum rule, request policy, and equivocation key lock the phase-0 baseline"):
    val validatorSetHash = ValidatorSetHash(UInt256.fromHex("01").toOption.get)
    val window = HotStuffWindow(
      chainId = ChainId.unsafe("chain-main"),
      height = 7L,
      view = 11L,
      validatorSetHash = validatorSetHash,
    )
    val equivocationKey = EquivocationKey(
      chainId = ChainId.unsafe("chain-main"),
      validatorId = ValidatorId.unsafe("validator-a"),
      height = 7L,
      view = 11L,
    )

    assertEquals(window.height, height(7L))
    assertEquals(window.view, view(11L))
    assertEquals(window.validatorSetHash, validatorSetHash)
    assertEquals(equivocationKey.height, height(7L))
    assertEquals(equivocationKey.view, view(11L))
    assertEquals(HotStuffPolicy.quorumSize(1), 1)
    assertEquals(HotStuffPolicy.quorumSize(2), 2)
    assertEquals(HotStuffPolicy.quorumSize(3), 3)
    assertEquals(HotStuffPolicy.quorumSize(4), 3)
    assertEquals(HotStuffPolicy.quorumSize(5), 4)
    assertEquals(HotStuffPolicy.quorumSize(6), 5)
    assertEquals(HotStuffPolicy.quorumSize(7), 5)
    assertEquals(HotStuffPolicy.requestPolicy.maxProposalRequestIds, 128)
    assertEquals(HotStuffPolicy.requestPolicy.maxVoteRequestIds, 512)
    assertEquals(HotStuffPolicy.requestPolicy.maxRetryAttemptsPerWindow, 2)
    assertEquals(HotStuffPolicy.deploymentTarget.blockProductionInterval.toMillis, 100L)

  test("audit nodes cannot emit and dual active key holders are rejected"):
    val validatorA = ValidatorId.unsafe("validator-a")
    val validatorB = ValidatorId.unsafe("validator-b")
    val nodeA = PeerIdentity.unsafe("node-a")
    val nodeB = PeerIdentity.unsafe("node-b")

    val holders = Vector(
      ValidatorKeyHolder(validatorA, nodeA, ValidatorKeyHolderStatus.Active),
      ValidatorKeyHolder(validatorA, nodeB, ValidatorKeyHolderStatus.Fenced),
      ValidatorKeyHolder(validatorB, nodeB, ValidatorKeyHolderStatus.Active),
    )

    assertEquals(
      HotStuffPolicy.canEmitLocally(LocalNodeRole.Validator, nodeA, validatorA, holders),
      Right(()),
    )
    assertEquals(
      HotStuffPolicy
        .canEmitLocally(LocalNodeRole.Audit, nodeA, validatorA, holders)
        .left
        .map(_.reason),
      Left("auditNodeCannotEmit"),
    )
    assertEquals(
      HotStuffPolicy
        .canEmitLocally(LocalNodeRole.Validator, nodeB, validatorA, holders)
        .left
        .map(_.reason),
      Left("validatorKeyFenced"),
    )
    assertEquals(
      HotStuffPolicy
        .canEmitLocally(LocalNodeRole.Validator, nodeA, validatorB, holders)
        .left
        .map(_.reason),
      Left("localValidatorKeyUnavailable"),
    )

    val dualActive = holders.updated(
      1,
      ValidatorKeyHolder(validatorA, nodeB, ValidatorKeyHolderStatus.Active),
    )

    assertEquals(
      HotStuffPolicy.ensureDistinctActiveKeyHolders(dualActive).left.map(_.reason),
      Left("dualActiveKeyHolder"),
    )
    assertEquals(
      HotStuffPolicy
        .canEmitLocally(LocalNodeRole.Validator, nodeA, validatorA, dualActive)
        .left
        .map(_.reason),
      Left("dualActiveKeyHolder"),
    )
    assertEquals(
      HotStuffPolicy
        .canEmitLocally(LocalNodeRole.Validator, nodeB, validatorB, dualActive)
        .map(identity),
      Right(()),
    )
    val dualActiveDetail =
      HotStuffPolicy
        .ensureDistinctActiveKeyHolders(dualActive)
        .left
        .toOption
        .flatMap(_.detail)
    assert(dualActiveDetail.exists(_.contains("validator-a:node-a,node-b")))

  test("window and equivocation key reject invalid progress values"):
    val _ = intercept[IllegalArgumentException]:
      HotStuffWindow(
        chainId = ChainId.unsafe("chain-main"),
        height = 0L,
        view = 0L,
        validatorSetHash = ValidatorSetHash(UInt256.fromHex("01").toOption.get),
      )

    val ignored = intercept[IllegalArgumentException]:
      EquivocationKey(
        chainId = ChainId.unsafe("chain-main"),
        validatorId = ValidatorId.unsafe("validator-a"),
        height = 1L,
        view = -1L,
      )
    assert(ignored.getMessage.nonEmpty)

    val negativeView = intercept[IllegalArgumentException]:
      HotStuffWindow(
        chainId = ChainId.unsafe("chain-main"),
        height = 1L,
        view = -1L,
        validatorSetHash = ValidatorSetHash(UInt256.fromHex("01").toOption.get),
      )
    assert(negativeView.getMessage.nonEmpty)

    val quorumFailure = intercept[IllegalArgumentException]:
      HotStuffPolicy.quorumSize(0)
    assert(quorumFailure.getMessage.nonEmpty)

    val negativeQuorumFailure = intercept[IllegalArgumentException]:
      HotStuffPolicy.quorumSize(-1)
    assert(negativeQuorumFailure.getMessage.nonEmpty)

  test("request policy and deployment target reject invalid boundaries"):
    val zeroProposalLimit = intercept[IllegalArgumentException]:
      HotStuffRequestPolicy(
        maxProposalRequestIds = 0,
        maxVoteRequestIds = 1,
        maxRetryAttemptsPerWindow = 0,
      )
    assert(zeroProposalLimit.getMessage.nonEmpty)

    val zeroVoteLimit = intercept[IllegalArgumentException]:
      HotStuffRequestPolicy(
        maxProposalRequestIds = 1,
        maxVoteRequestIds = 0,
        maxRetryAttemptsPerWindow = 0,
      )
    assert(zeroVoteLimit.getMessage.nonEmpty)

    val negativeRetries = intercept[IllegalArgumentException]:
      HotStuffRequestPolicy(
        maxProposalRequestIds = 1,
        maxVoteRequestIds = 1,
        maxRetryAttemptsPerWindow = -1,
      )
    assert(negativeRetries.getMessage.nonEmpty)

    val zeroInterval = intercept[IllegalArgumentException]:
      HotStuffDeploymentTarget(blockProductionInterval = java.time.Duration.ZERO)
    assert(zeroInterval.getMessage.nonEmpty)

    val negativeInterval = intercept[IllegalArgumentException]:
      HotStuffDeploymentTarget(blockProductionInterval = java.time.Duration.ofMillis(-1))
    assert(negativeInterval.getMessage.nonEmpty)

  private def height(
      value: Long,
  ): HotStuffHeight =
    HotStuffHeight.unsafeFromLong(value)

  private def view(
      value: Long,
  ): HotStuffView =
    HotStuffView.unsafeFromLong(value)
