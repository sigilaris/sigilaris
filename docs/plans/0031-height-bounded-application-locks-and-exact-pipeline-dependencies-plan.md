# 0031 - v0.3.0-M1 Height-Bounded Locks And Exact Pipeline Release Plan

## Status

Complete

## Created

2026-08-29

## Last Updated

2026-08-29

## Background

ADR-0036 replaces unbounded application locks and cross-epoch recovery machinery with a signed inclusion-height bound. It also permits one
application-registered exact `Producer -> Consumer` dependency profile without turning the generic pipeline API into a programmable transaction
graph.

The repository already contains useful lower-level pieces:

- ADR-0031 certified-ancestor branch validation and later-descendant eligibility;
- ADR-0032 stage-based pipeline admission, durable records, idempotency, projection, and HTTP adapters;
- application-neutral conflict-footprint derivation and sequential schedulable execution;
- finalized block observations and SwayDB-backed runtime stores.

The current implementation does not yet contain the substrate assumed by ADR-0035 and ADR-0036:

- `BlockHeader` has no committed execution-plan root;
- scheduling is still `Schedulable(footprint)` or `Compatibility(reason)`, without committed `ConflictFree` and `Ordered` waves;
- there are no application input-lock votes/certificates, certified-effect artifacts, proposal reservations, or authenticated applied-execution
  index;
- pipeline request/record models contain stages and `waitFor`, but no application-verified dependency profile, signed common deadline, or execution
  mode;
- epoch/configuration rotation does not drain an application lock domain to empty.

This plan implements the ADR-0035 M1 substrate first and then implements the ADR-0036 delta on top of it. The substrate and deadline/pipeline work
remain separately reviewable phases, but they ship together as the Sigilaris `0.3.0-M1` release. No downstream application repository or
unpublished source checkout is an input to that release.

## Goal

Deliver an application-neutral runtime profile in which:

1. every lock-capable application execution commits to `lastInclusionHeight`;
2. admission enforces the configured maximum lock lifetime and candidate application enforces the inclusive deadline;
3. application locks and proposal reservations are released only by canonical application or finalized-height `expiredUnapplied`;
4. epoch/configuration changes stop admission and drain all locks and reservations to zero;
5. an activated application profile may validate exactly one predeclared `Producer -> Consumer` dependency;
6. `OrderedAtomic` applies both transactions atomically in one ordered execution plan;
7. `CertifiedAncestor` accepts the consumer in any descendant that proves the producer ancestor, while adjacent placement remains a latency
   preference only;
8. current generic stage pipelines remain compatible and do not acquire same-block dependency semantics implicitly;
9. Sigilaris imports no embedding-application transaction types, reference codecs, or business-success rules; and
10. the reviewed JVM and Scala.js artifacts, integration guide, release notes, and golden vectors are published as `0.3.0-M1` before a downstream
    application starts implementation against this contract.

## Scope

- Add application-neutral deadline, dependency-profile, execution-mode, verified-plan, lifecycle, and failure models.
- Implement the accepted ADR-0035 M1 execution-plan, exact-input, ordered-wave, lock/certificate, reservation-interlock, replay, and lifecycle
  substrate required by ADR-0036.
- Define canonical codecs, signature-domain bindings, persistent schema versions, and golden vectors for every ADR-0036 field carried by Sigilaris.
- Add committed protocol configuration for `maxLockLifetimeBlocks` and activated dependency-profile manifests.
- Add deterministic hooks through which an embedder verifies signed application plan bytes and returns an opaque normalized dependency descriptor.
- Persist application locks, proposal reservations, application outcomes, and an authenticated applied-execution index.
- Integrate deadline checks into admission, voting, certification, proposal validation, block application, finalization, replay, and restart recovery.
- Extend the existing pipeline service with an explicitly activated exact dependency profile while preserving the generic ADR-0032 mode.
- Implement finalized-height expiry and maintenance drain-to-empty operations.
- Add pipeline/query diagnostics for deadline, execution mode, profile, progress, and `expiredUnapplied`.
- Add conformance, multi-validator, persistence, HTTP compatibility, and negative security tests.
- Publish `sigilaris-core` and `sigilaris-node-common` for both Scala JVM and Scala.js, and publish `sigilaris-node-jvm` for Scala JVM.

## Non-Goals

- Do not define application transaction families, object ids, UTXO hashes, balances, escrow rules, debt rules, or value conservation.
- Do not decode or reinterpret application reference bytes inside Sigilaris core.
- Do not activate `Latest`, implicit same-block lookup, arbitrary DAGs, branching, more than one dependency edge, cross-pipeline consumption, or
  cross-lane chaining.
- Do not use wall-clock TTL, local timeout, view change, absent QC, PAC, or recovery-frontier evidence to release a live lock or reservation.
- Do not carry locks or reservations across epoch/configuration changes.
- Do not make a proposal, certificate, inclusion, or application-specific materialization equivalent to finalization.
- Do not retrofit ADR-0036 semantics onto historical pipeline records or historical block bytes.
- Do not add future execution modes or recovery mechanisms outside the accepted ADR-0035/0036 M1 contracts.
- Do not encode a specific latency SLA as a consensus validity condition.

## Related ADRs And Docs

- [ADR-0031: Certified-Ancestor Dependent Transaction Pipelining](../adr/0031-certified-ancestor-dependent-transaction-pipelining.md)
- [ADR-0032: Stage-Based Transaction Pipeline API](../adr/0032-stage-based-transaction-pipeline-api.md)
- [ADR-0035: Application Execution Lanes, Ordered Waves, And Certified Fast Admission](../adr/0035-application-execution-lanes-ordered-waves-and-certified-fast-admission.md)
- [ADR-0036: Height-Bounded Application Locks And Explicit Pipeline Dependencies](../adr/0036-height-bounded-application-locks-and-explicit-pipeline-dependencies.md)
- [0023: Certified Ancestor Dependent Transaction Pipelining Plan](0023-certified-ancestor-dependent-transaction-pipelining-plan.md)
- [0025: Stage-Based Transaction Pipeline API Plan](0025-stage-based-transaction-pipeline-api-plan.md)
- [Plans Guide](README.md)

## Decisions Locked For Implementation

### D1. This plan owns the ADR-0035 substrate required for the release

ADR-0036 runtime activation requires all of the following versioned ADR-0035 surfaces:

- a block-committed execution plan and canonical ordered-wave semantics;
- exact application input descriptors with separate full-input, lock-subset, and footprint commitments;
- application input-lock and certified-effect vote/certificate types with historical validator-set verification;
- reciprocal proposal-reservation interlock;
- deterministic application result/state-root validation; and
- an execution id stable across admission, certification, application, and replay.

Phase 0 freezes the type/codec matrix and Phase 1 implements these surfaces. The Phase 1 commits and review evidence remain distinct from later
ADR-0036 work so failures can be attributed precisely, but `0.3.0-M1` is not published until every ADR-0035 and ADR-0036 activation vector passes.

### D2. Application verification is a registered opaque hook

The activated historical protocol manifest identifies a dependency profile by opaque profile id, version, verifier slot, and verifier-code/manifest
digest. An embedder installs the deterministic verifier for that slot. Given the exact submitted transaction bytes and signed application plan, the
verifier returns either a typed failure or a normalized descriptor containing:

- the signed application pipeline id;
- profile id and version;
- exactly two ordered execution ids;
- `OrderedAtomic` or `CertifiedAncestor` mode;
- one opaque exact producer-output reference and its commitment;
- the producer and consumer positions; and
- one common `lastInclusionHeight`.

Sigilaris validates the normalized shape and commitments but does not understand the application family ids, output-reference format, result bytes,
or business meaning. Unknown profile ids, versions, verifier slots, or manifest digests fail closed.

### D3. Node pipeline identity and signed application identity remain distinct

The existing `TxPipelineId` remains the node record/query and idempotency identity defined by ADR-0032. The application verifier returns a separate
signed application pipeline id. An adapter may derive or display one from the other only under a versioned identity strategy; textual equality is
not assumed by core validity rules.

Both identities and their binding are persisted so retries cannot substitute a different signed plan under the same node pipeline record.

### D4. Every stage signs one common inclusion deadline

Every transaction in an activated exact pipeline must produce the same `lastInclusionHeight` through the application descriptor. Missing,
malformed, or mismatched values reject the complete pipeline before any lock or reservation is created.

Admission validates:

```text
base.height < lastInclusionHeight
lastInclusionHeight <= base.height + maxLockLifetimeBlocks
```

