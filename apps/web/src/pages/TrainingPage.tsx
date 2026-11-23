import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { Link, useParams } from "react-router-dom";
import { Alert, Markdown, StateView, errorMessage, fmt } from "@basamak/ui";
import { api } from "../auth";

type Item = {
  key: string; type: "lesson" | "quiz" | "survey" | "tool"; title: string; completed: boolean;
  content?: string; video?: { url: string; minSeconds?: number };
  passingScore?: number; attemptsAllowed?: number; timeLimitSeconds?: number; questionsPerAttempt?: number;
  attemptsUsed?: number; passed?: boolean; bestPercent?: number;
  anonymous?: boolean; questions?: Question[];
};
type Question = { id: string; type: string; text: string; options: { id: string; text: string }[] };
type Training = {
  id: string; title: string; summary: string; versionNo: number; latestVersionNo: number; newerVersionAvailable: boolean;
  items: Item[]; lockReason: string | null;
  enrollment: null | { status: string; versionNo: number; completedCount: number; total: number; currentKey: string };
};

const TYPE_LABEL: Record<Item["type"], string> = { lesson: "Ders", quiz: "Quiz", survey: "Anket", tool: "Araç" };

export function TrainingPage() {
  const { id } = useParams();
  const [t, setT] = useState<Training | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [current, setCurrent] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const data = await api.get<Training>(`/api/trainings/${id}`);
      setT(data);
      setCurrent((c) => c ?? (data.enrollment?.currentKey || data.items.find((i) => !i.completed)?.key || data.items[0]?.key || null));
    } catch (e) {
      setError(errorMessage(e));
    }
  }, [id]);
  useEffect(() => { load(); }, [load]);

  const act = async (fn: () => Promise<{ pointsAwarded?: number; trainingCompleted?: boolean; certificatesIssued?: string[] } | void>) => {
    try {
      const r = await fn();
      const parts: string[] = [];
      if (r && r.pointsAwarded) parts.push(`+${fmt.num(r.pointsAwarded)} puan kazandınız.`);
      if (r && r.trainingCompleted) parts.push("Eğitimi tamamladınız!");
      if (r && r.certificatesIssued?.length) parts.push("Journey'i bitirdiniz; sertifikanız ana sayfada.");
      setNotice(parts.join(" ") || null);
      await load();
    } catch (e) {
      setNotice(null);
      setError(errorMessage(e));
    }
  };

  const select = (key: string) => {
    setCurrent(key);
    setNotice(null);
    setTimeout(() => headingRef.current?.focus(), 0);
  };

  const item = t?.items.find((i) => i.key === current) ?? null;

  useEffect(() => {
    // record the view (one point once per lesson, server-side idempotent)
    if (t?.enrollment && item) api.post(`/api/trainings/${id}/items/${item.key}/view`).catch(() => {});
  }, [id, item?.key, t?.enrollment?.versionNo]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <main className="container stack">
      <p><Link to="/">← Ana sayfa</Link></p>
      <StateView loading={!t && !error} error={!t ? error : null} onRetry={load}>
        {t && (
          <>
            <h1>{t.title}</h1>
            <p className="muted">{t.summary}</p>
            {error && <Alert kind="error"><p>{error}</p></Alert>}
            {notice && <Alert kind="success"><p>{notice}</p></Alert>}
            {t.newerVersionAvailable && t.enrollment && (
              <Alert kind="warning">
                <p>Bu eğitimin yeni bir sürümü yayınlandı (s{t.latestVersionNo}). Siz s{t.versionNo} ile devam ediyorsunuz; isterseniz bu sürümde bitirebilirsiniz.</p>
                <button className="btn" onClick={() => act(() => api.post(`/api/trainings/${id}/switch-version`))}>
                  Yeni sürüme geç (tamamladığınız ortak bölümler korunur)
                </button>
              </Alert>
            )}
            {!t.enrollment ? (
              <div className="card stack">
                {t.lockReason ? <Alert kind="info"><p>{t.lockReason}</p></Alert> : null}
                <button className="btn btn-primary" disabled={!!t.lockReason} onClick={() => act(() => api.post(`/api/trainings/${id}/enroll`))}>
                  Eğitime başla
                </button>
                {t.lockReason && <span className="hint">Düğme, yukarıdaki koşul sağlanınca etkinleşir.</span>}
              </div>
            ) : (
              <div className="layout-2">
                <nav aria-label="Eğitim içeriği" className="card">
                  <p className="muted" style={{ marginTop: 0 }}>{t.enrollment.completedCount}/{t.enrollment.total} tamamlandı{t.enrollment.status === "COMPLETED" ? " ✓" : ""}</p>
                  <ol className="list-plain">
                    {t.items.map((i) => (
                      <li key={i.key}>
                        <button className="btn item-btn" aria-current={i.key === current ? "step" : undefined} onClick={() => select(i.key)}>
                          <span aria-hidden="true">{i.completed ? "✓" : "○"}</span>
                          <span>{i.title} <span className="muted">· {TYPE_LABEL[i.type]}</span></span>
                          <span className="visually-hidden">{i.completed ? "(tamamlandı)" : "(tamamlanmadı)"}</span>
                        </button>
                      </li>
                    ))}
                  </ol>
                </nav>
                <section className="card stack" aria-labelledby="item-title">
                  {item && (
                    <>
                      <h2 id="item-title" tabIndex={-1} ref={headingRef}>{item.title}</h2>
                      {item.type === "lesson" && <LessonView trainingId={id!} item={item} onComplete={() => act(() => api.post(`/api/trainings/${id}/items/${item.key}/complete`))} />}
                      {item.type === "quiz" && <QuizView trainingId={id!} item={item} onDone={(r) => act(async () => r)} />}
                      {item.type === "survey" && <SurveyView item={item} onSubmit={(answers) => act(() => api.post(`/api/trainings/${id}/items/${item.key}/survey`, { answers }))} />}
                      {item.type === "tool" && <p className="muted">Bu öge bir araç çalıştırır. Araç çalıştırma ekranı bir sonraki fazda eklenecek.</p>}
                    </>
                  )}
                </section>
              </div>
            )}
          </>
        )}
      </StateView>
    </main>
  );
}

