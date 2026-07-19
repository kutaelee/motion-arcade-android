# Runbook — Motion threshold tuning

## Objective

precision/recall을 개선하되 안전·공정성·프레임률 독립성을 깨지 않는다.

## 절차

1. 실패 gesture와 비용 유형(false positive/negative)을 분류한다.
2. holdout을 열기 전에 tune set에서 재현한다.
3. candidate state, feature trace, cancel reason을 본다.
4. 하나의 가설만 변경한다.
5. 전체 confusion matrix와 neutral false event/min을 재실행한다.
6. dual attribution과 low-confidence case를 회귀한다.
7. config revision과 근거를 기록한다.
8. release holdout은 마지막에 한 번 평가하고 반복 튜닝에 사용하지 않는다.

## 변경 우선순위

1. 잘못된 좌표/시간/스케일 수정
2. required landmark와 confidence gate 수정
3. feature 조합 수정
4. hysteresis/duration/cooldown 수정
5. 임계값 미세 조정
6. gesture 단순화 또는 제거

## 금지

- 한 사용자 영상에 맞춘 threshold
- frame count 기반 duration
- neutral false positive를 숨기기 위한 과도한 cooldown
- confidence를 quality로 대체
- holdout 결과를 보고 반복 튜닝

## Required evidence

- before/after config diff
- tune set metrics
- frozen holdout metrics
- per-user distribution
- confusion matrix
- false event/min
- latency 영향
