# V2 core schema and API freeze

This document freezes the additive core boundary required before Phase 1 of [plan 0033](../plans/0033-application-execution-conformance-and-v2-activation-plan.md).
It supplements [the V2 contract](v2-contract.md) and [the compatibility inventory](core-compatibility-inventory.md). Definitions below belong to
`org.sigilaris.core.application.protocol.v2`; they do not replace the published protocol-1 classes. The declarations specify public field order,
types and signatures. Implementations may add private helpers and convenience constructors, but changing an encoded field, order, tag or
commitment requires an explicit amendment before implementing the changed codec.

## Primitive conventions

In the signatures, `Hash = UInt256`, `Bytes = scodec.bits.ByteVector`, `Text = Utf8`, `InputId = ApplicationInputId`, and
`Height = InclusionHeight` are notation for the existing core types, not new encodings. `ExecutionId` retains its existing fixed-32-byte wrapper.
All encoded versions, indices, counts stored as product fields, profile versions and epochs are Scala `Long`: eight-byte big endian, checked
nonnegative before acceptance. Collection/byte/string lengths use the existing canonical `BigNat` codec. Heights use canonical BigNat through
`InclusionHeight`; arithmetic is checked against the authenticated lifetime/resource bound, never truncated to Long.

`Hash` is exactly 32 bytes. `Bytes` is BigNat byte length then those bytes. `Text` is BigNat UTF-8 byte length then valid UTF-8. A vector is
BigNat element count followed by element encodings. `Option[A]` is 00 for None or 01 followed by A; other counts reject. Enum tags are one byte
and are not Scala ordinals. A product is the concatenation of its fields in the displayed order. There are no implicit fields or padding.

Strings used as field, family, profile or authority identifiers must be nonempty and equal to their trimmed form. Their actual UTF-8 bytes are
preserved; no Unicode normalization or case folding occurs. Every canonical order below is unsigned lexicographic order of the raw identity or
UTF-8 bytes, excluding the length prefix. A hash sorts by its fixed 32 bytes. Decoders verify these orders; they do not reorder network input.
Builder functions may explicitly return a canonicalized value. Duplicate identities reject before set conversion or read/write normalization.

The chain id is the already validated canonical `org.sigilaris.node.gossip.ChainId` string projected into Text by the node adapter. Core checks
the same `^[a-z0-9][a-z0-9._-]*$` grammar without depending on node-common; the adapter also applies the existing ChainId parser.
It is the sole validator signing-domain discriminator.
No independent signing-domain field is encoded or chosen. Epoch, configuration and validator set further scope artifacts within that chain.

For every public encoded product/enum below, its companion exposes `codec: CanonicalCodec[A]`:

```scala
trait CanonicalCodec[A]:
  def encode(value: A): Either[CoreFailure, Bytes]
  def decode(bytes: Bytes): Either[CoreFailure, A]

object Commitment:
  def preimage(domain: Text, canonicalPayload: Bytes): Bytes
  def hash(domain: Text, canonicalPayload: Bytes): Hash
```

`preimage = encodeText(domain) ++ encodeBytes(canonicalPayload)`; `hash = Keccak256(preimage)`. A signing-preimage API returns that framed
preimage, not its digest. This framing is the existing ApplicationProtocolHash framing with a newly selected domain. All decoder entry points
consume the complete input, verify resource bounds before allocating from an untrusted length, reject unsupported versions/tags and reject
noncanonical primitive encodings. `encode` rejects invalid/noncanonical values as well. No new generic decoder changes a historical codec.

## Context and manifests

