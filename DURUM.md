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
| F0 | İskelet, DB, Mailpit, web ve CMS iskeleti, CI, belge iskeletleri | **devam ediyor** |
| F1 | Kimlik, roller, TOTP, sürümleme, yayın, audit, Journey, tohum verisi | başlanmadı |
| F2 | İlk uçtan uca akış (senaryo 1, 2, 3, 4, 5, 10) | başlanmadı |
| F2.5 | Capacitor iskeleti ve ilk debug APK | başlanmadı |
| F3 | Araçlar, kuyruk, SSE, SSRF (6, 7) | başlanmadı |
| F4 | İş akışı motoru, cron ve webhook, fork, sıralama (8, 9, 11) | başlanmadı |
| F5 | Geçmişim, KVKK, raporlar (12, 13) | başlanmadı |
| F6 | Mobil özellikler (14) | başlanmadı |
| F7 | Kalite ve ölçümler (15) | başlanmadı |
| F8 | Teslim belgeleri | başlanmadı |

## Senaryolar (yalnız gerçek test sonucuyla güncellenir)
1–15: başlanmadı

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

## Açık işler ve engeller
- [ ] `scripts/db-init.ps1` — kullanıcının çalıştırması bekleniyor. BOM hatası düzeltildi.
- [ ] Kullanıcı: Docker Desktop kurulumu, Firebase projesi, GitHub'da private repo (hepsi isteğe bağlı ya da paralel).
