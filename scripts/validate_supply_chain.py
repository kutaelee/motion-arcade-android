#!/usr/bin/env python3
"""Static fail-closed checks for the repository's G0 supply-chain policy."""

from __future__ import annotations

import argparse
import hashlib
import re
import sys
import tomllib
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path


EXPECTED_GRADLE_URL = r"https\://services.gradle.org/distributions/gradle-9.4.1-bin.zip"
EXPECTED_GRADLE_SHA256 = "2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb"
EXPECTED_WRAPPER_JAR_SHA256 = "55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c"
ALLOWED_PERMISSIONS = {"android.permission.CAMERA"}
ANDROID_NAME = "{http://schemas.android.com/apk/res/android}name"
ANDROID_PROCESS = "{http://schemas.android.com/apk/res/android}process"
ANDROID_ISOLATED_PROCESS = "{http://schemas.android.com/apk/res/android}isolatedProcess"
ANDROID_MULTIPROCESS = "{http://schemas.android.com/apk/res/android}multiprocess"
ANDROID_SHARED_USER_ID = "{http://schemas.android.com/apk/res/android}sharedUserId"
ANDROID_SHARED_USER_MAX_SDK = "{http://schemas.android.com/apk/res/android}sharedUserMaxSdkVersion"
TOOLS_NODE = "{http://schemas.android.com/tools}node"
QUOTED_RE = re.compile(r"(?P<quote>['\"])(?P<value>.*?)(?P=quote)")
ACTION_KEY_RE = re.compile(r"^\s*(?:-\s*)?(?:uses|'uses'|\"uses\")\s*:\s*(?P<value>.*?)\s*$")
PLAIN_WORKFLOW_KEY_RE = re.compile(
    r"^(?:-\s*)?(?P<key>[A-Za-z_][A-Za-z0-9_-]*)\s*:\s*(?P<value>.*)$"
)
BLOCK_SCALAR_RE = re.compile(r"^[>|](?:[+-]?[1-9]?|[1-9][+-]?)$")
FORBIDDEN_WORKFLOW_KEYS = {"container", "services", "image"}
GRADLE_RANGE_RE = re.compile(r"^[\[(\]].*,.*[\])\[]$")
PROJECT_CALL_RE = re.compile(r"\bproject\s*\((?P<arguments>[^()]*)\)")
PROJECT_POSITIONAL_RE = re.compile(
    r"\s*(?P<quote>['\"]):(?P<module>[A-Za-z0-9_.-]+)(?P=quote)\s*"
)
PROJECT_NAMED_RE = re.compile(
    r"\s*path\s*(?:=|:)\s*(?P<quote>['\"]):(?P<module>[A-Za-z0-9_.-]+)(?P=quote)\s*"
)
TYPE_SAFE_PROJECT_RE = re.compile(r"\bprojects\.(?P<accessor>[A-Za-z][A-Za-z0-9_.]*)")
TYPE_SAFE_PROJECTS = {
    "app": "app",
    "gameCore": "game-core",
    "games": "games",
    "vision": "vision",
}
EXPECTED_MODULES = {"app", "vision", "game-core", "games"}
EXPECTED_GRADLE_SCRIPTS = {
    "settings.gradle.kts": "5ef64f250c523cb15b25c880949b87329daaeddaf977a074ece1039f5fc3e823",
    "build.gradle.kts": "1e81836a73bf96ac362992bdf7adc8b182f484b804cc70b4c76ec93b3cb7e38c",
    "app/build.gradle.kts": "3a2263a51e6380de93167f0095e90eb1fb7345f355a22fc7387464cb8820c095",
    "vision/build.gradle.kts": "befd8fec1c060aac190d230f7291cc641fca8880270dbf0aafdac3fac8fb9970",
    "game-core/build.gradle.kts": "94fb468af03a57517c11f0c89d0b1771fa2e0f1d7871e8e2bf392f88fd721892",
    "games/build.gradle.kts": "413d75ac39a68163cc15d59abb3cc5faa5c309246125bdbfcc9b55f6479a1201",
}
EXPECTED_GRADLE_CONTROL_FILES = {
    "gradlew": "aed171fb114f82e6eaea4970a245a200e0582a7dcc8ec0891ca41b6e4a62b754",
    "gradlew.bat": "fedad02c18e266ec094995a5751b7fe1eb6e74f66bf75db64fae2e50eb22c234",
    "gradle.properties": "ebd91407b91bb1facd92a323d52afb38f8844ce85f5028eb0d97e1ed8706f09a",
    "gradle/wrapper/gradle-wrapper.properties": "032a71c92fc7f3c1cadf8880f0554b71aa6beb411f97ac253aa93932cb3784d5",
}
EXPECTED_LOCKFILES = (
    "settings-gradle.lockfile",
    "app/gradle.lockfile",
    "vision/gradle.lockfile",
    "game-core/gradle.lockfile",
    "games/gradle.lockfile",
)
ALLOWED_PROJECT_EDGES = {
    "app": {"game-core", "games", "vision"},
    "vision": {"game-core"},
    "games": {"game-core"},
    "game-core": set(),
}


