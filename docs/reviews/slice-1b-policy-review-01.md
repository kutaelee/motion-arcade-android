# Slice 1B policy independent review 01

## Reviewed target

- Commit: `3202df0095fd6e75ee675b070084674664be487f`
- Tree: `e70150ee2705e6134f85760e51a7a1285bdd1948`
- Policy revision: `capability-v1`
- Review mode: independent, read-only, counterexample-driven
- Physical devices observed: 0
- Verdict: `REJECT / REWORK`

The review found no P0 issue, five P1 issues, and five P2 issues. This record does
not turn the proposed remediation into an approval; a new clean review must inspect
the actual corrected commit.

## Finding-to-resolution register

| Priority | Finding | Counterexample or risk | Proposed `capability-v2` resolution | State |
| --- | --- | --- | --- | --- |
| P1 | Exact 15 FPS could fall below 14.999 FPS | 15 FPS with 80 ms tail failed A and was excluded from B | Evaluate A first; B accepts every dual >=10 FPS that passes conditional rules, with no `<15` upper bound | Addressed; rereview pending |
| P1 | C, Unsupported, and incomplete overlapped | Requested dual sample shortage matched both incomplete and C | Partition transient/incomplete, permanent scoped failure, and completed measurement; incomplete is never cache-valid | Addressed; rereview pending |
| P1 | Source and monotonic clocks could be mixed | Camera2 unknown timestamp source can have an arbitrary offset | Window membership uses analyzer-entry elapsed realtime; source timestamps are only compared within their domain; pipeline age uses one monotonic clock | Addressed; rereview pending |
| P1 | Sparse delegate could win on low P95 | 30 completions in five seconds could pass candidate sampling at 6 FPS | Require camera and completion FPS eligibility; select solo/dual independently and rank dual support class before P95 | Addressed; rereview pending |
| P1 | CPU fallback could mix statistics | GPU samples could survive a CPU fallback | Close failed runtime, create CPU, repeat five warm-ups, eligibility, and fresh ten-second window; discard failed-attempt statistics | Addressed; rereview pending |
| P2 | Quartile membership/rounding undefined | Sample-count versus time buckets changes medians | Use time-based half-open first/last quarters, nearest-rank median, minimum five samples each | Addressed; rereview pending |
| P2 | Cache missed OS/actual-camera changes | OS/driver or logical camera could change under the same lens label | Add digested OS-build and CameraX identifier invalidation tokens; no identifier means no cross-process cache hit | Addressed; rereview pending |
| P2 | Thermal endpoint unavailable on API 26-28 | Public thermal status starts at API 29 | Record `THERMAL_UNAVAILABLE_API`; allow functional measurement but never infer `NONE` or pass G8 | Addressed; rereview pending |
| P2 | Resolution fallback conflicted with physical gate | Runtime allowed fallback while acceptance demanded exact 640 x 480 | Deterministically select/record actual fallback and make evidence scope exact; never relabel it as 640 x 480 | Addressed; rereview pending |
| P2 | Mandatory distributions were reduced | SSOT requires camera/inference/render rates and tail distributions | Require separate FPS plus P50/P90/P95/P99 latency distributions; mark gesture/event latency deferred rather than passed | Addressed; rereview pending |

## Required rereview fixtures

- exact 15 FPS with full-tail failure and conditional-tail success;
- mutually exclusive unrequested/incomplete/permanent/valid-below-10 outcomes;
- large source-clock offset, unknown timebase, exact end, and drain deadline;
- sparse low-P95 candidate and independent dual selection;
- fallback warm-up/statistics reset and no second retry;
- time-quartile boundaries and insufficient bucket samples;
- every cache-key invalidation and no-identifier behavior;
- API 26-28 thermal-unavailable behavior;
- deterministic resolution fallback and actual scope;
- complete rate/latency evidence schema.

## Approval boundary

No capability implementation may begin until a reviewer inspects a clean corrected
commit and reports no actionable P0/P1/P2 policy defect. No emulator review can
approve a physical tier, G2, or G8.
