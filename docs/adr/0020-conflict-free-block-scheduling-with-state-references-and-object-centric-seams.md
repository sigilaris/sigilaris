# ADR-0020: Conflict-Free Block Scheduling With State References And Object-Centric Seams

## Status
Accepted

## Context
- ADR-0009 already introduced `StoreState` and `AccessLog` as the execution-time carrier for precise key-level read/write tracking, and the current implementation records concrete accesses at `StateTable` boundary.
- As of `2026-04-03`, the current Sigilaris baseline execution path still reduces transactions sequentially from a single base state, and the recorded `AccessLog` is not yet elevated into a canonical block-admission or proposal-validation rule.
- The current local batch path therefore permits same-batch causality structurally: a later transaction in the submitted list may observe state mutations produced by an earlier transaction in the same sequential reduction pass.
- The compile-time `Reads` / `Writes` declarations used by reducers describe table/schema capability, not concrete state-cell or object identity. They are therefore too coarse to decide whether two transactions can safely coexist in the same block.
- Long-term execution goals now include:
  - making future parallel execution straightforward,
  - preventing same-block transaction dependencies,
  - ensuring that transactions touching the same mutable state do not coexist in one block.
- Current and likely future applications may contain flows that discover touched state dynamically during execution, for example prefix scans, automatic input selection, or open-ended balance search. Those flows make pre-execution conflict checks difficult because the exact touched-key set is not known from transaction bytes alone.
- A full Sui-style object runtime could make same-block conflict detection easier, but rewriting the entire Sigilaris storage and execution stack around first-class objects immediately would be a large migration. The near-term need is a scheduling and validation contract that works with the current trie/KV execution core while creating a clean seam for more object-centric applications.
- As of `2026-04-04`, the local rollout has landed per-tx footprint witnesses, batch-level schedulable vs compatibility planning, and deterministic footprint derivation for the currently shipped account/group transaction surface.
- As of `2026-04-04`, the HotStuff baseline keeps proposals header-first and tx-hash-set-carrying, but still does not require full block-body carriage in the proposal artifact. The canonical `BlockBody` / `BlockView` contract from ADR-0019 is now paired with proposer-side conflict-free body selection and body-visible validator helpers that re-check schedulability from block-body transactions without widening the header contract.

## Decision
1. **Sigilaris introduces `StateRef` and `ConflictFootprint` as the canonical scheduling contract for transaction conflict analysis.**
   - `StateRef` is the canonical identity of one concrete schedulable state item.
   - `ConflictFootprint` is the pair `(reads, writes)` of `StateRef` sets for a transaction.
   - The baseline scheduler does not reason about tables, modules, or prefixes alone; it reasons about concrete `StateRef` identity.

2. **The baseline `StateRef` representation is a canonical byte identity that every validator can deterministically recompute.**
   - The baseline contract is equivalent to an opaque canonical byte string such as `ByteVector`.
   - For table-key applications, the baseline `StateRef` bytes are the exact storage-item identity `tablePrefix ++ encodedKey`.
   - Hashing `tablePrefix ++ encodedKey` is not the baseline because the current `AccessLog` already records the unhashed concrete key bytes and validators do not need a second normalization step.
   - For object-centric applications, `StateRef` may be derived from canonical object-reference bytes such as `ByteEncoder[ObjectRef].encode(objectRef)` or an equivalent application-owned canonical encoding.
   - If multiple `StateRef` namespaces coexist, the application must keep them disjoint by construction, for example with domain tags or equivalent namespace separation.
   - Consensus and scheduler logic still treat the resulting bytes as opaque canonical identities. The exact higher-level interpretation belongs to the application package.

3. **Conflict-free block validity requires no same-block `W∩W` or `R∩W` overlap between transactions.**
   - For any two distinct transactions `txA` and `txB` in the same block:
     - `txA.writes ∩ txB.writes = ∅`
     - `txA.reads ∩ txB.writes = ∅`
     - `txA.writes ∩ txB.reads = ∅`
   - `R∩R` overlap is allowed by the baseline because it does not create execution dependency by itself.
   - In practical terms, the same mutable state item may appear in at most one transaction's write footprint within a block, and no transaction may read a state item another transaction in the same block writes.

