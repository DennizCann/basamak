# Ölçümler

> Bu dosyada **yalnız gerçekten koşulmuş** ölçümler yer alır. Her ölçümün yanında komutu, tarihi ve ortamı yazılıdır.
> Ölçülmemiş kalemler "ölçülmedi" olarak bırakılır. Tüm ölçümler **24.11.2026** sabahı tek bir geliştirici makinesinde yapıldı.

**Test makinesi:** Windows 11 Pro, 15,7 GB RAM, PostgreSQL 18 (yerel), Java 21 (Temurin), Node 24. Yük üreten istemci ile sunucu **aynı makinede** çalıştı. Sayılar bu yüzden ağ gecikmesi içermez, istemci de aynı CPU'yu paylaşır.

## Özet

| Ölçüm | Hedef (PDF) | Sonuç | Durum |
|---|---|---|---|
| Lighthouse mobil: ana sayfa, eğitim oynatıcı, Geçmişim | Perf ≥ 80, A11y ≥ 95, BP ≥ 95 | **100 / 100 / 100** (üç sayfada da) | karşılandı |
| axe-core: 21 ekran (üye, oturumsuz, CMS düzenleyici dahil), açık ve koyu tema | 0 kritik, 0 ciddi | **0 kritik, 0 ciddi, 0 diğer** | karşılandı |
| 320 px genişlik: yatay kaydırma | yok | **16 ekranda yok** | karşılandı |
| Klavye: Tab ile gezinmede odak görünür mü | her zaman | **25 Tab adımının hepsinde görünür** | karşılandı |
| Yük: eğitim okuma p95 | ≤ 300 ms | **27,3 ms** (1.418 istek/sn, %0 hata) | karşılandı |
| Yük: araç çalıştırma başlatma p95 | ≤ 500 ms | **8,5 ms** (10 başlatma/sn, %0 hata) | karşılandı |
| Yük: sıralama p95 | ≤ 200 ms | **8,5 ms** (3.069 istek/sn, %0 hata) | karşılandı (veri hacmi küçük, aşağıya bakın) |
| Mobil soğuk açılış | < 2 s (orta seviye Android) | **ilk kare 1,11 s, içerik hazır 1,27 s** (medyan) | karşılandı (emülatörde) |
| Backend test kapsamı (JaCoCo) | alan katmanında %80 | **satır %87,3**, dal %64,3 (110 test) | karşılandı; `mvnw verify` artık %80'in altında kırılır |

## 1. Erişilebilirlik, 320 px ve klavye (`e2e/quality.spec.ts`)
- **Komut:** `npx playwright test -c e2e quality.spec.ts`. Ayrı e2e yığını kullanılır: :8081 backend, :5183 web, :5184 CMS ve her başlangıçta yeniden kurulan `basamak_test` veritabanı.
- **Kurallar:** axe-core 4.x, WCAG 2.0/2.1/2.2 A ve AA etiketleri. Her ekran açık ve koyu temada ayrı ayrı taranır (`prefers-color-scheme` öykünmesi).
- **Ekranlar (21):**
  - **Oturumsuz:** Giriş, Kayıt, Bileşen kataloğu.
  - **Üye:** Öğrenimim, Eğitim oynatıcı, Geçmişim, Keşfet, Araçlarım, İş akışları, İş akışı kurucu, Topluluk, Sıralama, Bildirimler, Profil, Hesap, Sertifikalar.
  - **CMS:** içerik listesi, **içerik düzenleyici**, raporlar, moderasyon, ekip.
- **Sonuç:** 0 ihlal (ham çıktı: `docs/quality-report.json`). İlk koşu 2 ciddi ihlal buldu ve düzeltildi:
  - **Renk karşıtlığı:** AI bütçe çubuğunun CSS sınıfı ortak `Meter` bileşeninin sınıfıyla çakışıyordu. Sınıf adı `.budget-meter` olarak değiştirildi.
  - **Görünen metin ile erişilebilir ad uyumsuzluğu:** CMS raporlarındaki "Ayrıntı" düğmesinin erişilebilir adı görünen metinle başlamıyordu. Ad artık "Ayrıntı: …" diye başlıyor.
