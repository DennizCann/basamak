import { expect, test } from "@playwright/test";
import { WEB, api, newMember, webLogin } from "./helpers";

/**
 * Scenario 8/11 (UI path): a member builds a workflow step by step (AI step + condition), runs it and sees per-step details;
 * shares it; another member forks it from the community list and the author's points show up on the live
 * leaderboard. Engine edge cases (sub-flows, cycles, resume, cron, webhook) are covered by WorkflowIT.
 */
test("build, run, share and fork a workflow; leaderboard updates", async ({ page, browser }) => {
  test.setTimeout(120_000);
  const author = await newMember("e2ewf");
  await webLogin(page, author.email);
  await page.goto(`${WEB}/workflows`);
  await page.getByRole("button", { name: "Yeni iş akışı" }).click();
  await expect(page.getByRole("heading", { name: "Yeni iş akışı" })).toBeVisible();
  const name = `E2E akış ${Date.now()}`;
  // step 1: blank flow + name
  await page.getByLabel("Akışın adı").fill(name);
  await page.getByRole("button", { name: "İleri →" }).click();
  // step 2: one input
  await page.getByRole("button", { name: "+ Girdi ekle" }).click();
  await page.getByLabel("Soru / etiket").fill("Konu");
  await page.getByRole("button", { name: "İleri →" }).click();
  // step 3: AI step -> condition on its text -> AI step on the "yes" branch, one step edited at a time
  await page.getByRole("button", { name: "+ Adım ekle" }).click();
  await page.getByRole("group", { name: "Ne tür bir adım eklemek istersin?" }).getByText("Yapay zekâ", { exact: true }).click();
  await page.getByLabel("Talimat", { exact: true }).fill("Kısa özet yaz:");
  await page.getByLabel("Talimata değer ekle").selectOption({ label: "Girdi: Konu" });
  await expect(page.getByLabel("Talimat", { exact: true })).toHaveValue("Kısa özet yaz: {{input.konu}}");
  await page.getByRole("button", { name: "Tamam" }).click();
  await page.getByRole("button", { name: "+ Adım ekle" }).click();
  await page.getByRole("group", { name: "Ne tür bir adım eklemek istersin?" }).getByText("Koşul", { exact: true }).click();
  await page.getByLabel("Neye bakılsın?").selectOption({ label: "yz1 (yapay zekâ) → metin" });
  await page.getByLabel("Karşılaştırma").selectOption("contains");
  await page.getByLabel("Değer", { exact: true }).fill("Basamak");
  await page.getByRole("button", { name: "Tamam" }).click();
  await page.getByRole("button", { name: "+ Adım ekle" }).click();
  await page.getByRole("group", { name: "Ne tür bir adım eklemek istersin?" }).getByText("Yapay zekâ", { exact: true }).click();
  await page.getByLabel("Talimat", { exact: true }).fill("Basamak ile ilgili bir ipucu ver.");
  await page.getByRole("button", { name: "Tamam" }).click();
  await page.getByRole("button", { name: /2\. Koşul/ }).click();
  await page.getByRole("group", { name: "Evetse çalışacak adımlar" }).getByLabel("yz3").check();
  await page.getByRole("button", { name: "Tamam" }).click();
  await expect(page.getByRole("img", { name: "İş akışı şeması" })).toBeVisible();
  await page.getByRole("button", { name: "İleri →" }).click();
  // step 4: create -> opens on the "Çalıştır" tab
  await page.getByRole("button", { name: "Akışı oluştur" }).click();
  await expect(page.getByRole("tab", { name: "Çalıştır", selected: true })).toBeVisible();

  await page.getByLabel("Konu", { exact: true }).fill("Basamak platformu");
  await page.getByRole("button", { name: "Çalıştır", exact: true }).click();
  const status = page.getByRole("status").filter({ hasText: /Tamamlandı · Elle/ });
  await expect(status).toBeVisible({ timeout: 20_000 });
  await expect(status).toContainText("token");
  const table = page.getByRole("table");
  await expect(table.getByRole("row", { name: /yz1/ })).toContainText("Tamamlandı");
  await expect(table.getByRole("row", { name: /kosul2/ })).toContainText("Tamamlandı");
  await expect(table.getByRole("row", { name: /yz3/ })).toContainText("Tamamlandı");

  await page.getByRole("tab", { name: "Ayarlar" }).click();
  await page.getByRole("button", { name: "Toplulukla paylaş" }).click();
  await expect(page.getByText("Paylaşıldı.", { exact: true })).toBeVisible();

  // another member forks it; the author earns points and sees the leaderboard refresh by itself
  await page.goto(`${WEB}/leaderboard`);
  await expect(page.getByText("● Canlı güncelleniyor")).toBeVisible();
  const before = await page.locator(".leaderboard .me").textContent().catch(() => null);
  const forker = await newMember("e2efork");
  const list = await api<{ id: string; name: string }[]>("GET", "/api/community/workflows", forker.token);
  const shared = list.find((w) => w.name === name)!;
  await api("POST", `/api/workflows/${shared.id}/fork`, forker.token);
  await expect(page.locator(".leaderboard .me")).toBeVisible({ timeout: 10_000 });
  if (before) await expect(page.locator(".leaderboard .me")).not.toHaveText(before, { timeout: 10_000 });

  const forkerPage = await (await browser.newContext()).newPage();
  await webLogin(forkerPage, forker.email);
  await forkerPage.goto(`${WEB}/workflows`);
  await expect(forkerPage.getByRole("link", { name: `${name} (çatal)` })).toBeVisible();
});
