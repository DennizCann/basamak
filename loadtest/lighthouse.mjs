// Lighthouse (mobile profile) for the three PDF pages: home, training player, Geçmişim (OLCUMLER.md).
// Runs against the production web build (`vite preview` on :5183, the origin the e2e profile allows, /api proxied to the isolated e2e backend :8081).
//   1) java -jar backend/target/basamak-backend-0.1.0.jar --spring.profiles.active=e2e
//   2) npm run build -w apps/web; cd apps/web; $env:API_TARGET="http://localhost:8081"; npx vite preview --port 5183
//   3) node loadtest/lighthouse.mjs   (needs `npx lighthouse@13.6.0` and a Chromium: CHROME_PATH or Playwright's)
// The refresh cookie rotates on every use, so each page gets a fresh sign-in (cookie passed as an extra header,
// never printed or saved). Reports: loadtest/results/lighthouse-*.json + summary in lighthouse-summary.json.
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const API = "http://localhost:8081", WEB = "http://localhost:5183", MAILPIT = "http://localhost:8025";
const PASSWORD = "Basamak!2026";
const CHROME = process.env.CHROME_PATH || path.join(process.env.LOCALAPPDATA, "ms-playwright/chromium-1148/chrome-win/chrome.exe");

async function call(method, p, token, body) {
  const r = await fetch(API + p, { method, headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body) });
  if (!r.ok) throw new Error(`${method} ${p} -> ${r.status}`);
  return { json: (await r.text().then((t) => (t ? JSON.parse(t) : undefined))), cookies: r.headers.getSetCookie() };
}
async function mail(to, subject, since, re) {
  for (let i = 0; i < 60; i++) {
    const q = encodeURIComponent(`to:${to}` + (subject ? ` subject:"${subject}"` : ""));
    const data = await (await fetch(`${MAILPIT}/api/v1/search?query=${q}`)).json();
    const msg = (data.messages ?? []).find((m) => new Date(m.Created).getTime() >= since - 1000);
    if (msg) { const m = re.exec((await (await fetch(`${MAILPIT}/api/v1/message/${msg.ID}`)).json()).Text); if (m) return m[1]; }
    await new Promise((res) => setTimeout(res, 250));
  }
  throw new Error("no mail");
}
/** Web sign-in (password + e-mailed code) -> { token, cookie } where cookie is the "name=value" of the refresh cookie. */
async function signIn(email) {
  const since = Date.now();
  const step = (await call("POST", "/api/auth/login", undefined, { email, password: PASSWORD, client: "web" })).json;
  const r = await call("POST", "/api/auth/login/verify", undefined, { challengeId: step.challengeId, code: await mail(email, "Basamak giriş kodunuz", since, /giriş kodunuz: (\d{6})/) });
  const cookie = r.cookies.map((c) => c.split(";")[0]).filter((c) => c.startsWith("bsm_")).join("; ");
  return { token: r.json.accessToken, cookie };
}

const email = `lighthouse-${Date.now()}@test.local`;
let since = Date.now();
await call("POST", "/api/auth/register", undefined, { email, password: PASSWORD, displayName: "Lighthouse Üye", consent: true });
await call("POST", "/api/auth/verify-email", undefined, { token: await mail(email, "", since, /token=(\S+)/) });
const first = await signIn(email);
const journeys = (await call("GET", "/api/me/journeys", first.token)).json;
const intro = journeys[0].trainings[0].id;
await call("POST", `/api/trainings/${intro}/enroll`, first.token);
await call("POST", `/api/trainings/${intro}/items/hos-geldin/complete`, first.token).catch(() => {}); // some history to show

const pages = [["home", "/"], ["training", `/trainings/${intro}`], ["history", "/history"]];
const summary = { date: new Date().toISOString(), lighthouse: "13.6.0", formFactor: "mobile (default throttling)", pages: [] };
fs.mkdirSync("loadtest/results", { recursive: true });
for (const [name, p] of pages) {
  const { cookie } = await signIn(email);
  const out = `loadtest/results/lighthouse-${name}.json`;
  const headers = path.join(os.tmpdir(), `lh-headers-${process.pid}.json`); // temp file: no shell quoting, not in the repo
  fs.writeFileSync(headers, JSON.stringify({ Cookie: cookie }));
  try {
  execFileSync(process.platform === "win32" ? "npx.cmd" : "npx", ["--yes", "lighthouse@13.6.0", WEB + p, "--quiet", "--output=json", `--output-path=${out}`,
    `--chrome-path=${CHROME}`, "--chrome-flags=--headless=new", "--only-categories=performance,accessibility,best-practices",
    `--extra-headers=${headers}`], { stdio: ["ignore", "ignore", "inherit"], shell: process.platform === "win32" });
  } catch { throw new Error(`lighthouse failed for ${name}`); } finally { fs.rmSync(headers, { force: true }); }
  const r = JSON.parse(fs.readFileSync(out, "utf8"));
  // the cookie header must not stay in the saved report
  fs.writeFileSync(out, JSON.stringify({ ...r, configSettings: { ...r.configSettings, extraHeaders: "(removed)" } }));
  if (r.finalDisplayedUrl.includes("/login")) throw new Error(`${name}: ended on the login page (session not accepted) - scores would be for the wrong page`);
  const score = (k) => Math.round(r.categories[k].score * 100);
  const a = (id) => r.audits[id]?.displayValue;
  const row = { page: name, url: p.replace(intro, "{id}"), finalUrl: r.finalDisplayedUrl.replace(intro, "{id}"), performance: score("performance"),
    accessibility: score("accessibility"), bestPractices: score("best-practices"), fcp: a("first-contentful-paint"), lcp: a("largest-contentful-paint"),
    tbt: a("total-blocking-time"), cls: a("cumulative-layout-shift"), failed: Object.values(r.audits).filter((x) => x.score !== null && x.score < 0.9 && x.scoreDisplayMode === "binary").map((x) => x.id) };
  summary.pages.push(row);
  console.log(JSON.stringify(row));
}
fs.writeFileSync("loadtest/results/lighthouse-summary.json", JSON.stringify(summary, null, 2));
