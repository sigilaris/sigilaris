# 0020 - Armeria Tapir Client Gossip Transport Plan

## Status
Release Build Prepared (v0.2.5) - Resolver Publish Pending

## Created
2026-06-19

## Last Updated
2026-06-19

## Background
- The node-to-node gossip and HotStuff bootstrap server endpoints are already defined with Tapir and served through Armeria.
- The client side still constructs HTTP requests manually in `HotStuffBootstrapHttpTransport` with Java `HttpClient`.
- Gossip adapter and launch tests also use ad hoc Java `HttpClient` requests for session, event-stream, control, disconnect, and bootstrap coverage.
- `0.2.4` shipped the HotStuff application gossip topic extension. Embedders now need a reusable client-side peer transport, not application-specific 111xx request builders.
- The `0.2.5-SNAPSHOT` line can absorb source-breaking transport construction cleanup before publishing `0.2.5`.
- The intended direction is a single Tapir endpoint contract used by both server and generated client, with Armeria also used on the client execution side.

## Goal
- Move node-to-node gossip HTTP protocol ownership to shared Tapir endpoint definitions.
- Generate Tapir clients directly from those endpoint definitions for:
  - gossip session open;
  - event poll and keepalive;
  - control batch and keepalive;
  - session disconnect;
  - HotStuff finalized-anchor suggestion;
  - snapshot node fetch;
  - proposal replay;
  - historical backfill.
- Execute endpoint-derived clients through Armeria transport.
- Preserve transport-auth signatures, bootstrap capability authorization, binary event-stream framing, and existing wire paths.
- Replace the production Java `HttpClient` bootstrap transport with the generated Tapir client path.
- Provide a reusable peer transport API that embedders can consume when they bump to Sigilaris `0.2.5`.
- Publish the API-changing version as Sigilaris `0.2.5`.

## Scope
- Extract shared Tapir endpoint specs from the current server adapters.
- Keep server adapters as server-logic attachment layers over the shared endpoint specs.
- Add a Tapir client interpreter path and Armeria execution layer in `sigilaris-node-jvm`.
- Replace `HotStuffBootstrapHttpTransport` with the generated-client implementation or a source-compatible facade over it.
- Add a first-class steady-state gossip peer client for session, event-stream, control, and disconnect calls.
- Add a peer transport assembly API suitable for HotStuff-only and HotStuff-plus-application-topic runtimes.
- Update tests to exercise generated clients against Armeria server endpoints.
- Allow source-breaking API changes in `0.2.5-SNAPSHOT` where they simplify the public transport boundary.
- Update migration docs so downstream users target Sigilaris `0.2.5`, not an intermediate custom transport.

## Non-Goals
- No application-specific admission endpoint is added to Sigilaris.
- No change to public client-facing `/tx` APIs in embedders.
- No change to gossip URL paths, HTTP methods, media types, wire DTOs, rejection strings, or binary event frame format. Any required wire change must leave this plan and go through an ADR or a separate plan before implementation.
- No WebSocket, QUIC, streaming transport replacement, dynamic discovery, validator-set rotation, or peer scoring.
- No synchronous all-peer application-topic apply acknowledgement.
- No OpenAPI-generated client code path. The client is generated directly from shared Tapir endpoint values.

