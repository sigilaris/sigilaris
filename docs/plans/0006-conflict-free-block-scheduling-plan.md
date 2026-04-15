# 0006 - Conflict-Free Block Scheduling Plan

## Status
Phase 7 Complete

## Created
2026-04-03

## Last Updated
2026-04-04

## Background
- This document is the implementation plan for ADR-0020.
- ADR-0020 fixes the long-term scheduling rule: same-block transactions must not have `W∩W` or `R∩W` overlap on concrete `StateRef` identity, and object-centric application design is the preferred seam for schedulable mutable state.
- The current codebase already has the low-level ingredients for precise access tracking:
  - `StoreState` carries `MerkleTrieState + AccessLog`.
  - `StateTable` records concrete key-level reads and writes into `AccessLog`.
  - `AccessLog.conflictsWith()` already implements `W∩W` and `R∩W` detection.
- However, the current execution and admission path still has important gaps:
  - compile-time `Reads` / `Writes` are table-level capability declarations, not concrete pre-execution footprints,
  - `AccessLog` is only known after execution,
  - the current local reducer path still executes transactions sequentially and carries `StoreState` forward across the batch, so access logs accumulate unless the execution seam is changed,
  - the current local batch admission/commit path still accepts same-batch causality structurally because later transactions run on top of earlier transactions' writes in sequence.
- In the current local execution path, one accepted batch is reduced and committed as one block-shaped unit. The initial rollout in this plan therefore applies the future block-level conflict rule at the batch boundary as a local stand-in for future conflict-free block bodies.
- The shipped HotStuff baseline in `sigilaris-node-jvm` now consumes the ADR-0019 canonical `BlockHeader` / `BlockBody` / `BlockView` contract. Full consensus-level enforcement of ADR-0020 therefore no longer waits on block-model readiness itself; it depends on integrating deterministic footprint derivation and validation against that landed body/query surface.
- As of `2026-04-04`, Phase 7 is now landed:
  - the current local application batch runtime owns schedulable-vs-compatibility execution, duplicate/idempotency handling, and runtime-visible diagnostics through one concrete batch boundary,
  - the current HotStuff runtime owns one concrete body-visible seam via runtime-side `BlockStore` / `BlockQuery` hydration keyed by `proposal.targetBlockId`, so proposer selection, proposal acceptance, and vote gating can consume `ConflictFreeBlockBodySelector` / `HotStuffProposalViewValidator` without widening the ADR-0019 header artifact.
- The current application layer contains a mix of transaction families:
  - some can be made schedulable from explicit references already present in the transaction,
  - some still rely on dynamic discovery such as prefix scan, automatic input selection, or open-ended balance search and therefore cannot participate in the schedulable path yet.

## Goal
- Introduce core runtime abstractions for `StateRef`, `ConflictFootprint`, deterministic footprint derivation, and post-execution conformance checking.
- Change execution so actual per-transaction footprints are observable and can be checked against declared or derived footprints.
- Introduce a conflict-free schedulable batch path in the current local application execution flow as the first landing zone for ADR-0020.
- Provide an explicit compatibility path for non-schedulable transactions during migration.
- Prepare proposer-side selection and validator-side block-body verification for HotStuff on top of the landed ADR-0019 / Plan 0005 body contract.
- Close the remaining end-to-end runtime wiring gaps in the local application batch path and the HotStuff proposer/validator path without widening the ADR-0019 header contract unless a minimal body-availability seam proves unavoidable.

## Scope
- Core/shared scheduling abstractions and verification helpers.
- Per-transaction actual footprint capture in the current execution path.
- Application-owned admission and batch-planning logic for schedulable vs compatibility transactions.
- Initial deterministic `FootprintDeriver` rollout for pilot transaction families.
- HotStuff integration hooks and follow-through on top of the landed canonical block body support.
- Concrete runtime injection of scheduling helpers into the local batch admission/commit path and the body-visible HotStuff proposal acceptance/vote-gating path.
- Tests, docs, and migration notes related to the new scheduling contract.

