# Ethereum RLP와의 비교

[← 코덱 개요](README.md) | [API](api.md) | [타입 규칙](types.md) | [예제](examples.md) | [RLP 비교](rlp-comparison.md)

---

## 개요

이 문서는 Sigilaris 바이트 코덱과 Ethereum의 Recursive Length Prefix (RLP) 인코딩을 비교합니다.

**상태:** 작성 중.

## 주요 차이점

| 특징 | Sigilaris Codec | Ethereum RLP |
|------|-----------------|--------------|
| 작은 값 | 0x00~0x80 (0-128) | 0x00~0x7f (0-127) |
| 타입 안정성 | Scala 3 with cats | 동적 |
| 컬렉션 | Deterministic 정렬 | 순서 유지 |

---

[← 코덱 개요](README.md) | [API](api.md) | [타입 규칙](types.md) | [예제](examples.md) | [RLP 비교](rlp-comparison.md)
