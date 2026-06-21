# 0021 - HotStuff Proposal Tx Uniqueness Policy Plan

## Status

Complete

## Created

2026-06-20

## Last Updated

2026-06-21

`Last Updated` reflects the newest date in `Revision Notes`.

## Revision Notes

- 2026-06-21: Incorporated post-implementation review follow-up: finalized tx
  range handoff now retains a bounded in-memory history for slower pollers,
  docs describe `previousFinalized` gap detection, tests cover multi-block
  handoff ranges, automatic consensus rejects zero tx-exclusion capacity, and
  full `nodeJvm/test` passed with 359 tests after the follow-up.
- 2026-06-21: Completed Phase 5 integration and release docs: runtime and
  bootstrap surfaces now default to enforced tx uniqueness while exposing an
  explicit unsafe compatibility config, automatic consensus validates the
  policy bounds, unsafe mode records bounded diagnostics, handoff docs and
  v0.2.6 release notes describe the embedder contract, focused HotStuff suites
  passed with 137 tests, and full `nodeJvm/test` passed with 359 tests.
- 2026-06-20: Completed Phase 4 finalized tx range handoff: in-memory HotStuff
  exposes `FinalizedTxRangeObservation` by chain, range observations include
  previous/new finalized anchors plus every newly finalized proposal tx set,
  tests cover initial and advancing ranges, and handoff docs tell embedders to
  evict finalized tx ids from pending pools.
- 2026-06-20: Completed Phase 3 vote-time enforcement: automatic and manual
  local vote paths run unfinalized ancestor tx uniqueness before application
  proposal validation, conflict/unavailable ancestry suppress local votes with
  vote-time diagnostics or policy violations, and focused proposal-validation
  tests cover skipped application validation.
- 2026-06-20: Completed Phase 2 proposal-input enforcement: requests carry
  ancestor tx exclusions, pacemaker computes exclusions before provider lookup,
  provider outputs are rejected before signing on excluded tx conflicts, input
  diagnostics cover exclusions/conflicts/unavailable ancestry, and focused
  proposal-input tests pass.
- 2026-06-20: Completed Phase 1 core helper with bounded ancestor traversal,
  accepted-exclusion memoization, conflict/unavailable result taxonomy,
  diagnostic-safe metadata, and focused tests for finalized boundaries,
  missing ancestry, conflicts, sibling forks, proposal-id parent lookup, and
  traversal/exclusion overflow.
- 2026-06-20: Locked Phase 0 decisions: ADR-0029 accepted, runtime config and
  result type names fixed, proposal input gets source-compatible
  `txExclusion`, input `Unavailable` diagnostic is added, finalization handoff
  uses one range observation per advancement, traversal memoizes by
  parent/finalized-boundary and defaults to 1024 ancestors / 65536 tx ids, and
  unsafe compatibility uses the `UnsafeAllowAncestorTxConflicts` mode with an
  `unsafeAllowAncestorTxConflicts` helper.
- 2026-06-20: Incorporated review feedback before lock: finalization handoff
  now covers the newly finalized block range, branch-only ancestor scanning and
  sibling fork reuse are explicit, different local finalized boundaries are
  called out as a liveness risk, traversal cost bounds are a Phase 0 decision,
  and reason/result taxonomy is centralized.
- 2026-06-20: Initial plan for default unfinalized-ancestor tx uniqueness,
  proposal-input exclusions, vote-time validation, finalization pending-pool
  handoff, and diagnostics.

## Background

Sigilaris already exposes application-neutral proposal input and proposal
validation hooks. The proposal input hook lets the leader ask an embedder for
the next `ProposalTxSet`, and the proposal validation hook lets the embedder
gate a received proposal before signing a local vote.

The current HotStuff structural validator checks that a proposal's tx set is
canonical and duplicate-free inside that single proposal. It does not enforce a
chain-level rule that a proposal cannot repeat tx ids already present in its
unfinalized ancestors.

