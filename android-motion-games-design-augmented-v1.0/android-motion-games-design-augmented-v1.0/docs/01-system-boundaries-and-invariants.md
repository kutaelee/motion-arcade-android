# 시스템 경계와 불변조건

## 시스템 경계

```text
Android OS / Camera HAL
        ↓ untrusted device behavior
CameraX Adapter (:vision)
        ↓ RawPoseFrame only; no frame persistence
Pose Pipeline (:vision)
        ↓ NormalizedPoseFrame / TrackState
Gesture Engine (:vision)
        ↓ MotionEvent envelope
Game Runtime (:game-core)
        ↓ immutable GameSnapshot
Renderers (:games)
        ↓ drawing commands
App/UI (:app)
```

외부 경계는 Android OS, 카메라 HAL, MediaPipe native runtime, 파일 시스템, 사용자 신체 동작이다. 이 입력은 모두 실패·지연·누락·순서 변경 가능성이 있는 untrusted input으로 본다.

## 데이터 소유권

### Camera frame

- 소유자: analyzer callback 한 곳
- lifetime: callback 시작부터 `ImageProxy.close()`까지
- 공유 방식: 모델 API가 요구하는 최소 view/reference만 전달
- 금지: byte array 복제, 캐시, 파일 저장, crash attachment, analytics

### Pose frame

- 소유자: `PosePipeline`
- lifetime: bounded ring/window에 필요한 400~1200ms 범위
- 금지: 사용자 식별, 장기 저장, 릴리스 로그

### Player track

- 소유자: `PlayerTracker`
- 역할 ID와 관측 track ID를 분리한다.
- `trackId`는 세션 내부 임시 식별자이며 영속 저장하지 않는다.

### Game state

- 소유자: `GameSession`
- 변경은 simulation tick에서만 수행한다.
- renderer와 Compose는 snapshot만 읽는다.

### Persistent state

- 프로필, 설정, 해금, 안전 checkpoint만 저장한다.
- camera frame, raw pose, transient gesture candidate는 저장하지 않는다.

## 실패 전파 정책

| 실패 | 격리 범위 | 사용자 상태 | 복구 |
|---|---|---|---|
| 단일 frame 추론 실패 | vision frame | 유지 | frame 폐기 |
| 연속 pose 손실 <400ms | player track | 보류 | 제한적 hold |
| pose 손실 400ms~1.2s | gesture | candidate 취소 | 중립 재확인 |
| pose 손실 >1.2s | game session | pause | re-arm 후 countdown |
| camera disconnect | camera session | pause | rebind 또는 lens 재선택 |
| delegate crash/failure | vision runtime | pause | CPU fallback, capability 재평가 |
| renderer exception | presentation | pause/error screen | session snapshot 보존 |
| save corruption | persistent profile | 기본값 복구 | 손상본 격리, 사용자 안내 |

오류를 캐치하고 계속 진행하는 것보다 상태 오염을 막는 것이 우선이다.

## dependency 규칙

```text
:app -> :games, :game-core, :vision
:games -> :game-core
:vision -> standalone domain contract or :game-core input API
:game-core -> Kotlin/Android minimal APIs only
```

`:game-core`가 Android UI, CameraX, MediaPipe, Compose에 의존하면 테스트 대체성과 결정성이 깨진다.

## API 계약

- `PoseSource`: normalized frames만 제공
- `MotionEventSource`: confirmed semantic events만 제공
- `GameSession.accept(event)`: idempotent envelope 검사 후 queue에 추가
- `GameSession.tick(fixedDelta)`: 유일한 mutable transition point
- `GameSession.snapshot()`: immutable, render-safe
- `CheckpointRepository`: versioned atomic save/load
- `AssetCatalog`: manifest 기반 read-only lookup

## blast radius 최소화

- 카메라와 게임 loop를 같은 coroutine scope에 묶지 않는다.
- 한 게임 renderer 실패가 다른 게임 config를 손상시키지 않는다.
- game-specific gesture mapping은 core gesture detector를 직접 수정하지 않고 mapping layer에서 조정한다.
- style profile 변경은 gameId 단위 revision으로 격리한다.
