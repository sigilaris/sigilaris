# 0025 - Stage-Based Transaction Pipeline API Plan

## Status
Complete

## Created
2026-06-24

## Last Updated
2026-06-25

## Background

ADR-0031 and Plan 0023 added the HotStuff-side safety surface for certified
ancestor dependent transaction pipelining:

- certified block observations;
- parent-branch proposal input context;
- parent-branch proposal validation context;
- dependency unavailable/conflict reason codes;
- event-driven wake hooks for application work and certified observations.

Plan 0024 then added bounded descendant finality drive so a warm local cluster
can finalize dependent tx-bearing blocks without relying on unbounded empty
proposal chains.

ADR-0032 defines the next layer above those runtime primitives: a stage-based
node API where clients submit an ordered outer vector of stages, each stage
contains same-stage proposal candidates, and each later stage waits for the
previous stage to become a certified ancestor on the candidate branch. Sigilaris
does not currently have a stable public application transaction submission HTTP
contract in the core runtime, so this work must introduce the pipeline API
without moving application transaction semantics into consensus artifacts.

## Goal

Implement the ADR-0032 pipeline contract as an application-neutral node surface
that embedders can expose as `POST /tx-pipeline` and
`GET /tx-pipeline/{pipelineId}`.

The concrete target behavior is:

1. a client can submit all stages in one logical request;
2. stage `0` is admitted into normal proposal eligibility;
3. stage `N > 0` is accepted into a pipeline-held state until stage `N - 1`
   is certified on the candidate parent branch;
4. proposal input keeps held-stage work out of proposals until the certified
   ancestor barrier is satisfied;
5. vote validation still relies on ADR-0031 branch dependency validation, not
   consensus-visible stage metadata;
6. query and submit responses expose pipeline, stage, and transaction status
   separately;
7. idempotent retries can return the existing pipeline snapshot without
   duplicating submitted work.

## Scope

- Add application-neutral pipeline request, response, status, identity, and
  idempotency models.
- Add a durable pipeline metadata store with in-memory test implementation and
  JVM/SwayDB implementation or equivalent production-backed store.
- Add a pipeline admission service that validates shape and payload limits,
  computes stable transaction hashes through an embedder-supplied hasher, and
  persists the initial stage state.
- Add a stage eligibility engine that turns ADR-0032 barriers into
  proposal-input-ready work using ADR-0031 branch context.
- Add projection logic that correlates submitted stages with proposal,
  certification, finalization, failure, and unavailable observations.
- Add Tapir/Armeria endpoint definitions for the dedicated pipeline API.
- Add a one-stage normalizer that future single-transaction submission can
  reuse.
- Add tests that prove stage barriers, idempotency, status projection, and
  wait modes without encoding application ledger semantics in Sigilaris.

## Non-Goals

- Do not standardize application transaction bodies, reducer semantics, UTXO
  output ids, account balances, payment escrow rules, or application receipt
  values.
- Do not embed `pipelineId`, stage indexes, transaction indexes, or idempotency
  keys into HotStuff proposals, QCs, votes, or block headers.
- Do not weaken ADR-0031 dependency validation or ADR-0029 tx uniqueness.
- Do not make later-stage transactions rely on same-block execution order.
- Do not implement pipeline cancellation, retry policy, SSE, or webhook
  delivery in the first implementation.
- Do not require every embedder to expose the public HTTP path exactly as
  `/tx-pipeline`; the semantic contract is reusable even when an embedding API
  chooses a versioned path.
- Do not ship a public single-transaction endpoint as part of this plan unless
  an embedding node API explicitly needs it; only the internal one-stage
  normalization helper is in scope.

## Related ADRs And Docs