@dataclass(frozen=True)
class SupplyChainResult:
    errors: tuple[str, ...]

    @property
    def ok(self) -> bool:
        return not self.errors


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _build_files(root: Path) -> list[Path]:
    candidates = set(root.glob("*.gradle")) | set(root.glob("*.gradle.kts"))
    for relative in ("gradle", "app", "vision", "game-core", "games"):
        subtree = root / relative
        if not subtree.is_dir():
            continue
        for pattern in ("*.gradle", "*.gradle.kts"):
            candidates.update(
                path
                for path in subtree.rglob(pattern)
                if "build" not in path.relative_to(subtree).parts
                and ".gradle" not in path.relative_to(subtree).parts
            )
    fixed = (root / "gradle/libs.versions.toml", root / "gradle/wrapper/gradle-wrapper.properties")
    candidates.update(path for path in fixed if path.is_file())
    return sorted(path for path in candidates if path.is_file())


def _gradle_structural_view(text: str) -> str:
    """Blank comments and strings while preserving offsets for DSL token checks."""
    output = list(text)
    index = 0
    state = "code"
    quote = ""
    while index < len(text):
        if state == "line_comment":
            if text[index] == "\n":
                state = "code"
            else:
                output[index] = " "
            index += 1
            continue
        if state == "block_comment":
            output[index] = " "
            if text.startswith("*/", index):
                if index + 1 < len(text):
                    output[index + 1] = " "
                index += 2
                state = "code"
            else:
                index += 1
            continue
        if state == "string":
            if text[index] != "\n":
                output[index] = " "
            if text.startswith(quote, index):
                for position in range(index, min(index + len(quote), len(text))):
                    if text[position] != "\n":
                        output[position] = " "
                index += len(quote)
                state = "code"
                continue
            if len(quote) == 1 and text[index] == "\\" and index + 1 < len(text):
                if text[index + 1] != "\n":
                    output[index + 1] = " "
                index += 2
                continue
            index += 1
            continue
        if text.startswith("//", index):
            output[index : index + 2] = [" ", " "]
            index += 2
            state = "line_comment"
            continue
        if text.startswith("/*", index):
            output[index : index + 2] = [" ", " "]
            index += 2
            state = "block_comment"
            continue
        if text.startswith(('"""', "'''"), index):
            quote = text[index : index + 3]
            output[index : index + 3] = [" ", " ", " "]
            index += 3
            state = "string"
            continue
        if text[index] in "'\"":
            quote = text[index]
            output[index] = " "
            index += 1
            state = "string"
            continue
        index += 1
    return "".join(output)


def _github_expression_end(line: str, start: int) -> int | None:
    index = start + 3
    in_string = False
    while index < len(line):
        if in_string:
            if line[index] == "'" and index + 1 < len(line) and line[index + 1] == "'":
                index += 2
                continue
            if line[index] == "'":
                in_string = False
            index += 1
            continue
        if line[index] == "'":
            in_string = True
            index += 1
            continue
        if line.startswith("}}", index):
            return index + 2
        index += 1
    return None


def _unquoted_yaml_view(line: str) -> str:
    """Return same-width non-comment YAML text with strings/expressions blanked."""
    output = list(line)
    index = 0
    quote: str | None = None
    while index < len(line):
        char = line[index]
        if quote == "'":
            output[index] = " "
            if char == "'" and index + 1 < len(line) and line[index + 1] == "'":
                output[index + 1] = " "
                index += 2
                continue
            if char == "'":
                quote = None
            index += 1
            continue
        if quote == '"':
            output[index] = " "
            if char == "\\" and index + 1 < len(line):
                output[index + 1] = " "
                index += 2
                continue
            if char == '"':
                quote = None
            index += 1
            continue
        if line.startswith("${{", index):
            expression_end = _github_expression_end(line, index)
            expression_end = len(line) if expression_end is None else expression_end
            for position in range(index, expression_end):
                output[position] = " "
            index = expression_end
            continue
        if char in "'\"":
            output[index] = " "
            quote = char
            index += 1
            continue
        if char == "#" and (index == 0 or line[index - 1].isspace()):
            for position in range(index, len(line)):
                output[position] = " "
            break
        index += 1
    return "".join(output)


def _yaml_inline_construct_error(line: str) -> str | None:
    index = 0
    while index < len(line):
        if line.startswith("${{", index):
            expression_end = _github_expression_end(line, index)
            if expression_end is None:
                return "has an unterminated GitHub expression"
            index = expression_end
        else:
            index += 1
    return None


