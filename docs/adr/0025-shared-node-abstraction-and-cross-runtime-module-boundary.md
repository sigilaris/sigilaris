# ADR-0025: Shared Node Abstraction And Cross-Runtime Module Boundary

## Status
Accepted

## Acceptance Date
2026-04-14

## Context
- ADR-0015는 reusable JVM node infrastructure를 `sigilaris-node-jvm`으로 추출하고, 의존 방향을 `sigilaris-core` <- `sigilaris-node-jvm`으로 고정했다. 동시에 Cloudflare Workers 같은 두 번째 runtime 구현 압력이 생기면 shared node abstraction을 재검토한다고 명시했다.
- ADR-0016은 gossip/session substrate를 transport-neutral runtime/service contract로 고정했고, ADR-0021과 ADR-0024는 bootstrap service family 및 peer/session authorization ownership을 transport shortcut 이 아니라 runtime-owned seam에 두도록 정리했다. 즉, current node surface 안에는 이미 JVM adapter보다 더 상위의 shared contract 후보가 존재한다.
- ADR-0018은 future runtime naming을 `sigilaris-node-<runtime>` 형식으로 열어 두었다. 따라서 이번 문제는 단순 package cleanup 이 아니라 future runtime plurality 를 수용할 shared node layer owner를 정하는 문제다.
- `2026-04-09` 기준 repository는 이미 cross-project `sigilaris-core`와 JVM 전용 `sigilaris-node-jvm`을 함께 갖고 있다. `sigilaris-node-jvm` 내부에는 `runtime`, `transport`, `storage` seam이 존재하고, gossip/session substrate, bootstrap service family, HotStuff runtime, Armeria transport adapter가 그 위에 올라가 있다.
- 그러나 현재 경계에는 새 압력이 생겼다.
  - gossip/session/bootstrap 쪽에는 transport-neutral 또는 runtime-neutral 성격을 가진 node contract가 존재한다.
  - 일부 HTTP contract는 JVM module 안에서 Tapir endpoint definition과 Armeria server interpreter가 같이 묶여 있다.
  - future Scala.js client 또는 Cloudflare Workers runtime이 같은 node contract를 재사용하려면, 현재처럼 JVM module 안에 묶인 상태로는 재사용이 어렵다.
- 이 상황에서 단순히 shared node abstraction을 `sigilaris-core`로 올리면 다른 문제가 생긴다.
  - `sigilaris-core`는 application, codec, crypto, datatype, merkle 같은 blockchain primitive와 application-neutral library surface를 소유한다.
  - peer/session/gossip/bootstrap/node lifecycle 같은 개념은 blockchain primitive 라기보다 node execution concern이다.
  - `sigilaris-core`에 node abstraction을 올리면 core가 transport/storage/node-runtime concern과 다시 섞이기 시작한다.
- 반대로 모든 shared contract를 계속 `sigilaris-node-jvm`에 남기면 future runtime은 둘 중 하나를 강요받는다.
  - JVM-only bundle을 비정상적으로 참조한다.
  - 또는 같은 contract를 다른 runtime module에서 중복 정의한다.
- 또한 Cloudflare Workers / Durable Objects 같은 runtime은 serverless actor model, fetch/WebSocket/alarm 기반 transport, Scala.js client/backend reuse 같은 별도 integration pressure를 만든다. 이 pressure는 Armeria/SwayDB opinionated JVM bundle과는 다른 층위의 concern이다.
- 따라서 이번 ADR의 목표는 `core`를 오염시키지 않으면서, JVM과 future non-JVM runtime이 함께 소비할 shared node abstraction의 owner와 module boundary를 고정하는 것이다.

## Decision
1. **shared node abstraction은 `sigilaris-core`가 아니라 새 cross-project module `sigilaris-node-common`이 소유한다.**
   - canonical baseline은 sbt `crossProject` 기반의 JVM + JS shared node layer다.
   - 이 module은 execution runtime 그 자체가 아니라 cross-runtime reusable node contract layer다.
   - `sigilaris-core`는 계속 application-neutral primitive library로 유지한다.
   - Scala Native 같은 추가 target은 future pressure 가 생길 때 재검토할 수 있지만, 이 ADR이 잠그는 최소 baseline target은 JVM + JS 다.

2. **canonical dependency graph는 `sigilaris-core` <- `sigilaris-node-common` <- runtime-specific node modules 로 고정한다.**
   - current JVM baseline은 `sigilaris-core` <- `sigilaris-node-common` <- `sigilaris-node-jvm` 형태를 목표로 한다.
   - future runtime은 같은 규칙으로 `sigilaris-node-<runtime>` 형식을 따른다.
   - 예: Cloudflare Workers baseline 이 추가되면 `sigilaris-core` <- `sigilaris-node-common` <- `sigilaris-node-cloudflare-workers` 형태를 사용한다.
   - `sigilaris-core`는 `sigilaris-node-common` 또는 runtime-specific module에 reverse dependency를 만들지 않는다.

