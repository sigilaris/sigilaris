# ADR-0016: Multiplexed Gossip Session Sync Substrate

## Status
Accepted

## Context
- `sigilaris`는 현재 `sigilaris-core`와 JVM node bundle `sigilaris-node-jvm`을 중심으로 구성되어 있고, 향후 P2P networking과 node discovery는 아직 "coming soon" 영역으로 남아 있다.
- 멀티 블록체인 노드를 지원하려면 단순 transaction relay를 넘어서 `tx`와 기타 correctness-sensitive artifact를 peer 사이에서 동기화해야 한다.
- 이 artifact들은 모두 "내가 이미 알고 있는 것"과 "상대가 아직 모르는 것"의 차집합을 효율적으로 교환해야 하지만, delivery semantics는 동일하지 않다.
- 타입별로 별도 HTTP endpoint를 쪼개면 transport surface, 연결 수, 배치 정책, cursor 관리, 재시도 처리가 빠르게 복잡해진다.
- 동시에 `sigilaris-node-jvm`은 single opinionated bundle을 유지하되, `runtime`, `transport`, `storage` seam을 보존하도록 설계되어 있다. 가십 프로토콜도 이 경계를 따라 transport adapter와 runtime protocol/state를 분리해야 한다.
- 이전 문서는 gossip/session substrate와 consensus artifact semantics를 함께 다뤘다. 그러나 장기적으로는 "운반 규약"과 "합의 artifact 의미론"을 분리하는 편이 더 유지보수 가능하다.
- 따라서 이 ADR은 transport-neutral gossip/session substrate만 고정하고, consensus-specific artifact contract는 별도 ADR이 소유한다. HotStuff non-threshold-signature baseline은 ADR-0017이 담당한다.
- 초기 deployment topology와 node role 운영 baseline은 ADR-0018이 소유한다. 이 ADR은 peer discovery나 validator admission policy 자체를 고정하지 않고, gossip substrate가 그 위에서 동작할 수 있는 transport/runtime contract만 다룬다.
- 현재 `docs/plans/0002-sigilaris-node-jvm-extraction-plan.md`는 multi-node networking을 범위 밖으로 두고 있으므로, 이 ADR은 현재 extraction plan의 즉시 구현 범위를 넓히는 문서가 아니라 후속 networking 설계를 고정하기 위한 정책 문서다.
- `2026-04-06` 기준 `sigilaris-node-jvm`에는 tx-topic gossip/session baseline, negotiated liveness wiring, pre-open reject-and-close, static peer config bootstrap, topic-neutral producer polling/QoS seam, half-open re-handshake, reconnect filter reset, session-bound bootstrap transport consumer까지 HTTP baseline에 반영되었다. static-topology peer identity binding / session-bound capability authorization semantic baseline은 ADR-0024에서 drafted 되었고, exact transport credential binding과 추가 consensus topic semantics는 여전히 follow-up 범위다.

## Decision
이 절의 규칙은 `sigilaris-node-jvm`의 shipped tx-topic HTTP baseline과 follow-up topic/runtime work 모두에 적용되는 normative policy다. 현재 구현은 tx-topic anti-entropy, static peer topology baseline, session-bound bootstrap consumer path를 제공한다. static-topology peer identity binding / session-bound capability authorization semantic baseline은 ADR-0024가 소유하고, exact transport credential binding과 추가 consensus topic semantics는 여전히 follow-up 범위다.

