# RecoveryJournalV5 normative contract

## Scope and authority

This is the recovery contract referenced by ADR-011 `capability-v15`. It is the
single authority for the journal byte grammar, attempt boundaries, resource-uncertain
recovery, mode-store pending commit, explicit mode reset permit, and post-seal mode
poison. RecoveryJournalV3 is rejected evidence and is never read or migrated.
The proposed RecoveryJournalV4 was also rejected before implementation or release; V3
and V4 bytes are never read, migrated, or normalized into this schema.

The journal is crash-loop containment, not a source of capability. No journal state
can create a measured result, repair missing proof, or authorize native work before
strict recovery.

## Physical mode and artifact isolation

Each RuntimeArtifactId owns exactly two private no-backup
`CheckedAtomicReplaceV1` bases and two distinct single-thread IO owners:

```text
capability_recovery_v5/<lowercase RuntimeArtifactId>/solo/journal.bin
capability_recovery_v5/<lowercase RuntimeArtifactId>/dual/journal.bin
```

The canonical parent and fixed filename must remain beneath `noBackupFilesDir`.
Symlink, non-regular existing file, path escape, unknown owner/mode, trailing bytes, or
size above 16,384 bytes is corruption before unbounded allocation. An installed-byte
change selects another directory and never trusts, migrates, opens, or deletes the old
one. A solo operation never opens, waits for, resets, or mutates dual persistence, and
vice versa.

The state gate captures the existing journal read or mutation start timestamp before
enqueue; `JournalDirectoryBootstrapV1` is the owner's first step inside that same
deadline and receives no separate or reset clock. It starts from an lstat/open/fstat-
validated `noBackupFilesDir` and processes only these fixed
components in order: `capability_recovery_v5`, the exact lowercase 64-hex
RuntimeArtifactId, and the selected `solo` or `dual`. For each component, exact ENOENT
permits one public `Os.mkdir(path, 0700)`; `EEXIST` is accepted only after lstat. The
child is then opened with `O_RDONLY|O_NOFOLLOW` (API27+ also `O_CLOEXEC`), fstat-required
to be a directory, fsynced, and closed; its already validated parent is separately
fsynced/closed before descent. Existing components undergo the same validation. Any
other errno, symlink/type/path/mkdir/open/stat/fsync/close failure, deadline expiry, or death without a
later complete revalidation blocks the mode. `File.mkdirs`, unchecked booleans,
chmod/rename fallback, directory enumeration above the selected mode, and construction
of the other mode directory are forbidden.

Path-based public APIs cannot provide `openat`-style component handles. The bounded
assumption is Android private `noBackupFilesDir`, no app multiprocess component, and one
owner; every parent component is lstat-checked before each operation. A compromised
filesystem/device is outside this crash-consistency claim and never receives a “safe”
attestation.

Each file is `canonicalPayload || rawSha256(canonicalPayload)`. Inside the selected
mode directory, the sole declared journal sibling is fixed `journal.bin.next`;
`journal.bin.new`, `journal.bin.bak`, and numbered journal temporary names are
forbidden. The other mode is a separate sibling directory that this owner never opens
or enumerates. Recovery enumerates only the selected mode directory with API 26+ public
`Files.newDirectoryStream(selectedModePath)` and no glob/filter. It obtains the iterator
exactly once, accepts at most two entries, extracts only each `Path.getFileName()` string,
requires it to be exactly `journal.bin` or `journal.bin.next`, tracks each at most once,
and reconstructs the corresponding fixed trusted path instead of using the returned
entry path. A third/duplicate/empty/noncanonical name, iterator reuse, provider/path
mismatch, `IOException`, `SecurityException`, `DirectoryIteratorException`, iterator
failure, or stream-close failure blocks the mode. This bounded NIO call supplies names
only; it never reads, opens, mutates, or follows an entry. All file inspection and
mutation then uses public `android.system.Os`, and the owner never uses
`android.util.AtomicFile`: lstat every fixed path, create
`.next` with `O_CREAT|O_EXCL|O_WRONLY|O_NOFOLLOW`, add `O_CLOEXEC` only on API 27+, and
use mode `0600`; loop over checked partial `Os.write` results until the whole bounded
payload is written, reject zero progress/interruption, check `fsync` and close, and
strictly reread it through a new `O_RDONLY|O_NOFOLLOW` descriptor using the frozen-
length positive-progress plus separate zero-EOF probe below. A
commit-token CAS linearizes immediately before the sole `Os.rename(next, base)` call.
The writer never deletes base first and has no copy or rename fallback.
The API 26 branch performs no process exec or external call while its descriptor is
open and closes it before authorization; API 27+ additionally requires `O_CLOEXEC`.
After rename, the same owner lstat-requires the selected mode parent to be a directory,
opens that fixed path with `O_RDONLY|O_NOFOLLOW` and mode zero (plus API27+
`O_CLOEXEC`), fstat-requires `OsConstants.S_ISDIR(st_mode)`, and checks fsync/close,
then performs its direct intended-base verification. Public `OsConstants.O_DIRECTORY`
is absent and must not be referenced. Directory-sync failure after
authorization is outcome-unknown until clean-launch recovery.