function LessonView({ trainingId, item, onComplete }: { trainingId: string; item: Item; onComplete: () => void }) {
  const lastSent = useRef(0);
  const beat = (pos: number) => {
    if (Math.abs(pos - lastSent.current) < 5) return;
    lastSent.current = pos;
    api.post(`/api/trainings/${trainingId}/items/${item.key}/progress`, { positionSeconds: Math.floor(pos) }).catch(() => {});
  };
  return (
    <>
      {item.video && (
        <figure style={{ margin: 0 }}>
          <video controls preload="metadata" src={item.video.url} style={{ width: "100%" }}
                 onTimeUpdate={(e) => beat(e.currentTarget.currentTime)} />
          {item.video.minSeconds ? <figcaption className="hint">Dersi tamamlamak için en az {item.video.minSeconds} saniye izleyin. İleri sarılan süre sayılmaz.</figcaption> : null}
        </figure>
      )}
      {item.content && <Markdown source={item.content} />}
      {item.completed ? <p className="badge badge-success">✓ Bu dersi tamamladınız</p>
        : <button className="btn btn-primary" onClick={onComplete}>Dersi tamamladım</button>}
    </>
  );
}

type Attempt = { attemptId: string; attemptNo: number; attemptsAllowed: number; deadlineAt: string | null; questions: Question[] };
type QuizResult = { percent: number; passingScore: number; passed: boolean; attemptsLeft: number; late: boolean; pointsAwarded?: number; trainingCompleted?: boolean;
  results: { id: string; correct?: boolean; status?: string; correctOptionIds?: string[] }[] };