```scala
final case class DomainContext(
  protocolVersion: Long, chainId: Text, configurationDigest: Hash,
  epoch: Long, validatorSetHash: Hash,
)

enum FieldRole:
  case Immutable, ExactRead, ExactMutate, Opaque
enum Authority:
  case ConsensusOnly, LockEligible

final case class FieldManifest(
  fieldId: Text, role: FieldRole, schemaDigest: Hash,
  authorityPolicyDigest: Option[Hash],
)
final case class InputManifest(
  format: Long, familyId: Text, familyVersion: Long,
  fields: Vector[FieldManifest], transactionVerifierDigest: Hash,
  authorityVerifierDigest: Hash, classificationVerifierDigest: Hash,
  maintenanceVerifierDigest: Option[Hash],
)
final case class SubstrateVersions(
  input: Long, header: Long, plan: Long, manifest: Long,
  lock: Long, effect: Long, exactPipeline: Long, journal: Long,
)
final case class ProtocolLimits(
  maxIdentities: Long, maxWitnessBytes: Long, maxChunks: Long,
  chunkBytes: Long, maxIdentityBytes: Long,
)
final case class DependencyProfileBinding(
  profileId: Text, profileVersion: Long, verifierSlot: Text,
  verifierManifestDigest: Hash,
)
final case class ProtocolManifest(
  format: Long, protocolVersion: Long, chainId: Text, epoch: Long,
  validatorSetHash: Hash, maxLockLifetimeBlocks: Long,
  substrate: SubstrateVersions, limits: ProtocolLimits,
  families: Vector[InputManifest], profiles: Vector[DependencyProfileBinding],
)

object InputManifest:
  def validate(value: InputManifest): Either[CoreFailure, Unit]
  def digest(value: InputManifest): Either[CoreFailure, Hash]
object ProtocolManifest:
  def validate(value: ProtocolManifest): Either[CoreFailure, Unit]
  def configurationDigest(value: ProtocolManifest): Either[CoreFailure, Hash]
  def context(value: ProtocolManifest): Either[CoreFailure, DomainContext]
```

Role tags are Immutable=1, ExactRead=2, ExactMutate=3, Opaque=4. Authority tags are ConsensusOnly=1, LockEligible=2. There is initially only
Exact binding for declared existing state; no Latest variant or implied absence of a precondition is available.

Formats and protocol equal 2. Family/profile versions are positive application-owned integers. The substrate equals `(2,2,2,2,2,2,3,2)` in the
displayed field order. Limits equal `(100000,16777216,256,65536,256)` for this activated profile. Maximum lifetime is positive. The manifest
contains no configurationDigest field: its digest is calculated from the complete product, avoiding a self-reference. Each family manifest's
digest is computed independently. Families are nonempty and sorted uniquely by `(familyId,familyVersion)`; dependency profiles are sorted
uniquely by profile id, with unique `(verifierSlot,verifierManifestDigest)` bindings. An empty profile vector is valid when no exact-pipeline
profile is enabled. Historical manifest1 continues to enforce its published nonempty-profile rule.

`DomainContext.codec` is structural: it preserves a positive protocolVersion, including 1 for historical transition evidence. New protocol
manifests, input/execution/certificate validation require protocol 2 and the activated tuple. Evidence verification instead dispatches the
context through its authenticated historical `LegacyM1`/`LegacyM2` provenance where appropriate. A new evidence signature format does not
change the old application domain it describes, and decoding a historical context never authorizes new application work under it.

Each family has a fixed, nonempty sorted vector of unique declared field ids. Read/mutate declarations require `Some(authorityPolicyDigest)`;
immutable/opaque declarations require None. A schema digest names the exact canonical application codec; compatible schema alternatives require
an explicitly different authenticated family manifest, rather than an uncommitted decoder fallback. The authority verifier resolves policy
against authenticated state; a descriptor cannot grant itself eligibility by setting an enum value.

Repeated state references use explicit fields in that fixed manifest. For example, a family with three inputs declares exactly
`inputs/00000000`, `inputs/00000001`, `inputs/00000002`, each with its schema and role. Those are literal ids; the core does not expand an index
pattern or infer a count. Every transaction for that manifest supplies all three. A different arity requires an explicitly activated manifest
or family version. Repeated application values may also live within one schema's canonical opaque data, but opaque data cannot conceal extra
existing-state Exact references or lock-eligible mutations. Creation and actual AccessLog coverage still apply to all discovered state access.

## Independent descriptors and footprints