def _quoted_scalar_tail_error(value: str) -> str | None:
    stripped = value.strip()
    if not stripped or stripped[0] not in "'\"":
        return None
    quote = stripped[0]
    index = 1
    while index < len(stripped):
        char = stripped[index]
        if quote == "'" and char == "'" and index + 1 < len(stripped) and stripped[index + 1] == "'":
            index += 2
            continue
        if quote == '"' and char == "\\" and index + 1 < len(stripped):
            escaped = stripped[index + 1]
            if escaped in "0abtnvfre \"/\\N_LP":
                index += 2
                continue
            required = {"x": 2, "u": 4, "U": 8}.get(escaped)
            if required is None:
                return "contains an invalid double-quoted YAML escape"
            digits = stripped[index + 2 : index + 2 + required]
            if len(digits) != required or re.fullmatch(r"[0-9A-Fa-f]+", digits) is None:
                return "contains an invalid double-quoted YAML hex escape"
            index += required + 2
            continue
        if char == quote:
            tail = stripped[index + 1 :].strip()
            if tail and not tail.startswith("#"):
                return "has trailing content after a quoted scalar"
            return None
        index += 1
    return "has an unterminated quoted scalar"


def _plain_scalar_error(value: str) -> str | None:
    stripped = value.strip()
    if not stripped or stripped[0] in "'\"":
        return None
    structural = _unquoted_yaml_view(stripped).strip()
    if not structural or BLOCK_SCALAR_RE.fullmatch(structural):
        return None
    if structural.startswith(("{", "[")):
        return None
    if structural[0] in ",]}@`%" or structural.startswith(("|", ">")):
        return "starts with a forbidden plain-scalar indicator"
    if re.match(r"^[-?:](?:\s|$)", structural) or re.search(r":\s", structural):
        return "contains forbidden plain-scalar mapping syntax"
    return None


def _workflow_action_specs(text: str) -> tuple[tuple[str, str] | tuple[None, str], ...]:
    specs: list[tuple[str, str] | tuple[None, str]] = []
    block_scalar_parent_indent: int | None = None
    previous_indent = 0
    seen_indents = {0}
    scope_serial = 0
    scope_ids: dict[int, int] = {}
    seen_mapping_keys: dict[tuple[int, tuple[tuple[int, int], ...]], set[str]] = {}
    for line_number, line in enumerate(text.splitlines(), start=1):
        if not line.strip():
            continue
        leading = re.match(r"^[ \t]*", line).group(0)
        indent = len(leading)
        if block_scalar_parent_indent is not None:
            if indent > block_scalar_parent_indent:
                continue
            block_scalar_parent_indent = None
        if "\t" in leading:
            specs.append((None, f"line {line_number} uses tab indentation"))
            continue
        if indent % 2 != 0:
            specs.append((None, f"line {line_number} uses noncanonical odd indentation"))
            continue
        stripped = line[indent:]
        if stripped.startswith("#"):
            continue
        if indent > previous_indent + 2 or (
            indent < previous_indent and indent not in seen_indents
        ):
            specs.append((None, f"line {line_number} uses inconsistent canonical indentation"))
            continue
        previous_indent = indent
        seen_indents.add(indent)
        is_sequence_item = stripped == "-" or stripped.startswith("- ")
        logical_indent = indent + 2 if is_sequence_item else indent
        scope_cutoff = indent if is_sequence_item else logical_indent
        for level in tuple(scope_ids):
            if level >= scope_cutoff:
                del scope_ids[level]
        if is_sequence_item:
            scope_serial += 1
            scope_ids[indent] = scope_serial
        inline_error = _yaml_inline_construct_error(stripped)
        if inline_error is not None:
            specs.append((None, f"line {line_number} {inline_error}"))
            continue
        node = stripped[2:].lstrip() if stripped.startswith("- ") else stripped
        structural = _unquoted_yaml_view(stripped)
        structural_node = structural[2:].lstrip() if structural.startswith("- ") else structural
        if node.startswith(("'", '"')):
            specs.append(
                (
                    None,
                    f"line {line_number} uses a quoted YAML node/key outside the canonical subset",
                )
            )
            continue
        canonical_node = structural_node.lstrip()
        if canonical_node.startswith(("%", "---", "...")):
            specs.append((None, f"line {line_number} uses a YAML directive/document marker"))
            continue
        if canonical_node.startswith("!"):
            specs.append((None, f"line {line_number} uses a forbidden YAML tag"))
            continue
        if canonical_node.startswith(("&", "*")):
            specs.append((None, f"line {line_number} uses a forbidden YAML anchor or alias"))
            continue
        if canonical_node.startswith(("{", "[")):
            specs.append((None, f"line {line_number} uses a forbidden YAML flow collection"))
            continue
        if structural_node.startswith("?") or re.match(r"^<<\s*:", canonical_node):
            specs.append((None, f"line {line_number} uses an explicit/merge YAML key"))
            continue
        mapping = PLAIN_WORKFLOW_KEY_RE.match(stripped)
        if mapping is not None:
            scalar_tail_error = _quoted_scalar_tail_error(mapping.group("value"))
            if scalar_tail_error is not None:
                specs.append((None, f"line {line_number} {scalar_tail_error}"))
                continue
            plain_scalar_error = _plain_scalar_error(mapping.group("value"))
            if plain_scalar_error is not None:
                specs.append((None, f"line {line_number} {plain_scalar_error}"))
                continue
            scope = tuple(sorted((level, identity) for level, identity in scope_ids.items() if level < logical_indent))
            key_scope = (logical_indent, scope)
            keys = seen_mapping_keys.setdefault(key_scope, set())
            if mapping.group("key") in keys:
                specs.append(
                    (None, f"line {line_number} duplicates a canonical mapping key: {mapping.group('key')}")
                )
                continue
            keys.add(mapping.group("key"))
            if mapping.group("key") in FORBIDDEN_WORKFLOW_KEYS:
                specs.append(
                    (None, f"line {line_number} uses a forbidden external container key: {mapping.group('key')}")
                )
                continue
            scalar_indicator = _unquoted_yaml_view(mapping.group("value")).strip()
            if scalar_indicator.startswith("!"):
                specs.append((None, f"line {line_number} uses a forbidden YAML tag"))
                continue
            if scalar_indicator.startswith(("&", "*")):
                specs.append((None, f"line {line_number} uses a forbidden YAML anchor or alias"))
                continue
            if scalar_indicator.startswith(("{", "[")):
                specs.append((None, f"line {line_number} uses a forbidden YAML flow collection"))
                continue
            if BLOCK_SCALAR_RE.fullmatch(scalar_indicator):
                block_scalar_parent_indent = indent
            elif not scalar_indicator:
                scope_serial += 1
                scope_ids[logical_indent] = scope_serial
            if mapping.group("key") != "uses":
                continue
        elif stripped != "-" and not stripped.startswith("- "):
            specs.append((None, f"line {line_number} is outside the canonical YAML subset"))
            continue
        elif canonical_node.startswith((":", "?")) or re.search(r":\s", canonical_node):
            specs.append((None, f"line {line_number} is an invalid canonical sequence scalar"))
            continue
        match = ACTION_KEY_RE.match(stripped)
        if match is None:
            continue
        raw = match.group("value").strip()
        if not raw:
            specs.append((None, f"line {line_number} has empty uses value"))
            continue
        if raw[0] in "'\"":
            quote = raw[0]
            closing = raw.find(quote, 1)
            tail = raw[closing + 1 :].strip() if closing >= 0 else ""
            if closing < 0 or (tail and not tail.startswith("#")):
                specs.append((None, f"line {line_number} has malformed quoted uses value"))
                continue
            raw = raw[1:closing]
        else:
            raw = re.split(r"\s+#", raw, maxsplit=1)[0].strip()
        if raw.startswith("./"):
            specs.append((raw, ""))
            continue
        if raw.startswith("docker://") or raw.count("@") != 1:
            specs.append((None, f"line {line_number} has unsupported or unpinned uses value: {raw}"))
            continue
        action, ref = raw.rsplit("@", 1)
        if not action or not ref:
            specs.append((None, f"line {line_number} has malformed uses value: {raw}"))
            continue
        specs.append((action, ref))
    return tuple(specs)


