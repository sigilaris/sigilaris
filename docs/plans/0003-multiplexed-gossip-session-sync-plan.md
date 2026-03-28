# 0003 - Multiplexed Gossip Session Sync Plan

## Status
Draft

## Created
2026-03-28

## Last Updated
2026-03-28

## Background
- 이 문서는 `ADR-0016` Follow-Up `P1`에 해당하는 구현 plan 이다.
- `ADR-0016`은 peer synchronization을 endpoint-per-type RPC가 아니라 session-based anti-entropy protocol로 고정했고, `tx`, `consensus.proposal`, `consensus.vote`를 하나의 directional session 안에서 multiplex 하도록 결정했다.
- 현재 `sigilaris-node-jvm`은 `runtime`, `transport.armeria`, `storage` seam만 제공하며, 실제 gossip/session sync runtime과 transport adapter는 아직 없다.
- `docs/plans/0002-sigilaris-node-jvm-extraction-plan.md`는 multi-node networking을 명시적으로 범위 밖에 두었으므로, 새 networking 구현은 별도 plan으로 단계와 gate를 관리해야 한다.
- 동시에 `ADR-0016`은 consensus identity/sign-bytes contract, validator-set commitment derivation, snapshot/backfill capability, peer authentication binding 같은 일부 항목을 후속 ADR 또는 protocol spec으로 남겨 두었다.
- 따라서 이번 plan은 `ADR-0016`의 canonical session/runtime contract를 먼저 코드 구조로 내리고, 미확정 의존성에 막히는 부분은 experimental gate 또는 explicit rejection으로 분리하는 실행 전략이 필요하다.

## Goal
- `sigilaris-node-jvm` 안에 runtime-owned gossip/session sync substrate를 도입한다.
- `tx` topic 기준으로 directional session handshake, event stream, control batch, composite cursor, canonical rejection path를 end-to-end 로 구현한다.
- consensus proposal/vote sync에 필요한 envelope, window, QoS, rejection model을 runtime contract 수준에서 먼저 고정하되, production interoperability가 필요한 부분은 experimental gate 뒤로 둔다.
- `transport.armeria`가 protocol/state machine owner가 되지 않도록 seam을 유지한 채 HTTP-friendly baseline adapter를 제공한다.

## Scope
- `org.sigilaris.node.jvm.runtime.gossip` 계열 package에 session model, state machine, runtime service contract를 추가한다.
- directional session id / peer correlation id / handshake metadata / heartbeat / liveness / composite cursor / control batch / rejection class를 구현 단위로 고정한다.
- `tx` topic용 artifact source/sink, known-set/control path, replay/resume baseline을 구현한다.
- `org.sigilaris.node.jvm.transport.armeria.gossip` 계열 package에 session-open, event stream, control endpoint adapter를 추가한다.
- runtime과 transport seam을 검증하는 import rule / integration test / loopback test를 추가한다.
- consensus proposal/vote용 envelope validator, window key, QoS 분리 지점을 experimental mode 기준으로 문서화하고 필요한 최소 구현을 포함한다.

## Non-Goals
- peer discovery, peer scoring, topology management, validator admission policy는 이번 plan에 넣지 않는다.
- WebSocket, HTTP/2 stream, QUIC 같은 full-duplex transport는 이번 plan에서 구현하지 않는다.
- rate limiting, admission throttling, malicious peer flood 방어 정책은 deployment/adapter policy 로 두고 이번 plan의 구현 범위에 넣지 않는다.
- consensus proposal/vote의 interoperable sign-bytes contract와 `validatorSetHash` derivation contract를 이번 plan에서 최종 고정하지 않는다. 이는 `ADR-0016`의 `P2` 후속 문서가 소유한다.
- snapshot/backfill capability format, parent-session capability revocation, production-grade peer authentication binding 방식은 이번 plan에서 최종 고정하지 않는다. 이는 `ADR-0016`의 `P4` 후속 문서가 소유한다.
- open session registry 자체를 프로세스 재시작 후 복구하는 기능은 이번 plan의 완료 조건으로 두지 않는다.

## Related ADRs And Docs
- ADR-0009: Blockchain Application Architecture
- ADR-0012: Signed Transaction Requirement
- ADR-0015: JVM Node Bundle Boundary And Packaging
- ADR-0016: Multiplexed Gossip Session Sync
- `docs/plans/0002-sigilaris-node-jvm-extraction-plan.md`
- `docs/plans/plan-template.md`
- `README.md`

