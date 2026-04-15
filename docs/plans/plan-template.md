# NNNN - <Short Title>

## Status
Draft

## Created
YYYY-MM-DD

## Last Updated
YYYY-MM-DD

## Background
- 왜 이 변경이 필요한지 적는다.
- 현재 제약, 기존 동작, 문제 상황을 적는다.

## Goal
- 이번 작업이 완료되면 무엇이 가능해지는지 적는다.

## Scope
- 이번 plan에서 실제로 바꾸는 것만 적는다.

## Non-Goals
- 이번 작업에서 의도적으로 다루지 않는 범위를 적는다.

## Related ADRs And Docs
- ADR-NNNN: <Title>
- 관련 API, 테스트, 설계 문서 링크를 추가한다.

## Decisions To Lock Before Implementation
- 구현 전에 확정해야 할 정책이나 계약을 적는다.
- 장기적으로 유지할 결정이면 ADR로 승격할지 함께 적는다.

## Change Areas

### Code
- 바뀌는 모듈, 패키지, 파일 범위를 적는다.

### Tests
- 추가하거나 수정할 테스트 범위를 적는다.

### Docs
- README, 가이드, ADR, 예제 갱신 여부를 적는다.

## Implementation Phases

필요한 Phase만 남기고, 맞지 않는 Phase는 삭제한다.
- Checklist는 `Implementation Phases`와 같은 순서/경계를 반영해 작성한다.
- 각 체크 항목은 가능한 한 특정 Phase의 deliverable 또는 gate와 1:1로 대응되게 쪼갠다.
- flat checklist 하나로 끝내지 말고, `### Phase N` 단위의 하위 체크리스트로 정리한다.

### Phase 0: Policy And Contract Lock
- 정책, 타입 계약, 외부 노출 형식을 확정한다.

### Phase 1: Core Changes
- 핵심 도메인, 모델, 런타임 변경을 적는다.

### Phase 2: Integration
- 의존 모듈 연결, 조립, 마이그레이션 작업을 적는다.

### Phase 3: Verification And Docs
- 테스트, 문서, 회귀 검증 작업을 적는다.

## Test Plan
- 성공 경로를 어떻게 검증할지 적는다.
- 실패 경로와 회귀 케이스를 어떻게 검증할지 적는다.

## Risks And Mitigations
- 리스크와 대응 방안을 짝으로 적는다.

## Acceptance Criteria
1. 사용자가 관찰 가능한 완료 조건을 적는다.
2. 테스트나 문서 기준을 적는다.
3. 필요하면 성능, 호환성, 배포 조건을 적는다.

## Checklist

체크리스트는 반드시 페이즈와 연동해서 적는다.

### Phase 0: <Name>
- [ ] Phase 0 deliverable 1
- [ ] Phase 0 deliverable 2

### Phase 1: <Name>
- [ ] Phase 1 deliverable 1
- [ ] Phase 1 gate or verification

### Phase 2: <Name>
- [ ] Phase 2 deliverable 1
- [ ] Phase 2 documentation or cleanup

## Follow-Ups
- 이번 작업 이후 별도 plan이나 ADR로 넘길 후속 과제를 적는다.
