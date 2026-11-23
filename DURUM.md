# DURUM — Basamak geliştirme durumu

> Yeni bir oturumda önce bu dosyayı okuyun. Onaylı planın ayrıntıları için bu dosyaya ve [TASARIM.md](TASARIM.md)'ye bakın.
> Teslim: **24.11.2026 12.00 (Europe/Istanbul)**. Özellik dondurma: **08.00**.

## Çalışma kuralları
- Sıfır ek maliyet.
- Push ve repo oluşturma onayla yapılır.
- Her faz sonunda: test, commit, push, DURUM.md güncellemesi ve kısa rapor. Kullanıcı 23.11 akşamı F2.5'ten itibaren fazlar arası onay beklemeden devam edilmesini istedi.
- Saatler garanti değil, kontrol noktasıdır. Süre aşılırsa kapsam daraltılır.

## Faz durumu
| Faz | Hedef | Durum |
|---|---|---|
| F0 | İskelet, DB, Mailpit, web ve CMS iskeleti, CI, belge iskeletleri | **tamamlandı** (23.11 ~15.40) — F1 için onay bekleniyor |
| F1 | Kimlik, roller, TOTP, sürümleme, yayın, audit, Journey, tohum verisi | **tamamlandı** (23.11 16.45; backend API, UI yok) — F2 için onay bekleniyor |
| F2 | İlk uçtan uca akış (senaryo 1, 2, 3, 4, 5, 10) | **tamamlandı** (23.11 ~17.50) — F2.5 için onay bekleniyor |
| F2.5 | Capacitor iskeleti ve ilk debug APK | **tamamlandı** (23.11 23.40, commit 79f7118): emülatörde giriş ve Journey listesi çalışıyor; iOS test edilmedi |
| F3 | Araçlar, kuyruk, SSE, SSRF (6, 7) | **tamamlandı** (24.11 00.00, commit 06c6586): web ve CMS; mobilde araç ekranı denenmedi |
| F4 | İş akışı motoru, cron ve webhook, fork, sıralama (8, 9, 11) | **tamamlandı** (24.11 00.23, commit 450096f): web; mobilde denenmedi |
| F5 | Geçmişim, KVKK, raporlar (12, 13) | **tamamlandı** (24.11 00.36, commit 8958c2f) |
| F6 | Mobil özellikler (14) | **tamamlandı** (24.11 ~01.00): Android emülatöründe elle doğrulandı; iOS test edilmedi |
| F7 | Kalite ve ölçümler (15) | başlanmadı |
| F8 | Teslim belgeleri | başlanmadı |

