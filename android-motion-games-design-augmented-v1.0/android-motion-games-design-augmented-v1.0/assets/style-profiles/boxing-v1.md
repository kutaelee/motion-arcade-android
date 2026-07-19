# Style Profile — boxing-v1

- profileId: `boxing-v1`
- revision: `1`
- status: `LOCK_CANDIDATE`
- internal name: Neon Gym Punch
- scope: 링, 복서, 글러브, hit/guard FX, round/versus ornament

## Visual DNA

- mood: 활기찬 스포츠 arcade, 공격적이되 폭력적·현실적 상처 표현 없음
- silhouette: 각진 어깨, 큰 글러브, 안정된 stance
- outline: near-black plum `#24152D`, slightly angular joins, 1024px 기준 12~16px
- palette anchors: burgundy `#7A284B`, electric cyan `#25C7D9`, warm yellow `#F4C84A`, cream `#FFF3D6`, deep indigo `#292C58`
- lighting: upper-left arena key + 제한된 cyan rim
- shading: 3-tone cel, sweat/skin texture 최소화
- projection: 3/4 front camera-facing athlete, ring side perspective 고정
- character proportion: head 1:5.2, gloves 20% oversized

## Animation language

- punch: anticipation 1, extension 1~2, impact 1, return 2 frames
- guard: forearm parts tween, face silhouette 유지
- dodge: torso translation/tilt, feet jump 금지
- hit FX: starburst/arc, 최대 180ms, HUD safe zone 회피

## No-go

- blood, bruising, realistic injury
- actual famous boxer likeness
- comic-book halftone가 일부 asset에만 등장
- mixed outline colors across characters
- text on ring/banner

## Locked anchor requirements

- 4 boxer body/face proportion sheet
- gloves color variants
- ring background
- jab/hook/guard FX family
- round icon shell without text
