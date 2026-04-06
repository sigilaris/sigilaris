# ADR-0024: Static-Topology Peer Identity Binding And Session-Bound Capability Authorization

## Status
Proposed

## Context
- ADR-0016은 directional session, peer correlation id, control/event channel, canonical rejection class를 transport-neutral gossip substrate로 고정했고, peer authentication을 mandatory로 뒀다. 그러나 configured `PeerIdentity`와 실제 authenticated counterparty identity를 무엇으로 같은 principal로 인정할지, 그리고 snapshot/backfill 계열 child capability를 어떤 lifetime에 묶을지는 follow-up으로 남겼다.
- ADR-0018은 static peer topology, `knownPeers`, `directNeighbors`, validator / audit role, same-DC validator placement를 initial deployment baseline으로 accepted 했다. 하지만 이 문서는 어떤 transport/session principal이 configured peer와 동일한지, 또 bootstrap service family authorization이 peer-scoped인지 session-scoped인지는 직접 고정하지 않는다.
- ADR-0021은 `best finalized block suggestion`, snapshot trie node fetch, proposal replay/backfill, historical backfill을 runtime-owned, session-bound bootstrap service family로 고정했고, parent directional session 종료 또는 revoke 시 child bootstrap capability도 더 이상 새 data를 승인하지 않아야 한다고 결정했다.
- `2026-04-06` 기준 shipped JVM runtime에는 `PeerIdentity`, `StaticPeerTopology`, `StaticPeerAuthenticator`, `authorizeOpenSession`, `BootstrapSessionBinding(peer, sessionId)` seam이 landed 되어 있다. `HotStuffBootstrapArmeriaAdapter`는 bootstrap request마다 parent directional session이 여전히 open 인지 재검증한다.
- 그러나 문서 차원에서는 여전히 몇 가지 ambiguity가 남아 있다.
  - configured peer identity와 base URI / network address / HTTP caller를 같은 것으로 봐도 되는가
  - `knownPeers` 와 `directNeighbors` 중 무엇이 bootstrap service authorization을 소유하는가
  - same peer correlation id 아래 full re-handshake 로 복구된 새 directional session이 이전 bootstrap capability lineage를 승계하는가
  - transport adapter가 `sessionId` path만 보고 storage/runtime shortcut 을 열어도 되는가
- 이 ambiguity를 plan 문서나 adapter-local convention으로만 처리하면, static topology baseline 아래의 "trusted peer" 의미가 runtime, transport, bootstrap path마다 달라질 수 있다.
- 반대로 exact TLS certificate field, signed bearer capability format, HTTP header/path shape, dynamic discovery/scoring policy까지 지금 ADR에서 전부 잠그면 scope가 과도하게 넓어진다.
- 따라서 이번 phase의 목표는 static-topology baseline 아래의 canonical principal, session-scoped authorization ownership, parent-session revoke cascade를 semantic baseline으로 고정하고, exact transport credential / token / wire encoding detail은 후속 spec 또는 implementation plan으로 남기는 것이다.

## Decision
1. **configured `PeerIdentity`는 static-topology baseline의 canonical peer principal 이다.**
   - `knownPeers`, `directNeighbors`, peer correlation, session admission, child bootstrap capability ownership은 모두 configured `PeerIdentity`를 기준으로 판정한다.
   - network address, host:port, base URI, DNS name, Armeria route, TCP source address는 routing metadata일 수는 있어도 canonical peer principal 자체는 아니다.
   - 같은 configured `PeerIdentity`에 대응하는 transport endpoint가 바뀌어도, authenticated principal continuity가 유지되면 peer identity 자체가 바뀌었다고 보지 않는다.

2. **session-open admission은 authenticated counterparty identity를 configured `PeerIdentity`에 bind 해야 한다.**
   - local node는 inbound/outbound session-open을 처리할 때 "이 counterparty가 어느 configured peer 인가"를 canonical하게 resolve 해야 한다.
   - handshake payload 안의 `initiator` / `acceptor` field는 identity claim 이지, 그 자체로 sufficient trust root가 아니다.
   - address reachability, HTTP base URI possession, raw `sessionId` knowledge만으로 configured peer identity를 증명했다고 간주해서는 안 된다.
   - current shipped HTTP baseline은 static topology / application-level peer identity placeholder를 사용해 이 binding을 수행할 수 있다. stronger TLS / certificate / application credential binding은 follow-up spec 또는 implementation plan이 고정한다.

