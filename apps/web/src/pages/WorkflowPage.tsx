import { askNotificationPermissionOnce } from "../platform";
import { FlowGraph } from "../components/FlowGraph";
import { Comments, Rating } from "../components/Comments";
import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { Alert, SelectField, StateView, TextArea, TextField, errorMessage, fmt } from "@basamak/ui";
import { api, useAuth } from "../auth";
import type { ToolInput } from "../components/ToolRunner";
import {
  OPS, RUN_STATUS, STEP_LABEL, TRIGGER_LABEL, fmtCost, newStepId,
  type Definition, type PlatformTool, type Step, type Workflow, type WorkflowRun,
} from "../workflows";

type MyTool = { id: string; name: string; versionNo: number; definition: { inputs: ToolInput[] } };

export function WorkflowPage() {
  const { id } = useParams();
  const nav = useNavigate();
  const { user } = useAuth();
  const [wf, setWf] = useState<Workflow | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [runs, setRuns] = useState<WorkflowRun[]>([]);
  const [params] = useSearchParams();
  const [openRun, setOpenRun] = useState<string | null>(params.get("run")); // deep link / notification
  const runParam = params.get("run");
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

  return (
    <main className="page stack">
      <p><Link className="text-link" to="/workflows">← İş akışları</Link></p>
      {error && <Alert kind="error"><p>{error}</p></Alert>}
      {notice && <Alert kind="success"><p>{notice}</p></Alert>}
      {!owner ? (
        <section className="card stack">
          <h1>{wf.name}</h1>
          <p className="muted"><Link className="text-link" to={`/u/${wf.ownerId}`}>{wf.ownerName}</Link> paylaştı · {wf.definition.steps.length} adım · {wf.forkCount} çatal</p>
          <Rating workflowId={wf.id} canRate />
          <button className="btn btn-primary" onClick={async () => {
            try { const f = await api.post<Workflow>(`/api/workflows/${wf.id}/fork`); nav(`/workflows/${f.id}`); } catch (e) { setError(errorMessage(e)); }
          }}>Çatalla ve düzenle</button>
        </section>
      ) : null}
      {!owner && <Comments targetType="WORKFLOW" targetId={wf.id} />}
      {!owner ? null : (
        <>
          <Editor wf={wf} onSaved={(w, m) => { setWf(w); setNotice(m); setError(null); }} onError={setError} />
          <RunPanel wf={wf} onStarted={(runId) => { setOpenRun(runId); load(); }} onError={setError} />
          {openRun && <RunDetail runId={openRun} onChanged={load} onError={setError} />}
          <section className="card stack" aria-labelledby="runs-title">
            <h2 id="runs-title">Son çalıştırmalar</h2>
            {runs.length === 0 ? <p className="muted">Henüz çalıştırılmadı.</p> : (
              <ul className="card-list">
                {runs.map((r) => (
                  <li key={r.id} className="row space-between">
                    <span>{fmt.dateTime(r.createdAt)} · {TRIGGER_LABEL[r.trigger] ?? r.trigger} · <span className={`run-state run-${r.status.toLowerCase()}`}>{RUN_STATUS[r.status] ?? r.status}</span>
                      {r.failedStepId && ` (adım: ${r.failedStepId})`} · {fmtCost(r.totalCost)}</span>
                    <button className="btn btn-sm" onClick={() => setOpenRun(r.id)}>Ayrıntı</button>
                  </li>
                ))}
              </ul>
            )}
          </section>
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
            {wf.visibility === "PUBLIC" && <Rating workflowId={wf.id} canRate={false} />}
          </section>
          {wf.visibility === "PUBLIC" && <Comments targetType="WORKFLOW" targetId={wf.id} />}
        </>
      )}
    </main>
  );
}

// ---------------------------------------------------------------------------- editor

