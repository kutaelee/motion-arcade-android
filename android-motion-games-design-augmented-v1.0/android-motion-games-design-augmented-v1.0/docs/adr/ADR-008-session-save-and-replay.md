# ADR-008 — 세션 Checkpoint와 결정적 Replay

- Status: Accepted
- Date: 2026-07-14

## Context

seed만 저장하면 중간 복원 시 RNG 위치와 입력 적용 순서가 달라질 수 있다. camera/pose transient state를 저장하면 개인정보와 복구 안정성이 나빠진다.

## Decision

- safe checkpoint에서만 session snapshot을 저장한다.
- seed, PRNG algorithm/version/internal state, simulation tick, content revision을 저장한다.
- raw pose, gesture candidate, camera state는 저장하지 않는다.
- 복원 후 항상 PAUSED→camera/calibration→countdown→RUNNING 흐름을 따른다.

## Consequences

중간 세션 재현과 중복 보상 방지가 가능해진다. custom deterministic PRNG와 migration tests가 필요하다.
