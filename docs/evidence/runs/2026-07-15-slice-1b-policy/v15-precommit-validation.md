# Capability v15 pre-commit validation

Date: 2026-07-16 (Asia/Seoul)

## Verdict boundary

This report tracks the runtime-neutral `capability-v15` gate repair after review 14
permanently rejected the v14 implementation descendant chain with P0=0, P1=1, P2=0.
It is not an acceptance record, implementation authorization, evidence that Slice 1B
exists in an APK, or a physical/human release result. The candidate manifest retains
`status=CANDIDATE_REREVIEW_REQUIRED` and `implementation_authorized=false`.

The v15 delta changes no capability threshold, journal/store payload, native-close
rule, camera behavior, dependency, or release claim. It makes the protected tests
stage-aware before candidate `C`, freezes the four v14 authorization/review files as
historical evidence, and requires full discovery to pass with identical protected
blobs at candidate `C` and authorization `A`.

No physical Android device is connected. The available APK remains the non-playable
Slice 0A technical baseline; camera framing, two-person recognition, performance,
thermal behavior, perceptual quality, and human safety comprehension remain unobserved.

## Reproduced v14 stop condition

Formal validation of candidate `e9216b81b527c5825981fb429076ac7ba245644e`
against implementation head `7a8b69bbc34ef6d10c3eb17b1aa8f90e56917d5b`
returned `CAPABILITY_POLICY_AUTHORIZATION=NOT_AUTHORIZED`. Each implementation
descendant changed candidate-bound `scripts/tests/test_validate_capability_policy.py`.
At exact authorization commit `7ef91b566257371e98529f4db13d2ecce10c2371`,
focused discovery ran 42 tests and failed the two candidate-stage worktree assertions.
Review 14 records the command, object IDs, failure class, and required correction.

## Current authority SHA-256

| Authority | SHA-256 |
| --- | --- |
| `docs/adr/ADR-011-measured-capability-probe-policy.md` | `ed5ef254c12260fe58af466937faf14690309e2acda4877a07d3da267ed4f4ce` |
| `docs/contracts/capability-store-v4.md` | `3ad1e2411fc2ddbd39230ab1b33a1810e4c4d1c4fd218ec53fc5e705fcac0cef` |
| `docs/contracts/native-close-fence-proof-v1.md` | `31e142a0582861976e106d1b0813a33cfcb7913ef4106ead5e7e8a405b3bdfcf` |
| `docs/contracts/recovery-journal-v5.md` | `ea6e10e8304cfb6985785c3c20985a3dcad47b919277b9e62f0cd871d40130e5` |
| `docs/execution/slice-1b-contract.md` | `9ab435bc7b07a8854143eedffe6bc969451ec3aa0484e3a7c9d6331506f71c0d` |
| `docs/contracts/capability-policy-authority-v15.json` | `aed6e9276ce9593b19374928c475abe995f7bc36c3ab1ceb136488c674f89e68` |

The canonical manifest binds those five exact authorities and contiguous rejected
reviews 01-14 while retaining `implementation_authorized=false`.

## Frozen v14 gate history

| Historical artifact | SHA-256 |
| --- | --- |
| `docs/contracts/capability-policy-authority-v14.json` | `a8afa6050138b16789e246f86c989a53ad12b4eb8a0fc9036c688c35f0417d51` |
| `docs/evidence/runs/2026-07-15-slice-1b-policy/v14-precommit-validation.md` | `b99eb19acb807a0be9d1d125f631d07d316a55afb847032f67351a1b9c02b016` |
| `docs/contracts/capability-policy-authorization-v14.json` | `08aa2a854c011a1dd5137fe43e1a3a60ef21b6631357de2928d718571b910250` |
| `docs/reviews/slice-1b-policy-v14-exact-review-01.json` | `001895b9012ebd9b772ec564390212413b4406196a9f9e5785b51b07238591cb` |
| `docs/reviews/slice-1b-policy-v14-exact-review-02.json` | `295f5226c6592deba7f766d474f1bb042df1bf622c401c289e0df7236c0aa2e7` |
| `docs/reviews/slice-1b-policy-v14-exact-review-03.json` | `41109d24afcfc18397b10f5f075d0008dd059f8110f62e72ea747adefc8cdc09` |
| `docs/reviews/slice-1b-policy-review-14.md` | `75e4910a1b37700e78e5013c6e61d0184f7394c036ea30b9c2211777f3b92e8d` |

## Pre-C validation ledger

| Check | Actual result |
| --- | --- |
| v14 failure reproduction and review 14 | PASS; direct formal descendant failure and 42-test/two-failure authorization-stage contradiction recorded |
| v15 authority hashes and canonical manifest | PASS for the current bytes shown above; recheck after every authority edit |
| historical v14 authorization/review exact freezing | PASS; hashes above plus mutation, omission, mode, and portable-alias negative fixtures |
| dual-stage protected test behavior | PASS before `C`; candidate-absent live branch and candidate/authorization fixture branches passed with one test blob; actual `A` remains a post-C gate |
| focused/full Python regression | PASS; main policy 46/46, authorization 46/46, Windows full discovery 299 tests with 15 skips, and native-Linux ext4 full discovery 299 tests with 29 skips; all had 0 failure/error |
| capability/supply/APK/asset policy | PASS for technical validators; capability diagnostic and supply chain passed, APK policy passed, native ownership mapped 16/16, runtime license metadata passed 159/159; asset status is truthfully `NO_DEPLOYED_VISUAL_ASSETS` and G6 remains blocked |
| forced offline Gradle regression | PASS; exact contract command executed 228/228 actionable tasks, 52/52 JVM tests, debug + androidTest APKs, and lint with 0 errors/1 pre-existing missing-icon warning |
| physical Android evidence | NOT AVAILABLE; zero connected physical devices and no claim |