## Senaryolar (yalnız gerçek test sonucuyla güncellenir)
| # | Durum | Kanıt |
|---|---|---|
| 1 | web ve CMS tamam; mobil yok | `ContentWorkflowIT.scenario1_*`, `e2e/cms.spec.ts` |
| 2 | tamamlandı (API ve CMS ekranları) | `ContentWorkflowIT.scenario2_*`; CMS tarayıcıda elle denendi |
| 3 | tamamlandı | `LearningIT.scenario3_*` |
| 4 | tamamlandı (API ve çakışma ekranı) | `ContentWorkflowIT.scenario4_*` |
| 5 | tamamlandı (API ve web) | `LearningIT.scenario5_*`, `e2e/member.spec.ts` (gerçek Mailpit) |
| 6 | tamamlandı (web ve CMS) | `ToolIT` (yeniden deneme, zaman aşımı, iptal, SSE tekrar oynatma, sır gizliliği), `e2e/tools.spec.ts` |
| 7 | tamamlandı | `ToolIT.scenario7_*` (21 adres, DNS rebinding, yönlendirme), `UrlGuardTest` (25), `e2e/tools.spec.ts` |
| 8 | tamamlandı (web) | `WorkflowIT.scenario8_*` (eşleme, koşul, alt akış, devam, döngü), `e2e/workflows.spec.ts` |
| 9 | tamamlandı (API + web ekranı) | `WorkflowIT.scenario9_*` |
| 11 | tamamlandı (web) | `WorkflowIT.scenario11_*`, `e2e/workflows.spec.ts` (canlı sıralama) |
| 10 | tamamlandı | `GamificationIT` (5 test) |
| 15 | kısmen (giriş ve ana sayfa: 320 px, koyu tema, klavye) | `e2e/member.spec.ts` |
| 12 | tamamlandı (web) | `PrivacyReportIT.scenario12_*` (3), `e2e/privacy.spec.ts` |
| 13 | tamamlandı (CMS) | `PrivacyReportIT.scenario13_*`, `e2e/privacy.spec.ts` |
| 14 | kısmen (Android emülatör: giriş, Journey, ders, çevrimdışı ve eşitleme, bildirimden ekrana geçiş, derin bağlantı, Geçmişim; PDF paylaşımı denenmedi; iOS yok; FCM yok) | elle: `docs/mobile/*.png`; `NotificationIT` | — (14: Android'de yalnız giriş ve Journey listesi elle doğrulandı) |

## F1'de yapılanlar (özet)
- **Kimlik:**
  - Kayıt (KVKK açık rızası zorunlu), e-posta doğrulama (Mailpit), giriş, şifre politikası.
  - 5 hatalı denemede 15 dakika kilit, IP başına hız sınırı.
  - Döndürülen refresh token (web cookie ve CSRF başlığı; mobilde gövde), oturum listeleme ve kapatma, şifre sıfırlama.
- **CMS girişi:** Şifre ve TOTP zorunlu (ilk girişte doğrulayıcı kaydı). Ayrı audience ve ayrı cookie.
- **İçerik:**
  - Tam kopya sürümleme, taslak → inceleme → onay veya gerekçeli geri gönderme → yayın, zamanlanmış yayın, geri alma.
  - JSON fark, optimistic lock ile 409, yazar için nesne düzeyinde yetki, dört göz ilkesi.
  - Append-only audit, ön koşul döngüsü kontrolü.
- **Journey:** Üye API'si yalnız yayınlanmış Journey'leri döner.
- **Tohum verisi:** 6 demo hesabı (şifre `Basamak!2026`) ve onay akışından geçerek yayınlanmış 3 Journey.

## Ortam
- **Yerel:** Windows 11, Java 21, Node 24, PostgreSQL 18 (servis), Android SDK ve Studio.
- **Docker yok.** Kullanıcı kurabilir; compose dosyası henüz doğrulanmadı.
- Maven ve Mailpit `tools/` klasöründe (gitignore'da). Backend Maven Wrapper kullanıyor.
- `.env` yerelde ve gizli tutuluyor. Şablon: `.env.example`.

## Test kayıtları
| Tarih/saat | Komut | Sonuç |
|---|---|---|
| 23.11 ~15.00 | `npm run build` (web + cms) | başarılı |
| 23.11 ~15.10 | `mvnw -DskipTests package` | başarılı (jar üretildi) |
| 23.11 ~15.35 | `cd backend; .\mvnw.cmd -B verify` | **BUILD SUCCESS**, 2/2 test (`HealthIT`: health ve DB UP, korumalı uç nokta 401) |
| 23.11 ~15.37 | `java -jar` ile ana `basamak` DB'sine karşı smoke test | health UP, db UP, `/api/public/ping` → ok, `/actuator/metrics` → 401 |

| 23.11 16.40 | `cd backend; .\mvnw.cmd -B verify` | **BUILD SUCCESS, 18/18**: AuthIT 6, ContentWorkflowIT 8, RateLimitFilterTest 2, HealthIT 2 |
| 23.11 16.43 | `java -jar` + `curl` (ana DB) | Göçler ve tohum verisi uygulandı; `member@` girişi 200 ve JWT; CMS şifre adımı `setupRequired=true` |

| 23.11 17.44 | `cd backend; .\mvnw.cmd -B verify` | **27/27**: AuthIT 6, ContentWorkflowIT 8, GamificationIT 5, LearningIT 4, HealthIT 2, RateLimitFilterTest 2. Sondaki BUILD FAILURE testlerden değil: çalışan backend jar'ı kilitliyordu; kopyadan çalıştırmaya geçildi ve derleme temiz |
| 23.11 17.38 | `$env:PW_CHANNEL="msedge"; npm run e2e` | **3/3**: kayıt, Mailpit doğrulaması ve ders tamamlama; CMS'te Journey oluşturma ve web'de görünme; 320 px, koyu tema ve klavye. CMS testi tekrar çalıştırıldı, yine geçti |
| 23.11 17.44 | `npm run build` | web ve CMS başarılı |

| 23.11 ~20.00 | QA fazı: `cd backend; .\mvnw.cmd -B -o verify` | **33/33** (yeni: JourneyPolicyIT 5, AuthIT eşzamanlı refresh) |
| 23.11 ~20.05 | QA fazı: `npm run typecheck`, `npm run build` | temiz |
| 23.11 ~20.10 | QA fazı: `$env:PW_CHANNEL="msedge"; npm run e2e` (izole :8081/:5183/:5184) | **8/8** |
| 23.11 ~23.35 | F2.5: `npm run typecheck`, `npm run build`, izole e2e (msedge) | temiz; **21 geçti** (1 isteğe bağlı ekran testi atlandı). Backend kodu değişmediği için `mvnw verify` yeniden çalıştırılmadı (son: 46/46) |
| 23.11 ~23.30 | F2.5: `gradlew assembleDebug` ve `adb install`, Pixel 7 emülatörü | APK kuruldu; giriş, kod ve Journey listesi elle doğrulandı |
| 23.11 ~23.55 | F3: `cd backend; .\mvnw.cmd -B -o verify` | **79/79** (yeni: ToolIT 8, UrlGuardTest 25) |
| 23.11 ~23.58 | F3: typecheck, build, izole e2e (msedge; sahte API :8090) | temiz; **23 geçti**, 1 isteğe bağlı test atlandı (yeni: `e2e/tools.spec.ts` 2) |
| 24.11 ~00.15 | F4: `mvnw -B -o verify` | **86/86** (yeni: WorkflowIT 7) |
| 24.11 ~00.20 | F4: typecheck, izole e2e (msedge) | **24 geçti**, 1 isteğe bağlı test atlandı. İlk koşuda 3 hata: 2'si yeni menü bağlantılarının mobil başlığı 64 px'ten uzun yapmasıydı (düzeltildi: dar ekranda bu bağlantılar hesap menüsünde), 1'i tools.spec'te tekrar etmeyen bir hataydı (yavaş koşu, 4,4 dk) |
| 24.11 ~00.30 | F5: `mvnw -B -o verify` | **90/90** (yeni: PrivacyReportIT 4) |
| 24.11 ~00.34 | F5: typecheck, izole e2e | **26 geçti**, 1 atlandı. e2e gerçek bir hata buldu: cevapsız anket sorusunda rapor 500 veriyordu (`Map.of` null kabul etmiyor); düzeltildi ve entegrasyon testine eklendi |
| 24.11 ~00.55 | F6: `mvnw -B -o verify` | **91/91** (yeni: NotificationIT) |
| 24.11 ~00.58 | F6: izole e2e | **26 geçti**, 1 atlandı |
| 24.11 ~00.50 | F6: APK, Pixel 7 emülatörü | Keystore'a geçiş (düz depoda token yok), derin bağlantı, uçak modunda ders tamamlama → bağlantı gelince eşitlendi (sunucuda doğrulandı), bildirim → dokununca akış ekranı (2 hata bulundu ve düzeltildi), FLAG_SECURE (hesap ekranı siyah, diğer ekranlar normal) |

## QA düzeltme fazı (23.11, commit edilmedi — onay bekleniyor)
Tarayıcı incelemesi bulguları için ayrıntılı rapor sohbette. Özet: boş Journey politikası, E2E izolasyonu, CMS dönüş bağlantısı, öge anahtarı koruması (sunucu ve UI), quiz incelemesi ve cevap gösterme politikası, URL ile içerik gezinmesi, ortak bileşenli etkileşimli önizleme, Türkçe etiketler, PDF indirme geri bildirimi.
Testlerin ortaya çıkardığı ek gerçek hatalar: e-posta doğrulamada StrictMode çift isteği, eşzamanlı refresh yarışı (CMS oturum kaybı), CMS listesinde geç gelen cevap yarışı, önizlemede viewport'a göre yanlış yerleşim.
**Android/iOS: Capacitor henüz yok; hiçbir mobil kontrol yapılmadı.** Bulgu raporunun 13. bölümü F2.5/F6 kabul listesine taşındı.

## Üye UX fazı (23.11 akşam, commit edilmedi — onay bekleniyor)
- **Yaklaşım:** `docs/ux/YAKLASIM.md`. **Önce/sonra görüntüleri:** `docs/ux/before`, `docs/ux/after` (izole yığın, gerçek test kullanıcıları).
- **Yapılanlar:**
  - Yeni başlık ve hesap menüsü (tema ve çıkış menüye taşındı).
  - Duruma göre tek birincil eylemli ana sayfa (yeni / devam / sıradaki / hepsi bitti).
  - Adım listesi biçiminde öğrenme yolu, taranabilir eğitim kartları.
  - Sertifikalarım sayfası, gerçek kurallardan "Puanlar nasıl kazanılır?".
  - Ders ekranında yapışkan menü, mobilde kapalı içerik menüsü, Önceki/Sonraki.
  - Yeniden tasarlanan doğrulama sayfası.
- **Salt okunur backend eklemeleri:** Eğitim kartı alanları (`summary`, `itemCount`, `completedItems`, `currentKey`, `lastActivityAt`) ve `GET /api/point-rules`. Veri modeli ve iş kuralı değişmedi.
- **Testler:** backend 34/34; typecheck ve build temiz; izole e2e **16/16** (yeni `e2e/ux.spec.ts`: 8 kabul senaryosu, %200 metin büyütme dahil).
- **Yan düzeltme:** CMS'te liste yüklenmeden "Taslak oluştur" basılınca yeni Journey'in sırası 1 oluyordu; artık düğme liste yüklenene kadar pasif.

## F2.5 Erken Android (23.11 akşam)
- `apps/mobile`: Capacitor 7 (Android projesi üretildi, iOS yalnız yapılandırma). Web kodu `npm run build:mobile -w apps/web` ile paketleniyor. Ayrıntı: TASARIM §8 ve ADR-5.
- **Emülatör doğrulaması (Pixel 7, API 35):** APK kuruldu ve açıldı. Şifre ve Mailpit kodu ile giriş, ana sayfa ve Journey listesi çalışıyor; uygulama kapatılıp açılınca oturum geri yükleniyor. Ekran görüntüleri: `docs/mobile/`.
- **Bulunan ve düzeltilen:** Android 15 edge-to-edge yüzünden başlık durum çubuğunun altında kalıyordu.
- **Yapılmadı / test edilmedi:** iOS (macOS gerekir), gerçek Android cihaz, Keystore, çevrimdışı mod, bildirimler, derin bağlantı, mobilde PDF indirme (hepsi F6).
- APK üretimi: `cd apps/mobile/android; $env:JAVA_HOME=<JDK 21>; $env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"; .\gradlew.bat assembleDebug`.

## F3 Araçlar (23.11 gece)
- **Backend:**
  - Migration `0006` (`tool_secret`, `user_tool(+version)`, `job`, `tool_run`, `tool_run_event`).
  - `UrlGuard` (SSRF), `ToolDefinition` (şema ve güvenli şablon), `ToolExecutor` (HttpClient5, denetlenmiş DNS, 1 MB, elle yönlendirme).
  - `JobQueue` ve `JobWorker` (SKIP LOCKED, kira, yeniden deneme), `ToolRunService` (durumlar, iptal, puan, eğitim ögesi), `RunEvents` (SSE ve Last-Event-ID).
  - CMS sır ve deneme konsolu uç noktaları, üye araçları API'si.
- **CMS:** "Araçlar" menüsü, araç formu, "Sırlar ve deneme" sekmesi, eğitime "+ Araç" ile sürüm sabitleme.
- **Web:** Eğitimde araç ögesi (form, canlı durum, iptal, sonuç). "Araçlar" sayfası: kendi aracını oluşturma, düzenleme, çalıştırma ve son çalıştırmalar.
- **Sahte API:** `java mock-api/MockApi.java` (:8090). `scripts/dev.ps1` ve Playwright yapılandırması bunu başlatıyor.
- **Dev DB:** Migration 0006 uygulandı (yalnız yeni tablolar; veri silinmedi). Backend yeni jar'la yeniden başlatıldı.
- **Yapılmadı:** Devre kesici, çıktı temizliği, OAuth2 client credentials (TASARIM §11). Araç ekranı mobilde denenmedi (F6'da APK yenilenecek).

## F4 İş akışları (24.11 gece)
- **Backend:**
  - Migration `0007` (`workflow`, `workflow_version`, `workflow_run`, `workflow_step_run`, defter zaman indeksi).
  - `WorkflowDefinition` (şema, eşleme, koşul), `WorkflowService` (kayıt, döngü, paylaşım, çatal, cron, webhook), `WorkflowEngine` (kuyruk işi, adım kaydı, devam, alt akış), `WorkflowTriggers` (cron), `MockAiProvider`, `LeaderboardController` (SSE).
- **Web:** "Akışlar" (liste, topluluk, çatallama), akış sayfası (adım kurucu, çalıştırma, adım tablosu, devam, cron, webhook, paylaşım), "Sıralama" (canlı). Dar ekranda bu bölümler hesap menüsünde.
- **Dev DB:** Migration 0007 yalnız yeni tablo ve indeks ekler.
- **Yapılmadı:** Görsel sürükle-bırak kurucu (form tabanlı kurucu var), paralel adım çalıştırma, gerçek YZ sağlayıcısı (Ollama), iş akışı için SSE (adım tablosu 0,8 sn'de bir yoklanıyor), CMS'te iş akışı şablonu türü.

## F5 Geçmiş, KVKK, raporlar (24.11 gece)
- **Backend:** `PrivacyService` ve `PrivacyController` (`/api/me/history`, `/api/me/export`, `/api/me/delete`), `ReportService` ve `CmsReportController` (JSON ve 3 CSV).
- **Web:** "Geçmişim" (süzgeçler URL'de) ve "Hesabım ve verilerim" (indirme, silme). İkisi de hesap menüsünde.
- **CMS:** "Raporlar".
- **Yapılmadı:** Açık uçlu cevaplarda kişisel veri taraması, saklama süresi işleri (TASARIM §7.6).

> **Saat düzeltmesi (24.11 ~01.00):** F2.5–F5 için önceden yazılan saatler tahminiydi ve yanlıştı. Yukarıdaki saatler commit zamanlarından düzeltildi.

## F6 Mobil özellikler (24.11 gece)
- **Native:** `SecureStoragePlugin` (EncryptedSharedPreferences ve Keystore), `PrivacyScreenPlugin` (FLAG_SECURE), `basamak://` intent filtresi, POST_NOTIFICATIONS izni.
- **Web (mobil kipi):** `offline.ts` (önbellek ve giden kutusu), `MobileBridge.tsx` (derin bağlantı, geri tuşu, gizlilik ekranı, eşitleme, bildirimler), `share.ts` (PDF paylaşımı).
- **Backend:** Migration `0008` (`notification`), `NotificationService` ve `NotificationController` (liste, okundu, SSE). Araç, iş akışı, sertifika ve çatal bildirimleri. Bildirimler hesap silmede silinir, dışa aktarmada yer alır.
- **Yapılmadı:** FCM push (Firebase projesi gerekir), iOS Keychain ve iOS testi, App Links (alan adı gerekir), PDF paylaşımının cihazda denenmesi.

## Çalıştırma notu
Backend'i `tools/run/backend.jar` kopyasından çalıştırın (`java -jar tools\run\backend.jar`). `target/` altındaki jar çalışırken Windows dosyayı kilitliyor ve `mvnw verify` repackage adımında başarısız oluyor.

## Açık işler ve engeller
- [x] `scripts/db-init.ps1` — BOM hatası düzeltildi; kullanıcı çalıştırdı, iki DB de hazır.
- [x] Mailpit çalışıyor (kullanıcı doğruladı, :8025).
- [x] CI: #1 ve #2 backend'de başarısızdı. Sebep: CI'da Mailpit yoktu, mail sağlık kontrolü DOWN dönüyordu, HealthIT 503 alıyordu. Mailpit servis container'ı eklendi; **CI #3 (`243cf1f`) yeşil** (backend ve frontend).
- [ ] Web ve CMS dev sunucusu tarayıcıda açılarak denenmedi; yalnız derlemeleri doğrulandı (F1/F2'de UI ile birlikte doğrulanacak).
- [ ] Kullanıcı: Docker Desktop kurulumu, Firebase projesi, GitHub'da private repo (hepsi isteğe bağlı ya da paralel).
