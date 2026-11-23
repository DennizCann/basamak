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
```powershell
cd backend; .\mvnw.cmd verify
```
```powershell
npm run typecheck; npm run build
```

## Deneme hesapları
*(F1'de tohum verisiyle eklenecek)*

## Zorunlu senaryolar — durum
| # | Senaryo | Durum |
|---|---|---|
| 1–15 | — | Başlanmadı (F0 iskelet tamamlandı) |
