# NativeCloseFenceProofV1 normative contract

## Purpose, authority, and acyclic build order

ADR-011 `capability-v15` requires an exact native result/error callback-close fence
before any PoseLandmarker native create, persistence, cache, or service. This contract
defines the proof bytes and runtime match. It is evidence authorization, not a device-
name allowlist and not a substitute for physical G2/G8 validation.

The generated nonvisual registry is exactly
`vision/src/main/assets/native_close_fence_registry_v1.bin`. The only legal generation
DAG is:

```text
five exact policy files + pinned source/dependencies + Phase A DEX/JNI for each registered variant
  -> NativeClosePolicySetV1 / per-variant CodeImageV1 / installed JNI sets / ProofBasisV1
  -> per-row content-addressed proof evidence + independent approval
  -> ProofBundleV1 carrying that exact canonical ProofBasisV1 preimage and hash
  -> NativeCloseFenceRegistryV1
  -> each current variant WorkloadBuildManifestV3 embedding its identical ProofBasisV1
  -> per-variant final APK packaging
  -> independent per-variant final-APK DEX/JNI equality verification
```

Registry/workload assets are packaging inputs only and may not rerun or mutate a code
transform or merged-JNI task. The registry never contains or keys on the complete
WorkloadBuildManifest, RuntimeBuildId, RuntimeArtifactId, APK hash, registry hash, or
its own final package identity. The basis may use pre-workload field values named
below; using a downstream identity is a cycle and fails generation.

## Exact policy set, final code image, and canonical ProofBasis

`NativeClosePolicySetV1` is domain `native-close-policy-set-v1`, exact
`fieldCount=2`:

1. `schema_revision`: exact UTF-8 `native-close-policy-set-v1`;
2. `policy_files`: uint32 count exactly five, followed by
   `uint32_be(entryLength) || NativeClosePolicyFileV1` in the exact path order below.

Each `native-close-policy-file-v1` has exact `fieldCount=3`: `logical_path` as exact
UTF-8 repository-relative path, `file_length` as uint64 positive length, and
`file_sha256` as raw SHA-256 of exact checked-in bytes. The only paths, in unsigned
UTF-8 order, are:

```text
docs/adr/ADR-011-measured-capability-probe-policy.md
docs/contracts/capability-store-v4.md
docs/contracts/native-close-fence-proof-v1.md
docs/contracts/recovery-journal-v5.md
docs/execution/slice-1b-contract.md
```

Missing, extra, reordered, normalized, untracked, symlinked, or working-tree-different
bytes fail. Hashing the canonical policy-set bytes yields
`native_close_policy_set_sha256`. The reviewed proof basis therefore changes with any
current normative policy byte even when executable code is unchanged.

`NativeCloseCodeImageV1` is domain `native-close-code-image-v1`, exact `fieldCount=2`:

1. `schema_revision`: exact UTF-8 `native-close-code-image-v1`;
2. `dex_entries`: uint32 count `1..64`, followed by
   `uint32_be(entryLength) || NativeCloseDexEntryV1`.

Each `native-close-dex-entry-v1` has exact `fieldCount=4`: `package_output_name` as
exact UTF-8 `base` or an already validated split name; `dex_entry_name` as exact ASCII
matching `classes.dex` or `classes(?:[2-9]|[1-9][0-9]+)[.]dex`;
`uncompressed_length` as uint64 positive length at most 268,435,456; and
`content_sha256` as raw SHA-256 of the streamed uncompressed DEX bytes. Entries sort
strictly by unsigned UTF-8 package-output name then DEX entry name; duplicates, gaps in
the numeric sequence within a package output, unreadable bytes, length/CRC disagreement,
or total uncompressed DEX bytes above 536,870,912 fail. During packaged verification,
the raw local-header and central-directory filename bytes must be equal, ASCII, and the
exact canonical entry name. Local and central general-purpose flags must be equal and
exactly `0x0000` or `0x0800`: UTF-8 bit 11 may be zero or one because ASCII has the same
bytes, while bit 3 data descriptors and every other flag are forbidden. Local and
central compression method, CRC-32, compressed size, and uncompressed size must be
equal; method is exactly STORED(0) or DEFLATED(8), and the streamed uncompressed bytes
must reproduce length and CRC. STORED payload length equals uncompressed length;
DEFLATED input reaches end-of-stream exactly at the declared compressed-range end with
no unused or unconsumed byte. Central extra/comment lengths are zero. A local extra is
either empty or `1..65,535` zero bytes of zipalign padding; it has no parsed field
identity.
ZIP64 EOCD/locator, `0xffffffff` size/offset sentinels, ZIP64/Unicode-path extra fields,
data descriptors, encryption, malformed names, or duplicate raw/decoded names fail.
Hashing the canonical code-image bytes yields `native_close_code_image_sha256`.

