import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Alert, SelectField, StateView, TextArea, TextField, errorMessage, fmt } from "@basamak/ui";
import { api } from "../auth";
import { ToolRunner, statusText, type ToolInput, type ToolRun } from "../components/ToolRunner";

type Def = {
  description?: string; inputs: ToolInput[]; request: { method: "GET" | "POST"; url: string; headers?: Record<string, string>; body?: unknown };
  timeoutSeconds: number; retries: number; outputPath?: string;
};
type MyTool = { id: string; name: string; definition: Def; secretNames: string[]; secrets: { name: string; updatedAt: string }[]; versionNo: number; updatedAt: string };

const SECRET = "apiKey";
const EMPTY: Def = { description: "", inputs: [{ name: "q", label: "Sorgu", type: "string", required: true, maxLength: 200 }],
  request: { method: "GET", url: "https://", headers: {} }, timeoutSeconds: 10, retries: 2, outputPath: "" };

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
      {editing && <ToolEditor tool={editing === "new" ? null : editing} onClose={() => setEditing(null)} onSaved={(b) => { setEditing(null); setBreaking(b); load(); }} />}

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
                <div className="hint-line">{t.definition.request.method} · {hostOf(t.definition.request.url)} · sürüm {t.versionNo}</div>
                {t.secretNames.some((n) => !t.secrets.find((s) => s.name === n)) && <div className="hint-line warn">Sırrı eksik: araç sahibi anahtarı eklemeli.</div>}
              </div>
              <div className="row">
                <button className="btn btn-primary" onClick={() => { setRunning(t); setEditing(null); }}>Çalıştır</button>
                <button className="btn" onClick={() => { setEditing(t); setRunning(null); }}>Düzenle</button>
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

