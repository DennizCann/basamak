import { useCallback, useEffect, useState } from "react";
import { Alert, StateView, errorMessage, fmt } from "@basamak/ui";
import { api, useAuth } from "../auth";

type VersionRow = { versionNo: number; enrolled: number; completed: number; completionRate: number | null };
type TrainingRow = { trainingId: string; title: string; versionNo: number; enrolled: number; completed: number; completionRate: number | null; avgCompletionMinutes: number | null;
  dropped: number; mostDroppedItem: { key: string; title: string; members: number } | null; versions: VersionRow[] };
type JourneyRow = { journeyId: string; title: string; trainings: number; started: number; completed: number; dropped: number; completionRate: number | null };
type UsageRow = { name: string; scope?: string; visibility?: string; runs: number; errors: number; errorRate: number | null; avgMs: number | null; aiCostUsd?: number; tokens?: number };
type QuestionStat = { questionId: string; text: string; asked: number; correct: number; correctRate: number | null; avgSeconds: number | null };
type Quiz = { key: string; title: string; attempts: number; passed: number; passRate: number | null; avgScore: number | null; questions: QuestionStat[] };
type Dist = { value: string; label: string; count: number; rate: number | null };
type SurveyQ = { questionId: string; text: string; type: string; answered: number; distribution?: Dist[]; latestAnswers?: string[]; hidden?: boolean; hiddenReason?: string };
type Survey = { key: string; title: string; anonymous: boolean; responses: number; questions: SurveyQ[] };

const pct = (v: number | null) => (v === null ? "—" : `%${fmt.num(v)}`);

