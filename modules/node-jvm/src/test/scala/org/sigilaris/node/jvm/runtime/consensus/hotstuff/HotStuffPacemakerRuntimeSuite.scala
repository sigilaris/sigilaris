package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Instant

import munit.FunSuite

import org.sigilaris.core.crypto.CryptoOps
import org.sigilaris.core.datatype.UInt256
import org.sigilaris.node.jvm.runtime.block.BlockId
import org.sigilaris.node.jvm.runtime.gossip.ChainId

final class HotStuffPacemakerRuntimeSuite extends FunSuite:

  private val chainId       = ChainId.unsafe("chain-main")
  private val startedAt     = Instant.parse("2026-04-03T00:00:00Z")
  private val validatorKeys = Vector.fill(4)(CryptoOps.generate())
  private val validatorSet = ValidatorSet.unsafe(
    validatorKeys.zipWithIndex.map: (keyPair, index) =>
      ValidatorMember(
        ValidatorId.unsafe(s"validator-${index + 1}"),
        keyPair.publicKey,
      ),
  )

  test(
    "local leader activation and timeout backoff are deterministic across timeout windows",
  ):
    val runtime =
      HotStuffPacemakerRuntime.default(
        localValidator = validatorSet.members(1).id,
        validatorSet = validatorSet,
      )
    val activeWindow = HotStuffWindow(chainId, 2L, 1L, validatorSet.hash)
    val initial = runtime.start(
      activeWindow = activeWindow,
      highestKnownQc = bootstrapQc(),
      now = startedAt,
      bootstrapHoldReason = None,
    )

    assertEquals(initial.outcome, HotStuffPacemakerStepOutcome.Started)
    assertEquals(
      initial.commands,
      Vector(
        HotStuffPacemakerCommand.ActivateLeader(
          window = activeWindow,
          leader = validatorSet.members(1).id,
        ),
      ),
    )
    assertEquals(
      initial.state.timeoutDeadline,
      startedAt.plus(runtime.timeoutFor(activeWindow, 0)),
    )

    val timedOut =
      runtime.tick(initial.state, initial.state.timeoutDeadline)
    val expectedNextWindow =
      HotStuffPacemaker.nextWindowAfter(activeWindow)

    assertEquals(timedOut.outcome, HotStuffPacemakerStepOutcome.Applied)
    assertEquals(timedOut.state.consecutiveTimeoutWindows, 1)
    assertEquals(
      timedOut.commands,
      Vector(
        HotStuffPacemakerCommand.EmitTimeoutVote(
          voter = validatorSet.members(1).id,
          window = activeWindow,
          highestKnownQc = bootstrapQc(),
        ),
      ),
    )
    assert(
      runtime
        .timeoutFor(activeWindow, 1)
        .compareTo(runtime.timeoutFor(activeWindow, 0)) > 0,
    )

    val advanced = runtime.observeNewView(
      timedOut.state,
      newViewFor(activeWindow, bootstrapQc(), senderIndex = 0),
      timedOut.state.timeoutDeadline.plusMillis(1),
    )

    assertEquals(
      advanced.map(_.outcome),
      Right(HotStuffPacemakerStepOutcome.AdvancedWindow),
    )
    assertEquals(advanced.map(_.state.activeWindow), Right(expectedNextWindow))
    assertEquals(
      advanced.map(_.proposalEligibility),
      Right(
        HotStuffPacemakerProposalEligibility.Follower(
          expectedLeader = validatorSet.members(2).id,
        ),
      ),
    )

  test("bootstrap hold suppresses local pacemaker emission until released"):
    val runtime =
      HotStuffPacemakerRuntime.default(
        localValidator = validatorSet.members.head.id,
        validatorSet = validatorSet,
      )
    val activeWindow = HotStuffWindow(chainId, 1L, 0L, validatorSet.hash)
    val initial = runtime.start(
      activeWindow = activeWindow,
      highestKnownQc = bootstrapQc(),
      now = startedAt,
      bootstrapHoldReason = Some("forwardCatchUpUnavailable"),
    )

    assertEquals(
      initial.proposalEligibility,
      HotStuffPacemakerProposalEligibility.BootstrapHeld(
        reason = "forwardCatchUpUnavailable",
        expectedLeader = validatorSet.members.head.id,
      ),
    )
    assertEquals(initial.commands, Vector.empty)

    val timedOut =
      runtime.tick(initial.state, initial.state.timeoutDeadline)

    assertEquals(timedOut.commands, Vector.empty)
    assertEquals(
      timedOut.diagnostics,
      Vector(
        HotStuffPacemakerDiagnostic.BootstrapHoldBlockedTimeout(
          window = activeWindow,
          reason = "forwardCatchUpUnavailable",
        ),
      ),
    )

    val released =
      runtime.updateBootstrapHold(timedOut.state, None)

    assertEquals(
      released.commands,
      Vector(
        HotStuffPacemakerCommand.ActivateLeader(
          window = activeWindow,
          leader = validatorSet.members.head.id,
        ),
      ),
    )
    assertEquals(
      released.proposalEligibility,
      HotStuffPacemakerProposalEligibility.EligibleAsLeader(
        validatorSet.members.head.id,
      ),
    )

  test(
    "homogeneous timeout quorum emits new-view while divergent subjects surface diagnostics",
  ):
    val runtime =
      HotStuffPacemakerRuntime.default(
        localValidator = validatorSet.members.head.id,
        validatorSet = validatorSet,
      )
    val activeWindow   = HotStuffWindow(chainId, 1L, 1L, validatorSet.hash)
    val highestKnownQc = bootstrapQc()
    val alternateHighestKnownQc = alternateQc()
    val initial =
      runtime.start(activeWindow, highestKnownQc, startedAt, None)

    val ticked = runtime.tick(initial.state, initial.state.timeoutDeadline)

    val localVote   = signedTimeoutVoteFor(activeWindow, highestKnownQc, 0)
    val remoteVote2 = signedTimeoutVoteFor(activeWindow, highestKnownQc, 1)
    val remoteVote3 = signedTimeoutVoteFor(activeWindow, highestKnownQc, 2)
    val divergentVote =
      signedTimeoutVoteFor(activeWindow, alternateHighestKnownQc, 3)

    val afterLocal =
      runtime.observeTimeoutVote(ticked.state, localVote).toOption.get
    val afterSecond =
      runtime.observeTimeoutVote(afterLocal.state, remoteVote2).toOption.get
    val afterThird =
      runtime.observeTimeoutVote(afterSecond.state, remoteVote3).toOption.get
    val afterDivergent =
      runtime.observeTimeoutVote(afterThird.state, divergentVote).toOption.get

    assertEquals(
      afterThird.commands,
      Vector(
        HotStuffPacemakerCommand.EmitNewView(
          sender = validatorSet.members.head.id,
          highestKnownQc = highestKnownQc,
          timeoutCertificate =
            timeoutCertificateFor(activeWindow, highestKnownQc),
        ),
      ),
    )
    assertEquals(
      afterDivergent.diagnostics,
      Vector(
        HotStuffPacemakerDiagnostic.DivergentTimeoutSubjects(
          window = activeWindow,
          subjects = Set(
            highestKnownQc.subject,
            alternateHighestKnownQc.subject,
          ),
        ),
      ),
    )
    assertEquals(
      afterDivergent.state.timeoutCertificate.map(_.subject),
      Some(TimeoutVoteSubject(activeWindow, highestKnownQc.subject)),
    )

  test(
    "wrong-window new-view is rejected and already advanced windows are dropped as stale",
  ):
    val runtime =
      HotStuffPacemakerRuntime.default(
        localValidator = validatorSet.members.head.id,
        validatorSet = validatorSet,
      )
    val activeWindow = HotStuffWindow(chainId, 1L, 1L, validatorSet.hash)
    val initial =
      runtime.start(activeWindow, bootstrapQc(), startedAt, None)
    val validNewView = newViewFor(activeWindow, bootstrapQc(), senderIndex = 0)
    val jumpedNewView =
      newViewFor(
        HotStuffPacemaker.nextWindowAfter(activeWindow),
        bootstrapQc(),
        senderIndex = 1,
      )

    val advanced =
      runtime.observeNewView(
        initial.state,
        validNewView,
        startedAt.plusMillis(1),
      )
    val stale =
      advanced.flatMap(step =>
        runtime
          .observeNewView(step.state, validNewView, startedAt.plusMillis(2)),
      )
    val wrongWindow =
      runtime.observeNewView(
        initial.state,
        jumpedNewView,
        startedAt.plusMillis(1),
      )

    assertEquals(
      advanced.map(_.outcome),
      Right(HotStuffPacemakerStepOutcome.AdvancedWindow),
    )
    assertEquals(
      stale.map(_.outcome),
      Right(HotStuffPacemakerStepOutcome.Stale),
    )
    assertEquals(wrongWindow.left.map(_.reason), Left("wrongPacemakerWindow"))

  private def bootstrapQc(): QuorumCertificate =
    quorumCertificateFor(
      window = HotStuffWindow(chainId, 0L, 0L, validatorSet.hash),
      proposalIdHex = "70",
      blockIdHex = "71",
    )

  private def alternateQc(): QuorumCertificate =
    quorumCertificateFor(
      window = HotStuffWindow(chainId, 0L, 0L, validatorSet.hash),
      proposalIdHex = "72",
      blockIdHex = "73",
    )

  private def quorumCertificateFor(
      window: HotStuffWindow,
      proposalIdHex: String,
      blockIdHex: String,
  ): QuorumCertificate =
    val subject = QuorumCertificateSubject(
      window = window,
      proposalId = ProposalId(hex(proposalIdHex)),
      blockId = BlockId(hex(blockIdHex)),
    )
    QuorumCertificateAssembler
      .assemble(
        subject,
        Vector(
          signedVoteFor(window, subject.proposalId, 0),
          signedVoteFor(window, subject.proposalId, 1),
          signedVoteFor(window, subject.proposalId, 2),
        ),
        validatorSet,
      )
      .toOption
      .get

  private def signedVoteFor(
      window: HotStuffWindow,
      proposalId: ProposalId,
      index: Int,
  ): Vote =
    Vote
      .sign(
        UnsignedVote(window, validatorSet.members(index).id, proposalId),
        validatorKeys(index),
      )
      .toOption
      .get

  private def signedTimeoutVoteFor(
      window: HotStuffWindow,
      highestKnownQc: QuorumCertificate,
      index: Int,
  ): TimeoutVote =
    TimeoutVote
      .sign(
        UnsignedTimeoutVote(
          subject = TimeoutVoteSubject(
            window = window,
            highestKnownQc = highestKnownQc.subject,
          ),
          voter = validatorSet.members(index).id,
        ),
        validatorKeys(index),
      )
      .toOption
      .get

  private def timeoutCertificateFor(
      window: HotStuffWindow,
      highestKnownQc: QuorumCertificate,
  ): TimeoutCertificate =
    TimeoutCertificateAssembler
      .assemble(
        TimeoutVoteSubject(window, highestKnownQc.subject),
        Vector(
          signedTimeoutVoteFor(window, highestKnownQc, 0),
          signedTimeoutVoteFor(window, highestKnownQc, 1),
          signedTimeoutVoteFor(window, highestKnownQc, 2),
        ),
        validatorSet,
      )
      .toOption
      .get

  private def newViewFor(
      timeoutWindow: HotStuffWindow,
      highestKnownQc: QuorumCertificate,
      senderIndex: Int,
  ): NewView =
    val timeoutCertificate =
      timeoutCertificateFor(timeoutWindow, highestKnownQc)
    val nextWindow =
      HotStuffPacemaker.nextWindowAfter(timeoutWindow)
    NewView
      .sign(
        UnsignedNewView(
          window = nextWindow,
          sender = validatorSet.members(senderIndex).id,
          nextLeader =
            HotStuffPacemaker.deterministicLeader(nextWindow, validatorSet),
          highestKnownQc = highestKnownQc,
          timeoutCertificate = timeoutCertificate,
        ),
        validatorKeys(senderIndex),
      )
      .toOption
      .get

  private def hex(
      value: String,
  ): UInt256 =
    UInt256.fromHex(value).toOption.get
