import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { StateView, errorMessage, fmt } from "@basamak/ui";
import { api } from "../auth";
import { CertificateActions } from "../components/CertificateActions";
import type { Summary } from "../learning";

export function CertificatesPage() {
  const [summary, setSummary] = useState<Summary | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(() => {
    setError(null);
    api.get<Summary>("/api/me/summary").then(setSummary).catch((e) => setError(errorMessage(e)));
  }, []);
  useEffect(load, [load]);

  return (
    <main className="page">
      <div>
        <h1 style={{ marginBottom: 0 }}>Sertifikalarım</h1>
        <p className="lead">Bir Journey'deki eğitimlerin hepsini bitirdiğinde sertifika alırsın. Her sertifikanın herkese açık bir doğrulama sayfası vardır.</p>
      </div>
      <StateView loading={!summary && !error} error={error} onRetry={load}>
        {summary && (summary.certificates.length === 0 ? (
          <div className="hero">
            <p style={{ margin: 0 }}>Henüz sertifikan yok. İlk Journey'ini tamamladığında sertifikan burada görünecek.</p>
            <div className="hero-actions"><Link className="btn btn-primary" to="/">Öğrenimime dön</Link></div>
          </div>
        ) : (
          <ul className="cert-list">
            {summary.certificates.map((c) => (
              <li key={c.serial} className="cert">
                <div>
                  <h2 style={{ fontSize: "var(--font-size-lg)" }}>{c.journey_title}</h2>
                  <p className="meta" style={{ margin: "var(--space-1) 0 0" }}>{fmt.date(c.issued_at)} · Sertifika no: {c.serial}</p>
                </div>
                <CertificateActions serial={c.serial} compact />
              </li>
            ))}
          </ul>
        ))}
      </StateView>
    </main>
  );
}
