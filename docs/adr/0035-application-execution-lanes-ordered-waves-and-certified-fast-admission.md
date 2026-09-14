# ADR-0035: Application Execution Lanes, Ordered Waves, And Certified Fast Admission

## Status

Accepted as the `v0.3.0-M1` target contract.

Implementation status as of `2026-09-08`: M1 and M2 publish execution-plan, application certification, safety-journal and
exact-pipeline APIs. They do not yet complete this target contract's input/lock/footprint separation, consensus-only
execution, durable vote issuance and integrated V2 activation. The signature algorithm, domains, certificate
representation, validator-set binding and quorum derivation below remain the target decisions.

[Plan 0033](../plans/0033-application-execution-conformance-and-v2-activation-plan.md) tracks conformance work and its
activation evidence. [ADR-0037](0037-versioned-application-execution-upgrade-and-empty-blocks.md) proposes the empty-block
and post-M2 compatibility supplement. Its proposed rules do not change the meaning of published M1/M2 artifacts.

ADR-0036 supersedes this ADR's original unbounded lock recovery/frontier profile and its unconditional prohibition on
pre-final created-output consumption. M1 now uses signed inclusion-height bounds and permits only explicitly activated
exact pipeline dependencies.

## Context

ADR-0020 made conflict-free scheduling an application-neutral validity rule. It intentionally rejects same-block
`W∩W` and `R∩W` overlap, derives exact or conservative footprints before execution, and uses the execution `AccessLog`
as a conformance witness. This is still the right default for independent application transactions.

Some object-centric applications also need two behaviors that the current baseline cannot express:

1. Consensus-ordered transactions may intentionally overlap existing shared state, so canonical order must determine
   which exact preconditions remain current and where a stale entry makes the candidate invalid. A future family may
   need to validate against a value selected from that updated working state, but that is a distinct input-binding mode
   and is not implicit in M1. Credit caps, ordered allocation frontiers, and mutually exclusive shared lifecycles are
   representative examples.
2. A transaction whose mutable inputs are all exact single-authority state references may be certified by a validator
   quorum before normal block finality, while its normalized state transition is included in canonical block execution
   later. This is useful only if the fast path and ordinary consensus share one conflict/version-lock safety domain.

Routing both behaviors through `Compatibility(reason)` would erase an important distinction. Compatibility means that
the complete access set cannot be established before execution, for example because of a prefix scan or runtime input
discovery. An ordered transaction can have a complete exact footprint and still intentionally overlap another ordered
transaction. It should retain pre-execution validation and `AccessLog` conformance.

The block and execution contracts also need extension:

- ADR-0019 commits an unordered `BlockBody` member set. Set iteration or record-hash order cannot define execution
  order, and record hashes include results that are produced only after execution.
- ADR-0026's `TxExecution` owns the post-execution trie and access witness, but its receipt projection does not identify
  the transaction's exact pre-state root or position in an ordered block execution.
- ADR-0020 allows an object-centric `StateRef` to be derived from an `ObjectRef`. If version and digest bytes are part of
  conflict identity, two versions of the same mutable object may look disjoint even though they address the same
  mutable cell.
- ADR-0031 and ADR-0032 keep dependent newly-created outputs behind certified-ancestor barriers by default. ADR-0036
  additionally permits a profile-bound ordered-atomic exact dependency; ordinary ordered mutation must not silently
  acquire that exception.

Sigilaris must remain application-neutral. It should provide execution modes, commitments, witnesses, quorum artifact
verification, and conflict safety. An embedding application remains responsible for owner classes, transaction-family
allowlists, payload schemas, domain allocation policy, and deciding whether a transaction qualifies for a fast path.

Application neutrality must not collapse three different commitments into one. The complete resolved execution-input
vector, the subset that requires a distributed input lock, and the full read/write conflict footprint serve different
purposes. Likewise, ordered working-state execution does not by itself authorize a validator to replace a signed exact
input with whichever logical version happens to be current at that entry.

## Decision

### 1. Admission lane and block execution mode are separate axes

Sigilaris distinguishes how work is admitted from how canonical block execution is scheduled.

Admission has two protocol classes:

- `ConsensusAdmission`: an ordinary application transaction enters canonical state through an ordered consensus block.
- `CertifiedFastAdmission`: validators certify an exact-input execution with at least one lock-eligible mutable input
  before it is included in canonical state; a later consensus block includes and applies the certified result.

Canonical block execution has three classes:

- `ConflictFree`: the existing ADR-0020 rule. Transactions in the same execution wave have no forbidden footprint
  overlap.
- `Ordered`: transactions have complete declared footprints, but specified overlap is allowed and execution order is
  consensus-committed.