- [ADR-0028: HotStuff Finalization Observability And Embedder Failure Semantics](../adr/0028-hotstuff-finalization-observability-and-embedder-failure-semantics.md)
- [ADR-0029: HotStuff Proposal Tx Uniqueness Policy](../adr/0029-hotstuff-proposal-tx-uniqueness-policy.md)
- [ADR-0031: Certified Ancestor Dependent Transaction Pipelining](../adr/0031-certified-ancestor-dependent-transaction-pipelining.md)
- [ADR-0032: Stage-Based Transaction Pipeline API](../adr/0032-stage-based-transaction-pipeline-api.md)
- [HotStuff Proposal Provider Handoff](../dev/hotstuff-proposal-input-provider-handoff.md)
- [HotStuff Low-Latency Profile](../dev/hotstuff-low-latency-profile.md)
- [0023 - Certified Ancestor Dependent Transaction Pipelining Plan](0023-certified-ancestor-dependent-transaction-pipelining-plan.md)
- [0024 - HotStuff Bounded Descendant Finality Drive Plan](0024-hotstuff-bounded-descendant-finality-drive-plan.md)

## Decisions To Lock Before Implementation

1. **ADR promotion and compatibility statement**
   - Promote or revise ADR-0032 before public endpoint work begins.
   - State explicitly that there is no prior stable Sigilaris transaction
     submission endpoint to preserve.
   - Decide whether the initial implementation is marked experimental,
     versioned, or stable for embedders.

2. **Wire payload and transaction identity**
   - Lock the public signed transaction wire representation. The likely first
     shape is opaque encoded transaction bytes or strings, with application
     decoding owned by the embedder.
   - Lock how `txHash` is computed. The hash must be deterministic over the
     submitted signed transaction payload or supplied by an embedder-owned
     canonical hasher.
   - Lock whether duplicate transaction hashes in one pipeline are rejected,
     deduplicated, or allowed only in different idempotent replays. The
     conservative first policy should reject duplicate tx hashes in one
     canonical pipeline request.

3. **Shape validation and limits**
   - Reject empty `stages`.
   - Reject empty stages unless a later ADR explicitly defines empty-stage
     semantics.
   - Add configurable limits for stage count, transactions per stage, total
     transactions, payload bytes, and accepted-but-unfinalized pipelines.
   - Preserve inner-stage ordering in responses even when proposal input splits
     a stage across blocks.

4. **Barrier completion policy**
   - A later stage becomes eligible only when every successful transaction in
     the previous stage has a certified block on the candidate parent branch.
   - If a previous stage is split across multiple blocks, the barrier requires
     certified ancestry for every required block placement.
   - Lock how partial application failure inside a stage affects later stages:
     the first implementation should mark the stage `failed` when the pipeline
     cannot satisfy the public barrier and propagate failure to dependent
     stages.
   - `unavailable` means the local node cannot prove barrier satisfaction from
     current branch ancestry; it is not a terminal application failure.

5. **Status state machine**
   - Lock pipeline-level values:
     `accepted`, `running`, `certified`, `finalized`, `failed`,
     `partiallyFailed`.
   - Lock stage and transaction pipeline states:
     `accepted`, `held`, `eligible`, `proposed`, `certified`, `finalized`,
     `failed`, `unavailable`.
   - Keep application transaction status in a separate optional projection
     field whose values are owned by the embedder.
   - Define deterministic summary rules so a pipeline snapshot can be rebuilt
     from persisted metadata plus consensus observations.

6. **Idempotency**
   - Lock the caller-supplied idempotency carrier, likely an `Idempotency-Key`
     header for HTTP and an optional field for in-process service calls.
   - Store a canonical payload hash after request normalization.
   - Repeated submissions with the same idempotency key and payload hash return
     the existing snapshot.
   - Repeated submissions with the same key and different payload hash fail
     with an idempotency conflict.
   - `pipelineId` remains server-generated and non-consensus metadata.

