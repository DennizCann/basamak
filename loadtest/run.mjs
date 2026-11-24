// Load test for the three PDF endpoints (OLCUMLER.md): training read, tool-run start, leaderboard.
// Plain Node (no k6 binary; zero downloads). Runs against the ISOLATED e2e stack (backend :8081, database
// basamak_test rebuilt on start), never the developer's data. Start that backend first, e.g.:
//   java -jar backend/target/basamak-backend-0.1.0.jar --spring.profiles.active=e2e --basamak.tools.daily-runs-per-level=100000
// (the per-member daily quota is raised only so the test measures latency instead of quota refusals; the IP rate
//  limit of the e2e profile is 1000 runs/min). Mailpit and the mock API (:8090) must be running.
// Usage: node loadtest/run.mjs [seconds=30] [virtualUsers=20]
import { createHmac } from "node:crypto";
import fs from "node:fs";

const API = process.env.API || "http://localhost:8081";
const MAILPIT = process.env.MAILPIT_URL || "http://localhost:8025";
const PASSWORD = "Basamak!2026"; // demo password of the seeded / test accounts (README)
const SECONDS = Number(process.argv[2] || 30);
const VUS = Number(process.argv[3] || 20);

async function call(method, path, token, body) {
  const r = await fetch(API + path, {
    method,
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await r.text();
  if (!r.ok) throw new Error(`${method} ${path} -> ${r.status} ${text.slice(0, 200)}`);
  return text ? JSON.parse(text) : undefined;
}

async function mail(to, subject, since, re) {
  for (let i = 0; i < 60; i++) {
    const q = encodeURIComponent(`to:${to}` + (subject ? ` subject:"${subject}"` : ""));
    const data = await (await fetch(`${MAILPIT}/api/v1/search?query=${q}`)).json();
    const msg = (data.messages ?? []).find((m) => new Date(m.Created).getTime() >= since - 1000);
    if (msg) {
      const full = await (await fetch(`${MAILPIT}/api/v1/message/${msg.ID}`)).json();
      const m = re.exec(full.Text);
      if (m) return m[1];
    }
    await new Promise((res) => setTimeout(res, 250));
  }
  throw new Error("no mail for " + to);
}

async function member(i) {
  const email = `load-${Date.now()}-${i}@test.local`;
  let since = Date.now();
  await call("POST", "/api/auth/register", undefined, { email, password: PASSWORD, displayName: `Yük ${i}`, consent: true });
  await call("POST", "/api/auth/verify-email", undefined, { token: await mail(email, "", since, /token=(\S+)/) });
  since = Date.now();
  const step = await call("POST", "/api/auth/login", undefined, { email, password: PASSWORD, client: "mobile" });
  const r = await call("POST", "/api/auth/login/verify", undefined, { challengeId: step.challengeId, code: await mail(email, "Basamak giriş kodunuz", since, /giriş kodunuz: (\d{6})/) });
  return r.accessToken;
}

function totp(secret) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const c of secret.replace(/s|=/g, "").toUpperCase()) bits += alphabet.indexOf(c).toString(2).padStart(5, "0");
  const key = Buffer.from(bits.match(/.{8}/g).map((x) => parseInt(x, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 1000 / 30)));
  const h = createHmac("sha1", key).update(counter).digest();
  const o = h[h.length - 1] & 0xf;
  return ((h.readUInt32BE(o) & 0x7fffffff) % 1_000_000).toString().padStart(6, "0");
}

function pct(sorted, p) { return sorted.length ? sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)] : null; }