First application is valid at candidate height `<= lastInclusionHeight` and invalid above it. Finalization may occur later when application by the
bound is already recorded.

### D5. Finalized canonical height is the only expiry clock

An application lock or proposal reservation becomes `expiredUnapplied` only when:

```text
finalizedCanonicalHeight > lastInclusionHeight
and AppliedExecutionIndex proves no application at or below the deadline
```

Locally rejected, abandoned, or rolled-back proposals are not early-release evidence. Admission rejection creates no reservation. Finality stall
therefore stalls expiry. An expired execution is terminal; a client must sign new bytes and obtain a new execution id for another attempt.

### D6. Applied-index and lock updates are replay-safe and crash-safe

Canonical application writes its application result, applied-execution entry, and lock/reservation terminal state as one recoverable application
boundary. If the underlying storage engine cannot atomically update every physical map, a durable application journal plus idempotent recovery must
make partial visibility impossible to the validator and query surfaces.

The applied index records at least execution id, first application height, block id, result digest, and protocol version. Expiry checks use this
authenticated record rather than absence from a local queue.

### D7. The M1 dependency shape is exactly one edge

The normalized activated plan has two transactions and one `Producer -> Consumer` edge. The consumer reference is fixed before submission and must
match the producer result through the application verifier. Only that consumer in that signed pipeline may use the pre-final output.

Extra stages, multiple producers or consumers, branches, a second edge, reference replacement, outside consumers, and cross-pipeline reuse are
deterministic admission or proposal-validation failures.

### D8. Execution modes share validity but not placement

- `OrderedAtomic` puts the two entries consecutively in one committed ordered wave and applies all or none. Producer success followed by consumer
  failure rejects the candidate application; no partial producer result becomes canonical.
- `CertifiedAncestor` uses ADR-0031 ancestry proof. The consumer may appear in any proven descendant of the producer block. Adjacent placement is a
  scheduler preference and metric label, not a validity rule.

Both modes bind the same profile, ordered execution ids, exact dependency, and common deadline.

### D9. Configuration changes require drain-to-empty

`maxLockLifetimeBlocks`, activated profile entries, verifier manifest digest, signature/codec version, validator set, epoch, and any lock-domain
parameter are committed protocol configuration. A change performs:

1. close new lock votes, certificates, and proposal reservations;
2. finalize past the greatest admitted deadline;
3. resolve every live entry as applied or `expiredUnapplied`;
4. prove zero live locks and reservations; and
5. activate the new configuration and reopen admission.

There is no node-local override, rolling mixed-config activation, or cross-epoch handoff. Each deployment manifest supplies an explicit initial
`maxLockLifetimeBlocks`; the library does not hide a production default.

### D10. Existing generic pipelines remain generic

The current ADR-0032 request/record form remains decodable and retains certified-ancestor stage barriers. It does not gain same-block output
consumption, signed application identity, or lock semantics by inference. Exact dependency mode uses a new versioned request/record variant or an
equivalently explicit discriminator.

Historical records are read under their original codec and identity strategy. Fresh activated records use the new schema. Unknown discriminators
or missing activated fields fail closed.

### D11. The release platform matrix is part of the contract

The `0.3.0-M1` staging and release gate requires all five Scala 3 artifacts:

```text
org.sigilaris:sigilaris-core_3:0.3.0-M1
org.sigilaris:sigilaris-core_sjs1_3:0.3.0-M1
org.sigilaris:sigilaris-node-common_3:0.3.0-M1
org.sigilaris:sigilaris-node-common_sjs1_3:0.3.0-M1
org.sigilaris:sigilaris-node-jvm_3:0.3.0-M1
```

JVM-only publication does not satisfy the release gate. The staged-artifact smoke uses a neutral JVM consumer for core/node-common/node-jvm and a
separate Scala.js consumer for core/node-common. Release notes list every platform coordinate and checksum explicitly.

## Deliverable Map

| ADR-0036 contract | Primary implementation area | Completion gate |
| --- | --- | --- |
| signed deadline and maximum lifetime | shared models, protocol config, admission | canonical golden vectors and boundary tests |
| application locks and reservations | ADR-0035 integration, durable JVM stores | restart-safe conflict and release tests |
| applied-index guarded expiry | finalization/application boundary | finality-stall and inclusive/exclusive height tests |
| exact registered dependency | pipeline admission and application verifier hook | profile-isolation and scope-negative tests |
| ordered atomic mode | committed ordered-wave executor | producer/consumer failure leaves no partial state |
| certified ancestor mode | ADR-0031 eligibility and vote validation | adjacent, later-descendant, sibling, and missing-proof tests |
| drain-to-empty | runtime operations and committed configuration | non-empty refusal and zero-live activation tests |
| public lifecycle | pipeline projection and transport | compatible JSON/OpenAPI and terminal-state tests |
| consumable M1 release | JVM/Scala.js build, documentation, and publication | all five tagged artifacts, checksums, and two-platform consumer smoke |

## Change Areas

### Shared Models And Codecs

- `modules/node-common/shared/src/main/scala/org/sigilaris/node/txpipeline`
  - versioned exact-pipeline submit/normalized/record/snapshot models;
  - signed application pipeline identity binding;
  - dependency profile/version, execution mode, opaque reference commitment, and common deadline;
  - `expiredUnapplied` lifecycle and typed validation failures;
  - old-record compatibility decoder.
- ADR-0035 application execution model package selected in Phase 0
  - deadline-bearing lock/certificate subjects and verified dependency descriptor;
  - canonical byte encoders and signature-domain preimages.

### Core Scheduling And Validation

- Add versioned execution plans with committed `ConflictFree`/`Ordered` waves and deterministic body membership/order.
- Add exact field-role manifests, resolved-input/lock-subset/footprint commitments, stable mutable conflict identities, and compatibility-singleton
  behavior from ADR-0035.
- Add application vote/certificate domains, historical validator-set checks, reciprocal lock/reservation interlock, deterministic result binding,
  and first-application-wins replay handling.
- Integrate the verified exact dependency into committed ordered-wave planning without changing generic conflict-free semantics.
- Enforce all-or-none ordered application and reject implicit new-output discovery.
- Bind deadline and dependency-plan digest into execution, lock, result, and replay identities.
- Keep application family ids and reference bytes opaque.

### JVM Runtime And Storage

- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/txpipeline`
  - profile-aware admission, common-deadline validation, eligibility, projection, and retry handling.
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff`
  - deadline-aware proposal input/vote validation;
  - ordered-atomic plan carriage;
  - certified-ancestor exact dependency validation;
  - finalized-height expiry and drain admission gates.
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/storage`
  - in-memory and SwayDB lock, reservation, applied-index, and versioned pipeline persistence;
  - startup reconciliation and application-journal recovery.

### Transport And Observability

- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/txpipeline`
  - versioned exact-pipeline input and lifecycle output;
  - deterministic error mapping and OpenAPI schema.
- Runtime diagnostics
  - live lock/reservation counts, greatest admitted deadline, expiry count, drain state, profile/mode counts, and rejection reasons;
  - no application-specific labels or transaction names.

### Tests And Documentation

- Shared codec/property/golden suites for JVM and JS where the shared surface is cross-built.
- In-memory and SwayDB restart/fault tests.
- HotStuff four-validator conformance and adversarial branch tests.
- Pipeline HTTP compatibility and OpenAPI snapshots.
- Activation, drain, recovery, and finality-stall operator documentation.

## Implementation Phases

### Phase 0: Substrate Inventory And Contract Lock

- Record the current ADR-0031/0032 implementation surfaces and the missing ADR-0035 prerequisites.
- Freeze type names, numeric/string tags, canonical codecs, signature domains, error taxonomy, persistent schema versions, and configuration keys.
- Publish the ADR-0035 substrate compatibility matrix and block runtime activation when any required version is absent.
- Define two application-neutral test profiles with different opaque ids/codecs to prove registry isolation.
- Freeze the initial deployment manifest format and drain-to-empty state machine.

### Phase 1: ADR-0035 M1 Execution And Certification Substrate

- Add a versioned `BlockHeader`/`BlockId` codec with the execution-plan commitment, historical decoding, atomic protocol activation, and the
  canonical `ConflictFree`/`Ordered` wave model.
