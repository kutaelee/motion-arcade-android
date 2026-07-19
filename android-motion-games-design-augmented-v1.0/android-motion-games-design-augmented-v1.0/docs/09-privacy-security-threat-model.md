# 개인정보·보안 위협 모델

## 보호 대상

- 실시간 카메라 프레임
- 원시/정규화 포즈 좌표
- 캘리브레이션 값
- 게임 진행·설정
- 진단 bundle
- 모델·에셋·config 무결성

## Trust boundary

1. Camera HAL / Android OS
2. 앱 프로세스와 native MediaPipe runtime
3. 앱 내부 파일 저장소
4. 사용자가 선택한 외부 문서 경로
5. build dependency와 모델/에셋 공급망

## 위협과 통제

| 위협 | 통제 | 검증 |
|---|---|---|
| raw frame가 로그/crash dump에 포함 | frame `toString`/byte dump 금지, release log redaction | static scan + failure-path test |
| screenshot/screen recording으로 camera preview 저장 | live camera 화면 `FLAG_SECURE` 기본안 검토 | 접근성·기기 호환성 test 후 ADR 확정 |
| exported Activity/Service가 카메라·진단에 접근 | exported=false, explicit intent 최소화 | merged manifest 검사 |
| INTERNET permission 또는 SDK 네트워크 호출 | permission 금지, dependency allowlist, runtime traffic test | manifest + offline proxy 검사 |
| debug export에 pose가 포함 | 집계-only 기본, 별도 체크와 preview | export fixture inspection |
| path traversal/임의 overwrite | Storage Access Framework URI만 사용 | instrumentation test |
| model 변조 | packaged hash/size/version 확인 | startup integrity test |
| asset provenance 손실 | ImageGen manifest와 hashes | G6 gate |
| DataStore corruption | atomic write, corruption handler, backup policy | corruption fixture |
| release build 상세 skeleton log | build config gate | release log test |

## Data minimization

| 데이터 | 메모리 | 영속 | 내보내기 |
|---|---:|---:|---:|
| raw frame | 순간적 | 금지 | 금지 |
| raw pose | 짧은 window | 금지 | 기본 금지 |
| normalized pose | 짧은 window | 금지 | opt-in fixture 형식만 |
| aggregate performance | 허용 | 로컬 제한 | opt-in 허용 |
| calibration multiplier | 허용 | 선택적 | 기본 제외 |
| game progress | 허용 | 허용 | 사용자 백업 범위 밖 |

## `FLAG_SECURE` 결정

개인정보 원칙상 live camera 화면에 적용하는 것이 보수적이다. 다만 접근성, 디버깅, 외부 display, 제조사 호환성에 영향을 줄 수 있으므로 다음 조건을 충족한 후 승인한다.

- TalkBack/접근성 흐름 검증
- screen mirroring이 rear-camera setup에 필요한지 결정
- 사용자 기대와 개인정보 안내 문구 정합성 확인
- debug build에서만 명시적 override

결정 전 상태는 `Manual review required`다.

## Diagnostic export

- 사용자 명시적 액션
- 포함 항목 preview
- 좌표 포함은 별도 toggle과 경고
- raw image 절대 금지
- sessionId는 export용 random ID로 치환
- device model/OS는 필요 최소한
- 삭제 방법 안내

## 공급망

- 고정 버전
- Gradle dependency verification metadata
- wrapper distribution checksum
- 모델 파일 SHA-256
- THIRD_PARTY_NOTICES
- ImageGen provenance와 재배포 검토 상태 분리

## Approval Gate

네트워크, 영상/포즈 업로드, 얼굴 인식, 아동 대상 데이터, 새 권한, 분석 SDK는 별도 개인정보·보안 검토 없이는 진행하지 않는다.
