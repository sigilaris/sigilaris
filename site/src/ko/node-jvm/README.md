# Node JVM

`sigilaris-node-jvm`은 `sigilaris-node-common` 위에 얹힌 JVM runtime bundle이다.
이 모듈은 현재 runtime lifecycle seam, config/bootstrap assembly, HotStuff
integration, Armeria HTTP transport adapter, SwayDB-backed persistence helper를
소유한다.

## 현재 Baseline

- `org.sigilaris.node.jvm.runtime.*` 아래의 runtime lifecycle seam
- static peer topology와 transport-auth configuration loader
- session open, event polling, control batch, bootstrap HTTP flow를 담당하는
  Armeria transport adapter
- HotStuff bootstrap, catch-up, pacemaker, artifact validation runtime
- 현재 durable baseline을 위한 SwayDB-backed storage helper

## 섹션 가이드

- [Bootstrap And Sync](bootstrap-and-sync.md)는 static trust-root verification,
  snapshot sync, historical backfill을 다룬다.
- [HotStuff And Pacemaker](hotstuff-and-pacemaker.md)는 proposal/vote/QC 흐름과
  timeout/new-view progression을 다룬다.
- [Static Launch](static-launch.md)는 reference smoke harness, minimal config
  shape, operator-owned startup/restart note를 다룬다.

## 현재 제한 사항

- Static peer topology와 static validator inventory가 여전히 배포 baseline이다.
- Restart, fencing, DR sequencing은 여전히 operator-managed다.
- 현재 public repo는 reference harness와 library runtime을 제공하지만,
  productized launcher/orchestrator는 제공하지 않는다.

## 후속 작업

- dynamic discovery와 peer scoring
- validator-set rotation과 더 넓은 trust-root policy 진화
- automatic failover와 remote signer/KMS integration

## 관련 페이지

- [Node Common](../node-common/README.md)
- [API Reference](https://sigilaris.github.io/sigilaris/api/index.html)