Strict clean-process recovery first lstat-inspects base and `.next` without a mutating
helper. Base is either absent or one regular checksum-valid payload. A remaining regular
`.next`, including a partial write, proves rename did not consume that source; recovery
may remove it with checked public `Os.remove` only while base is absent/valid, then must
check parent-directory fsync/close and re-inspect both paths.
It never promotes `.next`. Symlink/non-regular path, unexpected sibling, failed cleanup,
or malformed/transition-invalid base is restart-required/corrupt. A strict valid base
is interpreted as its complete journal state; clean-launch code does not invent an
unpersisted “third hash” classifier. Under the single-writer protocol, same-directory
rename leaves exactly one whole old or new base across process death on API 26-37 and
payloads are never merged. This is not sudden-power-loss or compromised-filesystem
attestation.

Every strict journal read returns tagged `JournalDiskResultV1`: `0=ABSENT` only for
exact base ENOENT after `.next` recovery, or `1=PRESENT` with complete length/hash/bytes
and parsed payload. PRESENT uses `Os.open(O_RDONLY|O_NOFOLLOW)` (API27+ also
`O_CLOEXEC`), fstat regular type and frozen positive size at most 16,384 before
allocation, positive-progress reads until exactly that length, then a separate one-byte
read that must return zero as normal EOF. It requires stable final fstat length, checked
close, checksum, and canonical parse. Zero before expected length, nonzero at the EOF
probe, extra/changing bytes, or any stat/read/close failure blocks the mode; no reader
repairs bytes. ABSENT alone creates no capability and may seed only the canonical first
journal mutation.

## Canonical payload

The payload is the canonical manifest domain `recovery-journal-payload-v5` with exact
`fieldCount=9` and these fields in order:

1. `schema_revision`: exact UTF-8 `recovery-journal-payload-v5`;
2. `recovery_build_id`: raw 32-byte RuntimeArtifactId, exactly equal to the
   lowercase directory shard after decoding that shard;
3. `mode`: one byte `0=SOLO,1=DUAL`;
4. `last_epoch`: uint64, initially zero and never decreasing;
5. `active`: required ActiveV5 union;
6. `manual_retry_context`: required ManualRetryContextV1 union;
7. `entries`: required canonical EntryV5 list;
8. `pending_store_commit`: required PendingStoreCommitV5 union;
9. `mode_control`: required ModeControlV5 union.

All nested manifests use ADR-011's domain, field-count, fixed-name/value-length rules.
Unknown tags, values, or fields are corruption. Every nonzero epoch is allocated as
`checkedAdd(last_epoch,1)` in the same timely journal mutation that first uses it;
overflow is restart-required and invokes no native work. Epoch is a local recovery
sequence only: it is never written to CapabilityModeStoreV4, logs, analytics, UI,
evidence export, or any cross-device identifier.

## ActiveV5

Active is tag byte `0=ABSENT` or tag `1=PRESENT` followed by a canonical
`journal-active-v5` manifest with `fieldCount=7`:

1. raw 32-byte `probe_base_scope_id`;
2. uint64 nonzero `attempt_epoch`, at most `last_epoch`;
3. one-byte `delegate` (`1=CPU,2=GPU`);
4. one-byte `role` (`0=CANDIDATE,1=SELECTED,2=FALLBACK_CANDIDATE,
   3=FALLBACK_SELECTED`);
5. one-byte `state` (`0=ACTIVE,1=TEARDOWN_PENDING`);
6. one-byte `retry_used` bit;
7. required `retry_context_id` union: tag `0=ABSENT` or tag `1=PRESENT` followed by
   raw 32-byte SHA-256 of the exact ManualRetryContextV1 bytes.

