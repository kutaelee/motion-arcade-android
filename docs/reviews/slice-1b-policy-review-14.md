# Slice 1B capability policy review 14

Date: 2026-07-16 (Asia/Seoul)

## Verdict

**REJECT / REWORK.** The `capability-v14` policy bytes and their authorization record
remain immutable evidence, but the implementation descendant chain is not authorized.

- P0: 0
- P1: 1
- P2: 0

## Exact target and reproduction

- Candidate `C`: `e9216b81b527c5825981fb429076ac7ba245644e`
- Authorization `A`: `7ef91b566257371e98529f4db13d2ecce10c2371`
- Reviewed implementation `H`: `7a8b69bbc34ef6d10c3eb17b1aa8f90e56917d5b`
- First failing descendant: `d1d276f92ac3d3f1126d58901dc7eedd9253126c`
- Protected path: `scripts/tests/test_validate_capability_policy.py`
- Candidate blob: `86a394c`
- Implementation blob: `638a73d`

```text
python -B scripts\validate_capability_policy_authorization.py --root . --stage authorization --candidate-commit e9216b81b527c5825981fb429076ac7ba245644e --head-commit 7a8b69bbc34ef6d10c3eb17b1aa8f90e56917d5b

CAPABILITY_POLICY_AUTHORIZATION=NOT_AUTHORIZED
ERROR candidate-bound bytes or mode changed after C in d1d276f...:
scripts/tests/test_validate_capability_policy.py
```

The same error is emitted for each later implementation descendant. Restoring the
candidate blob only at a later commit cannot repair the chain because the formal
validator checks every commit from `A` through `H`.

## P1 finding: candidate and authorization stages require incompatible frozen tests

At exact `A`, the candidate-bound v14 test suite runs 42 cases and fails two:
`test_repository_current_policy_references_are_consistent` and
`test_cli_runs_from_repository_root_with_default_dot`. Both expect the mutable
repository to remain at candidate stage. The authorization commit necessarily adds
the four reserved v14 authorization/review paths, which the candidate diagnostic
correctly rejects. Updating those expectations after `C` makes full discovery green
but violates the exact protected-blob preservation rule.

This is a gate-design contradiction, not an Android runtime failure. The four kernel
commits may be retained as unapproved prototype evidence, but they cannot support an
implementation-authorized or Slice 1B progress claim on the v14 descendant chain.

## Required correction

Create one new policy revision with no runtime-policy expansion. Before its candidate
commit:

1. migrate the two tests so they validate the exact candidate commit after the
   authorization paths exist;
2. freeze the v14 authorization record and three exact reviews as historical inputs;
3. pass full test discovery at the candidate and authorization commits with identical
   protected blobs;
4. obtain three fresh exact-candidate reviews and a new authorization-only commit;
5. reapply the kernel commits without any protected-path mutation.

Until those conditions pass, implementation authorization is blocked. This review does
not reject the runtime thresholds or persistence/native-close contracts and does not
claim physical-device, G2, G6, G8, human-QA, or release evidence.
