import { expect, test } from "@playwright/test";
import { DEMO_PASSWORD, WEB, enterLoginCode, lastMailTo, loginCode, newMember, webLogin } from "./helpers";

/** Scenario 5 (UI path): register -> e-mail lands in Mailpit -> verify -> sign in -> start training -> lesson -> points. */
test("member registers via Mailpit verification and completes a lesson", async ({ page }) => {
  const email = `e2e-${Date.now()}@test.local`;

  await page.goto(`${WEB}/register`);
  await page.getByLabel("Ad soyad").fill("E2E Üye");
  await page.getByLabel("E-posta").fill(email);
  await page.getByLabel("Şifre").fill(DEMO_PASSWORD);
  await page.getByRole("checkbox").check();
  await page.getByRole("button", { name: "Hesap oluştur" }).click();
  await expect(page.getByText("Hesabınız oluşturuldu")).toBeVisible();

  const mail = await lastMailTo(email);
  const link = /http\S+verify-email\?token=\S+/.exec(mail)![0];
  await page.goto(link);
  await expect(page.getByText("E-posta adresiniz doğrulandı")).toBeVisible();

  await page.goto(`${WEB}/login`);
  await page.getByLabel("E-posta").fill(email);
  await page.getByLabel("Şifre").fill(DEMO_PASSWORD);
  const since = Date.now();
  await page.getByRole("button", { name: "Devam" }).click();
  // second step: 6-digit code e-mailed to the registered address (Mailpit), masked address on screen
  await expect(page.getByText(/^e2•+@test\.local$/)).toBeVisible();
  await enterLoginCode(page, email, since);
  // new member: one clear first action
  await expect(page.getByRole("heading", { level: 1, name: "Basamak'ta öğrenmeye başla" })).toBeVisible();
  await page.getByRole("link", { name: "Öğrenmeye başla" }).click();
  await page.getByRole("button", { name: "Eğitime başla" }).click();
  await expect(page.getByText("0/4 içerik").first()).toBeVisible();
  await page.getByRole("button", { name: "Dersi tamamladım" }).click();
  await expect(page.getByText(/\+5 puan kazandın/)).toBeVisible();
  await expect(page.getByText("1/4 içerik").first()).toBeVisible();

  await page.getByRole("link", { name: "Öğrenimim" }).first().click();
  // start (2) + view (1) + lesson complete (5)
  await expect(page.locator(".stat").filter({ hasText: "Toplam puan" }).locator(".value")).toHaveText("8");
  await expect(page.getByRole("heading", { level: 1, name: "Kaldığın yerden devam et" })).toBeVisible();
});

/** "Remember this device" (default on): after one code, the next login in the same browser needs only the password. */
test("remembered device: second login needs only the password", async ({ page }) => {
  const m = await newMember("remember");
  await webLogin(page, m.email); // first login on this browser: e-mailed code
  await page.getByRole("button", { name: /Hesap menüsü/ }).click();
  await page.getByRole("button", { name: "Çıkış yap" }).click();
  await page.goto(`${WEB}/login`);
  await page.getByLabel("E-posta").fill(m.email);
  await page.getByLabel("Şifre").fill(DEMO_PASSWORD);
  await page.getByRole("button", { name: "Devam" }).click();
  await page.waitForURL(`${WEB}/`);
  await expect(page.getByLabel("6 haneli kod")).toHaveCount(0);
});

/** Login code step: wrong code message with remaining attempts, resend cooldown, back to password. */
test("login code step: wrong code, resend cooldown, start over", async ({ page }) => {
  await page.goto(`${WEB}/login`);
  await page.getByLabel("E-posta").fill("member@basamak.local");
  await page.getByLabel("Şifre").fill(DEMO_PASSWORD);
  const since = Date.now();
  await page.getByRole("button", { name: "Devam" }).click();
  const real = await loginCode("member@basamak.local", since);
  await page.getByLabel("6 haneli kod").fill(real === "000000" ? "111111" : "000000");
  await page.getByRole("button", { name: "Giriş yap", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("Kod hatalı");
  await expect(page.getByRole("alert")).toContainText("4 deneme hakkınız kaldı");
  await expect(page.getByRole("button", { name: "Kodu yeniden gönder" })).toBeDisabled();
  await expect(page.getByText(/sn sonra yeniden isteyebilirsin/)).toBeVisible();
  await expect(page.getByText(/içinde geçerliliğini yitirir/)).toBeVisible();
  await page.getByRole("button", { name: "Farklı hesapla giriş yap" }).click();
  await expect(page.getByLabel("Şifre")).toBeVisible();
});

/** Scenario 15 (partial): no horizontal scroll at 320 px, dark theme renders, keyboard can reach the main controls. */
test("320px layout, dark theme and keyboard navigation on the login page", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 640 });
  await page.emulateMedia({ colorScheme: "dark" });
  await page.goto(`${WEB}/login`);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
  const bg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
  expect(bg).toBe("rgb(15, 17, 23)"); // --color-bg (dark)

  await page.keyboard.press("Tab");
  await expect(page.getByRole("link", { name: "İçeriğe geç" })).toBeFocused();
  await page.getByLabel("E-posta").focus();
  await page.keyboard.type("member@basamak.local");
  await page.keyboard.press("Tab");
  await page.keyboard.type(DEMO_PASSWORD);
  const since = Date.now();
  await page.keyboard.press("Enter");
  await expect(page.getByLabel("6 haneli kod")).toBeFocused(); // code step takes focus
  await page.keyboard.type(await loginCode("member@basamak.local", since));
  await page.keyboard.press("Enter");
  await page.waitForURL(`${WEB}/`);
  await expect(page.locator("main h1")).toBeVisible();
  const overflowHome = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflowHome).toBeLessThanOrEqual(0);
});
