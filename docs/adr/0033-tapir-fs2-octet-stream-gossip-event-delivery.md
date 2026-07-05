# ADR-0033: Tapir FS2 Octet-Stream Gossip Event Delivery

## Status
Accepted

Accepted on 2026-07-01 in Plan 0030 Phase 0 after the Tapir/Armeria streaming
endpoint and client capability types, stream-open resume shape, per-session
backpressure policy, and transport-proof lifetime policy were locked against
the pinned Sigilaris dependency set.

## Context
- ADR-0016 defines a logical gossip event stream and control channel per
  directional session.
- The current Armeria/Tapir implementation uses the event stream name for a
  finite polling HTTP endpoint:
  `POST /gossip/events/{sessionId}` accepts a JSON event request and returns one
  `application/octet-stream` byte array containing zero or more length-prefixed
  binary event frames.
- The current client API is `pollEvents`; the server calls
  `TxGossipRuntime.pollEvents` once and then completes the HTTP response.
- This shape preserves the session protocol but still leaves event delivery
  paced by client polling, peer loops, transport round trips, and keepalive
  requests.
- BBGO is currently the only Sigilaris consumer and is managed in the same
  deployment path. Source and wire compatibility with older Sigilaris gossip
  poll clients is therefore not a primary constraint for the next transport
  update.
- Sigilaris already pins Tapir, Armeria Cats server support, STTP4 Tapir client
  support, Armeria Cats client backend support, cats-effect, and fs2. Plan 0030
  Phase 0 adds the matching STTP4 Armeria FS2 backend for streaming response
  consumption. The intended transport can be expressed as a Tapir FS2 streaming
  body with `CodecFormat.OctetStream()`.

## Decision
1. **Use Tapir FS2 streaming output as the production gossip event transport.**
   - The event channel is represented as `fs2.Stream[F, Byte]`.
   - Tapir endpoints use `streamBinaryBody(Fs2Streams[F])(CodecFormat.OctetStream())`.
   - The HTTP content type is `application/octet-stream`.
   - The response is long-lived and must not be materialized into an
     `Array[Byte]` before transfer.
   - Server endpoints are interpreted by `ArmeriaCatsServerInterpreter`; stream
     clients use the Tapir STTP4 stream interpreter with `ArmeriaFs2Backend`.

2. **Replace finite polling as the production event delivery contract.**
   - The production event client exposes a streaming API, not `pollEvents`.
   - The old finite-poll endpoint and client may be kept only as a temporary
     implementation or test scaffold during the migration.
   - While the old finite-poll endpoint is mounted during migration, the new
     stream endpoint uses `POST /gossip/events/{sessionId}/stream`. A single
     Tapir endpoint value cannot be both `byteArrayBody` polling and
     `streamBinaryBody` streaming.
   - Published runtime and transport APIs do not need to preserve source
     compatibility with the finite-poll client because BBGO is the only managed
     downstream consumer.

3. **Make stream-open resume position explicit.**
   - A stream-open request must define where delivery resumes after reconnect.
   - The stream-open request body uses an optional composite cursor:
     `StreamOpenRequestWire(kind = "stream", resume =
     Option[Vector[CursorEntryWire]])`.
   - The production contract must not depend on an implicit "start wherever the
     server last happened to be" position.

4. **Keep the directional event channel unidirectional and keep control as
   ordinary HTTP requests for this change.**
   - The event channel remains producer-to-consumer for one directional
     session.
   - The control channel remains `POST /gossip/control/{sessionId}` with JSON
     request/response bodies.
   - Full-duplex WebSocket, QUIC, bidirectional HTTP/2 stream, and streaming
     control delivery are follow-up transport options, not part of this
     decision.

5. **Preserve the binary frame envelope while changing delivery from batch array
   to incremental stream.**
   - The stream emits the same logical `EventEnvelopeWire[A]` values currently
     used by `BinaryEventStreamCodec`.
   - The length-prefixed frame format remains the canonical binary
     representation unless Plan 0030 finds a correctness issue.
   - `BinaryEventStreamCodec` must grow incremental frame encoding and decoding
     APIs so frames survive arbitrary network chunk boundaries.
   - Keepalive and rejection messages remain normal event envelopes. Empty
     stream chunks are not protocol messages.

6. **Make runtime delivery event-driven, not a poll loop inside a stream.**
   - A streaming implementation that repeatedly calls `pollEvents` on a fixed
     short sleep is not the accepted production shape.
   - The runtime stream driver must wake on source append notifications, control
     operations that make work available, replay or explicit request state
     changes, batching flush timers, keepalive timers, and session termination.
   - Bounded fallback timers are allowed for defensive recovery, but they must
     not be the primary delivery mechanism.

7. **Transport authentication is checked when the stream is opened.**
   - The event stream open request is authenticated with the same peer identity
     and transport proof model used by existing peer endpoints.
   - The proof signs the HTTP method, rendered path, and request body that open
     the stream.
   - The current transport proof has no nonce, timestamp, or TTL. It authorizes
     stream establishment only; session ownership, keepalive/control activity,
     negotiated liveness timeout, and stream close/dead transitions govern the
     long-lived stream after open.
   - Stream-open authentication and establishment failures use the endpoint's
     `text/plain` HTTP error body. Protocol-level rejections after a successful
     open are delivered as binary frames in the `200 application/octet-stream`
     response.
   - Individual event frames are not separately HMAC-signed by this ADR.
     Payload identity, topic validation, session ownership, and peer
     authentication remain enforced by the runtime and topic contracts.