function QuizView({ trainingId, item, onDone }: { trainingId: string; item: Item; onDone: (r: QuizResult) => void }) {
  const [attempt, setAttempt] = useState<Attempt | null>(null);
  const [answers, setAnswers] = useState<Record<string, string[] | string>>({});
  const [result, setResult] = useState<QuizResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [left, setLeft] = useState<number | null>(null);

  useEffect(() => { setAttempt(null); setResult(null); setAnswers({}); setError(null); }, [item.key]);
  useEffect(() => {
    if (!attempt?.deadlineAt) { setLeft(null); return; }
    const tick = () => setLeft(Math.max(0, Math.round((new Date(attempt.deadlineAt!).getTime() - Date.now()) / 1000)));
    tick();
    const h = setInterval(tick, 1000);
    return () => clearInterval(h);
  }, [attempt]);

  const start = async () => {
    setError(null); setResult(null); setAnswers({});
    try { setAttempt(await api.post<Attempt>(`/api/trainings/${trainingId}/items/${item.key}/attempts`)); }
    catch (e) { setError(errorMessage(e)); }
  };
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!attempt) return;
    try {
      const r = await api.post<QuizResult>(`/api/quiz-attempts/${attempt.attemptId}/submit`, { answers });
      setResult(r);
      setAttempt(null);
      onDone(r);
    } catch (err) { setError(errorMessage(err)); }
  };
  const toggle = (q: Question, opt: string) => {
    if (q.type === "single") setAnswers({ ...answers, [q.id]: [opt] });
    else {
      const cur = (answers[q.id] as string[] | undefined) ?? [];
      setAnswers({ ...answers, [q.id]: cur.includes(opt) ? cur.filter((x) => x !== opt) : [...cur, opt] });
    }
  };
  const used = item.attemptsUsed ?? 0;
  const allowed = item.attemptsAllowed ?? 1;

  return (
    <div className="stack">
      <p className="muted">Geçme notu %{item.passingScore} · {item.questionsPerAttempt} soru · deneme hakkı {used}/{allowed}
        {item.timeLimitSeconds ? ` · süre ${Math.round(item.timeLimitSeconds / 60)} dk` : ""}</p>
      {error && <Alert kind="error"><p>{error}</p></Alert>}
      {item.completed && <p className="badge badge-success">✓ Bu quiz'i geçtiniz{item.bestPercent != null ? ` (%${item.bestPercent})` : ""}</p>}
      {result && (
        <Alert kind={result.passed ? "success" : "warning"}>
          <p><strong>{result.passed ? "Tebrikler, geçtiniz!" : "Bu sefer olmadı."}</strong> Puanınız %{result.percent} (geçme notu %{result.passingScore}).</p>
          {result.late && <p>Süre dolduktan sonra gönderildiği için deneme geçersiz sayıldı.</p>}
          {!result.passed && <p>Kalan deneme hakkı: {result.attemptsLeft}</p>}
          {result.results.some((x) => x.status === "pending_review") && <p>Açık uçlu cevaplarınız içerik ekibi tarafından değerlendirilecek.</p>}
        </Alert>
      )}
      {!attempt && !item.completed && (
        <button className="btn btn-primary" disabled={used >= allowed} onClick={start}>
          {used === 0 ? "Quiz'e başla" : "Yeniden dene"}
        </button>
      )}
      {!attempt && !item.completed && used >= allowed && <span className="hint">Deneme hakkınız kalmadı.</span>}
      {attempt && (
        <form onSubmit={submit} className="stack">
          {left !== null && (
            <p role="timer" aria-live={left <= 30 ? "assertive" : "off"} className={left <= 30 ? "alert alert-warning" : "muted"}>
              Kalan süre: {Math.floor(left / 60)}:{String(left % 60).padStart(2, "0")}
            </p>
          )}
          {attempt.questions.map((q, n) => (
            <fieldset key={q.id}>
              <legend>{n + 1}. {q.text} {q.type === "multi" && <span className="muted">(birden çok seçilebilir)</span>}</legend>
              {q.type === "open" ? (
                <textarea aria-label={q.text} rows={3} style={{ width: "100%" }} onChange={(e) => setAnswers({ ...answers, [q.id]: e.target.value })} />
              ) : q.options.map((o) => (
                <label key={o.id} className="choice">
                  <input type={q.type === "single" ? "radio" : "checkbox"} name={q.id}
                         checked={((answers[q.id] as string[] | undefined) ?? []).includes(o.id)} onChange={() => toggle(q, o.id)} />
                  <span>{o.text}</span>
                </label>
              ))}
              {result && null}
            </fieldset>
          ))}
          <button className="btn btn-primary">Cevapları gönder</button>
        </form>
      )}
    </div>
  );
}

function SurveyView({ item, onSubmit }: { item: Item; onSubmit: (a: Record<string, unknown>) => void }) {
  const [answers, setAnswers] = useState<Record<string, unknown>>({});
  if (item.completed) return <p className="badge badge-success">✓ Anketi doldurdunuz, teşekkürler.</p>;
  return (
    <form className="stack" onSubmit={(e) => { e.preventDefault(); onSubmit(answers); }}>
      {item.anonymous && <Alert kind="info"><p>Bu anket anonimdir: cevaplarınız adınızla eşleştirilmeden saklanır.</p></Alert>}
      {item.questions?.map((q, n) => (
        <fieldset key={q.id}>
          <legend>{n + 1}. {q.text}</legend>
          {q.type === "scale" && (
            <div className="row" role="radiogroup" aria-label={q.text}>
              {[1, 2, 3, 4, 5].map((v) => (
                <label key={v} className="choice"><input type="radio" name={q.id} checked={answers[q.id] === v} onChange={() => setAnswers({ ...answers, [q.id]: v })} /> {v}</label>
              ))}
            </div>
          )}
          {(q.type === "single" || q.type === "multi") && q.options.map((o) => (
            <label key={o.id} className="choice">
              <input type={q.type === "single" ? "radio" : "checkbox"} name={q.id}
                     onChange={(e) => {
                       if (q.type === "single") setAnswers({ ...answers, [q.id]: o.id });
                       else {
                         const cur = (answers[q.id] as string[] | undefined) ?? [];
                         setAnswers({ ...answers, [q.id]: e.target.checked ? [...cur, o.id] : cur.filter((x) => x !== o.id) });
                       }
                     }} />
              <span>{o.text}</span>
            </label>
          ))}
          {q.type === "open" && <textarea aria-label={q.text} rows={3} style={{ width: "100%" }} onChange={(e) => setAnswers({ ...answers, [q.id]: e.target.value })} />}
        </fieldset>
      ))}
      <button className="btn btn-primary">Anketi gönder</button>
    </form>
  );
}
