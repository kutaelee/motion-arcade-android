# Release Gate Checklist

- [ ] G0 저장소·공급망
- [ ] G1 계약·결정성
- [ ] G2 카메라·포즈
- [ ] G3 gesture 품질
- [ ] G4 2인 identity·공정성
- [ ] G5 안전·후면 카메라 UX
- [ ] G6 ImageGen asset SSOT·일관성
- [ ] G7 개인정보·보안
- [ ] G8 성능·발열
- [ ] G9 전체 기능·수동 승인

## Critical no-go

- [ ] raw frame 저장/업로드 없음
- [ ] INTERNET/마이크/불필요 권한 없음
- [ ] arbitrary P1/P2 swap 없음
- [ ] low-confidence 입력으로 state 진행 없음
- [ ] dynamic dependency 없음
- [ ] registry 밖 master asset 없음
- [ ] 외부 에셋 혼합 없음
- [ ] 생성물 권리 검토 상태 허위 표시 없음

최종 판정: `Approve / Approve with conditions / Manual review required / Additional data needed / Reject / Rework`
