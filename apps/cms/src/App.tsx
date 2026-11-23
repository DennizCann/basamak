import { useEffect, useState } from "react";
import { StateView, ThemeToggle } from "@basamak/ui";

export function App() {
  const [status, setStatus] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = () => {
    setError(null);
    setStatus(null);
    fetch("/api/public/ping")
      .then((r) => (r.ok ? r.json() : Promise.reject(r.status)))
      .then((d) => setStatus(d.status))
      .catch(() => setError("Sunucuya ulaşılamadı. Arka ucun çalıştığını kontrol edip tekrar deneyin."));
  };
  useEffect(load, []);

  return (
    <>
      <a className="skip-link" href="#main">İçeriğe geç</a>
      <header className="container" style={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: "var(--space-3)", paddingBlock: "var(--space-3)" }}>
        <strong>Basamak CMS</strong>
        <ThemeToggle />
      </header>
      <main id="main" className="container stack">
        <h1>İçerik Yönetimi</h1>
        <StateView loading={!status && !error} error={error} onRetry={load}>
          <p>API durumu: {status}</p>
        </StateView>
      </main>
    </>
  );
}