7. **Wait modes and HTTP behavior**
   - Lock the portable wait modes: `accepted`, `certified`, `finalized`.
   - `accepted` returns only after pipeline metadata and stage payloads are
     durably accepted.
   - `certified` and `finalized` wait for every required stage to reach the
     requested boundary or a terminal failure/unavailable condition.
   - Client disconnect or request timeout must not cancel the accepted
     pipeline.
   - Lock response codes for validation failure, idempotency conflict, accepted
     snapshot, and terminal failure snapshots.

8. **Runtime integration boundary**
   - Sigilaris owns pipeline metadata, stage barriers, branch observations, and
     public projection.
   - Embedders own transaction decoding, application admission validation,
     proposal body construction, state roots, body roots, and application
     status values.
   - The pipeline engine should expose a small provider-facing API for eligible
     stage work rather than forcing a default application proposal body.
   - Existing proposal providers should be able to compose the pipeline
     eligibility queue with application-specific validation and block assembly.

9. **Retention, pruning, and codec placement**
   - Lock terminal-state retention and pruning before durable store
     implementation begins.
   - Decide whether terminal pipelines are retained indefinitely, retained for
     a configured horizon, or pruned after a terminal-state grace period.
   - If pruning is deferred from the first implementation, mark the durable
     store as non-production-ready for unbounded public traffic until a
     retention policy is added.
   - Lock codec placement. The default plan is shared request/response codecs
     in `node-common` and JVM transport adapters in `node-jvm`.

## Phase 0 Locked Decisions

ADR-0032 is accepted for the first implementation. The locked contract is:

- wire transactions are opaque string envelopes;
- `POST /tx-pipeline` has required `stages` and required `waitFor`;
- portable wait modes are `accepted`, `certified`, and `finalized`;
- HTTP idempotency uses `Idempotency-Key`;
- canonical idempotency payload hashes are SHA-256 over the normalized submit
  shape, excluding the idempotency key;
- transaction hashes are computed by an embedder-supplied canonical hasher;
- duplicate transaction hashes inside one canonical pipeline request are
  rejected;
- initial bounds are `64` stages, `1024` transactions per stage, `4096` total
  transactions, `1048576` bytes per transaction payload, `16777216` total
  payload bytes, and `10000` accepted-but-nonterminal pipelines;
- shared models and Circe codecs live in `node-common`, while JVM admission,
  storage, wait coordination, Tapir endpoints, and Armeria adapters live in
  `node-jvm`;
- validation failures before durable acceptance use stable reason codes and
  optional stage/transaction locations;
- successful newly accepted or running `POST` snapshots use HTTP `202`,
  idempotent replay/query/terminal snapshots use `200`, validation failures use
  `400`, idempotency conflicts use `409`, and missing pipeline queries use
  `404`;
- accepted metadata, payload references, transaction hashes, placements, and
  observation links must survive restart;
- terminal pipelines are retained for a configurable grace period, default
  `24 hours`, and then become eligible for explicit pruning.

## Change Areas

### Code

- `modules/node-common/shared/src/main/scala/org/sigilaris/node/txpipeline`
  - application-neutral request, response, status, barrier, idempotency, and
    projection models;
  - stable validation errors and canonical payload hash model.
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/txpipeline`
  - pipeline admission service;
  - pipeline store algebra;
  - stage eligibility engine;
  - observation-to-status projection;
  - wait-mode coordinator;
  - embedder-facing transaction hasher/admission hooks.
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff`
  - integration helpers that consume `HotStuffProposalInputBranchContext`,
    certified observations, finalized observations, and pacemaker wake hooks;
  - optional diagnostics for stage-held, stage-eligible, stage-proposed, and
    barrier-unavailable decisions.
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/storage`
  - in-memory and durable pipeline metadata store implementations;
  - storage layout additions if SwayDB is used.
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria`
  - Tapir endpoint definitions, wire codecs, server adapter, and generated
    client tests for `POST /tx-pipeline` and `GET /tx-pipeline/{pipelineId}`.

### Tests

