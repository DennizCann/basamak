import { expect, test, type Page } from "@playwright/test";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { CMS, DEMO_PASSWORD, WEB, totp } from "./helpers";

// TOTP secrets learned at first enrolment are kept locally (gitignored) so the suite can be re-run.
const SECRETS_FILE = join(__dirname, ".totp-secrets.local.json");
const secrets = new Map<string, string>(existsSync(SECRETS_FILE) ? Object.entries(JSON.parse(readFileSync(SECRETS_FILE, "utf8"))) : []);
const persist = () => writeFileSync(SECRETS_FILE, JSON.stringify(Object.fromEntries(secrets), null, 2));

async function cmsLogin(page: Page, email: string) {
  await page.goto(`${CMS}/login`);
  await page.getByLabel("E-posta").fill(email);
  await page.getByLabel("Şifre").fill(DEMO_PASSWORD);
  await page.getByRole("button", { name: "Devam" }).click();
  const code = page.locator("code");
  await expect(page.getByLabel("6 haneli doğrulama kodu")).toBeVisible();
  if (await code.isVisible().catch(() => false)) { secrets.set(email, (await code.textContent())!.trim()); persist(); }
  const secret = secrets.get(email);
  test.skip(!secret, `TOTP secret for ${email} unknown (already enrolled in a previous run)`);
  await page.getByLabel("6 haneli doğrulama kodu").fill(totp(secret!));
  await page.getByRole("button", { name: "Doğrula ve giriş yap" }).click();
  await expect(page.getByRole("heading", { name: "Journey'ler" })).toBeVisible();
}

/**
 * Scenario 1 (UI path): admin creates a fourth journey in the CMS, publishes it, and it appears in the web app
 * without any code change. Uses the admin account (four-eyes rule allows an admin to approve their own draft).
 */
test("new journey created in CMS appears on the web after publishing", async ({ page, browser }) => {
  // fresh browser storage for CMS; admin enrols TOTP on first run
  await cmsLogin(page, "admin@basamak.local");
  const title = `E2E Journey ${Date.now()}`;
  await page.getByLabel("Yeni içerik başlığı").fill(title);
  await page.getByRole("button", { name: "Taslak oluştur" }).click();
  await expect(page.getByRole("heading", { name: new RegExp(title) })).toBeVisible();
  await page.getByLabel("Açıklama").fill("Uçtan uca testte oluşturuldu");
  await page.getByRole("button", { name: "Yeni sürüm olarak kaydet" }).click();
  await expect(page.getByText(/Kaydedildi: yeni taslak s2/)).toBeVisible();
  await page.getByRole("button", { name: "İncelemeye gönder" }).click();
  await page.getByRole("button", { name: "Onayla" }).click();
  await page.getByRole("button", { name: "Şimdi yayınla" }).click();
  await expect(page.getByText("Yayın işlemi tamamlandı.")).toBeVisible();

  const member = await (await browser.newContext()).newPage();
  await member.goto(`${WEB}/login`);
  await member.getByLabel("E-posta").fill("member@basamak.local");
  await member.getByLabel("Şifre").fill(DEMO_PASSWORD);
  await member.getByRole("button", { name: "Giriş yap" }).click();
  await expect(member.getByRole("heading", { name: new RegExp(title) })).toBeVisible();
});
