import { useEffect, useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import QRCode from "qrcode";
import { Alert, TextField, errorMessage } from "@basamak/ui";
import { api, useAuth, type StaffUser } from "../auth";

type Step1 = { mfaToken: string; setupRequired: boolean; otpauthUri?: string; secret?: string };

/** Password -> TOTP. On first sign-in the authenticator app is enrolled from a QR code. */
export function LoginPage() {
  const { setSession } = useAuth();
  const nav = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [step, setStep] = useState<Step1 | null>(null);
  const [code, setCode] = useState("");
  const [qr, setQr] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (step?.otpauthUri) QRCode.toDataURL(step.otpauthUri, { margin: 1, width: 240 }).then(setQr).catch(() => setQr(null));
  }, [step]);

  async function passwordStep(e: FormEvent) {
    e.preventDefault();
    setBusy(true); setError(null);
    try { setStep(await api.post<Step1>("/api/cms-auth/login", { email, password })); }
    catch (err) { setError(errorMessage(err)); }
    finally { setBusy(false); }
  }

  async function totpStep(e: FormEvent) {
    e.preventDefault();
    if (!step) return;
    setBusy(true); setError(null);
    try {
      const r = await api.post<{ accessToken: string; user: StaffUser }>("/api/cms-auth/totp", { mfaToken: step.mfaToken, code: code.trim() });
      setSession(r.accessToken, r.user);
      nav("/", { replace: true });
    } catch (err) {
      setError(errorMessage(err));
      if ((err as { code?: string }).code === "mfa_expired") setStep(null);
    } finally { setBusy(false); }
  }

  return (
    <main className="container" style={{ maxWidth: "30rem" }}>
      <h1>CMS girişi</h1>
      <div className="card stack">
        {error && <Alert kind="error"><p>{error}</p></Alert>}
        {!step ? (
          <form onSubmit={passwordStep} className="stack">
            <p className="muted">Yalnız içerik ekibi (yazar, editör, yayıncı, yönetici) giriş yapabilir.</p>
            <TextField label="E-posta" type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} />
            <TextField label="Şifre" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
            <button className="btn btn-primary" disabled={busy}>Devam</button>
          </form>
        ) : (
          <form onSubmit={totpStep} className="stack">
            {step.setupRequired ? (
              <div className="stack">
                <h2 style={{ margin: 0, fontSize: "var(--font-size-lg)" }}>İki aşamalı doğrulamayı kur (bir kerelik)</h2>
                <ol className="stack" style={{ paddingLeft: "1.25rem", margin: 0 }}>
                  <li>Telefonuna ücretsiz bir doğrulayıcı uygulama kur: <strong>Google Authenticator</strong>, <strong>Microsoft Authenticator</strong> ya da <strong>Aegis</strong>.</li>
                  <li>Uygulamada <strong>“+” → “QR kodu tara”</strong> seçeneğini aç ve bu kodu okut. Uygulamada <strong>“Basamak CMS”</strong> kaydı oluşur.
                    {qr && <div style={{ marginTop: "var(--space-3)", background: "#fff", padding: 8, borderRadius: 8, width: "fit-content" }}>
                      <img src={qr} alt="Doğrulayıcı uygulamada okutulacak QR kod" width={240} height={240} />
                    </div>}
                    <details style={{ marginTop: "var(--space-2)" }}>
                      <summary>QR okutamıyor musun? Anahtarı elle gir</summary>
                      <p className="muted" style={{ marginBottom: 4 }}>Uygulamada “Kurulum anahtarı gir” seçeneğini aç; hesap adı olarak “Basamak CMS” yaz ve bu anahtarı gir:</p>
                      <div className="row">
                        <code data-totp-secret style={{ fontSize: "var(--font-size-lg)", letterSpacing: ".08em", wordBreak: "break-all" }}>
                          {step.secret?.match(/.{1,4}/g)?.join(" ")}
                        </code>
                        <button type="button" className="btn btn-sm" onClick={() => navigator.clipboard?.writeText(step.secret ?? "").then(() => setCopied(true)).catch(() => {})}>
                          {copied ? "Kopyalandı" : "Kopyala"}
                        </button>
                      </div>
                    </details>
                  </li>
                  <li>Uygulamanın gösterdiği <strong>6 haneli kodu</strong> aşağıya yaz. Kod her 30 saniyede yenilenir.</li>
                </ol>
              </div>
            ) : (
              <p style={{ margin: 0 }}>Doğrulayıcı uygulamanda <strong>“Basamak CMS”</strong> kaydının gösterdiği 6 haneli kodu gir. Kod her 30 saniyede yenilenir.</p>
            )}
            <TextField label="6 haneli doğrulama kodu" inputMode="numeric" autoComplete="one-time-code" pattern="\d{6}" maxLength={6}
                       required value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} autoFocus
                       hint="Kod reddedilirse uygulamada yenisinin çıkmasını bekleyip tekrar dene." />
            <button className="btn btn-primary" disabled={busy || code.length !== 6}>Doğrula ve giriş yap</button>
            {code.length !== 6 && <span className="hint">6 haneyi girince düğme etkinleşir.</span>}
            <button type="button" className="btn btn-ghost btn-sm" style={{ justifySelf: "start" }} onClick={() => { setStep(null); setCode(""); }}>Geri dön</button>
          </form>
        )}
      </div>
    </main>
  );
}