### Canonical whole-file APK ZIP view

Every planned, final, or installed base/split APK is parsed independently by this one
whole-file grammar before any DEX/JNI entry is admitted. The parser freezes the positive
file length and uses checked unsigned-64 arithmetic for every addition, subtraction, and
range comparison. It never allocates from an unvalidated ZIP length/count. It does not
use `ZipFile` enumeration, `rfind`, a backward signature scan, recovery mode, or a second
parser view. The only EOCD candidate is the 22 bytes beginning at exact
`eocdOffset = fileLength - 22`; those bytes must contain the little-endian EOCD signature
`0x06054b50`, zero disk number, zero central-directory start disk, equal entries-on-this-
disk and total-entry counts in `1..65,534`, zero archive-comment length, and non-sentinel
32-bit central-directory size and offset. Checked
`centralOffset + centralSize` must equal `eocdOffset`. A short file, `0xffff` count,
`0xffffffff` size/offset, multi-disk value, comment, trailing byte, underflow, overflow,
or any other placement fails.

Starting at `centralOffset`, the parser consumes exactly the declared entry count and no
other count. Every record begins with little-endian central signature `0x02014b50`; its
complete fixed header plus raw name, extra, and comment ranges must remain inside the
declared central range. The cursor after the declared final record must equal
`eocdOffset` exactly. Every entry has disk-start zero, non-sentinel 32-bit compressed/
uncompressed sizes and local-header offset, central extra length zero, central comment
length zero, flags exactly `0x0000` or `0x0800`, and method exactly STORED(0) or
DEFLATED(8). Its nonempty raw name is canonical ASCII: local and central bytes are
identical; it has no NUL or backslash, no leading/trailing slash, and no empty, `.` or
`..` path component. Raw names are unique across the complete package output. Directory
entries, Unicode/path extra aliases, and decoded aliases are not permitted. No consumer
may case-fold, normalize, or otherwise replace the exact raw ASCII lookup key.

For every central record, its unique local offset identifies an exact little-endian
local signature `0x04034b50` before `centralOffset`. The local raw name, flags, method,
CRC-32, compressed size, and uncompressed size equal the central values. Its local extra
is empty or consists only of `1..65,535` zero zipalign bytes. Bit 3 and therefore a data
descriptor are impossible. Define the local record range as its 30-byte fixed header,
name, local extra, and exactly the declared compressed payload. Each range is checked
nonempty and bounded before `centralOffset`. Sorting all declared entries by unsigned
local offset must yield first offset zero, strict unique offsets, and exact adjacency:
each next start equals the prior end. Thus local headers/payloads neither overlap nor
leave an opaque hole.

Let `payloadEnd` be the end of the last adjacent local range. If
`payloadEnd == centralOffset`, the APK has no APK Signing Block. Otherwise the complete
gap `[payloadEnd, centralOffset)` must be one structurally valid APK Signing Block and is
the only permitted non-ZIP region. Its length is at least 44 bytes. The final 24 bytes
are little-endian uint64 `blockSize` followed by exact ASCII
`APK Sig Block 42`; checked `blockSize + 8` equals the complete gap length, `blockSize`
is at least 36, and the uint64 at `payloadEnd` equals the same `blockSize`. The region
from `payloadEnd + 8` through `centralOffset - 24` contains one or more adjacent signing
pairs. Each pair is little-endian `uint64 pairLength || uint32 id || value`, where
`pairLength >= 4`, the nonzero ID is unique, `value` has exactly `pairLength - 4` opaque
bytes, checked pair bounds stay inside the region, and the final pair ends at the footer
with no padding. Pair values are never decoded, allocated as a whole, or scanned for ZIP
signatures. RuntimeArtifactId still binds every signing-block byte.

