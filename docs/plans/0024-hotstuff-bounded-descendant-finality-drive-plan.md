# 0024 - HotStuff Bounded Descendant Finality Drive Plan

## Status
Complete

## Created
2026-06-23

## Last Updated
2026-06-24

## Progress

- Phase 0 implementation added local descendant-finality timing diagnostics,
  provider/proposal/vote timeline diagnostics, and a default-disabled
  `HotStuffFinalityDrivePolicy` with consensus-depth floor validation.
- The Sigilaris-side diagnostic gate is covered by deterministic HotStuff
  policy, finalization observation, and proposal-input pacemaker integration
  tests. That gate did not reject the missed-opportunity hypothesis. Phase 4
  now documents the exact BBGO fields and config to consume; BBGO still needs
  to rerun its local smoke outside this Sigilaris plan.
- Phase 1 wired the default-disabled finality-drive policy through runtime and
  bootstrap construction, added provider-visible bounded descendant-drive hints
  to proposal-input requests, reused the application/certified-observation wake
  path for eligible leaders, and covered per-anchor bounds with deterministic
  pacemaker integration tests.
- Phase 2 added derived finalization-progress metadata to proposal-input
  requests and finality-drive diagnostics for request, provider result, bound
  suppression, and finalized-target observations. Phase 0 timing diagnostics
  already expose proposal, vote, certification, and finalization timestamps.
- Phase 3 added a deterministic low-latency dependent-path integration
  regression that compares drive-disabled quiescence with bounded drive,
  rotates leaders across `T1`, `T2`, child, and grandchild views, and verifies
  that only the required empty descendants are emitted before the runtime
  returns to idle after finalization.
- Phase 4 documented the opt-in low-latency finality-drive profile, provider
  responsibilities for empty descendant safety and materialization boundaries,
  v0.2.8 draft release notes, and the BBGO dependent-payment config and
  diagnostic fields to consume.

## Background

Plan 0023 shipped the certified ancestor dependent transaction pipeline in
Sigilaris v0.2.7. It gave embedders the core safety surface needed for
dependent transactions:

- certified block observations;
- parent-branch proposal input context;
- parent-branch proposal validation context;
- application and certified-observation wake hooks;
- a warm static-cluster low-latency pacemaker profile.

Terminology in this plan:

- **100ms cadence** means the target warm-cluster proposal/QC round spacing.
- **500ms budget** means the embedder's end-to-end dependent payment target
  from `T1` submission to `T2` finalization.

BBGO integrated that surface for a local four-validator dependent payment
smoke where:

- `T1 = MintTokenWithSource`;
- `T2 = CreatePaymentEscrow`;
- `T2` spends the UTXO created by `T1`;
- success is measured from `T1` submit to `T2` finalized.

Correctness now passes, but the 500ms local low-latency gate is not stable. The
latest BBGO retained-code smoke run, `20260623T142216Z`, passed correctness but
reported:

- `T1 submit -> T1 proposed`: 196ms;
- `T1 proposed -> T1 certified`: 16ms;
- `T2 proposed -> T2 certified`: 194ms;
- `T2 certified -> T2 finalized`: 368ms;
- `T1 submit -> T2 finalized`: 938ms.

The corrected BBGO timing now uses Sigilaris `CertifiedBlockObservation`
`proposalObservedAt` rather than subtracting BBGO batch
`certificationLatencyMillis`, which was a batch received-to-certified value.
That correction strongly suggests the remaining issue is not application
admission or reducer preview. However, the available BBGO data does not yet
prove that the `T2 certified -> T2 finalized` leg is idle waiting rather than
ordinary round-trip work. This plan therefore treats "missed descendant proposal
opportunity" as a hypothesis that must be confirmed by timestamp diagnostics
before changing the runtime mechanism.

The underlying tension is quiescence versus finalization. Sigilaris intentionally
lets an application provider return `NoWork` so an idle chain does not produce
unbounded empty proposals. Standard HotStuff finalization of a tx-bearing block,
however, still needs descendant proposals. The intended runtime delta is:
temporarily relax quiescence when there is a certified-but-unfinalized
tx-bearing ancestor, allow only the empty descendants needed to finalize that
ancestor, then return to quiescence once the target finalizes or bounds are
exhausted. Existing wake hooks can notify the pacemaker; the missing contract is
the bounded willingness to produce finality descendants when the provider would
otherwise report no ordinary work.

