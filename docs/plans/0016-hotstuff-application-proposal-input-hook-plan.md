# 0016 - HotStuff Application Proposal Input Hook Plan

## Status

Draft

## Created

2026-05-16

## Last Updated

2026-05-16

## Background

The autonomous in-memory pacemaker currently emits application-empty proposals. In `PacemakerIntegrationRuntime.scala`, `InMemoryHotStuffPacemakerDriver.emitLeaderProposal` signs a block with `ProposalTxSet.empty` and synthetic roots. That is sufficient for pacemaker/view-change smoke coverage, but it prevents embedding applications from driving real admitted work through the autonomous leader proposal path.

Some embedders maintain their own pending transaction or batch admission queues. Today those embedders can only test non-empty proposal materialization by manually calling lower-level runtime helpers. The autonomous leader path has no application-neutral input hook.

`HotStuffNodeRuntime.emitProposalFromCandidates` already proves that Sigilaris can build and publish non-empty proposals from application candidates. The missing piece is an application-neutral proposal input hook that lets the pacemaker ask the embedding application for the next proposal body before signing a leader proposal.

## Goal

Add a Sigilaris-level, application-neutral proposal input contract so autonomous HotStuff leader proposals can consume application-supplied work.

The target outcome is:

- the pacemaker proposal path can emit a non-empty `ProposalTxSet`;
- legacy empty proposals remain available as an explicit fallback policy;
- provider errors never cause a malformed signed proposal;
- embedding applications can wire their own pending-work selector into this hook without importing application-specific types into Sigilaris;
- embedders can replace manual proposal-materialization harnesses with a true autonomous proposal smoke: local admission -> pacemaker proposal -> vote -> QC/finalization/materialization.

## Scope

- Define an application-neutral proposal input provider contract for HotStuff.
- Route autonomous leader proposal emission through that provider.
- Preserve existing default in-memory pacemaker behavior for tests and embedders that do not configure an application provider.
- Add diagnostics for provider rejection, provider failure, empty input, and fallback behavior.
- Add Sigilaris tests that prove the autonomous pacemaker can publish non-empty proposals supplied by a fake application provider.
- Document the new contract and the embedder handoff point.
- Update the Typelevel site node runtime narrative after the provider-backed proposal path lands.

## Non-Goals

- Do not add application-specific imports, lane ADTs, batch semantics, or manifest semantics to Sigilaris.
- Do not decide application-specific lane fairness or starvation policy here.
- Do not implement application-specific manifest/range consistency validation.
- Do not change HotStuff signing bytes, vote rules, QC rules, or validator-set rotation semantics.
- Do not implement application-level disaster-recovery fixtures or promotion smokes in this plan.

## Related ADRs And Docs

- `docs/adr/0017-hotstuff-consensus-without-threshold-signatures.md`
- `docs/adr/0019-canonical-block-header-and-application-neutral-block-view.md`
- `docs/adr/0020-conflict-free-block-scheduling-with-state-references-and-object-centric-seams.md`
- `docs/adr/0022-hotstuff-pacemaker-and-view-change-baseline.md`
- `docs/adr/0023-validator-set-rotation-and-bootstrap-trust-roots.md`
- `docs/adr/0026-tx-execution-witness-and-receipt-projection-boundary.md`
- `docs/plans/0004-hotstuff-consensus-without-threshold-signatures-plan.md`
- `docs/plans/0013-hotstuff-runtime-hardening-and-gossip-bridge-cleanup-plan.md`
- `docs/plans/0015-typelevel-site-current-implementation-alignment-plan.md`

## Decisions To Lock Before Implementation

1. **Provider shape**
   - Define a small HotStuff-owned provider interface, for example `HotStuffProposalInputProvider[F]`.
   - The request should include the consensus window/view, proposer identity, parent/justify context, current time, and any local limits needed to build a bounded proposal.
   - The response should distinguish:
     - application supplied a proposal input;
     - application intentionally has no work;
     - application rejected proposal emission for this view;
     - provider failed.

2. **Provider output**
   - Prefer returning application-neutral block body data that Sigilaris can turn into the existing `ProposalTxSet`.
   - Reuse `BlockBody.computeBodyRoot`, `ProposalTxSet.fromTxs`, and `emitProposalFromCandidates` where possible.
   - Avoid making embedders compute consensus-critical roots unless the root derivation is already part of a stable Sigilaris API.

3. **Empty proposal fallback**
   - Preserve the current behavior through a default legacy provider.
   - Make fallback explicit in the API or bootstrap config.
   - Production embedders should be able to require provider-backed proposals so an accidental empty-proposal-only deployment is visible.

4. **Safety behavior**
   - Keep the existing local-leader and local-key checks before signing.
   - Preserve the single-proposal-per-window invariant guarded by `emittedProposalWindow`.
   - If provider lookup fails or returns invalid input, do not sign a partial or synthetic application proposal.
   - Local provider output must pass the same block/proposal validation path used for proposals received from peers.