## Decisions To Lock Before Implementation
- `ADR-0016`이 아직 `Proposed` 상태이므로, 이 절은 장기 정책을 재결정하기 위한 것이 아니라 구현 중 임의 해석이 생기지 않게 ADR의 필수 입력 계약을 명시적으로 고정하는 용도로 사용한다.
- gossip/session public package root는 `org.sigilaris.node.jvm.runtime.gossip`과 `org.sigilaris.node.jvm.transport.armeria.gossip`으로 고정한다.
- `PeerSyncSession`, `GossipEvent`, `CompositeCursor`, `ControlBatch`, `ControlOp`, `CanonicalRejection`은 runtime-owned canonical model로 정의하고 transport는 이를 wire format으로만 투영한다.
- heartbeat/keepalive 는 transport-local ping 이 아니라 runtime-owned typed protocol message 로 모델링한다. producer-side keepalive 는 event-stream message family에, consumer-side keepalive 는 control-channel message family에 속한다.
- `transport.armeria.gossip`는 Armeria request/response, SSE 또는 NDJSON framing, connection lifecycle만 담당하고 `storage.swaydb` 같은 concrete storage package를 직접 import 하지 않는다.
- baseline transport는 HTTP-friendly adapter로 시작한다. directional session 하나는 session-open handshake resource, event stream resource, control resource로 매핑한다. concrete path shape는 Phase 0에서 고정하되 protocol model은 transport-neutral 로 유지한다.
- session-open handshake model은 최소한 peer correlation id, chain/topic subscription, `heartbeatInterval`, `livenessTimeout`, `maxControlRetryInterval`을 포함한다. chain/topic subscription baseline은 session lifetime 동안 immutable 하며 mid-session 변경은 reject 한다. consensus experimental mode가 켜진 경우 vote target convention/profile metadata도 같은 handshake family 안에서 합의한다.
- experimental consensus baseline vote target convention 은 ADR-0016의 interoperable baseline 인 `targetKind=proposal`, `targetId=proposal stable id` 를 기본값으로 둔다.
- simultaneous-open detection 에 쓰는 peer pair identity 는 concrete auth binding 과 분리된 runtime-owned `PeerIdentity` placeholder type 으로 모델링한다.
- Phase 1 session-open acceptance 는 concrete production auth binding 대신 test-fixture backed `PeerIdentity` contract 를 통해서만 통과할 수 있어야 한다.
- directional session은 session-open proposal 과 handshake-ack 가 왕복된 뒤에만 `open` 상태로 간주한다.
- session-open proposal/ack 는 `ControlBatch`/`ControlOp` family 가 아니라 별도의 handshake message family 다.
- `opening` 상태에서 도착한 event/control message 는 buffer 하지 않고 explicit rejection 또는 close 로 처리한다. pre-open traffic 이 session state 를 advance 시켜서는 안 된다.
- baseline pre-open handling 은 explicit rejection 후 해당 opening session close 로 고정한다.
- `openingHandshakeTimeout` local policy 기본값은 `30s` 다. handshake 가 그 안에 끝나지 않으면 `opening -> dead` 로 전이한다.
- local runtime 은 `opening` 상태에서 heartbeat/keepalive 를 emit 해서는 안 된다. pre-open keepalive ignore rule 은 received noise 처리에만 적용한다.
- initiator가 `heartbeatInterval` 또는 `livenessTimeout`을 생략하면 baseline proposal 은 각각 `10s`, `30s`로 간주한다.
- `heartbeatInterval` negotiation은 `1s` 이상 `60s` 이하의 값만 허용하고, acceptor는 동일 값 또는 더 짧은 값만 응답할 수 있다. `livenessTimeout`은 `3 * heartbeatInterval` 이상이어야 하며 acceptor는 동일 값 또는 더 긴 값만 응답할 수 있다. initiator는 acceptor가 더 긴 `heartbeatInterval` 또는 더 짧은 `livenessTimeout`을 반환하면 handshake 실패로 처리한다.
- `maxControlRetryInterval` negotiation은 `1s` 이상 `5m` 이하에서만 허용한다. initiator가 값을 생략하면 `30s` 제안으로 간주하고, acceptor는 explicit numeric echo 또는 더 짧은 값만 반환할 수 있다. acceptor의 더 긴 값, 범위 밖 값, invalid value, required echo omission은 initiator가 handshake 실패로 처리한다.
- 위 handshake negotiation validation failure 는 canonical `handshakeRejected` rejection class 로 투영한다.
- `CursorToken`은 runtime-issued opaque token으로 유지하고 explicit version prefix를 내부에 포함한다. text transport에서는 base64url without padding 문자열로만 canonical encoding 한다.
- `CursorToken`의 initial version prefix 는 pre-encoding payload 내부의 ASCII `v1:` marker 다. text wire 에서는 base64url decode 이후에만 관찰 가능하고, runtime 은 이를 wire-visible plain string prefix 로 취급하지 않는다.
- runtime model은 binary 또는 opaque value를 유지하고, stable `id`/`targetId`/`validatorSetHash`의 lowercase hex 및 `CursorToken`의 base64url without padding 같은 text transport canonical encoding은 `transport.armeria.gossip` adapter가 적용한다.
- 초기 correctness baseline은 `tx` topic end-to-end 구현이다. proposal/vote는 runtime model과 validation path를 먼저 도입하되, production interoperability는 experimental gate 없이는 켜지지 않게 한다.
- baseline supported tx filter family는 Bloom filter 로 고정한다. 다른 filter family는 unsupported kind rejection 으로 처리하고 후속 단계에서 확장한다.
- baseline Bloom filter payload 는 최소한 `bitset`, `numHashes`, `hashFamilyId` 를 포함한다. parameter negotiation 은 handshake 가 아니라 `setFilter` payload 가 소유한다. 같은 topic/session 에서 새 `setFilter` 는 이전 filter state 를 replace 하고 merge 하지 않는다. reconnect 시 filter state 는 carry-over 하지 않고 새 control state 로 다시 설정한다.
- baseline supported `config` key set은 `tx.maxBatchItems` 와 `tx.flushIntervalMs` 로 시작한다. 이외 key 는 unsupported key rejection 대상이다.
- 위 `config` key 는 consumer-side semantic 을 바꾸지 않고, 해당 directional session의 producer-side outbound tx batching 파라미터만 조정한다.
- tx topic 의 known-set baseline 은 Bloom `setFilter` fast-path 와 bounded exact tx-id `setKnown` fallback 을 함께 둔다. tx initial implementation 에서는 Bloom stage 를 cascade abstraction 의 optional first tier 로 취급하고, Bloom stage 가 불충분하거나 policy 상 실패하면 exact `setKnown` stage 로 내려가며, 그것도 실패하면 `backfillUnavailable` terminal 로 진행한다.
- Bloom hint tier 는 exact cascade stage 를 임의로 생략할 authority 가 아니다. Bloom result 가 unsupported, stale, absent, 또는 policy 상 신뢰 부족이면 exact `setKnown` stage 로 즉시 내려간다.
- Bloom-to-exact 전이는 runtime-owned cascade orchestrator 가 소유한다. Bloom hint evaluation 과 exact-known-set fallback 호출은 같은 orchestrator 안에서 직렬로 수행되어 strict ordering 을 보장한다.
- bounded exact tx-id `setKnown` 의 local policy 기본 상한은 `maxTxSetKnownEntries = 4096` 으로 둔다. 상한 초과 시 exact stage 는 oversize failure 로 간주하고 `backfillUnavailable` terminal 로 내려간다.
- `GossipEvent` 공통 필드는 `chainId`, `topic`, stable `id`, `cursor`, `ts`를 모두 포함한다. `ts`는 UTC epoch milliseconds 진단 메타데이터이며 ordering key가 아니다.
- consensus known-set baseline은 exact known set window path부터 구현하고, IBLT/GCS/range-set 같은 최적화는 correctness path가 고정된 뒤 후속 단계로 둔다.
- strict cascade ordering contract는 `delta structure -> exact known set -> snapshot/backfill` 순서를 유지해야 한다. 초기 구현은 cascade strategy abstraction 에 exact known set 과 terminal `backfillUnavailable` path만 채워 넣고, 이후 delta structure 를 추가하더라도 이 순서를 건너뛸 수 없게 한다.
- consensus exact known set이 configured threshold를 넘어가고 snapshot/backfill이 아직 없으면, 초기 구현은 silent partial apply 대신 explicit `backfillUnavailable` rejection 으로 종료한다.
- snapshot/backfill이 아직 구현되지 않은 phase에서는 runtime이 silent degrade 하지 않고 explicit `backfillUnavailable` rejection 을 반환해야 한다. 이 rejection path는 backfill endpoint/capability 자체가 없다는 뜻이며, parent-session capability binding 검증은 `P4` 후속 문서가 도입한다.
- 위 `backfillUnavailable` terminal rule 은 consensus exact known-set oversize 뿐 아니라 tx topic 에서 bounded exact tx-id `setKnown` fallback 으로도 delta 를 해결하지 못하는 경우에 동일하게 적용한다.
- `backfillUnavailable` 이후 session/topic scope consequence 는 ADR에 없는 gap 이다. initial implementation baseline 은 explicit rejection 을 반환하는 것까지만 고정하고, 그 뒤 session-level consequence 는 follow-up ADR or protocol spec sign-off 없이는 shipped contract 로 고정하지 않는다.
- peer authentication은 runtime API boundary에서 mandatory contract로 둔다. 다만 첫 concrete production binding 방식은 별도 후속 ADR이 고정하며, 이번 plan의 integration test는 test-only authenticator 또는 static fixture principal을 사용할 수 있다.
- control batch는 top-level envelope `ControlBatch(idempotencyKey, ops)` 로 모델링한다. ordered `ops` list를 atomic all-or-nothing 으로 apply 하고, `idempotencyKey`는 top-level envelope field 이며 lowercase canonical UUIDv4 string 형식으로 고정한다. directional session scope 안의 dedupe horizon은 runtime state가 소유한다.
- idempotency dedupe baseline 은 ADR 그대로 `(directionalSessionId, idempotencyKey)` 기준으로 수행한다. payload-aware dedupe hardening 은 initial implementation 범위에 넣지 않는다.
- empty `ops` list 를 가진 `ControlBatch` 는 no-op success 로 처리한다. atomic semantics 를 깨지 않으면서 ADR silence 와 호환되는 보수적 기본값으로 둔다.
- empty `ops` no-op success 도 normal apply outcome 이므로 해당 `idempotencyKey` slot 을 소비한다. 같은 session에서 같은 key 로 재전송되면 prior no-op success outcome 을 재사용한다.
- baseline session state mutation contract는 runtime-owned gossip state store 가 소유한다. 최소 state 는 session registry, peer correlation registry, cursor state, known-set/filter state, idempotency key horizon, QoS/config state 이다. initial implementation 은 in-memory state store 로 시작하고, 기존 `storage` seam 확장 여부는 follow-up 으로 미룬다.
- `ControlOp`별 baseline state mutation 범위는 다음으로 고정한다: `setFilter` 는 filter/known-hint state, `setKnown.tx` 는 tx exact-known-set state, `setKnown.consensusWindow` 는 consensus window known-set state, `setCursor` 는 cursor state, `nack` 는 replay request state or replay trigger marker, `config` 는 session-scoped producer batching/QoS state 를 갱신한다. batch reject 시 위 상태 중 어느 것도 partially commit 되지 않아야 한다.
- `setCursor` 는 consumer 가 producer 에게 자신의 durable consumed checkpoint 를 알리는 ack-like state mutation 이고, `nack` 는 durable cursor 를 전진시키지 않는 transient replay request marker 이다.
- `nack` 의 baseline semantic 은 live session 안에서 same handshake context 를 유지한 채 cursor-based replay 를 다시 요청하는 것이고, reconnect 와 달리 새 session-open 협상을 시작하지 않는다. initial implementation 은 이 semantic 을 locked baseline 으로 사용하고, follow-up ADR or protocol spec 이 다른 의미를 고정하면 그 시점에 plan 을 갱신한다.
- control retry horizon은 negotiated `maxControlRetryInterval`의 최소 2배 이상 최대 10배 이하로 고정한다.
- baseline lifecycle 구현 범위는 `opening -> open`, `opening -> closed|dead`, `open -> half-open`, `open -> closed|dead`, `half-open -> open`, `half-open -> closed|dead` 전이를 포함한다. detailed buffering rule과 revoke-driven edge case는 후속 spec으로 넘기되, 첫 directional session open 시 correlation id 생성, 한 방향 종료가 다른 방향을 즉시 무효화하지 않는 peer correlation 유지 규칙, 두 방향이 모두 `closed|dead` 가 된 뒤에만 correlation id를 폐기하는 규칙은 이번 plan에서 검증한다.
- `half-open -> open` recovery baseline 은 dead direction 이 기존 peer correlation id 아래에서 session-open proposal/ack 를 다시 완료하는 full re-handshake 경로로 고정한다.
- recovering direction 의 re-handshake 는 chain/topic subscription, `heartbeatInterval`, `livenessTimeout`, `maxControlRetryInterval` 을 다시 협상한다. surviving direction 의 기존 negotiated 값은 자동 상속하지 않는다.
- recovered direction 은 이 re-handshake 에서 새 directional session id 를 발급하고 기존 peer correlation id 를 재사용한다.
- 위 half-open recovery 방식은 follow-up protocol spec 이 상세 transition table 을 고정하기 전까지의 plan-local assumption 이며, 후속 spec 이 다른 recovery contract 를 고정하면 이 plan 도 그에 맞춰 수정한다. Phase 0 sign-off 에서 ADR author ruling 이 없으면 half-open recovery implementation 은 shipped scope 에 넣지 않는다.
- half-open recovery re-handshake 중 양쪽이 동시에 새 session-open proposal 을 보내는 simultaneous re-open edge case 는 initial implementation 에서 `handshakeRejected` 로 양쪽 recovery attempt 를 거절하고 half-open 상태를 유지한 뒤 one-side retry 로 수렴시키는 plan-local assumption 으로 둔다. follow-up protocol spec 이 다른 규칙을 고정하면 이 plan 도 그에 맞춰 수정한다.
- 위 rule 이 ADR-0016 §1의 simultaneous-open tie-break 와 충돌하지 않는 이유는, recovery case 에서는 양쪽이 이미 같은 peer correlation id 를 공유하므로 ADR tie-break 가 전제하는 "두 competing correlation id 비교"가 성립하지 않기 때문이다. 따라서 이 plan 은 shared-correlation recovery case 에 대해 별도 local rule 을 둔다.
- simultaneous-open detection baseline 은 같은 authenticated peer pair 에 대해 local opening session 이 존재하는 동안 상대의 session-open proposal 이 들어온 경우로 고정하고, 그 경우 correlation id lexicographic tie-break 를 수행한다.
- simultaneous-open detection 과 tie-break 는 transport 가 아니라 runtime-owned session admission contract 가 소유한다. transport adapter 는 handshake metadata 와 peer auth context 를 runtime admission service 로 전달하고 그 판정을 집행한다.
- runtime admission contract 는 simultaneous-open detection 시 incoming correlation id 와 local opening correlation id 를 비교해 분기한다. 둘이 다르면 fresh simultaneous-open 이고 ADR tie-break 를 적용하며, 같으면 half-open recovery simultaneous re-open 으로 보고 plan-local `handshakeRejected` rule 을 적용한다.
- simultaneous-open loser retry 가 reject 되거나 transport-level 로 실패하면 loser side 는 explicit rejection 또는 `dead` outcome 으로 종료하고, silent hang 상태에 머물지 않는다.
- fresh simultaneous-open loser retry 는 winner correlation id 를 verbatim peer correlation id 로 채택하고 새 correlation id 를 만들지 않는다.
- fresh simultaneous-open 에서 winner side 가 이미 자신의 reverse-direction session-open 을 시작한 경우에도 loser retry 는 winner correlation id 아래의 새로운 directional session-open 으로 coexist 할 수 있어야 하고, runtime 은 이를 충돌이 아니라 정상 reverse-direction open 으로 처리한다.
- `CursorToken`의 initial version prefix 는 opaque serialized token 내부의 ASCII `v1:` marker 로 시작한다. transport 는 전체 token 을 opaque base64url 문자열로만 다룬다.
- live session registry의 durability는 이번 plan의 1차 목표가 아니다. 재시작 호환성은 open session state 복구가 아니라 producer-side cursor token acceptance와 artifact replay contract로 검증한다.
- Phase 0 산출물은 이 문서의 `Package Ownership Appendix`와 `Phase Gate Appendix`에 기록한다.

