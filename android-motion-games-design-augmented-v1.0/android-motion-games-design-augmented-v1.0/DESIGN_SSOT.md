# Design SSOT

## 1. 목적

이 문서는 제품 요구를 실행 가능한 시스템 계약으로 고정한다. 구현자는 “대략 같은 동작”이 아니라 이 문서가 정의한 불변조건, 데이터 소유권, 시간 기준, 실패 정책, 증거 게이트를 만족해야 한다.

`MUST`, `MUST NOT`, `SHOULD`, `MAY`는 규범 용어다.

## 2. 시스템 불변조건

### 2.1 개인정보·네트워크

- 앱은 `android.permission.INTERNET`를 선언하지 않아야 한다.
- 카메라 프레임은 추론 파이프라인의 휘발성 메모리를 벗어나 저장·복제·내보내기하면 안 된다.
- 포즈 좌표, 얼굴, 신체 특징을 사용자 식별 목적으로 쓰면 안 된다.
- 릴리스 로그는 원시 랜드마크, 카메라 프레임, 사용자 경로, 파일 내용을 포함하면 안 된다.
- 진단 내보내기는 명시적 사용자 액션과 시스템 파일 선택기를 거쳐야 하며 기본값은 집계 지표만 포함한다.

### 2.2 의존성·경계

- `:games`와 `:game-core`는 CameraX와 MediaPipe 타입을 참조하면 안 된다.
- 카메라/추론 실패가 게임 상태를 임의 진행시키면 안 된다.
- 터치, 포즈, fixture 입력은 같은 의미 이벤트 계약을 사용해야 한다.
- 게임 상태는 시드, PRNG 알고리즘 버전, PRNG 내부 상태, 입력 이벤트 순서가 같으면 결정적으로 재현 가능해야 한다.

### 2.3 시간·동시성

- 런타임 판단은 wall clock이 아니라 단조 증가 시간(`elapsedRealtimeNanos` 계열)을 사용해야 한다.
- 제스처는 프레임 개수가 아니라 경과 시간으로 판정해야 한다.
- 카메라 프레임 큐는 최신 프레임 우선으로 버릴 수 있으나, 확인된 `MotionEvent`는 중복 제거 후 순서를 보존해야 한다.
- 하나의 제스처 사이클은 하나의 `eventId`만 발행해야 한다. 재콜백, 재구독, 회전, 일시정지 복귀로 같은 이벤트가 중복 적용되면 안 된다.
- 게임 시뮬레이션은 고정 tick을 유지하며, 렌더링 프레임과 ML 추론 프레임률이 달라도 규칙 결과가 바뀌면 안 된다.

### 2.4 좌표·좌우

- 해부학적 좌우, 센서 좌표, 회전된 분석 좌표, 미러된 표시 좌표, 게임 월드 좌표를 분리해야 한다.
- 전면 카메라의 미러는 표시 계층에만 적용한다. 내부 동작 의미의 `LEFT/RIGHT`는 해부학적 기준을 유지한다.
- `PreviewView` 크롭·레터박스가 있는 경우 오버레이는 동일한 `ViewPort` 변환을 사용해야 한다.
- 변환 검증 fixture가 통과하기 전에는 펀치 방향, 레인 교차, 좌우 회피 판정을 승인하면 안 된다.

### 2.5 2인 플레이

- P1/P2는 역할 ID이며 외형·얼굴 기반 정체성이 아니다.
- 추적이 모호하면 임의 재배정하지 말고 `AMBIGUOUS` 상태와 안전 일시정지를 사용해야 한다.
- 한 플레이어의 추적 손실을 다른 플레이어 입력으로 보충하면 안 된다.
- 품질·점수 계산은 각 플레이어의 캘리브레이션 스케일을 사용해야 하며 화면 픽셀 이동량으로 두 플레이어를 직접 비교하면 안 된다.

### 2.6 사용자 안전

- 인식 신뢰도가 낮거나 플레이어 간 겹침·거리 위험이 감지되면 보상보다 일시정지를 우선해야 한다.
- 높은 속도, 큰 진폭, 반복 횟수 자체에 무제한 보상을 주면 안 된다.
- 난이도는 게임 AI와 타이밍 설계로 조정하고, 낮은 인식 품질을 난이도로 위장하면 안 된다.
- 실제 운동 효과, 칼로리, 힘, 건강 개선을 측정하거나 주장하면 안 된다.

