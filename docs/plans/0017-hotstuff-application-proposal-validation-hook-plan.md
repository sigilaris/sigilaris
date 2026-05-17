# 0017 - HotStuff Application Proposal Validation Hook Plan

## Status

Proposed

## Created

2026-05-17

## Last Updated

2026-05-17

`Last Updated` reflects the newest date in `Revision Notes`.

## Revision Notes

- 2026-05-17: Locked and tightened Phase 0 decisions after code-review
  alignment with existing vote and sink validation surfaces; application
  validation is a vote-time per-`(proposal, localVoter)` gate, while the
  in-memory artifact sink remains the structural/generic retention layer.

## Background

Plan 0016 added an application-neutral proposal input hook for the autonomous
leader path. That lets an embedder provide non-empty work when the local node is
the leader.

The remaining embedder safety gap is on the receive and vote side. A node can
receive a peer proposal, have the HotStuff artifact pass Sigilaris structural
validation, and then auto-vote without giving the embedding application a
chance to validate application-owned policy. For downstream applications this
means proposal tx ids and block ids may need to be checked against local
admission, state, policy, or manifest stores before the validator signs a vote.

Sigilaris already has a low-level `proposalValidation` function inside the
in-memory artifact sink, a `createWithProposalValidation` sink factory, and a
generic block-query validation helper in `HotStuffRuntimeScheduling`. Those are
narrow sink-level surfaces, not a public runtime/bootstrap contract for
automatic consensus. This plan promotes and generalizes the existing validation
idea into an application-neutral runtime hook that can gate local vote emission.

## Goal

Add a Sigilaris-level, application-neutral proposal validation contract so
embedders can accept, reject, or hold received proposals before any local vote
is signed.

The target outcome is:

- received proposals can be checked by embedder-owned policy before local vote
  emission;
- automatic consensus can require an application validator and fail fast if it
  is missing;
- validation failures are visible through diagnostics rather than silent
  no-votes;
- proposal construction remains covered by plan 0016 and is not conflated with
  receive-side validation;
- Sigilaris still does not import application lane, batch, manifest, or
  disaster-recovery types.

## Scope

- Define an application-neutral proposal validation provider contract for
  HotStuff receive/vote paths.
- Expose runtime/bootstrap wiring for embedders to provide that validator.
- Route autonomous pacemaker auto-vote through the application validation gate.
- Route public/manual local vote helpers through the same validation gate where
  they can sign votes.
- Preserve legacy compatibility through an explicit allow-all validator policy.
- Add diagnostics for accepted, rejected, unavailable, failed, and missing
  validator outcomes.
- Add tests proving rejected or unavailable application validation never signs a
  local proposal vote.
- Document the new handoff point for embedders.

## Non-Goals

- Do not add downstream application fields, lane ADTs, batch records, manifest
  records, disaster-recovery semantics, or other application-specific types to
  Sigilaris.
- Do not decide application-specific fairness, starvation, batch splitting, or
  admission eviction policy.
- Do not change HotStuff signing bytes, vote identity, QC rules, validator-set
  rotation, or pacemaker artifact semantics.
- Do not make Sigilaris responsible for application state lookup,
  application-specific transaction availability, or manifest persistence.
- Do not replace the proposal input hook from plan 0016.
- Do not gate timeout votes with application proposal validation; timeout votes
  remain pacemaker liveness artifacts, not application proposal votes.
- Do not let validation providers mutate HotStuff runtime state. Providers
  should return read-only verdicts; downstream applications own any separate
  state transitions they need outside the Sigilaris hook.

## Related ADRs And Docs

- `docs/adr/0017-hotstuff-consensus-without-threshold-signatures.md`
- `docs/adr/0019-canonical-block-header-and-application-neutral-block-view.md`
- `docs/adr/0020-conflict-free-block-scheduling-with-state-references-and-object-centric-seams.md`
- `docs/adr/0021-snapshot-sync-and-background-backfill-from-best-finalized-suggestions.md`
- `docs/adr/0022-hotstuff-pacemaker-and-view-change-baseline.md`
- `docs/plans/0016-hotstuff-application-proposal-input-hook-plan.md`
- Motivating downstream embedder consumers live outside this repository; this
  plan keeps their application types and policies out of Sigilaris.

## Decisions To Lock Before Implementation

