package org.sigilaris.node.jvm.runtime.application.v2

import org.sigilaris.core.application.protocol.ExecutionId
import org.sigilaris.core.application.protocol.v2.*

/** Locally configured trust, not evidence supplied by a remote caller. Roles
  * are deliberately separated: archive authority cannot sign a fence or select
  * a rollback. This policy and baseline stay fixed for the transition lifetime.
  */
enum TransitionAuthorityRole:
  case Bootstrap, HistoricalAbsence, NeverEnabled, Restore
final case class TrustedTransitionAuthority(
    id: Text,
    publicKey: Bytes,
    roles: Set[TransitionAuthorityRole],
)
final case class TransitionPolicy(
    intent: TransitionIntent,
    targetManifest: ProtocolManifest,
    baseline: EvidenceBaseline,
    authorities: Vector[TrustedTransitionAuthority],
    sourceAndRetiredDomains: Vector[Text],
    applicationDomains: Vector[DomainContext],
)

/** All reads are original immutable evidence. Missing bytes fail; a new absence
  * attestation is never synthesized as a fallback for this fixed baseline.
  */
trait TransitionEvidenceRepository[F[_]]:
  def read(reference: EvidenceRef): Result[F, Bytes]
  def baseline(digest: Hash): Result[F, EvidenceBaseline]
  def sourceFence(digest: Hash): Result[F, SignedFencePromise]
  def restoreAuthentication(
      evidence: RestoreEvidence,
  ): Result[F, SignatureEnvelope]

final case class EvidenceArtifact(reference: EvidenceRef, bytes: Bytes)
final case class InventoryEntry(namespace: Text, key: Text, digest: Hash)
final case class ScopedInterval(id: Text, start: Long, end: Long)
final case class SourceSnapshotClosure(
    source: SourceBinding,
    dataInventory: Vector[InventoryEntry],
    statePayload: Bytes,
    ancestry: SourceAncestry,
    retiredDomains: Vector[Text],
    sourceAuthorityScopeDigest: Hash,
    sourceFence: SignedFencePromise,
)
final case class SurvivingApplicationInventory(
    context: DomainContext,
    inventoryDigest: Hash,
    issuedVoteSubjects: Vector[Hash],
    liveLockClaims: Vector[Hash],
    liveReservationOwners: Vector[Hash],
    nonterminalExactExecutions: Vector[ExecutionId],
)
final case class NeverEnabledAudit(
    domain: Text,
    lifetimeStart: Long,
    lifetimeEnd: Long,
    authorizedSignerInventory: Hash,
    authorizedSigners: Vector[ScopedInterval],
    deployments: Vector[ScopedInterval],
    survivingStores: Vector[SurvivingApplicationInventory],
)
final case class HistoricalAbsenceAudit(
    retiredDomain: Text,
    sourceBinding: Hash,
    targetChain: Text,
    missingScopes: Vector[Text],
    baselineDigest: Hash,
    independentlyEstablishedBeforeBaseline: Vector[Text],
    survivingStores: Vector[SurvivingApplicationInventory],
)
final case class PresentArchiveAudit(
    archive: PresentArchive,
    contents: Vector[InventoryEntry],
    presentScopes: Vector[Text],
    retirementFence: SignedFencePromise,
)
final case class EnforcedFenceAudit(
    promise: SignedFencePromise,
    signerPublicKey: Bytes,
    authorizedScopeDigest: Hash,
    originalSignedHeights: Vector[Height],
    enforcementInventory: Vector[InventoryEntry],
)
final case class ExactDeadlineRecord(
    context: DomainContext,
    executionId: ExecutionId,
    deadline: Height,
    planDigest: Hash,
    canonicalRecord: Bytes,
)
final case class DurableIssuedSubject(
    subjectDigest: Hash,
    context: DomainContext,
    executionId: ExecutionId,
    deadline: Height,
    canonicalSubject: Bytes,
    ownDurableRecord: Bytes,
    recordSequence: Long,
    issuanceSequence: Long,
    exactPlan: Option[ExactDeadlineRecord],
)
final case class OriginalDomainFinality(
    context: DomainContext,
    checkpointId: Hash,
    height: Height,
    stateRoot: Hash,
    evidenceDigest: Hash,
    originalProof: Bytes,
)
final case class DrainAudit(
    context: DomainContext,
    fence: SignedFencePromise,
    expectedIssuedSubjectIds: Vector[Hash],
    coveredSubjects: Vector[DurableIssuedSubject],
    reconciledInventoryDigest: Option[Hash],
    greatestRecordedDeadline: Option[Height],
    authenticatedBaseUpperBound: Option[Height],
    oldMaximumLifetime: Long,
    finality: Option[OriginalDomainFinality],
    zeroLiveEvidenceDigest: Option[Hash],
    survivingStores: Vector[SurvivingApplicationInventory],
)
final case class HistoricalFenceSet(
    context: DomainContext,
    validators: Vector[InitialValidator],
)
final case class ContinuationAudit(
    source: DomainContext,
    target: DomainContext,
    drainCheckpointId: Hash,
    drainCheckpointHeight: Height,
    drainStateRoot: Hash,
    parentId: Hash,
    parentHeight: Height,
    parentStateRoot: Hash,
    retainedSuffix: Vector[CertifiedSuffixEntry],
    retainedSafety: Vector[ConsensusSafetyBinding],
    historicalFenceSets: Vector[HistoricalFenceSet],
    allOldSignedHeights: Vector[Height],
)
final case class RestoreAudit(
    sourceGroupDigest: Hash,
    sourceGroupRecord: Bytes,
    currentSafetyInventoryDigest: Hash,
    sourceGroup: Vector[InventoryEntry],
    currentSafetyInventory: Vector[InventoryEntry],
    requiredFenceContexts: Vector[DomainContext],
    currentPromises: Vector[InventoryEntry],
    preservedPromises: Vector[InventoryEntry],
    currentCanonicalWrites: Vector[InventoryEntry],
    preservedCanonicalWrites: Vector[InventoryEntry],
    sourceFences: Vector[SignedFencePromise],
)

