# V2 exact pipeline schema and API freeze

This is the exact-pipeline part of the [V2 contract](v2-contract.md). It uses the exact primitive codecs and core products in the
[core schema](v2-core-schema.md), and the application recovery boundary in the [runtime schema](v2-runtime-schema.md).
These are selected contracts, not passing execution or artifact evidence. Existing `exactV2`, plan-v1 domains and schema-2 records remain unchanged.

## Acyclic signed intent and derived execution identities

The signed dependency intent must not contain execution ids whose own preimages contain that intent's digest. Define the following products
in `org.sigilaris.node.txpipeline.v2`; `Hash`, `Text`, `Bytes`, `Height` and canonical encoding have the core schema meanings.

```scala
enum ExactMode:
  case OrderedAtomic, CertifiedAncestor
final case class StageIntent(
  txId: Hash, signedTransaction: Bytes, manifestDigest: Hash, sourceKind: Byte,
  declaration: Declaration, fullInputCommitment: Hash, lockSubsetCommitment: Hash,
)
final case class ExactPlanIntent(
  format: Long, context: DomainContext, applicationPipelineId: Text,
  profile: DependencyProfileBinding, laneId: Text, mode: ExactMode,
  stages: Vector[StageIntent], producerIndex: Long, consumerIndex: Long,
  outputSlot: Long, consumerFieldId: Text, referenceSchemaDigest: Hash,
  consumerReference: Bytes, lastInclusionHeight: Height,
)
final case class SignedExactPlan(intent: ExactPlanIntent, authorization: Bytes)
final case class ExactIdentityBinding(
  format: Long, context: DomainContext, nodePipelineId: Text,
  applicationPipelineId: Text, signedPlanDigest: Hash,
  executionIds: Vector[ExecutionId],
)
final case class ExactSubmitRequest(
  format: Long, signedPlan: SignedExactPlan, idempotencyKey: Option[Text],
)
```

Intent/signed-plan format is 2; request and identity-binding format is 3. Mode tags are OrderedAtomic=1 and CertifiedAncestor=2.
There are exactly two distinct stage transaction ids and stage byte strings in signed order. ProducerIndex is 0 and consumerIndex is 1;
outputSlot is a nonnegative profile-defined index. Reference bytes are nonempty and verified by the authenticated profile. Lane id, application
pipeline id and optional idempotency key use canonical nonempty text. All other integer fields use the core I64 convention; the deadline is Height.
Each stage sourceKind is the core ConsensusTransaction=1 or CertifiedFastExecution=2 tag and is fixed by the signed intent. Certificate ids are
attached later in the block plan and cannot enter this pre-execution intent, since their subjects depend on the derived execution identity.

Stages carry independently derived pre-execution input commitments and declarations. They contain neither final result/actual-footprint roots,
the containing execution-plan root, nor a resulting block id. Transaction verification checks each transaction's signed deadline equals the
common outer deadline, including an empty-lock stage. The outer authorization signs the canonical intent and binds both exact stage subjects,
the dependency edge, profile, domain and deadline. The manifest-bound profile verifier validates authorization, each transaction's signature
and its replay policy; neither a node id nor an outer digest alone authorizes someone else's transaction.

The ordered derivation is:

1. Authenticate/canonically decode both signed application transactions and the signed outer intent. The application family contract must not
   require this outer digest inside the very transaction bytes being hashed here; such a cyclic codec is unsupported.
2. Compute `signedPlanDigest = H("sigilaris.tx-pipeline.exact.plan.v2", SignedExactPlan)` from the canonical complete signed outer envelope.
3. Compute each core ExecutionIdentity from its stage transaction/input/declaration fields, context, `dependencyPlanDigest=signedPlanDigest`
   and the common deadline. Neither execution id is an input to step 2.
4. Compute the binding digest from ExactIdentityBinding, using `sigilaris.tx-pipeline.exact.identity-binding.v2`. The node pipeline id is an
   independently allocated canonical request identity, not a digest that refers back to this binding.

`sigilaris.tx-pipeline.exact.reference.v2` hashes the canonical length-prefixed consumerReference bytes. Authorization verification signs
`Commitment.preimage("sigilaris.tx-pipeline.exact.plan.v2", Tag(0) ++ ExactPlanIntent.codec.encode(intent))`; the signed-plan digest hashes
the SignedExactPlan product under that domain without the signing-only prefix. The payload shapes differ explicitly and are not interchangeable.
Profile validation checks exactly one producer output to consumer reference edge, stage ordering, output type/value, lane, epoch/configuration
and replay ownership. Cross-lane, cross-pipeline, cross-epoch/configuration live handoff, extra stages and arbitrary DAGs remain rejected.

