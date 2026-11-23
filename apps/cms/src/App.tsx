import { BrowserRouter, Navigate, NavLink, Route, Routes } from "react-router-dom";
import type { ReactNode } from "react";
import { ThemeToggle } from "@basamak/ui";
import { AuthProvider, useAuth } from "./auth";
import { LoginPage } from "./pages/LoginPage";
import { ContentListPage } from "./pages/ContentListPage";
import { EditorPage } from "./pages/EditorPage";
import { ReportsPage } from "./pages/ReportsPage";

import { TYPES } from "./content";

export function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Shell>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/" element={<Navigate to="/content/journeys" replace />} />
            {TYPES.map((t) => (
              <Route key={t.type} path={`/content/${t.path}`} element={<RequireStaff><ContentListPage key={t.type} type={t.type} label={t.label} /></RequireStaff>} />
            ))}
            <Route path="/items/:id" element={<RequireStaff><EditorPage /></RequireStaff>} />
            <Route path="/reports" element={<RequireStaff><ReportsPage /></RequireStaff>} />
            <Route path="*" element={<main className="container"><h1>Sayfa bulunamadı</h1></main>} />
          </Routes>
        </Shell>
      </AuthProvider>
    </BrowserRouter>
  );
}

function Shell({ children }: { children: ReactNode }) {
  const { user, logout } = useAuth();
  return (
    <>
      <a className="skip-link" href="#main">İçeriğe geç</a>
      <header className="app-header">
        <div className="container row" style={{ justifyContent: "space-between", paddingBlock: "var(--space-2)" }}>
          <strong>Basamak CMS</strong>
          {user && (
            <nav aria-label="CMS menüsü" className="row">
              {TYPES.map((t) => <NavLink key={t.type} to={`/content/${t.path}`}>{t.label}</NavLink>)}
              <NavLink to="/reports">Raporlar</NavLink>
            </nav>
          )}
          <div className="row">
            <ThemeToggle />
            {user && <span className="muted">{user.displayName} · {user.roles.join(", ")}</span>}
            {user && <button className="btn" onClick={logout}>Çıkış</button>}
          </div>
        </div>
      </header>
      <div id="main" tabIndex={-1}>{children}</div>
    </>
  );
}

function RequireStaff({ children }: { children: ReactNode }) {
  const { user, ready } = useAuth();
  if (!ready) return <main className="container"><p role="status">Oturum kontrol ediliyor…</p></main>;
  if (!user) return <Navigate to="/login" replace />;
  return <>{children}</>;
}
