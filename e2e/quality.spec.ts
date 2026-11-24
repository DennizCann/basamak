import { expect, test, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import fs from "node:fs";
import { CMS, WEB, api, cmsLogin, newMember, webLogin } from "./helpers";

/**
 * Scenario 15 + OLCUMLER.md: every main screen, light and dark theme,
 *  - axe-core (WCAG 2.0/2.1/2.2 A + AA rules): zero critical and serious violations,
 *  - 320 px wide: no horizontal scrolling,
 *  - keyboard: Tab reaches the controls and the focused element always has a visible outline.
 * Findings are also written to test-results/quality-report.json (OLCUMLER.md is filled from it).
 */
const TAGS = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"];
type Finding = { page: string; theme: string; id: string; impact: string; nodes: number; help: string; target: string };
const report: { axe: Finding[]; overflow: { page: string; scrollWidth: number }[]; pages: string[] } = { axe: [], overflow: [], pages: [] };

async function scan(page: Page, name: string) {
  for (const theme of ["light", "dark"] as const) {
    await page.emulateMedia({ colorScheme: theme, reducedMotion: "reduce" });
    await page.waitForTimeout(150);
    const r = await new AxeBuilder({ page }).withTags(TAGS).analyze();
    for (const v of r.violations) {
      report.axe.push({ page: name, theme, id: v.id, impact: v.impact ?? "", nodes: v.nodes.length, help: v.help,
        target: v.nodes.slice(0, 3).map((n) => n.target.join(" ")).join(" | ") });
    }
  }
  await page.emulateMedia({ colorScheme: "light" });
  report.pages.push(name);
}

async function noOverflowAt320(page: Page, name: string) {
  await page.setViewportSize({ width: 320, height: 720 });
  await page.waitForTimeout(150);
  const sw = await page.evaluate(() => document.documentElement.scrollWidth);
  if (sw > 321) report.overflow.push({ page: name, scrollWidth: sw });
  await page.setViewportSize({ width: 1280, height: 800 });
}

test.afterAll(() => {
  fs.mkdirSync("test-results", { recursive: true });
  fs.writeFileSync("test-results/quality-report.json", JSON.stringify(report, null, 2));
});

test("member screens: axe (light + dark), 320 px, keyboard focus", async ({ page }) => {
  test.setTimeout(240_000);
  const m = await newMember("e2equality");
  const journeys = await api<{ trainings: { id: string; title: string }[] }[]>("GET", "/api/me/journeys", m.token);
  const intro = journeys[0].trainings[0].id;
  await api("POST", `/api/trainings/${intro}/enroll`, m.token);

  // signed out
  for (const [path, name] of [["/login", "Giriş"], ["/register", "Kayıt"], ["/ui", "Bileşen kataloğu"]] as const) {
    await page.goto(WEB + path);
    await expect(page.locator("main")).toBeVisible();
    await scan(page, name);
    await noOverflowAt320(page, name);
  }

  await webLogin(page, m.email);
  const pages: [string, string][] = [
    ["/", "Ana sayfa (Öğrenimim)"], [`/trainings/${intro}`, "Eğitim oynatıcı"], ["/history", "Geçmişim"], ["/explore", "Keşfet"],
    ["/tools", "Araçlarım"], ["/workflows", "İş akışları"], ["/workflows/new", "İş akışı kurucu"], ["/community", "Topluluk"],
    ["/leaderboard", "Sıralama"], ["/notifications", "Bildirimler"], ["/profile", "Profil"], ["/account", "Hesap"], ["/certificates", "Sertifikalar"],
  ];
  for (const [path, name] of pages) {
    await page.goto(WEB + path);
    await page.locator("main").first().waitFor(); await page.waitForTimeout(1200); // SSE keeps the network busy: no "networkidle"
    await scan(page, name);
    await noOverflowAt320(page, name);
  }

  // keyboard: from the top of the home page, every Tab stop is visible and outlined
  await page.goto(WEB + "/");
  await page.locator("main").first().waitFor(); await page.waitForTimeout(1200);
  const invisible: string[] = [];
  for (let i = 0; i < 25; i++) {
    await page.keyboard.press("Tab");
    const info = await page.evaluate(() => {
      const el = document.activeElement as HTMLElement | null;
      if (!el || el === document.body) return null;
      const s = getComputedStyle(el);
      const outlined = s.outlineStyle !== "none" && parseFloat(s.outlineWidth) >= 2;
      const ring = s.boxShadow && s.boxShadow !== "none";
      return { tag: el.tagName, text: (el.textContent || el.getAttribute("aria-label") || "").trim().slice(0, 40), ok: outlined || !!ring };
    });
    if (info && !info.ok) invisible.push(`${info.tag} "${info.text}"`);
  }
  expect(invisible, "focus must always be visible").toEqual([]);
});

test("CMS screens: axe (light + dark)", async ({ page }) => {
  test.setTimeout(180_000);
  await cmsLogin(page, "admin@basamak.local");
  await scan(page, "CMS içerik listesi");
  await page.getByRole("link", { name: "Eğitimler" }).click();
  await page.getByRole("link").filter({ hasText: "Basamak'a Giriş" }).first().click();
  await page.locator("main").first().waitFor(); await page.waitForTimeout(1200);
  await scan(page, "CMS içerik düzenleyici");
  for (const [path, name] of [["/reports", "CMS raporlar"], ["/moderation", "CMS moderasyon"], ["/team", "CMS ekip"]] as const) {
    await page.goto(CMS + path);
    await page.locator("main").first().waitFor(); await page.waitForTimeout(1200); // SSE keeps the network busy: no "networkidle"
    await scan(page, name);
  }
});

test("no critical or serious accessibility violations", async () => {
  const bad = report.axe.filter((f) => f.impact === "critical" || f.impact === "serious");
  expect(report.overflow, "320 px horizontal overflow").toEqual([]);
  expect(bad.map((f) => `${f.page} (${f.theme}): ${f.id} - ${f.help} [${f.target}]`)).toEqual([]);
});
