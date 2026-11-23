import { useCallback, useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { Alert, StateView, errorMessage, fmt } from "@basamak/ui";
import { api, useAuth } from "../auth";
import { defaultBody, STATUS_LABEL, type Item } from "../content";

export function ContentListPage({ type, label }: { type: string; label: string }) {
  const { can } = useAuth();
  const nav = useNavigate();
  const [items, setItems] = useState<Item[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [title, setTitle] = useState("");

  const load = useCallback(() => {
    let current = true;
    setItems(null); setError(null);
    // ignore a late response for a previous list type (e.g. quick switch Journey'ler -> Eğitimler)
    api.get<Item[]>(`/api/cms/items?type=${type}`)
      .then((r) => { if (current) setItems(r); })
      .catch((e) => { if (current) setError(errorMessage(e)); });
    return () => { current = false; };
  }, [type]);
  useEffect(load, [load]);

  async function create(e: React.FormEvent) {
    e.preventDefault();
    try {
      const created = await api.post<Item>("/api/cms/items", { type, title: title.trim(), body: defaultBody(type, (items?.length ?? 0) + 1) });
      nav(`/items/${created.id}`);
    } catch (err) { setError(errorMessage(err)); }
  }

  return (
    <main className="container stack">
      <h1>{label}</h1>
      {can("AUTHOR", "EDITOR") && (
        <form onSubmit={create} className="card row">
          <div className="field" style={{ flex: "1 1 16rem" }}>
            <label htmlFor="new-title">Yeni içerik başlığı</label>
            <input id="new-title" value={title} onChange={(e) => setTitle(e.target.value)} required maxLength={200} />
          </div>
          <button className="btn btn-primary" disabled={!title.trim()}>Taslak oluştur</button>
        </form>
      )}
      {error && items && <Alert kind="error"><p>{error}</p></Alert>}
      <StateView loading={!items && !error} error={!items ? error : null} onRetry={load} empty={items?.length === 0} emptyText="Bu türde henüz içerik yok.">
        <div className="table-wrap card">
          <table>
            <thead><tr><th>Başlık</th><th>Son sürüm</th><th>Yayında</th><th>Sahibi</th><th>Güncellendi</th></tr></thead>
            <tbody>
              {items?.map((i) => (
                <tr key={i.id}>
                  <td><Link to={`/items/${i.id}`}>{i.latest.title}</Link></td>
                  <td>s{i.latest.versionNo} · <span className="badge badge-info">{STATUS_LABEL[i.latest.status] ?? i.latest.status}</span></td>
                  <td>{i.publishedVersionNo ? `s${i.publishedVersionNo}` : <span className="muted">yok</span>}</td>
                  <td>{i.owner}</td>
                  <td>{fmt.dateTime(i.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </StateView>
    </main>
  );
}
