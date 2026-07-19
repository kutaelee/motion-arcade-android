# ImageGen Asset SSOT

## Normative decision

**모든 게임 master asset은 ImageGen 스킬을 통해 생성·수정한다.**

- Skill: `skills/imagegen-asset-production/SKILL.md`
- Prompt/request SSOT: `assets/imagegen-prompt-registry.json`
- Style SSOT: `assets/style-profiles/*.md`
- Output lineage SSOT: 실제 생성 단계에서 작성할 `asset-manifest.json`

레지스트리 밖의 채팅 프롬프트, 외부 이미지, 수작업 master는 배포 에셋으로 인정하지 않는다.

## 게임별 일관성 구조

```text
common-hub-v1       → 홈, 공통 보상, 설정, 카메라 안내
fishing-v1          → 낚시의 모든 캐릭터/배경/물고기/FX
boxing-v1           → 권투의 모든 복서/링/글러브/FX
monster-v1          → 몬스터의 모든 클래스/적/아레나/FX
```

게임 간 공통 UI shell은 common hub profile을 사용한다. 게임 내부 HUD 장식은 해당 game profile을 사용한다. 한 파일이 두 style profile을 동시에 참조하면 안 된다.

## 생성 순서

1. style profile 문서 승인
2. ImageGen으로 anchor board 생성
3. human visual review 후 `anchorRevision` lock
4. 캐릭터 turnaround/레이어 기준 생성
5. 동일 anchor/reference를 사용해 asset family 생성
6. contact sheet로 family consistency 검토
7. 결정적 후처리
8. asset manifest에 prompt hash, source hash, processed hash 기록
9. game runtime에서 pivot/scale/safe-zone 확인

anchor 승인 전 대량 asset 생성은 중단한다.

## Master와 derivative

### Master

ImageGen skill의 raw output. 형태, 색, 선화, 광원, 표정, 의상, 재질의 authoritative source다.

### 허용 derivative

- transparent padding crop
- canvas size normalization
- nearest/bicubic resize recipe
- sRGB conversion
- PNG/WebP conversion
- atlas packing
- metadata sidecar 생성

### 금지 derivative

- 사람이 눈·얼굴·의상·색을 다시 그림
- 다른 게임의 팔/무기/효과를 합성
- 외부 에셋을 배경에 삽입
- style mismatch를 수작업 paint-over로 숨김

금지 변경이 필요하면 ImageGen skill에 reference와 correction request를 전달하고 revision을 올린다.

## 파일 규칙

- raw: `assets-src/imagegen/<gameId>/<assetId>/rNN/raw.png`
- processed: `app/src/main/res/drawable-nodpi/<asset_id>.png` 또는 runtime asset directory
- manifest: `assets-src/imagegen/asset-manifest.json`
- contact sheet: `docs/evidence/assets/<gameId>-rNN-contact-sheet.png`

본 설계 패키지는 실제 이미지를 생성하지 않았으므로 위 runtime 경로는 계약 예시다.

## 색·alpha·크기

- sRGB
- 투명 sprite는 PNG straight alpha source, Android decode 후 premultiplied edge 검증
- transparent fringe/white halo 금지
- 기본 `drawable-nodpi`; 게임 논리 단위로 스케일
- pivot와 collision bounds는 이미지의 visible bounds 기준
- UI 텍스트/숫자는 이미지에 포함하지 않음

## 법적 상태

`provenance=OPENAI_IMAGEGEN`은 생성 경로를 뜻한다. 상업 이용·재배포 승인과 동일하지 않다. 실제 생성일의 적용 약관 검토 결과를 manifest의 `redistributionReviewStatus`에 기록한다. 검토 전은 `PENDING`이다.
