import { useEffect, useState, type ReactNode } from "react";
import { Preferences } from "@capacitor/preferences";
import { MediaProvider } from "@basamak/ui";
import { apiBase, isMobile } from "./platform";
import { cachedGet } from "./offline";

/**
 * Mobile "download for offline" + image cache:
 *  - downloadForOffline stores the training (and its lesson texts) like cachedGet does, and puts its images and
 *    attachments into the WebView's Cache Storage. Videos are not downloaded (size); they need a connection.
 *  - OfflineMediaProvider serves cached files as object URLs, so lesson images also show without a connection and are
 *    not downloaded again (images the member already looked at are cached by the WebView's HTTP cache as well).
 */
const CACHE = "basamak-media-v1";
const READY = "basamak.offlineTrainings";
const UUID_RE = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi;

const mediaUrl = (id: string) => `${apiBase}/api/public/media/${encodeURIComponent(id)}`;

type Item = { type: string; content?: string; attachments?: { mediaId: string }[]; video?: { mediaId?: string } };

export async function downloadForOffline(trainingId: string): Promise<{ files: number; skippedVideos: number }> {
  const { data } = await cachedGet<{ items: Item[] }>(`/api/trainings/${trainingId}`);
  const ids = new Set<string>();
  let skippedVideos = 0;
  for (const it of data.items) {
    if (it.type !== "lesson") continue;
    for (const m of (it.content ?? "").matchAll(/!\[[^\]]*\]\(media:([0-9a-f-]{36})\)/gi)) ids.add(m[1]);
    (it.attachments ?? []).forEach((a) => ids.add(a.mediaId));
    if (it.video?.mediaId) skippedVideos++;
  }
  const cache = await caches.open(CACHE);
  let files = 0;
  for (const id of ids) {
    const url = mediaUrl(id);
    if (await cache.match(url)) { files++; continue; }
    const res = await fetch(url);
    if (!res.ok) continue;
    await cache.put(url, res);
    files++;
  }
  const list = JSON.parse((await Preferences.get({ key: READY })).value ?? "[]") as string[];
  if (!list.includes(trainingId)) await Preferences.set({ key: READY, value: JSON.stringify([...list, trainingId]) });
  window.dispatchEvent(new Event("basamak:media-cached"));
  return { files, skippedVideos };
}

export async function isDownloaded(trainingId: string): Promise<boolean> {
  if (!isMobile) return false;
  return (JSON.parse((await Preferences.get({ key: READY })).value ?? "[]") as string[]).includes(trainingId);
}

export function OfflineMediaProvider({ children }: { children: ReactNode }) {
  const [urls, setUrls] = useState<Record<string, string>>({});
  useEffect(() => {
    if (!isMobile || typeof caches === "undefined") return;
    const load = async () => {
      const cache = await caches.open(CACHE);
      const next: Record<string, string> = {};
      for (const req of await cache.keys()) {
        const id = req.url.match(UUID_RE)?.[0];
        const res = await cache.match(req);
        if (id && res) next[id] = URL.createObjectURL(await res.blob());
      }
      setUrls((old) => { Object.values(old).forEach((u) => URL.revokeObjectURL(u)); return next; });
    };
    load().catch(() => {});
    window.addEventListener("basamak:media-cached", load);
    return () => window.removeEventListener("basamak:media-cached", load);
  }, []);
  return <MediaProvider resolve={(id) => urls[id] ?? mediaUrl(id)}>{children}</MediaProvider>;
}
