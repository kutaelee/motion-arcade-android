# Asset Review Gate

## 자동 검사

- registry에 assetId 존재
- generationMethod=`IMAGEGEN_SKILL`
- styleProfileId가 gameId와 일치
- reference anchor 존재
- raw/processed hash 기록
- 이미지 내 UI text 요청 금지
- source가 WEB/STOCK/MANUAL_MASTER가 아님
- planned output geometry 유효

## 수동 검사 점수표

각 항목 0~2점, critical 항목 0이면 탈락.

| 항목 | 0 | 1 | 2 | Critical |
|---|---|---|---|---|
| silhouette | 64px 식별 불가 | 일부 모호 | 즉시 식별 | Yes |
| character identity | 다른 캐릭터처럼 보임 | minor drift | anchor와 일치 | Yes |
| outline | family와 불일치 | 일부 변동 | 일관 | Yes |
| palette | game profile 이탈 | 보조색 drift | 잠긴 palette | Yes |
| lighting | 반대/복수 광원 | 일부 모호 | 방향 일치 | No |
| projection | 각도 불일치 | minor | 고정 | Yes |
| edge/alpha | halo/cutoff | minor | clean | Yes |
| HUD safety | guide 가림 | 조정 필요 | 안전 | Yes |
| originality | 특정 IP 연상 강함 | 재검토 | 독창적 | Yes |
| animation fit | pivot/part 불가 | 보정 필요 | tween 친화 | No |

승인 기준: critical 0 없음, 총점 18점 이상/20점. 동일 family contact sheet에서 편차가 크면 개별 점수가 높아도 재생성한다.

## Required evidence

- anchor/reference IDs
- prompt/template revision
- candidate contact sheet
- reviewer와 날짜
- 점수표
- raw/processed hash
- legal/provenance status
