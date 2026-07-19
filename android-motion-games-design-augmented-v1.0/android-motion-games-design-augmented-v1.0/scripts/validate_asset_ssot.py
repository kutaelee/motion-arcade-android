#!/usr/bin/env python3
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = ROOT / 'assets/imagegen-prompt-registry.json'
STYLE_DIR = ROOT / 'assets/style-profiles'
SKILL = ROOT / 'skills/imagegen-asset-production/SKILL.md'

GAME_STYLE = {
    'COMMON': 'common-hub-v1',
    'FISHING': 'fishing-v1',
    'BOXING': 'boxing-v1',
    'MONSTER': 'monster-v1',
}

def fail(message: str) -> None:
    raise AssertionError(message)

def main() -> int:
    data = json.loads(REGISTRY.read_text(encoding='utf-8'))
    policy = data['policy']
    if policy['generationMethod'] != 'IMAGEGEN_SKILL':
        fail('generationMethod must be IMAGEGEN_SKILL')
    if not SKILL.exists():
        fail('ImageGen SSOT skill is missing')

    for key in [
        'oneOffPromptsForbidden',
        'externalArtMixingForbidden',
        'manualMasterPaintingForbidden',
        'uiTextInImagesForbidden',
        'substantiveEditsRequireImageGenRevision',
    ]:
        if policy.get(key) is not True:
            fail(f'policy {key} must be true')

    templates = {t['templateId']: t for t in data['templates']}
    if len(templates) != len(data['templates']):
        fail('duplicate templateId')
    for t in templates.values():
        if 'no text' not in t['promptPattern'].lower():
            fail(f"template {t['templateId']} must explicitly forbid text")

    known_profiles = set(policy['approvedStyleProfiles'])
    for profile in known_profiles:
        path = STYLE_DIR / f'{profile}.md'
        if not path.exists():
            fail(f'missing style profile file: {profile}')
        if f'profileId: `{profile}`' not in path.read_text(encoding='utf-8'):
            fail(f'profileId mismatch in {path.name}')

    assets = data['assets']
    ids = {a['assetId'] for a in assets}
    if len(ids) != len(assets):
        fail('duplicate assetId')
    anchors = {a['assetId'] for a in assets if a['assetFamily'] == 'ANCHOR'}

    for a in assets:
        aid = a['assetId']
        if a['generationMethod'] != 'IMAGEGEN_SKILL':
            fail(f'{aid}: invalid generation method')
        expected_style = GAME_STYLE[a['gameId']]
        if a['styleProfileId'] != expected_style:
            fail(f'{aid}: game/style mismatch')
        if a['styleProfileId'] not in known_profiles:
            fail(f'{aid}: unknown style profile')
        if a['templateId'] not in templates:
            fail(f'{aid}: unknown template')
        for ref in a['referenceAssetIds']:
            if ref not in ids:
                fail(f'{aid}: unknown reference {ref}')
        if a['assetFamily'] != 'ANCHOR' and not any(ref in anchors for ref in a['referenceAssetIds']):
            fail(f'{aid}: non-anchor asset must reference an anchor')

        required_neg = {
            'NO_TEXT','NO_LOGO','NO_WATERMARK',
            'NO_COPYRIGHT_IMITATION','NO_EXTERNAL_ASSET_COLLAGE'
        }
        if not required_neg.issubset(set(a.get('negativeConstraints', []))):
            fail(f'{aid}: missing negative constraints')
        if a['outputSpec']['colorSpace'] != 'sRGB':
            fail(f'{aid}: color space must be sRGB')
        if a['outputSpec']['densityPolicy'] != 'drawable-nodpi':
            fail(f'{aid}: density policy must be drawable-nodpi')
        if not re.match(r'^[a-z0-9][a-z0-9._-]+$', aid):
            fail(f'{aid}: invalid assetId')

    print(
        f'OK ImageGen SSOT: {len(assets)} planned assets, '
        f'{len(templates)} templates, {len(known_profiles)} style profiles'
    )
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