GPU with either fallback role is invalid. Fresh work and proof-prefix resume require
`retry_used=0`. Every route in an authorized manual-retry epoch, including a delegate
that canonically precedes the quarantined target, requires `retry_used=1` and the exact
same-base/same-epoch ManualRetryContextV1. Retry zero requires an ABSENT context-ID;
retry one requires PRESENT equal to SHA-256 of that context. Readers never infer retry provenance
from delegate order or from whether the target marker has already become terminal.
There is no durable intentional-abort state.
An abort request is process-local while durable active remains ACTIVE. Immediately
before native close, the same active key/epoch moves to TEARDOWN_PENDING. It can clear
only after analyzer, source proxy, submitted input, callback output, buffer, runtime,
callback barrier, and render ownership have reached the exact clean closure required by
ADR-011, and the process-lifetime thermal monitor has reached its measurement cutoff
with a noncritical current-status check. Process death in either state is therefore
conservative.

## ManualRetryContextV1

The retry context is tag byte `0=ABSENT` or tag `1=PRESENT` followed by canonical
`manual-retry-context-v1` with exact `fieldCount=6`:

1. raw 32-byte `probe_base_scope_id`;
2. one-byte `target_delegate` (`1=CPU,2=GPU`);
3. uint64 nonzero `origin_quarantine_epoch`;
4. one-byte `origin_quarantine_reason`, exactly one QUARANTINED reason allowed by the
   EntryV5 table;
5. raw 32-byte `origin_quarantine_entry_sha256`, hashing the exact eligible retry-0,
   cache-invalidated-1, context-ID-absent, proof-absent EntryV5 bytes that authorization replaces;
6. uint64 nonzero `attempt_epoch`, greater than origin and exactly the new epoch
   allocated by the authorization mutation.

Authorization atomically replaces that exact target quarantine with
MANUAL_RETRY_ACTIVE/retry-1/cache-invalidated-1 at `attempt_epoch`, installs the context,
and optionally starts the first canonical active route with retry one. Context remains
present if the target marker becomes a retry-1 terminal proof, while another delegate
runs, through pending-store prepare, and until either final success or one total
consumption transition. Every active, TERMINAL_THIS_ATTEMPT, and MANUAL_RETRY_ACTIVE
entry for its base/attempt epoch requires retry one and the exact context ID. Every
RETRY_CONSUMED tombstone created from that attempt retains the same context ID even
after the payload context clears.

Context is fixed payload space, not an entry slot. It forbids a fresh/recheck/resume or
second manual authorization. Final success atomically clears it with the resolved
retry-1 entries. Cancellation, abandon, failed/not-committed save, or interrupted
recovery converts every retry-1 terminal/manual entry in its epoch plus a distinct
active key to the named RETRY_CONSUMED reason, clears active and context, and preserves
the target provenance only through the resulting absorbing tombstone(s). Missing,
duplicate, cross-base, cross-epoch, origin-hash mismatch, or retry-1 state without the
context is corruption.

## EntryV5 list and terminal evidence

Entries encode `uint32_be(count)` followed by `uint32_be(entryLength) || entryBytes`,
sorted uniquely by unsigned raw ProbeBaseScopeId then delegate. Count is 0..8. Each
`journal-entry-v5` manifest has exact `fieldCount=9`:

1. raw 32-byte `probe_base_scope_id`;
2. uint64 nonzero `attempt_epoch`, at most `last_epoch`;
3. one-byte `delegate` (`1=CPU,2=GPU`);
4. one-byte `state` (`0=TERMINAL_THIS_ATTEMPT,1=QUARANTINED,
   2=MANUAL_RETRY_ACTIVE,3=RETRY_CONSUMED`);
5. one-byte sealed `reason` from the state table below;
6. one-byte `retry_used` bit;
7. one-byte `cache_invalidated` bit;
8. required `retry_context_id` union: tag `0=ABSENT` or tag `1=PRESENT` followed by
   raw 32-byte SHA-256 of the exact ManualRetryContextV1 bytes;
9. required terminal-evidence union: tag `0=ABSENT` or tag `1=PRESENT` followed by
   `uint32_be(proofLength) ||` exact canonical TerminalProofV1 bytes.

Terminal evidence is PRESENT only for TERMINAL_THIS_ATTEMPT. Its delegate must match
the key; its role/reason/closure/resolved-owner mask and trace ordinal must form the
matching safe-terminal decision slot in CapabilityModeStoreV4's finite language. It is
the only terminal proof reusable after a process boundary. Trace ordinal is the fixed
canonical grammar slot, not callback wall time. A reviewer/reader never reconstructs a
missing field, changes an ordinal, or combines different epochs.

