import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Link, useParams } from "react-router-dom";
import { Alert, StateView, TextArea, errorMessage, fmt } from "@basamak/ui";
import { ApiError } from "@basamak/ui";
import { api, useAuth } from "../auth";

type Badge = { key: string; title: string; description: string; awardedAt?: string };
type Profile = {
  id: string; displayName: string; self: boolean; isPublic: boolean; bio: string; followers: number; following: number; isFollowing: boolean;
  level?: string | null; score?: number; streak?: { current: number; longest: number; activeToday: boolean };
  badges?: Badge[]; allBadges?: Badge[];
  journeys?: { title: string; completedAt: string; serial: string }[];
  sharedWorkflows?: { id: string; name: string; description: string; forkCount: number; avgRating: number | null }[];
};
type Settings = { isPublic: boolean; showLevel: boolean; showBadges: boolean; showJourneys: boolean; showShared: boolean; bio: string };

const BADGE_ICON: Record<string, string> = {
  FIRST_LESSON: "📘", FIRST_QUIZ: "✅", FIRST_TOOL: "🛠️", FIRST_WORKFLOW: "🔗", FIRST_SHARE: "🤝", FIRST_JOURNEY: "🏅", STREAK_3: "🔥", STREAK_7: "🌟",
};

/** Public member profile (/u/:id) and the member's own profile with visibility settings (/profile). */
export function ProfilePage() {
  const { id: param } = useParams();
  const { user } = useAuth();
  const id = param ?? user?.id;
  const [p, setP] = useState<Profile | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [hidden, setHidden] = useState(false);

  const load = useCallback(async () => {
    if (!id) return;
    setError(null);
    try { setP(await api.get<Profile>(`/api/profiles/${id}`)); setHidden(false); }
    catch (e) { if (e instanceof ApiError && e.status === 404) setHidden(true); else setError(errorMessage(e)); }
  }, [id]);
  useEffect(() => { load(); }, [load]);

  if (hidden) return <main className="page stack"><h1>Profil</h1><Alert kind="info"><p>Bu profil gizli ya da üye bulunamadı. Üyeler profillerini herkese açmayı kendileri seçer.</p></Alert></main>;
  if (!p) return <main className="page"><StateView loading={!error} error={error} onRetry={load} /></main>;

  const follow = async () => {
    try {
      if (p.isFollowing) { await api.del(`/api/profiles/${p.id}/follow`); } else { await api.post(`/api/profiles/${p.id}/follow`); }
      load();
    } catch (e) { setError(errorMessage(e)); }
  };
  const earned = new Set((p.badges ?? []).map((b) => b.key));

  return (
    <main className="page stack">
      <section className="card stack">
        <div className="row space-between">
          <div>
            <h1 style={{ margin: 0 }}>{p.displayName}</h1>
            {p.level && <p className="muted" style={{ margin: 0 }}>Seviye: <strong>{p.level}</strong></p>}
          </div>
          {!p.self && <button className={`btn ${p.isFollowing ? "" : "btn-primary"}`} aria-pressed={p.isFollowing} onClick={follow}>{p.isFollowing ? "Takibi bırak" : "Takip et"}</button>}
        </div>
        {p.bio && <p style={{ margin: 0 }}>{p.bio}</p>}
        <div className="stat-row">
          {p.score !== undefined && <div><strong>{fmt.num(p.score)}</strong><span className="muted">puan</span></div>}
          {p.streak && <div><strong>{fmt.num(p.streak.current)} gün</strong><span className="muted">seri (en uzun {fmt.num(p.streak.longest)})</span></div>}
          <div><strong>{fmt.num(p.followers)}</strong><span className="muted">takipçi</span></div>
          <div><strong>{fmt.num(p.following)}</strong><span className="muted">takip edilen</span></div>
        </div>
        {p.self && <p className="hint" style={{ margin: 0 }}>{p.isPublic ? "Profilin herkese açık. Aşağıdan neyin görüneceğini seçebilirsin." : "Profilin gizli: yalnız sen görüyorsun. Takip edilebilmek için aşağıdan herkese aç."}</p>}
      </section>

      {p.badges && (
        <section className="card stack" aria-labelledby="badges-h">
          <h2 id="badges-h">Rozetler</h2>
          <ul className="badge-grid">
            {(p.self ? p.allBadges ?? [] : p.badges).map((b) => {
              const has = earned.has(b.key);
              const got = p.badges?.find((x) => x.key === b.key);
              return (
                <li key={b.key} className="badge-tile" data-earned={has}>
                  <span className="badge-icon" aria-hidden="true">{BADGE_ICON[b.key] ?? "🏆"}</span>
                  <strong>{b.title}</strong>
                  <div className="hint-line">{has ? `${got?.awardedAt ? fmt.date(got.awardedAt) : ""} kazanıldı` : `Henüz değil: ${b.description}`}</div>
                </li>
              );
            })}
          </ul>
          {p.badges.length === 0 && !p.self && <p className="muted">Henüz rozet yok.</p>}
        </section>
      )}

      {p.journeys && (
        <section className="card stack" aria-labelledby="journeys-h">
          <h2 id="journeys-h">Tamamlanan Journey'ler</h2>
          {p.journeys.length === 0 ? <p className="muted">Henüz tamamlanan Journey yok.</p> : (
            <ul className="card-list">
              {p.journeys.map((j) => <li key={j.serial} className="row space-between"><span>{j.title}</span><Link className="text-link" to={`/verify/${j.serial}`}>{fmt.date(j.completedAt)} · sertifikayı doğrula</Link></li>)}
            </ul>
          )}
        </section>
      )}

      {p.sharedWorkflows && (
        <section className="card stack" aria-labelledby="shared-h">
          <h2 id="shared-h">Paylaştığı iş akışları</h2>
          {p.sharedWorkflows.length === 0 ? <p className="muted">Paylaşılan iş akışı yok.</p> : (
            <ul className="card-list">
              {p.sharedWorkflows.map((w) => (
                <li key={w.id} className="row space-between">
                  <Link className="text-link" to={`/workflows/${w.id}`}>{w.name}</Link>
                  <span className="muted">{w.avgRating != null ? `★ ${Number(w.avgRating).toLocaleString("tr-TR")} · ` : ""}{fmt.num(w.forkCount)} çatal</span>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}

      {p.self && <ProfileSettings onSaved={load} />}
    </main>
  );
}

function ProfileSettings({ onSaved }: { onSaved: () => void }) {
  const [s, setS] = useState<Settings | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  useEffect(() => { api.get<Settings>("/api/me/profile-settings").then(setS).catch((e) => setError(errorMessage(e))); }, []);
  if (!s) return error ? <Alert kind="error"><p>{error}</p></Alert> : null;
  const set = (k: keyof Settings, v: boolean | string) => { setS({ ...s, [k]: v }); setSaved(false); };
  const save = async (e: FormEvent) => {
    e.preventDefault();
    try { setS(await api.put<Settings>("/api/me/profile-settings", s)); setSaved(true); setError(null); onSaved(); } catch (x) { setError(errorMessage(x)); }
  };
  const box = (k: keyof Settings, label: string) => (
    <label className="choice"><input type="checkbox" checked={Boolean(s[k])} disabled={k !== "isPublic" && !s.isPublic} onChange={(e) => set(k, e.target.checked)} /> <span>{label}</span></label>
  );
  return (
    <form className="card stack" onSubmit={save} aria-labelledby="ps-h">
      <h2 id="ps-h">Profil ayarları</h2>
      {box("isPublic", "Profilim herkese açık olsun (diğer üyeler görebilir ve takip edebilir)")}
      <fieldset className="stack" disabled={!s.isPublic}>
        <legend>Profilimde görünsün</legend>
        {box("showLevel", "Seviye, puan ve seri")}
        {box("showBadges", "Rozetler")}
        {box("showJourneys", "Tamamlanan Journey'ler ve sertifikalar")}
        {box("showShared", "Paylaştığım iş akışları")}
      </fieldset>
      <TextArea label="Kısa tanıtım (isteğe bağlı)" rows={2} maxLength={280} value={s.bio} hint="En çok 280 karakter. E-posta ve telefon numarası otomatik gizlenir."
                onChange={(e) => set("bio", e.target.value)} />
      {error && <Alert kind="error"><p>{error}</p></Alert>}
      {saved && <p role="status" className="hint-line">Profil ayarların kaydedildi.</p>}
      <div><button className="btn btn-primary">Kaydet</button></div>
    </form>
  );
}