- `Compatibility`: the complete footprint is not available before execution. In the `v0.3.0-M1` baseline, a canonical
  compatibility block contains exactly one application transaction and cannot contain certified-fast block inclusion.

`CertifiedFastAdmission` is not a fourth block execution class. Its result eventually appears as a certified-fast
source entry in a `ConflictFree` or `Ordered` execution plan. Here and below, plan execution includes the transition in
the block state; `materialized` is reserved for the later embedder-owned application step defined by ADR-0028.

Embedding applications derive the admission lane and execution class deterministically from signed transaction bytes,
explicit references, protocol configuration, and, where the family contract allows it, an authenticated precondition
witness. Client-provided mode labels are not authoritative.

Whether a consensus-admitted transaction also requires an input-lock certificate is a third, independently derived
property. A transaction can remain `ConsensusAdmission` while requiring a distributed lock for the exact mutable-input
subset whose application-owned authority class shares the certified-fast conflict domain. The activated family manifest
and authenticated entry pre-state determine that subset; a client-provided certificate cannot change the lane, access
mode, binding mode, or lock eligibility.

### 2. Mutable `StateRef` identity is stable across logical versions

For mutable state, `StateRef` identifies the stable schedulable cell, not a particular version of its value.

- A table-backed item uses the canonical table prefix and encoded key.
- An object-backed item uses an application-domain tag and stable object/storage key, normally the object id.
- Version, payload digest, owner epoch, and equivalent optimistic-concurrency fields are preconditions associated with
  the transaction input. They are not part of the conflict identity.
- Two transactions naming different versions of the same mutable object therefore conflict on the same `StateRef`.
- Immutable content-addressed values may use a digest-bearing identity because they cannot be updated in place.

This rule narrows ADR-0020's example that allowed `ByteEncoder[ObjectRef]` bytes directly. Such bytes are valid only if
the application encoding projects to a stable mutable-cell namespace or the referenced value is immutable.

Namespaces remain application-owned and must be disjoint by construction. Sigilaris continues to treat canonical
`StateRef` bytes as opaque.

### 3. M1 input bindings are exact and manifest-derived

Object- or state-reference transaction wires remain application-owned, but every schedulable family uses typed named
fields rather than an untyped global input bag. For each field or repeated-field element, the activated family manifest
fixes at least:

- the field role and expected application type/schema or explicit compatible schema set;
- `ReadOnly` or `Mutate` access mode;
- input binding mode;
- canonical repeated-field ordering and duplicate rules; and
- required authority plus the type and codec of any authenticated family-specific proof.

The manifest is committed by the historical protocol configuration used for admission, execution, certification, and
replay. The client cannot override an input's access mode, binding mode, expected type, or proof role, and validators must
reject missing required fields, extra input fields, duplicate stable identities, non-canonical ordering, and resolved
values that do not match the declared role.

The `v0.3.0-M1` binding mode for every explicit declared existing-state input is `Exact`, regardless of lane or execution
class. An Exact claim carries the application's complete canonical optimistic-concurrency precondition, such as a
versioned object reference. It must resolve at the lane's required base anchor and must still identify the current value
in the entry's canonical ordered working state. This freshness rule applies to both `ReadOnly` and `Mutate`. If an earlier
entry has changed the stable state item, the later Exact claim is stale: it cannot read from a base-state snapshot, rebind
to the new value, or acquire implicit latest-value semantics. Reducer failure rejects the committed candidate block rather
than dropping only that entry. Compatibility reducers' dynamically discovered accesses are not input claims and cannot
claim Exact binding or exact-footprint guarantees merely because execution later records them in an `AccessLog`.

An activated ADR-0036 pipeline dependency is a separate, explicitly declared input class rather than an existing-state
Exact claim. Its consumer reference need not resolve at the base anchor; it must equal the reference committed before
submission and validate against the declared producer result in the ordered working state or certified ancestor branch,
according to the activated execution mode. This exception cannot rebind an existing-state Exact input or expose an
implicit latest-value lookup.

M1 does not activate a `Latest` family that omits the exact version/value commitment and asks ordered execution to select
the current value. Any future `Latest` or other working-state-selected binding requires a new protocol version and an
explicit family schema/tag, registry-bound typed precondition codec, signature binding, resolution point and same-block
chaining rule, resolved-input commitment, replay behavior, footprint/reservation derivation, and deterministic failure
mapping. Existing Exact-only transaction bytes cannot be reinterpreted under that future mode.

Explicit-input processing is lane-independent in M1: every accepted execution derives the first two canonical structures
below from its explicit declared inputs. Every accepted exact-footprint execution additionally derives the third before
execution. A compatibility execution instead obtains only a post-execution actual `AccessLog` footprint; that footprint
supports validation, vote-time interlock, and reservation but does not become a declared exact footprint.

