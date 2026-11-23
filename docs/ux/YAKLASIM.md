# Üye uygulaması — UX yaklaşımı (UX fazı, 23.11.2026)

Bu belge kod değişikliklerinden önce yazıldı ve uygulanan tasarımın gerekçesidir. "Önce" ve "sonra" görüntüleri `docs/ux/before` ile `docs/ux/after` klasörlerinde, izole E2E yığınında gerçek test kullanıcılarıyla alındı (`$env:SCREENS="after"; npm run e2e -- screens.spec.ts`).

## 1. Temel kullanıcı görevleri (sıklık sırasıyla)
1. Kaldığım yerden devam etmek (devam eden kullanıcı).
2. Başlayacağım ilk ya da sıradaki eğitimi bulmak (yeni kullanıcı).
3. Bir ders içinde ilerlemek: oku, tamamla, sonrakine geç.
4. Nerede olduğumu anlamak: seviye, öğrenme yolundaki konum, puan.
5. Sertifikamı bulmak, indirmek ve doğrulama bağlantısını paylaşmak.
6. Görünüm tercihini değiştirmek ve çıkış yapmak (seyrek).

## 2. Gezinme
- **Başlık:** Basamak işareti (üç basamak) ve iki gerçek hedef: **Öğrenimim** (kontrol merkezi ve öğrenme yolu) ile **Sertifikalarım**. Sağda baş harfli hesap düğmesi.
- **Ayrı sayfa açılmayanlar:** Profil, ayarlar, bildirim, topluluk, arama ve destek henüz yok; bunlara sahte bağlantı verilmedi. "Öğrenme yolu" ana sayfanın bir bölümü olarak kaldı, çünkü veri küçük ve ayrı bir sayfa yalnız menüyü kalabalıklaştırırdı.
- **Hesap menüsü:** Ad ve e-posta salt okunur gösterilir. Altında görünüm tercihi (Sistem / Açık / Koyu), "Puanlar nasıl kazanılır?" ve Çıkış yer alır. Menü Escape ile ve dışarı tıklayınca kapanır; açılınca ilk ögeye odaklanır, kapanınca odak hesap düğmesine döner.
- **Mobil:** Tek satırlık başlık. Yalnız iki hedef olduğu için alt gezinme çubuğu eklenmedi; iki kısa bağlantı ve hesap düğmesi 360 piksele sığıyor.

## 3. Ana sayfa: üç durum, tek birincil eylem
| Durum (gerçek veriden) | Başlık | Birincil eylem |
|---|---|---|
| Hiç kayıt yok | "Basamak'ta öğrenmeye hoş geldin" ve 2 cümlelik değer açıklaması | **Öğrenmeye başla** → açık olan ilk Journey'in ilk tamamlanmamış eğitimi |
| Devam eden kayıt var | "Kaldığın yerden devam et" ve eğitim adı, ilerleme (x/y içerik) | **Devam et** → en son etkinliği olan kaydın `current_item_key` ögesi |
| Devam eden yok, açık ve başlanmamış eğitim var | "Sıradaki eğitimin" | **Başla** |
| Açık eğitimlerin hepsi bitti | "Mevcut eğitimlerin hepsini tamamladın" ve kısa dürüst açıklama (yeni içerik hazırlanıyor ya da sıradaki Journey'in koşulu) | Sertifikalarım; ikincil: eğitimleri yeniden incele |

**Kurallar:** Kilitli ya da hazırlanmamış bir eğitim için "Devam et" gösterilmez. Son içerik bilgisi yoksa uydurulmaz; en son güncellenen kayıttan güvenli bir öneri üretilir.

## 4. Öğrenme yolu
- Dikey, numaralı adım listesi ("harita" yerine **Öğrenme yolu**). Her adımın simgesi ve metni durumunu söyler: ✓ tamamlandı, ● şu anki, kilit, kum saati (yakında).
- **Şu anki Journey açık** gelir ve eğitim kartlarını gösterir. **Tamamlananlar** daraltılmış gelir (açılabilir); sertifika bağlantısı daraltılmış satırda da görünür. **Kilitli ve yakında** adımlar tek satırdır: başlık ve kısa neden.
- **Eğitim kartı:** başlık, gerçek özet, gerçek içerik sayısı ve ilerleme, durum, tek eylem düğmesi (Başla / Devam et / Tekrar incele). Kartta başka etkileşimli öge yok.

## 5. Ders ekranı
- **Üst kısım:** "Öğrenimim" dönüş bağlantısı, eğitim adı, ilerleme (x/y) ve sürüm uyarısı (varsa).
- **Masaüstü:** Solda yapışkan içerik menüsü (tür simgesi, durum, seçili öge), sağda okuma alanı. Metin genişliği ~68 karakter, satır yüksekliği 1,7.
- **Mobil:** İçerik menüsü varsayılan olarak **kapalı** bir açılır panel olur. Kapalıyken de "3/4 · Şu an: Puanlar ve seviyeler" görünür. Bir öge seçilince panel kapanır ve odak başlığa gider.
- **Öge sonu:** **Önceki** ve **Sonraki** düğmeleri. Sonraki yalnız ögeyi açar; quiz'i başlatmaz, deneme hakkı tüketmez. Son ögede eğitim özetine ya da ana sayfaya yönlendirilir.
- URL'de seçili öge, yenileme ve Geri/İleri davranışı korunur.

## 6. Görsel ilkeler
- **Tek düz zemin:** Kartlar yalnız gruplama için; gölgeler hafif. Her şey kart içine alınmaz.
- **Tipografi ölçeği:** 14 / 16 / 18 / 20 / 24 / 32 px. Başlıklar 600–700 ağırlık.
- **Renk ve durum:** Birincil renk (mor) yalnız birincil eylem ve seçili durum için. Durum rozetleri "nokta ve metin" biçiminde; bilgi yalnız renkle verilmez.
- **İlerleme:** Sayı çubuğun **dışında** yazılır (kontrast sorunu yok).
- **Marka:** Ölçülü bir karakter: üç basamaklı işaret ve "aşama aşama" dili. Gradyan, konfeti ve dekoratif görsel yok.

## 7. Dil
- **Journey, Beginner, Expert, Master:** PDF'te ürün terimi oldukları için korundu. İlk karşılaşmada açıklama verilir: "Journey'ler seviyelerdir; içindeki eğitimleri bitirince bir sonrakine geçersin."
- **Tek kavram, tek kelime:** "eğitim", "içerik", "Journey", "seviye", "puan".
- **Kilitli:** "'Beginner' tamamlanınca açılır." **Yakında:** "Eğitimleri hazırlanıyor."
