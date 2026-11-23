import { useCallback, useEffect, useState } from "react";
import { Alert, StateView, errorMessage, fmt } from "@basamak/ui";
import { api, useAuth } from "../auth";

type TrainingRow = { trainingId: string; title: string; versionNo: number; enrolled: number; completed: number; completionRate: number | null; avgCompletionMinutes: number | null };
type QuestionStat = { questionId: string; text: string; asked: number; correct: number; correctRate: number | null };
type Quiz = { key: string; title: string; attempts: number; passed: number; passRate: number | null; avgScore: number | null; questions: QuestionStat[] };
type Dist = { value: string; label: string; count: number; rate: number | null };
type SurveyQ = { questionId: string; text: string; type: string; answered: number; distribution?: Dist[]; latestAnswers?: string[] };
type Survey = { key: string; title: string; anonymous: boolean; responses: number; questions: SurveyQ[] };

const pct = (v: number | null) => (v === null ? "—" : `%${fmt.num(v)}`);

/** Scenario 13: completion rates, per-question correctness, survey distributions; every table downloadable as CSV. */
export function ReportsPage() {
  const { can } = useAuth();
  const [rows, setRows] = useState<TrainingRow[] | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [detail, setDetail] = useState<{ quizzes: Quiz[]; surveys: Survey[] } | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try { setRows(await api.get<TrainingRow[]>("/api/cms/reports/trainings")); } catch (e) { setError(errorMessage(e)); }
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
        <button className="btn" onClick={() => csv("/api/cms/reports/trainings.csv", "egitim-tamamlama.csv")}>Tamamlama oranları (CSV)</button>
      </div>
      <p className="muted">Silinen hesaplar sayılara anonim olarak dahildir. Oranlar yayındaki eğitimler içindir.</p>
      {error && <Alert kind="error"><p>{error}</p></Alert>}
      <StateView loading={!rows && !error} error={null} empty={rows?.length === 0} emptyText="Yayında eğitim yok.">
        <div className="table-wrap card">
          <table>
            <caption className="visually-hidden">Eğitim tamamlama oranları</caption>
            <thead><tr><th scope="col">Eğitim</th><th scope="col">Başlayan</th><th scope="col">Tamamlayan</th><th scope="col">Oran</th><th scope="col">Ort. süre</th><th scope="col"><span className="visually-hidden">Ayrıntı</span></th></tr></thead>
            <tbody>
              {rows?.map((r) => (
                <tr key={r.trainingId} aria-selected={r.trainingId === selected}>
                  <th scope="row">{r.title} <span className="muted">s{r.versionNo}</span></th>
                  <td>{fmt.num(r.enrolled)}</td><td>{fmt.num(r.completed)}</td>
                  <td><Bar value={r.completionRate} label={pct(r.completionRate)} /></td>
                  <td>{r.avgCompletionMinutes === null ? "—" : `${fmt.num(r.avgCompletionMinutes)} dk`}</td>
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
                      <thead><tr><th scope="col">Soru</th><th scope="col">Soruldu</th><th scope="col">Doğru</th><th scope="col">Doğru oranı</th></tr></thead>
                      <tbody>
                        {q.questions.map((s) => (
                          <tr key={s.questionId}>
                            <th scope="row">{s.text}</th><td>{fmt.num(s.asked)}</td><td>{fmt.num(s.correct)}</td>
                            <td><Bar value={s.correctRate} label={pct(s.correctRate)} /></td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  <p className="hint-line">Açık uçlu sorular otomatik puanlanmadığı için bu tabloda yoktur.</p>
                </div>
              ))}
              {detail.surveys.map((s) => (
                <div key={s.key} className="card stack">
                  <h3>{s.title} <span className="muted">({fmt.num(s.responses)} cevap{s.anonymous ? ", anonim" : ""})</span></h3>
                  {s.questions.map((q) => (
                    <div key={q.questionId} className="stack">
                      <h4>{q.text} <span className="muted">· {fmt.num(q.answered)} cevap</span></h4>
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
