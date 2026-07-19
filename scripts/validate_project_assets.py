#!/usr/bin/env python3
"""Validate project visual-asset lineage against the immutable ImageGen SSOT.

This validator proves mechanical lineage and file integrity. It does not replace the
human visual, originality, safety, or redistribution review required by G6.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


SSOT_RELATIVE = Path(
    "android-motion-games-design-augmented-v1.0"
    "/android-motion-games-design-augmented-v1.0"
)
MANIFEST_RELATIVE = Path("assets-src/imagegen/asset-manifest.json")
DEPLOY_RELATIVE = Path("app/src/main/res/drawable-nodpi")
ANDROID_MODULES = ("app", "vision", "game-core", "games")
ALLOWED_MASTER_SUFFIXES = {".png", ".webp"}
VISUAL_SUFFIXES = {
    ".png",
    ".webp",
    ".jpg",
    ".jpeg",
    ".gif",
    ".bmp",
    ".avif",
    ".svg",
    ".psd",
    ".ai",
}
HASH_RE = re.compile(r"^[a-f0-9]{64}$")
SAFE_RECIPE_RE = re.compile(
    r"^(trim-alpha|remove-chroma-key|canvas-normalize|resize|resize-contain|"
    r"resize-contain-and-pad|convert-colorspace|"
    r"lossless-format-convert|atlas-pack|metadata)\([^\r\n()]*\)$"
)


@dataclass(frozen=True)
class ValidationResult:
    status: str
    deployed_count: int
    manifest_count: int
    errors: tuple[str, ...]

    @property
    def ok(self) -> bool:
        return not self.errors


def _load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _resolve_inside(root: Path, value: str, label: str, errors: list[str]) -> Path | None:
    candidate = (root / value).resolve()
    try:
        candidate.relative_to(root.resolve())
    except ValueError:
        errors.append(f"{label} escapes repository root: {value}")
        return None
    return candidate


def _png_size(path: Path) -> tuple[int, int]:
    with path.open("rb") as handle:
        header = handle.read(24)
    if len(header) != 24 or header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
        raise ValueError("invalid PNG header")
    return struct.unpack(">II", header[16:24])


def _webp_size(path: Path) -> tuple[int, int]:
    with path.open("rb") as handle:
        data = handle.read(30)
    if len(data) < 16 or data[:4] != b"RIFF" or data[8:12] != b"WEBP":
        raise ValueError("invalid WebP header")
    kind = data[12:16]
    if kind == b"VP8X" and len(data) >= 30:
        width = 1 + int.from_bytes(data[24:27], "little")
        height = 1 + int.from_bytes(data[27:30], "little")
        return width, height
    if kind == b"VP8 " and len(data) >= 30 and data[23:26] == b"\x9d\x01\x2a":
        width, height = struct.unpack("<HH", data[26:30])
        return width & 0x3FFF, height & 0x3FFF
    if kind == b"VP8L" and len(data) >= 25 and data[20] == 0x2F:
        bits = int.from_bytes(data[21:25], "little")
        return (bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1
    raise ValueError("unsupported WebP header")


def _image_size(path: Path) -> tuple[int, int]:
    if path.suffix.lower() == ".png":
        return _png_size(path)
    if path.suffix.lower() == ".webp":
        return _webp_size(path)
    raise ValueError(f"unsupported raster extension: {path.suffix}")


def _schema_errors(instance: Any, schema_path: Path) -> list[str]:
    try:
        from jsonschema import Draft202012Validator
    except ImportError:
        return [
            "asset manifest exists but jsonschema is unavailable; install the hashed "
            "requirements/validation-linux-cp314.lock environment"
        ]
    schema = _load_json(schema_path)
    validator = Draft202012Validator(schema)
    return [
        f"asset manifest schema error at /{'/'.join(map(str, error.path))}: {error.message}"
        for error in sorted(validator.iter_errors(instance), key=lambda item: list(item.path))
    ]


def _android_visual_files(root: Path) -> list[Path]:
    found: set[Path] = set()
    for module in ANDROID_MODULES:
        source_root = root / module / "src"
        if not source_root.exists():
            continue
        for path in source_root.rglob("*"):
            if not path.is_file():
                continue
            if path.suffix.lower() in VISUAL_SUFFIXES:
                found.add(path)
                continue
            if path.suffix.lower() == ".xml" and any(
                part.startswith("drawable") or part.startswith("mipmap")
                for part in path.parts
            ):
                found.add(path)
    return sorted(found)


def validate_project_assets(
    root: Path,
    *,
    require_schema_dependency: bool = True,
) -> ValidationResult:
    root = root.resolve()
    ssot = root / SSOT_RELATIVE
    registry_path = ssot / "assets/imagegen-prompt-registry.json"
    schema_path = ssot / "assets/asset-manifest.schema.json"
    manifest_path = root / MANIFEST_RELATIVE
    deploy_root = root / DEPLOY_RELATIVE
    errors: list[str] = []

    if not registry_path.is_file() or not schema_path.is_file():
        return ValidationResult(
            "INVALID_SSOT_PATH",
            0,
            0,
            ("immutable ImageGen registry or manifest schema is missing",),
        )

    all_visuals = _android_visual_files(root)
    deployed = sorted(
        path
        for path in all_visuals
        if deploy_root in path.parents and path.suffix.lower() in ALLOWED_MASTER_SUFFIXES
    )
    disallowed_visuals = [path for path in all_visuals if path not in deployed]
    for path in disallowed_visuals:
        errors.append(
            "visual asset is outside the only allowed ImageGen deployment path/format: "
            f"{path.relative_to(root).as_posix()}"
        )

    if not deployed and not manifest_path.exists():
        status = "NO_DEPLOYED_VISUAL_ASSETS" if not errors else "INVALID"
        return ValidationResult(status, 0, 0, tuple(errors))
    if deployed and not manifest_path.is_file():
        errors.append("deployed raster assets exist but asset manifest is missing")
        return ValidationResult("INVALID", len(deployed), 0, tuple(errors))
    if not manifest_path.is_file():
        return ValidationResult("INVALID", len(deployed), 0, tuple(errors))

    try:
        manifest = _load_json(manifest_path)
    except (OSError, json.JSONDecodeError) as exc:
        return ValidationResult("INVALID", len(deployed), 0, (f"invalid asset manifest: {exc}",))

    if require_schema_dependency:
        errors.extend(_schema_errors(manifest, schema_path))

    registry = _load_json(registry_path)
    registry_assets = {
        entry.get("assetId"): entry
        for entry in registry.get("assets", [])
        if isinstance(entry, dict) and isinstance(entry.get("assetId"), str)
    }
    allowed_profiles = set(registry.get("policy", {}).get("approvedStyleProfiles", []))
    manifest_assets = manifest.get("assets", []) if isinstance(manifest, dict) else []
    if not isinstance(manifest_assets, list):
        manifest_assets = []

    if manifest.get("generationMethod") != "IMAGEGEN_SKILL":
        errors.append("manifest generationMethod must be IMAGEGEN_SKILL; mixed/manual/external lineage rejected")

    by_id: dict[str, dict[str, Any]] = {}
    processed_to_id: dict[str, str] = {}
    for index, entry in enumerate(manifest_assets):
        label = f"assets[{index}]"
        if not isinstance(entry, dict):
            errors.append(f"{label} is not an object")
            continue
        asset_id = entry.get("assetId")
        if not isinstance(asset_id, str):
            errors.append(f"{label}.assetId is missing")
            continue
        if asset_id in by_id:
            errors.append(f"duplicate manifest assetId: {asset_id}")
        by_id[asset_id] = entry
        registry_entry = registry_assets.get(asset_id)
        if registry_entry is None:
            errors.append(f"registry-external assetId rejected: {asset_id}")
            continue
        if registry_entry.get("generationMethod") != "IMAGEGEN_SKILL":
            errors.append(f"registry generation method is not ImageGen Skill: {asset_id}")
        for field in ("gameId", "styleProfileId", "styleRevision", "templateId"):
            if entry.get(field) != registry_entry.get(field):
                errors.append(f"{asset_id}.{field} does not match the immutable registry")
        if entry.get("styleProfileId") not in allowed_profiles:
            errors.append(f"unapproved style profile for {asset_id}")
        if entry.get("provenance") != "OPENAI_IMAGEGEN":
            errors.append(f"non-ImageGen provenance rejected for {asset_id}")
        for field in ("promptRecordHash", "sourceSha256", "processedSha256"):
            value = entry.get(field)
            if not isinstance(value, str) or not HASH_RE.fullmatch(value) or set(value) == {"0"}:
                errors.append(f"{asset_id}.{field} must be a non-placeholder SHA-256")
        recipe = entry.get("processingRecipe")
        if not isinstance(recipe, list) or any(
            not isinstance(step, str) or not SAFE_RECIPE_RE.fullmatch(step)
            for step in recipe
        ):
            errors.append(f"unsafe or unrecorded processing recipe for {asset_id}")

        for field, hash_field in (("sourceFile", "sourceSha256"), ("processedFile", "processedSha256")):
            value = entry.get(field)
            if not isinstance(value, str):
                errors.append(f"{asset_id}.{field} is missing")
                continue
            path = _resolve_inside(root, value, f"{asset_id}.{field}", errors)
            if path is None:
                continue
            if not path.is_file():
                errors.append(f"{asset_id}.{field} does not exist: {value}")
                continue
            expected_hash = entry.get(hash_field)
            if isinstance(expected_hash, str) and HASH_RE.fullmatch(expected_hash):
                actual_hash = _sha256(path)
                if actual_hash != expected_hash:
                    errors.append(f"{asset_id}.{hash_field} mismatch")
            if field == "sourceFile":
                source_root = (root / "assets-src/imagegen").resolve()
                try:
                    path.relative_to(source_root)
                except ValueError:
                    errors.append(f"{asset_id}.sourceFile is outside assets-src/imagegen")
            else:
                relative = path.relative_to(root).as_posix()
                if relative in processed_to_id:
                    errors.append(f"processed file reused by multiple assets: {relative}")
                processed_to_id[relative] = asset_id
                try:
                    actual_width, actual_height = _image_size(path)
                    if (actual_width, actual_height) != (entry.get("width"), entry.get("height")):
                        errors.append(f"{asset_id} processed dimensions do not match manifest")
                except ValueError as exc:
                    errors.append(f"{asset_id} processed image invalid: {exc}")

        source_value = entry.get("sourceFile")
        if isinstance(source_value, str):
            source_path = _resolve_inside(root, source_value, f"{asset_id}.sourceFile", errors)
            if source_path is not None:
                prompt_path = source_path.parent / "prompt-record.json"
                if not prompt_path.is_file():
                    errors.append(f"{asset_id}.prompt-record.json is missing beside sourceFile")
                else:
                    expected_prompt_hash = entry.get("promptRecordHash")
                    if (
                        isinstance(expected_prompt_hash, str)
                        and HASH_RE.fullmatch(expected_prompt_hash)
                        and _sha256(prompt_path) != expected_prompt_hash
                    ):
                        errors.append(f"{asset_id}.promptRecordHash mismatch")
                    try:
                        prompt_record = _load_json(prompt_path)
                    except (OSError, json.JSONDecodeError) as exc:
                        errors.append(f"{asset_id}.prompt-record.json is invalid: {exc}")
                    else:
                        for field in (
                            "assetId",
                            "gameId",
                            "generationMethod",
                            "styleProfileId",
                            "styleRevision",
                            "templateId",
                            "promptVariables",
                            "referenceAssetIds",
                        ):
                            expected = (
                                "IMAGEGEN_SKILL"
                                if field == "generationMethod"
                                else registry_entry.get(field)
                            )
                            if prompt_record.get(field) != expected:
                                errors.append(
                                    f"{asset_id}.prompt-record.json {field} does not match registry"
                                )

    deployed_relatives = {path.relative_to(root).as_posix() for path in deployed}
    for relative in sorted(deployed_relatives - set(processed_to_id)):
        errors.append(f"deployed raster lacks manifest lineage: {relative}")

    for relative in sorted(deployed_relatives & set(processed_to_id)):
        entry = by_id[processed_to_id[relative]]
        asset_id = entry["assetId"]
        review = entry.get("review", {})
        if entry.get("redistributionReviewStatus") != "APPROVED":
            errors.append(f"deployed asset redistribution not approved: {asset_id}")
        if (
            not isinstance(review, dict)
            or review.get("status") != "APPROVED"
            or not isinstance(review.get("score"), int)
            or review.get("score", 0) < 18
            or review.get("criticalFailures") != []
            or not review.get("reviewer")
            or not review.get("reviewDate")
        ):
            errors.append(f"deployed asset human review gate failed: {asset_id}")
        registry_entry = registry_assets.get(asset_id, {})
        for anchor_id in registry_entry.get("referenceAssetIds", []):
            anchor = by_id.get(anchor_id)
            anchor_review = anchor.get("review", {}) if isinstance(anchor, dict) else {}
            if (
                not isinstance(anchor, dict)
                or anchor.get("revision") != entry.get("anchorRevision")
                or anchor_review.get("status") != "APPROVED"
                or anchor_review.get("score", 0) < 18
                or anchor_review.get("criticalFailures") != []
            ):
                errors.append(f"approved anchor revision missing for {asset_id}: {anchor_id}")

    status = "LINEAGE_VALID" if not errors else "INVALID"
    return ValidationResult(status, len(deployed), len(manifest_assets), tuple(errors))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    result = validate_project_assets(args.root)
    print(
        f"PROJECT_ASSET_STATUS={result.status} "
        f"deployed={result.deployed_count} manifest={result.manifest_count}"
    )
    if result.status == "NO_DEPLOYED_VISUAL_ASSETS":
        print("G6 is not approved: no deployed visual assets were evaluated.")
    for error in result.errors:
        print(f"ERROR: {error}", file=sys.stderr)
    return 0 if result.ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