## Change Areas

### Code
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/gossip`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/gossip/tx`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/gossip/consensus`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/transport/armeria/gossip`
- 필요 시 `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime`의 bootstrap wiring 확장
- 필요 시 `modules/node-jvm/src/test/scala` 아래 gossip loopback / import rule / Armeria integration test

### Tests
- session state machine unit test
- handshake negotiation / simultaneous-open tie-break test
- composite cursor / stale cursor / partial cursor replay test
- control batch atomicity / idempotency / retry horizon test
- tx sync loopback integration test
- Armeria stream/control end-to-end test
- runtime / transport import rule test 확장
- consensus experimental validation / QoS 분리 test

### Docs
- `docs/plans/0003-multiplexed-gossip-session-sync-plan.md`
- 필요 시 `README.md`의 networking 관련 설명 보강
- `ADR-0016` follow-up 상태 링크
- 필요 시 module-level adoption note 또는 transport baseline note

## Implementation Phases

### Phase 0: Contract Slice And Boundary Lock
- `ADR-0016` 항목을 runtime contract, transport adapter, 후속 ADR 의존 항목으로 분리한 구현 inventory를 확정한다.
- Phase 0 inventory는 `Implementation Inventory Appendix`에 기록한다.
- gossip/session package root, public surface, internal ownership 경계를 `Package Ownership Appendix`에 고정한다.
- gossip/session seam 의 allowed/forbidden dependency 방향은 `Seam Validation Appendix`에 고정한다.
- HTTP baseline mapping에서 session-open, event stream, control resource의 역할과 payload family를 고정한다.
- baseline supported tx filter family와 baseline supported `config` key set을 이 phase 에서 고정한다.
- `tx.toHash` direct availability 또는 `TxIdentity` abstraction 필요 여부를 이 phase 에서 잠그고 Phase 1 model 입력으로 사용한다.
- minimal tx producer batching model(`tx.maxBatchItems`, `tx.flushIntervalMs` 가 조정하는 대상)을 이 phase 에서 고정한다.
- experimental consensus baseline vote target convention 도 이 phase 에서 고정한다.
- baseline Bloom `setFilter` payload parameter contract 와 replace semantics, half-open recovery simultaneous re-open ADR gap 처리, half-open recovery re-negotiation policy 를 이 phase 에서 고정한다.
- `tx` end-to-end 완료 gate와 consensus experimental gate를 `Phase Gate Appendix`에 별도 기준으로 정의한다.
- snapshot/backfill, sign-bytes, validator-set commitment, peer authentication binding 중 이번 plan에서 미루는 항목을 explicit blocker list로 남긴다.