1. **Peer synchronization은 endpoint-per-type RPC가 아니라 session-based anti-entropy protocol로 정의한다.**
   - canonical 모델은 방향성을 가진 "peer session"이며, session은 한 producer에서 한 consumer로 향하는 delta 전송 흐름과 그에 대한 known-set 교환, replay/backfill, heartbeat를 포함한다.
   - `tx`와 기타 topic은 같은 session 안의 logical topic으로 다룬다.
   - 양방향 peer sync가 필요하면 동일한 contract를 반대 방향으로 한 번 더 적용해 두 개의 directional session으로 구성한다.
   - 각 directional session은 독립된 session id를 가진다. session id 형식은 lowercase hyphenated UUIDv4 string (`8-4-4-4-12`)이다. 양방향 peer 관계를 묶을 때는 runtime이 두 directional session을 하나의 mandatory peer correlation id 아래에서 관리한다.
   - peer correlation id는 runtime-owned 개념이며, 최초 directional session initiator의 runtime이 생성한다. 형식은 lowercase hyphenated UUIDv4 string (`8-4-4-4-12`)이다. bidirectional sync를 의도하는 session-open에서는 transport가 이를 handshake metadata의 opaque field로 반드시 전달해야 한다. per-event envelope field로는 강제하지 않는다.
   - simultaneous-open tie-break는 transport가 아니라 runtime/session layer가 handshake metadata를 전달받은 뒤 수행하며, canonical UUIDv4 string의 bytewise lexicographic order를 사용한다.
   - 반대 방향 directional session을 여는 peer는 새 correlation id를 만들지 않고 기존 peer correlation id를 session-open handshake에서 재사용해야 한다. acceptor는 이를 수락하거나 mismatch로 거절한다.
   - 두 peer가 동시에 첫 directional session을 여는 simultaneous open에서는 lexicographically smaller correlation id를 승자로 채택하고, 다른 쪽 opener는 provisional loser session을 닫은 뒤 winner id로 retry 해야 한다. loser session의 cursor/resume lineage는 폐기하며, 이미 fully applied 된 artifact는 normal dedup/validation 규칙 아래에서만 유지할 수 있고 별도 rollback contract를 요구하지 않는다.
   - directional session은 correlation id를 포함한 session-open handshake와 handshake-ack가 왕복된 뒤에만 open 상태로 간주한다.
   - peer correlation id는 첫 directional session이 열릴 때 생성되고, 두 방향 중 하나만 살아 있는 동안에도 half-open peer relationship을 식별하는 데 유지된다.
   - 각 방향의 liveness는 event/control heartbeat와 transport disconnect signal을 기준으로 runtime timeout으로 판정한다. peer correlation id는 두 방향 모두가 closed 또는 dead 상태가 된 뒤에만 폐기할 수 있다.
   - directional session 자체의 baseline 상태는 `opening -> open -> closed|dead` 이다.
   - peer correlation id 아래의 bidirectional peer relationship baseline 상태는 `opening -> open -> half-open -> closed|dead` 이다. 여기서 `half-open`은 정확히 한 방향의 directional session만 살아 있는 관계 상태를 뜻한다.
   - 초기 JVM deployment baseline에서는 dynamic discovery 없이 static peer registry와 static neighbor list를 사용해도 된다. exact config source와 운영 baseline은 ADR-0018이 소유한다.
   - detailed transition table은 follow-up protocol spec 또는 implementation plan에서 고정한다.

2. **한 peer session은 하나의 logical event stream과 하나의 logical control channel을 가진다.**
   - event stream은 producer가 consumer에게 delta/event를 흘려보내는 단방향 채널이다.
   - control channel은 consumer가 producer에게 filter, known-set, cursor, nack, explicit request, config 같은 session 제어 메시지를 보내는 단방향 채널이다.
   - producer heartbeat/keepalive는 event stream 쪽으로, consumer ack/keepalive는 control channel 쪽으로 보낸다.
   - session-open handshake는 `heartbeatInterval`과 `livenessTimeout`도 함께 합의해야 한다. 값은 integer milliseconds로 표현하고, 기본값은 각각 `10s`, `30s`다.
   - initiator가 heartbeat/liveness 값을 제안하면 acceptor는 동일 값 또는 더 보수적인 값으로 응답할 수 있다.
   - 여기서 보수적이라는 뜻은 `heartbeatInterval`은 더 짧거나 같은 값, `livenessTimeout`은 더 길거나 같은 값을 의미한다. `heartbeatInterval`은 `1s` 이상 `60s` 이하, `livenessTimeout`은 최소 `3 * heartbeatInterval` 이상이어야 한다.
   - producer와 consumer는 negotiated heartbeat interval보다 느리지 않게 stream/control 쪽 keepalive를 보내야 하고, negotiated liveness timeout 안에 관련 traffic가 없으면 상대를 `dead`로 판정할 수 있다.
   - 위 heartbeat/liveness 값은 transport/session liveness 기본값이지 consensus block cadence나 leader pacing target을 직접 의미하지 않는다. block production target과 pacemaker timing baseline은 ADR-0017, ADR-0018, 후속 pacemaker 문서가 소유한다.
   - transport가 full-duplex이면 두 채널은 하나의 물리 연결 위에서 multiplex될 수 있다.
   - transport가 HTTP half-duplex이면 directional session 하나를 stream resource와 control resource의 두 HTTP endpoint로 매핑할 수 있다. concrete path shape와 framing은 transport adapter design에서 고정한다.
   - optional snapshot/backfill adapter는 별도 endpoint로 제공할 수 있지만, 이는 runtime-owned backfill service를 노출하는 얇은 transport adapter여야 한다.

