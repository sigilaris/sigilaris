# 0023 - Certified Ancestor Dependent Transaction Pipelining Plan

## Status
Release Build Prepared (v0.2.7) - Resolver Publish Pending

## Created
2026-06-22

## Last Updated
2026-06-22

## Revision Notes

- 2026-06-22: Completed Phase 0 policy and surface lock: ADR-0031 is Accepted,
  the first certified observation type is `CertifiedBlockObservation`, the
  initial surface is runtime diagnostics on `HotStuffNodeRuntime`, dependency
  reason codes are fixed, proposal input/validation require parent-branch
  context, and the low-latency profile labels distinguish block-round progress
  from client wall-clock latency.
- 2026-06-22: Completed Phase 1 certified observation runtime surface:
  in-memory HotStuff diagnostics now retain current and recent
  `CertifiedBlockObservation` values with local proposal/certification
  timestamps, including duplicate QC, out-of-order QC/body, missing body,
  invalid QC, and retention coverage.
- 2026-06-22: Completed Phase 2 dependency-aware proposal input: provider
  requests now include bounded certified parent-branch context, dependency-held
  reason codes are exercised through pacemaker diagnostics, and tests model a
  mint tx plus held escrow tx that becomes selectable only on an eligible
  certified parent branch after tx exclusions are applied.
- 2026-06-22: Completed Phase 3 dependency-aware vote validation: proposal
  validation requests now receive certified parent-branch context, stable vote
  dependency unavailable/conflict reason codes are exposed, and validation tests
  cover valid descendants, sibling branch rejection, and missing ancestry
  unavailable vote suppression.
- 2026-06-22: Completed Phase 4 event-driven wake-up and low-latency profile:
  runtime exposes application/certified-observation pacemaker wake-up methods,
  automatic pacemaker timeout policy is configurable with a non-default warm
  static-cluster profile, and low-latency progress drivers plus warm-readiness
  requirements are documented without changing production defaults.
- 2026-06-22: Completed Phase 5 end-to-end pipelining verification: automatic
  proposal input now has an integration test for `mint -> escrow` adjacent-child
  selection, certification, and dependent block finalization, plus a
  later-descendant branch-context proposal case and deterministic timing label
  verification without wall-clock CI gating.
- 2026-06-22: Completed Phase 6 documentation and release handoff: proposal
  provider handoff now documents dependent pipeline and certified observation
  boundaries, the low-latency profile documents operator requirements and
  residual risks, and draft v0.2.7 release notes summarize the certified
  ancestor pipelining contract.

## Background

Payment applications may require more than one on-chain transaction even when
the user experiences the action as one payment. A representative flow is:

1. debit fiat from a customer's bank balance into a deposit account;
2. mint an on-chain deposit-token UTXO to the customer's account;
3. spend that newly minted UTXO into a shop payment escrow.

The mint and escrow transactions have a hard dependency: the escrow tx input is
created by the mint tx. They cannot safely be put in the same block under a
UTXO model that requires outputs to exist in an ancestor block.

Waiting for the mint tx to finalize before submitting the escrow tx serializes
two finality paths and makes a sub-second payment target unrealistic. The
intended low-latency strategy is to let the second tx enter a dependency-held
pending state as soon as the mint output can be named, commonly after the mint
proposal is observed, then make it eligible only on a candidate branch whose
certified ancestry contains the mint block. The payment is complete only when
the escrow tx block is finalized.

The current HotStuff runtime already has proposal input/validation hooks,
unfinalized-ancestor tx-id uniqueness, finalized tx range observations, and
transitive artifact relay. It does not yet expose a first-class certified block
observation surface or a dependency-aware branch-local application state
contract.

## Goal

Enable embedders to pipeline dependent application transactions across adjacent
certified HotStuff blocks while preserving final settlement at the dependent
transaction's finality point.

The concrete target is:

- tx `T1` is included in block `B1`;
- dependent tx `T2` can be pre-admitted or submitted after `B1` is observed,
  but held until candidate branch eligibility is proven;