| State | Allowed reasons | retry-used | cache-invalidated | context ID | proof |
| --- | --- | --- | --- | --- | --- |
| TERMINAL_THIS_ATTEMPT | `OPTIONS_PROTO_REJECTED_BEFORE_CREATE_ENTRY`, `DETECT_EXCEPTION_RETURNED`, `RESULT_CALLBACK_DEADLINE_CLEAN` | 0 or 1 | exactly 1 | ABSENT iff retry 0; exact PRESENT iff retry 1 | exact matching PRESENT |
| QUARANTINED recovered | `RECOVERED_ACTIVE`, `RECOVERED_TEARDOWN_PENDING` | exactly 0 | 0 before exact deletion, then 1 | ABSENT | ABSENT |
| QUARANTINED resource | `CREATE_UNCERTAIN`, `CREATE_TIMEOUT`, `SUBMISSION_TIMEOUT`, `PROCESS_ABORT`, `IMAGE_PROXY_CLOSE`, `SUBMITTED_INPUT_CLOSE`, `BUFFER_ZERO`, `BUFFER_RELEASE`, `ANALYZER_DETACH`, `CALLBACK_OUTPUT_DISPOSAL`, `CALLBACK_OUTPUT_UNDELIVERED`, `LANDMARKER_CLOSE`, `JOURNAL_POST_CREATE`, `THERMAL_SAFETY_MONITOR_FAILED`, `FRAME_METRICS_REMOVE_OR_DRAIN`, `CALLBACK_BARRIER_UNPROVEN` | exactly 0 | 0 before exact deletion, then 1; direct 1 only after durable absence | ABSENT | ABSENT |
| RETRY_CONSUMED | `MANUAL_RETRY_ABANDONED`, `MANUAL_RETRY_ABORTED`, `MANUAL_RETRY_INTERRUPTED`, `MANUAL_RETRY_SAVE_NOT_COMMITTED` | exactly 1 | exactly 1 | exact PRESENT retained from consumed context | ABSENT |
| MANUAL_RETRY_ACTIVE | `MANUAL_RETRY_AUTHORIZED` | exactly 1 | exactly 1 | exact PRESENT | ABSENT |

The reason byte encoding is exact and exhaustive in this order:

```text
0 OPTIONS_PROTO_REJECTED_BEFORE_CREATE_ENTRY
1 DETECT_EXCEPTION_RETURNED
2 RESULT_CALLBACK_DEADLINE_CLEAN
3 RECOVERED_ACTIVE
4 RECOVERED_TEARDOWN_PENDING
5 CREATE_UNCERTAIN
6 CREATE_TIMEOUT
7 SUBMISSION_TIMEOUT
8 PROCESS_ABORT
9 IMAGE_PROXY_CLOSE
10 SUBMITTED_INPUT_CLOSE
11 BUFFER_ZERO
12 BUFFER_RELEASE
13 ANALYZER_DETACH
14 CALLBACK_OUTPUT_DISPOSAL
15 CALLBACK_OUTPUT_UNDELIVERED
16 LANDMARKER_CLOSE
17 JOURNAL_POST_CREATE
18 THERMAL_SAFETY_MONITOR_FAILED
19 FRAME_METRICS_REMOVE_OR_DRAIN
20 CALLBACK_BARRIER_UNPROVEN
21 MANUAL_RETRY_ABANDONED
22 MANUAL_RETRY_ABORTED
23 MANUAL_RETRY_INTERRUPTED
24 MANUAL_RETRY_SAVE_NOT_COMMITTED
25 MANUAL_RETRY_AUTHORIZED
```

Every unlisted tuple is corruption. A generic ErrorListener reason, terminal resource
uncertainty, proof on a nonterminal entry, missing terminal proof, retry changing 1 to
0, or duplicate key is invalid. The only legal same-key active/entry pair is a
MANUAL_RETRY_ACTIVE entry with retry/cache bits 1/1 and identical attempt epoch. An
active with retry one requires the exact retry context for the same base/epoch and its
hash in every context-ID union; all terminal/manual entries in that base/epoch also
require retry one and that hash. Active retry zero forbids a payload context and requires
ABSENT context IDs. Consumed retry-one tombstones retain their former hash while the
payload context is absent. No pending commit may coexist with active.
RETRY_CONSUMED never
authorizes native work, never supplies terminal proof, and is not resumable. Other-key
entries remain untouched. Any transition that would otherwise create QUARANTINED with
retry bit 1 instead atomically clears active and converts every retry-1 terminal/manual
entry in that base/epoch, plus the active key if distinct, to
RETRY_CONSUMED/MANUAL_RETRY_INTERRUPTED. Before setting an active key, the writer must
reserve every list slot this conservative conversion could require; the invariant is
`entryCount + (activeKeyAbsentFromEntries ? 1 : 0) <= 8`.

