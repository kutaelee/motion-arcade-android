# Style Profile — fishing-v1

- profileId: `fishing-v1`
- revision: `1`
- status: `LOCK_CANDIDATE`
- internal name: Harbor Pop
- scope: 낚시 배경, 캐릭터, 어구, 물고기, 물/날씨 FX, game HUD ornament

## Visual DNA

- mood: 밝고 산뜻한 항구 모험, 긴장보다 성취감
- silhouette: 둥근 물고기 body, 곡선형 낚싯대, 넓은 brim/vest shape
- outline: deep blue `#14364A`, rounded joins, 1024px 기준 12~16px
- palette anchors: sea teal `#2BA9A1`, deep water `#176B87`, foam `#EAF9F4`, coral `#F47B61`, sand `#E9C77C`, rare violet `#7656C9`
- lighting: upper-left warm daylight; underwater asset도 같은 방향
- shading: 3-tone cel, scale texture는 큰 단순 패턴만
- projection: side-view game entities, props는 3/4 side
- character proportion: head 1:5, hands slightly oversized

## Animation language

- idle: gentle 2~3px bob equivalent
- cast: long arc, 4~6 key frames
- reel: arm parts rotated/tweened; full redraw 최소화
- fish: body bend + tail part rotation
- splash: 4-frame key animation, opaque core와 투명 droplets 분리

## No-go

- realistic fish scales/eyes
- horror sea creature
- thin fishing line baked into character sprite
- blue palette가 monster magic과 동일한 violet glow로 변함
- text on signs/UI

## Locked anchor requirements

- angler front/side reference
- standard fish silhouette sheet 7종
- harbor background daylight
- water splash FX sheet
- rod/net prop sheet
