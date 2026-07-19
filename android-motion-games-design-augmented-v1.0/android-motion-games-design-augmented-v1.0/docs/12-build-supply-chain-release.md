# 빌드·공급망·릴리스 암묵지

## Version policy

- compileSdk/targetSdk/AGP/Kotlin/Compose/CameraX/MediaPipe는 구현 시작 시 공식 안정 릴리스를 확인하고 version catalog에 정확히 고정한다.
- 이 설계 패키지는 현재 버전 번호를 추정하지 않는다.
- alpha/beta/snapshot은 ADR, 대체안 비교, rollback이 없으면 금지한다.

## Reproducibility

- Gradle wrapper distribution checksum 검증
- dependency verification metadata 사용
- 가능한 범위의 dependency locking
- CI와 로컬의 JDK version 고정
- build command와 environment를 README에 기록
- release artifact SHA-256 기록

## Model governance

모델은 다음을 기록한다.

- file name
- upstream source
- version/model variant
- license/provenance
- SHA-256
- expected size
- supported task/options
- packaging path
- replacement approval date

런타임 다운로드 없이 APK/AAB에 포함한다. 모델 변경은 gesture regression을 다시 수행한다.

## Android manifest gate

- CAMERA만 필요한지 검사
- INTERNET, RECORD_AUDIO, READ/WRITE storage, location, advertising ID 관련 선언 금지
- merged manifest에서 transitive SDK가 추가한 permission 확인
- exported component 명시
- backup 정책과 민감 데이터 포함 여부 검토

## R8/Native

- debug 성공을 release 성공으로 간주하지 않는다.
- MediaPipe/CameraX reflection/native keep rule을 release에서 검증한다.
- ABI별 native library 포함과 install size를 기록한다.
- unsupported ABI/device 실패 메시지를 정의한다.

## Asset packaging

- runtime asset은 processed PNG/WebP와 manifest subset만 포함한다.
- raw ImageGen source, contact sheet, prompt 문서는 저장소에 두되 APK에 넣지 않는다.
- pixel art가 아니므로 임의 palette quantization으로 edge 품질을 훼손하지 않는다.
- transparent sprites는 sRGB PNG, `drawable-nodpi` 기본, 런타임 logical size로 스케일한다.
- background는 해상도별 메모리 예산을 확인하고 필요 시 tile/layer로 분리한다.

## License and notices

- OSS license를 자동 수집하되 최종 고지는 사람이 검토한다.
- 폰트는 OFL 등 재배포 조건을 확인한다.
- ImageGen 생성물은 OSS license와 동일하게 처리하지 않는다. provenance와 적용 서비스 약관 검토 상태를 별도로 기록한다.
- “무료 생성”을 “재배포 권리 확인”으로 오해하지 않는다.

## Release signing

키 생성·업로드·production signing은 비가역/보안 영향이 있으므로 수동 approval gate다. 이 패키지는 키를 만들거나 포함하지 않는다.

## Rollback

- app version rollback만으로 저장 스키마가 깨지지 않게 compatibility window를 둔다.
- 새 config/asset ID는 이전 버전에서 읽을 수 없을 수 있으므로 alias와 fallback asset을 유지한다.
- model/gesture config rollback 시 calibration profile revision을 무효화한다.
