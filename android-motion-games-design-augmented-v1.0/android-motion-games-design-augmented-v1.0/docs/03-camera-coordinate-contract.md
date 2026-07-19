# 카메라·좌표 변환 계약

## 좌표 공간

1. **Sensor/Buffer**: ImageProxy의 원본 버퍼 좌표
2. **Rotated analysis**: rotationDegrees를 적용한 분석 좌표
3. **Canonical anatomical**: 골반 중심 원점, 해부학적 좌우, 스케일 정규화
4. **Preview display**: PreviewView의 crop/letterbox와 front mirror가 적용된 표시 좌표
5. **Game world**: 게임 field의 논리 좌표

동작 인식은 3번만 사용한다. skeleton overlay는 4번, 게임 entity는 5번을 사용한다.

## 변환 순서

```text
buffer landmark
→ rotation correction
→ analysis crop normalization
→ canonical anatomical orientation
→ body-centric translation/scale
```

표시할 때만 다음을 별도로 적용한다.

```text
canonical/unrotated observation
→ PreviewView ViewPort crop matrix
→ display rotation
→ front-camera mirror (display only)
```

front mirror를 canonical 단계에 중복 적용하지 않는다.

## Crop·letterbox

CameraX Preview와 ImageAnalysis를 같은 `UseCaseGroup`/`ViewPort`로 맞추는 것을 우선한다. 그렇지 않으면 각 use case crop rect를 명시적으로 기록하고 matrix를 계산한다.

`PreviewView.ScaleType`을 변경하면 transform golden tests를 다시 실행해야 한다. `FIT_CENTER`와 `FILL_CENTER`는 같은 좌표가 아니다.

## 좌우 의미

- `anatomicalLeft`: 사용자 신체의 왼쪽
- `screenLeft`: 화면의 왼쪽
- `laneLeft`: 게임 레인의 왼쪽

`DODGE_LEFT`의 의미를 게임마다 명시한다. 권장 기본은 “플레이어가 자신의 해부학적 왼쪽으로 이동”이다. 화면 미러 상태와 무관해야 한다.

## 정규화 스케일

기본 스케일은 어깨 폭과 몸통 길이의 robust combination을 사용한다.

- 단일 landmark 쌍이 가려지면 즉시 스케일을 바꾸지 않는다.
- 최근 안정 구간 median으로 scale을 유지한다.
- 20% 이상 급변이 500ms 지속되면 calibration drift로 판단한다.
- 두 플레이어의 scale을 서로 공유하지 않는다.

## Overlay 정확도 검증

Synthetic fixture:

- rotation 0/90/180/270
- lens front/back
- aspect 4:3, 16:9, tall portrait
- preview fill/fit
- crop rect offset

Physical fixture:

- 화면 네 모서리와 중심에 표시한 기준 자세
- 어깨, 손목, 골반 overlay 오차 측정
- 허용 오차는 preview 짧은 변 기준 3% 목표, 5% 초과 시 release block

원본의 10% stop condition은 초기 개발 중단 기준으로 유지하되, 릴리스 기준은 더 엄격하게 별도 측정한다.

## 테스트 oracle

각 transform test는 입력 landmark와 기대 display point를 숫자로 고정한다. 화면 캡처만 보고 “대략 맞음”으로 승인하지 않는다.

## 오류 처리

- rotation/crop metadata 누락: frame 폐기
- matrix determinant 0 또는 NaN: frame 폐기 + 진단 증가
- display와 analysis aspect mismatch 미처리: overlay 비활성화, game input도 보수적으로 pause
- lens switch: transform cache 폐기, calibration revision 증가
