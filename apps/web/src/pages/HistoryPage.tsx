import { useCallback, useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { SelectField, StateView, TextField, errorMessage, fmt } from "@basamak/ui";
import { api } from "../auth";

type Item = { id: number; type: string; objectType: string; objectId: string; occurredAt: string; source: string; points: number; objectTitle: string | null; itemTitle: string | null };
type Page = { items: Item[]; page: number; size: number; total: number };

const LABEL: Record<string, string> = {
  LESSON_VIEWED: "Dersi açtın", LESSON_COMPLETED: "Dersi tamamladın", TRAINING_STARTED: "Eğitime başladın", TRAINING_COMPLETED: "Eğitimi tamamladın",
  QUIZ_PASSED: "Quiz'i geçtin", QUIZ_FAILED: "Quiz'i geçemedin", QUIZ_SUBMITTED: "Quiz'i gönderdin", SURVEY_SUBMITTED: "Anketi doldurdun",
  JOURNEY_COMPLETED: "Journey'i tamamladın", CERTIFICATE_ISSUED: "Sertifika aldın",
  TOOL_CREATED: "Araç oluşturdun", TOOL_RUN_SUCCEEDED: "Aracı çalıştırdın", WORKFLOW_CREATED: "İş akışı oluşturdun", WORKFLOW_RUN_SUCCEEDED: "İş akışını çalıştırdın",
  ITEM_SHARED: "Paylaştın", SHARED_ITEM_FORKED: "Paylaştığın akış çatallandı", SHARED_ITEM_USED: "Paylaştığın akış kullanıldı",
};
const CATEGORIES: [string, string][] = [["", "Hepsi"], ["learning", "Öğrenme"], ["tools", "Araçlar"], ["workflows", "İş akışları"], ["community", "Topluluk"]];
const SOURCE: Record<string, string> = { web: "web", mobile: "mobil", system: "sistem" };

/** "Geçmişim": everything I did, filterable by kind and date; filters live in the URL (shareable, back button works). */
export function HistoryPage() {
  const [params, setParams] = useSearchParams();
  const category = params.get("category") ?? "";
  const from = params.get("from") ?? "";
  const to = params.get("to") ?? "";
  const page = Number(params.get("page") ?? "0");
  const [data, setData] = useState<Page | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    const q = new URLSearchParams({ page: String(page), size: "20" });
    if (category) q.set("category", category);
    if (from) q.set("from", from);
    if (to) q.set("to", to);
    try { setData(await api.get<Page>(`/api/me/history?${q}`)); } catch (e) { setError(errorMessage(e)); }
  }, [category, from, to, page]);
  useEffect(() => { load(); }, [load]);

  const set = (k: string, v: string) => {
    const n = new URLSearchParams(params);
    if (v) n.set(k, v); else n.delete(k);
    if (k !== "page") n.delete("page");
    setParams(n);
  };
  const pages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1;

  return (
    <main className="page stack">
      <h1>Geçmişim</h1>
      <form className="grid-4 card" role="search" aria-label="Geçmişi süz" onSubmit={(e) => e.preventDefault()}>
        <SelectField label="Tür" value={category} onChange={(e) => set("category", e.target.value)}>
          {CATEGORIES.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
        </SelectField>
        <TextField label="Başlangıç" type="date" value={from} max={to || undefined} onChange={(e) => set("from", e.target.value)} />
        <TextField label="Bitiş" type="date" value={to} min={from || undefined} onChange={(e) => set("to", e.target.value)} />
        <div><button type="button" className="btn" onClick={() => setParams(new URLSearchParams())} disabled={!category && !from && !to}>Süzgeçleri temizle</button></div>
      </form>
      <StateView loading={!data && !error} error={error} onRetry={load} empty={data?.total === 0}
        emptyText={category || from || to ? "Bu süzgeçlere uyan kayıt yok." : "Henüz bir etkinliğin yok. Bir eğitime başlayınca burada görünür."}>
        <p className="hint-line" aria-live="polite">{data && `${fmt.num(data.total)} kayıt · sayfa ${data.page + 1}/${pages}`}</p>
        <ol className="card-list" aria-label="Etkinlikler">
          {data?.items.map((i) => (
            <li key={i.id} className="card row space-between">
              <div>
                <strong>{LABEL[i.type] ?? i.type}</strong>
                <div>{[i.objectTitle, i.itemTitle].filter(Boolean).join(" · ") || <span className="muted">(silinmiş ya da yayından kalkmış içerik)</span>}</div>
                <div className="hint-line">{fmt.dateTime(i.occurredAt)} · {SOURCE[i.source] ?? i.source}</div>
              </div>
              {i.points !== 0 && <strong className={i.points > 0 ? "run-succeeded" : "run-failed"}>{i.points > 0 ? "+" : ""}{fmt.num(i.points)} puan</strong>}
            </li>
          ))}
        </ol>
        <nav className="pager row" aria-label="Sayfalar">
          <button className="btn" disabled={page <= 0} onClick={() => set("page", String(page - 1))}>← Önceki</button>
          <button className="btn" disabled={page + 1 >= pages} onClick={() => set("page", String(page + 1))}>Sonraki →</button>
        </nav>
      </StateView>
    </main>
  );
}