## Attempt epoch lifecycle

An attempt is exact `(ProbeBaseScopeId, attempt_epoch)`. There are only three user-visible
ways to enter native work after journal recovery:

1. **Start fresh / Recheck.** One timely atomic journal mutation allocates a new epoch,
   removes every older retry-0 TERMINAL_THIS_ATTEMPT entry for that exact base,
   and sets the first allowed active route with active retry zero. `Recheck` first
   durably deletes the exact mode-store record. Context must be absent before this
   mutation; a retry-1 active/terminal/manual entry while context is absent is corruption,
   while an already consumed tombstone is retained unchanged.
   QUARANTINED and RETRY_CONSUMED entries are never erased by fresh start.
2. **Resume interrupted proof prefix.** The exact base has one and only one terminal
   epoch, no QUARANTINED route required by its unique legal continuation, and no
   conflicting active epoch. Every proof remains in that epoch. Candidate measurements
   are process-local and never journaled: every nonterminal candidate slot needed after
   restart is rerun, no pre-crash metric/rank is reused, and the eventual selected
   steady is fresh. The next active route uses that same epoch and retry zero.
3. **Retry one quarantined route.** Only an exact retry-0 QUARANTINED entry is eligible,
   and it receives at most one authorization. This budget belongs to that exact
   quarantine entry, not every future quarantine that a later fresh run might create.
   One atomic manual-authorization mutation allocates a new epoch, deletes older
   retry-0 terminal entries for the base, requires no live retry-1 state/context, retains
   every existing RETRY_CONSUMED tombstone unchanged, replaces only the target quarantine with
   MANUAL_RETRY_ACTIVE at the new epoch, installs the exact origin-bound
   ManualRetryContextV1 and writes its hash to the marker/active context-ID unions, then
   starts the canonical route sequence at its first
   allowed slot. If another delegate precedes the target, the manual entry may
   wait without active while that delegate runs; every active and terminal proof in the
   new epoch stores retry one. Other QUARANTINED/RETRY_CONSUMED entries remain and produce only their legal
   skipped slots. No proof from the quarantine epoch or another epoch is reused.

An exact base with resumable terminal entries from multiple epochs, terminal and
active epochs that differ outside the atomic manual-new-epoch transition, or a proof
prefix with no unique continuation is corruption. RETRY_CONSUMED is absorbing for the
exact artifact/mode/base/delegate key: tombstones from older epochs are excluded from
proof-prefix selection, are never rewritten or cleared in that artifact shard, and
continue to forbid authorization. A successful manual retry resolves its original
quarantine through the selected record and does not create RETRY_CONSUMED; a later
fresh native failure may create a new quarantine, but it is not a second authorization
of the old entry. If a consumed tombstone exists instead, the route stays skipped and
cannot create a same-key quarantine.
There is no automatic background resume and no cache/native service while an
interrupted attempt awaits the explicit choice.

Cancellation has a total attempt boundary:

- before the first journal mutation/native authorization, it changes no byte and
  consumes no retry;
- while active, durable active remains until all cleanup/barriers finish; a clean final
  mutation clears active, deletes every retry-0 terminal entry of that base/epoch, and
  converts every retry-1 terminal or MANUAL_RETRY_ACTIVE entry of that base/epoch plus
  a distinct retry-1 active key to RETRY_CONSUMED/MANUAL_RETRY_ABORTED/1/1, then clears
  the matching retry context;
- between runtimes with no active, one atomic abandon mutation performs the same
  retry-0 deletion and retry-1 terminal/manual conversion plus context clear for that
  base/epoch;
- if abandon times out or the process dies, strict checked-replace old/new recovery yields
  either the complete resumable old attempt or the complete abandoned new state. They
  are never merged.