This positional partition is exhaustive:
`local-records || optional-signing-block || central-directory || exact-EOF-EOCD`.
ZIP64 records, locators, sentinels, extra fields, an unlisted local record, an alternate
central range, or a shadow EOCD view cannot participate. EOCD/central/local/signing-magic
byte sequences occurring inside a declared compressed payload or opaque signing-pair
value are inert data and do not themselves cause rejection; only the structural offsets
above select records. Build, CI, and runtime must reproduce the identical complete
entry view before comparing DEX/JNI canonical sets.

Whole-file negative fixtures include nonzero EOCD comment; one-byte prefix/trailing or
truncated EOCD; disk/count/sentinel mismatch; central range under/over-consumption;
declared-count mismatch; a parser choosing an embedded/shadow EOCD or central header;
duplicate/out-of-range local offsets; local range hole, alias, traversal, or overlap;
local/central name or metadata disagreement; nonzero/Unicode extra; and signing-block
short length, bad magic, unequal sizes, pair underflow/overflow, duplicate/zero ID, or
nonexact pair consumption. Positive controls include an unsigned adjacent APK, the
current adjacent APK Signing Block layout, and inert signature bytes inside a declared
payload/value; the latter must not be blanket-scanned or mistaken for a shadow view.

Phase A reads the final variant DEX outputs before asset packaging. The final packaged
APK set is independently reopened and must reproduce the exact same canonical code
image. A build task running any Java/Kotlin/desugar/shrink/dex transform after the Phase
A snapshot, or final-package mismatch, fails before publication. Binding final
output bytes replaces any subjective attempt to enumerate tools that “can transform”
dependency bytecode.

`NativeCloseProofBasisV1` is domain `native-close-proof-basis-v1`, exact
`fieldCount=5`:

1. `schema_revision`: exact UTF-8 `native-close-proof-basis-v1`;
2. `native_close_policy_set_sha256`: raw SHA-256 of the exact PolicySetV1 above;
3. `dependency_artifacts_value_sha256`: raw SHA-256 of the exact canonical
   WorkloadBuildManifestV3 `dependency_artifacts` field value, including pinned Tasks
   Vision AAR/JAR bytes;
4. `build_variant`: canonical `native-close-build-variant-v1`, exact `fieldCount=2`,
   containing `build_type` as exact UTF-8 and `minified` as one byte;
5. `native_close_code_image_sha256`: raw code-image SHA-256.

Hashing exact basis bytes yields `proof_basis_sha256`. Build and CI independently
construct one basis for each registered canonical `build_variant` from current pre-
workload field values and that variant's Phase A output, parse every canonical field,
re-encode byte-identically, and recompute all five values. Two different basis hashes
may coexist only when their complete canonical `build_variant` values differ; two bases
for the same build-variant value, or a flavor/variant dimension not represented by
`build_type` plus `minified`, fails and requires a new schema revision. Debug and release
can therefore coexist when they have distinct exact build-variant values.

Every accepted bundle carries its own exact canonical basis preimage and matching hash
as specified below. CI must reproduce fields 2 through 5 for every registered basis from
the current five policy files, exact dependency field value, declared build variant, and
that variant's Phase A CodeImage; an unavailable variant reconstruction fails registry
generation. For the variant currently being packaged, the reproduced bundle-carried
basis bytes, without regeneration or substitution, become the exact
WorkloadBuildManifestV3 `native_close_proof_basis` field. After workload generation,
build and CI require byte equality with that embedded field.

