# 오프라인 관측 가능성·지원 설계

## 목표

외부 telemetry 없이도 사용자가 허용한 범위에서 성능·인식·복구 문제를 재현할 수 있어야 한다. 관측 가능성이 개인정보 원칙을 침해하면 안 된다.

## Metric planes

### Camera

- bound lens/orientation/resolution
- source FPS
- analyzer invocation/drop count
- frame age

### Vision

- inference P50/P95/P99
- pose count
- delegate/model revision
- low-confidence duration
- transform invalid count

### Tracking/Gesture

- track state per player
- ambiguity count/duration
- candidate/confirmed/cancel reason counts
- duplicate event count
- event end-to-end latency

### Game

- simulation tick drift
- input queue depth
- pause reason timeline
- fixed-step catch-up count
- save/restore status

### Render/System

- render frame P50/P90/P99
- memory class/current usage
- thermal tier
- quality degradation level

## Privacy tiers

- **Release default**: 집계 수치와 상태 코드만
- **Developer overlay**: joint confidence와 skeleton을 화면에만 표시, 저장 금지
- **User-exported diagnostic**: 집계 + timeline; pose는 별도 opt-in
- **Test fixture capture**: 개발자가 사전 동의된 테스트 환경에서 생성; 앱 사용자 데이터와 분리

## In-memory ring buffer

최근 2~5분의 structured diagnostic event를 bounded ring에 유지한다. background/process death 시 기본 폐기한다. 저장하려면 사용자 export 액션이 필요하다.

이벤트 예:

```text
CAMERA_BOUND
DELEGATE_FALLBACK
CALIBRATION_DRIFT
TRACK_AMBIGUOUS
GAME_PAUSED(reason)
EVENT_DUPLICATE_REJECTED
THERMAL_DEGRADE(level)
CHECKPOINT_SAVED
```

## Support code

결과/오류 화면에 비식별 support code를 표시할 수 있다.

```text
gameId + appVersion + failureCategory + local counter
```

카메라 frame, pose, device serial, account 정보는 포함하지 않는다.

## 성능 보고 규율

- mean 하나만 보고하지 않는다.
- warm-up 구간과 steady-state를 분리한다.
- rotation/rebind 구간을 별도 표시한다.
- thermal 시작/종료를 기록한다.
- 실제 측정하지 않은 기기는 표에 `not tested`로 남긴다.

## 실패 시 최소 증거

1. app/build version
2. device/OS/SoC
3. lens/orientation/mode
4. quality tier/delegate/resolution
5. pause/error reason timeline
6. latency/drop histograms
7. 재현 단계
8. raw image 미포함 확인

## Open loop

외부 crash analytics가 없으므로 전체 사용자 crash rate는 관측할 수 없다. 이를 “crash 없음”으로 표현하면 안 된다. 출시 후 지원 전략은 수동 진단 export와 스토어 crash console 등 플랫폼 제공 수단의 개인정보 조건을 별도 검토해야 한다.
