import { useCallback, useEffect, useState } from "react";
import { Alert, StateView, errorMessage, trCompare } from "@basamak/ui";
import { api, useAuth } from "../auth";

type Member = { id: string; email: string; displayName: string; totpEnabled: boolean; roles: string };
const ROLE: Record<string, string> = { AUTHOR: "Yazar", EDITOR: "Editör", PUBLISHER: "Yayıncı", ADMIN: "Yönetici" };

/** Content team and the "lost authenticator" reset (admin only; an admin cannot reset their own). */
export function TeamPage() {
  const { can, user } = useAuth();
  const [rows, setRows] = useState<Member[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const load = useCallback(async () => {
    try { setRows(await api.get<Member[]>("/api/cms/team")); } catch (e) { setError(errorMessage(e)); }
  }, []);
  useEffect(() => { if (can("ADMIN")) load(); }, [load, can]);

  if (!can("ADMIN")) return <main className="container"><h1>Ekip</h1><Alert kind="info"><p>Ekip sayfasını yalnız yönetici görebilir.</p></Alert></main>;

  return (
    <main className="container stack">
      <h1>Ekip</h1>
      <p className="muted">İçerik ekibi ve iki aşamalı doğrulama durumu. Doğrulayıcı uygulamasına erişemeyen (telefonunu kaybeden) bir ekip üyesinin ayarını buradan sıfırlayabilirsin: açık CMS oturumları kapanır, bir sonraki girişte yeni QR kodla kurulum ekranı çıkar. Kendi ayarını başka bir yönetici sıfırlamalıdır.</p>
      {error && <Alert kind="error"><p>{error}</p></Alert>}
      {notice && <Alert kind="success"><p>{notice}</p></Alert>}
      <StateView loading={!rows && !error} error={null} empty={rows?.length === 0} emptyText="Ekipte kimse yok.">
        <div className="table-wrap card">
          <table>
            <caption className="visually-hidden">İçerik ekibi</caption>
            <thead><tr><th scope="col">Ad</th><th scope="col">E-posta</th><th scope="col">Roller</th><th scope="col">Doğrulayıcı</th><th scope="col"><span className="visually-hidden">İşlem</span></th></tr></thead>
            <tbody>
              {[...(rows ?? [])].sort((a, b) => trCompare(a.displayName, b.displayName)).map((m) => {
                const self = m.id === user?.id;
                return (
                  <tr key={m.id}>
                    <th scope="row">{m.displayName}</th>
                    <td>{m.email}</td>
                    <td>{m.roles.split(",").map((r) => ROLE[r] ?? r).join(", ")}</td>
                    <td>{m.totpEnabled ? "Kurulu" : "Kurulmadı (ilk girişte kurulur)"}</td>
                    <td>
                      <button className="btn btn-sm" disabled={self || !m.totpEnabled}
                              title={self ? "Kendi ayarını başka bir yönetici sıfırlamalı." : !m.totpEnabled ? "Henüz kurulmamış." : undefined}
                              onClick={async () => {
                                if (!window.confirm(`${m.displayName} için doğrulayıcı sıfırlansın mı? Açık CMS oturumları kapanır ve bir sonraki girişte yeniden kurulum yapılır.`)) return;
                                try { await api.post(`/api/cms/team/${m.id}/totp-reset`); setNotice(`${m.displayName} için doğrulayıcı sıfırlandı.`); setError(null); load(); }
                                catch (e) { setError(errorMessage(e)); }
                              }}>Doğrulayıcıyı sıfırla</button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </StateView>
    </main>
  );
}
