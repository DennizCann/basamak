import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Alert, StateView, errorMessage, fmt } from "@basamak/ui";
import { api } from "../auth";
import { useNotifications } from "../notifications";

type Pref = { type: string; label: string; inApp: boolean; email: boolean; push: boolean };
const CHANNELS: [keyof Pick<Pref, "inApp" | "email" | "push">, string][] = [["inApp", "Uygulama içi"], ["email", "E-posta"], ["push", "Mobil bildirim"]];

/** Notification centre (scenario 3.9): list, mark read, and which event arrives through which channel. */
export function NotificationsPage() {
  const { items, unread, loaded, reload, markRead, markAll } = useNotifications();
  const nav = useNavigate();
  const [prefs, setPrefs] = useState<Pref[] | null>(null);
  const [prefError, setPrefError] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);

  const loadPrefs = () => api.get<Pref[]>("/api/me/notifications/prefs").then(setPrefs).catch((e) => setPrefError(errorMessage(e)));
  useEffect(() => { loadPrefs(); reload(); }, [reload]);

  const toggle = async (p: Pref, ch: "inApp" | "email" | "push", value: boolean) => {
    const next = { ...p, [ch]: value };
    setPrefs((ps) => ps?.map((x) => (x.type === p.type ? next : x)) ?? null);
    setSaved(null);
    try {
      setPrefs(await api.put<Pref[]>("/api/me/notifications/prefs", [next]));
      setSaved(`"${p.label}" için tercih kaydedildi.`);
      reload();
    } catch (e) {
      setPrefError(errorMessage(e));
      loadPrefs();
    }
  };

  return (
    <main className="page stack">
      <div className="row space-between">
        <h1>Bildirimler</h1>
        <button className="btn" onClick={markAll} disabled={unread === 0} title={unread === 0 ? "Okunmamış bildirim yok." : undefined}>Tümünü okundu say</button>
      </div>
      <p className="hint-line" aria-live="polite">{loaded ? (unread ? `${fmt.num(unread)} okunmamış bildirim` : "Okunmamış bildirim yok.") : ""}</p>
      <StateView loading={!loaded} error={null} empty={loaded && items.length === 0}
                 emptyText="Henüz bildirimin yok. Bir araç ya da iş akışı bitince, seviye atlayınca burada görünür.">
        <ol className="card-list" aria-label="Bildirim listesi">
          {items.map((n) => (
            <li key={n.id} className={`card notice ${n.readAt ? "" : "notice-unread"}`}>
              <div className="row space-between">
                <div>
                  <strong>{!n.readAt && <span className="visually-hidden">Okunmamış: </span>}{n.title}</strong>
                  {n.body && <div>{n.body}</div>}
                  <div className="hint-line">{fmt.dateTime(n.createdAt)}</div>
                </div>
                <div className="row">
                  {n.link && <button className="btn" onClick={() => { markRead(n.id); nav(n.link!); }}>Aç</button>}
                  {!n.readAt && <button className="btn" onClick={() => markRead(n.id)}>Okundu</button>}
                </div>
              </div>
            </li>
          ))}
        </ol>
      </StateView>

      <section className="card stack" aria-labelledby="prefs-h">
        <h2 id="prefs-h">Bildirim tercihleri</h2>
        <p className="hint" style={{ margin: 0 }}>Hangi olayı hangi kanaldan alacağını seç. Mobil bildirim, uygulama açıkken ya da arka plandayken gelir (uygulama tamamen kapalıyken gelmesi için FCM kurulumu gerekir).</p>
        {prefError && <Alert kind="error"><p>{prefError}</p></Alert>}
        {saved && <p role="status" className="hint-line">{saved}</p>}
        {!prefs ? <p className="muted">Yükleniyor…</p> : (
          <div className="table-wrap">
            <table className="table">
              <caption className="visually-hidden">Olay ve kanal tercihleri</caption>
              <thead><tr><th scope="col">Olay</th>{CHANNELS.map(([, l]) => <th key={l} scope="col">{l}</th>)}</tr></thead>
              <tbody>
                {prefs.map((p) => (
                  <tr key={p.type}>
                    <th scope="row">{p.label}</th>
                    {CHANNELS.map(([ch, l]) => (
                      <td key={ch}>
                        <label className="choice"><input type="checkbox" checked={p[ch]} onChange={(e) => toggle(p, ch, e.target.checked)} />
                          <span className="visually-hidden">{p.label}: {l}</span></label>
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </main>
  );
}
