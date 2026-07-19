# Slice 1B policy independent review 02

## Reviewed target

- Commit: `6e2b8b74191062482031c4ac0f76f8bf5893c1a5`
- Tree: `10f99d4952c6262aaf5483b94fbce9d5a41ba090`
- Policy revision: `capability-v2`
- Review mode: independent, read-only, SSOT/API/bytecode counterexamples
- Physical devices observed: 0
- Verdict: `REJECT / REWORK`

The reviewer confirmed that all five P1 and five P2 v1 findings were materially
closed. The v2 contract nevertheless exposed four new P1 and six new P2 issues.
This record does not approve v3; a new reviewer must inspect its actual clean commit.

## Finding-to-resolution register

| Priority | Finding | Counterexample or risk | Proposed `capability-v3` resolution | State |
| --- | --- | --- | --- | --- |
| P1 | All valid solo candidates below 20 had no selected path | 19/18/17 FPS produced zero eligible candidates, so no ten-second proof | Rank and select a measured below-floor representative, then run fresh selected steady | Addressed; rereview pending |
| P1 | Pipeline age hid CameraX buffer residence | Two seconds before callback plus 20 ms inference looked 20 ms old | Require REALTIME sensor source and use completion minus source timestamp as frame age; unknown source cannot tier | Addressed; rereview pending |
| P1 | LIVE_STREAM submission was mistaken for warm-up completion | Flow limiting can discard async inputs and timestamps can collide in milliseconds | One outstanding task, exactly five correlated successful callbacks, source-relative strict task timestamp, phase barrier | Addressed; rereview pending |
| P1 | ImageProxy/MPImage conversion and lifetime conflicted | Async MPImage could use a proxy already closed, while a broad copy stop blocked the official conversion | Fixed RGBA_8888 input, one callback-bounded direct-buffer copy, ByteBuffer MPImage, result barrier, explicit close/quiesce order | Addressed; rereview pending |
| P2 | Steady minimum completion semantics absent | Slow complete run versus interrupted run could classify differently | Support-ready minimum 30 and five per bucket; structurally complete low count is below floor, interruption is incomplete | Addressed; rereview pending |
| P2 | Selected delegate made cache lookup circular; digest bytes undefined | Delegate is unknown until after the lookup | Base-scope lookup, one replaceable result adding delegate, duplicate fail-closed, domain/length/exact UTF-8 digest encoding | Addressed; rereview pending |
| P2 | Resolution fallback comparator ambiguous | Exact 4:3 versus approximate candidates and portrait sizes could tie differently | Positive candidate set, orientation normalization, rational aspect comparator, area/edge tie-break, actual negotiated scope | Addressed; rereview pending |
| P2 | CPU fallback preconditioning differed from normal path | Fallback reused one runtime for candidate and steady | Fresh CPU candidate runtime then a second fresh CPU selected runtime, each with five callbacks | Addressed; rereview pending |
| P2 | Drop categories and denominators absent | CameraX overwrite, source rejection, input/inference error, and late result could double count | Mark upstream overwrite unobservable; use mutually exclusive source/input/error/timeout dispositions, conservation, and fixed integer denominators | Addressed; rereview pending |
| P2 | Drain/fatal/late and memory/render failure semantics absent | A post-freeze callback could not both be counted and leave result immutable | Freeze outstanding as timeout, keep post-freeze callback volatile, include fatal events through drain, define PSS/FrameMetrics and release-partial semantics | Addressed; rereview pending |

## Required v3 rereview fixtures

- 19/18/17 FPS candidate selection and all-runtime-failed distinction;
- REALTIME two-second buffer-age failure and unknown-timebase no-tier result;
- five callback barriers, task timestamp collision/overflow, stale/missing result;
- RGBA stride/capacity/close order/timeout/late-callback ownership;
- 0/29/30 structurally complete steady completions versus interruption;
- base/result cache, duplicate records, exact digest bytes, exact rotation;
- rational resolution ties and negotiated-size mismatch;
- symmetric CPU fallback runtime identities and reset;
- disposition conservation, zero denominator, fixed source FPS;
- fatal-at-drain, frozen timeout, volatile late callback, release-partial metrics.

## Approval boundary

No Slice 1B code implementation may begin until an independent reviewer inspects a
clean `capability-v3` commit and reports no actionable P0/P1/P2 policy defect. No
emulator evidence approves a physical tier, G2, or G8.
