# 생명주기·상태·저장·복구

## 상태 머신 분리

### App lifecycle

`FOREGROUND / BACKGROUND / PROCESS_RECREATED`

### Camera session

`UNBOUND / BINDING / ACTIVE / SWITCHING / ERROR`

### Calibration

`NONE / RUNNING / VALID / DRIFTED / REARMING`

### Game session

`CREATED / COUNTDOWN / RUNNING / PAUSED / COMPLETED / ABORTED`

이 상태들을 하나의 boolean `isPlaying`으로 표현하면 race와 잘못된 재개가 발생한다.

## Pause reason

`PauseReason`을 enum/structured data로 기록한다.

- USER
- APP_BACKGROUND
- ROTATION_REBIND
- CAMERA_SWITCH
- CAMERA_ERROR
- POSE_LOST
- PLAYER_OVERLAP
- PLAYER_LANE_CROSS
- THERMAL_CRITICAL
- EVENT_QUEUE_OVERFLOW

재개 조건은 reason마다 다르다. 사용자 pause는 즉시 재개할 수 있지만, pose lost는 neutral re-arm과 countdown이 필요하다.

## 회전

1. GameSession pause with `ROTATION_REBIND`
2. checkpoint in memory; persistent save는 필요 시만
3. Preview/analysis unbind
4. 새 orientation과 ViewPort로 bind
5. transform cache 갱신
6. pose stable + calibration validity 확인
7. 3초 countdown
8. 같은 tick/HP/score에서 resume

“최대 1초 내 자동 재개”와 “3초 countdown”은 충돌할 수 있다. 해석은 **카메라 재바인딩과 상태 준비가 1초 목표이고, 실제 game simulation 재개 전 3초 사용자 countdown은 별도**다.

## Camera switch

- 기존 event queue flush
- calibration revision 증가
- camera-specific calibration 무효화
- 최소 핵심 동작 재연습
- game state는 보존

## Checkpoint policy

저장 시점:

- game phase 전환
- fish catch/boss phase/round 종료
- 사용자 pause 후 안정 상태
- background 진입
- 결과 화면 진입

매 frame 또는 매 tick 저장하지 않는다.

저장 항목:

- schemaVersion
- sessionId, gameId, mode
- simulationTick
- game state와 player state
- seed + PRNG algorithmVersion + internal state
- score/reward pending state
- role mapping
- pause reason
- content/config revision

저장 금지:

- raw pose
- track prediction
- gesture CANDIDATE
- camera frame
- transient renderer particles

## Deterministic PRNG

플랫폼 기본 `Random` 구현에 장기 재현성을 맡기지 않는다. game-core에 작은 고정 알고리즘을 구현하고 `algorithmId`와 내부 state를 snapshot에 저장한다. 알고리즘 변경은 save compatibility ADR이 필요하다.

## Proto evolution

- field number 재사용 금지
- 삭제 field는 reserved
- additive field를 우선
- migration은 old→new 단방향 함수와 fixture 제공
- unreadable/corrupt data는 원본을 덮어쓰기 전에 격리
- 설정 default 변경은 기존 사용자 값과 구분

## Process death

복원 후 camera/gesture를 그대로 RUNNING으로 두지 않는다.

1. checkpoint load
2. game session `PAUSED`
3. camera bind
4. calibration/re-arm
5. user 확인/countdown
6. resume

## Audio/haptic lifecycle

- background 시 SoundPool stream stop/pause
- audio focus loss 처리
- resume 시 오래된 SFX replay 금지
- haptic은 confirmed event 적용 시 한 번만
- reduce-effects/disable-vibration 설정을 snapshot이 아니라 profile에서 읽는다.
