import { useCallback, useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { Alert, StateView, errorMessage, fmt } from "@basamak/ui";
import { api, useAuth } from "../auth";
import type { Workflow } from "../workflows";

/** "İş akışlarım" + community (shared) workflows that can be forked. */
export function WorkflowsPage() {
  const nav = useNavigate();
  const { user } = useAuth();
  const [mine, setMine] = useState<Workflow[] | null>(null);
  const [shared, setShared] = useState<Workflow[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const [m, c] = await Promise.all([api.get<Workflow[]>("/api/workflows"), api.get<Workflow[]>("/api/community/workflows")]);
      setMine(m);
      setShared(c.filter((w) => w.ownerId !== user?.id));
    } catch (e) { setError(errorMessage(e)); }
  }, [user?.id]);
  useEffect(() => { load(); }, [load]);

  const create = () => nav("/workflows/new");

  const fork = async (w: Workflow) => {
    try {
      const f = await api.post<Workflow>(`/api/workflows/${w.id}/fork`);
      setNotice(`“${w.name}” çatallandı. Yazarı puan kazandı.`);
      nav(`/workflows/${f.id}`);
    } catch (e) { setError(errorMessage(e)); }
  };

  return (
    <main className="page stack">
      <div className="row space-between">
        <div>
          <h1>İş akışları</h1>
          <p className="muted">Araçları, yapay zekâ adımlarını, koşulları ve alt akışları birbirine bağla; elle, zamanlı ya da webhook ile çalıştır.</p>
        </div>
        <button className="btn btn-primary" onClick={create}>Yeni iş akışı</button>
      </div>
      {notice && <Alert kind="success"><p>{notice}</p></Alert>}
      <section className="stack" aria-labelledby="mine-title">
        <h2 id="mine-title">İş akışlarım</h2>
        <StateView loading={!mine && !error} error={error} onRetry={load} empty={mine?.length === 0}
          emptyText="Henüz iş akışın yok. “Yeni iş akışı” ile başla ya da aşağıdan paylaşılan bir akışı çatalla.">
          <ul className="card-list">
            {mine?.map((w) => (
              <li key={w.id} className="card row space-between">
                <div>
                  <Link className="text-link" to={`/workflows/${w.id}`}><strong>{w.name}</strong></Link>
                  <div className="hint-line">
                    {w.definition.steps.length} adım · sürüm {w.versionNo} · {w.visibility === "PUBLIC" ? "paylaşıldı" : "yalnız sen"}
                    {w.forkCount > 0 && ` · ${w.forkCount} çatal`}{w.cron && ` · zamanlı (${w.cron})`}{w.hasWebhook && " · webhook"}
                  </div>
                </div>
                <Link className="btn" to={`/workflows/${w.id}`}>Aç</Link>
              </li>
            ))}
          </ul>
        </StateView>
      </section>

      <section className="stack" aria-labelledby="community-title">
        <h2 id="community-title">Topluluğun paylaştıkları</h2>
        {shared.length === 0 ? <p className="muted">Şimdilik paylaşılan bir akış yok.</p> : (
          <ul className="card-list">
            {shared.map((w) => (
              <li key={w.id} className="card row space-between">
                <div>
                  <strong>{w.name}</strong>
                  <div className="hint-line">{w.ownerName} · {w.definition.steps.length} adım · {w.forkCount} çatal · {fmt.date(w.updatedAt)}</div>
                  {w.description && <p>{w.description}</p>}
                </div>
                <button className="btn" onClick={() => fork(w)}>Çatalla</button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </main>
  );
}
