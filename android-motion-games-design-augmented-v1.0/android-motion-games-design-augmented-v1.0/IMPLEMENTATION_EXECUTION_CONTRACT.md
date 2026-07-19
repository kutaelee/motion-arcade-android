# 구현 실행 계약

## 목적

원본 Slice를 “코드가 존재한다”가 아니라 독립적으로 검증 가능한 vertical slice로 실행한다. 각 slice 종료 시 실제 증거가 없으면 `partial` 또는 `blocked`로 판정한다.

## 공통 계약

- **Scope**: 해당 slice의 bounded objective만 변경한다.
- **Non-goals**: 다음 slice 기능을 임시로 끌어오지 않는다.
- **Invariants**: `DESIGN_SSOT.md`를 위반하지 않는다.
- **Validation command**: 저장소 명령이 확정되기 전에는 `build/test command discovery 필요`로 표시한다.
- **Required evidence**: 명령, exit code, 로그, 결과 파일, 캡처, 수치 분포를 보관한다.
- **Rollback**: slice 시작 태그 또는 별도 브랜치로 되돌릴 수 있어야 한다.
- **Stop**: 개인정보, 안전, 데이터 손상, 동적 의존성, 임의 에셋 혼합 발견 시 즉시 중단한다.

## 보강된 실행 순서

### Slice 0A — 계약과 공급망 기준선

**Objective**: 모듈, 버전, 의존성, 모델 파일, 권한, 데이터 계약을 고정한다.

**Acceptance**

- Gradle wrapper, version catalog, dependency verification 정책이 존재한다.
- 모델 파일에 출처·버전·SHA-256이 기록된다.
- `MotionEvent`, `CalibrationProfile`, `SessionSnapshot`의 Kotlin 모델과 스키마가 대응된다.
- manifest에 INTERNET 및 불필요 권한이 없다.
- ImageGen SSOT 파일과 검증 스크립트가 CI에서 실행된다.

**Stop**: 최신 버전 추정, 동적 버전, 라이선스 미확인 모델, 외부 에셋 혼합.

### Slice 1A — 시간·좌표 샌드박스

**Objective**: 카메라와 포즈 검출보다 먼저 좌표·timestamp 계약을 검증한다.

**Acceptance**

- 0/90/180/270도, front/back, crop/letterbox 조합의 synthetic tests가 통과한다.
- 모든 frame에 monotonic timestamp가 붙는다.
- overlay와 canonical pose의 오차를 수치화할 수 있다.
- `ImageProxy.close()`가 예외 경로에서도 보장된다.

**Stop**: 동일 자세가 렌즈·회전에 따라 다른 의미 이벤트로 해석됨.

### Slice 1B — 기기 capability probe

**Objective**: 첫 실행에서 솔로·듀얼 지원 여부와 품질 tier를 측정한다.

**Acceptance**

- 모델 warm-up과 10초 샘플 결과가 기록된다.
- 실패 delegate는 자동 fallback한다.
- 듀얼 기준 미달 시 해당 모드를 숨기거나 호환 불가로 명확히 표시한다.
- 판정은 기기명 allowlist가 아니라 실제 측정값을 사용한다.

### Slice 2A — Motion engine과 fixture governance

**Objective**: 프레임률 독립, 중복 없는 gesture event engine을 만든다.

**Acceptance**

- 이벤트당 `eventId`, `sequenceNumber`, `calibrationRevision`이 있다.
- 같은 gesture cycle에서 중복 이벤트 0건.
- tune set과 holdout set이 사용자/세션 단위로 분리된다.
- hard negative와 neutral 60초 fixture가 존재한다.

### Slice 2B — 2인 identity ambiguity

**Objective**: 교차·가림 시 안전하게 멈추고 역할을 보존한다.

**Acceptance**

- track state가 `TENTATIVE/ACTIVE/OCCLUDED/AMBIGUOUS/LOST`로 관측된다.
- 비용 차이가 불충분하면 assignment를 확정하지 않는다.
- ID swap 대신 pause와 re-arm 절차가 작동한다.

### Slice 3~6 — 게임 vertical slices

각 게임은 다음 추가 acceptance를 공통 적용한다.

- detection confidence 저하가 난이도나 점수 손해로 숨겨지지 않는다.
- motion과 touch 입력의 규칙 결과가 같은 event sequence에서 동일하다.
- pause 동안 simulation tick과 game timer가 진행되지 않는다.
- process death 복원은 안전한 checkpoint에서만 가능하다.
- rear camera는 실제 화면 가시성 조건을 충족한 setup에서만 full-play 승인한다.
- anchor style profile과 ImageGen SSOT를 통과한 에셋만 master로 등록한다.

### Slice 7A — 에셋 생산

**Objective**: 게임마다 style anchor를 잠그고 모든 배포 에셋을 ImageGen lineage로 추적한다.

**Sequential steps**

1. common hub와 게임 3종 anchor board 생성
2. 수동 승인 후 style revision lock
3. 캐릭터 turnaround/레이어 기준 생성
4. 환경·적·아이콘·FX 순서로 생성
5. contact sheet 일관성 검토
6. 결정적 후처리
7. manifest와 해시 생성
8. 64px·128px·실게임 배율 검증

**Stop**: 한 asset이 다른 style profile을 참조, 텍스트 포함, 저작권 모방, 외부 이미지 혼입, 수작업 재창작.

### Slice 7B — 릴리스 후보

**Acceptance**

- 모든 critical gate 통과
- 실제 기기 성능 분포 존재
- rear camera 사용성 실증
- 듀얼 안전 테스트 실증
- asset provenance와 약관 검토 상태 기록
- 미검증 항목이 명시됨

**Final status rule**

- 코드·자동 테스트만 통과: `Implementation complete; physical-device verification pending`
- 물리 기기·2인·안전·법무 검토 완료: `Release candidate approved`
- 하나라도 critical failure: `Manual review required` 또는 `Reject / Rework`