3. **이벤트는 chain-aware multiplexed envelope로 전송한다.**
   - 모든 stream event는 공통 필드로 `chainId`, `topic`, stable `id`, `cursor`, `ts`를 포함한다.
   - `chainId`는 non-empty lowercase ASCII token이다.
   - `ts`는 producer가 기록한 UTC 기준 Unix epoch milliseconds wall-clock timestamp이며, 진단/관찰용 메타데이터이지 ordering key가 아니다.
   - 공통 필드의 `cursor`는 해당 `(chainId, topic)` position에 대한 `CursorToken` 자체이며, 재연결 시 query/header/`Last-Event-ID`로 실어 보내는 값과 동일한 token이다.
   - stable `id`는 producer-assigned sequence number가 아니라 해당 topic contract가 정하는 deterministic artifact identifier다.
   - `tx` topic의 stable `id`는 ADR-0012의 `tx.toHash`가 반환하는 값과 동일하다. exact preimage/canonical bytes 정의는 ADR-0012와 ADR-0009가 소유하며, 이 ADR은 이를 재정의하지 않는다.
   - text-based transport에서 binary identity는 lowercase hex로 canonical encoding 한다.
   - 이 ADR은 공통 필드 외의 topic-specific mandatory envelope field를 직접 정의하지 않는다. `tx` 외 topic의 추가 필드, identity, validation, sign-bytes contract는 해당 topic을 소유하는 별도 ADR 또는 protocol spec이 고정한다.
   - `topic`은 최소한 `tx`를 지원하고, 이후 `consensus.proposal`, `consensus.vote` 같은 타입을 확장 가능하게 설계한다.

4. **cursor는 global single offset이 아니라 `(chainId, topic)` 단위의 composite cursor로 관리한다.**
   - canonical contract는 `CompositeCursor = Map[(chainId, topic), CursorToken]` 이다.
   - `CompositeCursor`의 key space는 session-open에서 합의된 chain/topic subscription으로 제한되며, producer는 그 범위를 넘는 key를 요구해서는 안 된다.
   - session-open에서 합의된 chain/topic subscription은 session lifetime 동안 immutable baseline으로 둔다. 이를 바꾸려면 새 session을 열어 다시 합의한다.
   - `CursorToken`은 runtime이 발급하는 opaque serialized resume token이다. 서로 다른 `(chainId, topic)` 사이의 token은 비교 대상이 아니며, 같은 key 안에서도 consumer가 순서를 계산하지 않는다.
   - runtime은 previously issued token을 재시작 전후로 resume token으로 계속 받아들이거나, 더 이상 유효하지 않을 때 explicit stale-cursor/replay-required response를 반환해야 한다.
   - `CursorToken`은 producer/runtime이 해석하는 explicit version prefix를 내부에 포함해야 하며, consumer에게는 여전히 opaque하다. runtime이 token format 또는 token version을 변경할 때는 기존 token을 silent misinterpret 하지 말고 반드시 explicit stale-cursor/replay-required response로 거절해야 한다.
   - 재연결, 리와인드, nack recovery는 `CompositeCursor` 기준으로 동작하고, consumer는 `(chainId, topic)`마다 가장 마지막으로 수신한 token 하나만 저장한 뒤 그대로 되돌려 보내며 직접 비교하거나 병합 규칙을 추론하지 않는다.
   - HTTP adapter는 query parameter, header, 또는 SSE `Last-Event-ID` 확장 표현으로 token을 opaque하게 실어 나를 수 있지만, canonical contract는 transport-neutral composite cursor다. text-based transport는 `CursorToken`을 base64url without padding으로 canonical encoding 해야 한다.
   - `cursor`는 resume position 전용이고 ordering key가 아니다. ordering은 producer의 emission order와 topic-local metadata로 정의하며, `id`는 identity/dedup 용도로만 사용한다.
   - event envelope에 붙은 `cursor`는 해당 event까지 inclusive 하게 전달되었음을 나타내는 checkpoint다. consumer가 그 값을 재전송하면 producer는 그 다음 event부터 replay/resume 한다.
   - replay/retransmit 동안 같은 stable `id`가 더 새로운 `cursor`와 함께 다시 나타날 수 있다. consumer dedup는 같은 `(chainId, topic)` 안에서 stable `id` 기준으로 수행하고, `cursor`는 resume 위치로만 사용한다.
   - reconnect 시 partial `CompositeCursor`를 보내는 것은 허용한다. producer는 map에 없는 `(chainId, topic)` key를 해당 key의 origin replay 요청으로 해석한다.