## Non-Goals
- Rewriting Sigilaris into a full Sui-style object runtime in this plan.
- Migrating every existing application transaction family to schedulable status in the first rollout.
- Eliminating the compatibility path immediately.
- Designing final distributed mempool policy, fee market policy, or proposer fairness strategy.
- Solving pacemaker, timeout, or leader-rotation policy in HotStuff.
- Replacing ADR-0019 / Plan 0005 block migration work; this plan consumes that landed block contract rather than redefining it.

## Related ADRs And Docs
- ADR-0009: Blockchain Application Architecture
- ADR-0017: HotStuff Consensus Without Threshold Signatures
- ADR-0019: Canonical Block Header And Application-Neutral Block View
- ADR-0020: Conflict-Free Block Scheduling With State References And Object-Centric Seams
- `docs/plans/0004-hotstuff-consensus-without-threshold-signatures-plan.md`
- `docs/plans/0005-canonical-block-structure-migration-plan.md`
- `docs/plans/plan-template.md`

## Decisions To Lock Before Implementation
- `StateRef` baseline representation follows ADR-0020: canonical bytes, with table-key baseline equal to `tablePrefix ++ encodedKey`.
- `ConflictFootprint` baseline is two concrete `StateRef` sets: `reads` and `writes`.
- Core/shared ownership for scheduling primitives is `org.sigilaris.core.application.scheduling`; low-level key logging remains in `org.sigilaris.core.application.state`, and Phase 5 block-body verification helpers may live in `org.sigilaris.node.jvm.runtime.consensus.hotstuff` while still consuming the shared scheduling types.
- The baseline scheduling acquisition path is an application-owned deterministic `FootprintDeriver` over signed transaction bytes plus explicit referenced state/object ids already present in the transaction.
- If deterministic derivation fails, the transaction is not schedulable. The initial rollout will route it to an explicit compatibility path rather than silently treating it as schedulable.
- The initial rollout keeps compatibility-path execution sequential. It does not attempt mixed-mode scheduling inside one conflict-free block body.
- The initial developer-visible classification model is `Schedulable(footprint)` vs `Compatibility(reason)`.
- Mixed batches fall back to the explicit compatibility path; the initial rollout does not admit only the schedulable subset from a mixed candidate batch.
- The execution seam must capture actual footprint per transaction, not only a batch-accumulated `AccessLog`.
- Conformance baseline is subset-based: `actualReads ⊆ declaredReads` and `actualWrites ⊆ declaredWrites`.
- One-pass aggregate-set verification is the baseline implementation strategy for both proposer-side selection and validator-side body checks; pairwise `O(N^2)` comparison is not required.
- Full HotStuff proposal/validator enforcement builds on the landed ADR-0019 / Plan 0005 canonical block body or equivalent deterministic transaction projection.
- Initial schedulable pilot families should prioritize transactions whose touched state can be derived exactly from explicit ids already present in the transaction, and defer dynamic scan / auto-selection families to compatibility mode.
- For the currently shipped application surface, the initial pilot schedulable families are the existing account and group transactions whose touched keys are explicit in transaction fields: `CreateNamedAccount`, `UpdateAccount`, `AddKeyIds`, `RemoveKeyIds`, `RemoveAccount`, `CreateGroup`, `DisbandGroup`, `AddAccounts`, `RemoveAccounts`, and `ReplaceCoordinator`.
- The landed Phase 5 helper seam does not by itself count as end-to-end runtime proposer/validator enforcement; concrete runtime wiring is deferred to a later phase.
- Plan completion is split into three landing levels:
  - helper rollout completion: Phases 1-6 landed in shared scheduling, execution, helper HotStuff surfaces, tests, and docs,
  - local runtime rollout completion: Phase 7 wires the current local batch path to those landed helpers,
  - full consensus runtime rollout completion: Phase 7 wires proposer selection and body-visible validator acceptance/vote gating on top of the Plan 0005 body contract.

