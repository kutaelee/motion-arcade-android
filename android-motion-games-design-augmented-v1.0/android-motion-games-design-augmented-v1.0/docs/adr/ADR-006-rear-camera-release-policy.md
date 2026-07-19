# ADR-006 — 후면 카메라 Full-play 출시 정책

- Status: Accepted with conditions
- Date: 2026-07-14

## Context

후면 카메라 지원 요구가 있으나 일반 배치에서는 사용자가 화면을 볼 수 없다. 게임은 시각적 예고와 안전 메시지를 요구한다.

## Decision

- 전면 카메라를 기본값으로 한다.
- 후면 카메라 full-play는 화면 가시성 setup을 실제로 검증한 경우만 승인한다.
- 미검증 기기/setup에서는 실험적 표시, 전면 권고, 또는 gameplay 차단 중 하나를 선택한다.
- audio-only로 요구를 충족했다고 가정하지 않는다.

## Approval condition

사용자가 핵심 cue를 인지하고 안전하게 완주한 물리 테스트 증거가 필요하다.

## Status implication

현재 설계만으로는 `Manual review required before release`다.
