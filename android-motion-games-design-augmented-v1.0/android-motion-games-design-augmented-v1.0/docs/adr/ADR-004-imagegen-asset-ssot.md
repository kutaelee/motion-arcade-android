# ADR-004 — ImageGen Asset Production을 SSOT로 고정

- Status: Accepted
- Date: 2026-07-14

## Context

생성형 이미지의 프레임·캐릭터·광원·선화 일관성은 일회성 프롬프트로 유지되지 않는다. 외부 에셋이나 수작업 보정을 혼합하면 provenance와 시각적 일관성이 깨진다.

## Decision

모든 master raster asset은 `skills/imagegen-asset-production/SKILL.md`를 통해 생성·수정한다.

Authoritative inputs:

- game별 locked style profile
- approved anchor/reference images
- versioned prompt template/variables
- output geometry/pivot/collision specification

Authoritative records:

- prompt registry
- raw output hash
- deterministic post-process recipe
- processed output hash
- review status

임의 one-off prompt, web/stock art 혼합, substantive manual repaint는 master로 허용하지 않는다. deterministic crop/resize/format/packing만 derivative로 허용한다.

## Consequences

장점:

- 게임별 에셋 일관성
- 생성·수정 경로 재현 가능
- drift를 revision 단위로 통제
- provenance 추적

비용:

- anchor 승인 전 병렬 생성 제한
- 재생성 비용
- ImageGen 서비스/도구 가용성 의존
- 생성물 이용 약관은 별도 검토 필요

## Rejected alternatives

1. 디자이너 자유 프롬프트: 속도는 빠르나 drift와 재현성 위험이 큼.
2. 외부 무료 에셋 혼합: 라이선스와 스타일 mismatch 위험.
3. 모든 프레임 수작업 수정: SSOT가 사람이 되어 재현성과 lineage가 깨짐.

## Rollback

이 결정을 바꾸려면 새 asset pipeline ADR, 전체 style audit, provenance migration, affected assets 재승인이 필요하다.