5. **known-set synchronization 구조는 topic별로 다르게 허용한다.**
   - `tx`는 Bloom filter, GCS, 또는 유사한 probabilistic structure를 자주 보내는 방식을 허용한다.
   - correctness-sensitive topic은 Bloom filter만으로 정합성을 판단해서는 안 된다.
   - correctness-sensitive topic의 exact known set, range set, GCS, IBLT, window key 같은 세부 구조는 해당 topic contract가 고정한다.
   - IBLT나 유사한 delta structure가 capacity 초과나 decode failure로 실패하면 peer는 exact known set으로 먼저 fallback 하고, exact known set도 과도하면 snapshot/backfill로 내려가는 strict cascade를 따라야 한다.
   - protocol은 top-level `ControlBatch(idempotencyKey, ops)` envelope 아래에서 `setFilter`, `setKnown`, `setCursor`, `nack`, `requestById`, `config`류 control op를 배치로 보낼 수 있어야 한다.
   - `ops`는 ordered list로 해석하고, `ControlBatch`는 baseline으로 atomic all-or-nothing apply semantics를 가져야 한다. batch 내 한 op라도 validation/apply에 실패하면 전체 batch는 reject 되고 어떤 op도 effect를 남겨서는 안 된다.
   - `idempotencyKey` 형식은 lowercase canonical UUIDv4 string이다.
   - `idempotencyKey`는 directional session id scope 안에서 unique 해야 하고, control batch receiver는 configured control retry horizon 동안 이를 dedupe 해야 한다.
   - session-open handshake는 합의된 `maxControlRetryInterval`을 포함해야 한다. 값은 integer milliseconds로 표현한다. session initiator가 값을 제안하고 acceptor가 동일 값을 echo 하거나 더 짧은 값으로 응답해 확정한다. 제안값과 확정값은 모두 `1s` 이상 `5m` 이하여야 하며, acceptor는 local min/max policy를 벗어나는 제안값을 reject 할 수 있다. acceptor가 더 긴 값, 범위 밖의 값, invalid value, 또는 required echo omission을 반환하면 initiator는 session-open을 실패로 처리한다. initiator가 값을 생략하면 제안값은 `30s`로 간주하고, acceptor는 그 값을 explicit numeric echo, 단축, 또는 policy 위반 시 reject 할 수 있다.
   - control retry horizon은 directional session의 `maxControlRetryInterval`의 최소 2배 이상 최대 10배 이하여야 하며, retry horizon이 끝나거나 session이 종료되면 오래된 key를 폐기할 수 있다.
   - `requestById`는 현재 session subscription 범위 안에서 bounded explicit fetch를 요청하는 control op다. 어떤 topic에서 허용하는 최대 id 수, batching 규칙, authorization, oversize handling은 topic contract 또는 runtime policy가 고정한다.
   - strict cascade의 terminal fallback인 snapshot/backfill이 unavailable이면 peer는 explicit `backfillUnavailable` rejection으로 종료해야 하며, silent partial apply로 넘어가서는 안 된다.

