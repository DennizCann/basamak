import { test, type Page } from "@playwright/test";
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import { API, WEB, api, completeEverything, newMember, webLogin } from "./helpers";

/**
 * Documentation screenshots of the member app in three real user states, on the isolated e2e stack.
 * Opt-in:  $env:SCREENS="after"; npm run e2e -- screens.spec.ts   -> docs/ux/after/*.png
 */
const phase = process.env.SCREENS;
test.skip(!phase, "set SCREENS=before|after to capture screenshots");
const dir = join(__dirname, "..", "docs", "ux", phase ?? "x");

async function shot(page: Page, name: string, width: number, height = 900, fullPage = true) {
  mkdirSync(dir, { recursive: true });
  await page.setViewportSize({ width, height });
  await page.waitForTimeout(250);
  await page.screenshot({ path: join(dir, `${name}.png`), fullPage });
}

test("screens: new, in-progress and all-done members", async ({ browser }) => {
  for (const scheme of ["light", "dark"] as const) {
    // new member
    const fresh = await newMember("ux-new");
    const ctx = await browser.newContext({ colorScheme: scheme });
    const p = await ctx.newPage();
    await webLogin(p, fresh.email);
    await shot(p, `home-new-${scheme}-1280`, 1280);
    if (scheme === "light") {
      await shot(p, "home-new-light-390", 390, 844);
      const menu = p.getByRole("button", { name: /Hesap menüsü/ });
      if (await menu.count()) {
        await menu.click();
        await shot(p, "account-menu-light-390", 390, 844, false);
        await p.setViewportSize({ width: 1280, height: 600 });
        await shot(p, "account-menu-light-1280", 1280, 600, false);
      }
    }
    await ctx.close();
  }

  // in-progress member: first lesson done
  const mid = await newMember("ux-mid");
  const journeys = await api<any[]>("GET", "/api/me/journeys", mid.token);
  const intro = journeys.find((j: any) => j.title === "Beginner").trainings.find((t: any) => t.title === "Basamak'a Giriş").id;
  await api("POST", `/api/trainings/${intro}/enroll`, mid.token);
  await api("POST", `/api/trainings/${intro}/items/hos-geldin/complete`, mid.token);
  await api("POST", `/api/trainings/${intro}/items/puanlar/view`, mid.token);
  const c1 = await browser.newContext();
  const p1 = await c1.newPage();
  await webLogin(p1, mid.email);
  await shot(p1, "home-progress-light-1280", 1280);
  await p1.goto(`${WEB}/trainings/${intro}?item=puanlar`);
  await p1.waitForTimeout(400);
  await shot(p1, "lesson-light-1280", 1280);
  for (const w of [360, 390, 768]) await shot(p1, `lesson-light-${w}`, w, 844, false);
  await c1.close();

  // member who finished everything that exists
  const done = await newMember("ux-done");
  await completeEverything(done.token);
  const c2 = await browser.newContext({ colorScheme: "dark" });
  const p2 = await c2.newPage();
  await webLogin(p2, done.email);
  await shot(p2, "home-done-dark-1280", 1280);
  await shot(p2, "home-done-dark-390", 390, 844);
  const summary = await api("GET", "/api/me/summary", done.token);
  await p2.goto(`${WEB}/verify/${summary.certificates[0].serial}`);
  await shot(p2, "verify-dark-390", 390, 844);
  await c2.close();
  void API;
});
