# Android 2인 모션 인식 2D 미니게임 — 보강 설계 패키지 v1.0

## 한 줄 결론

기존 요구사항을 기준선으로 유지하면서, 실제 제품화에서 빠지기 쉬운 시간·좌표·동시성·복구·기기 편차·2인 공정성·안전·에셋 일관성 계약을 추가했다. 에셋 제작의 유일한 원천은 `skills/imagegen-asset-production/SKILL.md`와 그 입력 레지스트리이며, 임의 프롬프트나 외부 그림 혼합은 허용하지 않는다.

## 이 패키지가 제공하는 것

이 ZIP은 Android 앱 구현물이 아니라 **구현·검증·승인에 바로 사용할 수 있는 설계 SSOT 보강본**이다.

- 업로드된 원본 요구사항의 보존본
- 누락된 암묵지와 실패 모드 분석
- 런타임 시간·좌표·동시성·이벤트 계약
- 2인 추적, 캘리브레이션, 생명주기, 저장·복구 설계
- 기기 등급·열화·성능 저하 정책
- 오프라인 개인정보·진단·공급망 통제
- 게임별 안전·공정성·실패 복구 규칙
- 릴리스 전 검증 게이트와 증거 계약
- **ImageGen 전용 에셋 SSOT, 게임별 스타일 바이블, 프롬프트 레지스트리, 자산 매니페스트 스키마**
- 구조와 SSOT 위반을 검사하는 로컬 검증 스크립트

## 규범 우선순위

충돌 시 다음 순서로 해석한다.

1. `DESIGN_SSOT.md`의 불변조건과 승인 게이트
2. `docs/adr/`의 승인된 결정
3. `contracts/`와 `assets/*.schema.json`의 기계 판독 계약
4. `source/ORIGINAL_SPEC.md`의 제품 요구사항
5. 나머지 가이드·런북·템플릿

원본 요구사항을 임의로 삭제하지 않는다. 보강 문서가 원본과 충돌할 경우, 보강 문서는 기능을 축소하는 것이 아니라 **검증되지 않은 완료 주장이나 안전하지 않은 동작을 막는 제약**으로만 우선한다.

## 에셋 SSOT

에셋 관련 authoritative source는 아래 네 묶음이다.

1. `skills/imagegen-asset-production/SKILL.md`: 생성 절차와 stop condition
2. `assets/imagegen-prompt-registry.json`: 승인된 템플릿·요청·참조 관계
3. `assets/style-profiles/*.md`: 게임별 잠긴 시각 DNA
4. 생성 후 작성되는 `asset-manifest.json`: 원본·파생 파일·해시·검토 증거

다음은 금지한다.

- 채팅창에서 만든 일회성 프롬프트를 레지스트리 밖에서 사용
- 웹·스톡·타 게임 에셋을 스타일 보완용으로 혼합
- Photoshop 등에서 수작업으로 스타일을 재창작한 뒤 ImageGen 원본이라고 표시
- 게임 A의 캐릭터·효과를 게임 B 스타일로 무단 재사용
- 생성 이미지에 UI 텍스트 포함

크롭, 투명 여백 제거, 리사이즈, 포맷 변환, 패킹처럼 **결정적이고 재현 가능한 후처리**만 파생 산출물로 허용한다. 형태·색·표정·의상·광원·선화를 바꾸는 편집은 ImageGen 스킬을 다시 실행하고 revision을 올려야 한다.

## 검증 방법

```bash
python3 scripts/validate_package.py
```

검증 범위는 문서 구조, JSON 스키마의 문법, ImageGen SSOT 참조 무결성, 금지된 외부 에셋 방식의 혼입 여부다. Android 빌드, MediaPipe 추론, 실제 기기 성능, 2인 안전성은 이 패키지에서 실행하지 않았다.

## 완료 판정

- 설계 보강 패키지: `completed`
- Android 구현: `not started`
- 실제 기기·2인 검증: `pending`
- 출시 승인: `Manual review required`
