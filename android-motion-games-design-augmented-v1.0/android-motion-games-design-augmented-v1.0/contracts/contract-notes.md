# Runtime Contract Notes

## JSON schema의 역할

이 스키마는 Kotlin/Proto의 완전한 대체가 아니라, 이벤트·fixture·진단·매니페스트를 독립 검증하기 위한 최소 계약이다. Kotlin data class와 필드명·범위·enum이 불일치하면 G1 실패다.

## 추가 권고

- `MotionType`은 game-independent core enum과 game mapping을 분리한다.
- metadata는 임시 escape hatch다. 안정 필드는 정식 property로 승격한다.
- float NaN/Infinity는 JSON에서 허용하지 않는다.
- pose fixture는 normalized coordinates만 사용하고 camera frame을 포함하지 않는다.
- session `state`는 게임별 별도 schema로 확장한다.
- schemaVersion migration fixture를 최소 이전 2개 버전 유지한다.
