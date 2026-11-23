import { Preferences } from "@capacitor/preferences";

/**
 * Platform switch for the same React code base:
 *  - web  : same-origin API (Vite proxy / reverse proxy), refresh token in an HttpOnly cookie.
 *  - mobile (Capacitor build, `vite --mode mobile`): API on VITE_API_BASE, refresh + remembered-device tokens
 *    returned in the body and kept in app storage (F6 moves them to Android Keystore / iOS Keychain).
 */
export const isMobile = import.meta.env.VITE_PLATFORM === "mobile";
export const apiBase = (import.meta.env.VITE_API_BASE as string | undefined) ?? "";

function store(key: string) {
  return {
    async get() { return (await Preferences.get({ key })).value; },
    async set(value: string | null) {
      if (value) await Preferences.set({ key, value });
      else await Preferences.remove({ key });
    },
  };
}

export const refreshTokenStore = store("basamak.refresh");
export const deviceTokenStore = store("basamak.device");
