import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { createApi, type Api } from "@basamak/ui";
import { apiBase, deviceTokenStore, isMobile, refreshTokenStore } from "./platform";

export type User = { id: string; email: string; displayName: string; roles: string[] };

export type LoginChallenge = { challengeId: string; maskedEmail: string; expiresAt: string; resendAvailableAt: string; delivered: boolean; message: string };

type Session = { accessToken: string; user: User; refreshToken?: string; deviceToken?: string };

type AuthState = {
  api: Api;
  user: User | null;
  ready: boolean;
  /** Step 1: password. Returns null when this device is remembered (already signed in), else the code challenge. */
  login: (email: string, password: string) => Promise<LoginChallenge | null>;
  /** Step 2: the e-mailed code -> session; `remember` keeps this browser/app trusted for 30 days. */
  verifyCode: (challengeId: string, code: string, remember: boolean) => Promise<void>;
  resendCode: (challengeId: string) => Promise<LoginChallenge>;
  logout: () => Promise<void>;
};

const Ctx = createContext<AuthState | null>(null);

let onLost: () => void = () => {};
/** Web: cookie refresh. Mobile: refresh token in the body, kept in app storage (see platform.ts). */
export const api = createApi({
  baseUrl: apiBase,
  refreshPath: "/api/auth/refresh",
  refreshTokenStore: isMobile ? refreshTokenStore : undefined,
  onSessionLost: () => onLost(),
});

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [ready, setReady] = useState(false);
  onLost = () => setUser(null);

  useEffect(() => {
    // restore the session (web: HttpOnly refresh cookie, mobile: stored refresh token)
    api.tryRestore()
      .then(async (ok) => { if (ok) setUser(await api.get<User>("/api/me")); })
      .catch(() => setUser(null))
      .finally(() => setReady(true));
  }, []);

  const start = useCallback(async (s: Session) => {
    api.setToken(s.accessToken);
    if (isMobile) {
      if (s.refreshToken) await refreshTokenStore.set(s.refreshToken);
      if (s.deviceToken) await deviceTokenStore.set(s.deviceToken);
    }
    setUser(s.user);
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const deviceToken = isMobile ? await deviceTokenStore.get() : undefined;
    const r = await api.post<(LoginChallenge & { status: "code_required" }) | ({ status: "authenticated" } & Session)>(
      "/api/auth/login", { email, password, client: isMobile ? "mobile" : "web", deviceToken: deviceToken ?? undefined });
    if (r.status === "authenticated") { await start(r); return null; }
    return r;
  }, [start]);

  const verifyCode = useCallback(async (challengeId: string, code: string, remember: boolean) => {
    await start(await api.post<Session>("/api/auth/login/verify", { challengeId, code, remember }));
  }, [start]);

  const resendCode = useCallback((challengeId: string) => api.post<LoginChallenge>("/api/auth/login/resend", { challengeId }), []);

  const logout = useCallback(async () => {
    try {
      await api.post("/api/auth/logout", isMobile ? { refreshToken: await refreshTokenStore.get() } : undefined);
    } finally {
      if (isMobile) await refreshTokenStore.set(null); // the remembered-device token is kept on purpose
      api.setToken(null);
      setUser(null);
    }
  }, []);

  return <Ctx.Provider value={{ api, user, ready, login, verifyCode, resendCode, logout }}>{children}</Ctx.Provider>;
}

export function useAuth() {
  const c = useContext(Ctx);
  if (!c) throw new Error("AuthProvider missing");
  return c;
}
