import { useState, type ReactNode } from "react";
import { Alert, Markdown, ProgressBar, SelectField, StateView, TextArea, TextField, ThemeToggle } from "@basamak/ui";
import { Meter } from "../components/Meter";

/**
 * Component catalogue (section 4, "Storybook or similar"): every shared component in all of its states, rendered with
 * the real CSS and tokens, in the current theme (switch with the theme control). Hover / focus / pressed are shown
 * statically with state classes and can also be tried live. Public on purpose (no data), at /ui.
 */
export function CatalogPage() {
  const [modal, setModal] = useState(false);
  const [tab, setTab] = useState("a");
  return (
    <main className="page stack catalog">
      <div className="row space-between">
        <h1>Bileşen kataloğu</h1>
        <ThemeToggle />
      </div>
      <p className="muted">Web, CMS ve mobil aynı tasarım değişkenlerini kullanır (<code>packages/ui/src/tokens.css</code>). Her bileşen bütün durumlarıyla aşağıda; tema düğmesiyle açık ve koyu temayı karşılaştırabilirsiniz.</p>

      <Section title="Düğme">
        {[["btn", "İkincil"], ["btn btn-primary", "Birincil"], ["btn btn-danger", "Tehlikeli"], ["btn btn-ghost", "Hayalet"]].map(([cls, label]) => (
          <div key={cls} className="catalog-row">
            <State label="Varsayılan"><button className={cls}>{label}</button></State>
            <State label="Üzerine gelme"><button className={`${cls} is-hover`}>{label}</button></State>
            <State label="Odak"><button className={`${cls} is-focus`}>{label}</button></State>
            <State label="Basılı"><button className={`${cls} is-active`}>{label}</button></State>
            <State label="Devre dışı"><button className={cls} disabled title="Neden devre dışı olduğu ipucunda yazar">{label}</button></State>
            <State label="Yükleniyor"><button className={cls} disabled aria-busy="true">Kaydediliyor…</button></State>
          </div>
        ))}
        <p className="hint">Dokunma alanı en az 44×44 px. Devre dışı düğme nedenini <code>title</code> ya da yanındaki ipucuyla söyler.</p>
      </Section>

      <Section title="Form alanları">
        <div className="catalog-row">
          <State label="Varsayılan"><TextField label="E-posta" placeholder="ad@ornek.com" /></State>
          <State label="İpucu"><TextField label="Şifre" type="password" hint="En az 10 karakter" /></State>
          <State label="Hata"><TextField label="E-posta" defaultValue="ad@" error="Geçerli bir e-posta adresi girin." /></State>
          <State label="Devre dışı"><TextField label="Kullanıcı adı" defaultValue="sabit" disabled /></State>
        </div>
        <div className="catalog-row">
          <State label="Seçim"><SelectField label="Tür" defaultValue="b"><option value="a">Ders</option><option value="b">Quiz</option></SelectField></State>
          <State label="Metin alanı"><TextArea label="Yorum" rows={2} defaultValue="Çok satırlı metin" /></State>
          <State label="Onay kutusu"><label className="choice"><input type="checkbox" defaultChecked /> <span>Bildirim al</span></label></State>
          <State label="Seçenek"><label className="choice"><input type="radio" name="r" defaultChecked /> <span>Seçenek A</span></label></State>
        </div>
      </Section>

      <Section title="Bildirim (uyarı kutusu)">
        <Alert kind="info"><p>Bilgi: Değişiklik kaydedildi, onay bekliyor.</p></Alert>
        <Alert kind="success"><p>Başarılı: Eğitim yayınlandı.</p></Alert>
        <Alert kind="warning"><p>Uyarı: Bağlantı koptu. Değişikliğiniz bu cihazda saklandı, bağlantı gelince kaydedilecek.</p></Alert>
        <Alert kind="error"><p>Hata: Kaydedilemedi çünkü başka biri bu içeriği değiştirdi. Güncel sürümü yükleyip yeniden deneyin.</p></Alert>
      </Section>

      <Section title="Kart, liste ve sekme">
        <div className="card stack"><strong>Kart başlığı</strong><p className="muted" style={{ margin: 0 }}>Kart gövdesi ve ikincil metin.</p></div>
        <ul className="card-list"><li className="card">Liste ögesi 1</li><li className="card">Liste ögesi 2</li></ul>
        <div role="tablist" aria-label="Örnek sekmeler" className="row">
          {[["a", "Düzenle"], ["b", "Önizleme"], ["c", "Geçmiş"]].map(([k, l]) => (
            <button key={k} role="tab" aria-selected={tab === k} className={`btn ${tab === k ? "btn-primary" : ""}`} onClick={() => setTab(k)}>{l}</button>
          ))}
        </div>
      </Section>

      <Section title="İlerleme göstergesi">
        <ProgressBar value={3} max={8} label="Eğitim ilerlemesi" />
        <Meter value={5} max={8} label="Journey ilerlemesi" />
      </Section>

      <Section title="Ekran durumları">
        <div className="catalog-row">
          <State label="Yükleniyor (iskelet)"><div style={{ width: 260 }}><StateView loading /></div></State>
          <State label="Boş"><StateView empty emptyText="Henüz bir aracın yok. “Yeni araç” ile ilkini oluştur." /></State>
          <State label="Hata"><StateView error="Sunucuya ulaşılamadı. İnternet bağlantınızı kontrol edip tekrar deneyin." onRetry={() => {}} /></State>
          <State label="Yetkisiz"><Alert kind="warning"><p>Bu sayfayı görmek için yetkiniz yok. Bir editörden erişim isteyin.</p></Alert></State>
          <State label="Çevrimdışı"><Alert kind="warning"><p>Çevrimdışısın. Kayıtlı içerikleri okuyabilirsin; 2 işlem bağlanınca eşitlenecek.</p></Alert></State>
        </div>
      </Section>

      <Section title="Pencere (modal)">
        <button className="btn" onClick={() => setModal(true)}>Pencereyi aç</button>
        {modal && (
          <div className="modal-backdrop" role="presentation" onClick={() => setModal(false)}>
            <div role="dialog" aria-modal="true" aria-labelledby="demo-modal" className="card stack modal-card" onClick={(e) => e.stopPropagation()}
                 onKeyDown={(e) => e.key === "Escape" && setModal(false)}>
              <h2 id="demo-modal">Örnek pencere</h2>
              <p>Escape ya da Kapat ile kapanır; odak pencerenin içinde kalır.</p>
              <button className="btn btn-primary" autoFocus onClick={() => setModal(false)}>Kapat</button>
            </div>
          </div>
        )}
      </Section>

      <Section title="Zengin metin">
        <div className="card"><Markdown source={"## Başlık\nParagraf **kalın** ve *eğik* metin, `kod`.\n\n- Liste\n- Öge\n\n[Bağlantı](https://example.org)"} /></div>
      </Section>
    </main>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return <section className="stack" aria-label={title}><h2>{title}</h2>{children}</section>;
}

function State({ label, children }: { label: string; children: ReactNode }) {
  return <div className="catalog-state"><span className="hint-line">{label}</span>{children}</div>;
}