A clean terminal transition atomically clears matching active and writes the exact
TerminalProofV1 entry under the same epoch. A clean nonterminal intermediate clears
matching active while preserving same-epoch terminal/manual entries. CPU terminal then
GPU active/crash therefore retains the CPU proof and quarantines GPU; a later explicit
same-epoch retry can finish the proof. A fresh attempt can never consume that proof.

## PendingStoreCommitV5

Pending is tag byte `0=ABSENT` or tag `1=PRESENT` followed by canonical
`pending-store-commit-v5` with exact `fieldCount=12`:

1. raw 32-byte `probe_base_scope_id`;
2. uint64 nonzero `attempt_epoch`, at most `last_epoch`;
3. raw 32-byte `capability_result_id`;
4. one-byte `selected_delegate` (`0=NONE,1=CPU,2=GPU`);
5. one-byte `retry_used` bit;
6. required `retry_context_id` union, ABSENT iff retry zero or PRESENT with the exact
   ManualRetryContextV1 hash iff retry one;
7. one-byte `expected_old_file_state` (`0=ABSENT,1=HASH_PRESENT`);
8. uint64 `expected_old_file_length`, zero iff ABSENT and positive iff HASH_PRESENT;
9. required `expected_old_file_sha256` union, ABSENT iff file state ABSENT or PRESENT
   with the raw 32-byte hash of the exact complete old protobuf envelope;
10. uint64 positive `intended_new_file_length`, at most 1,048,576;
11. raw 32-byte `intended_new_file_sha256`, hashing the exact complete intended protobuf
    envelope already produced by the canonical serializer;
12. raw 32-byte `intended_new_record_sha256`, hashing the exact strict-validated new
    CapabilityRecordV4 bytes embedded in that envelope.

CapabilityResultId or one record hash alone never distinguishes old from new or detects
an unrelated-record mutation. Pending is legal only after
active is absent and every runtime/image/buffer/analyzer/callback owner and barrier is
proven clean, while the process-lifetime thermal monitor has frozen its partial window
and passed the current-status safety check. The capability-v15 public render owner instead requires a
returned removal, atomically disabled metric mutation, and the exact non-authoritative
closed state; it never asserts native quiescence. Detached resolved PSS evidence is the
only other exception.
Every terminal proof needed by an ALL-safe-terminal new record remains as a same-epoch
entry until final journal clear. Pending `retry_used` is one iff the exact matching
ManualRetryContextV1 is present and every same-epoch terminal/manual entry uses its hash;
it is zero iff context is absent and those entries use zero/ABSENT. Selected CPU/GPU or
NONE does not infer this provenance. The pending
record's base/result/delegate/terminal proofs must all cross-validate against the exact
canonical new bytes before prepare.

Immediately before prepare, the mode mutation gate enters `FINALIZING_FROZEN` and
rejects every other read-modify-write until serve/failure. The single live DataStore
owner supplies its strict canonical current payload and the exact key as absent or its
canonical old-record hash, plus the complete file absence/length/hash last established
by a pre-owner or post-update ModeStoreDiskVerifierV1. The canonical serializer freezes
the exact intended complete envelope bytes/length/hash before prepare. Its initial disk
read and every accepted update are owned by that same instance; no other writer or raw
access to the backing base or `<base>.tmp` may overlap it. The sole exception is the same
serialized coordinator's read-only open/fstat of the already validated parent directory
immediately before update, solely to require the frozen ModeStoreDirectoryIdentityV1;
it neither enumerates nor opens base/`.tmp`. The timely prepare mutation records both
whole-file identities, the intended record hash, and the retry context ID.

One DataStore `updateData` may then write the exact new record. After it returns, the
normal-save coordinator cancels and joins that sole owner within the owner-handoff/save
deadline, then reopens the parent, requires exact pre-update directory-identity equality,
and completes the checked parent-directory fsync sequence. With no DataStore
owner alive, `ModeStoreDiskVerifierV1` freshly opens the
fixed backing file read-only, enforces the 1,048,576-byte envelope/wire/canonical/hash
grammar, requires the fixed DataStore `<base>.tmp` sibling absent, and admits the exact
intended full envelope length/hash plus embedded record. A same-instance `data.first()` is cached
and never substitutes for this evidence. Only that exact disk result permits a final
journal clear. The clear removes pending, clears its matching retry context when present, and
only the same-base/same-epoch terminal/MANUAL_RETRY_ACTIVE entries resolved by the
result. It never removes QUARANTINED or RETRY_CONSUMED. After clear, a fresh direct
journal read and another fresh ModeStoreDiskVerifierV1 read, each under its named
deadline and with mutation exclusion still held, must cross-validate every skipped
trace event against its retained exact external entry before serve. The verifier is
read-only, invokes no migration/corruption handler, and is not a DataStore owner. A
handler-free successor DataStore may be constructed only after these reads; its first
read must equal the verified payload and the prior owner must already be joined. It may
then remain as the sole live owner.

