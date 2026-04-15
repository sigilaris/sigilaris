package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import munit.FunSuite

import org.sigilaris.core.datatype.UInt256
import org.sigilaris.node.gossip.{ChainId, PeerIdentity}

final class HotStuffPolicySuite extends FunSuite:

  test("BlockId ProposalId and VoteId stay compile-time distinct"):
    assert(
      compileErrors("""
        import org.sigilaris.core.datatype.UInt256
        import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
        val shared = UInt256.fromHex("01").toOption.get
        val blockId: BlockId = ProposalId(shared)
      """).nonEmpty,
    )
    assert(
      compileErrors("""
        import org.sigilaris.core.datatype.UInt256
        import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
        val shared = UInt256.fromHex("01").toOption.get
        val proposalId: ProposalId = BlockId(shared)
      """).nonEmpty,
    )
    assert(
      compileErrors("""
        import org.sigilaris.core.datatype.UInt256
        import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
        val shared = UInt256.fromHex("01").toOption.get
        val blockId: BlockId = VoteId(shared)
      """).nonEmpty,
    )
    assert(
      compileErrors("""
        import org.sigilaris.core.datatype.UInt256
        import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
        val shared = UInt256.fromHex("01").toOption.get
        val voteId: VoteId = BlockId(shared)
      """).nonEmpty,
    )
    assert(
      compileErrors("""
        import org.sigilaris.core.datatype.UInt256
        import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
        val shared = UInt256.fromHex("01").toOption.get
        val proposalId: ProposalId = VoteId(shared)
      """).nonEmpty,
    )
    assert(
      compileErrors("""
        import org.sigilaris.core.datatype.UInt256
        import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
        val shared = UInt256.fromHex("01").toOption.get
        val voteId: VoteId = ProposalId(shared)
      """).nonEmpty,
    )

  test(
    "window, quorum rule, request policy, and equivocation key lock the phase-0 baseline",
  ):
    val validatorSetHash = ValidatorSetHash(UInt256.fromHex("01").toOption.get)
    val window = HotStuffWindow.unsafe(
      chainId = ChainId.unsafe("chain-main"),
      height = 7L,
      view = 11L,
      validatorSetHash = validatorSetHash,
    )
    val equivocationKey = EquivocationKey.unsafe(
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
    assertEquals(HotStuffPolicy.quorumSize(1), Right(1))
    assertEquals(HotStuffPolicy.quorumSize(2), Right(2))
    assertEquals(HotStuffPolicy.quorumSize(3), Right(3))
    assertEquals(HotStuffPolicy.quorumSize(4), Right(3))
    assertEquals(HotStuffPolicy.quorumSize(5), Right(4))
    assertEquals(HotStuffPolicy.quorumSize(6), Right(5))
    assertEquals(HotStuffPolicy.quorumSize(7), Right(5))
    assertEquals(HotStuffPolicy.requestPolicy.maxProposalRequestIds, 128)
    assertEquals(HotStuffPolicy.requestPolicy.maxVoteRequestIds, 512)
    assertEquals(HotStuffPolicy.requestPolicy.maxRetryAttemptsPerWindow, 2)
    assertEquals(
      HotStuffPolicy.deploymentTarget.blockProductionInterval.toMillis,
      100L,
    )

  test("audit nodes cannot emit and dual active key holders are rejected"):
    val validatorA = ValidatorId.unsafe("validator-a")
    val validatorB = ValidatorId.unsafe("validator-b")
    val nodeA      = PeerIdentity.unsafe("node-a")
    val nodeB      = PeerIdentity.unsafe("node-b")

    val holders = Vector(
      ValidatorKeyHolder(validatorA, nodeA, ValidatorKeyHolderStatus.Active),
      ValidatorKeyHolder(validatorA, nodeB, ValidatorKeyHolderStatus.Fenced),
      ValidatorKeyHolder(validatorB, nodeB, ValidatorKeyHolderStatus.Active),
    )

    assertEquals(
      HotStuffPolicy
        .canEmitLocally(LocalNodeRole.Validator, nodeA, validatorA, holders),
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
      HotStuffPolicy
        .ensureDistinctActiveKeyHolders(dualActive)
        .left
        .map(_.reason),
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

  test(
    "window and equivocation key allow genesis height zero but reject negative progress values",
  ):
    val genesisWindow =
      HotStuffWindow.fromLongs(
        chainId = ChainId.unsafe("chain-main"),
        height = 0L,
        view = 0L,
        validatorSetHash = ValidatorSetHash(UInt256.fromHex("01").toOption.get),
      )
    assertEquals(genesisWindow.map(_.height), Right(height(0L)))
    assertEquals(height(7L) + 2L, Right(height(9L)))
    assertEquals(view(11L) + 5L, Right(view(16L)))

    assertEquals(
      HotStuffWindow.fromLongs(
        chainId = ChainId.unsafe("chain-main"),
        height = -1L,
        view = 0L,
        validatorSetHash = ValidatorSetHash(UInt256.fromHex("01").toOption.get),
      ),
      Left("Should be positive or zero"),
    )

    assertEquals(
      EquivocationKey.fromLongs(
        chainId = ChainId.unsafe("chain-main"),
        validatorId = ValidatorId.unsafe("validator-a"),
        height = 1L,
        view = -1L,
      ),
      Left("Should be positive or zero"),
    )

    assertEquals(
      HotStuffWindow.fromLongs(
        chainId = ChainId.unsafe("chain-main"),
        height = 1L,
        view = -1L,
        validatorSetHash = ValidatorSetHash(UInt256.fromHex("01").toOption.get),
      ),
      Left("Should be positive or zero"),
    )

    assertEquals(height(7L) + -1L, Left("height delta must be non-negative"))
    assertEquals(view(11L) + -1L, Left("view delta must be non-negative"))
    assertEquals(
      HotStuffPolicy.quorumSize(0),
      Left("activeValidatorCount must be positive"),
    )
    assertEquals(
      HotStuffPolicy.quorumSize(-1),
      Left("activeValidatorCount must be positive"),
    )

  test("request policy and deployment target reject invalid boundaries"):
    val zeroProposalLimit =
      HotStuffRequestPolicy(
        maxProposalRequestIds = 0,
        maxVoteRequestIds = 1,
        maxRetryAttemptsPerWindow = 0,
      )
    assertEquals(
      zeroProposalLimit,
      Left("maxProposalRequestIds must be positive"),
    )

    val zeroVoteLimit =
      HotStuffRequestPolicy(
        maxProposalRequestIds = 1,
        maxVoteRequestIds = 0,
        maxRetryAttemptsPerWindow = 0,
      )
    assertEquals(zeroVoteLimit, Left("maxVoteRequestIds must be positive"))

    val negativeRetries =
      HotStuffRequestPolicy(
        maxProposalRequestIds = 1,
        maxVoteRequestIds = 1,
        maxRetryAttemptsPerWindow = -1,
      )
    assertEquals(
      negativeRetries,
      Left("maxRetryAttemptsPerWindow must be non-negative"),
    )

    val zeroInterval =
      HotStuffDeploymentTarget(blockProductionInterval =
        java.time.Duration.ZERO,
      )
    assertEquals(zeroInterval, Left("blockProductionInterval must be positive"))

  test("topic policy rejects invalid batching boundaries"):
    assertEquals(
      HotStuffTopicPolicy(
        exactKnownSetLimit = 0,
        requestByIdLimit = 1,
        maxBatchItems = 1,
        flushInterval = java.time.Duration.ZERO,
        deliveryPriority = 1,
      ),
      Left("exactKnownSetLimit must be positive"),
    )
    assertEquals(
      HotStuffTopicPolicy(
        exactKnownSetLimit = 1,
        requestByIdLimit = 0,
        maxBatchItems = 1,
        flushInterval = java.time.Duration.ZERO,
        deliveryPriority = 1,
      ),
      Left("requestByIdLimit must be positive"),
    )
    assertEquals(
      HotStuffTopicPolicy(
        exactKnownSetLimit = 1,
        requestByIdLimit = 1,
        maxBatchItems = 0,
        flushInterval = java.time.Duration.ZERO,
        deliveryPriority = 1,
      ),
      Left("maxBatchItems must be positive"),
    )
    assertEquals(
      HotStuffTopicPolicy(
        exactKnownSetLimit = 2,
        requestByIdLimit = 3,
        maxBatchItems = 4,
        flushInterval = java.time.Duration.ZERO,
        deliveryPriority = 5,
      ).map(_.requestByIdLimit),
      Right(3),
    )

    val negativeInterval =
      HotStuffDeploymentTarget(blockProductionInterval =
        java.time.Duration.ofMillis(-1),
      )
    assertEquals(
      negativeInterval,
      Left("blockProductionInterval must be non-negative"),
    )

  test("unsafe policy helpers throw on invalid boundaries"):
    val requestError =
      intercept[IllegalArgumentException]:
        HotStuffRequestPolicy.unsafe(
          maxProposalRequestIds = 0,
          maxVoteRequestIds = 1,
          maxRetryAttemptsPerWindow = 0,
        )
    assertEquals(
      requestError.getMessage,
      "maxProposalRequestIds must be positive",
    )

    val deploymentError =
      intercept[IllegalArgumentException]:
        HotStuffDeploymentTarget.unsafe(
          blockProductionInterval = java.time.Duration.ZERO,
        )
    assertEquals(
      deploymentError.getMessage,
      "blockProductionInterval must be positive",
    )

    val topicError =
      intercept[IllegalArgumentException]:
        HotStuffTopicPolicy.unsafe(
          exactKnownSetLimit = 1,
          requestByIdLimit = 0,
          maxBatchItems = 1,
          flushInterval = java.time.Duration.ZERO,
          deliveryPriority = 1,
        )
    assertEquals(topicError.getMessage, "requestByIdLimit must be positive")

  private def height(
      value: Long,
  ): HotStuffHeight =
    HotStuffHeight.unsafeFromLong(value)

  private def view(
      value: Long,
  ): HotStuffView =
    HotStuffView.unsafeFromLong(value)