## Related ADRs And Docs
- [ADR-0016: Multiplexed Gossip Session Sync Substrate](../adr/0016-multiplexed-gossip-session-sync.md)
- [ADR-0024: Static-Topology Peer Identity Binding And Session-Bound Capability Authorization](../adr/0024-static-topology-peer-identity-binding-and-session-bound-capability-authorization.md)
- [0019 - HotStuff Application Gossip Topic Extension Plan](0019-hotstuff-application-gossip-topic-extension-plan.md)
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/TxGossipArmeriaAdapter.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/HotStuffBootstrapArmeriaAdapter.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/HotStuffBootstrapHttpTransport.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/TxGossipWire.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/HotStuffBootstrapWire.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/BinaryEventStreamCodec.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/GossipTransportAuth.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/ArmeriaServer.scala`

## Decisions To Lock Before Implementation

### D1. Endpoint Specs Are The Single Source
- Introduce shared endpoint definitions, for example `TxGossipTapirEndpoints` and `HotStuffBootstrapTapirEndpoints`.
- Server adapters must attach logic to those endpoint values instead of defining paths, inputs, outputs, and bodies inline.
- Generated clients must consume the same endpoint values.
- Tests must assert the server routes still expose the existing paths:
  - `POST /gossip/session/open`
  - `POST /gossip/events/{sessionId}`
  - `POST /gossip/control/{sessionId}`
  - `POST /gossip/session/{sessionId}/disconnect`
  - `POST /gossip/bootstrap/finalized/{sessionId}/{chainId}`
  - `POST /gossip/bootstrap/snapshot/{sessionId}/{chainId}`
  - `POST /gossip/bootstrap/replay/{sessionId}/{chainId}`
  - `POST /gossip/bootstrap/backfill/{sessionId}/{chainId}`

### D2. Client Generation And Armeria Execution
- The outcome is endpoint-derived request/response handling with Armeria as the production transport.
- Phase 0 must lock the exact module combination against the pinned Tapir/STTP versions before code changes.
- If a Tapir/STTP Armeria backend is available and exposes enough request metadata for transport auth, use it.
- If the selected Tapir client interpreter cannot execute directly through Armeria for the pinned versions, an Armeria `WebClient` bridge is an accepted implementation path, not a second-class fallback. In that shape Tapir owns endpoint-derived request/response handling and Armeria sends the bytes.
- Do not reintroduce production Java `HttpClient` request construction in Sigilaris gossip/bootstrap transport. This is a source-level ownership rule, not a claim about transitive dependencies inside third-party clients.

### D3. Transport Auth Canonicalization
- Outbound auth must sign the exact HTTP method, request path, and request body bytes that the generated request sends.
- Bootstrap capability headers must be computed from the same generated method/path/body tuple.
- Avoid a second set of manually assembled canonical path strings in production code.
- Phase 0 must include a signed generated-client-to-`ArmeriaServer` loopback spike for every endpoint family before Phase 3 can replace bootstrap transport.
- The loopback gate must cover path variable rendering, URL encoding, empty bodies, JSON string bodies, binary event-stream responses, and bootstrap capability headers.
- Sharing endpoint specs is not enough by itself: server and client interpreters can still render path variables differently, so the signed loopback test is a required gate.

### D4. Wire Compatibility
- Keep current wire DTOs, rejection rendering, URL paths, HTTP methods, media types, and binary frame format unchanged.
- `application/octet-stream` gossip event responses remain decoded by `BinaryEventStreamCodec`.
- The events endpoint has no normal `errorOut`; protocol rejections inside a successful event response are binary rejection frames.
- HTTP-level event failures, non-2xx statuses, connection failures, and body decode failures are transport failures and must be classified outside `BinaryEventStreamCodec`.
- Rejection bodies and rejection events keep their stable reason strings.
- Consensus and application-topic binary payload bytes remain unchanged from `0.2.4`.
- Any required wire-visible change must be split into an ADR or separate plan before publishing `0.2.5`.

### D5. Public API Shape
- `0.2.5` may break source compatibility for transport construction if the new API is cleaner and migration notes are provided.
- Prefer a new explicit client resource/API over preserving Java `HttpClient` constructor parameters.
- Candidate public surface:
  - `TxGossipPeerClient[F, A]` for open, events, control, and disconnect;
  - `HotStuffBootstrapPeerClient[F]` for bootstrap services;
  - `HotStuffPeerTransportClient[F, A]` or equivalent assembly for both;
  - a `Resource[F, ...]` constructor that owns Armeria client lifecycle when Sigilaris creates it.
- Existing runtime bootstrap APIs should expose the generated-client transport without requiring embedders to reimplement peer HTTP.

### D6. Downstream Migration Contract
- Downstream consumers should target Sigilaris `0.2.5` for reusable peer transport instead of adding custom 111xx request builders.
- If an embedder needs a peer call that the generated client does not expose, extend Sigilaris `0.2.5` before adding application-specific peer HTTP.

### D7. ADR Need
- If shared endpoint specs or generated clients change ADR-0016's normative protocol contract, amend ADR-0016 or add a new ADR before publishing `0.2.5`.
- If the work is only a client/server implementation refactor with source-level API cleanup, this plan plus release notes are sufficient.

## Phase 0 Locked Decisions
- Tapir client generation is locked to `tapir-sttp-client4` `1.11.44`, matching the pinned Tapir line.
- Client execution is locked to STTP4 `armeria-backend-cats` `4.0.11`, so outbound peer calls execute through Armeria while Tapir endpoint values render the request shape.
- Phase 0 dependency validation used an sbt compile spike with `tapir-sttp-client4` `1.11.44` and `armeria-backend-cats` `4.0.11` against Scala `3.7.3`.
- Public client type names are locked as `TxGossipPeerClient[F, A]`, `HotStuffBootstrapPeerClient[F]`, and `HotStuffPeerTransportClient[F, A]`.
- Sigilaris-owned client lifecycle is exposed as `Resource[F, ...]` constructors that own the STTP Armeria backend when the caller does not provide one.
- `HotStuffBootstrapHttpTransport` is retained in `0.2.5` as a deprecated compatibility facade over the generated bootstrap client path; production request construction must no longer use Java `HttpClient`.
- `HotStuffBootstrapHttpTransport.servicesResource` is the lifecycle-owning migration path; the retained value-returning `services` facade no longer accepts Java `HttpClient` injection.
- Auth signing input is extracted from the Tapir-generated STTP request immediately before execution: HTTP method, rendered path including any query string, and exact request body bytes.
- Transport auth headers and bootstrap capability headers are appended after that generated request tuple is captured, avoiding a second production path/body rendering source.
- The Phase 2 signed loopback gate must cover gossip session/control/event/disconnect and all bootstrap endpoints before Phase 3 replaces bootstrap transport internals.
- No wire path, method, body, media type, DTO, rejection string, or binary event frame changes are required.
- Downstream migration docs continue to target Sigilaris `0.2.5`; no temporary application-specific peer HTTP client is part of the migration contract.

## Change Areas

### Code
- `build.sbt`
  - add Tapir client and Armeria execution dependencies needed by `sigilaris-node-jvm`;
  - keep dependency additions JVM-only.
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip`
  - add shared Tapir endpoint spec objects;
  - refactor server adapters to use the shared specs;
  - add generated client implementation and Armeria execution layer;
  - replace or wrap `HotStuffBootstrapHttpTransport` with generated-client code;
  - add steady-state gossip peer client API.
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff`
  - update runtime bootstrap assembly to use the new client transport;
  - remove production Java `HttpClient` request construction from HotStuff bootstrap transport wiring.
- Tests currently hand-building Java HTTP requests remain only where the test is intentionally checking raw wire compatibility.

### Tests
- Endpoint spec parity tests for server routes and generated clients.
- Auth canonicalization tests for each endpoint path and body shape.
- Signed generated-client-to-Armeria loopback gate before bootstrap transport replacement.
- Generated client loopback tests against `ArmeriaServer`.
- Binary event-stream decode tests through the generated event client.
- HTTP-level event failure classification tests separate from binary rejection-frame decode tests.
- Bootstrap client tests for finalized, snapshot, replay, backfill, rejection, and capability failure paths.
- HotStuff launch smoke using the generated client transport.
- Application-topic session test proving consensus and application artifacts flow through the generated peer client.
- Import-rule regression asserting production gossip/bootstrap transport source files do not import `java.net.http`; this does not forbid transitive use inside third-party client backends.

### Docs
- Update release notes for `0.2.5`.
- Update HotStuff/application-topic handoff docs with generated client usage.
- Update downstream migration notes to depend on Sigilaris `0.2.5`.
- Document any source-breaking migration from `HotStuffBootstrapHttpTransport.services(...)`.

## Implementation Phases

### Phase 0: API And Backend Lock
- Confirm the exact Tapir client interpreter and Armeria execution module compatible with current Tapir/STTP versions.
- Lock whether the generated client uses:
  - a Tapir/STTP client interpreter with Armeria backend; or
  - a small Armeria `WebClient` bridge around Tapir-generated request/response handling.
- Lock public client type names and resource ownership.
- Lock whether `HotStuffBootstrapHttpTransport` is removed, renamed, or retained as a deprecated facade.
- Lock the method/path/body extraction strategy used before issuing transport auth headers.
- Confirm no wire path/body/media-type changes are required.
- Complete a signed generated-client-to-`ArmeriaServer` loopback spike for every endpoint family.
- Confirm downstream migration docs point to `0.2.5` instead of temporary custom 111xx clients.

### Phase 1: Shared Endpoint Definitions
- Add shared Tapir endpoint specs for gossip session, event, control, disconnect, and HotStuff bootstrap endpoints.
- Move path, header, body, error, and output definitions out of server adapter methods.
- Refactor `TxGossipArmeriaAdapter` to attach server logic to shared specs.
- Refactor `HotStuffBootstrapArmeriaAdapter` to attach server logic to shared specs.
- Add route parity tests against the old endpoint paths and media types.

### Phase 2: Generated Client Core
- Add generated client constructors from shared endpoint specs.
- Add Armeria client execution resource.
- Add auth header injection that signs the generated method/path/body.
- Decode string rejection bodies into the existing `CanonicalRejection` shapes.
- Decode binary event responses with `BinaryEventStreamCodec`.
- Classify HTTP-level event failures separately from binary rejection frames.
- Add generated-client unit tests with recorded request method/path/body/header expectations.
- Complete the signed generated-client-to-Armeria loopback gate before Phase 3.

### Phase 3: Bootstrap Transport Replacement
- Replace `HotStuffBootstrapHttpTransport.services` internals or public API with generated-client bootstrap services.
- Remove production Java `HttpClient` request construction from bootstrap transport.
- Update `HotStuffRuntimeBootstrap` wiring to use the generated-client transport.
- Update bootstrap Armeria adapter tests to exercise the generated client where possible.
- Keep raw-wire tests only for compatibility assertions.

### Phase 4: Steady-State Gossip Peer Client
- Add a reusable client for session open, event poll/keepalive, control batch/keepalive, and disconnect.
- Expose the client through a peer transport API suitable for HotStuff-only and HotStuff-plus-application-topic runtimes.
- Add loopback tests for session lifecycle, event delivery, control rejections, disconnect, and binary event-stream rejection events.
- Add an application-topic loopback test that uses the generated peer client for the same session.

### Phase 5: Release And Downstream Handoff
- Update migration docs and `0.2.5` release notes.
- Publish Sigilaris `0.2.5` after tests pass.
- Start the next development snapshot after publish.
- Document that downstream consumers should bump to `0.2.5` for generated peer transport APIs.
- Verify a downstream compile smoke against the new generated peer transport API when an integration branch is available.

## Test Plan
- `sbt nodeJVM/testOnly *TxGossipArmeriaAdapterSuite`
- `sbt nodeJVM/testOnly *HotStuffBootstrapArmeriaAdapterSuite`
- `sbt nodeJVM/testOnly *HotStuffLaunchSmokeSuite`
- New generated-client suites covering:
  - request rendering;
  - auth header signing;
  - rejection decode;
  - binary event-stream decode;
  - HTTP-level event failure classification;
  - bootstrap capability authorization.
- Signed generated-client-to-Armeria loopback tests must pass before bootstrap transport replacement.
- Import-rule tests asserting production gossip/bootstrap transport source files do not import `java.net.http`; this does not forbid transitive use inside third-party client backends.
- Downstream compile smoke after `0.2.5` when an integration branch is available.

## Risks And Mitigations
- Risk: generated client path rendering differs from manually signed paths.
  - Mitigation: derive auth signing input from the generated request and assert exact path strings in tests.
- Risk: Tapir client backend support does not map cleanly to Armeria for the pinned versions.
  - Mitigation: lock the module combination in Phase 0; if needed, use the planned Armeria `WebClient` bridge while preserving Tapir endpoint-derived request/response handling.
- Risk: source-breaking cleanup disrupts downstream migrations.
  - Mitigation: land Sigilaris `0.2.5` with migration notes before downstream dependency bumps.
- Risk: event-stream errors are hidden because the endpoint returns `byteArrayBody` instead of normal error output.
  - Mitigation: keep `BinaryEventStreamCodec` as the generated client's decoder for successful event response bodies, classify HTTP-level failures outside the codec, and test both binary rejection frames and transport failures explicitly.
- Risk: server and client endpoint definitions drift after extraction.
  - Mitigation: server and client must import the same endpoint spec objects; add route/client parity tests.

## Acceptance Criteria
1. Gossip and bootstrap server routes remain wire-compatible with `0.2.4`: URL paths, methods, media types, wire DTOs, rejection strings, event frame format, and consensus/application payload bytes are unchanged.
2. Shared Tapir endpoint specs are the single source for server and generated client definitions.
3. Production HotStuff bootstrap/gossip transport source files do not import `java.net.http` for request construction.
4. Endpoint-derived clients execute through Armeria transport, either via a compatible Tapir/STTP Armeria backend or a documented Armeria `WebClient` bridge.
5. Transport auth and bootstrap capability signatures are computed from the exact generated method/path/body bytes, proven by signed generated-client-to-Armeria loopback before bootstrap replacement.
6. Generated client tests cover session open, events, control, disconnect, finalized, snapshot, replay, and backfill.
7. Event client tests classify binary rejection frames separately from HTTP-level transport failures.
8. A HotStuff-plus-application-topic loopback test uses the generated peer client for application artifact delivery.
9. Sigilaris is published as `0.2.5`.
10. Downstream migration notes target `0.2.5` and do not require application-specific 111xx HTTP clients.

## Checklist

### Phase 0: API And Backend Lock
- [x] Verify Tapir client interpreter and Armeria execution module compatibility.
- [x] Lock generated client execution strategy.
- [x] Lock public client type names and lifecycle ownership.
- [x] Lock `HotStuffBootstrapHttpTransport` migration strategy.
- [x] Lock auth signing input extraction from generated requests.
- [x] Confirm no wire compatibility changes are needed.
- [x] Complete signed generated-client-to-`ArmeriaServer` loopback spike.
- [x] Confirm downstream migration docs target Sigilaris `0.2.5`.

### Phase 1: Shared Endpoint Definitions
- [x] Add shared gossip endpoint spec object.
- [x] Add shared HotStuff bootstrap endpoint spec object.
- [x] Refactor `TxGossipArmeriaAdapter` to use shared specs.
- [x] Refactor `HotStuffBootstrapArmeriaAdapter` to use shared specs.
- [x] Add route parity tests.

### Phase 2: Generated Client Core
- [x] Add generated client constructors from shared specs.
- [x] Add Armeria client execution resource.
- [x] Add transport-auth and bootstrap-capability header injection.
- [x] Add rejection and binary event response decoding.
- [x] Add HTTP-level event failure classification.
- [x] Add request rendering and auth canonicalization tests.
- [x] Complete signed generated-client-to-Armeria loopback gate.

### Phase 3: Bootstrap Transport Replacement
- [x] Replace production Java `HttpClient` bootstrap request construction.
- [x] Update `HotStuffRuntimeBootstrap` wiring.
- [x] Update bootstrap adapter tests to use generated clients.
- [x] Keep raw-wire compatibility tests where useful.

### Phase 4: Steady-State Gossip Peer Client
- [x] Add session open/event/control/disconnect peer client.
- [x] Add peer transport assembly API for HotStuff runtimes.
- [x] Add session lifecycle loopback tests.
- [x] Add application-topic loopback test through the generated client.

### Phase 5: Release And Downstream Handoff
- [x] Update `0.2.5` release notes and migration docs.
- [ ] Publish Sigilaris `0.2.5`.
- [ ] Start next development snapshot.
- [x] Document downstream dependency bump guidance for `0.2.5`.
- [ ] Verify downstream compile against the generated peer transport API when an integration branch is available.

## Follow-Ups
- Consider moving shared endpoint specs to a transport-neutral module only if a non-JVM client needs them.
- Consider generated OpenAPI client artifacts only if external consumers appear; current in-repo consumers should use Tapir endpoint-derived clients directly.
- Revisit WebSocket or streaming transport only after the HTTP generated-client path is stable.
