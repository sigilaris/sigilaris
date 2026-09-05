# 0032 - Application Safety And Exact Pipeline Operational Hardening Plan

## Status

Proposed — post-M2 backlog; not an M2 publication gate.

## Created

2026-09-06

## Last Updated

2026-09-06

## Background

The fifth review of plan 0031 accepted the implementation with no CRITICAL or WARNING findings. It reconfirmed the compact exact-pipeline
indexes, primary-write invalidation, keyed recovery diagnostics, bounded page decoding, and legacy drain-repair observation. The fourth-review
source gate passed 1,910 tests; the fifth review separately reran the 27 exact-store/admission and 15 application-safety tests.

The remaining suggestions concern defensive error handling, future reuse of startup-only recovery, and explicit diagnostic contracts. They do not
block the reviewed M2 release. This plan tracks their implementation separately so the completed 0031 history remains a record of completed work,
not a growing list of unimplemented requirements.

M2 preparation completed two documentation-only clarifications: repair warnings are best effort and the journal is the authoritative record;
exact-store `list` order is backend-specific and does not promise cross-backend string sorting. They are completed prerequisites outside this plan's code phases.

## Goal

- Keep successful safety recovery independent of both synchronous and effectful observer failures.
- Return a typed, actionable result when the secondary idempotency write fails, while retaining safe replay and repair from the durable primary.
- Make index reconstruction safe to invoke under concurrency without adding a public online-recovery API.
- Distinguish corrupted or invalidated derived state from legitimate submission conflicts, with direct regression coverage.

## Scope

- Defer custom observer invocation into `IO` and exercise both failure modes directly.
- Type secondary idempotency write failures and verify retry behavior at both sides of the durable write.
- Serialize index reconstruction with indexed admission and request lookup; define failure and cancellation behavior.
- Report an indexed pipeline whose primary row is absent as corruption, and preserve the existing owner's identity in genuine conflict diagnostics.
- Cover indexed request lookup while indexes are invalidated.
- Review whether a dedicated `IndexesInvalidated` failure case improves the public error contract enough to justify an API change.

## Non-Goals

- No M2 version bump, merge, tag, staging, or publication is part of this plan.
- No change to inclusion-height validity, terminal replay, profile rotation, drain repair semantics, signature domains, or canonical codecs.
- No reliable event-delivery protocol: a journaled repair may have no corresponding warning after a crash.
- No public online-rebuild operation, new automatic retry loop, or guarantee that storage-engine errors are transient.
- No journal retention/checkpointing, primary-and-alias namespace redesign, event-driven wait service, or V2 activation.
- No application-specific transaction or funds semantics.

## Related ADRs And Docs

- [0031: Height-Bounded Locks And Exact Pipeline Release Plan](0031-height-bounded-application-locks-and-exact-pipeline-dependencies-plan.md)
- [ADR-0032: Stage-Based Transaction Pipeline API](../adr/0032-stage-based-transaction-pipeline-api.md)
- [ADR-0036: Height-Bounded Application Locks And Explicit Pipeline Dependencies](../adr/0036-height-bounded-application-locks-and-explicit-pipeline-dependencies.md)
- Internal operations reference: `docs/dev/application-safety-operations-runbook.md` (canonical repository only; public recovery guidance is in the M2 guide).
- [M2 Integration And Migration Guide](../releases/v0.3.0-M2-integration-guide.md)
- [Plans Guide](README.md)

## Decisions To Lock Before Implementation

1. The immutable primary remains the authority. A secondary-alias failure after primary/index publication must not invalidate an otherwise coherent
   index or be reported as if the primary had rolled back. Replaying the same submission repairs the alias without creating a second primary.
2. Define error reason, diagnostic detail, and caller behavior for alias-write failure, invalidated indexes, and indexed-but-missing primary before
   adding enum cases. Audit exhaustive matches and HTTP mappings. `IndexesInvalidated` is a decision to review, not a preapproved API addition.