/** Scenario 13: completion rates, per-question correctness, survey distributions; every table downloadable as CSV. */
export function ReportsPage() {
  const { can } = useAuth();
  const [rows, setRows] = useState<TrainingRow[] | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [detail, setDetail] = useState<{ quizzes: Quiz[]; surveys: Survey[] } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [journeys, setJourneys] = useState<JourneyRow[] | null>(null);
  const [usage, setUsage] = useState<{ tools: UsageRow[]; workflows: UsageRow[] } | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      setRows(await api.get<TrainingRow[]>("/api/cms/reports/trainings"));
      setJourneys(await api.get<JourneyRow[]>("/api/cms/reports/journeys"));
      setUsage(await api.get<{ tools: UsageRow[]; workflows: UsageRow[] }>("/api/cms/reports/usage"));
    } catch (e) { setError(errorMessage(e)); }
  }, []);
  useEffect(() => { if (can("EDITOR", "PUBLISHER")) load(); }, [load, can]);
  useEffect(() => {
    if (!selected) return;
    setDetail(null);
    api.get<{ quizzes: Quiz[]; surveys: Survey[] }>(`/api/cms/reports/trainings/${selected}`).then(setDetail).catch((e) => setError(errorMessage(e)));
  }, [selected]);

  const csv = async (path: string, name: string) => {
    try { await api.download(path, name); } catch (e) { setError(errorMessage(e)); }
  };

  if (!can("EDITOR", "PUBLISHER")) return <main className="container"><Alert kind="warning"><p>Raporları editör, yayıncı ya da yönetici görebilir.</p></Alert></main>;
  const current = rows?.find((r) => r.trainingId === selected);

  return (
    <main className="container stack">
      <div className="row" style={{ justifyContent: "space-between" }}>
        <h1>Raporlar</h1>
        <div className="row">
          <button className="btn" onClick={() => csv("/api/cms/reports/trainings.csv", "egitim-tamamlama.csv")}>Eğitimler (CSV)</button>
          <button className="btn" onClick={() => csv("/api/cms/reports/journeys.csv", "journey-raporu.csv")}>Journey'ler (CSV)</button>
          <button className="btn" onClick={() => csv("/api/cms/reports/versions.csv", "surum-karsilastirma.csv")}>Sürüm karşılaştırması (CSV)</button>
          <button className="btn" onClick={() => csv("/api/cms/reports/usage.csv", "arac-akis-kullanimi.csv")}>Araç ve akışlar (CSV)</button>
        </div>
      </div>
      <p className="muted">Silinen hesaplar sayılara anonim olarak dahildir. Oranlar yayındaki eğitimler içindir. "Bırakan": başlayıp bitirmeyen ve 14 gündür ilerlemeyen üye. Veriler canlı veritabanından okunur (gecikme yok).</p>
      {journeys && journeys.length > 0 && (
        <div className="table-wrap card">
          <table>
            <caption>Journey başına</caption>
            <thead><tr><th scope="col">Journey</th><th scope="col">Eğitim</th><th scope="col">Başlayan</th><th scope="col">Bitiren</th><th scope="col">Bırakan</th><th scope="col">Bitirme oranı</th></tr></thead>
            <tbody>{journeys.map((j) => (
              <tr key={j.journeyId}><th scope="row">{j.title}</th><td>{fmt.num(j.trainings)}</td><td>{fmt.num(j.started)}</td><td>{fmt.num(j.completed)}</td><td>{fmt.num(j.dropped)}</td>
                <td><Bar value={j.completionRate} label={pct(j.completionRate)} /></td></tr>
            ))}</tbody>
          </table>
        </div>
      )}
      {error && <Alert kind="error"><p>{error}</p></Alert>}
      <StateView loading={!rows && !error} error={null} empty={rows?.length === 0} emptyText="Yayında eğitim yok.">
        <div className="table-wrap card">
          <table>
            <caption className="visually-hidden">Eğitim tamamlama oranları</caption>
            <thead><tr><th scope="col">Eğitim</th><th scope="col">Başlayan</th><th scope="col">Tamamlayan</th><th scope="col">Bırakan</th><th scope="col">Oran</th><th scope="col">Ort. süre</th><th scope="col">En çok bırakılan</th><th scope="col"><span className="visually-hidden">Ayrıntı</span></th></tr></thead>
            <tbody>
              {rows?.map((r) => (
                <tr key={r.trainingId} aria-selected={r.trainingId === selected}>
                  <th scope="row">{r.title} <span className="muted">s{r.versionNo}</span></th>
                  <td>{fmt.num(r.enrolled)}</td><td>{fmt.num(r.completed)}</td><td>{fmt.num(r.dropped)}</td>
                  <td><Bar value={r.completionRate} label={pct(r.completionRate)} /></td>
                  <td>{r.avgCompletionMinutes === null ? "—" : `${fmt.num(r.avgCompletionMinutes)} dk`}</td>
                  <td>{r.mostDroppedItem ? `${r.mostDroppedItem.title} (${fmt.num(r.mostDroppedItem.members)})` : "—"}</td>
                  <td><button className="btn btn-sm" onClick={() => setSelected(r.trainingId)} aria-label={`${r.title} ayrıntıları`}>Ayrıntı</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </StateView>

      {current && (
        <section className="stack" aria-labelledby="detail-title">
          <div className="row" style={{ justifyContent: "space-between" }}>
            <h2 id="detail-title">{current.title}</h2>
            <div className="row">
              <button className="btn" onClick={() => csv(`/api/cms/reports/trainings/${current.trainingId}/questions.csv`, "soru-dogru-oranlari.csv")}>Sorular (CSV)</button>
              <button className="btn" onClick={() => csv(`/api/cms/reports/trainings/${current.trainingId}/surveys.csv`, "anket-dagilimi.csv")}>Anketler (CSV)</button>
            </div>
          </div>
          {current.versions.length > 0 && (
            <div className="table-wrap card">
              <table>
                <caption>Sürüm karşılaştırması (üye başladığı sürümü bitirir)</caption>
                <thead><tr><th scope="col">Sürüm</th><th scope="col">Başlayan</th><th scope="col">Tamamlayan</th><th scope="col">Tamamlama oranı</th></tr></thead>
                <tbody>{current.versions.map((v) => (
                  <tr key={v.versionNo}><th scope="row">s{v.versionNo}</th><td>{fmt.num(v.enrolled)}</td><td>{fmt.num(v.completed)}</td><td><Bar value={v.completionRate} label={pct(v.completionRate)} /></td></tr>
                ))}</tbody>
              </table>
            </div>
          )}
          {!detail ? <p role="status" className="muted">Yükleniyor…</p> : (
            <>
              {detail.quizzes.length === 0 && <p className="muted">Bu eğitimde gönderilmiş quiz denemesi yok.</p>}
              {detail.quizzes.map((q) => (
                <div key={q.key} className="card stack">
                  <h3>{q.title}</h3>
                  <p>{fmt.num(q.attempts)} deneme · geçme oranı {pct(q.passRate)} · ortalama puan {q.avgScore === null ? "—" : `%${q.avgScore}`}</p>
                  <div className="table-wrap">
                    <table>
                      <caption className="visually-hidden">{q.title}: soru başına doğru oranı</caption>
                      <thead><tr><th scope="col">Soru</th><th scope="col">Soruldu</th><th scope="col">Doğru</th><th scope="col">Doğru oranı</th><th scope="col">Ort. süre*</th></tr></thead>
                      <tbody>
                        {q.questions.map((s) => (
                          <tr key={s.questionId}>
                            <th scope="row">{s.text}</th><td>{fmt.num(s.asked)}</td><td>{fmt.num(s.correct)}</td>
                            <td><Bar value={s.correctRate} label={pct(s.correctRate)} /></td>
                            <td>{s.avgSeconds == null ? "—" : `${fmt.num(s.avgSeconds)} sn`}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  <p className="hint-line">Açık uçlu sorular otomatik puanlanmadığı için bu tabloda yoktur. *Soru başına süre ayrı ölçülmez: deneme süresi o denemedeki soru sayısına bölünerek yaklaşık hesaplanır.</p>
                </div>
              ))}
              {detail.surveys.map((s) => (
                <div key={s.key} className="card stack">
                  <h3>{s.title} <span className="muted">({fmt.num(s.responses)} cevap{s.anonymous ? ", anonim" : ""})</span></h3>
                  {s.questions.map((q) => (
                    <div key={q.questionId} className="stack">
                      <h4>{q.text} <span className="muted">· {fmt.num(q.answered)} cevap</span></h4>
                      {q.hidden && <p className="muted">{q.hiddenReason}</p>}
                      {q.distribution && (
                        <ul className="dist">
                          {q.distribution.map((d) => <li key={d.value}><span className="dist-label">{d.label}</span><Bar value={d.rate} label={`${fmt.num(d.count)} (${pct(d.rate)})`} /></li>)}
                        </ul>
                      )}
                      {q.latestAnswers && (q.latestAnswers.length === 0 ? <p className="muted">Açık uçlu cevap yok.</p> : (
                        <details><summary>Son {q.latestAnswers.length} açık uçlu cevap (isimsiz)</summary>
                          <ul>{q.latestAnswers.map((a, i) => <li key={i}>{a}</li>)}</ul></details>
                      ))}
                    </div>
                  ))}
                </div>
              ))}
            </>
          )}
        </section>
      )}

      {usage && (
        <section className="stack" aria-labelledby="usage-h">
          <h2 id="usage-h">Araç ve iş akışı kullanımı</h2>
          <div className="table-wrap card">
            <table>
              <caption>Araçlar (platform ve üye araçları)</caption>
              <thead><tr><th scope="col">Araç</th><th scope="col">Tür</th><th scope="col">Çalıştırma</th><th scope="col">Hata oranı</th><th scope="col">Ort. süre</th></tr></thead>
              <tbody>{usage.tools.length === 0 ? <tr><td colSpan={5} className="muted">Henüz çalıştırma yok.</td></tr> : usage.tools.map((t, i) => (
                <tr key={i}><th scope="row">{t.name}</th><td>{t.scope === "CONTENT" ? "Platform" : "Üye"}</td><td>{fmt.num(t.runs)}</td>
                  <td><Bar value={t.errorRate} label={pct(t.errorRate)} /></td><td>{t.avgMs == null ? "—" : `${fmt.num(t.avgMs)} ms`}</td></tr>
              ))}</tbody>
            </table>
          </div>
          <div className="table-wrap card">
            <table>
              <caption>İş akışları (yapay zekâ maliyeti sahte modelde benzetimdir)</caption>
              <thead><tr><th scope="col">İş akışı</th><th scope="col">Çalıştırma</th><th scope="col">Hata oranı</th><th scope="col">Ort. süre</th><th scope="col">Yapay zekâ maliyeti</th></tr></thead>
              <tbody>{usage.workflows.length === 0 ? <tr><td colSpan={5} className="muted">Henüz çalıştırma yok.</td></tr> : usage.workflows.map((w, i) => (
                <tr key={i}><th scope="row">{w.name}{w.visibility === "PUBLIC" ? " (paylaşılan)" : ""}</th><td>{fmt.num(w.runs)}</td>
                  <td><Bar value={w.errorRate} label={pct(w.errorRate)} /></td><td>{w.avgMs == null ? "—" : `${fmt.num(w.avgMs)} ms`}</td>
                  <td>{Number(w.aiCostUsd ?? 0).toLocaleString("tr-TR", { maximumFractionDigits: 6 })} USD</td></tr>
              ))}</tbody>
            </table>
          </div>
        </section>
      )}
    </main>
  );
}

function Bar({ value, label }: { value: number | null; label: string }) {
  return (
    <span className="bar-cell">
      <span className="bar" aria-hidden="true"><span style={{ width: `${Math.min(100, value ?? 0)}%` }} /></span>
      <span>{label}</span>
    </span>
  );
}
