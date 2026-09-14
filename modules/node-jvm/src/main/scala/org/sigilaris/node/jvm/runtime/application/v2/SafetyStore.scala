package org.sigilaris.node.jvm.runtime.application.v2

import org.sigilaris.core.application.protocol.v2.*

/** One serialized safety boundary shared by application voters, reservations
  * and canonical application. Implementations start fenced until recovery.
  */
trait SafetyStore[F[_]] extends ReservationWitnessStore[F]:
  def context: DomainContext
  def snapshot: Result[F, SafetyState]
  def claimLock(
      request: VerifiedLockRequest,
      validatorId: Text,
  ): Result[F, VoteIntent]
  def claimEffect(
      request: VerifiedEffectRequest,
      validatorId: Text,
  ): Result[F, VoteIntent]
  def claimConsensus(
      request: VerifiedConsensusProposal,
      validatorId: Text,
  ): Result[F, ConsensusVoteIntent]
  def withSigningPermission[A](intentDigest: Hash)(sign: F[A]): Result[F, A]
  def importLock(certificate: VerifiedLockCertificate): Result[F, Unit]
  def importEffect(certificate: VerifiedEffectCertificate): Result[F, Unit]
  def recover: Result[F, VotingRecovery]
