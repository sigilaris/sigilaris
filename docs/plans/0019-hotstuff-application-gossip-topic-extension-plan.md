# 0019 - HotStuff Application Gossip Topic Extension Plan

## Status
Published (v0.2.4) - Next Development Snapshot Started

## Created
2026-06-18

## Last Updated
2026-06-19

## Background
- `sigilaris-node-jvm` already provides a session-based gossip substrate with binary event-stream framing, static peer topology, transport authentication, and topic-aware contracts.
- HotStuff consensus currently uses that substrate through `HotStuffRuntimeBootstrap`, but the assembled peer runtime is fixed to `TxGossipRuntime[F, HotStuffGossipArtifact]`.
- `HotStuffGossipArtifact` currently contains only consensus artifacts: proposal, vote, timeout-vote, and new-view.
- Embedders need to replicate application-owned artifacts, for example signed transaction admission bodies, over the same authenticated peer session and listener used by HotStuff.
- Without an upstream application-topic extension, embedders must either:
  - keep separate public HTTP fanout paths for application artifacts; or
  - add embedder-specific binary endpoints beside the HotStuff listener.
- Both work, but they leave session/topic multiplexing underused and make the embedder own transport behavior that Sigilaris already models generically.
- `build.sbt` started this plan on `0.2.4-SNAPSHOT`; the completed extension was published as `0.2.4` and `main` has moved on to `0.2.5-SNAPSHOT`.

## Goal
- Add a public extension point that lets HotStuff embedders attach application-owned gossip topics to the same static peer session, Armeria listener, binary event-stream codec, control channel, and transport-auth boundary as HotStuff consensus artifacts.
- Preserve the existing HotStuff-only bootstrap API and behavior.
- Keep application artifact semantics owned by the embedder while Sigilaris owns session, topic multiplexing, binary framing, control, replay, request-by-id, auth, and QoS plumbing.
- Provide enough hooks for an embedder to implement transaction-admission body sync so validators can receive application tx bodies before voting on proposals that only carry tx ids.
- Publish the completed extension as `0.2.4`.

## Scope
- A generic application gossip topic registration API for HotStuff runtime bootstrap.
- A composite peer artifact envelope that can carry either HotStuff consensus artifacts or embedder application artifacts.
- Composite source, sink, and topic-contract registry adapters.
- Armeria adapter compatibility for `TxGossipArmeriaAdapter` and `HotStuffBootstrapArmeriaAdapter` when the peer runtime payload type is composite.
- Session subscription support for consensus topics plus application topics.
- Topic-scoped stable-id, `requestById`, and retention policy wiring for application topics.
- Exact-known-set opt-in for application topics using the existing `ControlOp.SetKnownExact`, `ControlOp.RequestByIdExact`, and `ExactKnownSetScope` primitives.
- Exact-scope request retry accounting that treats `maxExactRequestRetriesPerScope` as a consecutive-unfulfilled retry limit, not a lifetime request counter for a scope.
- Binary codec and rejection behavior for composite artifacts.
- Tests demonstrating a sample application topic sharing the HotStuff peer session and binary event stream.

## Non-Goals
- No embedder package, application-specific admission model, public submission API, or commerce transaction codec is added to Sigilaris.
- No change to HotStuff proposal, vote, timeout-vote, new-view signing bytes or identity rules.
- No change to consensus safety, pacemaker, QC, finalization, or materialization semantics.
- No public API commitment that every application topic must be consensus-critical.
- No first-class synchronous all-peer apply acknowledgement, correlation RPC, or remote commit barrier for application topics in `0.2.4`.
- No dynamic discovery, validator-set rotation, or peer scoring.
- No mandatory migration from embedder-owned direct binary endpoints in the same release.
- No switch to WebSocket, QUIC, or a new physical transport.