1. The full resolved execution-input vector contains every explicit existing-state input, including `ReadOnly` and
   `Mutate`, with its canonical exact precondition and access mode; each entry deterministically projects to a stable
   `StateRef`. Its `executionInputsDigest` is recorded by ordered execution artifacts and certified-execution artifacts.
2. The input-lock vector is the deterministic subset whose claims are `Exact`, whose access mode is `Mutate`, and whose
   resolved application-owned authority class is lock-eligible under the activated manifest. Its independent digest is
   bound by the input-lock certificate. Read-only inputs and consensus-only authority classes remain in the full input
   vector and the applicable declared or actual conflict/reservation footprint but are not added to this lock vector.
3. For an exact-footprint execution, the declared `ConflictFootprint` contains every concrete read and write needed for
   execution, including prospective create keys and other absent-key assertions. It remains subject to post-execution
   `AccessLog` conformance.

These structures and their digests cannot be aliased. Validators independently derive every applicable structure and
verify the configured ordering, duplicate rules, subset relations, and correspondence between the full `Mutate` input set
and normalized update/delete previous values. A prospective create key is not an exact existing-state input and therefore
is not inserted into the input-lock vector merely because it is a write. For an exact-footprint execution, it remains in
the declared footprint and consensus reservation. A family whose fresh-key derivation allows two distinct signed
transactions to create the same key is not eligible for certified-fast admission unless a separately activated
reservation artifact closes that collision domain.

M1 certified-fast admission additionally requires a non-empty input-lock vector. A create-only transaction, or any other
transaction whose derived lock subset is empty, remains `ConsensusAdmission` in M1 even if its fresh keys are
collision-free. M1 does not issue an empty-vector `InputLockCertificate`; activating lock-free certified execution would
require a later protocol contract for its eligibility, certificate shape, deadline and persistence treatment.

### 4. Blocks commit an explicit application execution plan

The `v0.3.0-M1` block header adds `executionPlanRoot`, a deterministic commitment to an application execution plan.
The plan preimage is conceptually equivalent to:

```scala
enum ExecutionWaveKind:
  case ConflictFree
  case Ordered
  case CompatibilitySingleton

enum ExecutionPlanSource:
  case ConsensusTransaction(
    txId: ApplicationTxId,
    inputLockCertificateId: Option[InputLockCertificateId],
  )
  case CertifiedFastExecution(
    txId: ApplicationTxId,
    certificateId: CertifiedExecutionId,
  )

enum ExecutionDeclaration:
  case ExactFootprint(declaredFootprintDigest: Hash)
  case Compatibility(reasonDigest: Hash)

final case class ExecutionPlanEntry(
  source: ExecutionPlanSource,
  declaration: ExecutionDeclaration,
  classificationWitnessCommitment: Option[Hash],
)

final case class ExecutionWave(
  kind: ExecutionWaveKind,
  entries: Vector[ExecutionPlanEntry],
)

final case class BlockExecutionPlan(
  waves: Vector[ExecutionWave],
)
```

The exact public type names, numeric tags, hash domain, and byte codec are fixed by the implementation plan and golden
vectors before activation. The semantic requirements are fixed here:

- `waves` and each ordered wave's `entries` are ordered vectors committed byte-for-byte by `executionPlanRoot`.
- Entries in a conflict-free wave are canonicalized by application transaction/source id; collection iteration order is
  never used.
- `ConflictFree` and `Ordered` entries require `ExactFootprint`; a `CompatibilitySingleton` entry requires
  `Compatibility` and cannot use a certified-fast source.
- A compatibility wave contains exactly one entry and is the only wave in its block.
- The same transaction id or certified execution id cannot occur more than once in one plan.
- Every plan source has exactly one corresponding application record/block-inclusion result in the block body, and no
  executable body member is absent from the plan.
- A consensus transaction has `inputLockCertificateId = Some(...)` exactly when its manifest-derived input-lock vector is
  non-empty. The referenced certificate, complete signed transaction, and independently derived lock vector must agree
  on transaction id, protocol configuration, finalized base, and exact input-lock digest. `None` with a non-empty vector
  or `Some` with an empty or mismatched vector is invalid. A certified-fast source is valid only with a non-empty derived
  lock vector, and its execution certificate binds the required corresponding input-lock certificate transitively.
- `ExactFootprint.declaredFootprintDigest` commits the footprint used during proposal construction. Validators derive
  the footprint independently, compare its digest, and still validate the actual `AccessLog` against the full declared
  footprint. It is not the full resolved-input digest or the input-lock-subset digest. `Compatibility.reasonDigest`
  commits the deterministic classification reason without claiming an exact footprint.
- The execution plan preimage must be available to a validator before it votes. A root without its validated preimage is
  insufficient.