- `T2` can be selected only for proposals whose parent branch contains `B1`
  and whose justification/certified ancestry is sufficient for local
  validation;
- `T2` can be proposed in `B2`, a child or later descendant of `B1`, without
  waiting for `B1` finalization;
- the application reports final payment success only when `B2` is finalized;
- runtime diagnostics expose `admitted -> proposed -> certified -> finalized`
  timing separately enough to tune a warm-cluster 500ms on-chain budget for the
  two dependent txs.

## Scope

- Add or expose a certified proposal/block observation model in the JVM
  HotStuff runtime.
- Provide runtime diagnostics for certified observation latency and
  proposal-to-certification timing.
- Extend proposal input request context, if needed, so embedders can select
  branch-eligible dependent work for the candidate parent.
- Extend proposal validation handoff guidance and tests so embedders can reject
  or hold proposals whose branch-local dependencies are unavailable.
- Add low-latency wake-up and readiness work needed to make adjacent-block
  pipelining observable under a warm local/static cluster.
- Document or add dependency-held admission semantics at the runtime handoff
  boundary without standardizing application transaction bodies.
- Add tests and docs that model a mint-then-escrow dependency without moving
  UTXO semantics into Sigilaris.

## Non-Goals

- Do not implement bank debit, fiat custody, deposit-token issuance, or payment
  escrow application logic in Sigilaris.
- Do not standardize UTXO transaction bodies, output ids, spend rules, or
  intra-block execution order.
- Do not allow same-block dependent spends as a generic HotStuff feature.
- Do not report application payment success before the dependent escrow block
  is finalized.
- Do not replace ADR-0028's separation between consensus finalization and
  application materialization.
- Do not encode a hard 500ms SLA in consensus state. The target is a deployment
  and verification profile.
- Do not remove or weaken unfinalized-ancestor tx-id uniqueness from ADR-0029.

## Related ADRs And Docs

- [ADR-0017: HotStuff Consensus Without Threshold Signatures](../adr/0017-hotstuff-consensus-without-threshold-signatures.md)
- [ADR-0022: HotStuff Pacemaker And View-Change Baseline](../adr/0022-hotstuff-pacemaker-and-view-change-baseline.md)
- [ADR-0028: HotStuff Finalization Observability And Embedder Failure Semantics](../adr/0028-hotstuff-finalization-observability-and-embedder-failure-semantics.md)
- [ADR-0029: HotStuff Proposal Tx Uniqueness Policy](../adr/0029-hotstuff-proposal-tx-uniqueness-policy.md)
- [ADR-0030: HotStuff Transitive Artifact Relay](../adr/0030-hotstuff-transitive-artifact-relay.md)
- [ADR-0031: Certified Ancestor Dependent Transaction Pipelining](../adr/0031-certified-ancestor-dependent-transaction-pipelining.md)
- [HotStuff Proposal Provider Handoff](../dev/hotstuff-proposal-input-provider-handoff.md)
- [HotStuff Low-Latency Profile](../dev/hotstuff-low-latency-profile.md)
- [Draft v0.2.7 Release Notes](../dev/v0.2.7-release-notes.md)
- [0016 - HotStuff Application Proposal Input Hook Plan](0016-hotstuff-application-proposal-input-hook-plan.md)
- [0017 - HotStuff Application Proposal Validation Hook Plan](0017-hotstuff-application-proposal-validation-hook-plan.md)
- [0021 - HotStuff Proposal Tx Uniqueness Policy Plan](0021-hotstuff-proposal-tx-uniqueness-policy-plan.md)
- [0022 - HotStuff Transitive Artifact Relay Plan](0022-hotstuff-transitive-artifact-relay-plan.md)

## Decisions To Lock Before Implementation

