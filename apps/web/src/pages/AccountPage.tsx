import { useState, type FormEvent } from "react";
import { Alert, TextField, errorMessage } from "@basamak/ui";
import { api, useAuth } from "../auth";

/** "Hesabım ve verilerim": download all my data (JSON) and delete the account (anonymisation). */
export function AccountPage() {
  const { user, logout } = useAuth();
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [downloading, setDownloading] = useState(false);
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [deleting, setDeleting] = useState(false);

  const download = async () => {
    setError(null); setNotice(null); setDownloading(true);
    try {
      await api.download("/api/me/export", `basamak-verilerim-${new Date().toISOString().slice(0, 10)}.json`);
      setNotice("Verilerin indirildi (JSON). Dosyada şifreler, anahtarlar ve gizli değerler yoktur.");
    } catch (e) { setError(errorMessage(e)); }
    finally { setDownloading(false); }
  };

  const remove = async (e: FormEvent) => {
    e.preventDefault();
    setError(null); setDeleting(true);
    try {
      await api.post("/api/me/delete", { password, confirmation: confirm });
      await logout().catch(() => {});
      window.location.assign("/login?deleted=1");
    } catch (err) { setError(errorMessage(err)); setDeleting(false); }
  };

  return (
    <main className="page stack">
      <h1>Hesabım ve verilerim</h1>
      {error && <Alert kind="error"><p>{error}</p></Alert>}
      {notice && <Alert kind="success"><p>{notice}</p></Alert>}
      <section className="card stack" aria-labelledby="me-title">
        <h2 id="me-title">Hesap</h2>
        <p><strong>{user?.displayName}</strong> · {user?.email}</p>
      </section>
      <section className="card stack" aria-labelledby="export-title">
        <h2 id="export-title">Verilerimi indir</h2>
        <p>Profilin, ilerlemen, quiz ve anket cevapların, etkinlik geçmişin, puan hareketlerin, sertifikaların, araçların, iş akışların ve oturumların tek bir JSON dosyasında.</p>
        <div><button className="btn btn-primary" onClick={download} disabled={downloading}>{downloading ? "Hazırlanıyor…" : "Verilerimi indir"}</button></div>
      </section>
      <section className="card stack danger-zone" aria-labelledby="delete-title">
        <h2 id="delete-title">Hesabımı sil</h2>
        <p>Bu işlem <strong>geri alınamaz</strong>. Silindiğinde:</p>
        <ul>
          <li>e-posta adresin, adın, şifren, oturumların, araçların ve iş akışların silinir;</li>
          <li>sertifikaların geçersiz olur (doğrulama sayfası “geri alınmış” der);</li>
          <li>topluluğa paylaştığın akışlar kaldırılır, başkalarının çatalları onların kopyası olarak kalır;</li>
          <li>istatistikler (tamamlama oranları gibi) bozulmasın diye etkinlik ve puan kayıtları <em>adsız</em> olarak kalır; artık sana bağlanamaz.</li>
        </ul>
        <form className="stack" onSubmit={remove}>
          <TextField label="Şifren" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
          <TextField label="Onay için SİL yaz" required value={confirm} onChange={(e) => setConfirm(e.target.value)} autoComplete="off" />
          <div><button className="btn btn-danger" type="submit" disabled={deleting || !password || confirm.trim().toLocaleUpperCase("tr-TR") !== "SİL"}>
            {deleting ? "Siliniyor…" : "Hesabımı kalıcı olarak sil"}</button></div>
        </form>
      </section>
    </main>
  );
}
