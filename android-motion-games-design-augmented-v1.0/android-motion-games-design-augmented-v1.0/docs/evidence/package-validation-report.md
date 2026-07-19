# Package Validation Report

- Package: `android-motion-games-design-augmented-v1.0`
- Validation date: `2026-07-14`
- Scope: 설계 문서·JSON 계약·ImageGen 에셋 SSOT·파일 무결성
- Excluded: Android 빌드, MediaPipe 실행, 실제 카메라, 물리 기기, 2인 플레이, 실제 ImageGen 이미지 생성

## Claims and evidence

| Claim | Direct evidence | Status |
|---|---|---|
| 원본 요구사항이 변조 없이 포함됨 | `source/ORIGINAL_SPEC.md` SHA-256과 package manifest의 source hash 비교 | PASS |
| JSON 스키마가 파싱 가능함 | `python3 scripts/validate_json_schemas.py` | PASS |
| 예제 asset manifest가 schema를 만족함 | 동일 validator의 instance validation | PASS |
| 모든 master asset 요청이 ImageGen skill을 사용함 | `python3 scripts/validate_asset_ssot.py` | PASS |
| 게임별 asset이 올바른 style profile·anchor를 참조함 | 동일 validator의 game/style/reference checks | PASS |
| 외부 stock/web/manual master 방식이 registry에 없음 | `python3 scripts/validate_package.py` forbidden-method check | PASS |
| 패키지 파일 단위 무결성을 검증할 수 있음 | `SHA256SUMS` + `python3 scripts/validate_sha256s.py` | PROVIDED |
| Android 기능·성능이 완료됨 | 미실행·미관측 | NOT CLAIMED |
| 실제 에셋의 시각 일관성이 승인됨 | anchor/ImageGen output/contact sheet 미생성 | PENDING |

## Executed commands

```bash
python3 scripts/validate_json_schemas.py
python3 scripts/validate_asset_ssot.py
python3 scripts/validate_package.py
```

위 명령은 패키징 전 실제 실행했다. 최종 ZIP 생성 후에는 `unzip -t`로 archive integrity를 별도 확인한다.

## Observed validator summary

- JSON schemas parsed: 6
- Example manifests validated: 1
- Planned ImageGen asset requests: 44
- Prompt templates: 6
- Locked style-profile IDs: 4
- Android/device/runtime validation: 0; 범위 밖이며 `pending`

## Final judgment

- Design augmentation package: `completed`
- ImageGen production governance SSOT: `implemented and deterministically validated`
- Generated images and visual contact-sheet approval: `pending`
- Android implementation: `not started`
- Release readiness: `Manual review required`