- If lane selection, execution classification, or footprint derivation depends on an authenticated precondition or
  Merkle witness, `classificationWitnessCommitment` commits a canonical classification statement derived from the
  transaction/source bytes, applicable entry pre-state root, activated family manifest, exact referenced keys and
  expected values, and witness purpose. It does not commit arbitrary transport-proof bytes. The complete proof artifact
  for that statement must be available and verified before a validator votes; a transaction id, plan root, or statement
  digest without the artifact is insufficient.
- The statement codec and derivation are unique and deterministic. Validators independently derive and compare the
  commitment. If an activated proof format commits the proof bytes themselves, its codec must admit exactly one
  canonical full-consumption encoding and reject redundant nodes, alternate ordering, duplicate elements, and other
  semantically equivalent encodings. Otherwise multiple valid transport proofs may verify the same canonical statement
  without changing `executionPlanRoot`.
- If those derivations do not depend on an authenticated witness, `classificationWitnessCommitment` must be `None`.
  Supplying an unused commitment is non-canonical and invalid; semantically identical entries cannot acquire distinct
  `executionPlanRoot` values through arbitrary witness attachments.

`BlockBody` retains unordered membership semantics and `bodyRoot` retains its existing role. `executionPlanRoot`
commits execution order independently of result/event membership. Because `BlockId` hashes the whole header, adding the
field changes the block identity codec and requires an explicit `v0.3` protocol activation rather than silent mixed
version interpretation.

### 5. Execution proceeds through committed waves

Validators execute waves in vector order from the parent block's authenticated application state root.

- `ConflictFree` wave entries must satisfy ADR-0020's `W∩W` and `R∩W` exclusion within the wave.
- A `ConflictFree` entry must also be free of forbidden overlap with every other exact-footprint entry in the block.
  Moving conflicting entries into different conflict-free waves cannot bypass ADR-0020; intentional cross-entry
  overlap belongs to `Ordered` waves.
- `Ordered` wave entries may overlap. They execute in committed entry order, and each entry observes the working state
  produced by all earlier entries and waves.
- Observing the canonical working state does not weaken an Exact input. Every Exact `ReadOnly` or `Mutate` claim must
  still match the current value at that entry. A write followed by a transaction carrying the old exact value makes the
  latter transaction stale; validators cannot satisfy it from the parent/base snapshot or rewrite it to the successor.
  The reverse order, an exact read followed by an exact write of the same still-current value, may succeed when the
  family manifests and declared footprints otherwise permit the ordered overlap.
- A future parallel executor may execute a conflict-free wave concurrently, but it must produce the same wave post-state
  as the canonical semantics.
- Actual reads and writes must remain subsets of each entry's declared footprint. Ordered execution does not authorize
  hidden scans or undeclared state discovery.
- An authenticated precondition or Merkle witness is verified against the exact pre-state root of its entry. A witness
  for the parent checkpoint or an earlier working state cannot be reused after an earlier entry changes the referenced
  state.
- Reducer failure rejects the candidate block execution; an implementation must not omit a failed entry and continue
  with the same committed plan.

The M1 ordered mode is limited to identities and exact precondition namespaces that are derivable before block
construction. It may serialize intentional overlap, but it does not allow a transaction to consume an updated version
or new logical identity produced by an earlier entry in the same block, except for the one exact producer output declared
by an activated ADR-0036 `OrderedAtomic` pipeline profile. That output reference is fixed before submission and verified
against the producer result; it is not discovered from transaction order. Existing-state version chaining would require
a future explicitly activated working-state-selected binding. Other newly-created-output dependencies continue to use
the certified-ancestor pipeline defined by ADR-0031 and ADR-0032.

### 6. Compatibility is not the normal ordered lane

Compatibility execution remains available for deterministic legacy reducers whose full concrete access set cannot be
derived before execution. It has narrower guarantees:

- A canonical M1 compatibility block contains exactly one application transaction.
- The transaction executes from the parent state with a fresh `AccessLog`; the resulting access witness remains
  observable for diagnostics and migration work.
- It cannot issue or include a certified-fast execution.
- It cannot claim conflict-free or ordered exact-footprint guarantees.
- Local non-consensus batch APIs may retain broader compatibility behavior during migration, but that behavior is not
  a canonical multi-transaction block contract.
- Compatibility execution may dynamically discover reads of existing state regardless of resolved application authority
  class, and may dynamically discover mutations of existing state only when the resolved authority class is
  consensus-only. It may not dynamically discover an existing-state mutation whose resolved authority class is
  input-lock-eligible. Every such mutation must already be an explicit Exact input, appear in the full resolved-input
  vector and derived input-lock subset, and be covered by the transaction's pre-proposal `InputLockCertificate`. A
  post-execution `AccessLog` write cannot retroactively acquire that certificate; discovering an uncovered lock-eligible
  mutation is a deterministic candidate failure.
