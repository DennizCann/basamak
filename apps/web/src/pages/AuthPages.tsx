import { useEffect, useRef, useState, type FormEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { Alert, ApiError, TextField, errorMessage } from "@basamak/ui";
import { api, useAuth, type LoginChallenge } from "../auth";

function Card({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <main className="page auth">
      <h1>{title}</h1>
      <div className="card stack">{children}</div>
    </main>
  );
}

export function LoginPage() {
  const { login } = useAuth();
  const nav = useNavigate();
  const [params] = useSearchParams();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [challenge, setChallenge] = useState<LoginChallenge | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const c = await login(email, password);
      setPassword("");
      if (c) setChallenge(c);
      else done(); // remembered device: password was enough
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const done = () => {
    const next = params.get("next");
    // only same-app paths (no "//host" or absolute URLs) -> no open redirect
    nav(next && next.startsWith("/") && !next.startsWith("//") ? next : "/", { replace: true });
  };

  if (challenge) {
    return <CodeStep challenge={challenge} onChallenge={setChallenge} onDone={done}
                     onRestart={(msg) => { setChallenge(null); setError(msg); }} />;
  }

  return (
    <Card title="Giriş yap">
      {params.get("deleted") && <Alert kind="success"><p>Hesabın silindi ve kişisel verilerin anonimleştirildi. Görüşmek üzere.</p></Alert>}
      <form onSubmit={submit} className="stack" noValidate>
        {error && <Alert kind="error"><p>{error}</p></Alert>}
        <TextField label="E-posta" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        <TextField label="Şifre" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
        <button className="btn btn-primary" disabled={busy}>{busy ? "Kontrol ediliyor…" : "Devam"}</button>
        <span className="hint">Yeni bir cihazdan girişte e-posta adresine 6 haneli bir kod göndeririz.</span>
      </form>
      <p className="row"><Link to="/register">Hesap oluştur</Link><Link to="/forgot-password">Şifremi unuttum</Link></p>
    </Card>
  );
}

function useSecondsUntil(iso: string) {
  const calc = () => Math.max(0, Math.ceil((new Date(iso).getTime() - Date.now()) / 1000));
  const [left, setLeft] = useState(calc);
  useEffect(() => {
    setLeft(calc());
    const h = setInterval(() => setLeft(calc()), 1000);
    return () => clearInterval(h);
  }, [iso]); // eslint-disable-line react-hooks/exhaustive-deps
  return left;
}

/** Second step: the 6-digit code e-mailed to the account's address (single use, 10 minutes). */
function CodeStep({ challenge, onChallenge, onDone, onRestart }: {
  challenge: LoginChallenge; onChallenge: (c: LoginChallenge) => void; onDone: () => void; onRestart: (msg: string) => void;
}) {
  const { verifyCode, resendCode } = useAuth();
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(challenge.delivered ? null : challenge.message);
  const [info, setInfo] = useState<string | null>(challenge.delivered ? challenge.message : null);
  const [busy, setBusy] = useState(false);
  const [remember, setRemember] = useState(true);
  const expiresIn = useSecondsUntil(challenge.expiresAt);
  const resendIn = useSecondsUntil(challenge.resendAvailableAt);

  async function verify(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    setBusy(true); setError(null);
    try {
      await verifyCode(challenge.challengeId, code.trim(), remember);
      onDone();
    } catch (err) {
      const c = err instanceof ApiError ? err.code : "";
      // these end the attempt: the member starts again with the password
      if (c === "too_many_attempts" || c === "code_used" || c === "challenge_not_found") onRestart(errorMessage(err));
      else { setError(errorMessage(err)); setCode(""); }
    } finally { setBusy(false); }
  }

  async function resend() {
    setError(null); setInfo(null);
    try {
      const c = await resendCode(challenge.challengeId);
      onChallenge(c);
      if (c.delivered) setInfo("Yeni kodu gönderdik. Önceki kod artık geçersiz."); else setError(c.message);
      setCode("");
    } catch (err) {
      const c = err instanceof ApiError ? err.code : "";
      if (c === "too_many_sends" || c === "code_used" || c === "challenge_not_found") onRestart(errorMessage(err));
      else setError(errorMessage(err));
    }
  }

  const mm = (s: number) => `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
  return (
    <Card title="Giriş kodunu gir">
      <form onSubmit={verify} className="stack" noValidate>
        <p style={{ margin: 0 }}><strong>{challenge.maskedEmail}</strong> adresine 6 haneli bir kod gönderdik.</p>
        {info && <Alert kind="success"><p>{info}</p></Alert>}
        {error && <Alert kind="error"><p>{error}</p></Alert>}
        <TextField label="6 haneli kod" inputMode="numeric" autoComplete="one-time-code" pattern="\d{6}" maxLength={6} required autoFocus
                   value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
                   hint={expiresIn > 0 ? `Kod ${mm(expiresIn)} içinde geçerliliğini yitirir.` : "Kodun süresi doldu; yeni kod isteyin."} />
        <label className="choice">
          <input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} />
          <span>Bu cihazı 30 gün hatırla <span className="muted">(ortak kullanılan bilgisayarlarda işaretlemeyin)</span></span>
        </label>
        <button className="btn btn-primary" disabled={busy || code.length !== 6 || expiresIn === 0}>{busy ? "Doğrulanıyor…" : "Giriş yap"}</button>
        <div className="row">
          <button type="button" className="btn" disabled={resendIn > 0} onClick={resend} aria-describedby="resend-hint">Kodu yeniden gönder</button>
          <span id="resend-hint" className="hint" aria-live="polite">{resendIn > 0 ? `${resendIn} sn sonra yeniden isteyebilirsin.` : "E-posta gelmediyse yeni kod iste."}</span>
        </div>
        <button type="button" className="btn btn-ghost btn-sm" style={{ justifySelf: "start" }} onClick={() => onRestart("")}>Farklı hesapla giriş yap</button>
      </form>
    </Card>
  );
}

export function RegisterPage() {
  const [form, setForm] = useState({ email: "", password: "", displayName: "", consent: false });
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const fe: Record<string, string> = {};
    if (!/^\S+@\S+\.\S+$/.test(form.email)) fe.email = "Geçerli bir e-posta adresi girin.";
    if (form.password.length < 10) fe.password = "Şifre en az 10 karakter olmalı.";
    if (form.displayName.trim().length < 2) fe.displayName = "Adınızı girin (en az 2 karakter).";
    setFieldErrors(fe);
    if (Object.keys(fe).length) return;
    setBusy(true);
    setError(null);
    try {
      await api.post("/api/auth/register", form);
      setDone(true);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <Card title="E-postanızı kontrol edin">
        <Alert kind="success"><p>Hesabınız oluşturuldu. <strong>{form.email}</strong> adresine gönderdiğimiz bağlantıyla hesabınızı doğrulayın.</p></Alert>
        <p className="muted">Yerel demo ortamında e-postalar Mailpit'e düşer: http://localhost:8025</p>
        <Link to="/login">Giriş sayfasına dön</Link>
      </Card>
    );
  }

  return (
    <Card title="Hesap oluştur">
      <form onSubmit={submit} className="stack" noValidate>
        {error && <Alert kind="error"><p>{error}</p></Alert>}
        <TextField label="Ad soyad" autoComplete="name" value={form.displayName} error={fieldErrors.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })} />
        <TextField label="E-posta" type="email" autoComplete="email" value={form.email} error={fieldErrors.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
        <TextField label="Şifre" type="password" autoComplete="new-password" hint="En az 10 karakter." value={form.password} error={fieldErrors.password} onChange={(e) => setForm({ ...form, password: e.target.value })} />
        <details>
          <summary>KVKK aydınlatma metni</summary>
          <p className="muted">Basamak; hesabınızı yönetmek, eğitim ilerlemenizi ve puanlarınızı tutmak için ad, e-posta ve platformdaki işlemlerinizi işler. Verileriniz yalnız bu amaçlarla kullanılır. Hesabınızdan verilerinizi indirebilir ve hesabınızı silebilirsiniz.</p>
        </details>
        <label className="choice">
          <input type="checkbox" checked={form.consent} onChange={(e) => setForm({ ...form, consent: e.target.checked })} />
          <span>Aydınlatma metnini okudum, kişisel verilerimin işlenmesine açık rıza veriyorum.</span>
        </label>
        <button className="btn btn-primary" disabled={busy || !form.consent} aria-describedby="consent-why">
          {busy ? "Oluşturuluyor…" : "Hesap oluştur"}
        </button>
        {!form.consent && <span id="consent-why" className="hint">Devam etmek için açık rıza kutusunu işaretleyin.</span>}
      </form>
      <Link to="/login">Zaten hesabım var</Link>
    </Card>
  );
}

export function VerifyEmailPage() {
  const [params] = useSearchParams();
  const [state, setState] = useState<{ ok?: string; error?: string }>({});
  const sent = useRef(false);
  useEffect(() => {
    // the token is single-use: never send it twice (React StrictMode runs effects twice in development)
    if (sent.current) return;
    sent.current = true;
    const token = params.get("token");
    if (!token) { setState({ error: "Bağlantıda doğrulama kodu yok." }); return; }
    api.post<{ message: string }>("/api/auth/verify-email", { token })
      .then((r) => setState({ ok: r.message }))
      .catch((e) => setState({ error: errorMessage(e) }));
  }, [params]);
  return (
    <Card title="E-posta doğrulama">
      {!state.ok && !state.error && <p role="status">Doğrulanıyor…</p>}
      {state.ok && <Alert kind="success"><p>{state.ok}</p></Alert>}
      {state.error && <Alert kind="error"><p>{state.error}</p></Alert>}
      <Link to="/login">Giriş yap</Link>
    </Card>
  );
}

export function ForgotPasswordPage() {
  const [email, setEmail] = useState("");
  const [msg, setMsg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  async function submit(e: FormEvent) {
    e.preventDefault();
    try { setMsg((await api.post<{ message: string }>("/api/auth/forgot-password", { email })).message); }
    catch (err) { setError(errorMessage(err)); }
  }
  return (
    <Card title="Şifremi unuttum">
      <form onSubmit={submit} className="stack">
        {msg && <Alert kind="success"><p>{msg}</p></Alert>}
        {error && <Alert kind="error"><p>{error}</p></Alert>}
        <TextField label="E-posta" type="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        <button className="btn btn-primary">Sıfırlama bağlantısı gönder</button>
      </form>
    </Card>
  );
}

export function ResetPasswordPage() {
  const [params] = useSearchParams();
  const [password, setPassword] = useState("");
  const [msg, setMsg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  async function submit(e: FormEvent) {
    e.preventDefault();
    try { setMsg((await api.post<{ message: string }>("/api/auth/reset-password", { token: params.get("token"), password })).message); }
    catch (err) { setError(errorMessage(err)); }
  }
  return (
    <Card title="Yeni şifre">
      <form onSubmit={submit} className="stack">
        {msg && <Alert kind="success"><p>{msg}</p></Alert>}
        {error && <Alert kind="error"><p>{error}</p></Alert>}
        <TextField label="Yeni şifre" type="password" autoComplete="new-password" hint="En az 10 karakter." value={password} onChange={(e) => setPassword(e.target.value)} />
        <button className="btn btn-primary">Şifreyi değiştir</button>
      </form>
      <Link to="/login">Giriş yap</Link>
    </Card>
  );
}
