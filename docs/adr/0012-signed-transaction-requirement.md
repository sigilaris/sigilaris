# ADR 0012: Signed Transaction Requirement

## Status

Proposed

## Context

블록체인 시스템에서는 모든 트랜잭션의 무결성과 출처 검증이 필수적이다. 트랜잭션이 블록체인에 전달되기 전에 서명이 이루어져야 하며, 이는 다음과 같은 이유로 중요하다:

1. **신원 확인**: 트랜잭션을 생성한 계정의 신원을 암호학적으로 보장
2. **무결성 보장**: 트랜잭션 내용이 전송 중 변조되지 않았음을 보장
3. **부인 방지**: 트랜잭션 생성자가 나중에 해당 행위를 부인할 수 없도록 보장
4. **권한 검증**: 해당 계정이 트랜잭션을 수행할 권한이 있음을 증명

현재 시스템에서는 트랜잭션 서명이 선택적이거나 런타임에 검증되는 구조로, 컴파일 타임에 서명 누락을 발견할 수 없는 문제가 있다.

## Decision

모든 블록체인 트랜잭션은 `Signed[A <: Tx]` 타입으로 래핑되어야 하며, 이를 타입 시스템을 통해 강제한다.

```scala
import org.sigilaris.core.crypto.Signature
import org.sigilaris.core.application.accounts.Account

final case class AccountSignature(
  account: Account,
  sig: Signature
)

final case class Signed[+A <: Tx](
  sig: AccountSignature,
  value: A
)
```

### 핵심 원칙

1. **타입 레벨 강제**: `Signed[+A <: Tx]` 타입을 통해 컴파일 타임에 서명 존재를 보장
2. **서명 정보 포함**: 서명과 함께 서명한 계정 정보를 `AccountSignature`로 묶어 관리
3. **공변 설계**: `Signed[+A <: Tx]`의 공변 구조로 `List[Signed[Tx & ModuleRoutedTx]]` 등 실제 blueprint API와 조합 가능
4. **명시적 구조**: 서명된 트랜잭션과 서명되지 않은 데이터를 타입으로 명확히 구분

### 서명 대상 바이트 페이로드 (Sign-Bytes Contract)

서명 검증의 상호운용성을 보장하기 위해, 서명 대상은 `Hash.ops.toHash`를 사용한 트랜잭션의 해시이며, 검증은 `Recover.ops.recover`로 복구한 공개키의 KeyId20을 계정의 키와 비교한다:

```scala
import org.sigilaris.core.failure.SigilarisFailure
import org.sigilaris.core.crypto
import crypto.{Hash, Sign, Recover}
import crypto.Hash.ops.*
import crypto.Sign.ops.*
import crypto.Recover.ops.*
import crypto.{Signature, PublicKey, KeyPair}
import org.sigilaris.core.application.accounts.{Account, KeyId20}
import org.sigilaris.core.datatype.UInt256

// 서명 생성
def sign[A <: Tx](
  tx: A,
  keyPair: KeyPair,
  account: Account
)(using Hash[A], Sign[A]): Either[SigilarisFailure, Signed[A]] =
  val hash = tx.toHash  // Hash.ops를 통한 Keccak-256 해시
  for
    sig <- hash.signBy(keyPair)  // Sign.ops를 통한 서명, Either 반환
  yield Signed(AccountSignature(account, sig), tx)

// 서명 검증 - recover 방식
def verify[A <: Tx](signed: Signed[A])(using Hash[A], Recover[A]): Either[SigilarisFailure, Boolean] =
  val hash = signed.value.toHash
  for
    recoveredPubKey <- hash.recover(signed.sig.sig)  // Recover.ops를 통한 공개키 복구
    keyId20 = deriveKeyId20(recoveredPubKey)         // 복구된 공개키로부터 KeyId20 유도
    // 계정이 해당 키를 가지고 있는지 확인 (StateReducer 내부에서 수행)
    hasKey <- checkAccountHasKey(signed.sig.account, keyId20)
  yield hasKey

// PublicKey로부터 KeyId20 유도 (Ethereum 방식)
def deriveKeyId20(pubKey: PublicKey): KeyId20 =
  val pubKeyHash = pubKey.toHash  // Keccak-256(64-byte uncompressed public key)
  KeyId20.unsafeApply(pubKeyHash.toUInt256.bytes.takeRight(20))  // 마지막 20바이트
```

**Canonicalization 규칙**:
1. 트랜잭션 `A`를 `ByteEncoder[A]`로 인코딩 (implicit given)
2. `Hash.ops.toHash`를 통해 Keccak-256 해시 (`Hash.Value[A]`) 생성
3. 해시를 서명 대상으로 사용

