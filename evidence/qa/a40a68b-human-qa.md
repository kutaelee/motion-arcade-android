# Motion Arcade a40a68b 사람 QA 요청

## 정확한 후보

- APK: [app-arm64-v8a-debug.apk](https://github.com/kutaelee/motion-arcade-qa-downloads/releases/download/qa-a40a68b/app-arm64-v8a-debug.apk)
- 대상: ARM64 Android 실기기, debug 서명 QA 전용
- SHA-256: `bb9e81ad50039a8cd66de1bf0488ce005713313fea128f2c4fd2b6fe84f4a3c0`
- 자동 검증: JVM 단위 테스트 871 통과, 실패 0, 스킵 0
- 미승인: 실기기 모션 인식, 시각 에셋, 재배포, 앵커, 발열/성능, 릴리스 서명

## A. 공통 시각 QA

- [ ] 9:16 세로 화면에서 8비트/픽셀/혼합 스타일이 아니라 현대적인 질감으로 보인다.
- [ ] 네 프레임에서 얼굴·체형·의상·머리카락·장갑·낚싯대·릴·소품 정체성이 유지된다.
- [ ] 예비 동작→정점→회복 중 머리카락·천·끈·루어·낚싯줄·파티클 관성이 자연스럽다.
- [ ] 팔·다리·의상·머리카락·무기·낚싯줄·소품이 서로 부자연스럽게 교차/관통하지 않는다.
- [ ] 이미지 안에 의도하지 않은 글자·숫자·로고·워터마크·프랜차이즈 유사성이 없다.
- [ ] 공통 HUD가 점수/콤보/상태를 가리지 않고 코드 텍스트가 읽힌다.

검수 원본:

- [낚시 캐스팅 4프레임](https://github.com/kutaelee/motion-arcade-qa-downloads/releases/download/qa-a40a68b/fishing_gameplay_cast_sequence_v2.png)
- [낚시 포획·계측 4프레임](https://github.com/kutaelee/motion-arcade-qa-downloads/releases/download/qa-a40a68b/fishing_catch_measure_sequence_v2.png)
- [낚싯대 선택 4프레임](https://github.com/kutaelee/motion-arcade-qa-downloads/releases/download/qa-a40a68b/fishing_rod_selection_v2.png)
- [격투 캐릭터 선택 4프레임](https://github.com/kutaelee/motion-arcade-qa-downloads/releases/download/qa-a40a68b/boxing_character_selection_v2.png)
- [격투 공격·피격·회복 4프레임](https://github.com/kutaelee/motion-arcade-qa-downloads/releases/download/qa-a40a68b/boxing_combat_exchange_sequence_v2.png)
- [공통 HUD 4프레임](https://github.com/kutaelee/motion-arcade-qa-downloads/releases/download/qa-a40a68b/hud_score_combo_sequence_v2.png)
- [협동 전투 4프레임](https://github.com/kutaelee/motion-arcade-qa-downloads/releases/download/qa-a40a68b/monster_combat_coop_sequence_v2.png)

## B. 실기기 게임/모션 QA

- [ ] 낚시: 1인칭 배 위 시점, 캐스팅, 물고기 포획·길이 계측, 낚싯대 선택 화면이 정상 표시된다.
- [ ] 격투: 캐릭터 선택, 좌우 대결, 공격·피격·회복 순서가 읽히며 두 사람 역할이 뒤바뀌지 않는다.
- [ ] 협동 1P+AI: 한 사람만으로 진입하고, 두 번째 사람이 들어오면 즉시 안전 정지한다.
- [ ] 협동: 동료 방향 양팔 뻗기 1.5초 부활, raised-V 800ms 마법 충전, 클래스 기술이 의도대로 발동한다.
- [ ] 협동 2P: 동시 필살기, 한 명 이탈 시 즉시 정지, 재진입/re-arm 후 점수·체력·웨이브가 중복되지 않는다.
- [ ] 각 게임 10분 플레이 중 눈에 띄는 입력 지연, 프레임 급락, 발열 경고, 앱 종료가 없다.

## 결과 작성

댓글에 다음만 남겨 주세요.

1. 기기 모델 / Android 버전 / 화면 방향
2. `SHA-256 일치` 또는 불일치
3. 통과한 체크와 실패한 체크 번호
4. 실패 시 발생 시각, 재현 순서, 기대/실제
5. 필요할 때만 얼굴·주변 개인정보를 가린 짧은 영상 또는 스크린샷
6. 시각 에셋별 `승인`/`반려` 및 재배포 허용 여부

수정 요청은 PNG 수작업 지시가 아니라 해당 asset ID와 실패 프레임을 지정해 주세요. 결과와 무관한 구현은 계속 진행합니다.