1. **Validation contract shape**
   - Define a HotStuff-owned validator interface, for example
     `HotStuffProposalValidationProvider[F]`.
   - The request should include the proposal, local validation time, validator
     set context, and the local voter identity only if Phase 0 chooses a
     per-`(proposal, localVoter)` request scope.
   - Keep the request in HotStuff vocabulary. Embedders map proposal tx ids and
     block ids to their own local admission/session/manifest stores.
   - Lock whether the primary request scope is per proposal or per
     `(proposal, localVoter)`. This decision is coupled to hook placement:
     sink-level validation has no local voter, while vote-time validation is
     voter-specific and can matter for multi-voter nodes.

2. **Result taxonomy**
   - Distinguish at least:
     - accepted;
     - rejected as application-invalid;
     - unavailable or held because local application data is not ready;
     - validator failed;
     - validator missing when required.
   - Lock whether effect-level provider failures are caught by the runtime and
     converted to a `Failed` result, or whether providers must return `Failed`
     explicitly. Runtime-owned conversion is preferred so thrown provider
     failures are diagnostic and non-signing.
   - Rejected proposals must not produce local votes.
   - Unavailable/held proposals must not produce local votes, but Phase 0 must
     decide whether they are retained, relayed, or rejected from the in-memory
     sink.

3. **Hook placement**
   - The hook must run before `PacemakerIntegrationRuntime` signs an automatic
     vote.
   - Manual or helper vote emission surfaces such as `HotStuffNodeRuntime`
     vote helpers must either run through the same hook or be clearly documented
     as lower-level unsafe APIs.
   - `HotStuffNodeRuntime.emitVote` is currently a low-level vote emission
     surface without the block-query gate, while
     `HotStuffNodeRuntime.emitVoteForProposalView` already validates through a
     block-query-backed proposal view path. Phase 0 must decide whether to
     preserve this split, rename the low-level surface, or absorb the
     block-query path into the new provider contract.
   - If the hook is also wired into `InMemoryHotStuffArtifactSink`, make sure
     the sink behavior does not turn temporary application-data unavailability
     into a permanent invalid-artifact rejection by accident.
   - Explicitly choose one of the load-bearing sink policies:
     - keep the sink structural/generic and run application validation only in
       the pacemaker or local vote path; or
     - keep an application hook in the sink and represent unavailable/held
       proposals as retained-but-not-votable state instead of rejected
       artifacts.

4. **Compatibility and production policy**
   - Preserve the current behavior with an explicit allow-all validator for
     tests and compatibility embedders.
   - Add a require-validator mode for automatic consensus production profiles.
   - Prefer naming that mirrors plan 0016 proposal input config helpers, such
     as `legacyCompatible` and `requireValidationProvider`, so embedder runtime
     config remains predictable.
   - Validate bootstrap config so `automaticConsensus=true` can fail fast when
     application validation is required but no validator is configured.

5. **Diagnostics and cardinality**
   - Record validation outcome diagnostics with window/view, proposal id, block
     id, local voter when applicable, reason, detail, and whether a vote was
     suppressed.
   - Do not log or expose full proposal payload bodies by default.
   - Deduplicate repeated diagnostics at least by
     `(window, proposalId, voter, reason)`, with Phase 0 locking whether
     the retention window is the current view, a bounded count, or a timed
     cache.

6. **Interaction with catch-up readiness**
   - Keep bootstrap readiness and proposal catch-up readiness as data
     sufficiency gates.
   - Application validation should be able to report "not enough local
     application data yet" without calling the proposal permanently invalid.
   - The exact interaction with existing `ProposalCatchUpReadiness` should be
     locked before changing artifact retention or relay behavior.

7. **Provider timeout and cancellation**
   - Lock whether the runtime owns a validation timeout, the caller supplies one
     around the provider effect, or validation can run unbounded.
   - Timeout and cancellation outcomes must be diagnostic and non-signing.

## Phase 0 Locked Decisions

1. **Request shape and scope**
   - The public hook is `HotStuffProposalValidationProvider[F]`.
   - The request is per-`(proposal, localVoter)`, not per proposal.
   - The request contains only HotStuff/runtime vocabulary: the received
     `Proposal`, the local voter identity, the validation time, and the active
     validator set context.
   - Multi-voter nodes therefore let the same proposal be accepted for one
     local voter and held or rejected for another without inventing
     application-specific fields.

