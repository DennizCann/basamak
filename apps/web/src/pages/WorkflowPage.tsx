import { askNotificationPermissionOnce } from "../platform";
import { FlowGraph } from "../components/FlowGraph";
import { WorkflowBuilder } from "../components/WorkflowBuilder";
import { Comments, Rating } from "../components/Comments";
import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { Alert, SelectField, StateView, TextField, errorMessage, fmt } from "@basamak/ui";
import { api, useAuth } from "../auth";
import {
  RUN_STATUS, STEP_LABEL, TRIGGER_LABEL, fmtCost,
  type Workflow, type WorkflowRun,
} from "../workflows";


type Tab = "build" | "run" | "history" | "settings" | "talk";
const TABS: [Tab, string][] = [["build", "Kurgu"], ["run", "Çalıştır"], ["history", "Geçmiş"], ["settings", "Ayarlar"]];

/** New workflow: the step-by-step builder; after saving, the workflow opens on its "Çalıştır" tab. */
export function NewWorkflowPage() {
  const nav = useNavigate();
  return (
    <main className="page stack">
      <p><Link className="text-link" to="/workflows">← İş akışları</Link></p>
      <h1>Yeni iş akışı</h1>
      <WorkflowBuilder isNew initial={{ name: "", description: "", definition: { inputs: [], steps: [], output: "" } }}
        onCancel={() => nav("/workflows")}
        onSave={async (d) => {
          const w = await api.post<Workflow>("/api/workflows", { name: d.name, description: d.description, definition: d.definition });
          nav(`/workflows/${w.id}?tab=run`, { replace: true });
        }} />
    </main>
  );
}

