import { Capacitor, registerPlugin } from "@capacitor/core";
import { Preferences } from "@capacitor/preferences";

/**
 * Platform switch for the same React code base:
 *  - web  : same-origin API (Vite proxy / reverse proxy), refresh token in an HttpOnly cookie.
 *  - mobile (Capacitor build, `vite --mode mobile`): API on VITE_API_BASE, refresh + remembered-device tokens
 *    returned in the body and kept in the Android Keystore (SecureStoragePlugin, EncryptedSharedPreferences).
 *    iOS would use the Keychain; not implemented/tested there, so iOS falls back to Preferences (documented).
 */
export const isMobile = import.meta.env.VITE_PLATFORM === "mobile";
export const apiBase = (import.meta.env.VITE_API_BASE as string | undefined) ?? "";
const android = isMobile && Capacitor.getPlatform() === "android";

type KV = { get(o: { key: string }): Promise<{ value: string | null }>; set(o: { key: string; value: string }): Promise<void>; remove(o: { key: string }): Promise<void> };
const Secure = registerPlugin<KV>("SecureStorage");
export const PrivacyScreen = registerPlugin<{ enable(): Promise<void>; disable(): Promise<void> }>("PrivacyScreen");

function secureStore(key: string) {
  return {
    async get() {
      if (!android) return (await Preferences.get({ key })).value;
      const v = (await Secure.get({ key })).value;
      if (v) return v;
      // one-time migration from the older plain Preferences storage (F2.5 builds)
      const old = (await Preferences.get({ key })).value;
      if (old) { await Secure.set({ key, value: old }); await Preferences.remove({ key }); }
      return old;
    },
    async set(value: string | null) {
      if (!android) { if (value) await Preferences.set({ key, value }); else await Preferences.remove({ key }); return; }
      if (value) await Secure.set({ key, value }); else await Secure.remove({ key });
    },
  };
}

export const refreshTokenStore = secureStore("basamak.refresh");
export const deviceTokenStore = secureStore("basamak.device");

/**
 * Notification permission is asked only when it is first useful (the first tool / workflow run), after explaining
 * why, never at sign-in. A "no" is remembered and not asked again from here (the system settings still work).
 */
export async function askNotificationPermissionOnce(): Promise<void> {
  if (!isMobile) return;
  try {
    const { LocalNotifications } = await import("@capacitor/local-notifications");
    const state = (await LocalNotifications.checkPermissions()).display;
    if (state !== "prompt" && state !== "prompt-with-rationale") return;
    const asked = await Preferences.get({ key: "basamak.notifyAsked" });
    if (asked.value) return;
    await Preferences.set({ key: "basamak.notifyAsked", value: "1" });
    const ok = window.confirm("İş bitince haber verelim mi?\n\nAraç ya da iş akışı arka planda çalışır. Bittiğinde (ya da hata verdiğinde) bildirim gönderip dokununca sonucu açabiliriz. İzni istediğin zaman telefon ayarlarından kapatabilirsin.");
    if (ok) await LocalNotifications.requestPermissions();
  } catch { /* no plugin (web) */ }
}