Every pre-C row other than the explicitly unavailable physical row must have direct
PASS evidence before candidate `C` is created. Physical evidence is not a policy-gate
substitute and remains required later for Slice 1B acceptance.

## Pre-C artifact observations

| Artifact or command | Observed result |
| --- | --- |
| `python -B scripts\validate_capability_policy.py` | `CAPABILITY_POLICY_WORKTREE_DIAGNOSTIC=PASS`; v15, 5 authorities, 14 reviews, formalGate=false |
| focused policy modules | 46/46 + 46/46 passed; 0 failure/error/skip |
| Windows full Python discovery | 299 tests; 0 failure/error; 15 skipped; 112.833s |
| native-Linux ext4 full Python discovery | clean full-history clone with the final pre-C diff; 299 tests; 0 failure/error; 29 skipped; 6.863s |
| Gradle contract command | `BUILD SUCCESSFUL in 1m 12s`; 228 actionable tasks executed |
| Gradle JVM XML | 8 XML files; 52 tests; 0 failure/error/skip |
| `app-debug.apk` | 68,384,279 bytes; SHA-256 `4e89a7ed05e56a0b93bb84fcf0e6f27463aef3b2cbcc1bfcd8ae4bce7306336d` |
| `app-debug-androidTest.apk` | 1,044,896 bytes; SHA-256 `ede1f2095714bd9cbf4efaf380001ea522fc8f65d80328cae3f4cbdb5f3762d4` |
| lint report | 0 errors/1 `MissingApplicationIcon` warning; SHA-256 `afa1480e648c0609a3dbbf66dd7908ce37d31129aa633ecfee647b1c64b64dcc` |
| APK/native/license validators | APK policy PASS; 16/16 native entries mapped with zero unmatched/ambiguous/duplicate; 159 coordinates with license metadata, not legal approval |
| supply/assets | supply-chain static policy PASS; no deployed visual assets, so G6 is not approved |

An independent evidence-and-governance review of candidate
`8c00fb75ba20a732381186f9bf9ccc4e93e5934d` found that the Android test APK hash
had four mistyped hexadecimal characters. The size, preserved artifact timestamp,
fresh SHA-256 calculation, and frozen v14 ledger all identify the corrected value
above. That review recorded P2=1 and withheld approval. The two earlier zero-finding
review sessions for that candidate are not reusable; the corrected candidate requires
three entirely fresh exact-byte reviews.

## Discarded pre-review candidate attempt

Commit `b5de0614794694cba52aed25d41bf5fdc054a342` passed both local exact-object
candidate gates, but it was withdrawn before any exact review record was created.
Remote push run `29463971536` exposed 43 authorization-fixture errors: the default
depth-one checkout did not contain annotated rollback tag object
`776d3717bc0bf5dc6e93f593256530214a334101`, which the fail-closed fixture requires.
The Android strict build step passed independently. A WSL run on the Windows-mounted
DrvFS worktree also failed the two live candidate diagnostics because that filesystem
cannot acquire the required POSIX exclusive snapshot lease; the same 299-test suite
passed in a full-history native-ext4 clone. These are environment-discriminating
results, not candidate approvals.

The corrected candidate tree sets `fetch-depth: 0` for the SSOT checkout so the fixed
tag object, its target commit/tree, and future `C..A..H` ancestry exist locally. The
supply-chain validator and its 53 tests pass with that workflow delta. The final
candidate must receive a new remote run and entirely fresh reviewer sessions; no
session or verdict from the withdrawn attempt may be reused.

## Post-C immutable gates

| Check | Candidate-ledger state |
| --- | --- |
| candidate commit/tree and three independent exact-commit reviews | PENDING |
| authorization-only follow-up commit and full discovery with identical protected blobs | PENDING |
| formal authorization against the actual rebuilt implementation descendant | PENDING |

These rows are structurally PENDING in the immutable ledger stored by `C`; later PASS
evidence belongs in the exact-review records, authorization record, and implementation
run ledger. PENDING is never itself a pass.

## Next gate

Complete the remaining pre-C rows from observed commands, update this ledger once, and
freeze candidate `C`. Three fresh whole-candidate reviews must return zero findings
before the authorization-only four-file commit is created. Full Python discovery and
the formal validator must then pass at exact `A` without changing any candidate-bound
blob. Only that result permits reapplying the kernel on the new linear history. No
policy review proves physical-device behavior, G2, G6, G8, games, visual assets, human
acceptance, or release readiness.