## Change Areas

### Code
- `sigilaris/modules/core/shared/src/main/scala/org/sigilaris/core/application/state`
- `sigilaris/modules/core/shared/src/main/scala/org/sigilaris/core/application/execution`
- possibly a new package for scheduling helpers, for example `org.sigilaris.core.application.scheduling` or equivalent
- application-owned reducer / coordinator / admission modules that currently own local batch execution
- transaction-family-specific footprint derivation code in application-owned modules
- `sigilaris/modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff` to integrate against the landed Plan 0005 block-body baseline
- `sigilaris/modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/gossip/tx` or whichever concrete node-runtime path owns local batch admission / idempotency / commit behavior

### Tests
- unit tests for `StateRef`, `ConflictFootprint`, merge/intersection, and one-pass verification
- execution tests for per-transaction access-log reset and actual-footprint capture
- application batch-planning tests for schedulable success, schedulable conflict rejection, and compatibility fallback
- conformance tests for `actual ⊆ declared`
- HotStuff proposal/block validation tests on top of the landed canonical body support
- end-to-end runtime tests for concrete local batch-path wiring and body-visible HotStuff proposer/validator wiring

### Docs
- `docs/adr/0020-conflict-free-block-scheduling-with-state-references-and-object-centric-seams.md`
- `docs/plans/0006-conflict-free-block-scheduling-plan.md`
- `docs/plans/0005-canonical-block-structure-migration-plan.md` if dependency wording needs tightening
- application developer/runtime docs if batch semantics or compatibility behavior become externally relevant

## Implementation Phases

### Phase 0: Contract Lock And Rollout Shape
- Decide package ownership for `StateRef`, `ConflictFootprint`, `FootprintDeriver`, conformance-check helpers, and block-body verification helpers.
- Lock the initial compatibility-path rule:
  - if a transaction is non-schedulable, it must not enter the conflict-free path,
  - if a batch mixes schedulable and non-schedulable transactions, the initial rollout should route the whole batch to compatibility mode rather than partially schedule it.
- Lock the initial developer-visible classification model, for example `Schedulable(footprint)` vs `Compatibility(reason)`.
- Lock the first pilot transaction families that must produce exact or conservative deterministic footprints.
- Even though Plan 0005 body-contract readiness is already satisfied, keep HotStuff proposer/validator enforcement as a later phase so the local application rollout can land first without pretending consensus-level enforcement already exists.

### Phase 1: Core Scheduling And Conformance Primitives
- Add the core data types for `StateRef` and `ConflictFootprint`.
- Add one-pass verification helpers that maintain aggregate `readsSeen` and `writesSeen` and detect forbidden overlap.
- Add conversion helpers from actual `AccessLog` to actual `ConflictFootprint`.
- Add conformance helpers that check `actualReads ⊆ declaredReads` and `actualWrites ⊆ declaredWrites`.
- Add a deterministic derivation seam such as `FootprintDeriver[T]` or an equivalent application-owned registry.
- Keep the API storage-neutral, but make sure the table-key baseline is easy to build from existing `StateTable` / `AccessLog` machinery.

### Phase 2: Execution Seam Refactor For Per-Tx Actual Footprints
- Change the current execution path so each transaction runs with a fresh empty `AccessLog` over the current trie state, and returns:
  - the next trie state,
  - the actual per-transaction footprint derived from the resulting `AccessLog`,
  - the execution result/events already returned today.
- A practical baseline shape is:
  - derive a fresh execution state from the current trie state only, for example `StoreState.fromTrieState(current.trieState)` or an equivalent helper,
  - run one transaction against that fresh-log state,
  - persist the resulting trie state forward,
  - capture the resulting per-transaction `AccessLog` or derived footprint into `TxExecution`,
  - do not carry the old transaction's `AccessLog` into the next transaction run.
