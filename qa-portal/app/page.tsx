import { qaManifest, type QaTask } from "./qa-manifest";
import { CopyButton } from "./copy-button";

const statusLabel: Record<QaTask["status"], string> = {
  ready: "지금 확인 가능",
  waiting_build: "해당 빌드 대기",
  waiting_approval: "승인 후 공개",
  not_requested: "QA 요청 안 함",
};

function issueUrl(task: QaTask) {
  const url = new URL(qaManifest.submission.newIssueUrl);
  url.searchParams.set("title", `[Physical QA] ${task.id} · 기기명`);
  url.searchParams.set(
    "body",
    [
      "## 검증 빌드",
      `- APK: ${qaManifest.build.fileName}`,
      `- SHA-256: ${qaManifest.build.sha256}`,
      "",
      "## 기기",
      "- 제조사·모델:",
      "- Android 버전·보안 패치:",
      "- 화면 방향:",
      "",
      "## 관찰 체크리스트",
      ...task.checks.map((check) => `- [ ] ${check}`),
      "",
      "## 관찰한 사실과 재현 순서",
      "",
      "## 필요한 경우에만 첨부",
      ...task.evidence.map((item) => `- ${item}`),
      "",
      "> 원인 추측과 관찰 사실을 구분하고, 얼굴·집 내부·알림 등 개인정보는 제거해 주세요.",
    ].join("\n"),
  );
  return url.toString();
}

