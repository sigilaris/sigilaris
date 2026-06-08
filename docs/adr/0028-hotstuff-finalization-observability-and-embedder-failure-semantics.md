# ADR-0028: HotStuff Finalization Observability And Embedder Failure Semantics

## Status
Accepted

Promoted from `Proposed` to `Accepted` in the v0.2.3 release commit after Phase 4 verification confirmed the contract is backed by tests (Plan 0018 Phase 0 release-candidate decision).

## Context
- ADR-0017 defines the HotStuff proposal / vote / QC baseline, and ADR-0022 defines the pacemaker / view-change ownership boundary.
- The current JVM runtime detects three-chain finalization from observed proposals and stores an in-memory `FinalizationTrackerSnapshot` containing `bestFinalized` and safety faults.
- ADR-0021 uses best finalized suggestions as the anchor source for snapshot sync, forward catch-up, and historical backfill, so the finalized-anchor observation contract affects bootstrap and recovery diagnostics as well as steady-state consensus.
- Bootstrap diagnostics can expose best finalized anchors and finalization safety faults, but there is no single ADR that defines:
  - what "finalized" means at the library contract boundary;
  - which timestamps can be used for finalization latency;
  - which failures are consensus-owned versus embedder materialization failures;
  - what an embedder should expect when application materialization fails after consensus has finalized an anchor.
- Downstream runtimes such as bbgo need finalization observability to size timeout/retry/sweeper policies, but application transaction submission and application materialization are outside the core HotStuff consensus runtime.

## Decision
1. **Consensus finalization and application materialization are separate domains.**
   - Sigilaris HotStuff owns consensus finalization: detecting and verifying a finalized anchor from valid proposal/QC chains.
   - The embedding application owns application materialization: applying finalized application payloads to its own durable state, read model, API status, streams, and recovery policy.
   - A finalized HotStuff anchor is not automatically an application-level "materialized" or "served" state.

2. **The canonical consensus finalized anchor is a verified three-chain anchor.**
   - `FinalizedAnchorSuggestion` represents an anchor proposal plus child and grandchild proposals that prove finalization.
   - In this ADR, "three-chain" means an anchor proposal justified by a child proposal that is itself justified by a grandchild proposal, with strictly increasing block heights and valid QCs along the chain.
   - `HotStuffFinalizedAnchorVerifier` validates the anchor, child, grandchild, and their QCs against the applicable validator sets.
   - A verified suggestion may become the current best finalized anchor for its chain unless a safety fault blocks that height.

3. **Finalization observability should identify the anchor, proof, and observation time.**
   - A v0.2.3 finalization observation surface should include at least:
     - `chainId`;
     - finalized anchor `proposalId`;
     - finalized anchor `blockId`;
     - finalized anchor height;
     - child and grandchild proposal ids used as the proof;
     - the anchor proposal window validator-set hash (`suggestion.proposal.window.validatorSetHash`);
     - `finalizedObservedAt`, the runtime time at which the finalized anchor was first observed as finalized by the local runtime.
   - If later diagnostics include child, grandchild, or QC-subject validator-set hashes, those fields should be named as additional proof-window hashes rather than replacing the anchor proposal window hash.
   - `finalizedObservedAt` is an observation timestamp, not a claim about when the quorum objectively finalized in the network.

4. **Consensus finalization latency should be computed from proposal observation to finalized-anchor observation.**
   - The baseline library metric should be computed from:
     - `proposalObservedAt`: when the local runtime first accepted or emitted the finalized anchor proposal artifact;
     - `finalizedObservedAt`: when the local runtime first observed the verified finalized anchor;
     - `latency = finalizedObservedAt - proposalObservedAt`.
   - v0.2.3 exposes both `proposalObservedAt` and `finalizedObservedAt` as `FinalizedAnchorObservation` fields (their difference is the consensus finalization latency) via `HotStuffNodeRuntime.currentFinalizationObservations`, the runtime diagnostic snapshot locked by Plan 0018 Phase 0 and backed by an internal bounded first-observation history.
   - Application-level submission-to-finalized latency is embedder-owned because application submission/admission may happen before Sigilaris sees a proposal.
   - Application-level materialization latency is embedder-owned; v0.2.3 provides no Sigilaris materialization-timestamp hook, so materialization-latency reporting stays embedder-owned (any such hook is deferred to a future ADR).

5. **Finalization absence is not a failure by itself.**
   - A chain with no `bestFinalized` may be bootstrapping, waiting for proposals, partitioned, stalled, or simply not advanced enough for a three-chain proof.
   - The runtime should not turn "no finalized anchor yet" into a fatal failure without a separate policy threshold owned by pacemaker, bootstrap, or the embedder.

6. **Finalization safety faults are consensus-owned high-severity diagnostics.**
   - Conflicting finalized anchors at the same height are represented by `FinalizedAnchorSafetyFault`.
   - A safety fault prevents that height from being selected as best finalized by the local tracker.
   - Operators and embedders should treat safety faults as severe consensus diagnostics requiring explicit operator action.