Runtime does not have repository rules or build-tool artifacts and never pretends to
recompute them: it parses those exact embedded basis bytes, reconstructs field 2 from
the five exact `build_files` entries, cross-validates fields 3/4 against that manifest,
recomputes the installed DEX code image for field 5, re-encodes the identical canonical
basis, then hashes the validated bytes. No component looks up a hash preimage or trusts
an evidence digest or approval to supply missing basis bytes: the bundle is the sole
preimage carrier, and the current workload is the runtime authority for which fully
validated registry basis is active.

## Installed JNI set

The installed or planned package set must produce a sorted unique outer list of `1..4`
represented ABI sets; zero represented sets is unproved and invokes zero native calls.
For each ABI represented by a native entry, build canonical
`native-close-installed-jni-set-v1`, exact `fieldCount=2`:

1. `abi`: exact UTF-8 one of `arm64-v8a`, `armeabi-v7a`, `x86`, or `x86_64`;
2. `entries`: uint32 count `1..256`, followed by
   `uint32_be(entryLength) || NativeCloseJniEntryV1`.

Each `native-close-jni-entry-v1` has exact `fieldCount=4`:
`package_output_name` as exact UTF-8 `base` or validated split name; `jni_entry_name` as
exact ASCII ZIP entry `lib/<abi>/<logicalName>` where `logicalName` matches
`[A-Za-z0-9._+-]+[.]so`; `uncompressed_length` as uint64 positive length at most
268,435,456; and `content_sha256` as raw SHA-256 of streamed uncompressed member bytes.
Entries sort strictly by unsigned UTF-8 package-output name
then entry name. Raw local-header and central-directory filename bytes must be equal,
ASCII, and the exact canonical entry name. The exact same flag/method/CRC/size/zero-
padding/no-descriptor/no-ZIP64 rules used for DEX apply to JNI. ZIP general-purpose
UTF-8 bit 11 may be zero or one; directory, encrypted, Unicode-path alias extra fields, path-traversing,
malformed, duplicate central-directory, duplicate raw/decoded-name, cross-split
logical-path collision, unexpected ABI, unreadable entry, length/CRC disagreement,
per-ABI total above 536,870,912, or changing bytes is incomplete.
STORED versus DEFLATED metadata is not hashed; the whole installed APK bytes remain
separately bound by RuntimeArtifactId. Hashing the canonical set yields
`installed_jni_set_sha256`.

Build rows use the Phase A merged-JNI/package-output plan. After registry/workload asset
packaging, CI recomputes every final-output JNI set and requires exact equality. Runtime
uses the already artifact-verified PackageManager base/split index pairs and the same
central-directory/uncompressed-byte algorithm. A universal APK requires every
represented ABI row; an ABI split normally yields one. The outer list sorts strictly by
unsigned UTF-8 ABI and rejects duplicates. An absent or unplanned set has no row and
invokes zero native calls.

## Content-addressed proof bundle and approval

The only bundle discovery root is
`docs/evidence/native-close-fence-v1/bundles/`. Every entry is a regular non-symlink
file named `<lowercase 64-hex sha256>.bin`; its filename must equal the SHA-256 of its
complete bytes. Any other entry, duplicate decoded row, malformed bundle, or root size
above 16,777,216 bytes fails generation. Evidence artifacts are regular content-
addressed files under `docs/evidence/native-close-fence-v1/artifacts/`, named by their
lowercase SHA-256 with no extension; no bundle may reference a missing, symlinked,
length/hash-mismatched, or untracked artifact.

`NativeCloseProofEvidenceV1` is domain `native-close-proof-evidence-v1`, exact
`fieldCount=9`:

1. `schema_revision`: exact UTF-8 `native-close-proof-evidence-v1`;
2. `producer_id`: canonical ASCII identity below;
3. `proof_basis_sha256`: raw SHA-256 of the exact fully parsed and recomputed
   ProofBasis bytes carried by this evidence's BundleV1;
