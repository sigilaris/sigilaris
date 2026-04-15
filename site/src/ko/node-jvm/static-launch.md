# Static Launch

현재 public repository는 reference static launch baseline과 repo-local smoke
harness를 함께 제공한다. 이 문서는 그 baseline을 설명하는 narrative
overview이며, 완전한 production operations manual은 아니다.

## Reference Smoke

현재 launch proof는 다음 명령으로 실행한다.

```bash
sbt "testOnly org.sigilaris.node.jvm.runtime.consensus.hotstuff.HotStuffLaunchSmokeSuite"
```

이 smoke harness는 static mesh 형성, stalled leader 이후 HotStuff progress,
newcomer bootstrap, archive reopen, same-validator relocation flow를 검증한다.

## Non-compiling Configuration Sketch

```hocon
# Non-compiling configuration sketch
sigilaris.node.gossip.peers {
  local-node-identity = "node-a"
  known-peers = ["node-b", "node-c", "node-d"]
  direct-neighbors = ["node-b", "node-c", "node-d"]
  transport-auth.peer-secrets { ... }
}

sigilaris.node.consensus.hotstuff {
  local-role = validator
  validators = [ ... ]
  key-holders = [ ... ]
  local-signers = [ ... ]
  historical-sync-enabled = true
}
```

## Operator-Owned Baseline

- 모든 node에 같은 validator inventory, holder mapping, peer graph,
  peer secret를 먼저 provision한다
- validator holder를 audit/newcomer node보다 먼저 시작한다
- config drift, signer mismatch, missing topic registration은 startup failure로
  취급한다
- DR relocation restart 전에 old validator holder를 먼저 fence한다

## 현재 제한 사항

- launch flow는 여전히 operator-managed다
- harness는 baseline을 증명하지만 packaged daemon/orchestrator는 아니다
- dynamic discovery, validator rotation, automatic failover는 현재 baseline
  밖에 있다

## 관련 페이지

- [Bootstrap And Sync](bootstrap-and-sync.md)
- [HotStuff And Pacemaker](hotstuff-and-pacemaker.md)
- [API Reference](https://sigilaris.github.io/sigilaris/api/index.html)