The initial registered output binding is an exact reference to the producer's normalized **execution output**, identified deterministically
from signed producer bytes and outputSlot. `consumerFieldId` names one explicit Opaque field in the consumer's fixed InputManifest; its schema
digest equals referenceSchemaDigest and its canonical value equals consumerReference. The complete consumer signedTransaction and full-input
commitment bind those reference bytes before admission. It is an explicitly named execution-output reference, not a hidden existing-state
Exact reference and not a field filled later with the producer's latest output. Core Opaque structural rules still require no mutable stable id,
precondition or authority on that reference field. All ordinary existing-state Exact fields remain independently authenticated as usual.

Admission verifies the profile's deterministic reference derivation from the producer, consumer signature/reference/schema equality and durable
same-pipeline ownership. It verifies the signed promise, not a claim that the output already exists. Consumption additionally verifies actual
producer execution output/result digest under that profile, using immediately preceding working execution for OrderedAtomic or authentic
block certification/ancestor proof for CertifiedAncestor. Only that registered consumer receives the verified output value; its descriptor is
not rebound or rehashed after output production. The result bytes and producer proof are execution witnesses, not replacements for signed input.

If an application output also denotes concrete state, every resulting concrete read/write/creation is still independently declared and
instrumented in the full footprint. This reference binding grants no exemption for existing lock-eligible mutations or generic Exact freshness;
such an application must satisfy those ordinary input/certificate rules. Profiles requiring an undeclared mutable lookup, missing input lock,
or a different producer-output binding are rejected rather than converted into Opaque state access. The neutral conformance profile returns
opaque normalized output bytes and uses separate declared state inputs/creation targets, so both stages' lock combinations remain exercisable.

## Persistent schema 3

Canonical persistent products are binary and live in explicit schema-3 namespaces. The public transport discriminator is `exactV3`.
An `exactV3` transport envelope contains `kind="exactV3"` and `payload`, the lowercase hex of the full canonical ExactSubmitRequest bytes;
decoding rejects extra JSON fields, nonlowercase/odd hex, wrong embedded format and trailing canonical bytes. Old envelopes retain old JSON codecs.

```scala
enum ExactStageLifecycle:
  case Accepted, LockCertified, Reserved, Included, Finalized, Materialized, ExpiredUnapplied, Failed
final case class ExactStageRecord(
  executionId: ExecutionId, sourceKind: Byte, lifecycle: ExactStageLifecycle,
  inputLockCertificateId: Option[Hash], effectCertificateId: Option[Hash],
  firstApplicationBlock: Option[Hash], firstApplicationHeight: Option[Height],
  resultDigest: Option[Hash], terminalEvidenceDigest: Option[Hash],
)
final case class ExactPipelineRecord(
  schema: Long, binding: ExactIdentityBinding, request: ExactSubmitRequest,
  stages: Vector[ExactStageRecord], acceptedAtMillis: Long,
  admissionJournalSequence: Option[Long],
)
final case class ExactPipelineSnapshot(
  schema: Long, context: DomainContext, records: Vector[ExactPipelineRecord],
  idempotency: Vector[IdempotencyOwner], stageOwners: Vector[StageOwner], outputOwners: Vector[OutputOwner],
)
final case class IdempotencyOwner(key: Text, requestDigest: Hash, nodePipelineId: Text)
final case class StageOwner(context: DomainContext, stageTxId: Hash, bindingDigest: Hash)
final case class OutputOwner(
  context: DomainContext, producerTxId: Hash, outputSlot: Long, bindingDigest: Hash,
)
```

Schema fields equal 3. Stage lifecycle tags are the displayed order starting at 1. sourceKind equals the signed stage's core source tag;
consensus stages never acquire an effectCertificateId or a fabricated effect-certified lifecycle. Fast
source readiness additionally requires its verified effect certificate. Two-stage execution uses one signed plan but admission-source
eligibility is checked independently for each stage under the active profile.

