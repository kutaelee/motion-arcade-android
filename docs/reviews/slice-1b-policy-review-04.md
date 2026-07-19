# Slice 1B policy independent review 04

## Reviewed target

- Commit: `89bb54eecc1115df49d384518b4ff25199818c6e`
- Tree: `5b4750cc7f7ba1e7249fb0655bd9a25c05908de5`
- Policy revision: `capability-v4`
- Review mode: three fresh independent read-only SSOT/domain/runtime reviews
- Physical devices observed: 0
- Verdict: `REJECT / REWORK`

All reviewers began and ended on the exact clean target and made no repository edits.
No P0 was found. Deduplicating overlap and taking the highest priority assigned by any
reviewer produced ten P1 and seven P2 findings. This record does not approve v5; a
fresh reviewer must inspect its actual clean commit.

## Direct pinned-runtime evidence

- tasks-core 0.10.35 AAR SHA-256:
  `4EDF2F33C840D682C751B3CA951B1AE0465373734776D7FB37DCEF936DE28AD0`;
- tasks-vision 0.10.35 AAR SHA-256:
  `E2F0E0B63DEA220D0424802FBBD4050DFB3B19C616B969C48F12D6C49CF94CDE`;
- camera-core 1.6.1 AAR SHA-256:
  `A36F1C323851B51215BA476640E1EB4153882B0C368C0376E9FB905E689DF84A`;
- camera-camera2 1.6.1 AAR SHA-256:
  `10D8F0102468D10A311FDF73695FC29A6A69A738BB0445FBCFFD9B9C037CB36B`.

Pinned `TaskOptions.convertBaseOptionsToProto` handles CPU/TFLite and GPU only; enum
NPU falls through with empty acceleration. ErrorListener has no timestamp. Direct
buffer packet creation copies by base address/capacity before a normal detect return.
MPImage close and TaskRunner send use dependency-internal synchronization, and
TaskRunner close can block in native graph completion.

## Finding-to-resolution register

| Priority | v4 finding | Counterexample or risk | Proposed `capability-v5` resolution | State |
| --- | --- | --- | --- | --- |
| P1 | NPU enum was ranked although no NPU acceleration serialized | Default/empty acceleration could be persisted as NPU | CPU/GPU only; explicit pinned-API NPU unavailable; future adapter needs new ADR/G0 | Addressed; rereview pending |
| P1 | `numPoses`/empty scene could pass representative workload | Blank wall passed solo; one person passed dual compute | Exact aggregate 1/2-pose occupancy in every quartile; absence/instability incomplete, no cache/tier | Addressed; rereview pending |
| P1 | Four rates but “all three FPS” | Source FPS inclusion changed tier for identical other evidence | Name only callback/completion/representative completion as tier inputs; others diagnostic | Addressed; rereview pending |
| P1 | Equality and deadline epochs contradicted | Finalizer at exact deadline could timeout an equality-pass callback | Normative typed start/end table; completion `<=`, expiry only `now >` | Addressed; rereview pending |
| P1 | Drain admitted unaccounted post-end frames | Callback at end+100 ms could be ignored/counted/submitted | Close source admission at end; outside-window diagnostic only; admitted frames alone drain | Addressed; rereview pending |
| P1 | Singular sentinel erased prior delegate failures | CPU failure→GPU crash or GPU→CPU crashes could alternate forever | Active plus bounded sorted per-scope recovery set; atomic preserving transitions | Addressed; rereview pending |
| P1 | Journal/cache precedence and save ordering absent | Matching cache could survive ACTIVE crash or save before close | Recover before cache; matching unclean invalidates; save after clean close + active clear | Addressed; rereview pending |
| P1 | Resource/persistence failures missing from effect matrix | Close/input/buffer/journal/cache errors produced different fallback/cache | Total boundary matrix; uncertain resource restart-only, precreate journal prevents native, save failure no tier | Addressed; rereview pending |
| P1 | Dirty/unavailable tree could not identify crashing APK | Same dirty binary retried forever or different bytes shared quarantine | Hash exact installed base/split APK bytes as mandatory RuntimeArtifactId/RecoveryBuildId | Addressed; rereview pending |
| P1 | Submission timeout lacked late-return cleanup | A 1.1 s return could retain input/buffer/proxy until process death | Analyzer returns/proxy closes; owner generation-guarded late close/full zero/release; no state revival | Addressed; rereview pending |
| P2 | Claimed one-completion runtime contradicted watchdogs | Exact 5/10 s with one completion necessarily stalled | Separate pure low-sample math from runtime-reachable traces; no hard 1-29 runtime claim | Addressed; rereview pending |
| P2 | Solo after dual incomplete “may remain” | Cache/work differed by implementation | Solo and dual independent; clean solo must save first and always remain | Addressed; rereview pending |
| P2 | ML A could claim full effects without game render | P99 100 ms/missing FrameMetrics still yielded full effects | Separate ML and EffectCapability; v5 effects always conservative-unverified | Addressed; rereview pending |
| P2 | Stage 0 said remote CI open after verified CI | Status contradicted direct run evidence | Mark technical baseline/remote CI verified; retain physical/legal release gates | Addressed; rereview pending |
| P2 | Literal “no lock” impossible with pinned library | Dependency TaskRunner/MPImage are synchronized | Prohibit app-owned locks across calls; explicitly allow dependency internals | Addressed; rereview pending |
| P2 | Timestamp-less ErrorListener had no correlation rule | Error could poison a newer task or become unexplained incomplete | Current generation + exactly one outstanding maps error; otherwise stale diagnostic | Addressed; rereview pending |
| P2 | Render FPS and critical thermal boundary undefined | Different numerator/duration/status choices changed G8 evidence | Unique matched FrameMetrics/exact duration; API29 status >= CRITICAL; listener lifecycle | Addressed; rereview pending |

## Required v5 rereview fixtures

- NPU enum to empty-acceleration bytecode counterexample and exact unavailable state;
- blank-wall solo, one-/two-pose quartile occupancy, representative FPS subset;
- exact three FPS inputs with source-FPS counterexample;
- every deadline exact/+1 ns and start-transition table;
- post-end callback versus admitted drain completion conservation;
- CPU→GPU and GPU→CPU cross-launch crash state preservation;
- recovery-before-cache and close/clear/save crash points;
- every resource/persistence matrix row and unsafe no-fallback path;
- exact installed base/split mutation, missing hash, dirty APK cross-launch identity;
- submission timeout then late/permanent return cleanup;
- pure versus runtime-reachable low samples and exact idle anchor;
- independent solo save through all dual outcomes;
- ML A plus bad/missing render retains conservative effects;
- app-owned versus dependency lock, timestamp-less errors, render/thermal boundaries.

## Approval boundary

No Slice 1B implementation may begin until an independent reviewer inspects a clean
`capability-v5` commit and reports no actionable P0/P1/P2 defect. Emulator evidence
does not approve a physical tier, effect promotion, G2, or G8.
