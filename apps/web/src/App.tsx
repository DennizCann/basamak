import { BrowserRouter, Navigate, NavLink, Route, Routes, useLocation } from "react-router-dom";
import { ThemeToggle } from "@basamak/ui";
import { AuthProvider, useAuth } from "./auth";
import { LoginPage, RegisterPage, VerifyEmailPage, ForgotPasswordPage, ResetPasswordPage } from "./pages/AuthPages";
import { HomePage } from "./pages/HomePage";
import { TrainingPage } from "./pages/TrainingPage";
import { VerifyCertificatePage } from "./pages/VerifyCertificatePage";
import type { ReactNode } from "react";

export function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Shell>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/register" element={<RegisterPage />} />
            <Route path="/verify-email" element={<VerifyEmailPage />} />
            <Route path="/forgot-password" element={<ForgotPasswordPage />} />
            <Route path="/reset-password" element={<ResetPasswordPage />} />
            <Route path="/verify/:serial" element={<VerifyCertificatePage />} />
            <Route path="/" element={<RequireAuth><HomePage /></RequireAuth>} />
            <Route path="/trainings/:id" element={<RequireAuth><TrainingPage /></RequireAuth>} />
            <Route path="*" element={<main className="container"><h1>Sayfa bulunamadı</h1><p><a href="/">Ana sayfaya dön</a></p></main>} />
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
          <NavLink to="/" style={{ fontWeight: 700, textDecoration: "none" }}>Basamak</NavLink>
          <nav aria-label="Ana menü" className="row">
            {user && <NavLink to="/" end>Ana sayfa</NavLink>}
          </nav>
          <div className="row">
            <ThemeToggle />
            {user && <button className="btn" onClick={logout}>Çıkış</button>}
          </div>
        </div>
      </header>
      <div id="main" tabIndex={-1}>{children}</div>
    </>
  );
}

function RequireAuth({ children }: { children: ReactNode }) {
  const { user, ready } = useAuth();
  const loc = useLocation();
  if (!ready) return <main className="container"><p role="status">Oturum kontrol ediliyor…</p></main>;
  if (!user) return <Navigate to={`/login?next=${encodeURIComponent(loc.pathname)}`} replace />;
  return <>{children}</>;
}