- Add exact application family field-role manifests and separate full-input, lock-subset, footprint, and normalized-result commitments.
- Add stable mutable conflict identities, exact freshness validation, compatibility-singleton execution, and deterministic wave failure behavior.
- Add input-lock/certified-effect vote and certificate domains with historical validator-set and quorum verification.
- Add reciprocal consensus proposal reservations, deterministic result/state-root binding, lifecycle states, and replay handling.
- Pass the ADR-0035 four-validator and adversarial activation gates before ADR-0036 runtime integration begins.

### Phase 2: ADR-0036 Shared Models, Codecs, And Configuration

- Add deadline, profile, mode, verified plan, opaque dependency, signed application identity, and lifecycle models.
- Add versioned request/record/snapshot codecs with compatibility reads for current generic records.
- Add canonical signature/hash preimages and golden vectors for every ADR-0036 artifact extension.
- Add committed configuration parsing/validation with no hidden production lifetime default.
- Add fail-closed unknown-version, unknown-profile, and missing-field behavior.
- Carry the Phase 1 decoder lesson forward: inspect a unique version tag before
  decoding variant fields, require canonical optional-field markers, and bind
  family/profile version into every derived commitment rather than only the
  outer record.

### Phase 3: Durable Locks, Applied Index, Expiry, And Drain

- Add persistent lock, proposal-reservation, applied-execution, and terminal-outcome stores for in-memory and SwayDB profiles.
- Decode each durable variant by schema before payload fields, revalidate
  cross-record identities/commitments after decoding, and keep terminal audit
  rows separate from live lock/reservation ownership.
- Integrate deadline checks into admission, vote/certificate creation, proposal validation, application, and replay.
- Apply finalized-height expiry only after the applied-index check.
- Add crash recovery for partial physical writes and deterministic startup reconciliation.
- Implement admission closure, greatest-deadline tracking, zero-live proof, and committed configuration rotation.

### Phase 4: Registered Exact Pipeline Admission

- Add the deterministic application verifier registry and historical manifest binding.
- Authenticate the verifier/profile result before any durable lock is created,
  and persist enough evidence for reconciliation to reproduce that decision.
- Preserve opaque dependency references as raw bytes end to end; only the
  registered verifier may interpret them, while public JSON uses canonical
  lowercase hex.
- Normalize and validate exactly two transactions and one dependency edge.
- Enforce common deadline, signed-plan/node-record identity binding, exact opaque reference commitment, and same-pipeline scope.
- Reject outside consumers, cross-pipeline reuse, extra edges/stages, implicit lookup, and inactive profiles before lock creation.
- Persist the complete verified descriptor, including the exact certified
  artifact and resulting state root, needed for proposal validation and replay.
- Reconcile physical descriptor rows against journal replay without relying on
  storage-engine key iteration order.

### Phase 5: Ordered-Atomic And Certified-Ancestor Execution

- Map `OrderedAtomic` to one committed ordered wave and verify atomic producer/consumer result application.
- Map `CertifiedAncestor` to ADR-0031 branch eligibility and vote validation.
- Accept adjacent and later proven descendants; reject sibling/conflicting ancestry and unavailable proof.
- Integrate reciprocal lock/reservation checks with both modes.
- Resolve and authenticate historical lock/effect certificates at the active
  manifest boundary before either execution mode may vote or apply results.
- Consume the persisted, store-revalidated exact descriptor for execution;
  never rerun an inactive verifier to reinterpret a historical accepted plan.
- Commit exact lifecycle/projection updates in the same recoverable application
  journal boundary as result, applied-index, lock, and reservation changes.
- Compare the entire certified artifact, deadline, placement, result, and state
  root during replay; do not treat an execution id alone as idempotent.
- Ensure lifecycle states distinguish accepted, lock-certified, effect-certified, included, finalized, materialized, and `expiredUnapplied`.

### Phase 6: API, Projection, Persistence Compatibility, And Operations

- Expose versioned exact-pipeline admission/query fields and deterministic typed errors.
- Read exact lifecycle and terminal failure from the recoverable application
  journal projection rather than the admission store's original accepted row;
  do not rerun an inactive verifier while serving a historical query.
- Preserve current generic pipeline JSON, idempotency, identity, and store decoding.
- Add an explicit compatibility read for pre-exact application journal deltas,
  and refuse startup when the exact physical namespace, journal replay, active
  manifest, or store schema disagree.
- Add diagnostics and an operator-only drain status/action surface, or an equivalent offline runbook if no mutable operator API is activated.
- Add startup refusal for incompatible protocol manifest/store combinations.
- Document retry, expiry, finality stall, drain, backup/restore, and configuration-change procedures.

### Phase 7: Conformance, Multi-Validator Verification, And `0.3.0-M1` Release

- Run codec, property, persistence, API, HotStuff, and full module regression suites.
- Run a four-validator 3-of-4 lock/certification fixture proving that two executions cannot certify the same stable mutable input.
- Run both neutral test profiles through ordered-atomic and certified-ancestor modes.
- Re-run the generic-boundary bypass, immutable terminal-failure replay,
  lifecycle/applied-index reconciliation, and exact-namespace fault fixtures.
- Exercise finality stall, restart, expiry boundary, non-empty drain refusal, and successful configuration rotation.
- Verify import/package boundaries and repository diffs contain no embedding-application dependency.
- Run clean downstream-neutral JVM and Scala.js consumer smoke fixtures against staged Maven artifacts rather than repository source dependencies.
- Publish release notes, integration guide, canonical golden vectors, API/codec compatibility matrix, artifact checksums, and the
  JVM/Scala.js `sigilaris-core`, JVM/Scala.js `sigilaris-node-common`, and JVM `sigilaris-node-jvm` `0.3.0-M1` artifacts.
- Create the `v0.3.0-M1` release tag only after staged-artifact verification and record the immutable artifact coordinates in the release notes.
- Normalize the pre-existing repository-wide Scalafmt baseline (including the
  parse-blocking blank line after the `CompositionTest` annotation) before the
  final `scalafmtCheckAll` gate. Until then, each implementation phase formats
  only its exact changed-file set so unrelated mass rewrites do not obscure
  protocol review.

## Test Plan

### Codec And Contract Tests

- Golden encode/decode vectors for deadline-bearing transaction descriptors, votes, certificates, effects, verified dependency plans, store records,
  and public JSON.
- Golden and negative vectors for the versioned block header/id, execution-plan membership/order, exact input/lock/footprint commitments, and
  normalized result binding.
- Domain-separation negatives for transaction, lock, effect, pipeline, profile version, epoch, validator set, and configuration digest.
- JVM/JS parity for shared wire models.
- Historical generic pipeline record and request fixtures remain byte/JSON compatible.

### Deadline And Lock Tests

- Accept `base.height < deadline == base.height + max`; reject equal-to-base and above-max deadlines.
- Permit first application at the deadline and reject first application after it.
- Do not expire at finalized height equal to the deadline; expire only when finalized height is greater.
- Do not expire during finality stall or because a proposal was abandoned, rolled back, timed out, or lacked a QC.
- Do not revive an expired execution; exact retransmission before expiry remains idempotent.
- Prevent two conflicting execution ids from both obtaining valid quorum artifacts.

### Persistence And Drain Tests

- Recover each fault-injected boundary between result, applied index, lock, reservation, and pipeline-record writes.
- Reconcile SwayDB state after restart without exposing a partial canonical application.
- Refuse configuration rotation with any live lock/reservation or unresolved applied-index state.
- Complete drain after finalized progress passes the greatest deadline, then activate the new manifest with zero live entries.
- Refuse node-local/mixed-manifest overrides.

### Exact Dependency Tests

- Accept two different registered neutral profiles without core knowledge of their family/reference encodings.
- Reject inactive/unknown profile, manifest digest mismatch, wrong verifier slot, wrong stage count, extra edge, and changed signed plan.
- Reject mismatched stage deadlines, changed producer reference, outside consumer, second pipeline reuse, and implicit latest lookup.
- Prove that exact producer bytes deterministically bind the reference accepted by the consumer verifier.

### Execution Mode Tests

- `OrderedAtomic`: producer failure, consumer failure, and result/root mismatch all leave no partial canonical producer state.
- `CertifiedAncestor`: adjacent child and later descendant succeed with proof; sibling and missing ancestry suppress the vote or fail deterministically.
- Both modes bind identical profile/plan/deadline semantics and differ only in placement.
- Consumer finalization updates the pipeline finalized boundary; earlier lifecycle states remain progress only.

### Verification Commands

Targeted commands are refined with exact suite names during Phase 0. The minimum gates are:

