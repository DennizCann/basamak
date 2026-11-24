import { useCallback, useEffect, useRef, useState } from "react";
import { downloadForOffline, isDownloaded } from "../offlineMedia";
import { haptic, isMobile } from "../platform";
import { Comments } from "../components/Comments";
import { Link, useParams, useSearchParams } from "react-router-dom";
import {
  Alert, ITEM_TYPE_LABEL, LessonBody, QuestionForm, QuizResult, QuizReview, StateView, SurveyForm,
  attemptsText, errorMessage, fmt, type Answers, type PlayerItem, type Question, type QuizResultData, type ReviewAttempt,
} from "@basamak/ui";
import { api } from "../auth";
import { Meter } from "../components/Meter";
import { ToolRunner, type ToolInput, type ToolRun } from "../components/ToolRunner";
import { cachedGet, patchCache, postOrQueue } from "../offline";

type Training = {
  id: string; title: string; summary: string; versionNo: number; latestVersionNo: number; newerVersionAvailable: boolean;
  items: PlayerItem[]; lockReason: string | null;
  enrollment: null | { status: string; versionNo: number; completedCount: number; total: number; currentKey: string };
};
type Outcome = { pointsAwarded?: number; trainingCompleted?: boolean; certificatesIssued?: string[]; queued?: true };