On the next clean process, pending recovery compares the strict complete mode-store
file identity before constructing DataStore:

- exact intended-new length/hash with the exact intended embedded record: the write
  committed; perform the same retention-preserving
  final clear and dual strict reread, then serve;
- exact expected-old full-file length/hash or expected absence: the write did not
   commit; clear pending, reject the attempted classification, convert every retry-1
   terminal/manual entry in its context to
  RETRY_CONSUMED/MANUAL_RETRY_SAVE_NOT_COMMITTED/1/1 with the retained context ID, and
  clear the payload context;
- any third file identity, malformed record/envelope, matching CapabilityResultId with
  different bytes, unrelated-record mutation, or impossible terminal/context/epoch
  relation: corruption; do not delete or serve it.

Mode-store/journal timeout after commit authorization remains outcome-unknown until
this comparison. A timed-out process never interprets late bytes.

## ModeControlV5, reset, and post-seal poison

Mode control is a fixed-capacity required union independent of the eight-entry list:

- tag `0=NONE`;
- tag `1=RESET_REQUESTED` plus uint64 nonzero `control_epoch`;
- tag `2=RESET_RUNNING` plus uint64 nonzero `control_epoch`;
- tag `3=POST_SEAL_MODE_POISON` plus one-byte reason and one-byte
  `cache_invalidated` bit. The sole exact reason value is
  `0=RUNTIME_CALLBACK_ENTERED_AFTER_PROVEN_SEAL`; every other byte is corruption.

RESET_REQUESTED and RESET_RUNNING require `control_epoch == last_epoch`; a zero, stale,
or future value is corruption. Initial confirmation and every explicit reauthorization
uses checked `last_epoch+1` and writes that same value to both fields atomically.
REQUESTED to RUNNING preserves both values. POST_SEAL_MODE_POISON carries no epoch and
does not change `last_epoch`.

POST_SEAL_MODE_POISON has priority over every other control state, may coexist with
pending/entries, and can always be written without an entry reservation. Repeated
events boolean-OR `cache_invalidated`; this merge is idempotent. A proved-seal runtime
violation first latches in memory and revokes same-process service, then attempts the
durable mutation. It may occur before or after pending/final clear. Timeout, mutation
failure, or death before commit carries no durable guarantee and cannot retroactively
order the event before a completed clear; the artifact/API/ABI native-fence proof is the
authorization premise. The public non-authoritative FrameMetrics and process-lifetime
thermal paths have no poison reason. Their late messages are respectively discarded
render diagnostics or live same-process safety events. On the next clean process,
bit 0 first requires an
idempotent replacement of that exact mode store with its canonical empty current-owner
payload, followed by a journal mutation to bit 1. The poison then permanently blocks
cache and native work for that artifact/mode. It has no same-artifact reset; installed
byte change is the only recovery. Pending can never be accepted while poison exists.

An explicit unreadable-mode reset is clean-process only and has at most one live
DataStore owner for the fixed file at any instant:

1. with no active, pending, or poison, a confirmed UI action allocates a control epoch,
   writes RESET_REQUESTED to that mode journal, and requests full process restart; it
   does not construct or recreate DataStore in the requesting process;
2. the next process atomically changes REQUESTED to RESET_RUNNING before constructing
   a reset owner. That owner captures one in-memory CAS permit `ARMED -> CLAIMED` in its
   fixed `ReplaceFileCorruptionHandler`; the handler performs no journal/DataStore call;
3. if the first strict `data.first()` reports corruption, the handler may claim once
   and return only the canonical empty current-build/current-mode payload. That same
   read must return the replacement after its synchronous descriptor-synced write.
   Crash/restart evidence still requires owner join, parent fsync, and direct verification. Handler
   reinvocation after a failed write observes CLAIMED and rethrows;
4. if first read is valid but nonempty, the same reset owner performs one normal
   `updateData` to exact empty. If already empty it performs no update;
