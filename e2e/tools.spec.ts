import { expect, test } from "@playwright/test";
import { WEB, cmsLogin, newMember, webLogin } from "./helpers";

/**
 * Scenario 6 (UI path): the content team defines a tool that calls the mock API with a secret, tries it in the
 * test console, publishes it, pins it in a training; a member runs it and follows the status live.
 * Scenario 7 (UI path): a member cannot save a tool that targets an internal address.
 * Needs the mock API (`java mock-api/MockApi.java`, started by playwright.config.ts).
 */
test("CMS tool with secret + test console, pinned in a training, run by a member with live status", async ({ page, browser }) => {
  test.setTimeout(120_000);
  await cmsLogin(page, "admin@basamak.local");
  const stamp = Date.now();

  // 1. tool (default body = mock writing checker with {{secret.apiKey}}); the definition is a 4-step wizard
  await page.getByRole("link", { name: "Araçlar" }).click();
  await page.getByLabel("Yeni içerik başlığı").fill(`E2E Denetleyici ${stamp}`);
  await page.getByRole("button", { name: "Taslak oluştur" }).click();
  await expect(page.getByRole("heading", { name: new RegExp(`E2E Denetleyici ${stamp}`) })).toBeVisible();
  await page.getByRole("tab", { name: "Sırlar ve deneme" }).click();
  await page.getByLabel("Değer").fill("demo-mock-key");
  await page.getByRole("button", { name: "Kaydet", exact: true }).click();
  await expect(page.getByText(/şifrelenerek kaydedildi/)).toBeVisible();
  await expect(page.getByText("demo-mock-key")).toHaveCount(0);
  await page.getByLabel("Metin").fill("bu bu deneme metni");
  await page.getByRole("button", { name: "Dene" }).click();
  await expect(page.getByText(/Başarılı · HTTP 200/)).toBeVisible();
  await expect(page.getByText("issueCount", { exact: false }).first()).toBeVisible();
  await page.getByRole("button", { name: "İncelemeye gönder" }).click();
  await page.getByRole("button", { name: "Onayla" }).click();
  await page.getByRole("button", { name: "Şimdi yayınla" }).click();
  await expect(page.getByText("Yayın işlemi tamamlandı.")).toBeVisible();

  // 2. training with the tool pinned
  await page.getByRole("link", { name: "Eğitimler" }).click();
  await page.getByLabel("Yeni içerik başlığı").fill(`E2E Araç eğitimi ${stamp}`);
  await page.getByRole("button", { name: "Taslak oluştur" }).click();
  await expect(page.getByRole("heading", { name: new RegExp(`E2E Araç eğitimi ${stamp}`) })).toBeVisible();
  await page.getByRole("button", { name: "+ Araç" }).click();
  await page.getByLabel("Araç", { exact: true }).selectOption({ label: `E2E Denetleyici ${stamp}` });
  await page.getByLabel("Başlık", { exact: true }).last().fill("Metnini denetlet");
  await page.getByRole("button", { name: "Yeni sürüm olarak kaydet" }).click();
  await expect(page.getByText(/Kaydedildi: yeni taslak s2/)).toBeVisible();
  await page.getByRole("button", { name: "İncelemeye gönder" }).click();
  await page.getByRole("button", { name: "Onayla" }).click();
  await page.getByRole("button", { name: "Şimdi yayınla" }).click();
  await expect(page.getByText("Yayın işlemi tamamlandı.")).toBeVisible();
  const trainingId = page.url().split("/items/")[1];

  // 3. member runs it
  const { email } = await newMember("e2etool");
  const member = await (await browser.newContext()).newPage();
  await webLogin(member, email);
  await member.goto(`${WEB}/trainings/${trainingId}`);
  await member.getByRole("button", { name: "Eğitime başla" }).click();
  await member.getByRole("button", { name: /Metnini denetlet/ }).first().click();
  await member.getByLabel("Metin").fill("Bu cümle İzmir hakkında kısa bir deneme");
  await member.getByRole("button", { name: "Çalıştır" }).click();
  await expect(member.getByRole("status").filter({ hasText: /Tamamlandı/ })).toBeVisible({ timeout: 20_000 });
  await expect(member.getByText("Sıraya alındı")).toBeVisible();
  await expect(member.locator(".run-output")).toContainText("score");
  await expect(member.locator(".run-output")).not.toContainText("demo-mock-key");
});

test("member cannot save a tool that targets an internal address", async ({ page }) => {
  const { email } = await newMember("e2essrf");
  await webLogin(page, email);
  await page.goto(`${WEB}/tools`);
  await page.getByRole("button", { name: "Yeni araç" }).click();
  await page.getByLabel("Araç adı").fill("Metadata denemesi");
  await page.getByRole("button", { name: "İleri →" }).click(); // step 2: the blank template asks one question
  await page.getByRole("button", { name: "İleri →" }).click(); // step 3: where it connects
  await page.getByLabel("Servis adresi (URL)").fill("http://169.254.169.254/latest/meta-data/?q={{input.soru}}");
  await page.getByRole("button", { name: "İleri →" }).click();
  await page.getByRole("button", { name: "Kaydet" }).click();
  await expect(page.getByRole("alert").filter({ hasText: /iç ağ|özel bir adres/i })).toBeVisible();
  await page.getByRole("button", { name: "← Geri" }).click();
  await page.getByLabel("Servis adresi (URL)").fill("http://localhost:8090/glossary?term={{input.soru}}");
  await page.getByRole("button", { name: "İleri →" }).click();
  await page.getByRole("button", { name: "Kaydet" }).click();
  await expect(page.getByRole("alert").filter({ hasText: /localhost/ })).toBeVisible();
});
