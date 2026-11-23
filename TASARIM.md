# Basamak — Tasarım Belgesi

> Durum: **canlı belge.** Her geliştirme fazında, o fazda gerçekten alınan kararlarla güncellenir.
> Bir bölümün yanında *(taslak)* yazıyorsa karar verilmiş ama henüz kodda doğrulanmamıştır.
> Kodda doğrulanmış kararlar ilgili test sınıfına bağlantı verir.

## İçindekiler
1. Kapsam, varsayımlar ve yapmadıklarımız
2. Mimari (C4 bağlam ve kap)
3. Veri modeli ve sürümleme
4. Araç ve iş akışı çalıştırma motoru
5. Oyunlaştırma ve puan defteri
6. API sözleşmesi
7. Yetki, güvenlik (OWASP eşlemesi) ve KVKK
8. Mobil: çevrimdışı eşitleme, bildirim, derin bağlantı
9. Ölçeklenme, izleme ve hata yönetimi
10. Mimari karar kayıtları (ADR)
11. İleriye bırakılanlar
12. "Sizin kararınız" — karar listesi
13. Yapay zekâ kullanımı

---

## 1. Kapsam, varsayımlar ve yapmadıklarımız

**Bağlam.** Ödevin normal süresi 10 gün, fiilî teslim süresi ise yaklaşık 21 saat (23.11.2026 14.16 → 24.11.2026 12.00). Kapsam bu süreye göre önceliklendirildi:
P0 çalışan uçtan uca akış → P1 zorunlu senaryoların iş kuralları ve testleri → P2 mobil ve kalan senaryolar → P3 kalite ve ölçüm.
Hangi senaryonun hangi durumda olduğu her zaman `README.md` ve `DURUM.md` içinde dürüstçe yazılıdır.

**Varsayımlar**
- **Sıfır ek maliyet.** Ücretli servis kullanılmaz. Yapay zekâ sağlayıcısı varsayılan olarak `mock`; isteğe bağlı olarak yerel Ollama (ücretsiz) seçilebilir. E-posta Mailpit'e, araçlar kendi yazdığımız sahte servise gider.
- **Tek bölge ve tek veritabanı.** İlk yıl ölçeği (50.000 üye, 2.000 eşzamanlı kullanıcı, günde 20.000 çalıştırma) tek bir PostgreSQL birincil sunucusu ve yatay ölçeklenen, durumsuz uygulama düğümleriyle karşılanabilir (bkz. §9).
- Bütün zamanlar veritabanında UTC olarak saklanır. Gün sınırı (seri, günlük kota, haftalık sıralama) ve zamanlanmış yayın **Europe/Istanbul** saatine göre hesaplanır.
- **Journey seviyesi.** Üyenin seviyesi, açık olan ve henüz bitirmediği en düşük sıralı Journey'dir. Hiçbirini bitirmemişse Beginner'dır.

**Yapmadıklarımız** (gerekçe ve alternatifler için bkz. §11)
- iOS derlemesi ve simülatör testi: Windows makinede yapılamaz. Yapılandırma ve talimat hazırdır ama **test edilmemiştir**. APNs, ücretli Apple Developer hesabı gerektirir.
- Bölüm 3.11 (arama, çok dillilik, kurumsal alanlar): PDF'e göre kodda seçmeli; yalnız tasarımı yapıldı.
- Topluluk özelliklerinin (takip, akış, yorum, moderasyon) arayüzleri ertelendi. Zorunlu senaryolarda yer almıyorlar; tasarım kararları §12'de.

## 2. Mimari

