import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { StateView, errorMessage, fmt } from "@basamak/ui";
import { api, useAuth } from "../auth";
import { Meter } from "../components/Meter";
import { CertificateActions } from "../components/CertificateActions";
import { PointsDialog } from "../components/PointsDialog";
import { currentJourneyId, nextAction, trainingHref, type Journey, type NextAction, type Summary, type Training } from "../learning";

export function HomePage() {
  const { user } = useAuth();
  const [journeys, setJourneys] = useState<Journey[] | null>(null);
  const [summary, setSummary] = useState<Summary | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pointsOpen, setPointsOpen] = useState(false);

  const load = useCallback(() => {
    setError(null);
    Promise.all([api.get<Journey[]>("/api/me/journeys"), api.get<Summary>("/api/me/summary")])
      .then(([j, s]) => { setJourneys(j); setSummary(s); })
      .catch((e) => setError(errorMessage(e)));
  }, []);
  useEffect(load, [load]);

  const action = journeys ? nextAction(journeys) : null;
  const firstName = user?.displayName.split(" ")[0] ?? "";

  return (
    <main className="page">
      <StateView loading={!journeys && !error} error={error} onRetry={load}>
        {journeys && summary && action && (
          <>
            <NextStep action={action} firstName={firstName} certificateCount={summary.certificates.length} />

            <section aria-label="Özet" className="stats">
              <div className="stat">
                <span className="label">Seviyen</span>
                <span className="value">{summary.level || "—"}</span>
                <span className="help muted">{journeys.every((j) => j.completed) ? "Bütün Journey'leri tamamladın" : "Tamamlamadığın ilk Journey"}</span>
              </div>
              <div className="stat">
                <span className="label">Toplam puan</span>
                <span className="value">{fmt.num(summary.totalPoints)}</span>
                <button className="btn btn-ghost btn-sm text-link" style={{ justifySelf: "start", paddingLeft: 0, minHeight: 32 }} onClick={() => setPointsOpen(true)}>
                  Puanlar nasıl kazanılır?
                </button>
              </div>
              <div className="stat">
                <span className="label">Sertifikalar</span>
                <span className="value">{summary.certificates.length}</span>
                {summary.certificates.length > 0 ? <Link className="text-link help" to="/certificates">Sertifikalarımı gör</Link>
                  : <span className="help muted">Bir Journey'i bitirince kazanırsın</span>}
              </div>
            </section>

            <LearningPath journeys={journeys} currentId={currentJourneyId(journeys, action)} />
            <PointsDialog open={pointsOpen} onClose={() => setPointsOpen(false)} />
          </>
        )}
      </StateView>
    </main>
  );
}

function NextStep({ action, firstName, certificateCount }: { action: NextAction; firstName: string; certificateCount: number }) {
  if (action.kind === "empty") {
    return (
      <section className="hero" aria-labelledby="next-title">
        <h1 id="next-title">Merhaba {firstName}</h1>
        <p className="lead" style={{ margin: 0 }}>Şu anda yayında bir öğrenme yolu yok. İçerikler yayınlandığında burada göreceksin.</p>
      </section>
    );
  }
  if (action.kind === "done") {
    const w = action.waitingOn;
    return (
      <section className="hero done" aria-labelledby="next-title">
        <p className="eyebrow">Tebrikler, {firstName}</p>
        <h1 id="next-title">Mevcut eğitimlerin hepsini tamamladın</h1>
        <p className="lead" style={{ margin: 0 }}>
          {!w ? "Öğrenme yolundaki bütün Journey'leri bitirdin."
            : w.comingSoon ? `Sıradaki Journey “${w.title}” için eğitimler hazırlanıyor. Yayınlandıklarında burada görünecek.`
            : w.lockReason ?? `Sıradaki Journey “${w.title}”.`}
        </p>
        <div className="hero-actions">
          {certificateCount > 0 && <Link className="btn btn-primary" to="/certificates">Sertifikalarımı gör</Link>}
          <a className="btn" href="#yol">Eğitimleri tekrar incele</a>
        </div>
      </section>
    );
  }
  const t = action.training;
  const copy = {
    welcome: { eyebrow: `Hoş geldin, ${firstName}`, title: "Basamak'ta öğrenmeye başla", lead: "Kısa eğitimleri tamamlayarak seviye atlarsın; her adım puan kazandırır ve her Journey'in sonunda sertifika alırsın.", cta: "Öğrenmeye başla" },
    continue: { eyebrow: `Tekrar hoş geldin, ${firstName}`, title: "Kaldığın yerden devam et", lead: null, cta: "Devam et" },
    next: { eyebrow: `Merhaba ${firstName}`, title: "Sıradaki eğitimin hazır", lead: null, cta: "Başla" },
  }[action.kind];
  return (
    <section className="hero" aria-labelledby="next-title">
      <div>
        <p className="eyebrow">{copy.eyebrow}</p>
        <h1 id="next-title">{copy.title}</h1>
        {copy.lead && <p className="lead">{copy.lead}</p>}
      </div>
      <div className="hero-target">
        <span className="muted" style={{ fontSize: "var(--font-size-sm)" }}>{action.journey.title} · Journey</span>
        <span className="title">{t.title}</span>
        {t.summary && <span className="muted">{t.summary}</span>}
        {action.kind === "continue" ? <Meter value={t.completedItems} max={t.itemCount} label={`${t.title} ilerlemesi`} />
          : <span className="muted" style={{ fontSize: "var(--font-size-sm)" }}>{t.itemCount} içerik</span>}
      </div>
      <div className="hero-actions">
        <Link className="btn btn-primary" to={trainingHref(t)}>{copy.cta}</Link>
      </div>
    </section>
  );
}

