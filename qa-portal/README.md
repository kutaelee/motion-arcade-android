# Motion Arcade Physical QA Desk

Motion Arcade에서 자동화가 증명할 수 없는 실기기·사람 관찰만 요청하는
manifest-driven 정적 포털입니다.

## Product boundary

- 현재 활성 항목은 `Slice 1B` 전면 카메라 프리뷰이며 완성 게임이 아닙니다. 권한·프리뷰·회전·미러·수명주기만 사람 QA로 요청합니다.
- QA 요청·상태·APK Release 주소는 `app/qa-manifest.ts`가 유일한 포털 원본입니다.
- 활성 QA 결과 제출은 manifest의 빌드·체크리스트를 미리 채운 GitHub Issue로 연결됩니다.
- 포털 앱은 자체 계정, 분석, 데이터베이스, 업로드 저장소를 구현하지 않습니다. 비공개
  접근 제어와 봇 방어를 위해 호스팅 계층의 로그인 및 필수 보안 쿠키가 사용될 수 있습니다.
- 시각 표현은 시스템 글꼴과 CSS 도형만 사용하며 이미지·SVG·아이콘·OG 이미지를
  포함하지 않습니다.

## Local verification

Node.js `>=22.13.0` 환경에서:

```bash
npm ci
npm run build
node --test tests/rendered-html.test.mjs
npm run lint
```

## Advancing a slice

새 APK를 공개할 때 `app/qa-manifest.ts`에서 정확한 파일명, SHA-256, 고정 Release
URL을 갱신합니다. 해당 빌드로 사람이 확인할 수 있는 요청만 `ready`로 바꾸고,
자동 테스트가 판별 가능한 항목은 QA 요청에 추가하지 않습니다.