```text
sbt "coreJVM/test" "coreJS/test" "nodeCommonJVM/testOnly *Application*Pipeline* *Application*Lock*" "nodeCommonJS/test"
sbt "nodeJvm/testOnly *TxPipeline* *ApplicationLock* *AppliedExecution* *HotStuff*Dependency*"
sbt "nodeJvm/test"
```

Each phase also runs `scalafmtCheckAll`, compile, and `git diff --check` before review.

The frozen contract, exact phase suite patterns, schema/version matrix, and
activation gate are recorded in
[v0.3.0-M1 Contract Lock](../dev/v0.3.0-m1-contract-lock.md).

## Risks And Mitigations

- **Risk: ADR-0036 code is built against an incomplete ADR-0035 substrate.**
  - Mitigation: implement and close the ADR-0035 substrate in Phase 1, retain an explicit compatibility matrix, and prohibit Phase 2 runtime work
    until the Phase 1 conformance gate passes.
- **Risk: physical stores expose a result without its applied index or release a lock early.**
  - Mitigation: one recoverable application journal boundary, idempotent replay, fault injection, and startup reconciliation.
- **Risk: application-specific semantics leak into the runtime.**
  - Mitigation: opaque profile/reference bytes, two unrelated neutral conformance profiles, and import-rule tests.
- **Risk: existing node pipeline identity is confused with the signed application pipeline id.**
  - Mitigation: distinct types, persisted binding, versioned derivation only, and substitution negative tests.
- **Risk: a finality halt makes locks unavailable for a long period.**
  - Mitigation: expose greatest deadline/live counts, document the availability trade-off, and never weaken safety with local time.
- **Risk: configuration rotation deadlocks on hidden durable entries.**
  - Mitigation: authoritative full-store scan, zero-live proof, restart rehearsal, and no cross-epoch carry.
- **Risk: pipeline support accidentally opens a general DAG.**
  - Mitigation: exact two-transaction/one-edge shape, profile registry, committed plan, and broad scope-negative tests.
- **Risk: mixed binaries interpret new block or store bytes differently.**
  - Mitigation: atomic protocol activation, historical codec retention, manifest digest checks, and startup refusal.

## Acceptance Criteria

1. All ADR-0036 wire fields have versioned canonical codecs, signature/hash bindings, persistent schemas, and golden vectors.
2. The ADR-0035 execution-plan, exact-input, ordered-wave, quorum artifact, reservation, replay, and lifecycle activation gates pass before
   ADR-0036 runtime integration is enabled.
3. Deadline admission, inclusive application, exclusive finalized-height expiry, and applied-index checks match ADR-0036 exactly.
4. Locks and reservations survive restart and release only on canonical application or authenticated `expiredUnapplied`.
5. Configuration changes cannot proceed until authoritative live lock/reservation counts are zero.
6. Every activated pipeline stage signs the same deadline; mismatch creates no lock, reservation, or partial record.
7. The registered exact dependency is limited to two transactions and one edge, with no outside or cross-pipeline consumer.
8. Ordered-atomic failure leaves no partial producer state; certified-ancestor accepts any proven descendant and rejects conflicting ancestry.
9. Existing generic pipeline fixtures remain compatible and acquire no implicit same-block semantics.
10. Four-validator tests prove quorum-intersection safety for conflicting executions.
11. Public status distinguishes progress, finalization, materialization, and `expiredUnapplied`.
12. Source and test import checks show no dependency on any embedding-application repository, package, transaction family, or protocol document.
13. Targeted and full module tests, formatting checks, local documentation links, and `git diff --check` pass.
14. Downstream-neutral JVM and Scala.js smoke projects compile, link, and run against all five staged `0.3.0-M1` Maven artifacts without a
    source/project dependency.
15. Release notes publish immutable artifact coordinates, checksums, golden-vector locations, configuration/activation requirements, and known
    limitations, and the `v0.3.0-M1` tag points to the reviewed release commit.

## Checklist

### Phase 0: Substrate Inventory And Contract Lock

- [x] Record current ADR-0031/0032 surfaces and missing ADR-0035 prerequisites.
- [x] Freeze tags, codecs, signature domains, errors, store versions, and config keys.
- [x] Publish the ADR-0035 substrate compatibility matrix and activation gate.
- [x] Define two application-neutral profile fixtures and the initial deployment manifest format.
- [x] Review Phase 0 artifacts and record the verdict before implementation proceeds.

### Phase 1: ADR-0035 M1 Execution And Certification Substrate

- [x] Add the versioned block header/id, execution-plan root, historical decoder, activation boundary, and canonical ordered/conflict-free waves.
- [x] Add exact field-role manifests and separate full-input/lock-subset/footprint/result commitments.
- [x] Add exact freshness, stable conflicts, compatibility-singleton execution, and deterministic failure.
- [x] Add vote/certificate domains, historical-set quorum verification, and reciprocal reservations.
- [x] Add deterministic result binding, lifecycle, and replay handling.
- [x] Pass ADR-0035 four-validator and adversarial activation gates.

### Phase 2: ADR-0036 Shared Models, Codecs, And Configuration

- [x] Add deadline/profile/mode/verified-plan/lifecycle shared models.
- [x] Add versioned request, record, snapshot, and compatibility codecs.
- [x] Add canonical preimages and golden vectors.
- [x] Add committed configuration parsing and fail-closed validation.
- [x] Pass JVM/JS shared-model targeted tests.

### Phase 3: Durable Locks, Applied Index, Expiry, And Drain

- [x] Implement in-memory and SwayDB stores and schemas.
- [x] Integrate deadline and applied-index checks at all runtime boundaries.
- [x] Add finalized-height expiry and terminal outcomes.
- [x] Add recoverable application journaling and restart reconciliation.
- [x] Add drain closure, greatest-deadline, zero-live, and rotation logic.
- [x] Pass fault-injection, finality-stall, and drain tests.

### Phase 4: Registered Exact Pipeline Admission

- [x] Implement the application verifier registry and manifest binding.
- [x] Validate exactly two executions, one edge, one common deadline, and identity binding.
- [x] Persist the complete verified descriptor.
- [x] Reject inactive profiles, substitutions, outside consumers, extra edges, and implicit lookup.
- [x] Pass both neutral-profile conformance suites.

### Phase 5: Ordered-Atomic And Certified-Ancestor Execution

- [x] Integrate ordered-atomic plan construction and all-or-none application.
- [x] Integrate certified-ancestor branch eligibility and vote validation.
- [x] Accept adjacent and later descendants and reject sibling/missing ancestry.
- [x] Integrate reciprocal lock/reservation checks and lifecycle projection.
- [x] Pass atomic-failure and adversarial branch tests.

### Phase 6: API, Compatibility, And Operations

- [x] Add versioned transport models, deterministic errors, and OpenAPI output.
- [x] Preserve generic pipeline identity, idempotency, and historical record reads.
- [x] Add diagnostics and drain operational surface/runbook.
- [x] Add incompatible-manifest/store startup refusal.
- [x] Document retry, expiry, finality-stall, backup/restore, and rotation behavior.

### Phase 7: Conformance And `0.3.0-M1` Release

- [x] Run shared, node JVM, HotStuff, persistence, transport, and full regression suites.
- [x] Pass the four-validator conflicting-lock and exact-pipeline fixtures.
- [x] Rehearse restart, finality stall, expiry, drain refusal, and successful rotation.
- [x] Verify repository dependency/import neutrality.
- [x] Run downstream-neutral JVM and Scala.js smoke fixtures against staged Maven artifacts.
- [x] Publish release notes, integration guide, golden vectors, compatibility matrix, checksums, and all five platform artifacts.
- [x] Create the `v0.3.0-M1` tag only after staged-artifact verification.

## Follow-Ups

- General dependency DAGs, multiple edges, longer pipelines, and cross-lane chaining require a new ADR and plan.
- Any `Latest` binding requires a new family/schema version and separate concurrency semantics.
- Cross-epoch lock handoff or early-release certificates require a new safety argument and are not incremental extensions of this plan.
- A generic streaming lifecycle API may be added after the snapshot/query contract is stable.
- Deployment-specific latency tuning remains an operator profile layered above this consensus contract.

## Execution Journal

### Phase 0

- Inventory: the existing v1 block header has no version or execution-plan
  commitment; scheduling exposes only `Schedulable`/`Compatibility`; generic
  pipelines have no discriminator, signed application identity, deadline, or
  exact dependency; and no application lock-domain stores exist.
