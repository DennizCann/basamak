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