- Compatibility execution may dynamically create a fresh identity regardless of the authority class assigned to the new
  state. A create has no resolved existing-state authority and does not become an input-lock entry before it exists. Its
  absent key must appear as a write in the complete actual `AccessLog` footprint, whose digest the validator binds into its
  durable proposal reservation. Another creation of the same key therefore conflicts. The application must still enforce
  its activated fresh-key derivation and collision-domain rules.
- A dynamically discovered read of input-lock-eligible state is not an Exact input or input-lock claim. It must appear
  in the complete actual `AccessLog` read footprint produced by deterministic parent-state execution. A prior live input
  lock blocks the proposal vote through `L ∩ (R_e ∪ W_e)`, while a prior durable proposal reservation blocks a later
  conflicting input-lock vote through the reciprocal interlock in Section 8.

An application flow moves from compatibility to ordered mode only after its references and authenticated
preconditions are bounded before proposal construction.

### 7. Ordered execution has an explicit pre/post-state witness

The execution seam extends ADR-0026 with context sufficient to reproduce ordered validation. The internal witness is
conceptually equivalent to:

```scala
final case class TxExecutionContext(
  parentStateRoot: StateRoot,
  executionPlanRoot: ExecutionPlanRoot,
  waveIndex: BigNat,
  entryIndex: BigNat,
  preStateRoot: StateRoot,
)

final case class TxExecutionWitness[Result, Event](
  context: TxExecutionContext,
  postStateRoot: StateRoot,
  executionInputsDigest: Hash,
  inputLockDigest: Option[Hash],
  actualFootprint: ConflictFootprint,
  resultDigest: Hash,
  result: Result,
  events: List[Event],
)
```

The exact generic result/event projection remains application-owned. The core requirements are:

- `preStateRoot` equals the working root immediately before the entry.
- `postStateRoot` equals the working root immediately after the entry.
- Adjacent canonical entries link `previous.postStateRoot == next.preStateRoot`.
- The first entry starts at the parent state root and the final entry ends at `BlockHeader.stateRoot`.
- `executionInputsDigest` binds the complete resolved input vector. `inputLockDigest` is `Some` exactly when the entry's
  manifest-derived lock subset is non-empty and must equal the referenced input-lock certificate digest. Neither digest
  can be substituted for the declared or actual footprint commitment.
- `resultDigest` binds the normalized application result/effect commitment without requiring Sigilaris to interpret it.
- An application may represent deletion by removing live keys and committing a compact deletion reference in the
  normalized result. Sigilaris treats each removal as a declared/actual write but does not require a per-object tombstone
  or full deletion-receipt body to remain in the current application trie. Application manifests own deletion policy,
  terminal-identity markers, cleanup predicates, and any archive/proof-serving contract.
- Public receipts may omit raw trie and access-log data, but any receipt claiming ordered proof must retain or reference
  the roots, plan position, full resolved-input commitment, optional input-lock commitment, footprint commitment, and
  result digest needed for verification.

The current `TxExecutionReceiptProjection(actualFootprint, result, events)` does not satisfy this ordered-proof
contract by itself.

### 8. Input-lock certificates and certified-fast execution share height-bounded cross-lane locks

Sigilaris introduces two application-neutral quorum artifacts.

1. `InputLockCertificate` binds chain id, transaction id, historical protocol configuration and validator-set
   epoch/hash, finalized base/application state root, exact lock-eligible mutable-input vector/digest, and the signed
   `lastInclusionHeight`.
2. The certified-execution certificate carries that complete lock certificate and binds its signature-independent
   subject/id, complete signed transaction, full resolved execution-input vector/digest, declared/actual footprint
   commitments, normalized result/effect digest, deterministic fresh outputs, and the same deadline.

M1 uses distinct signature domains
`sigilaris.application.input-lock.vote.sign.v1` and
`sigilaris.application.certified-execution.vote.sign.v1`. Sign bytes are the canonical full vote body wrapped by
`ApplicationVoteSignPreimageV1` and hashed with Keccak-256. Votes use recoverable ECDSA `secp256k1`; signers emit
Low-S signatures and verifiers reject High-S/non-canonical `(v,r,s)` before public-key recovery. Certificates contain a
canonical validator-id-ordered vector of individual signatures. BLS, threshold and opaque aggregate forms are not
activated.

Artifact identity hashes the complete common signed subject without the signer vector. Adding valid signatures from
`q` toward `n` therefore does not change the lock/execution id. Historical `validatorSetHash` and epoch are verified
against chain-authorized transition records; the current set is never substituted. For `n > 0`, M1 derives
`f = floor((n - 1) / 3)` and `q = n - f`, so `2q - n > f` and `q <= n - f`.