4. **Only transactions with explicit pre-execution conflict footprints are eligible for conflict-free block scheduling.**
   - The baseline acquisition path is an application-owned deterministic footprint derivation function over signed transaction bytes plus the explicit state/object references already named in the transaction.
   - The initial core/shared implementation owns the generic scheduling primitives under `org.sigilaris.core.application.scheduling`, while application modules plug in transaction-family-specific derivation at that seam.
   - Sigilaris does not use proposer-local dry-run as the primary baseline mechanism for obtaining schedulable footprints.
   - Duplicating the full `ConflictFootprint` as a separate mandatory on-wire transaction field is also not the baseline; that remains an optional future or application-specific extension if the wire and signing trade-offs are worth it.
   - A transaction's `ConflictFootprint` may be exact or a conservative deterministic superset, but in either case it must be derivable before block construction.
   - If deterministic derivation fails, the transaction is not eligible for the schedulable conflict-free path. An implementation may either reject it at admission or route it to an explicit non-schedulable compatibility path, but it must not silently treat it as schedulable.
   - The initial developer-visible classification surface is `Schedulable(footprint)` vs `Compatibility(reason)`.
   - If a batch mixes schedulable and compatibility transactions, the initial rollout routes the whole batch to compatibility mode instead of partially selecting only the schedulable subset.
   - This mixed-batch fallback applies to the current local application batch path. Proposer-side block construction may still filter a larger candidate pool down to the schedulable, conflict-free subset that enters a conflict-free block body.
   - Transactions that require open-ended prefix scans, automatic coin/input selection, implicit balance search, or other dynamic state discovery are not schedulable under this baseline until they are normalized into explicit state references.
   - "Normalize" here means that the transaction must name the concrete state/object references it may read or write before the proposer attempts same-block conflict analysis.
   - As of `2026-04-04`, the currently shipped account/group transaction surface has no remaining compatibility-only family in the deployed baseline. Future dynamic-discovery families must still ship on `Compatibility(reason)` until they expose explicit refs.

5. **`AccessLog` remains mandatory, but as an execution witness and conformance check rather than the primary admission contract.**
   - The scheduler may use declared or deterministically derived `ConflictFootprint` before execution.
   - Execution must still record actual accesses through `AccessLog`.
   - The execution layer must verify that actual recorded accesses satisfy `actualReads ⊆ declaredReads` and `actualWrites ⊆ declaredWrites` under the selected application policy.
   - An application may choose to require exact equality as a stricter local rule, but subset conformance is the scheduling baseline.
   - If execution escapes the declared footprint, the transaction is invalid for this scheduling model.

6. **Sigilaris explicitly prefers object-centric application design for schedulable mutable state, but does not require an immediate full object-runtime rewrite.**
   - Applications are encouraged to model schedulable state as explicit owned/shared/immutable objects or equivalent categories.
   - Owned mutable objects should appear as explicit input references to the transaction that consumes or updates them.
   - Shared mutable objects should appear explicitly in transaction write footprints, making same-block exclusion straightforward.
   - Immutable objects may appear in many read footprints without conflict.
   - The underlying storage can remain trie/KV; object-centrism is an application-facing modeling seam, not an immediate replacement for the storage engine.

7. **Block building must enforce conflict-free transaction selection before proposal emission.**
   - The proposer or block builder must compute or load each candidate transaction's `ConflictFootprint`.
   - It must select a block body whose transactions satisfy the conflict-free rule from Decision 3.
   - A practical baseline implementation may maintain aggregate `readsSeen` and `writesSeen` sets while scanning candidate transactions, rejecting a candidate as soon as its footprint intersects the current aggregates in a forbidden way.
   - The conflict predicate is ordering-independent: reordering the scan of an already selected transaction set must not change whether that set is valid under the baseline rule.
   - The body ordering may remain canonical application order after selection, but validity must not depend on "earlier transaction mutates state for later transaction" semantics inside the same block.
   - Future parallel executors may exploit this property, but the rule itself is a validity and scheduling contract, not merely an optimization hint.

8. **Block validation must re-check internal conflict freedom and footprint conformance.**
   - A validator must not trust the proposer's claimed non-conflict set without recomputation or deterministic verification.
   - Proposal/block acceptance must reject a block whose transaction footprints overlap under the baseline conflict rule.
   - Pairwise `O(N^2)` comparison is not required. A validator may verify the block body in one pass by maintaining aggregate read/write sets and checking each transaction against the current aggregates before unioning its footprint into them.
   - Execution validation must also reject a transaction whose actual `AccessLog` exceeds its declared/derived footprint.
   - In the current HotStuff baseline, proposal artifacts remain header-first and do not carry full block bodies. Body-aware validation therefore attaches at call sites that also hold canonical `BlockView` access, using helpers equivalent to proposer-side body selection plus `HotStuffProposalViewValidator`.

9. **Current compile-time `Reads` / `Writes` remain in place as schema capability declarations and are not replaced by `ConflictFootprint`.**
   - `Reads` / `Writes` still describe which tables a reducer may legally access.
   - `ConflictFootprint` is an additional, finer-grained runtime scheduling contract for concrete state references.
   - Table-level capability checks and per-key/per-object conflict checks solve different problems and should both exist.

10. **ADR-0019 canonical block contract remains application-neutral and does not gain mandatory scheduler metadata in the header baseline.**
   - The canonical `BlockHeader` / `BlockBody` / `BlockView` layering remains unchanged.
   - Conflict-free scheduling is an execution and validation rule over the ordered block body.
   - The baseline intentionally keeps footprint metadata out of the header because it is body-derived and application-owned, and duplicating it in the header would enlarge and churn the consensus header contract without changing the canonical block-linkage semantics ADR-0019 is trying to keep minimal.
   - Applications may later expose footprint summaries or touched-object metadata in their own `BlockRecord` projection, but the baseline block header does not require it.

