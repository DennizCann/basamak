import { expect, test } from "@playwright/test";
import { cmsLogin, webLogin } from "./helpers";

/**
 * Scenario 1 (UI path): admin creates a fourth journey in the CMS, adds a published training (an empty journey
 * cannot be published), publishes it, and it appears in the web app without any code change.
 */
test("new journey created in CMS appears on the web after publishing", async ({ page, browser }) => {
  await cmsLogin(page, "admin@basamak.local");
  const title = `E2E Journey ${Date.now()}`;
  await page.getByLabel("Yeni içerik başlığı").fill(title);
  await page.getByRole("button", { name: "Taslak oluştur" }).click();
  await expect(page.getByRole("heading", { name: new RegExp(title) })).toBeVisible();
  await page.getByLabel("Açıklama").fill("Uçtan uca testte oluşturuldu");
  await page.getByLabel("Eğitim ekle").selectOption({ label: "Basamak'a Giriş" });
  await page.getByRole("button", { name: "Yeni sürüm olarak kaydet" }).click();
  await expect(page.getByText(/Kaydedildi: yeni taslak s2/)).toBeVisible();
  await page.getByRole("button", { name: "İncelemeye gönder" }).click();
  await page.getByRole("button", { name: "Onayla" }).click();
  await page.getByRole("button", { name: "Şimdi yayınla" }).click();
  await expect(page.getByText("Yayın işlemi tamamlandı.")).toBeVisible();

  const member = await (await browser.newContext()).newPage();
  await webLogin(member, "member@basamak.local");
  await expect(member.getByRole("heading", { name: new RegExp(title) })).toBeVisible();
});

/** An empty journey is refused with a clear message instead of creating a dead end for members. */
test("CMS explains why an empty journey cannot be published", async ({ page }) => {
  await cmsLogin(page, "admin@basamak.local");
  await page.getByLabel("Yeni içerik başlığı").fill(`E2E Boş ${Date.now()}`);
  await page.getByRole("button", { name: "Taslak oluştur" }).click();
  await expect(page.getByText(/yayınlanmış eğitim yok/i).first()).toBeVisible();
  await page.getByRole("button", { name: "İncelemeye gönder" }).click();
  await page.getByRole("button", { name: "Onayla" }).click();
  await page.getByRole("button", { name: "Şimdi yayınla" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "yayınlanamaz" })).toBeVisible();
});
