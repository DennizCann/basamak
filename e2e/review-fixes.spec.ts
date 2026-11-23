import { expect, test } from "@playwright/test";
import { writeFileSync } from "node:fs";
import { API, CMS, DEMO_PASSWORD, WEB, api, cmsLogin, completeBeginner, newMember, webLogin } from "./helpers";

test("training item is reflected in the URL; back/forward and reload keep the selection", async ({ page }) => {
  const m = await newMember("nav");
  await webLogin(page, m.email);
  await page.getByRole("listitem", { name: "1. Beginner" }).getByRole("link", { name: "Başla: Basamak'a Giriş" }).click();
  await page.getByRole("button", { name: "Eğitime başla" }).click();
  await expect(page).toHaveURL(/\?item=hos-geldin/);
  await page.getByRole("button", { name: /Puanlar ve seviyeler/ }).click();
  await expect(page).toHaveURL(/\?item=puanlar/);
  await page.getByRole("button", { name: /Giriş quiz'i/ }).click();
  await expect(page).toHaveURL(/\?item=giris-quiz/);

  await page.goBack();
  await expect(page).toHaveURL(/\?item=puanlar/);
  await expect(page.getByRole("heading", { level: 2, name: "Puanlar ve seviyeler" })).toBeVisible();
  await page.goForward();
  await expect(page.getByRole("heading", { level: 2, name: "Giriş quiz'i" })).toBeVisible();
  await page.reload();
  await expect(page.getByRole("heading", { level: 2, name: "Giriş quiz'i" })).toBeVisible();

  // removed / unknown item key in a link is handled explicitly
  await page.goto(page.url().replace(/item=[^&]+/, "item=yok-boyle-bir-sey"));
  await expect(page.getByText(/Bağlantıdaki içerik bu eğitimin senin sürümünde yok/)).toBeVisible();
});

test("quiz attempts can be reviewed without consuming an attempt; answers stay hidden while retries remain", async ({ page }) => {
  const m = await newMember("quizreview");
  await webLogin(page, m.email);
  await page.getByRole("listitem", { name: "1. Beginner" }).getByRole("link", { name: "Başla: Basamak'a Giriş" }).click();
  await page.getByRole("button", { name: "Eğitime başla" }).click();
  await page.getByRole("button", { name: /Giriş quiz'i/ }).click();
  await expect(page.getByText("3 deneme hakkınız var.")).toBeVisible();
  await page.getByRole("button", { name: "Quiz'e başla" }).click();
  await page.getByRole("button", { name: "Cevapları gönder" }).click(); // warns about empty answers
  await page.getByRole("button", { name: "Cevapları gönder" }).click(); // sends anyway
  await expect(page.getByText("Bu sefer olmadı.")).toBeVisible();
  await expect(page.getByText("1 deneme kullanıldı, 2 deneme hakkınız kaldı.")).toBeVisible();

  await page.getByRole("button", { name: "Denemelerimi incele" }).click();
  await expect(page.getByText(/1\. deneme · geçmedi/)).toBeVisible();
  await expect(page.getByText("Cevabınız: boş").first()).toBeVisible();
  await expect(page.getByText(/Doğru cevap:/)).toHaveCount(0); // not revealed while retries remain
  await expect(page.getByText("1 deneme kullanıldı, 2 deneme hakkınız kaldı.")).toBeVisible(); // unchanged
});

test("CMS: back link returns to the right list; published keys are read-only; preview writes nothing", async ({ page }) => {
  // editor (not admin): each spec file loads its own module copy, so a TOTP secret enrolled in another file is unknown here
  await cmsLogin(page, "editor@basamak.local");
  await page.getByRole("link", { name: "Eğitimler" }).click();
  await page.getByRole("link", { name: "Basamak'a Giriş" }).click();
  await expect(page.getByRole("heading", { name: /Basamak'a Giriş \(Eğitim\)/ })).toBeVisible();

  // published item keys cannot be edited in the UI (the server rejects it too - JourneyPolicyIT)
  const key = page.getByLabel("Anahtar (sabit)").first();
  await expect(key).toHaveAttribute("readonly", "");

  // preview: capture every non-GET API call while using it
  const writes: string[] = [];
  page.on("request", (r) => { if (r.url().includes("/api/") && r.method() !== "GET") writes.push(`${r.method()} ${r.url()}`); });
  await page.getByRole("tab", { name: "Önizleme" }).click();
  await expect(page.getByText(/ÖNİZLEME/)).toBeVisible();
  await page.getByRole("button", { name: /Giriş quiz'i/ }).click();
  await page.getByRole("button", { name: "Önizleme denemesi başlat" }).click();
  await page.getByRole("button", { name: "Cevapları gönder" }).click();
  await page.getByRole("button", { name: "Cevapları gönder" }).click();
  await expect(page.getByText(/Puanınız %/)).toBeVisible();
  await page.getByRole("button", { name: /Görüşünüz/ }).click();
  await page.getByRole("button", { name: "Anketi gönder" }).click();
  await expect(page.getByText(/kaydedilmedi/)).toBeVisible();
  await page.getByRole("button", { name: "Önizlemeyi sıfırla" }).click();
  await page.getByRole("button", { name: "Masaüstü (1440)" }).click();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0); // the 1440 px frame scrolls inside itself
  expect(writes).toEqual([]);

  await page.getByRole("link", { name: "← Eğitim listesi" }).click();
  await expect(page).toHaveURL(`${CMS}/content/trainings`);
  await expect(page.getByRole("heading", { name: "Eğitimler" })).toBeVisible();

  // the editor's preview did not create points for them (no enrolment, no ledger rows)
  const editor = await api("POST", "/api/auth/login", undefined, { email: "editor@basamak.local", password: DEMO_PASSWORD, client: "mobile" });
  const summary = await api("GET", "/api/me/summary", editor.accessToken);
  expect(summary.totalPoints).toBe(0);
});

test("certificate PDF is a real, non-empty PDF with an embedded Unicode font; the button gives feedback", async ({ browser }, testInfo) => {
  const m = await newMember("pdf");
  const { serial } = await completeBeginner(m.token);

  // 1) the actual file, fetched with the member's token and written to disk (attached to the test report)
  const res = await fetch(`${API}/api/me/certificates/${serial}/pdf`, { headers: { Authorization: `Bearer ${m.token}` } });
  expect(res.status).toBe(200);
  expect(res.headers.get("content-type")).toBe("application/pdf");
  expect(res.headers.get("content-disposition")).toContain(`basamak-sertifika-${serial}.pdf`);
  const file = Buffer.from(await res.arrayBuffer());
  const out = testInfo.outputPath(`sertifika-${serial}.pdf`);
  writeFileSync(out, file);
  await testInfo.attach("sertifika.pdf", { path: out, contentType: "application/pdf" });
  expect(file.subarray(0, 5).toString()).toBe("%PDF-");
  expect(file.length).toBeGreaterThan(5000);
  // Turkish letters need an embedded Unicode TrueType font (Identity-H), not the WinAnsi Helvetica fallback
  expect(file.toString("latin1")).toContain("/FontFile2");
  expect(file.toString("latin1")).toContain("Identity-H");
  // another member cannot download it
  const other = await newMember("pdf-other");
  expect((await fetch(`${API}/api/me/certificates/${serial}/pdf`, { headers: { Authorization: `Bearer ${other.token}` } })).status).toBe(404);

  // 2) the UI button: busy state + success message. Downloads are not captured here because the local Edge
  //    channel's browser process can exit after a captured download (observed), which is a harness issue.
  const ctx = await browser.newContext({ acceptDownloads: false });
  const page = await ctx.newPage();
  await webLogin(page, m.email);
  await page.goto(`${WEB}/certificates`);
  await page.getByRole("listitem").filter({ hasText: "Beginner" }).getByRole("button", { name: "Sertifikayı indir (PDF)" }).click();
  await expect(page.getByText(/Sertifika indirildi\./)).toBeVisible();
  await ctx.close();

  const verify = await (await fetch(`${API}/api/public/certificates/${serial}`)).json();
  expect(verify.valid).toBe(true);
});
