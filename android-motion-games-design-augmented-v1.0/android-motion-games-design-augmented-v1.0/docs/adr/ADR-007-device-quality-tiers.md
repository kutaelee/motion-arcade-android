# ADR-007 — 측정 기반 Device Quality Tier

- Status: Accepted
- Date: 2026-07-14

## Context

API level이나 모델명만으로 CameraX/MediaPipe 성능과 안정성을 판단할 수 없다.

## Decision

first-run/diagnostic capability probe로 mode support와 effect tier를 정한다. 기기명 allowlist는 보조 자료일 뿐 SSOT가 아니다.

- Tier A: solo+dual full
- Tier B: solo+conditional dual/reduced effects
- Tier C: solo low effects
- Unsupported: safe block

simulation timing과 gesture thresholds는 tier에 따라 바꾸지 않는다.
