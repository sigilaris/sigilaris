# 0018 - v0.2.3 Signature And Finalization Contract Release Plan

## Status
Completed

## Created
2026-06-06

## Last Updated
2026-06-07

## Background
- `build.sbt` already sets `ThisBuild / version := "0.2.3-SNAPSHOT"`.
- v0.2.1 added the HotStuff application proposal input provider, and v0.2.2 added the proposal validation provider.
- Downstream embedders now need two contracts to be explicit before they can safely tune recovery and signing policies:
  - account-key binding guarantees for `SignatureVerifier`;
  - HotStuff finalization observability and the boundary between consensus finalization and embedder materialization failures.
- These are long-lived contracts, so ADRs should be locked before implementation details are finalized.

## Goal
- Prepare `v0.2.3` as a contract-hardening release.
- Make `SignatureVerifier` guarantees explicit and test-covered.
- Add or tighten HotStuff finalization diagnostics so embedders can measure consensus-layer finalization latency and distinguish it from application materialization.
- Produce release notes and verification gates that are ready to fill with final test results before publishing `0.2.3`.

## Scope
- Core signature verifier contract documentation and focused regression tests.
- Node JVM HotStuff finalization observability diagnostics, or the minimal runtime hooks needed to expose the observations described by ADR-0028.
- Documentation updates:
  - ADR-0027;
  - ADR-0028;
  - v0.2.3 release notes.
- Release readiness checks for the existing Maven coordinates.

## Non-Goals
- Embedder HTTP errorKey or SSE/API contract changes.
- Application-specific materialization retry policy.
- A public metrics exporter, HTTP endpoint, Prometheus registry, or SSE replay protocol.
- Pacemaker artifact redesign or validator-set rotation changes.
- A hard transaction submission-to-finalized SLO owned by Sigilaris.

## Related ADRs And Docs
- [ADR-0027: SignatureVerifier Account-Key Binding Contract](../adr/0027-signature-verifier-account-key-binding-contract.md)
- [ADR-0028: HotStuff Finalization Observability And Embedder Failure Semantics](../adr/0028-hotstuff-finalization-observability-and-embedder-failure-semantics.md)
- [ADR-0010: Blockchain Account Model and Key Management](../adr/0010-blockchain-account-model-and-key-management.md)
- [ADR-0012: Signed Transaction Requirement](../adr/0012-signed-transaction-requirement.md)
- [ADR-0017: HotStuff Consensus Without Threshold Signatures](../adr/0017-hotstuff-consensus-without-threshold-signatures.md)
- [ADR-0022: HotStuff Pacemaker And View-Change Baseline](../adr/0022-hotstuff-pacemaker-and-view-change-baseline.md)
- [v0.2.3 Release Notes Draft](../dev/v0.2.3-release-notes.md)

## Decisions To Lock Before Implementation

_All resolved in Phase 0 — see the "Phase 0 Locked Decisions" subsection below. The items here record what Phase 0 needed to lock._

- Whether ADR-0027 and ADR-0028 stay `Proposed` for the release candidate or are promoted to `Accepted` before publishing.
- Whether verifier failure messages remain diagnostic-only for v0.2.3, or whether a typed signature failure reason is added in this release.
- Whether finalization observations are exposed as:
  - a current snapshot only;
  - a bounded in-memory history;
  - an embedder-provided sink/callback;
  - or a combination of the above.
- Confirm the exact timestamp names used for consensus-layer finalization latency (`proposalObservedAt` and `finalizedObservedAt` unless revised during Phase 0).
- Whether materialization outcomes are only mentioned as embedder-owned in docs, or whether v0.2.3 adds an optional materialization observation hook.

## Change Areas

### Code
- `modules/core/shared/src/main/scala/org/sigilaris/core/application/security/SignatureVerifier.scala`
  - no semantic change expected; v0.2.3 hardening is documentation and tests only.