For embedders that use `ProposalTxSet` ids as transaction identities, that gap
is material. A transaction selected into one unfinalized proposal should not
remain eligible for a descendant proposal. When the earlier block is finalized,
the embedder should remove its tx ids from the pending pool. Before that
finalization point, Sigilaris can enforce the bounded invariant directly from
proposal metadata: among unfinalized ancestors, the same tx id must not appear
twice on the same chain.

## Goal

Add a default HotStuff tx uniqueness policy that prevents local proposal signing
and local vote signing when a proposal tx id conflicts with tx ids already
present in the candidate's unfinalized ancestor chain.

The target outcome is:

- finalized blocks give embedders a clear finalized tx-id handoff so they can
  evict every tx in the newly finalized block range from pending pools;
- leader proposal input is selected with the unfinalized ancestor tx ids
  excluded from consideration;
- provider-supplied proposal input is rejected before signing if it still
  contains excluded tx ids;
- received proposals are checked with the same unfinalized ancestor rule before
  a local vote is signed;
- tx uniqueness is enabled by default for automatic consensus;
- missing ancestry suppresses proposal/vote emission until catch-up supplies
  enough data to prove the invariant;
- application-specific finalized-history replay checks remain embedder-owned.

## Scope

- Add a HotStuff-owned tx uniqueness policy and check result taxonomy.
- Compute unfinalized ancestor tx exclusions from the local HotStuff proposal
  graph and finalization snapshot.
- Extend proposal-input requests with an exclusion view so embedders can select
  pending work without reusing tx ids from unfinalized ancestors.
- Validate provider-supplied proposal input against the exclusion view before
  signing a local proposal.
- Gate local vote signing with the same unfinalized ancestor tx conflict check.
- Add diagnostics for supplied exclusions, conflicts, unavailable ancestry, and
  explicit unsafe compatibility mode.
- Add tests for proposal construction, vote suppression, finalization handoff,
  and default runtime wiring.
- Document the embedder contract for pending-pool eviction on finalization.

## Non-Goals

- Do not add application transaction bodies or application batch types to
  Sigilaris.
- Do not make Sigilaris own application pending-pool persistence.
- Do not make Sigilaris maintain an unbounded finalized tx replay index.
- Do not replace application proposal validation. Embedders still own
  application-state checks, finalized-history replay checks, body availability,
  nonce/account checks, and materialization policy.
- Do not silently mutate provider-supplied proposal input after the provider has
  computed roots.
- Do not change HotStuff proposal, vote, or QC signing bytes except where an
  explicit type addition requires normal canonical encoding updates.
- Do not change timeout-vote behavior. Timeout votes remain liveness artifacts.

## Related ADRs And Docs

- [ADR-0017: HotStuff Consensus Without Threshold Signatures](../adr/0017-hotstuff-consensus-without-threshold-signatures.md)
- [ADR-0019: Canonical Block Header And Application-Neutral Block View](../adr/0019-canonical-block-header-and-application-neutral-block-view.md)
- [ADR-0028: HotStuff Finalization Observability And Embedder Failure Semantics](../adr/0028-hotstuff-finalization-observability-and-embedder-failure-semantics.md)
- [ADR-0029: HotStuff Proposal Tx Uniqueness Policy](../adr/0029-hotstuff-proposal-tx-uniqueness-policy.md)
- [0016 - HotStuff Application Proposal Input Hook Plan](0016-hotstuff-application-proposal-input-hook-plan.md)
- [0017 - HotStuff Application Proposal Validation Hook Plan](0017-hotstuff-application-proposal-validation-hook-plan.md)

## Decisions To Lock Before Implementation