def _is_dynamic_token(value: str) -> bool:
    lowered = value.lower()
    if "latest." in lowered or "snapshot" in lowered:
        return True
    stripped = value.strip()
    candidates: list[str] = []
    for part in (stripped, *(item.strip() for item in stripped.split(":"))):
        candidates.append(part)
        candidates.append(part.split("@", 1)[0].split("!!", 1)[0].strip())
    if any(GRADLE_RANGE_RE.fullmatch(candidate) for candidate in candidates):
        return True
    return any(
        "+" in candidate and bool(re.search(r"\d|^\+$", candidate))
        for candidate in candidates
    )


def _project_target(arguments: str) -> str | None:
    for pattern in (PROJECT_POSITIONAL_RE, PROJECT_NAMED_RE):
        match = pattern.fullmatch(arguments)
        if match is not None:
            return match.group("module")
    return None


def _locked_coordinates(path: Path, configuration: str) -> frozenset[str]:
    coordinates: set[str] = set()
    target_seen = False
    explicit_empty = False
    for line_number, raw_line in enumerate(
        path.read_text(encoding="utf-8").splitlines(), start=1
    ):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise ValueError(f"line {line_number} has no '='")
        coordinate, raw_configurations = line.split("=", 1)
        configurations = raw_configurations.split(",")
        if (
            not coordinate
            or not raw_configurations
            or any(not item or item.strip() != item for item in configurations)
        ):
            raise ValueError(f"line {line_number} is malformed")
        if configuration not in configurations:
            continue
        target_seen = True
        if coordinate == "empty":
            explicit_empty = True
            continue
        if coordinate in coordinates:
            raise ValueError(
                f"line {line_number} duplicates {coordinate!r} for {configuration}"
            )
        coordinates.add(coordinate)
    if not target_seen:
        raise ValueError(f"configuration {configuration!r} is absent")
    if explicit_empty and coordinates:
        raise ValueError(f"configuration {configuration!r} is both empty and populated")
    if explicit_empty or not coordinates:
        raise ValueError(f"configuration {configuration!r} has no locked coordinates")
    return frozenset(coordinates)


