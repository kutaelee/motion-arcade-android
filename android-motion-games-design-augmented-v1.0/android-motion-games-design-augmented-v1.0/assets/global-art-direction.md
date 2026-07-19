# Global Art Direction

## 공통 시각 언어

세 게임은 각각 다른 세계관을 갖지만 한 앱의 제품군으로 보이도록 다음을 공유한다.

- 2D mobile arcade cartoon
- 작은 화면에서 읽히는 굵은 silhouette
- dark colored outline, 배경보다 높은 value contrast
- 캐릭터 3~4단계 명암, 복잡한 texture 최소화
- 상단 왼쪽 계열 key light
- 둥근 모서리와 과장된 action curve
- UI text는 Android에서 별도 렌더링
- pose guide와 안전 lane보다 FX가 앞서지 않음

## 공통 geometry token

- character head-to-body: 약 1:4.5~1:5.5 범위
- hands/weapons: 실제 비율보다 10~20% 과장
- outline: 1024px master 기준 10~16px 상당, asset family 내 변동 20% 이내
- corner radius: hub UI는 큰 radius, game props는 style profile에 따라 조정
- shadow: soft blob 또는 2-step cel shadow, realistic cast shadow 금지

## 공통 camera/projection

- 캐릭터: front 또는 3/4 front, 게임 내 facing variant는 명시
- props: orthographic-like 3/4, 과도한 perspective 금지
- background: side-view/arena view를 style profile에서 고정
- icon: centered, 12% safe margin, transparent background

## Accessibility

- 상태 차이는 색만으로 표현하지 않는다.
- red/green 단독 구분 금지
- 64px에서도 silhouette 식별
- flashes는 강도 설정과 안전 기준을 따름
- FX opacity/coverage cap으로 카메라 inset·pose guide 보호

## Cross-game reuse

재사용 허용:

- 공통 설정/카메라/접근성 glyph
- 공통 보상 shell과 neutral coin shape
- 공통 badge frame

재사용 금지:

- 낚시 물보라를 몬스터 마법으로 사용
- 권투 hit spark를 몬스터 slash로 사용
- 캐릭터 body parts를 다른 game profile로 이동

공통 glyph라도 각 game HUD에 들어갈 때 game-specific frame/background와 조합한다.
