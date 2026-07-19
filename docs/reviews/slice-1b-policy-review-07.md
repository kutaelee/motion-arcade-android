# Slice 1B capability policy review 07

## Verdict and reviewed snapshot

**REJECT / REWORK.** The pre-commit `capability-v7` snapshot was reviewed read-only
against review 06 and the SSOT. It had no P0, three P1, and three P2 findings. It was
never committed or approved. The repository HEAD remained the rejected v6 commit
`b29f0c9bf3bc85b5fb8db806d2840f3e9b2ad7e0`; the reviewed v7 document bytes were:

| Document | SHA-256 |
| --- | --- |
| `docs/adr/ADR-011-measured-capability-probe-policy.md` | `1A561A880FE486541074DEF24DCA102418D62EE2262B3F467BBC988EAF87A573` |
| `docs/contracts/capability-store-v4.md` | `051846B2F5104ACA00CAEBC623E82CEAE959A3F49E57F7456790E0C8B5AD932A` |
| `docs/contracts/recovery-journal-v4.md` | `E79C1968776F9B52589338B5BBC29E04D7C0AC2C2F8649CD1B35C47BE394681D` |
| `docs/execution/slice-1b-contract.md` | `599D370E2075211FD2E245126C59BEED90C794F13AA04E9EB8C142627BB86016` |

The files remained byte-stable for the review. Physical G2/G8, Android DataStore
integration, and per-ABI/API callback proofs were correctly still pending and were not
treated as policy-review failures.

## Findings and required v8 resolution

| Priority | v7 defect | Counterexample | Required `capability-v8` resolution |
| --- | --- | --- | --- |
| P1 | Retry-1 terminal state could be deleted by fresh start/cancel | manual retry -> clean terminal -> cancel between runtimes erased the only consumed bit, allowing a second authorization | Fixed-capacity `RETRY_CONSUMED` state; fresh/abandon/cancel/save-failure converts every retry-1 terminal/manual entry and only strict final success clears it |
| P1 | Quarantine-only recovery had no legal epoch | first CPU runtime crash produced quarantine without a terminal epoch, so the stated resume precondition could never authorize its one retry | Separate proof-prefix resume from quarantine retry; quarantine retry accepts only retry-0, allocates a new epoch, reuses no old proof/metrics, and preserves other quarantine/tombstones |
| P1 | Thermal drain could miss an already queued CRITICAL callback | admission closed before removal while the platform callback body was still queued | Admit at app wrapping-executor submission, account queued plus running, keep admission open through removal, close at the same FIFO sentinel, and poison any post-sentinel submission |
| P2 | Recovery owner identity conflicted | directory used RuntimeArtifactId while payload required RuntimeBuildId | Payload `recovery_build_id` is raw RuntimeArtifactId and must equal the decoded directory shard |
| P2 | PSS/thermal endpoint multiplicity was not conserved | three PSS endpoints equal max with certificate equalCount 1; two thermal endpoints status 3 with histogram bin3 1 | PSS max equalCount covers all present endpoints equal max; every thermal bin covers the count of equal present endpoints |
| P2 | Reset control epoch had no cross-invariant | checksum-valid `last_epoch=7, RESET_RUNNING=2` had implementation-defined meaning | REQUESTED/RUNNING control epoch equals last epoch; confirmation/rearm checked-increments both; stale/future/zero is corruption |

Review 06's count/length-prefixed terminal hash, one pre-enqueue deadline, pending
expected-old/intended-new record hashes, no intentional-abort state, bounded joint rank
witness, timestamp ceiling, absent-PSS-zero rule, exact 8/13 PSS schedules, callback
barrier, and reset-owner lifecycle were otherwise materially present in v7.

## Required v8 rereview fixtures

- retry-1 terminal followed by fresh, active cancellation, between-runtime abandon,
  save-not-committed, crash, and strict-success clear;
- first-route quarantine-only, terminal-plus-quarantine, both-delegate quarantine, and
  different-epoch manual recovery with no proof/sample reuse;
- thermal callback submitted before remove but executed before sentinel, queued
  CRITICAL, sentinel equality/+1, and post-sentinel submission poison;
- recovery payload/directory current/foreign owner mutation;
- PSS one/two/three equal-max endpoints versus maximum equalCount and thermal equal/
  distinct endpoint histogram multiplicity;
- RESET zero/stale/future/equal-last, REQUESTED-to-RUNNING preservation, and explicit
  rearm checked increment.

`capability-v8` remains proposed until fresh reviewers inspect its clean exact commit
and report no actionable P0/P1/P2 defect. This document is rejection evidence, not an
implementation or release approval.
