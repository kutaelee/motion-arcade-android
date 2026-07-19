# Capability v14 pre-commit validation

Date: 2026-07-16 (Asia/Seoul)

## Verdict boundary

This report tracks the uncommitted `capability-v14` remediation after review 13
permanently rejected v13 with edit-level deduplicated P0=0, P1=5, P2=8. It is not an acceptance record,
implementation authorization, evidence that Slice 1B exists in the
APK, or a physical/human release result. The current manifest permanently retains
`status=CANDIDATE_REREVIEW_REQUIRED` and `implementation_authorized=false`.

The final v13 rejection ledger is immutable at SHA-256
`71db385c245f31ae00809160981cfd25fdf4f0b65f5bd07bf4b92ad79a2a76c3`.
Review 13 is immutable at SHA-256
`4de103affeb31156ce235efaca96d1076ef171dc9c73c4a6368d1d4d45138f30`.
The canonical rejected v13 manifest is immutable at SHA-256
`d1522f3882d5d123c6f8b4948dbfc44b605448ff86c227f70a546165f9b095ff`.

No physical Android device is connected. Camera framing, two-person recognition,
perceptual quality, physical performance/thermal behavior, and human safety
comprehension remain unobserved. The available APK is still Slice 0A only, no visual
asset is deployed, and G6 remains blocked. Human game QA has no actionable build yet.

## Current authority SHA-256

| Authority | SHA-256 |
| --- | --- |
| `docs/adr/ADR-011-measured-capability-probe-policy.md` | `9e35bf8ce03f802dbde1cb08346c7d91bce697d83feb51a2f7064d134bc3b53c` |
| `docs/contracts/capability-store-v4.md` | `505c178d414d3a0dd9901fbf3d00bd30d6163251a7a3350284e09a659b4bfb59` |
| `docs/contracts/native-close-fence-proof-v1.md` | `2f3a2898c12be5fdd7e674502a33babb45abdd3e46aaf9a3b529a1445c7cb05a` |
| `docs/contracts/recovery-journal-v5.md` | `d0a9d50513cc4b4f15aca9be2196380e4bd534ae6dc7b341812e61ad0b056f37` |
| `docs/execution/slice-1b-contract.md` | `d7684b6ead08f221f0920e533c3e3da07dbcec20e9560b39ba532ac6d88b71d3` |
| `docs/contracts/capability-policy-authority-v14.json` | `a8afa6050138b16789e246f86c989a53ad12b4eb8a0fc9036c688c35f0417d51` |

The canonical v14 manifest binds those five exact authorities and contiguous rejected
reviews 01-13 while remaining false. Any authority, manifest, table, historical ledger,
or review-byte change invalidates this candidate.

## Required v14 remediation

- independently freeze exact raw hashes for historical manifests v10-v13, final
  rejection ledgers v10-v13, and rejected reviews 01-13;
- run the 31-file handle-anchored worktree capture only as a pre-C diagnostic, then use
  immutable Git commit/tree objects as the formal same-byte candidate gate;
- reject NFKC-plus-casefold aliases in every component of review, governed, and
  candidate-stage reserved authorization paths, as well as exact forbidden paths;
- verify this visible authority table contains exactly one current row for each of the
  five authorities and the manifest, with no stale or hidden substitute;
- retain bounded Windows and Linux immutable capture, typed malformed JSON/snapshot
  failure, visible-token and logical Markdown defenses;
- freeze the normative three-review and authorization-only transition without adding a
  sixth authority or changing the false candidate manifest; formal authorization takes
  explicit immutable `C` and `H` object IDs, while checkout/tag state is operational.

## Pre-C validation ledger

| Check | Actual result |
| --- | --- |
| v13 rejection ledger/review/hash-cycle audit | PASS; immutable hashes above independently rereviewed |
| v14 authority hashes and canonical manifest | PASS for the current bytes shown above; must be rechecked after every authority edit |
| historical manifest/ledger/review exact freezing | PASS; the diagnostic validator binds v10-v14 manifests, final v10-v13 ledgers, and reviews 01-13; full regression remained green |
| diagnostic worktree snapshot shape and formal Git-object snapshot tests | PASS; 61 snapshot cases ran on Windows with 46 pass/15 platform skips, and 61 ran on WSL with 32 pass/29 platform skips; combined platform coverage had zero failure |
| visible current-evidence linkage and stale/duplicate/hidden-row attacks | PASS; 18 Markdown parser and 42 current-validator cases passed in the full regression |
| authorization transition validator and negative fixtures | PASS; Windows 43/43 and WSL 43/43; exact-byte independent audit APPROVE with P0=0/P1=0/P2=0 |
| focused/full Python regressions | PASS; full discovery ran 292 tests with 277 pass, 0 failure/error, and 15 explicit platform skips; targeted POSIX snapshot and both authorization suites also passed |
| capability/supply/APK/asset policy | PASS for the validators: diagnostic capability, static supply-chain, and APK policy passed; asset validator truthfully returned `NO_DEPLOYED_VISUAL_ASSETS`, so G6 remains blocked |
| forced offline Gradle/APKs and persisted logs | PASS; exact contract commands ran with strict verification and rerun-tasks: focused 76/76 and full 224/224 actionable tasks, both build-successful |
| native/license/JUnit/APK evidence | PASS for technical checks: 16/16 native entries mapped with zero ambiguity, 159/159 locked coordinates had POM license metadata, and 52/52 JUnit cases passed; license metadata is not legal approval |