The shared safety rules are:

- Validators derive the full exact input vector, lock-eligible `Mutate` subset, and footprint independently. Version
  is an Exact precondition, not part of the stable lock namespace.
- A validator durably records a complete transaction-scoped lock vote before returning it and never votes for a
  conflicting fast execution or consensus proposal while that lock is live.
- The interlock is reciprocal: proposal votes create durable footprint reservations, and a conflicting reservation
  prevents a new input-lock/effect vote.
- Consensus transactions whose manifest-derived lock subset is non-empty carry the same `InputLockCertificate`.
  Consensus-only authority refs stay out of the distributed lock subset and are protected by canonical ordering,
  reservation and Exact freshness.
- Block inclusion revalidates certificate signatures, historical config/set, exact preconditions, declared/actual
  footprint conformance, output uniqueness, result digest and replay receipt. A stale certificate cannot mutate state.
- Protocol config commits `maxLockLifetimeBlocks`; admission verifies
  `base.height < lastInclusionHeight <= base.height + maxLockLifetimeBlocks`.
- A transaction may first apply only at a candidate application height at or below `lastInclusionHeight`. If included
  by that height, later finalization remains valid.
- A lock/reservation is released as `expiredUnapplied` only after finalized canonical height exceeds the deadline and
  the authenticated application `AppliedExecutionIndex` proves non-application. Finality stalls therefore stall
  expiry. Wall-clock TTL, local timeout, PAC, view change and failure to observe a QC do not authorize release.
- An expired transaction cannot be revived. A client signs new bytes with a new deadline and transaction id.
- Epoch/configuration activation stops new application votes, finalizes past the greatest admitted deadline, resolves
  every lock/reservation as applied or expired, requires the live sets to be empty, then rotates. M1 has no
  cross-epoch carry, handoff, recovery frontier or proposal-abandonment certificate.

The exact deadline and persistence contract is defined by ADR-0036. Concrete application vote-body fields and
subject-id preimages remain application-owned and are committed through the historical application protocol
configuration.

### 9. Fast certification is not canonical finality

A certified-fast execution does not mutate canonical application state and is not a finalized block result.

- `fastCertified` means that a valid certified-execution certificate exists.
- `blockIncluded` means a validated consensus block execution plan includes the certificate and commits its normalized
  transition at that block's state root; the block need not yet be finalized.
- `finalized` means the including block has the finality proof required by the active consensus protocol.
- `materialized` means the embedding application has applied the finalized application payload to its durable state,
  read model, API status, and streams as defined by ADR-0028. Materialization failure does not undo finality.

Block inclusion verifies certificate signatures, protocol/epoch binding, the full resolved-input commitment, exact
input preconditions, derived input-lock subset, declared and actual footprint commitments and their conformance, fresh
output uniqueness and result digest, then checks the applied-execution receipt before current-input freshness. Absence
permits the atomic application. A receipt with the same source, the same `Option[CertifiedExecutionId]` value (including
`None`/`None`), protocol configuration, input commitment, and result commitment is an idempotent no-op even though its
inputs are now stale. The no-op path still validates the transaction/source identity, historical configuration,
certificate signatures, and subject-id derivation when present, and a larger valid signer proof for the same subject
does not create another execution.

If the source, protocol configuration, input commitment, and result commitment are identical but the receipt and replay
differ only between `Some(certifiedExecutionId)` and `None`, the same transaction won a benign certified-fast versus
consensus-lane race. The first canonical application wins. The later artifact is a deterministic already-applied no-op
that does not rewrite the receipt's lane provenance or first-applied boundary, and this option mismatch alone is not
validator/client/proposer equivocation, misbehavior, or slashing/reporting evidence. Two different valid `Some` execution
subjects for one source in the same application-vote epoch/domain are certificate-level certified-execution equivocation
evidence when they bind mutually exclusive lock, footprint, or result subjects. Attributing or slashing an individual
validator additionally requires its two conflicting signed votes. Every receipt/replay mismatch not covered by the
exact-replay, benign cross-lane, or mutually-exclusive-subject branches above is rejected as a source-replay conflict.
This default includes two different non-mutually-exclusive `Some` subjects with otherwise identical commitments, such
as recertifying the same lock, footprint, and result at a newer `finalizedBase`. The catch-all does not attribute actor
equivocation without a separate conflicting valid same-lane quorum artifact.

Block execution applies object/state transitions, including live-key removals,
application effects, compact deletion/replay commitments, and replay receipts as one atomic application result. It does
not reinterpret the transaction under the block executor's current reducer version. After finality, the embedder
materializes that finalized payload under ADR-0028's retry and observability contract.