const STEP_STATE = (j: Journey, currentId: string | null) =>
  j.completed ? "done" : j.locked ? "locked" : j.comingSoon ? "soon" : j.id === currentId ? "current" : "open";

function LearningPath({ journeys, currentId }: { journeys: Journey[]; currentId: string | null }) {
  return (
    <section aria-labelledby="path-title" id="yol">
      <div className="section-head">
        <h2 id="path-title">Öğrenme yolu</h2>
        <span className="muted" style={{ fontSize: "var(--font-size-sm)" }}>Journey'ler seviyelerdir; içindeki eğitimleri bitirince bir sonrakine geçersin.</span>
      </div>
      <ol className="path">
        {journeys.map((j) => {
          const s = STEP_STATE(j, currentId);
          const marker = s === "done" ? "✓" : s === "locked" ? <LockIcon /> : s === "soon" ? "…" : j.order;
          return (
            <li key={j.id} className={`step ${s === "done" ? "is-done" : s === "current" || s === "open" ? "is-current" : "is-future"}`}
                aria-label={`${j.order}. ${j.title}`}>
              <span className="step-marker" aria-hidden="true">{marker}</span>
              <div className="step-body">
                {s === "done" ? (
                  <details>
                    <summary>
                      <h3>{j.title}</h3>
                      <span className="chip chip-done">Tamamlandı{j.certificateSerial ? " · sertifika alındı" : ""}</span>
                      <span className="chev">Eğitimleri göster</span>
                    </summary>
                    <div className="step-content">
                      {j.certificateSerial && <CertificateActions serial={j.certificateSerial} compact />}
                      {j.trainings.map((t) => <TrainingCard key={t.id} t={t} />)}
                    </div>
                  </details>
                ) : (
                  <>
                    <div className="step-title">
                      <h3>{j.title}</h3>
                      {s === "locked" && <span className="chip chip-todo">Kilitli</span>}
                      {s === "soon" && <span className="chip chip-soon">Yakında</span>}
                      {(s === "current" || s === "open") && <span className="chip chip-progress">{j.completedCount}/{j.trainings.length} eğitim</span>}
                    </div>
                    {s === "locked" && <p className="step-note">{j.lockReason}</p>}
                    {s === "soon" && <p className="step-note">Eğitimleri hazırlanıyor; yayınlandığında burada görünecek.</p>}
                    {(s === "current" || s === "open") && (
                      <>
                        {j.description && <p className="step-note">{j.description}</p>}
                        <div className="step-content">{j.trainings.map((t) => <TrainingCard key={t.id} t={t} />)}</div>
                      </>
                    )}
                  </>
                )}
              </div>
            </li>
          );
        })}
      </ol>
    </section>
  );
}

function TrainingCard({ t }: { t: Training }) {
  const chip = t.status === "COMPLETED" ? <span className="chip chip-done">Tamamlandı</span>
    : t.status === "IN_PROGRESS" ? <span className="chip chip-progress">Devam ediyor</span>
    : <span className="chip chip-todo">Başlanmadı</span>;
  const label = t.status === "COMPLETED" ? "Tekrar incele" : t.status === "IN_PROGRESS" ? "Devam et" : "Başla";
  return (
    <article className="tcard" aria-label={t.title}>
      <div className="tcard-main">
        <span className="tcard-title">{t.title}</span>
        {t.summary && <p className="tcard-summary">{t.summary}</p>}
        <div className="tcard-meta">
          {chip}
          {t.status === "NOT_STARTED" ? <span>{t.itemCount} içerik</span>
            : <span style={{ flex: "1 1 10rem", maxWidth: "16rem" }}><Meter value={t.completedItems} max={t.itemCount} label={`${t.title} ilerlemesi`} /></span>}
        </div>
      </div>
      {/* secondary on purpose: the page's single primary action is the "next step" panel above */}
      <Link className="btn btn-sm" to={trainingHref(t)} aria-label={`${label}: ${t.title}`}>{label}</Link>
    </article>
  );
}

function LockIcon() {
  return <svg width="14" height="14" viewBox="0 0 16 16" aria-hidden="true"><rect x="3" y="7" width="10" height="7" rx="1.5" fill="currentColor" /><path d="M5 7V5a3 3 0 0 1 6 0v2" fill="none" stroke="currentColor" strokeWidth="1.6" /></svg>;
}