1. **Policy surface**
   - Introduce a runtime config
     `HotStuffProposalTxUniquenessRuntimeConfig`.
   - Default automatic consensus should use
     `EnforceUnfinalizedAncestors`.
   - Any opt-out must be explicit and named as unsafe compatibility behavior:
     the mode value is `UnsafeAllowAncestorTxConflicts`, and config helpers
     expose it as `unsafeAllowAncestorTxConflicts`.
   - Bootstrap validation should reject production automatic-consensus
     configurations that accidentally disable the policy unless the caller opts
     into the unsafe compatibility mode.

2. **Unfinalized ancestor boundary**
   - The scan starts from the candidate parent block id.
   - The scan follows known proposal parents on the same chain.
   - The scan stops before the local best finalized anchor when that anchor is
     known and the candidate descends from it.
   - If there is no best finalized anchor, scan all known ancestors until
     genesis or the local ancestry boundary.
   - If an expected ancestor between the candidate parent and the finalized
     boundary is missing, the result is `Unavailable`, not `Accepted`.
   - The scan is branch-local. Tx ids present only in sibling or cousin forks
     are allowed on the candidate branch unless they also appear in the
     candidate's ancestor chain.

3. **Proposal-input exclusion contract**
   - Add an exclusion view to `HotStuffProposalInputRequest`:
     `txExclusion: HotStuffProposalTxExclusion`.
   - The exclusion view must be canonical and duplicate-free.
   - Providers must not include excluded tx ids in returned input.
   - Sigilaris validates the returned input and suppresses signing with
     `proposalInputTxAncestorConflict` if the provider returns a conflict.
   - Sigilaris must not remove the conflicting tx ids itself because
     `stateRoot` and `bodyRoot` may already commit to the original selection.

4. **Vote-time validation placement**
   - Structural validation remains first.
   - Tx uniqueness runs before application proposal validation and before any
     local vote is signed.
   - If tx uniqueness reports `Conflict`, suppress the local vote and map the
     vote diagnostic outcome to `Rejected`.
   - If tx uniqueness reports unavailable ancestry, suppress the local vote
     with `Unavailable`.
   - Application proposal validation still runs only after generic structural
     and uniqueness checks have accepted the proposal.

5. **Finalization handoff**
   - Expose finalized tx ids for every newly finalized block through
     `FinalizedTxRangeObservation`.
   - The handoff scope is the finalized range from the previous local best
     finalized anchor, exclusive, to the new best finalized anchor, inclusive.
   - Emit one range observation per finalization advancement.
   - The handoff must not expose only the explicit three-chain anchor tx set.
   - The handoff should identify at least chain id, finalized observed time,
     previous finalized anchor when known, new finalized anchor, and each
     newly finalized proposal's proposal id, block id, height, and tx set.
   - Embedders are responsible for removing those tx ids from pending pools.
   - If an embedder keeps finalized tx ids pending, application validation or
     application state must still reject finalized-history replays.

6. **Result and reason taxonomy**
   - The core uniqueness check result type is
     `HotStuffProposalTxUniquenessResult`, with taxonomy members `Accepted`,
     `Conflict`, and `Unavailable`.
   - Proposal-input diagnostics map `Conflict` to the existing input `Invalid`
     outcome because the provider returned input that violates the request
     exclusions.
   - Proposal-input diagnostics should map `Unavailable` to a dedicated input
     `Unavailable` outcome.
   - Vote-time diagnostics map `Conflict` to the existing
     proposal-validation `Rejected` outcome and `Unavailable` to the existing
     `Unavailable` outcome.
   - Outcome and reason codes are path-specific:

     | Path | Core result | Diagnostic outcome | Reason |
     | ---- | ----------- | ------------------ | ------ |
     | proposal input | `Conflict` | `Invalid` | `proposalInputTxAncestorConflict` |
     | proposal input | `Unavailable` | `Unavailable` | `proposalInputTxAncestorUnavailable` |
     | proposal vote | `Conflict` | `Rejected` | `proposalVoteTxAncestorConflict` |
     | proposal vote | `Unavailable` | `Unavailable` | `proposalVoteTxAncestorUnavailable` |

   - Additional reasons may be added for bounds or configuration diagnostics,
     but these four names are the stable baseline for the policy.

