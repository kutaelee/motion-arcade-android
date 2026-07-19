# Asset Consistency Eval

## Family-level review

개별 asset이 좋아 보여도 family가 일관되지 않으면 실패다. game별 contact sheet에서 다음을 비교한다.

- silhouette family
- character identity
- outline width/color
- palette distribution
- light direction/shadow count
- camera projection
- rendering density
- material language
- FX opacity/coverage
- transparent edge

## Deterministic checks

- dimensions and alpha channel
- sRGB metadata/profile
- transparent border/crop bounds
- filename/assetId mapping
- manifest hash match
- styleProfileId/gameId match
- no generated text request in registry

## Human checks

- 64px identification
- anchor similarity
- cross-asset consistency
- originality/no IP resemblance
- accessibility contrast
- HUD/pose guide safety

## Failure classes

- Critical: wrong style, identity drift, text/watermark, IP imitation, external art, alpha cutoff, HUD obstruction
- Major: outline/light/projection mismatch, unreadable silhouette
- Minor: small color drift, noncritical padding

Critical 1건이면 reject. Major 2건 이상이면 family 재생성 검토.
