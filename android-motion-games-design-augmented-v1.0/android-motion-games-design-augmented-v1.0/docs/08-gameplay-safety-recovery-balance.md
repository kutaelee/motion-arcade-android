# 게임별 안전·실패 복구·밸런스 암묵지

## 공통 규칙

### Detection과 difficulty 분리

- 인식 confidence 하락은 AI 강화, timer 단축, damage 감소로 처리하지 않는다.
- confidence 하락 구간은 input hold/grace/pause로 처리한다.
- 사용자에게 “동작 실패”와 “카메라가 보지 못함”을 다른 메시지로 보여준다.

### Latency-aware feedback

동작은 즉시 시각적 후보 피드백과 confirmed 피드백을 구분한다.

- candidate: 약한 outline/guide
- confirmed: 캐릭터 action/SFX/haptic
- rejected: 이유가 명확할 때만 짧은 guide

게임 entity가 먼저 움직이고 나중에 detection이 취소되는 speculative gameplay는 금지한다.

### 안전 공간

픽셀 거리 대신 다음을 결합한다.

- body bbox occupancy
- player center separation / 평균 shoulder width
- bbox overlap IoU
- limb endpoint proximity
- lane boundary penetration

경고→pause 두 단계와 500ms 내외 hysteresis를 둔다. 값은 실제 2인 테스트로 확정한다.

### 반복 피로

- 동일 동작 연속 요구 횟수 상한
- 60~90초 플레이 후 선택적 휴식 안내
- low-intensity mode에서 range와 hold time를 완화하되 precision을 해치지 않음
- 과속 동작에 점수 추가 없음

## 낚시

### 암묵적 제약

- 후면 캐스팅처럼 큰 팔 스윙을 요구하면 좁은 공간에서 위험하다. shoulder 뒤로 크게 보내는 대신 몸 옆·위의 안전 범위에서 시작하도록 튜토리얼한다.
- 릴은 카메라 depth보다 2D phase가 중요하다. 원형 완벽도를 요구하지 않는다.
- 장력 제어가 detection dropout 때문에 파손으로 이어지면 불공정하다.

### 회복 설계

- 짧은 pose loss 동안 tension은 중립으로 감쇠하고 즉시 line break하지 않는다.
- 협동 역할 하나가 실패하면 recovery window를 준다.
- rare fish 보정은 실패 streak를 사용하되 시드 replay에서 결정적이어야 한다.
- 역할 교대는 자동이 아니라 결과 화면 또는 명시적 gesture/UI로 한다.

### score saturation

캐스팅 범위와 릴 속도는 안전 상한 이후 점수가 증가하지 않는다. 더 큰 동작이 아니라 적정 timing과 안정성이 최고 점수다.

## 권투

### 신체 접촉 방지

- 두 플레이어는 모두 카메라 방향을 본다.
- 게임 속 캐릭터가 서로 마주보더라도 사용자에게는 좌우가 아니라 전방 펀치 안내를 준다.
- 상대 player bbox 방향으로 wrist velocity가 향하고 거리가 가까우면 warning/pause 후보로 본다.

### stance

- orthodox/southpaw를 calibration에서 선택하거나 자동 후보 후 사용자 확인한다.
- front/back hand 매핑만 바뀌며 damage가 달라지지 않는다.
- stance가 불명확하면 `JAB_LEFT/RIGHT`처럼 해부학적 event로 보내고 game mapping에서 결정한다.

### attack resolution

- forward depth ambiguity 때문에 화면상 큰 손목 이동만 보상하지 않는다.
- return-to-guard를 콤보 re-arm 조건으로 사용한다.
- 같은 손 연타 stamina penalty는 event 중복과 구분되어야 한다.
- AI telegraph는 end-to-end P95 latency보다 충분히 길어야 한다. 기기 성능 저하가 예고 시간을 침식하면 게임을 pause하거나 난이도 floor를 적용한다.

## 몬스터·보스

### 역할 충돌

Vanguard block과 Mage charge처럼 비슷한 양팔 자세는 exclusivity group과 game context로 구분한다. context만으로 낮은 confidence gesture를 확정하지 않는다.

### 협동 필살기

- 두 player event-time을 비교한다.
- 첫 성공 후 상대 대기 중에는 명확한 진행 UI를 제공한다.
- 한 명 tracking loss는 즉시 실패가 아니라 window pause 또는 재시도한다.
- 실패해도 즉사·전체 자원 소멸을 피한다.

### Solo AI

AI 동료는 이동, 기본 공격, 위험 회피만 수행한다. 다음은 대신할 수 없다.

- 협동 필살기 trigger의 player half
- 부활 gesture
- weak-point 핵심 action
- 튜토리얼 목표

AI만으로 무입력 클리어 가능한 replay는 critical failure다.

## 결과·보상

- pending reward는 result commit 전 checkpoint에 기록한다.
- app kill 시 중복 지급되지 않도록 reward transaction ID를 사용한다.
- 접근성 mode 사용 여부는 개인 낙인·감점으로 표시하지 않는다.
- cosmetic unlock은 asset catalog revision과 호환되어야 한다. 삭제된 assetId는 migration alias를 둔다.
