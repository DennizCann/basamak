import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { api, useAuth } from "./auth";

export type Notice = { id: number; type: string; title: string; body: string; link: string | null; createdAt: string; readAt: string | null };
type Ctx = { items: Notice[]; unread: number; loaded: boolean; reload: () => Promise<void>; markRead: (id: number) => Promise<void>; markAll: () => Promise<void> };

const NotificationsContext = createContext<Ctx>({ items: [], unread: 0, loaded: false, reload: async () => {}, markRead: async () => {}, markAll: async () => {} });

/** In-app notification centre state: loaded once per sign-in, kept live by the per-user SSE stream (no page refresh). */
export function NotificationsProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const [items, setItems] = useState<Notice[]>([]);
  const [unread, setUnread] = useState(0);
  const [loaded, setLoaded] = useState(false);

  const reload = useCallback(async () => {
    try {
      const r = await api.get<{ items: Notice[]; unread: number }>("/api/me/notifications?limit=50");
      setItems(r.items);
      setUnread(r.unread);
    } catch { /* offline: keep what we have */ }
    setLoaded(true);
  }, []);

  useEffect(() => {
    if (!user) { setItems([]); setUnread(0); setLoaded(false); return; }
    reload();
    const ctrl = new AbortController();
    (async () => {
      let last = 0;
      while (!ctrl.signal.aborted) {
        try {
          await api.stream("/api/me/notifications/stream", (e) => {
            if (e.event !== "notification" || e.id <= last) return;
            last = e.id;
            reload(); // respects the member's in-app preference on the server
          }, { signal: ctrl.signal, lastEventId: last });
        } catch { /* reconnect below */ }
        if (!ctrl.signal.aborted) await new Promise((r) => setTimeout(r, 5000));
      }
    })();
    return () => ctrl.abort();
  }, [user, reload]);

  const markRead = async (id: number) => {
    setItems((xs) => xs.map((x) => (x.id === id && !x.readAt ? { ...x, readAt: new Date().toISOString() } : x)));
    setUnread((u) => Math.max(0, u - (items.some((x) => x.id === id && !x.readAt) ? 1 : 0)));
    await api.post(`/api/me/notifications/${id}/read`).catch(() => {});
  };
  const markAll = async () => {
    await api.post("/api/me/notifications/read-all");
    await reload();
  };
  return <NotificationsContext.Provider value={{ items, unread, loaded, reload, markRead, markAll }}>{children}</NotificationsContext.Provider>;
}

export const useNotifications = () => useContext(NotificationsContext);
