import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Alert, SelectField, TextField, errorMessage, fmt } from "@basamak/ui";
import { api } from "./auth";
import type { Json, Version } from "./content";

type Secret = { name: string; updatedAt: string };
type TestResult = {
  versionNo: number; outcome: string; httpStatus: number | null; durationMs: number; error: string | null;
  request: { method?: string; url?: string; headers?: Record<string, string>; body?: Json }; output: Json; missingSecrets: string[];
};

const OUTCOME: Record<string, string> = {
  SUCCEEDED: "Başarılı", HTTP_ERROR: "Dış servis hata döndü", RETRYABLE: "Geçici hata (gerçek çalıştırmada yeniden denenir)",
  TIMED_OUT: "Zaman aşımı", BLOCKED: "Engellendi (güvenlik)", FAILED: "Başarısız", CANCELLED: "İptal edildi",
};

/** Secrets (write-only) + test console of a CMS tool. Tests run a SAVED version, one attempt, same rules as members. */
export function ToolConsole({ itemId, versions, canWrite, dirty }: { itemId: string; versions: Version[]; canWrite: boolean; dirty: boolean }) {
  const [secrets, setSecrets] = useState<Secret[]>([]);
  const [name, setName] = useState("apiKey");
  const [value, setValue] = useState("");
  const [versionNo, setVersionNo] = useState(versions[0]?.versionNo ?? 1);
  const [input, setInput] = useState<Record<string, string>>({});
  const [result, setResult] = useState<TestResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [running, setRunning] = useState(false);

  const load = useCallback(async () => {
    try { setSecrets(await api.get<Secret[]>(`/api/cms/tools/${itemId}/secrets`)); } catch (e) { setError(errorMessage(e)); }
  }, [itemId]);
  useEffect(() => { load(); }, [load]);

  const version = versions.find((v) => v.versionNo === versionNo) ?? versions[0];
  const inputs: Json[] = version?.body?.inputs ?? [];

  const saveSecret = async (e: FormEvent) => {
    e.preventDefault();
    setError(null); setNotice(null);
    try {
      setSecrets(await api.put<Secret[]>(`/api/cms/tools/${itemId}/secrets/${encodeURIComponent(name)}`, { value }));
      setValue("");
      setNotice(`“${name}” sırrı şifrelenerek kaydedildi. Değer bir daha gösterilmez.`);
    } catch (err) { setError(errorMessage(err)); }
  };

  const test = async (e: FormEvent) => {
    e.preventDefault();
    setError(null); setResult(null); setRunning(true);
    try {
      const payload: Record<string, Json> = {};
      for (const i of inputs) if (input[i.name] !== undefined && input[i.name] !== "") payload[i.name] = i.type === "number" ? Number(input[i.name]) : input[i.name];
      setResult(await api.post<TestResult>(`/api/cms/tools/${itemId}/test`, { versionNo, input: payload }));
    } catch (err) { setError(errorMessage(err)); }
    finally { setRunning(false); }
  };

  return (
    <section className="stack" aria-label="Sırlar ve deneme konsolu">
      {error && <Alert kind="error"><p>{error}</p></Alert>}
      {notice && <Alert kind="success"><p>{notice}</p></Alert>}

      <form className="card stack" onSubmit={saveSecret}>
        <h2>Sırlar</h2>
        <p className="muted">API anahtarları sürüm geçmişine, farka ve yayına girmez; AES-256-GCM ile şifrelenir ve yalnız istek anında çözülür.</p>
        {secrets.length === 0 ? <p className="muted">Kayıtlı sır yok.</p> : (
          <ul>
            {secrets.map((s) => (
              <li key={s.name} className="row">
                <code>{s.name}</code> <span className="muted">•••• · {fmt.dateTime(s.updatedAt)}</span>
                {canWrite && <button type="button" className="btn btn-danger" onClick={async () => {
                  try { setSecrets(await api.del<Secret[]>(`/api/cms/tools/${itemId}/secrets/${encodeURIComponent(s.name)}`)); } catch (err) { setError(errorMessage(err)); }
                }}>Sil</button>}
              </li>
            ))}
          </ul>
        )}
        {canWrite && (
          <div className="row">
            <TextField label="Sır adı" value={name} onChange={(e) => setName(e.target.value)} hint="Tanımda {{secret.ad}} olarak kullanılır." />
            <TextField label="Değer" type="password" autoComplete="off" required value={value} onChange={(e) => setValue(e.target.value)} />
            <button className="btn btn-primary" type="submit" disabled={!value}>Kaydet</button>
          </div>
        )}
      </form>

      <form className="card stack" onSubmit={test}>
        <h2>Deneme konsolu</h2>
        {dirty && <Alert kind="warning"><p>Kaydedilmemiş değişiklikler denenmez; konsol kaydedilmiş bir sürümü çalıştırır.</p></Alert>}
        <SelectField label="Sürüm" value={versionNo} onChange={(e) => setVersionNo(Number(e.target.value))}>
          {versions.map((v) => <option key={v.id} value={v.versionNo}>s{v.versionNo} · {v.status}</option>)}
        </SelectField>
        {inputs.map((i) => (
          <TextField key={i.name} label={(i.label || i.name) + (i.required ? "" : " (isteğe bağlı)")} type={i.type === "number" ? "number" : "text"}
                     value={input[i.name] ?? ""} onChange={(e) => setInput({ ...input, [i.name]: e.target.value })} />
        ))}
        <div><button className="btn btn-primary" type="submit" disabled={running}>{running ? "Çalışıyor…" : "Dene"}</button></div>
        {result && (
          <div className="stack" role="status" aria-live="polite">
            <p><strong>{OUTCOME[result.outcome] ?? result.outcome}</strong>
              {result.httpStatus !== null && <> · HTTP {result.httpStatus}</>} · {fmt.num(result.durationMs)} ms · s{result.versionNo}</p>
            {result.error && <Alert kind={result.outcome === "BLOCKED" ? "warning" : "error"}><p>{result.error}</p></Alert>}
            {result.missingSecrets.length > 0 && <Alert kind="warning"><p>Eksik sır: {result.missingSecrets.join(", ")}</p></Alert>}
            <details open><summary>Gönderilen istek (sırlar gizli)</summary><pre>{JSON.stringify(result.request, null, 2)}</pre></details>
            <details open><summary>Cevap</summary><pre>{typeof result.output === "string" ? result.output : JSON.stringify(result.output, null, 2)}</pre></details>
          </div>
        )}
      </form>
    </section>
  );
}
