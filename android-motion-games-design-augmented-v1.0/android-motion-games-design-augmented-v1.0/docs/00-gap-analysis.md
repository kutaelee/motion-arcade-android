# 놓친 암묵지 분석

원본 설계는 기능 범위와 책임 분리를 상세히 정의했지만, 구현자가 각자 해석하면 다른 결과가 나오는 “운영 계약” 일부가 빠져 있다. 아래 항목은 기능 추가가 아니라 실패 가능성을 줄이는 보강이다.

| Gap | 암묵적으로만 존재한 문제 | 잘못 구현될 때 영향 | 보강 결정 |
|---|---|---|---|
| G-01 | 후면 카메라 사용 시 플레이어가 화면을 보기 어렵다 | 튜토리얼·예고 미인지, 안전 저하 | rear-camera usability gate와 full-play 조건 추가 |
| G-02 | Camera timestamp, callback time, game tick의 시간 기준이 다르다 | cooldown·반응 지연·재생 불일치 | monotonic event-time contract 고정 |
| G-03 | Preview crop/letterbox가 분석 좌표와 다르다 | skeleton overlay 및 레인 판정 오차 | 좌표 공간과 matrix chain 명시 |
| G-04 | LIVE_STREAM callback과 game loop의 동시성 정책이 없다 | 프레임 누적·중복 이벤트·race | bounded pipeline과 ownership 고정 |
| G-05 | 모델 warm-up/delegate 실패 대응이 없다 | 첫 게임 정지, 특정 SoC crash | capability probe와 fallback 추가 |
| G-06 | 단순 X 매칭 외 assignment 확정 기준이 없다 | P1/P2 swap | ambiguity state와 cost margin 추가 |
| G-07 | 왼손잡이·권투 stance·한쪽 팔 모드가 임계값에 반영되지 않는다 | 접근성·정확도 저하 | calibration profile에 dominant side/stance 추가 |
| G-08 | 캘리브레이션 이후 거리·조명이 변할 수 있다 | 시간이 지날수록 오인식 증가 | drift monitor와 재캘리브레이션 checkpoint 추가 |
| G-09 | confirmed gesture의 exactly-once 의미가 없다 | 한 번의 동작이 두 번 적용 | eventId/sequence/dedupe contract 추가 |
| G-10 | seed만 저장하면 중간 복원 시 난수 상태가 달라진다 | 보상·AI 재현 실패 | PRNG 알고리즘 버전과 내부 상태 저장 |
| G-11 | process death, camera failure, activity rotation 상태가 섞여 있다 | 세션 초기화·중복 resume | lifecycle state machine 분리 |
| G-12 | minSdk만으로 듀얼 지원을 가정한다 | 저사양 기기에서 사용 불가 | 측정 기반 device tier와 mode compatibility 추가 |
| G-13 | 평균 FPS 목표만으로 체감 품질을 판단할 위험 | tail latency 은폐 | P90/P99, event age, drop rate gate 추가 |
| G-14 | 오프라인 앱의 장애 조사 방법이 없다 | 현장 재현 불가 | redacted local diagnostic bundle 추가 |
| G-15 | fixture 분할 규칙이 없다 | 같은 사용자 데이터 누수로 지표 과대평가 | subject/session holdout 규칙 추가 |
| G-16 | 2D 카메라의 깊이 모호성에 대한 게임별 fallback이 없다 | 잽·릴 동작 불안정 | 복합 특징과 단순화/제거 stop condition 추가 |
| G-17 | 안전 공간을 픽셀로 정의하기 어렵다 | 렌즈별 잘못된 거리 판단 | body occupancy·lane overlap 기반 상대 지표 사용 |
| G-18 | 모션 실패와 게임 난이도가 섞일 수 있다 | 불공정, 사용자 탓으로 보임 | detection quality와 game difficulty 분리 |
| G-19 | SoundPool/audio focus/lifecycle가 빠져 있다 | 중복 사운드·백그라운드 재생 | 오디오 상태 계약 추가 |
| G-20 | generated asset의 일관성 유지 방법이 추상적이다 | 게임별 이질감·캐릭터 drift | ImageGen skill, style anchor, prompt registry를 SSOT로 고정 |
| G-21 | 생성물 provenance와 사용 권리 검토 상태가 분리되지 않는다 | 라이선스 완료 오판 | provenance와 redistribution review를 별도 필드로 관리 |
| G-22 | drawable density/alpha/color space 규칙이 없다 | 기기별 크기·테두리 차이 | nodpi, sRGB, 투명 여백, pivot 계약 추가 |
| G-23 | UI 텍스트와 이미지가 섞일 위험 | 현지화·접근성 불가 | 이미지 내 텍스트 금지, string resource only |
| G-24 | build supply-chain 검증이 고정되지 않는다 | 종속성 변조·재현 실패 | wrapper checksum, dependency verification, model hash 추가 |
| G-25 | live camera 화면의 OS screenshot/recording 경계가 없다 | 원본 영상이 사용자 의도 밖에 저장될 수 있음 | `FLAG_SECURE` 후보와 접근성 검증 gate 추가 |

## 보수적 기본값

증거가 없을 때 다음을 기본으로 한다.

- 전면 카메라를 기본값으로 사용한다.
- 듀얼 성능 기준 미달 기기에서는 듀얼 모드를 노출하지 않는다.
- 추적 모호성은 ID swap보다 pause를 선택한다.
- 인식 품질이 낮으면 실패 판정이나 감점보다 “입력 보류”를 선택한다.
- 에셋 anchor가 승인되지 않았으면 나머지 에셋을 생성하지 않는다.
- 생성물 이용 권리 검토가 끝나지 않았으면 release artifact에 넣지 않는다.
