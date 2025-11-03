# Accounts 모듈 (ADR-0010)

[← 개요](README.md) | [English →](../../en/application/accounts.md)

---

## 개요

Accounts 모듈은 사람이 읽을 수 있는 이름과 키 관리를 갖춘 블록체인 계정 모델을 구현합니다. ADR-0010을 기반으로, 가디언 기반 키 복구를 지원하는 Named Account와 경량 사용을 위한 Unnamed Account를 제공합니다.

**주요 기능:**
- **Named Accounts**: 여러 공개키를 가진 UTF-8 이름
- **Unnamed Accounts**: 온체인 상태 없이 KeyId20 기반 계정
- **Guardian 복구**: 서비스 운영자가 오프체인 KYC를 통해 키 복구 지원
- **Nonce 보호**: 상태 변경 트랜잭션의 재생 공격 방지

## 계정 타입

### Named Account

UTF-8 문자열 이름으로 식별되며, 여러 공개키를 지원하고 가디언을 통한 키 복구를 가능하게 합니다.

**속성:**
- 이름은 온체인에 완전 공개 (블록 익스플로러에 표시)
- 계정당 하나의 가디언 (선택사항)
- 재생 공격 방지를 위한 Nonce
- 메타데이터와 함께 여러 키 등록 가능

**사용 사례:**
- 키 로테이션이 필요한 서비스 계정
- 복구 메커니즘이 필요한 계정
- UX를 위한 사람이 읽을 수 있는 주소

### Unnamed Account

온체인 계정 상태 없이 KeyId20(20바이트 공개키 해시)로만 식별됩니다.

**속성:**
- 온체인 계정 메타데이터 없음
- UTXO 모델에서만 잔고 추적
- 개인키 분실 시 복구 불가능
- 일회성 또는 임시 사용을 위한 경량

**사용 사례:**
- 일회성 결제 주소
- 임시 테스트 계정
- 프라이버시 중심의 경량 계정

## 공개키 식별자 (KeyId20)

Ethereum의 주소 생성 방식을 사용합니다:
- 64바이트 공개키의 Keccak256 해시 계산
- 마지막 20바이트를 KeyId20로 사용
- 충돌 위험은 수용 가능 (Ethereum에서 검증됨)

**예시:**
```
공개키:    04 <32바이트 X> <32바이트 Y>  (64바이트 압축 해제)
해시:      Keccak256(64바이트)          (32바이트)
KeyId20:   해시의 마지막 20바이트         (20바이트)
```

## 상태 스키마

### 계정 정보
```scala
Entry["accountInfo", Name, AccountInfo]
```

**AccountInfo** 필드:
- `guardian: Option[Account]` - 단일 가디언 (None일 수 있음)
- `nonce: BigNat` - 상태 변경 트랜잭션용 순차 번호 (정확히 +1 증가)

### 키 등록 (Named Account 전용)
```scala
Entry["nameKey", (Name, KeyId20), KeyInfo]
```

**KeyInfo** 필드:
- `addedAt: Instant` - 키 추가 시각
- `expiresAt: Option[Instant]` - 선택적 만료 시각
- `description: Utf8` - 키에 대한 사람이 읽을 수 있는 설명

### 잔고 (UTXO 모델)
UTXO 모듈에서 별도 관리:
```scala
Entry["utxo", (AccountId, TokenDefId, TokenId, UTXOHash), Unit]
```

`AccountId`는 Named Account의 경우 `Name`, Unnamed Account의 경우 `KeyId20`입니다.

## 트랜잭션

모든 계정 트랜잭션은 공통 필드가 있는 **Envelope**를 포함합니다:
- `networkId: BigNat` - 체인/네트워크 식별자
- `createdAt: Instant` - 트랜잭션 생성 시각
- `memo: Option[Utf8]` - 감사/운영 목적의 선택적 메모

서명은 Envelope를 포함한 전체 페이로드의 해시 위에서 검증됩니다.

### CreateNamedAccount

초기 키와 선택적 가디언으로 새 Named Account를 생성합니다.

**파라미터:**
- `name: Name` - 계정 이름 (UTF-8 문자열)
- `initialKeyId: KeyId20` - 초기 공개키 식별자
- `guardian: Option[Account]` - 선택적 단일 가디언

**서명:** `initialKeyId`에 대응하는 개인키

**사전조건:**
- 계정 상태에 `name`이 존재하지 않아야 함
- `name`이 유효한 UTF-8이어야 함

