# 0030 - Tapir FS2 Octet-Stream Gossip Event Delivery Plan

## Status
Completed

## Created
2026-07-01

## Last Updated
2026-07-02 KST

## Background
- Sigilaris currently exposes gossip event delivery through a Tapir/Armeria
  endpoint named `eventStream`, but the HTTP behavior is finite polling:
  `POST /gossip/events/{sessionId}` returns one `byteArrayBody` response.
- `TxGossipPeerClient` exposes `pollEvents`, and the server adapter calls
  `TxGossipRuntime.pollEvents` once per request.
- This design keeps the logical session open, but event propagation is still
  limited by the client's poll cadence, peer loop scheduling, and repeated HTTP
  request/response turns.
- BBGO is the only Sigilaris consumer and is managed together with this code,
  so the migration can prioritize a clean streaming contract over old poll
  compatibility.
- The pinned Sigilaris dependency set already includes Tapir, Armeria Cats
  server support, Tapir STTP4 client support, Armeria Cats client backend,
  cats-effect, and fs2. Phase 0 adds the matching STTP4 Armeria FS2 backend
  needed for streaming client responses.
- ADR-0033 proposes replacing finite event polling with Tapir FS2
  `application/octet-stream` streaming event delivery.

## Goal
- Make steady-state gossip event delivery a persistent
  `application/octet-stream` FS2 byte stream defined by Tapir endpoint specs.
- Use the shared Tapir endpoint definitions on both server and client paths.
- Replace the production `pollEvents` transport path with a stream client API.
- Preserve the logical event envelope, cursor, keepalive, rejection, topic, and
  session semantics from ADR-0016.
- Add runtime wakeups so streams emit promptly when source or control state
  changes, instead of hiding polling inside a stream.
- Prepare BBGO to consume the Sigilaris streaming peer transport in a follow-up
  integration branch.

## Scope
- `sigilaris-node-common` gossip runtime and model changes needed for
  event-driven stream delivery.
- `sigilaris-node-jvm` Armeria/Tapir endpoint, adapter, client, and server
  assembly changes.
- Binary event frame codec changes from whole-response arrays to incremental
  stream frames.
- Runtime/source notification seams needed to wake event streams.
- Tests for server streaming, client streaming, auth, framing, liveness,
  backpressure, and HotStuff gossip launch behavior.
- Documentation and migration notes for the new Sigilaris peer transport API.

## Non-Goals
- No BBGO code changes in this Sigilaris plan. BBGO integration is a downstream
  follow-up after the Sigilaris API is available.
- No WebSocket, QUIC, bidirectional HTTP/2 stream, or streaming control channel.
- No dynamic peer discovery, validator admission, peer scoring, or topology
  management changes.
- No HotStuff pacemaker, finality, proposal scheduling, or transaction pipeline
  semantic changes.
- No public application transaction submission API changes.
- No reverse-direction control-channel acceleration. Cursor acknowledgements,
  known-set updates, replay requests, and other control feedback remain ordinary
  JSON POST requests in this plan.
- No requirement to keep the old finite-poll gossip event endpoint compatible
  for external consumers.

