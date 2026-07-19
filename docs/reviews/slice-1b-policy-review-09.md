# Slice 1B capability policy review 09

## Verdict and reviewed snapshot

**REJECT / REWORK.** Three independent read-only passes rejected exact commit
`9fbd391553b9260128c7dee52e98e40f33b0ad64` (tree
`8430416f87d9627c018fef2a66e5d834695a33b2`) and its `capability-v9` policy with no
P0, five deduplicated P1, and two deduplicated P2 findings. The branch and worktree
were clean at the start and end of every pass. The reviewed normative bytes were:

| Document | SHA-256 |
| --- | --- |
| `docs/adr/ADR-011-measured-capability-probe-policy.md` | `E45B9946EE8188021F039836E13FFA27B2789BF15531272E1C4476B3A6ACD6AC` |
| `docs/contracts/capability-store-v4.md` | `8021913FD86A75105D613C278F09D14EDEEAF31AA1908276C03394C8E4357667` |
| `docs/contracts/recovery-journal-v4.md` | `C3FEEE4E28F27574D158AE08858AA14F72422583DCEDB3CCF2553A0AFEB682FA` |
| `docs/execution/slice-1b-contract.md` | `CBA0DA535DF96F274C4B0E04FC6B3056FBA36E633A817A4B060F393B0BD8BD05` |

The package ZIP remained SHA-256
`C998B9EB5F102F231BB626F50864C881E7D0E83E9ADEE5868E363D4FF8094330`.
No implementation, physical-device result, G2/G8 result, or release approval was
credited.

## Findings and required v10 resolution

| Priority | v9 defect and counterexample | Required `capability-v10` resolution |
| --- | --- | --- |
| P1 | **`android.util.AtomicFile` is not one recovery protocol across minSdk 26 through API 37.** API 26-29 moves the prior base to `.bak` and writes base directly; API 30+ uses `.new`. A process death after the legacy base-to-`.bak` move leaves a valid old `.bak` and partial base, which v9 rejects as corruption. | Remove the platform-version dependency or define complete API-specific grammars. The selected correction must use one app-owned, public-API, same-directory atomic-replace protocol with checked pre-authorization sync, exact post-operation inspection, and API 26/27/28/29/30+/37 crash fixtures. |
| P1 | **Thermal listener removal does not fence Binder-to-executor admission.** An oneway callback can enter the client Binder queue before unregister, pause before `executor.execute`, then arrive after wrapper seal, store commit, final clear, or first serve. Process death before a later poison mutation leaves the saved record unpoisoned. | Treat public thermal delivery as non-authoritative unless an actual platform happens-before fence is proven. Persist only a conservative delivery-fence-unproven tuple, keep G8 blocked, and use the listener plus current-status snapshots as same-process safety signals rather than proof of complete historical coverage. |
| P1 | **Runtime post-seal poison promises an impossible ordering.** v9 first accepts a no-future-callback proof, yet also says any callback that violates it is durably latched before pending/final clear. A callback injected after final clear disproves that promise, and process death can precede poison persistence. | Make the native fence an explicit artifact/API/ABI-bound prerequisite. Without it, persistence is forbidden. With it, post-proof callback handling is defense-in-depth only: revoke same-process service and attempt durable poison without claiming that an arbitrary future violation was recorded before an earlier clear. Add post-clear/first-serve crash cuts. |
| P1 | **`ActiveV4` has no retry provenance.** With a manual-retry marker for GPU and CPU active in the same epoch, one reader can quarantine CPU as retry 0 while another can consume it as retry 1. Both follow v9 prose and yield different future authorization. | Add an exact `retry_used` bit to ActiveV4 and freeze every start, intermediate, terminal, cancel, recovery, and capacity transition. Active/manual entries in one epoch must cross-validate a single retry route; no reader inference is permitted. |
| P1 | **`ProbeBaseScopeV2.profile_schema_revision` has no exact UTF-8 value.** Two conforming writers can choose different strings and therefore different scope IDs, journal keys, and cache identities. | Freeze the exact UTF-8 value and add exact-value plus one-byte-mutation fixtures. |
| P2 | **Normal-save fresh evidence is undefined.** A same-instance DataStore read is cached and a second live owner is forbidden, so “strict reread” can mean cached state or disk bytes depending on the implementer. | Name one read-only backing-file verifier, its mutation exclusion and owner ordering, exact deadline, parser/size rules, and the save -> verify -> final-clear -> dual-verify sequence. It must not construct a second live DataStore owner. |
| P2 | **The execution contract still points to v8-era inputs and evidence.** It names ADR v8/reviews 01-06, “six rejected records,” v1-v7-to-v8 rules, and complete v8 fixtures while its header says v9. | Update every normative revision/review reference to the exact v10 history and add an automated revision-reference consistency fixture. |

