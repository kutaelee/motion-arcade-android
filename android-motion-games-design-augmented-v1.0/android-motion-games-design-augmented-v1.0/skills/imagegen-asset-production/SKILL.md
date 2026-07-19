# Skill — ImageGen Asset Production SSOT

## Trigger

게임·공통 UI의 master raster asset을 생성, 수정, 재생성, style correction할 때 반드시 사용한다.

## Objective

승인된 game style profile과 anchor reference를 사용해 일관된 에셋을 만들고, prompt부터 runtime derivative까지 lineage를 남긴다.

## Authoritative inputs

- `assets/imagegen-prompt-registry.json`
- `assets/style-profiles/<profile>.md`
- 승인된 anchor/reference image files
- asset request의 output/pivot/collision spec

## Non-goals

- UI 문자열 생성
- 외부 스톡/웹 이미지 수집
- 특정 게임·애니메이션·작가 스타일 모방
- Photoshop 수작업 master 제작
- 한 번에 완전한 장편 sprite animation 생성

## Preconditions

- assetId가 registry에 존재
- gameId와 styleProfileId가 일치
- anchor asset이면 style profile이 review ready
- 일반 asset이면 승인된 anchorRevision 존재
- output geometry와 transparent/background 요구가 명확
- redistribution review 상태가 별도 관리됨

조건이 없으면 `blocked`로 종료한다.

## Sequential procedure

1. registry의 template과 variables를 조합한다.
2. style profile의 visual DNA와 no-go를 prompt의 locked block으로 포함한다.
3. 일반 asset은 승인 anchor/reference image를 입력으로 사용한다.
4. ImageGen으로 후보를 생성한다.
5. 후보 원본을 변경 없이 raw master로 저장한다.
6. contact sheet review를 수행한다.
7. critical failure가 있으면 수작업 수정하지 말고 correction prompt로 ImageGen revision을 생성한다.
8. 승인본에만 결정적 후처리를 수행한다.
9. raw/processed SHA-256, prompt record hash, recipe, reviewer를 manifest에 기록한다.
10. 64px/실게임/pivot/HUD safe-zone QA를 실행한다.

## Output contract

- raw ImageGen output
- processed runtime file
- prompt record
- asset manifest entry
- review scorecard
- contact sheet reference
- final status: `approved`, `rejected`, `blocked`, `partial`

## Critical stop conditions

- registry 밖 one-off prompt
- wrong/missing style profile
- unapproved anchor
- 외부 asset collage
- 특정 IP/작가 모방
- 이미지 내 텍스트·워터마크
- 캐릭터 identity 또는 game style drift
- substantive manual repaint 요청
- provenance/약관 상태를 허위로 approved 처리

## Allowed deterministic post-processing

- crop transparent margins
- canvas normalization
- color space conversion to sRGB
- lossless format conversion
- resize with recorded algorithm
- sprite atlas packing
- metadata generation

이외 변경은 ImageGen revision이다.

## Evidence

- exact registry entry
- style profile revision
- reference asset IDs/hashes
- raw/processed hashes
- review score and critical checks
- runtime screenshots at target sizes