8. **Backpressure and liveness are part of the stream contract.**
   - The retained artifact source plus cursor state is the source of truth; a
     per-session stream buffer or wakeup queue is not allowed to become the only
     copy of an event.
   - A slow peer must not block source appends or event delivery to other
     sessions.
   - Wakeup notifications may be coalesced or dropped when a session is already
     awake, because the next drain reads from the retained source by cursor.
     Event frames selected for emission must not be silently dropped.
   - In-flight frame loss: selected-but-unsent frame availability is still
     bounded by the retained source horizon. If global retention prunes beyond a
     slow session before the frame is delivered, the loss must surface as
     stale-cursor, backfill, reconnect, or close behavior; the stream must not
     skip the gap silently.
   - FS2/Armeria backpressure may slow that peer's stream, but the slowdown must
     stay isolated to that session.
   - Cursor-lag loss: if a peer falls behind the retained cursor horizon before
     the next drain selects frames, the runtime must use the existing
     stale-cursor, backfill, reconnect, or close behavior instead of unbounded
     buffering.
   - A peer that remains blocked or silent beyond the negotiated liveness policy
     may be marked dead and the stream closed.
   - Armeria request timeout configuration must not close healthy long-lived
     gossip streams at the generic request timeout boundary. In mixed
     finite/streaming server assemblies, no-timeout or extended-timeout
     behavior is scoped to the stream route/service so finite endpoints retain
     their generic request-timeout guard.
   - Stream client requests disable the STTP read timeout for long-lived
     responses by using `Duration.Inf` when the stream request timeout policy is
     configured as `Duration.ZERO`.
   - Application-level keepalive frames maintain Sigilaris session liveness, not
     arbitrary load balancer or proxy liveness. Deployments that place
     middleboxes between static peers must configure transport-level idle
     handling separately or add an explicit follow-up.

9. **Expose stream observability.**
   - Diagnostics should identify stream open, stream close, reconnect, emitted
     frame count, emitted byte count, keepalive count, binary decode failures,
     source-to-emit lag, backpressure stalls, and liveness closures.
   - Existing gossip diagnostics should distinguish finite poll counters from
     streaming counters during the migration.

## Consequences
- Event propagation can be driven by source/control wakeups instead of peer
  polling cadence.
- Persistent event delivery should reduce transport round trips and remove one
  class of readiness propagation delay in BBGO.
- This accelerates producer-to-consumer event delivery only. Reverse-direction
  control feedback, known-set updates, and cursor acknowledgements remain on the
  ordinary control POST path until a separate control-channel transport change.
- Runtime and transport APIs become more tightly connected to fs2 in the
  gossip event path.
- Armeria server/client capability types become part of the endpoint assembly
  boundary, so wrappers that currently accept `ServerEndpoint[Any, F]` need to
  support `Fs2Streams[F]`. STTP4 streaming clients use the Armeria FS2 backend;
  the Armeria Cats backend remains for non-stream request/response clients.
- Long-lived streams need explicit lifecycle, timeout, and backpressure tests.
- Existing finite-poll tests will need to be migrated or kept only as
  compatibility fixtures.

## Rejected Alternatives
1. **Keep poll delivery and only swap Java/Armeria HTTP clients.**
   - This reduces request overhead but leaves event propagation paced by polling
     and loop scheduling.

2. **Implement FS2 stream by sleeping and calling `pollEvents` repeatedly.**
   - This gives a streaming surface without fixing the latency source. It is
     acceptable only as a short-lived spike to validate Tapir/Armeria mechanics.

3. **Switch directly to WebSocket or a custom Armeria service.**
   - Both are viable later, but the current codebase already centralizes
     endpoint contracts in Tapir. Using Tapir FS2 streams keeps server and
     client contract sharing intact.

4. **Keep old wire compatibility as a hard requirement.**
   - The only downstream consumer is BBGO and it is under the same operational
     control. Preserving poll compatibility would add migration complexity
     without serving an external interoperability requirement.

## Follow-Up
- Implement through [Plan 0030](../plans/0030-tapir-fs2-octet-stream-gossip-event-delivery-plan.md).
- Update BBGO to consume the streaming Sigilaris peer transport after the
  Sigilaris API lands.
- Re-measure BBGO dependent transaction pipeline latency after the stream client
  is active in the deployed node runtime.

## References
- [ADR-0016: Multiplexed Gossip Session Sync Substrate](0016-multiplexed-gossip-session-sync.md)
- [ADR-0024: Static-Topology Peer Identity Binding And Session-Bound Capability Authorization](0024-static-topology-peer-identity-binding-and-session-bound-capability-authorization.md)
- [0020 - Armeria Tapir Client Gossip Transport Plan](../plans/0020-armeria-tapir-client-gossip-transport-plan.md)
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/TxGossipTapirEndpoints.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/TxGossipArmeriaAdapter.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/TxGossipPeerClient.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip/BinaryEventStreamCodec.scala`
- `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/tx/TxGossipRuntime.scala`