### 2.7 에셋

- 모든 master raster asset의 `generationMethod`는 `IMAGEGEN_SKILL`이어야 한다.
- 게임별 에셋은 하나의 승인된 `styleProfileId`와 anchor revision을 참조해야 한다.
- 임의 프롬프트, 외부 에셋 혼합, 사람이 다시 그린 master는 SSOT 위반이다.
- UI 문자열은 이미지에 넣지 않고 Android string resource로 렌더링해야 한다.
- ImageGen 출력의 상업 이용·재배포 적합성은 출시 전 적용 약관 기준으로 별도 확인해야 하며, 미확인 상태를 “무료 라이선스 확인 완료”로 표시하면 안 된다.

## 3. 상태 소유권

| 상태 | 단일 소유자 | 비고 |
|---|---|---|
| Camera binding | `CameraSessionController` | Activity/Composable이 직접 소유하지 않음 |
| Raw/normalized pose | `:vision` | 세션 밖 저장 금지 |
| Player track | `PlayerTracker` | P1/P2 역할과 분리 |
| Gesture state | player별 `GestureEngine` | 이벤트 발행 후 cooldown 관리 |
| Game simulation | `GameSession` | 카메라와 무관한 결정적 상태 |
| UI navigation | `:app` | 게임 상태의 복제본이 아님 |
| Persistent profile | Proto DataStore repository | 버전·복구 정책 필요 |
| Asset master/provenance | ImageGen SSOT | 레지스트리와 매니페스트 |

두 컴포넌트가 같은 mutable state를 동시에 소유하면 설계 결함으로 본다.

## 4. SSOT 지도

| 관심사 | authoritative source |
|---|---|
| 제품 범위 | `source/ORIGINAL_SPEC.md` + 승인 ADR |
| 시스템 불변조건 | 본 문서 |
| 런타임 데이터 형태 | `contracts/*.schema.json` |
| 제스처 임계값 | 앱의 버전 관리된 gesture config; 하드코딩 금지 |
| 게임 규칙 | 게임별 config + 결정적 테스트 |
| 에셋 생성 | `skills/imagegen-asset-production/SKILL.md` |
| 에셋 요청·프롬프트 | `assets/imagegen-prompt-registry.json` |
| 게임별 시각 언어 | `assets/style-profiles/*.md` |
| 생성 결과 이력 | `asset-manifest.json`의 실제 배포본 |
| 성능·품질 완료 주장 | `docs/evidence/`의 실제 측정 결과 |

## 5. 변경 통제

다음 변경은 최소 ADR과 회귀 증거가 필요하다.

- 시간 기준, 좌표 변환, 이벤트 중복 제거 방식
- 플레이어 ID 배정·재배정 정책
- gesture threshold 또는 상호 배제 규칙
- PRNG 알고리즘·저장 스키마
- 기기 등급 판정과 열화 순서
- 카메라 프레임·포즈 데이터 취급
- style profile, anchor board, master asset 생성 방식

네트워크, 새 권한, 영상 저장, 포즈 업로드, 얼굴 인식, 유료 SDK, 게임 엔진, 런타임 생성형 AI는 원본 Approval Gate를 따른다.

## 6. 완료의 증거 계약

“완료”라고 표시하려면 각 주장에 직접 증거가 있어야 한다.

| 주장 | 최소 증거 |
|---|---|
| 빌드 가능 | 실행 명령, exit code, 빌드 로그, 산출물 해시 |
| 좌표 정확 | synthetic transform test + 실제 overlay 캡처 |
| 동작 품질 | 독립된 holdout fixture의 precision/recall/confusion matrix |
| 2인 안정 | 교차·가림·이탈 시나리오 영상/fixture와 ID timeline |
| 성능 충족 | 실제 기기명·SoC·빌드 타입·해상도·delegate가 포함된 분포 |
| 개인정보 준수 | manifest 권한 검사, 네트워크 호출 검사, 저장 경로 검사 |
| 에셋 일관 | 승인 anchor, contact sheet, 매니페스트 해시, review gate |
| 출시 가능 | 모든 critical gate 통과 + 수동 안전·법무 검토 |

테스트하지 않은 기기, 렌즈, 자세, 조명 조건은 `unverified`로 남긴다.