7. **Diagnostics**
   - Add bounded diagnostics for:
     - exclusions computed for proposal input;
     - proposal input suppressed by ancestor tx conflict;
     - local vote suppressed by ancestor tx conflict;
     - local vote suppressed because ancestry is unavailable;
     - unsafe compatibility mode enabled.
   - Diagnostics must include proposal id or target block id where available,
     chain id, height, local voter for vote-time checks, reason, detail, and
     conflict count.
   - Diagnostics must not include transaction bodies.

8. **Traversal cost and bounds**
   - The implementation should avoid rebuilding the same parent-chain
     exclusion set for every local vote when a parent and finalized boundary
     pair has already been checked.
   - Phase 0 must choose whether to memoize by
     `(chainId, parentBlockId, bestFinalizedBlockId)`.
   - Phase 0 chooses both a maximum traversal depth and a maximum exclusion tx
     count. The default bounds are 1024 ancestors and 65536 tx ids. Exceeding
     those limits should produce `Unavailable`, not a local vote or proposal.
   - These limits are liveness controls for finalization stalls; they do not
     weaken the uniqueness rule.

9. **Compatibility**
   - Existing legacy empty-proposal tests should keep working because empty
     tx sets never conflict.
   - The `HotStuffProposalInputRequest` migration is source-compatible for
     direct construction because `txExclusion` has a default empty value.
   - Providers should consult `request.txExclusion.excludedTxIds` when selecting
     pending work. Providers that ignore the new field still compile, but their
     returned input is rejected before signing if it includes excluded ids.
   - Adding `HotStuffProposalInputDiagnosticOutcome.Unavailable` is an additive
     sealed-enum change for consumers that exhaustively pattern-match on
     diagnostics.

## Change Areas

### Code

- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/ProposalInput.scala`
  - Add the proposal-input exclusion field and validation.
  - Extend proposal-input diagnostics to report ancestor conflicts.
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/ApplicationProposalValidation.scala`
  - Keep application validation as the embedder-owned stricter policy.
  - Update decision ordering or docs so generic tx uniqueness runs before the
    provider.
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/Validation.scala`
  - Keep per-proposal canonical tx-set validation.
  - Add or call the ancestor uniqueness check from vote-signing paths rather
    than broad structural artifact retention.
- New or existing HotStuff helper, for example `ProposalTxUniqueness.scala`
  - Define `HotStuffProposalTxUniquenessPolicy`.
  - Define check results such as `Accepted`, `Conflict`, and `Unavailable`.
  - Build canonical unfinalized ancestor exclusion sets.
  - Provide reusable functions for proposal-input and vote-time checks.
  - Add the shared traversal core; the current finalization code has
    specialized ancestor walkers for finalization/backfill, not a general
    proposal tx uniqueness helper.
- `PacemakerIntegrationRuntime.scala`
  - Build the exclusion set before invoking the proposal input provider.
  - Validate returned input against the exclusion set before signing.
  - Record diagnostics for exclusions, conflicts, and unavailable ancestry.
  - Use the same uniqueness check before auto-vote signing.
- `HotStuffNodeRuntime.scala`
  - Ensure manual/public local vote helpers route through the uniqueness gate.
  - Expose finalization tx observations or helper accessors if the handoff is
    runtime-owned.
- `InMemoryHotStuffGossipBridge.scala` and `Finalization.scala`
  - Expose the local proposal graph and finalization snapshot needed to resolve
    unfinalized ancestors through the new shared helper.
  - Expose enough snapshot data for uniqueness checks without duplicating
    fragile traversal code.
- `HotStuffRuntimeBootstrap.scala`
  - Add runtime/bootstrap config wiring and validation for the default policy.

### Tests

- Unit tests for ancestor exclusion computation:
  - no ancestors;
  - ancestors above finalized boundary;
  - finalized boundary stops the scan;
  - missing ancestor returns unavailable;
  - duplicate tx in an unfinalized ancestor returns conflict.
  - tx ids in sibling or cousin forks do not conflict with the candidate
    branch.
  - traversal depth or exclusion-size overflow returns unavailable.
- Proposal input tests:
  - request contains excluded tx ids from unfinalized ancestors;
  - provider returning excluded tx is suppressed before signing;
  - empty tx set remains compatible;
  - unsafe mode records diagnostics if enabled.
- Vote-time tests:
  - local vote is signed when no conflict exists;
  - local vote is suppressed for ancestor tx conflict;
  - local vote is suppressed for unavailable ancestry;
  - application proposal validation is not invoked after generic conflict;
  - manual vote helper and automatic pacemaker path use the same gate.
- Finalization handoff tests:
  - finalized tx observation contains every newly finalized block in the range
    from the previous best finalized anchor to the new best finalized anchor;
  - embedders can consume all newly finalized tx ids to remove pending tx ids;
  - finalized ancestors are not included in the unfinalized exclusion set.
- Integration tests:
  - multi-node HotStuff flow where the same pending tx is proposed once, then
    excluded from descendant proposals until finalization;
  - a conflicting peer proposal is retained for catch-up if structurally valid
    but never receives a local vote under the default policy.

### Docs

- Update `docs/dev/hotstuff-proposal-input-provider-handoff.md` with the new
  exclusion request field and provider obligations.
- Update release notes for the target version.
- Link ADR-0029 from any implementation plan or release checklist.
- Add migration notes for embedders with custom proposal input providers.

## Implementation Phases

### Phase 0: Policy And Contract Lock

- Lock ADR-0029.
- Choose final type names for policy config, exclusion request field, and check
  results.
- Lock the path-specific outcome and reason taxonomy for proposal input and
  vote-time diagnostics, including whether proposal-input `Unavailable` adds a
  new diagnostic enum value or uses an explicit compatibility fallback.
- Decide source-compatibility strategy for `HotStuffProposalInputRequest`.
- Decide whether finalized range tx handoff is a new observation type, an added
  field on existing finalization observations, or a helper accessor.
- Lock whether range handoff is emitted as one event per newly finalized block
  or one range event containing every newly finalized block.
- Choose traversal memoization and bounds for finalization stalls.
- Confirm default bootstrap behavior and unsafe compatibility naming.

### Phase 1: Ancestor Uniqueness Core

- Implement the reusable unfinalized ancestor traversal and tx-id collection.
- Return structured `Accepted`, `Conflict`, and `Unavailable` results.
- Implement the chosen traversal memoization and depth/size bounds.
- Add focused unit tests for ancestry boundaries and missing ancestry.
- Add sibling-fork allowance tests and traversal-overflow tests.
- Add diagnostics metadata helpers without exposing transaction bodies.

### Phase 2: Proposal Input Enforcement

- Extend `HotStuffProposalInputRequest` with the exclusion view.
- Populate exclusions before calling the provider.
- Validate provider output against exclusions before signing proposals.
- Suppress proposal signing on conflicts or unavailable ancestry.
- Add proposal input provider tests and pacemaker diagnostics tests.

### Phase 3: Vote-Time Enforcement

- Wire the uniqueness check into automatic vote emission before application
  proposal validation.
- Wire the same check into manual/public local vote helpers.
- Add vote suppression tests for conflict and unavailable ancestry.
- Verify application validation is skipped after a generic uniqueness failure.

### Phase 4: Finalization Handoff

- Expose every newly finalized block and its tx ids through the selected range
  observation or helper surface.
- Add tests showing all newly finalized tx ids are available to embedders,
  including intermediate ancestors finalized by the same advancement.
- Document that embedders remove finalized tx ids from pending pools on this
  handoff.

### Phase 5: Integration And Release Docs

- Run focused HotStuff proposal-input, proposal-validation, pacemaker, and
  runtime bootstrap suites.
- Run full `nodeJvm/test` if the focused suites pass.
- Update handoff docs and release notes.
- Mark this plan's checklist complete only after tests and docs are updated.

## Test Plan

- Run focused suites around:
  - `HotStuffProposalInputProviderSuite`;
  - `HotStuffProposalInputPacemakerIntegrationSuite`;
  - `HotStuffProposalValidationProviderSuite`;
  - `HotStuffProposalValidationPacemakerIntegrationSuite`;
  - `HotStuffRuntimeSchedulingIntegrationSuite`;
  - `HotStuffRuntimeBootstrapSuite`.
- Add new tests for the reusable uniqueness helper before runtime wiring.
- Add integration coverage where a tx appears in an unfinalized parent and the
  descendant proposal path excludes it.
- Add negative coverage where a received peer proposal repeats an unfinalized
  ancestor tx and local voting is suppressed.
- Add unavailable-ancestry coverage so missing parent-chain data does not sign.
- Add sibling-fork coverage so tx ids from non-ancestor forks remain eligible
  on the candidate branch.
- Add traversal-bound coverage so excessive unfinalized ancestry returns
  unavailable rather than signing.
- Add finalized-range handoff coverage where one finalization advancement
  commits multiple intermediate ancestors.
- Run full `sbt "nodeJvm/test"` before closing the plan.

## Risks And Mitigations

- **Risk: source compatibility break for proposal input providers.**
  - Mitigation: either provide a source-compatible default field strategy or
    document the release as a planned provider API migration.

- **Risk: liveness reduction when ancestry is missing.**
  - Mitigation: classify missing ancestry as `Unavailable`, keep proposals
    structurally retained for catch-up, and make diagnostics clear enough for
    operators to distinguish catch-up lag from invalid proposals.

- **Risk: liveness delay when honest nodes observe different finalized
  boundaries.**
  - Mitigation: document that a node with a lower local finalized boundary may
    suppress a vote that a more caught-up node would accept, and rely on
    catch-up/finalization convergence plus embedder finalized-history replay
    checks to resolve the discrepancy.

- **Risk: exclusion traversal grows during finalization stalls.**
  - Mitigation: memoize exclusion sets for repeated parent/finalized-boundary
    checks and configure traversal depth or tx-count bounds that return
    `Unavailable` when exceeded.

- **Risk: runtime post-filtering would desynchronize roots.**
  - Mitigation: make the provider select with exclusions and suppress invalid
    returned input instead of mutating it.

- **Risk: embedders assume Sigilaris provides full replay protection.**
  - Mitigation: document that Sigilaris enforces only unfinalized ancestor
    uniqueness by default; finalized-history replay checks remain
    application-owned.

- **Risk: finalized-boundary traversal differs across nodes with different
  local finalization observations.**
  - Mitigation: if best finalized is unknown locally, scan all known ancestors;
    never accept a proposal merely because the local finalized snapshot is
    behind or missing. If the finalized boundary is known but behind another
    node's boundary, prefer local safety and expose the liveness delay through
    diagnostics.

## Acceptance Criteria

1. Automatic consensus enables unfinalized ancestor tx uniqueness by default.
2. Proposal input requests include a canonical exclusion set for tx ids already
   present in unfinalized ancestors of the candidate parent.
3. Provider-supplied proposal input containing an excluded tx id is not signed.
4. Local vote emission is suppressed before signing when a received proposal
   repeats a tx id from an unfinalized ancestor.
5. Local vote emission is suppressed when ancestry needed for the uniqueness
   check is unavailable.
6. Newly finalized range tx ids are observable or queryable so embedders can
   remove every newly finalized tx from pending pools.
7. Tests cover proposal-input enforcement, vote-time enforcement, unavailable
   ancestry, sibling-fork allowance, traversal overflow, finalized-range
   behavior, and default config wiring.
8. Handoff docs explain both the default Sigilaris policy and the remaining
   embedder-owned replay checks.

## Checklist

### Phase 0: Policy And Contract Lock

- [x] ADR-0029 accepted or explicitly revised.
- [x] Type names and request shape locked.
- [x] Result, diagnostic outcome, and reason taxonomy locked.
- [x] Proposal-input `Unavailable` enum addition or fallback mapping locked.
- [x] Source-compatibility strategy chosen.
- [x] Finalized range tx handoff surface chosen.
- [x] Finalized range event granularity chosen.
- [x] Traversal memoization and bounds chosen.
- [x] Default and unsafe compatibility config names locked.

### Phase 1: Ancestor Uniqueness Core

- [x] Reusable ancestor tx uniqueness helper implemented.
- [x] Conflict and unavailable results implemented.
- [x] Traversal memoization and bounds implemented.
- [x] Unit tests cover finalized boundary, missing ancestry, and conflicts.
- [x] Unit tests cover sibling-fork allowance and traversal overflow.
- [x] Diagnostics metadata helpers added.

### Phase 2: Proposal Input Enforcement

- [x] Proposal input request carries exclusions.
- [x] Pacemaker populates exclusions before provider lookup.
- [x] Provider output is checked against exclusions before signing.
- [x] Proposal input diagnostics cover exclusions and conflicts.
- [x] Proposal input tests pass.

### Phase 3: Vote-Time Enforcement

- [x] Automatic vote path uses the uniqueness gate.
- [x] Manual/public local vote helpers use the same uniqueness gate.
- [x] Application proposal validation runs only after uniqueness accepts.
- [x] Vote-time suppression tests cover conflict and unavailable ancestry.

### Phase 4: Finalization Handoff

- [x] Newly finalized range tx ids are exposed through the selected handoff
      surface.
- [x] Finalization handoff tests pass.
- [x] Docs state that embedders evict finalized tx ids from pending pools.

### Phase 5: Integration And Release Docs

- [x] Focused HotStuff suites pass.
- [x] Full `sbt "nodeJvm/test"` passes or any failures are documented.
- [x] Handoff docs updated.
- [x] Release notes updated.
- [x] Plan status updated after implementation and verification.

## Verification

- `sbt "nodeJvm/Test/compile"` passed.
- `sbt "nodeJvm/testOnly *HotStuffProposalTxUniquenessSuite *HotStuffProposalInputProviderSuite *HotStuffProposalInputPacemakerIntegrationSuite *HotStuffProposalValidationProviderSuite *HotStuffProposalValidationPacemakerIntegrationSuite *HotStuffRuntimeSchedulingIntegrationSuite *HotStuffRuntimeBootstrapSuite *HotStuffFinalizationObservationSuite *HotStuffFinalizationSuite *HotStuffPacemakerRuntimeSuite *HotStuffRuntimeServiceSuite *HotStuffGossipLoopbackSuite *HotStuffLaunchSmokeSuite"` passed: Total 137, Failed 0, Errors 0.
- Post-review follow-up `sbt "nodeJvm/Test/compile" "nodeJvm/testOnly *HotStuffFinalizationObservationSuite *HotStuffRuntimeBootstrapSuite"` passed: Total 45, Failed 0, Errors 0.
- `sbt "nodeJvm/test"` passed: Total 359, Failed 0, Errors 0.

## Follow-Ups

- Downstream embedders that maintain finalized transaction indexes should add
  application-level validation for already-confirmed tx ids across finalized
  history.
- Downstream embedders should add materializer preflight checks so application
  blocks are not persisted before duplicate/replay checks pass.
- Consider persistent or replayable Sigilaris proposal-index diagnostics if
  embedders need post-restart visibility into unfinalized tx exclusions.