- Contract: type/package names, numeric and wire tags, canonical hash domains,
  error reasons, persistent schema versions, manifest keys, two neutral test
  profiles, and the drain state machine are frozen in the contract-lock
  document.
- Review loop 1: found that activation evidence was listed without a rule that
  forbids partial production enablement. Added an atomic, all-rows-required
  activation rule and startup/rotation checks.
- Review loop 2: found that public JSON height encoding and collection ordering
  were implicit. Froze non-negative JSON integers, vector order, and canonical
  signer/profile sorting.
- Review loop 3 verdict: `No finding`.
- Lesson Learned: cross-built contracts must live in `core/shared` or
  `node-common/shared`, while durable recovery remains JVM-only. Phase 1 will
  therefore put execution-plan and application artifact value types in core,
  retain the existing JVM block model with an explicit historical codec, and
  avoid coupling cross-built protocol types to HotStuff runtime types.

### Phase 1

- Implementation: added cross-built application protocol identifiers, exact
  field-role manifests, family/version-bound input commitments, exact freshness,
  stable execution identity, canonical conflict-free/ordered waves, atomic
  ordered execution, lock/effect artifacts, historical quorum verification,
  reciprocal reservations, first-application-wins replay, and lifecycle state.
- Block compatibility: retained byte-identical v1 header/id encoding and golden
  vectors, added explicitly tagged v2 headers with execution-plan roots, a
  height activation boundary, strict historical/v2 decoding, and a pinned v2
  block-id vector.
- Review loop 1: found a duplicated execution-plan domain in the hash preimage
  and an implicit freshness seam. Removed the duplicate binding and added exact
  missing/changed/extra input validation.
- Review loop 2: found that family version was absent from input commitments and
  certificate encoding did not canonicalize votes. Bound family id/version into
  all three input commitments and canonicalized lock/effect certificate bytes.
- Review loop 3: found terminal lock records still blocked later executions and
  initial application did not require the reciprocal reservation. Limited
  conflicts to live entries and required live lock/reservation pairs while
  keeping replay idempotent.
- Review loop 4: found unknown header tags could be interpreted as legacy
  `Option` lengths. Restricted v1 to parent markers `0/1`, v2 to tag `2`, and v2
  root presence to marker `1`; final verdict: `No finding`.
- Verification: JVM and Scala.js protocol suites passed; block model, scheduling,
  snapshot, and SwayDB snapshot suites passed; full core JVM/JS regression
  passed 477 tests; changed files were Scalafmt-formatted; import-neutrality and
  `git diff --check` passed.
- Lesson Learned: variant decoding must branch on a reserved tag before parsing
  payloads; every commitment must bind the registry version that gives bytes
  meaning; and terminal audit records must be separated from live conflict
  ownership. Phase 2 will apply these rules to exact-pipeline JSON/binary/store
  variants and manifest validation. Repository-wide Scalafmt normalization is
  deferred to the Phase 7 gate because the pre-existing baseline currently
  rewrites unrelated files and stops at `CompositionTest.scala`.

### Phase 2

- Implementation: added the cross-built exact request, normalized request,
  verified dependency plan, raw opaque reference, signed/node identity binding,
  record/snapshot schema v2, lifecycle, protocol-manifest, and substrate-version
  models. Historical generic request/record/snapshot JSON remains unwrapped and
  byte-for-byte encoder compatible.
- Canonical contract: used the repository `ByteEncoder` for domain-first
  reference, verified-plan, identity-binding, and sorted-manifest preimages;
  published pinned JSON, binary preimages, Keccak digests, and record/snapshot
  JSON checksums in `v0.3.0-m1-golden-vectors.md`.
- Review loop 1: found that derived decoders accepted a substituted reference
  commitment and identity-binding digest. Added recomputation at decode time and
  negative substitution tests.
- Review loop 2: found a custom text preimage that violated the frozen common
  `ByteEncoder` contract, nullable discriminators treated as absent, nested
  manifest keys that did not match the deployment contract, and record/snapshot
  identity links that were not revalidated. Replaced the preimages, made the
  manifest flat and fully required, rejected null/unknown tags, added binding
  validation, and carried the binding into public snapshots.
- Review loop 3: found the opaque producer-output reference was represented and
  hashed as UTF-8 text, which could not preserve the two neutral binary profile
  codecs without interpretation. Replaced it with non-empty raw bytes, limited
  JSON to canonical lowercase hex, and updated all golden vectors.
- Review loop 4 verdict: `No finding`.
- Verification: targeted exact/generic model tests passed 19 tests on JVM and
  Scala.js; full node-common regression passed 118 JVM and 117 Scala.js tests;
  changed Scala files were individually Scalafmt-formatted and `git diff
  --check` passed.
- Lesson Learned: every durable/public wrapper must verify both its own digest
  and links to embedded identities after decoding; absence and explicit null are
  distinct at a version boundary; and “opaque” must mean uninterpreted bytes,
  not a convenient text surrogate. Phase 3 will apply decode-and-reconcile
  validation to journals and stores, and Phase 4 will pass raw reference bytes
  only to the historically bound verifier.

### Phase 3

- Implementation: added schema-first durable lock, reciprocal reservation,
  applied-index, terminal-outcome, drain-control, and journal records; an
  in-memory transactional store; and one namespaced SwayDB safety database with
  prepared/committed recovery. The runtime enforces exclusive-base and
  inclusive-maximum admission, inclusive application, authenticated lock/effect
  certificates, full-result/state-root replay equality, finalized-height-only
  expiry, and zero-live manifest rotation.
- Review loop 1: found that vote conflicts and reservations could be evaluated
  at different lifecycle boundaries. Closed reservations with admission and
  required exact reciprocal input ownership at proposal and application time.
- Review loop 2: found cross-record reconciliation did not reject two different
  descriptors for one execution, incomplete reservation input sets, terminal
  lifecycle disagreement, or an insufficient drain watermark. Added complete
  ownership, identity, terminal, and greatest-deadline proofs.
- Review loop 3: found SwayDB key iteration was not lexicographic and separate
  physical maps made restart fault tests contend on shutdown locks. Moved the
  safety domain under one physical namespaced database and made namespace scans
  filter and sort the complete key set explicitly.
- Review loop 4: found failed writes could reuse a journal sequence and that
  committed journal rows were not proven to reproduce the physical keyspaces.
  Poisoned mutation after a storage failure until recovery, checked contiguous
  journal keys/sequences, replayed from the initial snapshot, and required exact
  physical equality while rejecting unknown keys.
- Review loop 5: found optional terminal evidence was not schema-explicit,
  finality evidence and application state roots were not durable, certificate
  signatures were not authenticated at the runtime boundary, and activation
  intent could be lost across restart. Added explicit markers, persisted and
  reconciled both evidence types, required historical quorum authenticators,
  and recovered the latest journaled drain intent before manifest validation.
- Review loop 6 verdict: `No finding`.
- Verification: the in-memory runtime suite passed 9 tests; the SwayDB
  persistence/fault/restart suite passed 12 tests including every write fault
  point; runtime, historical pipeline-store, and base SwayDB regression suites
  passed 22 tests together. Changed Scala files were individually
  Scalafmt-formatted; import-neutrality and `git diff --check` passed.
- Lesson Learned: durable namespace scans must not infer contiguity from storage
  ordering; a committed journal is trustworthy only when replay exactly
  reproduces every physical row; terminal outcomes need self-validating height
  evidence; and signatures plus the resulting state root must be authenticated
  before a lock or application becomes durable. Phase 4 will authenticate and
  persist the complete verifier decision as one descriptor, and Phase 5 will
  resolve historical certificate authorities and compare full certified
  artifacts in both execution modes.

### Phase 4

- Implementation: added a deterministic registry keyed by historical
  `(verifierSlot, verifierManifestDigest)`, exact request normalization,
  signed-plan byte binding, verifier-result revalidation, deterministic
  wait-insensitive node identity, in-memory and SwayDB exact-v2 stores, and
  application/execution/reference ownership indexes. Two unrelated neutral
  verifiers alone interpret their 32-byte and tagged UTF-8 reference formats.
- Review loop 1: found that constructed records could bypass JSON decoder
  validation and SwayDB values were not checked against their physical keys.
  Unified descriptor validation across creation and reads, checked reference
  and identity digests again, enforced the canonical one-stage/two-transaction
  projection, and explicitly sorted SwayDB results.