The 100ms warm-cluster target is still feasible in principle. A dependent
payment that places `T1` in `B1` and `T2` in child `B2` needs sequential progress
through `B1`, `B2`, `B3`, and `B4`, where `B4` finalizes `B2`. Four
100ms-class rounds leave only a small headroom budget, so the runtime must avoid
missing productive proposal opportunities after application work or certified
observations arrive. Simply lowering the base timeout is not the right contract:
BBGO experiments with 50ms did not improve the end-to-end target, and 25ms made
the dependent path fail correctness by outrunning dependency convergence.

## Goal

First prove whether the observed dependent-payment latency is caused by missed
descendant proposal opportunities or by unavoidable vote/QC/finalization round
work. If the missed-opportunity hypothesis is confirmed, make the 100ms warm
static-cluster profile capable of stable dependent transaction finality by
driving only the bounded descendant proposals needed to finalize tx-bearing
certified branches.

The target behavior is:

1. when a certified block with application work is observed, the runtime can
   promptly drive descendant proposals needed for finalization;
2. the drive is bounded and stops once the tx-bearing ancestor is finalized or
   the configured depth/budget is exhausted;
3. embedders keep ownership of whether empty application input is safe for the
   branch;
4. diagnostics distinguish proposal emission, proposal observation, vote
   emission, QC/certification observation, and finalization observation.

## Scope

- Add diagnostics needed to prove or reject the missed descendant proposal
  hypothesis before changing liveness behavior.
- Add a Sigilaris-owned bounded finality-drive policy for certified
  tx-bearing branches.
- Strengthen event-driven wake semantics so application work and certified
  observations can reach the next eligible leader and retry proposal input
  without waiting for a full timeout tick.
- Extend branch/proposal input diagnostics so embedders can tell whether a
  request is attempting normal application work, dependency selection, or
  descendant finality drive.
- Expose enough timing diagnostics to separate:
  - local proposal emitted/observed;
  - local vote emitted;
  - QC/certification observed;
  - finalization observed.
- Add integration tests that model a dependent `T1 -> T2` path under 100ms-class
  policy without encoding application UTXO semantics in Sigilaris.
- Update low-latency profile documentation to state that 100ms can be
  sufficient only when bounded descendant drive is active and the cluster is
  warm.

## Non-Goals

- Do not lower the default HotStuff pacemaker timeout.
- Do not make 50ms or 25ms the recommended dependent-payment fix.
- Do not encode BBGO payment, escrow, UTXO, or reducer semantics in Sigilaris.
- Do not let Sigilaris finalize or report application payment success.
- Do not allow unbounded empty proposal chains as a low-latency mechanism.
- Do not weaken HotStuff safety, QC validation, vote validation, branch context
  validation, or unfinalized-ancestor tx uniqueness.
- Do not make the 500ms dependent payment budget a universal production SLA.

## Related ADRs And Docs

- [ADR-0017: HotStuff Consensus Without Threshold Signatures](../adr/0017-hotstuff-consensus-without-threshold-signatures.md)
- [ADR-0022: HotStuff Pacemaker And View-Change Baseline](../adr/0022-hotstuff-pacemaker-and-view-change-baseline.md)
- [ADR-0028: HotStuff Finalization Observability And Embedder Failure Semantics](../adr/0028-hotstuff-finalization-observability-and-embedder-failure-semantics.md)
- [ADR-0029: HotStuff Proposal Tx Uniqueness Policy](../adr/0029-hotstuff-proposal-tx-uniqueness-policy.md)
- [ADR-0031: Certified Ancestor Dependent Transaction Pipelining](../adr/0031-certified-ancestor-dependent-transaction-pipelining.md)
- [HotStuff Proposal Provider Handoff](../dev/hotstuff-proposal-input-provider-handoff.md)
- [HotStuff Low-Latency Profile](../dev/hotstuff-low-latency-profile.md)
- [v0.2.7 Release Notes](../dev/v0.2.7-release-notes.md)
- [0023 - Certified Ancestor Dependent Transaction Pipelining Plan](0023-certified-ancestor-dependent-transaction-pipelining-plan.md)
- BBGO [0020 - Local Four-Node Dependent Payment Latency Progress](../../../bbgo3/docs/plans/0020-local-four-node-dependent-payment-latency-progress.md)

## Decisions To Lock Before Implementation

1. **100ms remains the target cadence, not the proof**
   - The follow-up should preserve the 100ms-class warm profile as the primary
     target. The existing data does not prove that 100ms rounds are
     mathematically insufficient.
   - The existing data also does not yet prove that the missed portion is idle
     wait rather than real round work. Phase 0 must close that diagnostic gap
     before Phase 1 changes liveness behavior.
   - If Phase 0 shows the gap is genuine vote/QC/finalization round work rather
     than idle or quiescence delay, this plan stops before Phase 1 and pivots to
     another plan, such as pipelining-depth reduction or round-trip reduction,
     instead of shipping bounded drive.
   - Lower timeout experiments can remain local diagnostics, but the planned
     product surface is bounded finality drive plus stronger wake behavior.