**사후조건:**
- `name → AccountInfo(guardian, nonce=0)` 생성
- `(name, initialKeyId) → KeyInfo(now, None, "")` 생성

### UpdateAccount

계정 정보 업데이트 (가디언 변경).

**파라미터:**
- `name: Name`
- `nonce: BigNat`
- `newGuardian: Option[Account]`

**서명:** 이 계정에 등록된 키 중 하나, 또는 가디언

**사전조건:**
- 계정 존재
- `nonce`가 현재 계정 nonce와 일치

**사후조건:**
- `AccountInfo.guardian` 업데이트 (설정/변경/제거)
- `AccountInfo.nonce` 증가

### AddKeyIds

Named Account에 새 공개키를 등록합니다.

**파라미터:**
- `name: Name`
- `nonce: BigNat`
- `keyIds: Map[KeyId20, Utf8]` - 키 식별자와 설명
- `expiresAt: Option[Instant]` - 모든 새 키에 적용할 선택적 만료 시각

**서명:** 이 계정에 등록된 키 중 하나, 또는 가디언

**사전조건:**
- 계정 존재
- `nonce`가 현재 계정 nonce와 일치
- 각 `keyId`가 이 계정에 미등록 상태여야 함

**사후조건:**
- 각 키에 대해 `(name, keyId) → KeyInfo(addedAt=now, expiresAt, description)` 생성
- `AccountInfo.nonce` 증가

### RemoveKeyIds

Named Account에서 공개키를 제거합니다.

**파라미터:**
- `name: Name`
- `nonce: BigNat`
- `keyIds: Set[KeyId20]`

**서명:** 이 계정에 등록된 키 중 하나, 또는 가디언

**사전조건:**
- 계정 존재
- `nonce`가 현재 계정 nonce와 일치
- 각 `keyId`가 이 계정에 등록되어 있어야 함

**사후조건:**
- `(name, keyId)` 키 정보 엔트리 제거
- `AccountInfo.nonce` 증가
- 마지막 키까지 제거 가능 (잔고는 UTXO로 별도 관리)

### RemoveAccount

Named Account를 삭제합니다.

**파라미터:**
- `name: Name`
- `nonce: BigNat`

**서명:** 이 계정에 등록된 키 중 하나, 또는 가디언

**사전조건:**
- 계정 존재
- `nonce`가 현재 계정 nonce와 일치
- 잔고 확인은 모듈 경계에서 강제하지 않음 (운영 정책으로 보장)

**사후조건:**
- 상태에서 계정 제거
- 모든 `(name, *)` 키 정보 제거
- `AccountInfo.nonce` 증가 (삭제 전)
- UTXO 잔고는 영향받지 않음 (접근 불가능해짐)

## Guardian 시스템

**Guardian 역할:**
- 서비스 운영자가 가디언 역할 수행
- 오프체인 KYC를 통한 키 복구 지원
- 계정 소유자를 대신하여 키 추가/제거 가능
- 프라이빗 블록체인을 위한 중앙화된 신뢰 모델

**복구 프로세스:**
1. 사용자가 개인키 분실
2. 사용자가 서비스 운영자(가디언)에게 연락
3. 오프체인 KYC 검증
4. 가디언이 새 키로 `AddKeyIds` 트랜잭션에 서명
5. 사용자가 새 개인키로 접근 권한 회복

**보안 고려사항:**
- 가디언이 계정 키에 대한 완전한 제어권을 가짐
- 서비스 운영자에 대한 신뢰 필요
- 프라이빗/컨소시엄 블록체인에 적합
- 무신뢰/퍼블릭 블록체인에는 부적합

## Blueprint 구조