## Related ADRs And Docs
- [ADR-0033: Tapir FS2 Octet-Stream Gossip Event Delivery](../adr/0033-tapir-fs2-octet-stream-gossip-event-delivery.md)
- [ADR-0016: Multiplexed Gossip Session Sync Substrate](../adr/0016-multiplexed-gossip-session-sync.md)
- [ADR-0024: Static-Topology Peer Identity Binding And Session-Bound Capability Authorization](../adr/0024-static-topology-peer-identity-binding-and-session-bound-capability-authorization.md)
- [0020 - Armeria Tapir Client Gossip Transport Plan](0020-armeria-tapir-client-gossip-transport-plan.md)
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/ArmeriaServer.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/TxGossipTapirEndpoints.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/TxGossipArmeriaAdapter.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/TxGossipPeerClient.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/BinaryEventStreamCodec.scala`
- `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/tx/TxGossipRuntime.scala`
- `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/tx/TxGossipRuntimePollingOps.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/InMemoryHotStuffGossipBridge.scala`

## Decisions To Lock Before Implementation

### D1. Endpoint Shape
- Default target: replace the production semantics of
  `POST /gossip/events/{sessionId}` with a long-lived FS2 streaming response.
- The endpoint still receives the authenticated peer header, transport proof
  header, session id path variable, and a string request body.
- The request body should identify a stream-open request, for example an
  `EventRequestWire("stream")` successor, not a poll request.
- The stream-open request must carry a resume contract. Phase 0 must lock
  whether this is an explicit composite resume cursor in the event request body
  or a reference to a durable cursor already committed through the control
  channel. The preferred shape is an optional composite cursor field on the
  `EventRequestWire` successor so stream reconnect does not require an extra
  control round trip before the first event drain.
- If finite poll is retained as a mounted temporary fixture during migration,
  the streaming endpoint must use a distinct route such as
  `POST /gossip/events/{sessionId}/stream`. The current single `eventStream`
  endpoint value cannot be both `byteArrayBody` poll output and
  `streamBinaryBody` output at compile time.
- If Phase 0 finds that a distinct path is materially simpler for migration or
  tests, use `POST /gossip/events/{sessionId}/stream` and document that the old
  poll path is not a compatibility guarantee.

### D2. Tapir And Armeria Capability Types
- Compile the exact Tapir endpoint shape with:
  - `Fs2Streams[F]`;
  - `streamBinaryBody(Fs2Streams[F])(CodecFormat.OctetStream())`;
  - `ArmeriaCatsServerInterpreter`;
  - the STTP4 Tapir stream client interpreter and Armeria FS2 backend.
- Phase 0 found that `armeria-backend-cats` remains correct for ordinary
  JSON and byte-array requests, but its STTP4 backend type is non-streaming in
  the pinned dependency set. Streaming responses use `armeria-backend-fs2`.
- Update `ArmeriaServer` so it can serve streaming endpoints, likely by
  accepting `ServerEndpoint[Fs2Streams[F], F]` or a small capability-polymorphic
  wrapper.
- Non-stream endpoints must still be mountable in the same server assembly.
- Do not silently fall back to a manually defined production Armeria service if
  Tapir client streaming cannot be made to work. That would require revisiting
  ADR-0033.

### D3. Runtime Wakeup Contract
- Streaming must be event-driven.
- The runtime stream driver needs wakeups for:
  - source append by chain/topic;
  - successful control batch application that changes pending request,
    known-set, cursor, nack, or replay state;
  - batching flush deadlines;
  - negotiated event keepalive deadlines;
  - session close, dead, superseded, or disconnect transitions;
  - sidecar hold release or fallback decisions when applicable.
- A short fixed poll loop inside `Stream.awakeEvery` is not acceptable as the
  production delivery mechanism.
- Phase 0 must choose whether this is represented as an extension to
  `GossipArtifactSource`, a separate notifier service, or a runtime-owned
  session wakeup bus.

### D4. Binary Framing
- Preserve the existing length-prefixed binary event envelope format unless a
  Phase 0 codec spike proves it cannot be streamed safely.
- Add incremental frame APIs to `BinaryEventStreamCodec`.
- Decoding must handle arbitrary chunk boundaries, partial frames, multiple
  frames per chunk, empty chunks, malformed lengths, oversized frames, and
  truncated streams.
- Keepalive and rejection remain ordinary envelope frames.

### D5. Authentication And Liveness
- The stream-open request is authenticated once using the same transport proof
  model as the existing event endpoint.
- The proof must sign the exact generated method, path, and request body bytes.
- Phase 0 must lock how transport proof replay and expiry interact with
  long-lived streams. If proof TTL is shorter than the stream lifetime, either
  the proof explicitly authorizes only stream establishment or the stream must
  be renewed or re-authenticated before expiry.
- Long-lived stream health is enforced by session ownership, event keepalive,
  control activity, negotiated liveness timeout, and stream close/dead
  transitions.
- Armeria request timeout policy must be changed or overridden so a healthy
  stream is not closed by the generic request timeout. The production override
  must be scoped to the stream route or stream service; disabling the server
  global timeout for a mixed finite/streaming assembly would also remove the
  timeout guard from finite control endpoints.
- Application-level keepalive frames are assumed to maintain Sigilaris session
  liveness, not arbitrary middlebox liveness. If a deployment places load
  balancers or proxies between static peers, their idle timeouts must be handled
  by deployment transport configuration or an explicit follow-up.

### D6. Backpressure, Overflow, And Slow-Peer Isolation
- The retained source and cursor state must remain the source of truth. A
  per-session queue must not be the only place where undelivered events live.
- A slow peer must not block source append, other peer sessions, or unrelated
  chain-topic delivery.
- Wakeup queues may be bounded and coalescing. Overflow may drop wakeup signals,
  but not retained events; the next successful wakeup drains by cursor from the
  retained source.
- Once an event frame is selected for emission to a stream, it must not be
  silently dropped. If the stream cannot make progress, backpressure is isolated
  to that session until liveness policy closes or reconnects it.
- In-flight frame loss: selected-but-unsent frame availability is still bounded
  by the retained source horizon. If global retention prunes beyond a slow
  session before the frame is delivered, the implementation must surface
  stale-cursor, backfill, reconnect, or close behavior instead of skipping the
  gap silently.
- Cursor-lag loss: if the retained source prunes beyond a slow peer's resume
  cursor before the next drain selects frames, the runtime must use the existing
  stale-cursor/backfill/reconnect/close path instead of growing memory without
  bound.
- Phase 0 must lock the concrete implementation policy: pull-on-demand,
  bounded coalescing wakeups, bounded per-session frame queues, or another
  equivalent policy that satisfies the rules above.

### D7. Compatibility Position
- The old finite-poll client and endpoint do not need to remain public.
- Retaining poll as a temporary private test fixture is allowed only if it
  reduces migration risk.
- If a finite-poll fixture is mounted while streaming is mounted, it must use a
  route distinct from the streaming endpoint. If the production stream reuses
  `POST /gossip/events/{sessionId}`, the poll fixture must be unmounted or
  exercised below the HTTP endpoint layer.
- Release notes must state that downstream consumers should move to the stream
  peer transport API.

## Phase 0 Locked Decisions
Locked on 2026-07-01 after the compile and loopback spike passed against the
pinned Sigilaris dependency set.

- Endpoint path: keep the finite poll fixture at
  `POST /gossip/events/{sessionId}` during migration. The stream-open endpoint
  is `POST /gossip/events/{sessionId}/stream`, so one Tapir endpoint value is
  never both `byteArrayBody` and `streamBinaryBody`.
- Stream-open request body: use `StreamOpenRequestWire(kind = "stream",
  resume = Option[Vector[CursorEntryWire]])`. The optional composite cursor is
  the reconnect resume contract; absence means the runtime uses the durable
  cursor state already established for the session.
- Server capability strategy: `ArmeriaServer` accepts
  `List[ServerEndpoint[Fs2Streams[F], F]]`. Non-stream `ServerEndpoint[Any, F]`
  values widen into that capability, while streaming endpoints compile with
  `Fs2Streams[F]`.
- Client capability strategy: derive stream requests with
  `StreamSttpClientInterpreter` and send them with STTP4
  `ArmeriaFs2Backend`. `ArmeriaCatsBackend` remains the non-stream backend for
  existing request/response clients.
- Armeria timeout policy: Phase 0 proves Armeria can run the spike server with
  `ArmeriaServerConfig.requestTimeout = Duration.ZERO`, but production mixed
  finite/streaming assemblies must keep the finite-endpoint timeout guard and
  apply no-timeout or extended-timeout behavior at the stream route/service
  boundary in Phase 3. Stream client requests treat
  `requestTimeout = Duration.ZERO` as STTP `Duration.Inf`, disabling the client
  read timeout for the long-lived response. Liveness remains a Sigilaris
  session policy.
- Runtime wakeup shape: use a runtime-owned per-session wakeup bus. Source
  append, control application, replay/request state, timer deadlines, and
  session termination publish coalescing wakeups; the stream driver drains from
  retained source and cursor state.
- Slow-peer/backpressure policy: use pull-on-demand drains plus bounded,
  coalescing wakeups. Retained source plus cursor state stays the source of
  truth. A selected frame is not silently dropped; if delivery stalls until the
  retention horizon is exceeded, the runtime surfaces stale-cursor, backfill,
  reconnect, or close behavior.
- Stream-open auth input: the proof signs `POST`, the rendered
  `/gossip/events/{sessionId}/stream` path, and the exact stream-open request
  body bytes.
- Stream-open error channel: the Tapir stream endpoint uses `stringBody`
  `errorOut` for HTTP-level auth/open failures. Protocol-level stream
  rejections after a successful open are encoded as binary frames in the `200`
  `application/octet-stream` response.
- Transport-proof lifetime: the current proof has no timestamp, nonce, or TTL,
  so it authorizes stream establishment only. Long-lived stream health is
  controlled by session ownership, event keepalive, control activity, negotiated
  liveness timeout, and stream close/dead transitions.
- ADR-0033 is promoted to `Accepted` by this Phase 0 decision lock.

## Phase 1 Implementation Notes
Completed on 2026-07-01.

- `BinaryEventStreamCodec` now exposes length-prefixed per-frame
  `encodeFrame` and `decodeFrame` primitives.
- Incremental decoding uses `DecoderState`, `decodeChunk`, and `finishDecode`
  so complete frames are emitted while partial frame bytes remain buffered
  until more stream bytes arrive or end-of-stream reports truncation.
- FS2 helpers `encodeFrames` and `decodeFrames` convert between
  `EventEnvelopeWire[A]` values and `application/octet-stream` bytes without
  materializing the whole response.
- The existing whole-vector `encode` and `decode` helpers remain available
  during migration and are implemented on top of the new frame primitives.
- The outer frame length parser follows the canonical `BigNat` wire format and
  rejects long-form length prefixes whose declared-size magnitude width exceeds
  the maximum frame-size width, preventing unbounded buffering before the
  `MaxFrameSizeBytes` guard can run.
- Tests cover arbitrary chunk boundaries, empty chunks, partial frames,
  multiple frames per chunk, malformed length prefixes, oversize frames,
  unknown version/kind tags, corrupt middle frames, truncated streams, and
  FS2 pipe round trips.

## Phase 2 Implementation Notes
Completed on 2026-07-01.

- `TxGossipRuntime.streamEvents` now exposes a transport-neutral FS2 stream for
  outbound event sessions. It validates the open outbound session, registers a
  wakeup resource once per stream, drains retained source state through the same
  `pollOpenSession` planning path as finite poll, and waits only on wakeups or
  exact keepalive/flush deadlines.
- `TxGossipWakeupBus` provides runtime-owned per-session wakeups using bounded
  capacity-one queues and non-blocking coalescing offers. Duplicate live stream
  registrations for the same session are rejected so two streams cannot race one
  session cursor.
- Source append wakeups are advisory. `InMemoryTxArtifactSource` and
  `InMemoryHotStuffArtifactSource` publish after the retained append is visible
  to subsequent reads, and notifier failures do not roll back or block appends.
- Applied control batches wake the event stream for request/replay work, while
  session close/dead transitions publish termination wakeups. Stream
  finalization marks the session dead so selected-but-unsent frames are surfaced
  through reconnect from the durable cursor rather than silently skipped on the
  same session.
- `nextFlushDeadline` computes the next partial-batch flush deadline without
  mutating cursor state. Existing sidecar holds deliberately avoid immediate
  retry deadlines to prevent hot loops; source/control wakeups and keepalive
  deadlines re-drain the session.
- Runtime tests cover source append wakeup, `RequestById` control wakeup,
  cancellation cleanup and session-dead marking, keepalive timer wakeup, partial
  flush timer wakeup, duplicate stream registration rejection, and slow-peer
  coalescing that does not block another session.
- HotStuff sidecar tests cover the held-proposal liveness path: after a
  required sidecar miss records a hold, a retained sidecar append publishes a
  source wakeup and the FS2 stream emits the sidecar plus proposal without a
  fixed polling loop.
- Validation note: one Armeria adapter combined run saw a non-reproduced
  truncated-frame decode failure in a finite poll test; the adapter suite then
  passed alone and in the final combined Phase 2 rerun.

## Phase 3 Implementation Notes
Completed on 2026-07-01.

- `TxGossipArmeriaAdapter` now mounts the Tapir-derived
  `POST /gossip/events/{sessionId}/stream` endpoint alongside the temporary
  finite poll fixture. The stream endpoint verifies the transport proof against
  the exact stream path and request body, authorizes the authenticated peer for
  an open session, applies an optional resume cursor, and returns
  `TxGossipRuntime.streamEvents` encoded through the incremental binary frame
  pipe.
- Stream-open HTTP/auth/open failures remain HTTP-level `stringBody` errors.
  Protocol-level stream-open failures after a valid authenticated open request
  are returned as short `200 application/octet-stream` streams containing a
  binary rejection frame, including malformed stream-open JSON bodies that pass
  transport authentication.
- Source-breaking note: `TxGossipArmeriaAdapter.endpoints` and
  `HotStuffGossipArmeriaAdapter.endpoints` now return
  `List[ServerEndpoint[Fs2Streams[F], F]]` so the combined compile/test
  convenience endpoint lists can include the FS2 stream endpoint.
- `TxGossipRuntime.applyStreamResumeCursor` applies stream-open resume cursors
  using the same subscription validation as `ControlOp.SetCursor` without
  allocating a control-batch idempotency key.
- `ArmeriaServer.resourceWithScopedStreamTimeout` lets mixed assemblies bind
  finite endpoints with the configured generic timeout while binding the
  long-lived stream route with a scoped timeout, normally `Duration.ZERO`.
- `HotStuffGossipArmeriaAdapter` exposes finite and stream endpoint groups so
  combined HotStuff gossip servers can use the scoped stream timeout assembly.
- Server tests cover source-append delivery over the FS2 octet stream, exact
  stream path/body auth signing including raw received bodies, binary rejection
  frames for protocol failures and stale resume cursors, resume cursor delivery,
  route-scoped timeout configuration, long-open stream survival past the finite
  timeout, and Armeria client early termination releasing the server wakeup
  registration. Delivery tests gate appends on stream wakeup registration rather
  than fixed sleeps, and unopened sessions are pinned as HTTP-level stream-open
  failures. KeepAlive frame mapping remains covered by runtime and binary-codec
  tests. A production mount search found no non-test callers mounting the
  combined convenience endpoint list through the generic finite timeout helper.

## Phase 4 Implementation Notes
Completed on 2026-07-02.

- `TxGossipPeerClient` now exposes `streamEvents`, built from the Tapir-derived
  stream-open request and `ArmeriaFs2Backend`. Stream-open auth is still signed
  over the exact generated method, path, and raw body, including the optional
  resume cursor body shape locked in Phase 0.
- Source-breaking note: `TxGossipPeerClient` implementers must now implement
  `streamEvents`; Phase 5 will remove production finite-poll event delivery
  once the migration tests are in place.
- `GossipTapirClientCore.sendEventStreamEndpoint` sends the stream request with
  the STTP FS2 streaming backend, classifies stream-open transport and HTTP
  failures separately, decodes the `application/octet-stream` body through
  `BinaryEventStreamCodec.decodeFrames`, surfaces codec-raised
  `IllegalArgumentException`s as peer-client response decode errors, and
  classifies non-codec post-open stream failures as transport failures.
- Binary rejection frames are preserved as event-level `EventEnvelopeWire`
  rejection values on the streaming path. HTTP-level stream-open failures remain
  peer-client errors so callers can distinguish open failures from in-stream
  protocol rejections.
- `HotStuffPeerTransportClient` assembly now owns both finite request and FS2
  stream backends. It keeps a shared finite-request gate for control,
  bootstrap, disconnect, and temporary poll compatibility calls, while giving
  each peer its own long-lived stream gate so one peer's open stream cannot
  block opening another peer's stream.
- Client resource configuration accepts a dedicated `streamRequestTimeout`,
  where `Duration.ZERO` keeps the long-lived Armeria client stream unbounded
  while finite request timeout validation and behavior remain unchanged.
- Phase 4 uses separate STTP Armeria backends for finite request/response calls
  and FS2 stream calls because their capability types differ in the pinned
  Tapir/STTP versions. This intentionally creates separate client resources for
  finite traffic and long-lived event streams.
- Adapter tests cover generated client stream delivery after the stream is
  registered, binary rejection-frame decoding, HTTP failure classification,
  peer-isolated stream gates with `maxConcurrentRequests = 1`, and cancellation
  cleanup through server wakeup registration release. Existing Tapir capability
  tests continue to cover early streamed-byte observation and exact stream-open
  auth.

## Phase 5 Implementation Notes
Completed on 2026-07-02.

- `TxGossipPeerClient.pollEvents` and the generated finite event client helpers
  were removed from the production peer client surface. Production event
  delivery now opens `streamEvents` through the Tapir-derived stream-open
  endpoint and the Armeria FS2 backend.
- The finite `POST /gossip/events/{sessionId}` server route remains mounted as
  a distinct transition/codec compatibility route. Tests that exercise this
  route do so through direct HTTP fixtures, not through the production peer
  client API.
- Generated client, HotStuff peer transport, and application-topic transport
  loopback tests now consume `streamEvents`. Binary rejection and HTTP-level
  open failure assertions remain explicit on the streaming path.
- `HotStuffLaunchSmokeSuite` now installs a long-lived stream-open client fiber
  for each directed mesh link and applies streamed frames to the receiving
  runtime as they arrive. Its Armeria server assembly uses
  `resourceWithScopedStreamTimeout`, so the stream route has its own long-lived
  timeout while finite endpoints keep the generic request timeout guard. The
  old fixed finite-poll relay loop was removed.
- HotStuff bootstrap assembly wires a `TxGossipWakeupBus` into the in-memory
  artifact source and exposes that bus on bootstrap results so launch tests can
  drive event-driven timer wakeups explicitly.
- Source-rule regressions assert that production peer transport sources do not
  expose or call the finite event client helpers, and that the launch smoke
  does not reintroduce a finite event poll relay.
- Draft v0.2.10 release notes and the HotStuff application gossip handoff doc
  describe the streaming peer transport and state that old finite poll
  compatibility is not guaranteed for downstream consumers such as BBGO.

## Phase 6 Implementation Notes
Completed on 2026-07-02 KST.

- Sigilaris `0.2.10-SNAPSHOT` was republished locally with `sbt
  -Dsbt.supershell=false -Dsbt.server=false publishLocal` after Phase 5. The
  local Ivy publish included `sigilaris-core_3`, `sigilaris-core_sjs1_3`,
  `sigilaris-node-common_3`, `sigilaris-node-common_sjs1_3`, and
  `sigilaris-node-jvm_3`.
- BBGO already references `org.sigilaris` `0.2.10-SNAPSHOT` for
  `sigilaris-core`, `sigilaris-node-common`, and `sigilaris-node-jvm`.
  Downstream compile passed with the newly published streaming transport API:
  `sbt -Dsbt.supershell=false -Dsbt.server=false --error 'node / Test /
  compile'`.
