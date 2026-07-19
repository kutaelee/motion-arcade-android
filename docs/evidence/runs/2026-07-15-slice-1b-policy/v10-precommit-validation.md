# Capability v10 pre-commit validation

Date: 2026-07-15 (Asia/Seoul)

## Verdict boundary

This report records validation of the uncommitted `capability-v10` candidate on
branch `agent/slice-1b-capability-probe`, based on rejected v9 commit
`9fbd391553b9260128c7dee52e98e40f33b0ad64`. It is not an acceptance record,
an implementation authorization, or evidence that Slice 1B exists in the APK.
The canonical authority manifest remains
`status=CANDIDATE_REREVIEW_REQUIRED` and `implementation_authorized=false`.

No physical Android device was connected. Camera framing, two-person recognition,
perceptual quality, physical performance/thermal behavior, and human safety
comprehension were not observed. No visual asset is deployed and G6 remains blocked.

## Frozen candidate authorities

| Authority | SHA-256 |
| --- | --- |
| `docs/adr/ADR-011-measured-capability-probe-policy.md` | `36c22db812ff6124b745563dd71e7367b53c6e913dcbb7b53d6e6c4cbbc650b5` |
| `docs/contracts/capability-store-v4.md` | `4fe2ace36de6d76a58bc7f7e7f7911606d3a0d4ae74c873e5637aa377773577e` |
| `docs/contracts/native-close-fence-proof-v1.md` | `104bbdd92e251047bec31ba40320cdae7d9af1b7fb2db3937973c6b9548b37dc` |
| `docs/contracts/recovery-journal-v5.md` | `875b8cbe52cd7d63da6ab1dedd4612871513553da81489add4798cfa33471175` |
| `docs/execution/slice-1b-contract.md` | `62f8aa609bfd85321be2f6e6b95820563b9ed7f9f94fa2cb23da1bf45450a557` |
| `docs/contracts/capability-policy-authority-v10.json` | `5009b65b1f9f6d541fffd7f3ac94d828de53ceb6d6e78de562734c40a1b6a3bd` |

The authority reference validator directly matched all five file hashes and all
nine rejected review records.

## Deterministic validation observed

| Check | Actual result |
| --- | --- |
| `python -B -m unittest discover -s scripts/tests -v` | PASS, 138/138 |
| `python -B -m unittest scripts.tests.test_validate_capability_policy -v` | PASS, 15/15 |
| `python -B -m unittest scripts.tests.test_validate_supply_chain -v` | PASS, 48/48 after adversarial remediation |
| `python -B -m unittest scripts.tests.test_validate_apk_policy -v` | PASS, 13/13 |
| `python -B scripts/validate_capability_policy.py --root .` | PASS; candidate v10, five authorities, nine rejected reviews, implementation false |
| `python -B scripts/validate_supply_chain.py --root .` | PASS |
| `python -B scripts/validate_project_assets.py --root .` | `NO_DEPLOYED_VISUAL_ASSETS`; G6 not approved |
| `git diff --check` | PASS |

The supply-chain regression set includes quoted/escaped/tagged Action keys, flow
collections, merge keys, explicit keys, anchors/aliases, canonical scalar/indent/key
grammar, local-reference rejection, job/service container rejection, exact Gradle
launcher/control/script inventory and hashes, map-form `apply`, variable
`includeBuild`, competing Groovy settings, Groovy method pointers, `findProject`,
parenthesized type-safe accessors, exact sha256-only dependency-verification XML,
decoded TOML versions, source/APK permission forms, and normal expression,
apostrophe, block, brace/star, and bare-dash YAML cases. The first adversarial pass
rejected the previous validator with P1=4/P2=2 and later passes exposed additional
launcher, verification, container, permission, and syntax gaps. Every recorded
fixture now fails closed or passes without the prior false positive as applicable.

## Android build artifact checks

The Android sources and the six approved Gradle scripts did not change during the
v10 policy/document and Python-validator remediation. The last full local build
was run with strict offline dependency verification:

```text
gradlew --no-daemon --no-configuration-cache -Pkotlin.incremental=false
  --offline --dependency-verification strict
  test lint :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks
BUILD SUCCESSFUL in 1m 10s; exit=0, elapsed=72.4 s; 224/224 actionable tasks executed
```

A focused `:vision:testDebugUnitTest :vision:lintDebug --rerun-tasks` run also
reported `BUILD SUCCESSFUL` in 49 seconds with 76 executed tasks. The resulting
JUnit XML inventory was re-read after the build: 8 files, 52 tests, 0 failures,
0 errors, and 0 skipped.

Current APK evidence:

| Artifact/check | Actual result |
| --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 68,384,279 bytes; SHA-256 `4e89a7ed05e56a0b93bb84fcf0e6f27463aef3b2cbcc1bfcd8ae4bce7306336d` |
| APK policy | PASS; package `com.motionarcade.app.debug`; exact CAMERA plus package-scoped receiver permission; pinned model hash |
| Native runtime ownership | PASS; 16 APK native entries, 3 exact verified AAR owners |
| Runtime technical license audit | PASS; 159 `releaseRuntimeClasspath` coordinates |
| License inventory comparison | generated and checked-in SHA-256 both `a248299bcbf597d07f3cae07ea3e9b60e81b662033b162302d110b6443e1bf1b` |

These checks prove the unchanged technical baseline under the tested local
conditions. They do not prove the unimplemented capability policy, legal
redistribution approval, physical-device behavior, or release readiness.

## Pre-commit independent checks

- Native fence/ZIP/JNI pre-commit audit: P0=0, P1=0, P2=0 in its assigned scope;
  not an approval and exact-commit review remains required.
- Recovery/store final pre-commit audit: P0=0, P1=0, P2=0 in its assigned scope;
  all authority hashes stayed stable. API 26/27 linkage and Android DataStore
  behavior remain implementation/device unknowns.
- Supply-chain final pre-commit rereview: P0=0, P1=0, P2=0 at validator SHA-256
  `8e28bfa61bddf5b60d06fc285163cfdb0b3c039c07dba0cd44c874310faa0b84`
  and test SHA-256
  `07a0d6160acd9c49f423495fc6016dbc21cdb4a048234a60998e2413ae34d76d`;
  48/48 and the repository gate passed. This is not an exact-commit approval.
- Authority/APK final pre-commit rereview: P0=0, P1=0, P2=0; 28/28 focused
  tests, authority validator, actual APK policy, and diff check passed with stable
  authority bytes. This is not an exact-commit approval.

## Next gate

The final pre-commit rereviews returned with no actionable P0/P1/P2 findings. Commit
the candidate without changing any authority byte. Then three fresh reviewers must
inspect that exact commit/tree independently. Any actionable finding rejects v10
and requires a new revision. Even a clean exact-commit review does not silently
authorize implementation; the authorization transition must bind the reviewed
commit/tree and preserve the five authority hashes.
