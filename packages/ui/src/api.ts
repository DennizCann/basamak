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
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (accessToken) headers["Authorization"] = `Bearer ${accessToken}`;
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), opts.timeoutMs ?? 20000);
    let res: Response;
    try {
      res = await fetch(base + path, {
        method,
        headers,
        credentials: "include",
        body: body === undefined ? undefined : JSON.stringify(body),
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
    setToken: (t: string | null) => (accessToken = t),
    hasToken: () => accessToken !== null,
    tryRestore: () => refresh(),
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