export function WorkflowPage() {
  const { id } = useParams();
  const nav = useNavigate();
  const { user } = useAuth();
  const [wf, setWf] = useState<Workflow | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [runs, setRuns] = useState<WorkflowRun[]>([]);
  const [params, setParams] = useSearchParams();
  const runParam = params.get("run");
  const tab: Tab = (params.get("tab") as Tab) ?? (runParam ? "history" : "build");
  const setTab = (t: Tab) => { const n = new URLSearchParams(params); n.set("tab", t); if (t !== "history" && t !== "run") n.delete("run"); setParams(n, { replace: true }); setNotice(null); };
  const [openRun, setOpenRun] = useState<string | null>(runParam); // deep link / notification
  useEffect(() => { if (runParam) setOpenRun(runParam); }, [runParam]);

  const load = useCallback(async () => {
    try {
      const w = await api.get<Workflow>(`/api/workflows/${id}`);
      setWf(w);
      if (w.ownerId === user?.id) setRuns(await api.get<WorkflowRun[]>(`/api/workflows/${id}/runs`));
    } catch (e) { setError(errorMessage(e)); }
  }, [id, user?.id]);
  useEffect(() => { load(); }, [load]);

  if (!wf) return <main className="page"><StateView loading={!error} error={error} onRetry={load} /></main>;
  const owner = wf.ownerId === user?.id;

  if (!owner) {
    return (
      <main className="page stack">
        <p><Link className="text-link" to="/workflows">← İş akışları</Link></p>
        {error && <Alert kind="error"><p>{error}</p></Alert>}
        <section className="card stack">
          <h1>{wf.name}</h1>
          {wf.description && <p style={{ margin: 0 }}>{wf.description}</p>}
          <p className="muted"><Link className="text-link" to={`/u/${wf.ownerId}`}>{wf.ownerName}</Link> paylaştı · {wf.definition.steps.length} adım · {wf.forkCount} çatal</p>
          <FlowGraph def={wf.definition} />
          <Rating workflowId={wf.id} canRate />
          <div><button className="btn btn-primary" onClick={async () => {
            try { const f = await api.post<Workflow>(`/api/workflows/${wf.id}/fork`); nav(`/workflows/${f.id}`); } catch (e) { setError(errorMessage(e)); }
          }}>Çatalla ve düzenle</button></div>
        </section>
        <Comments targetType="WORKFLOW" targetId={wf.id} />
      </main>
    );
  }

  return (
    <main className="page stack">
      <p><Link className="text-link" to="/workflows">← İş akışları</Link></p>
      <div>
        <h1 style={{ marginBottom: 0 }}>{wf.name}</h1>
        <p className="hint-line">sürüm {wf.versionNo}{wf.forkedFrom ? " · çatal" : ""} · {wf.visibility === "PUBLIC" ? "toplulukla paylaşıldı" : "yalnız sen"}</p>
      </div>
      <div role="tablist" aria-label="Akış bölümleri" className="tabs">
        {[...TABS, ...(wf.visibility === "PUBLIC" ? [["talk", "Tartışma"] as [Tab, string]] : [])].map(([k, l]) => (
          <button key={k} role="tab" id={`tab-${k}`} aria-selected={tab === k} aria-controls={`panel-${k}`} className={`btn ${tab === k ? "btn-primary" : ""}`} onClick={() => setTab(k)}>{l}</button>
        ))}
      </div>
      {error && <Alert kind="error"><p>{error}</p></Alert>}
      {notice && <Alert kind="success"><p>{notice}</p></Alert>}

      <div role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`} className="stack">
        {tab === "build" && (
          <WorkflowBuilder key={wf.versionNo} isNew={false} selfId={wf.id}
            initial={{ name: wf.name, description: wf.description, definition: wf.definition }}
            onSave={async (d) => {
              const w = await api.put<Workflow>(`/api/workflows/${wf.id}`, { name: d.name, description: d.description, definition: d.definition });
              setWf(w); setError(null); setNotice(`Kaydedildi: sürüm ${w.versionNo}. “Çalıştır” sekmesinden deneyebilirsin.`);
            }} />
        )}
        {tab === "run" && (
          <>
            <RunPanel wf={wf} onStarted={(runId) => { setOpenRun(runId); load(); }} onError={setError} />
            {openRun && <RunDetail runId={openRun} onChanged={load} onError={setError} />}
          </>
        )}
        {tab === "history" && (
          <>
            {openRun && <RunDetail runId={openRun} onChanged={load} onError={setError} />}
            <section className="card stack" aria-labelledby="runs-title">
              <h2 id="runs-title">Çalıştırmalar</h2>
              {runs.length === 0 ? <p className="muted">Henüz çalıştırılmadı. “Çalıştır” sekmesinden dene.</p> : (
                <ul className="card-list">
                  {runs.map((r) => (
                    <li key={r.id} className="row space-between">
                      <span>{fmt.dateTime(r.createdAt)} · {TRIGGER_LABEL[r.trigger] ?? r.trigger} · <span className={`run-state run-${r.status.toLowerCase()}`}>{RUN_STATUS[r.status] ?? r.status}</span>
                        {r.failedStepId && ` (adım: ${r.failedStepId})`} · {fmtCost(r.totalCost)}</span>
                      <button className="btn btn-sm" aria-pressed={openRun === r.id} onClick={() => setOpenRun(r.id)}>Ayrıntı</button>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          </>
        )}
        {tab === "settings" && (
          <>
            <Triggers wf={wf} onChanged={(w, m) => { setWf(w); setNotice(m); }} onError={setError} />
            <section className="card stack" aria-labelledby="share-title">
              <h2 id="share-title">Paylaşım</h2>
              <p className="muted">{wf.visibility === "PUBLIC"
                ? `Toplulukla paylaşıldı. ${wf.forkCount} kez çatallandı; her çatal sana puan kazandırır.`
                : "Yalnız sen görüyorsun. Paylaşılan akış yalnız platform araçlarını kullanabilir; kendi anahtarın başkasınca harcanmaz."}</p>
              <div className="row">
                <button className="btn" onClick={async () => {
                  try { const w = await api.post<Workflow>(`/api/workflows/${wf.id}/share`, { shared: wf.visibility !== "PUBLIC" }); setWf(w); setNotice(w.visibility === "PUBLIC" ? "Paylaşıldı." : "Paylaşım kaldırıldı."); }
                  catch (e) { setError(errorMessage(e)); }
                }}>{wf.visibility === "PUBLIC" ? "Paylaşımı kaldır" : "Toplulukla paylaş"}</button>
                <button className="btn btn-danger" onClick={async () => {
                  if (!window.confirm(`“${wf.name}” silinsin mi? Zamanlama ve webhook da kapanır.`)) return;
                  try { await api.del(`/api/workflows/${wf.id}`); nav("/workflows"); } catch (e) { setError(errorMessage(e)); }
                }}>Sil</button>
              </div>
            </section>
          </>
        )}
        {tab === "talk" && wf.visibility === "PUBLIC" && (
          <>
            <section className="card stack"><h2 style={{ margin: 0 }}>Topluluk puanı</h2><Rating workflowId={wf.id} canRate={false} /></section>
            <Comments targetType="WORKFLOW" targetId={wf.id} />
          </>
        )}
      </div>
    </main>
  );
}

// ---------------------------------------------------------------------------- run

function RunPanel({ wf, onStarted, onError }: { wf: Workflow; onStarted: (id: string) => void; onError: (m: string) => void }) {
  const [values, setValues] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const run = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    try {
      await askNotificationPermissionOnce();
      const r = await api.post<WorkflowRun>(`/api/workflows/${wf.id}/runs`, { input: values, clientRequestId: crypto.randomUUID() });
      onStarted(r.id);
    } catch (err) { onError(errorMessage(err)); }
    finally { setBusy(false); }
  };
  return (
    <form className="card stack" onSubmit={run} aria-labelledby="run-title">
      <AiBudget />
      <h2 id="run-title">Çalıştır</h2>
      <p className="hint-line">Kaydedilmiş son sürüm (s{wf.versionNo}) çalışır.</p>
      {wf.definition.inputs.map((i) => (
        <TextField key={i.name} label={(i.label || i.name) + (i.required ? "" : " (isteğe bağlı)")} required={i.required}
          value={values[i.name] ?? ""} onChange={(e) => setValues({ ...values, [i.name]: e.target.value })} />
      ))}
      <div><button className="btn btn-primary" type="submit" disabled={busy}>Çalıştır</button></div>
    </form>
  );
}

function RunDetail({ runId, onChanged, onError }: { runId: string; onChanged: () => void; onError: (m: string) => void }) {
  const [run, setRun] = useState<WorkflowRun | null>(null);
  const timer = useRef<number | undefined>(undefined);
  const changed = useRef(onChanged);
  changed.current = onChanged;

  const poll = useCallback(async () => {
    try {
      const r = await api.get<WorkflowRun>(`/api/workflow-runs/${runId}`);
      setRun(r);
      if (r.status === "QUEUED" || r.status === "RUNNING") timer.current = window.setTimeout(poll, 800);
      else changed.current();
    } catch (e) { onError(errorMessage(e)); }
  }, [runId]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { poll(); return () => window.clearTimeout(timer.current); }, [poll]);

  if (!run) return <p role="status" className="muted">Çalıştırma yükleniyor…</p>;
  return (
    <section className="card stack" aria-labelledby="detail-title">
      <h2 id="detail-title">Çalıştırma ayrıntısı</h2>
      <p role="status" aria-live="polite" className={`run-state run-${run.status.toLowerCase()}`}>
        {RUN_STATUS[run.status] ?? run.status} · {TRIGGER_LABEL[run.trigger]} · s{run.workflowVersionNo} · {fmt.num(run.totalTokens)} token · {fmtCost(run.totalCost)} (benzetim)
        {run.resumeCount > 0 && ` · ${run.resumeCount} kez devam ettirildi`}
      </p>
      {run.error && <Alert kind="error"><p>{run.error}</p></Alert>}
      {run.status === "FAILED" && (
        <div><button className="btn btn-primary" onClick={async () => {
          try { await api.post(`/api/workflow-runs/${run.id}/resume`); poll(); } catch (e) { onError(errorMessage(e)); }
        }}>Hatalı adımdan devam et</button> <span className="hint-line">Başarılı adımlar tekrar çalışmaz; çıktıları kullanılır.</span></div>
      )}
      <div className="table-wrap">
        <table>
          <caption className="visually-hidden">Adım ayrıntıları</caption>
          <thead><tr><th scope="col">Adım</th><th scope="col">Tür</th><th scope="col">Durum</th><th scope="col">Süre</th><th scope="col">Token</th><th scope="col">Maliyet</th><th scope="col">Deneme</th></tr></thead>
          <tbody>
            {run.steps.map((s) => (
              <tr key={s.id}>
                <th scope="row"><code>{s.stepId}</code></th>
                <td>{STEP_LABEL[s.type] ?? s.type}</td>
                <td className={`run-${s.status.toLowerCase()}`}>{RUN_STATUS[s.status] ?? s.status}{s.error && <div className="hint-line">{s.error}</div>}</td>
                <td>{s.durationMs !== null ? `${fmt.num(s.durationMs)} ms` : "—"}</td>
                <td>{fmt.num(s.tokens)}</td>
                <td>{fmtCost(s.cost)}</td>
                <td>{s.attempts}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {run.steps.filter((s) => s.status !== "SKIPPED").map((s) => (
        <details key={s.id}>
          <summary>{s.stepId}: girdi ve çıktı</summary>
          <div className="grid-2">
            <div><h3 className="h-sub">Girdi</h3><pre className="run-output" tabIndex={0}>{JSON.stringify(s.input, null, 2)}</pre></div>
            <div><h3 className="h-sub">Çıktı</h3><pre className="run-output" tabIndex={0}>{s.output === null ? "—" : JSON.stringify(s.output, null, 2)}</pre></div>
          </div>
        </details>
      ))}
      {run.status === "SUCCEEDED" && (
        <><h3 className="h-sub">Akışın sonucu</h3><pre className="run-output" tabIndex={0}>{typeof run.output === "string" ? run.output : JSON.stringify(run.output, null, 2)}</pre></>
      )}
    </section>
  );
}

// ---------------------------------------------------------------------------- triggers

function EventTrigger({ wf, onChanged, onError }: { wf: Workflow; onChanged: (w: Workflow, m: string) => void; onError: (m: string) => void }) {
  const [events, setEvents] = useState<Record<string, string>>({});
  const [value, setValue] = useState(wf.eventTrigger ?? "");
  useEffect(() => { api.get<Record<string, string>>("/api/workflow-event-triggers").then(setEvents).catch(() => {}); }, []);
  return (
    <form className="stack" onSubmit={async (e) => {
      e.preventDefault();
      try { const w = await api.put<Workflow>(`/api/workflows/${wf.id}/event-trigger`, { event: value || null }); onChanged(w, w.eventTrigger ? "Olay tetikleyicisi kaydedildi." : "Olay tetikleyicisi kapatıldı."); }
      catch (err) { onError(errorMessage(err)); }
    }}>
      <h3 className="h-sub">Platform olayı</h3>
      <SelectField label="Şu olduğunda çalıştır" value={value} onChange={(e) => setValue(e.target.value)}
        hint="Yalnız kendi öğrenme olayların. Akışa şu girdiler verilir (girdi adı aynıysa): eventType, objectType, objectId, occurredAt.">
        <option value="">Kapalı</option>
        {Object.entries(events).map(([k, l]) => <option key={k} value={k}>{l}</option>)}
      </SelectField>
      <div><button className="btn" type="submit">Olay tetikleyicisini kaydet</button></div>
    </form>
  );
}

function AiBudget() {
  const [b, setB] = useState<{ limitUsd: number; spentUsd: number; exhausted: boolean; resetsOn: string; simulated: boolean } | null>(null);
  useEffect(() => { api.get<typeof b>("/api/me/ai-budget").then(setB).catch(() => {}); }, []);
  if (!b) return null;
  const pct = Math.min(100, Math.round((Number(b.spentUsd) / Math.max(Number(b.limitUsd), 0.000001)) * 100));
  return (
    <div className="stack" style={{ gap: 4 }}>
      <p className="hint-line" style={{ margin: 0 }}>
        Bu ay yapay zekâ harcaman: {Number(b.spentUsd).toLocaleString("tr-TR", { maximumFractionDigits: 6 })} / {Number(b.limitUsd).toLocaleString("tr-TR")} USD
        {b.simulated ? " (sahte model: benzetim)" : ""}. Yenilenme: {fmt.date(b.resetsOn)}.
      </p>
      <div className="meter" role="meter" aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct} aria-label="Aylık yapay zekâ bütçesi kullanımı">
        <span style={{ width: `${pct}%` }} />
      </div>
      {b.exhausted && <Alert kind="warning"><p>Bu ayki bütçen doldu: yapay zekâ adımı olan akışlar {fmt.date(b.resetsOn)} tarihine kadar başlamaz; çalışan akış yapay zekâ adımında güvenle durur.</p></Alert>}
    </div>
  );
}

function Triggers({ wf, onChanged, onError }: { wf: Workflow; onChanged: (w: Workflow, m: string) => void; onError: (m: string) => void }) {
  const [cron, setCron] = useState(wf.cron ?? "");
  const [cronInput, setCronInput] = useState<Record<string, string>>(wf.cronInput ?? {});
  const [hook, setHook] = useState<string | null>(null);
  const apiBase = (import.meta.env.VITE_API_BASE as string | undefined) || window.location.origin;
  return (
    <section className="card stack" aria-labelledby="trig-title">
      <h2 id="trig-title">Otomatik çalıştırma</h2>
      <form className="stack" onSubmit={async (e) => {
        e.preventDefault();
        try { const w = await api.put<Workflow>(`/api/workflows/${wf.id}/cron`, { cron, input: cronInput }); onChanged(w, w.cron ? `Zamanlama kaydedildi. Sıradaki: ${fmt.dateTime(w.nextRunAt!)}.` : "Zamanlama kapatıldı."); }
        catch (err) { onError(errorMessage(err)); }
      }}>
        <TextField label="Zamanlama (cron, İstanbul saati)" placeholder="*/15 * * * *" value={cron} onChange={(e) => setCron(e.target.value)}
          hint="5 alan: dakika saat gün ay haftanın-günü. Örn. “0 9 * * 1” her pazartesi 09.00. Boş bırakıp kaydedersen kapanır." />
        {wf.definition.inputs.map((i) => (
          <TextField key={i.name} label={`Zamanlı çalıştırmada ${i.label || i.name}`} value={cronInput[i.name] ?? ""}
            onChange={(e) => setCronInput({ ...cronInput, [i.name]: e.target.value })} />
        ))}
        {wf.nextRunAt && <p className="hint-line">Sıradaki çalıştırma: {fmt.dateTime(wf.nextRunAt)}</p>}
        <div><button className="btn" type="submit">Zamanlamayı kaydet</button></div>
      </form>
      <EventTrigger wf={wf} onChanged={onChanged} onError={onError} />
      <div className="stack">
        <h3 className="h-sub">Webhook</h3>
        <p className="muted">{wf.hasWebhook ? "Webhook açık." : "Webhook kapalı."} Gövdedeki JSON akışın girdisi olur. Aynı <code>Idempotency-Key</code> başlığıyla (yoksa aynı gövdeyle) gelen tekrar, ikinci bir çalıştırma başlatmaz.</p>
        {hook && (
          <Alert kind="warning">
            <p>Bu adresi şimdi kopyala; bir daha gösterilmez:</p>
            <p><code style={{ wordBreak: "break-all" }}>{apiBase}{hook}</code></p>
          </Alert>
        )}
        <div className="row">
          <button className="btn" onClick={async () => {
            try { const r = await api.post<{ path: string }>(`/api/workflows/${wf.id}/webhook`); setHook(r.path); onChanged({ ...wf, hasWebhook: true }, "Yeni webhook adresi oluşturuldu; eskisi artık çalışmaz."); }
            catch (e) { onError(errorMessage(e)); }
          }}>{wf.hasWebhook ? "Yeni adres üret" : "Webhook aç"}</button>
          {wf.hasWebhook && <button className="btn btn-danger" onClick={async () => {
            try { const w = await api.del<Workflow>(`/api/workflows/${wf.id}/webhook`); setHook(null); onChanged(w, "Webhook kapatıldı."); } catch (e) { onError(errorMessage(e)); }
          }}>Webhook'u kapat</button>}
        </div>
      </div>
    </section>
  );
}
