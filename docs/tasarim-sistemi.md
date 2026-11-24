# Tasarım sistemi — Figma'ya aktarım notu

Kod ve Figma aynı değişkenlerden beslenir. **Tek kaynak:** [`packages/ui/tokens.json`](../packages/ui/tokens.json).
`npm run tokens` bu dosyadan `tokens.css` üretir; web, CMS ve mobil bu dosyayı kullanır. Figma'da aynı adlarla **Variables** (Light/Dark modları) oluşturun.

## Değişkenler
| Grup | Değerler |
|---|---|
| Renk (light / dark) | `bg`, `surface`, `surface-2`, `border`, `text`, `text-muted`, `primary`, `primary-contrast`, `primary-soft`, `success`, `warning`, `danger`, `info`, `focus` |
| Tipografi | Sistem yazı tipi yığını; `xs 12`, `sm 14`, `md 16`, `lg 20`, `xl 24`, `2xl 32` px; satır yüksekliği 1,5 |
| Boşluk | 4, 8, 12, 16, 24, 32, 48 px |
| Köşe yarıçapı | `sm 4`, `md 8`, `lg 12`, `pill` |
| Hareket | `fast 120ms`, `base 200ms`, `cubic-bezier(.2,0,0,1)`; "hareketi azalt" açıksa 0 |
| Dokunma alanı | 48 px (Android 48 dp, iOS ≥ 44 pt) |

**Karşıtlık hedefi (WCAG 2.2 AA):** metinde ≥ 4,5:1, arayüz ögelerinde ≥ 3:1. Ölçüm sonuçları `OLCUMLER.md`'de; ölçülmemiş değer yazılmaz.

## Bileşenler (kodda olanlar)
- **Düğme:** `btn`, `btn-primary`, `btn-danger`. Durumlar: varsayılan, üzerine gelme, odak (3 px `focus` çerçeve), basılı, devre dışı (devre dışı olma nedeni yanında yazılır).
- **Form alanları:** metin, şifre, sayı, seçim, çok satırlı metin, onay kutusu ve radyo. Etiket, ipucu ve hata metni (`aria-describedby`) vardır.
- **Kart, rozet, ilerleme çubuğu** (`role=progressbar`), **uyarı** (info / success / warning / error), **iskelet yükleyici**, **boş durum**, **tablo**, **sekme**.
- **Tema seçici:** Sistem / Açık / Koyu. Varsayılan sistem ayarıdır.

## Ekran durumları
Her ekranda şu durumlar bulunur:
- **Yükleniyor:** iskelet ve `role=status`.
- **Hata:** ne olduğu, ne yapılacağı ve "Tekrar dene" düğmesi.
- **Boş:** açıklayıcı metin.
- **Yetkisiz:** giriş sayfasına yönlendirme.
- **Çevrimdışı:** yalnız mobilde (F6).

## Ekran listesi (PDF 8.2)
| Ekran | Kodda | Not |
|---|---|---|
| Giriş, kayıt, e-posta doğrulama, şifre sıfırlama | ✓ | web |
| Ana sayfa ve Journey haritası | ✓ | seviye, puan, ilerleme, kilit nedeni, sertifika |
| Eğitim oynatıcı (ders, quiz, anket) | ✓ | süre sayacı, sonuç, doğru cevaplar, yeni sürüm uyarısı |
| Sertifika doğrulama (herkese açık) | ✓ | |
| Araç çalıştırma ve sonuç | F3 | |
| İş akışı kurucu ve çalıştırma geçmişi | F4 | |
| Geçmişim, profil ve sıralama, bildirimler | F4/F5 | |
| CMS: giriş ve TOTP kurulumu | ✓ | QR ve elle anahtar |
| CMS: içerik listesi ve düzenleyici | ✓ | Journey, eğitim (ders, quiz, anket), puan kuralı ve JSON modu |
| CMS: sürüm geçmişi, fark, onay akışı, denetim kaydı, önizleme (390 / 1440) | ✓ | |
| CMS: araç tanımı ve deneme konsolu | F3 | |
| CMS: raporlar | F5 | |

## Temel kullanıcı akışları
1. **Üye:** kayıt → e-posta doğrulama → giriş → Journey haritası → eğitim → ders, quiz, anket → puan → Journey sonu → sertifika (PDF ve QR).
2. **İçerik ekibi:** yazar taslak oluşturur ve kaydeder (her kayıt yeni sürüm) → incelemeye gönderir → editör onaylar ya da gerekçeyle geri gönderir → yayıncı yayınlar ya da zamanlar → geçmiş, fark ve geri alma.

## Figma tasarım panosu
Geliştiricinin Figma'da hazırladığı pano: [`docs/tasarim/basamak-tasarim-panosu.png`](tasarim/basamak-tasarim-panosu.png).
- **Kapsam:** Masaüstü 1440 × 900 ve telefon 390 × 844, açık ve koyu tema.
  - **Web:** Giriş, Öğrenimim ve Journey, Ders, İş akışı kurucu.
  - **Telefon:** Giriş, Öğrenimim, Ders (çevrimdışı rozetiyle), Üret.
  - **CMS:** İçerik ve onay, Araç tanımı ve deneme.
  - **Durum kartları:** Boş, yükleniyor, hata, çevrimdışı.
- **Uygulamayla uyum:** Ekran düzeni ve bileşenler uygulamayla aynıdır: alt gezinme (Öğren / Keşfet / Üret / Topluluk / Profil), ders sayfası (Önceki / Sonraki içerik), akış kurucu sekmeleri ve şeması, CMS yayın durumu ve deneme konsolu.
- **Renkler panoya uyduruldu (24.11):** Panodaki 6 renk `tokens.json`a işlendi; açık temada üzerine binen tonlar da (ana eylemin üzerine gelme rengi, metin rengi ve odak rengi) aynı yöne kaydırıldı. Panoda görünmeyen koyu tema tonları değişmedi; yalnız koyu zemin panoya uyduruldu. Android simgesi ve açılış ekranı ile favicon da yeni ana eylem rengini kullanır.

| Renk | Önceki `tokens.json` | Şimdi (panodaki) | Kontrast (WCAG AA ≥ 4,5) |
|---|---|---|---|
| Ana eylem | `#3B30C9` | `#4030CB` | Beyaz yazıyla 8,39 |
| Açık zemin | `#F6F6F8` | `#F4F5FA` | Soluk metin 7,20 |
| Koyu zemin | `#0F1117` | `#151525` | Metin 15,80, soluk metin 9,15 |
| Başarı | `#126B34` | `#137048` | Zeminde 5,61 |
| Uyarı | `#8A5300` | `#855500` | Zeminde 5,85 |
| Hata | `#B3261E` | `#9A334E` | Zeminde 6,52 |
