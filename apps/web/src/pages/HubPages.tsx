import { useEffect, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { fmt, useThemePreference, type Theme } from "@basamak/ui";
import { api, useAuth } from "../auth";
import { PointsDialog } from "../components/PointsDialog";

/** Mobile "Üret" tab: the two things a member builds, with a short explanation and the main actions. */
export function CreatePage() {
  const [counts, setCounts] = useState<{ tools: number; flows: number } | null>(null);
  useEffect(() => {
    Promise.all([api.get<unknown[]>("/api/my-tools"), api.get<unknown[]>("/api/workflows")])
      .then(([t, w]) => setCounts({ tools: t.length, flows: w.length })).catch(() => {});
  }, []);
  return (
    <main className="page stack">
      <p className="muted" style={{ margin: 0 }}>Öğrendiğini uygula: bir servise bağlanan araçlar kur, onları yapay zekâ adımlarıyla birleştirip iş akışı yap ve toplulukla paylaş.</p>
      <HubCard to="/workflows" title="İş akışlarım" meta={counts ? `${fmt.num(counts.flows)} akış` : undefined}
               text="Adımları zincirle: araç → yapay zekâ → koşul. Hazır şablonlarla başlayabilirsin." action={{ to: "/workflows/new", label: "Yeni iş akışı" }} />
      <HubCard to="/tools" title="Araçlarım" meta={counts ? `${fmt.num(counts.tools)} araç` : undefined}
               text="Bir web servisine bağlanan kendi aracını 4 adımda kur ve çalıştır." action={{ to: "/tools", label: "Araçlarıma git" }} />
    </main>
  );
}

/** Mobile "Profil" tab: everything about me in one list (the web shows these in the account menu). */
export function MeHubPage() {
  const { user, logout } = useAuth();
  const [theme, setTheme] = useThemePreference();
  const [points, setPoints] = useState(false);
  const options: [Theme, string][] = [["system", "Sistem"], ["light", "Açık"], ["dark", "Koyu"]];
  return (
    <main className="page stack">
      {user && <div className="card"><strong>{user.displayName}</strong><div className="hint-line">{user.email}</div></div>}
      <nav aria-label="Profil" className="m-list">
        <Link to="/profile">Profilim ve rozetlerim</Link>
        <Link to="/history">Geçmişim</Link>
        <Link to="/certificates">Sertifikalarım</Link>
        <Link to="/notifications">Bildirimler ve tercihler</Link>
        <Link to="/leaderboard">Sıralama</Link>
        <Link to="/account">Hesabım ve verilerim</Link>
        <button type="button" onClick={() => setPoints(true)}>Puanlar nasıl kazanılır?</button>
      </nav>
      <fieldset className="card">
        <legend>Görünüm</legend>
        <div className="segmented">
          {options.map(([v, l]) => (
            <label key={v}><input type="radio" name="m-theme" value={v} checked={theme === v} onChange={() => setTheme(v)} /><span>{l}</span></label>
          ))}
        </div>
      </fieldset>
      <button type="button" className="btn btn-block" onClick={() => logout()}>Çıkış yap</button>
      <PointsDialog open={points} onClose={() => setPoints(false)} />
    </main>
  );
}

function HubCard({ to, title, meta, text, action }: { to: string; title: string; meta?: string; text: string; action: { to: string; label: string } }): ReactNode {
  return (
    <section className="card stack">
      <div className="row space-between"><Link className="text-link" to={to}><strong>{title}</strong></Link>{meta && <span className="hint-line">{meta}</span>}</div>
      <p style={{ margin: 0 }}>{text}</p>
      <div><Link className="btn btn-primary" to={action.to}>{action.label}</Link></div>
    </section>
  );
}