export default function Home() {
  const readyCount = qaManifest.tasks.filter((task) => task.status === "ready").length;

  return (
    <>
      <a className="skip-link" href="#qa-tasks">
        QA 요청으로 바로 이동
      </a>
      <main>
        <header className="hero" aria-labelledby="page-title">
          <nav className="topbar" aria-label="상단 탐색">
            <a className="wordmark" href="#top" aria-label="Motion Arcade QA 홈">
              <span aria-hidden="true" className="wordmark-mark">
                M·A
              </span>
              <span>Motion Arcade / QA Desk</span>
            </a>
            <a className="text-link" href={qaManifest.repositoryUrl}>
              GitHub 저장소
            </a>
          </nav>

          <div id="top" className="hero-grid">
            <div className="hero-copy">
              <p className="eyebrow">Physical truth, without blocking the build</p>
              <h1 id="page-title">
                사람만 볼 수 있는 것을,
                <br />
                정확히 요청합니다.
              </h1>
              <p className="lede">
                자동화가 증명할 수 없는 실기기 관찰만 모으는 QA 창구입니다. 결과를
                기다리는 동안 코드·테스트·게임 구현은 계속 진행됩니다.
              </p>
              <div className="hero-actions">
                {readyCount > 0 ? (
                  <a className="button button-primary" href="#qa-tasks">
                    요청 {readyCount}건 확인
                  </a>
                ) : (
                  <p className="hero-status" role="status">
                    활성 QA 요청 없음
                  </p>
                )}
                <a className="button button-secondary" href="#evidence-guide">
                  증거 작성법
                </a>
              </div>
            </div>

            <aside className="build-card" aria-labelledby="current-build-title">
              <div className="build-card-head">
                <p className="card-kicker">보관된 QA APK</p>
                <span className="status-dot" aria-label="보관된 UI·에셋 QA 릴리스">
                  {qaManifest.build.badge}
                </span>
              </div>
              <h2 id="current-build-title">{qaManifest.build.displayName}</h2>
              <p className="warning-copy">{qaManifest.build.scopeWarning}</p>
              <dl className="build-facts">
                <div>
                  <dt>종류</dt>
                  <dd>{qaManifest.build.signing}</dd>
                </div>
                <div>
                  <dt>패키지</dt>
                  <dd>{qaManifest.build.packageName}</dd>
                </div>
                <div>
                  <dt>APK 파일명</dt>
                  <dd className="copy-value hash">
                    <span className="copy-text">{qaManifest.build.fileName}</span>
                    <CopyButton label="APK 파일명" value={qaManifest.build.fileName} />
                  </dd>
                </div>
                <div>
                  <dt>SHA-256</dt>
                  <dd className="copy-value hash">
                    <span className="copy-text">{qaManifest.build.sha256}</span>
                    <CopyButton label="APK SHA-256" value={qaManifest.build.sha256} />
                  </dd>
                </div>
              </dl>
              <a
                className="button button-download"
                href={qaManifest.build.downloadUrl}
                aria-describedby="release-note"
              >
                QA14 APK 다운로드
                <span aria-hidden="true">↓</span>
              </a>
              <p id="release-note" className="microcopy">
                {qaManifest.build.releaseNote}
              </p>
            </aside>
          </div>
        </header>

        <section className="truth-strip" aria-label="현재 빌드 범위">
          {qaManifest.build.truthStrip.map((fact) => (
            <div key={fact.value}>
              <span className="truth-number">{fact.value}</span>
              <span>{fact.label}</span>
            </div>
          ))}
          <p>{qaManifest.build.notIncluded.join(" · ")}</p>
        </section>

        <section id="qa-tasks" className="section task-section" aria-labelledby="task-title">
          <div className="section-heading">
            <div>
              <p className="eyebrow">Manifest-driven requests</p>
              <h2 id="task-title">실기기 QA 요청</h2>
            </div>
            <p>
              현재 빌드에서 가능한 요청만 활성화됩니다. 이후 Slice는 같은 manifest에서
              상태와 체크 항목만 갱신합니다.
            </p>
          </div>

          <div className="task-list">
            {qaManifest.tasks.map((task, index) => {
              const ready = task.status === "ready";
              return (
                <article
                  className={`task-card ${ready ? "is-ready" : "is-waiting"}`}
                  key={task.id}
                >
                  <div className="task-index" aria-hidden="true">
                    {String(index + 1).padStart(2, "0")}
                  </div>
                  <div className="task-main">
                    <div className="task-title-row">
                      <div>
                        <p className="task-slice">{task.slice}</p>
                        <h3>{task.title}</h3>
                      </div>
                      <span className={`task-status status-${task.status}`}>
                        {statusLabel[task.status]}
                      </span>
                    </div>
                    <p className="task-reason">{task.whyHuman}</p>

                    {task.imagePath ? (
                      <figure className="qa-artifact">
                        <img src={task.imagePath} alt={task.imageAlt ?? "QA 검토 후보"} />
                        <figcaption>등록된 ImageGen 검수 후보</figcaption>
                      </figure>
                    ) : null}

                    <details open={ready}>
                      <summary>관찰 항목과 제출 증거 보기</summary>
                      <div className="task-detail-grid">
                        <div>
                          <h4>직접 확인</h4>
                          <ol>
                            {task.checks.map((check) => (
                              <li key={check}>{check}</li>
                            ))}
                          </ol>
                        </div>
                        <div>
                          <h4>함께 남길 것</h4>
                          <ul>
                            {task.evidence.map((item) => (
                              <li key={item}>{item}</li>
                            ))}
                          </ul>
                        </div>
                      </div>
                    </details>

                    {ready ? (
                      <a className="button button-task" href={issueUrl(task)}>
                        이 요청 결과 제출
                        <span aria-hidden="true">↗</span>
                      </a>
                    ) : (
                      <p className="waiting-note">{task.waitingReason}</p>
                    )}
                  </div>
                </article>
              );
            })}
          </div>
        </section>

        <section id="evidence-guide" className="section evidence-section" aria-labelledby="evidence-title">
          <div className="section-heading evidence-heading">
            <div>
              <p className="eyebrow">Evidence, not a verdict</p>
              <h2 id="evidence-title">한 번에 재현 가능한 기록</h2>
            </div>
            <p>
              통과·실패 판정 대신 관찰한 사실을 남겨 주세요. 모르는 값은 추측하지 않고
              ‘확인 불가’로 기록합니다.
            </p>
          </div>

          <div className="evidence-grid">
            {qaManifest.requiredContext.map((item, index) => (
              <div className="evidence-item" key={item.label}>
                <span>{String(index + 1).padStart(2, "0")}</span>
                <div>
                  <h3>{item.label}</h3>
                  <p>{item.prompt}</p>
                </div>
              </div>
            ))}
          </div>

          <div className="privacy-note">
            <div>
              <p className="card-kicker">PRIVACY NOTE</p>
              <h3>얼굴과 주변 정보는 증거가 아닙니다.</h3>
            </div>
            <p>
              화면 녹화는 가능하면 사람을 프레임 밖에 두거나 얼굴을 가리고 촬영하세요.
              위치·계정·알림·음성 등 불필요한 개인정보는 자르거나 흐리게 처리한 뒤 비공개
              GitHub 저장소 이슈에 첨부합니다.
            </p>
          </div>
        </section>

        <footer>
          <p>
            포털 앱은 자체 계정·분석·업로드 저장소를 구현하지 않으며 QA 결과는 GitHub
            Issue에만 저장됩니다. 비공개 접근 제어와 봇 방어를 위해 호스팅 계층의 로그인과
            필수 보안 쿠키가 사용될 수 있습니다.
          </p>
          {readyCount > 0 ? (
            <a className="button button-footer" href="#qa-tasks">
              활성 QA 요청 {readyCount}건 보기
            </a>
          ) : (
            <p className="footer-status">활성 Physical QA 요청 없음</p>
          )}
        </footer>
      </main>
    </>
  );
}
