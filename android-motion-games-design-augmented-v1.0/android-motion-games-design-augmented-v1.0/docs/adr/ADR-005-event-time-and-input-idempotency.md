# ADR-005 — Monotonic Event Time과 입력 Idempotency

- Status: Accepted
- Date: 2026-07-14

## Context

CameraX, MediaPipe callback, UI, game loop은 서로 다른 주기와 thread에서 동작한다. frame count나 wall clock을 사용하면 회전·지연·재콜백에서 cooldown과 damage가 달라질 수 있다.

## Decision

- 모든 판단은 monotonic nanosecond time을 사용한다.
- confirmed gesture는 player별 sequence와 결정적 eventId를 가진다.
- GameSession은 eventId와 sequence로 중복/역순을 거부한다.
- game rule transition은 fixed simulation tick에서만 수행한다.

## Consequence

진단과 replay가 가능해지고, 회전/재구독 중복이 줄어든다. 대신 timestamp mapping과 queue 계측이 필요하다.
