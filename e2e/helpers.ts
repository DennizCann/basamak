import { createHmac } from "node:crypto";
import { expect, type Page } from "@playwright/test";

export async function webLogin(page: Page, email: string) {
  await page.goto(`${WEB}/login`);
  await page.getByLabel("E-posta").fill(email);
  await page.getByLabel("Şifre").fill(DEMO_PASSWORD);
  await page.getByRole("button", { name: "Giriş yap" }).click();
  await expect(page.getByRole("heading", { name: "Journey haritası" })).toBeVisible();
}

// The e2e database is rebuilt on every run, so staff enrol TOTP afresh; the secret shown at enrolment is kept
// in memory for this run only (shared by all spec files in the single worker; never written to disk or logs).
const cmsSecrets = new Map<string, string>();
export async function cmsLogin(page: Page, email: string) {
  await page.goto(`${CMS}/login`);
  await page.getByLabel("E-posta").fill(email);
  await page.getByLabel("Şifre").fill(DEMO_PASSWORD);
  await page.getByRole("button", { name: "Devam" }).click();
  await expect(page.getByLabel("6 haneli doğrulama kodu")).toBeVisible();
  const code = page.locator("code");
  if (await code.isVisible().catch(() => false)) cmsSecrets.set(email, (await code.textContent())!.trim());
  await page.getByLabel("6 haneli doğrulama kodu").fill(totp(cmsSecrets.get(email)!));
  await page.getByRole("button", { name: "Doğrula ve giriş yap" }).click();
  await expect(page.getByRole("heading", { name: "Journey'ler" })).toBeVisible();
}

// isolated e2e stack (see playwright.config.ts) - never the developer's :5173/:5174/:8080
export const WEB = "http://localhost:5183";
export const CMS = "http://localhost:5184";
export const MAILPIT = process.env.MAILPIT_URL || "http://localhost:8025";
export const DEMO_PASSWORD = "Basamak!2026";

/** RFC 6238 TOTP from a base32 secret (same algorithm as authenticator apps). */
export function totp(secret: string): string {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const c of secret.replace(/\s|=/g, "").toUpperCase()) bits += alphabet.indexOf(c).toString(2).padStart(5, "0");
  const key = Buffer.from(bits.match(/.{8}/g)!.map((b) => parseInt(b, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 1000 / 30)));
  const h = createHmac("sha1", key).update(counter).digest();
  const o = h[h.length - 1] & 0xf;
  return ((h.readUInt32BE(o) & 0x7fffffff) % 1_000_000).toString().padStart(6, "0");
}

export const API = "http://localhost:8081"; // isolated e2e backend

/** Minimal API client for test setup (runs in Node against the isolated e2e backend). */
export async function api<T = any>(method: string, path: string, token?: string, body?: unknown): Promise<T> {
  const r = await fetch(API + path, {
    method,
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!r.ok) throw new Error(`${method} ${path} -> ${r.status} ${await r.text()}`);
  const text = await r.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** Registers a member, verifies the address through Mailpit and returns { email, token }. */
export async function newMember(prefix: string) {
  const email = `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@test.local`;
  await api("POST", "/api/auth/register", undefined, { email, password: DEMO_PASSWORD, displayName: "E2E Üye", consent: true });
  const token = /token=(\S+)/.exec(await lastMailTo(email))![1];
  await api("POST", "/api/auth/verify-email", undefined, { token });
  const login = await api("POST", "/api/auth/login", undefined, { email, password: DEMO_PASSWORD, client: "mobile" });
  return { email, token: login.accessToken as string };
}

/** Known answers of the seeded intro quiz. */
export const INTRO_ANSWERS: Record<string, string[]> = { q1: ["a"], q2: ["a"], q3: ["a", "b"], q4: ["a"], q5: ["a"] };

/** Finishes the seeded Beginner journey through the API (both trainings) -> a certificate exists. */
export async function completeBeginner(token: string) {
  const journeys = await api<any[]>("GET", "/api/me/journeys", token);
  const t = (title: string) => journeys[0].trainings.find((x: any) => x.title === title).id;
  const intro = t("Basamak'a Giriş"), second = t("İlk Aracını Tanı");
  await api("POST", `/api/trainings/${intro}/enroll`, token);
  for (const k of ["hos-geldin", "puanlar"]) await api("POST", `/api/trainings/${intro}/items/${k}/complete`, token);
  const a = await api("POST", `/api/trainings/${intro}/items/giris-quiz/attempts`, token);
  const answers = Object.fromEntries(a.questions.map((q: any) => [q.id, INTRO_ANSWERS[q.id]]));
  await api("POST", `/api/quiz-attempts/${a.attemptId}/submit`, token, { answers });
  await api("POST", `/api/trainings/${intro}/items/giris-anket/survey`, token, { answers: { s1: 5 } });
  await api("POST", `/api/trainings/${second}/enroll`, token);
  await api("POST", `/api/trainings/${second}/items/arac-nedir/complete`, token);
  const done = await api("POST", `/api/trainings/${second}/items/arac-anket/survey`, token, { answers: { s1: 4 } });
  return { intro, serial: done.certificatesIssued[0] as string };
}

/** Reads the newest e-mail sent to `to` from Mailpit's API and returns its text body. */
export async function lastMailTo(to: string): Promise<string> {
  for (let i = 0; i < 20; i++) {
    const r = await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent("to:" + to)}`);
    const data = await r.json();
    if (data.messages?.length) {
      const msg = await (await fetch(`${MAILPIT}/api/v1/message/${data.messages[0].ID}`)).json();
      return msg.Text as string;
    }
    await new Promise((res) => setTimeout(res, 500));
  }
  throw new Error("no mail for " + to);
}
