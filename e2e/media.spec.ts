import { expect, test } from "@playwright/test";
import { WEB, cmsLogin, newMember, webLogin } from "./helpers";

// 2x2 PNG (valid image so the browser actually decodes it)
const PNG = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR4nGP8z8DAwMDAxMDAwMDAAAANHQEDasKb6QAAAABJRU5ErkJggg==", "base64");
const PDF = Buffer.from("%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n");

/**
 * Section 3.3: a lesson with an uploaded image (alt text) and a file attachment. The draft media is visible in the
 * CMS preview (signed URL), the member sees the image and the attachment after publication.
 */
test("CMS lesson with uploaded image and attachment is shown to the member after publication", async ({ page, browser }) => {
  test.setTimeout(120_000);
  await cmsLogin(page, "admin@basamak.local");
  const stamp = Date.now();
  await page.getByRole("link", { name: "Eğitimler" }).click();
  await page.getByLabel("Yeni içerik başlığı").fill(`E2E Medya ${stamp}`);
  await page.getByRole("button", { name: "Taslak oluştur" }).click();
  await expect(page.getByRole("heading", { name: new RegExp(`E2E Medya ${stamp}`) })).toBeVisible();
  await page.getByRole("button", { name: "+ Ders" }).click();
  await page.getByLabel("Başlık", { exact: true }).last().fill("Görselli ders");
  await page.getByLabel("Ders metni (Markdown)").last().fill("Aşağıdaki şemaya bakın.");

  // an SVG is refused with a clear reason
  await page.locator("input[type=file]").last().setInputFiles({ name: "x.svg", mimeType: "image/svg+xml", buffer: Buffer.from("<svg/>") });
  await expect(page.getByText(/SVG ve HTML güvenlik nedeniyle kabul edilmez/).last()).toBeVisible();

  await page.locator("input[type=file]").last().setInputFiles({ name: "sema.png", mimeType: "image/png", buffer: PNG });
  await expect(page.getByText(/Yüklendi: sema.png/)).toBeVisible();
  await page.getByRole("button", { name: "Metne ekle" }).click();
  await expect(page.getByText(/açıklamasını \(alt metin\) yazın/)).toBeVisible();
  await page.getByLabel("Görselin açıklaması (alt metin)").fill("Journey şeması");
  await page.getByRole("button", { name: "Metne ekle" }).click();
  await expect(page.getByLabel("Ders metni (Markdown)").last()).toHaveValue(/!\[Journey şeması\]\(media:[0-9a-f-]{36}\)/);

  await page.locator("input[type=file]").last().setInputFiles({ name: "ders-notlari.pdf", mimeType: "application/pdf", buffer: PDF });
  await page.getByRole("button", { name: "Ek dosya olarak ekle" }).click();
  await expect(page.getByText("📎 ders-notlari.pdf")).toBeVisible();

  await page.getByRole("button", { name: "Yeni sürüm olarak kaydet" }).click();
  await expect(page.getByText(/Kaydedildi: yeni taslak s2/)).toBeVisible();

  // draft preview: image decoded through a signed URL
  await page.getByRole("tab", { name: "Önizleme" }).click();
  await page.getByRole("button", { name: /Görselli ders/ }).first().click().catch(() => {});
  const previewImg = page.getByRole("img", { name: "Journey şeması" });
  await expect(previewImg).toBeVisible();
  await expect.poll(() => previewImg.evaluate((i: HTMLImageElement) => i.naturalWidth)).toBe(2);

  await page.getByRole("tab", { name: "Düzenle" }).click();
  await page.getByRole("button", { name: "İncelemeye gönder" }).click();
  await page.getByRole("button", { name: "Onayla" }).click();
  await page.getByRole("button", { name: "Şimdi yayınla" }).click();
  await expect(page.getByText("Yayın işlemi tamamlandı.")).toBeVisible();
  const trainingId = page.url().split("/items/")[1];

  const { email } = await newMember("e2emedia");
  const member = await (await browser.newContext()).newPage();
  await webLogin(member, email);
  await member.goto(`${WEB}/trainings/${trainingId}`);
  await member.getByRole("button", { name: "Eğitime başla" }).click();
  await member.getByRole("button", { name: /Görselli ders/ }).first().click();
  const img = member.getByRole("img", { name: "Journey şeması" });
  await expect(img).toBeVisible();
  await expect.poll(() => img.evaluate((i: HTMLImageElement) => i.naturalWidth)).toBe(2);
  const link = member.getByRole("link", { name: "ders-notlari.pdf" });
  await expect(link).toBeVisible();
  const res = await member.request.get(new URL(await link.getAttribute("href") as string, WEB).toString());
  expect(res.status()).toBe(200);
  expect(res.headers()["content-type"]).toContain("application/pdf");
});
