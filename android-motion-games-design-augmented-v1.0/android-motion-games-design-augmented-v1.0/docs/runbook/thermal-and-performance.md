# Runbook — 발열·성능 저하

## Trigger

- end-to-end P95 180ms 초과
- inference queue age 증가
- render P99 33ms 초과 지속
- Android thermal severe/critical
- 메모리 pressure

## 순서

1. 현재 mode/lens/resolution/delegate 기록
2. 오래된 frame 누적 여부 확인
3. inference rate 한 단계 감소
4. analysis resolution 감소
5. visual effect budget 감소
6. preview resolution 감소
7. hand detector off
8. shadow/post effect off
9. severe/critical이면 pause와 cooldown 안내

## 검증

각 단계 후 최소 관찰 window에서 P95, drop rate, thermal trend를 비교한다. simulation timing과 gesture duration이 변하지 않았는지 replay로 확인한다.

## Stop

- thermal critical이 반복
- gameplay 안전 cue가 frame drop으로 보이지 않음
- dual 기준 10 FPS 미만
- event queue overflow
- 기기 과열 경고

이 경우 mode를 중단하고 compatibility 결과를 낮춘다.