7. **Verification failures are consensus-owned diagnostics, not application materialization failures.**
   - Invalid finalization suggestions, invalid QCs, validator-set lookup failures, or proof-shape mismatches are HotStuff verification failures.
   - These failures should be surfaced through structured `reason` / `detail` diagnostics and should not be collapsed into application reducer failures.

8. **Application materialization failures do not roll back consensus finalization.**
   - Once the HotStuff runtime has verified a finalized anchor, an embedder-side materialization failure does not make the consensus anchor unfinalized.
   - The embedder must decide whether to retry, mark application state as stuck, expose a degraded status, alert operators, or rebuild from archive.
   - Sigilaris may provide a hook or diagnostic carrier for materialization outcomes, but the retry budget and terminal failure policy are embedder-owned unless a later ADR explicitly moves them into Sigilaris.
   - This embedder materialization boundary is distinct from existing HotStuff bootstrap/catch-up materialization helpers such as `ForwardCatchUpMaterialization`.

9. **`FailedPermanently` is not a core HotStuff finalization state unless Sigilaris owns the retry policy.**
   - A permanent materialization failure after N retries is an embedder policy if the embedder owns N and the retry schedule.
   - Sigilaris should not emit a normative `FailedPermanently` consensus state for application materialization failures it does not retry.
   - v0.2.3 adds no materialization observation hook; if a future release adds one, it may carry embedder-reported terminal outcomes, but those outcomes must be labeled as embedder materialization diagnostics.

10. **Pacemaker timing values are not finalization SLAs.**
    - Proposal interval, view timeout, view-change backoff, and transport liveness each belong to distinct timing domains described by ADR-0022.
    - Exposing those policy values is useful for operators, but they do not by themselves provide a hard upper bound for application transaction finalization.
    - Finalization SLOs should be based on observed finalization metrics plus deployment assumptions.

11. **v0.2.3 should add diagnostics before external protocol commitments.**
    - The release should prefer a runtime diagnostic snapshot and test-visible observation records over an HTTP/SSE/exporter-specific contract.
    - Exporters, metrics backends, and application APIs can project those records later without changing the core finalization semantics.

## Consequences
- Embedders get a clean contract boundary: HotStuff finalization is consensus-owned; materialization/retry/API status is embedder-owned.
- Finalization latency can be measured consistently at the consensus layer, while application submission-to-finalized latency remains outside Sigilaris unless the embedder provides admission timestamps.
- Safety faults and verification failures remain distinguishable from reducer/materialization failures.
- v0.2.3 can improve observability without forcing a particular metrics backend or public API shape.
- Downstream systems still need their own stuck-materialization and application read-model recovery policies.

## Rejected Alternatives
1. **Treat application materialization failure as consensus unfinalization**
   - Consensus finality and application apply are different domains.
   - Rolling back the finality claim would make the consensus state depend on application reducer health.

2. **Use pacemaker timeout constants as transaction finalization guarantees**
   - View-change timers are liveness controls, not end-to-end application latency bounds.
   - Real finalization latency depends on proposal input, networking, quorum behavior, bootstrap state, and embedder materialization.

3. **Publish only logs for finalization observability**
   - Logs help operators but are weak as a machine-readable contract.
   - Runtime diagnostics and test-visible observation records provide a stronger baseline.

4. **Make Sigilaris own embedder retry budgets by default**
   - Retry thresholds depend on application state, storage, archive, and API semantics.
   - Embedders should own the policy unless a future runtime integration explicitly standardizes it.

## Follow-Up
- Plan 0018 is the v0.2.3 implementation plan for finalization observation diagnostics and tests.
- The v0.2.3 finalization observation exposure shape is locked by Plan 0018 Phase 0: a runtime diagnostic snapshot exposed through `HotStuffNodeRuntime.currentFinalizationObservations`, backed by an internal bounded first-observation history. An embedder-provided observation sink/callback is deferred to a follow-up.
- If embedders need stable metrics names, add a metrics/exporter doc after the diagnostic model lands.
- If Sigilaris later standardizes embedder materialization hooks, write a separate ADR or amend this one with explicit ownership and retry semantics.

## References
- [ADR-0017: HotStuff Consensus Without Threshold Signatures](0017-hotstuff-consensus-without-threshold-signatures.md)
- [ADR-0021: Snapshot Sync And Background Backfill From Best Finalized Suggestions](0021-snapshot-sync-and-background-backfill-from-best-finalized-suggestions.md)
- [ADR-0022: HotStuff Pacemaker And View-Change Baseline](0022-hotstuff-pacemaker-and-view-change-baseline.md)
- [0018 - v0.2.3 Signature And Finalization Contract Release Plan](../plans/0018-v0-2-3-signature-and-finalization-contract-release-plan.md)