### Phase 1: Runtime Protocol Model And Session Engine
- directional session id, peer correlation id, chain/topic subscription, handshake proposal/ack, heartbeat/liveness negotiation, `maxControlRetryInterval` negotiation model을 추가한다.
- consensus experimental mode를 위해 vote target convention/profile handshake field를 reserve 하고 mismatch rejection path를 runtime model에 포함한다.
- `opening -> open -> half-open -> closed|dead` baseline lifecycle과 simultaneous-open loser close/retry plus loser lineage discard rule을 pure runtime state machine 으로 구현한다.
- `GossipEvent`, `CompositeCursor`, `CursorToken`, `ControlBatch`, `ControlOp`, `CanonicalRejection`을 runtime-owned value model로 추가한다. `GossipEvent` 공통 필드는 `chainId`, `topic`, `id`, `cursor`, `ts`를 포함하고 `ts`는 ordering key가 아님을 모델과 문서에 반영한다. `ControlOp` ADT 는 `setFilter`, `setKnown.tx`, `setKnown.consensusWindow`, `setCursor`, `nack`, `config` 전 타입을 포함한다.
- consensus experimental mode 용 `GossipEvent` variant 에는 `height`, `round`, `validatorSetScheme`, `validatorSetHash`, `targetKind`, `targetId` field slot 을 예약한다.
- event-stream keepalive message 와 control-channel keepalive/ack message 를 Phase 1 runtime protocol model 에 포함한다.
- simultaneous-open detection 에 사용할 `PeerIdentity` placeholder type 을 Phase 1 model 에 포함한다.
- Phase 1의 `ControlBatch` 는 model-only envelope 와 structural/schema validation contract 까지만 소유하고, semantic validation 과 atomic all-or-nothing apply interpreter 는 Phase 2에서 도입한다.
- 따라서 Phase 1에서는 `ControlBatch` 를 accept/apply 하지 않고, 필요한 경우 parse 또는 structural validation 이후 explicit rejection 으로만 처리한다.
- structural/schema validation 의 범위는 envelope shape, top-level `idempotencyKey` format, `ops` list presence/order, discriminator decode, required generic field presence 로 한정한다. unsupported filter kind, unsupported `config` key, topic/window mismatch 같은 domain-level 판정은 Phase 2 semantic validation 이 소유한다.
- Phase 1에서 structurally valid 하지만 semantic apply 가 아직 없는 `ControlBatch` 는 transitional behavior 로 canonical `controlBatchRejected` rejection class 를 사용한다.
- 단, empty `ops` list `ControlBatch` 는 이 transitional reject-all rule 의 예외로서 no-op success 로 처리한다.
- Phase 2/3 runtime 에서는 consensus-only semantic op(`setKnown.consensusWindow` 등)가 들어오면 atomic batch validation 단계에서 canonical `controlBatchRejected` 로 거절한다.
- Phase 1의 `CompositeCursor`/token 테스트는 value-level resume contract 검증으로 한정하고, control-channel `setCursor` apply semantics 는 Phase 2에서 처음 활성화한다.
- `handshakeRejected`, `unknownScheme`, `staleCursor`, `conventionMismatch`, `controlBatchRejected`, `backfillUnavailable` canonical rejection class 전체를 Phase 1 runtime model 에서 distinct type 으로 정의한다.
- in-memory session registry, idempotency dedupe horizon slot, keepalive deadline tracker 같은 testable baseline runtime component를 추가한다. 이는 Phase 2 runtime-owned gossip state store contract 의 first in-memory implementation 으로 승격된다. payload-aware idempotency comparison logic 은 Phase 2 semantic apply 에서 처음 활성화한다.
- runtime import rule test를 gossip package까지 확장한다.

### Phase 2: Artifact Contracts And Tx Sync Baseline
- Phase 2는 sub-gate `2a: state store + atomic apply + cascade seam` 과 `2b: tx sync + reconnect + lifecycle integration` 순서로 진행한다. `2a` 가 green 이 되기 전에는 `2b` loopback wiring 으로 넘어가지 않는다.
- Phase 0 sign-off 에서 half-open recovery local rule 이 승인되지 않으면, recovery implementation/test/gate 항목은 shipped scope 밖으로 남기고 tx baseline 완료 기준에서는 제외한다.
- Phase 0 sign-off 에서 Bloom hint-tier interpretation 이 승인되지 않으면, Bloom hardening 및 cascade-front hint wiring 은 shipped scope 밖으로 남기고 tx baseline 은 exact `setKnown` + cursor core path까지만 완료로 본다.
- producer/consumer 양쪽에서 사용할 runtime-owned service contract를 정의한다. 최소 범위는 artifact source, artifact apply sink, known-set evaluator, peer authenticator, clock 또는 scheduler abstraction이다.
- `tx.toHash` 가 `runtime.gossip.tx` 에서 `sigilaris-core` public API 경유로 직접 접근 가능한지 확인하고, 불가능하면 동일 의미의 `TxIdentity` abstraction 을 도입하는 pre-gate 를 둔다.
- runtime-owned gossip state store contract 를 정의한다. initial implementation 은 in-memory 이고, atomic apply interpreter 는 이 state store 위에서 동작한다.
- future probabilistic delta structure 를 붙일 수 있도록 strict cascade 를 소유하는 minimal cascade strategy abstraction 을 도입한다. 초기 구현은 exact known set 과 `backfillUnavailable` terminal path만 사용하며, terminal trigger contract 를 위한 minimal `BackfillRequest` placeholder type 을 함께 둔다. Bloom `setFilter` 는 이 abstraction 앞단의 hint tier 로만 동작한다.
- 위 minimal `BackfillRequest` placeholder 는 최소 필드로 `parentDirectionalSessionId` 를 포함한다.
- `tx` topic의 stable id 는 `ADR-0012`의 `tx.toHash`를 그대로 사용하고, event envelope와 dedup path를 그 기준에 맞춰 구현한다.
- `tx` baseline control path는 `setFilter`, `setKnown`, `setCursor`, `nack`, `config`를 모두 모델에 포함한다. correctness gate의 필수 조건은 exact known-set plus cursor path이며, Phase 0 sign-off 가 Bloom hint-tier interpretation 을 승인한 경우에만 `setFilter` 가 최소 하나의 supported tx filter family를 실제 apply path로 제공하고 unsupported filter kind rejection 까지 포함한다.
- `config` op는 baseline runtime이 이해하는 session tuning 또는 QoS key에 한해 apply 가능하게 하고, unsupported key는 batch rejection 대상으로 처리한다.
- Phase 2는 최소 tx producer batching model 을 포함한다. outbound tx emission 은 `tx.maxBatchItems` upper bound 와 `tx.flushIntervalMs` timer 중 먼저 만족하는 조건으로 flush 한다.
- `setKnown`은 topic-discriminated control op 로 유지한다. baseline tx path 에서는 `setKnown.tx` exact fallback apply path 를 제공하고, consensus experimental path 에서는 `setKnown.consensusWindow` exact known-set exchange 로 확장한다.
- `setCursor`는 mid-session cursor update control op 로 처리한다. `nack`는 `(chainId, topic)` 단위의 cursor-based replay request 로 정의하고, arbitrary event id 범위 지정은 baseline contract 에 넣지 않는다.
- `ts` 는 artifact source 가 제공하는 값이 아니라 producer-side clock abstraction 이 emission 시점에 채우는 wall-clock metadata 로 고정한다.
- replay/resume 은 `CompositeCursor` 기준으로 동작하게 하고, partial cursor omission 을 origin replay 로 해석하는 규칙과 inclusive cursor checkpoint 뒤 next-event resume semantics를 테스트로 고정한다.
- session-open, control path, event apply path에 peer authentication context가 빠지지 않도록 runtime contract를 연결한다.
- transport disconnect 이후 새 session-open 에 이전 `CompositeCursor` 일부 key 만 포함한 mixed reconnect path 를 runtime contract 로 검증한다.
- loopback integration test로 handshake, stream, ack, resume, stale cursor rejection, idempotency retry, same stable id with newer cursor replay dedupe, bidirectional correlation id reuse, half-open 유지 규칙을 검증한다.
- above loopback harness 는 transport 없이도 logical event-stream/control-channel separation 을 유지하는 runtime test harness 로 정의한다.

### Phase 3: Armeria HTTP Adapter
- `transport.armeria.gossip` 아래에 session-open handshake endpoint, event stream endpoint, control endpoint adapter를 추가한다.
- Phase 0 sign-off 가 recovery local rule 을 승인한 경우에만 half-open recovery re-handshake 도 같은 session-open resource family 를 재사용하도록 HTTP mapping 을 고정한다.
- event stream은 SSE 또는 NDJSON 중 하나를 baseline으로 고정하되, serialization envelope는 transport-neutral runtime model을 그대로 반영한다.
- negotiated heartbeat interval과 liveness timeout을 transport timer 및 disconnect callback에 연결한다.
- canonical rejection class를 HTTP status/body 또는 stream terminal event로 손실 없이 투영한다.
- transport import rule test를 gossip adapter까지 확장하고 `transport.armeria.gossip -> storage.*` 직접 의존이 없는지 검사한다.
- Armeria integration test로 실제 HTTP stream/control path를 검증한다.