4. `os_api`: uint32 exactly 26..37;
5. `abi`: exact UTF-8 ABI from the allowlist;
6. `installed_jni_set_sha256`: raw JNI-set SHA-256;
7. `delegate_mask`: one byte exactly `0x03` for CPU and GPU;
8. `claim`: one byte exactly
   `0=NO_RESULT_OR_ERROR_CALLBACK_ENTRY_AFTER_CLOSE_RETURN_AND_ADMITTED_DRAIN`;
9. `evidence_entries`: uint32 count `8..64`, followed by
   `uint32_be(entryLength) || NativeCloseEvidenceEntryV1`.

`producer_id` is either `agent:/<canonical-task-path>` with each nonempty path segment
matching `[a-z0-9_]+`, or `github:<login>` matching
`[a-z0-9](?:[a-z0-9-]{0,37}[a-z0-9])?`; its total byte length is 7..128. Aliases,
display names, uppercase, whitespace, and any other identity grammar fail.

Each evidence entry is domain `native-close-evidence-entry-v1`, exact `fieldCount=3`:
`category` as one byte, `artifact_length` as uint64 positive length at most 4,194,304,
and `artifact_sha256` as raw artifact SHA-256. Entries sort by category then hash and
may not duplicate either pair. The
categories are exact and every bundle requires at least one of each:

```text
0 FINAL_DEX_DISASSEMBLY
1 JNI_SOURCE_OR_DISASSEMBLY
2 CPU_CLOSE_RACE
3 GPU_CLOSE_RACE
4 CALLBACK_AFTER_CLOSE_ADVERSARIAL
5 THROW_TIMEOUT_AND_DRAIN
6 PROCESS_DEATH_CUTS
7 BUILD_DAG_AND_PHASE_A_PROVENANCE
```

Total referenced evidence bytes may not exceed 67,108,864. Hashing the exact evidence
manifest yields `proof_evidence_sha256`.

`NativeCloseProofApprovalV1` is domain `native-close-proof-approval-v1`, exact
`fieldCount=9`:

1. `schema_revision`: exact UTF-8 `native-close-proof-approval-v1`;
2. `proof_evidence_sha256`: raw evidence SHA-256;
3. `reviewer_id`: canonical ASCII using the exact `producer_id` grammar;
4. `reviewed_commit`: exact lowercase 40-hex commit;
5. `reviewed_tree`: exact lowercase 40-hex tree;
6. `verdict`: one byte exactly `0=APPROVE`;
7. `p0_count`: uint32 zero;
8. `p1_count`: uint32 zero;
9. `p2_count`: uint32 zero.

The reviewer must
use the same identity namespace as the evidence producer and the complete IDs must be
unequal. CI verifies that comparison, the named commit/tree exists locally, and the tree
contains the referenced evidence artifacts plus the five exact policy files whose
canonical PolicySetV1 hash equals field 2 of the exact bundle-carried parsed ProofBasis
and the current build PolicySetV1 hash. EvidenceV1's basis hash must equal the SHA-256 of
those exact carried bytes; neither CI nor the reviewer infers basis fields from a bare
digest. Approval contains no bundle/registry/workload/APK hash, so it is not self-
referential.

`NativeCloseProofBundleV1` is domain `native-close-proof-bundle-v1`, exact
`fieldCount=7`:

1. `schema_revision`: exact UTF-8 `native-close-proof-bundle-v1`;
2. `proof_basis`: `uint32_be(length) ||` exact canonical NativeCloseProofBasisV1
   bytes, with positive length at most 4,096 before allocation;
3. `proof_basis_sha256`: raw SHA-256 of those exact basis bytes;
4. `proof_evidence`: `uint32_be(length) ||` exact EvidenceV1 bytes;
5. `proof_evidence_sha256`: raw evidence SHA-256;
6. `proof_approval`: `uint32_be(length) ||` exact ApprovalV1 bytes;
7. `proof_approval_sha256`: raw approval SHA-256.

