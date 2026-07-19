# Slice 1B policy independent review 06

## Reviewed target

- Commit: `b29f0c9bf3bc85b5fb8db806d2840f3e9b2ad7e0`
- Tree: `6439e89cf836d0f612f890b321c49d69f3dcbbd7`
- Policy revision: `capability-v6`
- Review mode: three fresh independent read-only SSOT/domain/runtime reviews
- Physical devices observed: 0
- Verdict: `REJECT / REWORK`

All reviewers inspected the exact target. The SSOT and domain reviewers verified a
clean worktree at the end; the runtime reviewer verified it at entry and performed no
repository write. No P0 was found. Deduplicating overlap and retaining the highest
priority assigned by any reviewer produced ten P1 and four P2 findings. This record
does not approve v7; fresh reviewers must inspect its actual clean commit and tree.

## Direct pinned-runtime and validation evidence

The runtime reviewer disassembled pinned MediaPipe Tasks 0.10.35. `OutputHandler.run`
catches `MediaPipeException` across result conversion, callback-input conversion, and
the app result listener, then invokes the same timestamp-free ErrorListener.
`TaskRunner.close()` calls native `waitUntilGraphDone()` before resource release, but
Java bytecode alone does not prove that every Java callback has returned or that no
later callback can enter. `BaseVisionTaskApi.sendLiveStreamData()` performs unchecked
millisecond-to-microsecond multiplication, and `TaskRunner.send()` requires strictly
increasing microseconds. Static JNI inspection found `nativeCreateCpuImage`, but did
not prove the complete synchronous direct-buffer copy lifetime contract.

The SSOT reviewer revalidated ZIP SHA-256, the 66-file byte projection, 65/65 internal
checksums, `git diff --check`, the 75-test Python suite, static supply-chain policy, and
the zero-deployed-asset state. The domain reviewer repeated exact commit/tree and clean
status checks. No Gradle, emulator, implementation, or physical-device result was
claimed for this documentation-only target.

## Finding-to-resolution register

| Priority | v6 finding | Counterexample or risk | Required `capability-v7` resolution | State |
| --- | --- | --- | --- | --- |
| P1 | Recovered terminal entries discard proof role/closure/mask/ordinal | CPU reaches a clean terminal, GPU later crashes, and recovery cannot construct the CPU TerminalProof needed for ALL-safe-terminal without inventing or rerunning evidence | Persist exact canonical TerminalProof bytes with the delegate and attempt epoch; accept them only under the matching finite trace | Planned; rereview required |
| P1 | `TERMINAL_THIS_ATTEMPT` has no attempt boundary | Cancellation between CPU/GPU runtimes permits an old clean terminal to mix with a new route or makes the delegate permanently unavailable | Allocate a durable nonzero attempt epoch; define resume, fresh-start, abandon, and between-runtime cancellation transitions; never combine epochs | Planned; rereview required |
| P1 | Pending commit identifies only base/result/delegate | Different canonical outcomes for the same base/delegate share CapabilityResultId, so recovery can mistake a prior record for the attempted write | Bind expected-old state/hash and exact intended-new canonical-record hash; compare exact record bytes/hash on clean-launch recovery | Planned; rereview required |
| P1 | Durable `INTENTIONAL_ABORT` precedes cleanup | Process death after that write but before detach/close is recovered as harmless while native/listener resources were uncertain | Keep durable active through all pre-clean abort work; clear or convert only after every owner/barrier is proven clean | Planned; rereview required |
| P1 | Distribution and quartile certificates are independently feasible only | A Q1 median can exceed an overall certified maximum while every individual certificate passes, changing growth/tier from an impossible window | Add a bounded canonical quartile/global joint-feasibility witness and exact reader conservation checks | Planned; rereview required |
| P1 | Post-seal ErrorListener recovery can overflow eight entries | More sealed generations can later report an error than the journal has unreserved entry capacity | Require an executable callback barrier and pinned artifact proof; additionally reserve a fixed-size mode poison independent of the entry list for any observed post-seal violation | Planned; rereview required |
| P1 | Persistence deadline starts both before dispatch and on owner entry | Queue delay is either included or silently receives a fresh deadline depending on implementation | Capture one state-gate timestamp immediately before enqueue for every read/mutation; owner entry never resets it | Planned; rereview required |
| P1 | Listener seal has no counter/barrier or pinned happens-before proof | A result can be persisted/served before a late Graph callback revokes it | Freeze callback-entry admission, in-flight counting, close-return/zero-count seal order, and per-ABI/API artifact proof; proof failure forbids persistence/service | Planned; rereview required |
| P1 | Thermal removal occurs after finalization and has no executor drain | Pending commit requires clean thermal resources even though removal happens later; a queued CRITICAL callback can miss the frozen histogram | End snapshot, close admission, remove, bounded same-executor drain/in-flight zero, seal, then authorize pending/store | Planned; rereview required |
| P1 | DataStore reset lifecycle depends on unspecified retry semantics | A corruption handler is fixed at instance construction; v6 does not define permit delivery, same-instance replacement return, or singleton recreation | Freeze a clean-process construction-time one-shot handler state machine, one instance/file/process, and a pinned 1.2.1 artifact test before adapter approval | Planned; rereview required |
| P2 | Timestamp ceiling has two possible reasons | At the exact pinned millisecond ceiling, `+1` succeeds but the result cannot be multiplied safely | `previous == ceiling` is deterministically `TASK_TIMESTAMP_EXHAUSTED`; invalid input/conversion alone is `TASK_TIMESTAMP_INVALID` | Planned; rereview required |
| P2 | PSS PARTIAL allows values for absent endpoints | A checksum-valid tuple can claim a nonzero endpoint value while its presence bit is clear | Absent bit requires exact zero; present bit requires a successful token and a value bounded by certified maximum | Planned; rereview required |
| P2 | Candidate PSS timing is only “analogous” | Implementations can sample at different times, perturb delegate ranking differently, and still claim the same workload revision | Freeze eight candidate tokens at before warm-up, window start, seconds 1–5, and after drain | Planned; rereview required |
| P2 | ALL-terminal hash input is underspecified | Implementations may hash raw concatenation or length-delimited proofs and disagree on valid records | Freeze `SHA256(uint32_be(count) || Σ(uint32_be(length) || canonicalProofBytes))` | Planned; rereview required |

