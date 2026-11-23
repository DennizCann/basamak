import { useEffect, useState, type FormEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { Alert, TextField, errorMessage } from "@basamak/ui";
import { api, useAuth } from "../auth";

function Card({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <main className="container" style={{ maxWidth: "28rem" }}>
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

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(email, password);
      nav(params.get("next") || "/", { replace: true });
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title="Giriş yap">
      <form onSubmit={submit} className="stack" noValidate>
        {error && <Alert kind="error"><p>{error}</p></Alert>}
        <TextField label="E-posta" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        <TextField label="Şifre" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
        <button className="btn btn-primary" disabled={busy}>{busy ? "Giriş yapılıyor…" : "Giriş yap"}</button>
      </form>
      <p className="row"><Link to="/register">Hesap oluştur</Link><Link to="/forgot-password">Şifremi unuttum</Link></p>
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
  useEffect(() => {
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
