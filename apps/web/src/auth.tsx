import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { createApi, type Api } from "@basamak/ui";

export type User = { id: string; email: string; displayName: string; roles: string[] };

export type LoginChallenge = { challengeId: string; maskedEmail: string; expiresAt: string; resendAvailableAt: string; delivered: boolean; message: string };

type AuthState = {
  api: Api;
  user: User | null;
  ready: boolean;
  /** Step 1: password. Returns null when this device is remembered (already signed in), else the code challenge. */
  login: (email: string, password: string) => Promise<LoginChallenge | null>;
  /** Step 2: the e-mailed code -> session; `remember` keeps this browser trusted for 30 days. */
  verifyCode: (challengeId: string, code: string, remember: boolean) => Promise<void>;
  resendCode: (challengeId: string) => Promise<LoginChallenge>;
  logout: () => Promise<void>;
};

const Ctx = createContext<AuthState | null>(null);

let onLost: () => void = () => {};
export const api = createApi({ refreshPath: "/api/auth/refresh", onSessionLost: () => onLost() });

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [ready, setReady] = useState(false);
  onLost = () => setUser(null);

  useEffect(() => {
    // restore the session from the HttpOnly refresh cookie (if any)
    api.tryRestore()
      .then(async (ok) => { if (ok) setUser(await api.get<User>("/api/me")); })
      .catch(() => setUser(null))
      .finally(() => setReady(true));
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const r = await api.post<(LoginChallenge & { status: "code_required" }) | { status: "authenticated"; accessToken: string; user: User }>(
      "/api/auth/login", { email, password, client: "web" });
    if (r.status === "authenticated") { api.setToken(r.accessToken); setUser(r.user); return null; }
    return r;
  }, []);

  const verifyCode = useCallback(async (challengeId: string, code: string, remember: boolean) => {
    const r = await api.post<{ accessToken: string; user: User }>("/api/auth/login/verify", { challengeId, code, remember });
    api.setToken(r.accessToken);
    setUser(r.user);
  }, []);

  const resendCode = useCallback((challengeId: string) => api.post<LoginChallenge>("/api/auth/login/resend", { challengeId }), []);

  const logout = useCallback(async () => {
    try { await api.post("/api/auth/logout"); } finally { api.setToken(null); setUser(null); }
  }, []);

  return <Ctx.Provider value={{ api, user, ready, login, verifyCode, resendCode, logout }}>{children}</Ctx.Provider>;
}

export function useAuth() {
  const c = useContext(Ctx);
  if (!c) throw new Error("AuthProvider missing");
  return c;
}