Records sort uniquely by node pipeline id; idempotency owners sort uniquely by key. The request digest is
`H("sigilaris.tx-pipeline.exact.request.v3", ExactSubmitRequest)` with idempotencyKey replaced by None, so aliases do not change signed work.
A reused key with different canonical signed work rejects; replay of the identical request returns its existing binding and lifecycle.
An additional key for the same signed work is retained through a canonical no-op `ExactLifecycle` update. The update keeps the full record
identical and references an immutable `ExactAliasEvidence(schema:I64=3, context:DomainContext, owner:IdempotencyOwner,
priorRecordDigest:H32)` blob in `exact-evidence`, hashed under `sigilaris.tx-pipeline.exact.alias.v3`. Its bytes are forced before the journal
record. Recovery checks all committed no-op updates and their source/prior-record bindings, rather than reading only the latest update.
Admission atomically claims `(context,stageTxId)` for both stages and `(context,producerTxId,outputSlot)` for the output with the immutable
binding digest. Another signed outer plan/applicationPipelineId cannot take either claim even if its derived execution ids differ. StageOwner
and OutputOwner vectors sort uniquely by these keys and reconstruct exactly from retained authoritative records before admission reopens.
Terminal/expired records retain these ownership facts: a retry/new deadline requires newly signed transaction bytes and new transaction ids,
not reuse of the same producer output. Missing/conflicting ownership coverage is a recovery failure, never permission to reassign an output.
Every record verifies the original intent, derived execution ids and identity binding on recovery. Stage count/order/ids agree exactly with
the signed intent. Certificate references and application/terminal fields have presence rules corresponding to validated lifecycle evidence;
no decoder may infer application, expiry or effect certification from a numeric lifecycle tag alone.

First application block/height/result are all present together from Included onward and remain immutable on finalization/materialization.
ExpiredUnapplied requires no application fields and authenticated same-domain expiry evidence. Failed records do not release reservations;
subsequent application/expiry recovery retains the authoritative safety claim and canonical evidence. Nonterminal admissions keep their
common deadline journaled, including both lock-free stages. Terminal replay never rewrites archived schema-2 records into schema 3.

`Reserved` describes an acquired complete reservation; a fast stage may reach it with an input-lock certificate before an effect quorum exists.
Fast readiness and application still require the actual effect certificate. A failed candidate is a non-releasing diagnostic and may return
to `Reserved` under a newly verified candidate with a retained intent and live complete owner; it never returns to an unregistered/Accepted
state. Actual canonical application may move directly from Accepted/LockCertified/Reserved/Failed to Materialized in the same application
decision, allowing nodes without prior local votes to apply finality. Certification of an unfinalized block alone does not set first-application
fields. The first recorded certificate references remain immutable when a later valid certificate has another quorum signer set. The actual
selected plan retains its own references; both certificates must be authenticated and full lock subjects must agree when linking an effect
through an equivalent archived lock reference.

The runtime schema's `ExactRegistration` journal operation retains the full canonical schema-3 record, signed request/binding digests and
common deadline before admission success or stage voting. Its selected initial sequence populates `admissionJournalSequence`; retries preserve
it. Startup reconciles source admission/ownership and the journal mirror, repairing only an already durable unregistered admission and derived
projections, never inventing a vote or lost descriptor. `ExactRecordUpdate` carries immutable-binding/prior-record checks and the full next
record. Included/ExpiredUnapplied transitions are part of the owning recoverable application/expiry operation; certificate/reservation and
finalization/materialization projections require their matching evidence. Failed candidate state does not erase a still-live deadline or
stage/output ownership. The exact document and runtime payload table therefore name one shared registration/recovery boundary.

## Public runtime integration

```scala
trait ExactPlanAuthentication:
  def verify(signed: SignedExactPlan, manifest: ProtocolManifest)
    : Either[CoreFailure, VerifiedExactPlan]

final case class ExactCandidateEvidence(
  context: DomainContext, parentBlockId: Hash, candidateHeight: Height,
  stageEntries: Vector[PlanEntry], availableArtifacts: Vector[ReferencedArtifact],
)
final case class ReferencedArtifact(digest: Hash, canonicalBytes: Bytes)

trait ExactExecutionRequestVerifier[F[_]]:
  def ordered(nodePipelineId: Text, candidate: ExactCandidateEvidence)
    : Result[F, VerifiedOrderedExecution]
  def producer(nodePipelineId: Text, candidate: ExactCandidateEvidence)
    : Result[F, VerifiedProducerExecution]
  def consumer(nodePipelineId: Text, candidate: ExactCandidateEvidence)
    : Result[F, VerifiedConsumerExecution]

trait ExactConsensusExecutionRuntime[F[_]]:
  def admit(request: ExactSubmitRequest): Result[F, ExactPipelineRecord]
  def executeOrdered(request: VerifiedOrderedExecution): Result[F, PreparedExactCandidate]
  def executeProducer(request: VerifiedProducerExecution): Result[F, PreparedExactCandidate]
  def executeConsumer(request: VerifiedConsumerExecution): Result[F, PreparedExactCandidate]
  def recover: Result[F, ExactPipelineRecovery]
```