- **320 px:** Oturumsuz ve üye ekranlarının (16) her birinde `document.scrollWidth` ≤ 321. CMS masaüstü uygulaması olduğu için 320 px kontrolüne girmedi.
- **Klavye:** Ana sayfanın başından 25 kez Tab'a basıldı. Her adımda odaklanan öğede en az 2 px dış çizgi ya da odak halkası vardı.
- **Sınır:** Otomatik tarama WCAG'in yalnız bir kısmını yakalar. Ekran okuyucuyla (TalkBack/NVDA) elle test **yapılmadı**.

## 2. Yük testi (`loadtest/run.mjs`)
- **Araç:** k6 yerine düz Node betiği kullanıldı (indirme gerekmez). Yöntem aynıdır: sabit sayıda sanal kullanıcı, her uç için 30 sn süre, p50, p95, p99 ve hata oranı.
- **Ortam:** Yalnız ayrı e2e yığını kullanıldı (geliştirici verisine dokunulmadı). Komut:
  `java -jar backend/target/basamak-backend-0.1.0.jar --spring.profiles.active=e2e --basamak.tools.daily-runs-per-level=100000`
  ardından `node loadtest/run.mjs 30 20`. Ham sonuç: `loadtest/results/latest.json`.
  Üye başına günlük araç hakkı yalnız bu test için yükseltildi; amaç hak reddini değil gecikmeyi ölçmekti. Diğer korumalar açıktı.
- **Kurulum:** 20 üye gerçek kayıt, e-posta doğrulama ve e-postayla gelen giriş koduyla oturum açar. Yönetici, CMS API'si üzerinden (TOTP ile) tohumdaki "Terim sözlüğü" aracını içeren bir eğitim yayınlar ve her üye bu eğitime kaydolur.

| Uç | Yük | İstek | İstek/sn | p50 | p95 | p99 | En kötü | Hata |
|---|---|---|---|---|---|---|---|---|
| `GET /api/trainings/{id}` | 20 kullanıcı, olabildiğince hızlı | 42.550 | 1.418 | 11,5 ms | **27,3 ms** | 36,4 ms | 97,8 ms | %0 |
| `POST /api/tool-runs` | 10 başlatma/sn (20 üyeye yayılmış) | 300 | 10 | 6,1 ms | **8,5 ms** | 14,2 ms | 19,1 ms | %0 |
| `POST /api/tool-runs` (aşırı yük) | 20 kullanıcı, olabildiğince hızlı | 87.000 | 2.900 | 5,8 ms | 12,6 ms | 23,3 ms | 104 ms | %99,7 = **429** (beklenen) |
| `GET /api/leaderboard?period=week` | 20 kullanıcı, olabildiğince hızlı | 92.069 | 3.069 | 6,2 ms | **8,5 ms** | 14,3 ms | 51,2 ms | %0 |

**Yorum (dürüst sınırlar):**
- **Araç başlatma:** Ölçülen süre, isteğin kabul edilip kuyruğa yazılmasının süresidir. Dış servisin çalışması işçide eşzamansız olur ve üyeye SSE ile bildirilir. Bu süre dış servise bağlıdır (sahte serviste milisaniyeler).
- **Aşırı yük satırı:** Korumaların çalıştığını gösterir. IP başına dakikada 1.000 başlatma sınırı (e2e profili; normal profilde 30) ve üye başına aynı anda en fazla 5 çalışma aşılınca istek 429 ile, p95 12,6 ms'de reddedildi. Sunucu çökmedi, diğer uçlar etkilenmedi.
- **Sıralama:** Ölçüm küçük veriyle yapıldı (20 üyenin ve tohum hesaplarının defter satırları). Sorgu, haftalık `point_ledger` satırlarını her istekte topluyor. Milyonlarca satırda bu ilk darboğaz olur. Çözüm önerisi (önbellek ya da artımlı toplam tablosu) TASARIM §9'dadır.
- **Kapsam:** Tek makine, ağ gecikmesi yok; 20 eşzamanlı kullanıcıdan fazlası denenmedi.