Consensus and fast paths therefore share safety but not latency or finality semantics. User-facing APIs must not label a
lock vote, execution vote, or certified-fast result as `finalized`.

### 10. Application ownership remains outside Sigilaris core

Sigilaris does not define `FastOwned`, `Shared`, object payload registries, debt ordering, escrow authority, or CRDT
effects. The embedding application owns:

- owner and authority categories;
- the allowlist deciding which transaction families qualify for certified-fast admission;
- typed named input fields, expected schemas, exact access/binding roles, canonical ordering, and lock eligibility;
- application-family input cardinality and value-object lifecycle, including whether a later ordered consumer must
  terminalize one fresh custody output or may update/batch several, plus domain allocation, refund, cancellation,
  source-attribution, and value-conservation rules;
- payload type/schema validation and typed storage;
- domain precondition and frontier proof validation;
- object deletion policy, compact deletion commitments, terminal identity and cleanup manifests, and archive retention;
- normalized result/effect schemas and projection materialization;
- concrete application vote-body fields and the versioned lock/execution subject-id domains and complete preimages that
  commit those fields, registered through the historical application protocol configuration.

Sigilaris owns the opaque stable references, separate resolved-input/lock-subset/footprint commitments, execution-plan
commitment, the rule that quorum artifact identity excludes signer proofs, ordered working-state context, Exact freshness
enforcement, access conformance, quorum artifact verification, replay boundary, and cross-lane conflict safety.

For an application profile that turns exact owned inputs into one fresh consensus-only custody output and later
terminalizes that output in an ordered transaction, the cross-layer M1 boundary is fixed as follows:

- The certified-fast source must still have a non-empty manifest-derived input-lock vector. The fresh custody identity
  is a prospective declared write/create and is not inserted into that vector.
- The ordered consumer is authored only after the block committing the custody creation is finalized. Neither a
  certified result nor a pre-final included output can be resolved as its Exact input.
- The consumer's family manifest fixes whether its custody field is scalar or repeated. Sigilaris validates that exact
  manifest-derived shape and cannot widen a scalar M1 field into an application batch.
- The finalized custody ref is present in the full resolved-input vector and declared footprint. If its application
  authority class is consensus-only, it is excluded from the certified-fast input-lock subset and protected by
  canonical ordering, consensus reservation, Exact freshness, and application reducer validation.
- A whole-output terminalization is represented as the application's normalized delete/result commitment and the
  corresponding declared/actual writes. Sigilaris does not infer domain value conservation or require a current-state
  tombstone, but every validator verifies the application result digest and the resulting state root atomically.

This custody profile does not add same-block created-input chaining or `Latest` binding. Separately, ADR-0036 allows an
application-registered exact `Producer -> Consumer` dependency in ordered-atomic or certified-ancestor mode. No other
family acquires pre-final chaining from ordered execution alone. A partial successor or multi-custody batch remains an
application protocol decision requiring a separately activated schema.

## Interaction With Existing ADRs

- **ADR-0017:** ordinary HotStuff domains remain unchanged. Application votes reuse historical validator keys and the
  quorum derivation but use distinct application domains.
- **ADR-0019:** extended with `executionPlanRoot`; this changes the versioned block-header codec and `BlockId`.
- **ADR-0020:** conflict-free exclusion remains mandatory for `ConflictFree` waves. Stable mutable identity is
  version-independent, while version/digest remain Exact preconditions.
- **ADR-0022:** timeout certificates remain view-change liveness artifacts. They do not release application locks or
  proposal reservations.
- **ADR-0023:** historical validator-set lookup remains authoritative. ADR-0036 requires the lock domain to drain to
  empty before validator-set/configuration rotation.
- **ADR-0026:** ordered proof witnesses add plan position, pre/post roots, input/lock/footprint commitments and result
  digest.
- **ADR-0028:** remains authoritative for the distinction between block finality and embedder materialization.
- **ADR-0031/0032:** certified-ancestor dependency remains the default. ADR-0036 additionally defines the narrow
  activated ordered-atomic exact-pipeline exception.
- **ADR-0036:** supersedes this ADR's original unbounded recovery/frontier/handoff profile with signed inclusion-height
  bounds and maintenance drain-to-empty.

## Consequences

- Independent work retains conflict-free parallelism; intentional shared-state overlap has a committed deterministic
  order and exact freshness result.
- Full resolved inputs, distributed lock subset and conflict footprint have separate commitments.
- Certified-fast acknowledgement remains distinct from canonical inclusion, finality and materialization.
- Lock expiry has one consensus clock and cannot diverge by local elapsed time.
- Finality stalls can keep inputs locked, and epoch/configuration rotation may require bounded write downtime.
- A registered two-stage flow may avoid two independent finalization waits without enabling general transaction DAGs.
- Ordered waves reduce parallelism where applications intentionally share mutable state.
- Compatibility-singleton execution may reduce throughput until runtime scans are replaced by explicit references.