```scala
final case class ExactPrecondition(schemaDigest: Hash, bytes: Bytes)
final case class ResolvedField(
  fieldId: Text, role: FieldRole, value: Bytes,
  stableId: Option[InputId], precondition: Option[ExactPrecondition],
  authority: Option[Authority],
)
final case class LockInput(
  fieldId: Text, stableId: InputId, precondition: ExactPrecondition,
  authority: Authority,
)
final case class Footprint(format: Long, reads: Vector[InputId], writes: Vector[InputId])
final case class InputDescriptor(
  format: Long, manifestDigest: Hash, fields: Vector[ResolvedField],
  fullInputCommitment: Hash, lockSubsetCommitment: Hash,
)
enum AccessKind:
  case ReadExisting, MutateExisting, CreateAbsent
final case class ActualAccess(
  identity: InputId, kind: AccessKind, resolvedAuthority: Option[Authority],
)
final case class ResolutionEvidence(fieldId: Text, artifact: Bytes)

trait InputAuthentication:
  def verify(
    manifest: InputManifest, signedTransaction: Bytes, entryPreStateRoot: Hash,
    field: ResolvedField, evidence: ResolutionEvidence,
  ): Either[CoreFailure, Unit]
  def verifyAbsentCreation(
    manifest: InputManifest, signedTransaction: Bytes, entryPreStateRoot: Hash,
    identity: InputId, evidence: Bytes,
  ): Either[CoreFailure, Unit]

trait DeclaredFootprintAuthentication:
  def derive(
    manifest: InputManifest, signedTransaction: Bytes, entryPreStateRoot: Hash,
    descriptor: InputDescriptor,
  ): Either[CoreFailure, Option[Footprint]]

object InputDerivation:
  def derive(manifest: InputManifest, fields: Vector[ResolvedField])
    : Either[CoreFailure, InputDescriptor]
  def validate(
    manifest: InputManifest, signedTransaction: Bytes, entryPreStateRoot: Hash,
    descriptor: InputDescriptor, evidence: Vector[ResolutionEvidence],
    authentication: InputAuthentication,
  ): Either[CoreFailure, Unit]
  def lockInputs(manifest: InputManifest, descriptor: InputDescriptor)
    : Either[CoreFailure, Vector[LockInput]]
  def validateActual(
    descriptor: InputDescriptor, declared: Option[Footprint],
    actual: Vector[ActualAccess],
  ): Either[CoreFailure, Footprint]

object Footprint:
  def canonical(reads: Vector[InputId], writes: Vector[InputId])
    : Either[CoreFailure, Footprint]
  def declaredCommitment(value: Footprint): Either[CoreFailure, Hash]
  def actualCommitment(value: Footprint): Either[CoreFailure, Hash]
  def conflicts(left: Footprint, right: Footprint): Boolean
```

Descriptor/footprint format equals 2. Resolved fields have exact manifest field membership/order/roles. Existing read/mutate fields require
Some(stableId), Some(precondition), Some(authority), with nonempty identity and nonempty canonical precondition bytes whose schema equals the
manifest's schema. Immutable/opaque fields require all three to be None. Stable ids are unique across the descriptor. ResolutionEvidence is
transport input, with exactly one item per existing-state field and no unused item. Its bytes do not enter commitment preimages: the verifier
authenticates the semantic resolved fields against the entry pre-state and manifest. Different valid Merkle transports therefore cannot create
different identities. Authentication runs again against each actual ordered entry state, preserving Exact freshness.
`InputAuthentication.verify` also checks every immutable/opaque field's binding to the signed transaction and manifest, using the canonical
empty `ResolutionEvidence(fieldId, Bytes.empty)` supplied by core. Such fields do not add transport evidence or state proofs. Omitting state
proof requirements therefore does not exempt non-state fields from authenticated signed-field binding.

`derive` performs structural/canonical derivation only and never returns an authenticated capability. Public voting/execution paths must call
`validate` with the configured verifier and evidence; they cannot treat a successful `derive` as proof of authority or freshness. The application
adapter verifies complete signed preconditions and semantic authority under its bound verifier, not an unauthenticated boolean supplied by a client.

The full-input payload is `(format=2, manifestDigest, fields)`. The lock payload is `(format=2, manifestDigest, lockInputs)`; lockInputs contains
exactly ExactMutate/LockEligible fields sorted by stable id. Each selected LockInput has authority LockEligible. A read, consensus-only mutation,
or absent creation target never enters it. Empty lockInputs is a valid commitment preimage, not a valid lock certificate subject.

DeclaredFootprintAuthentication returns Some only for an independently complete Exact declaration; None selects Compatibility and cannot be
upgraded to an Exact declaration after execution. The runtime compares the derived declared digest to the entry before signing or applying.
All declared creation targets also pass InputAuthentication.verifyAbsentCreation against the authentic entry pre-state.

Footprint read/write vectors are individually sorted, duplicate-free and disjoint. Builders first reject duplicates within either input vector,
then remove reads also present as writes; writes subsume reads. Decoders reject overlaps instead of normalizing them. Footprints include every
concrete state access, including consensus-only cells and absent creation targets. A declared footprint is independently derived/authenticated
by the application before execution; it is not inferred only from lockInputs. Every declared existing field appears with at least its required
access mode, and each declared creation has authenticated absence evidence. Actual instrumentation cannot be supplied by a proposer.
Actual instrumentation includes the reads used to authenticate Exact preconditions and authority, so every resolved existing identity must
appear in the actual read-or-write footprint even if the reducer makes no write. This retains read protection when execution only checks an
input or returns a deterministic unsuccessful result; an ExactMutate declaration alone does not force an actual write.