## Platform evidence

The Android-focused reviewer compared API 26-37 public surfaces and official AOSP
source. Relevant immutable source evidence was:

| Source | SHA-256 or reference | Consequence |
| --- | --- | --- |
| Android 8 `AtomicFile.java` | `449E15F8F72A311134B0945D959185C77CC680EA7253CC6ED1CF3D770AD53569` | legacy `.bak` protocol |
| Android 10 `AtomicFile.java` | `250611C835DEE4D0551747A7727D094200A285009CD7143149C6666020103A68` | legacy `.bak` protocol remains |
| Android 11 `AtomicFile.java` | `9DF7E76954767CF0F23B86790368EC94BAA2EA48663276D568FB788AA3022251` | `.new` protocol begins |
| Android 36 `AtomicFile.java` | `90E1CC85E92E53E6C257E49156677DE354A79D2B6CAF65D826CB77D0CFFAB70E` | finish/cleanup remains void or log-only |
| Android 36 `PowerManager.java` | `ECEED282B2A2C36266B5B180FD1D832A8F6F8DD57F53043F5E8C0F39B5063D1C` | Binder callback calls supplied executor; unregister does not drain it |
| `IThermalStatusListener.aidl` | official AOSP interface is `oneway` | server callback enqueue is not an unregister fence |
| AOSP `RenderProxy.cpp` | add/remove observer work is asynchronously posted | v9 FrameMetrics conservative disablement remains correct |

The FrameMetrics v9 remediation passed this audit: public Window evidence remains exact
`UNAVAILABLE/REMOVAL_FENCE_UNPROVEN`, COMPLETE/PARTIAL bytes are reader-invalid, and G8
remains blocked. The review does not infer stronger OEM behavior from a quiet sample.

## Required v10 rereview fixtures

- app-owned atomic writer: old/absent base, partial/full `.next`, checked sync/close,
  authorization immediately before rename, rename success/throw, direct exact reread,
  cleanup failure, exact/+1 deadline, and process-death cuts on API
  26/27/28/29/30+/37;
- manual GPU marker plus active CPU, manual CPU marker plus active CPU, retry-1 terminal
  CPU plus active GPU, exact retry provenance mutation, later authorization, and entry
  capacity at 8/9;
- exact `profile_schema_revision` bytes and every one-byte mutation class used by the
  canonical manifest parser;
- normal DataStore update followed by mutation exclusion, direct fresh-file verify,
  retention-preserving journal clear, dual direct verification, cached-read rejection,
  verifier timeout/throw, and death at every cut;
- thermal oneway callback delayed until after cutoff, store commit, final clear, and
  first serve; CRITICAL handling, exact 64/65 app-FIFO capacity, current-status
  rechecks, and process death before any defensive poison commit;
- native callback injection before pending, after store commit, after final clear, and
  after first serve; proved-fence and unproved-fence branches; no finite quiet period
  accepted as a universal fence;
- one automated scan that rejects stale policy, store, journal, review-range, and
  fixture-revision references.

`capability-v9` is permanently rejected evidence. `capability-v10` must be committed as
a new exact clean snapshot and independently reviewed before Slice 1B implementation.
This record is not an implementation, physical validation, performance approval, or
release approval.
