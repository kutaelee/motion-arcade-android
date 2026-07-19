# 2인 추적·역할·공정성 계약

## Track state

```text
TENTATIVE → ACTIVE → OCCLUDED → ACTIVE
                   ↘ AMBIGUOUS → REARM → ACTIVE
                   ↘ LOST
```

- `TENTATIVE`: 300~500ms 안정 관측 전
- `ACTIVE`: assignment confidence 충분
- `OCCLUDED`: 400ms 이내 일시 손실, 예측만 제한 사용
- `AMBIGUOUS`: 두 assignment 비용 차이가 불충분
- `LOST`: 1.2초 이상 또는 화면 이탈
- `REARM`: neutral 1초 후 역할 재결합

## Assignment cost

두 pose이므로 identity-preserving assignment 두 경우를 모두 계산한다.

```text
cost =
  pelvisDistanceNormalized * wp
+ shoulderDistanceNormalized * ws
+ velocityMismatch * wv
+ scaleMismatch * wz
+ directionDiscontinuity * wd
+ lanePenalty * wl
```

모든 항은 body scale 또는 frame diagonal로 정규화한다. 한 항이 픽셀 단위로 지배하면 안 된다.

### 확정 조건

- 최소 비용이 absolute gate 안에 있어야 한다.
- 1순위와 2순위 비용 차이가 margin보다 커야 한다.
- 이전 assignment와 반대되는 결과는 N개 프레임 또는 일정 시간 hysteresis를 통과해야 한다.
- margin 미달이면 `AMBIGUOUS`, 임의 swap 금지.

가중치와 gate는 JSON config와 fixture로 관리한다.

## 역할과 track 분리

- `roleId`: P1/P2, Vanguard/Ranger, Rod/Net 등 게임 의미
- `trackId`: 현재 관측되는 임시 신체 궤적

re-arm은 role을 새 track에 다시 결합하는 절차다. 역할을 화면 좌우로 매 프레임 재정의하지 않는다.

## Lane policy

- lane은 화면 픽셀이 아니라 normalized preview space에서 정의한다.
- player body bbox의 20~30%가 lane boundary를 넘는 단기 현상은 경고한다.
- 두 bbox IoU 또는 중심 거리/shoulder scale이 안전 임계치를 넘으면 pause한다.
- 정확한 안전 임계값은 physical test로 조정하며 기기 FOV별로 재검증한다.

## 이탈·가림

- 한 명이 사라져도 다른 사람에게 두 역할을 부여하지 않는다.
- solo fallback은 사용자가 명시적으로 mode 변경할 때만 가능하다.
- occluded track의 예측 좌표로 공격·점수 event를 확정하지 않는다.
- 두 플레이어가 겹친 상태에서 gesture candidate를 모두 취소한다.

## 동시 이벤트

- player별 sequence를 독립 관리한다.
- 같은 timestamp의 두 event도 모두 보존한다.
- 게임 규칙이 충돌할 경우 명시적 deterministic tie-breaker를 둔다. 예: 먼저 timestamp, 같으면 playerId 순서. 협동 판정은 별도 window로 묶는다.
- queue가 한 player 이벤트를 다른 player로 overwrite하면 critical defect다.

## 공정성

- 범위·속도는 player별 body scale로 정규화한다.
- 카메라에 가까운 사람이 더 큰 damage를 얻지 않아야 한다.
- dominant side/stance를 반영하되 공격력 차이를 만들지 않는다.
- 저강도/한쪽 팔 모드의 점수는 별도 leaderboard가 없는 MVP에서는 동일 목표 내 보정한다. 접근성 모드를 불리하게 만들지 않는다.
- 추적 confidence가 낮은 구간은 상대에게 자동 이득을 주지 않고 pause/grace로 처리한다.

## 필수 fixture

- 두 사람 정지
- 같은 방향 이동
- 서로 접근 후 복귀
- lane 교차
- 완전 가림 300ms/800ms/1500ms
- 한 명 퇴장·재입장
- 서로 다른 신장/거리
- 한 명만 격렬한 동작
- 동시 동일 gesture
- 동시 상반 gesture

릴리스 주장에는 각 시나리오의 assignment timeline과 pause reason이 필요하다.