6. **동일 session 안에서 logical topic은 multiplex하되, runtime은 QoS를 분리해야 한다.**
   - low-priority topic backlog가 correctness-sensitive topic 전송을 막아서는 안 된다.
   - batching은 허용하지만 topic별 별도 우선순위, flush policy, 또는 독립 버퍼를 둘 수 있어야 한다.
   - ordering contract는 topic에 맞게 정의하고, event envelope에 reordering/dedup에 필요한 metadata를 담는다.
   - 단일 directional stream 안에서는 producer emission order가 canonical delivery order다.
   - fixed even/odd time window 같은 구체적인 스케줄링 방식은 implementation-local optimization일 수 있지만, canonical wire contract나 correctness assumption으로 고정해서는 안 된다.

7. **transport adapter는 protocol/state machine의 owner가 아니다.**
   - 이 ADR은 gossip/session path에 대해 별도의 seam policy를 추가로 정의하며, transport가 runtime-owned protocol/service contract를 통해서만 storage-backed data에 접근하도록 요구한다.
   - `runtime` 또는 그 위의 service contract가 peer session state, cursor merge, filter merge, replay decision을 소유한다.
   - `transport.armeria`는 stream/control adapter, serialization, connection lifecycle만 담당한다.
   - `transport`는 `storage.swaydb` 같은 concrete storage implementation에 직접 의존하지 않는다.
   - snapshot/backfill endpoint가 존재하더라도 transport가 storage를 직접 조회하지 않고 runtime/downstream service contract를 호출해 수행한다.
   - backfill/snapshot 기능을 구현하는 경우, 그 path는 directional session과 동일한 peer authentication policy를 따라야 하고, replay를 제공하는 producer가 소유한 directional session id에 바인딩되어야 한다.
   - backfill request는 parent directional session id를 명시적으로 참조해야 하며, consumer는 기존 session state에서 그 값을 얻어 사용한다.
   - configured `PeerIdentity` 와 authenticated counterparty identity binding, session-bound child capability ownership, parent-session revoke/close cascade semantic baseline은 ADR-0024가 소유한다.
   - directional session id에서 파생한 capability/token을 쓰는 경우의 exact construction은 follow-up spec이 고정한다. experimental implementation의 최소 contract는 peer가 그 capability가 parent session과 peer authentication context에 바인딩되어 있음을 검증할 수 있어야 하고, parent session lifetime을 넘겨 재사용될 수 없게 만드는 것이다.
   - parent directional session이 종료되거나 revoke 되면 그 session에 종속된 snapshot/backfill capability도 더 이상 새 데이터를 승인하지 않도록 무효화되어야 하고, in-flight backfill은 runtime이 termination을 관측한 시점 이후 abort 또는 explicit re-authentication으로 전환되어야 한다.

8. **Canonical Rejection Classes를 유지한다.**
   - wire-level schema는 transport adapter/follow-up spec이 고정하더라도, runtime/service contract는 최소한 `handshakeRejected`, `staleCursor`, `controlBatchRejected`, `artifactContractRejected`, `backfillUnavailable` rejection class를 canonical하게 구분해야 한다.
   - topic-specific validation failure가 더 세밀한 이유 코드를 가지더라도, transport-neutral substrate 경계에서는 최소한 위 rejection class 가운데 하나로 손실 없이 투영할 수 있어야 한다.
   - 따라서 이전 consensus-specific draft에서 별도 canonical rejection처럼 보였던 `unknownScheme`, `conventionMismatch` 같은 세부 사유는 substrate가 아니라 topic owner ADR 또는 protocol spec이 소유한다. substrate는 이를 필요하면 `artifactContractRejected` 아래의 topic-local subreason으로만 운반한다.
   - transport adapter는 위 rejection class를 손실 없이 wire representation으로 투영해야 한다.