export function TrainingPage() {
  const { id } = useParams();
  const [params, setParams] = useSearchParams();
  const [t, setT] = useState<Training | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const requested = params.get("item");
  const [savedCopy, setSavedCopy] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const r = await cachedGet<Training>(`/api/trainings/${id}`);
      setT(r.data);
      setSavedCopy(r.offline ? r.savedAt ?? null : null);
    }
    catch (e) { setError(errorMessage(e)); }
  }, [id]);
  useEffect(() => { load(); }, [load]);
  useEffect(() => { const h = () => { setNotice(null); load(); }; window.addEventListener("basamak:synced", h); return () => window.removeEventListener("basamak:synced", h); }, [load]);

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
      if (r && r.queued) {
        setNotice("Çevrimdışı kaydedildi; bağlantı gelince eşitlenecek.");
        return;
      }
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

  /** offline: show the item as done now (screen + stored copy); the server confirms when the outbox is sent */
  function markDoneLocally(key: string) {
    const upd = (x: Training): Training => {
      const was = x.items.find((i) => i.key === key)?.completed;
      return {
        ...x,
        items: x.items.map((i) => (i.key === key ? { ...i, completed: true } : i)),
        enrollment: x.enrollment && !was ? { ...x.enrollment, completedCount: x.enrollment.completedCount + 1 } : x.enrollment,
      };
    };
    setT((x) => (x ? upd(x) : x));
    patchCache<Training>(`/api/trainings/${id}`, upd).catch(() => {});
  }

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
        {isMobile && t.enrollment && <OfflineDownload trainingId={t.id} />}
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
            {savedCopy && <p className="hint-line">Çevrimdışı kopya: {fmt.dateTime(savedCopy)} tarihli.</p>}
            {unknownItem && <Alert kind="warning"><p>Bağlantıdaki içerik bu eğitimin senin sürümünde yok (kaldırılmış olabilir). Listeden bir içerik seç.</p></Alert>}
            {item && !unknownItem && (
              <>
                <div>
                  <span className="item-type">{ITEM_TYPE_LABEL[item.type]} · {index + 1}/{t.items.length}</span>
                  <h2 id="item-title" tabIndex={-1} ref={headingRef}>{item.title}</h2>
                </div>
                {item.type === "lesson" && <Lesson trainingId={id!} item={item} onComplete={() => act(async () => {
                  const r = await postOrQueue<Outcome>(`/api/trainings/${id}/items/${item.key}/complete`, undefined, `Ders: ${item.title}`);
                  if ((r as Outcome).queued) markDoneLocally(item.key);
                  haptic("success");
                  return r as Outcome;
                })} />}
                {item.type === "quiz" && <Quiz trainingId={id!} item={item} onDone={(r) => act(async () => { if (r.passed) haptic("success"); return r; })} />}
                {item.type === "survey" && (item.completed
                  ? <p className="done-note">✓ Anketi doldurdun, teşekkürler.</p>
                  : <SurveyForm item={item} onSubmit={(answers) => act(async () => {
                    const r = await postOrQueue<Outcome>(`/api/trainings/${id}/items/${item.key}/survey`, { answers }, `Anket: ${item.title}`);
                    if ((r as Outcome).queued) markDoneLocally(item.key);
                    return r as Outcome;
                  })} />)}
                {item.type === "tool" && <TrainingTool key={item.key} trainingId={id!} item={item} onSucceeded={load} />}

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
      {t && <div style={{ marginTop: "var(--space-5, 24px)" }}><Comments targetType="TRAINING" targetId={t.id} /></div>}
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

type Attempt = { attemptId: string; attemptNo: number; attemptsAllowed: number; deadlineAt: string | null; questions: Question[]; extended?: boolean };

function Quiz({ trainingId, item, onDone }: { trainingId: string; item: PlayerItem; onDone: (r: QuizResultData & Outcome) => void }) {
  const [attempt, setAttempt] = useState<Attempt | null>(null);
  const [result, setResult] = useState<QuizResultData | null>(null);
  const [review, setReview] = useState<ReviewAttempt[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [left, setLeft] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [queued, setQueued] = useState(false);

  useEffect(() => { setAttempt(null); setResult(null); setReview(null); setError(null); setQueued(false); }, [item.key]);
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
      const r = await postOrQueue<QuizResultData & Outcome>(`/api/quiz-attempts/${attempt.attemptId}/submit`, { answers }, `Quiz: ${item.title}`);
      setAttempt(null);
      if ((r as Outcome).queued) { setQueued(true); return; } // graded by the server when the outbox is sent
      setResult(r as QuizResultData & Outcome);
      onDone(r as QuizResultData & Outcome);
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
      {queued && <Alert kind="warning"><p>Cevapların cihazda saklandı. Bağlantı gelince gönderilecek ve sonucu Geçmişim'de ve burada göreceksin. Süre sınırını aşan cevaplar sunucu kuralına göre değerlendirilir.</p></Alert>}
      {result && <QuizResult result={result} />}
      {!attempt && !item.completed && (
        <div><button className="btn btn-primary" disabled={used >= allowed} onClick={start}>{used === 0 ? "Quiz'e başla" : "Yeniden dene"}</button></div>
      )}
      {attempt?.deadlineAt && !attempt.extended && left !== null && left > 0 && (
        <p className="row" style={{ alignItems: "center" }}>
          <button type="button" className="btn btn-sm" onClick={async () => {
            try { const a = await api.post<Attempt>(`/api/quiz-attempts/${attempt.attemptId}/extend`); setAttempt({ ...attempt, deadlineAt: a.deadlineAt, extended: true }); }
            catch (e) { setError(errorMessage(e)); }
          }}>Ek süre iste</button>
          <span className="hint-line">Süre yetmeyecekse bir kez ek süre isteyebilirsin (süre sınırının yarısı kadar).</span>
        </p>
      )}
      {attempt?.extended && <p className="hint-line">Ek süre verildi.</p>}
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

/** A tool item: the member fills the tool's inputs and runs it; a successful run completes the item. */
function TrainingTool({ trainingId, item, onSucceeded }: { trainingId: string; item: PlayerItem; onSucceeded: () => void }) {
  const [tool, setTool] = useState<{ name: string; description: string; inputs: ToolInput[]; timeoutSeconds: number; retries: number } | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    api.get<typeof tool>(`/api/trainings/${trainingId}/tools/${item.key}`).then(setTool).catch((e) => setError(errorMessage(e)));
  }, [trainingId, item.key]);
  if (error) return <Alert kind="error"><p>{error}</p></Alert>;
  if (!tool) return <p role="status" className="muted">Araç yükleniyor…</p>;
  return (
    <div className="stack">
      {item.completed && <p className="done-note">✓ Bu aracı başarıyla çalıştırdın. İstersen yeniden çalıştırabilirsin.</p>}
      {tool.description && <p>{tool.description}</p>}
      <p className="hint-line">En fazla {tool.timeoutSeconds} sn beklenir{tool.retries > 0 ? `; geçici hatalarda ${tool.retries} kez yeniden denenir` : ""}.</p>
      <ToolRunner inputs={tool.inputs}
        start={(input, clientRequestId) => api.post<ToolRun>("/api/tool-runs", { trainingId, itemKey: item.key, input, clientRequestId })}
        onFinished={(r) => { if (r.status === "SUCCEEDED") onSucceeded(); }} />
    </div>
  );
}

/** Mobile: save the training (texts, images, attachments) on the phone before going offline. */
function OfflineDownload({ trainingId }: { trainingId: string }) {
  const [state, setState] = useState<"idle" | "busy" | "done">("idle");
  const [msg, setMsg] = useState<string | null>(null);
  useEffect(() => { isDownloaded(trainingId).then((d) => d && setState("done")).catch(() => {}); }, [trainingId]);
  return (
    <p className="row" style={{ alignItems: "center", margin: "8px 0 0" }}>
      <button className="btn btn-sm" disabled={state === "busy"} onClick={async () => {
        setState("busy");
        try {
          const r = await downloadForOffline(trainingId);
          setState("done");
          setMsg(`Çevrimdışı kullanıma hazır: ders metinleri ve ${r.files} dosya telefonda.${r.skippedVideos ? ` ${r.skippedVideos} video boyutu nedeniyle indirilmedi; izlemek için bağlantı gerekir.` : ""} Quiz'i başlatmak için bir kez bağlanman gerekir; cevaplar çevrimdışı verilebilir.`);
        } catch (e) { setState("idle"); setMsg(errorMessage(e)); }
      }}>{state === "busy" ? "İndiriliyor…" : state === "done" ? "Yeniden indir" : "Çevrimdışı için indir"}</button>
      {state === "done" && !msg && <span className="hint-line">✓ Bu eğitim telefonda kayıtlı.</span>}
      {msg && <span className="hint-line" role="status">{msg}</span>}
    </p>
  );
}