1. **Certified observation definition**
   - `Certified` means a proposal/block has been observed with a locally
     verified quorum certificate for the proposal subject, commonly through a
     child proposal's `justify` QC.
   - `Proposed` alone is not sufficient for dependent tx proposal eligibility
     or final settlement.
   - `Proposed` may be sufficient for early client submission into an
     embedder-owned held pending queue when the dependent output id is already
     known.
   - `Finalized` remains the verified three-chain anchor from ADR-0028.

2. **Payment success boundary**
   - A dependent payment flow may continue after `T1` is certified.
   - The payment is externally complete only when `T2` is finalized.
   - Intermediate `T1 certified` status is a pipeline status, not a final
     settlement status.

3. **Branch-local dependency rule**
   - Dependent tx `T2` is eligible only on descendants of the certified block
     that created its input.
   - In the adjacent-child path, HotStuff already provides the certified-parent
     proof: a `B2` proposal directly after `B1` must carry a `justify` QC for
     `B1`. The new implementation work is to hold/select `T2` at the right
     time and validate that the received proposal's parent branch contains
     `B1`.
   - Later-descendant paths need an explicit local ancestry proof from the
     proposal parent chain back to the dependency block.
   - If the candidate parent chain does not contain the dependency block,
     proposal input must not select `T2`.
   - If `T2` was pre-admitted after a merely proposed `B1`, the provider must
     continue holding it until the target proposal parent branch proves the
     dependency.
   - If vote-time validation cannot prove the dependency from local ancestry,
     it must return unavailable and suppress the local vote.

4. **Sigilaris/application boundary**
   - Sigilaris owns certified/finalized branch observation and proposal ancestry
     metadata.
   - Embedders own UTXO semantics, spend validation, speculative state
     construction, and application materialization.
   - Sigilaris should provide enough branch context for embedders to do those
     checks without importing application transaction bodies into HotStuff
     consensus logic.

5. **Telemetry taxonomy**
   - Required timing dimensions:
     - application admission observed;
     - proposal emitted/accepted;
     - proposal certified;
     - dependent proposal emitted/accepted;
     - dependent proposal certified;
     - dependent proposal finalized;
     - application materialized, if the embedder reports it.
   - Runtime diagnostics should label certified observation separately from
     finalized observation.

6. **Low-latency profile**
   - A warm same-region/static-cluster profile may target two dependent txs
     finalized in under 500ms.
   - The reviewable budget is block-round based, not just wall-clock based. If
     `T1` is in `B1` and `T2` is first proposed in child `B2`, `T2` finality
     requires sequential progress through `B1`, `B2`, `B3`, and `B4`, where
     `B4` commits `B2` as the three-chain anchor.
   - The 500ms profile therefore assumes roughly 100ms-class proposal/QC rounds
     plus small materialization and observation headroom. This is aggressive and
     must be measured as a profile-specific target.
   - That profile requires event-driven pacemaker/proposer wake-up, peer
     transport settings compatible with sub-second progress, and readiness that
     excludes cold bootstrap paths.
   - The profile is not a safety requirement for all deployments.

## Phase 0 Locked Decisions

- ADR-0031 is Accepted for implementation after this lock.
- The certified runtime observation type is `CertifiedBlockObservation`.
- A retained certified observation records `chainId`, `proposalId`, `blockId`,
  `height`, `window`, `qcSubject`, `validatorSetHash`, `proposalObservedAt`,
  and `certifiedObservedAt`.
- The first implementation exposes current and recent certified observations
  through `HotStuffNodeRuntime` diagnostics only. Generic stream/callback,
  HTTP, or SSE projections remain follow-up work.
- `HotStuffProposalInputRequest` needs additional parent-branch context beyond
  the existing `parent`, `justify`, and `txExclusion` fields so embedders can
  prove dependency eligibility before returning branch-committed roots.
- `HotStuffProposalValidationRequest` needs parent-branch context for received
  proposals so validators can reject sibling dependency conflicts and return
  unavailable when ancestry is missing.
- Stable dependency reason codes:
  - `proposalInputDependencyHeld`;
  - `proposalInputDependencyAncestorUnavailable`;
  - `proposalInputDependencyBranchConflict`;
  - `proposalVoteDependencyAncestorUnavailable`;
  - `proposalVoteDependencyBranchConflict`.