3. Reconstruction acquires the existing store gate for its read/validate/publish boundary. Define how an attempted rebuild becomes unavailable on
   failure or cancellation so stale indexes cannot become usable again; do not expose a partially rebuilt index.
4. Observer failures are diagnostic-only. Use `IO.defer` around the invocation and handle its failed effect without changing journal authority or
   introducing a warning re-delivery guarantee.
5. Preserve current protocol/storage formats. Document public failure-contract changes in the next release migration guide; promote any new
   long-lived recovery policy to an ADR if the implementation requires one.

## Change Areas

### Code

- `modules/node-jvm/.../storage/swaydb/SwayDbApplicationSafetyStore.scala`: observer invocation boundary.
- `modules/node-jvm/.../storage/swaydb/SwayDbExactTxPipelineStore.scala`: alias-write failure handling and reconstruction gate/state lifecycle.
- `modules/node-jvm/.../runtime/txpipeline/ExactTxPipelineStore.scala`: corruption classification and any reviewed failure-model changes.
- Exact-admission and HTTP failure mappings only where the chosen typed contract requires changes.

### Tests

- `SwayDbApplicationSafetyStoreSuite`: repair success despite synchronous throw or failed `IO` observers, including restart idempotence.
- `SwayDbExactTxPipelineStoreSuite`: alias-write fault injection, indexed lookup invalidation, reconstruction concurrency, and missing primary.
- Exact admission/shared store validation suites: genuine collision owner identity and reviewed error propagation.

### Docs

- Operations runbook: typed failure interpretation and required operator action.
- Integration/migration guide: any source/API changes and their next-release boundary.
- This plan: phase evidence, self-review results, and lessons applied to remaining phases.

## Implementation Phases

For every phase, implement its deliverables, repeat self-review and fixes until `No finding`, apply lessons to the remaining plan, and commit with
a detailed message. Completion below refers to future implementation; creating this document does not complete these phases.

### Phase 0: Failure And Recovery Contract (Required Before Code)

- Audit store failure consumers and select typed classifications without changing existing genuine-conflict semantics.
- Decide whether to add `IndexesInvalidated` now or retain a documented existing category; record the compatibility tradeoff either way.
- Specify alias-write retry outcomes and reconstruction failure/cancellation state under the gate.
- Gate: every later acceptance test has an explicit expected state, error category, and recovery action.

### Phase 1: Observer Failure Isolation (First Priority)

- Wrap observer invocation in `IO.defer` and suppress diagnostic failures after the journal commit.
- Test a callback that throws before returning `IO`, and one that returns `IO.raiseError`.
- Gate: both sessions recover the repaired snapshot; the repair remains journaled; the next restart produces no additional repair entry or callback.

### Phase 2: Typed Idempotency Write Failure (First Priority)

- Capture secondary write errors into the failure contract selected in Phase 0.
- Inject failures before an alias put and after a put that actually persisted but reported failure.
- Gate: neither result escapes as an untyped effect failure; primary and indexes remain usable; the same submission retries as `created = false`
  and leaves a valid alias. A mismatched retry still fails and cannot overwrite ownership. Rebuilding a session must preserve those outcomes.

### Phase 3: Reconstruction And Corruption Diagnostics (Second Priority)

- Place reconstruction under the same semaphore as create/replay and request-identity lookup, respecting the Phase 0 cancellation policy.
- Use controlled barriers to verify a concurrent create or indexed lookup cannot cross the reconstruction publication boundary.
- Classify index membership with an absent primary as corruption instead of ordinary `PipelineConflict`.
- Add direct invalidated request-lookup and existing-owner collision assertions; cover any new failure case through its consumers.
- Gate: successful reconstruction publishes one coherent replacement; failed/canceled reconstruction cannot expose partial or stale valid indexes;
  corruption and ordinary collision diagnostics are distinct and identify the affected pipeline or durable key.

### Phase 4: Integrated Verification And Documentation

- Run the affected exact-store/admission/application-safety suites and relevant HTTP suites if failure mappings changed.
- Run the repository's required formatting and source test gates once against the final implementation; record actual counts and commands.
- Update operational and migration documentation for the selected contract, preserving the best-effort warning and backend-specific ordering notes.
- Gate: final self-review reports `No finding`; checklists and evidence agree; release work remains a separate, explicitly authorized workflow.