## Rejected Alternatives

1. **Use compatibility for all shared-state work:** it would discard exact footprint and conformance guarantees.
2. **Use versioned object refs as conflict identity:** different versions could bypass stable-cell conflict detection.
3. **Treat certified-fast as alternate finality:** a certificate has no canonical block position or finalized state root.
4. **Allow implicit `Latest` in ordered execution:** it would change signed transaction meaning according to proposal
   order.
5. **Use wall-clock lease expiry:** validators may observe different elapsed time and unlock conflicting transactions.
6. **Use PAC/frontier/cross-epoch handoff in M1:** finalized height plus an authenticated applied index and maintenance
   drain provide a smaller first profile.
7. **Allow arbitrary same-block dependencies:** the required flow is covered by ADR-0036's exact activated profile;
   general DAG scheduling remains out of scope.

## Activation And Follow-Up

Before `v0.3.0-M1` can claim this contract, implementation and conformance tests must complete:

1. Versioned `BlockHeader`/`BlockId` codecs with `executionPlanRoot` and atomic protocol activation.
2. Canonical execution-plan types, source/body membership, ordered/conflict-free/compatibility validation and
   deterministic failure rules.
3. Versioned family field-role manifests with Exact binding, type/schema, access mode, ordering, authority/proof and lock
   eligibility.
4. Separate codecs/commitments for full resolved inputs, lock subset, declared/actual footprints and normalized result.
5. The fixed application-vote signature profile, signature-independent subject ids, historical-set verification,
   quorum derivation, durable vote/artifact storage and gossip.
6. `lastInclusionHeight`, `maxLockLifetimeBlocks`, applied-index-guarded `expiredUnapplied` release and
   drain-to-empty epoch/configuration transition from ADR-0036.
7. Reciprocal input-lock/proposal-reservation interlock across exact-footprint and deterministically executed
   compatibility-singleton entries.
8. Replay handling for exact repeats, benign fast/consensus first-application-wins duplicates, and mismatched subjects.
9. Public lifecycle surfaces that distinguish `fastCertified`, `blockIncluded`, `finalized`,
   `expiredUnapplied`, and embedder-owned `materialized`.
10. Exact-binding/adversarial tests for stale reads/writes, missing/extra lock inputs, double-spend, wrong deadline/config/
    epoch/set, finality stall, expiry boundary and rotation with non-empty locks.
11. Four-validator 3-of-4 transfer fixture producing the same state root and rejecting two certificates for one stable
    input.
12. Activated pipeline tests for registry isolation, deterministic exact producer output, a common stage deadline,
    same-pipeline scope, ordered-atomic rollback, certified-ancestor descendants, deadline expiry and rejection of outside
    consumers/general chaining.

Until these gates pass, current conflict-free scheduling remains the only schedulable block baseline and no
certified-fast result has canonical or final status.

## References

- [ADR-0017: HotStuff Consensus Without Threshold Signatures](0017-hotstuff-consensus-without-threshold-signatures.md)
- [ADR-0019: Canonical Block Header And Application-Neutral Block View](0019-canonical-block-header-and-application-neutral-block-view.md)
- [ADR-0020: Conflict-Free Block Scheduling With State References And Object-Centric Seams](0020-conflict-free-block-scheduling-with-state-references-and-object-centric-seams.md)
- [ADR-0022: HotStuff Pacemaker And View-Change Baseline](0022-hotstuff-pacemaker-and-view-change-baseline.md)
- [ADR-0023: Validator-Set Rotation And Bootstrap Trust Roots](0023-validator-set-rotation-and-bootstrap-trust-roots.md)
- [ADR-0026: TxExecution Witness And Receipt Projection Boundary](0026-tx-execution-witness-and-receipt-projection-boundary.md)
- [ADR-0028: HotStuff Finalization Observability And Embedder Failure Semantics](0028-hotstuff-finalization-observability-and-embedder-failure-semantics.md)
- [ADR-0031: Certified Ancestor Dependent Transaction Pipelining](0031-certified-ancestor-dependent-transaction-pipelining.md)
- [ADR-0032: Stage-Based Transaction Pipeline API](0032-stage-based-transaction-pipeline-api.md)
- [ADR-0036: Height-bounded application locks and explicit pipeline dependencies](0036-height-bounded-application-locks-and-explicit-pipeline-dependencies.md)
- [ADR-0037: Versioned Application Execution Upgrade And Empty Blocks](0037-versioned-application-execution-upgrade-and-empty-blocks.md)
- [Plan 0033: Application Execution Conformance And V2 Activation](../plans/0033-application-execution-conformance-and-v2-activation-plan.md)
