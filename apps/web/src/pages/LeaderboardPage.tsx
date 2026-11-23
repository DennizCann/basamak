import { useCallback, useEffect, useRef, useState } from "react";
import { StateView, errorMessage, fmt } from "@basamak/ui";
import { api } from "../auth";

type Row = { rank: number; userId: string; displayName: string; points: number; me: boolean };
type Board = { period: "week" | "all"; since: string | null; top: Row[]; me: { rank: number; points: number } | null };

/** Weekly / all-time leaderboard; refreshes itself when anyone's points change (SSE). */
export function LeaderboardPage() {
  const [period, setPeriod] = useState<"week" | "all">("week");
  const [board, setBoard] = useState<Board | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [live, setLive] = useState(false);
  const periodRef = useRef(period);
  periodRef.current = period;

  const load = useCallback(async (p = periodRef.current) => {
    try { setBoard(await api.get<Board>(`/api/leaderboard?period=${p}`)); setError(null); }
    catch (e) { setError(errorMessage(e)); }
  }, []);
  useEffect(() => { setBoard(null); load(period); }, [period, load]);

  useEffect(() => {
    const ctrl = new AbortController();
    (async () => {
      while (!ctrl.signal.aborted) {
        try {
          await api.stream("/api/leaderboard/stream", (e) => {
            if (e.event === "ready") setLive(true);
            if (e.event === "changed") load();
          }, { signal: ctrl.signal });
        } catch { /* reconnect below */ }
        setLive(false);
        if (!ctrl.signal.aborted) await new Promise((r) => setTimeout(r, 3000));
      }
    })();
    return () => ctrl.abort();
  }, [load]);

  return (
    <main className="page stack">
      <h1>Sıralama</h1>
      <div className="row space-between">
        <div role="tablist" aria-label="Dönem" className="row">
          {([["week", "Bu hafta"], ["all", "Tüm zamanlar"]] as const).map(([k, l]) => (
            <button key={k} role="tab" aria-selected={period === k} className={`btn ${period === k ? "btn-primary" : ""}`} onClick={() => setPeriod(k)}>{l}</button>
          ))}
        </div>
        <span className="hint-line" aria-live="polite">{live ? "● Canlı güncelleniyor" : "Bağlanıyor…"}</span>
      </div>
      {board?.since && <p className="muted">Hafta {fmt.date(board.since)} Pazartesi 00.00'da başladı (İstanbul saati).</p>}
      <StateView loading={!board && !error} error={error} onRetry={() => load()} empty={board?.top.length === 0} emptyText="Bu dönemde henüz puan kazanan yok.">
        <ol className="card-list leaderboard" aria-label="Sıralama listesi">
          {board?.top.map((r) => (
            <li key={r.userId} className={`card row space-between ${r.me ? "me" : ""}`} aria-current={r.me ? "true" : undefined}>
              <span><strong className="rank">{r.rank}.</strong> {r.displayName}{r.me && " (sen)"}</span>
              <strong>{fmt.num(r.points)} puan</strong>
            </li>
          ))}
        </ol>
        {board?.me && <p className="card">Senin sıran: <strong>{board.me.rank}.</strong> · {fmt.num(board.me.points)} puan</p>}
      </StateView>
    </main>
  );
}
