# Runbook — 2인 ID 모호성·손실

## Trigger

- assignment margin 미달
- bbox overlap 과다
- 한 player 400ms 이상 미검출
- lane crossing
- track scale 급변

## Mitigation

1. 새로운 gesture confirmation 중단
2. GameSession pause with reason
3. 화면에 각 role lane 표시
4. 두 player가 neutral pose와 원래 lane으로 복귀
5. 1초 안정 관측
6. role-track 재결합
7. 3초 countdown

## 하지 말 것

- x 좌표로 즉시 P1/P2 재정렬
- 더 가까운 pose에 높은 점수 role 부여
- 한 player만 남았을 때 다른 캐릭터 auto-control
- occluded prediction으로 공격 confirm

## Evidence

- frame별 track state
- assignment cost와 margin
- role binding before/after
- pause/rearm timestamp
- 이벤트 손실/중복 count

두 번 이상 같은 시나리오에서 swap이 발생하면 threshold 반복 조정 대신 tracking architecture를 재검토한다.