- Do not keep batch-accumulated access logs as the only observable output.
- Extend `TxExecution` or an equivalent runtime record to carry actual per-transaction footprint or enough information to reconstruct it deterministically.
- Ensure the refactor does not change current reducer semantics except for the new footprint observability and conformance-check capability.

### Phase 3: Local Application Admission And Batch Planning
- Introduce a batch-planning stage ahead of reduction in the current local application path.
- The planner should:
  - classify each transaction as schedulable or compatibility,
  - derive deterministic footprints for schedulable transactions,
  - reject schedulable batches with internal `W∩W` or `R∩W` conflicts using one-pass aggregate-set verification,
  - route non-schedulable or mixed batches to explicit compatibility mode in the initial rollout.
- Integrate conformance checking after execution:
  - schedulable transactions must prove `actual ⊆ declared`,
  - any violation must fail execution for the schedulable path.
- Preserve current duplicate detection, batch idempotency, and receipt flow in the application-owned commit/receipt path.
- Keep the initial executor sequential even for schedulable batches; the first landing is about validity and observability, not parallel execution yet.

### Phase 4: Pilot FootprintDeriver Rollout And Compatibility Classification
- Implement exact or conservative deterministic derivation for pilot families whose touched state is already explicit.
- Likely early candidates include:
  - account or registry transactions whose touched state is fully named by explicit ids in the transaction,
  - membership or governance transactions whose mutable objects are all explicitly referenced,
  - payment or escrow transactions that already carry explicit input ids, balance ids, or escrow ids and can be derived without open-ended search, subject to Phase 0 lock.
- The current shipped account/group surface lands schedulable derivation for the Phase 0 pilot families:
  - `CreateNamedAccount`, `UpdateAccount`, `AddKeyIds`, `RemoveKeyIds`, `RemoveAccount`,
  - `CreateGroup`, `DisbandGroup`, `AddAccounts`, `RemoveAccounts`, `ReplaceCoordinator`.
- `CurrentApplicationScheduling.documentedCompatibilityFamilies` is intentionally empty for the current shipped surface because no remaining account/group transaction family still requires compatibility-only routing.
- Keep dynamic discovery families on the compatibility path initially, especially flows that currently perform prefix scans or automatic UTXO/input selection.
- Document each non-schedulable family and the reason it remains on compatibility mode.
- For dynamic families that must eventually become schedulable, identify whether the right migration is:
  - explicit input/state refs,
  - new object-centric transaction shapes,
  - collapsing dependent operations into one transaction,
  - or splitting the flow across blocks.
- Migration note for future reducer changes:
  - if a family grows prefix scans, automatic input selection, or open-ended membership/key cleanup, it must either expose those touched refs explicitly in the transaction or fall back to `Compatibility(reason)` until the transaction shape is migrated.

### Phase 5: HotStuff Proposer And Validator Integration
- Consume the landed Plan 0005 canonical block body or equivalent deterministic block transaction projection visible to validators.
- Introduce proposer-side selection that only assembles schedulable, conflict-free transaction sets into conflict-free block bodies.
- Add validator-side one-pass body verification using deterministic footprint derivation from block-body transactions.
- Make block acceptance or vote emission reject bodies that violate the ADR-0020 conflict rule.
- Keep footprint metadata out of the canonical block header in line with ADR-0019; derive from body transactions instead.
- Ensure proposal/validation code paths stay application-neutral except for application-owned derivation seams intentionally plugged into block-body verification.
- The landed helper seam for the current header-first, tx-hash-set-carrying proposal artifact is:
  - `ConflictFreeBlockBodySelector` for proposer-side candidate filtering,
  - `HotStuffBlockBodyVerifier` for canonical body/view verification,
  - `HotStuffProposalViewValidator` for body-visible proposal acceptance or vote-gating call sites.
- This helper seam intentionally stops short of end-to-end runtime wiring. Phase 7 owns how application-owned `classifyTx` injection reaches the concrete proposer assembly path and the body-visible validator vote-gating/acceptance path without collapsing the current application-neutral HotStuff boundary.

