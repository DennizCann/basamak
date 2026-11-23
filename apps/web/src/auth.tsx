import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { createApi, type Api } from "@basamak/ui";

export type User = { id: string; email: string; displayName: string; roles: string[] };

type AuthState = {
  api: Api;
  user: User | null;
  ready: boolean;
  login: (email: string, password: string) => Promise<void>;
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
    const r = await api.post<{ accessToken: string; user: User }>("/api/auth/login", { email, password, client: "web" });
    api.setToken(r.accessToken);
    setUser(r.user);
  }, []);

  const logout = useCallback(async () => {
    try { await api.post("/api/auth/logout"); } finally { api.setToken(null); setUser(null); }
  }, []);

  return <Ctx.Provider value={{ api, user, ready, login, logout }}>{children}</Ctx.Provider>;
}

export function useAuth() {
  const c = useContext(Ctx);
  if (!c) throw new Error("AuthProvider missing");
  return c;
}
