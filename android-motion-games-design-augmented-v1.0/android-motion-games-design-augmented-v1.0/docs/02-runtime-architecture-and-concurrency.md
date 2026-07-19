# 런타임 아키텍처·동시성·시간 계약

## Thread model

| 실행 영역 | 책임 | 금지 |
|---|---|---|
| Main/UI | Compose state, navigation, PreviewView binding | ML 추론, game simulation, bitmap processing |
| Camera executor | ImageAnalysis callback, frame timestamp capture | blocking disk I/O |
| Vision serial worker | MediaPipe 호출과 callback serialization | 병렬로 같은 landmarker instance 호출 |
| Feature worker | normalization, tracking, gesture state | unbounded queue |
| Game loop | fixed 60Hz simulation, input drain | Camera/MediaPipe 타입 접근 |
| Render callback | snapshot interpolation/draw | mutable game rule transition |
| I/O worker | DataStore checkpoint, diagnostic export | raw frame/pose 저장 |

실제 MediaPipe callback threading은 라이브러리 버전에 따라 확인해야 한다. 확인 전에는 하나의 serial adapter가 callback을 순서화한다고 가정하고, 동시 재진입을 허용하지 않는다.

## Pipeline queue policy

```text
ImageProxy: capacity 1, DROP_OLD/KEEP_ONLY_LATEST
RawPoseFrame: capacity 1~2, DROP_OLDEST
NormalizedPoseFrame: capacity 2, DROP_OLDEST
MotionEvent: bounded ordered queue, no silent drop
GameSnapshot: StateFlow-like latest value
```

프레임은 오래되면 가치가 없지만 confirmed event는 게임 의미를 가진다. MotionEvent queue overflow는 숨기지 말고 session pause와 진단 이벤트를 발생시킨다.

## Timestamp contract

각 frame/event는 다음 시간값을 분리한다.

- `sourceTimestampNs`: CameraX/Image의 monotonic timestamp
- `inferenceCompletedAtNs`: 결과 callback 시각
- `eventTimestampNs`: 동작이 확인된 관측 시간
- `enqueuedAtNs`: game queue 삽입 시간
- `appliedAtTick`: simulation 적용 tick

wall clock(`System.currentTimeMillis`)은 UI 날짜 표시와 로그 파일명 외에는 사용하지 않는다.

### Latency

```text
visionLatency = inferenceCompletedAtNs - sourceTimestampNs
eventQueueLatency = tickTimeNs - eventTimestampNs
endToEndLatency = appliedTickTimeNs - eventTimestampNs
```

음수 latency, 역순 timestamp, 1초 이상 오래된 confirmed event는 invariant violation으로 기록하고 적용하지 않는다.

## Event envelope

기존 `MotionEvent`에는 최소 다음 필드를 추가한다.

```kotlin
data class MotionEventEnvelope(
    val eventId: String,
    val sessionId: String,
    val playerId: PlayerId,
    val sequenceNumber: Long,
    val type: MotionType,
    val quality: Float,
    val confidence: Float,
    val eventTimestampNs: Long,
    val calibrationRevision: Int,
    val source: InputSource,
    val metadata: Map<String, Float>
)
```

- `eventId`: `sessionId/playerId/sequenceNumber` 기반 결정적 ID 권장
- `sequenceNumber`: player별 단조 증가
- `calibrationRevision`: 오래된 calibration에서 생성된 event 차단
- `source`: `MOTION`, `TOUCH`, `FIXTURE`

## Exactly-once 적용

1. Gesture state가 `CONFIRMED`에 처음 진입할 때만 event를 생성한다.
2. player별 sequence를 증가시킨다.
3. GameSession은 최근 eventId window와 마지막 sequence를 확인한다.
4. duplicate 또는 rewind sequence는 적용하지 않고 계수한다.
5. 회전·구독 재시작 시 이벤트 스트림 replay를 0으로 한다. 상태는 checkpoint로 복원한다.

## Fixed timestep

- simulation: 60Hz fixed tick
- 최대 catch-up tick: 3~5개로 제한
- 초과 지연은 simulation을 무한 추격하지 않고 pause/skip 정책으로 계측한다.
- gesture event는 tick 경계에서 순서대로 적용한다.
- renderer는 이전/현재 snapshot을 interpolation하되 규칙을 변경하지 않는다.

## Cancellation과 lifecycle

- camera scope와 game scope를 분리한다.
- background 진입 시 camera/vision scope를 취소하고 GameSession을 pause한다.
- 화면 회전은 GameSession을 파괴하지 않고 presentation/camera binding만 재생성한다.
- camera switch는 calibration revision을 올리고 이전 revision event를 폐기한다.

## Stop condition

다음 중 하나가 관측되면 기능 개발보다 pipeline 수정이 우선이다.

- 동일 eventId가 두 번 game state에 적용됨
- timestamp 역전
- analyzer queue가 2개 이상 지속 누적
- render thread에서 추론·파일 I/O 수행
- camera switch 후 이전 lens의 event가 적용됨
- game timer가 pause 동안 진행됨