11. **Legacy sequential transactions that depend on dynamic state discovery are outside the conflict-free baseline.**
   - Applications may temporarily keep such flows in compatibility mode during migration.
   - However, such transactions must not be treated as fully schedulable parallel-safe transactions until they are rewritten to explicit `StateRef` / object-reference form.
   - Applications that currently rely on same-block causality must migrate by collapsing dependent steps into one transaction, separating them across blocks, or keeping them on a non-schedulable compatibility path until refactored.
   - For the long-term Sigilaris baseline, schedulable application transactions should converge toward explicit state/object references.

## Consequences
- Sigilaris gets a precise, application-neutral rule for "these transactions may coexist in one block" without waiting for a full storage-engine rewrite.
- Future parallel execution becomes much simpler because same-block independence is part of validity rather than an executor-local guess.
- Existing `AccessLog` machinery remains valuable and becomes a post-execution correctness witness instead of dead instrumentation.
- Object-centric applications become easier to validate and schedule because conflict checks reduce to explicit object-reference set intersection.
- Applications that currently rely on hidden scans, implicit input discovery, or automatic balance search will need API and reducer refactors to participate in this model.
- Conservative superset footprints are allowed, but they can introduce false conflicts and therefore reduce achievable block throughput compared with exact footprints.
- Proposer and validator implementations become stricter because they must both compute and verify conflict footprints, not merely execute a sequential body.
- Allowing `R∩R` while forbidding `R∩W` / `W∩W` preserves useful concurrency without overserializing immutable or read-only access patterns.
- Because block validity no longer relies on intra-block state-dependency chains, application authors lose some flexibility to express "transaction B consumes the state created by transaction A in the same block" as a baseline behavior.
- Applications that currently depend on sequential same-block causality will need a migration path, typically by combining dependent operations into one transaction or by accepting cross-block staging.
- Keeping scheduler metadata out of the block header means validators must parse the block body and deterministically derive footprints from its transactions, which is an intentional cost of preserving an application-neutral header contract.

## Rejected Alternatives
1. **Keep the current sequential block semantics and treat parallel execution as an executor-local optimization**
   - This leaves same-block dependency structure implicit.
   - Different executors would need to rediscover safe parallelism ad hoc, and block validity would not directly guarantee independence.

2. **Use only compile-time `Reads` / `Writes` for block conflict analysis**
   - Those declarations operate at table/schema granularity.
   - They are too coarse to allow useful concurrency because unrelated keys in the same table would be treated as conflicting.

3. **Use only post-execution `AccessLog` and infer conflicts after the block is already built**
   - `AccessLog` is only known after execution.
   - It is suitable as a witness and validation artifact, but not as the sole admission contract for proposal construction.

4. **Immediately replace Sigilaris with a full Sui-style object runtime**
   - That would make some scheduling cases easier, but it forces a much larger migration than the current goal requires.
   - A thinner `StateRef` / `ConflictFootprint` layer preserves the existing trie/KV core while opening a path toward more object-centric applications.

5. **Forbid any same-block overlap at all, including `R∩R`**
   - This is stricter than necessary for dependency freedom.
   - It would reduce throughput by serializing harmless shared reads of immutable or stable state.

## Follow-Up
- Define how application packages expose explicit state/object references in transaction bodies.
- Migrate application flows that still rely on dynamic state discovery so they can participate in conflict-free scheduling.
- Keep future dynamic-discovery families on `Compatibility(reason)` until they name explicit refs or move to a more object-centric transaction shape.
- If the header-only HotStuff artifact baseline later needs first-class body fetch/availability wiring for validator vote emission, document that transport/runtime seam in a follow-up ADR or plan without widening the ADR-0019 header contract.
- Concrete rollout history and migration gates are tracked in `docs/plans/0006-conflict-free-block-scheduling-plan.md`.
- If a first-class object runtime becomes necessary later, write a follow-up ADR that supersedes or extends the `StateRef` representation while preserving the same high-level conflict rules.

## References
- [ADR-0009: Blockchain Application Architecture](0009-blockchain-application-architecture.md)
- [ADR-0017: HotStuff Consensus Without Threshold Signatures](0017-hotstuff-consensus-without-threshold-signatures.md)
- [ADR-0019: Canonical Block Header And Application-Neutral Block View](0019-canonical-block-header-and-application-neutral-block-view.md)
- [blockchain-application-post-adr-improvements.md](../dev/blockchain-application-post-adr-improvements.md)
- [0006 - Conflict-Free Block Scheduling Plan](../plans/0006-conflict-free-block-scheduling-plan.md)
