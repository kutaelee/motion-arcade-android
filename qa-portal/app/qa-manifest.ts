export type QaTask = {
  id: string;
  slice: string;
  title: string;
  status: "ready" | "waiting_build" | "waiting_approval" | "not_requested";
  whyHuman: string;
  checks: readonly string[];
  evidence: readonly string[];
  waitingReason?: string;
  imagePath?: string;
  imageAlt?: string;
};

export const qaManifest = {
  repositoryUrl: "https://github.com/kutaelee/motion-arcade-android",
  build: {
    displayName: "Upper-body reasons + game selection · Physical QA 14",
    scopeWarning:
      "게임·인원 선택, 부분 신체 인식 안내, 16:9 낚시 전장, 가장자리 HUD와 v1.0 ImageGen 에셋을 확인하는 ARM64 후보입니다. 듀얼 낚시 협동 역할·듀얼 복싱 PvP·최종 에셋 승인은 아직 완료되지 않았습니다.",
    signing: "debug-signed physical QA prerelease",
    packageName: "com.motionarcade.app.debug",
    sha256: "49af2c78747067282722c39eb66d326dc1da8566928e1f565fb4310b82ad5193",
    fileName: "app-arm64-v8a-debug.apk",
    releaseTag: "upper-body-reasons-qa-14",
    releaseUrl:
      "https://github.com/kutaelee/motion-arcade-qa-builds/releases/tag/upper-body-reasons-qa-14",
    downloadUrl:
      "https://github.com/kutaelee/motion-arcade-qa-builds/releases/download/upper-body-reasons-qa-14/app-arm64-v8a-debug.apk",
    releaseState: "active_prerelease",
    notIncluded: ["에셋 사람 승인", "듀얼 낚시 협동 역할", "듀얼 복싱 PvP", "성능 릴리스 게이트"],
  },
  submission: {
    newIssueUrl: "https://github.com/kutaelee/motion-arcade-android/issues/new",
  },
  requiredContext: [
    {
      label: "기기와 OS",
      prompt: "제조사·정확한 모델명, Android 버전, 보안 패치 날짜를 기록합니다.",
    },
    {
      label: "렌즈와 모드",
      prompt: "전면 렌즈, 2인 역할 설정 여부, 화면 방향을 요청 범위에 맞춰 기록합니다.",
    },
    {
      label: "정확한 빌드",
      prompt: "APK 파일명과 SHA-256을 적어 다른 바이너리 결과가 섞이지 않게 합니다.",
    },
    {
      label: "보이는 사실",
      prompt: "발생 시각, 재현 순서, 기대와 실제를 쓰고 추측한 원인은 분리합니다.",
    },
    {
      label: "최소 증거",
      prompt: "개인정보를 제거한 스크린샷·짧은 영상·관련 logcat만 첨부합니다.",
    },
  ],
  tasks: [
    {
      id: "slice-0a-physical-smoke",
      slice: "Slice 0A",
      title: "비대화형 기술 베이스라인 · 사람 QA 미요청",
      status: "not_requested",
      whyHuman:
        "이 아티팩트는 계약·빌드·공급망 경로 고정용이며 카메라, 모션 인식, 게임 플레이가 없어 사람 인식 QA의 대상이 아닙니다.",
      checks: [
        "실행하면 Motion Arcade 제목과 Slice 0A contract and supply-chain baseline 문구만 표시됩니다.",
        "다음 화면, 버튼, 카메라 권한 요청, 모션 인식 또는 게임 플레이는 존재하지 않습니다.",
      ],
      evidence: [
        "빌드·설치·시작 자동화 증거는 Slice 0A 증거 번들에서 관리합니다.",
        "사람 QA 증거는 실제 모션 인식 또는 게임 플레이 빌드부터 요청합니다.",
      ],
      waitingReason:
        "현재 활성 요청이 아닙니다. 실제 플레이 가능한 후속 APK가 준비되면 별도 작업이 열립니다.",
    },
    {
      id: "slice-1b-live-camera-probe",
      slice: "Slice 1B",
      title: "전면 카메라 프리뷰 실기기 확인",
      status: "not_requested",
      whyHuman:
        "이 범위는 QA 8의 2인 역할 추적 요청에 포함되므로 별도 결과 제출을 요구하지 않습니다.",
      checks: [
        "처음 실행해 카메라 권한을 거부했을 때 앱이 멈추거나 빈 화면이 되지 않는지 확인합니다.",
        "설정에서 권한을 허용하거나 다시 요청해 전면 영상과 ‘카메라 프리뷰 활성’ 상태가 나타나는지 확인합니다.",
        "세로와 가로 방향에서 영상이 찌그러지거나 90/180도 잘못 회전하지 않는지 확인합니다.",
        "오른손을 들었을 때 화면이 자연스러운 셀피 미러 방향으로 반응하는지 확인합니다.",
        "홈 화면으로 나갔다 돌아오고, 화면을 잠갔다 해제한 뒤 프리뷰가 한 번만 정상 복귀하는지 확인합니다.",
        "두 사람이 머리부터 발끝까지 들어오도록 섰을 때 과도한 crop이나 검은 띠가 없는지 확인합니다.",
      ],
      evidence: [
        "정확한 기기 모델·Android 버전·화면 방향",
        "APK 파일명과 SHA-256 확인 결과",
        "권한 거부 화면과 활성 프리뷰의 개인정보 제거 스크린샷",
        "실패했다면 재현 순서와 짧은 영상 또는 관련 logcat",
      ],
      waitingReason: "동일 APK의 Slice 2B 요청으로 통합됐습니다.",
    },
    {
      id: "modern-entry-partial-body-hud",
      slice: "Slice 1B–6 UI",
      title: "게임·인원 선택, 부분 신체 인식, 16:9 HUD 확인",
      status: "ready",
      whyHuman:
        "안전영역·폰 화면비·카메라 crop에 따라 HUD가 동작을 가리는지와 필요한 관절만 보일 때 실제 동작이 받아들여지는지는 실기기에서 확인해야 합니다.",
      checks: [
        "첫 화면에서 낚시·복싱·몬스터 중 하나를 고른 뒤 1인 또는 2인을 선택할 수 있고, 뒤로 가면 게임 선택으로 돌아오는지 확인합니다.",
        "무릎과 발목은 화면 밖에 두되 어깨·팔꿈치·손목·엉덩이는 보이게 한 상태에서 1인 모션 입력이 가능한지 확인합니다.",
        "카메라 권한 거부, 사람 0명, 2인 모드에서 한 명만 보임, 두 사람 겹침을 각각 만들고 이유와 해결 행동이 포함된 토스트가 보이는지 확인합니다.",
        "낚시 화면의 실제 전장이 16:9로 보이고 상단 상태·하단 미터·카메라/게임 선택 버튼이 낚싯대, 물고기, 물보라와 몸 동작을 가리지 않는지 확인합니다.",
        "복싱과 몬스터에서 캐릭터·배경·FX가 구식 8비트가 아닌 한 스타일로 보이고, 공격·방어·피격 상태가 구분되는지 확인합니다.",
      ],
      evidence: [
        "게임 선택 → 인원 선택 → 각 게임 진입의 짧은 영상",
        "무릎·발목이 화면 밖인 상태의 인식 성공/실패 결과",
        "각 실패 조건의 토스트 문구 스크린샷",
        "낚시 16:9 전장과 HUD가 함께 보이는 세로·가로 스크린샷",
      ],
    },
    {
      id: "slice-2b-dual-role-safety",
      slice: "Slice 2B",
      title: "2인 역할 설정·안전 정지·re-arm 실기기 QA",
      status: "ready",
      whyHuman:
        "두 사람이 교차하거나 가려질 때 역할을 임의로 바꾸지 않고 멈추는지, 그리고 실제 카메라에서만 보이는 crop·조명·프레임 지연이 없는지 자동 테스트만으로 판정할 수 없습니다.",
      checks: [
        "전면 카메라로 두 사람이 전신이 보이게 서면 BOXING 또는 MONSTER에 ‘2P camera setup / safety hold’가 나타나는지 확인합니다.",
        "P1은 분석 화면의 왼쪽, P2는 오른쪽에 선 채 ‘Set / re-arm P1 + P2’를 누르고 약 1초 동안 중립 자세를 유지합니다. 그 전에는 게임 재개와 터치 입력이 열리지 않아야 합니다.",
        "안전 상태가 된 뒤 두 사람이 서로 자리를 가로질러 지나가거나 몸이 겹치게 섭니다. 안전 정지 문구가 나타나고 라운드 타이머·점수 변화가 멈추는지 확인합니다.",
        "교차·가림 뒤에는 두 사람이 다시 원래 역할 위치로 돌아가 중립 자세를 유지하고 re-arm을 다시 눌러야만 입력이 열리는지 확인합니다. 자동으로 다시 열리면 실패입니다.",
        "홈 화면으로 나갔다 돌아오거나 화면을 잠갔다 해제한 뒤에도 새 역할 설정 없이 입력이 자동으로 열리지 않는지 확인합니다.",
      ],
      evidence: [
        "얼굴을 가린 짧은 화면 영상 또는 스크린샷: setup hold, active, 교차/가림 pause, re-arm 결과",
        "기기·Android 버전·화면 방향·두 사람의 거리·조명 조건",
        "안전 정지가 없었거나 역할이 뒤바뀐 것으로 보인 정확한 재현 순서와 시각",
      ],
    },
    {
      id: "g6-v1-runtime-art-review",
      slice: "Release Gate G6",
      title: "Fishing·Boxing·Monster v1.0 런타임 에셋 눈검수",
      status: "ready",
      whyHuman:
        "ImageGen 계보와 해시는 자동 검증할 수 있지만, 현대적인 시각 품질·캐릭터 정체성·관성·가림·신체 및 소품 관통은 사람의 눈으로 승인해야 합니다.",
      checks: [
        "세 게임 모두 현대적인 고해상도 셀 애니메이션 질감으로 보이고 픽셀 아트·8비트·혼합 스타일로 보이지 않는지 확인합니다.",
        "낚시 항구·낚싯대·7종 물고기·물 FX가 같은 색·외곽선·조명 규칙을 유지하는지 확인합니다.",
        "복싱 선수 4명의 정체성이 구분되고 링·공격·방어·피격 FX와 스타일이 맞는지 확인합니다.",
        "몬스터 두 영웅, 일반 몬스터, 3단계 보스가 얼굴·체형·의상·무기 정체성을 유지하는지 확인합니다.",
        "머리카락, 망토, 의상, 무기, 방패와 파티클이 동작 방향에 맞고 몸·소품·효과가 부자연스럽게 관통하지 않는지 확인합니다.",
        "의도하지 않은 글자·숫자·로고·상표·프랜차이즈 유사성·공포·고어가 없는지 확인합니다.",
      ],
      evidence: [
        "게임별 승인/반려와 실패한 체크 번호 및 화면 위치",
        "alpha 테두리, 잘린 실루엣, HUD 가림 또는 교차가 보이면 해당 스크린샷",
        "재배포 승인 여부와, 수정이 필요하면 이미지 수작업 지시가 아닌 prompt registry 변경 요청",
      ],
    },
    {
      id: "g8-three-games-thermal",
      slice: "Release Gate G8",
      title: "게임 3종 지속 플레이·발열 관찰",
      status: "ready",
      whyHuman:
        "대표 게임 렌더링을 포함한 장시간 체감 지연, 프레임 끊김, 기기 발열과 throttling은 실제 기기에서만 승인할 수 있습니다.",
      checks: [
        "각 게임을 지정 시간 동안 2인으로 연속 플레이합니다.",
        "입력과 화면 반응이 눈에 띄게 어긋나는 순간을 타임코드로 기록합니다.",
        "발열 경고, 밝기 저하, 프레임 급락, 앱 종료 여부를 관찰합니다.",
      ],
      evidence: [
        "게임/모드별 시작·종료 시각과 기기 상태",
        "문제가 나타난 구간의 짧은 화면 영상",
        "앱이 내보내는 집계 성능 증거(원시 개인 데이터 제외)",
      ],
    },
    {
      id: "g6-style-anchor-review",
      slice: "Release Gate G6",
      title: "게임별 ImageGen 스타일 앵커 승인",
      status: "ready",
      whyHuman:
        "스타일 일관성과 게임별 구분 가능성은 생성 계보·자동 검사만으로 승인할 수 없는 시각 판단입니다.",
      checks: [
        "게임별 승인 후보가 해당 style profile과 prompt registry 설명에 맞는지 비교합니다.",
        "세 게임이 한 제품군으로 보이면서도 플레이 중 즉시 구분되는지 확인합니다.",
        "텍스트 왜곡, 혼합 스타일, 의도하지 않은 상표·인물이 없는지 확인합니다.",
      ],
      evidence: [
        "후보 asset ID와 prompt registry revision",
        "승인/반려 및 구체적인 관찰 근거",
        "수정이 필요하면 결과 이미지가 아닌 registry 변경 요청",
      ],
    },
  ] as readonly QaTask[],
} as const;