## Related ADRs And Docs
- [ADR-0016: Multiplexed Gossip Session Sync Substrate](../adr/0016-multiplexed-gossip-session-sync.md)
- [ADR-0017: HotStuff Consensus Without Threshold Signatures](../adr/0017-hotstuff-consensus-without-threshold-signatures.md)
- [ADR-0018: Static Peer Topology And Initial HotStuff Deployment Baseline](../adr/0018-static-peer-topology-and-initial-hotstuff-deployment-baseline.md)
- [ADR-0024: Static-Topology Peer Identity Binding And Session-Bound Capability Authorization](../adr/0024-static-topology-peer-identity-binding-and-session-bound-capability-authorization.md)
- [0003 - Multiplexed Gossip Session Sync Plan](0003-multiplexed-gossip-session-sync-plan.md)
- [0016 - HotStuff Application Proposal Input Hook Plan](0016-hotstuff-application-proposal-input-hook-plan.md)
- [0017 - HotStuff Application Proposal Validation Hook Plan](0017-hotstuff-application-proposal-validation-hook-plan.md)
- `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/Contracts.scala`
- `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/GossipCursorModel.scala`
- `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/GossipIdentifiers.scala`
- `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/GossipSessionProtocol.scala`
- `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/tx/TxGossipRuntime.scala`
- `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/tx/TxGossipRuntimeControlOps.scala`
- `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/tx/TxGossipRuntimePollingOps.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/gossip/tx/TxGossipRuntimeBootstrap.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/HotStuffRuntimeBootstrap.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/HotStuffNodeRuntime.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/HotStuffGossipBridge.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/BinaryEventStreamCodec.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/TxGossipArmeriaAdapter.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/HotStuffBootstrapArmeriaAdapter.scala`

## Decisions To Lock Before Implementation

### D0. Delivery Consistency Model
`0.2.4` uses the existing gossip consistency model: local publication, peer event-stream delivery, and topic-scoped `requestById` catch-up. It does not add a synchronous all-peer apply acknowledgement primitive.

- A source publish success means the application artifact is accepted by the local runtime/source and can be served by topic/id according to the topic policy.
- Remote sink apply success is asynchronous. If an embedder needs an application-level acknowledgement, it can model that as another application topic or local diagnostic, not as a Sigilaris transport primitive in `0.2.4`.
- Consensus safety for proposals that reference application artifact IDs remains an embedder responsibility through proposal input/validation/readiness hooks.
- `requestById` catch-up is only valid when the requesting side knows the same `StableArtifactId` that the application topic source serves. Embedders whose proposals reference application artifacts must align proposal reference IDs with application topic artifact IDs, or provide an explicit application-owned resolver before relying on `requestById`.
- Application-topic catch-up uses the existing exact-known-set control path: `ControlOp.SetKnownExact(scope, ids)` and `ControlOp.RequestByIdExact(scope, ids)`, where `scope` is an `ExactKnownSetScope(chainId, topic, windowKey)`.
- `SetKnownExact` accumulates ids in the peer session for a given scope. Proposal catch-up helpers must not blindly emit `SetKnownExact(scope, proposal.txSet.txIds)` for an unbounded stream; missing-artifact catch-up should use `RequestByIdExact` grouped by scope, with `SetKnownExact` reserved for bounded known-set updates.
- `RequestByIdExact` retry accounting must reset or clear `requestScopeRetryCounts(scope)` when the explicit requested ids for that scope are fulfilled and the corresponding `pendingRequestScopeIds(scope)` entry is cleared. The retry budget should cap consecutive unfulfilled retries, not successful future catch-up requests against a long-lived scope.
- If Phase 0 discovers that a release consumer truly requires synchronous all-peer apply acknowledgement, that must become an explicit `0019` scope expansion before publishing `0.2.4`; it must not be implied by the generic application-topic API.

### D1. Public Shape
Add a new additive API instead of changing existing HotStuff-only methods.

Baseline shape:

```scala
final case class ApplicationGossipTopic[F[_], A](
    topic: GossipTopic,
    source: GossipArtifactSource[F, A],
    sink: GossipArtifactSink[F, A],
    contract: GossipTopicContract[A],
    policy: ApplicationGossipTopicPolicy,
)

enum HotStuffPeerArtifact[+A]:
  case Consensus(value: HotStuffGossipArtifact)
  case Application(value: A)
```

The exact names can change during Phase 0, but the contract should stay additive:

- existing `HotStuffRuntimeBootstrap.fromConfig/fromTopology` continues to return the current HotStuff-only bootstrap;
- new `fromConfigWithApplicationTopics` / `fromTopologyWithApplicationTopics` or equivalent returns a bootstrap whose peer gossip runtime payload type is `HotStuffPeerArtifact[A]`;
- application artifact type `A` is embedder-owned and requires `ByteEncoder`, `ByteDecoder`, and topic contracts supplied by the embedder.
- an application topic that supports id-based catch-up must opt into exact-known-set semantics through its `GossipTopicContract`: `exactKnownScopeOf` and `requestByIdLimit` must be populated for `RequestByIdExact`; `exactKnownSetLimit` must be populated if the topic uses `SetKnownExact`.