AccessKind tags are ReadExisting=1, MutateExisting=2, CreateAbsent=3. Read/mutate observations require Some(resolvedAuthority), and creation
requires None because no existing authority exists. Repeated instrumentation observations are allowed: deterministic reduction makes one
read/write footprint, with writes subsuming reads. Conflicting creation/existing classifications of the same identity reject unless the
application instrumentation explicitly reports a create followed by access to that same newly-created value as CreateAbsent; it must not be
misreported as a pre-existing cell. Access observations are execution evidence, not a standalone wire commitment; their reduced footprint is.

For an Exact declaration, actual reads must be within declared reads-or-writes and actual writes within declared writes. For Compatibility,
the complete actual footprint is derived after execution. In either class every observed existing LockEligible mutation must match an explicit
ExactMutate/LockEligible field. Dynamically discovered existing mutations may only be ConsensusOnly; dynamically discovered reads and absent
creations remain in the actual footprint. Manifest/pre-state authentication and actual instrumentation must jointly prevent omitted eligible
mutations; a caller-selected Authority flag does not satisfy this check.

## Sources, declarations, statements and plan

```scala
enum PlanSource:
  case ConsensusTransaction(txId: Hash, signedTransaction: Bytes,
    inputLockCertificateId: Option[Hash])
  case CertifiedFastExecution(txId: Hash, signedTransaction: Bytes,
    inputLockCertificateId: Hash, effectCertificateId: Hash)
enum Declaration:
  case Exact(declaredFootprintDigest: Hash)
  case Compatibility(reasonDigest: Hash)
enum WaveKind:
  case ConflictFree, Ordered, CompatibilitySingleton
final case class ReferencedValue(identity: InputId, precondition: ExactPrecondition)
enum ClassificationPurpose:
  case Admission, ExecutionClass, Footprint, MaintenanceOpening
final case class ClassificationStatement(
  format: Long, manifestDigest: Hash, txId: Hash, sourceKind: Byte,
  entryPreStateRoot: Hash, declarationDigest: Hash,
  referencedValues: Vector[ReferencedValue], purposes: Vector[ClassificationPurpose],
)
final case class ClassificationProof(statementCommitment: Hash, artifact: Bytes)
final case class PlanEntry(
  executionId: ExecutionId, source: PlanSource, manifestDigest: Hash,
  declaration: Declaration, declarationDigest: Hash,
  fullInputCommitment: Hash, lockSubsetCommitment: Hash,
  actualFootprintCommitment: Hash, dependencyPlanDigest: Hash,
  lastInclusionHeight: Height, entryPreStateRoot: Hash,
  classificationStatementCommitment: Option[Hash],
)
final case class ExecutionWave(kind: WaveKind, entries: Vector[PlanEntry])
final case class ExecutionPlan(
  format: Long, waves: Vector[ExecutionWave], statements: Vector[ClassificationStatement],
)
final case class ExecutionIdentityInput(
  context: DomainContext, manifestDigest: Hash, txId: Hash,
  signedTransaction: Bytes, fullInputCommitment: Hash, lockSubsetCommitment: Hash,
  declarationDigest: Hash, dependencyPlanDigest: Hash, lastInclusionHeight: Height,
)
final case class BodyMember(txId: Hash, executionId: ExecutionId)
final case class VerifiedEntry(
  entry: PlanEntry, declaredFootprint: Option[Footprint], actualFootprint: Footprint,
)

trait EntryAuthentication:
  def verify(entry: PlanEntry, statement: Option[ClassificationStatement])
    : Either[CoreFailure, VerifiedEntry]

object Declaration:
  def digest(value: Declaration): Either[CoreFailure, Hash]
object ClassificationStatement:
  def commitment(value: ClassificationStatement): Either[CoreFailure, Hash]
object ExecutionIdentity:
  def compute(value: ExecutionIdentityInput): Either[CoreFailure, ExecutionId]
object ExecutionPlan:
  def validate(
    plan: ExecutionPlan, body: Vector[BodyMember], authentication: EntryAuthentication,
  ): Either[CoreFailure, Vector[VerifiedEntry]]
  def computeRoot(plan: ExecutionPlan): Either[CoreFailure, ExecutionPlanRoot]
  def empty: ExecutionPlan
```