`Result` uses the runtime schema. `PreparedExactCandidate` retains the verified proposal, selected execution ids, working results and durable
consensus intent digest. It grants no canonical application or finality. This prepublication API amendment separates speculative execution from
P2's `PreparedApplication`, whose creation requires actual finality. VerifiedExactPlan and Verified*Execution constructors are private to the authenticated
verification boundary; a client cannot create one with arbitrary pre-state or ancestry. A verified execution request carries the registered
plan/binding, candidate domain/height/parent, relevant stage source/descriptor/pre-state proofs, complete reservations and optional input-lock
certificate, plus effect artifacts only for a fast source. The consumer request also carries an authenticated ancestor lookup result for
CertifiedAncestor, or the immediately preceding producer's verified working output for OrderedAtomic.

The public request verifier is configured with the registered plan store, active/historical manifests and trusted transaction/input/artifact
verifiers, canonical parent-state provider, instrumented executor and CanonicalAncestorLookup. It is the public producer of those private
verified requests. ExactCandidateEvidence and ReferencedArtifact are untrusted lookup input, not a separately committed wire format: every
reference is independently hashed/decoded and matched against its selected existing/core artifact codec. Supplied bytes and values cannot
assert availability, canonical ancestry or authority by themselves. Missing required artifacts are retrieved through configured public lookup
hooks or return the typed unavailable result. It verifies the original registered binding and ownership, candidate profile/domain/height,
mode-specific stage membership/order, source/certificate presence, input and pre-state evidence, and the designated producer-output proof.
An ordered request contains both signed stages; producer/consumer requests contain only their respective stage. Invalid/missing/unused or
conflicting artifact references reject; request verification does not acquire a lock, sign or commit an application. The execution runtime
rechecks live safety and entry-local working state inside its atomic/reservation boundary, so a stale verified request cannot bypass a change.

`executeOrdered` executes both stages against immutable sequential working state and returns the prepared candidate only if both succeed.
`executeProducer` prepares the producer candidate without a fast effect certificate for a consensus source. `executeConsumer` proves the producer's
block certification/ancestry/output/profile/deadline, including older finalized history, then prepares only its selected consumer stage. The full
proposal retains complete reservations, including independent entries. Actual finality subsequently enters the ordinary application verifier/store
and its one canonical decision. A deterministic unsuccessful reducer retains its complete authenticated access trace and bounded reservations;
the runtime returns the rejection after journaling that protection and never invokes a signer for it.

Ordinary lock/effect/consensus voting also checks exact bindings against the same authoritative journal's registration and permanent stage
ownership. A read-only signed-plan source alone cannot establish durable admission, and a generic source cannot reuse an owned stage transaction.
Consensus signing additionally requires successful exact mode/output/ancestry readiness for every exact execution in its proposal. These
permissions are process-local, are revoked for failed candidate stages, and clear on recovery; a retained Reserved record or vote intent alone
does not authorize exact signing after restart. Recovery authenticates voting, exact and application evidence against the original immutable
safety state, without recursively calling the recovering store's gate.

Both modes must cover 00/01/10/11 nonempty-lock combinations for two consensus stages. A fast stage still requires both certificates and a
nonempty subset. The application profile authenticates declared access, exact entry-local freshness and complete actual instrumentation.
Rejected candidate execution retains bounded reservation claims until canonical application or authenticated expiry. Producer failure,
consumer failure, journal/admission interruption, replay and unavailable history each have typed failures and no partial atomic application.

Required exported fixture families are V2ExactConformance and V2AncestryConformance. New signatures/codecs use frozen byte vectors on JVM and
Scala.js; source implementation suites additionally exercise persisted recovery and public ordinary HotStuff integration.
