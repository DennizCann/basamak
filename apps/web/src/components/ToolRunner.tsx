import { askNotificationPermissionOnce } from "../platform";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { Alert, TextField, errorMessage, fmt } from "@basamak/ui";
import { api } from "../auth";

export type ToolInput = { name: string; label: string; type: "string" | "number" | "boolean"; required: boolean; maxLength: number };
export type ToolRun = {
  id: string; status: string; attempts: number; maxAttempts: number; httpStatus: number | null; output: unknown; error: string | null;
  durationMs: number | null; createdAt: string; finishedAt: string | null; toolName: string; nextAttemptInMs?: number;
};

const TERMINAL = ["SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED"];
const EVENT_LABEL: Record<string, string> = {
  queued: "Sıraya alındı", running: "Çalışıyor", retrying: "Geçici hata, yeniden denenecek", succeeded: "Tamamlandı",
  failed: "Başarısız", timed_out: "Zaman aşımı", cancelled: "İptal edildi",
};

export function statusText(r: ToolRun): string {
  switch (r.status) {
    case "QUEUED": return "Sırada bekliyor…";
    case "RUNNING": return `Çalışıyor… (deneme ${r.attempts}/${r.maxAttempts})`;
    case "RETRYING": return `Dış servis geçici hata verdi. ${r.nextAttemptInMs ? Math.round(r.nextAttemptInMs / 1000) + " sn sonra " : ""}yeniden denenecek (deneme ${r.attempts}/${r.maxAttempts}).`;
    case "SUCCEEDED": return `Tamamlandı (${fmt.num(r.durationMs ?? 0)} ms${r.attempts > 1 ? `, ${r.attempts}. denemede` : ""}).`;
    case "TIMED_OUT": return `Zaman aşımı: dış servis zamanında cevap vermedi (${r.attempts} deneme).`;
    case "CANCELLED": return "Çalıştırma iptal edildi.";
    default: return r.error ? `Başarısız: ${r.error}` : "Başarısız.";
  }
}

/**
 * Run form + live status of a tool run. Status comes over SSE; if the connection drops it reconnects with the last
 * event id (nothing is missed), and falls back to polling if streaming is not possible.
 */
export function ToolRunner({ inputs, start, onFinished }: {
  inputs: ToolInput[];
  start: (input: Record<string, unknown>, clientRequestId: string) => Promise<ToolRun>;
  onFinished?: (run: ToolRun) => void;
}) {
  const [values, setValues] = useState<Record<string, string | boolean>>({});
  const [run, setRun] = useState<ToolRun | null>(null);
  const [log, setLog] = useState<{ id: number; label: string; at: string }[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const abort = useRef<AbortController | null>(null);
  const finished = useRef(onFinished);
  finished.current = onFinished;

  useEffect(() => () => abort.current?.abort(), []);

  const follow = async (id: string) => {
    abort.current?.abort();
    const ctrl = new AbortController();
    abort.current = ctrl;
    let last = 0;
    let current: ToolRun | null = null;
    for (let attempt = 0; attempt < 30 && !ctrl.signal.aborted; attempt++) {
      try {
        await api.stream(`/api/tool-runs/${id}/events`, (e) => {
          if (e.id <= last) return; // replayed after reconnect
          last = e.id;
          current = e.data as ToolRun;
          setRun(current);
          setLog((l) => [...l, { id: e.id, label: EVENT_LABEL[e.event] ?? e.event, at: new Date().toLocaleTimeString("tr-TR") }]);
        }, { signal: ctrl.signal, lastEventId: last });
      } catch {
        if (ctrl.signal.aborted) return;
        try { current = await api.get<ToolRun>(`/api/tool-runs/${id}`); setRun(current); } catch { /* keep trying */ }
      }
      if (current && TERMINAL.includes((current as ToolRun).status)) { finished.current?.(current); return; }
      await new Promise((r) => setTimeout(r, 1000));
    }
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setBusy(true);
    setLog([]);
    try {
      const input: Record<string, unknown> = {};
      for (const i of inputs) {
        const v = values[i.name];
        if (v === undefined || v === "") continue;
        input[i.name] = i.type === "number" ? Number(v) : v;
      }
      await askNotificationPermissionOnce();
      const r = await start(input, crypto.randomUUID());
      setRun(r);
      follow(r.id);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const cancel = async () => {
    if (!run) return;
    try { setRun(await api.post<ToolRun>(`/api/tool-runs/${run.id}/cancel`)); } catch (err) { setError(errorMessage(err)); }
  };

  const active = !!run && !TERMINAL.includes(run.status);
  return (
    <div className="tool-runner">
      <form className="stack" onSubmit={submit} aria-describedby={error ? "tool-error" : undefined}>
        {inputs.length === 0 && <p className="muted">Bu araç girdi istemiyor.</p>}
        {inputs.map((i) => i.type === "boolean"
          ? <label key={i.name} className="choice"><input type="checkbox" checked={!!values[i.name]} disabled={active}
              onChange={(e) => setValues({ ...values, [i.name]: e.target.checked })} /> {i.label}</label>
          : <TextField key={i.name} label={i.label + (i.required ? "" : " (isteğe bağlı)")} required={i.required} disabled={active}
              type={i.type === "number" ? "number" : "text"} maxLength={i.type === "string" ? i.maxLength : undefined}
              value={(values[i.name] as string) ?? ""} onChange={(e) => setValues({ ...values, [i.name]: e.target.value })} />)}
        <div className="row">
          <button className="btn btn-primary" type="submit" disabled={busy || active}>{run && !active ? "Yeniden çalıştır" : "Çalıştır"}</button>
          {active && <button className="btn" type="button" onClick={cancel}>İptal et</button>}
        </div>
      </form>
      {error && <div id="tool-error"><Alert kind="error"><p>{error}</p></Alert></div>}
      {run && (
        <section className="run-status" aria-label="Çalıştırma durumu">
          <p role="status" aria-live="polite" className={`run-state run-${run.status.toLowerCase()}`}>{statusText(run)}</p>
          {log.length > 0 && (
            <ol className="run-log">
              {log.map((l) => <li key={l.id}><span className="muted">{l.at}</span> {l.label}</li>)}
            </ol>
          )}
          {run.status === "SUCCEEDED" && run.output !== null && run.output !== undefined && (
            <>
              <h3 className="h-sub">Sonuç</h3>
              <pre className="run-output" tabIndex={0}>{typeof run.output === "string" ? run.output : JSON.stringify(run.output, null, 2)}</pre>
            </>
          )}
        </section>
      )}
    </div>
  );
}
