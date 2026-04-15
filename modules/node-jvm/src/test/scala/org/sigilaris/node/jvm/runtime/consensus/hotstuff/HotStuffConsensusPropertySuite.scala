package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Instant

import hedgehog.*
import hedgehog.munit.HedgehogSuite

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder, DecodeResult}
import org.sigilaris.core.crypto.{CryptoOps, KeyPair}
import org.sigilaris.core.datatype.UInt256
import org.sigilaris.node.gossip.{ChainId, StableArtifactId}

final class HotStuffConsensusPropertySuite extends HedgehogSuite:

  private val chainId   = ChainId.unsafe("chain-main")
  private val startedAt = Instant.parse("2026-04-10T00:00:00Z")

  private val genValidatorOrdinals: Gen[List[Int]] =
    Gen.list(Gen.int(Range.linear(1, 32)), Range.linear(1, 6)).map(_.distinct)

  private val genTxOrdinals: Gen[List[Long]] =
    Gen.list(Gen.long(Range.linear(0L, 128L)), Range.linear(0, 12))

  private val genPacemakerLocalIndex: Gen[Int] =
    Gen.int(Range.linear(0, 3))

  private val genPacemakerTimeoutWindows: Gen[Int] =
    Gen.int(Range.linear(0, 8))

  private val genWindowHeight: Gen[Long] =
    Gen.long(Range.linear(1L, 32L))

  private val genWindowView: Gen[Long] =
    Gen.long(Range.linear(0L, 32L))

  property("ValidatorSet hash is stable across insertion order"):
    for ordinals <- genValidatorOrdinals.forAll
    yield
      val members    = ordinals.map(validatorMember).toVector
      val reversed   = members.reverse
      val base       = ValidatorSet(members)
      val reordered  = ValidatorSet(reversed)
      val memberIds  = members.map(_.id)
      Result.all(
        List(
          Result.assert(base.isRight),
          Result.assert(reordered.isRight),
          Result.assert(base.map(_.hash) == reordered.map(_.hash)),
          Result.assert(base.map(_.members.map(_.id)) == Right(memberIds)),
        ),
      )

  property("ValidatorSet rejects generated duplicate ids and public keys"):
    for ordinal <- Gen.int(Range.linear(1, 32)).forAll
    yield
      val primary = validatorMember(ordinal)
      val other   = validatorMember(ordinal + 100)
      val duplicateIds =
        Vector(
          primary,
          ValidatorMember(
            id = primary.id,
            publicKey = other.publicKey,
          ),
        )
      val duplicateKeys =
        Vector(
          primary,
          ValidatorMember(
            id = ValidatorId.unsafe(s"validator-shadow-$ordinal"),
            publicKey = primary.publicKey,
          ),
        )
      Result.all(
        List(
          Result.assert(
            ValidatorSet(duplicateIds) == Left(ValidatorSetError.DuplicateIds),
          ),
          Result.assert(
            ValidatorSet(duplicateKeys) ==
              Left(ValidatorSetError.DuplicatePublicKeys),
          ),
        ),
      )

  property("ProposalTxSet canonicalization is idempotent and round-trips on the wire"):
    for ordinals <- genTxOrdinals.forAll
    yield
      val txIds =
        ordinals.map(proposalTxId).toVector
      val canonical =
        ProposalTxSet.canonical(ProposalTxSet(txIds))
      val reversed =
        ProposalTxSet.canonical(ProposalTxSet(txIds.reverse))
      val encoded =
        ByteEncoder[ProposalTxSet].encode(canonical)
      val decoded =
        ByteDecoder[ProposalTxSet].decode(encoded)
      Result.all(
        List(
          Result.assert(ProposalTxSet.isCanonical(canonical)),
          Result.assert(ProposalTxSet.canonical(canonical) == canonical),
          Result.assert(reversed == canonical),
          decoded match
            case Right(DecodeResult(value, remainder)) =>
              Result.all(
                List(
                  Result.assert(remainder.isEmpty),
                  Result.assert(value == canonical),
                ),
              )
            case Left(_) =>
              Result.failure,
        ),
      )

  property("Pacemaker tick follows the timeout-emission model across generated windows"):
    for
      localIndex <- genPacemakerLocalIndex.forAll
      consecutiveTimeoutWindows <- genPacemakerTimeoutWindows.forAll
      height <- genWindowHeight.forAll
      view <- genWindowView.forAll
      bootstrapHeld <- Gen.boolean.forAll
    yield
      val fixture      = pacemakerFixture()
      val runtime      =
        HotStuffPacemakerRuntime.default(
          localValidator = fixture.validatorSet.members(localIndex).id,
          validatorSet = fixture.validatorSet,
        )
      val activeWindow =
        HotStuffWindow.unsafe(chainId, height, view, fixture.validatorSet.hash)
      val initial =
        runtime.start(
          activeWindow = activeWindow,
          highestKnownQc = bootstrapQc(fixture),
          now = startedAt,
          bootstrapHoldReason =
            Option.when(bootstrapHeld)("forwardCatchUpUnavailable"),
        )
      val prepared =
        initial.state.copy(
          consecutiveTimeoutWindows = consecutiveTimeoutWindows,
          timeoutDeadline = startedAt,
          localTimeoutVoteRequested = false,
          localTimeoutVote = None,
        )
      val ticked = runtime.tick(prepared, startedAt)
      val expectedDeadline =
        startedAt.plus(
          runtime.timeoutFor(activeWindow, consecutiveTimeoutWindows + 1),
        )
      Result.all(
        List(
          Result.assert(ticked.outcome == HotStuffPacemakerStepOutcome.Applied),
          Result.assert(
            ticked.state.consecutiveTimeoutWindows ==
              consecutiveTimeoutWindows + 1,
          ),
          Result.assert(ticked.state.timeoutDeadline == expectedDeadline),
          if bootstrapHeld then
            Result.all(
              List(
                Result.assert(ticked.commands.isEmpty),
                Result.assert(!ticked.state.localTimeoutVoteRequested),
                Result.assert(
                  ticked.diagnostics.exists:
                    case HotStuffPacemakerDiagnostic.BootstrapHoldBlockedTimeout(
                          window,
                          reason,
                        ) =>
                      window == activeWindow && reason == "forwardCatchUpUnavailable"
                    case _ =>
                      false,
                ),
              ),
            )
          else
            Result.all(
              List(
                Result.assert(ticked.commands.size == 1),
                Result.assert(ticked.state.localTimeoutVoteRequested),
                Result.assert(
                  ticked.commands.headOption.contains(
                    HotStuffPacemakerCommand.EmitTimeoutVote(
                      voter = fixture.validatorSet.members(localIndex).id,
                      window = activeWindow,
                      highestKnownQc = bootstrapQc(fixture),
                    ),
                  ),
                ),
              ),
            ),
        ),
      )

  property("Pacemaker new-view advancement preserves the timeout streak model"):
    for
      localIndex <- genPacemakerLocalIndex.forAll
      consecutiveTimeoutWindows <- genPacemakerTimeoutWindows.forAll
      height <- genWindowHeight.forAll
      view <- genWindowView.forAll
    yield
      val fixture      = pacemakerFixture()
      val runtime      =
        HotStuffPacemakerRuntime.default(
          localValidator = fixture.validatorSet.members(localIndex).id,
          validatorSet = fixture.validatorSet,
        )
      val activeWindow =
        HotStuffWindow.unsafe(chainId, height, view, fixture.validatorSet.hash)
      val initial =
        runtime.start(activeWindow, bootstrapQc(fixture), startedAt, None)
      val nextWindow =
        HotStuffPacemaker.nextWindowAfter(activeWindow)
      val advanced =
        runtime.observeNewView(
          initial.state.copy(consecutiveTimeoutWindows = consecutiveTimeoutWindows),
          newViewFor(fixture, activeWindow, senderIndex = 0),
          startedAt.plusSeconds(1L),
        )
      Result.all(
        List(
          Result.assert(
            advanced.map(_.outcome) ==
              Right(HotStuffPacemakerStepOutcome.AdvancedWindow),
          ),
          Result.assert(
            advanced.map(_.state.activeWindow) == Right(nextWindow),
          ),
          Result.assert(
            advanced.map(_.state.consecutiveTimeoutWindows) ==
              Right(consecutiveTimeoutWindows),
          ),
          Result.assert(
            advanced.map(_.state.timeoutVotes) == Right(TimeoutVoteAccumulator.empty),
          ),
          Result.assert(
            advanced.map(_.state.localTimeoutVoteRequested) == Right(false),
          ),
          Result.assert(
            advanced.map(_.state.localNewViewRequested) == Right(false),
          ),
        ),
      )

  private final case class PacemakerFixture(
      validatorKeys: Vector[KeyPair],
      validatorSet: ValidatorSet,
  )

  private def pacemakerFixture(): PacemakerFixture =
    val validatorKeys =
      (1 to 4).toVector.map(index => keyPairFor(index))
    val validatorSet =
      ValidatorSet.unsafe(
        validatorKeys.zipWithIndex.map: (keyPair, index) =>
          ValidatorMember(
            id = ValidatorId.unsafe(s"validator-${index + 1}"),
            publicKey = keyPair.publicKey,
          ),
      )
    PacemakerFixture(
      validatorKeys = validatorKeys,
      validatorSet = validatorSet,
    )

  private def validatorMember(
      ordinal: Int,
  ): ValidatorMember =
    val keyPair = keyPairFor(ordinal)
    ValidatorMember(
      id = ValidatorId.unsafe(s"validator-$ordinal"),
      publicKey = keyPair.publicKey,
    )

  private def keyPairFor(
      ordinal: Int,
  ): KeyPair =
    CryptoOps.fromPrivate(BigInt(ordinal) + 1)

  private def proposalTxId(
      ordinal: Long,
  ): StableArtifactId =
    StableArtifactId.unsafeFromBytes(
      UInt256.unsafeFromBigIntUnsigned(BigInt(ordinal) + 1).bytes,
    )

  private def bootstrapQc(
      fixture: PacemakerFixture,
  ): QuorumCertificate =
    quorumCertificateFor(
      fixture = fixture,
      window = HotStuffWindow.unsafe(chainId, 0L, 0L, fixture.validatorSet.hash),
      proposalIdHex = "70",
      blockIdHex = "71",
    )

  private def quorumCertificateFor(
      fixture: PacemakerFixture,
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
          signedVoteFor(fixture, window, subject.proposalId, 0),
          signedVoteFor(fixture, window, subject.proposalId, 1),
          signedVoteFor(fixture, window, subject.proposalId, 2),
        ),
        fixture.validatorSet,
      )
      .toOption
      .get

  private def signedVoteFor(
      fixture: PacemakerFixture,
      window: HotStuffWindow,
      proposalId: ProposalId,
      index: Int,
  ): Vote =
    Vote
      .sign(
        UnsignedVote(window, fixture.validatorSet.members(index).id, proposalId),
        fixture.validatorKeys(index),
      )
      .toOption
      .get

  private def signedTimeoutVoteFor(
      fixture: PacemakerFixture,
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
          voter = fixture.validatorSet.members(index).id,
        ),
        fixture.validatorKeys(index),
      )
      .toOption
      .get

  private def timeoutCertificateFor(
      fixture: PacemakerFixture,
      window: HotStuffWindow,
      highestKnownQc: QuorumCertificate,
  ): TimeoutCertificate =
    TimeoutCertificateAssembler
      .assemble(
        TimeoutVoteSubject(window, highestKnownQc.subject),
        Vector(
          signedTimeoutVoteFor(fixture, window, highestKnownQc, 0),
          signedTimeoutVoteFor(fixture, window, highestKnownQc, 1),
          signedTimeoutVoteFor(fixture, window, highestKnownQc, 2),
        ),
        fixture.validatorSet,
      )
      .toOption
      .get

  private def newViewFor(
      fixture: PacemakerFixture,
      timeoutWindow: HotStuffWindow,
      senderIndex: Int,
  ): NewView =
    val highestKnownQc =
      bootstrapQc(fixture)
    val timeoutCertificate =
      timeoutCertificateFor(fixture, timeoutWindow, highestKnownQc)
    val nextWindow =
      HotStuffPacemaker.nextWindowAfter(timeoutWindow)
    NewView
      .sign(
        UnsignedNewView(
          window = nextWindow,
          sender = fixture.validatorSet.members(senderIndex).id,
          nextLeader =
            HotStuffPacemaker.deterministicLeader(nextWindow, fixture.validatorSet),
          highestKnownQc = highestKnownQc,
          timeoutCertificate = timeoutCertificate,
        ),
        fixture.validatorKeys(senderIndex),
      )
      .toOption
      .get

  private def hex(
      value: String,
  ): UInt256 =
    UInt256.fromHex(value).toOption.get