5. **Diagnostics**
   - Provider failures and rejections should appear in runtime diagnostics.
   - The diagnostic should include the window/view, proposer id, reason key, and whether empty fallback was used.
   - Diagnostics must not leak application payload bodies by default.

## Locked Phase 0 Decisions

1. **Provider contract**
   - Add `HotStuffProposalInputProvider[F]` in the HotStuff runtime package.
   - The provider is effectful and application-neutral.
   - Requests include window, proposer, parent block id, height, justify QC, current time, and local proposal bounds.
   - Responses use a sealed taxonomy: supplied input, no work, rejected, and failed.

2. **Provider output model**
   - Provider-supplied input returns consensus-owned proposal body metadata:
     `ProposalTxSet`, `StateRoot`, `BodyRoot`, parent, block height, and timestamp.
   - Embedders may derive this from application candidates, but Sigilaris does not import application batch, lane, or manifest types.
   - The existing `emitProposalFromCandidates` helper remains the application-block-body path for typed candidates; the pacemaker hook signs the already application-neutral provider output.

3. **Empty proposal fallback**
   - Legacy behavior is preserved by an explicit `LegacyEmptyHotStuffProposalInputProvider`.
   - Fallback is controlled by `HotStuffProposalInputFallbackPolicy`.
   - Compatibility mode may fall back to the legacy empty proposal on no-work, rejection, provider failure, or invalid supplied input.
   - Require-provider mode never signs an empty proposal as an implicit fallback.

4. **Safety behavior**
   - Local leader/key/readiness checks remain before signing.
   - The `emittedProposalWindow` invariant remains the single-emission guard.
   - Provider failures, rejections, invalid supplied input, and timestamp/root construction failures do not publish malformed signed proposals.
   - Locally produced proposals still pass the same in-memory sink validation path before they are observed by the pacemaker.

5. **Diagnostics**
   - Pacemaker snapshots include provider outcome diagnostics.
   - Diagnostics record window, proposer, reason, and fallback usage, but never payload bodies.
   - Rejection/failure/no-work/fallback cases are test-visible through `currentPacemakerSnapshot`.

## Change Areas

### Code

- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/PacemakerIntegrationRuntime.scala`
  - Replace hardcoded `ProposalTxSet.empty` emission with a provider-backed emission path.
  - Keep legacy empty proposal behavior behind an explicit default provider or fallback policy.

- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/HotStuffNodeRuntime.scala`
  - Expose or adapt the existing non-empty proposal emission path so the pacemaker can consume provider output.
  - Prefer reusing `emitProposalFromCandidates` rather than adding a parallel proposal builder.

- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/RuntimeScheduling.scala`
  - Add provider request/result/rejection ADTs here if they fit the existing scheduling model.
  - Otherwise create a focused `ProposalInput.scala` in the same package.

- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/HotStuffRuntimeBootstrap.scala`
  - Add optional provider wiring for embedders.
  - Preserve current bootstrap behavior when no provider is supplied.

- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/Validation.scala`
  - Confirm local provider-built proposals are validated consistently with peer proposals.
  - Add helper validation only if an existing validation path cannot be reused cleanly.

### Tests

- `modules/node-jvm/src/test/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/HotStuffPacemakerRuntimeSuite.scala`
  - Add provider-backed automatic proposal tests.
  - Keep legacy empty proposal tests.

- `modules/node-jvm/src/test/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/HotStuffRuntimeSchedulingIntegrationSuite.scala`
  - Add integration coverage for non-empty proposal emission and validation/materialization.

- `modules/node-jvm/src/test/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/HotStuffLaunchSmokeSuite.scala`
  - Add a smoke test proving autonomous pacemaker proposal emission can carry application input.

### Docs

- Update ADR-0022 or the runtime docs with the provider contract once the API lands.
- Document how embedders supply proposal input without depending on application-specific package names.
- Update `site/src/en/node-jvm/hotstuff-and-pacemaker.md` and `site/src/ko/node-jvm/hotstuff-and-pacemaker.md`.
- Update `site/src/en/node-jvm/README.md` and `site/src/ko/node-jvm/README.md` only if the section overview would otherwise drift.
- Keep EN/KO pages in sync as translations of the same contract, not separate narratives.
- Keep the Typelevel site narrative focused on current baseline, limitations, and API handoff; detailed method inventory remains in generated `/api`.

## Implementation Phases

### Phase 0 - Contract Lock

- [x] Lock the provider shape and result taxonomy from the decisions above.
- [x] Lock the provider output model from the decisions above.
- [x] Lock the empty-proposal fallback policy from the decisions above.
- [x] Lock safety and diagnostics behavior from the decisions above.
- [x] Record the final choices in this plan before Phase 1 starts.

### Phase 1 - Provider ADT And Legacy Provider

- [x] Add the HotStuff-owned proposal input provider interface.
- [x] Add a legacy provider that reproduces the current empty proposal behavior.
- [x] Add unit coverage for provider result encoding and fallback decisions.
- [x] Keep all existing pacemaker tests green before changing runtime wiring.

### Phase 2 - Pacemaker Proposal Wiring

- [x] Route `InMemoryHotStuffPacemakerDriver.emitLeaderProposal` through the provider.
- [x] Reuse `HotStuffNodeRuntime.emitProposalFromCandidates` or a shared helper for non-empty proposal construction.
- [x] Preserve `emittedProposalWindow` single-emission behavior.
- [x] Ensure provider failure or invalid input does not publish a signed proposal.
- [x] Emit structured diagnostics for provider result, rejection, failure, and fallback.

### Phase 3 - Bootstrap And Embedding API

- [x] Add optional provider wiring to the runtime bootstrap surface.
- [x] Preserve existing behavior for embedders that do not supply a provider.
- [x] Add a mode that requires application input and fails visibly if only the legacy empty provider is configured.
- [x] Keep the API application-neutral and free of embedder package dependencies.

### Phase 4 - Verification

- [ ] Test autonomous pacemaker emission with a fake provider returning non-empty candidates.
- [ ] Test provider returns no work and fallback is disabled.
- [ ] Test provider returns no work and fallback is enabled.
- [ ] Test provider failure produces diagnostics and no signed proposal.
- [ ] Test repeated pacemaker ticks do not emit more than one proposal for the same window.
- [ ] Test a non-empty provider-backed proposal can be validated and materialized through the existing runtime path.

### Phase 5 - Typelevel Site Documentation

- [ ] Update the EN/KO HotStuff pacemaker narrative with provider-backed autonomous proposals.
- [ ] Describe the legacy empty proposal fallback as an explicit compatibility policy.
- [ ] Document provider failure/rejection behavior at the narrative level without exposing payload bodies.
- [ ] Link readers to ADR-0022 and generated `/api` for details instead of duplicating method inventory.
- [ ] Confirm generated `/api` exposes the provider-facing symbols after site verification.
- [ ] Run the Typelevel site verification command from the Test Plan.

### Phase 6 - Embedder Handoff

- [ ] Document the Sigilaris provider contract for embedders.
- [ ] Provide a small fake embedder adapter example in tests or docs.
- [ ] Confirm an embedder can move from manual proposal-materialization harnesses to autonomous provider-backed proposal smokes.

## Test Plan

- Run the focused HotStuff pacemaker and scheduling suites after each runtime wiring step.
- Run `sbt "nodeJvm/test"` before commit.
- Add at least one launch smoke that starts a local validator runtime with the provider enabled and observes a non-empty proposal.
- Confirm all legacy empty proposal tests still pass when the legacy provider is selected.
- Confirm diagnostics are asserted in failure-path tests, not only logged.
- Run `sbt ";unidoc;tlSite"` after Typelevel site updates and confirm `target/docs/site/api` exposes the provider-facing symbols.

## Risks And Mitigations

- **Risk: provider hook becomes application-shaped.**
  - Keep provider input and output in HotStuff vocabulary only: proposer, view/window, parent/justify, candidate payload references, roots, and bounds.

- **Risk: fallback hides production misconfiguration.**
  - Make fallback explicit and provide a require-provider mode for embedders.

- **Risk: local proposal construction diverges from peer proposal validation.**
  - Reuse existing proposal construction and validation helpers rather than adding a second path.

- **Risk: provider latency stalls pacemaker progress.**
  - Keep the provider API effectful and bounded by caller policy.
  - Add diagnostics for timeout/failure; decide timeout ownership during Phase 0.

- **Risk: application returns nondeterministic or unsafe ordering.**
  - Treat candidate ordering and filtering as provider/application responsibility, but validate canonical proposal encoding in Sigilaris before signing.

## Acceptance Criteria

- The automatic pacemaker leader proposal path can publish a non-empty proposal supplied by an application-neutral provider.
- The default runtime behavior remains compatible with existing empty-proposal tests.
- Provider failure, rejection, and no-work outcomes are observable in tests.
- A provider-backed non-empty proposal passes the existing HotStuff validation/materialization path.
- No Sigilaris production code imports embedder application packages.
- Embedders have a documented handoff point for wiring their pending-work selector into the provider.
- The Typelevel site EN/KO HotStuff pacemaker pages describe the provider-backed proposal path and `sbt ";unidoc;tlSite"` is green.

## Checklist

- [x] Provider ADTs landed
- [x] Legacy empty proposal provider landed
- [x] Pacemaker consumes provider output
- [ ] Provider-backed non-empty proposal smoke green
- [ ] Provider failure/rejection diagnostics tested
- [x] Require-provider mode available for embedders
- [ ] Runtime docs updated
- [ ] Typelevel site EN/KO docs updated
- [ ] `sbt ";unidoc;tlSite"` green after docs update
- [ ] Embedder handoff documented

## Follow-Ups

- Embedding applications should wire their pending-work selectors into the Sigilaris provider after this lands.
- Application-specific fairness/starvation policy remains an embedder follow-up unless Sigilaris needs a generic scheduler policy later.
- Application-specific manifest/range consistency validation remains outside this Sigilaris plan.
- Application-level disaster-recovery fixtures and promotion smokes remain follow-up work after autonomous provider-backed proposals are stable.