9. **topic-specific identity/signing contract는 topic owner가 소유한다.**
   - transaction은 기존 signed transaction requirement를 따른다.
   - 따라서 `tx` topic의 signing/sign-bytes contract는 ADR-0012를 그대로 따른다.
   - `tx` 외 topic은 검증 가능한 signed artifact 또는 validated artifact로 취급하되, cross-node interoperability 대상으로 shipping 하기 전 해당 topic owner ADR 또는 protocol spec에서 canonical whole-value deterministic encoding과 identity/sign-bytes contract를 먼저 고정해야 한다.
   - 이 ADR의 event envelope 규칙은 session wire contract이고, topic-specific sign-bytes contract가 transport envelope를 그대로 서명한다는 뜻은 아니다.
   - HotStuff non-threshold-signature consensus artifact는 ADR-0017이 소유한다.
   - control path에서는 peer authentication과 schema validation을 mandatory로 둔다.
   - 모든 control batch는 top-level envelope field로 `idempotencyKey`를 mandatory로 둔다.
   - rate limiting은 wire field가 아니라 deployment/adapter policy로 취급한다.

10. **초기 구현은 HTTP-friendly adapter를 허용하되, canonical protocol은 transport-neutral하게 유지한다.**
   - 초기 운영 단순성을 위해 SSE/NDJSON 기반 stream과 batched JSON control request를 사용할 수 있다.
   - 이후 WebSocket, HTTP/2 stream, QUIC 등 full-duplex transport가 필요해져도 protocol model 자체는 바꾸지 않는다.

## Security Considerations
- peer authentication model은 mandatory이며, static-topology baseline 아래의 configured-peer/session-bound capability ownership은 ADR-0024가 고정한다. 구체적인 TLS/application-level binding 방식은 follow-up spec이 고정한다.
- malicious peer의 과도한 replay 요청, oversized known-set/control batch, reconnect flood는 rate limiting과 admission policy로 방어해야 한다.
- session hijacking 방지를 위해 session-open, control path, backfill path는 동일한 peer authentication context 아래에 묶여야 한다.
- topic-specific validation rule이 아직 experimental이면 production deployment에서는 해당 topic contract의 support 여부를 명시적으로 관리해야 한다.

## Consequences
- 타입별 endpoint를 늘리지 않고도 여러 artifact의 known-set sync를 한 session에 묶을 수 있다.
- connection 수와 control-plane round trip을 줄이면서도 batch update와 atomic apply를 지원할 수 있다.
- `sigilaris-node-jvm`의 `runtime`/`transport` seam을 유지한 채, gossip/session logic을 transport adapter 바깥에 둘 수 있다.
- multi-chain 환경에서 cursor와 replay state가 `chainId` 단위로 분리되므로 잘못된 재전송이나 cursor 충돌을 줄일 수 있다.
- `requestById` 같은 bounded explicit fetch를 control contract에 포함하므로 Bloom/exact-known-set 기반 anti-entropy와 hole-filling fetch를 같은 세션 아래에 둘 수 있다.
- 초기 deployment는 static peer registry와 static neighbor topology만으로도 출발할 수 있고, peer discovery는 후속 과제로 남길 수 있다.
- 대신 composite cursor, topic-aware QoS, control op atomicity 같은 runtime complexity가 늘어난다.
- 이 ADR만으로 peer discovery, peer scoring, validator admission, topology management, consensus artifact semantics가 해결되지는 않는다.

## Rejected Alternatives
1. **타입별 개별 endpoint를 둔다** (`/bloom/tx`, `/known/topic`, `/request/topic`, ...)
   - 직관적이지만 connection 수와 요청 수가 늘고, atomic control update와 batching이 어렵다.
   - topic이 늘수록 transport surface와 운영 복잡도가 비례해서 증가한다.

2. **모든 artifact를 하나의 probabilistic filter로만 동기화한다**
   - transaction에는 유용할 수 있지만, correctness-sensitive topic은 false positive를 단순히 허용하기 어렵다.
   - 누락은 correctness/liveness 문제로 이어질 수 있으므로 exact or near-exact delta 구조가 필요하다.

