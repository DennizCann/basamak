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

  useEffect(() => {
    if (step?.otpauthUri) QRCode.toDataURL(step.otpauthUri, { margin: 1, width: 220 }).then(setQr).catch(() => setQr(null));
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
            {step.setupRequired && (
              <Alert kind="info">
                <p><strong>İki aşamalı doğrulamayı kurun.</strong> Doğrulayıcı uygulamanızla (Google Authenticator, Microsoft Authenticator, Aegis…) bu QR kodu okutun.</p>
                {qr && <img src={qr} alt="Doğrulayıcı uygulama için QR kod" width={220} height={220} />}
                <p>QR okutamıyorsanız bu anahtarı elle girin: <code style={{ wordBreak: "break-all" }}>{step.secret}</code></p>
              </Alert>
            )}
            <TextField label="6 haneli doğrulama kodu" inputMode="numeric" autoComplete="one-time-code" pattern="\d{6}" maxLength={6}
                       required value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} autoFocus />
            <button className="btn btn-primary" disabled={busy || code.length !== 6}>Doğrula ve giriş yap</button>
            {code.length !== 6 && <span className="hint">Uygulamadaki 6 haneli kodu girince düğme etkinleşir.</span>}
          </form>
        )}
      </div>
    </main>
  );
}
