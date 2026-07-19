# Capability v12 pre-commit validation and rejection

Date: 2026-07-15 (Asia/Seoul)

## Verdict boundary

This report records the uncommitted `capability-v12` remediation after review 11 rejected
uncommitted v11 with P0=0, P1=1, P2=1. Three fresh current-byte audits rejected v12
before commit with deduplicated P0=0, P1=1, P2=7. It is not an acceptance record, an
implementation authorization, or evidence that Slice 1B exists in the APK. v12 was
never committed and must not receive an exact-commit review or authorization transition.

No physical Android device is connected. Camera framing, two-person recognition,
perceptual quality, physical performance/thermal behavior, and human safety
comprehension remain unobserved. No visual asset is deployed and G6 remains blocked.

## Rejected-v11 findings being remediated

- P1: a final-file-only link check allowed a governed path to escape the repository
  through an ancestor Windows junction or symlink.
- P2: enumerated authorization/governing-authority sentences missed equivalent positive
  prose and rejected comment/history/code contexts.

The historical v11 authority manifest is immutable rejected evidence at SHA-256
`7cfa899e4251991effa2b092118c588b0d22d3f784bf08d45c2f676d2b5363bd`.
Review 11 is immutable rejected evidence at SHA-256
`d6476b1207c853c183a67e728d7c2e3e499e7741defbc8ebc50dc9afd2bfda37`.

## Validation ledger

| Check | Actual result |
| --- | --- |
| v12 authority hashes/manifest | PASS; five authority hashes match; canonical manifest SHA-256 `458001ccc978e1c591090c2824538071248b3f890467a7ef87820e4a0fcc9bc6` |
| governed-path containment regressions | REJECT; preflight checks passed but an actual post-validation Windows junction swap let outside authority/review bytes pass; a broken forbidden-path junction also bypassed raw `exists()` |
| authorization/governance context regressions | REJECT; CommonMark formatting/wrapping, paraphrase families, mixed history clauses, conditional/meta-denial contexts, and fence grammar produced five deduplicated P2 classes |
| focused/full Python regressions | PASS WITH THE SAME 3 SKIPS; capability 34 passed/3 skipped of 37, supply 53/53, full 162 passed/3 skipped of 165 |
| capability/supply/APK/asset policy | PASS for capability, supply, and APK policy; asset gate reports zero deployed assets and does not approve G6 |
| forced offline Gradle test/lint/APKs with persisted raw log | PASS; 224/224 actionable tasks executed, exit 0, 77.9 seconds; raw log SHA-256 `a0c1a1a258bedd796faee5e4af8da874add043532f527edbc3b35e1148de376d` |
| native ownership/runtime license/JUnit/APK evidence | PASS; native 16 entries/3 owners, licenses 159 coordinates, JUnit 52/52, APK hash/size stable |
| three independent current-byte audits | REJECT; path audit P0/P1/P2=0/1/2, prose audit 0/0/5, whole-candidate audit 0/0/1 with its P2 overlapping the prose findings |

The independent-audit rejection permanently prevents freezing, committing, or
authorizing v12. Passing deterministic tests and technical Android checks do not
override a reproduced policy-gate bypass.

## Frozen candidate authorities

| Authority | SHA-256 |
| --- | --- |
| `docs/adr/ADR-011-measured-capability-probe-policy.md` | `c0965243988f8fd9c52ec20da3057d1ff8cc6d3a38835f4380809cd03b787944` |
| `docs/contracts/capability-store-v4.md` | `83367cd10dbc66cbe49e895b5bc4a14089ee19f47ce041f5a17e2d9da47ebf8b` |
| `docs/contracts/native-close-fence-proof-v1.md` | `7a8d886d46fab82e192912f87e70292722ed0daa967923aaf2f7b995ae74f20a` |
| `docs/contracts/recovery-journal-v5.md` | `fd6e34118fb6b0666a8eba077ce111f558c678ef7c229e65570f9ccb552553c7` |
| `docs/execution/slice-1b-contract.md` | `a36738205708bf475068e63f3eaa018c102efa2e7f1c1b078591e1ffbab62007` |
| `docs/contracts/capability-policy-authority-v12.json` | `458001ccc978e1c591090c2824538071248b3f890467a7ef87820e4a0fcc9bc6` |

The manifest is compact canonical JSON plus LF, binds the exact sorted five-file set,
binds contiguous rejected reviews 01-11, and remains
`implementation_authorized=false`. A focused documentation precheck first found two P2
traceability defects (a stale ten-review count and an omitted human-facing fifth
authority); both were corrected, the execution hash/manifest were regenerated, and a
read-only closure rereview returned P0=0, P1=0, P2=0 at the hashes above.

