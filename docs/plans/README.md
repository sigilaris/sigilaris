# Plans Guide

`docs/plans`는 구현을 진행하기 위한 실행 문서를 모아두는 곳이다. 여기서는 왜 그 결정을 내렸는지보다, 어떤 순서로 무엇을 바꾸고 어떻게 검증할지를 우선한다.

## Use A Plan When
- 변경 범위가 여러 파일, 모듈, 테스트, 문서에 걸친다.
- 구현 순서나 단계 분리가 중요하다.
- 리스크, acceptance criteria, 체크리스트를 명시해야 한다.
- 아직 ADR로 굳히기 전인 실행 전략을 정리해야 한다.

## Do Not Use A Plan As
- 장기 정책의 단일 근거 문서
- 아키텍처 결정을 영구 보존하는 저장소
- 단순 회의 메모나 짧은 TODO 목록

장기적으로 유지할 정책, 상태 모델, 의미론은 ADR에 남긴다. plan에는 해당 ADR 링크를 건다.

## File Naming
- 형식: `NNNN-short-kebab-case-plan.md`
- 예시: `0001-accounts-module-assembly-plan.md`
- 번호는 `docs/plans` 안에서 증가하는 정수 시퀀스를 사용한다.

## Suggested Status Values
- `Draft`: 초안
- `Proposed`: 검토 가능 상태
- `In Progress`: 구현 진행 중
- `Blocked`: 외부 의존이나 미확정 결정으로 진행 보류
- `Done`: 구현과 검증 완료
- `Superseded`: 더 새로운 plan이나 ADR에 의해 대체됨

가능하면 `Created`와 `Last Updated`를 함께 적어 문서 수명과 갱신 시점을 드러낸다.

## Minimum Contents
- 상태와 날짜 메타데이터
- 배경과 목표
- 범위와 비범위
- 선결정 사항 또는 가정
- 변경 범위
- 구현 단계
- 테스트 계획
- 리스크와 완화
- 수용 기준
- 체크리스트

## Relationship To ADR
- 새로운 장기 결정이 필요한 작업이면 plan에서 그 결정을 식별하고 ADR로 승격한다.
- ADR이 이미 있으면 plan의 상단에 관련 ADR 링크를 둔다.
- 구현이 끝난 뒤에도 장기적으로 참조할 내용은 plan에만 남기지 않는다.

## Phase Guidance
- Phase는 고정 개수가 아니다.
- 작은 작업이면 필요한 Phase만 남기고 나머지는 삭제한다.
- 큰 작업이면 Phase를 더 잘게 나눠도 된다.
- 중요한 것은 번호보다 구현 순서와 검증 경계가 드러나는 것이다.

## Template
- 새 문서는 [plan-template.md](plan-template.md)를 복사해서 시작한다.
