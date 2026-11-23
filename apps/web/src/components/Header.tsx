import { useEffect, useId, useRef, useState } from "react";
import { Link, NavLink, useLocation } from "react-router-dom";
import { useThemePreference, type Theme } from "@basamak/ui";
import { useAuth } from "../auth";
import { PointsDialog } from "./PointsDialog";

/** Three rising steps: the Basamak mark ("basamak" = step). */
export function BrandMark({ size = 26 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <rect x="2" y="15" width="6" height="6" rx="1.5" fill="currentColor" opacity=".45" />
      <rect x="9" y="9.5" width="6" height="11.5" rx="1.5" fill="currentColor" opacity=".7" />
      <rect x="16" y="3" width="6" height="18" rx="1.5" fill="currentColor" />
    </svg>
  );
}

export function Header() {
  const { user } = useAuth();
  const { pathname } = useLocation();
  return (
    <header className="topbar">
      <div className="topbar-inner">
        <Link to="/" className="brand" aria-label="Basamak, Öğrenimim">
          <BrandMark />
          <span className="brand-name">Basamak</span>
        </Link>
        {user ? (
          <>
            <nav aria-label="Ana menü" className="mainnav">
              {/* trainings belong to "Öğrenimim", so it stays the active section inside a training */}
              <Link to="/" aria-current={pathname === "/" || pathname.startsWith("/trainings/") ? "page" : undefined}>Öğrenimim</Link>
              <NavLink to="/certificates">Sertifikalarım</NavLink>
            </nav>
            <AccountMenu />
          </>
        ) : <span style={{ marginLeft: "auto" }} />}
      </div>
    </header>
  );
}

function initials(name: string) {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] ?? "") + (parts.length > 1 ? parts[parts.length - 1][0] : "")).toLocaleUpperCase("tr-TR") || "?";
}

/**
 * Account disclosure: identity (read-only), appearance, points help, sign out.
 * Keyboard: Enter/Space opens and focuses the first control, Escape closes and returns focus, Tab moves through.
 * Click outside closes. It is a disclosure (not role="menu") because it contains a radio group.
 */
function AccountMenu() {
  const { user, logout } = useAuth();
  const [open, setOpen] = useState(false);
  const [theme, setTheme] = useThemePreference();
  const [pointsOpen, setPointsOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  const id = useId();

  useEffect(() => {
    if (!open) return;
    panel.current?.querySelector<HTMLElement>("input:checked, button, a")?.focus();
    const onDown = (e: MouseEvent) => { if (!root.current?.contains(e.target as Node)) setOpen(false); };
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") { setOpen(false); button.current?.focus(); } };
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => { document.removeEventListener("mousedown", onDown); document.removeEventListener("keydown", onKey); };
  }, [open]);

  if (!user) return null;
  const options: [Theme, string][] = [["system", "Sistem"], ["light", "Açık"], ["dark", "Koyu"]];

  return (
    <div className="account" ref={root}>
      <button ref={button} className="avatar-btn" aria-expanded={open} aria-controls={id} onClick={() => setOpen((o) => !o)}>
        <span className="avatar" aria-hidden="true">{initials(user.displayName)}</span>
        <span className="visually-hidden">Hesap menüsü: {user.displayName}</span>
        <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true"><path d="M2 4l4 4 4-4" fill="none" stroke="currentColor" strokeWidth="1.6" /></svg>
      </button>
      {open && (
        <div id={id} ref={panel} className="account-panel" role="group" aria-label="Hesap">
          <div className="account-id">
            <strong>{user.displayName}</strong>
            <span>{user.email}</span>
          </div>
          <div className="account-section">
            <fieldset>
              <legend>Görünüm</legend>
              <div className="segmented">
                {options.map(([value, label]) => (
                  <label key={value}>
                    <input type="radio" name={id + "-theme"} value={value} checked={theme === value} onChange={() => setTheme(value)} />
                    <span>{label}</span>
                  </label>
                ))}
              </div>
            </fieldset>
          </div>
          <div className="menu-sep" />
          <button className="menu-item" onClick={() => { setOpen(false); setPointsOpen(true); }}>Puanlar nasıl kazanılır?</button>
          <button className="menu-item" onClick={() => { setOpen(false); logout(); }}>Çıkış yap</button>
        </div>
      )}
      <PointsDialog open={pointsOpen} onClose={() => { setPointsOpen(false); button.current?.focus(); }} />
    </div>
  );
}
