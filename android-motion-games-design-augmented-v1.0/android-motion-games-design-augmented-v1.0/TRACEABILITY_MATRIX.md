# 요구사항 추적성 매트릭스

| 원본 관심사 | 보강된 계약 | 검증 게이트 | 핵심 증거 |
|---|---|---|---|
| CameraX / 회전 / 미러 | `docs/03-camera-coordinate-contract.md` | G2 | transform tests, overlay 캡처 |
| LIVE_STREAM / backpressure | `docs/02-runtime-architecture-and-concurrency.md` | G2, G8 | latency histogram, drop count |
| 1인·2인 추적 | `docs/05-two-player-tracking-and-fairness.md` | G4 | ID timeline, ambiguity pause |
| gesture state machine | `docs/04-pose-motion-recognition-tacit-guide.md` | G3 | holdout confusion matrix |
| 세션 회전·복구 | `docs/06-lifecycle-state-persistence.md` | G1, G9 | process recreation tests |
| 기기 성능 | `docs/07-device-capability-thermal-degradation.md` | G8 | capability report, thermal trace |
| 낚시/권투/몬스터 | `docs/08-gameplay-safety-recovery-balance.md` | G3, G5 | end-to-end replays, safety tests |
| 개인정보·권한 | `docs/09-privacy-security-threat-model.md` | G7 | manifest scan, export inspection |
| 관측 가능성 | `docs/10-observability-offline-support.md` | G8, G9 | redacted diagnostic bundle |
| 테스트·Acceptance | `docs/11-eval-release-gates.md` | G0~G9 | evidence index |
| OSS·버전 고정 | `docs/12-build-supply-chain-release.md` | G0, G7 | dependency verification, notices |
| ImageGen 에셋 | `assets/README_IMAGEGEN_SSOT.md` + ADR-004 | G6 | anchor, registry, manifest, contact sheet |
| 에셋 일관성 | 게임별 `assets/style-profiles/` | G6 | style review scorecard |
| Approval Gate | `DESIGN_SSOT.md` | G9 | 승인 기록 |