/** Required application/deployment-specific proof interpreters. There is no
  * permissive implementation. Every method reads/verifies the original proof
  * closure from `evidence`, authenticates it against independently installed
  * deployment/schema/consensus trust, and returns recomputed facts. The
  * returned products themselves are untrusted data, never signing capabilities.
  *
  * `source` decodes every state blob, checks complete inventory and actual
  * root, verifies schema/provenance/replay policy, source selection and
  * non-ancestry, and establishes that the source write fence preceded snapshot
  * selection. `neverEnabled` independently authenticates the entire historical
  * signer and deployment roster, binary/configuration capabilities and every
  * alternate key path; it must not copy the submitted roster as its scope of
  * completeness. `absence` verifies independent rollback/non-ancestry/source
  * evidence and the pre-baseline absence event, including unknown dates
  * explicitly recorded by the attestation. Later loss fails even with a newly
  * signed attestation. `fence` checks active durable key/write controls and the
  * complete historical watermark, including already-issued subjects, on every
  * invocation. A signed promise alone is insufficient. `drain` authenticates
  * original subject and own durable deadline records before issuance, exact
  * plan links, original finality/nonapplication and reconciled stores across
  * the full signer scope. `continuation` verifies every historical
  * proposal/QC/profile/state replay, original highQC/lockedQC/watermarks, all
  * still-certifiable validator sets and the actual historical safe-vote rule
  * for the selected continuation branch. `restore` authenticates the complete
  * stopped-node consistency group, current promise/canonical-write histories
  * and proposed preservation inventory.
  */
trait TransitionAuditAuthentication[F[_]]:
  def artifact(reference: EvidenceRef, bytes: Bytes): Result[F, Unit]
  def source(
      binding: SourceBinding,
      evidence: TransitionEvidenceRepository[F],
  ): Result[F, SourceSnapshotClosure]
  def neverEnabled(
      record: NeverEnabled,
      evidence: TransitionEvidenceRepository[F],
  ): Result[F, NeverEnabledAudit]
  def absence(
      record: ArchiveAbsence,
      baseline: EvidenceBaseline,
      evidence: TransitionEvidenceRepository[F],
  ): Result[F, HistoricalAbsenceAudit]
  def presentArchive(
      record: PresentArchive,
      evidence: TransitionEvidenceRepository[F],
  ): Result[F, PresentArchiveAudit]
  def fence(
      promise: SignedFencePromise,
      intent: TransitionIntent,
      evidence: TransitionEvidenceRepository[F],
  ): Result[F, EnforcedFenceAudit]
  def drain(
      record: DrainEvidence,
      evidence: TransitionEvidenceRepository[F],
  ): Result[F, DrainAudit]
  def continuation(
      record: HandoverEvidence,
      evidence: TransitionEvidenceRepository[F],
  ): Result[F, ContinuationAudit]

  /** Rechecks actual current writer/key controls, including newly active target
    * keys. The authorized scope is the complete current safety inventory; this
    * does not relax ordinary source snapshot/fence authority checks.
    */
  def restoreFence(
      promise: SignedFencePromise,
      restore: RestoreEvidence,
      policy: TransitionPolicy,
      evidence: TransitionEvidenceRepository[F],
  ): Result[F, EnforcedFenceAudit]
  def restore(
      record: RestoreEvidence,
      evidence: TransitionEvidenceRepository[F],
  ): Result[F, RestoreAudit]
