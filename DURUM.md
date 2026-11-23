# DURUM — Basamak geliştirme durumu

> Yeni bir oturumda önce bu dosyayı okuyun. Onaylı planın ayrıntıları için bu dosyaya ve [TASARIM.md](TASARIM.md)'ye bakın.
> Teslim: **24.11.2026 12.00 (Europe/Istanbul)**. Özellik dondurma: **08.00**.

## Çalışma kuralları
- Sıfır ek maliyet.
- Push ve repo oluşturma onayla yapılır.
- Her faz sonunda: test, yerel commit, DURUM.md güncellemesi, kısa rapor ve **onay beklenir**.
- Saatler garanti değil, kontrol noktasıdır. Süre aşılırsa kapsam daraltılır.

## Faz durumu
| Faz | Hedef | Durum |
|---|---|---|
| F0 | İskelet, DB, Mailpit, web ve CMS iskeleti, CI, belge iskeletleri | **tamamlandı** (23.11 ~15.40) — F1 için onay bekleniyor |
| F1 | Kimlik, roller, TOTP, sürümleme, yayın, audit, Journey, tohum verisi | **tamamlandı** (23.11 16.45; backend API, UI yok) — F2 için onay bekleniyor |
| F2 | İlk uçtan uca akış (senaryo 1, 2, 3, 4, 5, 10) | başlanmadı |
| F2.5 | Capacitor iskeleti ve ilk debug APK | başlanmadı |
| F3 | Araçlar, kuyruk, SSE, SSRF (6, 7) | başlanmadı |
| F4 | İş akışı motoru, cron ve webhook, fork, sıralama (8, 9, 11) | başlanmadı |
| F5 | Geçmişim, KVKK, raporlar (12, 13) | başlanmadı |
| F6 | Mobil özellikler (14) | başlanmadı |
| F7 | Kalite ve ölçümler (15) | başlanmadı |
| F8 | Teslim belgeleri | başlanmadı |

## Senaryolar (yalnız gerçek test sonucuyla güncellenir)
| # | Durum | Kanıt |
|---|---|---|
| 1 | kısmen: API tamam, UI yok | `ContentWorkflowIT.scenario1_*` |
| 2 | kısmen: API tamam (onay, geri gönderme, v2, fark, geri alma, audit), UI yok | `ContentWorkflowIT.scenario2_*` |
| 4 | kısmen: API tamam (sıralı ve gerçek eşzamanlı), çakışma UI'ı yok | `ContentWorkflowIT.scenario4_*` |
| 3, 5–15 | başlanmadı | — |

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

## Açık işler ve engeller
- [x] `scripts/db-init.ps1` — BOM hatası düzeltildi; kullanıcı çalıştırdı, iki DB de hazır.
- [x] Mailpit çalışıyor (kullanıcı doğruladı, :8025).
- [x] CI: #1 ve #2 backend'de başarısızdı. Sebep: CI'da Mailpit yoktu, mail sağlık kontrolü DOWN dönüyordu, HealthIT 503 alıyordu. Mailpit servis container'ı eklendi; **CI #3 (`243cf1f`) yeşil** (backend ve frontend).
- [ ] Web ve CMS dev sunucusu tarayıcıda açılarak denenmedi; yalnız derlemeleri doğrulandı (F1/F2'de UI ile birlikte doğrulanacak).
- [ ] Kullanıcı: Docker Desktop kurulumu, Firebase projesi, GitHub'da private repo (hepsi isteğe bağlı ya da paralel).
