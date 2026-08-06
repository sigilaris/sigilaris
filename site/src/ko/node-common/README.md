# Node Common

`sigilaris-node-common`은 `sigilaris-core`와 runtime-specific node
implementation 사이에 놓인 shared node contract layer다. public surface는
cross-platform이며 `org.sigilaris.node.gossip`,
`org.sigilaris.node.gossip.tx`, `org.sigilaris.node.txpipeline` 아래에 있다.

## 현재 Baseline

- transport-neutral gossip/session contract
- static peer registry와 authenticator abstraction
- topic contract registry와 canonical rejection model
- producer-session, cursor, polling-compatibility, streaming state machinery
- runtime 간에 공유되는 transaction anti-entropy logic
- normalized transaction-pipeline model과 cross-platform
  `TxPipelineIdentityStrategy` contract

이 레이어의 목적은 gossip protocol contract와 shared runtime rule을 JVM 전용
transport/storage detail과 분리해 재사용 가능하게 유지하는 데 있다.

## 언제 직접 의존할까

아래가 필요하면 `sigilaris-node-common`에 직접 의존하면 된다.

- JVM과 Scala.js에서 공통으로 쓰는 gossip/session model
- topic contract와 artifact source/sink abstraction
- JVM runtime bundle 없이 재사용할 transaction anti-entropy logic

Armeria transport, Typesafe config loading, SwayDB helper, HotStuff runtime
assembly가 필요하면 [Node JVM](../node-jvm/README.md)으로 올라가면 된다.

## Transaction Pipeline Identity

`TxPipelineIdentityStrategy[F]`는 normalized request에서 하나의
`TxPipelineIdentity`를 계산한다. 결과는 durable `canonicalPayloadHash`와
`pipelineId`를 함께 담으며, admission은 replay 검사, record 생성, collision
convergence, idempotency alias binding에 같은 결과를 재사용해야 한다.

`TxPipelineIdentityStrategy.v1(identityScope)`는 JVM과 Scala.js 모두에서 기존
`bbgo.tx-pipeline.id.v1` preimage와 lowercase SHA-256 출력을 보존한다.
JVM은 platform `MessageDigest` provider를 사용하고 Scala.js 구현은 같은 vector로
고정된다. `waitFor`는 제외하고 명시적 scope를 포함하며, pipeline id는 `txp_`
뒤에 canonical payload hash를 붙여 만든다. Embedder는 custom strategy를 제공할
수 있지만 canonical hash는 normalized request와 identity scope에 대해
deterministic하고 collision-resistant해야 한다. Admission은 replay, alias binding,
idempotency key 없는 convergence에서 동일 hash를 동일 canonical request의 증거로
취급한다. Pipeline id와 hash/id pair도 안정적으로 유지해야 한다. 운영 중 strategy
변경은 durable identity 의미를 바꾸므로 coordinated rollout과 data compatibility
계획이 필요하다.

## 현재 제한 사항

- 이 레이어는 standalone daemon이나 transport implementation을 제공하지
  않는다.
- Static peer topology는 현재 runtime stack이 상속하는 baseline assumption이다.
- Detailed runtime packaging과 operator flow는 이 레이어 위에서 다뤄진다.

## 관련 페이지

- [Node JVM](../node-jvm/README.md)
- [API Reference](https://sigilaris.github.io/sigilaris/api/index.html)
