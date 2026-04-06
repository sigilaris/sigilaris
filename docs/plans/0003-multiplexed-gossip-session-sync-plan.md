# 0003 - Multiplexed Gossip Session Sync Plan

## Status
Phase 3A Complete; ADR-0024 Drafted; Identity-Binding And Binary-Wire Follow-Ups Scoped

## Created
2026-03-28

## Last Updated
2026-04-06

## Background
- 이 문서는 `ADR-0016` Follow-Up `P1`에 해당하는 구현 plan 이다.
- `ADR-0016`은 peer synchronization을 endpoint-per-type RPC가 아니라 session-based anti-entropy protocol로 고정했고, transport-neutral gossip/session substrate를 `runtime`과 `transport` seam 위에 두도록 결정했다.
- 이전 초안은 gossip substrate와 consensus artifact semantics를 한 문서에 함께 담고 있었지만, 장기 정책은 ADR-0017과 별도 consensus plan으로 분리했다.
- 따라서 이 문서는 `sigilaris-node-jvm` 안에 runtime-owned gossip/session substrate를 도입하는 작업만 다루고, HotStuff proposal/vote/QC semantics는 이 plan의 범위 밖에 둔다.
- initial deployment baseline은 static peer topology와 static neighbor list를 `application.conf` 또는 동등한 JVM config source에서 읽어 오는 방식으로 출발할 수 있다. 이 운영 baseline은 ADR-0018이 소유한다.
- plan 작성 시점에는 `sigilaris-node-jvm`이 `runtime`, `transport.armeria`, `storage` seam만 제공했고, 실제 gossip/session sync runtime과 transport adapter는 아직 없었다.
- `docs/plans/0002-sigilaris-node-jvm-extraction-plan.md`는 multi-node networking을 명시적으로 범위 밖에 두었으므로, 새 networking 구현은 별도 plan으로 단계와 gate를 관리해야 한다.
- `2026-04-01` 문서-구현 대조 리뷰에서 Phase 3 shipped baseline은 compile/test 기준으로 green 이지만, negotiated liveness wiring, pre-open reject-and-close 의 end-to-end enforcement, `tx.flushIntervalMs` timer ownership, static peer config bootstrap wiring, generic topic seam hardening에 갭이 확인되었다.
- 위 리뷰는 landed runtime model, Armeria request path, bootstrap wiring, test coverage를 이 문서의 checklist / acceptance criteria 와 대조하는 방식으로 수행했다.
- Phase 3A scope 는 위 confirmed gap closure 외에도 Armeria parity hardening 항목으로 `half-open -> open` recovery re-handshake 와 reconnect 후 filter state reset 의 end-to-end verification을 포함한다.
- 기존 Phase 0-3 checklist 는 처음 landed slice 를 기록하고, 위 implementation-review gap closure 와 추가 Armeria parity hardening 은 이 문서의 Phase 3A 에서 별도로 추적한다.
- `2026-04-01` Phase 3A gap closure implementation 이 landed 되면서 negotiated liveness/opening timeout wiring, pre-open reject-and-close, producer-local batching clock, static peer config bootstrap, topic-neutral producer seam, half-open re-handshake, reconnect filter reset, 그리고 대응 regression test 가 모두 compile/test 기준으로 green 상태가 되었다.
- `2026-04-06` 기준 static-topology peer identity binding, session-bound bootstrap capability authorization, parent-session revoke cascade semantic baseline 은 ADR-0024 로 분리됐다. 이 plan 에 남는 일은 exact transport credential binding, capability/token encoding, stronger production auth mechanism 같은 concrete runtime/transport follow-up 과 peer-facing event stream operational protocol 을 binary 로 전환하는 wire follow-up 이다.

## Goal
- `sigilaris-node-jvm` 안에 runtime-owned gossip/session sync substrate를 도입한다.
- `tx` topic 기준으로 directional session handshake, event stream, control batch, composite cursor, canonical rejection path를 end-to-end 로 구현한다.
- correctness-sensitive topic이 이후 별도 ADR 아래에서 붙을 수 있도록 generic topic contract seam, `requestById` control path, topic-aware QoS hook을 먼저 고정한다.
- `transport.armeria`가 protocol/state machine owner가 되지 않도록 seam을 유지한 채 HTTP-friendly baseline adapter를 제공한다.

## Scope
- `org.sigilaris.node.jvm.runtime.gossip` 계열 package에 session model, state machine, runtime service contract를 추가한다.
- directional session id / peer correlation id / handshake metadata / heartbeat / liveness / composite cursor / control batch / rejection class를 구현 단위로 고정한다.
- `tx` topic용 artifact source/sink, known-set/control path, replay/resume baseline을 구현한다.
- generic `GossipTopicContract` 또는 동등한 extension seam을 추가해 추후 `consensus.*` topic이 substrate에 붙을 수 있게 한다.
- `requestById`를 포함한 control op model과 transport mapping을 추가한다.
- initial JVM baseline용 static peer registry와 direct-neighbor config loader를 추가한다.
- `org.sigilaris.node.jvm.transport.armeria.gossip` 계열 package에 session-open, event stream, control endpoint adapter를 추가한다.
- shipped HTTP baseline parity hardening 범위로 `half-open -> open` recovery 와 reconnect 후 filter state reset 의 Armeria end-to-end verification을 포함한다.
- runtime과 transport seam을 검증하는 import rule / integration test / loopback test를 추가한다.

## Non-Goals
- HotStuff proposal/vote identity, sign-bytes, QC model, validator-set window, pacemaker semantics는 이번 plan에 넣지 않는다. 이는 ADR-0017과 plan 0004가 소유한다.
- peer discovery, peer scoring, topology management, validator admission policy는 이번 plan에 넣지 않는다.
- WebSocket, HTTP/2 stream, QUIC 같은 full-duplex transport는 이번 plan에서 구현하지 않는다.
- rate limiting, admission throttling, malicious peer flood 방어 정책은 deployment/adapter policy 로 두고 이번 plan의 구현 범위에 넣지 않는다.
- snapshot/backfill capability format, parent-session capability revocation, production-grade peer authentication binding의 semantic baseline 은 ADR-0024 가 소유한다. 이 plan 에서는 exact transport credential / token mechanism 을 최종 고정하지 않는다.
- binary wire follow-up 이 activated 되더라도 session-open / control family 전체를 즉시 binary-only 로 재작성하는 것을 완료 조건으로 두지 않는다. binary-only 전환 대상은 peer-facing event stream 이고, NDJSON text projection 이 남더라도 debug/tooling/manual inspection surface 에 한정한다.
- open session registry 자체를 프로세스 재시작 후 복구하는 기능은 이번 plan의 완료 조건으로 두지 않는다.

