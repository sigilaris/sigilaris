package org.sigilaris.node.jvm.runtime.application.v2

import org.sigilaris.core.application.protocol.ExecutionId
import org.sigilaris.core.application.protocol.v2.*

/** Rebuilt from canonical journal records; this is not a second storage schema.
  */
final case class SafetyState(
    committed: Vector[JournalRecord],
    intents: Map[Hash, VoteIntent],
    consensusIntents: Map[Hash, ConsensusVoteIntent],
    locks: Map[ExecutionId, LiveLockClaim],
    claims: Map[Hash, ReservationClaim],
    witnesses: Map[Hash, WitnessRef],
    index: Vector[ConflictIndexRow],
    lockCertificates: Map[Hash, LockCertificate],
    effectCertificates: Map[Hash, EffectCertificate],
    preparations: Map[Hash, PreparedApplication],
    decisions: Map[Hash, ApplicationDecision],
    appliedEntries: Map[ExecutionId, AppliedIndexRecord],
    canonical: Option[ApplicationDecision],
    exactRegistrations: Map[String, ExactRegistration],
    exactUpdates: Map[String, ExactRecordUpdate],
    fences: Vector[SignedFencePromise],
):
  def sequence: Long = committed.lastOption.fold(0L)(_.sequence)

object SafetyState:
  val empty: SafetyState = SafetyState(
    Vector.empty,
    Map.empty,
    Map.empty,
    Map.empty,
    Map.empty,
    Map.empty,
    Vector.empty,
    Map.empty,
    Map.empty,
    Map.empty,
    Map.empty,
    Map.empty,
    None,
    Map.empty,
    Map.empty,
    Vector.empty,
  )

final case class VotingRecovery(
    highestSequence: Long,
    inventoryDigest: Hash,
    indexDigest: Hash,
)

final case class ReservationRecovery(
    highestSequence: Long,
    inventoryDigest: Hash,
    indexDigest: Hash,
)

final case class ApplicationRecovery(
    selectedDecision: Option[ApplicationDecision],
    highestSequence: Long,
    inventoryDigest: Hash,
)

/** Supplied from the authenticated installed checkpoint/bootstrap identity. */
final case class ApplicationAnchor(
    context: DomainContext,
    blockId: Hash,
    height: Height,
    stateRoot: Hash,
)

trait SafetyPublication[F[_]]:
  /** Authenticated canonical finality; local clock/height guesses are invalid.
    */
  def finalizedHeight(context: DomainContext): Result[F, Height]

  /** Rechecks the independently executed fast request against current state. */
  def verifyEffectState(request: VerifiedEffectRequest): Result[F, Unit]

  /** Rechecks the executed parent/branch immediately before reserving/signing.
    */
  def verifyConsensusState(request: VerifiedConsensusProposal): Result[F, Unit]

/** Only a configured verifier may authorize overlap across distinct proposals.
  * It must resolve and authenticate their branch/order proof, not trust a
  * digest as evidence. Same-plan ordered overlap is checked directly by the
  * store.
  */
trait ReservationOrdering[F[_]]:
  def verify(existing: Owner, incoming: Owner): Result[F, Unit]

object ReservationOrdering:
  def isolated[F[_]: cats.Applicative]: ReservationOrdering[F] =
    new ReservationOrdering[F]:
      def verify(existing: Owner, incoming: Owner): Result[F, Unit] =
        cats.data.EitherT.leftT[F, Unit](
          V2RuntimeFailure.at(
            RuntimeFailureCode.Conflict,
            "overlap requires authenticated consensus branch/order evidence",
          ),
        )