**서명 검증 프로토콜**:
1. 트랜잭션의 해시 재계산: `signed.value.toHash`
2. 서명으로부터 공개키 복구: `hash.recover(signature)`
3. 복구된 공개키로부터 KeyId20 유도: `Keccak256(publicKey)[12..32]` (마지막 20바이트)
4. 계정의 키 저장소에서 해당 KeyId20 존재 여부 확인

이 방식은 ECDSA의 공개키 복구 기능을 활용하여, 서명에 공개키를 명시적으로 포함하지 않고도 검증이 가능하다.

**ADR-0009 연계**:
- `Hash.build[A]`는 `ByteEncoder[A]`를 기반으로 자동 생성
- ADR-0009에서 정의한 머클 트라이 인코딩 규칙과 동일한 `ByteEncoder` 인스턴스 사용
- 길이 접두(length-prefix) 및 구조화된 인코딩 규칙을 따라 바이트 레벨 호환성 보장

**ADR-0010 연계**:
- Named 계정은 KeyId20 → KeyInfo 테이블을 통해 키 검증
- Unnamed 계정은 계정 식별자 자체가 KeyId20이므로 직접 비교

### 적용 범위

- 블록체인에 제출되는 모든 트랜잭션 타입
- 계정 상태 변경을 요구하는 모든 작업
- 블록체인 레이어로 전달되는 모든 사용자 요청

## Consequences

### Positive

1. **컴파일 타임 안전성**: 서명되지 않은 트랜잭션이 블록체인 레이어에 전달되는 것을 컴파일 타임에 방지
2. **명확한 인터페이스**: API 타입 시그니처만으로 서명 요구사항이 명확히 드러남
3. **타입 주도 개발**: 타입 시스템이 올바른 사용 패턴을 가이드
4. **보안 강화**: 서명 누락으로 인한 보안 취약점을 원천 차단
5. **문서화 효과**: 코드 자체가 서명 요구사항에 대한 명확한 문서 역할
6. **리팩토링 안전성**: 서명 관련 코드 변경 시 타입 체커가 영향받는 모든 코드를 찾아줌
7. **상호운용성 보장**: 명시적 canonicalization 규칙으로 모든 클라이언트가 동일한 서명 생성
8. **공변성 지원**: `Signed[+A <: Tx]`로 `Signed[CreateNamedAccount]`를 `Signed[Tx & ModuleRoutedTx]` 컨텍스트에서 사용 가능

### Negative

1. **타입 래핑 오버헤드**: 모든 트랜잭션을 `Signed[A]`로 래핑해야 하는 추가 작업
2. **API 복잡도 증가**: 함수 시그니처가 `Signed[Transaction]` 형태로 더 복잡해짐
3. **기존 코드 마이그레이션**: 현재 서명되지 않은 트랜잭션을 처리하는 코드의 수정 필요
4. **테스트 복잡도**: 테스트 시에도 항상 서명된 트랜잭션을 생성해야 함
5. **유연성 제약**: 특수한 경우(예: 시스템 트랜잭션) 처리를 위한 추가 설계 필요
6. **ByteEncoder 의존성**: 서명 검증이 `ByteEncoder` 인스턴스의 정확성에 의존하며, 인코더 변경 시 호환성 문제 발생 가능

### Mitigation

- 테스트용 헬퍼 함수 제공으로 테스트 작성 부담 경감:
  ```scala
  import org.sigilaris.core.failure.SigilarisFailure
  import org.sigilaris.core.crypto
  import crypto.{Hash, Sign}
  import crypto.Hash.ops.*
  import crypto.Sign.ops.*
  import crypto.{Signature, KeyPair}
  import org.sigilaris.core.application.accounts.Account

  object TestHelpers:
    // Either를 반환하는 안전한 버전
    def signedTx[A <: Tx](
      tx: A,
      keyPair: KeyPair,
      account: Account
    )(using Hash[A], Sign[A]): Either[SigilarisFailure, Signed[A]] =
      sign(tx, keyPair, account)

    // 테스트에서 예외 발생을 허용하는 unsafe 버전
    def signedTxUnsafe[A <: Tx](
      tx: A,
      keyPair: KeyPair,
      account: Account
    )(using Hash[A], Sign[A]): Signed[A] =
      sign(tx, keyPair, account).fold(
        err => throw new RuntimeException(s"Failed to sign tx: ${err.msg}"),
        identity
      )
  ```
- 서명 생성/검증 유틸리티 함수 제공으로 API 사용 편의성 향상
- 시스템 트랜잭션 등 특수 케이스를 위한 별도 타입 정의 (예: `SystemTx`)
- `ByteEncoder` 버전 관리 및 마이그레이션 전략 수립 (codec version field 추가 고려)

## References

- [ADR 0009: Blockchain Application Architecture](0009-blockchain-application-architecture.md)
- [crypto package documentation](../api/crypto.md)
- Ethereum's signed transaction model
- Bitcoin's transaction signature scheme
