#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = [
    "README.md",
    "DESIGN_SSOT.md",
    "EVIDENCE_INDEX.md",
    "source/ORIGINAL_SPEC.md",
    "docs/00-gap-analysis.md",
    "docs/11-eval-release-gates.md",
    "docs/evidence/package-validation-report.md",
    "assets/README_IMAGEGEN_SSOT.md",
    "assets/imagegen-prompt-registry.json",
    "assets/asset-manifest.schema.json",
    "skills/imagegen-asset-production/SKILL.md",
    "scripts/validate_asset_ssot.py",
    "scripts/validate_json_schemas.py",
    "scripts/validate_sha256s.py",
    "manifest/package-manifest.json",
    "SHA256SUMS",
]


def run(script: str) -> None:
    result = subprocess.run(
        [sys.executable, str(ROOT / script)],
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )
    print(result.stdout, end="")
    if result.returncode != 0:
        print(result.stderr, file=sys.stderr)
        raise SystemExit(result.returncode)


def main() -> int:
    for rel in REQUIRED:
        path = ROOT / rel
        if not path.exists() or path.stat().st_size == 0:
            raise AssertionError(f"missing or empty: {rel}")
        print(f"OK required: {rel}")

    for path in sorted(ROOT.rglob("*.json")):
        json.loads(path.read_text(encoding="utf-8"))
        print(f"OK json: {path.relative_to(ROOT)}")

    manifest = json.loads(
        (ROOT / "manifest/package-manifest.json").read_text(encoding="utf-8")
    )
    source_hash = hashlib.sha256(
        (ROOT / "source/ORIGINAL_SPEC.md").read_bytes()
    ).hexdigest()
    if manifest["source"]["sha256"] != source_hash:
        raise AssertionError("source SHA256 differs from package manifest")
    if manifest["assetSsot"]["method"] != "IMAGEGEN_SKILL":
        raise AssertionError("package manifest must lock IMAGEGEN_SKILL")
    if manifest["status"]["releaseApproval"] != "MANUAL_REVIEW_REQUIRED":
        raise AssertionError("unverified package must remain manual-review-required")
    actual_file_count = sum(1 for path in ROOT.rglob("*") if path.is_file())
    if manifest["fileCountIncludingChecksumManifest"] != actual_file_count:
        raise AssertionError(
            f"package file count mismatch: manifest={manifest['fileCountIncludingChecksumManifest']} "
            f"actual={actual_file_count}"
        )
    print(f"OK source SHA256: {source_hash}")
    print(f"OK package file count: {actual_file_count}")

    registry = json.loads(
        (ROOT / "assets/imagegen-prompt-registry.json").read_text(encoding="utf-8")
    )
    registry_text = json.dumps(registry, sort_keys=True).upper()
    for forbidden in [
        '"GENERATIONMETHOD": "WEB"',
        '"GENERATIONMETHOD": "STOCK"',
        '"GENERATIONMETHOD": "MANUAL_MASTER"',
    ]:
        if forbidden in registry_text:
            raise AssertionError(f"forbidden registry method: {forbidden}")
    if any(asset.get("status") != "PLANNED" for asset in registry["assets"]):
        raise AssertionError("design package must not claim generated/approved image assets")
    if manifest["assetSsot"]["plannedAssetRequests"] != len(registry["assets"]):
        raise AssertionError("planned asset count differs from package manifest")
    if manifest["assetSsot"]["promptTemplates"] != len(registry["templates"]):
        raise AssertionError("prompt template count differs from package manifest")

    generated = ROOT / "assets/generated"
    unexpected_masters = [
        p for p in generated.rglob("*")
        if p.is_file() and p.name != "README.md"
    ]
    if unexpected_masters:
        raise AssertionError(
            f"untracked generated assets present: {[str(p.relative_to(ROOT)) for p in unexpected_masters]}"
        )

    run("scripts/validate_json_schemas.py")
    run("scripts/validate_asset_ssot.py")
    run("scripts/validate_sha256s.py")

    print("PACKAGE VALIDATION PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