- Low-latency profile labels:
  - `t1AdmittedToT1Certified`;
  - `t1CertifiedToT2Selected`;
  - `t2AdmittedToT2Certified`;
  - `t2CertifiedToT2Finalized`;
  - `t1AdmittedToT2Finalized`.
- Block-round timing is tracked separately from client wall-clock latency, with
  the adjacent-child critical path labelled `B1 -> B4`.

## Change Areas

### Code

- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff`
  - certified proposal/block observation model;
  - certified observation retention and diagnostics;
  - pacemaker/proposal drive wake-up for new application work;
  - proposal input request context additions if current parent/ancestor context
    is insufficient;
  - proposal validation diagnostics for dependency unavailable/rejected cases.
- Runtime bootstrap surfaces:
  - optional low-latency timing profile wiring;
  - readiness diagnostics that distinguish cold bootstrap from warm operation.
- Test utilities:
  - small fake embedder dependency model that represents `mint -> escrow`
    without standardizing UTXO semantics.

### Tests

- Certified observation unit tests for proposal/QC observation.
- Proposal input provider tests for branch-local dependency eligibility.
- Proposal validation provider tests for dependency rejection and unavailable
  ancestry.
- Pacemaker integration tests for adjacent-block dependent tx pipelining.
- Finalization integration tests proving that finalizing the dependent block
  also commits the required ancestor block on the same branch.
- Low-latency smoke or deterministic simulation that records the timing
  breakdown, with budget assertions kept profile-specific and non-flaky.

### Docs

- Add this plan and ADR-0031.
- Update HotStuff proposal provider handoff docs with dependency-aware
  selection guidance.
- Update finalization/observability docs or release notes with certified
  observation semantics after implementation.
- Add a low-latency profile note if runtime config defaults or recommended
  peer-loop settings change.

## Implementation Phases

### Phase 0: Policy And Surface Lock

- Promote or revise ADR-0031 after review.
- Do not promote ADR-0031 from Proposed to Accepted independently of this
  phase; the promotion should happen after certified observation fields,
  dependency reason codes, and profile measurement labels are locked.
- Name the certified observation types and diagnostic fields.
- Decide whether the certified observation surface is runtime-only first or
  also projected through a generic stream/callback.
- Decide whether `HotStuffProposalInputRequest` needs additional parent
  proposal/block fields beyond the existing request data.
- Decide the minimum branch context the validation provider receives for
  dependency checks.
- Define stable reason codes for dependency rejected/unavailable diagnostics.
- Lock the block-round timing budget and label it separately from wall-clock
  client-observed latency.

### Phase 1: Certified Observation Runtime Surface

- Track the first local time a proposal becomes certified.
- Expose current/recent certified observations through `HotStuffNodeRuntime`.
- Include chain id, proposal id, block id, height, window, QC subject, validator
  set hash, `proposalObservedAt`, and `certifiedObservedAt`.
- Add bounded retention so slower pollers can observe recent certification
  events without unbounded memory growth.
- Add tests for duplicate QC observations, out-of-order proposal/QC arrival,
  missing proposal bodies, and retention bounds.

### Phase 2: Dependency-Aware Proposal Input

- Extend the proposal input request context if needed.
- Add an embedder test provider that keeps a pending queue with:
  - an independent mint tx;
  - an escrow tx that requires the mint block on the candidate parent branch;
  - a held state for the escrow tx before branch eligibility is proven.
- Ensure the provider can pre-admit the escrow tx but selects it only for
  descendants of the mint block whose parent-chain certification is sufficient.
- Ensure tx-exclusion from ADR-0029 still applies before dependency selection
  returns provider input.
- Add diagnostics for dependency-held proposal input.

### Phase 3: Dependency-Aware Vote Validation

- Add or document validation-provider context needed to prove branch-local
  dependencies.
- Ensure a received proposal containing a dependent tx is locally voted only
  when its parent branch contains the dependency.
- Map missing ancestry to unavailable and suppress the vote.
- Map branch-local dependency conflict to rejection and suppress the vote.
- Add tests for sibling branch rejection, missing ancestor unavailable, and
  valid descendant acceptance.

### Phase 4: Event-Driven Wake-Up And Low-Latency Profile

- Audit where pacemaker progress is currently driven by source reads, peer
  polling, or fixed sleeps.
- Add an explicit wake-up path from application work admission/certified
  observation to proposer/pacemaker drive where safe.
- Make low-latency peer-loop and timeout settings configurable if they are not
  already.
- Define a warm-readiness check that confirms peer sessions, bootstrap seed,
  proposal/vote/QC transport, and finalization observation are live before the
  low-latency measurement starts.
- Add non-default low-latency profile docs. Do not change production defaults
  until the profile has stable test evidence.

### Phase 5: End-To-End Pipelining Verification

- Build an integration test or smoke harness with two dependent tx ids.
- Pre-admit `T2` after `T1` is proposed, or submit `T2` after `T1` is
  certified, depending on which mode the harness is measuring.
- Confirm `T2` remains held until a candidate parent branch contains the `T1`
  block with sufficient certified ancestry.
- Confirm `T2` is proposed only on a descendant of `T1`'s block.
- Confirm the adjacent-child case relies on `B2.justify` certifying `B1`, while
  later-descendant cases prove the dependency through local parent-chain
  ancestry.
- Confirm finalizing `T2` also finalizes the required `T1` ancestor.
- Record timing for:
  - `T1 admitted -> T1 certified`;
  - `T1 certified -> T2 admitted/selected`;
  - `T2 admitted -> T2 certified`;
  - `T2 certified -> T2 finalized`;
  - total `T1 admitted -> T2 finalized`.
- Keep the 500ms target as an observed low-latency profile gate, not a generic
  CI assertion, unless the harness is deterministic enough to avoid flakes.

### Phase 6: Documentation And Release Handoff

- Update handoff docs for proposal input, proposal validation, and finalization
  observability.
- Add release notes describing the certified observation surface and the
  dependent tx pipeline contract.
- Document residual risks and operator requirements for low-latency payment
  deployments.

## Test Plan

- Unit-test certified observation state transitions:
  - proposal before QC;
  - QC before proposal;
  - duplicate observation;
  - invalid QC rejection;
  - bounded recent observation retention.
- Unit-test dependency selection with fake application tx ids:
  - mint eligible without dependency;
  - escrow may be held after mint proposal observation;
  - escrow ineligible before branch eligibility is proven;
  - escrow eligible on a descendant of the mint block;
  - escrow ineligible on a sibling branch;
  - escrow held when ancestry is unavailable.
- Unit-test vote validation:
  - valid dependent proposal accepted;
  - sibling branch proposal rejected;
  - missing ancestry unavailable;
  - finalized boundary remains compatible with ADR-0029 tx uniqueness.
- Integration-test adjacent-block pipelining:
  - `B1` contains `T1`;
  - `B2` descends from `B1` and contains `T2`;
  - finality of `B2` implies the expected ancestor chain.
- Measure warm-cluster latency separately from correctness:
  - do not include node startup/bootstrap in the low-latency budget;
  - fail profile tests only when the environment is explicitly marked as
    deterministic enough for timing gates.

## Risks And Mitigations

- **Risk: Applications treat certification as final settlement.**
  - Mitigation: API/docs must label certified status as a pipeline trigger only;
    payment success remains dependent tx finalization.
- **Risk: Branch-local speculative state diverges between validators.**
  - Mitigation: validation providers must return unavailable when ancestry or
    dependency proof is missing; tests cover sibling and missing-ancestor cases.
- **Risk: Low-latency settings hurt liveness in slower deployments.**
  - Mitigation: ship as an explicit profile with diagnostics before changing
    defaults.
- **Risk: The 500ms budget is mistaken for a single timer setting.**
  - Mitigation: document and measure the `B1 -> B4` block-round path separately
    from client wall-clock latency; profile gates must expose per-round timing.
- **Risk: Certified observation retention grows without bound.**
  - Mitigation: use bounded recent observation history, mirroring existing
    finalization observation patterns.
- **Risk: Timing tests become flaky.**
  - Mitigation: keep correctness tests deterministic and separate profile
    measurements from mandatory generic CI gates.
- **Risk: Sigilaris accidentally standardizes UTXO semantics.**
  - Mitigation: use fake tx ids and dependency metadata in tests; keep actual
    output/spend semantics in embedder-owned fixtures.

## Acceptance Criteria

1. Sigilaris exposes a tested certified proposal/block observation surface that
   is distinct from finalized observation.
2. A fake embedder can model `mint -> escrow` dependency, hold the escrow tx
   before branch eligibility, and select it only for descendants of the mint
   block with sufficient certified ancestry.
3. Local vote validation suppresses dependent proposals whose branch-local
   dependency cannot be proven.
4. An integration test demonstrates adjacent-block pipelining and finalizes the
   dependent block.
5. Runtime diagnostics expose enough timestamps to compute
   `admitted -> certified -> dependent finalized` latency.
6. Documentation clearly states that certified ancestor observation is a
   pipeline trigger, not final settlement.
7. Low-latency profile guidance identifies warm-readiness and transport/pacemaker
   assumptions needed to target a 500ms two-tx on-chain budget, including the
   four-round `B1 -> B4` critical path when `T2` first appears in `B2`.

## Checklist

### Phase 0: Policy And Surface Lock
- [x] ADR-0031 reviewed and accepted or revised.
- [x] Certified observation type names and fields locked.
- [x] Dependency diagnostic reason codes locked.
- [x] Proposal input/validation context gaps identified.
- [x] Block-round latency budget and wall-clock labels locked.

### Phase 1: Certified Observation Runtime Surface
- [x] Certified observation tracker implemented.
- [x] Current/recent certified observations exposed from `HotStuffNodeRuntime`.
- [x] Certification observation tests added.

### Phase 2: Dependency-Aware Proposal Input
- [x] Proposal input context extended or documented as sufficient.
- [x] Fake dependent tx provider added to tests.
- [x] Held dependent tx admission modeled in tests.
- [x] Branch-local dependency selection tests added.

### Phase 3: Dependency-Aware Vote Validation
- [x] Validation-provider dependency context finalized.
- [x] Missing ancestry maps to unavailable.
- [x] Branch mismatch maps to rejection.
- [x] Vote validation dependency tests added.

### Phase 4: Event-Driven Wake-Up And Low-Latency Profile
- [x] Pacemaker/source-read/polling wake-up paths audited.
- [x] Admission/certification wake-up path implemented where safe.
- [x] Low-latency config/profile documented.
- [x] Warm-readiness checks defined.

### Phase 5: End-To-End Pipelining Verification
- [x] Adjacent-block `T1 -> T2` integration test added.
- [x] Adjacent-child justify-QC and later-descendant ancestry cases covered.
- [x] Dependent block finalization proves ancestor chain finalization.
- [x] Timing breakdown calculated in deterministic integration test.
- [x] 500ms wall-clock gate kept profile-specific and out of generic CI.

### Phase 6: Documentation And Release Handoff
- [x] Proposal provider handoff doc updated.
- [x] Finalization/certification observability docs updated.
- [x] Release notes drafted.
- [x] Residual risks documented.

## Follow-Ups

- Add an application-facing HTTP/SSE projection for certified observations after
  the runtime diagnostic shape stabilizes.
- Define optional metrics names for certified latency and dependent tx pipeline
  latency.
- Evaluate whether a deterministic simulator can enforce the 500ms profile
  budget in CI without relying on wall-clock cluster timing.
- Coordinate with BBGO application docs for UTXO-specific pending admission,
  speculative branch state, and payment escrow status semantics.
