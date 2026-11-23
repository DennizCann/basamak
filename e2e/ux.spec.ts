import { expect, test } from "@playwright/test";
import { WEB, api, completeEverything, newMember, webLogin } from "./helpers";

/** UX phase acceptance scenarios on the isolated stack with real member states. */

async function introId(token: string) {
  const j = await api<any[]>("GET", "/api/me/journeys", token);
  return j.find((x: any) => x.title === "Beginner").trainings.find((t: any) => t.title === "Basamak'a Giriş").id as string;
}

test("new member: one clear first action that opens the first available training", async ({ page }) => {
  const m = await newMember("ux-new");
  const intro = await introId(m.token);
  await webLogin(page, m.email);
  await expect(page.getByRole("heading", { level: 1, name: "Basamak'ta öğrenmeye başla" })).toBeVisible();
  const cta = page.getByRole("link", { name: "Öğrenmeye başla" });
  await expect(cta).toHaveAttribute("href", `/trainings/${intro}`);
  await expect(page.getByText("Kaldığın yerden devam et")).toHaveCount(0);
});

test("in-progress member: continue goes to the stored current item", async ({ page }) => {
  const m = await newMember("ux-mid");
  const intro = await introId(m.token);
  await api("POST", `/api/trainings/${intro}/enroll`, m.token);
  await api("POST", `/api/trainings/${intro}/items/hos-geldin/complete`, m.token);
  await api("POST", `/api/trainings/${intro}/items/puanlar/view`, m.token);
  await webLogin(page, m.email);
  await expect(page.getByRole("heading", { level: 1, name: "Kaldığın yerden devam et" })).toBeVisible();
  await page.locator(".hero").getByRole("link", { name: "Devam et" }).click();
  await expect(page).toHaveURL(`${WEB}/trainings/${intro}?item=puanlar`);
  await expect(page.getByRole("heading", { level: 2, name: "Puanlar ve seviyeler" })).toBeVisible();
});