def _decoded_dynamic_values(value: object, path: str = "") -> tuple[str, ...]:
    errors: list[str] = []
    if isinstance(value, str):
        if _is_dynamic_token(value):
            errors.append(f"{path or '<root>'}={value}")
    elif isinstance(value, dict):
        for key, child in value.items():
            errors.extend(_decoded_dynamic_values(child, f"{path}.{key}" if path else str(key)))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            errors.extend(_decoded_dynamic_values(child, f"{path}[{index}]"))
    return tuple(errors)


def validate_supply_chain(root: Path) -> SupplyChainResult:
    root = root.resolve()
    errors: list[str] = []

    wrapper_properties = root / "gradle/wrapper/gradle-wrapper.properties"
    wrapper_jar = root / "gradle/wrapper/gradle-wrapper.jar"
    verification = root / "gradle/verification-metadata.xml"
    required_paths = (
        root / "gradlew",
        root / "gradlew.bat",
        root / "gradle.properties",
        wrapper_properties,
        wrapper_jar,
        verification,
        root / "gradle/libs.versions.toml",
        *(root / relative for relative in EXPECTED_LOCKFILES),
    )
    for path in required_paths:
        if not path.is_file():
            errors.append(f"required supply-chain file missing: {path.relative_to(root).as_posix()}")

    actual_control_files: dict[str, Path] = {}
    control_candidates = set(root.glob("gradlew*")) | set(root.rglob("*gradle*.properties"))
    for path in control_candidates:
        if not path.is_file():
            continue
        relative_parts = path.relative_to(root).parts
        if any(
            part in {".git", ".gradle", "_working", "build", "node_modules"}
            for part in relative_parts
        ):
            continue
        if relative_parts and relative_parts[0].startswith("android-motion-games-design-"):
            continue
        actual_control_files[path.relative_to(root).as_posix()] = path
    expected_control_names = set(EXPECTED_GRADLE_CONTROL_FILES)
    actual_control_names = set(actual_control_files)
    if actual_control_names != expected_control_names:
        errors.append(
            "Gradle launcher/control inventory differs from the approved set: "
            f"missing={sorted(expected_control_names - actual_control_names) or 'none'} "
            f"unexpected={sorted(actual_control_names - expected_control_names) or 'none'}"
        )
    for relative, expected_hash in EXPECTED_GRADLE_CONTROL_FILES.items():
        path = actual_control_files.get(relative)
        if path is not None and _sha256(path) != expected_hash:
            errors.append(f"Gradle launcher/control hash differs from approved bytes: {relative}")

    if wrapper_properties.is_file():
        properties = {}
        for line in wrapper_properties.read_text(encoding="utf-8").splitlines():
            if "=" in line:
                key, value = line.split("=", 1)
                properties[key] = value
        if properties.get("distributionUrl") != EXPECTED_GRADLE_URL:
            errors.append("Gradle distribution URL is not the approved exact 9.4.1 binary URL")
        if properties.get("distributionSha256Sum") != EXPECTED_GRADLE_SHA256:
            errors.append("Gradle distribution SHA-256 is missing or unexpected")
        if properties.get("validateDistributionUrl") != "true":
            errors.append("Gradle distribution URL validation must be enabled")
    if wrapper_jar.is_file() and _sha256(wrapper_jar) != EXPECTED_WRAPPER_JAR_SHA256:
        errors.append("Gradle wrapper JAR SHA-256 does not match the official 9.4.1 wrapper")

    if verification.is_file():
        try:
            raw_verification = verification.read_bytes()
            if b"<!DOCTYPE" in raw_verification.upper() or b"<!ENTITY" in raw_verification.upper():
                raise ValueError("DOCTYPE/entity declarations are forbidden")
            tree = ET.parse(verification)
            root_node = tree.getroot()
            namespace_uri = "https://schema.gradle.org/dependency-verification"
            namespace = f"{{{namespace_uri}}}"
            schema_attribute = "{http://www.w3.org/2001/XMLSchema-instance}schemaLocation"
            expected_schema = (
                f"{namespace_uri} {namespace_uri}/dependency-verification-1.3.xsd"
            )
            if root_node.tag != f"{namespace}verification-metadata":
                raise ValueError("unexpected dependency-verification root element")
            if root_node.attrib != {schema_attribute: expected_schema}:
                raise ValueError("dependency-verification root attributes differ from policy")
            if [child.tag for child in root_node] != [
                f"{namespace}configuration",
                f"{namespace}components",
            ]:
                raise ValueError("dependency-verification root children differ from policy")
            configuration, components_node = list(root_node)
            if configuration.attrib or [child.tag for child in configuration] != [
                f"{namespace}verify-metadata",
                f"{namespace}verify-signatures",
            ]:
                raise ValueError("dependency-verification configuration is not canonical")
            if any(child.attrib or len(child) for child in configuration):
                raise ValueError("dependency-verification flags must be scalar elements")
            if configuration[0].text != "true" or configuration[1].text != "false":
                raise ValueError("dependency-verification flags must be metadata=true/signatures=false")
            if components_node.attrib or not len(components_node):
                raise ValueError("dependency-verification components are missing or attributed")
            seen_components: set[tuple[str, str, str]] = set()
            for component in components_node:
                if component.tag != f"{namespace}component" or set(component.attrib) != {
                    "group",
                    "name",
                    "version",
                }:
                    raise ValueError("dependency-verification component is malformed")
                coordinate = tuple(component.attrib[key] for key in ("group", "name", "version"))
                if any(not part for part in coordinate) or coordinate in seen_components:
                    raise ValueError("dependency-verification component is empty or duplicated")
                seen_components.add(coordinate)
                if _is_dynamic_token(coordinate[2]):
                    raise ValueError(f"dynamic dependency-verification component: {coordinate}")
                if not len(component):
                    raise ValueError(f"dependency-verification component has no artifacts: {coordinate}")
                seen_artifacts: set[str] = set()
                for artifact in component:
                    if artifact.tag != f"{namespace}artifact" or set(artifact.attrib) != {"name"}:
                        raise ValueError("dependency-verification artifact is malformed")
                    artifact_name = artifact.attrib["name"]
                    if not artifact_name or artifact_name in seen_artifacts or len(artifact) != 1:
                        raise ValueError("dependency-verification artifact is empty, duplicate, or ambiguous")
                    seen_artifacts.add(artifact_name)
                    checksum = artifact[0]
                    if checksum.tag != f"{namespace}sha256" or set(checksum.attrib) != {
                        "value",
                        "origin",
                    }:
                        raise ValueError("dependency-verification artifact must have sha256 only")
                    if (
                        re.fullmatch(r"[a-f0-9]{64}", checksum.attrib["value"]) is None
                        or not checksum.attrib["origin"]
                        or len(checksum)
                    ):
                        raise ValueError("dependency-verification sha256 entry is malformed")
        except (ET.ParseError, OSError, ValueError) as exc:
            errors.append(f"dependency verification XML is invalid: {exc}")

    catalog = root / "gradle/libs.versions.toml"
    if catalog.is_file():
        try:
            decoded_catalog = tomllib.loads(catalog.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, tomllib.TOMLDecodeError) as exc:
            errors.append(f"version catalog TOML is invalid: {exc}")
        else:
            for dynamic_value in _decoded_dynamic_values(decoded_catalog):
                errors.append(f"decoded dynamic/ranged version rejected in version catalog: {dynamic_value}")

    if (root / "buildSrc").exists():
        errors.append("buildSrc convention code is outside the approved Gradle script inventory")

    build_files = _build_files(root)
    actual_gradle_scripts = {
        path.relative_to(root).as_posix(): path
        for path in build_files
        if path.name.endswith((".gradle", ".gradle.kts"))
    }
    expected_script_names = set(EXPECTED_GRADLE_SCRIPTS)
    actual_script_names = set(actual_gradle_scripts)
    if actual_script_names != expected_script_names:
        errors.append(
            "Gradle script inventory differs from the approved executable set: "
            f"missing={sorted(expected_script_names - actual_script_names) or 'none'} "
            f"unexpected={sorted(actual_script_names - expected_script_names) or 'none'}"
        )
    for relative, expected_hash in EXPECTED_GRADLE_SCRIPTS.items():
        path = actual_gradle_scripts.get(relative)
        if path is not None and _sha256(path) != expected_hash:
            errors.append(f"Gradle script hash differs from the approved inventory: {relative}")

    if (root / "settings.gradle").exists():
        errors.append("alternate Groovy settings.gradle would override canonical settings.gradle.kts")

    for path in build_files:
        text = path.read_text(encoding="utf-8")
        relative = path.relative_to(root).as_posix()
        structural = _gradle_structural_view(text)
        if re.search(r"\bmavenLocal\s*\(", text):
            errors.append(f"mavenLocal repository rejected: {relative}")
        if re.search(r"\bflatDir\s*\{", text):
            errors.append(f"flatDir repository rejected: {relative}")
        if "http://" in text:
            errors.append(f"insecure HTTP repository/string rejected: {relative}")
        apply_tokens = list(re.finditer(r"\bapply\b", structural))
        allowed_apply_tokens = {
            match.start()
            for match in re.finditer(r"\bapply\s+false\b", structural)
        }
        if any(match.start() not in allowed_apply_tokens for match in apply_tokens):
            errors.append(f"Gradle apply DSL is outside the approved inventory: {relative}")
        if re.search(r"\bincludeBuild\b", structural):
            errors.append(f"included Gradle build is outside the approved architecture: {relative}")
        if "`" in structural:
            errors.append(f"backtick Gradle identifiers are outside the canonical subset: {relative}")
        for match in QUOTED_RE.finditer(text):
            if _is_dynamic_token(match.group("value")):
                errors.append(f"dynamic/ranged version rejected in {relative}: {match.group('value')}")

    workflow_root = root / ".github/workflows"
    if workflow_root.exists():
        if workflow_root.is_symlink():
            errors.append("GitHub workflow directory must not be a symlink")
        pending_workflows = list(sorted(workflow_root.glob("*.y*ml")))
        visited_workflows: set[Path] = set()
        while pending_workflows:
            path = pending_workflows.pop(0)
            try:
                resolved_path = path.resolve(strict=True)
                resolved_path.relative_to(root)
            except (FileNotFoundError, OSError, ValueError):
                errors.append(f"workflow/action file is missing or outside the repository: {path}")
                continue
            if path.is_symlink():
                errors.append(
                    f"workflow/action file must not be a symlink: "
                    f"{path.relative_to(root).as_posix()}"
                )
                continue
            if resolved_path in visited_workflows:
                continue
            visited_workflows.add(resolved_path)
            text = path.read_text(encoding="utf-8")
            for action, ref_or_error in _workflow_action_specs(text):
                if action is None:
                    errors.append(
                        f"invalid GitHub Action uses entry in "
                        f"{path.relative_to(root).as_posix()}: {ref_or_error}"
                    )
                    continue
                if action.startswith("./"):
                    errors.append(
                        f"local GitHub Action/workflow references are outside the approved "
                        f"inventory in {path.relative_to(root).as_posix()}: {action}"
                    )
                    continue
                if not re.fullmatch(r"[a-f0-9]{40}", ref_or_error):
                    errors.append(
                        f"GitHub Action is not pinned to a full commit SHA in "
                        f"{path.relative_to(root).as_posix()}: {action}@{ref_or_error}"
                    )

    manifest_paths = sorted(
        path
        for module in ("app", "vision", "game-core", "games")
        for path in (root / module / "src").glob("*/AndroidManifest.xml")
    )
    for path in manifest_paths:
        try:
            manifest = ET.parse(path).getroot()
        except ET.ParseError as exc:
            errors.append(f"invalid Android manifest {path.relative_to(root).as_posix()}: {exc}")
            continue
        for element in manifest.iter():
            element_name = element.tag.rsplit("}", 1)[-1]
            if element.get(ANDROID_PROCESS) is not None:
                errors.append(
                    f"app multiprocess component rejected in "
                    f"{path.relative_to(root).as_posix()}: android:process"
                )
            if element.get(ANDROID_ISOLATED_PROCESS) is not None:
                errors.append(
                    f"isolated app process rejected in "
                    f"{path.relative_to(root).as_posix()}: android:isolatedProcess"
                )
            if element.get(ANDROID_MULTIPROCESS) is not None:
                errors.append(
                    f"legacy multiprocess provider rejected in "
                    f"{path.relative_to(root).as_posix()}: android:multiprocess"
                )
            if element.get(ANDROID_SHARED_USER_ID) is not None:
                errors.append(
                    f"shared Android UID rejected in "
                    f"{path.relative_to(root).as_posix()}: android:sharedUserId"
                )
            if element.get(ANDROID_SHARED_USER_MAX_SDK) is not None:
                errors.append(
                    f"shared Android UID compatibility attribute rejected in "
                    f"{path.relative_to(root).as_posix()}: android:sharedUserMaxSdkVersion"
                )
            if element_name in {"permission-tree", "permission-group"}:
                errors.append(
                    f"dynamic/group permission declaration rejected in "
                    f"{path.relative_to(root).as_posix()}: {element_name}"
                )
            if not element_name.startswith("uses-permission"):
                continue
            if re.fullmatch(r"uses-permission(?:-sdk-(?:[0-9]+|m))?", element_name) is None:
                errors.append(
                    f"unsupported uses-permission element in "
                    f"{path.relative_to(root).as_posix()}: {element_name}"
                )
                continue
            if element.get(TOOLS_NODE) == "remove":
                continue
            if set(element.attrib) != {ANDROID_NAME}:
                errors.append(
                    f"noncanonical Android permission attributes in "
                    f"{path.relative_to(root).as_posix()}: {element_name}"
                )
                continue
            permission = element.get(ANDROID_NAME)
            if permission is None:
                errors.append(f"unnamed {element_name} in {path.relative_to(root).as_posix()}")
                continue
            if element_name != "uses-permission" or permission not in ALLOWED_PERMISSIONS:
                errors.append(
                    f"unapproved Android permission in {path.relative_to(root).as_posix()}: "
                    f"{element_name} {permission}"
                )

    forbidden_imports = {
        "game-core": ("androidx.camera", "com.google.mediapipe", "androidx.compose"),
        "games": ("androidx.camera", "com.google.mediapipe", "androidx.compose"),
    }
    for module, prefixes in forbidden_imports.items():
        for path in sorted((root / module / "src").rglob("*.kt")):
            text = path.read_text(encoding="utf-8")
            for prefix in prefixes:
                if re.search(rf"^\s*import\s+{re.escape(prefix)}(?:\.|$)", text, re.MULTILINE):
                    errors.append(
                        f"forbidden dependency import in {path.relative_to(root).as_posix()}: {prefix}"
                    )

    settings = root / "settings.gradle.kts"
    if not settings.is_file():
        errors.append("required canonical settings file missing: settings.gradle.kts")
    else:
        declared_modules = set(
            re.findall(r"\binclude\s*\(\s*['\"]:([A-Za-z0-9_.-]+)['\"]\s*\)", settings.read_text(encoding="utf-8"))
        )
        if declared_modules != EXPECTED_MODULES:
            errors.append(
                "Gradle module set differs from the approved architecture: "
                f"expected={sorted(EXPECTED_MODULES)} actual={sorted(declared_modules)}"
            )

    for module, allowed_targets in ALLOWED_PROJECT_EDGES.items():
        for build_file in (root / module / "build.gradle.kts", root / module / "build.gradle"):
            if not build_file.is_file():
                continue
            text = build_file.read_text(encoding="utf-8")
            structural = _gradle_structural_view(text)
            noncanonical_lookup = re.search(
                r"\b(?:findProject|rootProject|childProjects|allprojects|subprojects)\b",
                structural,
            )
            if noncanonical_lookup is not None:
                errors.append(
                    f"noncanonical Gradle project lookup in "
                    f"{build_file.relative_to(root).as_posix()}: "
                    f"{noncanonical_lookup.group(0)}"
                )
            project_calls = list(PROJECT_CALL_RE.finditer(structural))
            project_call_starts = {match.start() for match in project_calls}
            for token in re.finditer(r"\bproject\b", structural):
                previous = structural[: token.start()].rstrip()[-1:]
                if token.start() not in project_call_starts or previous in {".", "&", "`"}:
                    errors.append(
                        f"noncanonical Gradle project dependency access in "
                        f"{build_file.relative_to(root).as_posix()}"
                    )
            for match in project_calls:
                arguments = text[match.start("arguments") : match.end("arguments")]
                target = _project_target(arguments)
                if target is None:
                    errors.append(
                        f"unrecognized Gradle project dependency syntax in "
                        f"{build_file.relative_to(root).as_posix()}: project({arguments})"
                    )
                    continue
                if target not in allowed_targets:
                    errors.append(f"forbidden Gradle project edge: {module} -> {target}")
            type_safe_matches = list(TYPE_SAFE_PROJECT_RE.finditer(structural))
            type_safe_starts = {match.start() for match in type_safe_matches}
            for token in re.finditer(r"\bprojects\b", structural):
                previous = structural[: token.start()].rstrip()[-1:]
                if token.start() not in type_safe_starts or previous in {".", ")", "`"}:
                    errors.append(
                        f"noncanonical type-safe Gradle project accessor in "
                        f"{build_file.relative_to(root).as_posix()}"
                    )
            for match in type_safe_matches:
                accessor = match.group("accessor")
                target = TYPE_SAFE_PROJECTS.get(accessor)
                if target is None:
                    errors.append(
                        f"unrecognized type-safe Gradle project accessor in "
                        f"{build_file.relative_to(root).as_posix()}: projects.{accessor}"
                    )
                    continue
                if target not in allowed_targets:
                    errors.append(f"forbidden Gradle project edge: {module} -> {target}")

    app_lock = root / "app/gradle.lockfile"
    if app_lock.is_file():
        locked_by_configuration: dict[str, frozenset[str]] = {}
        for configuration in ("releaseRuntimeClasspath", "debugRuntimeClasspath"):
            try:
                locked_by_configuration[configuration] = _locked_coordinates(
                    app_lock, configuration
                )
            except (OSError, UnicodeError, ValueError) as exc:
                errors.append(
                    f"cannot validate app lock {configuration}: {exc}"
                )
        if len(locked_by_configuration) == 2:
            release_coordinates = locked_by_configuration["releaseRuntimeClasspath"]
            debug_coordinates = locked_by_configuration["debugRuntimeClasspath"]
            if release_coordinates != debug_coordinates:
                debug_only = sorted(debug_coordinates - release_coordinates)
                release_only = sorted(release_coordinates - debug_coordinates)
                errors.append(
                    "debug/release runtime lock drift would invalidate the APK license audit: "
                    f"debugOnly={debug_only or 'none'} releaseOnly={release_only or 'none'}"
                )

    return SupplyChainResult(tuple(errors))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    result = validate_supply_chain(args.root)
    if result.ok:
        print("SUPPLY_CHAIN_STATIC_POLICY=PASS")
        return 0
    for error in result.errors:
        print(f"ERROR: {error}", file=sys.stderr)
    print(f"SUPPLY_CHAIN_STATIC_POLICY=FAIL errors={len(result.errors)}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
