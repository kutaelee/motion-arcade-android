from __future__ import annotations

import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

from scripts.validate_apk_policy import (
    MODEL_PATH,
    _declared_permission_policy_errors,
    _merged_manifest_errors,
    _parse_permission_dump,
    _permission_policy_errors,
    validate_apk_policy,
)


class ApkPermissionParserTest(unittest.TestCase):
    def test_camera_and_custom_receiver_permission_parse(self) -> None:
        output = """package: com.example.debug
uses-permission: name='android.permission.CAMERA'
permission: com.example.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
uses-permission: name='com.example.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
"""
        package_name, permissions = _parse_permission_dump(output)
        self.assertEqual("com.example.debug", package_name)
        self.assertEqual(
            (
                "android.permission.CAMERA",
                "com.example.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            ),
            permissions,
        )

    def test_internet_permission_is_observable(self) -> None:
        output = """package: com.example
uses-permission: name='android.permission.INTERNET'
"""
        _, permissions = _parse_permission_dump(output)
        self.assertIn("android.permission.INTERNET", permissions)

    def test_permission_attributes_and_sdk_tags_cannot_hide_internet(self) -> None:
        fixtures = (
            "uses-permission: name='android.permission.INTERNET' maxSdkVersion='32'\n",
            "uses-permission-sdk-23: name='android.permission.ACCESS_NETWORK_STATE'\n",
            'uses-permission-sdk-m: name="android.permission.READ_MEDIA_IMAGES"\n',
            "uses-permission: name='android.permission.CAMERA' maxSdkVersion='32'\n",
            "uses-permission: name='com.motionarcade.app.debug."
            "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION' maxSdkVersion='32'\n",
        )
        for fixture in fixtures:
            with self.subTest(fixture=fixture):
                with self.assertRaises(ValueError):
                    _parse_permission_dump(
                        "package: com.motionarcade.app.debug\n" + fixture
                    )

    def test_unknown_or_ambiguous_permission_dump_line_fails_closed(self) -> None:
        fixtures = (
            "uses-permission-sdk-future: name='android.permission.INTERNET'\n",
            "uses-permission: maxSdkVersion='32'\n",
            "uses-permission: name='android.permission.CAMERA' name='android.permission.INTERNET'\n",
        )
        for fixture in fixtures:
            with self.subTest(fixture=fixture):
                with self.assertRaises(ValueError):
                    _parse_permission_dump("package: com.motionarcade.app.debug\n" + fixture)

    def test_camera_is_required_exactly_once(self) -> None:
        errors = _permission_policy_errors("com.motionarcade.app.debug", ())
        self.assertTrue(any("CAMERA" in error and "count=0" in error for error in errors))

    def test_receiver_permission_request_is_required_exactly_once(self) -> None:
        errors = _permission_policy_errors(
            "com.motionarcade.app.debug", ("android.permission.CAMERA",)
        )

        self.assertTrue(
            any("DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" in error and "count=0" in error for error in errors)
        )

    def test_package_name_is_exact(self) -> None:
        errors = _permission_policy_errors(
            "other.vendor.app", ("android.permission.CAMERA",)
        )
        self.assertIn("unexpected APK package name: other.vendor.app", errors)

    def test_every_merged_multiprocess_attribute_is_rejected_by_presence(self) -> None:
        output = "\n".join(
            (
                'A: android:process(0x01010011)=":worker" (Raw: ":worker")',
                "A: android:isolatedProcess(0x0101046b)=(type 0x12)0x0",
                "A: android:multiprocess(0x01010013)=(type 0x12)0x0",
            )
        )

        errors = _merged_manifest_errors(output)

        for attribute in ("android:process", "android:isolatedProcess", "android:multiprocess"):
            self.assertIn(
                f"forbidden multiprocess attribute in merged manifest: {attribute}",
                errors,
            )

    def test_shared_uid_attributes_are_rejected_by_presence(self) -> None:
        output = "\n".join(
            (
                'A: android:sharedUserId(0x0101000b)="com.example.shared"',
                "A: android:sharedUserMaxSdkVersion(0x01010591)=(type 0x10)0x20",
            )
        )

        errors = _merged_manifest_errors(output)

        for attribute in ("android:sharedUserId", "android:sharedUserMaxSdkVersion"):
            self.assertIn(
                f"forbidden shared UID attribute in merged manifest: {attribute}",
                errors,
            )

    def test_permission_tree_and_group_nodes_are_rejected(self) -> None:
        output = "\n".join(
            (
                "    E: permission-tree (line=17)",
                "    E: permission-group (line=21)",
            )
        )

        errors = _merged_manifest_errors(output)

        for node in ("permission-tree", "permission-group"):
            self.assertIn(
                f"forbidden permission declaration node in merged manifest: {node}",
                errors,
            )

    def test_receiver_permission_declaration_is_exact_signature(self) -> None:
        package_name = "com.motionarcade.app.debug"
        permission_name = f"{package_name}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        output = f"""    E: permission (line=17)
      A: http://schemas.android.com/apk/res/android:name(0x01010003)=\"{permission_name}\"
      A: http://schemas.android.com/apk/res/android:protectionLevel(0x01010009)=0x00000002
    E: uses-permission (line=21)
"""

        self.assertEqual((), _declared_permission_policy_errors(output, package_name))

    def test_missing_weakened_duplicate_and_foreign_permission_declarations_fail(self) -> None:
        package_name = "com.motionarcade.app.debug"
        permission_name = f"{package_name}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

        missing = _declared_permission_policy_errors("E: manifest\n", package_name)
        self.assertTrue(any("count=0" in error for error in missing))

        weakened = _declared_permission_policy_errors(
            "  E: permission\n"
            f'    A: android:name(0x1)=\"{permission_name}\"\n'
            "    A: android:protectionLevel(0x2)=0x00000000\n",
            package_name,
        )
        self.assertTrue(any("exact signature" in error for error in weakened))

        duplicate = _declared_permission_policy_errors(
            "  E: permission\n"
            f'    A: android:name(0x1)=\"{permission_name}\"\n'
            "    A: android:protectionLevel(0x2)=0x00000002\n"
            "  E: permission\n"
            f'    A: android:name(0x1)=\"{permission_name}\"\n'
            "    A: android:protectionLevel(0x2)=0x00000002\n",
            package_name,
        )
        self.assertTrue(any("count=2" in error for error in duplicate))

        foreign = _declared_permission_policy_errors(
            "  E: permission\n"
            '    A: android:name(0x1)=\"com.example.FOREIGN\"\n'
            "    A: android:protectionLevel(0x2)=0x00000002\n",
            package_name,
        )
        self.assertTrue(any("unapproved" in error for error in foreign))

    def test_full_validator_rejects_missing_camera_and_wrong_package(self) -> None:
        root = Path(__file__).resolve().parents[2]
        model = root / "vision/src/main/assets/pose_landmarker_lite.task"
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            apk = temp / "candidate.apk"
            aapt2 = temp / "aapt2"
            aapt2.write_bytes(b"fixture")
            with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_STORED) as archive:
                archive.write(model, MODEL_PATH)

            permission_output = ""

            def runner(command: list[str]) -> subprocess.CompletedProcess[str]:
                stdout = permission_output if "permissions" in command else "E: manifest"
                return subprocess.CompletedProcess(command, 0, stdout, "")

            missing = validate_apk_policy(apk, aapt2, runner=runner)
            self.assertFalse(missing.ok)
            self.assertTrue(any("CAMERA" in error for error in missing.errors))

            permission_output = (
                "package: other.vendor.app\n"
                "uses-permission: name='android.permission.CAMERA'\n"
            )
            wrong = validate_apk_policy(apk, aapt2, runner=runner)
            self.assertFalse(wrong.ok)
            self.assertIn("unexpected APK package name: other.vendor.app", wrong.errors)


if __name__ == "__main__":
    unittest.main()
