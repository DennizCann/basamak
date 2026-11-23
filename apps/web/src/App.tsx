import { BrowserRouter, Link, Navigate, Route, Routes, useLocation } from "react-router-dom";
import type { ReactNode } from "react";
import { AuthProvider, useAuth } from "./auth";
import { Header } from "./components/Header";
import { LoginPage, RegisterPage, VerifyEmailPage, ForgotPasswordPage, ResetPasswordPage } from "./pages/AuthPages";
import { HomePage } from "./pages/HomePage";
import { TrainingPage } from "./pages/TrainingPage";
import { CertificatesPage } from "./pages/CertificatesPage";
import { ExplorePage } from "./pages/ExplorePage";
import { ToolsPage } from "./pages/ToolsPage";
import { WorkflowsPage } from "./pages/WorkflowsPage";
import { WorkflowPage } from "./pages/WorkflowPage";
import { LeaderboardPage } from "./pages/LeaderboardPage";
import { HistoryPage } from "./pages/HistoryPage";
import { AccountPage } from "./pages/AccountPage";
import { VerifyCertificatePage } from "./pages/VerifyCertificatePage";
import "./app.css";

export function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <a className="skip-link" href="#main">İçeriğe geç</a>
        <Header />
        <div id="main" tabIndex={-1}>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/register" element={<RegisterPage />} />
            <Route path="/verify-email" element={<VerifyEmailPage />} />
            <Route path="/forgot-password" element={<ForgotPasswordPage />} />
            <Route path="/reset-password" element={<ResetPasswordPage />} />
            <Route path="/verify/:serial" element={<VerifyCertificatePage />} />
            <Route path="/" element={<RequireAuth><HomePage /></RequireAuth>} />
            <Route path="/certificates" element={<RequireAuth><CertificatesPage /></RequireAuth>} />
            <Route path="/explore" element={<RequireAuth><ExplorePage /></RequireAuth>} />
            <Route path="/tools" element={<RequireAuth><ToolsPage /></RequireAuth>} />
            <Route path="/workflows" element={<RequireAuth><WorkflowsPage /></RequireAuth>} />
            <Route path="/workflows/:id" element={<RequireAuth><WorkflowPage /></RequireAuth>} />
            <Route path="/leaderboard" element={<RequireAuth><LeaderboardPage /></RequireAuth>} />
            <Route path="/history" element={<RequireAuth><HistoryPage /></RequireAuth>} />
            <Route path="/account" element={<RequireAuth><AccountPage /></RequireAuth>} />
            <Route path="/trainings/:id" element={<RequireAuth><TrainingPage /></RequireAuth>} />
            <Route path="*" element={<main className="page"><h1>Sayfa bulunamadı</h1><p><Link className="text-link" to="/">Öğrenimime dön</Link></p></main>} />
          </Routes>
        </div>
      </AuthProvider>
    </BrowserRouter>
  );
}

function RequireAuth({ children }: { children: ReactNode }) {
  const { user, ready } = useAuth();
  const loc = useLocation();
  if (!ready) return <main className="page"><p role="status">Oturum kontrol ediliyor…</p></main>;
  if (!user) return <Navigate to={`/login?next=${encodeURIComponent(loc.pathname + loc.search)}`} replace />;
  return <>{children}</>;
}