## 3. Mobil soğuk açılış
- **Cihaz:** Android Emulator, Pixel 7 AVD (`sdk_gphone64_x86_64`), Android 15. Derleme: debug APK (`app-debug.apk`, 24.11 05:13). **Gerçek bir orta seviye cihazda ölçülmedi**; emülatör, host CPU'sunu kullandığı için gerçek cihazdan farklı sonuç verebilir.
- **İlk kare:** `adb shell am start -W -S -n com.basamak.app/.MainActivity` komutu 7 kez çalıştırıldı (`-S` her seferinde süreci öldürür). `TotalTime` sonuçları 1.250, 1.032, 1.240, 1.110, 1.132, 1.089 ve 1.054 ms; **medyan 1.110 ms**. Bu süre açılış ekranının çizildiği andır.
- **İçerik hazır:** Logcat'te başlatma isteğinden `SplashScreen.hide` çağrısına kadar geçen süre ölçüldü. Bu çağrı, oturum kontrolü bitip ilk ekran çizilince yapılır. 5 koşunun sonuçları 1,04, 1,27, 1,23, 1,27 ve 1,31 s; **medyan 1,27 s**. Bu süre, emülatörden backend'e (10.0.2.2:8080) yapılan oturum isteğini de içerir.

## 4. Backend test kapsamı (JaCoCo)
- **Komut:** `cd backend; .\mvnw.cmd verify`. Sonuç: 110 test, 0 hata. Rapor `backend/target/site/jacoco/index.html` dosyasındadır.
- **Toplam:** satır **%87,3** (4.151 satır), dal %64,3.
- **Paket bazında (satır):**
  - %90 ve üstü: journey %100, privacy %97,3, report %93,9, content %93,0, learning %92,0, auth %91,7.
  - %80–90: media %85,5, seed %85,0, common %84,9, community %84,4, tool %84,2, workflow %82,6, gamification %82,5.
  - %80'in altı: jobs %76,8, notification %73,4.
- **Kalite kapısı:** `pom.xml` dosyasına JaCoCo `check` eklendi. Toplam satır kapsamı %80'in altına düşerse `mvnw verify` ve dolayısıyla CI kırılır.
- **Not:** Dal kapsamı (%64) satır kapsamından düşüktür. En zayıf alanlar hata ve yeniden deneme dalları (tool, workflow, media) ile iki paket: jobs (%76,8) ve notification (%73,4).

## 5. Lighthouse (`loadtest/lighthouse.mjs`)
- **Araç:** Lighthouse 13.6.0 (`npx lighthouse@13.6.0`, onayla indirildi), Playwright Chromium 1148, headless. Profil: varsayılan **mobil** (Moto G Power öykünmesi, yavaş 4G ve 4 kat CPU yavaşlatma).
- **Ortam:** Üretim derlemesi kullanıldı (`npm run build -w apps/web`, `vite preview --port 5183`). `/api` ayrı e2e backend'ine (:8081) gider.
- **Oturum:** Her sayfa için yeni bir üye oturumu açılır. Yenileme çerezi `--extra-headers` ile verilir, rapordan silinir ve hiçbir yere yazılmaz. Betik, sayfa giriş ekranına yönlenirse ölçümü geçersiz sayıp durur.
- **İlk geçerli koşu:** Puanlar 100 / 100 / 96 çıktı. Lighthouse iki sorun buldu ve ikisi düzeltildi:
  - **Eksik favicon (404):** Web ve CMS'e `favicon.svg` eklendi.
  - **Görünen metin ile erişilebilir ad uyumsuzluğu:** Dar ekranda "Sertifikalar" görünürken bağlantının adı "Sertifikalarım" idi. Bağlantı artık her genişlikte "Sertifikalar".
- **Son koşu sonuçları:**

| Sayfa | Performans | Erişilebilirlik | En iyi uygulamalar | FCP | LCP | TBT | CLS |
|---|---|---|---|---|---|---|---|
| Ana sayfa (Öğrenimim) | **100** | **100** | **100** | 0,8 s | 1,8 s | 0 ms | 0 |
| Eğitim oynatıcı | **100** | **100** | **100** | 0,8 s | 1,8 s | 0 ms | 0 |
| Geçmişim | **100** | **100** | **100** | 0,8 s | 1,8 s | 0 ms | 0 |

- **Ham raporlar:** `loadtest/results/lighthouse-*.json`; özet `lighthouse-summary.json`.
- **Sınırlar:**
  - Sunucu yerel makinedeydi, ağ gecikmesini yalnız Lighthouse'un öykünmesi ekledi.
  - SEO ve PWA kategorileri ölçülmedi (PDF istemiyor).
  - Betiğin bir koşusu ayrıntı vermeden yarıda kaldı; tekrar çalıştırınca sorunsuz tamamlandı. Yukarıdaki sonuçlar bu tam koşudandır.
