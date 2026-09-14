package org.sigilaris.conformance

import cats.effect.IO
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.ApplicationValidatorId
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.node.jvm.runtime.application.v2.*

/** A real signed consensus request spanning the protocol's full identity count
  * and all 256 supported witness chunks, including durable index replay.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object V2ReservationScaleConformance:
  import V2RequestConformance.*
  import V2VotingConformance.{accepted, rejected, memory}

  private def byteBoundaries(
      witness: ReservationWitness,
      ref: WitnessRef,
  ): Unit =
    val remaining = ProtocolLimits.V2.maxWitnessBytes - ref.encodedBytes
    val expanded  =
      witness.entries.foldLeft(remaining -> Vector.empty[WitnessEntry]) {
        case ((left, entries), entry) =>
          // Expanded identities retain their natural length width and unique prefix.
          val growth = if entry.identity.bytes.size >= 128L then
            math.min(left, 255L - entry.identity.bytes.size)
          else 0L
          val identity = inputId(
            entry.identity.bytes ++ ByteVector.fill(growth)(0.toByte),
          )
          (left - growth) -> (entries :+ entry.copy(identity = identity))
      }
    assert(expanded._1 == 0L)
    val atLimit = ReservationWitness(1L, expanded._2)
    val exact   = value(WitnessRef.fromWitness(atLimit))
    assert(
      exact.encodedBytes == ProtocolLimits.V2.maxWitnessBytes && exact.chunkCount == 256L,
    )
    val index = atLimit.entries.indexWhere(entry =>
      entry.identity.bytes.size >= 128L && entry.identity.bytes.size < 255L,
    )
    val entry = atLimit.entries(index)
    val over  = atLimit.copy(entries =
      atLimit.entries.updated(
        index,
        entry.copy(identity =
          inputId(entry.identity.bytes ++ ByteVector(0.toByte)),
        ),
      ),
    )
    assert(
      ReservationWitness.codec
        .encode(over)
        .left
        .exists(_.code == FailureCode.ProtocolLimitExceeded),
    )
    assert(
      WitnessChunk.codec
        .encode(
          WitnessChunk(
            1L,
            exact.witnessDigest,
            256L,
            257L,
            ByteVector(0.toByte),
          ),
        )
        .left
        .exists(_.code == FailureCode.ProtocolLimitExceeded),
    )

  def run(): IO[Unit] = IO.defer {
    val identities = (0 until 99996).toVector.map(index =>
      inputId(
        uint(index.toLong + 1000L).bytes ++ ByteVector.fill(
          if index < 49998 then 133L else 132L,
        )(0.toByte),
      ),
    )
    val fixture = new Fixture(91L, Authority.ConsensusOnly, 0, identities)
    for
      request <- accepted(
        fixture
          .proposalVerifier(fixture.executedCandidate)
          .verifyProposal(fixture.candidate, fixture.consensusPlan),
      )
      witness = request.reservations(0).witness
      ref     = value(WitnessRef.fromWitness(witness))
      _ <- IO(
        assert(
          ref.identityCount == 100000L && ref.chunkCount == 256L && ref.encodedBytes <= ProtocolLimits.V2.maxWitnessBytes,
        ),
      )
      _        <- IO(byteBoundaries(witness, ref))
      env      <- memory(fixture)
      prepared <- accepted(env.runtime(0).prepareConsensusVote(request))
      _        <- accepted(env.runtime(0).signConsensusVote(prepared))
      stored   <- accepted(env.store.snapshot)
      _        <- IO(
        assert(
          stored.claims.size == 1 && stored.index.size == 100000 && stored.consensusIntents.size == 1,
        ),
      )
      last <- accepted(env.store.readChunk(ref, 255))
      _    <- IO(
        assert(last.index == 255L && last.count == 256L && last.bytes.nonEmpty),
      )
      restarted <- env.reopen
      recovered <- accepted(restarted.store.snapshot)
      _         <- IO(
        assert(
          recovered.claims == stored.claims && recovered.index == stored.index && recovered.consensusIntents == stored.consensusIntents,
        ),
      )
      _ <- Vector(
        SafetyCapacity(0L, Long.MaxValue),
        SafetyCapacity(Long.MaxValue, ref.encodedBytes - 1L),
      ).traverse_ { capacity =>
        for
          lowJournal <- MemoryDurableJournal.create[IO]
          low        <- accepted(
            JournalSafetyStore.open(
              ApplicationAnchor(
                fixture.context,
                fixture.baseBlockId.toUInt256,
                height(5L),
                fixture.preStateRoot,
              ),
              lowJournal,
              env.publication,
              SafetyProfile(fixture.manifest, fixture.artifacts),
              SafetyRecoveryAuthentication.voting(env.recoveryRequests),
              ReservationOrdering.isolated[IO],
              capacity,
            ),
          )
          calls <- cats.effect.Ref.of[IO, Int](0)
          original = ApplicationVoteSigner.secp256k1[IO](
            ApplicationValidatorId(fixture.keys(0)._1),
            fixture.keys(0)._2,
          )
          counted = new ApplicationVoteSigner[IO]:
            def validatorId: ApplicationValidatorId = original.validatorId
            def sign(context: DomainContext, preimage: Bytes): IO[Bytes] =
              calls.update(_ + 1) *> original.sign(context, preimage)
          voter = DurableApplicationVoting.fromStore(
            low,
            counted,
            fixture.artifacts,
          )
          failure <- rejected(
            voter.prepareConsensusVote(request).flatMap(voter.signConsensusVote),
          )
          _ <- IO(
            assert(failure.code == RuntimeFailureCode.CapacityUnavailable),
          )
          snapshot <- accepted(low.snapshot)
          invoked  <- calls.get
          _        <- IO(
            assert(
              snapshot.claims.isEmpty && snapshot.consensusIntents.isEmpty && invoked == 0,
            ),
          )
        yield ()
      }
      overflowAccess = ActualAccess(
        inputId(ByteVector.fill(166L)(0xff.toByte)),
        AccessKind.ReadExisting,
        Some(Authority.ConsensusOnly),
      )
      overflow = fixture.executedCandidate.copy(entries =
        fixture.executedCandidate.entries.map(entry =>
          entry.copy(actualAccesses = entry.actualAccesses :+ overflowAccess),
        ),
      )
      overflowEnv <- memory(fixture)
      failure     <- rejected(
        fixture
          .proposalVerifier(overflow)
          .verifyProposal(fixture.candidate, fixture.consensusPlan)
          .flatMap(overflowEnv.runtime(0).prepareConsensusVote)
          .flatMap(overflowEnv.runtime(0).signConsensusVote),
      )
      _ <- IO(assert(failure.code == RuntimeFailureCode.ProtocolLimitExceeded))
      untouched <- accepted(overflowEnv.store.snapshot)
      _         <- IO(
        assert(untouched.claims.isEmpty && untouched.consensusIntents.isEmpty),
      )
      _ <- IO(
        println(
          s"V2ReservationScaleConformance PASS: identities=${ref.identityCount} bytes=${ref.encodedBytes} chunks=${ref.chunkCount}; complete durable index/restart, local capacity holds and limit+1 rejection",
        ),
      )
    yield ()
  }