### Phase 4: Consensus Experimental Mode, QoS, Verification
- `consensus.proposal`, `consensus.vote` envelope validator와 `(chainId, height, round, validatorSetScheme, validatorSetHash)` window key model을 추가한다.
- experimental deployment에서는 `x-exp-id:*` 와 `x-exp-scheme:*` validation, exact match scheme check, vote target convention mismatch rejection, exact known-set window comparison을 우선 구현한다.
- consensus topic 도 Phase 2의 artifact source/sink 와 known-set evaluator abstraction 을 재사용하도록 wiring 하고, window-bound known-set query contract 를 추가한다.
- tx backlog 가 consensus topic delivery 를 막지 않도록 per-topic priority, flush policy, 또는 독립 버퍼를 runtime queue model 에 반영한다.
- snapshot/backfill 미구현 상태에서는 exact known set oversize 를 포함해 explicit `backfillUnavailable` rejection 을 유지하고, production consensus interoperability blocker 를 문서화한다.
- README 및 관련 문서에 baseline transport, experimental consensus gate, follow-up ADR dependency 를 반영한다.
- compile/test/regression 결과와 phase completion gate 충족 여부를 문서에 기록한다.

## Test Plan
- Phase 1 Success: pure unit test로 UUID format validation, lexicographic tie-break, simultaneous-open loser close/retry producing a new directional session id under the winner correlation id without creating a new correlation id, heartbeat/liveness negotiation default/range, `livenessTimeout >= 3 * heartbeatInterval`, `maxControlRetryInterval` default/echo/shorten 규칙, immutable subscription baseline을 검증한다.
- Phase 1 Success: pure negotiation test로 acceptor 가 더 짧은 `heartbeatInterval` 또는 더 긴 `livenessTimeout` counter-proposal 을 반환하는 valid conservative path 가 accept 되는지를 검증한다.
- Phase 1 Success: pure unit test로 `PeerIdentity` placeholder type 이 simultaneous-open detection key 로 사용되고 concrete auth binding 과 분리되는지를 검증한다.
- Phase 1 Success: pure unit test로 `CompositeCursor` partial resume, fully empty `CompositeCursor` 가 full origin replay 로 해석되는 규칙, cursor token version prefix generation/validation을 검증한다.
- Phase 1 Success: above cursor tests 는 value-level resume contract 검증으로 한정하고, control-channel `setCursor` apply semantics 는 Phase 2에서 처음 검증한다.
- Phase 1 Failure: pure unit test로 `opening` 상태의 pre-open event/control traffic 이 explicit rejection 후 close 되고 session state 를 advance 시키지 않는지를 검증한다.
- Phase 1 Failure: pure unit test로 `openingHandshakeTimeout` expiry 가 `opening -> dead` 전이를 일으키는지를 검증한다.
- Phase 1 Failure: pure unit test로 malformed `idempotencyKey`, malformed discriminator 같은 `ControlBatch` structural/schema validation rejection을 검증한다.
- Phase 1 Success: pure unit test로 empty `ops` list `ControlBatch` 가 no-op success 로 처리되는지를 검증한다.
- Phase 1 Failure: pure unit test로 structurally valid but semantically unavailable `ControlBatch` 가 transitional `controlBatchRejected` 로 투영되는지를 검증한다.
- Phase 1 Success: pure unit test로 event-stream keepalive 와 control-channel keepalive/ack 가 typed protocol message 로 모델링되고, transport-local ping 과 혼동되지 않는지를 검증한다.
- Phase 2 Success: control batch test로 ordered `ops` list apply, lowercase UUIDv4 `idempotencyKey`, retry horizon이 `maxControlRetryInterval`의 2배 이상 10배 이하인지, horizon 경계와 session 종료 시점에서 old key eviction 이 올바르게 동작하는지를 검증한다.
- Phase 2 Success: sub-gate `2a` test로 in-memory gossip state store, atomic apply interpreter, exact-known-set-to-`backfillUnavailable` cascade seam 이 isolated unit test에서 green 임을 검증한다.
- Phase 2 Success: atomic apply test로 `setKnown`, `setCursor`, `nack`, `config` 가 정의된 session state store slice 만 갱신하고, Phase 0 sign-off 가 Bloom hint-tier interpretation 을 승인한 경우에만 `setFilter` state slice 도 포함되며, reject 시 아무 slice 도 partially commit 되지 않는지를 검증한다.
- Phase 2 Success: loopback integration test로 `tx` handshake-ack round-trip, event delivery, duplicate delivery dedupe, replay 중 same stable id with newer cursor dedupe, bounded exact tx-id `setKnown` apply, `setCursor` mid-session update, locked baseline `nack` recovery, `tx.maxBatchItems` 및 `tx.flushIntervalMs` `config` apply path가 minimal producer batching model 에 반영되는지, `ts` populate/propagation 을 검증한다. Phase 0 sign-off 가 Bloom hint-tier interpretation 을 승인한 경우에만 Bloom `setFilter(bitset,numHashes,hashFamilyId)` apply path 도 같은 loopback suite 에 포함한다.
- Phase 2 Success: Phase 0 sign-off 가 Bloom hint-tier interpretation 을 승인한 경우에만 filter-state test로 같은 topic/session 의 두 번째 `setFilter` 가 이전 filter state 를 replace 하고 merge 하지 않으며, reconnect 후에는 filter state 가 carry-over 되지 않고 비어 있는지 검증한다.
- Phase 2 Success: reconnect integration test로 일부 `(chainId, topic)` key 는 기존 `CompositeCursor` 로 next-event resume 하고, 누락된 key 는 같은 reconnect 안에서 origin replay 가 시작되는 mixed partial-resume path를 검증한다.
- Phase 2 Success: bidirectional session integration test로 correlation id reuse, half-open peer relationship 유지, 한 방향 close 후 다른 방향 지속 가능성, 두 방향 모두 종료된 뒤에만 correlation id discard 가 일어나는 규칙을 검증한다. Phase 0 sign-off 가 recovery local rule 을 승인한 경우에만 새 directional session id + 기존 correlation id 를 쓰는 full re-handshake 기반 half-open 에서 open 으로의 recovery 도 같은 suite 에 포함한다.
- Phase 2 Failure: pure unit test로 loser lineage discard, stale cursor rejection, cursor token version prefix mismatch, `maxControlRetryInterval` reject 규칙, 더 긴 heartbeat 응답 reject, 더 짧은 liveness timeout 응답 reject 를 검증한다.
- Phase 2 Failure: control batch test로 batch reject 시 no side effect, unsupported `config` key rejection을 검증한다. Phase 0 sign-off 가 Bloom hint-tier interpretation 을 승인한 경우에만 unsupported `setFilter` kind rejection 도 같은 suite 에 포함한다.
- Phase 2 Failure: cascade strategy test로 delta stage 가 exact known set 또는 backfill 단계를 건너뛰지 못하고, configured order 밖의 fallback 을 reject 하는지를 검증한다.
- Phase 2 Failure: tx cascade unit test로 bounded exact tx-id `setKnown` fallback 으로도 delta 를 해결하지 못하고 backfill 이 없을 때 explicit `backfillUnavailable` rejection emission 이 반환되는지를 검증한다. post-rejection session/topic consequence 는 이 test 에서 assert 하지 않는다.
- Phase 2 Failure: runtime protocol test로 event-stream keepalive 가 control path 로 오거나 control-channel keepalive 가 event stream 으로 오는 wrong-channel case 를 reject 하는지를 검증한다.
- Phase 2 Failure: Phase 0 sign-off 가 recovery local rule 을 승인한 경우에만 half-open recovery simultaneous re-open test로 양쪽 recovery attempt 가 `handshakeRejected` 로 거절되고 half-open 상태를 유지하는 plan-local path를 검증한다.
- Phase 3 Success: Armeria integration test로 session-open, stream keepalive, control endpoint, producer heartbeat는 event stream 쪽으로, consumer keepalive 는 control path 쪽으로만 인정되는 규칙, transport disconnect 기반 dead 판정을 검증한다.
- Phase 3 Failure: import rule test로 `runtime.gossip` 가 Armeria/SwayDB 구현 타입과 text transport encoding helper를 import 하지 않고, `transport.armeria.gossip` 가 `storage.*` 구현 타입을 import 하지 않는지 확인한다.
- Phase 4 Success: consensus experimental integration test로 window-bound known-set query contract wiring, mandatory `targetKind`/`targetId` modeling, QoS separation 이 intra-topic producer emission order 를 깨지 않는지를 검증한다.
- Phase 4 Failure: consensus experimental test로 unknown scheme rejection, vote target convention mismatch rejection, mandatory `targetKind`/`targetId` presence 및 baseline convention validation, exact known set oversize 시 `backfillUnavailable` 를 검증한다.
- Phase N Regression: 상위 phase 에서 추가한 unit/integration/import-rule test 는 이후 phase 에서 모두 계속 green 상태를 유지해야 한다.