### Phase 6: Verification, Docs, And Tightening
- Add regression tests covering ordering independence of the aggregate-set verifier.
- Add tests that prove compatibility batches do not get misclassified as schedulable.
- Tighten docs and status in ADR-0020 once the initial rollout shape is implemented.
- Revisit whether some compatibility-path families can be promoted to schedulable status after initial landing.
- Ordering-independence regression remains locked in the shared scheduling verifier suite, while HotStuff body-level regression now also checks canonical unordered-body validation and compatibility rejection at the body boundary.
- Compatibility behavior is now documented in both:
  - `CurrentApplicationScheduling.documentedCompatibilityFamilies` for shipped application families,
  - this plan / ADR-0020 follow-up wording for future dynamic-discovery families that must stay on `Compatibility(reason)` until their transaction shape names explicit refs.

### Phase 7: End-To-End Runtime Wiring
- Wire the current local application batch admission path to call `BatchPlanner.plan(...)(CurrentApplicationScheduling.classify)` or an equivalent application-owned classifier before sequential reduction.
- Route `BatchPlan.Schedulable` through `SchedulableBatchExecutor.executeSequentially` backed by the fresh-log `StateModuleExecutor.runExecution...` seam, and make conformance failures reject the batch at the concrete runtime boundary rather than only in helper tests.
- Route `BatchPlan.Compatibility` through an explicit compatibility-mode sequential execution path while preserving the existing duplicate detection, batch idempotency, and receipt/diagnostic behavior at the concrete runtime boundary.
- If the current node/runtime still lacks one concrete owner for local application batch execution, establish that ownership boundary explicitly in this phase instead of leaving the scheduling helpers library-only.
- Surface runtime-visible diagnostics for `Schedulable` vs `Compatibility(reason)` so mixed and non-schedulable fallbacks are observable in the concrete batch path.
- Decide the concrete body carriage, fetch, or local lookup seam required for body-visible HotStuff proposal acceptance and vote gating while keeping the canonical `Proposal` artifact header-first and lighter than full body carriage unless a minimal runtime-side extension is unavoidable.
- Wire application-owned `classifyTx` injection into the real proposer assembly path so conflict-free block-body selection is exercised by the runtime rather than only by helper tests.
- Wire body-visible proposal acceptance / vote-gating call sites to `HotStuffProposalViewValidator` or an equivalent wrapper so proposals with conflicting or compatibility-only bodies are rejected before acceptance or vote emission in the real runtime path.
- Keep the ADR-0019 header contract unchanged unless Phase 7 proves that a minimal documented side-channel or lookup seam is required for body availability.

## Test Plan
- Phase 1 Success: unit tests show `StateRef` / `ConflictFootprint` equality, merge, and forbidden-overlap predicates behave deterministically.
- Phase 1 Success: one-pass aggregate verification accepts the same transaction set regardless of scan order, proving ordering independence.
- Phase 1 Failure: unit tests reject `W∩W` and `R∩W` conflicts and allow `R∩R`.
- Phase 2 Success: execution tests show each transaction gets a fresh `AccessLog`, actual footprint is captured per transaction, and no hidden batch accumulation is required to observe it.
- Phase 2 Failure: execution-seam regression tests fail if a prior transaction's `AccessLog` is still visible during the next transaction run.
- Phase 3 Success: schedulable application batches with non-conflicting footprints are accepted and still execute correctly under the current sequential executor.
- Phase 3 Failure: schedulable application batches with internal conflict are rejected before reduction.
- Phase 3 Success: mixed or non-schedulable batches are routed to explicit compatibility mode rather than silently entering the schedulable path.
- Phase 3 Failure: conformance tests fail when actual execution touches a `StateRef` outside the declared or derived footprint.
- Phase 4 Success: pilot transaction families derive deterministic footprints without dry-run.
- Phase 4 Failure: dynamic scan or auto-selection families remain compatibility-only until migrated.
- Phase 5 Success: proposer-side selection and validator-side body verification reject conflicting block bodies on top of the landed canonical block-body support.
- Phase 5 Regression: HotStuff proposal/vote/QC identity, request-by-id, and exact-known-set behavior remain green after adding body-level conflict checks.
- Phase 7 Success: the concrete local batch runtime rejects conflicting all-schedulable batches before reducer execution and still preserves duplicate/idempotency behavior for compatibility-mode and deduplicated batches.
- Phase 7 Success: the concrete local batch runtime reports whether a batch executed in schedulable or compatibility mode and surfaces `Compatibility(reason)` for fallback cases.
- Phase 7 Success: the concrete HotStuff proposer runtime emits only body selections that pass application-owned schedulability classification and one-pass conflict checks.
- Phase 7 Failure: the concrete HotStuff proposal acceptance/vote path rejects a body-visible proposal whose body contains a compatibility transaction or scheduling conflict, not only the helper-level validator.
- Phase 7 Regression: existing HotStuff proposal/vote/QC identity, request-by-id, exact known-set, and header-first artifact assumptions remain green after body-visible runtime wiring.