Source tags are ConsensusTransaction=1 and CertifiedFastExecution=2, followed by the displayed case fields. Declaration tags are Exact=1 and
Compatibility=2. WaveKind tags are ConflictFree=1, Ordered=2, CompatibilitySingleton=3. Statement format and plan format equal 2. Classification
purposes have tags Admission=1, ExecutionClass=2, Footprint=3, MaintenanceOpening=4, sorted numerically and unique; the vector is nonempty.
ReferencedValues is sorted by identity and unique. `sourceKind` must be 1 or 2 and match the source; it is not an extensibility escape hatch.

The application transaction verifier derives txId from the complete canonical signedTransaction and authenticates its domain/replay policy.
The execution id commits that complete signed transaction and its pre-execution declarations. It does not commit the result, actual footprint,
its containing plan root or block id: those depend on execution/proposal construction and would otherwise cause a hash cycle. The plan and
fast effect subject bind the actual footprint once known. Entry pre-state roots are authenticated when executing the committed sequence.

Conflict-free entries sort by transaction/source id (`source.txId`), not execution id. Duplicate txIds reject anywhere in the plan, as do duplicate
execution ids or reused certified effect certificate ids. Ordered entry order is the consensus-committed vector order; an application exact-pipeline
profile additionally verifies its signed stage order. Compatibility is precisely one consensus source in one CompatibilitySingleton wave, which
is the only wave in its block. ConflictFree/Ordered require Exact declarations. CompatibilitySingleton requires Compatibility.

Statements are sorted uniquely by their computed commitment. Every required statement is referenced exactly once and binds the exact entry
manifest, source, pre-state root, declaration (including reason through declarationDigest), referenced keys/preconditions and purposes. The
configured classifier independently derives these values. Witness-dependent classification requires a matching ClassificationProof artifact
and configured proof verification before a vote; unused/missing/duplicate statements or proofs reject. Witness-independent classification
requires None. Transport artifact bytes are not hashed unless the activated application proof verifier mandates one canonical full-consumption
proof codec; such a verifier must reject redundant nodes, alternate ordering and equivalent alternate encodings.

EntryAuthentication is a trusted application/node adapter, not a proposer-controlled callback result. It retrieves/validates the input descriptor,
manifest, complete source transaction, admission base, optional/required certificates, declared/actual footprints and classification proof; it
recomputes all commitments and execution identity. The returned VerifiedEntry must equal the supplied entry and validated footprints. The core
then independently verifies equality, declaration/actual digest correspondence, membership, isolation and cross-wave conflicts. At the runtime
boundary this adapter executes against the authentic sequential working state and instrumented accesses; structural plan validation alone is
not a pre-vote authorization.

Every ConflictFree entry's complete declared footprint is compared against every other Exact entry in all waves. Read/read sharing is allowed;
write/write and read/write overlap involving a ConflictFree entry reject. Ordered/Ordered overlap may proceed only with fresh Exact values and
the runtime's authenticated ordering/reservation authorization; no plan authorization overrides a conflicting external fast lock.

The canonical empty plan is `(format=2, waves=[], statements=[])`. Its structural bytes are `00000000000000020000`; its root uses the normal
plan-v2 domain. Body membership is empty and application state root must equal the authenticated parent's root. Every present wave is nonempty.
Initial inherited-root installation is separately authenticated chain birth; it is not an empty descendant that may mutate state.

## Optional lock certificates and fast effects

