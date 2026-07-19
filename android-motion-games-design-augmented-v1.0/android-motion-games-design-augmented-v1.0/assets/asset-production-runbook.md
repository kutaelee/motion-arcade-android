# Asset Production Runbook

## Objective

각 게임의 모든 에셋이 같은 캐릭터 비율, 선 굵기, 광원, 팔레트, 투영, FX 언어를 유지하도록 ImageGen 생성과 review를 순차 통제한다.

## Input contract

모든 요청은 다음을 가진다.

- assetId
- gameId
- styleProfileId/revision
- templateId
- asset family
- intended runtime size
- transparent/background requirement
- pivot/collision expectation
- reference asset IDs
- forbidden elements
- acceptance checklist

누락 시 생성하지 않는다.

## Phase 1 — Anchor board

1. style profile의 locked block을 그대로 사용한다.
2. 캐릭터·배경·props·FX의 대표 조합을 ImageGen으로 생성한다.
3. 2~4개 후보를 contact sheet로 비교한다.
4. silhouette, outline, lighting, palette, projection을 검토한다.
5. 승인된 anchor만 reference asset으로 등록한다.
6. profile revision을 lock한다.

## Phase 2 — Character identity

- front/3/4/side reference를 먼저 생성한다.
- 머리, 몸통, 상완, 전완, 손/글러브, 무기, 액세서리를 분리할 layer spec을 만든다.
- 캐릭터마다 immutable identity traits 3~5개를 정의한다.
- 의상 variant도 base reference를 사용한다.

## Phase 3 — Asset family generation

같은 family는 한 batch에서 생성하고 같은 reference set을 사용한다.

권장 순서:

1. base character/creature
2. props/equipment
3. background layers
4. action key poses
5. FX
6. icons/badges

한 프롬프트에서 여러 animation frames를 한 장에 무리하게 생성하지 않는다. frame별 identity drift가 크면 layered tween으로 대체한다.

## Phase 4 — Review

Critical check:

- style profile 일치
- anchor character identity 유지
- text/logo/watermark 없음
- 외부 IP 모방 없음
- transparent edge 정상
- mobile silhouette/readability
- pose guide/HUD safe zone 침범 없음
- game별 팔레트와 광원 일치

Critical failure는 후보 탈락 또는 ImageGen correction revision으로 처리한다. 수작업 paint-over 금지.

## Phase 5 — Deterministic processing

처리 recipe 예:

```text
trim-alpha(threshold=1)
canvas-normalize(1024x1024, anchor=center-bottom)
convert-colorspace(sRGB)
resize(Lanczos, target=512x512)
export(PNG, optimize-lossless)
```

recipe와 tool version을 manifest에 기록한다.

## Phase 6 — Runtime QA

- 64/128/실게임 크기에서 확인
- portrait/landscape와 tablet에서 확인
- pivot rotation 시 분리 파츠 gap/overlap 확인
- collision bounds는 visible art와 별도 검증
- background와 캐릭터 contrast
- FX가 camera preview/guide를 가리지 않는지 확인

## Revision policy

- prompt wording, style profile, anchor, geometry가 바뀌면 revision 증가
- deterministic compression만 바뀌면 processed revision 증가
- released assetId는 재사용하지 않고 alias/migration을 둔다.