- Model validation and canonicalization tests.
- Idempotency replay/conflict tests.
- Pipeline store durability and restart reconstruction tests.
- Stage eligibility tests over synthetic branch contexts.
- Status projection tests for proposed, certified, finalized, failed,
  partially failed, unavailable, and split-stage cases.
- Wait-mode tests for accepted/certified/finalized boundaries and terminal
  failure wake-up.
- Armeria/Tapir loopback tests for request/response shape and error mapping.
- HotStuff integration tests with fake application transactions and a fake
  provider that composes pipeline eligibility with proposal input.

### Docs

- Add this plan and keep ADR-0032 linked from related implementation docs.
- Update proposal provider handoff docs with pipeline-aware queue composition.
- Add a node API guide or OpenAPI export for the pipeline endpoint.
- Add release notes once the target version is selected.
- Add migration notes for embedders that currently implement dependent
  transaction queues outside Sigilaris.

## Implementation Phases

### Phase 0: Contract Lock And ADR Promotion

- Review ADR-0032 and either promote it from Proposed or revise unresolved API
  details before implementation.
- Lock wire request/response shape, path names, idempotency carrier, wait-mode
  HTTP behavior, status values, and shape limits.
- Decide whether the first public transaction payload is opaque bytes, a string
  envelope, or an embedder-supplied codec projection.
- Lock codec placement between shared models/codecs and JVM transport codecs.
- Decide how the pipeline service returns application admission failures before
  any consensus proposal exists.
- Define canonical payload hashing for idempotency conflicts.
- Define the first durable store requirements and restart behavior for
  accepted pipelines.
- Define terminal-state retention and pruning requirements for the durable
  pipeline store.

### Phase 1: Pipeline Domain Model And Store

- Add request, response, status, barrier, transaction reference, stage
  placement, idempotency, and validation models.
- Add non-empty shape validation and configurable bounds.
- Add canonical request normalization, including one-stage normalization for
  future single-transaction submit.
- Add a store algebra that can:
  - create a pipeline atomically;
  - look up by `pipelineId`;
  - look up by idempotency key;
  - persist stage/transaction status transitions;
  - persist proposal/certification/finalization observations;
  - rebuild a snapshot after restart.
- Implement in-memory store for tests and a durable JVM store for production
  node usage.

### Phase 2: Admission And Idempotency Service

- Add an admission service that accepts a full pipeline request as one logical
  submission.
- Compute transaction hashes through the locked hasher boundary.
- Validate payload and stage limits before persisting.
- Persist the initial pipeline with stage `0` eligible or accepted according to
  the locked state machine and later stages held.
- Implement idempotent replay and conflict behavior.
- Notify the HotStuff runtime through `notifyApplicationWorkAvailable` after
  newly eligible stage work is accepted.
- Return a pipeline-shaped accepted snapshot.

### Phase 3: Stage Eligibility And Proposal Input Integration

- Add a pipeline eligibility API that proposal providers can call with
  `HotStuffProposalInputBranchContext`.
- Keep later-stage work out of selection until the previous stage barrier is
  satisfied on the candidate parent branch.
- Support stage splitting when provider or runtime tx limits select only part
  of a stage.
- Track per-stage and per-transaction placement into proposal tx sets without
  adding pipeline metadata to HotStuff artifacts.
- Map branch-context gaps to `unavailable` or held diagnostics rather than
  false application failure.
- Ensure proposal input still applies ADR-0029 tx exclusion before returning
  eligible pipeline work.
- Wake proposal input when certified observations satisfy a barrier.

### Phase 4: Observation Projection And Wait Modes

- Correlate proposals, certified block observations, finalized observations,
  and failed/unavailable validation outcomes back to pipeline stages.
- Implement deterministic summary rules for pipeline, stage, and transaction
  statuses.
- Represent split stages with per-block consensus metadata.
- Implement barrier failure propagation from a failed stage to dependent later
  stages.