3. **`knownPeers` 와 `directNeighbors` 는 routing inventory 와 session authorization inventory로 분리된다.**
   - `knownPeers` 는 local config가 알고 있는 peer principal inventory 다.
   - `directNeighbors` 는 실제 directional session을 열고 유지할 수 있는 peer subset 이다.
   - static-topology baseline에서 outbound session-open, inbound session admission, runtime-owned bootstrap service family authorization은 모두 `directNeighbors` 기준으로 판정한다.
   - `knownPeers` 에만 있고 `directNeighbors` 에는 없는 peer는 routing/reference inventory 일 수는 있어도 current session authorization principal 은 아니다.

4. **directional session은 peer-scoped 가 아니라 parent authorization context 다.**
   - authenticated counterparty identity가 configured `PeerIdentity`에 bind 되더라도, bootstrap/replay/backfill authorization은 peer principal 하나만으로 충분하지 않다.
   - open 상태의 `DirectionalSessionId` 는 runtime이 발급하고 추적하는 parent authorization context 다.
   - `PeerCorrelationId` 는 관계 추적과 half-open recovery lineage를 돕지만, child capability authorization을 직접 대체하지 않는다.
   - same peer, same correlation id, same base URI 라도 different `DirectionalSessionId` 는 different authorization lineage다.

5. **bootstrap service family 는 session-bound child capability 로만 authorize 한다.**
   - `best finalized block suggestion`
   - snapshot trie node fetch
   - proposal replay/backfill
   - historical backfill
   - 위 서비스는 모두 `(configured peer identity, parent directional session id)` 또는 그와 동등한 runtime-owned binding 아래에서만 authorize 한다.
   - `BootstrapSessionBinding(peer, sessionId)` 또는 equivalent handle 은 peer-scoped convenience key가 아니라 parent session authorization handle 이다.

6. **runtime 이 authorization owner 이고 transport adapter 는 bypass 할 수 없다.**
   - transport adapter는 bootstrap request를 받더라도 parent directional session이 여전히 open 이고 authenticated context가 유효한지 runtime-owned authorization seam으로 재검증해야 한다.
   - `sessionId` path parameter parse success, `peerBaseUris` lookup success, HTTP route match, local storage availability는 authorization success가 아니다.
   - transport adapter는 bootstrap path에서 storage/runtime direct shortcut 으로 권한을 우회해서는 안 된다.
   - outbound transport에서 `PeerIdentity -> base URI` map은 routing hint 이지 authority source 가 아니다.

7. **parent-session revoke/close/dead/re-authentication failure 는 child capability authorization을 즉시 끊는다.**
   - parent directional session이 `closed` 또는 `dead` 로 전이되면 그 session에 종속된 bootstrap capability는 새 request를 승인해서는 안 된다.
   - runtime이 termination, revoke, explicit disconnect, 또는 re-authentication failure를 관측한 시점 이후의 child request는 canonical rejection 또는 explicit re-authentication flow로 전환해야 한다.
   - in-flight operation 은 termination 관측 이후 completion 을 보장하는 baseline 을 두지 않는다. abort 또는 explicit failure surface 가 canonical baseline 이다.

8. **half-open recovery 와 full re-handshake 는 새 authorization lineage를 만든다.**
   - same peer correlation id 아래에서 dead direction 이 fresh session id 로 full re-handshake 되어 `half-open -> open` recovery 를 달성하더라도, recovered session 은 old parent session을 재사용하지 않는다.
   - old session id 에서 파생된 bootstrap capability 는 same peer / same correlation id recovery 뒤에도 재사용될 수 없다.
   - recovering direction은 새 parent session authorization 아래에서 child capability 를 다시 획득해야 한다.

9. **이 ADR 은 static-topology baseline 아래의 identity/capability ownership만 고정한다.**
   - exact TLS certificate subject mapping, mutual TLS requirement, signed bearer capability token format, HTTP header/path/query shape, re-auth handshake payload 는 follow-up spec 또는 implementation plan이 고정한다.
   - dynamic discovery, peer scoring, validator admission, topology management, cross-topology trust migration은 이 ADR의 범위 밖에 둔다.
   - validator-set rotation continuity / bootstrap trust root precedence는 ADR-0023이 소유하고, pacemaker / leader rotation semantic baseline은 ADR-0022가 소유한다.

