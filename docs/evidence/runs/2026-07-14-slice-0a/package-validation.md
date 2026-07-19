# SSOT package validation evidence

- Observed: 2026-07-14T21:20:42+09:00
- Host invocation: Windows 11 PowerShell to WSL Ubuntu
- Validator runtime: Python 3.12.3, jsonschema 4.10.3
- Exit code: 0

Command:

```powershell
wsl.exe bash -lc "cd /mnt/c/codex/motion_game/android-motion-games-design-augmented-v1.0/android-motion-games-design-augmented-v1.0 && PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/validate_package.py"
```

Observed terminal output:

```text
OK required: README.md
OK required: DESIGN_SSOT.md
OK required: EVIDENCE_INDEX.md
OK required: source/ORIGINAL_SPEC.md
OK required: docs/00-gap-analysis.md
OK required: docs/11-eval-release-gates.md
OK required: docs/evidence/package-validation-report.md
OK required: assets/README_IMAGEGEN_SSOT.md
OK required: assets/imagegen-prompt-registry.json
OK required: assets/asset-manifest.schema.json
OK required: skills/imagegen-asset-production/SKILL.md
OK required: scripts/validate_asset_ssot.py
OK required: scripts/validate_json_schemas.py
OK required: scripts/validate_sha256s.py
OK required: manifest/package-manifest.json
OK required: SHA256SUMS
OK json: assets/asset-manifest.example.json
OK json: assets/asset-manifest.schema.json
OK json: assets/imagegen-prompt-registry.json
OK json: contracts/calibration-profile.schema.json
OK json: contracts/diagnostic-bundle.schema.json
OK json: contracts/motion-event.schema.json
OK json: contracts/pose-frame.schema.json
OK json: contracts/session-snapshot.schema.json
OK json: manifest/package-manifest.json
OK source SHA256: 78a95f1f52c51d39da7630acab8ce494137c98a9704e69d85383569d833dec5a
OK package file count: 66
OK schema: contracts/motion-event.schema.json
OK schema: contracts/pose-frame.schema.json
OK schema: contracts/calibration-profile.schema.json
OK schema: contracts/session-snapshot.schema.json
OK schema: contracts/diagnostic-bundle.schema.json
OK schema: assets/asset-manifest.schema.json
OK example: assets/asset-manifest.example.json
OK ImageGen SSOT: 44 planned assets, 6 templates, 4 style profiles
OK SHA256SUMS: 65 files verified
PACKAGE VALIDATION PASSED
```

## Interpretation and limitation

This directly validates the checked-in extracted package under Linux path semantics.
Separate direct ZIP inspection recorded ZIP SHA-256
`c998b9eb5f102f231bb626f50864c881e7d0e83e9adee5868e363d4ff8094330`,
CRC success, no encrypted entries, and 65/65 checksum matches.

The provided checksum script is not portable to Windows because it compares native
backslash paths with slash paths in `SHA256SUMS`. The Linux pass above distinguishes
that known validator defect from package corruption. CI therefore runs this validator
on Ubuntu with a hash-locked validation environment.