2. **Quiescence is temporarily relaxed only for finality**
   - The core delta is not another generic wake hook. Wake hooks already exist
     from Plan 0023.
   - The missing contract is that a certified-but-unfinalized tx-bearing
     ancestor can temporarily override idle-chain quiescence and permit bounded
     empty descendants needed for finality.
   - When the target finalizes or bounds are exhausted, the runtime returns to
     normal quiescent behavior.

3. **Finality drive is liveness-only**
   - The drive may cause a validator to ask the provider for input sooner.
   - It must not alter vote safety, QC acceptance, finalization rules, or
     branch ancestry validation.
   - If the provider returns no input or validation is unavailable, the runtime
     falls back to normal pacemaker behavior.

4. **Bounded drive ownership**
   - Sigilaris owns the generic observation that a certified branch contains
     application work and is not finalized yet.
   - Embedders own whether it is safe to emit an empty application proposal for
     the candidate branch.
   - The first implementation should allow the provider to return an explicit
     empty descendant-drive input, and the runtime should bound how often it
     will request or accept that drive for the same anchor.
   - Runtime bounds apply even if the provider keeps eagerly returning empty
     drive input.

5. **Stopping condition**
   - Drive stops when the target certified block is finalized.
   - Drive also stops when the configured maximum descendant depth, attempt
     count, or elapsed budget is exhausted.
   - The configured maximum descendant depth must be at least the consensus
     finalization depth needed to finalize the target anchor. Settings below
     that floor must be rejected during config validation or disable the policy
     with an explicit diagnostic.
   - A default safe profile should not drive empty-only chains forever after
     all tx-bearing ancestors are finalized.

6. **Leader rotation reachability**
   - This is not a special routing requirement beyond normal certified
     artifact dissemination.
   - Each successive leader must independently evaluate and honor the
     finality-drive policy for its own view, not rely on the node that observed
     the certified block.
   - In a four-validator round-robin, `B1`, `B2`, `B3`, and `B4` may have four
     different leaders. The policy is useful only if each eligible leader is
     awake and willing to request bounded finality-drive input for its view.
   - Integration tests must rotate leaders rather than accidentally exercising a
     single favorable local leader.

7. **Diagnostics are required**
   - Operators need to know whether latency was spent waiting for:
     - work admission wake;
     - leader proposal activation;
     - provider input;
     - vote validation;
     - QC/certification;
     - descendant finalization.
   - Existing `proposalObservedAt` and `certifiedObservedAt` remain useful but
     are not enough to explain missed proposal opportunities.
   - The diagnostic scope is local-runtime monotonicity. Cross-node end-to-end
     legs, such as BBGO submit node to finalization observation node, still
     depend on wall-clock assumptions and must be labeled as such by embedders.

8. **Application compatibility**
   - Existing applications that use `legacyCompatible` proposal input or do not
     opt into low-latency drive must keep current behavior.
   - The new policy should be opt-in through runtime/bootstrap configuration.

## Change Areas

### Code

- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff`
  - pacemaker runtime wake path;
  - automatic proposal activation;
  - low-latency pacemaker policy wiring;
  - certified/finalized observation sink;
  - proposal input request/diagnostic models;
  - runtime bootstrap config.
- Potential new or extended types:
  - `HotStuffFinalityDrivePolicy`;
  - `HotStuffFinalityDriveDiagnostic`;
  - branch-context finalization progress metadata;
  - proposal input reason/hint for descendant finality drive.

### Tests

- Unit tests for finality-drive policy bounds and stopping conditions.
- Pacemaker tests proving a certified tx-bearing branch wakes the proposal path
  without waiting for a full timeout tick.
- Integration tests for:
  - `T1` certified;
  - `T2` selected on an eligible descendant;
  - bounded empty descendants finalizing `T2`;
  - no runaway empty chain after the target finalizes.
- Diagnostics tests proving proposal/vote/QC/finalization timestamps are
  available and monotonic within a local runtime.

### Docs

- Update [HotStuff Low-Latency Profile](../dev/hotstuff-low-latency-profile.md).
- Update [HotStuff Proposal Provider Handoff](../dev/hotstuff-proposal-input-provider-handoff.md).
- Add release notes once the implementation target version is selected.
- Consider an ADR if the finality-drive policy becomes a durable runtime
  contract rather than an experimental low-latency profile extension.

## Implementation Phases

### Phase 0: Diagnostic Gate And Contract Lock

- Add or expose enough local timestamps to determine whether the observed
  descendant gap is:
  - idle wait before proposal activation;
  - provider `NoWork` quiescence;
  - vote validation delay;
  - QC/certification work;
  - finalization observation delay.
- Use those diagnostics to prove or reject the missed-opportunity hypothesis on
  the BBGO-observed shape before changing runtime liveness behavior.
- If the hypothesis is rejected, stop this plan before Phase 1 and open a new
  plan for the measured bottleneck class.
- Define the exact finality-drive trigger:
  - certified block has non-empty application tx set;
  - block is not finalized;
  - local runtime has enough branch context to attempt descendants.
- Define the exact stopping rules:
  - finalized target;
  - minimum descendant depth equal to the consensus finalization depth;
  - max descendant depth;
  - max attempts per anchor;
  - max elapsed wall-clock budget.
- Reject or explicitly disable configurations whose maximum descendant depth is
  below the consensus finalization-depth floor.
- Decide whether the runtime can infer "tx-bearing" from proposal tx-set size
  only, or whether embedders must return an explicit drive hint.
- Lock the first public/diagnostic type names and config keys.

### Phase 1: Runtime Wake And Drive Policy

- Add `HotStuffFinalityDrivePolicy` with disabled default and a warm-profile
  preset.
- Wire certified-observation and application-work wake paths so each
  successive eligible leader independently evaluates the drive policy and can
  schedule an immediate proposal-input retry for its view.
- Make the policy's main semantic delta explicit: when an eligible leader sees a
  certified-but-unfinalized tx-bearing ancestor, it may ask the provider for
  bounded finality-drive input even when ordinary work would otherwise be
  quiescent.
- Track active drive anchors and enforce bounds.
- Preserve the existing one-proposal-per-window emission rule.

### Phase 2: Provider Context And Diagnostics

- Extend proposal input request context with finalization progress metadata for
  the candidate parent branch.
- Add a provider-visible reason/hint for descendant finality drive.
- Record diagnostics for:
  - drive requested;
  - provider supplied input;
  - provider returned no work;
  - drive suppressed by bounds;
  - target finalized.
- Expose proposal/vote/QC timing fields needed by embedders to diagnose
  latency without deriving proposal time from application batch timestamps.

### Phase 3: Integration Tests

- Extend the existing dependent proposal input pacemaker integration suite with
  a 100ms-class bounded finality-drive scenario.
- Use deterministic test control where possible, such as simulated clocks,
  explicit pacemaker steps, or bounded logical event progression. Wall-clock
  assertions should stay out of CI-critical tests unless the margin is large and
  the test is quarantined as a smoke/profile check.
- Rotate leaders across the `T1`, `T2`, child, and grandchild views so the test
  proves each next leader evaluates the policy rather than a single-node happy
  path.
- Add a regression for the BBGO-observed shape:
  - `T2` certified;
  - descendant proposal gap would exceed the target without drive;
  - finality drive emits only the required descendants.
- Add a no-runaway regression:
  - idle warm cluster after finalization does not continue producing
    empty-only descendant chains beyond configured bounds.

### Phase 4: Documentation And Release Handoff

- Document the new profile and the limits of the 500ms budget.
- Document embedder responsibilities:
  - application-owned empty proposal safety;
  - materialization timing;
  - payment success boundary.
- Update release notes and migration notes for the selected version.
- Hand off to BBGO with the exact config and diagnostic fields to consume.

## Test Plan

- First add focused diagnostics tests that reproduce the BBGO-observed timing
  shape and classify the `T2 certified -> finalized` leg before enabling a new
  drive mechanism.
- `sbt "nodeJvm/testOnly *HotStuffPacemakerRuntimeSuite *HotStuffProposalInputPacemakerIntegrationSuite"`
  covers policy and dependent pipeline behavior.
- `sbt "nodeJvm/testOnly *HotStuffFinalizationObservationSuite *HotStuffFinalizationSuite"`
  covers finalization and observation regressions.
- `sbt "nodeJvm/testOnly *HotStuffProposalValidationPacemakerIntegrationSuite"`
  covers dependency validation compatibility.
- BBGO should rerun:
  - `sbt "node / Test / compile"`;
  - `sbt "node / assembly"`;
  - `scripts/local-four-node-consensus-smoke.sh --skip-assembly --scenario dependent-payment --low-latency-profile --low-latency-target-millis 500 --readiness-timeout 120`.

## Risks And Mitigations

- **Runaway empty proposals**
  - Mitigation: opt-in policy, runtime-enforced per-anchor bounds, and
    stop-on-finalized target, regardless of provider eagerness.
- **Wrong mechanism for the measured bottleneck**
  - Mitigation: Phase 0 must prove the descendant gap is recoverable idle or
    quiescence delay before Phase 1 enables bounded drive. If it does not, this
    plan stops and pivots.
- **Depth bound below finalization depth**
  - Mitigation: validate that the configured maximum descendant depth is at
    least the consensus finalization depth required for the target anchor.
- **Driving branches the application considers unsafe**
  - Mitigation: provider remains responsible for supplying empty drive input;
    no runtime-forced application block body.
- **Timeout reduction hides the actual bottleneck**
  - Mitigation: keep 100ms target cadence, add timing diagnostics, and avoid
    recommending 50ms/25ms as the product fix.
- **Wake storms under heavy certification churn**
  - Mitigation: coalesce wake signals per chain/window and enforce attempt
    limits.
- **Diagnostic timestamp ambiguity**
  - Mitigation: name each timestamp by event source and avoid reusing
    application batch latency as proposal latency.
- **False-positive integration coverage under leader rotation**
  - Mitigation: tests must rotate leaders across the dependent block and its
    finality descendants.
- **CI flakiness from wall-clock timing**
  - Mitigation: use simulated/logical timing for correctness tests and keep
    wall-clock latency gates in profile smoke tests.

## Acceptance Criteria

1. Diagnostics can classify the BBGO-observed latency shape and distinguish
   idle/quiescence delay from proposal/vote/QC/finalization round work.
2. If the missed-opportunity hypothesis is confirmed, a warm four-validator
   Sigilaris integration test can drive a `T1 -> T2` dependent path with `T2`
   finalized through bounded descendants under the 100ms-class profile, with
   leaders rotating across the relevant views.
3. If the missed-opportunity hypothesis is confirmed, the runtime does not keep
   emitting empty-only descendants after the tx-bearing target has finalized or
   bounds are exhausted.
4. Diagnostics expose enough timestamps to explain whether latency was spent in
   proposal activation, provider input, vote/QC, or finalization observation.
5. Existing default-profile behavior remains unchanged unless the new policy is
   explicitly enabled.
6. If the missed-opportunity hypothesis is confirmed, BBGO can consume the new
   policy/diagnostics and rerun the dependent-payment smoke without custom
   50ms/25ms pacemaker experiments.

## Checklist

### Phase 0: Diagnostic Gate And Contract Lock

- [x] Add diagnostics that classify descendant latency into idle, provider,
      vote/QC, and finalization observation buckets.
- [x] Prove or reject the missed-opportunity hypothesis on a BBGO-shaped
      dependent path before enabling bounded drive.
- [x] Evaluate the stop condition; the Sigilaris-side diagnostic gate did not
      reject the hypothesis, so no replacement plan was opened.
- [x] Lock finality-drive trigger and stopping rules.
- [x] Reject or disable depth bounds below the consensus finalization-depth
      floor.
- [x] Decide runtime-inferred tx-bearing versus provider-declared drive hint.
- [x] Lock config/type names and default-disabled behavior.

### Phase 1: Runtime Wake And Drive Policy

- [x] Add finality-drive policy model and warm-profile preset.
- [x] Coalesce certified-observation/application-work wake signals.
- [x] Ensure each successive eligible leader evaluates the drive policy for its
      view.
- [x] Enforce one-proposal-per-window and per-anchor drive bounds.

### Phase 2: Provider Context And Diagnostics

- [x] Add finalization progress metadata to proposal input context.
- [x] Add provider-visible descendant-drive hint/reason.
- [x] Add proposal/vote/QC/finalization timing diagnostics.

### Phase 3: Integration Tests

- [x] Add dependent path finality-drive integration test.
- [x] Rotate leaders across `T1`, `T2`, child, and grandchild views.
- [x] Use simulated/logical timing for CI-critical latency assertions.
- [x] Add no-runaway empty descendant regression.
- [x] Add diagnostics monotonicity and source-label tests.

### Phase 4: Documentation And Release Handoff

- [x] Update low-latency profile documentation.
- [x] Update proposal provider handoff documentation.
- [x] Add release notes for the selected Sigilaris version.
- [x] Hand off config and diagnostic consumption notes to BBGO.

## Follow-Ups

- Decide whether bounded finality drive should be promoted to an ADR after the
  first implementation validates the runtime contract.
- Consider a generic runtime stream/callback for certified and finalized
  observations if embedders keep polling too frequently.
- Revisit local smoke profiles after BBGO confirms whether the 500ms dependent
  payment gate is stable with 100ms cadence and bounded descendant drive.
