# Capability v11 pre-commit validation and rejection

Date: 2026-07-15 (Asia/Seoul)

## Verdict boundary

This report records the uncommitted `capability-v11` remediation based on rejected exact
v10 commit `04a7705249a09167834ea41c8f6cc44b14f1c6fd` (tree
`9ff6c30ef2674f7a98197fb39dcdc00f8cc2bfdc`) and review 10. Three independent
current-byte audits rejected v11 before commit with one deduplicated P1 and one P2.
It is not an acceptance record, an implementation authorization, or evidence that
Slice 1B exists in the APK. v11 was never committed and must not receive an exact-commit
review or authorization transition.

No physical Android device is connected. Camera framing, two-person recognition,
perceptual quality, physical performance/thermal behavior, and human safety
comprehension remain unobserved. No visual asset is deployed and G6 remains blocked.

## Rejected-v10 findings being remediated

- P1: Gradle alternative exclusive range delimiters bypassed exact-version policy.
- P1: rollback deletion could erase same-artifact tombstone/poison evidence.
- P1: selected-entry ZIP checks lacked a unique whole-APK container grammar.
- P1: arbitrary registry basis hashes had no canonical ProofBasis preimage to inspect.
- P2: positive authorization and legacy-governance prose could bypass candidate lint.

The deduplicated exact-v10 verdict was P0=0, P1=4, P2=1. Review 10 is the normative
rejection record. No v11 validation result is credited until the remediation bytes,
current authority manifest, full tests, build, APK/native/license checks, and independent
pre-commit audits are complete and recorded below.

## Validation ledger

| Check | Actual result |
| --- | --- |
| v11 authority hashes/manifest | PASS; all five current bytes match the manifest; manifest SHA-256 `7cfa899e4251991effa2b092118c588b0d22d3f784bf08d45c2f676d2b5363bd` |
| focused Python regressions | PASS; capability policy 20/20 and supply-chain policy 53/53 |
| full Python regression suite | PASS; 148/148 |
| capability/supply/APK policy | PASS; candidate v11 remains non-authorizing, supply-chain policy passes, and the current debug APK has the exact approved permission/model policy |
| forced offline Gradle test/lint/APKs | PASS; 224/224 actionable tasks executed, exit 0, 67.4 seconds |
| native ownership and runtime license audit | PASS; 16 native entries map to 3 verified owners; 159 runtime coordinates; generated and checked-in license inventories match |
| independent pre-commit audits | REJECT; native P0/P1/P2=0/0/0, recovery P0/P1/P2=0/0/0, validator P0/P1/P2=0/1/1 |

`REJECT` permanently prevents committing, freezing, or authorizing this v11 snapshot.

## Independent-audit findings

| Priority | Reproduced defect | Required v12 remediation |
| --- | --- | --- |
| P1 | Capability authority/review inputs reject only a final symlink. An ancestor Windows junction was redirected outside the repository, the manifest hashes were refreshed, and the real validator returned PASS while `docs/reviews` resolved outside `root`. Authority, status, index, and evidence paths share the same containment weakness. | Resolve every governed input strictly under the canonical repository root, reject a final or ancestor symlink, reject Windows junction/reparse ancestors, require a regular final file, and add final-link, ancestor-link, junction, and out-of-root fixtures. |
| P2 | The prose lint catches the three review-10 strings but accepts equivalent positive statements such as `Implementation authorization is granted.`, `` `implementation_authorized` is `true`. ``, `Slice 1B is ready for implementation.`, and several forms saying capability-v9 governs. It also rejects the old sentence inside rejected-history prose or an HTML comment. | Define a line-aware normative grammar for positive authorization and stale governing-authority claims, exclude comments/code/rejected quotation/history contexts, and add every reproduced positive variant plus denial/history/comment/code controls. |

The structured manifest remained `implementation_authorized=false`, so the prose defect
did not independently flip machine authorization. It is still an actionable P2 because
the advertised contradiction/reference lint is neither complete nor context-correct.

## Frozen candidate authorities

| Authority | SHA-256 |
| --- | --- |
| `docs/adr/ADR-011-measured-capability-probe-policy.md` | `8af9a387ec9215128659ec44d94d318c2bbfb4f47e96831bd672a97c3d664d0f` |
| `docs/contracts/capability-store-v4.md` | `e4e970e006abea3dc4d0605484f9b88ce9f5c68312eacfa1e81a83ee60212f24` |
| `docs/contracts/native-close-fence-proof-v1.md` | `988c8a8ce98a8549ac1ac5b0d49e43f2c5b82c63f408a5a323695a3cbe9a90d1` |
| `docs/contracts/recovery-journal-v5.md` | `b6739973e59a5528ce6b5331dcb52cb4a92a2c37e3ef2bee7fb59e5a7e916a8a` |
| `docs/execution/slice-1b-contract.md` | `5b841dbde6031205ed82c014cebc6b2e07abb24bb894919a31931c2d47791217` |
| `docs/contracts/capability-policy-authority-v11.json` | `7cfa899e4251991effa2b092118c588b0d22d3f784bf08d45c2f676d2b5363bd` |

The manifest also pins rejected review 10 at SHA-256
`da50f6a04e7ce96f8488f62743ec8d6f217a4e75ec0c06468009407b7adf4383`
and preserves the immutable rejected-v10 manifest reference.

## Deterministic validation observed

