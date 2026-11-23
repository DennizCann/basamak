import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { StateView, errorMessage } from "@basamak/ui";
import { api } from "../auth";
import { BrandMark } from "../components/Header";

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
    <main className="page">
      <StateView loading={!r && !error} error={error}>
        {r && (
          <section className="verify" aria-labelledby="verify-title">
            <p className="eyebrow" style={{ display: "flex", gap: "var(--space-2)", alignItems: "center" }}>
              <span style={{ color: "var(--color-primary)" }}><BrandMark size={18} /></span> Basamak sertifika doğrulama
            </p>
            <h1 id="verify-title" className={`verify-status ${r.valid ? "ok" : "bad"}`} style={{ margin: 0 }}>
              <span className="icon" aria-hidden="true">{r.valid ? "✓" : "✕"}</span>
              {r.valid ? "Geçerli sertifika" : "Geçersiz sertifika"}
            </h1>
            {r.valid ? (
              <dl>
                <dt>Sahibi</dt><dd>{r.holderName}</dd>
                <dt>Journey</dt><dd>{r.journeyTitle}</dd>
                <dt>Tarih</dt><dd>{r.issuedAt}</dd>
                <dt>Sertifika no</dt><dd>{r.serial}</dd>
              </dl>
            ) : (
              <>
                <p style={{ margin: 0 }}>{r.reason}</p>
                <dl><dt>Sertifika no</dt><dd>{r.serial}</dd></dl>
              </>
            )}
            <p className="muted" style={{ margin: 0, fontSize: "var(--font-size-sm)" }}>
              Bu sonuç Basamak kayıtlarından anlık olarak doğrulanır. Sertifika numarası ya da kayıt değiştirilmişse geçersiz görünür.
            </p>
          </section>
        )}
      </StateView>
    </main>
  );
}
