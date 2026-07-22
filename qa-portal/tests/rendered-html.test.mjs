import assert from "node:assert/strict";
import { access, readFile, readdir } from "node:fs/promises";
import test from "node:test";

async function readdirOrEmpty(url) {
  try {
    return await readdir(url);
  } catch (error) {
    if (error && typeof error === "object" && error.code === "ENOENT") {
      return [];
    }
    throw error;
  }
}

async function render() {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);

  return worker.fetch(
    new Request("http://localhost/", {
      headers: { accept: "text/html" },
    }),
    {
      ASSETS: {
        fetch: async () => new Response("Not found", { status: 404 }),
      },
    },
    {
      waitUntil() {},
      passThroughOnException() {},
    },
  );
}

test("renders the active modern UI and physical QA request with truthful build scope", async () => {
  const response = await render();
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);

  const html = await response.text();
  const visibleText = html.replace(/<!--\s*-->/g, "");
  assert.match(html, /<html lang="ko"/i);
  assert.match(html, /<title>Motion Arcade · Physical QA Desk<\/title>/i);
  assert.match(html, /QA 14 보관본 · Slice 6 신규 APK 승인 대기/);
  assert.match(html, /부분 신체 인식/);
  assert.match(html, /신규 Monster 자세 분리/);
  assert.match(html, /Slice 1B–6 UI/);
  assert.match(html, /QA14 APK 다운로드/);
  assert.match(html, /QA 14 ARCHIVE/);
  assert.match(visibleText, /활성 QA 요청 4건 보기/);
  assert.match(visibleText, /요청 4건 확인/);
  assert.match(html, /호스팅 계층의 로그인과/);
  assert.match(html, /필수 보안 쿠키가 사용될 수 있습니다/);
  assert.doesNotMatch(html, /이 사이트는 로그인, 쿠키, 분석 도구/);
  assert.match(
    html,
    /href="https:\/\/github\.com\/kutaelee\/motion-arcade-android\/issues\/new/,
  );
  const submitHref = html.match(/<a class="button button-task" href="([^"]+)"/)?.[1];
  assert.ok(submitHref);
  const submitUrl = new URL(submitHref.replaceAll("&amp;", "&"));
  const submitBody = submitUrl.searchParams.get("body") ?? "";
  assert.equal(submitUrl.searchParams.get("template"), null);
  assert.match(submitBody, /## 검증 빌드/);
  assert.match(submitBody, /## 관찰 체크리스트/);
  assert.match(submitBody, /49af2c78747067282722c39eb66d326dc1da8566928e1f565fb4310b82ad5193/);
  assert.match(submitBody, /무릎과 발목은 화면 밖/);
  assert.match(submitBody, /개인정보는 제거/);
  assert.match(html, /app-arm64-v8a-debug\.apk/);
  assert.match(html, /APK 파일명 복사/);
  assert.match(html, /게임·인원 선택, 부분 신체 인식, 16:9 HUD 확인/);
  assert.match(html, /Monster 강공격·충전·협동 필살기 분리 QA/);
  assert.match(html, /각자 800ms 유지하고, 두 사람의 자세 확인 시점 차이가 600ms 이내/);
  assert.match(html, /v1\.0 ImageGen 에셋 재배포 승인 뒤/);
  assert.match(html, /무릎·발목은 화면 밖이어도 됩니다/);
  assert.match(html, /지금 확인 가능/);
  assert.doesNotMatch(html, /codex-preview|react-loading-skeleton/i);
});

test("enforces response security headers with a fresh nonce on every response", async () => {
  const [first, second] = await Promise.all([render(), render()]);
  const firstPolicy = first.headers.get("content-security-policy") ?? "";
  const secondPolicy = second.headers.get("content-security-policy") ?? "";
  const firstNonce = firstPolicy.match(/\bscript-src\b[^;]*'nonce-([^']+)'/)?.[1];
  const secondNonce = secondPolicy.match(/\bscript-src\b[^;]*'nonce-([^']+)'/)?.[1];

  assert.ok(firstNonce);
  assert.ok(secondNonce);
  assert.notEqual(firstNonce, secondNonce);
  assert.doesNotMatch(firstPolicy, /'unsafe-inline'|'unsafe-eval'/);
  assert.match(firstPolicy, /\bstrict-dynamic\b/);
  assert.match(firstPolicy, /\bframe-ancestors 'none'(?:;|$)/);
  assert.equal(first.headers.get("x-content-type-options"), "nosniff");
  assert.equal(first.headers.get("x-frame-options"), "DENY");
  assert.equal(first.headers.get("referrer-policy"), "no-referrer");
  assert.match(first.headers.get("permissions-policy") ?? "", /camera=\(\)/);
  assert.equal(first.headers.get("cross-origin-opener-policy"), "same-origin");
  assert.equal(first.headers.get("cross-origin-resource-policy"), "same-origin");
  assert.equal(first.headers.get("cache-control"), "no-store");
  assert.equal(first.headers.get("set-cookie"), null);

  const html = await first.text();
  const scriptTags = html.match(/<script\b[^>]*>/gi) ?? [];
  assert.ok(scriptTags.length > 0);
  for (const tag of scriptTags) {
    assert.match(tag, new RegExp(`\\bnonce=["']${firstNonce}["']`));
  }
  assert.doesNotMatch(html, /\sstyle=/i);
});

test("keeps QA content manifest-driven and ships only the registered review candidate", async () => {
  const [page, manifest, layout, packageJson, publicEntries] = await Promise.all([
    readFile(new URL("../app/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/qa-manifest.ts", import.meta.url), "utf8"),
    readFile(new URL("../app/layout.tsx", import.meta.url), "utf8"),
    readFile(new URL("../package.json", import.meta.url), "utf8"),
    readdirOrEmpty(new URL("../public/", import.meta.url)),
  ]);

  assert.match(page, /qaManifest\.tasks\.map/);
  assert.match(manifest, /releaseState: "archived_prerelease"/);
  assert.match(manifest, /49af2c78747067282722c39eb66d326dc1da8566928e1f565fb4310b82ad5193/);
  assert.match(manifest, /status: "ready"/);
  assert.match(manifest, /waiting_build/);
  assert.match(manifest, /status: "not_requested"/);
  assert.doesNotMatch(layout, /icons:|openGraph:|twitter:|next\/font/i);
  assert.doesNotMatch(packageJson, /react-loading-skeleton/);
  assert.deepEqual(publicEntries, []);
  assert.match(manifest, /g6-v1-runtime-art-review/);
  assert.match(manifest, /slice-6-monster-motion-separation/);
  assert.doesNotMatch(manifest, /\.v2/);

  await assert.rejects(access(new URL("../app/_sites-preview", import.meta.url)));
});