**Modüler monolit** yaklaşımı (gerekçesi ADR-1'de): Tek bir Spring Boot dağıtım birimi var. İçindeki modüller paket sınırlarıyla ayrılır ve birbirlerine yalnız servis arayüzleri üzerinden erişir. Arka plan işçileri aynı ikili dosyada çalışır; işçi sayısı ayarla ölçeklenir ve ayrı bir süreç olarak da başlatılabilir.

### C4 — Bağlam
```mermaid
flowchart LR
  member([Üye\nweb + mobil])
  staff([İçerik ekibi\nyazar · editör · yayıncı · yönetici])
  basamak[[Basamak sistemi]]
  ext[(Dış HTTP API'ler\nüye ve ekip araçları)]
  ai[(Yapay zekâ sağlayıcısı\nmock / Ollama)]
  mail[(E-posta\nMailpit, prod'da SMTP)]
  push[(FCM / APNs)]
  member -->|HTTPS| basamak
  staff -->|HTTPS + TOTP| basamak
  basamak --> ext
  basamak --> ai
  basamak --> mail
  basamak --> push
```

### C4 — Kaplar
```mermaid
flowchart TB
  subgraph clients[İstemciler]
    web[Üye web\nReact+TS · :5173]
    cms[CMS\nReact+TS · ayrı uygulama · :5174]
    mob[Mobil\nCapacitor Android/iOS]
  end
  subgraph server[Basamak API · Spring Boot · :8080]
    api[REST API\n/api/** üye · /api/cms/** ekip]
    sse[SSE akışı\n/api/stream]
    workers[İş kuyruğu işçileri\nSKIP LOCKED]
    sched[Zamanlayıcı\nyayın · cron]
  end
  db[(PostgreSQL\niçerik · defter · kuyruk · olaylar)]
  files[(Dosya deposu\nyerel disk, prod'da S3 uyumlu)]
  mock[Sahte dış API\n:8090]
  web & mob --> api
  web & mob --> sse
  cms --> api
  api --> db
  workers --> db
  sched --> db
  workers --> mock
  workers --> files
```

**Modüller:** `auth`, `content` (sürüm ve yayın), `journey`, `learning` (kayıt, ilerleme, quiz, anket), `gamification` (kural, defter, rozet, sıralama), `tool`, `workflow`, `community` (paylaşım ve çatallama), `notification` (SSE, e-posta, push), `report`, `privacy` (dışa aktarma ve silme), `common`.

## 3. Veri modeli ve sürümleme

### 3.1 Varlık–ilişki (F1 itibarıyla kodda olanlar)
```mermaid
erDiagram
  users ||--o{ user_roles : has
  users ||--o{ refresh_sessions : "signs in on"
  users ||--o{ email_tokens : receives
  users ||--o{ content_item : owns
  content_item ||--|{ content_version : "has versions"
  content_item |o--o| content_version : "published_version_id"
  users ||--o{ audit_log : "acts in"
  users { uuid id PK
    string email "unique lower()"
    string password_hash "bcrypt(11)"
    string totp_secret_enc "AES-GCM"
    int failed_login_count
    timestamptz locked_until }
  content_item { uuid id PK
    string type "JOURNEY|TRAINING|TOOL|WORKFLOW_TEMPLATE|POINT_RULE"
    uuid owner_id
    uuid published_version_id
    bigint lock_version "optimistic lock" }
  content_version { uuid id PK
    uuid item_id FK
    int version_no "unique per item"
    string status
    jsonb body "full snapshot"
    int based_on_no
    uuid created_by
    uuid reviewed_by
    uuid published_by
    timestamptz scheduled_at }
  audit_log { bigserial id PK
    string action
    jsonb details
    string request_id }
```

### 3.2 Sürümleme modeli: tam kopya (ADR-3)
- Her kayıt yeni bir `content_version` satırı açar. Gövde, içeriğin **tam JSONB kopyasıdır**. Eski satırlar silinmez ve güncellenmez; yalnız durum alanı ilerler.
- `content_item.published_version_id`, üyeye gösterilecek tek sürümü işaret eder. Yayın ve geri alma yalnız bu işaretçiyi değiştirir, bu yüzden atomiktir.
- **Durum makinesi:**
  `DRAFT → IN_REVIEW → APPROVED → (SCHEDULED →) PUBLISHED → ARCHIVED`.
  `IN_REVIEW → CHANGES_REQUESTED` (gerekçe zorunlu); yazar yeniden kaydedince yeni bir `DRAFT` açılır.
  Gönderilmeden üzerine yeni kayıt yapılan taslaklar `SUPERSEDED` olur ama geçmişte görünmeye devam eder.
- **Geri alma:** `ARCHIVED` (bir kez yayınlanmış) bir sürüm, yeni bir onay gerekmeden yeniden yayınlanabilir; o kopya zaten onaylanmıştı. Bu işlem `content.republished` olarak audit'e düşer.
- **Fark görünümü:** İki kopya arasında yapısal JSON farkı alınır (`JsonDiff`). Çıktı `path`, `op` (added / removed / changed) ve önceki/sonraki değerden oluşur.
- **Zamanlanmış yayın:** Saat CMS'te Europe/Istanbul olarak girilir ve UTC `Instant` olarak saklanır. `ScheduledPublisher` 15 saniyede bir `scheduled_at <= now` olan sürümleri `FOR UPDATE SKIP LOCKED` ile alır. Birden çok düğüm aynı sürümü iki kez yayınlayamaz. Sunucu kapalıyken kaçırılan yayınlar ilk çalışmada yetişir.
- **Yayınlanmamış içerik sızıntısı:** Üye tarafı içeriğe yalnız `PublishedContent` servisinden erişir. Bu servis yalnız `published_version_id` işaretçisi üzerinden sorgu yapar; taslak okuyan bir üye API'si yoktur. `ContentWorkflowIT.scenario1` bunu liste ve id üzerinden doğrular (taslak için 404).

### 3.3 İç içe içerik ve sürüm sabitleme
- **Eğitim bir aggregate'tir.** Ders, quiz, anket ve soru bankası, eğitim sürümünün içinde kararlı `itemKey` anahtarlarıyla tutulur. Böylece eğitimin bir sürümü, içerdiği her şeyin tutarlı bir kopyasıdır. Quiz'i ayrı yayınlayıp eğitimi kendiliğinden değiştirmek yoktur; quiz değişirse eğitimin yeni sürümü yayınlanır.
- **Araçlar** kendi sürümleri olan bağımsız içeriklerdir. Eğitim bir araca `(toolId, versionNo)` ile **sabitlenmiş** referans verir. Aracın yeni sürümü eğitimi sessizce değiştirmez; editör eğitimin yeni sürümünde sabitlemeyi yükseltir.
- **Journey** eğitimlere item id ile bağlanır (sabitlenmemiş). Journey her zaman eğitimin son yayınlanan sürümünü gösterir, çünkü Journey "hangi eğitimler" sorusunun cevabıdır, içeriğin kendisi değildir.

### 3.4 Eşzamanlı düzenleme (senaryo 4)
- Editör içeriği açtığında `lockVersion` değerini alır ve kaydederken bu değeri geri gönderir. Değer güncel değilse **409 `edit_conflict`** döner; cevapta son sürüm numarası ve onu kaydeden kişi yer alır. CMS çakışma ekranı bu bilgiyle "güncel sürümü yükle ve değişikliğini yeniden uygula" seçeneğini sunar.
- Kontrol ile yazma arasındaki yarış durumunu JPA `@Version` kapatır (`UPDATE … WHERE lock_version = ?`). Ayrıca `(item_id, version_no)` UNIQUE kısıtı ikinci bir güvencedir.
- **Kanıt:** `ContentWorkflowIT.scenario4_trulyConcurrentSavesExactlyOneWins`, iki iş parçacığını aynı anda kaydettirir. Sonuç tam olarak bir 200 ve bir 409; geçmişte yalnız bir yeni sürüm oluşuyor.
- **Öge anahtarlarının sabitliği:** Eğitim ögesinin `key` alanı; ilerleme, defterdeki tek seferlik puan anahtarı (`eğitim:anahtar`) ve quiz denemeleri için kimlik görevi görür. Yayınlanmış bir anahtarın değişmesi iki zarar doğurur: ilerleme yeni sürüme taşınmaz ve öge yeniden tamamlanınca **tekrar puan** verilir.
  - **Kural:** Daha önce yayınlanmış bir anahtar sıradan bir kayıtla kaybolamaz (yeniden adlandırma ya da silme). Sunucu 409 `published_keys_removed` döndürür.
  - **Bilerek kaldırma:** Ancak açık onayla (`confirmRemovedKeys`) yapılır.
  - **CMS'te:** Yayınlanmış anahtar alanı salt okunurdur; yayınlanmış bir ögeyi silmek onay ister.
  - **Kanıt:** `JourneyPolicyIT.publishedItemKeysCannotSilentlyDisappear`.
- **Neden kilitleme yok, gerçek zamanlı ortak düzenleme yok:** Düşük çakışma oranında (20 kişilik ekip) iyimser kilit basittir ve veri kaybını önler. Ortak düzenleme (CRDT) bu kapsam için fazla karmaşık; §11'de ertelenenler arasında.

## 4. Araç ve iş akışı çalıştırma motoru

### 4.1 Araç modeli
- **CMS aracı:** Sürümlü bir içeriktir (`TOOL`) ve taslak → inceleme → onay → yayın akışından geçer. Gövdesi `ToolDefinition`'dır: girdiler, yöntem, URL, başlıklar, JSON gövde şablonu, zaman aşımı (1–30 sn), yeniden deneme (0–3) ve sonuç alanı. Eğitim aracı `(toolId, versionNo)` ile **sabitler**. Yayın sırasında sabitlenen sürümün yayınlanmış olması zorunludur.
- **Üye aracı:** `user_tool` tablosunda tutulur. Her kayıt yeni bir sürüm satırı açar (`user_tool_version`), çalıştırma o sürüme bağlanır. Aynı üyenin aynı tanımı ikinci kez kaydetmesi reddedilir (`def_hash`); boş ya da kopya araçla puan toplanamaz.
- **Sırlar:** `tool_secret` tablosunda AES-256-GCM ile şifreli tutulur. Sürüm kopyasına, farka, denetim kaydına ve API cevaplarına girmez; yalnız adı ve son güncelleme zamanı döner. Değer, işçi isteği gönderirken çözülür. Önizlemede ve hata mesajlarında `••••` görünür.
- **Şablonlar enjeksiyona kapalıdır:**
  - Değerler yerleştirilir, hiçbir zaman değerlendirilmez.
  - URL'de yüzde kodlanır ve yalnız yol ile sorguda kullanılabilir; sunucu adı kaydedilirken sabittir.
  - Başlıklarda CR ve LF silinir.
  - JSON gövde metin birleştirmeyle değil Jackson ağacıyla üretilir.
  - Sırlar gövdede kullanılamaz.

### 4.2 Çalıştırma (sıra şeması)
```mermaid
sequenceDiagram
  participant U as Üye (web/mobil)
  participant API
  participant DB as PostgreSQL (job, tool_run, tool_run_event)
  participant W as İşçi (JobWorker, N iş parçacığı)
  participant X as Dış API
  U->>API: POST /api/tool-runs (girdi, clientRequestId)
  API->>DB: tool_run QUEUED + job (aynı işlem, idempotency_key)
  API-->>U: run (QUEUED)
  U->>API: GET /api/tool-runs/{id}/events (SSE, Last-Event-ID)
  W->>DB: claim … FOR UPDATE SKIP LOCKED (kira 90 sn)
  W->>DB: RUNNING + olay
  W->>X: istek (UrlGuard'dan geçen IP'ye, zaman aşımı)
  X-->>W: 503
  W->>DB: RETRYING + olay, job run_after = +1 sn / +2 sn / +4 sn
  W->>X: yeniden deneme
  X-->>W: 200
  W->>DB: SUCCEEDED + çıktı + olay, puan defteri, eğitim ögesi tamam
  API-->>U: olaylar canlı (kopan bağlantı kaçırdıklarını tablodan alır)
```
- **Yeniden denenir:** Zaman aşımı, bağlantı hatası, 408, 425, 429 ve 5xx. **Denenmez:** Diğer 4xx, engellenen adres, eksik sır ve 1 MB'ı aşan cevap.
- **İptal:** Kuyrukta bekleyen iş hemen kuyruktan alınır. Çalışan iş, canlı HTTP isteği iptal edilerek durdurulur; işçi CANCELLED yazar.
- **Sunucu yeniden başlarsa:** Kirası dolan işi başka bir işçi alır. Deneme hakkı bitmişse sonuç FAILED yazılır; iş sessizce kaybolmaz.
- **Aynı iş iki kez çalışmaz:**
  - Kuyrukta `idempotency_key` UNIQUE kısıtı vardır.
  - Çift tıklamada aynı `clientRequestId` aynı çalıştırmayı döndürür.
  - Bir işi aynı anda iki işçi alamaz (`SKIP LOCKED`, `ToolIT.queue_*`).
- **Dış API yavaşlarsa ya da çökerse:** En fazla `JOB_WORKERS` iş parçacığı bağlanır ve API'nin istek iş parçacıkları etkilenmez. Üye başına en çok 5 eşzamanlı çalıştırma vardır. Her denemenin bir süre sınırı vardır. Devre kesici (art arda hata veren hedefi bir süre atlamak) **yapılmadı**; §11'de.
- **Çıktı:** 1 MB sınırı vardır. JSON değilse en fazla 10 KB metin saklanır. Saklama süresi önerisi 90 gündür (KVKK, F5); temizleme işi henüz yok.

### 4.3 SSRF koruması (senaryo 7) — `UrlGuard`
1. Yalnız http ve https kabul edilir; kullanıcı bilgisi içeren URL reddedilir. Sunucu adı şablonla oluşturulamaz.
2. `localhost`, `*.local`, `*.internal` ve metadata adları DNS'e sorulmadan reddedilir.
3. Ad çözülür ve dönen **her adres** denetlenir: loopback, 0/8, 10/8, 172.16/12, 192.168/16, 100.64/10, 169.254/16 (metadata dahil), 198.18/15, multicast ve ayrılmış aralıklar, IPv6 loopback, ULA ve link-local. IPv6 içine gömülü IPv4 de denetlenir (`::ffff:`, NAT64, 6to4). `2130706433`, `127.1` ve `::ffff:127.0.0.1` gibi yazımlar aynı adrese çözüldüğü için aynı kontrolde yakalanır.
4. **DNS rebinding:** HTTP istemcisinin `DnsResolver`'ı aynı denetimi yapar, bağlantı yalnız denetimden geçen adreslere açılır. Kaydederken herkese açık olan ama sonra iç ağı gösteren bir ad, çalıştırma anında reddedilir (test edildi).
5. **Yönlendirme:** Otomatik takip kapalıdır. En fazla 3 yönlendirme elle izlenir ve her adım 1–4'ten yeniden geçer. Başka bir sunucuya yönlendirmede sırlı başlıklar gönderilmez.
6. Proxy, çerez ve otomatik yeniden deneme kapalıdır.
7. **İstisna:** `TOOLS_TRUSTED_HOSTS` (varsayılan `localhost:8090`, yani sahte API) **yalnız CMS araçları** için ve **yalnız bu host:port** için açıktır. Üye araçları hiçbir koşulda iç ağa gidemez. Prod'da bu liste boş bırakılır.

### 4.4 Sahte dış API (`mock-api/MockApi.java`)
Yalnız JDK ile çalışan tek bir dosyadır (`java mock-api/MockApi.java`, :8090). Sağladıkları:
- `/weather`: `X-Api-Key` başlığı ister.
- `/slow?ms=`: Zaman aşımını göstermek için gecikmeli cevap verir.
- `/flaky?key=&fail=`: İlk N çağrıda 503 döner, sonra başarılı olur (yeniden denemeyi gösterir).
- `/status/{code}`: İstenen HTTP kodunu döner.
- `/redirect?to=`: Yönlendirme engelini gösterir.
- `/echo`: Gönderilen gövdeyi geri döner.

Ücretli hiçbir servise gidilmez.

### 4.5 İş akışları (senaryo 8) — kendi motorumuz
- **Neden kendi motorumuz:** Temporal ya da Airflow ayrı bir altyapı ister; sıfır maliyet ve tek gün kısıtına uymaz. Akışlar kısa ve doğrusaldır (en fazla 20 adım), bu yüzden ADR-2'deki kuyrukta tek bir iş (`WORKFLOW_RUN`) olarak çalışır. İşçi her adımda kirasını uzatır (`heartbeat`).
- **Model:** Adımlar sırayla çalışır. Türler: `tool` (platform aracı sürümüyle ya da üye aracı kayıt anındaki sürümüyle **sabitlenir**), `ai`, `condition` ve `workflow` (alt akış). Koşul adımı yalnız kendisinden *sonraki* adımlara dallanır; seçilmeyen dal SKIPPED olur.
- **Eşleme:** Bir adım yalnız `{{input.x}}` ile girdiyi ve `{{steps.önceki.output.alan}}` ile *önceki* adımların çıktısını okuyabilir. Tek bir yer tutucudan oluşan değer JSON türünü korur. Bu kural, bir akışın içinde döngü kurulmasını yapısal olarak imkânsız kılar.
- **Dolaylı döngü:** Alt akışlar çağrıldıkları anda alt akışın *güncel* sürümünü kullanır. Kayıt sırasında bütün alt akış grafiği DFS ile taranır (A → B → C → A ve kendini çağırma reddedilir; hata mesajı döngünün yolunu adlarıyla gösterir). Çalışma anında ayrıca 5 seviyelik derinlik sınırı vardır.
- **Adım başına kayıt:** Her çalıştırma `workflow_step_run` tablosuna yazılır: girdi, çıktı, süre, token, maliyet, deneme sayısı ve alt akışın çalıştırma kimliği.
- **Hatalı adımdan devam:** Devam edildiğinde aynı çalıştırma yeniden kuyruğa girer. Başarılı adımların son çıktıları kullanılır ve hatalı adımdan itibaren çalışılır. Geçmiş korunur: hatalı satır kalır, üzerine yeni satır eklenir. Sunucu çökerse yarıda kalan adım FAILED olarak kapatılır ve kira dolunca çalıştırma kaldığı yerden sürer.
- **Çalışma sırasında aracın yeni sürümü yayınlanırsa:** Akış sabitlediği sürümü kullanmaya devam eder. Alt akışın yeni sürümü ise bir sonraki çağrıdan itibaren kullanılır. Hangi sürümün çalıştığı çalıştırma kaydında saklanır.
- **Yapay zekâ:** `AiProvider` arayüzünün tek gerçeklemesi `MockAiProvider`'dır. Ücretsizdir ve deterministiktir. Token sayısı tahminidir (yaklaşık 4 karakter = 1 token), maliyet uydurma bir fiyat listesiyle hesaplanır ve arayüzde "benzetim" olarak etiketlenir. `[fail]` işareti bilerek hata üretir (devam etme senaryosunu göstermek için). Ollama gibi gerçek bir sağlayıcı **yazılmadı**.
- **İstem enjeksiyonu:** Yapay zekâ çıktısı yalnız veridir. Sonraki adımın metnine ve koşula girebilir, ama araç, URL, sır ya da adım seçemez. Sırlar istemlere hiç girmez; URL'nin sunucu adı sabittir ve UrlGuard'dan geçer. En kötü durumda üretilen metin yanıltıcı olur.
- **Büyük çıktı:** Araç cevabı 1 MB ile sınırlıdır (§4.2).

### 4.6 Tetikleyiciler (senaryo 9)
- **Cron:** 5 alanlı, Europe/Istanbul saatiyle ve en sık dakikada bir. Zamanlayıcı 15 saniyede bir, vadesi gelen akışları `FOR UPDATE SKIP LOCKED` ile kilitler. İş anahtarı `cron:{akış}:{planlanan zaman}` olduğu için aynı tik iki kez ateşlense bile tek çalıştırma olur (test edildi).
- **Webhook:** `POST /api/hooks/workflows/{token}`. Token rastgeledir, yalnız bir kez gösterilir ve SHA-256 özeti saklanır. `Idempotency-Key` başlığı (yoksa gövdenin özeti) iş anahtarına girer: aynı teslimat 200 ve aynı çalıştırma kimliğiyle cevaplanır, ilk teslimat 202 alır.

### 4.7 Paylaşım, çatallama ve sıralama (senaryo 11)
- **Paylaşılan akışın anahtarı:** Paylaşılan (PUBLIC) bir akış yalnız platform araçlarını kullanabilir (anahtar platformundur, kota platformdan düşer). Üyenin kendi aracını kullanan akış paylaşılamaz. Paylaşılan bir akışın alt akışları da paylaşılmış olmalıdır.
- **Çatallama:** Güncel tanım üyenin listesine kopyalanır (`forked_from`) ve `fork_count` artar. Yazar, her çatal için bir kez `SHARED_ITEM_FORKED` puanı kazanır. Kendi akışını çatallamak reddedilir. Başkası paylaşılan akışı alt akış olarak kullanırsa yazar günde bir kez `SHARED_ITEM_USED` puanı alır.
- **Özgün sahip akışını silerse:** Çatallar bağımsız kopya olduğu için çalışmaya devam eder. Silinen akışı alt akış olarak kullanan akış, çalıştırma anında açık bir hata mesajıyla durur.
- **Sıralama:**
  - Haftalık sıralama, defterden pazartesi 00.00'dan (İstanbul) bu yana yazılan puanlarla hesaplanır (`ix_point_ledger_created`). Genel sıralama `user_score`'dan okunur.
  - Canlı güncelleme için puan değişikliği commit edilince bir bayrak kalkar ve saniyede en fazla bir kez açık SSE akışlarına "changed" gönderilir. İstemci listeyi yeniden çeker.
  - Ölçek önerisi (§9): haftalık toplam için saatlik bir özet tablo ya da Redis sorted set.

## 5. Oyunlaştırma ve puan defteri

### 5.1 Puan tablosu (tohum verisi; CMS'ten değiştirilebilir)
| Aksiyon | Nesne | Puan | Tekrar politikası | Gerekçe |
|---|---|---:|---|---|
| `LESSON_VIEWED` | Ders | 1 | nesne başına bir kez | Görüntüleme en zayıf sinyal; tekrar açmak puan şişirmemeli |
| `TRAINING_STARTED` | Eğitim | 2 | bir kez | Başlamayı teşvik eder ama tek başına değersizdir |
| `LESSON_COMPLETED` | Ders | 5 | bir kez | Gerçek ilerleme |
| `SURVEY_SUBMITTED` | Anket | 10 | bir kez | Geri bildirim içerik ekibi için değerli |
| `QUIZ_PASSED` | Quiz | 20 | bir kez | Ölçülmüş öğrenme |
| `TRAINING_COMPLETED` | Eğitim | 50 | bir kez | Ana hedef |
| `JOURNEY_COMPLETED` | Journey | 200 | bir kez | Seviye atlama |
| `TOOL_CREATED` / `WORKFLOW_CREATED` | Araç / iş akışı | 30 | bir kez | Üretim; boş ya da kopya kayıt ayrıca engellenir (F3/F4) |
| `TOOL_RUN_SUCCEEDED` / `WORKFLOW_RUN_SUCCEEDED` | Araç / iş akışı | 2 / 5 | **günde bir kez** | Döngüde çalıştırma puan şişirmesin |
| `ITEM_SHARED` | * | 15 | bir kez | Paylaşım |
| `SHARED_ITEM_USED` | * (yazara) | 3 | günde bir kez | Başkasının kullanması değer üretir |
| `SHARED_ITEM_FORKED` | * (yazara) | 10 | nesne başına bir kez | Çatallanma ödülü; kendi kendini çatallama sayılmaz (F4) |

Görüntülemek, başlamak, tamamlamak, oluşturmak ve paylaşmak farklı puan getirir; aynı aksiyon farklı nesnede farklı puan getirebilir. Kural, `(action, objectType)` çiftiyle eşleşir; tam eşleşme `*`'a göre önceliklidir.

### 5.2 Kural motoru ve defter
```mermaid
sequenceDiagram
  participant API as LearningService (aynı işlem)
  participant G as GamificationService
  participant DB as PostgreSQL
  API->>G: record(event, eventKey)
  G->>DB: INSERT activity_event ON CONFLICT(event_key) DO NOTHING
  alt aynı olay daha önce geldi
    DB-->>G: 0 satır → puan yok
  else yeni olay
    G->>DB: yayındaki POINT_RULE (action, objectType)
    G->>DB: INSERT point_ledger(idempotency_key=f(policy)) ON CONFLICT DO NOTHING
    G->>DB: UPSERT user_score += puan
  end
```
- **Kural kodda değil, içerikte:** `POINT_RULE` sürümlü bir CMS içeriğidir. Yeni bir kuralın ya da puan değişikliğinin yayınlanması kod değişikliği ve deploy gerektirmez. Her defter satırı `rule_item_id` ve `rule_version_no` taşır.
- **Defter değiştirilemez:** `point_ledger` için UPDATE ve DELETE bir trigger ile yasaktır. Düzeltme, `reverses_id` alanı dolu negatif bir satırla yapılır ve tekrar işlemeye dayanıklıdır (`reverse|<id>`).
- **Tek seferlik puan, veritabanı kısıtıyla:** Politika `idempotency_key`'e kodlanır: `ONCE_PER_OBJECT` = kullanıcı|aksiyon|nesne; `ONCE_PER_DAY` = … ve İstanbul günü; `EVERY` = olay anahtarı. Bu kontrol uygulama kodunda değil, `UNIQUE` kısıtındadır; bu yüzden eşzamanlı isteklerde de doğrudur.
- **Kanıtlar (senaryo 10):**
  - `GamificationIT.openingTheSameLessonHundredTimesGivesPointsOnce`: 100 olay kaydedilir, 1 defter satırı oluşur.
  - `sameEventTwiceIsRecordedOnceEvenConcurrently`: 4 iş parçacığı aynı olayı gönderir, olay 1 kez kaydedilir. HTTP `Idempotency-Key` ile de doğrulanır.
  - `differentActionsGiveDifferentPoints`.
  - `ruleChangedInCmsAppliesWithoutCodeChangeAndLedgerKeepsRuleVersion`: eski satırlar 5 puan, yeni satır 8 puan ve kural s2.
  - `ledgerIsAppendOnlyAndCorrectionsAreReversingEntries`.

### 5.3 Kararlar
- **Kural değişince geçmiş puanlar yeniden hesaplanmaz.** Üye o gün geçerli kurala göre kazanmıştır; geriye dönük değişiklik güveni bozar. Toplu düzeltme gerekirse ters kayıtla yapılır.
- **Silinen araç ya da geri alınan yayın:** Yayının geri alınması kazanılmış puanı geri almaz; üye o sürümü dürüstçe tamamlamıştır. Kötüye kullanımla silinen araçta (spam) moderatör ters kayıt yazar.
- **Kötüye kullanım:**
  - Aynı dersi tekrar açmak: politika gereği tek puan.
  - Açıp hemen kapatmak: görüntüleme 1 puan, asıl puan tamamlamada; videolu derste izleme süresi sunucuda duvar saatiyle sınırlanır.
  - Döngüde çalıştırma: günlük tek puan.
  - Boş, kopya ya da karşılıklı çatallama: F4'te.
- **Çevrimdışı aksiyon ve cihaz saati:** İstemcinin `occurredAt` değeri yalnız gösterim içindir. Günlük politika ve sıralama **sunucunun kabul anına** (`received_at`, İstanbul günü) göre hesaplanır; cihaz saatini ileri almak işe yaramaz.
- **Sıralama tablosu (50.000 üye):** `user_score.total` aynı işlemde güncellenir ve indekslidir. Genel sıralama `ORDER BY total DESC LIMIT N` sorgusudur. Haftalık sıralama `point_ledger(created_at)` indeksiyle hesaplanır (F4'te ayrıntı ve ölçüm).

### 5.4 Journey ve seviye kuralları
- **Journey'in tamamlanması:** Journey, içindeki **o anki** bütün yayınlanmış eğitimler bitince tamamlanır. O anda sertifika verilir ve `JOURNEY_COMPLETED` kaydedilir.
- **Açılma koşulları:** `requiresPreviousJourney` (sırada bir önceki Journey bitmiş olmalı) ve `minPoints`. Kilitli bir Journey'in eğitimine başlanamaz (`training_locked`, 403).
- **Seviye** = tamamlanmamış ilk Journey. **Seviye düşmez:** sertifikası olan Journey tamamlanmış sayılır. Sonradan eklenen bir eğitim "yeni" olarak görünür ama seviyeyi geri almaz.
- **Boş Journey (QA bulgusu, P1):** Hiç yayınlanmış eğitimi olmayan bir Journey hiçbir zaman tamamlanamaz, bu yüzden ondan sonraki Journey'leri sonsuza kadar kilitler.
  - **Önlem:** Sunucu böyle bir Journey'in **yayınlanmasını reddeder** (`empty_journey`). CMS'teki düzenleyici bunu yayından önce uyarı olarak gösterir.
  - **Eskiden yayınlanmış ya da son eğitimi yayından çekilmiş Journey:** Üyeye **"Yakında"** olarak gösterilir. Kendiliğinden tamamlanmaz; puan ya da sertifika vermez. Sonraki Journey hangi Journey'i beklediğini adıyla söyler.
  - **Neden "atla" seçilmedi:** Boş Journey'i atlamak, ona otomatik tamamlanma vermek anlamına gelir ve sertifika ile seviye kuralını belirsizleştirir.
  - **Kanıt:** `JourneyPolicyIT.emptyJourneyCannotBePublished`, `legacyEmptyJourneyIsComingSoonAndNextOneSaysWhatItWaitsFor`.
- **Doğrudan bağlantı:** Kayıt olmamış üyeye eğitimin yalnız **ana hatları** (başlık ve tür) döner. Ders metni ve anket soruları kayıt gerektirir, kayıt da Journey kilidi ve ön koşullarla korunur (`notEnrolledMemberGetsOnlyTheOutline`).
- **Yayından çekilen Journey:** Kazanılmış sertifika ve puanlar geçerli kalır; sertifika, Journey'in o anki sürüm numarasını taşır.
- **Puan ve seviye ilişkisi:** Seviye eğitim tamamlamaya bağlıdır, puana değil. Puan yalnız `minPoints` açılma koşulunda kullanılır, böylece puan biriktirerek eğitim atlanamaz.

### 5.5 Eğitim ilerlemesi ve sürüm kuralı (senaryo 3)
- **Sürüm sabitleme:** Kayıt (`enrollment`) başladığı sürüme sabitlenir (`training_version_no`). Yeni yayın, devam eden üyenin gördüğünü **değiştirmez**. Üye "yeni sürüm var" uyarısı görür ve isterse kendi sürümünde bitirir.
- **"Yeni sürüme geç":** Bu seçenek ilerlemeyi kararlı `itemKey` anahtarlarıyla taşır. Yeni sürümde olmayan ögelerin ilerlemesi düşer; yeni ögeler tamamlanmamış görünür. Yeni kayıtlar her zaman son yayından başlar.
- **Gerekçe:** Quiz'in ortasındaki bir üyenin sorularının değişmesi adil değildir ve veri bütünlüğünü bozar. Zorunlu geçiş yerine isteğe bağlı geçiş seçildi.
- **Geri alınan yayın:** Arada verilen cevaplar, sabitlenmiş sürüme bağlı oldukları için geçerli kalır.
- **Kanıt:** `LearningIT.scenario3_newVersionWhileMemberIsMidTraining`.

### 5.6 Quiz ve anket kararları
- **Soru seçimi:** Soru bankasından `SecureRandom` ile rastgele seçim yapılır ve şıklar karıştırılır. Seçilen sorular denemeye kaydedilir, böylece sayfa yenilenince aynı sorular gelir. **Doğru cevaplar asla istemciye gönderilmez**; testler bunu doğrular.
- **Süre:** Süre sunucuda tutulur (`deadline_at`). Bağlantı koparsa açık deneme süre içinde kaldığı yerden devam eder. Süre dolduktan sonra **60 saniyelik ağ toleransı** tanınır; daha geç gelen gönderim "geç" sayılır ve geçemez. Mobil çevrimdışı quiz için F6'da ayrı kural yazılacak.
- **Doğru cevapların gösterilmesi:** "Üye doğru cevapları görsün" ayarı açıksa doğru cevaplar **yalnız quiz geçildiğinde ya da deneme hakkı bittiğinde** gösterilir. Başarısız olup hakkı kalan üyeye gösterilmez; aksi halde bir sonraki denemede kullanılabilirdi.
- **Deneme incelemesi:** Üye kendi gönderdiği denemeleri inceleyebilir: sorular **gösterildiği hâliyle** (deneme kaydındaki kopya), verdiği cevaplar ve sonuç. Doğru cevaplar aynı kurala bağlıdır. Değerlendirme, denemenin sabitlendiği eğitim sürümünden yapılır; güncel soru bankası kullanılmaz. İnceleme deneme hakkını, puanı ya da ilerlemeyi değiştirmez (`quizReviewShowsOwnAttemptsAndRevealsAnswersOnlyWhenAllowed`).
- **Açık uçlu sorular** otomatik puana katılmaz ve "değerlendirme bekliyor" olarak işaretlenir. İnsan değerlendirmesi ve itiraz akışı ertelendi (§11). Seçmeli madde olan yapay zekâ ön değerlendirmesi yapılmadı.
- **Anonim anket:** Cevap satırında `user_id` yoktur, zaman damgası yerine yalnız tarih tutulur. Tekrar doldurmayı önleyen `survey_submission` kaydı cevapla ilişkilendirilmez, böylece yönetici bile cevabı kişiyle eşleştiremez. Raporlarda az sayıda cevapta dağılım gizlenecek (F5).

### 5.7 Sertifika
- **Belge:** PDF'te QR kod ve doğrulama bağlantısı (`/verify/{serial}`) yer alır. Doğrulama sayfası girişsiz açılır.
- **Sahteciliğe karşı:**
  - Seri numarası 10 karakterlik rastgele bir değerdir.
  - Satır, (seri, üye, Journey, sürüm, tarih) üzerine sunucu anahtarlı **HMAC-SHA256** imzası taşır. Doğrulama imzayı yeniden hesaplar; değiştirilmiş bir satır geçersiz görünür.
  - Geri alma için `revoked_at` alanı vardır.

## 6. API sözleşmesi
OpenAPI dosyası springdoc ile üretilir ve `docs/openapi.yaml` olarak depoya eklenir.

## 7. Yetki, güvenlik ve KVKK

### 7.1 Oturum ve jeton yapısı
| İstemci | Access token | Refresh token |
|---|---|---|
| Web | JWT HS512, 15 dakika, bellekte tutulur, `aud=web` | Opak rastgele değer. HttpOnly, `SameSite=Strict`, `path=/api` cookie (`bsm_rt`). Her kullanımda döndürülür |
| Mobil | JWT, `aud=mobile` | Gövdede döner. Android Keystore ve iOS Keychain'de saklanır |
| CMS | JWT, `aud=cms`. **Yalnız şifre ve TOTP'den sonra** verilir | Ayrı cookie (`bsm_cms_rt`), 12 saat |

- Refresh token'lar veritabanında yalnız SHA-256 özeti olarak tutulur (`refresh_sessions`). Her oturum bir cihazdır; üye açık oturumlarını görür ve kapatır.
- JWT'deki `sid` her istekte kontrol edilir. Bu yüzden kapatılan bir oturumun access token'ı da **hemen** geçersiz olur (bkz. `AuthIT.revokedSessionInvalidatesAccessTokenImmediately`). Bedeli istek başına bir birincil anahtar okumasıdır; ölçekte bu kontrol önbelleğe alınabilir.
- **Eşzamanlı refresh:** Token döndürme, oturum satırı kilitlenerek (`SELECT … FOR UPDATE`) yapılır. Aynı token'la gelen iki eşzamanlı istekten yalnız biri döndürür, diğeri 401 alır. Tarayıcıda kalan cookie her zaman geçerli kalır (`AuthIT.concurrentRefreshWithSameTokenRotatesOnlyOnce`). Bu bir QA bulgusuydu: StrictMode altında CMS oturumu sayfa yenilemede kayboluyordu.
- **CSRF:** API Bearer token ile çalıştığı için CSRF'ye açık değildir. Cookie kullanan tek uç nokta refresh'tir ve `X-Requested-With: basamak` başlığı zorunludur. Siteler arası bir form bu başlığı ekleyemez.

### 7.1a İkinci adım: üyede e-posta kodu, CMS'te TOTP
| | Üye girişi: e-posta kodu | CMS girişi: TOTP (zorunlu) |
|---|---|---|
| Neye bağlı | E-posta hesabına erişime | Kayıtlı cihazdaki gizli anahtara |
| Kullanım kolaylığı | Ek kurulum yok | Bir kerelik uygulama kurulumu (QR) |
| Ağ ve teslim | E-posta teslimine bağlı (gecikme, spam) | Çevrimdışı çalışır |
| Saldırı yüzeyi | E-posta hesabı ele geçirilirse ikinci adım da düşer. Kod oltalama (phishing) ile çalınabilir, ama 10 dakika ve tek kullanımla sınırlı | Cihaz ya da anahtar çalınmalı. Oltalamayla anlık kod çalınabilir, ama 30 saniyelik pencereyle sınırlı |
| Neden bu seçim | Üye için güvenlik ve kullanım dengesi | İçerik yayını ve yetki yüksek riskli; PDF TOTP'yi zorunlu tutuyor |

**E-posta kodunun korumaları (`EmailCodeService`):**
- 6 hane `SecureRandom` ile üretilir, **10 dakika** geçerlidir ve **tek kullanımlıktır**.
- Kod açık metin olarak saklanmaz ve loglanmaz; yalnız `HMAC-SHA256(sunucu sırrı, challengeId:kod)` tutulur. Kod e-postanın konu satırında değil, yalnız gövdesinde yer alır; konu satırı kilit ekranı bildirimlerinde görünebilir.
- **5 yanlış deneme** girişi kapatır. Yanlış kodlar ayrıca hesap kilidine sayılır (5 hatada 15 dakika).
- **Yeniden gönderme** 60 saniye bekleme ister ve giriş başına en fazla 5 gönderimle sınırlıdır. Yeni kod öncekini geçersiz kılar.
- `/api/auth/**` uç noktalarında IP başına hız sınırı vardır. Kod doğrulanmadan oturum açılmaz ve token verilmez.
- Gönderim başarısız olursa kullanıcıya açık mesaj gösterilir ve yeniden gönderme seçeneği sunulur.

**Cihazı hatırlama (yalnız üye):**
- **Neden:** Her girişte kod istemek gereksiz bir yük. Ödev üye için ikinci adım istemiyor; e-posta doğrulaması ve şifre yeterli. E-posta kodu bu yüzden yalnız **yeni bir cihazda** istenir.
- **Nasıl çalışır:** Kod doğrulanırken "Bu cihazı 30 gün hatırla" seçiliyse cihaza rastgele bir token verilir. Web'de bu, `bsm_dev` adlı HttpOnly, `SameSite=Strict` ve `path=/api/auth` olan bir cookie'dir; mobilde güvenli depoda tutulur. Sunucu yalnız token'ın SHA-256 özetini saklar (`trusted_device`).
- **Sınırlar:** Token yalnız sahibinin hesabında geçerlidir. **Şifre her zaman istenir.** Şifre sıfırlama hatırlanan bütün cihazları iptal eder.
- **Kanıt:** `LoginCodeIT.remembered*`, `passwordResetForgetsRememberedDevices`, `mobileRememberedDeviceTokenTravelsInTheBody`, e2e `remembered device`.

CMS bu kapsamın dışında: içerik ekibi her yeni oturumda TOTP kodu girer. CMS oturumu 12 saat sürer.

**TOTP geçişi:** CMS'teki TOTP kayıtları değişmedi. Bu adımda veri geçişi yok; kayıtlı hesaplar aynı uygulamayla girmeye devam eder.

**Kanıt:** `LoginCodeIT` (kodun yalnız özet olarak saklanması ve tek kullanım, yanlış kod sınırı, süre dolması ve yeniden gönderme, bekleme süresi, gönderim hatası, mobil), `AuthIT`, e2e `member.spec.ts`.

### 7.2 Yetki modeli: rol tabanlı ve nesne düzeyinde
- **Roller:** `MEMBER`, `AUTHOR`, `EDITOR`, `PUBLISHER`, `ADMIN`.
- **Kaba kapı (`SecurityConfig`):** `/api/cms/**` yalnız `aud=cms` token'ı kabul eder; `/api/**` yalnız üye token'ı kabul eder. Bir üye token'ı CMS'e, bir CMS token'ı üye API'sine giremez.
- **İnce kurallar (servis katmanında):**
  - Yazar yalnız **kendi** içeriğini düzenler; editör ve yönetici herkesinkini düzenleyebilir.
  - Editör kendi sürümünü onaylayamaz (dört göz ilkesi; yönetici istisna).
  - Yalnız yayıncı ve yönetici yayınlar, zamanlar ve geri alır.
  - Yayıncı onaylanmamış bir sürümü yayınlayamaz.
- Ön yüzde düğme gizlemek yalnız kullanılabilirlik içindir; bütün kurallar sunucuda testle doğrulanır (`ContentWorkflowIT.rolesAreEnforcedOnTheServer`).

### 7.3 Kaba kuvvet koruması
- 5 hatalı deneme (şifre ya da TOTP) hesabı **15 dakika** kilitler (423). Kilitliyken doğru şifre de reddedilir. Şifre sıfırlama kilidi açar.
- Giriş, kayıt ve sıfırlama uç noktalarında IP ve uç nokta başına dakikada 20 istek sınırı vardır (429 ve `Retry-After`).
- Var olmayan bir e-postada da bcrypt çalıştırılır (zamanlama eşitleme). Kayıt dışındaki uç noktalar hesabın var olup olmadığını belli etmez.
- **Şifre politikası (ASVS 2.1):** 10–128 karakter; bilinen zayıf şifreler ve e-postayla aynı şifre reddedilir.
- **CMS'te TOTP zorunlu:** İlk girişte doğrulayıcı uygulama kaydı yapılır (otpauth URI ve QR). TOTP sırrı AES-256-GCM ile şifrelenmiş saklanır.

### 7.4 Gizli bilgiler
- Bütün anahtarlar `.env` dosyasından gelir (`.env.example` şablon). Depoda gizli bilgi yoktur.
- Veritabanında saklanan sırlar (TOTP, araç API anahtarları) `CryptoService` ile AES-256-GCM kullanılarak şifrelenir. Ana anahtar `ENCRYPTION_KEY`'dir; prod'da KMS ya da Vault'a taşınır.

### 7.5 Denetim kaydı
- Her CMS işlemi ve güvenlik olayı `audit_log` tablosuna yazılır. Tabloda aktör, eylem, nesne, ayrıntı ve **istek kimliği** (`X-Request-Id`) tutulur.
- Tablo **yalnız eklemeyle büyür**: bir PostgreSQL trigger'ı UPDATE ve DELETE'i reddeder. Bunu `ContentWorkflowIT.auditLogIsAppendOnly` doğrular.

*(OWASP Top 10 eşlemesi ve KVKK bölümü F5'te tamamlanacak.)*

## 8. Mobil *(F2.5 yazıldı; çevrimdışı, bildirim ve derin bağlantı F6'da)*

### 8.1 Yapı (ADR-5)
- `apps/mobile` bir Capacitor 7 kabuğudur. İçine `apps/web`'in **aynı React kodu** `vite build --mode mobile` ile derlenip `apps/mobile/www`'ye konur (`npm run build:mobile -w apps/web`). Ardından `npx cap sync android` çalışır ve APK `gradlew assembleDebug` ile üretilir.
- Platform farkları tek bir dosyada toplanır (`apps/web/src/platform.ts`):
  - **API adresi:** `VITE_API_BASE`. Emülatörde `http://10.0.2.2:8080` (`apps/web/.env.mobile`).
  - **Oturum:** Web, refresh token'ı HttpOnly cookie'de tutar. Mobil ise `client: "mobile"` ile giriş yapar; refresh token ve hatırlanan cihaz token'ı gövdede döner ve uygulama deposunda saklanır (Capacitor Preferences). **F6'da Android Keystore ve iOS Keychain'e taşınacak.** Bugünkü depolama uygulamaya özel alandır ve `allowBackup="false"` ile yedeklemeye girmez, ama şifrelenmiş değildir.
  - **Android Geri tuşu:** Uygulama içinde geri gider; ilk ekrandaysa uygulamayı kapatır (`@capacitor/app`).
- **Ağ güvenliği:** `network_security_config.xml` düz HTTP'ye yalnız `10.0.2.2` ve `localhost` için izin verir. Diğer bütün hostlar HTTPS ister ve release derlemesi HTTPS bir API adresi kullanır.
- **Android 15 edge-to-edge:** WebView durum çubuğunun altına çiziliyordu (emülatörde görüldü). Tema `windowOptOutEdgeToEdgeEnforcement` ile düzeltildi. Kalıcı çözüm safe-area padding'idir (F6).
- **Web değişikliği mobile nasıl yansır:** Yeniden `build:mobile`, `cap sync` ve APK gerekir. Backend ve CMS'ten yayınlanan içerik değişiklikleri ise anında yansır, çünkü aynı API kullanılır.

### 8.2 Doğrulama durumu
- **Android:** Pixel 7 emülatöründe (API 35) debug APK kuruldu ve açıldı. Şifre, Mailpit'ten e-posta kodu ve Journey listesi çalışıyor. Uygulama kapatılıp açılınca oturum geri yükleniyor (`docs/mobile/*.png`). Gerçek cihazda **denenmedi**.
- **iOS:** `@capacitor/ios` bağımlılığı ve `capacitor.config.ts` hazır. Xcode yalnız macOS'ta çalıştığı için iOS projesi üretilmedi ve **hiç test edilmedi**. macOS'ta yapılacaklar: `npm run build:mobile -w apps/web`, `cd apps/mobile`, `npx cap add ios`, `npx cap open ios`. API adresi simülatörde `http://localhost:8080` olur; bunun için Info.plist'e geliştirme amaçlı bir ATS istisnası gerekir.

## 9. Ölçeklenme, izleme ve hata yönetimi *(F7'de ölçümlerle yazılacak)*

## 10. Mimari karar kayıtları (ADR)

### ADR-1 — Modüler monolit
- **Bağlam:** Dört istemci yüzeyi ve çok sayıda alan var. Ekip tek kişi, süre çok kısa. İçerik, defter ve iş akışı arasında **işlemsel tutarlılık** gerekiyor; örneğin eğitim tamamlanınca ilerleme ve puan aynı işlemde yazılmalı.
- **Seçenekler:** (a) Mikroservisler. (b) Modüler monolit. (c) Katmanlı ama modülsüz monolit.
- **Karar:** (b). Modüller paket sınırlarıyla ayrılır; puan defteri gibi kritik yazımlar tek bir veritabanı işleminde yapılır.
- **Sonuçlar:** Dağıtık işlem ya da saga gerekmez ve yerelde tek komutla ayağa kalkar. İleride işçiler ve SSE ayrı süreçlere çıkarılabilir, çünkü kuyruk zaten veritabanında. Bunun bedeli, modül sınırlarının disiplinle korunması gerekmesidir.

### ADR-2 — İş kuyruğu PostgreSQL üzerinde (`FOR UPDATE SKIP LOCKED`)
- **Bağlam:** Araç ve iş akışı çalıştırmaları dakikalarca sürebilir. Tekrar deneme, zaman aşımı, iptal ve sunucu yeniden başlarken iş kaybetmeme gerekiyor. Ölçek günde 20.000, kampanya günlerinde 200.000 çalıştırma; bu, saniyede ortalama yaklaşık 2,3 iş demek.
- **Seçenekler:** (a) Redis ve BullMQ (Node'a özgü). (b) RabbitMQ veya Kafka. (c) Temporal. (d) Postgres tablosu ve `SKIP LOCKED`.
- **Karar:** (d).
  - İş kaydı, iş verisiyle **aynı işlemde** yazılır (transactional outbox'a gerek kalmaz).
  - Kilit süresi (`locked_until`) dolan işi başka bir işçi devralır; böylece sunucu yeniden başladığında iş kaybolmaz.
  - `idempotency_key` UNIQUE kısıtı aynı işin iki kez kuyruğa girmesini önler.
  - Ek altyapı gerekmez ve sıfır maliyet kuralına uyar.
- **Sonuçlar:** Saniyede birkaç yüz işe kadar rahattır. Daha yüksek hacimde (§9) kuyruk tablosu bölümlenir ya da işler adanmış bir aracıya taşınır. Kuyruğa erişim `JobQueue` arayüzünün arkasındadır.

### ADR-4 — Puan defteri: yalnız eklemeyle büyüyen defter ve veritabanı kısıtıyla tek seferlik puan
- **Bağlam:** Aynı olay ağ hatası, yeniden deneme ya da mobil eşitleme nedeniyle birden çok kez gelebilir. Puanlar denetlenebilir olmalı ve silinmemeli.
- **Seçenekler:**
  - (a) Kullanıcı satırında bir sayaç (UPDATE).
  - (b) Olay kaynaklı ve projeksiyonlu bir yapı.
  - (c) Yalnız eklemeyle büyüyen defter, `UNIQUE(idempotency_key)` ve aynı işlemde güncellenen toplam.
- **Karar:** (c).
  - Her puanın kaynağı (olay, kural, kural sürümü) satırda durur.
  - Tekrar işleme kontrolü, eşzamanlı isteklerde bile doğru olan veritabanı kısıtına bırakılır.
  - Toplam, okumayı hızlandıran türetilmiş bir veridir; defterden yeniden hesaplanabilir.
- **Sonuçlar:**
  - Düzeltmeler ters kayıtla yapılır; tablo bir trigger ile korunur.
  - (a)'daki kayıp güncelleme ve çift sayma riski ortadan kalkar.
  - (b)'nin karmaşıklığına gerek kalmaz.

### ADR-3 — İçerik sürümleme: tam kopya ve eğitim aggregate'i
- **Bağlam:** Bütün içerik sürümlü olmalı. Fark görünümü ve geri alma gerekiyor. Üye her zaman tek bir tutarlı sürümü görmeli. İç içe içerik var (eğitim içinde ders, quiz ve anket).
- **Seçenekler:**
  - (a) Tam kopya (JSONB).
  - (b) Değişiklik kaydı (delta).
  - (c) Olay kaynaklı (event sourcing).
  - Ayrıca her alt ögeyi ayrı sürümlemek ya da eğitimi bir aggregate olarak sürümlemek.
- **Karar:** (a), eğitim bir aggregate olarak ele alınır.
  - **Okuma:** Tek satır okunur; yeniden oluşturma maliyeti yoktur.
  - **Fark:** İki kopya karşılaştırılarak üretilir.
  - **Geri alma:** İşaretçi değiştirilir.
  - **Kayıt sıklığı:** Günde yaklaşık 100 yayın ve birkaç KB'lık gövdeler için depolama maliyeti önemsiz.
  - **Neden (c) değil:** Projeksiyon ve yeniden oynatma karmaşıklığı bu ölçek için gereksiz.
  - **Neden aggregate:** Alt ögeleri ayrı sürümlemek, "eğitimin v3'ü hangi quiz sürümünü gösterir?" sorusunu her okumada çözmeyi gerektirir; aggregate bu soruyu ortadan kaldırır.
- **Sonuçlar:**
  - Alt ögenin tek başına geçmişi, eğitimin geçmişinden fark alınarak görülür.
  - Araçlar bağımsız sürümlenir ve sabitlenir (§3.3).
  - Medya dosyaları kopyaya gömülmez; içerik adresli (hash) dosya anahtarlarıyla referans verilir. Aynı dosya sürümler arasında bir kez saklanır.

### ADR-6 — Canlı durum: SSE (WebSocket değil)
- **Bağlam:** Çalıştırma durumu ve ileride sıralama tablosu sunucudan istemciye tek yönlü akar. Web ve Capacitor WebView'da çalışmalı. Bağlantı koparsa olay kaybolmamalı.
- **Seçenekler:** (a) WebSocket. (b) SSE. (c) Kısa aralıklı yoklama.
- **Karar:** (b).
  - Olaylar önce `tool_run_event` tablosuna yazılır, sonra açık akışlara gönderilir.
  - İstemci yeniden bağlanırken `Last-Event-ID` gönderir ve kaçırdıkları tablodan yeniden oynatılır.
  - İstemci fetch ile okur, çünkü EventSource `Authorization` başlığı gönderemez. Akış alınamazsa yoklamaya düşer.
- **Sonuçlar:**
  - HTTP/1.1'de tarayıcı başına 6 bağlantı sınırı vardır; HTTP/2 arkasında sorun olmaz.
  - Dağıtım bugün süreç içidir (tek düğüm). Birden çok düğümde Postgres `LISTEN/NOTIFY` ya da bir yayın kanalı eklenir; tablo zaten kalıcı kaynak olduğu için kayıp olmaz.

### ADR-5 — Mobil: Capacitor ile web kodunun yeniden kullanımı
- **Bağlam:** PDF, Android ve iOS uygulaması istiyor (RN, Flutter ya da yerel, seçim bize bırakılmış). Ekip tek kişi ve süre bir gün. Üye ekranları web'de zaten var ve test edildi (320 px, koyu tema, klavye).
- **Seçenekler:**
  - (a) React Native: native bileşenler ve daha iyi kaydırma ve animasyon hissi. Ama bütün ekranlar yeniden yazılır ve iki ayrı UI test edilir.
  - (b) Flutter: (a)'nın sorunlarına ek olarak yeni bir dil gerekir.
  - (c) Capacitor: web kodu native bir kabukta çalışır. Native API'lere (bildirim, Keystore, dosya, derin bağlantı) eklentilerle erişilir.
- **Karar:** (c). Tek UI kodu, tek tasarım sistemi ve tek test seti kullanılır; web'deki erişilebilirlik ve tema işi mobile kendiliğinden geçer. Capacitor bir PWA değildir; mağazaya giden gerçek bir APK ya da IPA üretir.
- **Sonuçlar:** Çok uzun listelerde ve ağır animasyonlarda WebView, RN'nin gerisinde kalır; bu ürünün ekranları içerik ve form ağırlıklı olduğu için kabul edildi. Platforma özgü davranış `platform.ts` ve eklentilerle sınırlı tutulur. Soğuk açılış süresi F7'de ölçülecek.

## 11. İleriye bırakılanlar *(her fazda güncellenir)*
- **Araçlarda devre kesici ve hedef başına hız sınırı:** Bugün işçi sayısı, süre sınırı ve üye başına 5 eşzamanlı çalıştırma var. Art arda hata veren bir hedefin geçici olarak atlanması yok.
- **Araç çıktısı temizliği (90 gün) ve büyük dosya çıktısının diske akışı:** Çıktı 1 MB ile sınırlı.
- **OAuth2 client credentials ile araç kimlik doğrulaması:** Yalnız başlıkta API anahtarı ya da bearer var.
- **Gerçek zamanlı ortak düzenleme (CRDT veya OT):** Yerine iyimser kilit ve çakışma ekranı kullanıldı (§3.4).
- **TOTP kodunun tekrar kullanılmasının engellenmesi:** Aynı 30 saniyelik pencerede aynı kod ikinci kez kabul ediliyor. Çözüm: Son kabul edilen zaman adımını kullanıcıya yazmak.
- **Çok düğümlü hız sınırı:** Bellek içi sayaç tek düğüm için yeterli. Çok düğümde Postgres ya da Redis sayacına geçilir.
- **Açık uçlu quiz cevaplarının insan değerlendirmesi ve itiraz akışı:** Cevaplar saklanıyor ve "değerlendirme bekliyor" olarak işaretleniyor. Değerlendirme ekranı ve itiraz yok.
- **Video saklama ve HLS dağıtımı:** Kodda video, URL ile oynatılıyor ve izleme süresi sunucuda ileri sarmaya karşı ölçülüyor. HLS dönüştürme ve CDN dağıtımı yalnız tasarımda.
- **CMS'te zengin metin düzenleyici:** Ders metni Markdown olarak yazılıyor. Görüntüleyici yalnız React ögeleri üretiyor (XSS'e kapalı), görsel bir düzenleyici yok.

## 12. "Sizin kararınız" — karar listesi
Her soru, cevaplandığı bölüme bağlanacak. Henüz cevaplanmamış sorular ☐, cevaplanmış olanlar ☑ ile işaretlidir.

**3.1 Üyelik ve yetki**
- ☑ Web ve mobil için oturum ve jeton yapısı → §7.1
- ☑ Yetki modeli: rol tabanlı **ve** nesne düzeyinde; yazar yalnız kendi içeriğini düzenler → §7.2
- ☑ Kaba kuvvet koruması ve hesap kilitleme → §7.3

**3.2 Journey**
- ☑ Journey'i bitirmiş üyeler varken yeni eğitim eklenirse seviyeleri ne olur? → §5.4 (seviye düşmez)
- ☑ Ön koşul döngüsü nasıl engellenir? Her kayıtta bütün eğitimlerin son sürümlerinden ön koşul çizgesi kurulur ve DFS ile döngü aranır; döngü varsa kayıt reddedilir ve döngünün yolu gösterilir (`ContentValidator`, `trainingPrerequisiteCyclesAreRejected`).
- ☑ Puan ile seviye arasındaki ilişki; seviye düşebilir mi? → §5.4
- ☑ Journey yayından çekilirse kazanılmış sertifika ve puanlar ne olur? → §5.4

**3.3 Eğitim ve içerik**
- ☑ Açık uçlu cevapları kim değerlendirir; üyenin itiraz yolu ne? → §5.6 (insan değerlendirmesi; akış ertelendi)
- ☑ Quiz süresi dolarken bağlantı koparsa ne olur? → §5.6
- ☐ Videolar nasıl saklanır ve oynatılır (HLS)? (izleme süresi kodda var; saklama ve dağıtım §9'da)
- ☑ Anonim anket gerçekten anonim mi? → §5.6

**3.4 Sürüm ve yayın**
- ☑ Sürümleme modeli: tam kopya → §3.2, ADR-3
- ☑ İç içe içerikte sürüm bağlama: eğitim aggregate'tir, araçlar sabitlenir; kendiliğinden güncelleme yoktur → §3.3
- ☑ Eğitimin ortasındayken yeni sürüm yayınlanırsa ne olur; ilerleme nasıl taşınır? → §5.5
- ☑ Yanlış yayın geri alınınca arada verilen cevaplar ve kazanılan puanlar ne olur? → §5.3, §5.5
- ☑ Medya sürümleme ve saklama maliyeti: içerik adresli dosya anahtarlarıyla kopyalar arasında tekilleştirme → ADR-3

**3.5 Araçlar**
- ☑ Kuyruk ve işçi mimarisi (ADR-2, §4.2) · ☑ Aynı işin iki kez çalışmasının önlenmesi → §4.2
- ☑ Dış API yavaşlar ya da çökerse koruma: eşzamanlılık sınırı, süre sınırı, üye kotası (devre kesici ertelendi) → §4.2
- ☑ Çıktıların saklanma süresi (öneri: 90 gün; temizlik işi yok) · ☐ Kötü niyetli araçların tespiti (SSRF engeli var; içerik taraması yok) · ☑ Şablon enjeksiyonu → §4.1

**3.6 İş akışları**
- ☑ Akış modeli ve çalıştırma motoru: kendi motorumuz → §4.5
- ☑ Çalışma sırasında aracın ya da alt akışın yeni sürümü yayınlanırsa ne olur? → §4.5
- ☑ İstem enjeksiyonu · ☑ Büyük çıktı · ☑ Sunucu yeniden başlarsa ne olur? → §4.5 · ☑ Aynı webhook iki kez gelirse? → §4.6
- ☑ Çatallanan akışın özgün sahibi akışını silerse ne olur? → §4.7

**3.7 Oyunlaştırma**
- ☑ Puan tablosu ve gerekçesi → §5.1 · ☑ Kural değişince geçmiş puanlar yeniden hesaplanır mı? Hayır → §5.3
- ☑ Silinen araç ya da geri alınan yayın için puan geri alınır mı? → §5.3
- ☐ 50.000 üyede sıralama tablosu (taslak §5.3; F4'te ölçümle) · ☑ Çevrimdışı aksiyon puanı ve cihaz saatiyle oynanması → §5.3

**3.8 Topluluk**
- ☐ Yorumlarda kişisel veri ve küfür süzme · ☐ Paylaşılan araç kimin kotasından düşer, kimin anahtarını kullanır? (☑ §4.7) · ☐ Etkinlik akışının ölçeklenmesi

**3.9 Bildirimler**
- ☑ WebSocket mi, SSE mi? → ADR-6 · ☑ Kaçırılan olaylar → ADR-6 · ☐ Bildirimin iki kez gitmemesi · ☐ Sessiz saatler

**3.10 Geçmişim ve KVKK**
- ☐ Olay kaydının yapısı ve saklama süresi (yapı: `activity_event`; saklama F5) · ☐ Silinen hesabın paylaştıkları, yorumları ve sıralamadaki yeri · ☑ Sertifika sahteciliği → §5.7

**3.11 Arama ve kurumsal kullanım**
- ☐ Arama altyapısı · ☐ Eksik çeviri · ☐ Çok kiracılı veri ayrımı ve maliyeti

**3.12 Raporlar**
- ☐ Canlı veritabanı mı, analitik depo mu? · ☐ Kabul edilebilir gecikme

**Bizim bulduğumuz ek kararlar**
- ☑ Yayınlanmamış içeriğin sızmasını önleyen katman → §3.2 (`PublishedContent`)
- ☑ CMS ile üye uygulamasının ayrımı: ayrı origin (:5174), ayrı giriş akışı, ayrı token audience'ı, ayrı cookie → §7.1
- ☑ Zamanlanmış yayının saat dilimi ve kaçırılan tetikleme → §3.2
- ☑ Editör kendi sürümünü onaylayabilir mi? Hayır, dört göz ilkesi geçerli → §7.2
- ☐ Mobil eşitlemede olay kimliğini istemci mi üretir?

## 13. Yapay zekâ kullanımı *(sürekli güncellenir)*
- **Araç:** Claude Code (Anthropic).
- **Kullanım alanları:** PDF analizi ve gereksinim matrisi, mimari plan taslağı, iskelet kod ve test üretimi, belge taslakları.
- **Doğrulama:** Her faz derleme ve otomatik testlerle doğrulanır. Komutlar ve gerçek sonuçları `DURUM.md`'de kayıtlıdır. Kararların sahibi geliştiricidir; reddedilen ve değiştirilen öneriler burada listelenir.