```scala mdoc:compile-only
import cats.Monad
import cats.effect.IO
import java.time.Instant
import org.sigilaris.core.application.module.blueprint.*
import org.sigilaris.core.application.module.provider.TablesProvider
import org.sigilaris.core.application.state.{Entry, StoreF, Tables}
import org.sigilaris.core.application.support.compiletime.Requires
import org.sigilaris.core.application.transactions.*
import org.sigilaris.core.assembly.EntrySyntax.*
import org.sigilaris.core.codec.byte.ByteCodec
import org.sigilaris.core.codec.byte.ByteCodec.given
import org.sigilaris.core.datatype.Utf8
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Positive0

// 도메인 타입 (ADR-0010)
type KeyId20 = BigInt  // 20바이트 공개키 해시
type Account = Utf8    // Named 또는 Unnamed 계정 식별자

case class AccountInfo(
  guardian: Option[Account],
  nonce: BigInt :| Positive0
)

case class KeyInfo(
  addedAt: Instant,
  expiresAt: Option[Instant],
  description: Utf8
)

// 참고: 프로덕션에서는 도메인 타입에 대한 ByteCodec 인스턴스를 제공해야 합니다
// given ByteCodec[AccountInfo] = ...
// given ByteCodec[KeyInfo] = ...

// 스키마
type AccountsOwns = (
  Entry["accountInfo", Utf8, AccountInfo],       // name → AccountInfo
  Entry["nameKey", (Utf8, KeyId20), KeyInfo],    // (name, keyId) → KeyInfo
)

type AccountsNeeds = EmptyTuple  // 외부 의존성 없음

// 트랜잭션
trait CreateNamedAccount extends Tx:
  type Reads = EmptyTuple
  type Writes = AccountsOwns
  def nameValue: Utf8
  def initialKeyId: KeyId20
  def guardian: Option[Account]

trait AddKeyIds extends Tx:
  type Reads = AccountsOwns
  type Writes = AccountsOwns
  def nameValue: Utf8
  def keyIds: Map[KeyId20, Utf8]  // keyId → 설명

// 리듀서
class AccountsReducer[F[_]: Monad] 
  extends StateReducer0[F, AccountsOwns, AccountsNeeds]:
  
  def apply[T <: Tx](signedTx: Signed[T])(using
    Requires[signedTx.value.Reads, AccountsOwns],
    Requires[signedTx.value.Writes, AccountsOwns],
    Tables[F, AccountsOwns],
    TablesProvider[F, AccountsNeeds],
  ): StoreF[F][(signedTx.value.Result, List[signedTx.value.Event])] = ???

// 블루프린트 (경로 독립)
// val accountsBP = new ModuleBlueprint[
//   IO, "accounts", AccountsOwns, AccountsNeeds, AccountsTxs
// ](...)
```

**핵심 속성:**
- `Needs = EmptyTuple`: 외부 의존성 없음
- 경로 독립: 어디에든 배포 가능
- 재사용 가능: 여러 인스턴스에 동일한 블루프린트 사용

## 배포 예시

### 독립 배포
```scala
val accountsModule = accountsBP.mount[("app", "accounts")]
```

### 여러 모듈이 공유
```scala
// 공유되는 단일 accounts 인스턴스
val accounts = accountsBP.mount[("app", "shared", "accounts")]

// Group과 Token 둘 다 accounts 참조
val groupModule = groupBP
  .withProvider(TablesProvider.fromModule(accounts))
  .mount[("app", "group")]

val tokenModule = tokenBP
  .withProvider(TablesProvider.fromModule(accounts))
  .mount[("app", "token")]
```

### 모듈별 샌드박스
```scala
// 각 모듈이 격리된 accounts를 가짐
val groupAccounts = accountsBP.mount[("app", "group", "accounts")]
val tokenAccounts = accountsBP.mount[("app", "token", "accounts")]

val groupModule = groupBP
  .withProvider(TablesProvider.fromModule(groupAccounts))
  .mount[("app", "group")]

val tokenModule = tokenBP
  .withProvider(TablesProvider.fromModule(tokenAccounts))
  .mount[("app", "token")]
```

## 설계 결정

**Named vs Unnamed:**
- Named: 사용자 친화적, 복구 가능, 온체인 상태 필요
- Unnamed: 경량, 복구 불가, 최소한의 온체인 공간

**단일 Guardian:**
- 더 단순한 운영 모델
- 책임에 대한 추론이 쉬움
- 프라이빗 블록체인 환경에 충분

**Nonce 기반 재생 방지:**
- 단순한 순차 카운터
- 트랜잭션 재생 방지
- 상태 변경 트랜잭션에 대해 정확히 +1 증가 필요

**KeyId20 (Ethereum 호환):**
- 검증된 충돌 저항성
- Ethereum 도구와의 상호운용성
- 보안을 위해 20바이트로 충분

## 참고 자료

- [ADR-0010: 블록체인 계정 모델 및 키 관리](https://github.com/sigilaris/sigilaris/blob/main/docs/adr/0010-blockchain-account-model-and-key-management.md)
- [ADR-0009: 블록체인 애플리케이션 아키텍처](https://github.com/sigilaris/sigilaris/blob/main/docs/adr/0009-blockchain-application-architecture.md)
- [Application 개요](README.md)

---

© 2025 Sigilaris. All rights reserved.
