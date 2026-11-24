# Basamak

Öğrenme yönetim sistemi ve topluluk platformu: üye web uygulaması, ayrı CMS, mobil uygulama (Capacitor) ve Spring Boot API.
Mimari kararlar [TASARIM.md](TASARIM.md)'de, ölçümler [OLCUMLER.md](OLCUMLER.md)'de, güncel ilerleme [DURUM.md](DURUM.md)'de.

## Depo yapısı
| Klasör | İçerik |
|---|---|
| `backend/` | Java 21 + Spring Boot 3.5 modüler monolit, Liquibase göçleri |
| `apps/web` | Üye web uygulaması (React + TS + Vite, :5173) |
| `apps/cms` | İçerik yönetim sistemi, ayrı uygulama (:5174) |
| `apps/mobile` | Capacitor Android uygulaması (web kodunu paketler), iOS (yalnız simülatörde derlenip açıldı), Maestro akışı |
| `mock-api/` | Araçlar için sahte dış servis (tek dosya, yalnız JDK) |
| `e2e/` | Playwright uçtan uca ve erişilebilirlik testleri |
| `loadtest/` | Yük testi ve Lighthouse betikleri, ham sonuçlar |
| `docs/` | `openapi.yaml`, tasarım sistemi, ekran görüntüleri, erişilebilirlik raporu |
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
| Sahte dış API (araçlar için) | http://localhost:8090 — `java mock-api\MockApi.java` (yalnız JDK; `scripts\dev.ps1` de başlatır). Tohum verisindeki üç öğrenme aracı (Metin özetleyici, Terim sözlüğü, Metin denetleyici) bunu kullanır; kurala dayalıdır, gerçek yapay zekâ değildir |

## Docker Compose
`docker-compose.yml` dosyası Postgres ve Mailpit'i ayağa kaldırır (backend, web ve sahte API yerelde çalışır). **Durum: test edilmedi.** Geliştirme makinesinde Docker kurulu değil. Doğrulanmış yol, yukarıdaki Docker'sız kurulumdur.