## Risks And Mitigations
- The execution seam may accidentally continue accumulating access logs across transactions and make per-tx witness data unusable.
  - Mitigation: make per-tx fresh-log execution an explicit Phase 2 gate with dedicated regression tests.
- Conservative superset footprints can create false conflicts and reduce throughput.
  - Mitigation: allow conservative supersets initially, but track which derivation paths are conservative and prioritize exact derivation for high-volume flows.
- Mixed-mode batches may confuse operators or hide which semantics applied.
  - Mitigation: add explicit classification and compatibility-path reason reporting in internal logs or diagnostics.
- Dynamic application transaction families may take longer to migrate than the core/shared seam.
  - Mitigation: keep compatibility mode explicit and list non-schedulable families in docs instead of forcing premature partial derivation.
- HotStuff integration may still stall even though Plan 0005 has landed, because application-specific footprint derivation and validator-side body verification are separate work.
  - Mitigation: land Phase 1-4 in local application execution first and keep the remaining HotStuff integration scope explicit instead of pretending the landed block contract already solves scheduler enforcement.
- Application-specific derivation logic may diverge from actual reducer behavior.
  - Mitigation: subset-based conformance checking is mandatory, and derivation bugs should fail fast in schedulable mode.
- Local runtime wiring may regress existing duplicate/idempotency/receipt semantics even if the shared scheduling helpers are correct.
  - Mitigation: Phase 7 must add end-to-end regression at the concrete runtime boundary instead of relying only on helper-level tests.
- Body-visible HotStuff vote gating may require a concrete body-availability seam that the current header-only runtime does not yet own.
  - Mitigation: keep the canonical header contract unchanged, and introduce only the smallest documented runtime-side lookup/attachment seam needed for Phase 7 validation.

## Acceptance Criteria
1. Core/shared code contains first-class `StateRef`, `ConflictFootprint`, deterministic derivation seam, and conformance helpers matching ADR-0020.
2. The execution path can produce actual per-transaction footprints instead of only a batch-accumulated `AccessLog`.
3. Local application admission can distinguish schedulable vs compatibility transactions and reject schedulable batches with internal conflict before execution.
4. Schedulable execution validates `actualReads ⊆ declaredReads` and `actualWrites ⊆ declaredWrites`.
5. At least one pilot transaction family derives deterministic footprints without dry-run, and dynamic discovery families are explicitly classified as compatibility-only.
6. Ordering-independence, conformance failure, compatibility fallback, and schedulable conflict rejection are all locked by tests.
7. Helper rollout completion is achieved when Phases 1-6 are landed in shared scheduling, execution, helper HotStuff surfaces, tests, and docs.
8. Phase 7 local runtime wiring makes the current local application path actually enforce ADR-0020 semantics at the current batch boundary.
9. Phase 7 HotStuff runtime wiring makes the concrete proposer/validator path actually enforce ADR-0020 conflict rules without changing the ADR-0019 header contract.