## Consequences
- static topology 아래의 "trusted peer" 판정이 address/base URI 가 아니라 configured peer principal 기준으로 고정된다.
- bootstrap transport 는 peer-only authorization 이 아니라 parent session authorization 을 재검증해야 하므로, old session id replay 나 stale bootstrap capability 재사용을 더 명확히 차단할 수 있다.
- half-open recovery 가 same peer correlation id 를 유지하더라도, child capability lineage 는 새 session 아래에서 다시 열어야 한다는 경계가 분명해진다.
- `ADR-0016` substrate 와 `ADR-0021` bootstrap service family 사이의 ownership이 정리되어, transport adapter가 storage direct path 로 새어 나갈 여지가 줄어든다.
- current shipped HTTP baseline 의 static/application-level peer identity placeholder 를 long-lived semantic slot 안에 위치시킬 수 있다.
- 대신 exact TLS/certificate binding, bearer capability format, re-auth payload, operator-facing credential UX 는 후속 spec 또는 implementation plan 작업이 계속 필요하다.

## Implementation Status
- `2026-04-06` 기준 shipped JVM runtime 은 `StaticPeerTopology`, `StaticPeerAuthenticator`, `TxGossipRuntime.authorizeOpenSession`, `BootstrapSessionBinding(peer, sessionId)` seam을 제공한다.
- shipped HTTP bootstrap transport 는 bootstrap request 마다 parent session open 상태를 runtime authorization 으로 재검증하고, disconnected parent session 에 대한 child request 를 canonical handshake rejection 으로 거절한다.
- current baseline 은 configured `PeerIdentity` placeholder 와 static topology admission 을 사용하며, stronger transport-cryptographic peer credential binding 은 아직 구현되지 않았다.

## Rejected Alternatives
1. **base URI 또는 network address 자체를 canonical peer identity 로 본다**
   - routing metadata 와 trust principal 이 섞인다.
   - same peer 의 endpoint 변경이나 proxy path 가 authority change 처럼 해석될 수 있다.

2. **bootstrap service family 를 peer principal 만으로 authorize 한다**
   - session lifetime 과 revoke cascade 가 분리된다.
   - stale parent session 아래에서 child bootstrap capability 가 재사용될 여지가 생긴다.

3. **same peer correlation id recovery 면 old bootstrap capability 를 계속 허용한다**
   - dead/closed parent session 과 fresh recovered session 의 authorization lineage 가 섞인다.
   - revoke/close cascade semantic 을 약화시킨다.

4. **transport adapter 가 `sessionId` parse 만으로 bootstrap storage path 를 직접 연다**
   - runtime-owned authorization ownership 이 무너진다.
   - session state, revoke, re-auth failure, canonical rejection handling 이 adapter shortcut 뒤로 숨어 버린다.

5. **dynamic discovery / scoring / validator admission policy 를 함께 고정한다**
   - static-topology identity binding ADR scope 를 과도하게 넓힌다.
   - deployment/discovery follow-up 과 authorization baseline 을 분리하는 편이 낫다.

## Follow-Up
- concrete TLS/application credential binding, `PeerIdentity` 와 transport credential subject 매핑, signed bearer capability token format, HTTP header/path/query shape 는 `docs/plans/0003-multiplexed-gossip-session-sync-plan.md` 또는 별도 protocol spec이 소유한다.
- bootstrap transport 와 remote fetch path 가 이 semantic baseline 을 concrete runtime/transport check 에 연결하는 작업은 `docs/plans/0007-snapshot-sync-and-background-backfill-plan.md`가 소유한다.
- dynamic discovery, peer scoring, topology management 가 static topology baseline 을 supersede 하려면 별도 deployment/discovery ADR이 필요하다.

## References
- [ADR-0016: Multiplexed Gossip Session Sync Substrate](0016-multiplexed-gossip-session-sync.md)
- [ADR-0018: Static Peer Topology And Initial HotStuff Deployment Baseline](0018-static-peer-topology-and-initial-hotstuff-deployment-baseline.md)
- [ADR-0021: Snapshot Sync And Background Backfill From Best Finalized Suggestions](0021-snapshot-sync-and-background-backfill-from-best-finalized-suggestions.md)
- [ADR-0022: HotStuff Pacemaker And View-Change Baseline](0022-hotstuff-pacemaker-and-view-change-baseline.md)
- [ADR-0023: Validator-Set Rotation And Bootstrap Trust Roots](0023-validator-set-rotation-and-bootstrap-trust-roots.md)
- [0003 - Multiplexed Gossip Session Sync Plan](../plans/0003-multiplexed-gossip-session-sync-plan.md)
- [0007 - Snapshot Sync And Background Backfill Plan](../plans/0007-snapshot-sync-and-background-backfill-plan.md)
- [0008 - Multi-Node Follow-Up ADR Authoring Plan](../plans/0008-multi-node-follow-up-adr-authoring-plan.md)