- Review loop 2: found that an exact retransmission was reverified against the
  current manifest, which broke historical idempotence after profile rotation.
  Matched persisted profile, signed-plan digest, and exact transaction bytes
  before active admission, while changed requests still fail against the new
  manifest.
- Review loop 3: found a record-first/index-second interruption could lose an
  idempotency alias and let the same key reach a different request. Treated the
  key embedded in every durable record as authoritative and repaired a missing
  physical alias when historical replay finds the exact record.
- Review loop 4: found embedding-specific verifier error reasons could escape
  the frozen taxonomy and exact submissions bypassed the generic accepted-work
  limit. Normalized unknown verifier failures to
  `applicationVerificationFailed` and applied the nonterminal limit only to new
  exact work inside a create gate.
- Review loop 5 verdict: `No finding`.
- Verification: the exact model suite passed 10 tests on both JVM and Scala.js;
  the JVM exact admission suite passed 13 tests including both neutral
  profiles, historical rotation, ownership conflicts, limit handling, and
  SwayDB round-trip; exact plus generic admission/store regression passed 41
  tests. Changed Scala files were individually Scalafmt-formatted;
  import-neutrality and `git diff --check` passed.
- Lesson Learned: historical replay must compare immutable persisted evidence
  before consulting a rotated active registry; constructed values need the same
  checks as decoded values; embedded idempotency ownership is recovery evidence,
  not redundant metadata; and a specialized admission path must retain generic
  resource limits. Phase 5 will execute only store-revalidated descriptors and
  atomically journal exact lifecycle projection with application safety state.

### Phase 5

- Implementation: added a journal-authoritative exact pipeline projection,
  evidence-gated lifecycle transitions, one committed ordered wave with
  all-or-none reducer application, and certified-ancestor producer/consumer
  execution against HotStuff branch context. Exact results, applied-index rows,
  terminal rows, lock/reservation lifecycle changes, and pipeline lifecycle are
  one application-store transaction; SwayDB persists the matching exact row in
  the same prepared/committed recovery protocol.
- Review loop 1: found that exact executions could enter the generic effect-vote
  or application path, application could begin before `effectCertified`, and a
  finalized replay attempted a lifecycle rollback. Rejected generic-boundary
  use for exact-owned execution ids, required effect certification or a fully
  matching terminal replay, required the producer before an ancestor-mode
  consumer, and preserved advanced lifecycle on exact replay.
- Review loop 2: found that historical exact descriptors already present in the
  application journal were rechecked against a rotated active profile, and
  startup reconciliation did not relate exact lifecycle to the complete
  applied index. Preferred immutable journal evidence for an exact replay and
  cross-checked mode-specific coverage, plan digest, deadline, terminal row,
  result, state root, placement, and block evidence.
- Review loop 3: found that a public lifecycle setter could report lock/effect
  certification without evidence. Replaced it with transition methods that
  require reciprocal live locks and authenticated historical quorum effect
  certificates: both certificates for ordered-atomic and the producer
  certificate for certified-ancestor.
- Review loop 4: found that expiry could overwrite `Failed` and an accepted
  exact descriptor without physical locks could remain nonterminal forever.
  Preserved immutable failure state and expired only accepted,
  lock-certified, or effect-certified work once finalized height passes the
  common deadline.
- Review loop 5: found that replaying a second failure could replace the first
  terminal failure. Made terminal failure first-write immutable.
- Review loop 6 verdict: `No finding`.
- Verification: the exact execution suite passed 8 tests covering atomic
  success, producer/consumer failure, commitment substitution, adjacent/later
  descendants, sibling and unavailable ancestry, generic-boundary rejection,
  terminal replay, and expiry; the application safety suite passed 9 tests;
  the SwayDB persistence/fault/restart suite passed 14 tests including every
  fault point and an exact-row interruption. The complete node-jvm test sources
  compiled; changed-file formatting, import-neutrality, and `git diff --check`
  passed.
- Lesson Learned: specialized semantics are safe only when the generic runtime
  cannot bypass their boundary; lifecycle labels must be derived from durable
  evidence rather than exposed as setters; full replay equality includes
  lifecycle, placement, height, result, state root, and terminal failure; and a
  projection is recoverable only when journal and physical namespaces advance
  together. Phase 6 will serve this journal-authoritative projection, add an
  explicit pre-exact journal compatibility read and startup schema refusal, and
  expose stable typed errors/diagnostics without reinterpreting historical
  verifier bytes. Phase 7 will retain the bypass, immutable-failure,
  reconciliation, and fault-injection fixtures in the release gates.

### Phase 6

- Implementation: added the versioned exact-v2 request/snapshot transport on
  the existing pipeline routes while retaining byte-identical unwrapped generic
  v1 JSON, exact wait-boundary behavior, stable typed errors, a read-only
  application status endpoint, and deterministic diagnostics. Exact queries
  cross-check the admission namespace and serve lifecycle/terminal failure from
  the application journal. Added pre-exact journal compatibility reads, full
  verifier activation validation, startup repair/refusal, and an operator
  runbook for retry, finality stall, expiry, drain, rotation, and coherent
  backup/restore.
- Review loop 1: found that new exact requests could pass transport while
  application admission was draining, exact/generic namespace collisions made
  the shared query route ambiguous, and exact idempotency conflicts exposed a
  different public reason from generic v1. Gated new exact persistence on the
  application drain, rejected namespace ambiguity, and normalized the public
  conflict to `idempotencyConflict`.
- Review loop 2: found that the new drain gate also blocked historical
  idempotent replay and that startup validated the supplied manifest without
  directly comparing it to the journal's active manifest. Split immutable
  historical replay from new admission, retained replay during drain, and
  added active configuration-digest refusal.
- Review loop 3 verdict: `No finding`.
- Verification: exact admission, application safety, combined generic/exact
  Armeria transport, and Tapir/OpenAPI suites passed 36 tests. The transport
  suite runs every historical generic HTTP fixture through the combined
  adapter and additionally covers exact submit/query/replay, drain gating,
  idempotency conflict, diagnostics, and namespace collision. Main/test
  compilation, `git diff --check`, explicit compatibility decode tests, and
  the startup manifest/verifier/store reconciliation tests passed. Changed-file
  Scalafmt was attempted; the known repository Scalafmt 3.7.3 router crash
  remains part of the Phase 7 baseline-normalization gate already required by
  this plan.
- Lesson Learned: a drain gate must distinguish immutable replay from new
  persistence; a journal-authoritative projection still needs a physical-store
  equality check; shared routes need explicit namespace ambiguity refusal; and
  startup compatibility means comparing configured, registered, journaled, and
  physically stored identities together. Phase 7 will rerun these transport,
  drain, manifest, pre-exact decode, and collision fixtures, and will normalize
  the formatter baseline before staging artifacts.

### Phase 7

- Implementation: added an explicit four-validator 3-of-4 conflicting-lock
  conformance fixture and exercised both neutral verifier profiles through both
  `orderedAtomic` and `certifiedAncestor`. Set the build version to
  `0.3.0-M1`, normalized the repository-wide Scala baseline with Scalafmt
  3.11.5, added the integration guide, compatibility matrix, release notes,
  artifact checksum manifest, and a standalone Maven-only JVM/Scala.js
  consumer build.
- Verification: core JVM passed 478 tests, core Scala.js 477, node-common JVM
  116, node-common Scala.js 115, and node-jvm 683, for 1,869 tests with no
  failures or errors. The node-jvm gate included HotStuff launch/relocation,
  SwayDB restart/fault recovery, exact admission/execution, transport,
  startup/drain/rotation, and every import-boundary suite.
  `scalafmtCheckAll` and `git diff --check` passed.
- Artifact gate: `publishM2` staged all five immutable coordinates. The
  standalone consumer compiled and ran on JVM, full-linked and ran on
  Scala.js, and had no repository project dependency. All staged POMs pinned
  their Sigilaris dependencies to `0.3.0-M1` and contained no snapshot or
  previous-version reference. Signed staging POMs/JARs matched the Maven Local
  copies byte-for-byte; all twenty staged signatures verified.
- Publication: Sonatype Central deployment
  `cfd9f033-8872-453a-a977-27dcde0464f8` reached `PUBLISHED`.
  Central-only Coursier resolution fetched all five coordinates and their
  transitive dependencies. The ten public POM/main-JAR files matched signed
  staging byte-for-byte and their detached signatures verified with release
  key fingerprint `09710A1F5E321DE8829C3F1902F21A461014CD4B`.