- `modules/core/shared/src/test/scala/...`
  - focused verifier contract tests.
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/Finalization.scala`
  - add the `FinalizedAnchorObservation` observation model/helper.
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/InMemoryHotStuffGossipBridge.scala`
  - record first observations; the in-memory sink owns the first-observation time.
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/HotStuffNodeRuntime.scala`
  - wire the runtime diagnostic surface `HotStuffNodeRuntime.currentFinalizationObservations`.

### Tests
- Signature verifier tests:
  - named account accepts registered non-expired recovered key;
  - named account rejects missing recovered key;
  - named account rejects expired recovered key at envelope timestamp;
  - named account accepts an `envelopeTimestamp` exactly equal to `expiresAt`;
  - named-account lookup receives `(name, recoveredKeyId)`: the claimed account name selects the account, but no caller-supplied key id is accepted as authority;
  - unnamed account accepts exact recovered key;
  - unnamed account rejects recovered-key mismatch;
  - `verifySignature` returns the recovered key id after recovery and ownership checks pass;
  - `CreateNamedAccount` bootstrap recovery/equality behavior is covered by a focused test or linked existing suite.
- Finalization diagnostics tests:
  - first finalized anchor observation records anchor/proof ids and timestamp;
  - finalization observation records the anchor proposal window validator-set hash (`suggestion.proposal.window.validatorSetHash`);
  - repeated snapshot calculation does not duplicate first-observation entries in the internal bounded history;
  - safety faults remain visible and separate from normal best-finalized observations;
  - verification failures keep structured `reason` / `detail` diagnostics and are not represented as materialization failures;
  - no-finalized-anchor state is not represented as a failure.

### Docs
- ADR-0027 and ADR-0028.
- `docs/dev/v0.2.3-release-notes.md`.
- Site docs only if new public types are introduced.

## Implementation Phases

### Phase 0: Contract Lock
- Review ADR-0027 and ADR-0028 with downstream integration questions in mind.
- Decide whether v0.2.3 needs typed signature failure reasons or documentation/test hardening is enough.
- Choose the finalization observation exposure shape.
- Freeze the v0.2.3 public surface list before code changes.

#### Phase 0 Locked Decisions
1. **ADR status for the release candidate**
   - ADR-0027 and ADR-0028 remain `Proposed` through the release candidate; promote both to `Accepted` only in the final `0.2.3` release commit after Phase 4 verification confirms the tests back the contract. ADR-0027 matches the current verifier implementation (`SignatureVerifier.scala:41-108`), while ADR-0028 still includes Phase 2 net-new timestamp exposure because the current snapshot has only `bestFinalized` and `safetyFaults` (`Finalization.scala:68-72`). The plan non-goals avoid declaring unverified external/API commitments before the release tests exist.

2. **Typed signature failure metadata scope**
   - v0.2.3 adds no `SignatureVerificationFailureReason`, verifier-specific `FailureCode`, or equivalent typed signature failure taxonomy. `SignatureVerifier` continues to recover and authorize through `CryptoFailure` (`SignatureVerifier.scala:41-54`, `SignatureVerifier.scala:61-91`), and `verifySignature` continues to return only the recovered `KeyId20` on success (`SignatureVerifier.scala:94-108`). `CryptoFailure.msg` remains diagnostic-only per ADR-0027; typed verifier metadata stays a follow-up so this release does not grow the shared verifier public API.

3. **Finalization observation exposure shape**
   - v0.2.3 locks a runtime diagnostic snapshot extension plus a small, bounded, in-memory first-observation history that is internal and sink-owned, used only to stamp and de-duplicate the first local observation of each finalized anchor: add a `FinalizedAnchorObservation` diagnostic record, keep the in-memory sink as the internal stateful owner that records first-observation time, and expose the embedder-facing current best observations through the always-available runtime diagnostic accessor `HotStuffNodeRuntime.currentFinalizationObservations: F[Map[ChainId, FinalizedAnchorObservation]]`, mirroring the existing `current*` accessor convention such as `currentBootstrapDiagnostics` and `currentPacemakerSnapshot` (`HotStuffNodeRuntime.scala:101-104`). The bounded history is not part of the public surface; the only public finalization-observation accessor in v0.2.3 is `HotStuffNodeRuntime.currentFinalizationObservations: F[Map[ChainId, FinalizedAnchorObservation]]`. This is Phase 2 net-new work: today `FinalizationTrackerSnapshot` contains only `bestFinalized` and `safetyFaults` (`Finalization.scala:68-72`), the in-memory sink stores finalization as `Map[ChainId, FinalizationTrackerSnapshot]` (`InMemoryHotStuffGossipBridge.scala:21-32`), and the sink recomputes finalization from proposals without observation timestamps (`InMemoryHotStuffGossipBridge.scala:493-500`). Capturing stable first-observation timestamps requires the Phase 2 observation path to be stateful: the in-memory sink/runtime must diff newly finalized anchors against previously observed anchors and stamp only the first local observation, because `FinalizationTrackerSnapshot` is recomputed statelessly via `withFinalization` -> `trackAll(snapshot.proposals.values)` and cannot assign stable observation times itself. No embedder-provided sink/callback, HTTP/SSE/Prometheus/exporter contract, or materialization hook is added.

4. **Timestamp names**
   - The observation timestamp field names are `proposalObservedAt` and `finalizedObservedAt`. They are not present in the current runtime and must be added in Phase 2; the existing `HotStuffWindow` / `Proposal` model has no naming collision (`Policy.scala:252-257`, `Artifacts.scala:440-449`). `proposalObservedAt` means local first acceptance or emission of the anchor proposal artifact, and `finalizedObservedAt` means local first observation of the verified finalized anchor.

5. **Materialization hook scope**
   - v0.2.3 adds no materialization observation hook, callback, sink, terminal status, or `FailedPermanently` consensus state. Materialization outcomes remain embedder-owned as ADR-0028 states, distinct from the existing HotStuff bootstrap/catch-up materialization helpers (`Materialization.scala:18-41`). The core finalization path remains consensus-owned diagnostics: verification failures use structured `reason` / `detail` (`Finalization.scala:13-17`, `Finalization.scala:184-274`) and safety faults stay separate (`Finalization.scala:30-67`).

6. **v0.2.3 public surface list**
   - No new public signature-verifier types or methods are added. The only public surface additions in v0.2.3 are HotStuff diagnostic additions in `sigilaris-node-jvm`: `FinalizedAnchorObservation` with `chainId`, `proposalId`, `blockId`, `height`, `childProposalId`, `grandchildProposalId`, `validatorSetHash` sourced from `suggestion.proposal.window.validatorSetHash`, `proposalObservedAt`, and `finalizedObservedAt`; plus `HotStuffNodeRuntime.currentFinalizationObservations: F[Map[ChainId, FinalizedAnchorObservation]]` as the always-available embedder-facing runtime diagnostic accessor. Existing published coordinates remain unchanged, and this does not create an external protocol, metrics, exporter, registry, materialization API, or new signature-verifier API. `FinalizedAnchorSuggestion` already carries the anchor/proof proposals (`Bootstrap.scala:144-178`), and `validatorSetHash` is already a real window field validated by HotStuff (`Policy.scala:252-257`, `Validation.scala:258-260`).

### Phase 1: Signature Verifier Hardening
- Add focused `SignatureVerifier` contract tests for named and unnamed accounts.
- Cover the strict expiration boundary: `envelopeTimestamp == expiresAt` is accepted; only timestamps after `expiresAt` are rejected.
- Use a finite-expiration named-key fixture, such as a key registered through `AddKeyIds(..., expiresAt = Some(...))`; `CreateNamedAccount` bootstrap keys are non-expiring.
- Assert named-account lookup receives `(name, recoveredKeyId)`: the claimed account name selects the account, while authority comes from the recovered key id.
- Assert successful `verifySignature` returns the recovered key id.
- Link or add focused coverage for the `CreateNamedAccount` bootstrap rule that recovered key id must equal `initialKeyId`.
- Confirm tests assert rejection through `CryptoFailure`, not through message fragments.
- Do not add typed signature failure metadata in v0.2.3; preserve current `CryptoFailure` compatibility.

### Phase 2: Finalization Observability
- Add the `FinalizedAnchorObservation` observation model.
- Record `proposalObservedAt`, `finalizedObservedAt`, and the anchor proposal window validator-set hash.
- Keep consensus finalization diagnostics separate from embedder materialization diagnostics.
- Preserve finalization verification failures as structured `reason` / `detail` diagnostics.
- Ensure safety faults remain high-severity structured diagnostics.

### Phase 3: Release Documentation
- Finalize `docs/dev/v0.2.3-release-notes.md`.
- Add or confirm release notes state that pacemaker timing values are not finalization SLAs.
- Update site docs if new public types or methods are introduced.
- Record verification command results in the release notes.

### Phase 4: Release Verification
- Run focused core verifier tests.
- Run focused HotStuff finalization/diagnostic tests.
- Run `sbt "nodeJvm/test"` before release candidate handoff.
- Run `sbt ";unidoc;tlSite"` if public types or site docs changed.
- Confirm published coordinates remain unchanged.

## Test Plan
- Core:
  - focused verifier suite;
  - existing accounts/group integration suites if verifier behavior is touched.
- Node JVM:
  - `HotStuffFinalizationSuite`;
  - `HotStuffDiagnosticSuite`;
  - any new finalization observation suite.
- Regression:
  - `sbt "nodeJvm/test"`;
  - `sbt ";unidoc;tlSite"` when public documentation/API changes need site validation.

## Risks And Mitigations
- **Risk:** verifier messages accidentally become external ABI.
  - **Mitigation:** tests assert failure type/contract, not message fragments; ADR states messages are diagnostic-only.
- **Risk:** finalization latency is misread as application submission-to-finalized latency.
  - **Mitigation:** field names and docs distinguish proposal observation from application admission.
- **Risk:** materialization failure is confused with consensus failure.
  - **Mitigation:** ADR-0028 separates consensus finalization, verification failure, safety fault, and embedder materialization.
- **Risk:** public API grows more than v0.2.3 needs.
  - **Mitigation:** prefer diagnostic snapshots and bounded in-memory observation history over hooks or exporter-specific protocols.

## Acceptance Criteria
1. ADR-0027 and ADR-0028 exist and are linked from the v0.2.3 plan and release notes.
2. Signature verifier guarantees are covered by focused tests; if equivalent existing coverage is used instead, Phase 1 must link the exact suites/assertions that cover each ADR-0027 case.
3. Signature verifier tests cover recovered-key lookup, exact expiration boundary behavior, unnamed-account equality, and successful recovered-key return.
4. Finalization observability exposes best-finalized anchor identity, proof identity, the anchor proposal window validator-set hash, `proposalObservedAt`, and `finalizedObservedAt` through `HotStuffNodeRuntime.currentFinalizationObservations`.
5. Finalization verification failures, safety faults, and embedder materialization failures remain distinct in tests or documented diagnostics.
6. Release notes include public contract notes, upgrade notes, verification results, and a reminder that pacemaker timing values are not finalization SLAs.
7. `build.sbt` remains on `0.2.3-SNAPSHOT` until the final publish step changes it to `0.2.3`.

## Checklist

### Phase 0: Contract Lock
- [x] Draft ADR-0027.
- [x] Draft ADR-0028.
- [x] Decide ADR status for release candidate.
- [x] Decide typed signature failure metadata scope.
- [x] Decide finalization observation exposure shape.

### Phase 1: Signature Verifier Hardening
- [x] Add focused named-account verifier tests.
- [x] Add focused unnamed-account verifier tests.
- [x] Cover exact expiration boundary acceptance at `expiresAt`.
- [x] Use a finite-expiration named-key fixture for expiration tests.
- [x] Assert named-account lookup receives `(name, recoveredKeyId)`.
- [x] Assert successful `verifySignature` returns the recovered key id.
- [x] Link or add `CreateNamedAccount` bootstrap recovery/equality coverage: added `SignatureVerifierSuite` test "SignatureVerifier: CreateNamedAccount bootstrap rejects signer whose recovered key differs from initialKeyId".
- [x] Confirm tests avoid message-fragment ABI assertions.

### Phase 2: Finalization Observability
- [x] Add the `FinalizedAnchorObservation` model and `HotStuffNodeRuntime.currentFinalizationObservations` wiring.
- [x] Add finalization observation tests.
- [x] Include the anchor proposal window validator-set hash in the observation model (`suggestion.proposal.window.validatorSetHash`).
- [x] Verify finalization verification failures keep structured `reason` / `detail` diagnostics (unchanged).
- [x] Verify safety faults stay separate from normal observations.
- [x] Document materialization failure as embedder-owned (unchanged; v0.2.3 adds no materialization hook).

### Phase 3: Release Documentation
- [x] Draft v0.2.3 release notes.
- [x] Add or confirm release notes distinguish pacemaker timing values from finalization SLAs.
- [x] Fill verification snapshot with final command results.
- [x] Update site docs if public types/methods are added (EN/KO `node-jvm/hotstuff-and-pacemaker.md` "Finalization Observability").

### Phase 4: Release Verification
- [x] Run focused verifier tests (`SignatureVerifierSuite`: 8 passed, 2026-06-08).
- [x] Run focused finalization diagnostics tests (observation/finalization/diagnostic suites: 20 passed; also covered by `nodeJvm/test`).
- [x] Run `sbt "nodeJvm/test"` (305 passed, 0 failed, 0 errors, 2026-06-08).
- [x] Run `sbt ";unidoc;tlSite"` (green: unidoc API + mdoc 0 errors + 51 site docs rendered).
- [x] Confirm published coordinates remain unchanged (`sigilaris-core{,_sjs1}_3`, `sigilaris-node-common{,_sjs1}_3`, `sigilaris-node-jvm_3`; no new module).
- [x] Switch version from `0.2.3-SNAPSHOT` to `0.2.3` only for the final release commit.

## Follow-Ups
- Embedders should keep HTTP/SSE/errorKey ADRs separate and reference these
  Sigilaris provider contracts.
- If a stable metrics backend is needed, create a follow-up plan for finalization metric export after the diagnostic model lands.
- If embedder materialization hooks become common across downstream applications, write a dedicated materialization handoff ADR.
