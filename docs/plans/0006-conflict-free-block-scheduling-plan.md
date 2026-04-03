# 0006 - Conflict-Free Block Scheduling Plan

## Status
Draft

## Created
2026-04-03

## Last Updated
2026-04-03

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
  - `TxReducer` currently executes transactions sequentially and carries `StoreState` forward across the batch, so access logs accumulate unless the execution seam is changed,
  - `PostTxCoordinator` and the BBGO single-node batch path still accept same-batch causality structurally because later transactions run on top of earlier transactions' writes in sequence.
- In the current BBGO single-node path, one accepted batch is reduced and committed as one block. The initial rollout in this plan therefore applies the future block-level conflict rule at the batch boundary as a local stand-in for future conflict-free block bodies.
- The current HotStuff baseline in `sigilaris-node-jvm` still uses the minimal `Block(parent, payloadHash)` artifact. Full consensus-level enforcement of ADR-0020 depends on ADR-0019 / Plan 0005 exposing a canonical block body or equivalent deterministic body projection that validators can inspect.
- BBGO currently contains a mix of transaction families:
  - some can be made schedulable from explicit references already present in the transaction,
  - some still rely on dynamic discovery such as prefix scan, automatic UTXO selection, or open-ended balance search and therefore cannot participate in the schedulable path yet.

## Goal
- Introduce core runtime abstractions for `StateRef`, `ConflictFootprint`, deterministic footprint derivation, and post-execution conformance checking.
- Change execution so actual per-transaction footprints are observable and can be checked against declared or derived footprints.
- Introduce a conflict-free schedulable batch path in BBGO's current single-node execution flow as the first landing zone for ADR-0020.
- Provide an explicit compatibility path for non-schedulable transactions during migration.
- Prepare proposer-side selection and validator-side block-body verification for HotStuff once ADR-0019 / Plan 0005 provides the required body contract.

## Scope
- Core/shared scheduling abstractions and verification helpers.
- Per-transaction actual footprint capture in the current execution path.
- BBGO admission and batch-planning logic for schedulable vs compatibility transactions.
- Initial deterministic `FootprintDeriver` rollout for pilot transaction families.
- HotStuff integration hooks and follow-through once canonical block body support is available.
- Tests, docs, and migration notes related to the new scheduling contract.

## Non-Goals
- Rewriting Sigilaris into a full Sui-style object runtime in this plan.
- Migrating every existing BBGO transaction family to schedulable status in the first rollout.
- Eliminating the compatibility path immediately.
- Designing final distributed mempool policy, fee market policy, or proposer fairness strategy.
- Solving pacemaker, timeout, or leader-rotation policy in HotStuff.
- Replacing ADR-0019 / Plan 0005 block migration work; this plan depends on that work for full consensus-level enforcement.

## Related ADRs And Docs
- ADR-0009: Blockchain Application Architecture
- ADR-0017: HotStuff Consensus Without Threshold Signatures
- ADR-0019: Canonical Block Header And Application-Neutral Block View
- ADR-0020: Conflict-Free Block Scheduling With State References And Object-Centric Seams
- `docs/plans/0004-hotstuff-consensus-without-threshold-signatures-plan.md`
- `docs/plans/0005-canonical-block-structure-migration-plan.md`
- `docs/plans/plan-template.md`
- `bbgo3/modules/node/src/main/scala/com/buchigo/bbgo/node/service/TxReducer.scala`
- `bbgo3/modules/node/src/main/scala/com/buchigo/bbgo/node/service/PostTxCoordinator.scala`

## Decisions To Lock Before Implementation
- `StateRef` baseline representation follows ADR-0020: canonical bytes, with table-key baseline equal to `tablePrefix ++ encodedKey`.
- `ConflictFootprint` baseline is two concrete `StateRef` sets: `reads` and `writes`.
- The baseline scheduling acquisition path is an application-owned deterministic `FootprintDeriver` over signed transaction bytes plus explicit referenced state/object ids already present in the transaction.
- If deterministic derivation fails, the transaction is not schedulable. The initial rollout will route it to an explicit compatibility path rather than silently treating it as schedulable.
- The initial rollout keeps compatibility-path execution sequential. It does not attempt mixed-mode scheduling inside one conflict-free block body.
- The execution seam must capture actual footprint per transaction, not only a batch-accumulated `AccessLog`.
- Conformance baseline is subset-based: `actualReads ⊆ declaredReads` and `actualWrites ⊆ declaredWrites`.
- One-pass aggregate-set verification is the baseline implementation strategy for both proposer-side selection and validator-side body checks; pairwise `O(N^2)` comparison is not required.
- Full HotStuff proposal/validator enforcement depends on ADR-0019 / Plan 0005 exposing a canonical block body or equivalent deterministic transaction projection.
- Initial schedulable pilot families should prioritize transactions whose touched state can be derived exactly from explicit ids already present in the transaction, and defer dynamic scan / auto-selection families to compatibility mode.
- Plan completion is split into two landing levels:
  - local rollout completion: Phases 1-4 landed in BBGO/local execution,
  - full consensus rollout completion: Phase 5 landed after Plan 0005 body-contract readiness.

