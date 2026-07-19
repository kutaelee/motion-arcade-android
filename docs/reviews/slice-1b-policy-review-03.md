# Slice 1B policy independent review 03

## Reviewed target

- Commit: `919132158839d309d0180e1af42b0f0406383298`
- Tree: `b4c7328b4021f4f1d7f36a22165b7c6e30c1e078`
- Policy revision: `capability-v3`
- Review mode: three independent, read-only SSOT/domain/runtime/API reviews
- Physical devices observed: 0
- Verdict: `REJECT / REWORK`

The exact clean target was reviewed independently as a full policy, as a pure-domain
contract, and against pinned Android runtime bytecode/source. No reviewer edited the
tree. No P0 was found. Taking the highest priority assigned by any reviewer and
deduplicating overlap produced seven P1 and six P2 findings. This record does not
approve v4; a fresh reviewer must inspect its actual clean commit.

## Direct evidence

- ZIP SHA-256:
  `C998B9EB5F102F231BB626F50864C881E7D0E83E9ADEE5868E363D4FF8094330`;
- ZIP projection: 66/66 byte-identical, 0 missing/mismatch/encrypted; CRC passed;
- pinned versions: CameraX 1.6.1, MediaPipe Tasks 0.10.35, minSdk 26;
- tasks-vision AAR SHA-256:
  `E2F0E0B63DEA220D0424802FBBD4050DFB3B19C616B969C48F12D6C49CF94CDE`;
- tasks-core AAR SHA-256:
  `4EDF2F33C840D682C751B3CA951B1AE0465373734776D7FB37DCEF936DE28AD0`;
- camera-core AAR SHA-256:
  `A36F1C323851B51215BA476640E1EB4153882B0C368C0376E9FB905E689DF84A`;
- camera-camera2 AAR SHA-256:
  `10D8F0102468D10A311FDF73695FC29A6A69A738BB0445FBCFFD9B9C037CB36B`.

Pinned `PoseLandmarker` bytecode/source converts output packet `IMAGE:image_out` to
a distinct Bitmap-backed MPImage and `OutputHandler` passes it to the listener without
closing it. Pinned `TaskRunner.close()` synchronously closes packet sources, waits for
the graph, and tears down without a timeout. CameraX
`CameraIdentifier.getInternalId()` is restricted to the library group; public
experimental Camera2 interop supplies a changeable camera ID instead.

## Finding-to-resolution register

| Priority | v3 finding | Counterexample or risk | Proposed `capability-v4` resolution | State |
| --- | --- | --- | --- | --- |
| P1 | One-MPImage/no-Bitmap ownership contradicted pinned runtime | Closing submitted input still leaked the separate callback Bitmap; stale/late paths leaked it too | Name submitted and callback images; permit dependency output conversion; exact-once callback close before barrier on every path | Addressed; rereview pending |
| P1 | Timeout cleanup synchronously closed an unbounded TaskRunner first | Native graph stall could retain buffer/proxy and block analyzer/lifecycle or deadlock on a held lock | Release returned-submission input before result wait; dedicated owner/watchdog, quarantine, async non-joined teardown, clean-process recovery | Addressed; rereview pending |
| P1 | Persistent camera token required restricted API | Strict lint or logical-camera `getInternalId()` failure made the contract unimplementable | Public Camera2 interop token, no suppression, re-read before lookup/save, process-local on absence | Addressed; rereview pending |
| P1 | Processing errors could still pass by high throughput | 300 accepted / 200 complete / 100 inference errors could meet 20 completion FPS | Total failure-effect matrix; any processing/source error blocks support; fixed dispositions and conservation | Addressed; rereview pending |
| P1 | Native delegate abort could loop every first launch | Process died before fallback/cache, then retried the same crashing delegate forever | Durable aggregate AtomicFile sentinel, exact quarantine, role-aware next-launch recovery, one bounded manual retry | Addressed; rereview pending |
| P1 | Drain deadline lacked a linearization point | Callback captured at the deadline but finalizer froze first on another executor | One short serial state gate; transition clock capture inside gate; admitted callback reservation/disposal and atomic freeze | Addressed; rereview pending |
| P1 | SSOT automatic CPU fallback remained `may/can` | Same selected GPU failure could fallback or become Unsupported | Mandatory exactly-once CPU route for enumerated selected non-CPU failures; enumerated no-fallback causes and prior-CPU rules | Addressed; rereview pending |
| P2 | Low-sample structural completeness and stall overlapped | Zero callbacks/completions could be either below floor or incomplete | First-frame/idle/task watchdogs; zero never valid; exact-duration 1-29 successful completions may be below floor | Addressed; rereview pending |
| P2 | Cache omitted the actual workload/build identity | Same versionCode could change library/options/adapter and reuse an old result | Complete serialized workload plus content-addressed runtime build identity in base scope | Addressed; rereview pending |
| P2 | Mid-probe scope changes could mix statistics | Rotation/crop/bound configuration changed inside steady window | Per-callback and lifecycle scope guard; `SCOPE_CHANGED` abort/rebind | Addressed; rereview pending |
| P2 | Tail/growth equality remained ambiguous | Equal P95 or growth could pass or fail by implementation | Inclusive class table with exact rates, P95, four-quartile growth, max age, and zero-error rules | Addressed; rereview pending |
| P2 | FrameMetrics reuse/drop/membership was unspecified | Delayed/reused callback or report drops biased percentiles | Immediate scalar copy, intended-vsync active-set membership, drop/invalid/late accounting, drain/remove lifecycle | Addressed; rereview pending |
| P2 | `finite/positive crop` rejected normal zero origin | Full-frame `(0,0,w,h)` could be treated invalid | Exact integer bounds and full-frame-copy/coordinate-only crop semantics | Addressed; rereview pending |

## Required v4 rereview fixtures

- two-image ownership for matched/mismatch/stale/late/error/disposal paths;
- create/submission/result/teardown hang and held-lock deadlock counterexamples;
- restricted-API lint and Camera2 token absent/change/logical-camera paths;
- failure-effect matrix, high-throughput error, mandatory and forbidden fallback;
- unclean candidate/selected/CPU launch, controlled abort, teardown pending, bounded
  manual retry, and build mismatch;
- callback/finalizer adversarial ordering at exact end/drain boundaries;
- zero callback/completion versus exact 1/29/30 low samples and gap equality;
- workload/options/AAR/build mutations and dirty-build process-local behavior;
- rotation/crop/size/token scope change and zero-origin crop;
- exact FPS/P95/growth/age equality and four-quartile samples;
- FrameMetrics reuse, `-1`, intended-vsync membership, drop, late callback, and remove.

## Approval boundary

No Slice 1B implementation may begin until an independent reviewer inspects a clean
`capability-v4` commit and reports no actionable P0/P1/P2 policy defect. No emulator
evidence approves a physical tier, G2, or G8.
