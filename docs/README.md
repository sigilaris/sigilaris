# Sigilaris Documentation Guide

`docs` 디렉터리는 오래 남는 결정과 실행 문서를 분리해 관리한다.

## Directory Roles
- `adr/`: 아키텍처, 도메인 정책, 상태 모델, 트랜잭션 의미론처럼 장기적으로 유지할 결정을 기록한다.
- `plans/`: 특정 변경이나 마일스톤을 구현하기 위한 실행 계획을 기록한다.
- `dev/`: 탐색 메모, 기능 요약, 임시 후속 아이디어, 정제 전 설계 노트를 둔다.
- `perf/`: 성능 기준, 벤치마크 정책, 측정 조건을 둔다.

## ADR Or Plan
- 아래 질문의 답이 "오래 유지할 결정인가?"이면 `adr/`에 쓴다.
- 아래 질문의 답이 "이번 구현을 어떻게 진행할까?"이면 `plans/`에 쓴다.
- 하나의 작업에 둘 다 필요할 수 있다. 이 경우 `plan`이 구현 순서를 잡고, `ADR`이 장기 결정을 고정한다.

## Recommended Workflow
1. 범위가 여러 파일이나 단계에 걸치면 먼저 `plans/`에 실행 문서를 만든다.
2. 구현 중 새 아키텍처 결정이나 장기 정책이 생기면 `adr/`에 별도 문서를 추가하거나 기존 ADR을 갱신한다.
3. 구현이 끝나면 plan의 상태와 체크리스트를 갱신하고, 최종 결정은 ADR이나 안정적인 가이드 문서에 반영한다.

## Naming
- ADR: `NNNN-short-kebab-case.md`
- Plan: `NNNN-short-kebab-case-plan.md`

## References
- [plans/README.md](plans/README.md)
- [plans/plan-template.md](plans/plan-template.md)
- [application-package-index.md](application-package-index.md)