function Editor({ wf, onSaved, onError }: { wf: Workflow; onSaved: (w: Workflow, m: string) => void; onError: (m: string) => void }) {
  const [name, setName] = useState(wf.name);
  const [description, setDescription] = useState(wf.description);
  const [def, setDef] = useState<Definition>(structuredClone(wf.definition));
  const [platform, setPlatform] = useState<PlatformTool[]>([]);
  const [mine, setMine] = useState<MyTool[]>([]);
  const [flows, setFlows] = useState<Workflow[]>([]);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    Promise.all([api.get<PlatformTool[]>("/api/tools"), api.get<MyTool[]>("/api/my-tools"), api.get<Workflow[]>("/api/workflows"), api.get<Workflow[]>("/api/community/workflows")])
      .then(([p, m, own, shared]) => { setPlatform(p); setMine(m); setFlows([...own, ...shared.filter((s) => !own.some((o) => o.id === s.id))].filter((f) => f.id !== wf.id)); })
      .catch((e) => onError(errorMessage(e)));
  }, [wf.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const setStep = (i: number, s: Step) => setDef({ ...def, steps: def.steps.map((x, j) => (j === i ? s : x)) });
  const addStep = (type: Step["type"]) => {
    const sid = newStepId(def.steps, type);
    const base: Step = type === "ai" ? { id: sid, type, prompt: "" }
      : type === "condition" ? { id: sid, type, left: "", op: "gt", right: "", then: [], else: [] }
      : type === "tool" ? { id: sid, type, tool: platform[0] ? { scope: "CONTENT", id: platform[0].id } : undefined, input: {} }
      : { id: sid, type, workflowId: flows[0]?.id, input: {} };
    setDef({ ...def, steps: [...def.steps, base] });
  };
  const move = (i: number, d: number) => {
    const s = [...def.steps];
    const j = i + d;
    if (j < 0 || j >= s.length) return;
    [s[i], s[j]] = [s[j], s[i]];
    setDef({ ...def, steps: s });
  };
  const inputsOf = (s: Step): ToolInput[] => {
    if (s.type === "tool") return (s.tool?.scope === "USER" ? mine.find((t) => t.id === s.tool?.id)?.definition.inputs : platform.find((t) => t.id === s.tool?.id)?.inputs) ?? [];
    if (s.type === "workflow") return flows.find((f) => f.id === s.workflowId)?.definition.inputs ?? [];
    return [];
  };

  const save = async (e: FormEvent) => {
    e.preventDefault();
    setSaving(true);
    try {
      const w = await api.put<Workflow>(`/api/workflows/${wf.id}`, { name, description, definition: def });
      setDef(structuredClone(w.definition));
      onSaved(w, `Kaydedildi: sürüm ${w.versionNo}.`);
    } catch (err) { onError(errorMessage(err)); }
    finally { setSaving(false); }
  };

  const placeholders = ["{{input.<ad>}}", ...def.steps.map((s) => `{{steps.${s.id}.output}}`)];
  return (
    <form className="card stack" onSubmit={save} aria-labelledby="wf-title">
      <h1 id="wf-title">{wf.name} <span className="hint-line">sürüm {wf.versionNo}{wf.forkedFrom ? " · çatal" : ""}</span></h1>
      <TextField label="Ad" required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} />
      <TextField label="Açıklama (isteğe bağlı)" maxLength={500} value={description} onChange={(e) => setDescription(e.target.value)} />

      <fieldset className="stack">
        <legend>Girdiler</legend>
        {def.inputs.map((inp, i) => (
          <div key={i} className="grid-4">
            <TextField label="Ad" value={inp.name} onChange={(e) => setDef({ ...def, inputs: def.inputs.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)) })} />
            <TextField label="Etiket" value={inp.label} onChange={(e) => setDef({ ...def, inputs: def.inputs.map((x, j) => (j === i ? { ...x, label: e.target.value } : x)) })} />
            <label className="choice"><input type="checkbox" checked={inp.required} onChange={(e) => setDef({ ...def, inputs: def.inputs.map((x, j) => (j === i ? { ...x, required: e.target.checked } : x)) })} /> Zorunlu</label>
            <button type="button" className="btn btn-danger" onClick={() => setDef({ ...def, inputs: def.inputs.filter((_, j) => j !== i) })}>Sil</button>
          </div>
        ))}
        <div><button type="button" className="btn" onClick={() => setDef({ ...def, inputs: [...def.inputs, { name: `girdi${def.inputs.length + 1}`, label: "", type: "string", required: false, maxLength: 500 }] })}>+ Girdi</button></div>
      </fieldset>

      <h2>Adımlar</h2>
      <FlowGraph def={def} />
      <p className="hint-line">Bir adım yalnız önceki adımların çıktısını kullanabilir: {placeholders.join(" · ")} (alan için {"{{steps.adim.output.alan}}"}).</p>
      <ol className="card-list">
        {def.steps.map((s, i) => (
          <li key={i} className="card stack" aria-label={`${i + 1}. adım: ${STEP_LABEL[s.type]}`}>
            <div className="row space-between">
              <strong>{i + 1}. {STEP_LABEL[s.type]} · <code>{s.id}</code></strong>
              <div className="row">
                <button type="button" className="btn btn-sm" aria-label="Yukarı taşı" onClick={() => move(i, -1)}>↑</button>
                <button type="button" className="btn btn-sm" aria-label="Aşağı taşı" onClick={() => move(i, 1)}>↓</button>
                <button type="button" className="btn btn-sm btn-danger" onClick={() => setDef({ ...def, steps: def.steps.filter((_, j) => j !== i) })}>Sil</button>
              </div>
            </div>
            <TextField label="Adım kimliği" value={s.id} onChange={(e) => setStep(i, { ...s, id: e.target.value.toLowerCase().replace(/[^a-z0-9_-]/g, "") })} />
            {i > 0 && (s.type === "tool" || s.type === "ai") && (def.steps[i - 1].type === "tool" || def.steps[i - 1].type === "ai") && (
              <label className="choice"><input type="checkbox" checked={Boolean(s.parallel)} onChange={(e) => setStep(i, { ...s, parallel: e.target.checked || undefined })} />
                <span>Önceki adımla aynı anda (paralel) çalışsın. Paralel adımlar birbirinin çıktısını kullanamaz; sonraki bir adım hepsini birleştirebilir.</span></label>
            )}
            {s.type === "ai" && <TextArea label="İstem" rows={3} value={s.prompt ?? ""} onChange={(e) => setStep(i, { ...s, prompt: e.target.value })}
              hint="Sahte model (ücretsiz): cevap ve maliyet benzetimdir. “[fail]” yazarsan adım bilerek hata verir." />}
            {s.type === "tool" && (
              <SelectField label="Araç" value={s.tool ? `${s.tool.scope}:${s.tool.id}` : ""} onChange={(e) => {
                const [scope, tid] = e.target.value.split(":");
                setStep(i, { ...s, tool: { scope: scope as "CONTENT" | "USER", id: tid }, input: {} });
              }}>
                <option value="">Seçin…</option>
                <optgroup label="Platform araçları">{platform.map((t) => <option key={t.id} value={`CONTENT:${t.id}`}>{t.name} (s{t.versionNo})</option>)}</optgroup>
                <optgroup label="Araçlarım (paylaşılan akışta kullanılamaz)">{mine.map((t) => <option key={t.id} value={`USER:${t.id}`}>{t.name}</option>)}</optgroup>
              </SelectField>
            )}
            {s.type === "workflow" && (
              <SelectField label="Alt akış" value={s.workflowId ?? ""} onChange={(e) => setStep(i, { ...s, workflowId: e.target.value, input: {} })}>
                <option value="">Seçin…</option>
                {flows.map((f) => <option key={f.id} value={f.id}>{f.name}{f.ownerId !== wf.ownerId ? ` (${f.ownerName})` : ""}</option>)}
              </SelectField>
            )}
            {(s.type === "tool" || s.type === "workflow") && inputsOf(s).map((inp) => (
              <TextField key={inp.name} label={`${inp.label || inp.name} ←`} value={s.input?.[inp.name] ?? ""} placeholder="{{input.konu}} ya da sabit değer"
                onChange={(e) => setStep(i, { ...s, input: { ...s.input, [inp.name]: e.target.value } })} />
            ))}
            {s.type === "condition" && (
              <>
                <div className="grid-2">
                  <TextField label="Değer" value={s.left ?? ""} placeholder="{{steps.arac1.output.temperatureC}}" onChange={(e) => setStep(i, { ...s, left: e.target.value })} />
                  <SelectField label="Karşılaştırma" value={s.op} onChange={(e) => setStep(i, { ...s, op: e.target.value })}>
                    {OPS.map(([k, l]) => <option key={k} value={k}>{l}</option>)}
                  </SelectField>
                  <TextField label="Karşı değer" value={s.right ?? ""} onChange={(e) => setStep(i, { ...s, right: e.target.value })} />
                </div>
                <BranchPicker label="Doğruysa çalışacak adımlar" steps={def.steps.slice(i + 1)} value={s.then ?? []} onChange={(v) => setStep(i, { ...s, then: v })} />
                <BranchPicker label="Yanlışsa çalışacak adımlar" steps={def.steps.slice(i + 1)} value={s.else ?? []} onChange={(v) => setStep(i, { ...s, else: v })} />
              </>
            )}
          </li>
        ))}
      </ol>
      <div className="row">
        {(["tool", "ai", "condition", "workflow"] as const).map((t) => <button key={t} type="button" className="btn" onClick={() => addStep(t)}>+ {STEP_LABEL[t]}</button>)}
      </div>
      <TextField label="Akışın sonucu (isteğe bağlı)" value={def.output} placeholder="Boşsa son adımın çıktısı" onChange={(e) => setDef({ ...def, output: e.target.value })} />
      <div><button className="btn btn-primary" type="submit" disabled={saving}>{saving ? "Kaydediliyor…" : "Kaydet (yeni sürüm)"}</button></div>
    </form>
  );
}

function BranchPicker({ label, steps, value, onChange }: { label: string; steps: Step[]; value: string[]; onChange: (v: string[]) => void }) {
  return (
    <fieldset>
      <legend>{label}</legend>
      {steps.length === 0 ? <p className="hint-line">Bu koşuldan sonra adım yok.</p> : (
        <div className="row">
          {steps.map((s) => (
            <label key={s.id} className="choice"><input type="checkbox" checked={value.includes(s.id)}
              onChange={(e) => onChange(e.target.checked ? [...value, s.id] : value.filter((x) => x !== s.id))} /> {s.id}</label>
          ))}
        </div>
      )}
    </fieldset>
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
