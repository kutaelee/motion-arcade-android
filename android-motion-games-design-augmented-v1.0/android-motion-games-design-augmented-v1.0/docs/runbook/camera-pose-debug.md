# Runbook — 카메라·포즈 디버깅

## 우선순위

1. 사용자 안전과 game pause
2. camera binding/frames
3. coordinate transform
4. model inference
5. player tracking
6. gesture thresholds

threshold부터 조정하지 않는다.

## 증상별 확인

### Skeleton이 옆으로/반대로 보임

- rotationDegrees
- PreviewView scale type/ViewPort
- front display mirror 중복
- crop rect
- canonical anatomical left/right fixture

### FPS는 높지만 반응이 느림

- source frame age
- inferenceCompleted-source timestamp
- callback queue
- event queue latency
- game tick catch-up

평균 FPS만 보지 않는다.

### 두 사람이 swap

- assignment cost components
- first/second margin
- lane penalty가 과도한지
- ambiguity state 진입 여부
- occluded prediction으로 confirm했는지

### 특정 gesture만 누락

- required landmark confidence
- calibration scale drift
- candidate cancel reason
- exclusivity conflict
- user/stance별 holdout 결과

## 안전한 변경

- 진단 overlay 활성화
- 해상도/빈도 한 단계 조정
- fixture 재생
- config branch에서 threshold 실험

## Stop

- raw frame 저장 요청
- release log에 landmarks 출력
- user data를 fixture로 무단 전환
- 테스트를 통과시키기 위해 confidence gate 삭제