```scala
enum AdmissionBase:
  case Finalized(blockId: Hash, height: Height, stateRoot: Hash)
  case InitialAnchor(blockId: Hash, height: Height, stateRoot: Hash, bundleDigest: Hash)
final case class LockSubject(
  context: DomainContext, txId: Hash, executionId: ExecutionId, manifestDigest: Hash,
  admissionBase: AdmissionBase, fullInputCommitment: Hash, lockSubsetCommitment: Hash,
  dependencyPlanDigest: Hash, lastInclusionHeight: Height, inputs: Vector[InputId],
)
final case class EffectSubject(
  context: DomainContext, txId: Hash, executionId: ExecutionId, manifestDigest: Hash,
  inputLockCertificateId: Hash, dependencyPlanDigest: Hash, lastInclusionHeight: Height,
  resultDigest: ApplicationResultDigest, stateRoot: ApplicationStateRoot,
  actualFootprintCommitment: Hash,
)
final case class ValidatorSignature(validatorId: Text, signature: Bytes)
final case class LockVote(subject: LockSubject, vote: ValidatorSignature)
final case class EffectVote(subject: EffectSubject, vote: ValidatorSignature)
final case class LockCertificate(subject: LockSubject, votes: Vector[ValidatorSignature])
final case class EffectCertificate(subject: EffectSubject, votes: Vector[ValidatorSignature])

trait ArtifactAuthentication:
  def historicalValidators(context: DomainContext)
    : Either[CoreFailure, Vector[Text]]
  def verifySignature(context: DomainContext, signer: Text, preimage: Bytes, signature: Bytes)
    : Either[CoreFailure, Unit]
  def verifyFinalizedBase(context: DomainContext, base: AdmissionBase)
    : Either[CoreFailure, Unit]

object LockSubject:
  def signingPreimage(value: LockSubject): Either[CoreFailure, Bytes]
object EffectSubject:
  def signingPreimage(value: EffectSubject): Either[CoreFailure, Bytes]
object LockCertificate:
  def id(value: LockCertificate): Either[CoreFailure, Hash]
  def verify(value: LockCertificate, manifest: ProtocolManifest,
    authentication: ArtifactAuthentication): Either[CoreFailure, Unit]
object EffectCertificate:
  def id(value: EffectCertificate): Either[CoreFailure, Hash]
  def verify(value: EffectCertificate, lock: LockCertificate,
    manifest: ProtocolManifest, authentication: ArtifactAuthentication): Either[CoreFailure, Unit]
object AdmissionValidation:
  def validateSource(entry: PlanEntry, input: InputDescriptor,
    lock: Option[LockCertificate], effect: Option[EffectCertificate],
    manifest: ProtocolManifest, authentication: ArtifactAuthentication)
    : Either[CoreFailure, Unit]
```

AdmissionBase tags are Finalized=1 and InitialAnchor=2. LockSubject accepts only a verified Finalized base and a nonempty sorted unique input
vector equal to the independently derived eligible subset. InitialAnchor exists solely for lock-free bootstrap opening; a lock certificate
with InitialAnchor rejects. DomainContext protocol=2, chain/configuration/epoch/set all match the active manifest. A lock subject and its execution
identity must agree on every corresponding field. `base.height < deadline <= base.height + maxLockLifetimeBlocks`; base proof authentication is
mandatory. Inclusion at deadline is permitted; candidate inclusion and same-domain finalized nonapplication expiry are runtime checks.

Certificate vectors sort uniquely by validator id. There is exactly one common subject, so each vote signs the complete same subject. Historical
validator membership and signatures are verified; a certificate requires floor(2N/3)+1 distinct authorized votes. Unknown/duplicate signers,
bad signatures, unavailable historical set/base and subject mismatch reject. Effect verification also verifies the complete referenced lock
certificate, then equality of tx/execution/manifest/context/dependency/deadline and its content id. No empty-lock fast effect is valid.

ValidatorSignature.signature is exactly the existing 72-byte signature representation `v:Long || r:Hash || s:Hash`, enclosed by the product's
normal Bytes length prefix. The cryptographic algorithm is secp256k1 over Keccak256 of the framed signing preimage. Canonical signatures require
v in 27..30, `0 < r < n` and `0 < s <= n/2` for secp256k1 order n; a decoder/verifier must reject high-S rather than normalize received bytes.
The validator public key comes from the authenticated historical set, never from a caller-supplied replacement. This fixed validator-artifact
scheme does not select the embedding application's user-transaction or maintenance-signature scheme.

For ConsensusTransaction, lock reference/argument are both None exactly when the derived subset is empty; they are both Some with matching id
and full subject exactly when it is nonempty. Effect must be None. For CertifiedFastExecution, both complete certificates are required, their
content ids equal the source references, and the independently derived subset is nonempty. A certificate reference or validator-count claim
without the underlying authenticated artifacts is insufficient. No consensus result is promoted to an effect-certified lifecycle.

## Opening and canonical witness chunks