- BBGO consensus mode builds through
  `HotStuffRuntimeBootstrap.fromTopologyWithApplicationTopics`, so it consumed
  the Sigilaris streaming peer transport by upgrading the local
  `0.2.10-SNAPSHOT` artifacts. No BBGO source edit was required for this
  handoff because BBGO does not call the removed `TxGossipPeerClient.pollEvents`
  API directly.
- BBGO assembly passed with `sbt -Dsbt.supershell=false -Dsbt.server=false
  --error 'node / assembly'`. The resulting artifact was
  `modules/node/target/scala-3.7.3/bbgo-node-assembly-3.0.0-SNAPSHOT.jar`
  with SHA-256
  `2681f58656e0d1d87f4b5b414832cc3dd74b44e44c61528149a08a6f198f29b1`.
- The operator-local BBGO inventory
  `deploy/local-cluster-audit-nonblocking.local.conf` was adjusted outside Git
  to bind the production digest gate to that assembly hash. The first remote
  attempt stopped before data-root deletion because the inventory still
  expected the previous `b28f01be...` artifact hash.
- The remote five-node public `/tx-pipeline` dependent-payment smoke passed
  after the digest pin update:
  `scripts/five-node-audit-submit-smoke.sh --inventory
  deploy/local-cluster-audit-nonblocking.local.conf --work-root
  target/five-node-audit-submit-smoke-0030-phase6 --scenario
  dependent-payment --submit-endpoint tx-pipeline --submit-node validator-a
  --submit-wait finalized --query-mode remote-curl --readiness-timeout 180
  --final-query-timeout 180 --allow-data-reset --skip-assembly
  --keep-running-on-failure`.
