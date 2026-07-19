from __future__ import annotations

import hashlib
import json
import struct
import tempfile
import unittest
import zlib
from pathlib import Path

from scripts.validate_project_assets import MANIFEST_RELATIVE, SSOT_RELATIVE, validate_project_assets


def png_bytes(width: int = 1, height: int = 1) -> bytes:
    def chunk(kind: bytes, payload: bytes) -> bytes:
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload))

    row = b"\x00" + b"\x00\x00\x00\x00" * width
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(row * height))
        + chunk(b"IEND", b"")
    )


class ProjectAssetValidatorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        ssot = self.root / SSOT_RELATIVE / "assets"
        ssot.mkdir(parents=True)
        (ssot / "asset-manifest.schema.json").write_text(
            json.dumps({"$schema": "https://json-schema.org/draft/2020-12/schema", "type": "object"}),
            encoding="utf-8",
        )
        registry = {
            "policy": {"approvedStyleProfiles": ["fishing-v1"]},
            "assets": [
                {
                    "assetId": "fishing.anchor.style-board",
                    "gameId": "FISHING",
                    "styleProfileId": "fishing-v1",
                    "styleRevision": 1,
                    "templateId": "anchor-board-v1",
                    "generationMethod": "IMAGEGEN_SKILL",
                    "referenceAssetIds": [],
                },
                {
                    "assetId": "fishing.icon.bite",
                    "gameId": "FISHING",
                    "styleProfileId": "fishing-v1",
                    "styleRevision": 1,
                    "templateId": "icon-v1",
                    "generationMethod": "IMAGEGEN_SKILL",
                    "referenceAssetIds": ["fishing.anchor.style-board"],
                },
            ],
        }
        (ssot / "imagegen-prompt-registry.json").write_text(json.dumps(registry), encoding="utf-8")

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _asset(self, asset_id: str, source: str, processed: str, revision: int = 1) -> dict:
        source_path = self.root / source
        processed_path = self.root / processed
        source_path.parent.mkdir(parents=True, exist_ok=True)
        processed_path.parent.mkdir(parents=True, exist_ok=True)
        data = png_bytes()
        source_path.write_bytes(data)
        processed_path.write_bytes(data)
        digest = hashlib.sha256(data).hexdigest()
        prompt_record = {
            "assetId": asset_id,
            "gameId": "FISHING",
            "generationMethod": "IMAGEGEN_SKILL",
            "styleProfileId": "fishing-v1",
            "styleRevision": 1,
            "templateId": "anchor-board-v1" if "anchor" in asset_id else "icon-v1",
            "promptVariables": None,
            "referenceAssetIds": [] if "anchor" in asset_id else ["fishing.anchor.style-board"],
        }
        prompt_path = source_path.parent / "prompt-record.json"
        prompt_data = json.dumps(prompt_record, sort_keys=True).encode()
        prompt_path.write_bytes(prompt_data)
        return {
            "assetId": asset_id,
            "gameId": "FISHING",
            "styleProfileId": "fishing-v1",
            "styleRevision": 1,
            "anchorRevision": 1,
            "templateId": "anchor-board-v1" if "anchor" in asset_id else "icon-v1",
            "promptRecordHash": hashlib.sha256(prompt_data).hexdigest(),
            "sourceFile": source,
            "sourceSha256": digest,
            "processedFile": processed,
            "processedSha256": digest,
            "width": 1,
            "height": 1,
            "pivotX": 0.5,
            "pivotY": 0.5,
            "collisionBounds": {"x": 0.0, "y": 0.0, "width": 1.0, "height": 1.0},
            "provenance": "OPENAI_IMAGEGEN",
            "redistributionReviewStatus": "APPROVED",
            "generationDate": "2026-07-14",
            "revision": revision,
            "processingRecipe": ["convert-colorspace(sRGB)"],
            "review": {
                "status": "APPROVED",
                "reviewer": "fixture-reviewer",
                "reviewDate": "2026-07-14",
                "score": 20,
                "criticalFailures": [],
            },
        }

    def _valid_manifest(self) -> dict:
        anchor = self._asset(
            "fishing.anchor.style-board",
            "assets-src/imagegen/FISHING/fishing.anchor.style-board/r01/raw.png",
            "docs/evidence/assets/fishing-anchor-r01.png",
        )
        icon = self._asset(
            "fishing.icon.bite",
            "assets-src/imagegen/FISHING/fishing.icon.bite/r01/raw.png",
            "app/src/main/res/drawable-nodpi/fishing_icon_bite.png",
        )
        return {"schemaVersion": 1, "generationMethod": "IMAGEGEN_SKILL", "assets": [anchor, icon]}

    def _write_manifest(self, manifest: dict) -> None:
        path = self.root / MANIFEST_RELATIVE
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(manifest), encoding="utf-8")

    def _validate(self):
        return validate_project_assets(self.root, require_schema_dependency=False)

    def test_no_assets_is_explicitly_not_g6(self) -> None:
        result = self._validate()
        self.assertTrue(result.ok)
        self.assertEqual("NO_DEPLOYED_VISUAL_ASSETS", result.status)

    def test_jpeg_in_app_drawable_is_rejected(self) -> None:
        path = self.root / "app/src/main/res/drawable/manual.jpg"
        path.parent.mkdir(parents=True)
        path.write_bytes(b"not-approved")
        result = self._validate()
        self.assertFalse(result.ok)
        self.assertTrue(any("manual.jpg" in error for error in result.errors))

    def test_raster_in_games_module_is_rejected(self) -> None:
        path = self.root / "games/src/main/res/drawable-nodpi/mixed.png"
        path.parent.mkdir(parents=True)
        path.write_bytes(png_bytes())
        result = self._validate()
        self.assertFalse(result.ok)
        self.assertTrue(any("games/src" in error for error in result.errors))

    def test_vector_drawable_is_rejected(self) -> None:
        path = self.root / "app/src/main/res/drawable/manual.xml"
        path.parent.mkdir(parents=True)
        path.write_text("<vector />", encoding="utf-8")
        result = self._validate()
        self.assertFalse(result.ok)
        self.assertTrue(any("manual.xml" in error for error in result.errors))

    def test_visual_in_android_assets_is_rejected(self) -> None:
        path = self.root / "app/src/main/assets/mixed.webp"
        path.parent.mkdir(parents=True)
        path.write_bytes(b"not-approved")
        result = self._validate()
        self.assertFalse(result.ok)
        self.assertTrue(any("mixed.webp" in error for error in result.errors))

    def test_valid_synthetic_lineage(self) -> None:
        self._write_manifest(self._valid_manifest())
        result = self._validate()
        self.assertEqual((), result.errors)
        self.assertEqual("LINEAGE_VALID", result.status)

    def test_path_traversal_is_rejected(self) -> None:
        manifest = self._valid_manifest()
        manifest["assets"][1]["sourceFile"] = "../outside.png"
        self._write_manifest(manifest)
        self.assertTrue(any("escapes repository root" in error for error in self._validate().errors))

    def test_wrong_hash_is_rejected(self) -> None:
        manifest = self._valid_manifest()
        manifest["assets"][1]["processedSha256"] = "b" * 64
        self._write_manifest(manifest)
        self.assertTrue(any("processedSha256 mismatch" in error for error in self._validate().errors))

    def test_wrong_prompt_record_hash_is_rejected(self) -> None:
        manifest = self._valid_manifest()
        manifest["assets"][1]["promptRecordHash"] = "b" * 64
        self._write_manifest(manifest)
        self.assertTrue(any("promptRecordHash mismatch" in error for error in self._validate().errors))

    def test_registry_external_asset_is_rejected(self) -> None:
        manifest = self._valid_manifest()
        manifest["assets"][1]["assetId"] = "fishing.icon.unregistered"
        self._write_manifest(manifest)
        self.assertTrue(any("registry-external" in error for error in self._validate().errors))

    def test_mixed_generation_method_is_rejected(self) -> None:
        manifest = self._valid_manifest()
        manifest["generationMethod"] = "MANUAL"
        self._write_manifest(manifest)
        self.assertTrue(any("mixed/manual/external" in error for error in self._validate().errors))


if __name__ == "__main__":
    unittest.main()