```scala
enum OpeningKind:
  case Handover, InitialBootstrap
final case class OpeningEnvelope(
  format: Long, kind: OpeningKind, sourceDomain: Text, sourceCheckpoint: Height,
  sourceRoot: Hash, sourceSchemaDigest: Hash, targetContext: DomainContext,
  targetManifestDigest: Hash, parentId: Hash, firstHeight: Height,
  admissionBase: AdmissionBase, lastInclusionHeight: Height,
  conversionPayload: Bytes, reasonDigest: Hash, authorityId: Text,
)
final case class SignedOpening(envelope: OpeningEnvelope, signature: Bytes)
final case class OpeningExecution[S](
  nextState: S, stateRoot: ApplicationStateRoot,
  result: NormalizedApplicationResult, actualAccesses: Vector[ActualAccess],
)
trait OpeningExecutor[S]:
  def execute(parentState: S, opening: SignedOpening)
    : Either[CoreFailure, OpeningExecution[S]]
trait MaintenanceAuthorizationVerifier:
  def verify(manifest: InputManifest, opening: SignedOpening, preimage: Bytes)
    : Either[CoreFailure, Unit]
object OpeningEnvelope:
  def signingPreimage(value: OpeningEnvelope): Either[CoreFailure, Bytes]
object OpeningValidation:
  def validate(opening: SignedOpening, entry: PlanEntry, input: InputDescriptor,
    manifest: ProtocolManifest, family: InputManifest,
    authorization: MaintenanceAuthorizationVerifier)
    : Either[CoreFailure, Unit]

enum WitnessAccess:
  case Read, Write
final case class WitnessEntry(identity: InputId, access: WitnessAccess)
final case class ReservationWitness(format: Long, entries: Vector[WitnessEntry])
final case class WitnessChunk(
  format: Long, witnessDigest: Hash, index: Long, count: Long, bytes: Bytes,
)
object ReservationWitness:
  def fromFootprint(value: Footprint): Either[CoreFailure, ReservationWitness]
  def digest(value: ReservationWitness): Either[CoreFailure, Hash]
  def chunks(value: ReservationWitness, limits: ProtocolLimits)
    : Either[CoreFailure, Vector[WitnessChunk]]
  def reassemble(chunks: Vector[WitnessChunk], limits: ProtocolLimits)
    : Either[CoreFailure, ReservationWitness]
object WitnessChunk:
  def digest(value: WitnessChunk): Either[CoreFailure, Hash]
```

OpeningKind tags are Handover=1 and InitialBootstrap=2. Opening/witness/chunk formats equal 1. WitnessAccess tags are Read=1, Write=2. Witness
entries sort uniquely by identity; disjoint Footprint vectors merge deterministically into that vector. A witness is not an application source
or an additional canonical transaction. Its digest names one authoritative stored witness; conflict-index rows reference owner/witness ids.
OpeningExecution is an in-process result and has no generic wire codec for application state S. OpeningExecutor is deterministic and has no
canonical side effects until the recoverable application store commits its result. Maintenance signatures use the exact canonical signature
codec and public-key/authority policy authenticated by the family's maintenanceVerifierDigest; the verifier rejects alternate encodings.

Opening's targetManifestDigest equals targetContext.configurationDigest, which is the complete target ProtocolManifest digest. The selected
family must be present in that manifest and its maintenance verifier must be Some and match the configured authorization policy. `sourceDomain`
identifies provenance and need not be a HotStuff chain id. The application's transaction verifier recognizes canonical SignedOpening bytes as
the complete signedTransaction of its ordinary ConsensusTransaction source; it does not introduce a new source kind. The source txId is derived
under that family's authenticated signature/replay contract. Its Compatibility reason and deadline equal the envelope. The opening is the sole
entry in its block. Parent/root/height/base and conversion execution are authenticated by the runtime, not accepted merely from envelope fields.

Handover uses a genuine Finalized base and the ordinary subset/certificate rules. InitialBootstrap uses the authenticated InitialAnchor G,
height zero and firstHeight one with parentId G. It requires an independently empty derived subset and no certificate, even when an attacker
supplies a purported certificate. Both kinds sign lastInclusionHeight and every reservation uses exactly it. The opening preimage excludes the
resulting block id, result root and plan root. Conversion results and complete actual accesses are committed through ordinary block application.

Canonical witness bytes include their format and vector length. An empty footprint therefore encodes as `000000000000000100` (9 bytes), has
one nonempty chunk and a normal witness digest. For L canonical witness bytes, `count=(L+65535)/65536`, index starts at zero, every nonfinal
chunk has exactly 65536 bytes, and the final chunk has precisely the remaining nonempty suffix. The count is never zero. Before voting,
reassembly verifies consecutive order, count/id agreement, exact segmentation, complete witness digest, full decode and authenticated limits.
Duplicate, missing, reordered, tampered or alternatively segmented chunks reject. Encoded witness bytes, not aggregate encoded transport-envelope
overhead, count toward maxWitnessBytes; identity count and maxIdentityBytes are also independently enforced. Oversize is a protocol failure;
node-local capacity shortage remains a typed runtime hold and cannot change block validity.