Generation parses and canonical-re-encodes field 2, requires byte identity, recomputes
field 3, and requires EvidenceV1 field 3 to equal it. Evidence and approval then cross-
reference exactly. Registry field 6 hashes the complete canonical bundle. Arbitrary
prose, an unparsed review note, approval alone, or a hash without its exact basis bytes
cannot authorize a row.

## Registry bytes and ordering

The registry is domain `native-close-fence-registry-v1`, exact `fieldCount=2`:

1. `schema_revision`: exact UTF-8 `native-close-fence-registry-v1`;
2. `entries`: uint32 count `0..512`, followed by
   `uint32_be(entryLength) || NativeCloseFenceEntryV1` bytes.

The complete registry is at most 131,072 bytes; each entry is 128..1,024 bytes before
allocation. Each `native-close-fence-entry-v1` has exact `fieldCount=6`:

1. `proof_basis_sha256`: raw 32-byte hash of this row's exact bundle-carried ProofBasis;
2. `os_api`: uint32 exactly 26..37;
3. `abi`: exact UTF-8 ABI from the allowlist;
4. `installed_jni_set_sha256`: raw 32-byte JNI-set hash;
5. `delegate_mask`: one byte exactly `0x03` with every other bit zero;
6. `proof_bundle_sha256`: raw 32-byte complete-bundle hash.

The exact sort/equality key bytes are
`basis[32] || uint32_be(api) || uint32_be(abiLength) || UTF8(abi) || jniHash[32]`.
Entries sort strictly by unsigned lexicographic key bytes and may not duplicate the
decoded four-field tuple. Unknown fields, trailing bytes, noncanonical order, zero
hash, missing/mismatched bundle, or duplicate key rejects the whole registry.

For every entry, generation parses the content-addressed bundle and requires exact
field equality: entry fields 1/2/3/4/5 equal EvidenceV1
`proof_basis_sha256`/`os_api`/`abi`/`installed_jni_set_sha256`/`delegate_mask`;
entry field 1 and EvidenceV1 field 3 equal BundleV1 field 3; and BundleV1 field 3 is the
recomputed SHA-256 of its parsed, byte-identically re-encoded field 2. That basis field 2
equals the PolicySet hash of the current five policy files, and its dependency, build-
variant, and CodeImage fields equal the independently reproduced values for that exact
registered variant. EvidenceV1 `claim` equals zero; ApprovalV1 hashes that exact
EvidenceV1 and has the required zero-finding verdict. A bundle may be referenced only by
that identical evidence tuple. Any cross-row bundle reuse, unchecked claim, stale policy
set, missing/noncanonical basis preimage, random or mismatched hash, duplicate canonical
build variant with a different basis, or field mismatch fails the complete registry.

Multiple basis hashes in one registry are legal only for distinct fully carried and
independently reproduced current-policy build variants. Generation fixtures mutate each
of the bundle, Evidence, and row basis hashes by one byte; omit, truncate, or
noncanonically encode the basis preimage; substitute a prior-policy or random basis;
mix a row with a basis hash that has no matching bundle preimage; register two bases for
one canonical build variant; copy a bundle across a different API/ABI/JNI row; and alter
one carried basis byte before re-encode. Every negative case rejects the complete
registry. Fully carried debug/release bases with distinct canonical build variants and
current PolicySet bytes are the positive multi-basis control.

## Runtime authorization and generation binding

Before any journal persistence, cache lookup/service, or native create, runtime must:

1. validate installed APK/workload/model identity and registry bytes/hash;
2. parse/cross-validate embedded ProofBasis and PolicySet hash, recompute installed
   CodeImage, and require its exact basis hash;
3. enumerate a nonempty sorted unique `1..4` list of installed ABI JNI sets by the exact
   algorithm above;
4. require one unique current-API registry row for every such ABI/set, exact basis/JNI
   hashes, delegate mask `0x03`, and a bundle hash already validated at build/CI.

Step 4 considers only rows whose basis hash equals the hash of the exact fully
recomputed Workload-embedded basis from step 2. Fully validated rows for other canonical
build variants remain inert registry data: runtime does not parse their external
bundles, substitute their bases, fall back to them, or let their approvals authorize the
current workload. A missing/duplicate current-basis row for any installed ABI rejects
the complete current authorization and invokes zero native calls; the presence of a
different valid variant row cannot satisfy that absence.

