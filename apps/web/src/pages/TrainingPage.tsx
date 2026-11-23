import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import {
  Alert, ITEM_TYPE_LABEL, LessonBody, QuestionForm, QuizResult, QuizReview, StateView, SurveyForm,
  attemptsText, errorMessage, fmt, type Answers, type PlayerItem, type Question, type QuizResultData, type ReviewAttempt,
} from "@basamak/ui";
import { api } from "../auth";
import { Meter } from "../components/Meter";

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
  const [menuOpen, setMenuOpen] = useState(false);
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
  const index = t ? t.items.findIndex((i) => i.key === (requested ?? fallbackKey)) : -1;
  const item = t && index >= 0 ? t.items[index] : null;
  const unknownItem = !!t && !!requested && index < 0;

  useEffect(() => {
    if (t?.enrollment && !requested && fallbackKey) setParams({ item: fallbackKey }, { replace: true });
  }, [t, requested, fallbackKey, setParams]);

  const select = (key: string) => {
    if (key !== requested) setParams({ item: key }); // one history entry per real change
    setNotice(null);
    setMenuOpen(false);
    setTimeout(() => { headingRef.current?.focus(); headingRef.current?.scrollIntoView({ block: "start" }); }, 0);
  };

  useEffect(() => {
    if (t?.enrollment && item) api.post(`/api/trainings/${id}/items/${item.key}/view`).catch(() => {});
  }, [id, item?.key, t?.enrollment?.versionNo]); // eslint-disable-line react-hooks/exhaustive-deps

  const act = async (fn: () => Promise<Outcome | void>) => {
    try {
      const r = await fn();
      const parts: string[] = [];
      if (r && r.pointsAwarded) parts.push(`+${fmt.num(r.pointsAwarded)} puan kazandın.`);
      if (r && r.trainingCompleted) parts.push("Eğitimi tamamladın.");
      if (r && r.certificatesIssued?.length) parts.push("Bir Journey'i bitirdin; sertifikan Sertifikalarım sayfasında.");
      setNotice(parts.join(" ") || null);
      await load();
    } catch (e) {
      setNotice(null);
      setError(errorMessage(e));
    }
  };

  if (!t) {
    return <main className="page"><StateView loading={!error} error={error} onRetry={load} /></main>;
  }
  const done = t.enrollment?.completedCount ?? 0;
  const total = t.enrollment?.total ?? t.items.length;
  const prev = index > 0 ? t.items[index - 1] : null;
  const next = index >= 0 && index < t.items.length - 1 ? t.items[index + 1] : null;
  const toc = (
    <ol className="toc-list">
      {t.items.map((i) => (
        <li key={i.key}>
          <button className="toc-item" aria-current={i.key === item?.key ? "step" : undefined} onClick={() => select(i.key)}>
            <span className={`toc-icon ${i.completed ? "done" : ""}`} aria-hidden="true">{i.completed ? "✓" : ""}</span>
            <span>{i.title}<span className="type">{ITEM_TYPE_LABEL[i.type]}{i.completed ? " · tamamlandı" : ""}</span></span>
          </button>
        </li>
      ))}
    </ol>
  );

  return (
    <main className="page">
      <div className="player-head">
        <nav className="crumb" aria-label="Konum"><Link to="/">Öğrenimim</Link><span aria-hidden="true">›</span><span>Eğitim</span></nav>
        <h1>{t.title}</h1>
        {t.enrollment ? <Meter value={done} max={total} label="Eğitim ilerlemesi" /> : t.summary && <p className="lead" style={{ marginTop: 0 }}>{t.summary}</p>}
      </div>

      {error && <Alert kind="error"><p>{error}</p></Alert>}
      {notice && <Alert kind="success"><p>{notice}</p></Alert>}
      {t.newerVersionAvailable && t.enrollment && (
        <Alert kind="warning">
          <p>Bu eğitimin yeni bir sürümü yayınlandı. Kendi sürümünde bitirebilir ya da yeni sürüme geçebilirsin; tamamladığın ortak bölümler korunur.</p>
          <button className="btn btn-sm" onClick={() => act(() => api.post(`/api/trainings/${id}/switch-version`))}>Yeni sürüme geç</button>
        </Alert>
      )}

      {!t.enrollment ? (
        <section className="reader" aria-label="Eğitime başla">
          {t.lockReason ? <Alert kind="info"><p>{t.lockReason}</p></Alert> : null}
          <h2 style={{ fontSize: "var(--font-size-xl)" }}>Bu eğitimde {t.items.length} içerik var</h2>
          <ol>{t.items.map((i) => <li key={i.key}>{i.title} <span className="muted">· {ITEM_TYPE_LABEL[i.type]}</span></li>)}</ol>
          <div className="row">
            <button className="btn btn-primary" disabled={!!t.lockReason} onClick={() => act(() => api.post(`/api/trainings/${id}/enroll`))}>Eğitime başla</button>
            {t.lockReason && <span className="hint">Koşul sağlanınca başlayabilirsin.</span>}
          </div>
        </section>
      ) : (
        <div className="player">
          <aside className="toc" aria-label="Eğitim içeriği">
            <details className="toc-mobile" open={menuOpen} onToggle={(e) => setMenuOpen((e.target as HTMLDetailsElement).open)}>
              <summary>
                <span className="hint-line">İçerik {index + 1}/{t.items.length} · {done}/{total} tamamlandı</span>
                <span className="now">{item?.title ?? "İçerik seç"}</span>
                <span className="hint-line">{menuOpen ? "Listeyi gizle" : "Tüm içerikleri göster"}</span>
              </summary>
              {toc}
            </details>
            <div className="toc-desktop">{toc}</div>
          </aside>

          <section className="reader" aria-labelledby="item-title">
            {unknownItem && <Alert kind="warning"><p>Bağlantıdaki içerik bu eğitimin senin sürümünde yok (kaldırılmış olabilir). Listeden bir içerik seç.</p></Alert>}
            {item && !unknownItem && (
              <>
                <div>
                  <span className="item-type">{ITEM_TYPE_LABEL[item.type]} · {index + 1}/{t.items.length}</span>
                  <h2 id="item-title" tabIndex={-1} ref={headingRef}>{item.title}</h2>
                </div>
                {item.type === "lesson" && <Lesson trainingId={id!} item={item} onComplete={() => act(() => api.post(`/api/trainings/${id}/items/${item.key}/complete`))} />}
                {item.type === "quiz" && <Quiz trainingId={id!} item={item} onDone={(r) => act(async () => r)} />}
                {item.type === "survey" && (item.completed
                  ? <p className="done-note">✓ Anketi doldurdun, teşekkürler.</p>
                  : <SurveyForm item={item} onSubmit={(answers) => act(() => api.post(`/api/trainings/${id}/items/${item.key}/survey`, { answers }))} />)}
                {item.type === "tool" && <p className="muted">Bu içerik bir araç çalıştırır; araç ekranı henüz hazır değil.</p>}

                {!next && (
                  <div className="hero-target" role="status">
                    {t.enrollment.status === "COMPLETED"
                      ? <><span className="title">Bu eğitimi tamamladın.</span><span className="muted">İçerikleri istediğin zaman yeniden inceleyebilirsin.</span></>
                      : <><span className="title">Eğitimin son içeriğindesin.</span><span className="muted">Tamamlamak için kalan içerikler: {t.items.filter((i) => !i.completed).map((i) => i.title).join(", ")}.</span></>}
                  </div>
                )}
                <nav className="pager" aria-label="İçerikler arasında gezin">
                  {prev && <button className="btn" onClick={() => select(prev.key)}><span aria-hidden="true">←</span> Önceki</button>}
                  {/* "Next" becomes the primary action only once this item is done (until then: complete / answer it) */}
                  {next ? <button className={`btn next ${item.completed ? "btn-primary" : ""}`} onClick={() => select(next.key)}>Sonraki: {ITEM_TYPE_LABEL[next.type]} <span aria-hidden="true">→</span></button>
                    : <Link className={`btn next ${t.enrollment.status === "COMPLETED" ? "btn-primary" : ""}`} to="/">Öğrenimime dön</Link>}
                </nav>
              </>
            )}
          </section>
        </div>
      )}
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
      {item.completed ? <p className="done-note">✓ Bu dersi tamamladın</p>
        : <div><button className="btn btn-primary" onClick={onComplete}>Dersi tamamladım</button></div>}
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
    setBusy(true);
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
      <p className="muted" style={{ margin: 0 }}>Geçme notu %{item.passingScore} · deneme başına {item.questionsPerAttempt} soru
        {item.timeLimitSeconds ? ` · süre ${Math.round(item.timeLimitSeconds / 60)} dk` : " · süre sınırı yok"}</p>
      <p style={{ margin: 0 }}>{attemptsText(used, allowed, !!item.completed)}</p>
      {error && <Alert kind="error"><p>{error}</p></Alert>}
      {item.completed && <p className="done-note">✓ Bu quiz'i geçtin{item.bestPercent != null ? ` (%${item.bestPercent})` : ""}</p>}
      {result && <QuizResult result={result} />}
      {!attempt && !item.completed && (
        <div><button className="btn btn-primary" disabled={used >= allowed} onClick={start}>{used === 0 ? "Quiz'e başla" : "Yeniden dene"}</button></div>
      )}
      {attempt && <QuestionForm questions={attempt.questions} timeLeft={left} onSubmit={submit} />}
      {!attempt && used > 0 && !review && <div><button className="btn" onClick={loadReview}>Denemelerimi incele</button></div>}
      {review && (
        <section className="stack" aria-label="Deneme incelemesi">
          <h3>Denemelerin</h3>
          <QuizReview attempts={review} />
          <div><button className="btn" onClick={() => setReview(null)}>İncelemeyi kapat</button></div>
        </section>
      )}
    </div>
  );
}