- Review loop 1: found the pinned Scalafmt 3.7.3 router crashed while attempting
  the plan-required repository-wide normalization. Upgraded the formatter to
  3.11.5, normalized all Scala sources, and reran the formatter gate.
- Review loop 2: found that running all modules in one default-heap sbt process
  exhausted the 1 GiB heap only when node-jvm compilation followed all four
  cross-built suites. Preserved the four completed module results and reran the
  complete node-jvm suite in a fresh 4 GiB process; compilation and all 683
  tests passed.
- Review loop 3: found that local staging alone did not prove the public bundle
  retained the reviewed bytes and dependency versions. Compared Maven Local,
  signed staging, and Maven Central POM/JAR bytes; scanned POMs for snapshot or
  old Sigilaris versions; verified staged and public signatures; and resolved
  every public coordinate from Central only.
- Review loop 4 verdict: `No finding`.
- Lesson Learned: repository-wide formatter repair belongs at the final
  release boundary after protocol phases remain reviewable; the storage-heavy
  node-jvm release gate needs an explicit 4 GiB heap and enough time for clean
  SwayDB compaction shutdowns; and a release is not closed by a successful
  upload alone. Future plans should require the same local-stage consumer,
  signed-stage byte comparison, final Central state, Central-only dependency
  resolution, and public byte/signature verification sequence.

### Post-M1 Review Hardening (No M2 Release)

- Implementation: made application-journal lifecycle authoritative for exact
  admission capacity while conservatively counting unjournaled rows, moved the
  create gate to a cancelable cats-effect semaphore, treated a dangling SwayDB
  idempotency alias as a repairable cache miss, and added one recovery journal
  boundary shared by live transport and startup reconciliation. A durably
  admitted row can therefore finish journal registration when drain closes in
  the intervening window, while admission that starts after closure still
  fails closed.
- Protocol defense: public application safety transitions now reject duplicate
  lock inputs, non-live reactivation, applied-execution reacquisition,
  descriptor drift, non-reciprocal reservations, and application after the
  inclusive deadline. Input descriptors reject duplicate stable conflict ids;
  family ids store their trimmed canonical form; exact descriptors validate
  their two-member positions, digest shapes, identity binding, and
  lifecycle/terminal-failure relation at constructed and decoded boundaries.
- Block and transport defense: every `BlockView` now enforces the versioned
  execution-plan commitment before body validation or persistence, and the V2
  decoder fails immediately on an absent root marker. Exact waits use bounded
  exponential polling with a five-minute hard cap. Application diagnostics are
  no longer mounted by the default exact endpoint list and require explicit
  operator-plane mounting by the embedder.
- Documentation: corrected verifier and storage package names, clarified that
  live lock/reservation counts are distinct executions rather than input rows,
  repaired the single-element tuple Scaladoc, and documented the diagnostics
  authentication and wait boundaries. Manifest rotation now explicitly
  requires reconciliation under the current manifest so a store-only row for
  a retiring profile is journaled and drained instead of deleted or orphaned.
- Regression coverage: restored the cross-built topic-neutral producer-session
  suite, added the previously missing unexpected-current-input and certified
  effect quorum/failure variants, and exercised exact certified timeout and
  finalized terminal waits through HTTP.
- Review loop 1: found that a configured exact timeout above five minutes could
  bypass the intended hard cap and that the topic-neutral gossip regression
  file had been left empty. Capped both explicit and absent timeouts, restored
  the JVM/Scala.js suite, and filled the freshness, effect-certificate, and
  exact-wait test gaps identified by the external review.
- Review loop 2: found that an idempotent application replay repaired neither
  stale live lock nor reservation rows in an externally inconsistent public
  safety state, and that profile-removal recovery policy remained implicit.
  Made a matching applied replay re-terminalize its execution and documented
  current-manifest reconciliation as a prerequisite to rotation.
- Verification: core JVM passed 482 tests, core Scala.js 481, node-common JVM
  120, node-common Scala.js 119, and node-jvm 688, for 1,890 tests with no
  failures or errors. The node-jvm gate included exact admission capacity and
  drain recovery, dangling-alias repair, SwayDB restart/fault recovery,
  HotStuff launch/relocation, and exact HTTP wait behavior.
  `scalafmtCheckAll` and `git diff --check` passed.
- Review loop 3 verdict: `No finding`.
- Lesson Learned: capacity limits must be driven by the state machine that owns
  terminal transitions, not by an immutable admission projection; a drain
  check and a durable write need an explicit recovery handoff; separately
  persisted secondary indexes must be treated as repairable caches when the
  primary record is absent; and optional diagnostics must be opt-in at the
  server assembly boundary, not merely described as internal.
- Deferred follow-up: bounded/checkpointed application-journal retention and a
  single-namespace exact record/idempotency transaction require a storage
  format and migration plan. Event-driven exact wait notification can replace
  bounded polling in that same follow-up. Before block-header V2 activation,
  proposal validation must also wire `BlockProtocolActivation.validate` and
  `BlockHeader.validateExecutionPlan` at the configured cutover boundary.
  These format, notification, and activation changes are not included in this
  hardening change, and no M2 version bump, tag, staging, publication, or
  release action is part of this section.

#### Follow-up Review Hardening (No Release)

- Admission and storage: replaced repeated exact-record decoding during the
  nonterminal capacity check with one bounded primary-key scan that counts only
  rows absent from the authoritative application journal. The scan stops once
  the remaining limit is reached and remains usable when an unrelated stored
  record is malformed. Aligned in-memory and SwayDB dangling-idempotency
  behavior while preserving immutable same-submission aliases and rejecting an
  attempted alias redirect.
- Drain and recovery: made accepted exact work a live drain owner and included
  its deadline in watermark reconciliation. New accepted work raises the
  greatest-admitted deadline; terminal replay does not. Drain advancement now
  performs phase validation, expiry, and readiness calculation in one store
  transaction, and expires eligible pre-inclusion exact lifecycles only after
  the common deadline. Both live registration and durable recovery revalidate
  the active verifier profile, with distinct audit operation names and
  lifecycle-specific transition labels.
- Protocol and API defense: retained terminal execution identities after their
  input rows are overwritten so an expired execution cannot be reacquired or
  reserved under a different input. Made manifest digest construction
  failure-aware for invalid programmatic verifier digests, validated normalized
  exact submissions at their factory boundary, and required split block-header
  persistence to validate the versioned execution-plan commitment before a
  write. Added explicit unsafe manifest construction only for trusted fixtures.
- Compatibility documentation: stamped the published immutable M1 boundary as
  tag `v0.3.0-M1` at commit `1c8c2b7`, documented the unreleased post-M1 source
  API migrations and the multi-key immutable-alias contract, and prohibited
  publishing the changed source bytes under the existing M1 version.
- Review loop 1: found that checking the drain phase before a separate expiry
  write still left a time-of-check/time-of-use window and could mutate state
  after a concurrent phase change. Folded the complete drain transition into a
  single application-store transaction and added a regression proving that an
  Open-phase call returns an error without expiring work.
- Review loop 2: found that treating every exact replay as new admission could
  raise the watermark from an already-terminal historical record. Derived the
  effective lifecycle from the journal first and limited watermark advancement
  to live exact work while still revalidating its profile.
- Review loop 3: found that the plan text overstated expiry as covering every
  nonterminal exact lifecycle and that the five-minute wait cap still lacked a
  direct, fast boundary test. Corrected the text to pre-inclusion expiry,
  extracted the pure effective-timeout selector, and tested absent,
  over-limit, and below-limit configurations without a five-minute sleep.
- Review loop 4 verdict: `No finding`.
- Verification: core JVM and Scala.js each passed 13 focused application-safety
  tests; node-common JVM and Scala.js each passed 14 focused exact-model tests;
  and the focused node-jvm safety, exact admission, SwayDB, block model, and
  block-store suites passed 54 tests. The complete source gate passed core JVM
  483, core Scala.js 482, node-common JVM 122, node-common Scala.js 121, and
  node-jvm 694 tests, for 1,902 tests without failures or errors. After the
  final review extracted the exact-wait cap selector, its 12-test Armeria suite
  passed with explicit absent, over-limit, and below-limit timeout cases.
  `scalafmtCheckAll` passed; `git diff --check` and explicit
  version/tag/publication checks are run immediately before commit.