## Testler
Backend birim ve entegrasyon testleri (`basamak_test` DB'sini kullanır; Mailpit çalışıyor olmalı). Kurulumdaki 1. ve 2. adımlar gerekir: `.env` olmadan testler veritabanına bağlanamaz (`password authentication failed`). Testler bittiğinde satır kapsamı %80'in altındaysa derleme kırılır (JaCoCo).
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
| Demo topluluk | `gizem@basamak.local`, `mert@basamak.local`, `zeynep@basamak.local` | Web, mobil. Profilleri açık; iş akışı paylaşmış, puan vermiş, yorum yazmış üyeler. `member@basamak.local` Gizem ve Mert'i takip eder, Zeynep "Kimi takip edebilirsin?" önerisinde çıkar |

### Giriş akışları
**Üye (web ve mobil): şifre; yeni cihazda ek olarak e-postayla gelen kod**
1. E-posta ve şifreyi girin, **Devam**'a basın.
2. **Bu tarayıcı ya da uygulama daha önce hatırlandıysa** doğrudan giriş yapılır.
3. **Yeni bir cihazdaysanız** hesabın kayıtlı adresine 6 haneli bir kod gönderilir. Yerelde e-postalar **Mailpit**'e düşer (http://localhost:8025; arama kutusuna kendi adresinizi yazın), gerçek ortamda yapılandırılmış SMTP kullanılır. Ekranda maskelenmiş adres ve kodun kalan süresi görünür.
4. Kodu girerken **"Bu cihazı 30 gün hatırla"** varsayılan olarak işaretlidir. Sonraki girişlerde o cihazda yalnız şifre yeter. Şifre sıfırlanınca hatırlanan bütün cihazlar unutulur.

Kod 10 dakika geçerlidir ve tek kullanımlıktır. 60 saniye sonra "Kodu yeniden gönder" ile yeni kod istenebilir; yeni kod öncekini geçersiz kılar. 5 yanlış denemeden sonra şifreyle baştan başlanır.

**CMS (içerik ekibi): şifre + TOTP doğrulayıcı uygulaması (ödev gereği zorunlu)**
1. İlk girişte CMS adım adım bir kurulum ekranı gösterir. Telefona ücretsiz bir doğrulayıcı uygulama kurun (Google Authenticator, Microsoft Authenticator, Aegis…) ve ekrandaki QR kodu okutun. QR okutulamıyorsa "Anahtarı elle gir" bölümündeki anahtar kopyalanıp uygulamaya yazılabilir.
2. Sonraki girişlerde uygulamanın gösterdiği "Basamak CMS" kodunu girin.
3. Telefon yoksa bilgisayarda da olur: Chrome/Edge için ücretsiz "Authenticator" eklentisine kurulum ekranındaki anahtar elle girilir.
4. Doğrulayıcıya erişimi kaybeden ekip üyesinin ayarını bir **yönetici** CMS'teki **Ekip** sayfasından sıfırlar; bir sonraki girişte kurulum ekranı yeniden açılır. Yönetici kendi ayarını sıfırlayamaz (başka bir yönetici gerekir). Yerel geliştirmede tek yönetici kilitlenirse: `UPDATE users SET totp_enabled = false, totp_secret_enc = NULL WHERE email = '...'`.
5. Üyeler (web ve mobil) doğrulayıcı uygulama kullanmaz: yeni cihazdan girişte e-postaya kod gelir.

## Mobil (Android)
Gerekenler: Android Studio (SDK ve bir emülatör, örneğin Pixel 7, API 35) ve Java 21. API emülatörden `http://10.0.2.2:8080` adresine gider (`apps/web/.env.mobile`). Önce backend'i yukarıdaki gibi başlatın.
```powershell
npm run build:mobile -w apps/web
```
```powershell
cd apps/mobile; npx cap sync android; cd android; .\gradlew.bat assembleDebug
```
```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```
- Derin bağlantı denemesi: `adb shell am start -a android.intent.action.VIEW -d "basamak://trainings/<id>"`.
- **iOS:** Mac olmadan GitHub'ın ücretsiz macOS makinesinde denendi: `.github/workflows/ios.yml` iOS projesini üretir, iPhone simülatörü için derler, simülatörü açar, uygulamayı kurup başlatır ve açık-koyu ekran görüntüsü alır (`docs/mobile/ios-*.png`). Sonuç: **derleniyor ve açılıyor**. İlk görüntü bir hatayı da gösterdi: üst çubuk iOS durum çubuğunun altına giriyordu (`docs/mobile/ios-before-safe-area.png`). Düzeltildi ve ikinci çalışmada doğrulandı. Sınırlar: Makinede backend yoktu, uygulama giriş ekranında kaldı. Giriş yapılmış tam bir tur, gerçek iPhone, iOS bildirimi (APNs, ücretli hesap) **denenmedi**. iOS'ta oturum jetonu Keychain'de değil, Capacitor Preferences'ta (düz) saklanıyor. Mac'te kendi denemeniz için adımlar TASARIM §8.3'te.
- **Push:** FCM kurulmadı. Bildirimler uygulama açıkken ya da arka plandayken yerel bildirim olarak gelir. Uygulama tamamen kapalıyken bildirim yoktur.

## API
- OpenAPI arayüzü: http://localhost:8080/swagger-ui.html. Dosya olarak: [`docs/openapi.yaml`](docs/openapi.yaml) (OpenAPI 3.1, 119 yol). Yeniden üretmek için backend çalışırken `curl http://localhost:8080/v3/api-docs.yaml -o docs/openapi.yaml`.
- Hatalar RFC 7807 `problem+json` biçimindedir: `code`, Türkçe `detail` ve `requestId` alanlarını içerir.

## Zorunlu senaryolar: durum ve demo adımları
Durumlar yalnız gerçek test sonucuna göre yazıldı. "Kanıt" sütunu otomatik testleri gösterir.

| # | Senaryo | Durum | Kanıt | Demo (elle) |
|---|---|---|---|---|
| 1 | Dinamik Journey | **Çalışıyor** (web, CMS, Android) | `ContentWorkflowIT.scenario1`, `e2e/cms.spec.ts` | CMS → Journey'ler → Yeni (yazar) → onay → yayın. Web ana sayfada 4. Journey görünür, kod değişmez |
| 2 | Onay, yayın, v2, fark, geri alma | **Çalışıyor** | `ContentWorkflowIT.scenario2_*` | Yazar eğitimi gönderir. `editor` geri gönderir (gerekçe) ya da onaylar, `publisher` yayınlar. v2'yi yapın; Geçmiş sekmesinde iki sürüm ve fark görünür, "Bu sürümü yeniden yayınla" v1'i geri getirir |
| 3 | Eğitim ortasında v2 | **Çalışıyor** | `LearningIT.scenario3_*` | Üye v1'de bir ders bitirir, CMS'te v2 yayınlanır. Üye v1'de kalır ve "Yeni sürüme geç" uyarısı görür |
| 4 | Eşzamanlı düzenleme (409) | **Çalışıyor** | `scenario4_*` (gerçek eşzamanlı) | İki tarayıcıda `editor` ve `editor2` aynı taslağı açıp kaydeder. İkincisi çakışma ekranı alır |
| 5 | Kayıt → sertifika | **Çalışıyor** | `LearningIT.scenario5_*`, `e2e/member.spec.ts` | Kayıt → Mailpit'te doğrulama → ders, quiz (rastgele sıra, deneme hakkı), anket → puan ve ilerleme → Sertifikalar'da PDF ve QR → `/verify/{seri}` |
| 6 | Araç: tanım, sır, deneme konsolu, çalıştırma | **Çalışıyor** (web ve CMS; mobilde tam tur yapılmadı) | `ToolIT`, `e2e/tools.spec.ts` | CMS → Araçlar → sahte API ile tanım, sır, deneme. Eğitime ekle, üye çalıştırır. Canlı durum; `/slow` ile zaman aşımı, `/flaky` ile yeniden deneme |
| 7 | SSRF engeli | **Çalışıyor** | `UrlGuardTest`, `ToolIT.scenario7_*` | Üye "Araçlarım" → URL `http://127.0.0.1` ya da `http://169.254.169.254` → kaydedilemez |
| 8 | Araç → YZ → koşul → alt akış | **Çalışıyor** (web; YZ sahte sağlayıcı) | `WorkflowIT.scenario8_*`, `e2e/workflows.spec.ts` | Üret → Yeni iş akışı (şablondan) → çalıştır. Adım başına girdi, çıktı, süre, token ve maliyet görünür. Hatalı adımdan "devam". Döngü kaydedilemez |
| 9 | Cron ve webhook | **Çalışıyor** | `WorkflowIT.scenario9_*` | Akış → Ayarlar → zamanla ya da webhook adresi. Aynı `Idempotency-Key` ile iki POST → tek çalıştırma |
| 10 | Dinamik puan kuralları | **Çalışıyor** | `GamificationIT` (100 açma → 1 puan, çift olay → 1 kayıt) | CMS → Puan kuralları → değer değiştir ve yayınla. Sonraki olay yeni değerle yazılır |
| 11 | Canlı sıralama, çatal yazara puan | **Çalışıyor** (web) | `WorkflowIT.scenario11_*` | İki tarayıcı: biri akışı paylaşır, öbürü çatallar. Sıralama yenilemeden güncellenir |
| 12 | Geçmişim, veri indirme, hesap silme | **Çalışıyor** | `PrivacyReportIT.scenario12_*`, `e2e/privacy.spec.ts` | Profil → Geçmişim (süzme, sayfalama). Hesap → Verilerimi indir (JSON) → Hesabımı sil |
| 13 | CMS raporları ve CSV | **Çalışıyor** | `PrivacyReportIT.scenario13_*` | CMS → Raporlar → eğitim → soru başına doğru oranı, anket dağılımı → CSV |
| 14 | Mobil | **Android'de çalışıyor** (emülatörde elle). iOS: simülatörde derlenip açıldı, tam tur yok. FCM yok | `docs/mobile/*.png`, `apps/mobile/maestro` (çalıştırılmadı) | Emülatörde giriş → Öğren → ders → uçak modunda ders ve quiz → çevrimiçi olunca eşitleme → araç çalıştırma → bildirime dokun → ilgili ekran → Profil → Geçmişim → `basamak://` bağlantısı |
| 15 | 320 px, koyu tema, klavye | **Çalışıyor** | `e2e/quality.spec.ts` (21 ekran × 2 tema, 0 ihlal), `e2e/ux.spec.ts` | Tarayıcı 320 px, sistem koyu teması, yalnız klavye ile gezinme |

Ölçümler (Lighthouse 100/100/100, yük testi p95, soğuk açılış, kapsam): [OLCUMLER.md](OLCUMLER.md).

## Ekran kaydı senaryosu (en fazla 15 dakika)
Hazırlık: backend, web, CMS, Mailpit, sahte API ve Android emülatörü açık olsun. Bir tarayıcıda web (üye), ikinci bir tarayıcı profilinde CMS açılsın.

| Dakika | Bölüm | Gösterilecekler |
|---|---|---|
| 0:00–1:00 | Giriş | Mimari tek cümleyle: modüler monolit, PostgreSQL, web, CMS ve Android. Depo yapısı |
| 1:00–3:30 | CMS: içerik ve sürüm (1, 2, 4) | Yazar yeni Journey ve eğitim oluşturur (ders, quiz, anket). Editör geri gönderir, sonra onaylar, yayıncı yayınlar. v2 yayınlanır. Geçmişte fark ve v1'in yeniden yayını. İki editörle çakışma ekranı |
| 3:30–6:00 | Üye: öğrenme (5, 3, 10) | Kayıt → Mailpit doğrulama → yeni Journey web'de görünür. Ders, quiz ve anket. Puan ve ilerleme artar. Sertifika PDF'i ve QR doğrulama. v1'deyken v2 uyarısı |
| 6:00–8:00 | Araçlar (6, 7) | CMS'te araç tanımı, sır ve deneme konsolu. Üye çalıştırır: canlı durum, zaman aşımı ve yeniden deneme. Üyenin `127.0.0.1` aracı reddedilir |
| 8:00–10:00 | İş akışları (8, 9, 11) | Şablondan akış: araç → YZ → koşul → alt akış. Adım ayrıntıları, hatalı adımdan devam, döngü uyarısı, webhook. Paylaş, çatalla, sıralama canlı değişir |
| 10:00–11:00 | KVKK ve rapor (12, 13) | Geçmişim, JSON indirme, hesap silme. CMS raporları ve CSV |
| 11:00–13:30 | Android (14) | Açılış ekranı, alt gezinme, geri tuşu. Ders, uçak modunda tamamlama ve eşitleme. Bildirime dokununca ekran açılır. Derin bağlantı |
| 13:30–14:30 | Kalite (15) | 320 px ve koyu tema. OLCUMLER.md özeti. Yapılmayanlar |

## Yapılmayanlar ve test edilmeyenler
- **iOS:** Yalnız simülatörde derlenip açıldı (giriş ekranı); giriş yapılmış tam tur ve gerçek iPhone denenmedi, oturum jetonu Keychain'de değil. **FCM push:** Yok, yalnız yerel bildirim var. **Gerçek Android cihaz:** Denenmedi, yalnız emülatör.
- **Docker Compose:** Test edilmedi. **Maestro akışı:** Yazıldı, çalıştırılmadı. **Ollama yapay zekâ sağlayıcısı:** Yazıldı, denenmedi.
- **Kodda olmayanlar (yalnız tasarımda):** Sessiz saatler, OpenTelemetry, sürükle bırak akış kurucusu, HLS video, arama ve çok dillilik (3.11), Content-Security-Policy başlığı, otomatik bağımlılık taraması. Ayrıntı: TASARIM §11.
- **Figma:** Tasarım panosu `docs/tasarim/basamak-tasarim-panosu.png` dosyasında; ayrıntı ve token farkları `docs/tasarim-sistemi.md` içinde. Figma bağlantısı teslim e-postasındadır.