3. **처음부터 WebSocket/QUIC 전용 full-duplex 프로토콜로 고정한다**
   - 장기적으로는 가능하지만, 현재는 transport-neutral message model을 먼저 잠그는 편이 낫다.
   - 초기 JVM node bundle은 Armeria 기반 HTTP adapter로도 충분히 출발할 수 있다.

4. **topic semantics와 gossip substrate를 같은 ADR에서 영구적으로 묶는다**
   - 문서 수는 줄지만, 합의 알고리즘 변경이 transport/session 정책까지 같이 흔들 수 있다.
   - 장기적으로는 "운반 규약"과 "artifact 의미론"을 분리하는 편이 더 안정적이다.

5. **topic마다 session을 완전히 분리한다**
   - QoS 분리는 쉬워지지만 peer state, authentication, cursor handshake, heartbeat가 중복된다.
   - 기본값으로는 single session multiplexing이 낫고, 극단적인 운영 요구가 생길 때만 물리적 분리를 고려한다.

## Follow-Up
- `P1`: runtime/service 경계에 `PeerSyncSession`, `GossipEvent`, `ControlOp`, `CompositeCursor`, session lifecycle state machine, rejection code model을 정의하는 gossip substrate implementation plan을 작성한다. baseline plan은 `docs/plans/0003-multiplexed-gossip-session-sync-plan.md`다.
- `P2`: HotStuff non-threshold-signature consensus artifact contract는 ADR-0017이 소유한다.
- `P3`: static peer topology, validator/audit node role, initial 100ms deployment target은 ADR-0018이 소유한다.
- `P4`: HotStuff consensus implementation plan은 `docs/plans/0004-hotstuff-consensus-without-threshold-signatures-plan.md`에서 관리한다.
- `P5`: `transport.armeria` baseline 은 peer-facing event stream 을 binary length-prefixed envelope 로 승격했고 session-open/control 은 JSON baseline 을 유지한다. 이후 full-duplex transport 로 전환할 때도 same runtime-owned envelope / compatibility policy 를 재사용한다.
- `P6`: static-topology peer identity binding, session-bound bootstrap capability authorization, parent-session revoke cascade semantic baseline은 ADR-0024가 소유한다. snapshot/backfill contract, nack semantics의 상세 rule, exact transport credential / capability encoding은 별도 protocol spec 또는 implementation plan에서 구체화한다.

## References
- [ADR-0009: Blockchain Application Architecture](0009-blockchain-application-architecture.md)
- [ADR-0012: Signed Transaction Requirement](0012-signed-transaction-requirement.md)
- [ADR-0015: JVM Node Bundle Boundary And Packaging](0015-jvm-node-bundle-boundary-and-packaging.md)
- [ADR-0017: HotStuff Consensus Without Threshold Signatures](0017-hotstuff-consensus-without-threshold-signatures.md)
- [ADR-0018: Static Peer Topology And Initial HotStuff Deployment Baseline](0018-static-peer-topology-and-initial-hotstuff-deployment-baseline.md)
- [ADR-0021: Snapshot Sync And Background Backfill From Best Finalized Suggestions](0021-snapshot-sync-and-background-backfill-from-best-finalized-suggestions.md)
- [ADR-0024: Static-Topology Peer Identity Binding And Session-Bound Capability Authorization](0024-static-topology-peer-identity-binding-and-session-bound-capability-authorization.md)
- [0002 - Sigilaris Node JVM Extraction](../plans/0002-sigilaris-node-jvm-extraction-plan.md)
- [0003 - Multiplexed Gossip Session Sync Plan](../plans/0003-multiplexed-gossip-session-sync-plan.md)
- [0004 - HotStuff Consensus Without Threshold Signatures Plan](../plans/0004-hotstuff-consensus-without-threshold-signatures-plan.md)
- [0007 - Snapshot Sync And Background Backfill Plan](../plans/0007-snapshot-sync-and-background-backfill-plan.md)
- [README](../../README.md)