- Lesson Learned: scale-sensitive admission invariants need a purpose-built,
  bounded store primitive rather than repeated object pagination; accepted
  durable work owns drain liveness before it acquires locks; recovery must
  reapply current policy even when reconstructing immutable state; a public
  failure-prone constructor should return the failure while unsafe helpers stay
  visibly fixture-only; and a split persistence API must reject an invalid half
  at its own write boundary. Release documentation must identify immutable
  artifact bytes separately from later source compatibility.
- Deferred follow-up: the previously recorded journal-retention,
  single-namespace transaction, event-driven wait, and V2 activation work
  remains deferred. This follow-up does not change the M1 version, create an M2
  tag, stage artifacts, publish artifacts, or perform any release action.

#### Third Review Compatibility Hardening (No Release)

- Rotation and historical replay: classified finalized, materialized, expired,
  and failed exact lifecycles through one terminal predicate. New work and live
  historical work still require the active verifier profile, while an already
  journaled terminal row retains the policy identity under which it completed
  and can survive restart, reconciliation, and replay after profile retirement.
  Idempotent store transactions now suppress unchanged deltas, sequence bumps,
  and audit entries so repeated startup reconciliation does not grow the
  journal.
- Legacy recovery: added a one-time `repairLegacyDrainState` migration for
  coherent pre-hardening snapshots whose accepted exact work or lock audit rows
  predate the persisted drain watermark. Recovery first requires the physical
  snapshot to match committed journal replay exactly, then derives the greatest
  live deadline, demotes an inconsistent ready phase to waiting finality, and
  records the repair as an ordinary prepared/committed journal transaction.
  A second restart is a no-op rather than another migration entry.
- Scale and storage: replaced per-submission full exact-record decoding with a
  startup-built immutable-primary index for request identity, application
  pipeline ownership, execution ownership, reference commitments, and embedded
  idempotency. In-memory and SwayDB paths now share the same replay and alias
  validation, fail closed on a hash mismatch or conflicting primary rows, and
  rebuild all derived indexes from durable records when a store session opens.
- Protocol and compatibility: core application safety now reports an exact
  terminal-execution failure instead of falling through to a generic lock
  conflict. The operations runbook and M1 integration guide document strict
  canonical verifier-manifest digests, terminal-row behavior across profile
  rotation, legacy watermark repair, and the new request-identity lookup that
  custom exact stores must implement. No storage format, artifact coordinate,
  release version, tag, staging, or publication action changed.
- Review loop 1: found that the startup-derived request index had behavioral
  coverage through normal admission but no direct rebuild regression. Added a
  store-session reconstruction test proving request replay works from immutable
  primary rows after all derived state starts empty.
- Review loop 2: a physical close/reopen variant exposed SwayDB's process-local
  overlapping-file-lock timing, which did not exercise the intended invariant
  deterministically. Replaced it with a deterministic backing-store lifecycle
  test of the same startup rebuild boundary and reran the admission and SwayDB
  exact-store suites. A second review of journal repair ordering, policy
  rotation, index publication, and concurrent admission found no defect.
- Review loop 3 verdict: `No finding`.
- Verification: core JVM passed 483 tests, core Scala.js 482, node-common JVM
  122, node-common Scala.js 121, and node-jvm 699, for 1,907 tests without
  failures or errors. Focused core and node-common safety/model suites passed
  13 and 14 tests per platform respectively; the focused node-jvm application
  safety, admission, exact-store, and runtime suites passed 48 tests, followed
  by a 24-test admission/exact-store rerun after the final index-rebuild
  regression was added. `scalafmtCheckAll`, `git diff --check`, and explicit
  version/tag/publication checks are run immediately before commit.
- Lesson Learned: current admission policy and immutable terminal audit history
  are different invariants and must not share an unconditional validation path;
  a strengthened derived invariant needs a journaled one-time migration only
  after old physical and journal state are proven coherent; an idempotent state
  machine operation should not manufacture audit history; and scale-sensitive
  lookups should rebuild disposable indexes once from immutable primary rows
  rather than decode the complete store per request. Lifecycle regressions are
  most reliable when they test the store-session reconstruction boundary
  directly instead of depending on storage-engine shutdown timing.
- Deferred follow-up: canonical failure-aware partial helpers remain a broader
  public API migration because their internal call sites are already guarded.
  The previously recorded journal-retention, single-namespace transaction,
  event-driven wait, and V2 activation work also remains deferred. This third
  review hardening does not release M2.

#### Fourth Review Operational Hardening (No Release)

- Bounded exact indexes: the SwayDB exact-pipeline indexes now retain only
  pipeline ids and compact request, application, execution, reference, and
  idempotency ownership material. Full transaction payloads remain solely in
  the durable primary store. Same-submission replay reads that primary record
  under the store gate, and paginated listing applies offset and limit to the
  primary-key iterator before decoding records.
- Publication and recovery hygiene: request-identity lookups share the same
  semaphore as exact primary/index publication, and create-or-replay keeps the
  primary write plus derived-index publication uncancelable. An ambiguous
  primary write invalidates the process-local indexes and makes later indexed
  operations fail closed until a restart rebuilds them. Startup rebuild errors
  identify the exact durable primary key and the uniqueness conflict, while a
  primary record absent from the rebuilt pipeline-id set cannot be accepted as
  a replay.
- Legacy-repair observability: an actual legacy drain-state repair emits a
  structured event containing the journal sequence and old/new phase and
  watermark. Production sessions log that event at warning level, tests can
  inject an observer, observer failure cannot block a safety-directed repair,
  and an idempotent second restart emits no event.
- Review loop 1: found that a request-identity read could observe the interval
  after the primary write but before the compact index publication. Serialized
  the read with the publication gate so the two-step durable/cache handoff has
  one visibility boundary.
- Review loop 2: found that loading an existing primary record directly could
  treat it as a valid replay even if the derived index did not contain its
  pipeline id. Added an explicit index-membership invariant so damaged or
  invalidated derived state remains fail closed.
- Review loop 3 verdict: `No finding`.
- Verification: the focused exact-store and admission suites passed 27 tests,
  including no-list admission, keyed startup-conflict diagnostics, physically
  bounded list decoding, and ambiguous-write invalidation/rebuild. The focused
  SwayDB application-safety suite passed 15 tests, including exactly-once
  legacy-repair observation. The complete source gate passed core JVM 483,
  core Scala.js 482, node-common JVM 122, node-common Scala.js 121, and
  node-jvm 702 tests, for 1,910 tests without failures or errors.
  `scalafmtCheckAll`, `git diff --check`, and explicit version/tag/publication
  checks are run immediately before commit.
- Lesson Learned: a derived uniqueness index should retain only comparison and
  ownership material while the immutable primary remains the record authority;
  an ambiguous durable write must poison the derived cache instead of allowing
  a process to continue with potentially stale ownership state; every read
  participating in a multi-step publication protocol must share its gate; safe
  automatic migrations still need explicit, non-blocking observability; and a
  fail-closed startup diagnostic must name the durable key operators need to
  restore from backup.
- Deferred follow-up: a direct in-memory corruption fixture would require
  exposing or weakening its private immutable state, while the shared hash
  validation already has a direct SwayDB regression. Canonical failure-aware
  partial helpers, bounded/checkpointed journal retention, a single-namespace
  exact transaction, event-driven wait notification, and V2 activation remain
  deferred. This fourth review hardening does not change the M1 version, create
  an M2 tag, stage or publish artifacts, or perform any release action.

#### Fifth Review Closure

- The fifth external review accepted the completed implementation with no
  CRITICAL or WARNING findings. It confirmed all fourth-review fixes and reran
  the exact-store/admission suites (27 tests) and application-safety suite
  (15 tests) successfully. The previously recorded complete source gate remains
  1,910 passing tests; this closure does not claim a new complete gate run.
- The remaining defensive error-handling and operational suggestions are tracked
  in [0032: Application Safety And Exact Pipeline Operational Hardening](0032-application-safety-and-exact-pipeline-operational-hardening-plan.md).
  That plan is `Proposed`, and its implementation is not an M2 publication gate.
  Observer invocation isolation, typed alias-write failures, reconstruction
  synchronization, and diagnostic regressions remain future work.
- M2 release preparation separately owns the two documentation clarifications:
  repair warnings are best effort while the journal is authoritative, and
  exact-store listing order is backend-specific. The completed implementation
  and review history above remain the record of plan 0031; M2 release evidence
  is recorded separately when its gates actually complete.
- Lesson Learned: close an accepted implementation plan with its actual evidence
  and link remaining nonblocking work to a separately scoped plan. A reviewed
  release boundary must not imply that future hardening is already implemented.
