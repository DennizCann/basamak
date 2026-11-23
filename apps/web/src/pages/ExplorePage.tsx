import { useCallback, useEffect, useId, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { StateView, errorMessage } from "@basamak/ui";
import { api } from "../auth";

type CatalogEntry = {
  id: string; title: string; summary: string; itemCount: number; status: "NOT_STARTED" | "IN_PROGRESS" | "COMPLETED";
  journeys: { id: string; title: string; order: number }[]; lockReason: string | null;
};

/** Turkish-aware matching: "İLK" ~ "ilk", "ILIK" ~ "ılık", "istanbul" ~ "İstanbul". */
export const trNormalize = (s: string) => s.toLocaleLowerCase("tr-TR").normalize("NFC").replace(/\s+/g, " ").trim();
const NO_JOURNEY = "none";

/**
 * Catalogue of published trainings the member has not started yet. Personal progress filters stay in "Öğrenimim".
 * Search + journey filter live in the URL (?q=&journey=) so results are shareable and back/forward works.
 * Locked trainings are listed with their condition; starting them is still refused by the server.
 */
export function ExplorePage() {
  const [params, setParams] = useSearchParams();
  const [all, setAll] = useState<CatalogEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const searchId = useId();
  const q = params.get("q") ?? "";
  const journey = params.get("journey") ?? "";

  const load = useCallback(() => {
    setError(null);
    api.get<CatalogEntry[]>("/api/catalog/trainings").then(setAll).catch((e) => setError(errorMessage(e)));
  }, []);
  useEffect(load, [load]);

  const notStarted = useMemo(() => (all ?? []).filter((t) => t.status === "NOT_STARTED"), [all]);
  const journeyOptions = useMemo(() => {
    const m = new Map<string, { id: string; title: string; order: number }>();
    notStarted.forEach((t) => t.journeys.forEach((j) => m.set(j.id, j)));
    return [...m.values()].sort((a, b) => a.order - b.order);
  }, [notStarted]);
  const hasUnassigned = notStarted.some((t) => t.journeys.length === 0);

  const results = useMemo(() => {
    const needle = trNormalize(q);
    return notStarted.filter((t) => {
      if (journey === NO_JOURNEY ? t.journeys.length > 0 : journey && !t.journeys.some((j) => j.id === journey)) return false;
      return !needle || trNormalize(`${t.title} ${t.summary}`).includes(needle);
    });
  }, [notStarted, q, journey]);

  const update = (next: { q?: string; journey?: string }) => {
    const p = new URLSearchParams(params);
    for (const [k, v] of Object.entries(next)) { if (v) p.set(k, v); else p.delete(k); }
    setParams(p, { replace: true }); // typing does not flood the history
  };
  const filtered = !!q || !!journey;

  return (
    <main className="page">
      <div>
        <h1 style={{ marginBottom: 0 }}>Eğitimleri keşfet</h1>
        <p className="lead">Henüz başlamadığın yayınlanmış eğitimler. İlerlemen ve tamamladıkların <Link className="text-link" to="/">Öğrenimim</Link> sayfasında.</p>
      </div>

      <StateView loading={!all && !error} error={error} onRetry={load}>
        {all && (notStarted.length === 0 ? (
          <div className="hero"><p style={{ margin: 0 }}>Başlamadığın yayınlanmış eğitim kalmadı. Yeni eğitimler yayınlandığında burada görünecek.</p></div>
        ) : (
          <>
            <form role="search" className="explore-filters" onSubmit={(e) => e.preventDefault()}>
              <div className="field">
                <label htmlFor={searchId}>Ara</label>
                <input id={searchId} type="search" value={q} placeholder="Başlık ya da açıklamada ara" autoComplete="off"
                       onChange={(e) => update({ q: e.target.value })} />
              </div>
              <div className="field">
                <label htmlFor={searchId + "-j"}>Journey (seviye)</label>
                <select id={searchId + "-j"} value={journey} onChange={(e) => update({ journey: e.target.value })}>
                  <option value="">Tümü</option>
                  {journeyOptions.map((j) => <option key={j.id} value={j.id}>{j.title}</option>)}
                  {hasUnassigned && <option value={NO_JOURNEY}>Journey'e bağlı olmayanlar</option>}
                </select>
              </div>
            </form>

            <p className="muted" role="status" aria-live="polite" style={{ margin: 0 }}>
              {results.length} eğitim{filtered ? " bulundu" : ""}
            </p>

            {results.length === 0 ? (
              <div className="hero">
                <p style={{ margin: 0 }}>
                  {q ? <>“{q}” için </> : null}{journey ? "seçili Journey'de " : ""}eşleşen eğitim bulunamadı. Farklı bir kelime dene ya da filtreleri temizle.
                </p>
                <div className="hero-actions"><button className="btn" onClick={() => setParams({}, { replace: true })}>Filtreleri temizle</button></div>
              </div>
            ) : (
              <ul className="explore-list">
                {results.map((t) => (
                  <li key={t.id}>
                    <article className="tcard" aria-label={t.title}>
                      <div className="tcard-main">
                        <span className="tcard-title">{t.title}</span>
                        {t.summary && <p className="tcard-summary">{t.summary}</p>}
                        <div className="tcard-meta">
                          {t.journeys.length > 0 && <span>{t.journeys.map((j) => j.title).join(", ")} · Journey</span>}
                          <span>{t.itemCount} içerik</span>
                          {t.lockReason ? <span className="chip chip-todo">Kilitli</span> : <span className="chip chip-progress">Başlayabilirsin</span>}
                        </div>
                        {t.lockReason && <p className="tcard-summary" style={{ color: "var(--color-text)" }}><strong>Erişim koşulu:</strong> {t.lockReason}</p>}
                      </div>
                      <Link className="btn btn-sm" to={`/trainings/${t.id}`} aria-label={`İncele: ${t.title}`}>İncele</Link>
                    </article>
                  </li>
                ))}
              </ul>
            )}
            {filtered && results.length > 0 && <div><button className="btn btn-ghost btn-sm text-link" onClick={() => setParams({}, { replace: true })}>Filtreleri temizle</button></div>}
          </>
        ))}
      </StateView>
    </main>
  );
}