### D2. Topic Ownership
- Sigilaris owns the composite routing, session, cursor, binary frame, control, request-by-id, and transport-auth behavior.
- HotStuff owns only the existing `consensus.*` topics.
- The embedder owns application topic names, stable artifact ID granularity, validation rules, payload codec, retention window, and local apply semantics.
- Sigilaris must not inspect or special-case application payload semantics.

### D3. Composite Source/Sink Semantics
Composite adapters route by topic and payload namespace.

- Consensus topics delegate to existing HotStuff source/sink/topic contracts.
- Application topics delegate to application source/sink/topic contracts.
- Unsupported topics return canonical `artifactContractRejected(reason = "unsupportedTopic")`.
- A consensus payload on an application topic, or an application payload on a consensus topic, is rejected as `unexpectedTopicPayload`.
- Request-by-id, exact known-set, QoS, retention, and cursor behavior remain topic-scoped.
- Exact-known-set application topics must derive the same `ExactKnownSetScope` for outgoing events and readiness control batches; mismatched `windowKey` derivation makes `RequestByIdExact` unable to serve the artifact.
- Exact-known-set scope design must avoid permanent single-window accumulation. The `windowKey` must either be derivable from the proposal-visible artifact id, or the embedder must provide a resolver that can derive the correct scope before fetching the artifact.

### D4. Binary Codec
The peer event-stream payload type becomes a composite artifact with an explicit namespace tag.

Baseline:

- `0x01`: HotStuff consensus artifact; payload is the existing `HotStuffGossipArtifact` binary encoding.
- `0x02`: application artifact; payload is encoded with the embedder-provided `ByteEncoder[A]`.

Decode must reject unknown namespace tags, trailing bytes, truncated payloads, and application decoder failures with stable rejection reasons. The composite codec must preserve existing HotStuff artifact bytes for consensus artifacts.

### D5. Session Subscription
The runtime must support opening sessions that subscribe to:

- the existing HotStuff consensus topics;
- zero or more application topics supplied by the embedder.

The implementation should either introduce a small helper for consensus-plus-application subscriptions or update the existing `SessionSubscription` / `topicContracts` assembly sites directly. The plan does not assume any pre-existing subscription helper.

Existing consensus-only subscription behavior remains unchanged.

### D6. HotStuff Proposal Tx Readiness
This extension does not define transaction admission semantics, but it must support the common pattern:

- proposal carries only application artifact IDs such as tx IDs;
- application topic carries full artifact bodies or admission records;
- embedder proposal catch-up readiness can hold votes until required application artifacts are locally known;
- readiness can emit `RequestByIdExact` controls for application topics, not only `RequestByIdTx` for the built-in transaction topic.

For this pattern to be correct, the proposal reference ID must be the same `StableArtifactId` served by the application topic for the needed artifact. A batch artifact whose only stable ID is not derivable from the proposal's referenced IDs is insufficient for `requestById` catch-up unless the embedder also provides an explicit resolver.

`0.2.4` should provide a generic helper alongside the existing tx-topic helper, for example `HotStuffProposalTxSync.controlBatchesForProposalOnExactTopics(...)`, that accepts a scope resolver:

- `scopeForId: StableArtifactId => Either[CanonicalRejection.ControlBatchRejected, ExactKnownSetScope]`.
- `requestByIdLimitForScope: ExactKnownSetScope => Either[CanonicalRejection.ControlBatchRejected, Int]`, or an equivalent way to read the topic contract's `requestByIdLimit`.

The helper groups `missingTxIds` by resolved scope and emits `ControlOp.RequestByIdExact(scope, idsChunk)` operations whose chunk size never exceeds the scope's configured `requestByIdLimit`. If a scope has more missing ids than can be requested in one control cycle without violating the retry budget, the helper must return staged control batches or a structured rejection instead of emitting an oversized op. It may optionally emit `ControlOp.SetKnownExact(scope, knownIdsForScope)` only when the known-set update is bounded by topic policy. This helper reuses existing control wire operations; it does not add a new control op.

The helper relies on runtime retry accounting that clears a scope's retry count after the requested ids are served. This keeps content-derived or resolver-derived scopes usable across long-lived sessions while preserving the existing retry budget for repeated unfulfilled requests.