## Change Areas

### Code
- `sigilaris/modules/core/shared/src/main/scala/org/sigilaris/core/application/state`
- `sigilaris/modules/core/shared/src/main/scala/org/sigilaris/core/application/execution`
- possibly a new package for scheduling helpers, for example `org.sigilaris.core.application.scheduling` or equivalent
- `bbgo3/modules/node/src/main/scala/com/buchigo/bbgo/node/service`
- `bbgo3/modules/node/src/main/scala/com/buchigo/bbgo/node/api`
- transaction-family-specific footprint derivation code in BBGO application modules
- `sigilaris/modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff` after Plan 0005 reaches the required block-body baseline

### Tests
- unit tests for `StateRef`, `ConflictFootprint`, merge/intersection, and one-pass verification
- execution tests for per-transaction access-log reset and actual-footprint capture
- BBGO batch-planning tests for schedulable success, schedulable conflict rejection, and compatibility fallback
- conformance tests for `actual ⊆ declared`
- HotStuff proposal/block validation tests once canonical body support lands

### Docs
- `docs/adr/0020-conflict-free-block-scheduling-with-state-references-and-object-centric-seams.md`
- `docs/plans/0006-conflict-free-block-scheduling-plan.md`
- `docs/plans/0005-canonical-block-structure-migration-plan.md` if dependency wording needs tightening
- BBGO developer/runtime docs if batch semantics or compatibility behavior become externally relevant

## Implementation Phases

### Phase 0: Contract Lock And Rollout Shape
- Decide package ownership for `StateRef`, `ConflictFootprint`, `FootprintDeriver`, conformance-check helpers, and block-body verification helpers.
- Lock the initial compatibility-path rule:
  - if a transaction is non-schedulable, it must not enter the conflict-free path,
  - if a batch mixes schedulable and non-schedulable transactions, the initial rollout should route the whole batch to compatibility mode rather than partially schedule it.
- Lock the initial developer-visible classification model, for example `Schedulable(footprint)` vs `Compatibility(reason)`.
- Lock the first pilot transaction families that must produce exact or conservative deterministic footprints.
- Explicitly gate HotStuff proposer/validator enforcement on Plan 0005 body-contract readiness so the local BBGO rollout can land first without pretending consensus-level enforcement already exists.

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
- Extend `TxReducer.TxExecution` or an equivalent runtime record to carry actual per-transaction footprint or enough information to reconstruct it deterministically.
- Ensure the refactor does not change current reducer semantics except for the new footprint observability and conformance-check capability.

### Phase 3: BBGO Single-Node Admission And Batch Planning
- Introduce a batch-planning stage ahead of reduction in the BBGO single-node path.
- The planner should:
  - classify each transaction as schedulable or compatibility,
  - derive deterministic footprints for schedulable transactions,
  - reject schedulable batches with internal `W∩W` or `R∩W` conflicts using one-pass aggregate-set verification,
  - route non-schedulable or mixed batches to explicit compatibility mode in the initial rollout.
- Integrate conformance checking after execution:
  - schedulable transactions must prove `actual ⊆ declared`,
  - any violation must fail execution for the schedulable path.
- Preserve current duplicate detection, batch idempotency, and receipt flow in `PostTxCoordinator`.
- Keep the initial executor sequential even for schedulable batches; the first landing is about validity and observability, not parallel execution yet.