## Complete commitment register and historical body mapping

Each payload below uses the exact displayed type/product fields in their displayed order. No hash function adds an implicit current version,
clock value, proof transport, selected storage namespace or application-specific identifier.

| Domain | Canonical payload |
| --- | --- |
| `sigilaris.application.family-manifest.v2` | InputManifest |
| `sigilaris.application.manifest.v2` | ProtocolManifest |
| `sigilaris.application.input.full.v2` | `(format:Long=2, manifestDigest:Hash, fields:Vector[ResolvedField])` |
| `sigilaris.application.input.lock-subset.v2` | `(format:Long=2, manifestDigest:Hash, inputs:Vector[LockInput])` |
| `sigilaris.application.input.footprint.declared.v2` | Footprint |
| `sigilaris.application.input.footprint.actual.v2` | Footprint |
| `sigilaris.application.declaration.v2` | Declaration including its tag |
| `sigilaris.application.classification.v2` | ClassificationStatement |
| `sigilaris.application.execution.id.v2` | ExecutionIdentityInput |
| `sigilaris.application.execution-plan.root.v2` | ExecutionPlan including its complete statement vector |
| `sigilaris.application.lock.vote.v2` | LockSubject; framed preimage is signed |
| `sigilaris.application.lock.certificate.v2` | LockCertificate |
| `sigilaris.application.effect.vote.v2` | EffectSubject; framed preimage is signed |
| `sigilaris.application.effect.certificate.v2` | EffectCertificate |
| `sigilaris.application.opening.v1` | OpeningEnvelope; framed preimage is signed |
| `sigilaris.application.reservation.witness.v1` | ReservationWitness |
| `sigilaris.application.reservation.chunk.v1` | WitnessChunk |

NormalizedApplicationResult retains `sigilaris.application.result.normalized.v1` over the existing normalized bytes. The existing node
BlockRecord/BlockBody encoders, `sigilaris.block.record.hash.v1` and `sigilaris.block.body.root.v1` remain unchanged. An embedding adapter maps
each validated source/execution to exactly one existing application result record, projects its `(txId,executionId)` as BodyMember, and verifies
that projection against the record's complete transaction/result semantics. The existing body root hashes the complete canonical records,
while plan root separately binds source and execution order. A fake body-membership projection without the corresponding verified record fails.
There is no new application-body-root domain or body-format axis in this profile, including for empty application blocks.

The header remains V2 with its existing codec and `sigilaris.block.header.id.v2` domain. Every V2 candidate's ordinary runtime validation must
load the selected plan preimage, verify the active format-2 schedule, exact source/body correspondence, body root, plan root and executed state
root before voting. Historical header/plan/body combinations dispatch through their authenticated LegacyM1/LegacyM2 provenance and retain
their historical bytes and validation language. Old plan1 continues to reject an empty plan.

## Failure surface and freeze review

The concrete core failure type is `CoreFailure(code: FailureCode, field: Option[Text], detail: Option[Text])`. It is diagnostic, not hashed or
signed. FailureCode cases are UnsupportedFormat, UnsupportedTuple, InvalidLength, NonCanonicalEncoding, TrailingBytes, InvalidIdentifier,
DuplicateField, DuplicateIdentity, MembershipMismatch, ManifestMismatch, RoleMismatch, AuthorityMismatch, PreconditionMismatch,
CommitmentMismatch, AccessOmission, MissingCertificate, UnexpectedCertificate, CertificateMismatch, InvalidSignature, UnknownSigner,
DuplicateSigner, QuorumNotReached, ProofUnavailable, ProofInvalid, ClassificationMismatch, ForbiddenOverlap, InvalidDeadline,
OpeningMismatch and ProtocolLimitExceeded. Specific field/reason details do not authorize fallback to a weaker validation path.

Phase-1 fixtures must pin the complete ordered preimages and digests rather than comparing two calls to a shared implementation. Cross-platform
fixtures cover all tags, trailing bytes, noncanonical lengths/orders, source/certificate/declaration/statement substitution, fixed manifest
membership, independent actual/declared footprints, Exact freshness, empty-plan historical dispatch and chunk canonicality. Structural codec
success, an application hook's success and durable pre-sign safety are three distinct gates; each public runtime path must satisfy all applicable
gates. The schema self-review found and resolved source-order ambiguity, application-body-domain drift, implicit repeated-field count, root/hash
self-reference and empty-witness chunk ambiguity. No unresolved schema choice remains within this core boundary.
