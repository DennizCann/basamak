import { expect, test } from "@playwright/test";
import { WEB, api, cmsLogin, completeBeginner, newMember, webLogin } from "./helpers";

/** Scenario 12 (Geçmişim filters, export, account deletion) and 13 (CMS reports) through the UI. */
test("history filters, data export and account deletion", async ({ page }) => {
  const m = await newMember("e2eprivacy");
  await completeBeginner(m.token);
  await webLogin(page, m.email);

  await page.goto(`${WEB}/history`);
  await expect(page.getByText(/kayıt · sayfa 1/)).toBeVisible();
  await expect(page.getByText("Dersi tamamladın").first()).toBeVisible();
  await page.getByLabel("Tür").selectOption("tools");
  await expect(page.getByText("Bu süzgeçlere uyan kayıt yok.")).toBeVisible();
  await expect(page).toHaveURL(/category=tools/);
  await page.getByRole("button", { name: "Süzgeçleri temizle" }).click();
  await expect(page.getByText("Dersi tamamladın").first()).toBeVisible();

  // export (checked through the API: captured downloads close the Edge test browser)
  const data = await api<{ profile: { email: string }; activity: unknown[] }>("GET", "/api/me/export", m.token);
  expect(data.profile.email).toBe(m.email);
  expect(data.activity.length).toBeGreaterThan(0);
  expect(JSON.stringify(data)).not.toContain("password_hash");

  await page.goto(`${WEB}/account`);
  await page.getByLabel("Şifren").fill("Basamak!2026");
  const button = page.getByRole("button", { name: "Hesabımı kalıcı olarak sil" });
  await expect(button).toBeDisabled();
  await page.getByLabel("Onay için SİL yaz").fill("SİL");
  await button.click();
  await expect(page.getByText(/Hesabın silindi ve kişisel verilerin anonimleştirildi/)).toBeVisible();
  await page.getByLabel("E-posta").fill(m.email);
  await page.getByLabel("Şifre").fill("Basamak!2026");
  await page.getByRole("button", { name: "Devam" }).click();
  await expect(page.getByRole("alert").filter({ hasText: /hatalı/ })).toBeVisible();
});

test("CMS reports show completion and question correctness", async ({ page }) => {
  const m = await newMember("e2ereport");
  await completeBeginner(m.token);
  await cmsLogin(page, "admin@basamak.local");
  await page.getByRole("link", { name: "Raporlar" }).click();
  await expect(page.getByRole("heading", { name: "Raporlar" })).toBeVisible();
  const row = page.getByRole("row", { name: /Basamak'a Giriş/ });
  await expect(row).toBeVisible();
  await row.getByRole("button", { name: /ayrıntıları/ }).click();
  await expect(page.getByText(/deneme · geçme oranı/).first()).toBeVisible();
  await expect(page.getByRole("button", { name: "Sorular (CSV)" })).toBeVisible();
});
