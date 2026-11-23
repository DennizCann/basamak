import { useState } from "react";
import { Link } from "react-router-dom";
import { errorMessage } from "@basamak/ui";
import { api } from "../auth";
import { isMobile } from "../platform";
import { sharePdf } from "../share";

/** PDF download with in-progress state (no double requests) and success / failure feedback. */
export function CertificateActions({ serial, compact = false }: { serial: string; compact?: boolean }) {
  const [state, setState] = useState<"idle" | "busy" | "done" | "error">("idle");
  const [msg, setMsg] = useState<string | null>(null);
  const download = async () => {
    if (state === "busy") return;
    setState("busy"); setMsg(null);
    try {
      if (isMobile) {
        await sharePdf(`/api/me/certificates/${serial}/pdf`, `basamak-sertifika-${serial}.pdf`);
        setState("done"); setMsg("Sertifika hazır: paylaşım ekranından kaydedebilir ya da gönderebilirsin.");
      } else {
      await api.download(`/api/me/certificates/${serial}/pdf`, `basamak-sertifika-${serial}.pdf`);
      setState("done"); setMsg("Sertifika indirildi. Tarayıcınızın indirilenler klasörüne bakın.");
      }
    } catch (e) {
      setState("error"); setMsg(errorMessage(e));
    }
  };
  return (
    <div className="stack" style={{ gap: "var(--space-2)" }}>
      <div className="row">
        <button className={`btn ${compact ? "btn-sm" : ""}`} onClick={download} disabled={state === "busy"} aria-busy={state === "busy"}>
          {state === "busy" ? "Hazırlanıyor…" : isMobile ? "Sertifikayı paylaş (PDF)" : "Sertifikayı indir (PDF)"}
        </button>
        <Link className="text-link" to={`/verify/${serial}`}>Doğrulama sayfası</Link>
      </div>
      {msg && <p role="status" className={state === "error" ? "error" : "muted"} style={{ margin: 0 }}>{msg}</p>}
    </div>
  );
}