// ratePerSec: open model (fixed arrival rate shared by all virtual users) instead of "as fast as possible"
async function phase(name, tokens, request, ratePerSec) {
  const lat = [], status = {};
  const end = Date.now() + SECONDS * 1000;
  let errors = 0;
  await Promise.all(tokens.map(async (token, vu) => {
    let n = 0;
    const gap = ratePerSec ? (tokens.length / ratePerSec) * 1000 : 0;
    if (gap) await new Promise((r) => setTimeout(r, (vu * gap) / tokens.length));
    while (Date.now() < end) {
      const t0 = performance.now();
      let code = 0;
      try {
        const { method, path, body } = request(vu, n++);
        const r = await fetch(API + path, { method, headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` }, body: body && JSON.stringify(body) });
        await r.arrayBuffer();
        code = r.status;
      } catch { code = -1; }
      lat.push(performance.now() - t0);
      status[code] = (status[code] || 0) + 1;
      if (code < 200 || code >= 300) errors++;
      if (gap) await new Promise((r) => setTimeout(r, Math.max(0, gap - (performance.now() - t0))));
    }
  }));
  lat.sort((a, b) => a - b);
  const total = lat.length;
  const res = { name, ratePerSec: ratePerSec ?? "max", requests: total, rps: +(total / SECONDS).toFixed(1), p50: +pct(lat, 50).toFixed(1), p95: +pct(lat, 95).toFixed(1),
    p99: +pct(lat, 99).toFixed(1), max: +lat[total - 1].toFixed(1), errorRate: +((errors / total) * 100).toFixed(2), status };
  console.log(JSON.stringify(res));
  return res;
}

const tokens = [];
for (let i = 0; i < VUS; i++) tokens.push(await member(i));
console.log(`${VUS} members signed in`);

// read target: the first published training. Tool target: a small training with the seeded "Terim sözlüğü" tool
// (mock API), published by the seeded admin through the CMS API (fresh e2e database: TOTP enrolment happens here;
// the secret stays in memory and is never printed). Member tools cannot point at localhost (SSRF guard), CMS tools can
// (trusted-hosts allowlist), which is why the CMS route is used.
const journeys = await call("GET", "/api/me/journeys", tokens[0]);
const readId = journeys[0].trainings[0].id;
const step = await call("POST", "/api/cms-auth/login", undefined, { email: "admin@basamak.local", password: PASSWORD });
if (!step.setupRequired) throw new Error("admin already enrolled TOTP: restart the e2e backend (fresh database)");
const admin = (await call("POST", "/api/cms-auth/totp", undefined, { mfaToken: step.mfaToken, code: totp(step.secret) })).accessToken;
const glossary = (await call("GET", "/api/tools", tokens[0])).find((t) => t.name === "Terim sözlüğü");
const tr = await call("POST", "/api/cms/items", admin, { type: "TRAINING", title: "Yük testi eğitimi", body: { summary: "Yük testi",
  items: [{ key: "t1", type: "tool", title: "Sözlük", toolId: glossary.id, toolVersionNo: glossary.versionNo }] } });
for (const st of ["submit", "approve", "publish"]) await call("POST", `/api/cms/versions/${tr.latest.id}/${st}`, admin);
for (const t of tokens) {
  await call("POST", `/api/trainings/${readId}/enroll`, t);
  await call("POST", `/api/trainings/${tr.id}/enroll`, t);
}

const results = {
  date: new Date().toISOString(), api: API, seconds: SECONDS, virtualUsers: VUS,
  phases: [
    await phase("GET /api/trainings/{id} (eğitim okuma)", tokens, () => ({ method: "GET", path: `/api/trainings/${readId}` })),
    // as fast as possible: shows the protection (IP limit 1000/min in the e2e profile, max 5 active runs per member -> 429)
    await phase("POST /api/tool-runs (araç başlatma, aşırı yük)", tokens, (vu, n) => ({ method: "POST", path: "/api/tool-runs",
      body: { trainingId: tr.id, itemKey: "t1", input: { term: "webhook" }, clientRequestId: `load-${vu}-${n}-${Date.now()}` } })),
    await new Promise((r) => setTimeout(r, 61_000)).then(() => null), // next rate-limit window, queue drained
    // paced under the limits: 10 starts/s (600/min) spread over all members -> measures the real start latency
    await phase("POST /api/tool-runs (araç başlatma, 10/sn)", tokens, (vu, n) => ({ method: "POST", path: "/api/tool-runs",
      body: { trainingId: tr.id, itemKey: "t1", input: { term: "webhook" }, clientRequestId: `paced-${vu}-${n}-${Date.now()}` } }), 10),
    await phase("GET /api/leaderboard (sıralama)", tokens, () => ({ method: "GET", path: "/api/leaderboard?period=week" })),
  ].filter(Boolean),
};
fs.mkdirSync("loadtest/results", { recursive: true });
fs.writeFileSync("loadtest/results/latest.json", JSON.stringify(results, null, 2));
console.log("written loadtest/results/latest.json");