- Successful run:
  `target/five-node-audit-submit-smoke-0030-phase6/20260701T173743Z`.
  `dependent-payment-validation-summary.json` reported
  `correctness=passed`; mint and escrow submissions were accepted by
  `validator-a` and finalized; validator-a/b/c/d converged to finalized block
  `2f17853406faffdcac1a85bd6ffdcf769df197022278e6f29b0e924066dda1e5`.
- Latency snapshot from `dependent-payment-latency-breakdown.json`:
  critical path `t1SubmittedToT2FinalizedMillis=2806`, submit response
  `2734`, `t1SubmittedToT1ProposedMillis=971`,
  `t1ProposedToT1CertifiedMillis=215`,
  `t2SubmittedEligibleToT2ProposedMillis=2134`,
  `t2ProposedToT2CertifiedMillis=68`, and
  `t2CertifiedToT2FinalizedMillis=602`. These measurements are overlapping
  phase observations, not additive segments. The low-latency gate was not
  enforced in this Phase 6 handoff run.
- Informal latency comparison point: the previous BBGO 0028 Phase 5 remote
  three-run window recorded critical paths `4033`, `4614`, and `3053` ms. The
  Phase 6 single-run critical path was `2806` ms, which is useful handoff
  evidence but not a new latency gate claim.
