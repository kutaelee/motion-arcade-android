# ADR-001: Four-module Android architecture

- Status: Accepted
- Date: 2026-07-14
- Risk tier: 1

## Context

The design SSOT requires game logic to remain replaceable and testable without
MediaPipe while camera frames remain ephemeral.

## Decision

Use `:app`, `:vision`, `:game-core`, and `:games` with the dependency direction
recorded in `docs/architecture/module-boundaries.md`. `:app` is the only composition
root. No reverse dependency or game-to-vision dependency is allowed.

## Alternatives

1. Single application module — rejected because dependency and privacy boundaries
   cannot be enforced mechanically.
2. Controller/service/repository modules — rejected because they split one runtime
   flow by layer while leaving game and vision contracts coupled.
3. Four responsibility modules — selected because fixtures can replace vision and
   each rules engine remains deterministic.

## Consequences

Gradle dependency reports and source-import checks become gate evidence. Cross-module
data must use explicit immutable contracts.

## Rollback

Revert to the `slice-0a-start` tag before implementation modules contain stateful
data. No data migration is involved.
