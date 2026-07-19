# Runbook — 후면 카메라 사용성

## 문제

후면 카메라는 화질·FOV가 좋을 수 있지만, 일반적인 설치에서는 플레이어가 화면을 볼 수 없다. “후면 카메라 지원”과 “후면 카메라로 실제 게임 가능”은 별도 claim이다.

## 허용 setup

1. 외부 화면/OS 미러링으로 HUD와 자세 안내를 볼 수 있음
2. 보호자/코치가 화면을 보고 음성으로 안내하는 감독형 setup
3. 기기 각도·거리상 플레이어가 화면을 직접 볼 수 있다는 실제 검증

네트워크 멀티플레이나 앱 내 스트리밍을 새로 추가하지 않는다. 외부 화면은 OS 기능/유선 출력일 수 있다.

## 시작 전 gate

- 사용자가 HUD를 볼 수 있는지 확인
- 3초 countdown, 보스 telegraph, pause reason을 식별하는 연습
- 카메라 배치가 넘어지지 않고 안전한지 확인
- 전신/상반신 FOV 확인
- 결과 확인 방법 안내

## 실패 정책

- 화면 가시성 확인 실패: 전면 카메라 권고 또는 gameplay 차단
- 외부 화면 연결이 끊김: game pause
- audio-only 안내만으로 안전/게임 규칙을 충족하지 못하면 release하지 않음

## Required evidence

- setup 사진(테스트 환경, 실제 사용자 동의)
- 사용자가 5개 핵심 UI cue를 인지한 결과
- front/back task completion 비교
- 안전 observer 기록

증거 전 판정: `Manual review required`.