3. **`sigilaris-core`는 node-agnostic boundary를 유지한다.**
   - peer session, gossip substrate, bootstrap transport contract, node lifecycle, peer topology, runtime-owned node service contract 같은 개념은 `core`에 올리지 않는다.
   - `core`는 transaction/application/security primitive, codec, crypto, deterministic data model처럼 node 없이도 의미가 있는 library concern만 소유한다.
   - node concern을 `core`로 이동하는 것은 ADR-0015가 유지한 `sigilaris-core`의 transport/storage/node-runtime 비의존 원칙을 깨는 것으로 본다.

4. **`sigilaris-node-common`은 cross-runtime semantic value가 있는 transport-neutral node contract를 소유한다.**
   - litmus test:
     - 해당 type 또는 package가 Armeria, SwayDB, Workers binding, Java-only runtime API 같은 runtime-specific or platform-specific dependency를 transitively 요구하지 않아야 한다.
     - 둘 이상의 runtime module 또는 runtime-specific client/server assembly가 같은 semantic contract를 공유해야 한다.
   - 위 두 조건을 동시에 만족할 때만 `sigilaris-node-common` candidate 로 본다.
   - 예시 범위:
     - peer/session/gossip/bootstrap protocol model
     - cursor, filter, control batch, canonical rejection class, peer/session identifier 같은 runtime-owned model
     - runtime-owned service contract와 transport-neutral interface
     - 둘 이상의 runtime이 함께 소비해야 하는 wire DTO / codec helper
     - server/client 양쪽이 재사용해야 하는 HTTP-level request/response contract
   - 위 개념은 JVM implementation detail이나 Cloudflare-specific platform API가 아니라 node substrate concern으로 취급한다.

5. **runtime-specific module은 concrete runtime, transport, storage, platform integration을 소유한다.**
   - `sigilaris-node-jvm`은 Armeria, SwayDB, concrete bootstrap/server wiring, OpenAPI export, JVM-specific persistence/runtime handle을 소유한다.
   - future `sigilaris-node-cloudflare-workers`는 Durable Object, Workers alarm/fetch/WebSocket, edge storage/wiring, Worker entrypoint assembly를 소유한다.
   - runtime-specific module은 shared node contract를 consume 하지만, 그 contract owner가 되지 않는다.

6. **shared package namespace는 `core` namespace가 아니라 `node` namespace를 사용한다.**
   - shared node abstraction의 canonical package root는 `org.sigilaris.node` 계열로 둔다.
   - current JVM-specific implementation package는 `org.sigilaris.node.jvm` 아래에 유지한다.
   - exact file/package move sequence는 implementation plan이 고정하지만, shared node contract를 `org.sigilaris.core` 아래로 흡수하지 않는다는 점은 이 ADR에서 잠근다.

7. **shared HTTP contract는 cross-platform description layer만 `sigilaris-node-common`에 둘 수 있다.**
   - HTTP transport contract를 declarative DSL로 공유해야 한다면, shared module에는 endpoint shape 자체만 둔다.
   - server interpreter, client backend, OpenAPI exporter, runtime-specific transport glue는 shared module에 두지 않는다.
   - current stack에서 Tapir를 계속 사용하더라도, shared module에 Tapir definition layer를 둘 수 있는지는 exact Tapir module availability 가 `sigilaris-node-common`의 JVM + JS target 모두에서 성립할 때만 허용한다.
   - Tapir official platform documentation 은 Scala.js support 가 selected modules 에 한정된다고 명시하므로, shared module은 "Tapir면 무조건 가능"하다고 가정하지 않는다.
   - 위 availability 검증이 실패하면 shared HTTP contract는 `sigilaris-node-common`에서 DTO / codec / semantic request-response model 로만 유지하고, Tapir endpoint definitions 자체는 runtime-specific module에 남긴다.
   - 즉 endpoint descriptions, shared codecs, shared schema는 `sigilaris-node-common` candidate 일 수 있지만 `tapir-armeria-server-*`, OpenAPI export helper, Armeria service assembly, sttp backend instantiation 같은 runtime-specific integration은 각 runtime module에 남긴다.

8. **`sigilaris-node-common`은 shared abstraction layer이지 premature ecosystem split이 아니다.**
   - 이 결정은 `transport-*`, `storage-*`, `runtime-*` artifact를 즉시 세분화하겠다는 뜻이 아니다.
   - current `sigilaris-node-jvm`은 여전히 opinionated JVM node bundle로 남을 수 있다.
   - `sigilaris-node-common`에는 실제 cross-runtime reuse 압력이 있는 concept만 올리고, JVM-only convenience helper까지 모두 분리하는 것은 요구하지 않는다.

9. **future Workers support는 runtime-specific module로 수용하고, shared abstraction은 그 prerequisite로 본다.**
   - Cloudflare Workers / Durable Objects support가 필요해져도 `sigilaris-core`에 edge-runtime concern을 직접 넣지 않는다.
   - Workers baseline은 shared node contract를 consume 하는 별도 runtime module로 설계한다.
   - Durable Object actor ownership, alarm-based retry, fetch/WebSocket adapter, Worker entrypoint는 runtime-specific implementation concern이다.

