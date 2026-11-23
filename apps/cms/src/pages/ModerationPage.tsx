import { useCallback, useEffect, useState } from "react";
import { Alert, StateView, errorMessage, fmt } from "@basamak/ui";
import { api, useAuth } from "../auth";

type Report = {
  commentId: number; body: string; targetType: string; targetId: string; createdAt: string; authorId: string; authorName: string;
  restrictedUntil: string | null; reports: number; reasons: string; firstReportedAt: string; hiddenLast30: number;
};

/**
 * Community moderation (section 3.8): members report comments; the content team hides or dismisses.
 * The third hidden comment within 30 days restricts the author for 7 days automatically; manual restriction is here too.
 */
export function ModerationPage() {
  const { can } = useAuth();
  const [rows, setRows] = useState<Report[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const load = useCallback(async () => {
    setError(null);
    try { setRows(await api.get<Report[]>("/api/cms/moderation/reports")); } catch (e) { setError(errorMessage(e)); }
  }, []);
  useEffect(() => { if (can("EDITOR", "PUBLISHER")) load(); }, [load, can]);

  if (!can("EDITOR", "PUBLISHER")) {
    return <main className="container"><h1>Moderasyon</h1><Alert kind="info"><p>Moderasyonu editör, yayıncı ya da yönetici yapabilir.</p></Alert></main>;
  }
  const act = async (fn: () => Promise<string>) => {
    try { setNotice(await fn()); setError(null); load(); } catch (e) { setError(errorMessage(e)); }
  };

  return (
    <main className="container stack">
      <h1>Moderasyon</h1>
      <p className="muted">Üyelerin bildirdiği yorumlar. Gizlenen yorum üyelere "topluluk kuralları nedeniyle gizlendi" olarak görünür. Son 30 günde 3 yorumu gizlenen üye 7 gün yorum yazamaz (otomatik).</p>
      {error && <Alert kind="error"><p>{error}</p></Alert>}
      {notice && <Alert kind="success"><p>{notice}</p></Alert>}
      <StateView loading={!rows && !error} error={null} empty={rows?.length === 0} emptyText="Bekleyen bildirim yok.">
        <ul className="stack" style={{ listStyle: "none", padding: 0 }}>
          {rows?.map((r) => (
            <li key={r.commentId} className="card stack">
              <p style={{ margin: 0, whiteSpace: "pre-wrap" }}>{r.body}</p>
              <p className="hint" style={{ margin: 0 }}>
                {r.authorName} · {fmt.dateTime(r.createdAt)} · {r.targetType === "TRAINING" ? "Eğitim" : "İş akışı"} yorumu ·{" "}
                <strong>{fmt.num(r.reports)} bildirim</strong> ({r.reasons}) · son 30 günde gizlenen: {fmt.num(r.hiddenLast30)}
                {r.restrictedUntil && new Date(r.restrictedUntil) > new Date() ? ` · ${fmt.dateTime(r.restrictedUntil)} tarihine kadar kısıtlı` : ""}
              </p>
              <div className="row">
                <button className="btn btn-danger" onClick={() => act(async () => {
                  const res = await api.post<{ authorRestricted: boolean }>(`/api/cms/moderation/comments/${r.commentId}/hide`, { reason: r.reasons });
                  return res.authorRestricted ? "Yorum gizlendi. Üye 7 gün yorum yazamayacak (son 30 günde 3. gizleme)." : "Yorum gizlendi.";
                })}>Gizle</button>
                <button className="btn" onClick={() => act(async () => { await api.post(`/api/cms/moderation/comments/${r.commentId}/dismiss`); return "Bildirim kapatıldı; yorum yerinde kaldı."; })}>Sorun yok, kapat</button>
                <button className="btn" onClick={() => act(async () => {
                  await api.post(`/api/cms/moderation/users/${r.authorId}/restrict`, { days: 7, reason: "Elle kısıtlama" });
                  return `${r.authorName} 7 gün yorum yazamayacak.`;
                })}>Üyeyi 7 gün kısıtla</button>
              </div>
            </li>
          ))}
        </ul>
      </StateView>
    </main>
  );
}