## Related ADRs And Docs
- ADR-0009: Blockchain Application Architecture
- ADR-0012: Signed Transaction Requirement
- ADR-0015: JVM Node Bundle Boundary And Packaging
- ADR-0016: Multiplexed Gossip Session Sync Substrate
- ADR-0017: HotStuff Consensus Without Threshold Signatures
- ADR-0018: Static Peer Topology And Initial HotStuff Deployment Baseline
- ADR-0021: Snapshot Sync And Background Backfill From Best Finalized Suggestions
- ADR-0024: Static-Topology Peer Identity Binding And Session-Bound Capability Authorization
- `docs/plans/0002-sigilaris-node-jvm-extraction-plan.md`
- `docs/plans/0004-hotstuff-consensus-without-threshold-signatures-plan.md`
- `docs/plans/0007-snapshot-sync-and-background-backfill-plan.md`
- `docs/plans/plan-template.md`
- `README.md`

## Decisions To Lock Before Implementation
- gossip/session public package root는 `org.sigilaris.node.jvm.runtime.gossip`, `org.sigilaris.node.jvm.runtime.gossip.tx`, `org.sigilaris.node.jvm.transport.armeria.gossip`으로 고정한다.
- `PeerSyncSession`, `GossipEvent`, `CompositeCursor`, `ControlBatch`, `ControlOp`, `CanonicalRejection`은 runtime-owned canonical model로 정의하고 transport는 이를 wire format으로만 투영한다.
- runtime은 topic-specific semantic을 직접 소유하지 않고, generic topic contract seam을 통해 topic owner 구현을 연결한다.
- simultaneous-open detection key는 concrete auth binding과 분리된 runtime-owned `PeerIdentity` placeholder 또는 동등한 authenticated peer identity abstraction으로 모델링한다.
- initial deployment에서는 static peer registry와 direct neighbor list를 `application.conf` 또는 동등한 config source에서 읽어 온다. dynamic discovery는 baseline shipped scope에 넣지 않는다.
- 위 static peer config는 최소한 local node identity, known peer identities, direct neighbor set을 제공해야 하며 `PeerIdentity` placeholder는 이 config 또는 그 위의 auth abstraction과 일관되게 매핑되어야 한다. configured peer identity 와 authenticated counterparty/session-bound capability semantic ownership 은 ADR-0024 기준으로 읽는다.
- heartbeat/keepalive 는 transport-local ping 이 아니라 runtime-owned typed protocol message 로 모델링한다. producer-side keepalive 는 event-stream message family에, consumer-side keepalive 는 control-channel message family에 속한다.
- `transport.armeria.gossip`는 Armeria request/response, current baseline NDJSON event framing, follow-up binary operational framing, optional debug/tooling text projection 같은 transport projection, connection lifecycle만 담당하고 `storage.swaydb` 같은 concrete storage package를 직접 import 하지 않는다.
- seam validation baseline은 `runtime.gossip.tx -> runtime.gossip`, `transport.armeria.gossip -> runtime.gossip`만 허용하고, `runtime.gossip -X-> transport.armeria.gossip`, `transport.armeria.gossip -X-> storage.*` 직접 의존은 금지한다.
- baseline transport는 HTTP-friendly adapter로 시작한다. directional session 하나는 session-open handshake resource, event stream resource, control resource로 매핑한다. concrete path shape는 Phase 0에서 고정하되 protocol model은 transport-neutral 로 유지한다.
- session-open handshake model은 최소한 peer correlation id, chain/topic subscription, `heartbeatInterval`, `livenessTimeout`, `maxControlRetryInterval`을 포함한다. chain/topic subscription baseline은 session lifetime 동안 immutable 하며 mid-session 변경은 reject 한다.
- directional session은 session-open proposal 과 handshake-ack 가 왕복된 뒤에만 `open` 상태로 간주한다.
- session-open proposal/ack 는 `ControlBatch`/`ControlOp` family 가 아니라 별도의 handshake message family 다.
- `opening` 상태에서 도착한 event/control message 는 buffer 하지 않고 explicit rejection 또는 close 로 처리한다. pre-open traffic 이 session state 를 advance 시켜서는 안 된다.
- `openingHandshakeTimeout` local policy 기본값은 `30s` 다. handshake 가 그 안에 끝나지 않으면 `opening -> dead` 로 전이한다.
- initiator가 `heartbeatInterval` 또는 `livenessTimeout`을 생략하면 baseline proposal 은 각각 `10s`, `30s`로 간주한다.
- `heartbeatInterval` negotiation은 `1s` 이상 `60s` 이하의 값만 허용하고, acceptor는 동일 값 또는 더 짧은 값만 응답할 수 있다. `livenessTimeout`은 `3 * heartbeatInterval` 이상이어야 하며 acceptor는 동일 값 또는 더 긴 값만 응답할 수 있다. initiator는 acceptor가 더 긴 `heartbeatInterval` 또는 더 짧은 `livenessTimeout`을 반환하면 handshake 실패로 처리한다.
- 위 heartbeat/liveness 값은 transport/session liveness 기본값이며, ADR-0018의 `100ms` block production target과 직접 동일시하지 않는다.
- `maxControlRetryInterval` negotiation은 `1s` 이상 `5m` 이하에서만 허용한다. initiator가 값을 생략하면 `30s` 제안으로 간주하고, acceptor는 explicit numeric echo 또는 더 짧은 값만 반환할 수 있다. acceptor의 더 긴 값, 범위 밖 값, invalid value, required echo omission은 initiator가 handshake 실패로 처리한다.
- 위 handshake negotiation validation failure 는 canonical `handshakeRejected` rejection class 로 투영한다.
- `CursorToken`은 runtime-issued opaque token으로 유지하고 explicit version prefix를 내부에 포함한다. text transport에서는 base64url without padding 문자열로만 canonical encoding 한다.
- runtime model은 binary 또는 opaque value를 유지하고, stable `id`의 lowercase hex 및 `CursorToken`의 base64url without padding 같은 text transport canonical encoding은 `transport.armeria.gossip` adapter가 적용한다.
- `Phase 3B` landed 이후 peer-facing event stream operational protocol 은 binary-only 로 고정한다. NDJSON text projection 이 남더라도 debug/tooling/manual inspection surface 로만 둔다.
- binary event stream follow-up 을 구현할 때 frame 은 explicit versioned envelope 와 bounded length-prefixed framing 을 사용한다. length field 는 existing `BigNat` codec 또는 동등 canonical length codec 을 재사용한다.
- binary event stream cutover 는 mixed-version NDJSON/binary negotiation 또는 runtime fallback 을 요구하지 않는다. same-cluster peer compatibility 는 startup/config gate 또는 explicit handshake reject 로 강제한다.
- binary event stream follow-up 은 text/base64 projection overhead 제거와 peer operational path 단순화를 위한 transport concern 으로만 다룬다. stable id, cursor, rejection semantics, replay/resume contract 의 semantic owner 는 계속 runtime model 이다.
- binary event stream follow-up 이 field-by-field byte layout, normative frame example, negotiation header/path grammar 같은 low-level detail 때문에 plan 본문을 과도하게 키우면 companion protocol spec 을 분리하되, scope / rollout gate / compatibility policy 는 이 plan 이 계속 소유한다.
- 초기 correctness baseline은 `tx` topic end-to-end 구현이다.
- baseline supported tx filter family는 Bloom filter 로 고정한다. 다른 filter family는 unsupported kind rejection 으로 처리하고 후속 단계에서 확장한다.
- baseline Bloom filter payload 는 최소한 `bitset`, `numHashes`, `hashFamilyId` 를 포함한다. parameter negotiation 은 handshake 가 아니라 `setFilter` payload 가 소유한다. 같은 topic/session 에서 새 `setFilter` 는 이전 filter state 를 replace 하고 merge 하지 않는다. reconnect 시 filter state 는 carry-over 하지 않고 새 control state 로 다시 설정한다.
- Bloom `setFilter` path는 optional experimental tier가 아니라 shipped tx baseline contract의 일부다.
- baseline supported `config` key set은 `tx.maxBatchItems` 와 `tx.flushIntervalMs` 로 시작한다. 이외 key 는 unsupported key rejection 대상이다.
- 위 `config` key 는 consumer-side semantic 을 바꾸지 않고, 해당 directional session의 producer-side outbound tx batching 파라미터만 조정한다.
- tx topic 의 known-set baseline 은 Bloom `setFilter` fast-path 와 bounded exact tx-id `setKnown` fallback 을 함께 둔다.
- bounded exact tx-id `setKnown` 의 local policy 기본 상한은 `maxTxSetKnownEntries = 4096` 으로 둔다.
- `requestById` 는 session subscription 범위 안에서 bounded explicit fetch를 요청하는 generic control op로 둔다. 초기 shipped apply contract는 `tx` topic에 한정한다.
- `requestById.tx` 의 local policy 기본 상한은 `maxTxRequestIds = 1024` 로 둔다. 상한 초과 또는 unsupported topic은 canonical `controlBatchRejected` 대상이다.
- `GossipEvent` 공통 필드는 `chainId`, `topic`, stable `id`, `cursor`, `ts`를 모두 포함한다. `ts`는 UTC epoch milliseconds 진단 메타데이터이며 ordering key가 아니다.
- correctness-sensitive topic을 위한 exact known-set, range-set, IBLT, GCS, window key model은 이 plan에서 구현하지 않고 generic seam만 남긴다.
- strict cascade ordering contract는 `delta structure -> exact known set -> snapshot/backfill` 순서를 유지해야 한다. 초기 구현은 tx topic에 대해서만 exact known set 과 terminal `backfillUnavailable` path를 사용한다.
- snapshot/backfill이 아직 구현되지 않은 phase에서는 runtime이 silent degrade 하지 않고 explicit `backfillUnavailable` rejection 을 반환해야 한다.
- control batch는 top-level envelope `ControlBatch(idempotencyKey, ops)` 로 모델링한다. ordered `ops` list를 atomic all-or-nothing 으로 apply 하고, `idempotencyKey`는 top-level envelope field 이며 lowercase canonical UUIDv4 string 형식으로 고정한다. directional session scope 안의 dedupe horizon은 runtime state가 소유한다.
- empty `ops` list 를 가진 `ControlBatch` 는 no-op success 로 처리한다.
- baseline session state mutation contract는 runtime-owned gossip state store 가 소유한다. 최소 state 는 session registry, peer correlation registry, cursor state, known-set/filter state, explicit request state, idempotency key horizon, QoS/config state 이다.
- `ControlOp`별 baseline state mutation 범위는 다음으로 고정한다: `setFilter` 는 filter/known-hint state, `setKnown.tx` 는 tx exact-known-set state, `setCursor` 는 cursor state, `nack` 는 replay request state or replay trigger marker, `requestById.tx` 는 bounded explicit fetch request state, `config` 는 session-scoped producer batching/QoS state 를 갱신한다. batch reject 시 위 상태 중 어느 것도 partially commit 되지 않아야 한다.
- `setCursor` 는 consumer 가 producer 에게 자신의 durable consumed checkpoint 를 알리는 ack-like state mutation 이고, `nack` 는 durable cursor 를 전진시키지 않는 transient replay request marker 이다.
- `nack` 의 baseline semantic 은 live session 안에서 same handshake context 를 유지한 채 cursor-based replay 를 다시 요청하는 것이다. `requestById` 는 stable id 기준 hole-filling 요청이고 `nack` 를 대체하지 않는다.
- control retry horizon은 negotiated `maxControlRetryInterval`의 최소 2배 이상 최대 10배 이하로 고정한다.
- configured peer identity binding, session-bound child capability ownership, parent-session revoke cascade semantic baseline 은 ADR-0024 가 소유한다. 이 plan 은 `authorizeOpenSession` 또는 동등 runtime check 와 concrete transport enforcement 를 구현 단위로 다룬다.
- directional session lifecycle 구현 범위는 `opening -> open`, `opening -> closed|dead`, `open -> closed|dead` 전이를 포함한다.
- peer relationship lifecycle 구현 범위는 shared peer correlation id 아래에서 `open -> half-open`, `half-open -> open`, `half-open -> closed|dead` 전이를 포함한다.
- `half-open -> open` recovery baseline 은 dead direction 이 기존 peer correlation id 아래에서 session-open proposal/ack 를 다시 완료하는 full re-handshake 경로로 고정한다.
- recovering direction 의 re-handshake 는 chain/topic subscription, `heartbeatInterval`, `livenessTimeout`, `maxControlRetryInterval` 을 다시 협상한다. surviving direction 의 기존 negotiated 값은 자동 상속하지 않는다.
- simultaneous-open detection 과 tie-break 는 transport 가 아니라 runtime-owned session admission contract 가 소유한다.
- `artifactContractRejected` 는 generic topic contract seam이 반환하는 semantic validation failure를 gossip substrate 경계에서 운반하는 canonical rejection class 로 둔다.