The extension must not force application artifacts into HotStuff proposals.

### D7. ADR Need
If Phase 0 changes ADR-0016's normative session/topic contract or exposes a broad new public API, draft ADR-0029 or amend ADR-0016 before implementation. If the work is purely additive under the accepted ADR-0016 topic-extension model, a plan and API docs are sufficient.

## Phase 0 Locked Decisions
- Delivery consistency remains local publish plus asynchronous peer delivery and topic-scoped `requestById` catch-up. No synchronous all-peer apply acknowledgement is added in `0.2.4`.
- Proposal-referenced application artifact IDs must be directly requestable as `StableArtifactId`s on the application topic, or the embedder must provide an application-owned resolver before using Sigilaris catch-up helpers.
- Application-topic request-by-id uses the existing exact-known-set control path only: `ControlOp.RequestByIdExact` and optional bounded `ControlOp.SetKnownExact`.
- The generic proposal catch-up helper is named `HotStuffProposalTxSync.controlBatchesForProposalOnExactTopics`. It groups missing proposal tx IDs by an embedder-supplied `StableArtifactId => ExactKnownSetScope` resolver and emits chunked `RequestByIdExact` operations.
- The helper must not emit unbounded `SetKnownExact` updates for every proposal tx ID. Any known-set publication remains an explicit bounded caller choice outside that helper.
- `RequestByIdExact` retry accounting treats `maxExactRequestRetriesPerScope` as a consecutive-unfulfilled limit. Once all pending requested IDs for a scope are served, both the pending request entry and retry counter for that scope are cleared.
- The helper respects each scope's `requestByIdLimit`. Groups that exceed one allowed control batch are returned as staged `ControlBatch` values instead of emitting an oversized op.
- Application-topic retention is exposed as an embedder-visible hint through `ApplicationGossipTopicPolicy(catchUpRetentionHint: Option[Duration])`; the source remains responsible for actually retaining artifacts by topic/id.
- Public type names are locked as `ApplicationGossipTopic[F, A]`, `ApplicationGossipTopicPolicy`, `HotStuffPeerArtifact[A]`, and `HotStuffRuntimeBootstrapWithApplications[F, A]`.
- Additive bootstrap methods are locked as `fromConfigWithApplicationTopics` and `fromTopologyWithApplicationTopics`.
- `ApplicationGossipTopic`, `ApplicationGossipTopicPolicy`, `HotStuffPeerArtifact`, and composite adapters live in `org.sigilaris.node.jvm.runtime.consensus.hotstuff` for `0.2.4`; transport-neutral extraction can be revisited later if another runtime needs the same API.
- Binary payload namespace allocation is `0x01` for `HotStuffGossipArtifact` consensus payloads and `0x02` for embedder application payloads.
- Stable binary decode rejection reasons are `unknownPeerArtifactNamespace`, `truncatedPeerArtifactPayload`, `peerArtifactTrailingBytes`, and `applicationPeerArtifactDecodeFailed`.
- No ADR-0016 amendment or ADR-0029 is required for `0.2.4`; this work is additive under ADR-0016's topic-extension model.
- The sample application artifact for tests is a tiny transaction-body record with a stable ID equal to the proposal-visible tx ID and an exact-known-set scope whose `windowKey` is derived from the first byte of that ID.

## Change Areas

### Code
- `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip`
  - composite source/sink/registry helper candidates;
  - optional topic registration value types if they are transport-neutral.
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff`
  - `HotStuffPeerArtifact` or equivalent;
  - application topic registration model;
  - composite source/sink/topic contract assembly;
  - additive bootstrap methods;
  - generic proposal catch-up helper for exact-known-set application topics;
  - subscription assembly/helper.
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip`
  - binary codec tests and any generic adapter changes needed for composite payloads;
  - no embedder-specific endpoint.
- `modules/node-jvm/src/test/scala`
  - HotStuff-only compatibility tests;
  - composite topic loopback tests;
  - Armeria binary event-stream tests with an application artifact.

### Tests
- Composite codec:
  - consensus payload round-trip preserves existing encoding;
  - application payload round-trip uses embedder codec;
  - unknown namespace tag rejection;
  - trailing-byte/truncated-payload rejection.
- Composite routing:
  - consensus topic delegates to HotStuff contract;
  - application topic delegates to application contract;
  - wrong namespace/topic combinations reject;
  - unsupported topic rejects.