test("member who finished everything sees the achievement, certificates and no fake continue", async ({ page }) => {
  const m = await newMember("ux-done");
  await completeEverything(m.token);
  await webLogin(page, m.email);
  await expect(page.getByRole("heading", { level: 1, name: "Mevcut eğitimlerin hepsini tamamladın" })).toBeVisible();
  await expect(page.getByRole("link", { name: /^Devam et/ })).toHaveCount(0);
  await expect(page.getByRole("link", { name: /^Başla/ })).toHaveCount(0);
  await page.locator(".hero").getByRole("link", { name: "Sertifikalarımı gör" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Sertifikalarım" })).toBeVisible();
  // one certificate per completed journey (other specs in the run may add journeys containing the same training)
  for (const j of ["Beginner", "Expert", "Master"]) await expect(page.locator(".cert").filter({ hasText: j })).toHaveCount(1);
  // completed journeys are collapsed but still reachable
  await page.goto(`${WEB}/`);
  await page.getByText("Eğitimleri göster").first().click();
  await expect(page.getByRole("link", { name: "Tekrar incele: Basamak'a Giriş" })).toBeVisible();
});

/**
 * The reviewer's real state (Beginner done, Expert published but empty -> "Yakında", Master locked) cannot be created
 * on a fresh database any more (empty journeys are refused at publish), so the API response is shaped here.
 */
test("finished available content while the next journey is coming soon: honest message, no fake action", async ({ page }) => {
  const m = await newMember("ux-soon");
  await page.route("**/api/me/journeys", (route) => route.fulfill({
    json: [
      { id: "j1", title: "Beginner", description: "", order: 1, completedCount: 1, completed: true, locked: false, lockReason: null, certificateSerial: "BSM-X", comingSoon: false,
        trainings: [{ id: "t1", title: "Basamak'a Giriş", status: "COMPLETED", summary: "", itemCount: 4, completedItems: 4, currentKey: "giris-anket", lastActivityAt: "2026-11-23T10:00:00Z" }] },
      { id: "j2", title: "Expert", description: "", order: 2, completedCount: 0, completed: false, locked: false, lockReason: null, certificateSerial: null, comingSoon: true, trainings: [] },
      { id: "j3", title: "Master", description: "", order: 3, completedCount: 0, completed: false, locked: true, lockReason: "Bu Journey, 'Expert' tamamlanınca açılır.", certificateSerial: null, comingSoon: false, trainings: [] },
    ],
  }));
  await webLogin(page, m.email);
  await expect(page.getByRole("heading", { level: 1, name: "Mevcut eğitimlerin hepsini tamamladın" })).toBeVisible();
  await expect(page.getByText("Sıradaki Journey “Expert” için eğitimler hazırlanıyor.")).toBeVisible();
  await expect(page.getByRole("listitem", { name: "2. Expert" }).getByText("Yakında")).toBeVisible();
  await expect(page.getByRole("listitem", { name: "3. Master" }).getByText("'Expert' tamamlanınca açılır")).toBeVisible();
  await expect(page.getByRole("link", { name: /^(Devam et|Başla)/ })).toHaveCount(0);
});

test("account menu: keyboard, Escape, outside click, theme and sign out", async ({ page }) => {
  const m = await newMember("ux-menu");
  await webLogin(page, m.email);
  const button = page.getByRole("button", { name: /Hesap menüsü/ });
  await button.focus();
  await page.keyboard.press("Enter");
  await expect(button).toHaveAttribute("aria-expanded", "true");
  await expect(page.getByText(m.email)).toBeVisible();
  await expect(page.getByRole("radio", { name: "Sistem" })).toBeFocused(); // focus moved into the panel
  await page.keyboard.press("Escape");
  await expect(button).toHaveAttribute("aria-expanded", "false");
  await expect(button).toBeFocused(); // focus returned

  await button.click();
  await page.getByText("Koyu", { exact: true }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.locator("main").click({ position: { x: 5, y: 5 } }); // outside click closes
  await expect(button).toHaveAttribute("aria-expanded", "false");

  await button.click();
  await page.getByRole("group", { name: "Hesap" }).getByRole("button", { name: "Puanlar nasıl kazanılır?" }).click();
  await expect(page.getByRole("dialog", { name: "Puanlar ve seviyeler" })).toBeVisible();
  await expect(page.getByRole("dialog").getByText("Quiz geçme")).toBeVisible(); // real published rule
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);

  await button.click();
  await page.getByRole("group", { name: "Hesap" }).getByRole("button", { name: "Çıkış yap" }).click();
  await page.goto(`${WEB}/`);
  await expect(page).toHaveURL(/\/login/);
});

test("lesson pager: previous/next move between items; next never starts a quiz", async ({ page }) => {
  const m = await newMember("ux-pager");
  const intro = await introId(m.token);
  await api("POST", `/api/trainings/${intro}/enroll`, m.token);
  await webLogin(page, m.email);
  await page.goto(`${WEB}/trainings/${intro}?item=hos-geldin`);
  await page.getByRole("button", { name: /Sonraki: Ders/ }).click();
  await expect(page).toHaveURL(/item=puanlar/);
  await page.getByRole("button", { name: /Önceki/ }).click();
  await expect(page).toHaveURL(/item=hos-geldin/);
  await page.goto(`${WEB}/trainings/${intro}?item=puanlar`);
  await page.getByRole("button", { name: /Sonraki: Quiz/ }).click();
  await expect(page).toHaveURL(/item=giris-quiz/);
  await expect(page.getByText("3 deneme hakkınız var.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Cevapları gönder" })).toHaveCount(0); // no attempt started
  const view = await api("GET", `/api/trainings/${intro}`, m.token);
  expect(view.items.find((i: any) => i.key === "giris-quiz").attemptsUsed).toBe(0);
});

test("text zoomed to 200% at 390px: home and lesson do not scroll horizontally", async ({ page }) => {
  const m = await newMember("ux-zoom");
  const intro = await introId(m.token);
  await api("POST", `/api/trainings/${intro}/enroll`, m.token);
  await page.setViewportSize({ width: 390, height: 844 });
  await webLogin(page, m.email);
  for (const url of [`${WEB}/`, `${WEB}/trainings/${intro}?item=puanlar`]) {
    await page.goto(url);
    await page.locator("main h1").waitFor();
    await page.evaluate(() => { document.documentElement.style.fontSize = "200%"; });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    const culprit = await page.evaluate(() => [...document.querySelectorAll("body *")]
      .filter((e) => e.getBoundingClientRect().right > document.documentElement.clientWidth + 1)
      .slice(0, 1).map((e) => {
        const chain: string[] = [];
        for (let n: Element | null = e; n && n !== document.body; n = n.parentElement) chain.push(`${n.tagName}.${(n as HTMLElement).className}:${Math.round(n.getBoundingClientRect().width)}`);
        return chain.join(" < ");
      }).join(""));
    expect(overflow, `${url} ${culprit}`).toBeLessThanOrEqual(0);
  }
});

test("mobile: compact header, collapsed content menu, reading area first, no horizontal scroll", async ({ page }) => {
  const m = await newMember("ux-mobile");
  const intro = await introId(m.token);
  await api("POST", `/api/trainings/${intro}/enroll`, m.token);
  await page.setViewportSize({ width: 390, height: 844 });
  await webLogin(page, m.email);
  for (const w of [360, 390, 768]) {
    await page.setViewportSize({ width: w, height: 844 });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow, `home @${w}`).toBeLessThanOrEqual(0);
  }
  const headerHeight = await page.locator(".topbar").evaluate((e) => e.getBoundingClientRect().height);
  expect(headerHeight).toBeLessThanOrEqual(64); // one row

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${WEB}/trainings/${intro}?item=puanlar`);
  const summary = page.locator(".toc-mobile > summary");
  await expect(summary).toContainText("Puanlar ve seviyeler");
  await expect(page.locator(".toc-mobile .toc-list")).toBeHidden();
  const top = await page.getByRole("heading", { level: 2, name: "Puanlar ve seviyeler" }).evaluate((e) => e.getBoundingClientRect().top);
  expect(top).toBeLessThan(844); // lesson starts within the first screen
  await summary.click();
  await page.locator(".toc-mobile").getByRole("button", { name: /Giriş quiz'i/ }).click();
  await expect(page).toHaveURL(/item=giris-quiz/);
  await expect(page.locator(".toc-mobile .toc-list")).toBeHidden(); // menu closes after choosing
  for (const w of [360, 390, 768]) {
    await page.setViewportSize({ width: w, height: 844 });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow, `lesson @${w}`).toBeLessThanOrEqual(0);
  }
});
