# Risk Register

| ID | 위험 | 가능성 | 영향 | 조기 신호 | 통제·완화 | 판정 |
|---|---|---:|---:|---|---|---|
| R-01 | 후면 카메라 사용자는 화면을 볼 수 없음 | 높음 | 높음 | 자세 안내·보스 예고를 놓침 | 전면 기본, 외부 화면/감독자/가시 배치 확인, 미충족 시 full-play 차단 | Manual review required |
| R-02 | 2인 Pose가 목표 기기에서 15 FPS 미만 | 중간~높음 | 높음 | 큐 drop 증가, P95 반응 지연 | capability probe, 해상도·빈도 축소, 듀얼 호환성 차단 | Open |
| R-03 | 전면 미러·crop 불일치로 좌우 판정 역전 | 중간 | 높음 | overlay 10% 이상 오차 | 단일 matrix contract, synthetic/golden tests, ViewPort 공유 | Controlled by gate |
| R-04 | 교차·가림 중 P1/P2 역할 swap | 높음 | 높음 | assignment cost 차이 축소 | AMBIGUOUS state, no auto swap, pause/re-arm | Controlled by gate |
| R-05 | 2D 포즈로 전방 펀치 깊이 해석이 불안정 | 높음 | 중간~높음 | 잽/가드 confusion | 팔꿈치 각·손목 경로·어깨 회전 결합, front-facing tutorial, 제거 기준 | Open |
| R-06 | 릴 원형 동작이 Pose만으로 불안정 | 높음 | 중간 | 손목 궤적 단절 | 팔꿈치 phase fallback, Hand는 optional, 단순화 gate | Open |
| R-07 | 캘리브레이션 후 거리·조명 변화로 threshold drift | 높음 | 중간 | confidence/scale 변화 | drift monitor, soft warning, re-calibration checkpoint | Controlled |
| R-08 | 프레임 재콜백·회전으로 이벤트 중복 적용 | 중간 | 높음 | 한 동작에 두 공격 | eventId/sequence/dedupe window, exactly-once tests | Controlled |
| R-09 | 열화로 추론 지연이 누적되어 판정이 늦음 | 높음 | 높음 | dropped frames, thermal tier 상승 | KEEP_ONLY_LATEST, monotonic latency, ordered degradation | Controlled |
| R-10 | minSdk 지원 기기 편차가 과도함 | 높음 | 높음 | camera bind/model delegate failure | capability-based compatibility, 기기명 추정 금지 | Open |
| R-11 | process death/회전 중 세션 손상 | 중간 | 높음 | timer/HP reset | versioned checkpoint, atomic write, state owner 분리 | Controlled |
| R-12 | 오프라인이라 현장 장애 데이터 부족 | 높음 | 중간 | 재현 불가 사용자 보고 | 로컬 ring buffer, opt-in redacted export, support code | Residual |
| R-13 | 생성 에셋의 게임 내 스타일 드리프트 | 높음 | 중간~높음 | 선 굵기·광원·비율 불일치 | ImageGen skill SSOT, anchor references, contact sheet gate | Controlled |
| R-14 | 생성물 약관·재배포 상태 오판 | 중간 | 높음 | provenance만 있고 법적 검토 없음 | 적용 약관 캡처/검토, 상태를 PENDING으로 유지 | Manual review required |
| R-15 | fixture가 같은 사용자/세션을 공유해 지표 과대평가 | 높음 | 높음 | tune과 test 성능 차이 없음 | subject/session holdout, frozen release set | Controlled by gate |
| R-16 | 위험 동작을 높은 점수로 유도 | 중간 | 높음 | 과속·과진폭 반복 | saturation, repetition fatigue, safety pause | Controlled |
| R-17 | `FLAG_SECURE`가 접근성·지원 흐름을 방해 | 중간 | 중간 | 화면 공유·스크린리더 문제 | 카메라 화면만 적용 검토, 기기 테스트, ADR 변경 | Open |
| R-18 | 모델/의존성 공급망 변조 | 낮음~중간 | 높음 | hash mismatch | dependency verification, model hash, locked versions | Controlled |
| R-19 | 효과가 포즈 안내·카메라 inset을 가림 | 중간 | 중간 | tutorial 실패 증가 | HUD safe zones, asset review gate, effect opacity cap | Controlled |
| R-20 | 솔로 AI 동료가 핵심 행동을 대신함 | 중간 | 중간 | 플레이어 무입력 클리어 | AI capability invariant, replay tests | Controlled |

## Top 5 선행 검증

1. 후면 카메라 화면 가시성 setup을 실제 사용자로 검증한다.
2. 중급·저사양 대표 기기에서 듀얼 Pose 10초 capability probe를 실행한다.
3. 좌표 변환 golden fixture를 실제 overlay 캡처와 대조한다.
4. 서로 교차·가림·이탈하는 2인 시퀀스로 ID timeline을 검증한다.
5. 게임별 anchor board를 ImageGen skill로 먼저 만들고 style revision을 잠근다.
