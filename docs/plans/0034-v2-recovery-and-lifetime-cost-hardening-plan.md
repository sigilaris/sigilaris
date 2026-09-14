# 0034 - V2 Recovery And Lifetime Cost Hardening Plan

## Status

Proposed — design work following the [independent full review](../conformance/full-review-2026-09-12.md). These items remain open; the [selected corrections](../conformance/full-review-corrections-2026-09-12.md) do not claim to implement repair, compaction or durable leader proposal uniqueness.

## Created / Last Updated

2026-09-12

## Background and goal

Plan 0033 implements the V2 safety and activation contracts. The subsequent review identifies availability and lifetime-cost limits that need a storage/provenance design, not removal of authentication from the signing path. This plan supplements [0032](0032-application-safety-and-exact-pipeline-operational-hardening-plan.md), which explicitly excludes journal checkpoints and V2 activation. Relevant contracts are [ADR-0037](../adr/0037-versioned-application-execution-upgrade-and-empty-blocks.md), [journal storage](../conformance/v2-application-storage.md) and [key-owning controllers](../conformance/v2-fence-controller.md).

## Scope and decisions before implementation

- **M3 / L5, verified repair and interrupted creation.** Define an offline, exclusive-owner repair capability for torn journal tails, pending controller snapshots, unreferenced blobs and interrupted initial layout. Bind the original controller, journal, validator, selected HEAD, immutable source inventory and whole consistency group. Preserve original bytes in a forced quarantine plus a repair receipt before any replacement. A HEAD alone cannot distinguish an uncommitted suffix from lost/rolled-back previously committed evidence. Never infer that a malformed tail proves no externally visible signature existed. Reconcile signed intent/audit watermarks and original archives; refuse contradictions, missing committed bytes or unavailable independent evidence. Choose whether repair requires a new physical format and authenticated migration. No automatic truncation is authorized by this plan.
- **M4, lifetime cost and checkpoints.** Measure increasing counts of intents, certificates, journal records and historical proposals independently of witness size. Define an authenticated monotonic checkpoint with retained original-proof provenance before using incremental replay or pruning. Cache keys must bind every immutable input, physical owner and history selection. Replacing full verification with a last-sequence or wall-clock cache must not hide source loss, reordering, a changed complete inventory or a rollback. Decide retained evidence, checkpoint format, migration and audit retention before implementation.
- **L1, durable leader proposal uniqueness.** Choose a controller-owned intent keyed by domain, proposer, height and view. Persist the exact unsigned proposal before key use; retries and restart must reproduce the original bytes, including timestamp. Preserve original historical proposal verification; changing historical signature/hash domains is outside scope. Define cancellation, missing preimage and conflicting retry behavior.
- **L3, local journal ownership.** Bind a physical journal to the configured local validator without confusing imported remote certificates with local vote intents. Define compatibility and migration for existing journal directories and consistency groups. Test same-domain foreign-validator substitution separately from ordinary remote evidence.
- **L10, callback reentrancy.** Decide a structural API boundary that prevents callbacks invoked under the safety semaphore from recursively reading the same store. Preserve the current lock ordering and actual finalizer/safety permissions; do not make the gate reentrant or release it around authentication without a new proof. Provide bounded cancellation/reentrancy diagnostics and tests.

## Operational questions for Phase 1

The [input review follow-up](../conformance/public-input-review-corrections-2026-09-12.md) adds two operational questions for Phase 1. The current maintenance provider remembers reached identities and stalls new IDs after its configured capacity (4096 by default) for the lifetime of that provider instance. Evaluate bounded retention or durable retirement evidence without allowing forgotten attempts or request-ID rebinding. Also check deployment fit for the never-enabled lifetime rule: a domain with an interval containing no authorized signer cannot use that route. Do not fabricate signer intervals or relax gap checks; any alternative evidence model requires a separate explicit design. These notes do not change current acceptance behavior.

## Non-goals

No public release, deployment, destructive repair, key replacement, reverse handover, historical signature normalization or weakening of vote-before-durable-intent semantics. This plan does not change the meaning of never-enabled evidence or turn local performance budgets into protocol validity limits.

## Phases

1. **Freeze decisions and measurements.** Inventory actual durable write/force/key-use boundaries, retained proof dependencies and callback lock ordering. Benchmark many small operations as well as large witnesses. Record recovery inputs that cannot be inferred from local HEAD.
2. **Verified offline recovery.** Implement the agreed receipt/quarantine and owner-bound repair procedure. Inject faults before and after every force/move, repeat repair after restart, and prove committed evidence and completed key use cannot be discarded.
3. **Authenticated checkpoints and bounded replay.** Implement the agreed checkpoint/index design; compare decisions and signatures with complete original replay. Corrupt every cache/checkpoint dependency and require fail-closed behavior. Demonstrate measured work bounds over growing history.
4. **Ownership, durable proposal intents and callback boundaries.** Add migration and exact-retry tests, same-domain foreign-store rejection and reentrancy protection. Exercise restart across actual controller and gossip paths.
5. **Public conformance and documentation.** Export executable repair/ownership/proposal/checkpoint cases, publish only to an explicit candidate repository for testing, and record new immutable manifests and reachable commits.

## Acceptance criteria and verification

- [ ] Repair inputs and authority are explicit; unrecoverable ambiguity remains fenced.
- [ ] Every destructive replacement has a durable original-byte quarantine and authenticated receipt, and is idempotent across crash/restart.
- [ ] A signed or potentially signed promise cannot be erased by truncating a tail or selecting an older snapshot.
- [ ] Checkpoints preserve original validation results and detect mutation/loss of every required dependency.
- [ ] Measurements cover operation count, history bytes, witness bytes and certificate count separately, with reproducible commands and capacity outcomes.
- [ ] A restarted leader reproduces one proposal per window, while conflicting requests are rejected before key use.
- [ ] Foreign local journals and callback self-reentrancy are rejected without weakening current safety gates.
- [ ] Source and standalone artifact tests pass; known limitations and migrations are documented before claiming completion.

## Risks and mitigations

The main risk is treating a faster or more available local state as authenticated history. Keep the existing complete replay as a comparison oracle, preserve all original bytes, and retain fail-closed behavior until the new capability proves the exact replacement. Storage-format and callback changes require explicit migration tests and an ADR update before implementation.
