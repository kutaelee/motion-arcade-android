#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = [
    ROOT / 'contracts/motion-event.schema.json',
    ROOT / 'contracts/pose-frame.schema.json',
    ROOT / 'contracts/calibration-profile.schema.json',
    ROOT / 'contracts/session-snapshot.schema.json',
    ROOT / 'contracts/diagnostic-bundle.schema.json',
    ROOT / 'assets/asset-manifest.schema.json',
]

def main() -> int:
    for path in SCHEMAS:
        data = json.loads(path.read_text(encoding='utf-8'))
        Draft202012Validator.check_schema(data)
        print(f'OK schema: {path.relative_to(ROOT)}')

    manifest_schema = json.loads((ROOT / 'assets/asset-manifest.schema.json').read_text(encoding='utf-8'))
    example = json.loads((ROOT / 'assets/asset-manifest.example.json').read_text(encoding='utf-8'))
    Draft202012Validator(manifest_schema).validate(example)
    print('OK example: assets/asset-manifest.example.json')
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
