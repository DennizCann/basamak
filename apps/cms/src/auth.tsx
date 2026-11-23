import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { createApi, type Api } from "@basamak/ui";

export type StaffUser = { id: string; email: string; displayName: string; roles: string[] };

type AuthState = {
  api: Api;
  user: StaffUser | null;
  ready: boolean;
  setSession: (token: string, user: StaffUser) => void;
  logout: () => Promise<void>;
  can: (...roles: string[]) => boolean;
};

const Ctx = createContext<AuthState | null>(null);
let onLost: () => void = () => {};
/** CMS has its own refresh endpoint and cookie; a member session never grants CMS access. */
export const api = createApi({ refreshPath: "/api/cms-auth/refresh", onSessionLost: () => onLost() });

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<StaffUser | null>(null);
  const [ready, setReady] = useState(false);
  onLost = () => setUser(null);

  useEffect(() => {
    // restore from the CMS refresh cookie; the refresh response carries the staff user
    fetch("/api/cms-auth/refresh", { method: "POST", credentials: "include", headers: { "X-Requested-With": "basamak" } })
      .then(async (r) => { if (r.ok) { const d = await r.json(); api.setToken(d.accessToken); setUser(d.user); } })
      .catch(() => {})
      .finally(() => setReady(true));
  }, []);

  const setSession = useCallback((token: string, u: StaffUser) => { api.setToken(token); setUser(u); }, []);
  const logout = useCallback(async () => {
    try { await api.post("/api/cms-auth/logout"); } finally { api.setToken(null); setUser(null); }
  }, []);
  const can = useCallback((...roles: string[]) => !!user && (user.roles.includes("ADMIN") || roles.some((r) => user.roles.includes(r))), [user]);

  return <Ctx.Provider value={{ api, user, ready, setSession, logout, can }}>{children}</Ctx.Provider>;
}

export function useAuth() {
  const c = useContext(Ctx);
  if (!c) throw new Error("AuthProvider missing");
  return c;
}
