from __future__ import annotations

import shutil
import tempfile
import unittest
from pathlib import Path

from scripts.validate_supply_chain import _is_dynamic_token, validate_supply_chain


def _copy_repository_policy_tree(destination: Path) -> None:
    source = Path(__file__).resolve().parents[2]
    relative_files = (
        "gradlew",
        "gradlew.bat",
        "gradle.properties",
        "settings.gradle.kts",
        "build.gradle.kts",
        "settings-gradle.lockfile",
        "gradle/libs.versions.toml",
        "gradle/verification-metadata.xml",
        "gradle/wrapper/gradle-wrapper.jar",
        "gradle/wrapper/gradle-wrapper.properties",
        "app/build.gradle.kts",
        "app/gradle.lockfile",
        "vision/build.gradle.kts",
        "vision/gradle.lockfile",
        "game-core/build.gradle.kts",
        "game-core/gradle.lockfile",
        "games/build.gradle.kts",
        "games/gradle.lockfile",
    )
    for relative in relative_files:
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / relative, target)
    github = source / ".github"
    if github.exists():
        shutil.copytree(github, destination / ".github")


class DynamicVersionTest(unittest.TestCase):
    def test_exact_versions_are_allowed(self) -> None:
        for value in ("1.2.3", "g:a:1.2.3", "2026.06.01"):
            with self.subTest(value=value):
                self.assertFalse(_is_dynamic_token(value))

    def test_gradle_plus_versions_are_rejected(self) -> None:
        for value in ("1.+", "g:a:1.+", "+"):
            with self.subTest(value=value):
                self.assertTrue(_is_dynamic_token(value))

    def test_maven_ranges_are_rejected(self) -> None:
        for value in (
            "[1.0,2.0)",
            "(,1.5]",
            "[1.0,)",
            "[1.0,2.0[",
            "]1.0,2.0]",
            "]1.0,2.0[",
            "(,1.0],[1.2,)",
            "g:a:[1.0,2.0[",
            "g:a:[1.0,2.0[@aar",
            "g:a:[1.0,2.0[!!",
            "g:a:]1.0,2.0]:classifier@aar",
        ):
            with self.subTest(value=value):
                self.assertTrue(_is_dynamic_token(value))

    def test_exact_versions_with_non_range_punctuation_are_allowed(self) -> None:
        for value in (
            "1.0-final",
            "1.0-build,7",
            "g:a:1.2.3:classifier@aar",
            "2026.06.01-rc1",
        ):
            with self.subTest(value=value):
                self.assertFalse(_is_dynamic_token(value))

    def test_latest_and_snapshot_are_rejected(self) -> None:
        for value in ("latest.release", "g:a:2.0-SNAPSHOT"):
            with self.subTest(value=value):
                self.assertTrue(_is_dynamic_token(value))