- Runtime:
  - session subscription includes consensus plus application topics;
  - `readAfter` and `readByIds` route by topic;
  - application-topic `requestById` uses the same stable IDs that the sample proposal/readiness path references;
  - exact-known-set application topics accept `RequestByIdExact` only when `exactKnownScopeOf` and `requestByIdLimit` are configured;
  - exact-known-set application topics accept `SetKnownExact` only when `exactKnownSetLimit` is configured;
  - long-running sessions do not force every proposal id into one permanent exact-known scope;
  - successful exact-scope fulfillment clears the scope's retry counter once the pending requested ids are served;
  - repeated unfulfilled `RequestByIdExact` calls still hit `maxExactRequestRetriesPerScope`;
  - proposal catch-up helpers never emit `RequestByIdExact` with more ids than the topic contract's `requestByIdLimit`;
  - application-topic source retains sample artifacts for the configured catch-up window;
  - application sink receives only application events;
  - consensus sink behavior is unchanged.
- Armeria:
  - a session can exchange consensus artifacts and a sample application artifact on the same binary event stream;
  - control `RequestByIdExact` works for application topic ids within configured bounds;
  - existing HotStuff launch smoke remains green.

### Docs
- Update ADR-0016 references or add ADR-0029 if Phase 0 requires it.
- Update README or developer docs with application topic registration example.
- Add scaladoc for new public extension types.
- Mention that HotStuff proposal input/validation hooks remain separate from application gossip topics.

## Implementation Phases

### Phase 0: Contract Lock
- Lock the delivery consistency model: local publish plus request-by-id catch-up, with no first-class synchronous remote apply ack in `0.2.4`.
- Lock the stable-ID contract for proposal-referenced application artifacts: proposal IDs must be directly requestable on the application topic, or the embedder must provide an explicit resolver outside Sigilaris.
- Lock exact-known-set as the `0.2.4` control path for application-topic request-by-id catch-up; no new wire op is required.
- Lock delivery of a generic proposal catch-up helper that groups missing ids by an application-provided `StableArtifactId => ExactKnownSetScope` resolver and emits `RequestByIdExact` per scope.
- Lock that the generic helper must not emit unbounded `SetKnownExact` updates for every proposal tx id.
- Lock `RequestByIdExact` retry reset semantics: fulfilled exact-scope requests clear the scope retry count, while unfulfilled retries remain bounded by `maxExactRequestRetriesPerScope`.
- Lock `RequestByIdExact` chunking semantics: helper output must respect each scope's `requestByIdLimit` and must stage or reject work that cannot fit within the retry budget before fulfillment.
- Decide the application-topic retention policy surface and default catch-up window semantics.
- Decide final public type names and method names.
- Decide whether the new return type is:
  - a new `HotStuffRuntimeBootstrapWithApplications[F, A]`; or
  - a genericized `HotStuffRuntimeBootstrap[F, A]` with compatibility aliases.
- Decide namespace byte allocation and binary rejection reasons.
- Decide whether `ApplicationGossipTopic` belongs in `node-common` or `node-jvm`.
- Decide whether ADR-0016 amendment or ADR-0029 is needed.
- Define a sample application artifact for tests.

### Phase 1: Composite Model And Codec
- Add composite peer artifact model.
- Add `ByteEncoder`/`ByteDecoder` for the composite artifact.
- Add composite topic-contract registry helper.
- Add composite source and sink adapters.
- Add unit tests for codec and routing.

### Phase 2: HotStuff Bootstrap Integration
- Add application-topic bootstrap config/API.
- Assemble `TxGossipRuntime[F, HotStuffPeerArtifact[A]]` from:
  - existing HotStuff consensus source/sink/contracts;
  - application source/sink/contracts.
- Preserve existing HotStuff-only `fromConfig/fromTopology` behavior.
- Introduce or update subscription assembly for consensus plus application topics.
- Add exact-known-set registration support for application topics.
- Update exact-scope polling state so served requested ids also clear the corresponding retry counter when no pending ids remain for the scope.
- Add generic proposal catch-up helper for application exact topics.
- Add helper tests for per-scope `requestByIdLimit` chunking and staged/rejected oversized scope groups.
- Add in-process runtime tests.