5. a reset coordinator cancels and joins that owner's scope within the named five-
   second handoff deadline. After successful join it checks the exact public parent-
   directory fsync sequence and runs ModeStoreDiskVerifierV1 with no DataStore owner,
   requiring exact canonical empty and `<base>.tmp` absent. Only then may it construct
   one handler-free verifier owner for the same canonical file. That owner's fresh
   first `data.first()` must equal the directly verified empty payload; it is itself
   cancelled and joined, and one final direct verifier must still match before the
   journal clears RESET_RUNNING. Two owners never overlap;
6. timeout/throw poisons the mode persistence generation, starts no additional owner,
   and leaves RESET_RUNNING for next-clean-process reconciliation. A launch finding
   RESET_RUNNING first performs clean `.tmp` recovery, checked parent fsync, and the
   owner-free direct verifier. Exact empty may enter the same handler-free-owner/final-
   direct-verify finish sequence; corrupt or valid-nonempty remains blocked. Re-arming replacement requires a new
   explicit user action that checked-increments `last_epoch`, writes the same new value
   as `control_epoch`, and changes RUNNING back to REQUESTED, followed by another clean
   process.

The official DataStore 1.2.1 JVM artifact fixture is release evidence for these exact
facts: duplicate live file ownership throws, cancel-and-join permits sequential reopen,
the corruption-triggering read returns a synchronously written replacement, a second
same-instance read is cached rather than strict disk evidence, unresolved corruption
reinvokes the handler, and an atomic one-shot permit authorizes only one write attempt.
Android integration must reproduce the same assertions before adapter approval. If it
does not, reset UI is not implemented and unreadable mode remains blocked; the normal
handler outside REQUESTED always throws.

## Recovery ordering and capacity

1. Compute installed/workload identities and strictly recover only the requested mode
   journal before constructing that mode store or invoking native work.
2. POST_SEAL_MODE_POISON is reconciled first. RESET_REQUESTED/RESET_RUNNING follows its
   clean-process state machine. Neither may be bypassed by cache lookup.
3. Pending whole-file old/new reconciliation runs before interpreting an otherwise
   interrupted manual context. Exact intended new may final-clear; exact expected old
   performs the save-not-committed context conversion; a third state blocks.
4. Recovered retry-0 ACTIVE/TEARDOWN_PENDING requires ABSENT context ID and becomes
   QUARANTINED with the same base, delegate, and epoch. Recovered retry-1 active requires
   the exact present ManualRetryContextV1/hash and atomically becomes
   RETRY_CONSUMED/MANUAL_RETRY_INTERRUPTED/1/1 together with every retry-1 terminal/
   manual entry in that epoch; a distinct active key receives its reserved tombstone and
   context ID. A context with no active/pending is interrupted by the same total
   conversion even when its target marker has already become terminal. Retry-0 terminal
   proof for a different attempt is untouched; mixed provenance is corruption.
5. Each exact-base/delegate quarantine bit 0 requires idempotent exact-record deletion
   in that mode store to commit before the bit becomes 1. Death can only repeat safe
   deletion. No result selecting that delegate is served while its entry exists.
6. Before every normal or manual native entry, the one atomic journal mutation must
   reserve capacity, set active, and return on time. Failure invokes no native; a late
   whole commit creates only conservative work for the next process.
7. All close, terminal, cancellation, pending, and final-clear transitions follow the
   schemas above. No mutation overwrites another scope's entry.

The entry maximum eight is checked before native authorization. Mode poison and reset
control occupy fixed payload space and require no list slot. Whole-file cap is checked
before allocation and after canonical encode. Overflow is incomplete/corrupt as named;
it is never treated as empty.

## Deadline, privacy, and validation boundary

Every read/mutation token captures its sole start timestamp at the state gate
immediately before enqueue, including owner queue time. Owner entry never resets it.
Completion/authorization/expiry CAS uses that timestamp and ADR-011's exact inclusive
deadline rules. Late authorized whole old/new bytes are interpreted only by a clean
process.

The serializer is an allowlist for only the fields above. It rejects pixels, Bitmaps,
landmarks, raw/per-frame pose data, source/task timestamps, frame/task/generation IDs,
camera/OS/device/user identifiers, paths, serials, and generic maps. The local epoch is
the sole categorical recovery sequence and never leaves the private journal.

Checksum/private storage protect benign corruption and crash recovery, not a
compromised device or sensor attestation. Exhaustive canonical round-trip, one-field
mutation, legal-tuple, crash-point, exact/+1 deadline, capacity, mode isolation,
attempt-boundary, pending old/new/third-state, reset, and post-seal-poison fixtures are
release-blocking.
