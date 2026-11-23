import { useEffect, useState } from "react";
import { Preferences } from "@capacitor/preferences";
import { ApiError } from "@basamak/ui";
import { api } from "./auth";
import { isMobile } from "./platform";

/**
 * Offline support (mobile app only; the web app always talks to the server):
 *  - cachedGet: every successful read of a screen's data is stored; without a connection the stored copy is shown.
 *  - postOrQueue: a lesson completion, survey or quiz answer made offline goes into an outbox and is sent in order
 *    when the connection returns (app resume, "online" event, every 30 s). The server endpoints are idempotent
 *    (one completion per item, one survey per member, one submission per attempt), so a resent action never counts
 *    twice. The server's answer wins: if an action is rejected (e.g. the quiz time ran out), it is dropped and the
 *    member is told why.
 */
const OUTBOX = "basamak.outbox";
type Queued = { id: string; path: string; body?: unknown; label: string; createdAt: string };
export type SyncState = { pending: number; syncing: boolean; last: string | null; offline: boolean };

let state: SyncState = { pending: 0, syncing: false, last: null, offline: false };
const listeners = new Set<(s: SyncState) => void>();
const emit = (patch: Partial<SyncState>) => { state = { ...state, ...patch }; listeners.forEach((l) => l(state)); };

export function useSync(): SyncState {
  const [s, setS] = useState(state);
  useEffect(() => { listeners.add(setS); return () => { listeners.delete(setS); }; }, []);
  return s;
}

const isNetwork = (e: unknown) => e instanceof ApiError && e.code === "network";

export async function cachedGet<T>(path: string): Promise<{ data: T; offline: boolean; savedAt?: string }> {
  try {
    const data = await api.get<T>(path);
    if (isMobile) {
      await Preferences.set({ key: `cache:${path}`, value: JSON.stringify({ savedAt: new Date().toISOString(), data }) });
      if (state.offline) emit({ offline: false });
    }
    return { data, offline: false };
  } catch (e) {
    if (!isMobile || !isNetwork(e)) throw e;
    const raw = (await Preferences.get({ key: `cache:${path}` })).value;
    if (!raw) throw new ApiError("Çevrimdışısın ve bu ekranın kayıtlı bir kopyası yok. Bağlanınca tekrar dene.", 0, "network");
    emit({ offline: true });
    const c = JSON.parse(raw) as { savedAt: string; data: T };
    return { data: c.data, offline: true, savedAt: c.savedAt };
  }
}

/** Updates the stored copy too (so an offline completion shows as done after a restart). */
export async function patchCache<T>(path: string, fn: (d: T) => T) {
  if (!isMobile) return;
  const raw = (await Preferences.get({ key: `cache:${path}` })).value;
  if (!raw) return;
  const c = JSON.parse(raw) as { savedAt: string; data: T };
  await Preferences.set({ key: `cache:${path}`, value: JSON.stringify({ ...c, data: fn(c.data) }) });
}

async function read(): Promise<Queued[]> {
  const raw = (await Preferences.get({ key: OUTBOX })).value;
  return raw ? (JSON.parse(raw) as Queued[]) : [];
}
async function write(q: Queued[]) {
  await Preferences.set({ key: OUTBOX, value: JSON.stringify(q) });
  emit({ pending: q.length });
}

export async function postOrQueue<T>(path: string, body: unknown, label: string): Promise<T | { queued: true }> {
  try {
    return await api.post<T>(path, body);
  } catch (e) {
    if (!isMobile || !isNetwork(e)) throw e;
    const q = await read();
    q.push({ id: crypto.randomUUID(), path, body, label, createdAt: new Date().toISOString() });
    await write(q);
    emit({ offline: true });
    return { queued: true };
  }
}

let flushing = false;
export async function flushOutbox(): Promise<void> {
  if (!isMobile || flushing) return;
  flushing = true;
  try {
    let q = await read();
    emit({ pending: q.length });
    if (q.length === 0) return;
    emit({ syncing: true });
    const rejected: string[] = [];
    let sent = 0;
    while (q.length) {
      const item = q[0];
      try {
        await api.post(item.path, item.body);
        sent++;
      } catch (e) {
        if (isNetwork(e)) { emit({ offline: true }); break; } // still offline: keep the rest
        rejected.push(`${item.label}: ${(e as Error).message}`); // the server said no: drop it, tell the member
      }
      q = q.slice(1);
      await write(q);
    }
    if (q.length === 0) {
      emit({ offline: false, last: rejected.length
        ? `${sent} işlem eşitlendi. Kabul edilmeyen: ${rejected.join(" · ")}`
        : `${sent} çevrimdışı işlem eşitlendi.` });
      window.dispatchEvent(new Event("basamak:synced"));
      window.setTimeout(() => emit({ last: null }), 8000);
    }
  } finally {
    flushing = false;
    emit({ syncing: false });
  }
}

export function startSync() {
  if (!isMobile) return () => {};
  flushOutbox();
  const onOnline = () => flushOutbox();
  window.addEventListener("online", onOnline);
  const h = window.setInterval(flushOutbox, 30_000);
  return () => { window.removeEventListener("online", onOnline); window.clearInterval(h); };
}