### Phase 4: Pilot FootprintDeriver Rollout And Compatibility Classification
- Implement exact or conservative deterministic derivation for pilot families whose touched state is already explicit.
- Likely early candidates include:
  - Accounts: `CreateNamedAccount`, `UpdateAccount`, `AddKeyIds`, `RemoveKeyIds`, `RemoveAccount`,
  - Groups: `CreateGroup`, `DisbandGroup`, `AddAccounts`, `RemoveAccounts`, `ReplaceCoordinator`,
  - payment/governance-style txs with explicit ids and no dynamic discovery: `DefinePaymentToken`, `RegisterDepositSource`, `UpdateDepositSourceStatus`,
  - payment/escrow txs that already carry explicit referenced input or escrow ids and can be derived without open-ended search, such as `BurnTokenWithSource`, `TransferToken`, `RefundFromBalance`, `CreatePaymentEscrow`, `ReleasePaymentEscrow`, `RefundPaymentEscrow`, subject to Phase 0 lock.
- Keep dynamic discovery families on the compatibility path initially, especially flows that currently perform prefix scans or automatic UTXO/input selection.
- Document each non-schedulable family and the reason it remains on compatibility mode.
- For dynamic families that must eventually become schedulable, identify whether the right migration is:
  - explicit input/state refs,
  - new object-centric transaction shapes,
  - collapsing dependent operations into one transaction,
  - or splitting the flow across blocks.

### Phase 5: HotStuff Proposer And Validator Integration
- Start only after Plan 0005 provides a canonical block body or equivalent deterministic block transaction projection visible to validators.
- Introduce proposer-side selection that only assembles schedulable, conflict-free transaction sets into conflict-free block bodies.
- Add validator-side one-pass body verification using deterministic footprint derivation from block-body transactions.
- Make block acceptance or vote emission reject bodies that violate the ADR-0020 conflict rule.
- Keep footprint metadata out of the canonical block header in line with ADR-0019; derive from body transactions instead.
- Ensure proposal/validation code paths stay application-neutral except for application-owned derivation seams intentionally plugged into block-body verification.

### Phase 6: Verification, Docs, And Tightening
- Add regression tests covering ordering independence of the aggregate-set verifier.
- Add tests that prove compatibility batches do not get misclassified as schedulable.
- Tighten docs and status in ADR-0020 once the initial rollout shape is implemented.
- Revisit whether some compatibility-path families can be promoted to schedulable status after initial landing.

## Test Plan
- Phase 1 Success: unit tests show `StateRef` / `ConflictFootprint` equality, merge, and forbidden-overlap predicates behave deterministically.
- Phase 1 Success: one-pass aggregate verification accepts the same transaction set regardless of scan order, proving ordering independence.
- Phase 1 Failure: unit tests reject `W∩W` and `R∩W` conflicts and allow `R∩R`.
- Phase 2 Success: execution tests show each transaction gets a fresh `AccessLog`, actual footprint is captured per transaction, and no hidden batch accumulation is required to observe it.
- Phase 2 Failure: execution-seam regression tests fail if a prior transaction's `AccessLog` is still visible during the next transaction run.
- Phase 3 Success: schedulable BBGO batches with non-conflicting footprints are accepted and still execute correctly under the current sequential executor.
- Phase 3 Failure: schedulable BBGO batches with internal conflict are rejected before reduction.
- Phase 3 Success: mixed or non-schedulable batches are routed to explicit compatibility mode rather than silently entering the schedulable path.
- Phase 3 Failure: conformance tests fail when actual execution touches a `StateRef` outside the declared or derived footprint.
- Phase 4 Success: pilot transaction families derive deterministic footprints without dry-run.
- Phase 4 Failure: dynamic scan or auto-selection families remain compatibility-only until migrated.
- Phase 5 Success: once block-body support exists, proposer-side selection and validator-side body verification reject conflicting block bodies.
- Phase 5 Regression: HotStuff proposal/vote/QC identity, request-by-id, and exact-known-set behavior remain green after adding body-level conflict checks.

## Risks And Mitigations
- The execution seam may accidentally continue accumulating access logs across transactions and make per-tx witness data unusable.
  - Mitigation: make per-tx fresh-log execution an explicit Phase 2 gate with dedicated regression tests.
- Conservative superset footprints can create false conflicts and reduce throughput.
  - Mitigation: allow conservative supersets initially, but track which derivation paths are conservative and prioritize exact derivation for high-volume flows.
- Mixed-mode batches may confuse operators or hide which semantics applied.
  - Mitigation: add explicit classification and compatibility-path reason reporting in internal logs or diagnostics.
