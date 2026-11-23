import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import {
  Alert, ITEM_TYPE_LABEL, LessonBody, QuestionForm, QuizResult, QuizReview, StateView, SurveyForm,
  attemptsText, errorMessage, fmt, type Answers, type PlayerItem, type Question, type QuizResultData, type ReviewAttempt,
} from "@basamak/ui";
import { api } from "../auth";

type Training = {
  id: string; title: string; summary: string; versionNo: number; latestVersionNo: number; newerVersionAvailable: boolean;
  items: PlayerItem[]; lockReason: string | null;
  enrollment: null | { status: string; versionNo: number; completedCount: number; total: number; currentKey: string };
};
type Outcome = { pointsAwarded?: number; trainingCompleted?: boolean; certificatesIssued?: string[] };

export function TrainingPage() {
  const { id } = useParams();
  const [params, setParams] = useSearchParams();
  const [t, setT] = useState<Training | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const requested = params.get("item");

  const load = useCallback(async () => {
    setError(null);
    try { setT(await api.get<Training>(`/api/trainings/${id}`)); }
    catch (e) { setError(errorMessage(e)); }
  }, [id]);
  useEffect(() => { load(); }, [load]);

  // The URL (?item=key) is the source of truth for the selected item: back/forward, reload and deep links work.
  const fallbackKey = t ? (t.enrollment?.currentKey || t.items.find((i) => !i.completed)?.key || t.items[0]?.key || null) : null;
  const item = t?.items.find((i) => i.key === (requested ?? fallbackKey)) ?? null;
  const unknownItem = !!t && !!requested && !t.items.some((i) => i.key === requested);

  useEffect(() => {
    // first visit without ?item: reflect the default selection without adding a history entry
    if (t && !requested && fallbackKey) setParams({ item: fallbackKey }, { replace: true });
  }, [t, requested, fallbackKey, setParams]);

  const select = (key: string) => {
    if (key !== requested) setParams({ item: key }); // one history entry per real change
    setNotice(null);
    setTimeout(() => headingRef.current?.focus(), 0);
  };

  useEffect(() => {
    // record the view (points once per lesson, server-side idempotent)
    if (t?.enrollment && item) api.post(`/api/trainings/${id}/items/${item.key}/view`).catch(() => {});
  }, [id, item?.key, t?.enrollment?.versionNo]); // eslint-disable-line react-hooks/exhaustive-deps

  const act = async (fn: () => Promise<Outcome | void>) => {
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
                <h2>İçerik</h2>
                <ol>{t.items.map((i) => <li key={i.key}>{i.title} <span className="muted">· {ITEM_TYPE_LABEL[i.type]}</span></li>)}</ol>
                <button className="btn btn-primary" disabled={!!t.lockReason} onClick={() => act(() => api.post(`/api/trainings/${id}/enroll`))}>
                  Eğitime başla
                </button>
                {t.lockReason ? <span className="hint">Düğme, yukarıdaki koşul sağlanınca etkinleşir.</span>
                  : <span className="hint">İçeriği görmek için eğitime başlayın.</span>}
              </div>
            ) : (
              <div className="layout-2">
                <nav aria-label="Eğitim içeriği" className="card">
                  <p className="muted" style={{ marginTop: 0 }}>{t.enrollment.completedCount}/{t.enrollment.total} tamamlandı{t.enrollment.status === "COMPLETED" ? " ✓" : ""}</p>
                  <ol className="list-plain">
                    {t.items.map((i) => (
                      <li key={i.key}>
                        <button className="btn item-btn" aria-current={i.key === item?.key ? "step" : undefined} onClick={() => select(i.key)}>
                          <span aria-hidden="true">{i.completed ? "✓" : "○"}</span>
                          <span>{i.title} <span className="muted">· {ITEM_TYPE_LABEL[i.type]}</span></span>
                          <span className="visually-hidden">{i.completed ? "(tamamlandı)" : "(tamamlanmadı)"}</span>
                        </button>
                      </li>
                    ))}
                  </ol>
                </nav>
                <section className="card stack" aria-labelledby="item-title">
                  {unknownItem && <Alert kind="warning"><p>Bağlantıdaki içerik bu eğitimin sizin sürümünüzde yok (kaldırılmış olabilir). Soldan bir içerik seçin.</p></Alert>}
                  {item && !unknownItem && (
                    <>
                      <h2 id="item-title" tabIndex={-1} ref={headingRef}>{item.title}</h2>
                      {item.type === "lesson" && <Lesson trainingId={id!} item={item} onComplete={() => act(() => api.post(`/api/trainings/${id}/items/${item.key}/complete`))} />}
                      {item.type === "quiz" && <Quiz trainingId={id!} item={item} onDone={(r) => act(async () => r)} />}
                      {item.type === "survey" && (item.completed
                        ? <p className="badge badge-success">✓ Anketi doldurdunuz, teşekkürler.</p>
                        : <SurveyForm item={item} onSubmit={(answers) => act(() => api.post(`/api/trainings/${id}/items/${item.key}/survey`, { answers }))} />)}
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

function Lesson({ trainingId, item, onComplete }: { trainingId: string; item: PlayerItem; onComplete: () => void }) {
  const lastSent = useRef(0);
  const beat = (pos: number) => {
    if (Math.abs(pos - lastSent.current) < 5) return;
    lastSent.current = pos;
    api.post(`/api/trainings/${trainingId}/items/${item.key}/progress`, { positionSeconds: Math.floor(pos) }).catch(() => {});
  };
  return (
    <>
      <LessonBody item={item} onTime={beat} />
      {item.completed ? <p className="badge badge-success">✓ Bu dersi tamamladınız</p>
        : <button className="btn btn-primary" onClick={onComplete}>Dersi tamamladım</button>}
    </>
  );
}

type Attempt = { attemptId: string; attemptNo: number; attemptsAllowed: number; deadlineAt: string | null; questions: Question[] };

function Quiz({ trainingId, item, onDone }: { trainingId: string; item: PlayerItem; onDone: (r: QuizResultData & Outcome) => void }) {
  const [attempt, setAttempt] = useState<Attempt | null>(null);
  const [result, setResult] = useState<QuizResultData | null>(null);
  const [review, setReview] = useState<ReviewAttempt[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [left, setLeft] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => { setAttempt(null); setResult(null); setReview(null); setError(null); }, [item.key]);
  useEffect(() => {
    if (!attempt?.deadlineAt) { setLeft(null); return; }
    const tick = () => setLeft(Math.max(0, Math.round((new Date(attempt.deadlineAt!).getTime() - Date.now()) / 1000)));
    tick();
    const h = setInterval(tick, 1000);
    return () => clearInterval(h);
  }, [attempt]);

  const start = async () => {
    setError(null); setResult(null); setReview(null);
    try { setAttempt(await api.post<Attempt>(`/api/trainings/${trainingId}/items/${item.key}/attempts`)); }
    catch (e) { setError(errorMessage(e)); }
  };
  const submit = async (answers: Answers) => {
    if (!attempt || busy) return;
    setBusy(true); // a double click cannot submit twice; the server also rejects a second submit
    try {
      const r = await api.post<QuizResultData & Outcome>(`/api/quiz-attempts/${attempt.attemptId}/submit`, { answers });
      setResult(r);
      setAttempt(null);
      onDone(r);
    } catch (err) { setError(errorMessage(err)); }
    finally { setBusy(false); }
  };
  const loadReview = async () => {
    try { setReview(await api.get<ReviewAttempt[]>(`/api/trainings/${trainingId}/items/${item.key}/attempts`)); }
    catch (e) { setError(errorMessage(e)); }
  };
  const used = item.attemptsUsed ?? 0;
  const allowed = item.attemptsAllowed ?? 1;

  return (
    <div className="stack">
      <p className="muted">Geçme notu %{item.passingScore} · deneme başına {item.questionsPerAttempt} soru
        {item.timeLimitSeconds ? ` · süre ${Math.round(item.timeLimitSeconds / 60)} dk` : " · süre sınırı yok"}</p>
      <p>{attemptsText(used, allowed, !!item.completed)}</p>
      {error && <Alert kind="error"><p>{error}</p></Alert>}
      {item.completed && <p className="badge badge-success">✓ Bu quiz'i geçtiniz{item.bestPercent != null ? ` (%${item.bestPercent})` : ""}</p>}
      {result && <QuizResult result={result} />}
      {!attempt && !item.completed && (
        <button className="btn btn-primary" disabled={used >= allowed} onClick={start}>{used === 0 ? "Quiz'e başla" : "Yeniden dene"}</button>
      )}
      {attempt && <QuestionForm questions={attempt.questions} timeLeft={left} onSubmit={submit} />}
      {!attempt && used > 0 && !review && <button className="btn" onClick={loadReview}>Denemelerimi incele</button>}
      {review && (
        <section className="stack" aria-label="Deneme incelemesi">
          <h3>Denemeleriniz</h3>
          <QuizReview attempts={review} />
          <button className="btn" onClick={() => setReview(null)}>İncelemeyi kapat</button>
        </section>
      )}
    </div>
  );
}