## Test Plan

- Fault tests use deterministic storage wrappers and `Deferred`/barriers rather than sleeps or storage-engine lock-release timing.
- Observer tests assert durable recovery behavior, not logger implementation details or exactly-once event delivery across crashes.
- Alias tests distinguish absence from an ambiguous successful put, then verify replay, hash/ownership rejection, and restart reconstruction.
- Reconstruction tests cover create and request lookup serialization, failed validation, and cancellation under the chosen contract.
- Corruption tests explicitly create an indexed-but-missing primary; conflict tests use two real submissions and assert the existing owner.
- Retain the existing no-list admission, compact-index, paginated decode, terminal replay after rotation, and legacy repair idempotence regressions.

## Risks And Mitigations

- New failure cases can break exhaustive client matches: audit consumers and document the source migration before changing the enum.
- Nested gate acquisition can deadlock: keep a clear distinction between externally gated entry points and private under-gate helpers.
- A canceled or failed rebuild can accidentally revive stale state: define the invalidation boundary first and test failure paths directly.
- Typing alias-write failure can encourage blind retries: specify immutable same-submission replay and preserve conflict/hash checks.
- Observer tests can overpromise delivery: assert journal durability and failure isolation, with warnings explicitly best effort.

## Acceptance Criteria

1. Both synchronous and effectful observer failures leave safety recovery complete and a second restart idempotent.
2. Alias-write failures produce typed results; valid retries repair or confirm the alias without duplicating the durable primary.
3. Reconstruction, indexed lookup, and primary/index publication share a coherent gate boundary, including failure and cancellation.
4. Indexed missing primaries and invalidated indexes cannot be accepted as new work or mistaken for a normal submission conflict.
5. Tests directly prove invalidated request-lookup rejection and existing-owner conflict diagnostics.
6. The `IndexesInvalidated` API decision and any migration implications are recorded, whether the case is introduced or deferred.
7. Each phase has a `No finding` review record, applied lessons, verification evidence, and a detailed commit; final required checks pass.
8. No M2 publication requirement is added retroactively; completing this plan is independent of the reviewed M2 release.

## Checklist

### Phase 0: Failure And Recovery Contract

- [ ] Audit error consumers and decide the typed failure contract, including `IndexesInvalidated`.
- [ ] Specify alias retry and reconstruction failure/cancellation behavior; review and commit the decisions.

### Phase 1: Observer Failure Isolation

- [ ] Defer invocation and cover synchronous/effectful failures plus restart idempotence.
- [ ] Reach `No finding`, apply lessons, and commit with verification evidence.

### Phase 2: Typed Idempotency Write Failure

- [ ] Type both alias-write fault outcomes and verify replay/ownership/restart invariants.
- [ ] Reach `No finding`, apply lessons, and commit with verification evidence.

### Phase 3: Reconstruction And Corruption Diagnostics

- [ ] Gate reconstruction and verify concurrent operation, failure, and cancellation behavior.
- [ ] Distinguish missing-primary corruption and test invalidated lookup/existing-owner diagnostics.
- [ ] Reach `No finding`, apply lessons, and commit with verification evidence.

### Phase 4: Integrated Verification And Documentation

- [ ] Complete affected suites and required repository gates; record actual results.
- [ ] Synchronize runbook/migration guidance and review all acceptance criteria.
- [ ] Reach `No finding` and commit the final evidence; mark this plan `Done` only after implementation.

## Follow-Ups

The broader items already deferred by 0031 remain separately scoped work: bounded/checkpointed journal retention, a single-namespace exact
primary/alias transaction, event-driven wait notification, canonical failure-aware partial helpers, and V2 activation. They are not implicit
deliverables of this plan. A direct InMemory corruption fixture remains unnecessary unless an appropriate invariant-preserving test seam becomes
available; shared validation and durable-store fault tests remain the existing coverage.
