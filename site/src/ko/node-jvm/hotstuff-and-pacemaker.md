# HotStuff And Pacemaker

이 페이지는 현재 `sigilaris-node-jvm`에 포함된 HotStuff runtime을 요약한다.

## 현재 Baseline

- proposal, vote, quorum-certificate artifact model이 JVM runtime package에
  존재한다
- consensus gossip은 exact-known window, bounded `requestById`,
  consensus-priority QoS를 사용한다
- canonical block modeling은 header/body/view artifact와 consensus-neutral
  block-store seam으로 나뉘어 있다
- pacemaker timeout-vote, timeout-certificate assembly, new-view progression이
  shipped runtime의 일부다
- deterministic leader activation, timer/backoff wiring, timeout/view-change
  advancement는 manual test hook이 아니라 runtime-owned baseline이다

## Runtime 경계

이 문서는 transport 문서와 의도적으로 분리돼 있다.

- gossip/session contract는 `sigilaris-node-common`에서 온다
- JVM transport adapter는 `org.sigilaris.node.jvm.transport.armeria` 아래에 있다
- consensus runtime과 validation은
  `org.sigilaris.node.jvm.runtime.consensus.hotstuff` 아래에 있다

즉 현재 저장소는 HotStuff logic을 runtime-owned로 두고, transport는 adapter
layer로 유지한다.

## 현재 제한 사항

- validator set은 여전히 static이다
- deployment model은 여전히 static-topology, same-DC 성격이 강하다
- runtime은 full operator product나 자동 orchestration 패키지로 제시되지
  않는다

## 후속 작업

- validator rotation과 더 넓은 trust-root 진화
- current operator-managed baseline을 넘어서는 recovery/failover automation
- runtime surface가 안정될 경우 장기 참조 문서 확장

## 관련 페이지

- [Bootstrap And Sync](bootstrap-and-sync.md)
- [Static Launch](static-launch.md)
- [API Reference](https://sigilaris.github.io/sigilaris/api/index.html)