## Checklist

### Phase 0: Contract Lock And Rollout Shape
- [x] package ownership for scheduling types/helpers decided
- [x] initial `Schedulable` vs `Compatibility` classification contract decided
- [x] derivation-failure routing decided
- [x] mixed-batch compatibility rule decided
- [x] pilot schedulable transaction families listed
- [x] dependency on landed Plan 0005 block-body contract documented

### Phase 1: Core Scheduling And Conformance Primitives
- [x] `StateRef` baseline type added
- [x] `ConflictFootprint` type added
- [x] one-pass aggregate verifier added
- [x] `AccessLog -> ConflictFootprint` conversion helper added
- [x] conformance helper for `actual ⊆ declared` added
- [x] deterministic `FootprintDeriver` seam added
- [x] unit tests for verifier/conformance green

### Phase 2: Execution Seam Refactor For Per-Tx Actual Footprints
- [x] per-tx fresh-log execution seam implemented
- [x] `TxExecution` or equivalent runtime record extended with actual footprint
- [x] regression tests prove no hidden batch-level log dependency remains

### Phase 3: Local Application Admission And Batch Planning
- [x] schedulable vs compatibility batch planner added
- [x] schedulable conflict rejection integrated before reduction
- [x] compatibility-path routing integrated
- [x] post-execution conformance check integrated
- [x] duplicate/idempotency path remains green

### Phase 4: Pilot FootprintDeriver Rollout And Compatibility Classification
- [x] pilot transaction-family derivation implementations added
- [x] non-schedulable families explicitly classified and documented
- [x] migration notes for dynamic-scan families written

### Phase 5: HotStuff Proposer And Validator Integration
- [x] proposer-side conflict-free selection integrated against the landed Plan 0005 body contract
- [x] validator-side one-pass body verification integrated
- [x] block rejection/vote gating for conflicting bodies added
- [x] HotStuff regression suite green

### Phase 6: Verification, Docs, And Tightening
- [x] ordering-independence regression tests green
- [x] compatibility-path behavior documented
- [x] ADR-0020 / plan wording updated to match landed shape
- [x] follow-up items for remaining compatibility families documented

### Phase 7: End-To-End Runtime Wiring
- Landed runtime seam:
  - local application batches now flow through `CurrentApplicationBatchRuntime`,
  - HotStuff body visibility now hydrates a `BlockView` from runtime-owned `BlockStore` / `BlockQuery` by `proposal.targetBlockId`, preserving the header-only `Proposal` contract.
- [x] local batch-path owner for schedulable vs compatibility execution identified and wired
- [x] `BatchPlanner` integrated into the concrete local batch admission path
- [x] `SchedulableBatchExecutor` integrated into the concrete schedulable execution path
- [x] explicit compatibility-mode runtime path integrated with existing duplicate/idempotency/receipt behavior preserved
- [x] runtime-visible diagnostics for `Schedulable` vs `Compatibility(reason)` added
- [x] concrete HotStuff body carriage/fetch/lookup seam decided and documented
- [x] proposer-side `classifyTx` injection wired into concrete block-body assembly
- [x] body-visible validator acceptance / vote-gating wired to `HotStuffProposalViewValidator` or equivalent
- [x] end-to-end local-runtime and HotStuff-runtime regression suite green

## Follow-Ups
- Introduce explicit object-centric transaction shapes for families that cannot become schedulable with the current table-key model.
- Revisit whether compatibility-mode batches should remain accepted long-term or be phased out after application migration.
- If HotStuff needs body-level footprint caching or receipt projection for operational reasons, document it in a follow-up ADR or plan without widening the ADR-0019 header contract.
- If exact deterministic derivation proves too expensive for some families, evaluate whether a limited on-wire footprint field is worth introducing as an application-level extension.