- Implement wait coordinators for `accepted`, `certified`, and `finalized`.
- Ensure waiters are released on terminal failure or idempotent replay.
- Keep optional timestamps such as `certifiedObservedAt` and
  `finalizedObservedAt` diagnostic-only.

### Phase 5: HTTP API And Wire Compatibility

- Add Tapir endpoints for:
  - `POST /tx-pipeline`;
  - `GET /tx-pipeline/{pipelineId}`.
- Add wire codecs for request, response, statuses, barriers, wait modes,
  idempotency conflict, validation failure, and not-found responses.
- Add an Armeria adapter and endpoint-derived client tests following the gossip
  transport pattern.
- Export or update OpenAPI documentation if the node API export is enabled.
- Verify the endpoint can be mounted under an embedding API path without
  changing the semantic model.

### Phase 6: End-To-End Verification And Handoff

- Add a fake application provider that models `T1 -> T2 -> T3` stages without
  standardizing UTXO semantics.
- Verify stage `0` can be proposed immediately, stage `1` remains held until
  stage `0` is certified on the candidate branch, and stage `2` remains held
  until stage `1` is certified.
- Verify same-stage transactions can be proposed together and can also be split
  across multiple blocks when bounds require it.
- Verify query snapshots identify the stage limiting progress.
- Verify idempotent retry returns the same pipeline snapshot and mismatched
  retry returns a conflict.
- Update provider handoff docs, node API docs, and release notes.

## Test Plan

- `sbt "nodeCommon/testOnly *TxPipeline*"` for shared model,
  canonicalization, status, and shared wire-codec tests.
- `sbt "nodeJvm/testOnly *TxPipeline*"` for admission, store, eligibility,
  projection, wait-mode, JVM transport codecs, and Armeria loopback tests.
- `sbt "nodeJvm/testOnly *HotStuffProposalInputProviderSuite *HotStuffProposalInputPacemakerIntegrationSuite"`
  for branch-context and pacemaker wake compatibility.
- `sbt "nodeJvm/testOnly *HotStuffProposalValidationPacemakerIntegrationSuite"`
  for ADR-0031 validation compatibility.
- Add deterministic integration coverage for:
  - one-stage pipeline normalization;
  - two-stage certified-ancestor barrier;
  - three-stage chain where the middle stage fails;
  - split stage across multiple blocks;
  - branch ancestry unavailable;
  - idempotency replay and conflict;
  - restart after `accepted` before proposal.

## Risks And Mitigations

- **Application-neutral tx payload boundary is too vague**
  - Mitigation: Phase 0 locks the wire payload and transaction hasher boundary
    before endpoint implementation begins.
- **Pipeline state diverges from consensus observations**
  - Mitigation: persist only node-local metadata and derive consensus progress
    from HotStuff proposal/certified/finalized observations with deterministic
    rebuild tests.
- **Stage splitting makes barrier semantics ambiguous**
  - Mitigation: model stage placements explicitly and require certification of
    every required successful placement before later-stage eligibility.
- **Long `waitFor=finalized` requests tie up server resources**
  - Mitigation: implement releasable waiters, bounded server-side wait policy,
    and query-after-submit behavior; client disconnect does not cancel the
    pipeline.
- **Clients mistake `certified` for final settlement**
  - Mitigation: response docs and API guide label certified as pipeline
    progress; final settlement remains application/finality-specific.
- **Idempotency canonicalization creates false conflicts**
  - Mitigation: canonicalize after decoding and normalization, store the
    canonical hash, and test semantically identical request encodings.
- **Pipeline metadata grows without bound**
  - Mitigation: lock retention configuration and terminal-state pruning policy
    in Phase 0 before treating the durable store as production-ready.
- **Proposal providers bypass the pipeline eligibility queue**
  - Mitigation: expose a small, easy-to-compose provider API and document that
    embedders using the pipeline endpoint must source matching tx work through
    it.

## Acceptance Criteria