- Downstream submitted-scope and recovery counters were all zero in the
  successful run: `unknownRequestedArtifact`, `pipelineStageMergeFailed`,
  `validationMissBackfill`, `submittedScopeValidationMiss`,
  `submittedScopeMetadataMiss`, `submittedScopeAcceptedMarkerMiss`,
  `submittedScopeInheritedGateMissIfCertifyingQuorum`, and
  `submittedScopeCertifyingQuorumBlocker`.
- Stream diagnostics were present in BBGO `/status` on all five nodes. The
  success snapshots showed retained/appended HotStuff gossip source counts for
  proposal, vote, timeout-vote, and new-view topics, `staleCursorRejections=0`
  for every topic on every node, and no sink rejected artifacts. This confirms
  the downstream runtime can observe the Sigilaris streaming gossip diagnostic
  surface while running the dependent-payment pipeline smoke. The all-zero
  stale-cursor and rejection counters mean those failure paths were not
  triggered by this happy-path smoke; loss-path behavior remains covered by the
  focused Sigilaris runtime/server tests from earlier phases.

## Change Areas

### Code
- `build.sbt`
  - add or adjust dependencies only if Phase 0 proves the current Tapir/STTP
    streaming modules are incomplete.
- `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip`
  - add stream/wakeup model types if they belong in the transport-neutral
    contract.