Only after steps 1-4 complete may one timely journal mutation reserve the exact active
route and return its committed ProbeBaseScopeId, attempt epoch, route ordinal, and
delegate. That mutation is conservative crash containment, not proof authority. After
its timely committed return, the state gate checked-increments `runtime_generation` and
constructs the sole immutable `NativeCloseAuthorizationSnapshotV1` from the still-
verified identity plus that exact reservation. Its fields are: nonzero uint64
`identity_generation`; nonzero uint64 `runtime_generation`; RuntimeArtifactId; workload,
registry, PolicySet, basis, and code-image hashes; current uint32 API; the returned
ProbeBaseScopeId; nonzero returned attempt epoch; uint32 returned finite-trace route
ordinal; returned one-byte CPU/GPU delegate; and `authorization_rows`, an immutable list
of `1..4` exact canonical `NativeCloseFenceEntryV1` byte strings. Rows remain associated
with their basis/API/ABI/JNI/delegate-mask/bundle fields, sort strictly by unsigned UTF-8
ABI, and equal the already validated row for every installed ABI; independently sorted
hash arrays and a derived “row hash” are forbidden. The gate then enters
`IDENTITY_VERIFIED(identityGeneration,runtimeGeneration,routeOrdinal)`. Immediately
before that route's CPU or GPU `PoseLandmarker.createFromOptions` entry, it CASes once to
`NATIVE_CREATE_AUTHORIZED(identityGeneration,runtimeGeneration,routeOrdinal,delegate)`
only if the exact snapshot is current, the delegate bit is present, journal/scope/epoch
guards still match, and all live safety latches are clear. Successful create return
enters `NATIVE_LIVE` for the same tuple. Clean close return, admitted-callback drain,
seal, resource disposal, and matching journal transition enter `NATIVE_CLOSED` and
destroy that snapshot. Only then may another route reserve its journal state and create
a newly incremented snapshot. Create/close/resource uncertainty invalidates the
identity generation and forbids every later same-process create. A snapshot is consumed
by one CAS and can never return to IDENTITY_VERIFIED or authorize two runtimes. A stale/
mutated snapshot, overflow, or failed CAS invokes no native call. Snapshot fields are
in-memory evidence only; no paths or row lists are logged or persisted.

A missing, malformed, duplicate, hash/API/ABI/code/JNI/delegate/bundle mismatch ends
exactly `PROBE_INCOMPLETE(RUNTIME_CALLBACK_FENCE_UNPROVEN)`, invokes zero native calls,
writes no result, and serves no cache. Device manufacturer/model is never a key.

## Semantic acceptance boundary

The evidence for each exact row must show from final DEX plus exact JNI source or
disassembly that close return, together with the app's admitted-callback drain,
establishes the exact no-later-result-or-ErrorListener-entry claim. Its executable
evidence must cover CPU/GPU close races, admitted drain, callback-after-close injection,
throw/timeout, process-death cuts, and the Phase A build provenance on the exact API/ABI
row. After the approved bundle and assets are packaged, the separate final-package
DEX/JNI equality check must pass before publication; it is deliberately not an
input to the earlier proof bundle. The independent approval must have zero actionable
P0/P1/P2.

The semantic argument must quantify over every scheduler interleaving, callback timing,
and driver/OEM outcome allowed by the shipped DEX/JNI and public API contract for that
API/ABI. Device success, a vendor allowlist, or one observed driver behavior cannot
stand in for that universal code-level fence; an unclosed external behavior leaves the
row unapproved.

A finite quiet period, successful close return alone, source for different bytes,
emulator extrapolation to physical G2/G8, arbitrary hashed prose, or an LLM assertion
cannot approve a row. Runtime never downloads or patches proof rows. A row is revoked
only by producing new content-addressed evidence/approval and therefore new registry,
workload, and runtime identities.