### Phase 3: Armeria And Binary Stream Verification
- Verify `TxGossipArmeriaAdapter` works with composite payloads without endpoint-specific changes.
- Add Armeria loopback tests for mixed consensus/application topics.
- Verify control path and `requestById` for application topic.
- Verify repeated successful exact-scope request fulfillment does not exhaust the retry budget.
- Verify application-topic retention keeps requestable artifacts available for the configured catch-up window.
- Confirm transport auth and session-bound capability behavior is unchanged.

### Phase 4: Docs And Embedder Handoff
- Add developer documentation and sample code.
- Document migration guidance for embedders that currently use separate node-to-node HTTP fanout.
- Record generic integration expectations without importing embedder code.
- Run full node JVM regression.
- Release mechanics:
  - change `ThisBuild / version` from `0.2.4-SNAPSHOT` to `0.2.4`;
  - publish the non-SNAPSHOT `0.2.4` artifact to the target resolver after regression and docs gates pass;
  - allow `publishLocal` only for pre-release embedder validation;
  - bump the next development snapshot after release if that is the repository convention.

## Test Plan
- Focused:
  - `sbt "nodeJvm/testOnly *ApplicationGossip*"`
  - `sbt "nodeJvm/testOnly *HotStuff*Gossip*"`
  - `sbt "nodeJvm/testOnly *TxGossipArmeriaAdapterSuite"`
- Regression:
  - `sbt "nodeCommonJVM/test" "nodeCommonJS/test"`
  - `sbt "nodeJvm/test"`
- Documentation/API:
  - `sbt ";unidoc;tlSite"` if public API docs or site docs change.
- Embedder validation:
  - a sample application can register an application topic without Sigilaris depending on the application package.
  - an embedder can keep its public client API separate while using the Sigilaris application topic for node-to-node artifact sync.
  - pre-release validation may use `publishLocal`, but final handoff requires resolving non-SNAPSHOT `0.2.4`.

## Risks And Mitigations
- Risk: genericizing HotStuff bootstrap breaks existing embedders.
  - Mitigation: keep current methods and return types, add new methods for application-topic runtime.
- Risk: application payload type leaks into HotStuff consensus internals.
  - Mitigation: use composite adapters at the peer gossip boundary only; HotStuff consensus runtime still consumes `HotStuffGossipArtifact`.
- Risk: app topic backlog delays consensus votes/proposals.
  - Mitigation: expose per-topic QoS/policy and keep consensus vote priority higher by default.
- Risk: binary namespace codec becomes a second topic system.
  - Mitigation: namespace only identifies payload family for decoding; topic contract remains the semantic routing key.
- Risk: embedders use app topics for consensus-critical data without readiness gates.
  - Mitigation: docs explicitly require embedder-owned readiness/validation hooks when proposals reference app artifact IDs.
- Risk: request-by-id for application topics creates unbounded fetch load.
  - Mitigation: application topic policy includes request limits and uses existing bounded control rejection path.
- Risk: long-lived exact scopes exhaust `maxExactRequestRetriesPerScope` even after successful catch-up.
  - Mitigation: reset the per-scope retry counter when requested ids are fulfilled; keep the retry budget for consecutive unfulfilled requests.
- Risk: a proposal references more missing ids for one exact scope than the topic's `requestByIdLimit`.
  - Mitigation: require helper chunking by `requestByIdLimit`; if chunk count would exceed the retry budget before fulfillment, stage the request or reject with a clear control-batch error.

## Acceptance Criteria
1. Existing HotStuff-only bootstrap code compiles and tests pass without changes.
2. A sample application artifact can share the same gossip session, binary event stream, and transport auth as HotStuff consensus artifacts.
3. Composite codec round-trips consensus and application artifacts and rejects malformed namespace/payload bytes.
4. Composite source/sink/contract routing preserves topic ownership and rejects wrong topic/payload combinations.
5. Armeria loopback test transfers at least one HotStuff artifact and one application artifact over the same peer relationship.
6. `RequestByIdExact` works for the sample application topic within policy limits.
7. Documentation shows how an embedder can register an application topic without adding embedder-specific code to Sigilaris.
8. Documentation states that `0.2.4` application topics do not provide synchronous all-peer remote apply acknowledgement.
9. Non-SNAPSHOT `0.2.4` artifacts are published to the documented resolver before downstream dependency bumps depend on them.
10. A sample proposal/readiness flow can request a missing application artifact by the same stable ID carried in the proposal.
11. The sample application topic uses `RequestByIdExact` with a configured `ExactKnownSetScope` and `requestByIdLimit`.
12. The proposal catch-up helper groups missing ids by scope and does not create an unbounded single-scope `SetKnownExact` accumulation.
13. Repeated successful `RequestByIdExact` fulfillment for the same scope can exceed `maxExactRequestRetriesPerScope` over time, while repeated unfulfilled requests are still rejected by the retry budget.
14. The proposal catch-up helper does not emit any `RequestByIdExact` op whose id count exceeds the topic contract's `requestByIdLimit`; oversized scope groups are chunked, staged, or rejected before runtime control application.

