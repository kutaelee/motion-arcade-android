#!/usr/bin/env python3
from __future__ import annotations

import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SUMS = ROOT / "SHA256SUMS"


def main() -> int:
    if not SUMS.exists():
        raise AssertionError("SHA256SUMS is missing")

    checked = 0
    for line_no, raw in enumerate(SUMS.read_text(encoding="utf-8").splitlines(), 1):
        if not raw.strip():
            continue
        try:
            expected, rel = raw.split("  ", 1)
        except ValueError as exc:
            raise AssertionError(f"invalid SHA256SUMS line {line_no}") from exc
        if rel == "SHA256SUMS":
            raise AssertionError("SHA256SUMS must not recursively include itself")
        path = ROOT / rel
        if not path.is_file():
            raise AssertionError(f"missing checksum target: {rel}")
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual != expected:
            raise AssertionError(f"checksum mismatch: {rel}")
        checked += 1

    actual_files = {
        str(p.relative_to(ROOT))
        for p in ROOT.rglob("*")
        if p.is_file() and p.name != "SHA256SUMS"
    }
    listed_files = {
        raw.split("  ", 1)[1]
        for raw in SUMS.read_text(encoding="utf-8").splitlines()
        if raw.strip()
    }
    missing = sorted(actual_files - listed_files)
    stale = sorted(listed_files - actual_files)
    if missing:
        raise AssertionError(f"files missing from SHA256SUMS: {missing}")
    if stale:
        raise AssertionError(f"stale SHA256SUMS entries: {stale}")

    print(f"OK SHA256SUMS: {checked} files verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
