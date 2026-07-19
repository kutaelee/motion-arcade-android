# Slice 1A independent review 1

- Reviewer task: `/root/slice1a_independent_review`
- Review target: `6c46b1b9be7e417bb08f52fe207b9f4f0daedfb9`
- Mode: read-only
- Files changed by reviewer: none
- Verdict: `REWORK`; do not tag or merge

## Work performed

The reviewer inspected the accepted Slice 0A-to-target diff, relevant extracted
SSOT contracts, ADR-005, ADR-010, the Slice 1A execution contract, all coordinate
and frame source/tests, CI/build metadata, evidence status, PR #1, and GitHub
Actions state.

## Actionable finding

`[P2] Invalid metadata can be masked as PAUSE_INPUT.`

`CameraCoordinateMapper.create` returned immediately for an `UNRESOLVED` preview
mapping before validating non-finite or singular metadata. A zero-width analysis
crop combined with an unresolved preview basis therefore returned
`PauseInput(UNRESOLVED_PREVIEW_MAPPING)` instead of
`DropFrame(SINGULAR_TRANSFORM)`. This contradicted ADR-010 and the Slice 1A
acceptance distinction: invalid per-frame metadata drops; only an otherwise valid
unresolved or inconsistent ViewPort relationship pauses input.

## Reproduction and correction evidence

The new test
`invalidFrameMetadataOutranksUnresolvedOrMissingViewportBasis` failed 1/1 before
the correction with the exact PauseInput result above. Commit
`500ca21df791a034381c22fd1f6d8bb553d69dae` validates required drop metadata before
any ViewPort pause branch and covers seven combined invalid/unresolved-or-missing
fixtures. After the correction, the targeted test passed, the focused forced run
passed 76/76 tasks, and the full forced regression passed 170/170 tasks.

## Review limitation and next gate

The reviewer stopped when five remote-CI evidence files appeared concurrently in
the shared worktree, as required by the review contract. Therefore this report is
not a statement that no further P0/P1/P2 issue exists. A second independent review
must run from a clean, committed worktree after post-fix CI evidence is present.

Physical overlay, lens/rotation switching, camera binding, pose inference, and
one/two-person behavior remain outside Slice 1A evidence.
