# 포즈·동작 인식 암묵지 보강

## 1. Confidence와 quality는 다르다

- **confidence**: 필요한 관절이 관측되고 detector 결과를 믿을 수 있는 정도
- **quality**: 게임이 요구한 타이밍·범위·자세·안정성에 얼마나 부합하는지

confidence가 낮으면 quality를 계산하지 않거나 보상을 보류한다. 낮은 confidence를 낮은 quality로 처리해 사용자 점수를 깎으면 모델 실패를 사용자 실패로 전가한다.

## 2. 프레임률 독립

모든 속도·duration·cooldown은 timestamp로 계산한다. “최근 5프레임”은 15 FPS와 30 FPS에서 다른 시간창이므로, 구현에서는 `최근 250ms`, `최근 500ms`처럼 시간 기반 buffer로 바꾼다.

## 3. Calibration profile

player별로 저장할 값:

- shoulder width, torso length의 robust baseline
- neutral joint angles와 허용 분산
- dominant side
- boxing stance: orthodox/southpaw/neutral
- one-arm mode와 usable side
- camera lens/orientation/analysis aspect
- scale confidence
- createdAt이 아니라 monotonic session revision

신체 치수를 장기 사용자 프로필로 저장할 필요는 없다. 가능하면 세션별로 유지하고, 영속 저장 시 정규화된 threshold multiplier만 저장한다.

## 4. Calibration drift

다음이 일정 시간 지속되면 drift를 감지한다.

- body bounding-box occupancy 20% 이상 변화
- shoulder/torso scale ratio 급변
- 주요 관절 confidence의 체계적 하락
- 카메라 rotation/lens 변경
- 두 플레이어 lane center 이동

대응 순서:

1. 미세 변화: EMA baseline 제한 갱신
2. 중간 변화: “위치 조정” soft warning
3. 큰 변화: pause와 1초 neutral re-arm
4. lens/orientation 변경: 전체 game-specific calibration

게임 도중 score를 유지하되, drift 구간 event는 적용하지 않는다.

## 5. Gesture state machine

```text
IDLE
  entry gate satisfied
→ CANDIDATE
  progress + minimum duration
→ CONFIRMED (single emit)
→ COOLDOWN
  neutral re-arm + cooldown elapsed
→ IDLE
```

추가 규칙:

- entry/exit threshold를 다르게 해 hysteresis를 둔다.
- `CANDIDATE`는 max duration, reverse motion, confidence loss로 취소한다.
- `CONFIRMED` 상태는 한 tick만 의미하고 event를 재발행하지 않는다.
- cooldown 종료만으로 재무장하지 말고 neutral posture를 확인한다.
- 여러 gesture가 같은 관절을 공유하면 exclusivity group을 사용한다.

## 6. 동작 특징 설계

단일 임계값보다 다음 조합을 사용한다.

- displacement normalized by body scale
- direction consistency
- elbow/knee angle delta
- torso/shoulder rotation proxy
- velocity profile와 reversal
- required landmarks coverage
- global body translation 분리

### 권투의 깊이 모호성

2D Pose에서 카메라 방향 전진은 화면 이동이 작을 수 있다. 잽/스트레이트는 손목 전진량 하나로 판정하지 말고 팔꿈치 extension, 손목-어깨 상대 거리, 어깨 회전, return-to-guard를 결합한다. 그래도 holdout recall이 기준 미달이면 “카메라에 약간 대각선” 튜토리얼 또는 공격 세트 단순화를 선택한다.

### 낚시 릴

원형 손 궤적은 손 landmark가 없으면 불안정하다. Pose-only baseline은 좌우 팔꿈치 굽힘 phase, 손목의 상하/좌우 quadrant 전이, 주기 일관성을 사용한다. Hand Landmarker는 보조 confidence만 제공하며, 실패하면 동일 게임 규칙을 Pose fallback으로 유지한다.

### 협동 동기화

두 event의 callback 완료 시각이 아니라 `eventTimestampNs`를 비교한다. 허용 오차 600ms는 카메라/추론 latency 차이를 보정한 관측 시간 기준이다.

## 7. 오인식과 누락의 비용

동작별 비용이 다르다.

- 권투 공격 false positive: 상대에게 불공정 피해 → 높은 precision 우선
- 방어/회피 false negative: 사용자 좌절 → recall과 grace window 균형
- 보스 필살기 false positive: 큰 상태 전이 → 두 단계 confirmation
- 낚시 릴 false negative: 반복 피로 → 누적 progress와 recovery 제공

하나의 공통 precision/recall 목표만으로 모든 gesture를 승인하지 않는다.

## 8. 데이터 분할

- 같은 사람·같은 세션의 변형을 tune과 release holdout에 나누지 않는다.
- 사용자 단위 또는 최소 recording session 단위로 holdout한다.
- threshold tuning 후 release set은 동결한다.
- positive 외에 neutral 60초, 유사 동작, 옷 정리, 머리 만짐, 카메라 접근, 타 플레이어 동작을 hard negative로 포함한다.
- dual fixture는 두 사람의 동시·엇갈린 timing과 occlusion을 포함한다.

## 9. 단순화 stop condition

다음 중 하나면 gesture를 더 복잡하게 튜닝하지 말고 대체 입력을 선택한다.

- 핵심 관절이 일반적인 FOV에서 반복적으로 잘림
- 사용자별 threshold 분산이 지나치게 큼
- holdout precision/recall 기준을 동시에 만족하지 못함
- 안전한 동작 범위에서 분류 불가능
- 저사양 듀얼 latency가 reaction window를 초과

대체 방식: 자세 유지, 더 큰 관절 사용, 양손 동작, touch fallback, gesture 삭제.