function ToolEditor({ tool, onClose, onSaved }: { tool: MyTool | null; onClose: () => void; onSaved: (breakingChanges: string[]) => void }) {
  const [name, setName] = useState(tool?.name ?? "");
  const [def, setDef] = useState<Def>(tool?.definition ?? EMPTY);
  const headerEntry = Object.entries(def.request.headers ?? {}).find(([, v]) => v.includes(`{{secret.${SECRET}}}`));
  const [authHeader, setAuthHeader] = useState(headerEntry?.[0] ?? "");
  const [secret, setSecret] = useState("");
  const [bodyText, setBodyText] = useState(def.request.body ? JSON.stringify(def.request.body, null, 2) : "");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const hasSecret = !!tool?.secrets.find((s) => s.name === SECRET);

  const setReq = (patch: Partial<Def["request"]>) => setDef({ ...def, request: { ...def.request, ...patch } });
  const setInput = (i: number, patch: Partial<ToolInput>) => setDef({ ...def, inputs: def.inputs.map((x, j) => (j === i ? { ...x, ...patch } : x)) });

  const save = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    let body: unknown = undefined;
    if (def.request.method === "POST" && bodyText.trim()) {
      try { body = JSON.parse(bodyText); } catch { setError("İstek gövdesi geçerli bir JSON değil."); return; }
    }
    const headers: Record<string, string> = authHeader.trim() ? { [authHeader.trim()]: `{{secret.${SECRET}}}` } : {};
    const definition = { ...def, request: { method: def.request.method, url: def.request.url.trim(), headers, ...(body !== undefined ? { body } : {}) } };
    setSaving(true);
    try {
      const payload = { name, definition, secrets: authHeader.trim() && secret ? { [SECRET]: secret } : undefined };
      if (tool) { const r = await api.put<{ breakingChanges?: string[] }>(`/api/my-tools/${tool.id}`, payload); onSaved(r.breakingChanges ?? []); }
      else { await api.post("/api/my-tools", payload); onSaved([]); }
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <form className="card stack" onSubmit={save} aria-labelledby="editor-title">
      <h2 id="editor-title">{tool ? "Aracı düzenle" : "Yeni araç"}</h2>
      {error && <Alert kind="error"><p>{error}</p></Alert>}
      <TextField label="Araç adı" required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} />
      <TextField label="Açıklama (isteğe bağlı)" value={def.description ?? ""} onChange={(e) => setDef({ ...def, description: e.target.value })} />
      <div className="grid-2">
        <SelectField label="Yöntem" value={def.request.method} onChange={(e) => setReq({ method: e.target.value as "GET" | "POST" })}>
          <option value="GET">GET</option>
          <option value="POST">POST</option>
        </SelectField>
        <TextField label="Adres (URL)" required value={def.request.url} onChange={(e) => setReq({ url: e.target.value })}
          hint="Girdileri {{input.ad}} ile yol ya da sorguya ekleyebilirsin; sunucu adı sabit olmalı." />
      </div>
      <div className="grid-2">
        <TextField label="Anahtar başlığı (isteğe bağlı)" placeholder="X-Api-Key" value={authHeader} onChange={(e) => setAuthHeader(e.target.value)} />
        <TextField label={hasSecret ? "API anahtarı (kayıtlı; değiştirmek için yaz)" : "API anahtarı"} type="password" autoComplete="off"
          disabled={!authHeader.trim()} value={secret} onChange={(e) => setSecret(e.target.value)}
          hint="Şifrelenerek saklanır ve bir daha gösterilmez." />
      </div>
      {def.request.method === "POST" && (
        <TextArea label="İstek gövdesi (JSON, isteğe bağlı)" rows={4} value={bodyText} onChange={(e) => setBodyText(e.target.value)}
          hint={'Örnek: {"q": "{{input.q}}"}'} />
      )}
      <fieldset className="stack">
        <legend>Girdiler</legend>
        {def.inputs.map((i, k) => (
          <div key={k} className="grid-4">
            <TextField label="Ad" value={i.name} onChange={(e) => setInput(k, { name: e.target.value })} />
            <TextField label="Etiket" value={i.label} onChange={(e) => setInput(k, { label: e.target.value })} />
            <SelectField label="Tür" value={i.type} onChange={(e) => setInput(k, { type: e.target.value as ToolInput["type"] })}>
              <option value="string">Metin</option>
              <option value="number">Sayı</option>
              <option value="boolean">Evet/Hayır</option>
            </SelectField>
            <div className="row">
              <label className="choice"><input type="checkbox" checked={i.required} onChange={(e) => setInput(k, { required: e.target.checked })} /> Zorunlu</label>
              <button type="button" className="btn btn-danger" onClick={() => setDef({ ...def, inputs: def.inputs.filter((_, j) => j !== k) })}>Sil</button>
            </div>
          </div>
        ))}
        <div><button type="button" className="btn" onClick={() => setDef({ ...def, inputs: [...def.inputs, { name: `girdi${def.inputs.length + 1}`, label: "", type: "string", required: false, maxLength: 200 }] })}>+ Girdi</button></div>
      </fieldset>
      <div className="grid-2">
        <TextField label="Zaman aşımı (sn, 1-30)" type="number" min={1} max={30} value={def.timeoutSeconds} onChange={(e) => setDef({ ...def, timeoutSeconds: Number(e.target.value) })} />
        <TextField label="Yeniden deneme (0-3)" type="number" min={0} max={3} value={def.retries} onChange={(e) => setDef({ ...def, retries: Number(e.target.value) })} />
      </div>
      <TextField label="Sonuç alanı (isteğe bağlı)" placeholder="data.result" value={def.outputPath ?? ""} onChange={(e) => setDef({ ...def, outputPath: e.target.value })} />
      <div className="row">
        <button className="btn btn-primary" type="submit" disabled={saving}>{saving ? "Kaydediliyor…" : "Kaydet"}</button>
        <button className="btn" type="button" onClick={onClose}>Vazgeç</button>
      </div>
    </form>
  );
}
