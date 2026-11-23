import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { Alert, StateView, errorMessage } from "@basamak/ui";
import { api } from "../auth";

type Result = { valid: boolean; serial: string; holderName?: string; journeyTitle?: string; issuedAt?: string; reason?: string };

/** Public page opened from the certificate's QR code. No login needed. */
export function VerifyCertificatePage() {
  const { serial } = useParams();
  const [r, setR] = useState<Result | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    api.get<Result>(`/api/public/certificates/${encodeURIComponent(serial ?? "")}`).then(setR).catch((e) => setError(errorMessage(e)));
  }, [serial]);
  return (
    <main className="container stack" style={{ maxWidth: "36rem" }}>
      <h1>Sertifika doğrulama</h1>
      <StateView loading={!r && !error} error={error}>
        {r && (r.valid ? (
          <Alert kind="success">
            <p><strong>✓ Geçerli sertifika</strong></p>
            <p>{r.holderName}, <strong>{r.journeyTitle}</strong> Journey'ini {r.issuedAt} tarihinde tamamladı.</p>
            <p className="muted">Sertifika no: {r.serial}</p>
          </Alert>
        ) : (
          <Alert kind="error">
            <p><strong>✗ Geçersiz sertifika</strong></p>
            <p>{r.reason}</p>
            <p className="muted">Sertifika no: {r.serial}</p>
          </Alert>
        ))}
      </StateView>
    </main>
  );
}
