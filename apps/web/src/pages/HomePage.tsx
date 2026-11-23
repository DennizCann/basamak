import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Alert, ProgressBar, StateView, errorMessage, fmt } from "@basamak/ui";
import { api, useAuth } from "../auth";

type Training = { id: string; title: string; status: "NOT_STARTED" | "IN_PROGRESS" | "COMPLETED" };
type Journey = {
  id: string; title: string; description: string; order: number; trainings: Training[];
  completedCount: number; completed: boolean; locked: boolean; lockReason: string | null; certificateSerial: string | null;
  comingSoon: boolean;
};
type Summary = { totalPoints: number; level: string; certificates: { serial: string; journey_title: string; issued_at: string }[] };

/** PDF download with in-progress state (no double requests) and success / failure feedback. */
function CertificateActions({ serial }: { serial: string }) {
  const [state, setState] = useState<"idle" | "busy" | "done" | "error">("idle");
  const [msg, setMsg] = useState<string | null>(null);
  const download = async () => {
    if (state === "busy") return;
    setState("busy"); setMsg(null);
    try {
      await api.download(`/api/me/certificates/${serial}/pdf`, `basamak-sertifika-${serial}.pdf`);
      setState("done"); setMsg("Sertifika indirildi. Tarayıcınızın indirilenler klasörüne bakın.");
    } catch (e) {
      setState("error"); setMsg(errorMessage(e));
    }
  };
  return (
    <div className="stack">
      <div className="row">
        <button className="btn" onClick={download} disabled={state === "busy"} aria-busy={state === "busy"}>
          {state === "busy" ? "Hazırlanıyor…" : "Sertifikayı indir (PDF)"}
        </button>
        <Link to={`/verify/${serial}`}>Doğrulama sayfası</Link>
      </div>
      {msg && <p role="status" className={state === "error" ? "error" : "muted"}>{msg}</p>}
    </div>
  );
}

const STATUS: Record<Training["status"], { text: string; cls: string }> = {
  NOT_STARTED: { text: "Başlanmadı", cls: "badge-muted" },
  IN_PROGRESS: { text: "Devam ediyor", cls: "badge-info" },
  COMPLETED: { text: "Tamamlandı", cls: "badge-success" },
};

export function HomePage() {
  const { user } = useAuth();
  const [journeys, setJourneys] = useState<Journey[] | null>(null);
  const [summary, setSummary] = useState<Summary | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    setError(null);
    Promise.all([api.get<Journey[]>("/api/me/journeys"), api.get<Summary>("/api/me/summary")])
      .then(([j, s]) => { setJourneys(j); setSummary(s); })
      .catch((e) => setError(errorMessage(e)));
  }, []);
  useEffect(load, [load]);

  return (
    <main className="container stack">
      <h1>Merhaba {user?.displayName}</h1>
      <StateView loading={!journeys && !error} error={error} onRetry={load}>
        {summary && (
          <section className="card row" aria-label="Özet" style={{ justifyContent: "space-between" }}>
            <div><span className="muted">Seviye</span><br /><strong style={{ fontSize: "var(--font-size-lg)" }}>{summary.level || "—"}</strong></div>
            <div><span className="muted">Toplam puan</span><br /><strong style={{ fontSize: "var(--font-size-lg)" }}>{fmt.num(summary.totalPoints)} puan</strong></div>
          </section>
        )}
        <section aria-labelledby="map-title" className="stack">
          <h2 id="map-title">Journey haritası</h2>
          <ol className="list-plain stack">
            {journeys?.map((j) => (
              <li key={j.id} className="card stack" aria-label={`${j.order}. ${j.title}`}>
                <div className="row" style={{ justifyContent: "space-between" }}>
                  <h3 style={{ margin: 0 }}>{j.order}. {j.title}</h3>
                  {j.completed ? <span className="badge badge-success">✓ Tamamlandı</span>
                    : j.locked ? <span className="badge badge-muted">🔒 Kilitli</span>
                    : j.comingSoon ? <span className="badge badge-warning">Yakında</span>
                    : <span className="badge badge-info">Açık</span>}
                </div>
                <p className="muted" style={{ margin: 0 }}>{j.description}</p>
                {j.locked && j.lockReason && <Alert kind="info"><p>{j.lockReason}</p></Alert>}
                {j.trainings.length > 0
                  ? <ProgressBar value={j.completedCount} max={j.trainings.length} label={`${j.title} ilerlemesi`} />
                  : <Alert kind="info"><p>Bu Journey'in eğitimleri hazırlanıyor. Yayınlandıklarında burada görünecek; sonraki Journey'ler bu Journey tamamlanınca açılır.</p></Alert>}
                <ul className="list-plain">
                  {j.trainings.map((t) => (
                    <li key={t.id} className="row" style={{ justifyContent: "space-between" }}>
                      {j.locked ? <span>{t.title}</span> : <Link to={`/trainings/${t.id}`}>{t.title}</Link>}
                      <span className={`badge ${STATUS[t.status].cls}`}>{STATUS[t.status].text}</span>
                    </li>
                  ))}
                </ul>
                {j.certificateSerial && <CertificateActions serial={j.certificateSerial} />}
              </li>
            ))}
          </ol>
        </section>
      </StateView>
    </main>
  );
}
