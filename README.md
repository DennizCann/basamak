# Basamak

Öğrenme yönetim sistemi ve topluluk platformu: üye web uygulaması, ayrı CMS, mobil uygulama (Capacitor) ve Spring Boot API.
Mimari kararlar [TASARIM.md](TASARIM.md)'de, ölçümler [OLCUMLER.md](OLCUMLER.md)'de, güncel ilerleme [DURUM.md](DURUM.md)'de.

## Depo yapısı
| Klasör | İçerik |
|---|---|
| `backend/` | Java 21 + Spring Boot 3.5 modüler monolit, Liquibase göçleri |
| `apps/web` | Üye web uygulaması (React + TS + Vite, :5173) |
| `apps/cms` | İçerik yönetim sistemi, ayrı uygulama (:5174) |
| `apps/mobile` | Capacitor Android/iOS *(F2.5'te eklenecek)* |
| `packages/ui` | Tasarım token'ları (`tokens.json`, tek kaynak) ve ortak bileşenler |
| `scripts/` | Yerel kurulum ve çalıştırma scriptleri (PowerShell) |

## Kurulum (Windows, Docker olmadan — doğrulandı)
Gereksinimler: Java 21, Node 20+, PostgreSQL 16+ (yerelde çalışıyor olmalı).

1. Örnek ayar dosyasını kopyalayın ve değerleri doldurun. Rastgele anahtarlar üretmek için `node -e "console.log(require('crypto').randomBytes(32).toString('base64'))"` komutunu kullanabilirsiniz.
   ```powershell
   Copy-Item .env.example .env
   ```
2. Veritabanlarını oluşturun (`postgres` şifresi sorulur):
   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\db-init.ps1
   ```
3. Bağımlılıkları kurun:
   ```powershell
   npm install
   ```
4. Mailpit'i indirin: [mailpit-windows-amd64.zip](https://github.com/axllent/mailpit/releases) dosyasını `tools\mailpit\` klasörüne açın.
5. Bütün sistemi başlatın:
   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\dev.ps1
   ```

| Servis | Adres |
|---|---|
| Web | http://localhost:5173 |
| CMS | http://localhost:5174 |
| API | http://localhost:8080 (sağlık: `/actuator/health`) |
| Mailpit | http://localhost:8025 |

## Docker Compose
`docker-compose.yml` Postgres ve Mailpit'i ayağa kaldırır. **Durum: henüz doğrulanmadı.** Geliştirme makinesinde Docker kurulu değil; doğrulanınca bu satır güncellenecek.

## Testler
Backend birim ve entegrasyon testleri (`basamak_test` DB'sini kullanır; Mailpit çalışıyor olmalı):
```powershell
cd backend; .\mvnw.cmd verify
```
Frontend tip kontrolü ve derleme:
```powershell
npm run typecheck; npm run build
```
Uçtan uca testler (Playwright) **kendi izole yığınlarında** çalışır ve geliştirme verinize dokunmaz:
- backend `e2e` profiliyle :8081'de çalışır ve `basamak_test` veritabanını her başlangıçta sıfırdan kurar;
- web :5183'te, CMS :5184'te açılır.

Bütün yığını test kendisi başlatır ve kapatır. Gerekenler: PostgreSQL ve Mailpit çalışıyor olmalı, backend jar'ı derlenmiş olmalı. `mvnw verify` ile aynı anda çalıştırmayın; ikisi de `basamak_test`'i kullanır.
```powershell
cd backend; .\mvnw.cmd -DskipTests package; cd ..
```
```powershell
$env:PW_CHANNEL="msedge"; npm run e2e
```
`PW_CHANNEL="msedge"` yerelde kurulu Edge'i kullanır. Bunun yerine önce `npx playwright install chromium` çalıştırıp `npm run e2e` de kullanabilirsiniz.

## Deneme hesapları
Uygulama ilk açılışta tohum verisini kendisi yükler (`DataSeeder`; işlem idempotent). Yüklenenler: aşağıdaki hesaplar ve yayınlanmış üç Journey (Beginner, Expert, Master).
Bütün demo hesaplarının şifresi: **`Basamak!2026`** (yalnız yerel ve demo ortamı için).

| Rol | E-posta | Nerede |
|---|---|---|
| Üye | `member@basamak.local` | Web, mobil |
| Yazar | `author@basamak.local` | CMS |
| Editör | `editor@basamak.local`, `editor2@basamak.local` | CMS (iki editör: senaryo 4 için) |
| Yayıncı | `publisher@basamak.local` | CMS |
| Yönetici | `admin@basamak.local` | CMS ve web |

**CMS'te iki aşamalı doğrulama zorunludur.** İlk girişte CMS bir QR kod ve sır gösterir. Bunu herhangi bir TOTP uygulamasına (Google Authenticator, Microsoft Authenticator, Aegis…) ekleyip 6 haneli kodu girin.
Elinizde uygulama yoksa, ekrandaki sırdan kodu şu komutla üretebilirsiniz:
```powershell
node scripts/totp.mjs <SIR>
```

## API
- OpenAPI arayüzü: http://localhost:8080/swagger-ui.html (JSON: `/v3/api-docs`).
- Hatalar RFC 7807 `problem+json` biçimindedir: `code`, Türkçe `detail` ve `requestId` alanlarını içerir.

## Zorunlu senaryolar — durum
| # | Senaryo | Durum |
|---|---|---|
| 1 | Dinamik Journey | **Web ve CMS'te çalışıyor.** API: `ContentWorkflowIT.scenario1`; uçtan uca: `e2e/cms.spec.ts` (CMS'te oluşturulan Journey web'de görünüyor). Mobil: F2.5/F6 |
| 2 | Onay, yayın, sürüm, fark, geri alma | **Web ve CMS'te çalışıyor.** API: `scenario2`; CMS'te onay ve geri gönderme, zamanlama, geçmiş, fark ve yeniden yayın ekranları var |
| 3 | Eğitim ortasında yeni sürüm | **Çalışıyor.** Kural: başladığı sürümde biter, isterse geçer. Otomatik test: `LearningIT.scenario3_*`. Web'de "yeni sürüme geç" uyarısı var |
| 4 | Eşzamanlı düzenleme | **Çalışıyor.** API testleri: `scenario4_*` (gerçek eşzamanlı test dahil). CMS'te çakışma ekranı var (kaydedilmemiş değişiklikler gösterilir) |
| 5 | Kayıt → sertifika | **Çalışıyor.** API: `LearningIT.scenario5_*` (Journey sonu, PDF ve doğrulama dahil). Uçtan uca: `e2e/member.spec.ts` (gerçek Mailpit doğrulaması ve ders tamamlama) |
| 10 | Dinamik puan kuralları ve tekrar | **Çalışıyor.** `GamificationIT` (100 görüntüleme → 1 puan; çift ve eşzamanlı olay → 1 kayıt; CMS'ten kural değişikliği) |
| 15 | 320 px, koyu tema, klavye | **Kısmen.** Giriş ve ana sayfa için uçtan uca kontrol var (`e2e/member.spec.ts`). Tam tarama F7'de |
| 6–9, 11–14 | — | Başlanmadı |