## Risks And Mitigations
- `ADR-0016`이 아직 `Proposed` 상태라 장기 정책 자체가 바뀔 수 있다. Phase 0 sign-off 전 ADR 상태를 다시 확인하고, rejection 또는 material change 가 발생하면 phase gate 와 scope 를 재평가한다.
- `ADR-0016`의 후속 의존성 없이 consensus production path까지 한 번에 밀어 넣으면 구현이 중간에서 멈출 수 있다. `tx` end-to-end 를 먼저 gate 로 삼고 consensus 는 experimental mode 로 분리한다.
- transport adapter 가 runtime state machine owner 처럼 비대해질 수 있다. runtime-owned model 과 import rule test 로 ownership 을 강제한다.
- cursor token 을 ordering key 로 오해하면 replay/dedup 버그가 생길 수 있다. opaque type 과 dedicated test 로 비교 금지 규칙을 고정한다.
- control batch partial apply 버그는 peer state divergence 로 이어질 수 있다. pure atomic apply interpreter 와 failure injection test 로 막는다.
- tx backlog 가 consensus delivery 를 막으면 liveness 문제가 생길 수 있다. topic-aware queue 와 flush policy 를 Phase 4 gate 에 포함한다.
- peer authentication 이 선택 사항처럼 새어 나가면 session hijacking 위험이 커진다. runtime contract 에서는 mandatory 로 두고, 미구현 구간은 테스트 전용 fixture 로만 허용한다.
- `ADR-0016`이 수정되면 plan 과 실제 구현 gate 가 조용히 어긋날 수 있다. Phase 0 sign-off 와 Completion Appendix decision log 에 ADR 변경 추적을 남기고, ADR 수정 시 이 plan 을 함께 갱신한다.

## Acceptance Criteria
1. `sigilaris-node-jvm` 에 runtime-owned gossip/session package 가 추가되고, directional session lifecycle, composite cursor, control batch, rejection model 이 compile/test 로 고정된다.
2. `tx` topic 기준으로 session-open, event stream, control batch, replay/resume, stale cursor rejection 이 end-to-end 로 동작한다.
3. `transport.armeria.gossip` 는 HTTP-friendly baseline adapter 를 제공하되 `storage.*` 구현 타입에 직접 의존하지 않는다.
4. consensus proposal/vote path 는 experimental gate 뒤에서 envelope validation, window key, QoS 분리 지점을 제공하거나, 미구현 항목을 canonical rejection 과 문서로 명시한다.
5. snapshot/backfill, production peer authentication binding, consensus sign-bytes/validator-set derivation 의 후속 의존성이 문서에 명확히 기록되고 silent fallback 이 남지 않는다.
6. 테스트와 문서가 Phase gate 기준을 충족하고, `ADR-0016` 과의 링크가 유지된다.

## Checklist

### Phase 0: Contract Slice And Boundary Lock
- [ ] runtime / transport / follow-up ADR ownership inventory 확정 및 appendix 반영
- [ ] gossip package root 와 public surface 확정
- [ ] HTTP baseline resource family 와 phase gate 정의
- [ ] blocker list 와 experimental gate 정책 문서화
- [ ] snapshot/backfill, sign-bytes, validator-set commitment, peer authentication binding blocker list 확정
- [ ] baseline Bloom filter 와 baseline `config` key set 확정
- [ ] Bloom `setFilter` payload parameter(`bitset`, `numHashes`, `hashFamilyId`) / replace semantics 확정
- [ ] `tx.toHash` direct availability 또는 `TxIdentity` abstraction 필요 여부 확정
- [ ] `maxTxSetKnownEntries` local policy 확정
- [ ] experimental consensus baseline vote target convention 확정
- [ ] minimal tx producer batching model 확정
- [ ] Bloom hint-tier vs strict-cascade interpretation ADR reconciliation 완료
- [ ] half-open recovery simultaneous re-open ADR gap sign-off
- [ ] half-open recovery re-negotiation policy 확정
- [ ] `backfillUnavailable` post-rejection scope local assumption ADR reconciliation 기록
- [ ] baseline `config` key set local semantics ADR reconciliation 기록
- [ ] `openingHandshakeTimeout` default 와 plan-local keepalive ignore policy 기록
- [ ] `ADR-0016` status 와 reference hash 기록
- [ ] appendices 와 checklist 갱신을 담은 Phase 0 sign-off commit 기준 확정

### Phase 1: Runtime Protocol Model And Session Engine
- [ ] session id / correlation id / handshake / lifecycle model 추가
- [ ] `PeerIdentity` placeholder type 추가
- [ ] test-fixture backed session-open auth contract 추가
- [ ] handshake-ack round-trip 과 `open` 진입 규칙 추가
- [ ] handshake message family 와 `ControlBatch` 분리 명시
- [ ] `opening` 상태 pre-open traffic reject-and-close/dead guard 구현
- [ ] `openingHandshakeTimeout` expiry test 추가
- [ ] `GossipEvent`, `CompositeCursor`, `ControlBatch`, `ControlOp`, `CanonicalRejection` 추가
- [ ] consensus envelope field slot(`height`, `round`, `validatorSetScheme`, `validatorSetHash`, `targetKind`, `targetId`) 예약
- [ ] full `ControlOp` ADT(`setFilter`, `setKnown.tx`, `setKnown.consensusWindow`, `setCursor`, `nack`, `config`) 추가
- [ ] event-stream keepalive 와 control-channel keepalive/ack typed message 추가
- [ ] `ts` 공통 필드와 `CursorToken` version prefix 생성/검증 규칙 추가
- [ ] `ControlBatch` structural/schema validation test 추가
- [ ] transitional `controlBatchRejected` rejection wiring 추가
- [ ] heartbeat/liveness ratio 와 `maxControlRetryInterval` 및 vote target convention handshake field 추가
- [ ] simultaneous-open loser close 후 winner correlation id 로 새 directional session id 를 열어 retry 하는 경로 구현
- [ ] winner-side reverse-direction session 과 loser retry coexistence test 추가
- [ ] pure validation + in-memory session engine 추가
- [ ] runtime gossip import rule test 추가

### Phase 2: Artifact Contracts And Tx Sync Baseline
- `2a: state store + atomic apply + cascade seam`
- [ ] artifact source/sink/authenticator contract 추가
- [ ] `tx.toHash` direct availability 확인 또는 `TxIdentity` abstraction 추가
- [ ] runtime-owned gossip state store contract 추가
- [ ] clock 또는 scheduler abstraction wiring 추가
- [ ] session-open / control / event apply 전 경로에 peer authentication context wiring 추가
- [ ] atomic apply interpreter isolated unit gate 추가
- [ ] control batch atomic no-side-effect rejection path 구현
- [ ] control batch idempotency horizon path 구현
- [ ] sub-gate `2a` green before tx loopback wiring
- `2b: tx sync + reconnect + lifecycle integration`
- [ ] `tx` envelope, stable-id dedup, replay/resume path 구현
- [ ] `ts` populate/propagation path 구현
- [ ] topic-discriminated `setKnown` contract 구현
- [ ] `setCursor` mid-session control path 구현
- [ ] locked baseline `nack` recovery path 구현
- [ ] consensus-only semantic op transitional rejection 구현
- [ ] Phase 0 sign-off 가 Bloom hint-tier interpretation 을 승인한 경우에만 supported `setFilter` apply path와 unsupported filter rejection path 구현
- [ ] supported `config` apply path와 unsupported key rejection path 구현
- [ ] minimal tx producer batching model 구현
- [ ] cascade strategy abstraction 추가
- [ ] minimal `BackfillRequest` placeholder type 추가
- [ ] tx loopback integration test green
- [ ] mixed partial-resume reconnect integration test green
- [ ] tx `backfillUnavailable` rejection emission-only unit test green
- [ ] Phase 0 sign-off 가 recovery local rule 을 승인한 경우에만 bidirectional half-open 유지 및 recovery integration test green