## Required v7 rereview fixtures

- attempt allocation exhaustion, same-epoch resume, fresh abandonment, cancellation
  before/between/after runtimes, CPU-terminal/GPU-crash recovery, and cross-epoch proof
  rejection;
- exact terminal-proof round trip and every delegate/role/reason/closure/mask/ordinal
  mismatch;
- pending expected-old absent/hash, intended-new hash, old/new timeout and process-death
  recovery, same CapabilityResultId with different canonical record, and third-value
  corruption;
- abort request/process death at every point before analyzer, callback, runtime, image,
  buffer, collector, and journal cleanup;
- callback entry during close, at seal, after seal, in-flight nonzero, close timeout, all
  shipped ABI/API `waitUntilGraphDone` artifacts, and mode-poison capacity with eight
  entries;
- one persistence dispatch timestamp with queue delay, exact deadline, and +1 ns for
  every read/mutation and authorization CAS;
- thermal end snapshot/admission close/remove/sentinel/in-flight/seal order, queued
  CRITICAL callback, removal/drain timeout, and callback-after-seal artifact behavior;
- DataStore 1.2.1 corrupt read, single handler invocation, same-read replacement,
  second invocation, permit absent/consumed/timeout, valid-file reset, and proof that no
  two live instances address one file;
- joint witness valid and one-field-mutated fixtures, including the v6 impossible-Q1
  counterexample, adjacent-value empty buckets, quartile/global conservation, and
  growth/outcome recomputation;
- exact timestamp ceiling reason, eight candidate PSS tokens, and every absent/present
  PSS endpoint tuple;
- exact count-and-length-prefixed terminal-evidence hash fixtures.

## Approval boundary

Slice 1B implementation remains stopped until fresh reviewers inspect a clean
`capability-v7` commit and report no actionable P0/P1/P2 defect. Emulator evidence
does not approve a physical tier, effect promotion, G2, G8, or human perception. Human
observations are collected through the non-blocking QA portal; missing human results do
not stop unrelated automated or implementation work, but their release gates remain
open.
