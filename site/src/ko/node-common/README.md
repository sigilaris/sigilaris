# Node Common

`sigilaris-node-common`은 `sigilaris-core`와 runtime-specific node
implementation 사이에 놓인 shared node contract layer다. public surface는
cross-platform이며 `org.sigilaris.node.gossip`,
`org.sigilaris.node.gossip.tx` 아래에 있다.

## 현재 Baseline

- transport-neutral gossip/session contract
- static peer registry와 authenticator abstraction
- topic contract registry와 canonical rejection model
- producer-session 및 polling state machinery
- runtime 간에 공유되는 transaction anti-entropy logic

이 레이어의 목적은 gossip protocol contract와 shared runtime rule을 JVM 전용
transport/storage detail과 분리해 재사용 가능하게 유지하는 데 있다.

## 언제 직접 의존할까

아래가 필요하면 `sigilaris-node-common`에 직접 의존하면 된다.

- JVM과 Scala.js에서 공통으로 쓰는 gossip/session model
- topic contract와 artifact source/sink abstraction
- JVM runtime bundle 없이 재사용할 transaction anti-entropy logic

Armeria transport, Typesafe config loading, SwayDB helper, HotStuff runtime
assembly가 필요하면 [Node JVM](../node-jvm/README.md)으로 올라가면 된다.

## 현재 제한 사항

- 이 레이어는 standalone daemon이나 transport implementation을 제공하지
  않는다.
- Static peer topology는 현재 runtime stack이 상속하는 baseline assumption이다.
- Detailed runtime packaging과 operator flow는 이 레이어 위에서 다뤄진다.

## 관련 페이지

- [Node JVM](../node-jvm/README.md)
- [API Reference](https://sigilaris.github.io/sigilaris/api/index.html)
