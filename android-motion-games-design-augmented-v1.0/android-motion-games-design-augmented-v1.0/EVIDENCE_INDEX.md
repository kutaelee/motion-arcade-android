# Evidence Index

| Evidence | Path | Meaning |
|---|---|---|
| 원본 보존본 | `source/ORIGINAL_SPEC.md` | 사용자 기준선, 해시 고정 |
| 설계 보강 추적 | `TRACEABILITY_MATRIX.md` | 원본 관심사 → 보강 계약 → 게이트 |
| gap 분석 | `docs/00-gap-analysis.md` | 놓친 암묵지 25개와 보강 결정 |
| 패키지 검증 보고서 | `docs/evidence/package-validation-report.md` | 실제 실행 범위와 미실행 범위 |
| ImageGen SSOT validator | `scripts/validate_asset_ssot.py` | style/anchor/generationMethod 검증 |
| JSON schema validator | `scripts/validate_json_schemas.py` | 계약·예제 검증 |
| package validator | `scripts/validate_package.py` | 필수 파일·금지 방식·source hash 검증 |
| checksum validator | `scripts/validate_sha256s.py` | 모든 패키지 파일 해시 검증 |
| file checksum manifest | `SHA256SUMS` | ZIP 내부 파일 단위 무결성 |

물리 Android 기기, 실제 카메라, 2인 플레이, MediaPipe 성능, 실제 ImageGen 이미지에 대한 증거는 이 패키지에 없다. 해당 완료 주장은 하지 않는다.