- Dynamic BBGO transaction families may take longer to migrate than the core/shared seam.
  - Mitigation: keep compatibility mode explicit and list non-schedulable families in docs instead of forcing premature partial derivation.
- HotStuff integration may stall if Plan 0005 does not expose a usable canonical body contract quickly enough.
  - Mitigation: land Phase 1-4 in BBGO/local execution first and make the dependency explicit instead of blocking the whole effort.
- Application-specific derivation logic may diverge from actual reducer behavior.
  - Mitigation: subset-based conformance checking is mandatory, and derivation bugs should fail fast in schedulable mode.

## Acceptance Criteria
1. Core/shared code contains first-class `StateRef`, `ConflictFootprint`, deterministic derivation seam, and conformance helpers matching ADR-0020.
2. The execution path can produce actual per-transaction footprints instead of only a batch-accumulated `AccessLog`.
3. BBGO single-node admission can distinguish schedulable vs compatibility transactions and reject schedulable batches with internal conflict before execution.
4. Schedulable execution validates `actualReads ⊆ declaredReads` and `actualWrites ⊆ declaredWrites`.
5. At least one pilot transaction family derives deterministic footprints without dry-run, and dynamic discovery families are explicitly classified as compatibility-only.
6. Ordering-independence, conformance failure, compatibility fallback, and schedulable conflict rejection are all locked by tests.
7. Local rollout completion is achieved when Phases 1-4 are landed and the BBGO single-node path enforces ADR-0020 semantics at the current batch-to-block boundary.
8. Once Plan 0005 exposes canonical block-body support, HotStuff proposer/validator integration can enforce ADR-0020 conflict rules without changing the ADR-0019 header contract.

## Checklist

### Phase 0: Contract Lock And Rollout Shape
- [ ] package ownership for scheduling types/helpers decided
- [ ] initial `Schedulable` vs `Compatibility` classification contract decided
- [ ] derivation-failure routing decided
- [ ] mixed-batch compatibility rule decided
- [ ] pilot schedulable transaction families listed
- [ ] dependency on Plan 0005 block-body readiness documented

### Phase 1: Core Scheduling And Conformance Primitives
- [ ] `StateRef` baseline type added
- [ ] `ConflictFootprint` type added
- [ ] one-pass aggregate verifier added
- [ ] `AccessLog -> ConflictFootprint` conversion helper added
- [ ] conformance helper for `actual ⊆ declared` added
- [ ] deterministic `FootprintDeriver` seam added
- [ ] unit tests for verifier/conformance green

### Phase 2: Execution Seam Refactor For Per-Tx Actual Footprints
- [ ] per-tx fresh-log execution seam implemented
- [ ] `TxExecution` or equivalent runtime record extended with actual footprint
- [ ] regression tests prove no hidden batch-level log dependency remains

### Phase 3: BBGO Single-Node Admission And Batch Planning
- [ ] schedulable vs compatibility batch planner added
- [ ] schedulable conflict rejection integrated before reduction
- [ ] compatibility-path routing integrated
- [ ] post-execution conformance check integrated
- [ ] duplicate/idempotency path remains green

### Phase 4: Pilot FootprintDeriver Rollout And Compatibility Classification
- [ ] pilot transaction-family derivation implementations added
- [ ] non-schedulable families explicitly classified and documented
- [ ] migration notes for dynamic-scan families written

### Phase 5: HotStuff Proposer And Validator Integration
- [ ] proposer-side conflict-free selection integrated after Plan 0005 readiness
- [ ] validator-side one-pass body verification integrated
- [ ] block rejection/vote gating for conflicting bodies added
- [ ] HotStuff regression suite green

### Phase 6: Verification, Docs, And Tightening
- [ ] ordering-independence regression tests green
- [ ] compatibility-path behavior documented
- [ ] ADR-0020 / plan wording updated to match landed shape
- [ ] follow-up items for remaining compatibility families documented

## Follow-Ups
- Introduce explicit object-centric transaction shapes for families that cannot become schedulable with the current table-key model.
- Revisit whether compatibility-mode batches should remain accepted long-term or be phased out after application migration.
- If HotStuff needs body-level footprint caching or receipt projection for operational reasons, document it in a follow-up ADR or plan without widening the ADR-0019 header contract.
- If exact deterministic derivation proves too expensive for some families, evaluate whether a limited on-wire footprint field is worth introducing as an application-level extension.