## Governed-path and prose remediation attempt

One shared preflight path helper handled the current manifest, both historical manifests, all
five authorities, all eleven reviews, slice status, evidence index, and current/historical
precommit ledgers. It rejects absolute or parent escape, strict resolved escape,
missing/nonregular targets, final or ancestor symlinks, and junction/reparse ancestors
before following them. The real Windows ancestor-junction fixture passed. Three tests
that require creating a privileged Windows symbolic link were skipped because the host
returned `WinError 1314`; deterministic final-file, ancestor-directory, redirected-root,
and reparse detector fixtures cover those branches and passed. The skips are not counted
as executed symlink integration evidence. The audit then proved that cached validated
`Path` values were dereferenced later: swapping `docs/contracts` or `docs/reviews` to an
outside junction after preflight made outside bytes/enumeration pass. The rejected v4
sentinel also used follow-sensitive `exists()` outside the helper, so a broken junction
was treated as absent. The test oracle did not inventory and forbid every later raw
filesystem dereference.

The attempted line-aware prose pass strips HTML comments and fenced code, treats blockquotes,
standalone quoted/code examples, and explicitly anchored rejected/history/counterexample
contexts as non-normative, but retains inline-code content inside normative prose. It
checks reusable affirmative authorization, start/proceed, modal implementation, and
stale-governance lexeme families. Local negation applies only to the matched clause;
mixed lines with a separate positive clause still fail. Root adversarial checks exposed
and closed three local-negation false positives and twelve broader semantic positive
bypasses before this snapshot. Smart quotation/apostrophe behavior is expressed with
ASCII `\\u2018`-`\\u201d` source escapes and covered deterministically. The independent audit
nevertheless reproduced five remaining classes:

1. emphasis, links, `<br>`, tables, and soft-wrapped paragraph/list claims were not
   normalized into rendered logical blocks;
2. actor/permission/commence and binding/source-of-truth/follows/supersedes paraphrases,
   plus spaced or Unicode-separated legacy identifiers, remained outside the grammar;
3. a history/rejected prefix suppressed a later positive clause on the same line;
4. `before`/`unless`/`only after` future gates and meta-denials became false positives;
5. fence closing length/trailing-info rules did not follow CommonMark.

The structured manifest stayed false, so these remain P2 prose-lint defects rather than
a machine authorization flip. The ledger's earlier prose PASS claim is withdrawn.

## Independent current-byte findings

| Priority | Deduplicated finding | Required v13 direction |
| --- | --- | --- |
| P1 | Cached path preflight and later dereference admit an actual post-validation junction swap for authority files and review enumeration. | Capture bytes/metadata from anchored non-following handles, verify final handle paths stay under canonical root, parse/hash/lint those immutable bytes, and freeze review enumeration through the same anchored snapshot. |
| P2 | Broken junction at forbidden `recovery-journal-v4.md` passes follow-sensitive `exists()`; tests do not independently inventory/prohibit raw filesystem I/O outside the snapshot reader. | Treat only `FileNotFoundError` from anchored lexical inspection as absence; add an independent exact I/O inventory plus broken-junction and swap fixtures. |
| P2 | CommonMark formatting/logical wrapping bypasses positive-claim recognition. | Normalize rendered logical blocks while preserving excluded code/quote contexts. |
| P2 | Common authorization and stale-governance paraphrase/identifier forms remain enumerable gaps. | Use bounded semantic token families, normalize identifier spacing/hyphens, add actor/permission/binding/source-of-truth/follows/supersedes cases, and label the prose lint defense-in-depth. |
| P2 | Non-normative prefixes suppress unrelated later positive clauses. | Split and classify clauses; scope history/rejection only to its labelled or quoted object. |
| P2 | Future gates, conditions, and meta-denials become false positives. | Classify local `before`/`until`/`unless`/`if`/`only after` and reporting/directive negation without masking another positive clause. |
| P2 | Fenced-code opening/closing state is not CommonMark-correct. | Track marker character and opening length; require a whitespace-only close of at least that length and marker-specific info rules. |

Current validator evidence hashes:

| File | SHA-256 |
| --- | --- |
| `scripts/validate_capability_policy.py` | `c6998d052f2723e63a1964de091463a80c657477032375565da9edff10c9d0a0` |
| `scripts/tests/test_validate_capability_policy.py` | `ef9a108f789ff2a4e49dc51e8e4b933cd909242bc313d795394c0a48a401e8cd` |
| `scripts/validate_supply_chain.py` | `5e96664ae67ff42ee32acf5a08b714feba983d0d2ae97c3997b6899549df5b78` |
| `scripts/tests/test_validate_supply_chain.py` | `b2144d9f475a9d539fb53cec8e2d589b8d18016474c7201a55327c340ef2502b` |

