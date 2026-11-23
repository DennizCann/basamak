import { expect, test } from "@playwright/test";
import { WEB, api, newMember, webLogin } from "./helpers";

const cards = (page: import("@playwright/test").Page) => page.locator(".explore-list article");

test("explore: Turkish-aware search, journey filter, empty state with clear, URL state", async ({ page }) => {
  const m = await newMember("explore");
  await webLogin(page, m.email);
  await page.getByRole("link", { name: "Keşfet" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Eğitimleri keşfet" })).toBeVisible();
  await expect(cards(page).filter({ hasText: "Basamak'a Giriş" })).toHaveCount(1);

  const search = page.getByRole("searchbox", { name: "Ara" });
  await search.fill("GİRİŞ"); // upper-case dotted İ must match "Giriş"
  await expect(cards(page).filter({ hasText: "Basamak'a Giriş" })).toHaveCount(1);
  await expect(cards(page).filter({ hasText: "İlk Aracını Tanı" })).toHaveCount(0);
  await expect(page).toHaveURL(/q=G%C4%B0R%C4%B0%C5%9E|q=GİRİŞ/);
  await search.fill("İLK"); // "İlk Aracını Tanı"
  await expect(cards(page).filter({ hasText: "İlk Aracını Tanı" })).toHaveCount(1);
  await search.fill("araçların"); // matches the summary text, not the title
  await expect(cards(page).filter({ hasText: "İlk Aracını Tanı" })).toHaveCount(1);

  await search.fill("");
  await page.getByLabel("Journey (seviye)").selectOption({ label: "Expert" });
  await expect(cards(page).filter({ hasText: "Basamak'a Giriş" })).toHaveCount(0);
  await expect(cards(page).filter({ hasText: "İş Akışlarına Giriş" })).toHaveCount(1);

  await search.fill("bulunmayan-bir-sey-xyz");
  await expect(page.getByText(/eşleşen eğitim bulunamadı/)).toBeVisible();
  await page.getByRole("button", { name: "Filtreleri temizle" }).click();
  await expect(search).toHaveValue("");
  await expect(page).toHaveURL(`${WEB}/explore`);
  await expect(cards(page).filter({ hasText: "Basamak'a Giriş" })).toHaveCount(1);
});

test("explore: locked trainings show their condition and cannot be started; started ones are not listed", async ({ page }) => {
  const m = await newMember("explore-lock");
  const journeys = await api<any[]>("GET", "/api/me/journeys", m.token);
  const intro = journeys.find((j: any) => j.title === "Beginner").trainings.find((t: any) => t.title === "Basamak'a Giriş").id;
  await api("POST", `/api/trainings/${intro}/enroll`, m.token); // started -> belongs to "Öğrenimim", not here
  await webLogin(page, m.email);
  await page.goto(`${WEB}/explore`);
  await expect(cards(page).filter({ hasText: "Basamak'a Giriş" })).toHaveCount(0);

  const locked = cards(page).filter({ hasText: "İş Akışlarına Giriş" });
  await expect(locked.getByText("Kilitli")).toBeVisible();
  await expect(locked.getByText(/Erişim koşulu:.*Beginner/)).toBeVisible();
  await locked.getByRole("link", { name: "İncele: İş Akışlarına Giriş" }).click();
  await expect(page.getByRole("button", { name: "Eğitime başla" })).toBeDisabled();
  await expect(page.getByText(/'Beginner' tamamlanınca açılır/)).toBeVisible();
});

test("explore: keyboard and mobile layout", async ({ page }) => {
  const m = await newMember("explore-kb");
  await webLogin(page, m.email);
  await page.goto(`${WEB}/explore`);
  // keyboard: reach search, type, tab to the journey filter, then to a card action
  await page.getByRole("searchbox", { name: "Ara" }).focus();
  await page.keyboard.type("giriş");
  await page.keyboard.press("Tab");
  await expect(page.getByLabel("Journey (seviye)")).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(page.getByRole("link", { name: /^İncele:/ }).first()).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\/trainings\//);

  await page.goto(`${WEB}/explore`);
  for (const w of [360, 390, 768]) {
    await page.setViewportSize({ width: w, height: 800 });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow, `@${w}`).toBeLessThanOrEqual(0);
    const h = await page.locator(".topbar").evaluate((e) => e.getBoundingClientRect().height);
    expect(h, `header @${w}`).toBeLessThanOrEqual(64); // three nav items still fit on one row
  }
});
