import { expect, test } from "@playwright/test";
import { WEB, api, cmsLogin, newMember, webLogin } from "./helpers";

/**
 * Section 3.8 (UI): a member opens their profile, a second member follows them and sees their activity, both discuss
 * a shared workflow (rating, reply), one comment is reported and hidden from the CMS moderation page.
 */
test("profile, follow + feed, comments, rating and moderation", async ({ page, browser }) => {
  test.setTimeout(150_000);
  const a = await newMember("e2eprofa");
  const b = await newMember("e2eprofb");

  // A: make the profile public and share a workflow
  await webLogin(page, a.email);
  await page.goto(`${WEB}/profile`);
  await expect(page.getByText(/Profilin gizli/)).toBeVisible();
  await page.getByLabel(/Profilim herkese açık olsun/).check();
  await page.getByLabel("Kısa tanıtım (isteğe bağlı)").fill("Otomasyon meraklısı");
  await page.getByRole("button", { name: "Kaydet" }).click();
  await expect(page.getByText("Profil ayarların kaydedildi.")).toBeVisible();
  const wf = await api("POST", "/api/workflows", a.token, { name: `E2E paylaşım ${Date.now()}`, definition: { inputs: [], steps: [{ id: "a", type: "ai", prompt: "x" }], output: "" } });
  await api("POST", `/api/workflows/${wf.id}/share`, a.token, { shared: true });
  await page.goto(`${WEB}/profile`);
  await expect(page.getByRole("heading", { name: "Rozetler" })).toBeVisible();
  await expect(page.locator('.badge-tile[data-earned="true"]').filter({ hasText: "İlk iş akışı" })).toBeVisible();

  // B: open the shared workflow, rate it, comment; follow A from the profile link; see A in the feed
  const pb = await (await browser.newContext()).newPage();
  await webLogin(pb, b.email);
  await pb.goto(`${WEB}/workflows/${wf.id}`);
  await pb.locator("label.star").nth(3).click();
  await expect(pb.getByRole("radio", { name: "4 yıldız" })).toBeChecked();
  await expect(pb.getByText("4 / 5")).toBeVisible();
  await pb.getByLabel("Yorum yaz").fill("Çok işime yaradı, bana yaz: b@ornek.com");
  await pb.getByRole("button", { name: "Gönder" }).click();
  await expect(pb.getByText("Çok işime yaradı, bana yaz: [e-posta gizlendi]")).toBeVisible();
  await pb.getByRole("link", { name: wf.ownerName }).first().click();
  await pb.getByRole("button", { name: "Takip et" }).click();
  await expect(pb.getByRole("button", { name: "Takibi bırak" })).toBeVisible();
  await pb.goto(`${WEB}/community`);
  await expect(pb.getByRole("list", { name: "Etkinlik akışı" })).toContainText("bir iş akışı paylaştı");

  // A gets notified (follow + comment) and reports nothing; B's second comment gets reported by A and hidden in the CMS
  await pb.goto(`${WEB}/workflows/${wf.id}`);
  await pb.getByLabel("Yorum yaz").fill("Bu yorum gizlenecek");
  await pb.getByRole("button", { name: "Gönder" }).click();
  await page.goto(`${WEB}/workflows/${wf.id}`);
  const target = page.locator("article.comment").filter({ hasText: "Bu yorum gizlenecek" });
  await target.getByRole("button", { name: "Bildir" }).click();
  await target.getByLabel("Bildirme nedeni").selectOption("Spam ya da reklam");
  await target.getByRole("button", { name: "Bildir" }).last().click();
  await expect(page.getByText(/Bildirimin içerik ekibine iletildi/)).toBeVisible();
  await page.goto(`${WEB}/notifications`);
  await expect(page.getByRole("list", { name: "Bildirim listesi" })).toContainText("seni takip etmeye başladı");

  const cms = await (await browser.newContext()).newPage();
  await cmsLogin(cms, "editor@basamak.local");
  await cms.getByRole("link", { name: "Moderasyon" }).click();
  const card = cms.locator("li.card").filter({ hasText: "Bu yorum gizlenecek" });
  await card.getByRole("button", { name: "Gizle" }).click();
  await expect(cms.getByText(/Yorum gizlendi/)).toBeVisible();
  await pb.reload();
  await expect(pb.getByText("Bu yorum topluluk kuralları nedeniyle gizlendi.")).toBeVisible();
});