## Consequences
- `sigilaris-core`의 node-agnostic 성격을 유지하면서도, JVM과 future JS/Workers runtime이 공유할 node contract의 명확한 owner가 생긴다.
- future Scala.js client 또는 Cloudflare Workers runtime이 JVM-only module 없이 gossip/bootstrap/session contract를 직접 재사용할 수 있다.
- shared HTTP contract를 재사용해야 할 때, endpoint shape와 runtime-specific interpreter/backend를 다른 module에 두는 구조가 가능해진다.
- `sigilaris-node-jvm` 안에 섞여 있던 transport-neutral contract 일부는 `sigilaris-node-common`으로 이동해야 하므로 package 이동과 import 정리가 필요하다.
- build graph와 published artifact surface가 한 단계 늘어난다.
- import rule / dependency rule도 새 경계에 맞춰 확장해야 한다.
- 어떤 type이 truly cross-runtime reusable 인지, 어떤 type이 JVM-only runtime concern인지 inventory가 필요해진다.

## Implementation Status
- `2026-04-14` 기준 repository에는 cross-project `sigilaris-core`, cross-project `sigilaris-node-common`, JVM-only `sigilaris-node-jvm`이 존재한다.
- transport-neutral gossip/session/bootstrap contract와 tx anti-entropy runtime logic의 first extraction slice는 `org.sigilaris.node.gossip` / `org.sigilaris.node.gossip.tx` 아래로 이동했다.
- `sigilaris-node-jvm`은 extracted contract를 consume 하고, Typesafe Config loader / JVM bootstrap assembly / Armeria transport adapter / SwayDB integration 같은 runtime-specific code를 계속 소유한다.
- shared HTTP contract는 first implementation에서 DTO / codec / semantic-model fallback 으로 잠겼고, Tapir endpoint definitions 자체는 `sigilaris-node-jvm`에 남아 있다.

## Rejected Alternatives
1. **shared node abstraction을 `sigilaris-core`로 올린다**
   - `core`가 다시 node-runtime concern을 흡수하게 된다.
   - application-neutral primitive library와 node execution abstraction의 경계가 흐려진다.
   - ADR-0015가 유지한 `sigilaris-core` 비의존 원칙과 어긋난다.

2. **shared contract를 계속 `sigilaris-node-jvm` 안에 둔다**
   - future JS/Workers runtime이 JVM-only bundle을 참조하거나 contract를 복제해야 한다.
   - cross-runtime client/server contract 재사용 이점이 사라진다.

3. **처음부터 `transport-*`, `storage-*`, `runtime-*` artifact로 세분화한다**
   - 현재 pressure의 핵심은 adapter ecosystem이 아니라 shared node abstraction의 owner 부재다.
   - immediate goal보다 artifact/packaging complexity가 먼저 커진다.

4. **future `sigilaris-node-cloudflare-workers`가 실제로 생길 때까지 경계 결정을 미룬다**
   - shared contract가 계속 JVM package에 고착된다.
   - plan이나 implementation patch가 임시로 architecture decision 역할을 떠안게 된다.
   - Tapir contract, wire DTO, session model 같은 shared surface의 drift 위험이 커진다.

## Follow-Up
- remaining consensus/runtime/shared candidate inventory 확장은 explicit future work 로 남기고, 새 extraction batch 가 승인될 때 별도 plan 으로 기록한다.
- shared HTTP contract가 실제 cross-runtime consumer 를 갖게 되면 DTO / codec / semantic model extraction 범위를 재검토한다.
- future `sigilaris-node-cloudflare-workers` runtime plan 에서 `sigilaris-node-common` surface 를 재사용 기준으로 삼는다.
- 필요 시 `sigilaris-node-common` public API curation / compatibility policy 를 문서화한다.
- first extraction batch implementation handoff 의 canonical record 는 [0010 - Node Common Extraction And Cross-Runtime Contract Plan](../plans/0010-node-common-extraction-and-cross-runtime-contract-plan.md) 이 소유한다.

## References
- [ADR-0015: JVM Node Bundle Boundary And Packaging](0015-jvm-node-bundle-boundary-and-packaging.md)
- [ADR-0016: Multiplexed Gossip Session Sync Substrate](0016-multiplexed-gossip-session-sync.md)
- [ADR-0018: Static Peer Topology And Initial HotStuff Deployment Baseline](0018-static-peer-topology-and-initial-hotstuff-deployment-baseline.md)
- [ADR-0021: Snapshot Sync And Background Backfill From Best Finalized Suggestions](0021-snapshot-sync-and-background-backfill-from-best-finalized-suggestions.md)
- [ADR-0024: Static-Topology Peer Identity Binding And Session-Bound Capability Authorization](0024-static-topology-peer-identity-binding-and-session-bound-capability-authorization.md)
- [0002 - Sigilaris Node JVM Extraction](../plans/0002-sigilaris-node-jvm-extraction-plan.md)
- [0008 - Multi-Node Follow-Up ADR Authoring Plan](../plans/0008-multi-node-follow-up-adr-authoring-plan.md)
- [0010 - Node Common Extraction And Cross-Runtime Contract Plan](../plans/0010-node-common-extraction-and-cross-runtime-contract-plan.md)