## Change Areas

### Code
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/gossip`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/gossip/tx`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip`
- 필요 시 JVM baseline config schema / loader (`application.conf` or equivalent) 확장
- 필요 시 `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime`의 bootstrap wiring 확장
- 필요 시 `modules/node-jvm/src/test/scala` 아래 gossip loopback / import rule / Armeria integration test

### Tests
- session state machine unit test
- handshake negotiation / simultaneous-open tie-break test
- composite cursor / stale cursor / partial cursor replay test
- control batch atomicity / idempotency / retry horizon test
- tx sync loopback integration test
- `requestById.tx` bounded fetch test
- static peer config parse / peer registry / neighbor admission test
- Armeria stream/control end-to-end test
- Armeria half-open recovery / reconnect filter reset parity test
- runtime / transport import rule test 확장

### Docs
- `docs/plans/0003-multiplexed-gossip-session-sync-plan.md`
- `docs/adr/0016-multiplexed-gossip-session-sync.md`
- `docs/adr/0017-hotstuff-consensus-without-threshold-signatures.md`
- `docs/adr/0021-snapshot-sync-and-background-backfill-from-best-finalized-suggestions.md`
- `docs/adr/0024-static-topology-peer-identity-binding-and-session-bound-capability-authorization.md`
- 필요 시 `README.md`의 networking 관련 설명 보강

## Implementation Phases

### Phase 0: Policy And Contract Lock
- ADR-0016 항목을 runtime contract, transport adapter, follow-up dependency로 분리한 구현 inventory를 확정한다.
- gossip/session public surface와 internal ownership 경계를 고정한다.
- HTTP baseline mapping에서 session-open, event stream, control resource의 역할과 payload family를 고정한다.
- static peer config schema, local node identity field, known peer list, direct neighbor list shape를 고정한다.
- baseline supported tx filter family, `config` key set, `requestById.tx` policy 상한을 고정한다.
- `tx.toHash` direct availability 또는 `TxIdentity` abstraction 필요 여부를 잠근다.
- `tx` end-to-end 완료 gate와 follow-up consensus integration seam을 별도 기준으로 정의한다.

### Phase 1: Runtime Protocol Model And Session Engine
- directional session id, peer correlation id, chain/topic subscription, handshake proposal/ack, heartbeat/liveness negotiation, `maxControlRetryInterval` negotiation model을 추가한다.
- directional session lifecycle(`opening -> open -> closed|dead`)과 peer relationship lifecycle(`opening -> open -> half-open -> closed|dead`) 및 simultaneous-open loser close/retry plus loser lineage discard rule을 pure runtime state machine 으로 구현한다.
- `GossipEvent`, `CompositeCursor`, `CursorToken`, `ControlBatch`, `ControlOp`, `CanonicalRejection`을 runtime-owned value model로 추가한다.
- `ControlOp` ADT 는 `setFilter`, `setKnown.tx`, `setCursor`, `nack`, `requestById.tx`, `config` 전 타입을 포함한다.
- event-stream keepalive message 와 control-channel keepalive/ack message 를 Phase 1 runtime protocol model 에 포함한다.
- simultaneous-open detection에 사용할 `PeerIdentity` placeholder 또는 동등 abstraction을 Phase 1 model에 포함한다.
- static peer registry와 local node identity bootstrap model을 Phase 1 contract에 포함한다.
- generic topic contract seam을 추가하고, `tx` topic이 그 seam을 통해 연결되는 baseline 구조를 고정한다.
- `ControlBatch` 는 model-only envelope 와 structural/schema validation contract 까지만 소유하고, semantic validation 과 atomic all-or-nothing apply interpreter 는 Phase 2에서 도입한다.
- `handshakeRejected`, `staleCursor`, `controlBatchRejected`, `artifactContractRejected`, `backfillUnavailable` canonical rejection class 전체를 Phase 1 runtime model 에서 distinct type 으로 정의한다.

### Phase 2: Tx Sync Baseline And Atomic Apply
- producer/consumer 양쪽에서 사용할 runtime-owned service contract를 정의한다. 최소 범위는 artifact source, artifact apply sink, topic contract registry, peer authenticator, clock 또는 scheduler abstraction이다.
- static peer config를 runtime-owned peer registry와 transport admission wiring에 연결한다.
- `tx.toHash` 가 `runtime.gossip.tx` 에서 `sigilaris-core` public API 경유로 직접 접근 가능한지 확인하고, 불가능하면 동일 의미의 `TxIdentity` abstraction 을 도입한다.
- runtime-owned gossip state store contract 를 정의한다. initial implementation 은 in-memory 이고, atomic apply interpreter 는 이 state store 위에서 동작한다.
- strict cascade 를 소유하는 minimal cascade strategy abstraction 을 도입한다. 초기 구현은 tx topic에 대해 exact known set 과 `backfillUnavailable` terminal path만 사용한다.
- `tx` topic의 stable id 는 `ADR-0012`의 `tx.toHash`를 그대로 사용하고, event envelope와 dedup path를 그 기준에 맞춰 구현한다.
- `tx` baseline control path는 `setFilter`, `setKnown`, `setCursor`, `nack`, `requestById`, `config`를 모두 모델에 포함한다.
- Bloom `setFilter` apply path는 tx shipped baseline의 일부로 구현한다.
- `requestById.tx` 는 bounded explicit fetch로 처리한다. 없는 id, oversize id set, unauthorized request, subscription 밖 topic은 rejection contract로 투영한다.
- `config` op는 baseline runtime이 이해하는 session tuning 또는 QoS key에 한해 apply 가능하게 하고, unsupported key는 batch rejection 대상으로 처리한다.
- Phase 2는 최소 tx producer batching model 을 포함한다. outbound tx emission 은 `tx.maxBatchItems` upper bound 와 `tx.flushIntervalMs` timer 중 먼저 만족하는 조건으로 flush 한다.
- `setCursor`는 mid-session cursor update control op 로 처리한다. `nack`는 `(chainId, topic)` 단위의 cursor-based replay request 로 정의한다.
- replay/resume 은 `CompositeCursor` 기준으로 동작하게 하고, partial cursor omission 을 origin replay 로 해석하는 규칙과 inclusive cursor checkpoint 뒤 next-event resume semantics를 테스트로 고정한다.
- loopback integration test로 handshake, stream, ack, resume, stale cursor rejection, idempotency retry, same stable id with newer cursor replay dedupe, bounded exact tx-id `setKnown`, `requestById.tx`, bidirectional correlation id reuse, half-open 유지 규칙을 검증한다.

### Phase 3: Armeria HTTP Adapter And Verification
- `transport.armeria.gossip` 아래에 session-open handshake endpoint, event stream endpoint, control endpoint adapter를 추가한다.
- event stream은 SSE 또는 NDJSON 중 하나를 baseline으로 고정하되, serialization envelope는 transport-neutral runtime model을 그대로 반영한다.
- negotiated heartbeat interval과 liveness timeout을 transport timer 및 disconnect callback에 연결한다.
- canonical rejection class를 HTTP status/body 또는 stream terminal event로 손실 없이 투영한다.
- transport import rule test를 gossip adapter까지 확장하고 `transport.armeria.gossip -> storage.*` 직접 의존이 없는지 검사한다.
- Armeria integration test로 실제 HTTP stream/control path, `requestById.tx`, wrong-channel keepalive rejection, disconnect 기반 dead 판정을 검증한다.
- static peer registry 기준 neighbor admission과 non-neighbor rejection을 검증한다.
- README 및 관련 문서에 baseline transport와 follow-up consensus dependency를 반영한다.

### Phase 3A: Post-Implementation Gap Closure And Armeria Parity Hardening
- negotiated `heartbeatInterval` / `livenessTimeout` 을 runtime-owned liveness driver 와 transport lifecycle 에 실제로 연결한다. `openingHandshakeTimeout` 과 open-session idle timeout 이 수동 `/disconnect` 없이도 `dead` 또는 동등한 lifecycle 전이로 반영되어야 한다.
- `opening` 상태의 event/control traffic 을 runtime/transport request path 에서 `rejectPreOpenTraffic` 또는 동등 state transition 으로 연결한다. explicit rejection 이후 session lineage 는 close/dead 처리되고 단순 `sessionNotOpen` 응답으로 끝나지 않아야 한다.
- `tx.flushIntervalMs` 는 `GossipEvent.ts` 가 아니라 producer-local enqueue/availability clock 또는 동등한 runtime-owned batching clock 을 기준으로 해석한다. `ts` 는 계속 진단 메타데이터로만 유지한다.
- static peer topology 를 `application.conf` 또는 동등한 config source 에서 읽어 `PeerRegistry`, runtime bootstrap, transport admission wiring 으로 연결하는 concrete JVM baseline loader 를 추가한다.
- generic topic contract seam 이 surface-only abstraction 에 머물지 않도록 topic-neutral producer session state / polling / QoS hook 을 추출한다. 후속 topic owner 가 `TxGossipRuntime` 재작성 없이 substrate 를 재사용할 수 있어야 한다.
- `half-open -> open` recovery 가 existing peer correlation id 아래 full re-handshake 로 runtime/Armeria handshake path 에서 end-to-end 로 동작하도록 harden 한다. dead direction recovery 중 surviving direction 의 stream/control path 는 유지되어야 한다.
- reconnect 로 새 directional session 이 열릴 때 이전 session 의 `setFilter` state 가 Armeria path 에서 carry-over 되지 않고, 새 session control state 에서 다시 설정되도록 harden 한다.
- README / ADR / plan 텍스트를 shipped tx baseline, 남아 있는 substrate-hardening work, consensus follow-up dependency 기준으로 다시 정렬한다.

### Phase 3B: P2 Binary Event-Stream Wire Follow-Up
- current NDJSON event stream baseline 을 peer-facing operational protocol 에서 대체하고, throughput/overhead 개선을 위해 binary event-stream transport projection 을 canonical path 로 승격한다.
- binary path 는 runtime-owned `GossipEvent` / cursor / rejection model 을 바꾸지 않고, event stream serialization 과 framing 만 교체한다. session-open handshake 와 control batch family 는 기존 HTTP/JSON baseline 을 유지한다.
- peer event stream compatibility matrix 는 binary media type 하나로 고정한다. mixed-version NDJSON/binary negotiation 또는 runtime fallback 은 두지 않고, pre-cutover NDJSON peer path 는 debug/tooling surface 또는 decommission 대상이다.
- binary event envelope 는 explicit version field 또는 동등한 forward-compatibility marker 를 포함해야 하며, stable id / cursor / timestamps / typed payload projection 을 lossless 하게 운반해야 한다.
- stream framing 은 bounded length-prefixed frame 으로 고정하고, length codec 은 `BigNat` 또는 동등 canonical codec 을 사용한다. truncated frame, oversize frame, unknown version, malformed envelope 는 explicit reject/close path 로 투영한다.
- Armeria adapter 는 binary path 가 existing shipped semantic baseline 과 같은 session/replay/resume semantics 를 유지하도록 shared projection fixture 또는 golden parity corpus 를 사용한다.
- byte layout table, field ordering rule, normative frame example, media type parameter, negotiation header/path grammar 가 plan 본문에 비해 과도하게 상세해지면 companion protocol spec 을 추가하되, 이 Phase 3B 가 rollout gate 와 compatibility policy 를 계속 소유한다.

## Test Plan
- Phase 1 Success: pure unit test로 UUID format validation, lexicographic tie-break, simultaneous-open loser close/retry, heartbeat/liveness negotiation default/range, `livenessTimeout >= 3 * heartbeatInterval`, `maxControlRetryInterval` default/echo/shorten 규칙, immutable subscription baseline을 검증한다.
- Phase 1 Success: pure unit test로 simultaneous-open detection key가 `PeerIdentity` placeholder 또는 동등 authenticated peer identity abstraction 기준으로 모델링되는지를 검증한다.
- Phase 1 Success: pure unit test로 static peer config가 local node identity, known peer set, direct neighbor set으로 파싱되는지를 검증한다.
- Phase 1 Success: pure unit test로 `CompositeCursor` partial resume, fully empty `CompositeCursor` 가 full origin replay 로 해석되는 규칙, cursor token version prefix generation/validation을 검증한다.
- Phase 1 Failure: pure unit test로 `opening` 상태의 pre-open event/control traffic 이 explicit rejection 후 close 되고 session state 를 advance 시키지 않는지를 검증한다.
- Phase 1 Failure: pure unit test로 malformed `idempotencyKey`, malformed discriminator 같은 `ControlBatch` structural/schema validation rejection을 검증한다.
- Phase 1 Success: pure unit test로 event-stream keepalive 와 control-channel keepalive/ack 가 typed protocol message 로 모델링되고, transport-local ping 과 혼동되지 않는지를 검증한다.
- Phase 2 Success: control batch test로 ordered `ops` list apply, lowercase UUIDv4 `idempotencyKey`, retry horizon이 `maxControlRetryInterval`의 2배 이상 10배 이하인지, horizon 경계와 session 종료 시점에서 old key eviction 이 올바르게 동작하는지를 검증한다.
- Phase 2 Success: atomic apply test로 `setKnown`, `setCursor`, `nack`, `requestById`, `config` 가 정의된 session state store slice 만 갱신하고, reject 시 아무 slice 도 partially commit 되지 않는지를 검증한다.
- Phase 2 Success: loopback integration test로 `tx` handshake-ack round-trip, event delivery, duplicate delivery dedupe, replay 중 same stable id with newer cursor dedupe, bounded exact tx-id `setKnown` apply, `setCursor` mid-session update, `requestById.tx`, `tx.maxBatchItems` 및 `tx.flushIntervalMs` `config` apply path, `ts` populate/propagation 을 검증한다.
- Phase 2 Success: filter-state test로 같은 topic/session 의 두 번째 `setFilter` 가 이전 filter state 를 replace 하고 merge 하지 않으며, reconnect 후에는 filter state 가 carry-over 되지 않고 비어 있는지 검증한다.
- Phase 2 Failure: control batch test로 batch reject 시 no side effect, unsupported `config` key rejection, unsupported topic request rejection, oversize `requestById.tx` rejection을 검증한다.
- Phase 2 Failure: tx cascade unit test로 bounded exact tx-id `setKnown` fallback 으로도 delta 를 해결하지 못하고 backfill 이 없을 때 explicit `backfillUnavailable` rejection emission 이 반환되는지를 검증한다.
- Phase 3 Success: Armeria integration test로 session-open, stream keepalive, control endpoint, `requestById.tx`, transport disconnect 기반 dead 판정을 검증한다.
- Phase 3 Failure: integration test로 static config의 direct neighbor set 밖 peer에 대한 outbound/open 또는 inbound admission이 baseline policy대로 reject 되는지를 검증한다.
- Phase 3 Failure: import rule test로 `runtime.gossip` 가 Armeria/SwayDB 구현 타입과 text transport encoding helper를 import 하지 않고, `transport.armeria.gossip` 가 `storage.*` 구현 타입을 import 하지 않는지 확인한다.
- Phase 3A Success (검증 대상: `3A-1`, 완료 체크: `3A-8`): runtime/transport integration test로 negotiated heartbeat/liveness 값이 실제 opening timeout 및 open-session idle timeout 판정에 연결되고, 수동 `/disconnect` 없이도 session 이 `dead` 로 전이되는지를 검증한다.
- Phase 3A Failure (검증 대상: `3A-2`, 완료 체크: `3A-9`): integration test로 `opening` 상태 session 에 event/control traffic 을 보내면 `preOpenEventTraffic` 또는 `preOpenControlTraffic` rejection 이 반환되고, 이후 session lineage 가 close/dead 처리되는지를 검증한다.
- Phase 3A Success (검증 대상: `3A-3`, 완료 체크: `3A-10`): batching test로 과거 또는 미래 `ts` 값을 가진 artifact 도 `tx.flushIntervalMs` 의미를 왜곡하지 않고, producer-local batching clock 만이 flush 시점을 결정하는지를 검증한다.
- Phase 3A Success (검증 대상: `3A-4`, 완료 체크: `3A-11`): bootstrap test로 `application.conf` 또는 동등한 config source 가 local node identity, known peers, direct neighbors 를 파싱하고 runtime/transport admission wiring 에 실제 연결되는지를 검증한다.
- Phase 3A Success (검증 대상: `3A-5`, 완료 체크: `3A-12`): seam test로 `tx` 외 topic stub 이 topic-neutral producer session state / polling / QoS hook 을 재사용할 수 있고, 추가 topic support 를 위해 `tx` runtime 내부를 다시 소유할 필요가 없는지를 검증한다.
- Phase 3A Success (검증 대상: `3A-6`, 완료 체크: `3A-13`): Armeria integration test로 dead direction 이 기존 peer correlation id 아래 fresh session id 로 full re-handshake 를 완료해 `half-open -> open` recovery 를 달성하고, surviving direction 의 stream/control path 가 recovery 중 유지되는지를 검증한다.
- Phase 3A Success (검증 대상: `3A-7`, 완료 체크: `3A-14`): Armeria integration test로 reconnect 뒤 새 session 의 filter state 가 비어 있고 이전 session 의 `setFilter` state 가 carry-over 되지 않으며, 새 control batch 로만 다시 설정되는지를 검증한다.
- Phase 3B Success (검증 대상: `3B-1`, 완료 체크: `3B-4`): codec unit test로 binary envelope encode/decode 가 stable id, cursor, payload discriminator, timestamp projection 을 lossless 하게 round-trip 하고, length prefix 경계가 deterministic 하게 직렬화되는지를 검증한다.
- Phase 3B Success (검증 대상: `3B-2`, 완료 체크: `3B-5`): Armeria integration test로 peer-facing event stream 이 binary-only operational protocol 로 노출되고, pre-cutover NDJSON peer endpoint 는 제거되거나 debug/tooling surface 로만 남는지를 검증한다.
- Phase 3B Success (검증 대상: `3B-3`, 완료 체크: `3B-6`): parity regression test로 same session/replay/resume/duplicate-dedupe 시나리오가 binary projection 에서 pre-Phase-3B semantic baseline 과 동일한 runtime outcome 을 만드는지를 검증한다.
- Phase 3B Failure (검증 대상: `3B-4`, 완료 체크: `3B-7`): stream decoder test와 Armeria failure test로 truncated frame, oversize frame, unknown envelope version, malformed payload 가 silent drop 되지 않고 explicit reject/close 로 처리되는지를 검증한다.
- Phase 3B Success (검증 대상: `3B-5`, 완료 체크: `3B-8`): docs review 로 binary-only operational policy, optional debug/tooling NDJSON surface, optional companion spec reference 가 README / ADR / plan 텍스트에 일관되게 반영되는지를 검증한다.

## Risks And Mitigations
- gossip substrate와 topic semantics가 다시 섞이면 follow-up consensus plan과 경계가 무너질 수 있다. generic topic contract seam과 문서 링크로 ownership 을 분리한다.
- transport adapter 가 runtime state machine owner 처럼 비대해질 수 있다. runtime-owned model 과 import rule test 로 ownership 을 강제한다.
- cursor token 을 ordering key 로 오해하면 replay/dedup 버그가 생길 수 있다. opaque type 과 dedicated test 로 비교 금지 규칙을 고정한다.
- control batch partial apply 버그는 peer state divergence 로 이어질 수 있다. pure atomic apply interpreter 와 failure injection test 로 막는다.
- `requestById`가 unbounded fetch로 새어 나가면 DoS surface가 커질 수 있다. topic별 명시적 상한과 rejection test로 bounded contract를 강제한다.
- peer authentication 이 선택 사항처럼 새어 나가면 session hijacking 위험이 커진다. runtime contract 에서는 mandatory 로 두고, 미구현 구간은 테스트 전용 fixture 로만 허용한다.
- optional debug/tooling NDJSON projection 이 binary operational protocol 과 드리프트할 수 있다. shared decode fixture 와 semantic-baseline regression 으로 debug surface 를 protocol-owner path 와 느슨하게 정렬하되, peer compatibility matrix 에서는 제외한다.
- length-prefixed binary framing 은 corrupted input 에서 메모리 증폭 위험이 있다. early size cap, truncated-frame reject, decoder failure test 로 bounded parsing contract 를 강제한다.
- pre-cutover peer 가 binary-only cluster 에 실수로 합류하면 silent decode failure 나 stalled stream 으로 이어질 수 있다. startup/config compatibility gate 와 explicit handshake reject policy 로 mixed-version join 을 막는다.

## Acceptance Criteria
1. `sigilaris-node-jvm` 에 runtime-owned gossip/session package 가 추가되고, directional session lifecycle, composite cursor, control batch, rejection model 이 compile/test 로 고정된다.
2. `tx` topic 기준으로 session-open, event stream, control batch, replay/resume, stale cursor rejection, bounded `requestById.tx` 가 end-to-end 로 동작한다.
3. `transport.armeria.gossip` 는 HTTP-friendly baseline adapter 를 제공하되 `storage.*` 구현 타입에 직접 의존하지 않는다.
4. generic topic contract seam이 존재해 후속 consensus plan이 gossip substrate를 재사용할 수 있다.
5. snapshot/backfill, production peer authentication binding, HotStuff consensus semantics 의 후속 의존성이 문서에 명확히 기록되고 silent fallback 이 남지 않는다.
6. negotiated heartbeat/liveness 와 `openingHandshakeTimeout` 이 shipped HTTP baseline 에서 end-to-end 로 동작하고, manual disconnect path 는 보조 운영 hook 으로만 남는다.
7. `opening` 상태의 pre-open event/control traffic 이 shipped HTTP baseline 에서 explicit reject-and-close 로 강제되고, 단순 `sessionNotOpen` 응답으로 끝나지 않는다.
8. `tx.flushIntervalMs` 가 `GossipEvent.ts` 에 의존하지 않고 producer-local batching clock 기준으로만 해석된다.
9. static peer topology 가 config source 에서 bootstrap 되어 `PeerRegistry`, runtime bootstrap, transport admission wiring 에 실제 연결된다.
10. generic topic seam 이 surface-only abstraction 이 아니라 topic-neutral producer session state / polling / QoS hook 을 제공하는 reusable substrate seam 으로 강화된다.
11. `half-open -> open` recovery 가 shipped HTTP baseline 에서 existing peer correlation id 아래 full re-handshake 로 동작하고, surviving direction 의 stream/control path 는 recovery 중 유지된다.
12. reconnect 뒤 새 directional session 의 filter state 가 shipped HTTP baseline 에서 empty control state 로 시작하고, 이전 session 의 `setFilter` state 를 carry-over 하지 않는다.
13. Phase 3B 후 peer-facing event stream operational protocol 은 binary-only 로 고정되고, runtime-owned `GossipEvent` / cursor / rejection public model 과 session-open/control semantic baseline 은 바뀌지 않는다. NDJSON 가 남더라도 debug/tooling surface 에 한정된다.
14. binary event stream 은 versioned envelope 와 bounded length-prefixed framing 을 사용하고, malformed/truncated/oversize/unknown-version input 을 explicit reject/close 로 처리한다.
15. same session/replay/resume/dedupe outcome 이 binary projection 에서 pre-Phase-3B semantic baseline 대비 parity regression test 로 고정된다.
16. binary wire detail 이 plan 본문을 과도하게 키우면 companion protocol spec 이 연결되지만, rollout gate 와 compatibility policy 는 이 plan 에 남는다.

## Checklist

### Phase 0: Policy And Contract Lock
- [x] runtime / transport / follow-up ownership inventory 확정
- [x] gossip package root 와 public surface 확정
- [x] HTTP baseline resource family 와 phase gate 정의
- [x] static peer config schema 확정
- [x] baseline Bloom filter, baseline `config` key set, `requestById.tx` policy 확정
- [x] `tx.toHash` direct availability 또는 `TxIdentity` abstraction 필요 여부 확정
- [x] generic topic contract seam ownership 문서화

### Phase 1: Runtime Protocol Model And Session Engine
- [x] session id / correlation id / handshake / lifecycle model 추가
- [x] handshake-ack round-trip 과 `open` 진입 규칙 추가
- [x] `opening` 상태 pre-open traffic reject-and-close/dead guard 구현
- [x] `GossipEvent`, `CompositeCursor`, `ControlBatch`, `ControlOp`, `CanonicalRejection` 추가
- [x] `requestById.tx` 를 포함한 full `ControlOp` ADT 추가
- [x] event-stream keepalive 와 control-channel keepalive/ack typed message 추가
- [x] `PeerIdentity` placeholder 또는 동등 authenticated peer identity abstraction 추가
- [x] static peer registry / local node identity bootstrap model 추가
- [x] generic topic contract seam 추가
- [x] runtime gossip import rule test 추가

### Phase 2: Tx Sync Baseline And Atomic Apply
- [x] artifact source/sink/topic-contract/authenticator contract 추가
- [x] `tx.toHash` direct availability 확인 또는 `TxIdentity` abstraction 추가
- [x] runtime-owned gossip state store contract 추가
- [x] atomic apply interpreter isolated unit gate 추가
- [x] static peer config to runtime registry wiring 추가
- [x] Bloom `setFilter` apply path 구현
- [x] `tx` exact known-set, cursor, replay, `requestById.tx` baseline 구현
- [x] `tx.maxBatchItems` / `tx.flushIntervalMs` config apply path 구현
- [x] tx loopback integration test green

### Phase 3: Armeria HTTP Adapter And Verification
- [x] session-open handshake endpoint, event stream endpoint, control endpoint adapter 추가
- [x] SSE 또는 NDJSON baseline 결정 및 serialization projection 구현
- [x] canonical rejection projection 구현
- [x] Armeria integration test green
- [x] docs / README 갱신

### Phase 3A: Post-Implementation Gap Closure And Armeria Parity Hardening
- [x] `3A-1` negotiated heartbeat/liveness timer 와 opening timeout end-to-end wiring 추가
- [x] `3A-2` pre-open event/control reject-and-close 를 runtime / Armeria request path 에 연결
- [x] `3A-3` `tx.flushIntervalMs` batching clock 를 `GossipEvent.ts` 와 분리
- [x] `3A-4` static peer config loader / runtime bootstrap / transport admission wiring 추가
- [x] `3A-5` topic-neutral producer session state / polling / QoS hook hardening
- [x] `3A-6` `half-open -> open` recovery 의 Armeria parity hardening 추가
- [x] `3A-7` reconnect filter reset 의 Armeria parity hardening 추가
- [x] `3A-8` liveness / opening-timeout integration regression test green
- [x] `3A-9` pre-open reject-and-close integration regression test green
- [x] `3A-10` batching clock regression test green
- [x] `3A-11` bootstrap regression test green
- [x] `3A-12` seam regression test green
- [x] `3A-13` half-open recovery Armeria regression test green
- [x] `3A-14` reconnect filter reset Armeria regression test green
- [x] `3A-15` README / ADR / plan wording refresh

### Phase 3B: P2 Binary Event-Stream Wire Follow-Up
- [x] `3B-1` binary media type / binary-only cutover / non-negotiated compatibility policy lock
- [x] `3B-2` versioned binary event envelope 와 `BigNat`-based length-prefixed frame codec 추가
- [x] `3B-3` Armeria event-stream binary projection 및 binary-only operational cutover wiring 추가
- [x] `3B-4` frame size cap, truncated-frame reject, malformed-version reject guard 추가
- [x] `3B-5` binary operational-protocol cutover integration regression test green
- [x] `3B-6` semantic-baseline preservation regression test green
- [x] `3B-7` decoder failure / malformed frame rejection test green
- [x] `3B-8` README / ADR / plan wording refresh 및 필요 시 companion protocol spec 연결

## Follow-Ups
- `P1`: configured `PeerIdentity` 와 concrete transport credential subject 간 매핑은 이 plan 의 gossip/session admission seam 에서 follow-up 한다. mutual TLS, application credential, equivalent auth principal 중 어떤 형태를 쓰든 ADR-0024 가 정의한 canonical peer principal 을 보존해야 한다.
- `P2`: current NDJSON event stream baseline 을 peer-facing operational path 에서 대체하고, event stream 은 byte-codec 기반 binary envelope 를 canonical protocol 로 승격한다. 우선 후보는 `application/octet-stream` 또는 동등 binary media type 위의 length-prefixed frame 이며, `BigNat` length codec 을 framing에 재사용한다. NDJSON text projection 이 남더라도 debug/tooling surface 에 한정한다. 이 작업은 exact wire/spec follow-up 이지 별도 ADR 대상은 아니며, 구현 gate 는 이 plan 의 `Phase 3B` 가 소유한다.
- `P3`: session-bound child capability 를 transport surface 에 투영해야 하면 capability token/header/path/query shape 와 re-auth handshake payload 는 이 plan 또는 별도 protocol spec 에서 고정한다.
- `P4`: bootstrap service family 가 consumer 로 쓰는 concrete authorization / disconnect diagnostics 는 plan `0007` 과 함께 정렬하되, runtime-owned authorization seam ownership 은 이 plan 이 유지한다.
- `P5`: HotStuff proposal/vote/QC 및 pacemaker artifact integration 은 plan `0004` 가 소유한다. correctness-sensitive topic의 exact known-set, range-set, IBLT/GCS, window key model은 각 topic owner plan이 고정한다.
