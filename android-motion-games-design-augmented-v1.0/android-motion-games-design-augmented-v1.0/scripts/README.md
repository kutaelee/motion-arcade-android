# 검증 스크립트

```bash
python3 scripts/validate_package.py
```

검사:

- 필수 파일 존재와 package manifest의 파일 수
- JSON parse 및 JSON Schema 자체 유효성
- example asset manifest의 schema 적합성
- ImageGen registry의 style/game/reference/template 무결성
- 모든 master asset 요청의 `generationMethod=IMAGEGEN_SKILL`
- 외부/수작업 master 금지 방식
- source baseline SHA-256 일치
- `SHA256SUMS`에 대한 전체 파일 해시와 누락·stale entry

개별 실행:

```bash
python3 scripts/validate_json_schemas.py
python3 scripts/validate_asset_ssot.py
python3 scripts/validate_sha256s.py
```

실제 이미지 파일의 시각 품질, Android build, MediaPipe, 물리 기기 성능은 검사하지 않는다.
