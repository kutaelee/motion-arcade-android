# Eval Contract와 릴리스 게이트

## 원칙

- 결정적 검사 > 스키마 검사 > 실행 테스트 > artifact inspection > 수동 검토 순서로 사용한다.
- critical failure 하나가 평균 점수보다 우선한다.
- 같은 사람/세션을 공유한 tune/test 결과로 release를 승인하지 않는다.
- LLM이나 주관적 “좋아 보임”만으로 안전·개인정보·에셋 일관성을 승인하지 않는다.

## Gate

### G0 — 저장소·공급망

- pinned versions, wrapper checksum, dependency verification
- dynamic version 0건
- INTERNET permission 0건
- OSS/model provenance 기록

### G1 — 계약·결정성

- JSON/Proto/Kotlin 계약 일치
- deterministic replay
- duplicate event rejection
- save migration/corruption tests

### G2 — Camera/Pose pipeline

- transform synthetic tests
- physical overlay error
- frame queue 비누적
- image close exception paths
- delegate fallback

### G3 — Gesture quality

- gesture별 subject/session holdout
- precision/recall 목표
- neutral false events/min
- confusion matrix
- confidence failure와 user failure 구분

### G4 — 2인 identity/fairness

- non-cross swap 0
- crossing/occlusion에서 arbitrary swap 0
- ambiguity pause
- per-player normalization fairness
- simultaneous event loss 0

### G5 — Safety·rear camera UX

- minimum-space 안내 이해도
- 두 플레이어 proximity pause
- camera-facing punch instruction
- rear camera에서 화면/예고를 실제로 볼 수 있는 setup
- low-intensity/one-arm 대체 경로

### G6 — Asset consistency / ImageGen SSOT

- 게임별 approved anchor revision
- 모든 master `generationMethod=IMAGEGEN_SKILL`
- registry 밖 prompt 0건
- external art mix 0건
- contact sheet와 style scorecard
- no UI text/copyright imitation
- provenance와 redistribution review 상태

### G7 — Privacy/security

- raw frame save/upload 0
- release log redaction
- exported component 최소화
- diagnostic export allowlist
- model/asset hash verification

### G8 — Performance/thermal

- render P90/P99
- inference/event P95
- drop rate and queue age
- dual FPS
- thermal degradation order
- representative physical devices

### G9 — Release readiness

- 기능 matrix 8 camera/orientation/mode 조합
- critical defects 0
- open risks accepted by owner
- rollback/recovery instructions
- legal/provenance review
- manual safety review

## Gesture 평가 단위

| 분류 | 최소 release sample | 비고 |
|---|---:|---|
| Positive | 30/gesture | 사용자·세션 다양성 |
| Hard negative | 30/gesture | 유사 동작 중심 |
| Neutral | 사용자별 60초 이상 | 오발생/min 계산 |
| Dual simultaneous | gesture 조합별 최소 10 | event loss/attribution |
| Occlusion/crossing | 시나리오별 최소 10 | ID policy |

원본 숫자는 최소 smoke/release gate로 유지하되, 사용자 수와 세션 분리를 추가한다.

## Asset 평가

- 64px silhouette 식별
- game palette/outline/lighting/projection 일관성
- transparent edge halo 없음
- pivot/collision bounds 검토
- HUD safe zone 침범 없음
- action FX가 pose guide를 가리지 않음
- 같은 캐릭터의 머리·의상·비율 drift 없음

## 승인 판정

- `Approve`: 모든 critical gate와 수동 검토 통과
- `Approve with conditions`: 비critical 제한과 owner/date가 명확
- `Manual review required`: 물리 기기·2인·법무·안전 증거 부족
- `Additional data needed`: 가장 값싼 discriminating test 명시
- `Reject / Rework`: invariant 위반 또는 critical failure