class RepositoryPolicyTest(unittest.TestCase):
    def test_full_tree_toml_gradle_range_forms_are_rejected(self) -> None:
        fixtures = (
            "[1.13, 2.0)",
            "(1.13, 2.0]",
            "[1.13, 2.0[",
            "]1.13, 2.0]",
            "]1.13, 2.0[",
            "(,1.13]",
            "[1.13,)",
            "(,1.12],[1.13,)",
        )
        for fixture in fixtures:
            with self.subTest(fixture=fixture), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                _copy_repository_policy_tree(root)
                catalog = root / "gradle/libs.versions.toml"
                catalog.write_text(
                    catalog.read_text(encoding="utf-8").replace(
                        'activity-compose = "1.13.0"',
                        f'activity-compose = "{fixture}"',
                    ),
                    encoding="utf-8",
                )

                result = validate_supply_chain(root)

                self.assertEqual(
                    1,
                    sum(
                        "decoded dynamic/ranged version rejected in version catalog" in error
                        for error in result.errors
                    ),
                )

    def test_full_tree_toml_exact_version_control_passes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _copy_repository_policy_tree(root)
            catalog = root / "gradle/libs.versions.toml"
            catalog.write_text(
                catalog.read_text(encoding="utf-8").replace(
                    'activity-compose = "1.13.0"',
                    'activity-compose = "1.13.1"',
                ),
                encoding="utf-8",
            )

            result = validate_supply_chain(root)

            self.assertEqual((), result.errors)

    def test_unpinned_action_and_permission_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".github/workflows").mkdir(parents=True)
            (root / ".github/workflows/ci.yml").write_text(
                "steps:\n  - uses: actions/checkout@v4\n",
                encoding="utf-8",
            )
            manifest = root / "app/src/main/AndroidManifest.xml"
            manifest.parent.mkdir(parents=True)
            manifest.write_text(
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
                '<uses-permission android:name="android.permission.INTERNET" />'
                '</manifest>',
                encoding="utf-8",
            )
            result = validate_supply_chain(root)
            self.assertTrue(any("full commit SHA" in error for error in result.errors))
            self.assertTrue(any("INTERNET" in error for error in result.errors))

    def test_quoted_unpinned_action_key_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workflow = root / ".github/workflows/ci.yml"
            workflow.parent.mkdir(parents=True)
            workflow.write_text(
                'jobs:\n  verify:\n    steps:\n      - "uses": "actions/checkout@v4"\n',
                encoding="utf-8",
            )

            result = validate_supply_chain(root)

            self.assertTrue(any("canonical subset" in error for error in result.errors))

    def test_flow_mapping_action_cannot_escape_pin_check(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workflow = root / ".github/workflows/ci.yml"
            workflow.parent.mkdir(parents=True)
            workflow.write_text(
                'jobs:\n  verify:\n    steps:\n      - {"uses": "actions/checkout@v4"}\n',
                encoding="utf-8",
            )

            result = validate_supply_chain(root)

            self.assertTrue(
                any("forbidden YAML flow collection" in error for error in result.errors)
            )

    def test_escaped_action_key_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workflow = root / ".github/workflows/ci.yml"
            workflow.parent.mkdir(parents=True)
            workflow.write_text(
                'steps:\n  - "u\\u0073es": actions/checkout@v4\n', encoding="utf-8"
            )

            result = validate_supply_chain(root)

            self.assertTrue(any("canonical subset" in error for error in result.errors))

    def test_nested_flow_reusable_workflow_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workflow = root / ".github/workflows/ci.yml"
            workflow.parent.mkdir(parents=True)
            workflow.write_text(
                'jobs: { call: { "u\\u0073es": owner/repo/.github/workflows/ci.yml@v1 } }\n',
                encoding="utf-8",
            )

            result = validate_supply_chain(root)

            self.assertTrue(any("forbidden YAML flow collection" in error for error in result.errors))

    def test_explicit_yaml_key_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workflow = root / ".github/workflows/ci.yml"
            workflow.parent.mkdir(parents=True)
            workflow.write_text(
                "steps:\n  - ? uses\n    : actions/checkout@v4\n", encoding="utf-8"
            )

            result = validate_supply_chain(root)

            self.assertTrue(any("explicit/merge YAML key" in error for error in result.errors))

    def test_spaced_merge_key_and_flow_map_cannot_hide_action(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workflow = root / ".github/workflows/ci.yml"
            workflow.parent.mkdir(parents=True)
            workflow.write_text(
                "steps:\n  - << : { uses: actions/checkout@v4 }\n", encoding="utf-8"
            )

            result = validate_supply_chain(root)

            self.assertTrue(any("explicit/merge YAML key" in error for error in result.errors))

    def test_yaml_anchor_alias_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workflow = root / ".github/workflows/ci.yml"
            workflow.parent.mkdir(parents=True)
            workflow.write_text(
                'steps:\n  - &unpinned\n    "u\\u0073es": actions/checkout@v4\n  - *unpinned\n',
                encoding="utf-8",
            )

            result = validate_supply_chain(root)

            self.assertTrue(any("anchor or alias" in error for error in result.errors))

    def test_yaml_tags_cannot_hide_action_key(self) -> None:
        fixtures = (
            "steps:\n  - !!str uses: actions/checkout@v4\n",
            'steps:\n  - !!str "u\\u0073es": actions/checkout@v4\n',
        )
        for fixture in fixtures:
            with self.subTest(fixture=fixture), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                workflow = root / ".github/workflows/ci.yml"
                workflow.parent.mkdir(parents=True)
                workflow.write_text(fixture, encoding="utf-8")

                result = validate_supply_chain(root)

                self.assertTrue(any("forbidden YAML tag" in error for error in result.errors))

    def test_plain_run_braces_and_star_are_not_yaml_structure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workflow = root / ".github/workflows/ci.yml"
            workflow.parent.mkdir(parents=True)
            workflow.write_text(
                "steps:\n  - run: echo {1..3}\n  - run: echo *test\n",
                encoding="utf-8",
            )

            result = validate_supply_chain(root)

            self.assertFalse(any("flow collection" in error for error in result.errors))
            self.assertFalse(any("anchor or alias" in error for error in result.errors))

    def test_plain_apostrophes_expression_literals_and_block_content_are_allowed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workflow = root / ".github/workflows/ci.yml"
            workflow.parent.mkdir(parents=True)
            workflow.write_text(
                "name: It's valid YAML\n"
                "jobs:\n"
                "  verify:\n"
                "    if: ${{ contains('}}', github.ref) }}\n"
                "    steps:\n"
                "      - run: |\n"
                "       echo hello\n"
                "      - run: |\n"
                "      \techo tab is scalar content\n"
                "      - run: echo it's valid shell text\n",
                encoding="utf-8",
            )

            result = validate_supply_chain(root)

            self.assertFalse(any("invalid GitHub Action uses entry" in error for error in result.errors))

    def test_bare_dash_sequence_items_receive_distinct_mapping_scopes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workflow = root / ".github/workflows/ci.yml"
            workflow.parent.mkdir(parents=True)
            workflow.write_text(
                "steps:\n  -\n    run: echo first\n  -\n    run: echo second\n",
                encoding="utf-8",
            )

            result = validate_supply_chain(root)

            self.assertFalse(any("duplicates a canonical mapping key" in error for error in result.errors))

    def test_invalid_anchor_like_node_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workflow = root / ".github/workflows/ci.yml"
            workflow.parent.mkdir(parents=True)
            workflow.write_text("steps:\n  - &.key\n", encoding="utf-8")

            result = validate_supply_chain(root)

            self.assertTrue(any("anchor or alias" in error for error in result.errors))

    def test_invalid_yaml_constructs_fail_closed(self) -> None:
        fixtures = (
            'name: "unterminated\n',
            "steps:\n   - run: echo bad-indent\n",
            "steps:\n  - : invalid\n",
            "jobs:\n    build:\n  steps:\n",
            'name: "bad \\q"\n',
            'name: "ok" trailing\n',
            "name: first\nname: second\n",
            "name: @bad\n",
            "name: `bad\n",
            "name: ? bad\n",
            "name: : bad\n",
            "steps:\n  - run: echo key: value\n",
        )
        for fixture in fixtures:
            with self.subTest(fixture=fixture), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                workflow = root / ".github/workflows/ci.yml"
                workflow.parent.mkdir(parents=True)
                workflow.write_text(fixture, encoding="utf-8")

                result = validate_supply_chain(root)

                self.assertTrue(any("invalid GitHub Action uses entry" in error for error in result.errors))

    def test_local_action_is_outside_the_approved_inventory(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workflow = root / ".github/workflows/ci.yml"
            workflow.parent.mkdir(parents=True)
            workflow.write_text(
                "steps:\n  - uses: ./.github/actions/transitive\n", encoding="utf-8"
            )
            action = root / ".github/actions/transitive/action.yml"
            action.parent.mkdir(parents=True)
            action.write_text(
                "name: transitive\nruns:\n  using: composite\n  steps:\n"
                "    - uses: actions/checkout@v4\n",
                encoding="utf-8",
            )

            result = validate_supply_chain(root)

            self.assertTrue(any("local GitHub Action/workflow references" in error for error in result.errors))

    def test_missing_and_traversing_local_actions_are_rejected(self) -> None:
        fixtures = ("./does-not-exist", "./../outside/action", "./safe/../other")
        for local_path in fixtures:
            with self.subTest(local_path=local_path), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                workflow = root / ".github/workflows/ci.yml"
                workflow.parent.mkdir(parents=True)
                workflow.write_text(
                    f"steps:\n  - uses: {local_path}\n", encoding="utf-8"
                )

                result = validate_supply_chain(root)

                self.assertTrue(
                    any("local GitHub Action/workflow references" in error for error in result.errors)
                )

    def test_uses_text_in_run_string_and_comment_is_not_a_mapping(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workflow = root / ".github/workflows/ci.yml"
            workflow.parent.mkdir(parents=True)
            workflow.write_text(
                'steps:\n  - run: echo "{ uses: documentation only }"\n'
                "# example: { uses: actions/example@v4 }\n",
                encoding="utf-8",
            )

            result = validate_supply_chain(root)

            self.assertFalse(any("YAML flow mapping" in error for error in result.errors))

    def test_applied_gradle_script_cannot_escape_inventory(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "build.gradle.kts").write_text(
                'apply(from = "gradle/unsafe.gradle.kts")', encoding="utf-8"
            )
            script = root / "gradle/unsafe.gradle.kts"
            script.parent.mkdir(parents=True)
            script.write_text(
                'repositories { mavenLocal() }\n'
                'dependencies { implementation("g:a:1.+") }\n',
                encoding="utf-8",
            )

            result = validate_supply_chain(root)

            self.assertTrue(any("Gradle apply DSL" in error for error in result.errors))
            self.assertTrue(any("mavenLocal" in error for error in result.errors))
            self.assertTrue(any("dynamic/ranged version" in error for error in result.errors))

    def test_groovy_applied_script_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "build.gradle").write_text(
                'apply from: "build-logic/unsafe.gradle"\n', encoding="utf-8"
            )

            result = validate_supply_chain(root)

            self.assertTrue(any("Gradle apply DSL" in error for error in result.errors))

    def test_groovy_map_apply_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "build.gradle").write_text(
                'apply([from: "build-logic/unsafe.gradle"])\n', encoding="utf-8"
            )

            result = validate_supply_chain(root)

            self.assertTrue(any("Gradle apply DSL" in error for error in result.errors))

    def test_groovy_no_parentheses_include_build_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "settings.gradle").write_text(
                'pluginManagement { includeBuild "build-logic" }\n', encoding="utf-8"
            )

            result = validate_supply_chain(root)

            self.assertTrue(any("included Gradle build" in error for error in result.errors))

    def test_variable_include_build_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "settings.gradle").write_text(
                'def target = "build-logic"\nincludeBuild target\n', encoding="utf-8"
            )

            result = validate_supply_chain(root)

            self.assertTrue(any("included Gradle build" in error for error in result.errors))

    def test_find_project_dependency_lookup_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            build_file = root / "games/build.gradle"
            build_file.parent.mkdir(parents=True)
            build_file.write_text(
                "dependencies { implementation findProject(':vision') }\n",
                encoding="utf-8",
            )

            result = validate_supply_chain(root)

            self.assertTrue(any("noncanonical Gradle project lookup" in error for error in result.errors))

    def test_gradle_launchers_and_daemon_properties_are_hash_pinned(self) -> None:
        fixtures = (
            ("gradlew", "#!/bin/sh\necho bypass\n"),
            ("gradlew.bat", "@echo bypass\r\n"),
            ("gradle.properties", "org.gradle.jvmargs=-javaagent:agent.jar\n"),
        )
        for relative, content in fixtures:
            with self.subTest(relative=relative), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8", newline="")

                result = validate_supply_chain(root)

                self.assertTrue(
                    any(
                        f"Gradle launcher/control hash differs from approved bytes: {relative}"
                        in error
                        for error in result.errors
                    )
                )

    def test_trusted_artifacts_cannot_disable_dependency_verification(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            verification = root / "gradle/verification-metadata.xml"
            verification.parent.mkdir(parents=True)
            verification.write_text(
                '<?xml version="1.0" encoding="UTF-8"?>\n'
                '<verification-metadata xmlns="https://schema.gradle.org/dependency-verification" '
                'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" '
                'xsi:schemaLocation="https://schema.gradle.org/dependency-verification '
                'https://schema.gradle.org/dependency-verification/dependency-verification-1.3.xsd">'
                '<configuration><verify-metadata>true</verify-metadata>'
                '<verify-signatures>false</verify-signatures>'
                '<trusted-artifacts><trust group=".*" regex="true" /></trusted-artifacts>'
                '</configuration><components><component group="g" name="a" version="1">'
                '<artifact name="a.jar"><sha256 value="'
                + "a" * 64
                + '" origin="fixture" /></artifact></component></components>'
                '</verification-metadata>',
                encoding="utf-8",
            )

            result = validate_supply_chain(root)

            self.assertTrue(any("dependency verification XML is invalid" in error for error in result.errors))

    def test_toml_escaped_dynamic_versions_are_rejected_after_decode(self) -> None:
        fixtures = ('agp = "9.2.\\u002b"\n', 'agp = "2.0-SNA\\u0050SHOT"\n')
        for fixture in fixtures:
            with self.subTest(fixture=fixture), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                catalog = root / "gradle/libs.versions.toml"
                catalog.parent.mkdir(parents=True)
                catalog.write_text("[versions]\n" + fixture, encoding="utf-8")

                result = validate_supply_chain(root)

                self.assertTrue(any("decoded dynamic/ranged version" in error for error in result.errors))

    def test_alternate_range_is_rejected_in_gradle_dependency_string(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "build.gradle.kts").write_text(
                'dependencies { implementation("g:a:[1.0,2.0[@aar") }\n',
                encoding="utf-8",
            )

            result = validate_supply_chain(root)

            self.assertTrue(
                any(
                    "dynamic/ranged version rejected in build.gradle.kts: g:a:[1.0,2.0[@aar"
                    in error
                    for error in result.errors
                )
            )

    def test_alternate_range_is_rejected_in_verification_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            verification = root / "gradle/verification-metadata.xml"
            verification.parent.mkdir(parents=True)
            verification.write_text(
                '<?xml version="1.0" encoding="UTF-8"?>\n'
                '<verification-metadata xmlns="https://schema.gradle.org/dependency-verification" '
                'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" '
                'xsi:schemaLocation="https://schema.gradle.org/dependency-verification '
                'https://schema.gradle.org/dependency-verification/dependency-verification-1.3.xsd">'
                '<configuration><verify-metadata>true</verify-metadata>'
                '<verify-signatures>false</verify-signatures></configuration>'
                '<components><component group="g" name="a" version="[1.0,2.0[">'
                '<artifact name="a.jar"><sha256 value="'
                + "a" * 64
                + '" origin="fixture" /></artifact></component></components>'
                '</verification-metadata>',
                encoding="utf-8",
            )

            result = validate_supply_chain(root)

            self.assertTrue(
                any("dynamic dependency-verification component" in error for error in result.errors)
            )

    def test_unpinned_job_and_service_containers_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workflow = root / ".github/workflows/ci.yml"
            workflow.parent.mkdir(parents=True)
            workflow.write_text(
                "jobs:\n  verify:\n    container: alpine:latest\n"
                "    services:\n      db:\n        image: postgres:latest\n",
                encoding="utf-8",
            )

            result = validate_supply_chain(root)

            for key in ("container", "services", "image"):
                self.assertTrue(any(f"container key: {key}" in error for error in result.errors))

    def test_parallel_groovy_settings_file_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "settings.gradle.kts").write_text(
                '\n'.join(f'include(":{module}")' for module in ("app", "vision", "game-core", "games")),
                encoding="utf-8",
            )
            (root / "settings.gradle").write_text(
                'println "unexpected settings"\n', encoding="utf-8"
            )

            result = validate_supply_chain(root)

            self.assertTrue(any("would override canonical" in error for error in result.errors))

    def test_app_multiprocess_attributes_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "app/src/main/AndroidManifest.xml"
            manifest.parent.mkdir(parents=True)
            manifest.write_text(
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
                '<application>'
                '<service android:name=".Worker" android:process=":worker" '
                'android:isolatedProcess="false" />'
                '<provider android:name=".Legacy" android:multiprocess="@bool/legacy" />'
                '</application>'
                '</manifest>',
                encoding="utf-8",
            )

            result = validate_supply_chain(root)

            self.assertTrue(any("app multiprocess component rejected" in error for error in result.errors))
            self.assertTrue(any("isolated app process rejected" in error for error in result.errors))
            self.assertTrue(any("legacy multiprocess provider rejected" in error for error in result.errors))

    def test_shared_uid_manifest_attributes_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "app/src/main/AndroidManifest.xml"
            manifest.parent.mkdir(parents=True)
            manifest.write_text(
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                'android:sharedUserId="com.example.shared" '
                'android:sharedUserMaxSdkVersion="32" />',
                encoding="utf-8",
            )

            result = validate_supply_chain(root)

            self.assertTrue(any("shared Android UID rejected" in error for error in result.errors))
            self.assertTrue(
                any("shared Android UID compatibility" in error for error in result.errors)
            )

    def test_sdk_permission_and_dynamic_permission_nodes_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "app/src/main/AndroidManifest.xml"
            manifest.parent.mkdir(parents=True)
            manifest.write_text(
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
                '<uses-permission-sdk-23 android:name="android.permission.CAMERA" />'
                '<permission-tree android:name="com.example.dynamic" />'
                '<permission-group android:name="com.example.group" />'
                '</manifest>',
                encoding="utf-8",
            )

            result = validate_supply_chain(root)

            self.assertTrue(any("uses-permission-sdk-23" in error for error in result.errors))
            self.assertTrue(any("permission-tree" in error for error in result.errors))
            self.assertTrue(any("permission-group" in error for error in result.errors))

    def test_source_camera_permission_cannot_have_max_sdk(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "app/src/main/AndroidManifest.xml"
            manifest.parent.mkdir(parents=True)
            manifest.write_text(
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
                '<uses-permission android:name="android.permission.CAMERA" '
                'android:maxSdkVersion="32" />'
                '</manifest>',
                encoding="utf-8",
            )

            result = validate_supply_chain(root)

            self.assertTrue(any("noncanonical Android permission attributes" in error for error in result.errors))

    def test_forbidden_project_edge_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "settings.gradle.kts").write_text(
                '\n'.join(f'include(":{module}")' for module in ("app", "vision", "game-core", "games")),
                encoding="utf-8",
            )
            build_file = root / "games/build.gradle.kts"
            build_file.parent.mkdir(parents=True)
            build_file.write_text(
                'dependencies { implementation(project(":vision")) }',
                encoding="utf-8",
            )

            result = validate_supply_chain(root)

            self.assertIn("forbidden Gradle project edge: games -> vision", result.errors)

    def test_named_project_edge_cannot_bypass_policy(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "settings.gradle.kts").write_text(
                '\n'.join(f'include(":{module}")' for module in ("app", "vision", "game-core", "games")),
                encoding="utf-8",
            )
            build_file = root / "games/build.gradle.kts"
            build_file.parent.mkdir(parents=True)
            build_file.write_text(
                'dependencies { implementation(project(path = ":vision")) }',
                encoding="utf-8",
            )

            result = validate_supply_chain(root)

            self.assertIn("forbidden Gradle project edge: games -> vision", result.errors)

    def test_type_safe_project_edge_cannot_bypass_policy(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            build_file = root / "games/build.gradle.kts"
            build_file.parent.mkdir(parents=True)
            build_file.write_text(
                "dependencies { implementation(projects.vision) }", encoding="utf-8"
            )

            result = validate_supply_chain(root)

            self.assertIn("forbidden Gradle project edge: games -> vision", result.errors)

    def test_parenthesized_type_safe_project_accessor_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            build_file = root / "games/build.gradle.kts"
            build_file.parent.mkdir(parents=True)
            build_file.write_text(
                "dependencies { implementation((projects).vision) }", encoding="utf-8"
            )

            result = validate_supply_chain(root)

            self.assertTrue(any("noncanonical type-safe" in error for error in result.errors))

    def test_groovy_project_edge_cannot_bypass_policy(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            build_file = root / "games/build.gradle"
            build_file.parent.mkdir(parents=True)
            build_file.write_text(
                'dependencies { implementation project(path: ":vision") }\n',
                encoding="utf-8",
            )

            result = validate_supply_chain(root)

            self.assertIn("forbidden Gradle project edge: games -> vision", result.errors)

    def test_groovy_project_method_pointer_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            build_file = root / "games/build.gradle"
            build_file.parent.mkdir(parents=True)
            build_file.write_text(
                "dependencies {\n"
                "  def p = delegate.&project\n"
                "  implementation p(path: ':vision')\n"
                "}\n",
                encoding="utf-8",
            )

            result = validate_supply_chain(root)

            self.assertTrue(any("noncanonical Gradle project" in error for error in result.errors))

    def test_scoped_type_safe_accessor_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            build_file = root / "games/build.gradle.kts"
            build_file.parent.mkdir(parents=True)
            build_file.write_text(
                "dependencies { with(projects) { implementation(vision) } }\n",
                encoding="utf-8",
            )

            result = validate_supply_chain(root)

            self.assertTrue(any("noncanonical type-safe" in error for error in result.errors))

    def test_unrecognized_project_call_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            build_file = root / "games/build.gradle.kts"
            build_file.parent.mkdir(parents=True)
            build_file.write_text(
                'dependencies { implementation(project(projectPath)) }',
                encoding="utf-8",
            )

            result = validate_supply_chain(root)

            self.assertTrue(
                any("unrecognized Gradle project dependency syntax" in error for error in result.errors)
            )

    def test_required_lockfile_deletion_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            result = validate_supply_chain(Path(directory))

            self.assertIn(
                "required supply-chain file missing: app/gradle.lockfile",
                result.errors,
            )

    def test_debug_runtime_drift_from_audited_release_runtime_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lock_file = root / "app/gradle.lockfile"
            lock_file.parent.mkdir(parents=True)
            lock_file.write_text(
                "g:shared:1=debugRuntimeClasspath,releaseRuntimeClasspath\n"
                "g:debug-only:1=debugRuntimeClasspath\n",
                encoding="utf-8",
            )

            result = validate_supply_chain(root)

            self.assertTrue(
                any("debug/release runtime lock drift" in error for error in result.errors)
            )

    def test_approved_project_edges_have_no_edge_error(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "settings.gradle.kts").write_text(
                '\n'.join(f'include(":{module}")' for module in ("app", "vision", "game-core", "games")),
                encoding="utf-8",
            )
            edges = {
                "app": ("game-core", "games", "vision"),
                "vision": ("game-core",),
                "games": ("game-core",),
                "game-core": (),
            }
            for module, targets in edges.items():
                build_file = root / module / "build.gradle.kts"
                build_file.parent.mkdir(parents=True, exist_ok=True)
                dependencies = " ".join(
                    f'implementation(project(":{target}"))' for target in targets
                )
                build_file.write_text(f"dependencies {{ {dependencies} }}", encoding="utf-8")

            result = validate_supply_chain(root)

            self.assertFalse(any("Gradle project edge" in error for error in result.errors))

    def test_repository_policy_currently_passes(self) -> None:
        root = Path(__file__).resolve().parents[2]

        result = validate_supply_chain(root)

        self.assertEqual((), result.errors)


if __name__ == "__main__":
    unittest.main()