These are the only rows that must have direct PASS evidence before creating `C`.

## Pre-C evidence artifacts

| Artifact | Observed result | SHA-256 |
| --- | --- | --- |
| `v14-python-full.log` | 292 run; 0 failure/error; 15 skipped | `4b528ba01e50629f7c97108fffae4559cd6ef157fbc9203f6e16e845d1c5275d` |
| `v14-posix-snapshot.log` | 61 run; 0 failure/error; 29 skipped | `153b28e5651e8e12e57ff274c3b6a51c8cbedfea9579dd02730b588fbc169679` |
| `v14-authorization-windows.log` | 43/43 passed | `506b87e86976eda3afbe2dfeba98492e75c23869dce56781a78705b6719d3c77` |
| `v14-authorization-posix.log` | 43/43 passed | `d9152cae392c1f14e052437bc49ecad2427c4a25e13af0ff0f55f70589592b6c` |
| `v14-authorization-validator-audit.md` | independent APPROVE; P0=0/P1=0/P2=0 | `16305cf3e2978bf756b9230d6672de15b96c7182a04215bca8a0c371222be419` |
| `v14-capability-diagnostic.log` | worktree diagnostic pass; formal gate false | `bba5c6666485e1e19ea831e98185c5fe0c10821555a0f6a34133d7c3c35170fd` |
| `v14-supply-chain.log` | static supply-chain policy pass | `24ebd10604ff2886d8c3fc3907ec4afe9256bbd2389e9344259173947d253d4b` |
| `v14-asset-policy.log` | no deployed assets; G6 not approved | `0489a7467db721f47bb19e60521cac04376b84da0996c201993caa2bb294ca22` |
| `v14-apk-policy.log` | APK policy pass | `bc97ec3230e03ddb558adc00d3f256baff20c6e9afd9f348cb74f03225845e8c` |
| `v14-adb-devices.log` | only the header; zero connected devices | `36f15c2fe32964a6fbe902e914a47739d4e6304b0d3e812bf08096ff0419b7ae` |
| `v14-gradle-focused.log` | build successful; 76/76 actionable tasks | `38137b080cf30ed3eaf246dc652ea09668dc73e3350e141b56917b03cbca354c` |
| `v14-gradle-full.log` | build successful; 224/224 actionable tasks | `bb4250ce4ee2edd5944b06d9e2ee1790b768702f9eea9055cdd93a23275323fb` |
| `v14-native-runtime-inventory.json` | 16/16 mapped; verdict pass | `f7ad295b05dca79dc0e6ab7323167d46fc7d4bae87487d81bff54f25023c633f` |
| `v14-runtime-license-inventory.json` | 159/159 metadata rows passed | `a248299bcbf597d07f3cae07ea3e9b60e81b662033b162302d110b6443e1bf1b` |
| `v14-junit/` | 8 XML files; 52 tests; 0 failure/error/skip | per-file XML retained in the directory |
| `app-debug.apk` | Slice 0A only; 68,384,279 bytes | `4e89a7ed05e56a0b93bb84fcf0e6f27463aef3b2cbcc1bfcd8ae4bce7306336d` |
| `app-debug-androidTest.apk` | 1,044,896 bytes | `ede1f2095714bd9cbf4efaf380001ea522fc8f65d80328cae3f4cbdb5f3762d4` |

The Android APKs are build outputs and are not candidate source-tree members. Their
hashes establish only the tested Slice 0A artifacts. `adb devices -l` reported no
connected device, so no physical-device, camera, thermal, two-person, or human QA result
is claimed.

## Post-C immutable gates

| Check | Candidate-ledger state |
| --- | --- |
| candidate commit/tree and three independent exact-commit reviews | PENDING |
| authorization-only follow-up commit/record validation | PENDING |

These two rows are structurally PENDING in the immutable ledger stored by `C`: neither
`C` nor its reviews/authorization follow-up can exist before that ledger is committed.
Their later PASS evidence lives only in the three exact-review JSON records and the
authorization record/validator output. PENDING is never itself a pass.

## Next gate

Populate the Pre-C validation ledger only from observed commands and immutable
artifacts. After every row in that section has direct passing evidence, commit exactly
those bytes as candidate `C` without editing this ledger. Validate `C` from immutable
Git objects, then obtain three independent whole-candidate reviews of its exact
commit/tree. A rejection
permanently closes v14 and requires a new revision. Three clean reviews still do not
alter the false manifest: only the separate four-file, authorization-only follow-up and
its mechanical validator may permit Slice 1B implementation. No policy review proves
physical-device behavior, G2/G6/G8, games, visual assets, human acceptance, or release
readiness.