## Deterministic validation observed

| Check | Actual result |
| --- | --- |
| `python -B -m unittest scripts.tests.test_validate_capability_policy -v` | 37 run; 34 passed, 3 skipped for `WinError 1314`; 0 failures/errors |
| `python -B -m unittest scripts.tests.test_validate_supply_chain -v` | PASS, 53/53 |
| `python -B -m unittest discover -s scripts/tests -v` | 165 run; 162 passed, the same 3 skipped; 0 failures/errors |
| `python -B scripts/validate_capability_policy.py --root .` | PASS; v12, five authorities, eleven rejected reviews, implementation false |
| `python -B scripts/validate_supply_chain.py --root .` | PASS |
| `python -B scripts/validate_project_assets.py --root .` | `NO_DEPLOYED_VISUAL_ASSETS`; G6 not approved |
| `git diff --check` | PASS |

Clean UTF-8 console logs were persisted without PowerShell error-record decoration:

| Log | Bytes | SHA-256 |
| --- | ---: | --- |
| `v12-capability-focused.log` | 8,128 | `fe3c0aa411f41da03dd1778c1b17a283b4bd9857b96e200a0bde20e9a53fb36a` |
| `v12-supply-focused.log` | 9,048 | `e4e8d058902cbd9ffc4a32d6ed9204c11f63b46b5b9a9f115b0232c14123e1fa` |
| `v12-python-full.log` | 27,908 | `1c8eb07199cfd3e7d6cbe78c6df194a5bbace96fafaabf68c25c1d3210086075` |

## Android build and artifact evidence

The full build was rerun from the current workspace with all tasks forced, strict
dependency verification, and offline resolution:

```text
gradlew --console=plain --no-daemon --no-configuration-cache
  -Pkotlin.incremental=false --offline --dependency-verification strict
  test lint :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks
BUILD SUCCESSFUL in 1m 17s; exit=0, elapsed=77.9 s; 224/224 actionable tasks executed
```

The complete Gradle console stream is preserved at `v12-gradle-full.log`: 25,832 bytes,
SHA-256 `a0c1a1a258bedd796faee5e4af8da874add043532f527edbc3b35e1148de376d`.
JUnit XML was re-read after the build: 8 files, 52 tests, 0 failures, 0 errors, and
0 skipped.

| Artifact/check | Actual result |
| --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 68,384,279 bytes; SHA-256 `4e89a7ed05e56a0b93bb84fcf0e6f27463aef3b2cbcc1bfcd8ae4bce7306336d` |
| APK policy | PASS; `com.motionarcade.app.debug`; exact CAMERA plus package-scoped receiver permission; pinned model SHA-256 `59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a` |
| Native runtime ownership | PASS; 16 native entries, 3 exact verified AAR owners |
| Runtime technical license audit | PASS; 159 `releaseRuntimeClasspath` coordinates |
| License inventory comparison | generated and checked-in SHA-256 both `a248299bcbf597d07f3cae07ea3e9b60e81b662033b162302d110b6443e1bf1b` |

No Android source/build file changed in the v12 policy/validator remediation. These
checks support the unchanged technical baseline under tested local conditions; they do
not prove Slice 1B implementation, legal redistribution approval, physical-device
behavior, or release readiness.

## Required adversarial fixtures

- current/historical manifest, authority, review, slice status, evidence index, and
  current/historical precommit-evidence paths must reject final symlinks, ancestor
  symlinks, Windows junction/reparse ancestors, lexical escape, strict resolved escape,
  missing targets, directories, and non-regular final objects;
- canonical in-root regular files must remain the positive control;
- Rejected counterexample fixtures that the grammar must catch include
  `Implementation authorization is granted.`,
  `Implementation is now authorized.`, `` `implementation_authorized` is `true`. ``,
  `Slice 1B is ready for implementation.`, and the reproduced capability-v9 governing
  forms must fail;
- genuine denial, explicit rejected-history/counterexample prose, HTML comments,
  blockquotes, fenced code, and standalone code examples must not become false positives;
  mixed normative prose using inline-code identifiers and values must still be checked;
- v11 native ZIP/ProofBasis and recovery/rollback requirements remain unchanged and
  cannot be weakened to make this revision pass.

## Next gate

Preserve the reviewed v12 authority/manifest/validator/log bytes and record review 12 as
rejected history. Create a new non-authorizing `capability-v13` candidate that closes the
anchored-snapshot and logical-Markdown findings, then rerun focused/full/build/artifact
evidence and three fresh current-byte audits. No policy review proves physical-device
behavior, G2/G8, games, visual assets, or release readiness.