1. A pipeline request with multiple stages can be durably accepted and queried
   by `pipelineId`.
2. Repeating the same idempotent request returns the existing snapshot; changing
   the payload under the same idempotency key returns a conflict.
3. Stage `N + 1` is never selected for proposal before stage `N` satisfies its
   certified-ancestor barrier on the candidate parent branch.
4. Query responses preserve request stage/transaction ordering and report
   pipeline, stage, and transaction status separately.
5. Split-stage projections report per-block consensus metadata and do not imply
   one stage always maps to one proposal.
6. `waitFor=accepted`, `waitFor=certified`, and `waitFor=finalized` return only
   after the requested boundary or terminal failure is observed.
7. A failed barrier propagates to dependent later stages according to ADR-0032.
8. No pipeline metadata is embedded into HotStuff consensus artifacts.
9. The proposal provider handoff docs explain how embedders compose the
   pipeline eligibility API with application-specific block construction.
10. The durable store has a locked terminal-state retention/pruning policy, or
    the first release explicitly marks the store as non-production-ready for
    unbounded public traffic.

## Checklist

### Phase 0: Contract Lock And ADR Promotion
- [x] ADR-0032 promoted or revised.
- [x] Wire payload, status, wait-mode, and error shapes locked.
- [x] Idempotency carrier and canonical payload hash locked.
- [x] Shape limits and duplicate transaction policy locked.
- [x] Shared/JVM codec placement locked.
- [x] Durable store and restart behavior locked.
- [x] Terminal-state retention and pruning policy locked.

### Phase 1: Pipeline Domain Model And Store
- [x] Shared pipeline request/response/status models added.
- [x] Shape validation and configurable bounds added.
- [x] One-stage normalization helper added.
- [x] Store algebra added.
- [x] In-memory and durable store implementations added.
- [x] Restart reconstruction tests added.

### Phase 2: Admission And Idempotency Service
- [x] Pipeline admission service implemented.
- [x] Transaction hashing boundary implemented.
- [x] Idempotent replay and conflict handling implemented.
- [x] Initial stage state persistence implemented.
- [x] Application-work wake hook invoked for newly eligible work.
- [x] Accepted snapshot response tests added.

### Phase 3: Stage Eligibility And Proposal Input Integration
- [x] Branch-context eligibility API added.
- [x] Held-stage barrier logic implemented.
- [x] Stage splitting and placement tracking implemented.
- [x] ADR-0029 tx exclusion compatibility covered.
- [x] Certified-observation wake for newly eligible stages covered.

### Phase 4: Observation Projection And Wait Modes
- [x] Proposal/certified/finalized observation projection implemented.
- [x] Pipeline/stage/transaction summary rules implemented.
- [x] Failure and unavailable propagation implemented.
- [x] Wait-mode coordinator implemented.
- [x] Split-stage projection tests added.

### Phase 5: HTTP API And Wire Compatibility
- [x] Tapir endpoint specs added.
- [x] Armeria adapter added.
- [x] Endpoint-derived client or loopback tests added.
- [x] Error mapping tests added.
- [x] OpenAPI or API docs updated.

### Phase 6: End-To-End Verification And Handoff
- [x] Fake staged application provider added.
- [x] Two-stage and three-stage barrier integration tests added.
- [x] Idempotent retry integration test added.
- [x] Stage-limiting query snapshot test added.
- [x] Provider handoff docs updated.
- [x] Release notes drafted.

## Follow-Ups

- 0.2.8 publish-readiness follow-up completed before final artifact validation:
  `CertifiedBlockObservation` now carries `txSet`, pipeline projection records
  certified placements from QC observations even without local proposal
  metadata, and projection hash matching normalizes stored tx hashes with
  `Locale.ROOT`.
- 0.2.8 v1 compatibility note: pipeline metadata and `pipelineId` remain
  node-local. Distributed pipeline metadata fanout is not part of this release;
  embedding smoke harnesses may seed all validators and measure the first
  finalized local pipeline as a best-case approximation.
