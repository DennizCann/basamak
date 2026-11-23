/**
 * Small fetch wrapper shared by web, CMS and mobile.
 * - Access token lives only in memory (never localStorage) -> not readable by injected scripts after reload.
 * - On 401 it calls the refresh endpoint once (HttpOnly cookie + X-Requested-With CSRF header) and retries.
 * - Errors are RFC 7807 problem+json; the Turkish `detail` is shown to the user as-is.
 */
export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
    public code: string,
    public details: Record<string, unknown> = {},
  ) {
    super(message);
  }
}

export type ApiOptions = {
  baseUrl?: string;
  refreshPath: string;
  /** For mobile: supply/persist refresh tokens yourself (secure storage) instead of cookies. */
  refreshTokenStore?: { get(): Promise<string | null>; set(token: string | null): Promise<void> };
  onSessionLost?: () => void;
  timeoutMs?: number;
};

export type Api = ReturnType<typeof createApi>;

export function createApi(opts: ApiOptions) {
  let accessToken: string | null = null;
  let refreshing: Promise<boolean> | null = null;
  const base = opts.baseUrl ?? "";

  async function refresh(): Promise<boolean> {
    if (!refreshing) {
      refreshing = (async () => {
        try {
          const stored = opts.refreshTokenStore ? await opts.refreshTokenStore.get() : null;
          if (opts.refreshTokenStore && !stored) return false;
          const res = await fetch(base + opts.refreshPath, {
            method: "POST",
            credentials: "include",
            headers: { "X-Requested-With": "basamak", "Content-Type": "application/json" },
            body: stored ? JSON.stringify({ refreshToken: stored }) : undefined,
          });
          if (!res.ok) return false;
          const data = await res.json();
          accessToken = data.accessToken;
          if (opts.refreshTokenStore && data.refreshToken) await opts.refreshTokenStore.set(data.refreshToken);
          return true;
        } catch {
          return false;
        } finally {
          setTimeout(() => (refreshing = null), 0);
        }
      })();
    }
    return refreshing;
  }

  async function request<T>(method: string, path: string, body?: unknown, extraHeaders: Record<string, string> = {}, retry = true): Promise<T> {
    const headers: Record<string, string> = { ...extraHeaders };
    const isForm = typeof FormData !== "undefined" && body instanceof FormData;
    if (body !== undefined && !isForm) headers["Content-Type"] = "application/json";
    if (accessToken) headers["Authorization"] = `Bearer ${accessToken}`;
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), isForm ? 300_000 : opts.timeoutMs ?? 20000);
    let res: Response;
    try {
      res = await fetch(base + path, {
        method,
        headers,
        credentials: "include",
        body: body === undefined ? undefined : isForm ? (body as FormData) : JSON.stringify(body),
        signal: ctrl.signal,
      });
    } catch (e) {
      throw new ApiError(
        (e as Error).name === "AbortError"
          ? "Sunucu zamanında cevap vermedi. Bağlantınızı kontrol edip tekrar deneyin."
          : "Sunucuya ulaşılamadı. İnternet bağlantınızı kontrol edip tekrar deneyin.",
        0,
        "network",
      );
    } finally {
      clearTimeout(timer);
    }
    if (res.status === 401 && retry && !path.includes("/login")) {
      if (await refresh()) return request<T>(method, path, body, extraHeaders, false);
      accessToken = null;
      opts.onSessionLost?.();
    }
    if (!res.ok) {
      let p: Record<string, unknown> = {};
      try {
        p = await res.json();
      } catch {
        /* not json */
      }
      const msg =
        (p.detail as string) ||
        (res.status === 401
          ? "Oturumunuz sona erdi. Lütfen yeniden giriş yapın."
          : res.status === 403
            ? "Bu işlem için yetkiniz yok."
            : "Bir hata oluştu. Lütfen tekrar deneyin.");
      throw new ApiError(msg, res.status, (p.code as string) || String(res.status), p);
    }
    const text = await res.text();
    return (text ? JSON.parse(text) : undefined) as T;
  }

  return {
    get: <T>(p: string) => request<T>("GET", p),
    post: <T>(p: string, b?: unknown, h?: Record<string, string>) => request<T>("POST", p, b ?? {}, h),
    put: <T>(p: string, b?: unknown) => request<T>("PUT", p, b ?? {}),
    del: <T>(p: string) => request<T>("DELETE", p),
    /** multipart/form-data upload (the browser sets the boundary header). */
    upload: <T>(p: string, form: FormData) => request<T>("POST", p, form),
    setToken: (t: string | null) => (accessToken = t),
    hasToken: () => accessToken !== null,
    tryRestore: () => refresh(),
    /**
     * Server-Sent Events read with fetch (EventSource cannot send the Authorization header, and fetch works the
     * same in the browser and the mobile WebView). Resolves when the server closes the stream; the caller
     * reconnects with the last id it saw, and the server replays what was missed.
     */
    async stream(path: string, onEvent: (e: { id: number; event: string; data: unknown }) => void,
                 o: { signal?: AbortSignal; lastEventId?: number } = {}) {
      const open = () => fetch(base + path, {
        headers: {
          Accept: "text/event-stream",
          ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
          ...(o.lastEventId ? { "Last-Event-ID": String(o.lastEventId) } : {}),
        },
        credentials: "include",
        signal: o.signal,
      });
      let res = await open();
      if (res.status === 401 && (await refresh())) res = await open();
      if (!res.ok || !res.body) throw new ApiError("Canlı durum alınamadı.", res.status, "stream_failed");
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buf = "";
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true }).replace(/\r\n/g, "\n");
        let cut: number;
        while ((cut = buf.indexOf("\n\n")) >= 0) {
          const block = buf.slice(0, cut);
          buf = buf.slice(cut + 2);
          let id = 0, event = "message", data = "";
          for (const line of block.split("\n")) {
            if (line.startsWith("id:")) id = Number(line.slice(3).trim());
            else if (line.startsWith("event:")) event = line.slice(6).trim();
            else if (line.startsWith("data:")) data += line.slice(5).replace(/^ /, "");
          }
          if (data) onEvent({ id, event, data: JSON.parse(data) });
        }
      }
    },
    /** Authenticated binary fetch (mobile: the file is saved and shared natively instead of an <a download>). */
    async blob(path: string): Promise<Blob> {
      let res = await fetch(base + path, { headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {}, credentials: "include" });
      if (res.status === 401 && (await refresh())) res = await fetch(base + path, { headers: { Authorization: `Bearer ${accessToken}` }, credentials: "include" });
      if (!res.ok) throw new ApiError("Dosya alınamadı. Tekrar deneyin.", res.status, "download_failed");
      return res.blob();
    },
    /** Raw download with auth (e.g. certificate PDF). */
    async download(path: string, filename: string) {
      let res = await fetch(base + path, { headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {}, credentials: "include" });
      if (res.status === 401 && (await refresh())) {
        res = await fetch(base + path, { headers: { Authorization: `Bearer ${accessToken}` }, credentials: "include" });
      }
      if (!res.ok) throw new ApiError("Dosya indirilemedi. Tekrar deneyin.", res.status, "download_failed");
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = filename;
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    },
  };
}

export function errorMessage(e: unknown): string {
  return e instanceof ApiError ? e.message : "Beklenmeyen bir hata oluştu. Tekrar deneyin.";
}
