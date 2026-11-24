import { useCallback, useEffect, useState } from "react";
import { Alert, StateView, errorMessage, fmt } from "@basamak/ui";
import { ToolWizard, type ToolDef } from "../components/ToolWizard";
import { api } from "../auth";
import { ToolRunner, statusText, type ToolRun } from "../components/ToolRunner";

type Def = ToolDef;
type MyTool = { id: string; name: string; definition: Def; secretNames: string[]; secrets: { name: string; updatedAt: string }[]; versionNo: number; updatedAt: string; visibility?: "PRIVATE" | "PUBLIC" };


/** "Araçlarım": a member's own HTTP tools - create, edit, run with live status, and recent runs. */
export function ToolsPage() {
  const [tools, setTools] = useState<MyTool[] | null>(null);
  const [runs, setRuns] = useState<ToolRun[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<MyTool | "new" | null>(null);
  const [running, setRunning] = useState<MyTool | null>(null);
  const [quota, setQuota] = useState<{ used: number; limit: number; level: string } | null>(null);
  const [breaking, setBreaking] = useState<string[]>([]);

  const load = useCallback(async () => {
    setError(null);
    try {
      const [t, r] = await Promise.all([api.get<MyTool[]>("/api/my-tools"), api.get<ToolRun[]>("/api/tool-runs?limit=10")]);
      setTools(t);
      setRuns(r);
      api.get<typeof quota>("/api/tool-runs/quota").then(setQuota).catch(() => {});
    } catch (e) { setError(errorMessage(e)); }
  }, []);
  useEffect(() => { load(); }, [load]);

  return (
    <main className="page stack">
      <div className="row space-between">
        <div>
          <h1>Araçlarım</h1>
          <p className="muted">Bir web servisine istek gönderen kendi araçlarını oluştur ve çalıştır. İç ağ adreslerine (localhost, 10.x, 192.168.x, 169.254.x …) istek gönderilemez.</p>
        </div>
        {!editing && <button className="btn btn-primary" onClick={() => { setEditing("new"); setRunning(null); }}>Yeni araç</button>}
      </div>

      {quota && <p className="hint-line">Bugünkü çalıştırma hakkın: {quota.used} / {quota.limit} (seviye: {quota.level || "—"}). Seviye atladıkça günlük hakkın artar; hak gece yarısı yenilenir.</p>}
      {breaking.length > 0 && (
        <Alert kind="warning">
          <p><strong>Yeni sürüm şemayı bozuyor.</strong> Bu aracı kullanan iş akışların eski sürüme sabitli kalır ve çalışmaya devam eder; yeni sürümü kullanmak için akışı açıp yeniden kaydet ve eşlemeleri kontrol et.</p>
          <ul>{breaking.map((b) => <li key={b}>{b}</li>)}</ul>
        </Alert>
      )}
      {editing && <ToolWizard tool={editing === "new" ? null : editing} onClose={() => setEditing(null)}
        onSaved={async (id, b) => {
          setEditing(null); setBreaking(b); await load();
          // new tool: open it right away so the member can try it
          try { const t = await api.get<MyTool>(`/api/my-tools/${id}`); setRunning(t); } catch { /* list is enough */ }
        }} />}

      {running && (
        <section className="card stack" aria-labelledby="run-title">
          <div className="row space-between">
            <h2 id="run-title">{running.name}</h2>
            <button className="btn" onClick={() => setRunning(null)}>Kapat</button>
          </div>
          {running.definition.description && <p>{running.definition.description}</p>}
          <ToolRunner inputs={running.definition.inputs}
            start={(input, clientRequestId) => api.post<ToolRun>("/api/tool-runs", { userToolId: running.id, input, clientRequestId })}
            onFinished={() => load()} />
        </section>
      )}

      <StateView loading={!tools && !error} error={error} onRetry={load}
        empty={tools?.length === 0} emptyText="Henüz aracın yok. “Yeni araç” ile ilkini oluştur; ilk aracın puan kazandırır.">
        <ul className="card-list" aria-label="Araçlarım">
          {tools?.map((t) => (
            <li key={t.id} className="card row space-between">
              <div>
                <strong>{t.name}</strong>
                <div className="hint-line">{t.definition.request.method} · {hostOf(t.definition.request.url)} · sürüm {t.versionNo}{t.visibility === "PUBLIC" ? " · toplulukla paylaşıldı" : ""}</div>
                {t.secretNames.some((n) => !t.secrets.find((s) => s.name === n)) && <div className="hint-line warn">Sırrı eksik: araç sahibi anahtarı eklemeli.</div>}
              </div>
              <div className="row">
                <button className="btn btn-primary" onClick={() => { setRunning(t); setEditing(null); }}>Çalıştır</button>
                <button className="btn" onClick={() => { setEditing(t); setRunning(null); }}>Düzenle</button>
                <button className="btn" aria-pressed={t.visibility === "PUBLIC"} onClick={async () => {
                  try { await api.post(`/api/my-tools/${t.id}/share`, { shared: t.visibility !== "PUBLIC" }); load(); } catch (e) { setError(errorMessage(e)); }
                }} title="Paylaşınca yalnız tanım görünür; gizli anahtarın asla paylaşılmaz.">{t.visibility === "PUBLIC" ? "Paylaşımı kaldır" : "Paylaş"}</button>
                <button className="btn btn-danger" onClick={async () => {
                  if (!window.confirm(`“${t.name}” silinsin mi?`)) return;
                  try { await api.del(`/api/my-tools/${t.id}`); load(); } catch (e) { setError(errorMessage(e)); }
                }}>Sil</button>
              </div>
            </li>
          ))}
        </ul>
      </StateView>

      {runs.length > 0 && (
        <section className="stack" aria-labelledby="runs-title">
          <h2 id="runs-title">Son çalıştırmalar</h2>
          <ul className="card-list">
            {runs.map((r) => (
              <li key={r.id} className="card">
                <strong>{r.toolName}</strong> <span className="hint-line">{fmt.dateTime(r.createdAt)}</span>
                <div className={`run-state run-${r.status.toLowerCase()}`}>{statusText(r)}</div>
              </li>
            ))}
          </ul>
        </section>
      )}
    </main>
  );
}

function hostOf(url: string) {
  try { return new URL(url).host; } catch { return url; }
}