- `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/tx`
  - add `streamEvents`-style runtime API;
  - reuse existing poll/drain planning logic where correct, but drive it from
    notifications and timers;
  - publish wakeups from control operations and session lifecycle changes.
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria`
  - make `ArmeriaServer` streaming-capable and set long-lived stream timeout
    policy.
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip`
  - change Tapir event endpoint output from `byteArrayBody` to FS2
    `streamBinaryBody`;
  - attach server logic that returns `Stream[F, Byte]`;
  - add streaming peer client API;
  - update transport auth extraction for the generated stream request;
  - remove production dependence on `pollEvents`.
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff`
  - update `InMemoryHotStuffArtifactSource` to publish source wakeups;
  - update launch/bootstrap wiring to use the streaming peer transport.

### Tests
- Tapir/Armeria compile and loopback tests for FS2 octet-stream endpoints.
- Binary frame property tests with arbitrary chunk boundaries.
- Server adapter tests for stream open auth, rejection frame emission, keepalive,
  normal event emission, session close, and malformed stream-open requests.
- Client tests for stream decoding, HTTP failure classification, binary
  rejection frames, reconnect behavior, and cancellation cleanup.
- Runtime tests proving source append and control application wake streams
  without fixed poll sleeps.
- Backpressure and bounded-buffer tests.
- Slow-peer tests proving one blocked stream does not block source append or
  delivery to another session.
- Retention-horizon tests proving selected-but-unsent frames that fall out of
  retention surface as stale-cursor, backfill, reconnect, or close behavior
  rather than silent gaps.
- Reconnect/resume tests proving stream-open resumes from the locked cursor
  contract and handles stale cursors explicitly.
- HotStuff launch smoke using streaming gossip delivery.
- Import or source-rule regression ensuring production steady-state gossip
  client code no longer calls finite `pollEvents`.

### Docs
- Add ADR-0033 and this plan.
- Update release notes for the target Sigilaris release.
- Update HotStuff gossip handoff docs with the streaming peer transport API.
- Add downstream migration notes for BBGO.

## Implementation Phases

### Phase 0: Contract And Capability Spike
- Compile a minimal Tapir FS2 `application/octet-stream` endpoint with
  `ArmeriaCatsServerInterpreter`.
- Compile a matching Tapir-derived STTP4 stream client with the Armeria FS2
  backend that consumes `Stream[F, Byte]`.
- Decide the final endpoint path, request body shape, and poll-fixture
  coexistence rule.
- Decide the stream-open resume cursor shape.
- Decide the `ArmeriaServer` endpoint capability type strategy.
- Decide the stream timeout policy for long-lived Armeria server and client
  requests.
- Decide the wakeup contract shape: source extension, notifier service, or
  runtime-owned wakeup bus.
- Decide the concrete slow-peer overflow and backpressure isolation policy.
- Validate the transport proof signing tuple for stream-open requests.
- Lock the relationship between transport proof TTL/replay policy and
  long-lived streams.
- Promote ADR-0033 to `Accepted` if the spike validates the selected design.

### Phase 1: Incremental Binary Frame Codec
- Add per-frame encode/decode primitives to `BinaryEventStreamCodec`.
- Add FS2 encode/decode helpers or pipes for event envelope streams.
- Preserve existing whole-vector encode/decode helpers until dependent tests are
  migrated.
- Add property tests for arbitrary byte chunking and malformed input.
- Add max-frame-size protection if the current decoder does not already enforce
  a usable bound.

### Phase 2: Runtime Stream Driver
- Add a streaming runtime API for outbound event sessions.
- Factor the existing poll/drain planning logic so both migration tests and the
  stream driver can reuse the same cursor, filter, exact-known, request-by-id,
  sidecar, and batching semantics.
- Add runtime wakeups for source append, control application, replay/request
  changes, keepalive deadlines, flush deadlines, and session termination.
- Update `InMemoryHotStuffArtifactSource` to publish chain-topic wakeups after
  appends.
- Ensure cancellation releases any per-session resources and marks or observes
  session termination consistently.

### Phase 3: Tapir Armeria Server Streaming
- Change or add the Tapir event endpoint to return an FS2 octet stream.
- Update `TxGossipArmeriaAdapter` to authenticate stream-open requests and
  return a binary frame stream.
- Encode pre-stream validation failures as a short stream containing a rejection
  frame when the protocol requires an event-level rejection.
- Classify true HTTP-level failures separately from binary event rejections.
- Update `ArmeriaServer` or the stream route/service assembly so long-lived
  stream timeout handling does not disable the generic timeout guard for
  co-mounted finite endpoints.

### Phase 4: Tapir Armeria Client Streaming
- Replace `TxGossipPeerClient.pollEvents` with a streaming API such as
  `streamEvents`.
- Decode the FS2 byte stream into `EventEnvelopeWire[A]` values using the
  incremental codec.
- Convert binary rejection frames into the existing peer-client error surface or
  event-level rejection model.
- Make client cancellation close the underlying stream and release Armeria/STTP
  resources.
- Update `HotStuffPeerTransportClient` assembly to expose the streaming gossip
  client.

### Phase 5: Migration Cleanup And Launch Tests
- Migrate server/client loopback tests from finite poll to streaming delivery.
- Keep finite poll helpers only where a test explicitly checks old codec
  compatibility during the transition.
- Update HotStuff launch smoke to run over streaming event delivery.
- Remove production finite-poll event delivery code after streaming tests pass.
- Update release notes and handoff docs.

### Phase 6: Downstream BBGO Handoff
- Publish or locally publish the Sigilaris snapshot/release containing the
  streaming peer transport.
- Update BBGO to consume the new Sigilaris transport API in a separate BBGO
  branch or plan.
- Run BBGO dependent transaction pipeline smoke after deployment.
- Compare stream delivery diagnostics with the previous poll-path readiness and
  finality latency breakdowns.

## Test Plan
- `sbt nodeCommonJVM/test`
- `sbt nodeJVM/test`
- Focused suites to add or update:
  - `BinaryEventStreamCodec` incremental frame property suite;
  - `TxGossipArmeriaAdapter` streaming server loopback suite;
  - `TxGossipPeerClient` streaming client loopback suite;
  - `TxGossipRuntime` stream wakeup suite;
  - `HotStuffLaunchSmokeSuite` streaming gossip path;
  - import/source rule suite for removal of production finite-poll event
    delivery calls.
- Manual or scripted smoke:
  - start a local Armeria gossip server;
  - open an event stream;
  - append a HotStuff artifact after the stream is already open;
  - verify first frame arrives without issuing another HTTP request;
  - cancel the client stream and verify server cleanup.
- Downstream verification:
  - BBGO compile against the new Sigilaris API;
  - BBGO remote dependent transaction pipeline smoke;
  - collect stream diagnostics, readiness waits, finality latency summary, and
    two-transaction critical latency.

## Risks And Mitigations
- Risk: Tapir/STTP/Armeria streaming client support does not compile cleanly for
  the pinned versions.
  - Mitigation: Phase 0 compile spike is mandatory before broad runtime edits.
    If it fails, revise ADR-0033 instead of silently replacing Tapir with a
    custom production service.
- Risk: the stream is long-lived but Armeria request timeout closes it, or a
  broad timeout override removes the guard from finite endpoints.
  - Mitigation: Phase 0 locks client timeout mapping and the requirement that
    server no-timeout behavior is scoped to the stream route/service in Phase
    3; the spike verifies Armeria can serve the stream with timeout disabled.
- Risk: the implementation hides a fixed poll loop inside FS2.
  - Mitigation: add runtime tests that use explicit source/control wakeups and
    fail without those wakeups.
- Risk: source append is not enough to wake all deliverable work.
  - Mitigation: include control application, request-by-id, nack/replay,
    batching flush, keepalive, and sidecar state changes in the wakeup model.
- Risk: slow peers cause unbounded memory growth.
  - Mitigation: make retained source plus cursor state the source of truth, use
    bounded/coalescing wakeups or pull-based reads, isolate backpressure per
    session, and test slow consumers against another live session.
- Risk: a selected-but-unsent frame is pruned by the global retention horizon.
  - Mitigation: make retention horizon the explicit durability bound for
    selected frames and test that horizon loss becomes stale-cursor, backfill,
    reconnect, or close behavior, never a silent gap.
- Risk: incremental binary decoding mishandles frame boundaries.
  - Mitigation: property-test arbitrary chunk boundaries and malformed/truncated
    frames.
- Risk: one-time stream-open auth is weaker than per-request poll auth.
  - Mitigation: keep session ownership checks, negotiated liveness, authenticated
    stream open proof, proof TTL/replay policy, and topic-level artifact
    validation; document that per-frame HMAC is not part of this ADR.
- Risk: stream reconnect resumes from an ambiguous cursor position.
  - Mitigation: lock a stream-open resume cursor contract in Phase 0 and test
    reconnect, duplicate delivery, stale cursor, and retained-horizon loss.
- Risk: event delivery improves but reverse control feedback remains on the old
  request/response path.
  - Mitigation: document this as a non-goal and keep streaming control or
    full-duplex transport as a follow-up if measurements identify it as the next
    bottleneck.
- Risk: BBGO latency remains high after stream delivery.
  - Mitigation: treat stream delivery as removing the poll transport bottleneck
    only; continue separate readiness quorum and pacemaker/finality-drive
    diagnosis in BBGO if needed.

## Acceptance Criteria
1. Production gossip event delivery uses Tapir FS2
   `application/octet-stream` streaming, not finite event polling.
2. The streaming endpoint and client are both derived from Sigilaris Tapir
   endpoint definitions.
3. `BinaryEventStreamCodec` supports incremental frame streaming and retains
   the logical event, keepalive, and rejection envelope semantics.
4. `TxGossipRuntime` can emit events from source/control wakeups without a fixed
   polling loop.
5. Stream-open resume cursor behavior is locked and tested for reconnect and
   stale cursor cases.
6. Slow-peer overflow behavior is locked and tested so one blocked stream does
   not block source append or another session.
7. If finite poll remains mounted during migration, it uses a route distinct
   from the streaming endpoint.
8. Armeria server timeout and client cancellation behavior are tested for
   long-lived streams.
9. HotStuff gossip launch smoke passes with streaming event delivery.
10. Production steady-state gossip client code no longer depends on
   `pollEvents`.
11. Release and handoff docs explain that BBGO should migrate to the streaming
   peer transport and that old finite poll compatibility is not guaranteed.

## Checklist

### Phase 0: Contract And Capability Spike
- [x] Compile minimal Tapir FS2 octet-stream server endpoint.
- [x] Compile matching Tapir-derived Armeria client stream consumer.
- [x] Lock endpoint path, request body shape, and poll-fixture coexistence rule.
- [x] Lock stream-open resume cursor shape.
- [x] Lock `ArmeriaServer` capability type strategy.
- [x] Lock Armeria long-lived stream timeout policy.
- [x] Lock runtime wakeup contract shape.
- [x] Lock concrete slow-peer overflow and backpressure isolation policy.
- [x] Validate stream-open transport proof signing input.
- [x] Lock transport proof TTL/replay policy for long-lived streams.
- [x] Promote ADR-0033 to `Accepted` or revise it.

### Phase 1: Incremental Binary Frame Codec
- [x] Add per-frame binary encode/decode primitives.
- [x] Add FS2 frame encode/decode helpers.
- [x] Preserve temporary whole-vector helpers during migration.
- [x] Add chunk-boundary and malformed-frame tests.

### Phase 2: Runtime Stream Driver
- [x] Add streaming runtime API.
- [x] Factor reusable drain/planning logic from poll implementation.
- [x] Add source/control/session/timer wakeups.
- [x] Update in-memory HotStuff source to publish append wakeups.
- [x] Add cancellation cleanup behavior.
- [x] Add runtime wakeup tests.

### Phase 3: Tapir Armeria Server Streaming
- [x] Change or add FS2 streaming event endpoint.
- [x] Attach authenticated server stream logic.
- [x] Emit binary rejection frames for protocol-level stream-open failures.
- [x] Classify HTTP-level failures separately.
- [x] Test long-lived stream timeout behavior.

### Phase 4: Tapir Armeria Client Streaming
- [x] Add streaming client API.
- [x] Decode byte stream into event envelopes.
- [x] Map binary rejection frames and decode failures.
- [x] Ensure client cancellation closes resources.
- [x] Update peer transport assembly.

### Phase 5: Migration Cleanup And Launch Tests
- [x] Migrate loopback tests to streaming delivery.
- [x] Remove production finite-poll event delivery.
- [x] Run HotStuff launch smoke on streaming gossip.
- [x] Update release notes.
- [x] Update handoff docs.

### Phase 6: Downstream BBGO Handoff
- [x] Publish or locally publish the Sigilaris streaming transport build.
- [x] Compile BBGO against the new API in a downstream branch.
- [x] Run BBGO remote dependent transaction pipeline smoke.
- [x] Capture and compare stream diagnostics and tx pipeline latency.

## Follow-Ups
- BBGO repeated-run latency calibration for the streaming peer transport,
  including the separate 500 ms low-latency gate work.
- Optional streaming control channel or full-duplex transport ADR if control
  request/response overhead becomes the next bottleneck.
- Additional performance plan if BBGO latency remains dominated by readiness
  quorum or pacemaker/finality-drive behavior after stream delivery lands.