### Phase 3: Armeria HTTP Adapter
- [ ] session-open / stream / control Armeria adapter 추가
- [ ] runtime session admission contract 연동으로 simultaneous-open detection/tie-break 집행
- [ ] heartbeat / liveness / rejection mapping 구현
- [ ] canonical rejection class HTTP projection 검증
- [ ] Phase 0 sign-off 가 recovery local rule 을 승인한 경우에만 existing correlation id 기반 half-open recovery re-handshake path 구현
- [ ] Phase 0 sign-off 가 recovery local rule 을 승인한 경우에만 same session-open resource family 기반 recovery HTTP mapping 고정
- [ ] clock 또는 scheduler abstraction 기반 deadline firing 검증
- [ ] HTTP control retry idempotency dedupe 검증
- [ ] transport gossip import rule test 추가
- [ ] Armeria end-to-end integration test green

### Phase 4: Consensus Experimental Mode, QoS, Verification
- [ ] proposal/vote experimental validator 와 window key model 추가
- [ ] consensus artifact source/sink 와 window-bound known-set query wiring 추가
- [ ] mandatory `targetKind` / `targetId` field validation 추가
- [ ] consensus QoS separation 반영
- [ ] QoS separation 이 intra-topic emission order 를 깨지 않는지 검증
- [ ] `backfillUnavailable` 및 production interoperability blocker 와 follow-up dependency 문서화
- [ ] compile 결과 기록
- [ ] test 및 regression 결과 기록
- [ ] docs 갱신 완료
- [ ] Completion Appendix 갱신

