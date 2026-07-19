# Motion Eval Contract

## Dataset partitions

- `tune`: threshold/feature development
- `validation`: implementation regression
- `release_holdout`: release 판단 전까지 봉인
- `adversarial`: neutral/hard negative/safety abuse

같은 사용자 또는 같은 recording session이 tune과 holdout에 동시에 있으면 안 된다.

## Unit of evaluation

- gesture occurrence 단위 precision/recall
- neutral minute 단위 false event rate
- player-event attribution
- end-to-end latency distribution
- state-transition correctness

## Required cases

- 정상/느림/작음/반대/취소/낮은 confidence
- 다른 gesture와 혼동
- neutral everyday movement
- occlusion
- orientation/lens change
- dual simultaneous and staggered
- one-arm/low-intensity modes

## Critical assertions

- duplicate confirmed event 0
- stale calibration revision event applied 0
- ambiguous ID 상태 공격 적용 0
- pause 중 game tick advance 0
- low confidence를 player miss로 점수 차감 0
