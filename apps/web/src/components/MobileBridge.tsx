import { useEffect, useRef } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { App as NativeApp } from "@capacitor/app";
import { LocalNotifications } from "@capacitor/local-notifications";
import { Alert } from "@basamak/ui";
import { api, useAuth } from "../auth";
import { PrivacyScreen, SystemBars, isMobile } from "../platform";
import { flushOutbox, startSync, useSync } from "../offline";
import { SplashScreen } from "@capacitor/splash-screen";

// id -> link of notifications shown in this app session (the tap payload may arrive without `extra`)
const shownLinks = new Map<number, string>();
let lastLink: string | null = null;

/** Screens that show secrets or personal data: blank in screenshots and the recent-apps preview (FLAG_SECURE). */
const PRIVATE = [/^\/login/, /^\/register/, /^\/reset-password/, /^\/account/, /^\/tools/];

/** Only in-app paths may be opened from outside (deep link / notification): no open redirect. */
export function safeInAppPath(raw: string): string | null {
  try {
    // relative in-app links are parsed against an http base (custom-scheme base resolution differs between WebViews)
    const u = raw.startsWith("/") ? new URL(raw, "http://app.invalid") : new URL(raw);
    const path = u.protocol === "basamak:" ? `/${u.host === "app" ? "" : u.host}${u.pathname}`.replace(/\/+/g, "/") : u.pathname;
    if (!/^\/(trainings|workflows|tools|certificates|verify|history|leaderboard|explore|account|notifications|community|profile|u)?(\/[\w-]*)*$/.test(path)) return null;
    return path + u.search;
  } catch {
    return null;
  }
}

/**
 * Mobile-only glue, mounted inside the router: deep links (basamak://...), Android back button, privacy screen per
 * route, offline outbox sync, and server notifications -> local notifications whose tap opens the related screen.
 */
export function MobileBridge() {
  const nav = useNavigate();
  const { pathname } = useLocation();
  const { user, ready } = useAuth();
  const sync = useSync();
  const navRef = useRef(nav);
  navRef.current = nav;

  // deep links + back button + resume
  useEffect(() => {
    if (!isMobile) return;
    const open = (url: string) => { const p = safeInAppPath(url); if (p) navRef.current(p); };
    NativeApp.getLaunchUrl().then((r) => { if (r?.url) open(r.url); }).catch(() => {});
    const subs = [
      NativeApp.addListener("appUrlOpen", (e) => open(e.url)),
      NativeApp.addListener("backButton", ({ canGoBack }) => { if (canGoBack) window.history.back(); else NativeApp.exitApp(); }),
      NativeApp.addListener("resume", () => flushOutbox()),
      LocalNotifications.addListener("localNotificationActionPerformed", (a) => {
        const link = (a.notification?.extra?.link as string | undefined) ?? shownLinks.get(a.notification?.id) ?? lastLink;
        console.info("notification tap", a.notification?.id, link);
        if (link) open(link);
      }),
    ];
    const stop = startSync();
    return () => { subs.forEach((s) => s.then((h) => h.remove())); stop(); };
  }, []);

  // splash stays until the session check is done (no flash of the login screen), then fades out
  useEffect(() => {
    // the splash exit re-applies the window theme (status bar icons reset), so the bar is styled again afterwards
    if (!isMobile || !ready) return;
    SplashScreen.hide({ fadeOutDuration: 200 }).catch(() => {});
    const timers = [500, 1500].map((ms) => setTimeout(() => window.dispatchEvent(new Event("basamak:statusbar")), ms));
    return () => timers.forEach(clearTimeout);
  }, [ready]);

  // status bar follows the app theme (light / dark / system): same color as the top app bar, readable icons
  useEffect(() => {
    if (!isMobile) return;
    // bars follow the app theme: the native side follows the system; an in-app choice (Açık / Koyu) is passed on
    const apply = () => {
      const t = document.documentElement.getAttribute("data-theme");
      SystemBars.setTheme({ mode: t === "light" || t === "dark" ? t : "system" }).catch(() => {});
    };
    apply();
    window.addEventListener("basamak:statusbar", apply);
    NativeApp.addListener("resume", apply).catch(() => {});
    const obs = new MutationObserver(apply);
    obs.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme", "class"] });
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    mq.addEventListener("change", apply);
    return () => { obs.disconnect(); mq.removeEventListener("change", apply); window.removeEventListener("basamak:statusbar", apply); };
  }, []);

  // privacy screen
  useEffect(() => {
    if (!isMobile) return;
    (PRIVATE.some((r) => r.test(pathname)) ? PrivacyScreen.enable() : PrivacyScreen.disable()).catch(() => {});
  }, [pathname]);

  // notifications stream -> local notifications (only while signed in)
  useEffect(() => {
    if (!isMobile || !user) return;
    const ctrl = new AbortController();
    let last = 0;
    (async () => {
      while (!ctrl.signal.aborted) {
        try {
          await api.stream("/api/me/notifications/stream", (e) => {
            if (e.event !== "notification" || e.id <= last) return;
            last = e.id;
            const n = e.data as { id: number; title: string; body: string; link: string | null; push?: boolean };
            if (n.push === false) return; // the member turned push off for this event type
            const nid = n.id % 2_000_000_000;
            if (n.link) { shownLinks.set(nid, n.link); lastLink = n.link; }
            LocalNotifications.schedule({ notifications: [{ id: nid, title: n.title, body: n.body, extra: { link: n.link } }] }).catch(() => {});
          }, { signal: ctrl.signal, lastEventId: last });
        } catch { /* offline: retry */ }
        if (!ctrl.signal.aborted) await new Promise((r) => setTimeout(r, 5000));
      }
    })();
    return () => ctrl.abort();
  }, [user]);

  if (!isMobile || (!sync.offline && !sync.pending && !sync.last)) return null;
  return (
    <div className="sync-banner" role="status" aria-live="polite">
      {sync.offline
        ? <Alert kind="warning"><p>Çevrimdışısın. Kayıtlı içerikleri okuyabilirsin{sync.pending ? `; ${sync.pending} işlem bağlanınca eşitlenecek` : ""}.</p></Alert>
        : sync.pending
          ? <Alert kind="info"><p>{sync.syncing ? "Eşitleniyor…" : `${sync.pending} işlem eşitlenmeyi bekliyor.`}</p></Alert>
          : sync.last && <Alert kind="success"><p>{sync.last}</p></Alert>}
    </div>
  );
}