## Checklist

### Phase 0: Contract Lock
- [x] Lock local-publish plus request-by-id catch-up as the `0.2.4` consistency model.
- [x] Confirm synchronous remote apply ack is out of scope for `0.2.4`.
- [x] Lock proposal-reference ID and application-artifact ID alignment rules.
- [x] Lock `SetKnownExact` / `RequestByIdExact` as the application-topic catch-up control path.
- [x] Lock generic proposal catch-up helper ownership in Sigilaris.
- [x] Lock grouped helper semantics and prohibit unbounded `SetKnownExact` emission.
- [x] Lock scope resolver requirements for proposal-visible ids.
- [x] Lock exact-scope retry reset semantics for fulfilled `RequestByIdExact` requests.
- [x] Lock per-scope request chunking and staging semantics for `requestByIdLimit`.
- [x] Decide application-topic retention policy surface and catch-up window defaults.
- [x] Decide final public type and method names.
- [x] Decide bootstrap return type strategy.
- [x] Decide namespace byte allocation and rejection reason names.
- [x] Decide package placement for application topic registration types.
- [x] Decide ADR-0016 amendment vs ADR-0029.
- [x] Define sample application artifact used by tests.
- [x] Confirm `0.2.4` public surface and release scope.

### Phase 1: Composite Model And Codec
- [x] Add composite peer artifact model.
- [x] Add composite `ByteEncoder`/`ByteDecoder`.
- [x] Add composite topic-contract registry helper.
- [x] Add composite source adapter.
- [x] Add composite sink adapter.
- [x] Add codec and routing unit tests.

### Phase 2: HotStuff Bootstrap Integration
- [x] Add application-topic bootstrap API.
- [x] Assemble composite `TxGossipRuntime` from HotStuff and application components.
- [x] Preserve existing HotStuff-only bootstrap methods.
- [x] Introduce or update consensus-plus-application subscription assembly.
- [x] Support exact-known-set application-topic registration.
- [x] Reset fulfilled exact-scope retry counters in runtime polling state.
- [x] Add generic proposal catch-up helper for exact-known-set application topics.
- [x] Add helper tests for request chunking and oversized scope handling.
- [x] Add in-process runtime tests.

### Phase 3: Armeria And Binary Stream Verification
- [x] Add Armeria mixed-topic loopback test.
- [x] Verify binary event-stream codec with composite payloads.
- [x] Verify `SetKnownExact` / `RequestByIdExact` for application topics.
- [x] Verify repeated successful exact-scope request fulfillment does not exhaust the retry budget.
- [x] Verify application-topic artifacts remain requestable for the configured retention window.
- [x] Verify transport auth/session-bound behavior remains unchanged.
- [x] Run focused HotStuff launch/regression suites.

### Phase 4: Docs And Embedder Handoff
- [x] Add developer docs and scaladoc.
- [x] Document embedder migration guidance.
- [x] Record generic integration expectations without application-specific dependencies.
- [x] Run `nodeCommonJVM/test`, `nodeCommonJS/test`, and `nodeJvm/test`.
- [x] Run `unidoc` / `tlSite` if public docs changed.
- [x] Remove `-SNAPSHOT` for the `0.2.4` release build.
- [x] Publish non-SNAPSHOT `0.2.4` to the target resolver.
- [x] Bump the next development snapshot after release.

## Follow-Ups
- Add a normative ADR if application-topic multiplexing becomes a long-lived public contract beyond the existing ADR-0016 extension model.
- Consider a type-erased `ByteVector` application artifact variant if embedders need dynamic topic registration at runtime.
- Add metrics hooks for per-topic queue depth, bytes, and reject counts.
- Revisit full-duplex transport only after the HTTP/Armeria composite topic path is stable.