| Check | Actual result |
| --- | --- |
| `python -B -m unittest scripts.tests.test_validate_capability_policy -v` | PASS, 20/20 |
| `python -B -m unittest scripts.tests.test_validate_supply_chain -v` | PASS, 53/53 |
| `python -B -m unittest discover -s scripts/tests -v` | PASS, 148/148 |
| `python -B scripts/validate_capability_policy.py --root .` | PASS; v11, five authorities, ten rejected reviews, implementation false |
| `python -B scripts/validate_supply_chain.py --root .` | PASS |
| `python -B scripts/validate_project_assets.py --root .` | `NO_DEPLOYED_VISUAL_ASSETS`; G6 not approved |
| `git diff --check` | PASS |

Validator evidence hashes at this candidate state:

| File | SHA-256 |
| --- | --- |
| `scripts/validate_capability_policy.py` | `98b1b17051b77c6d596bdabb4084aba4c53054c072f3c760220f92a811b80c06` |
| `scripts/tests/test_validate_capability_policy.py` | `968c1ecea92ba97520739fc71050cf7fe9d616954d08e280a2c0ed7342625ee2` |
| `scripts/validate_supply_chain.py` | `5e96664ae67ff42ee32acf5a08b714feba983d0d2ae97c3997b6899549df5b78` |
| `scripts/tests/test_validate_supply_chain.py` | `b2144d9f475a9d539fb53cec8e2d589b8d18016474c7201a55327c340ef2502b` |

The first integrated capability-policy rerun exposed one required-token mismatch caused
by a line break in the normative wording. The token was narrowed to the same normative
sentence and both the 20-case focused suite and the repository validator then passed.
That earlier run is not counted as a pass.

## Android build and artifact checks

The full Android command was forced offline with strict dependency verification and all
tasks rerun:

```text
gradlew --no-daemon --no-configuration-cache -Pkotlin.incremental=false
  --offline --dependency-verification strict
  test lint :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks
BUILD SUCCESSFUL in 1m 7s; exit=0, elapsed=67.4 s; 224/224 actionable tasks executed
```

Only the already-intended manifest-merger warnings for removal of `INTERNET` and
`ACCESS_NETWORK_STATE` appeared. JUnit XML was re-read after the build: 8 files,
52 tests, 0 failures, 0 errors, and 0 skipped.

| Artifact/check | Actual result |
| --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 68,384,279 bytes; SHA-256 `4e89a7ed05e56a0b93bb84fcf0e6f27463aef3b2cbcc1bfcd8ae4bce7306336d` |
| APK policy | PASS; package `com.motionarcade.app.debug`; exact CAMERA plus package-scoped receiver permission; pinned model SHA-256 `59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a` |
| Native runtime ownership | PASS; APK SHA-256 matched the artifact above, 16 native entries, 3 exact verified AAR owners |
| Runtime technical license audit | PASS; 159 `releaseRuntimeClasspath` coordinates |
| License inventory comparison | generated and checked-in SHA-256 both `a248299bcbf597d07f3cae07ea3e9b60e81b662033b162302d110b6443e1bf1b` |

An attempted `--expected-inventory` comparison against the historical Slice 0A native
inventory was rejected because that snapshot pins APK SHA-256
`58d350e136c50c2b1a407fd887e66a4939ceb8ac6348545a828a69d6c76f665f`,
not the current APK SHA-256. That mismatched-snapshot invocation is not credited as a
pass. The current APK was instead inspected directly and the validator returned
`NATIVE_RUNTIME=PASS` with the counts recorded above.

These checks support only the unchanged Slice 0A technical baseline and the v11 policy
candidate under the tested local conditions. They do not prove that Slice 1B is
implemented, that legal redistribution is approved, or that a physical-device game
flow works.

## Independent pre-commit status

- Focused rollback/store semantics rereview: P0=0, P1=0, P2=0 at execution-contract
  SHA-256 `08a6cc94fb310b8b5b203fd520156cead16bd014f63df528798cccdc02524780`.
  The rollback lines were not subsequently changed, but the execution authority later
  received native-summary wording and now hashes to the manifest value above. This is
  supporting evidence only, not a current-candidate approval.
- Native ZIP/bundle/multi-variant audit of all current bytes: P0=0, P1=0, P2=0.
  An independent exact-grammar parser reproduced 545 adjacent local records, exact
  central/EOCD consumption, and the 4,096-byte/two-pair signing block in the current APK.
- Recovery/store/rollback audit of all current bytes: P0=0, P1=0, P2=0. The six-file
  A -> B -> exact A preservation model and exact-key absorbing tombstones were coherent
  in the assigned policy scope; implementation/device execution remains unobserved.
- Validator/supply/APK/evidence audit of all current bytes: P0=0, P1=1, P2=1. The
  ancestor-junction escape and authorization/governance prose grammar defects above were
  reproduced against the real validator. Passing 20/20, 53/53, and 148/148 suites did
  not contain those adversarial cases and therefore cannot override the rejection.

## Next gate

Preserve this v11 manifest/evidence as rejected history, record review 11, and create a
new non-authorizing `capability-v12` candidate that closes both findings. Run focused
adversarial fixtures, full validation, a persisted/hash-bound raw Gradle log, and three
fresh current-byte audits before any v12 commit. Only a clean v12 pre-commit result may
advance to exact-commit review. No policy review proves physical-device behavior, G2/G8,
games, visual assets, or release readiness.
