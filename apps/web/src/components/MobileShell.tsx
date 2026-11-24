import type { ReactNode } from "react";
import { Link, NavLink, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../auth";
import { useNotifications } from "../notifications";
import { BrandMark } from "./Header";

/**
 * Android app chrome (Material Design 3), used only in the mobile build; the web keeps its header.
 *  - Top app bar: brand on the five tab pages; on inner pages a back arrow (same as the system back gesture) and the
 *    section name (the page keeps its own h1); notification bell.
 *  - Bottom navigation bar: five destinations (Öğren, Keşfet, Üret, Topluluk, Profil), 80 dp tall, 48 dp touch targets.
 */
type Tab = { to: string; label: string; icon: ReactNode; match: (p: string) => boolean };

const svg = (d: string) => (
  <svg width="24" height="24" viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d={d} fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" /></svg>
);

export const TABS: Tab[] = [
  { to: "/", label: "Öğren", icon: svg("M4 19.5V6a2 2 0 0 1 2-2h12v15H6a2 2 0 0 0-2 2Zm0 0A2 2 0 0 0 6 21.5h12"), match: (p) => p === "/" || p.startsWith("/trainings/") },
  { to: "/explore", label: "Keşfet", icon: svg("M11 4a7 7 0 1 1 0 14 7 7 0 0 1 0-14Zm9 16-4-4"), match: (p) => p.startsWith("/explore") },
  { to: "/create", label: "Üret", icon: svg("M14.7 6.3a4 4 0 0 0-5.4 5.4L3 18l3 3 6.3-6.3a4 4 0 0 0 5.4-5.4l-2.6 2.6-2.4-.6-.6-2.4 2.6-2.6Z"), match: (p) => ["/create", "/tools", "/workflows"].some((x) => p.startsWith(x)) },
  { to: "/community", label: "Topluluk", icon: svg("M16 19v-1a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4v1M9.5 10a3 3 0 1 0 0-6 3 3 0 0 0 0 6ZM21 19v-1a4 4 0 0 0-3-3.9M16 4.1a3 3 0 0 1 0 5.8"), match: (p) => ["/community", "/leaderboard", "/u/"].some((x) => p.startsWith(x)) },
  { to: "/me", label: "Profil", icon: svg("M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8Zm-7 9a7 7 0 0 1 14 0"), match: (p) => ["/me", "/profile", "/history", "/certificates", "/account", "/notifications"].some((x) => p.startsWith(x)) },
];

const ROOTS = ["/", "/explore", "/create", "/community", "/me", "/login", "/register"];
const TITLES: [RegExp, string][] = [
  [/^\/$/, "Öğrenimim"], [/^\/explore/, "Keşfet"], [/^\/create/, "Üret"], [/^\/tools/, "Araçlarım"], [/^\/workflows\/new/, "Yeni iş akışı"],
  [/^\/workflows\/./, "İş akışı"], [/^\/workflows/, "İş akışları"], [/^\/community/, "Topluluk"], [/^\/leaderboard/, "Sıralama"],
  [/^\/u\//, "Profil"], [/^\/me/, "Profil"], [/^\/profile/, "Profilim ve rozetlerim"], [/^\/history/, "Geçmişim"], [/^\/certificates/, "Sertifikalarım"],
  [/^\/account/, "Hesabım ve verilerim"], [/^\/notifications/, "Bildirimler"], [/^\/trainings\//, "Eğitim"], [/^\/login/, "Giriş"],
  [/^\/register/, "Hesap oluştur"], [/^\/verify\//, "Sertifika doğrulama"],
];

export function MobileTopBar() {
  const { pathname } = useLocation();
  const nav = useNavigate();
  const { user } = useAuth();
  const { unread } = useNotifications();
  const root = ROOTS.includes(pathname);
  const title = TITLES.find(([r]) => r.test(pathname))?.[1] ?? "Basamak";
  const back = () => {
    if (window.history.state?.idx > 0) nav(-1);
    else nav(TABS.find((t) => t.match(pathname))?.to ?? "/", { replace: true });
  };
  return (
    <header className="m-topbar">
      {root ? <span className="m-topbar-spacer" aria-hidden="true" />
        : <button type="button" className="m-icon-btn" aria-label="Geri" onClick={back}>{svg("M15 5l-7 7 7 7")}</button>}
      {root ? <span className="m-topbar-title m-brand"><BrandMark size={22} /> Basamak</span> : <span className="m-topbar-title">{title}</span>}
      {user ? (
        <Link to="/notifications" className="m-icon-btn m-bell" aria-label={unread ? `Bildirimler, ${unread} okunmamış` : "Bildirimler"}>
          {svg("M12 3a6 6 0 0 0-6 6v3.6L4.3 15.5A1 1 0 0 0 5.2 17h13.6a1 1 0 0 0 .9-1.5L18 12.6V9a6 6 0 0 0-6-6Zm-2.5 15a2.5 2.5 0 0 0 5 0")}
          {unread > 0 && <span className="bell-badge" aria-hidden="true">{unread > 99 ? "99+" : unread}</span>}
        </Link>
      ) : <span className="m-topbar-spacer" aria-hidden="true" />}
    </header>
  );
}

export function MobileBottomNav() {
  const { pathname } = useLocation();
  const { user } = useAuth();
  if (!user) return null;
  return (
    <nav className="m-bottomnav" aria-label="Ana gezinme">
      {TABS.map((t) => {
        const active = t.match(pathname);
        return (
          <NavLink key={t.to} to={t.to} className={`m-tab ${active ? "active" : ""}`} aria-current={active ? "page" : undefined}>
            <span className="m-tab-icon">{t.icon}</span>
            <span className="m-tab-label">{t.label}</span>
          </NavLink>
        );
      })}
    </nav>
  );
}
