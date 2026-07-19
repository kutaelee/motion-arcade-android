# 기기 Capability·발열·성능 열화 정책

## 기본 원칙

`minSdk 26`은 API 호환성일 뿐, 모든 기기가 솔로·듀얼·모든 효과를 만족한다는 의미가 아니다. 지원 여부는 실제 runtime probe와 물리 기기 검증으로 판정한다.

## First-run capability probe

카메라 권한 후 짧은 샌드박스에서 측정한다.

- camera bind 성공 여부
- 모델 asset 로드·hash 확인
- model warm-up 3~5회
- 후보 delegate별 안정성·latency
- 640×480 solo 10초
- 요청 시 dual 10초
- frame age, inference P50/P95, drop rate
- 메모리 peak와 thermal 상태

사용자 영상이나 pose를 저장하지 않고 집계만 profile에 보관한다.

## Quality tier

| Tier | 조건 예시 | 허용 모드 | 기본 효과 |
|---|---|---|---|
| A | dual 목표 충족, tail latency 안정 | solo+dual | full |
| B | solo 안정, dual 제한적 또는 효과 축소 필요 | solo, 조건부 dual | reduced particles/shadows |
| C | solo 최소 기준만 충족 | solo only | low effects, 낮은 analysis rate |
| Unsupported | bind/model/latency critical failure | 게임 차단 | 호환성 안내 |

수치는 실제 목표 기기군에서 확정한다. 기기 모델 allowlist만으로 tier를 고정하지 않는다.

## Delegate policy

- GPU/NNAPI/CPU 선택은 “빠를 것”이라는 추정이 아니라 warm-up 이후 P95와 crash/stability로 정한다.
- delegate 실패 시 CPU fallback을 시도한다.
- fallback 후 게임 중간에 판정 창을 변경하지 않는다.
- delegate 변경 시 calibration revision을 올리고 짧은 re-arm을 수행한다.

## Ordered degradation

원본 순서를 유지하되, 각 단계는 hysteresis를 가진다.

1. pose inference rate 감소
2. analysis resolution 감소
3. particle budget 감소
4. preview resolution 감소
5. Hand Landmarker off
6. shadow/post-effect off

추가 규칙:

- 한 단계 진입 후 최소 관찰 시간 전에 즉시 복귀하지 않는다.
- thermal status가 critical이면 점진 열화가 아니라 안전 pause를 사용한다.
- simulation tick, gesture duration, AI reaction window는 변경하지 않는다.
- 성능 복구 후 단계 상승은 한 번에 하나씩 한다.

## 메모리·저장공간

- bitmap은 game asset catalog에서 공유하고 화면별 중복 decode를 피한다.
- large background는 필요한 최대 해상도와 texture limit를 검증한다.
- `onTrimMemory`에서 재생성 가능한 cache를 해제한다.
- model과 master source는 APK에 중복 포함하지 않는다. 앱에는 processed runtime asset만 포함한다.

## 측정 방법

성능 결과에는 반드시 포함한다.

- device model, SoC, RAM, OS
- build type, minification 여부
- lens, orientation, solo/dual
- analysis resolution, preview resolution
- delegate, model version/hash
- thermal 시작/종료 상태
- camera/inference/render FPS
- P50/P90/P95/P99 latency
- dropped frame/event count

평균만 제시하면 불충분하다.

## Stop condition

- confirmed event P95가 180ms를 지속 초과
- dual inference가 10 FPS 미만
- queue age가 계속 증가
- thermal critical 반복
- 게임 loop tick loss가 규칙 결과를 바꿈
- device tier가 게임 시작 후 예고 없이 모드를 제거