2. **Result taxonomy and failure ownership**
   - Provider results are `Accepted`, `Rejected`, `Unavailable`, and `Failed`.
   - `Rejected` means application-invalid for local policy and is non-signing.
   - `Unavailable` means local application data is not ready yet and is also
     non-signing.
   - Provider effect failures are caught by the runtime and converted to a
     `Failed` diagnostic result; embedders may still return `Failed`
     explicitly when they can provide a stable reason.
   - Missing-required provider is a runtime decision outcome, not a provider
     result.

3. **Hook placement**
   - Application proposal validation runs before any local proposal vote is
     signed.
   - `PacemakerIntegrationRuntime.autoVoteOnProposal` and public
     `HotStuffNodeRuntime.emitVote` are the load-bearing signing surfaces and
     must both use the same gate.
   - `emitVoteForProposalView` keeps its existing block-query proposal-view
     validation first, then delegates to the application-gated `emitVote`.
   - The existing sink-level `createWithProposalValidation` remains a generic
     block-query/body-visible artifact validation helper. It is not the new
     application vote gate because it has no local voter context.

4. **Sink, retention, relay, and catch-up boundary**
   - `InMemoryHotStuffArtifactSink` continues to perform structural HotStuff
     validation before accepting proposals.
   - Application validation is not wired into sink acceptance. A structurally
     valid proposal is retained even if local application validation later
     rejects or holds a local vote.
   - Rejected or unavailable application validation suppresses only the local
     vote; it does not permanently mark the proposal artifact invalid and does
     not change relay policy.
   - Bootstrap readiness and `ProposalCatchUpReadiness` remain data-sufficiency
     gates. Application validation may report `Unavailable` when application
     data is missing, but that is distinct from bootstrap vote readiness.

5. **Compatibility and production policy**
   - `legacyCompatible` preserves current behavior through an explicit
     allow-all missing-provider policy.
   - `withProvider` uses a provider while still allowing startup without making
     provider presence a production invariant.
   - `requireProvider(provider)` both configures a provider and selects the
     production policy.
   - `requireValidationProvider` selects the production policy without a
     provider and makes automatic consensus fail fast until a provider is
     configured.

6. **Diagnostics**
   - Pacemaker snapshots are the first diagnostic surface for automatic
     validation results.
   - Diagnostics include window, proposal id, block id, local voter, outcome,
     reason, detail, and `voteSuppressed`.
   - Repeated proposal-validation diagnostics are deduplicated by
     `(window, proposalId, voter, reason)` within the in-memory pacemaker
     snapshot retention window.
   - Full proposal payload bodies are not exposed in diagnostics.

7. **Timeout and cancellation**
   - Sigilaris does not impose a provider timeout in this phase.
   - Embedders that need a deadline should wrap the provider effect before
     passing it to the runtime.
   - Runtime-caught failures and caller-cancelled effects are diagnostic,
     non-signing outcomes when they surface to the runtime.

## Change Areas

### Code

- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/ProposalInput.scala`
  - Keep proposal input provider types focused on leader proposal construction.
  - Add validation types here only if the file remains readable; otherwise
    create a focused `ApplicationProposalValidation.scala`.

- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/RuntimeScheduling.scala`
  - Reuse existing proposal/block-view validation helpers where possible.
  - Add adapters from block-query validation to the new provider contract if
    the result taxonomy matches.

- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/InMemoryHotStuffGossipBridge.scala`
  - Keep the existing low-level `Proposal => F[Either[...]]` validation surface
    structural/generic.
  - Do not wire application proposal validation into sink acceptance.
  - Preserve structurally valid proposals when application validation suppresses
    a local vote.

- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/PacemakerIntegrationRuntime.scala`
  - Gate `autoVoteOnProposal` and local vote signing through application
    validation.
  - Record proposal validation diagnostics in pacemaker snapshots or a matching
    runtime diagnostic surface.

- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/HotStuffNodeRuntime.scala`
  - Add runtime config plumbing for application proposal validation.
  - Make public/manual vote helper behavior explicit and safe for embedders.
  - Resolve the `emitVote` vs `emitVoteForProposalView` split according to the
    Phase 0 hook placement decision.

- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/HotStuffRuntimeBootstrap.scala`
  - Expose optional proposal validation wiring.
  - Add require-validator bootstrap policy for automatic consensus.

- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/PacemakerRuntime.scala`
  - Extend diagnostics if pacemaker snapshots are the chosen observation
    surface.

### Tests

- Add focused unit tests for validation result taxonomy and runtime policy
  decisions.
- Add pacemaker integration tests:
  - accepted application validation emits a vote;
  - rejected validation does not sign a vote;
  - unavailable/held validation does not sign a vote;
  - validator failure records diagnostics and does not sign a vote;
  - require-validator mode fails bootstrap/config validation when missing.
- Add proposal retention tests proving rejected, unavailable, and failed
  validation suppress votes without dropping structurally valid proposals.
- Add pacemaker diagnostic tests for validation de-duplication by
  `(window, proposalId, voter, reason)`.
- Add compatibility tests proving the explicit allow-all validator preserves
  current runtime behavior.
- Add a fake embedder validation provider in tests that inspects proposal tx ids
  without importing an application package.

### Docs

- Update ADR-0022 or add a focused ADR if Phase 0 makes new long-lived
  decisions about unavailable/held proposal retention.
- Update Typelevel site HotStuff pages in EN and KO to distinguish:
  - proposal input provider for leaders;
  - proposal validation provider for receivers/voters.
- Document compatibility allow-all mode and production require-validator mode.
- Document that application validation is an embedder handoff point and does not
  move application policy into Sigilaris.

## Implementation Phases

### Phase 0 - Contract And Policy Lock

- [x] Lock the validation provider request shape.
- [x] Lock request scope as per-proposal or per-`(proposal, localVoter)`.
- [x] Lock the validation result taxonomy.
- [x] Lock effect-level provider failure handling and `Failed` result
      ownership.
- [x] Decide rejected vs unavailable artifact retention and relay behavior.
- [x] Lock the responsibility boundary between catch-up readiness and
      application validation.
- [x] Decide whether diagnostics live in pacemaker snapshots, runtime snapshots,
      logs, or a new observation surface.
- [x] Lock diagnostic deduplication keys and retention window.
- [x] Lock provider timeout and cancellation ownership.
- [x] Lock compatibility and require-validation-provider mode policy.
- [x] Decide whether manual vote helper APIs must always validate or are marked
      unsafe/lower-level.
- [x] Decide how `emitVote`, `emitVoteForProposalView`, and
      `createWithProposalValidation` relate to the new provider contract.
- [x] Record any long-lived semantic decision in an ADR or ADR update.

### Phase 1 - Provider ADT And Compatibility Policy

- [x] Add the application proposal validation provider interface.
- [x] Add allow-all compatibility provider/config.
- [x] Add require-validator runtime policy.
- [x] Add unit tests for result encoding and policy decisions.
- [x] Keep existing proposal input provider tests green.

Phase 1 verification evidence:

- `sbt "nodeJvm/testOnly org.sigilaris.node.jvm.runtime.consensus.hotstuff.HotStuffProposalValidationProviderSuite org.sigilaris.node.jvm.runtime.consensus.hotstuff.HotStuffProposalInputProviderSuite"` passed on 2026-05-17.

### Phase 2 - Runtime And Bootstrap Wiring

- [x] Wire proposal validation config through `HotStuffNodeRuntime`.
- [x] Wire proposal validation config through `HotStuffRuntimeBootstrap`.
- [x] Preserve current behavior when compatibility allow-all validation is
      selected.
- [x] Fail fast when automatic consensus requires application validation and no
      provider is configured.
- [x] Add bootstrap/runtime tests for compatibility and require-validator modes.

Phase 2 verification evidence:

- `sbt "nodeJvm/testOnly org.sigilaris.node.jvm.runtime.consensus.hotstuff.HotStuffRuntimeServiceSuite org.sigilaris.node.jvm.runtime.consensus.hotstuff.HotStuffProposalValidationProviderSuite"` passed on 2026-05-17.

### Phase 3 - Vote Path Enforcement

- [x] Gate pacemaker auto-vote before signing a local vote.
- [x] Gate public/manual vote helper surfaces or document the remaining unsafe
      lower-level boundary.
- [x] Ensure rejected validation never signs or applies a local vote.
- [x] Ensure unavailable/held validation never signs or applies a local vote.
- [x] Assert diagnostics for every vote-suppression path.

Phase 3 verification evidence:

- `sbt "nodeJvm/testOnly org.sigilaris.node.jvm.runtime.consensus.hotstuff.HotStuffProposalValidationPacemakerIntegrationSuite org.sigilaris.node.jvm.runtime.consensus.hotstuff.HotStuffProposalValidationProviderSuite org.sigilaris.node.jvm.runtime.consensus.hotstuff.HotStuffRuntimeSchedulingIntegrationSuite"` passed on 2026-05-17.

### Phase 4 - Artifact Sink And Catch-Up Interaction

- [x] Reconcile the new provider with existing sink-level proposal validation.
- [x] Preserve structural HotStuff validation before application validation.
- [x] Implement the Phase 0 retention/relay policy for unavailable proposals.
- [x] Add tests for rejected proposal application behavior.
- [x] Add tests for unavailable proposal behavior and retry/catch-up semantics.

Phase 4 verification evidence:

- `sbt "nodeJvm/testOnly org.sigilaris.node.jvm.runtime.consensus.hotstuff.HotStuffProposalValidationPacemakerIntegrationSuite org.sigilaris.node.jvm.runtime.consensus.hotstuff.HotStuffRuntimeSchedulingIntegrationSuite"` passed on 2026-05-17.
- Phase 4 does not wire application validation into `InMemoryHotStuffArtifactSink`; the sink remains structural/generic, and the vote path suppresses local votes while retaining structurally valid proposals.

### Phase 5 - Docs And Embedder Handoff

- [x] Update EN Typelevel HotStuff docs.
- [x] Update KO Typelevel HotStuff docs.
- [x] Document the difference between proposal input and proposal validation
      hooks.
- [x] Document production require-validator guidance for automatic consensus.
- [x] Add a small fake embedder example in tests or docs.

Phase 5 verification evidence:

- `sbt ";unidoc;tlSite"` passed on 2026-05-17 after the EN/KO site and embedder handoff updates.

## Test Plan

- Run focused HotStuff unit suites after adding ADTs and policy decisions.
- Run pacemaker integration suites after vote-path enforcement.
- Run artifact sink suites after retention/relay behavior changes.
- Run `sbt "nodeJvm/test"` before commit.
- Run `sbt ";unidoc;tlSite"` after public API and site documentation updates.
- Confirm failure-path diagnostics are asserted in tests, not only logged; this
  is the test counterpart for acceptance criterion 5.
- Confirm no Sigilaris production code imports downstream embedder packages,
  verified statically through build graph inspection, a grep gate, or an
  equivalent CI rule.

## Risks And Mitigations

- **Risk: application-specific policy leaks into Sigilaris.**
  - Keep the provider request/result in HotStuff terms and leave tx/session
    metadata lookup to embedders.

- **Risk: temporary application data unavailability is treated as permanent
  proposal invalidity.**
  - Separate rejected and unavailable/held results and lock artifact retention
    semantics before wiring the sink.

- **Risk: compatibility allow-all hides production misconfiguration.**
  - Keep allow-all explicit and add require-validator bootstrap validation for
    automatic consensus production profiles.

- **Risk: manual vote helpers bypass the application gate.**
  - Route every local proposal-vote signing surface through the provider or
    document and rename lower-level unsafe APIs so embedders do not use them
    accidentally.

- **Risk: validation diagnostics become noisy under repeated pacemaker ticks.**
  - Include proposal/voter/reason keys and add deduplication or rate limiting
    where repeated suppression is expected.

- **Risk: validation provider latency stalls pacemaker progress.**
  - Keep timeout ownership explicit in Phase 0 and make provider failure/timeout
    outcomes diagnostic and non-signing.

## Acceptance Criteria

1. Embedders can configure an application-neutral proposal validation provider
   through the public HotStuff runtime/bootstrap surface.
2. Automatic local vote emission calls the provider before signing a proposal
   vote.
3. Rejected, unavailable, failed, or missing-required validation never emits a
   local vote.
4. Accepted validation preserves the current successful vote path.
5. Runtime diagnostics identify why a proposal vote was suppressed.
6. Compatibility allow-all mode preserves existing tests and behavior.
7. Require-validator mode fails fast when automatic consensus would otherwise
   run without an application validation provider.
8. Typelevel EN/KO docs describe the proposal input and proposal validation
   hooks as separate embedder contracts.
9. No Sigilaris production code imports any embedder application package.

## Follow-Ups

- Downstream embedders should implement their own validators that map proposal
  tx ids and block ids to local admission, state, policy, or manifest stores.
- Application-specific fairness, batch splitting, and admission eviction policy
  remain downstream concerns unless a future Sigilaris-generic scheduler policy
  is needed.
- Durable application validation audit logs and production metrics can be added
  after the core hook and diagnostics prove stable.
- If unavailable/held proposal handling requires new long-lived semantics, split
  that decision into a focused ADR before expanding beyond the in-memory
  runtime.