- 0.2.8 release coordinate validation uses `0.2.8`, not `0.2.8-SNAPSHOT`, for
  both Sigilaris publishLocal and the BBGO downstream compile/assembly/smoke
  check.
- 0.2.8 pre-publish verification completed on 2026-06-25:
  - `sbt "nodeJvm / Test / compile" "nodeJvm / testOnly *TxPipeline*" "nodeJvm / testOnly *HotStuffProposalInputPacemakerIntegrationSuite"`
    passed with 51 pipeline tests and 23 pacemaker integration tests.
  - `sbt "nodeJvm / test"` passed with 444 tests.
  - `sbt publishLocal` published stable local `0.2.8` artifacts.
  - BBGO consumed local `0.2.8`, passed `sbt "node / Test / compile" "node / assembly"`,
    and passed dependent-payment smoke at
    `target/local-four-node-consensus-smoke/20260625T023108Z`
    with `t1SubmittedToT2FinalizedMillis=396`.
  - After BBGO script dependencies were moved to `0.2.8`, dependent-payment
    smoke passed again at
    `target/local-four-node-consensus-smoke/20260625T023434Z`
    with `t1SubmittedToT2FinalizedMillis=478`.
  - BBGO `scripts/generate-deploy-secrets.scala` no longer declares the
    snapshot repository, and its `--help` path resolves against stable `0.2.8`.
  - BBGO `build.sbt` no longer declares the central snapshots resolver, and
    `sbt "node / Test / compile"` passed after removal.
  - Final BBGO assembly after resolver removal has jar hash
    `2a1c6a04e55e01509d8a45c8cb1230c3a3b2d915`, and the final smoke passed at
    `target/local-four-node-consensus-smoke/20260625T023833Z`
    with `t1SubmittedToT2FinalizedMillis=461`.
- Review follow-up completed: `SwayDbTxPipelineStore` was relocated from
  `org.sigilaris.node.jvm.runtime.txpipeline` to
  `org.sigilaris.node.jvm.storage.swaydb`, matching the existing concrete
  SwayDB store package boundary. The temporary runtime import-rule exception
  was removed, and runtime code now stays behind the generic `TxPipelineStore`
  algebra.
- Post-relocation verification completed:
  - `sbt "nodeJvm / Test / compile" "nodeJvm / testOnly *RuntimeImportRuleSuite" "nodeJvm / testOnly *TxPipelineStoreSuite"`
    passed.
  - `sbt "nodeJvm / test"` passed with 444 tests.
  - `sbt publishLocal` republished local stable `0.2.8` artifacts.
  - BBGO consumed the relocated artifact, passed
    `sbt "node / Test / compile" "node / assembly"` with jar hash
    `84396ff7ed217843a7e7e0dda4300e1ef2f701c5`, and passed dependent-payment
    smoke at `target/local-four-node-consensus-smoke/20260625T025809Z`
    with `t1SubmittedToT2FinalizedMillis=472`.
- Add SSE or streaming progress observation for clients that do not want to
  poll `GET /tx-pipeline/{pipelineId}`.
- Add transactional idempotency-index persistence or startup repair for durable
  pipeline stores.
- Add admission or embedder-level protection against the same canonical
  transaction being submitted into multiple live pipelines.
- Implement terminal-state pruning before exposing durable stores to unbounded
  public traffic.
- Add `blockHash -> pipeline` and `txId -> pipeline` indexes for projection and
  eligibility scans.
- Add public single-transaction submission as a compact projection over the
  one-stage pipeline model.
- Add pipeline cancellation and retry policies after the first projection model
  is stable.
- Add metrics names for accepted-to-proposed, proposed-to-certified,
  certified-to-finalized, and held-by-barrier durations.
- Coordinate with an embedding application to map application-specific
  transaction decoding, application status, and payment success semantics onto
  the generic pipeline projection.
