# Bootstrap And Sync

이 페이지는 static-topology deployment를 위한 현재 JVM bootstrap 경로를 요약한다.
여기서 목표는 current public baseline을 설명하는 것이지, 완전한 operator
runbook을 제공하는 것은 아니다.

## 현재 Baseline

- static peer topology는 `sigilaris.node.gossip.peers`에서 읽는다
- HotStuff bootstrap policy는 `sigilaris.node.consensus.hotstuff`에서 읽는다
- bootstrap은 finalized-anchor suggestion discovery와 static trust-root
  verification에서 시작한다
- snapshot sync는 선택된 anchor에 맞춰 trie data를 가져오고 검증한다
- forward catch-up은 검증된 anchor에 고정된 상태로 normal runtime progression
  이전 구간을 메운다
- low-priority historical backfill이 live tip 뒤쪽 archive state를 채운다

## Non-compiling Configuration Sketch

```hocon
# Non-compiling configuration sketch
sigilaris.node.gossip.peers {
  local-node-identity = "node-a"
  known-peers = ["node-b", "node-c"]
  direct-neighbors = ["node-b", "node-c"]
  transport-auth.peer-secrets {
    node-a = "..."
    node-b = "..."
    node-c = "..."
  }
}

sigilaris.node.consensus.hotstuff {
  local-role = validator
  validators = [ ... ]
  key-holders = [ ... ]
  local-signers = [ ... ]
  historical-sync-enabled = true
  # optional:
  # bootstrap-trust-root = { ... }
  # historical-validator-sets = [ ... ]
}
```

## 현재 제한 사항

- Trust root는 optional extension을 따로 주지 않는 한 여전히 static
  validator-set baseline에 묶여 있다.
- Historical sync와 archive behavior는 별도 archival product가 아니라 runtime
  feature다.
- Startup은 모든 node에 같은 static peer inventory와 signer mapping이 미리
  provision돼 있다는 가정을 유지한다.

## 후속 작업

- validator-set rotation과 weak-subjectivity 계열 trust-root policy 확장
- narrative site 밖의 더 풍부한 operator-facing bootstrap 문서

## 관련 페이지

- [HotStuff And Pacemaker](hotstuff-and-pacemaker.md)
- [Static Launch](static-launch.md)
- [API Reference](https://sigilaris.github.io/sigilaris/api/index.html)