## Follow-Ups
- `ADR-0016 P2`: proposal/vote sign-bytes contract, payload identity, `validatorSetHash` derivation contract 고정
- `ADR-0016 P3`: full-duplex transport 로 옮겨도 재사용 가능한 serialization envelope 정리
- `ADR-0016 P4`: snapshot/backfill capability, parent session revoke, peer authentication binding 구체화
- delta structure 후보(IBLT/GCS 등) 선택, benchmark, wire contract, strict cascade wiring 구체화
- Phase 0 sign-off 에서 확인된 local assumption(`backfillUnavailable`, empty `ops`, recovery simultaneous re-open`) 에 대해 ADR author ruling 또는 ADR amendment 제안
- peer discovery / scoring / topology management 용 별도 ADR 또는 plan 작성

## Phase 0 Artifacts

### Implementation Inventory Appendix
| Area | Scope | Notes |
| --- | --- | --- |
| runtime-owned in-scope | session lifecycle, handshake negotiation, composite cursor, tx replay/resume, peer correlation tracking, canonical rejection classes | `ControlBatch` semantic apply 와 strict cascade strategy seam 포함 |
| transport-owned in-scope | HTTP resource mapping, SSE 또는 NDJSON framing, lowercase hex and base64url text encoding, keepalive timer wiring, disconnect signal projection | runtime-owned model 의 wire projection 만 담당 |
| experimental in-scope | proposal/vote envelope validation, vote target convention agreement, consensus window key, QoS separation | production interoperability 는 gate 밖 |
| explicit deferred to follow-up ADR | proposal/vote sign-bytes, validator-set commitment derivation, snapshot/backfill capability format, parent-session capability binding, production peer authentication binding | `P2`, `P4` 소유 |

### Seam Validation Appendix
- allowed dependency 방향
  `runtime.gossip.tx|runtime.gossip.consensus -> runtime.gossip|runtime-owned abstractions`
- allowed dependency 방향
  `transport.armeria.gossip -> runtime.gossip`
- forbidden dependency 방향
  `runtime.gossip -X-> transport.armeria.gossip`
- forbidden dependency 방향
  `transport.armeria.gossip -X-> storage.*`
- forbidden dependency 방향
  `runtime.gossip -X-> org.sigilaris.node.jvm.transport.armeria.gossip.codec` 같은 text transport encoding helper

### Package Ownership Appendix
- `org.sigilaris.node.jvm.runtime.gossip`
  runtime-owned session model, lifecycle/state machine, handshake negotiation, cursor/control/rejection contract, peer correlation tracking을 소유한다.
- `org.sigilaris.node.jvm.runtime.gossip.tx`
  `tx` topic identity, replay/resume, known-set evaluator hookup, artifact source/sink contract 연결을 소유한다.
- `org.sigilaris.node.jvm.runtime.gossip.consensus`
  proposal/vote experimental envelope validation, vote target convention agreement, consensus window key, QoS 분리 지점을 소유한다.
- `org.sigilaris.node.jvm.transport.armeria.gossip`
  HTTP resource mapping, SSE 또는 NDJSON framing, text transport canonical encoding, disconnect callback, runtime rejection projection을 소유한다.
- follow-up ADR owned
  proposal/vote sign-bytes, `validatorSetHash` derivation, snapshot/backfill capability construction, production peer authentication binding은 이 plan의 구현물이 아니라 별도 문서가 소유한다.

### Phase Gate Appendix
- Phase 0 완료 gate
  `Package Ownership Appendix`와 이 appendix가 채워지고, `tx` baseline / consensus experimental / follow-up ADR blocker 경계가 문서 본문과 일치한다.
  baseline Bloom filter family 와 baseline `config` key set 이 문서에 기록된다.
  Bloom `setFilter` payload parameter(`bitset`, `numHashes`, `hashFamilyId`) / replace semantics 가 문서에 기록된다.
  `tx.toHash` direct availability 또는 `TxIdentity` abstraction 필요 여부가 sign-off 시점에 결정되고, Phase 1 model input 으로 고정된다.
  `maxTxSetKnownEntries` local policy 가 문서에 기록된다.
  experimental consensus baseline vote target convention 이 문서에 기록된다.
  Bloom hint-tier vs strict-cascade interpretation ADR reconciliation 이 완료된다.
  snapshot/backfill, sign-bytes, validator-set commitment, peer authentication binding blocker list 가 문서에 기록된다.
  half-open recovery simultaneous re-open ADR gap 에 대한 explicit sign-off 가 남는다.
  half-open recovery re-negotiation policy 가 sign-off decision 으로 잠긴다.
  `openingHandshakeTimeout` default 와 plan-local keepalive ignore policy 가 sign-off decision 으로 잠긴다.
  `backfillUnavailable` post-rejection scope, baseline `config` key semantics, recovery simultaneous re-open local rule 에 대한 ADR reconciliation action 이 남는다.
  `ADR-0016` status 와 reference hash 가 sign-off 기록에 남는다.
  appendices 와 checklist 갱신을 포함한 Phase 0 sign-off commit 이 기준선으로 기록된다.
- Phase 1 완료 gate
  handshake model에 peer correlation id, immutable subscription, heartbeat/liveness default/range/ratio 규칙, `maxControlRetryInterval` default/echo/shorten/reject 규칙, optional vote target convention field가 포함되고, negotiation validation failure 가 canonical `handshakeRejected` 로 투영된다.
  runtime state machine이 test-fixture backed `PeerIdentity` auth contract 를 통해 첫 directional session open 시 correlation id 를 생성하고, half-open 동안 유지하며, handshake-ack round-trip 뒤에만 `open` 상태로 진입하고, `opening` 상태의 pre-open traffic 을 explicit rejection 후 close 처리하며, `openingHandshakeTimeout` expiry 시 `dead` 로 전이하고, simultaneous-open detection 을 `PeerIdentity` 기반 concurrent opening session 으로 판단하며, incoming/local correlation id 가 다르면 fresh simultaneous-open ADR tie-break, 같으면 half-open recovery simultaneous re-open local rule 을 적용하고, loser close/retry with winner correlation id reuse, loser successful retry with a new directional session id, winner-side reverse-direction session 과 coexist 가능한 retry, loser retry failure 시 explicit rejection 또는 `dead` outcome, loser lineage discard, `opening -> open|closed|dead`, `open -> half-open|closed|dead`, `half-open -> open|closed|dead` 전이를 pure test로 검증한다.
  `CursorToken` version prefix generation/validation, stale cursor, control batch rejection class가 구분되고, malformed `idempotencyKey`, empty `ops` list, malformed discriminator 에 대한 structural/schema validation test 가 통과하며, handshake message family 가 `ControlBatch` 와 분리되고, event-stream keepalive 와 control-channel keepalive/ack 가 typed runtime protocol message 로 모델링되며, consensus envelope field slot(`height`, `round`, `validatorSetScheme`, `validatorSetHash`, `targetKind`, `targetId`) 이 예약되며, structurally valid but semantically unavailable `ControlBatch` 가 transitional `controlBatchRejected` 로 투영되며, `ControlBatch` 는 model-only envelope 와 structural/schema validation 까지만 소유하고 Phase 1 cursor tests 는 value-level resume contract 만 검증함이 분명해진다.
- Phase 2 완료 gate
  sub-gate `2a` 는 Phase 0에서 `tx.toHash`/`TxIdentity` 와 Bloom hint-tier interpretation 이 잠긴 뒤에만 시작한다. `2a` 에서 in-memory gossip state store, directional-session-scoped idempotency dedupe logic, atomic apply interpreter, exact-known-set-to-`backfillUnavailable` cascade seam 이 isolated unit test 로 먼저 green 이다. 그 다음에만 `tx` topic loopback path로 연결된다.
  `tx.toHash` 가 `runtime.gossip.tx` 에서 직접 접근 가능하거나, 동일 의미의 `TxIdentity` abstraction 이 도입되어 tx stable id contract 가 compile-time 으로 고정된다.
  `tx` topic이 `tx.toHash` 기반 stable id, `CompositeCursor` replay/resume, control batch atomicity, idempotency retry horizon을 갖춘 loopback path로 동작한다.
  partial `CompositeCursor` omission 과 fully empty `CompositeCursor` 가 각각 허용된 origin replay path 로 해석되고, 일부 key resume + 일부 key origin replay 가 같은 reconnect 안에서 함께 동작하며, inclusive checkpoint 뒤 next-event resume semantics 가 검증된다.
  `setKnown`, `setCursor`, `nack`, `config` control op 가 topic-discriminated baseline contract 를 가진다. `setFilter` 는 Phase 0 sign-off 가 Bloom hint-tier interpretation 을 승인한 경우에만 shipped apply contract 에 포함된다.
  consensus-only semantic op(`setKnown.consensusWindow` 등)는 Phase 2/3 runtime 에서 canonical `controlBatchRejected` 로 거절된다.
  Phase 0 sign-off 가 Bloom hint-tier interpretation 을 승인한 경우에만 Bloom `setFilter` path가 동작하고, 미지원 filter kind rejection 이 관찰 가능하다.
  Phase 0 sign-off 가 Bloom hint-tier interpretation 을 승인한 경우에만 같은 topic/session 의 새 `setFilter` 는 이전 filter state 를 replace 하고, reconnect 후 filter state 는 carry-over 되지 않는다.
  tx topic 에서는 bounded exact tx-id `setKnown` fallback 이 동작하고, consensus path 에 확장 가능한 topic-discriminated schema 를 유지한다.
  `tx.maxBatchItems`, `tx.flushIntervalMs` key 에 대한 `config` apply path가 producer-side outbound tx batching model 에 실제 반영되고, unsupported key rejection 이 관찰 가능하다.
  runtime-owned gossip state store 가 session registry, peer correlation, cursor, known-set/filter, idempotency, config/QoS state 를 소유하고, session-open, control, event apply 세 경로 모두에 peer authentication context 가 연결되며, logical event-stream/control-channel separation 위에서 active dedupe horizon 이 negotiated `maxControlRetryInterval` 을 따라 형성되고 horizon 경계, session 종료, horizon 만료 후 old key eviction 이 올바르게 동작한다.
  clock 또는 scheduler abstraction 이 `ts` emission 과 retry horizon 계산에 실제로 연결된다. heartbeat/liveness timeout firing 은 Phase 3 transport-integrated path 에서 검증한다.
  cascade strategy abstraction 이 minimal `BackfillRequest` placeholder type 을 포함하고, `delta structure -> exact known set -> snapshot/backfill` 순서를 강제하며 out-of-order fallback 을 reject 하고 positive advancement path 도 test 로 검증된다.
  tx topic 에서도 bounded exact `setKnown` fallback 으로 해결되지 않으면 explicit `backfillUnavailable` rejection emission 이 관찰 가능하다. post-rejection session/topic consequence 는 follow-up ADR or protocol spec sign-off 전까지 shipped contract 로 고정하지 않는다.
  `ts` 가 producer-side clock abstraction 에서 populate 되어 consumer 까지 전달되고, stable id dedup 는 newer cursor replay 에서도 유지된다.
  `nack` 는 `(chainId, topic)` cursor-based replay request 로만 동작하고, arbitrary event-id range contract 를 요구하지 않는다.
  wrong-channel keepalive 는 runtime level 에서 reject 되고, 양방향 directional session 두 개가 같은 peer correlation id 아래에서 재사용되며, correlation id 는 두 방향이 모두 `closed|dead` 가 된 뒤에만 폐기된다. 또한 Phase 0 sign-off 가 recovery local rule 을 승인한 경우에만 한 방향 종료 시 다른 방향이 half-open 으로 유지되고, 새 directional session id + 기존 correlation id 를 쓰고 subscription, `heartbeatInterval`, `livenessTimeout`, `maxControlRetryInterval` 을 다시 협상하는 full re-handshake 기반 half-open recovery 와 half-open simultaneous re-open 의 plan-local `handshakeRejected` path 가 관찰 가능해야 한다.
- Phase 3 완료 gate
  Armeria adapter가 session-open, stream, control HTTP path를 제공하고 runtime canonical rejection을 손실 없이 투영한다.
  canonical rejection class 가 HTTP/status/stream termination 으로 lossless 하게 투영됨이 검증되고, Phase 0 sign-off 가 recovery local rule 을 승인한 경우에만 existing correlation id 기반 half-open recovery re-handshake path 가 동작한다.
  Phase 0 sign-off 가 recovery local rule 을 승인한 경우에만 half-open recovery 가 same session-open resource family 위에서 새 directional session id + 기존 correlation id 로 재협상되는 HTTP mapping 으로 고정된다.
  text transport canonical encoding이 adapter 경계에서 수행되고 `transport.armeria.gossip -> storage.*` 직접 의존이 없다.
  실제 HTTP integration test가 keepalive, disconnect 기반 dead 판정, resume path, 동일 `idempotencyKey` control retry dedupe 를 검증한다.
- Phase 4 완료 gate
  consensus experimental mode가 `x-exp-id:*`, `x-exp-scheme:*`, vote target convention mismatch, exact known-set window 비교를 검증한다.
  exact known set oversize 와 backfill 미구현 조합에서 explicit `backfillUnavailable` rejection 이 관찰 가능하다.
  consensus artifact source/sink 와 window-bound known-set query contract 가 runtime abstraction 을 통해 연결되고, mandatory `targetKind`/`targetId` validation 과 QoS 분리가 intra-topic producer emission order 를 깨지 않음을 포함해 검증되며, 문서 갱신, follow-up ADR dependency 기록, compile/test/regression 기록이 완료된다.

### Completion Appendix
- verification log
  Phase 완료 시 compile/test/regression 명령과 결과를 여기에 기록한다.
- decision log
  phase 진행 중 잠근 local implementation choice와 후속 ADR로 넘긴 항목을 여기에 기록한다.
