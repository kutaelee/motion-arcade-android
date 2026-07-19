# Generated Asset Placeholder

이 디렉터리는 실제 ImageGen 산출물을 포함하지 않는다.

실제 생성 단계에서는 다음 순서를 따른다.

1. `assets/imagegen-prompt-registry.json`의 anchor asset부터 생성
2. 스타일 보드 수동 승인
3. 일반 asset 생성
4. raw/processed 파일과 manifest 작성
5. contact sheet 및 runtime QA
6. 재배포 검토 상태 승인

본 설계 패키지에 이미지가 없다는 사실을 “에셋 생성 완료”로 해석하면 안 된다.
