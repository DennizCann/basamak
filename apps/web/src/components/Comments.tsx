import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { Alert, TextArea, errorMessage, fmt } from "@basamak/ui";
import { api } from "../auth";

type C = { id: number; parentId: number | null; authorId: string | null; authorName: string; body: string; createdAt: string; deleted: boolean; hidden: boolean; mine: boolean };
const REASONS = ["Hakaret ya da küfür", "Spam ya da reklam", "Kişisel veri paylaşımı", "Konu dışı", "Diğer"];

/** Discussion under a training or a shared workflow: comments, one level of replies, report, delete own. */
export function Comments({ targetType, targetId }: { targetType: "TRAINING" | "WORKFLOW"; targetId: string }) {
  const [items, setItems] = useState<C[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const load = useCallback(async () => {
    try { setItems(await api.get<C[]>(`/api/comments?targetType=${targetType}&targetId=${targetId}`)); } catch (e) { setError(errorMessage(e)); }
  }, [targetType, targetId]);
  useEffect(() => { load(); }, [load]);

  const top = (items ?? []).filter((c) => c.parentId == null);
  const replies = (id: number) => (items ?? []).filter((c) => c.parentId === id);
  const visible = (items ?? []).filter((c) => !c.deleted && !c.hidden).length;

  return (
    <section className="card stack" aria-labelledby={`comments-${targetId}`}>
      <h2 id={`comments-${targetId}`}>Tartışma {items ? <span className="muted">({fmt.num(visible)})</span> : null}</h2>
      {error && <Alert kind="error"><p>{error}</p></Alert>}
      {notice && <p role="status" className="hint-line">{notice}</p>}
      <CommentForm label="Yorum yaz" onSubmit={async (body) => {
        await api.post("/api/comments", { targetType, targetId, body });
        setNotice("Yorumun eklendi.");
        setError(null);
        load();
      }} />
      {items && top.length === 0 && <p className="muted">Henüz yorum yok. İlk soruyu ya da notu sen yaz.</p>}
      <ol className="comment-list">
        {top.map((c) => (
          <li key={c.id} id={`yorum-${c.id}`}>
            <CommentView c={c} onChanged={load} onError={setError} onNotice={setNotice}
                         onReply={async (body) => { await api.post("/api/comments", { targetType, targetId, parentId: c.id, body }); load(); }} />
            {replies(c.id).length > 0 && (
              <ol className="comment-list comment-replies" aria-label="Cevaplar">
                {replies(c.id).map((r) => <li key={r.id} id={`yorum-${r.id}`}><CommentView c={r} onChanged={load} onError={setError} onNotice={setNotice} /></li>)}
              </ol>
            )}
          </li>
        ))}
      </ol>
      <p className="hint">Topluluk kuralları: saygılı ol; e-posta ve telefon gibi kişisel bilgiler otomatik gizlenir. Uygunsuz yorumları "Bildir" ile içerik ekibine iletebilirsin.</p>
    </section>
  );
}

function CommentForm({ label, onSubmit, onCancel }: { label: string; onSubmit: (body: string) => Promise<void>; onCancel?: () => void }) {
  const [body, setBody] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!body.trim()) { setErr("Yorum boş olamaz."); return; }
    setBusy(true);
    try { await onSubmit(body); setBody(""); setErr(null); onCancel?.(); } catch (x) { setErr(errorMessage(x)); } finally { setBusy(false); }
  };
  return (
    <form className="stack" onSubmit={submit}>
      <TextArea label={label} rows={3} maxLength={2000} value={body} error={err ?? undefined} onChange={(e) => setBody(e.target.value)} />
      <div className="row">
        <button className="btn btn-primary" disabled={busy}>{busy ? "Gönderiliyor…" : "Gönder"}</button>
        {onCancel && <button type="button" className="btn" onClick={onCancel}>Vazgeç</button>}
      </div>
    </form>
  );
}

function CommentView({ c, onReply, onChanged, onError, onNotice }: {
  c: C; onReply?: (b: string) => Promise<void>; onChanged: () => void; onError: (m: string) => void; onNotice: (m: string) => void;
}) {
  const [replying, setReplying] = useState(false);
  const [reporting, setReporting] = useState(false);
  const [reason, setReason] = useState(REASONS[0]);
  const gone = c.deleted || c.hidden;
  const remove = async () => {
    if (!window.confirm("Yorumun silinsin mi?")) return;
    try { await api.del(`/api/comments/${c.id}`); onChanged(); } catch (e) { onError(errorMessage(e)); }
  };
  const report = async (e: FormEvent) => {
    e.preventDefault();
    try { await api.post(`/api/comments/${c.id}/report`, { reason }); setReporting(false); onNotice("Bildirimin içerik ekibine iletildi. Teşekkürler."); }
    catch (x) { onError(errorMessage(x)); }
  };
  return (
    <article className="comment">
      <header className="hint-line">
        {c.authorId && !gone ? <Link className="text-link" to={`/u/${c.authorId}`}>{c.authorName}</Link> : <span>{gone ? "—" : c.authorName}</span>}
        {" · "}<time dateTime={c.createdAt}>{fmt.dateTime(c.createdAt)}</time>
      </header>
      <p className={gone ? "muted" : undefined} style={{ whiteSpace: "pre-wrap", margin: "4px 0" }}>{c.body}</p>
      {!gone && (
        <div className="row">
          {onReply && <button className="btn btn-sm" aria-expanded={replying} onClick={() => setReplying((r) => !r)}>Cevapla</button>}
          {c.mine
            ? <button className="btn btn-sm" onClick={remove}>Sil</button>
            : <button className="btn btn-sm" aria-expanded={reporting} onClick={() => setReporting((r) => !r)}>Bildir</button>}
        </div>
      )}
      {replying && onReply && <CommentForm label={`${c.authorName} kişisine cevap`} onSubmit={onReply} onCancel={() => setReplying(false)} />}
      {reporting && (
        <form className="row" onSubmit={report}>
          <label className="field">Bildirme nedeni
            <select value={reason} onChange={(e) => setReason(e.target.value)}>{REASONS.map((r) => <option key={r}>{r}</option>)}</select>
          </label>
          <button className="btn btn-sm btn-primary">Bildir</button>
        </form>
      )}
    </article>
  );
}

type RatingData = { count: number; average: number; mine: number | null };

/** 1-5 stars for a shared workflow (radio group: keyboard and screen reader friendly). */
export function Rating({ workflowId, canRate }: { workflowId: string; canRate: boolean }) {
  const [r, setR] = useState<RatingData | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { api.get<RatingData>(`/api/workflows/${workflowId}/rating`).then(setR).catch(() => {}); }, [workflowId]);
  const rate = async (stars: number) => {
    try { setR(await api.put<RatingData>(`/api/workflows/${workflowId}/rating`, { stars })); setError(null); } catch (e) { setError(errorMessage(e)); }
  };
  return (
    <div className="stack">
      <p style={{ margin: 0 }}>
        <strong>{r && r.count ? `${Number(r.average).toLocaleString("tr-TR")} / 5` : "Henüz puan yok"}</strong>
        {r && r.count ? <span className="muted"> · {fmt.num(r.count)} puan</span> : null}
      </p>
      {canRate && (
        <fieldset className="stars">
          <legend>Puanın</legend>
          {[1, 2, 3, 4, 5].map((s) => (
            <label key={s} className="star">
              <input type="radio" name={`rate-${workflowId}`} checked={r?.mine === s} onChange={() => rate(s)} />
              <span aria-hidden="true">{(r?.mine ?? 0) >= s ? "★" : "☆"}</span>
              <span className="visually-hidden">{s} yıldız</span>
            </label>
          ))}
        </fieldset>
      )}
      {error && <Alert kind="error"><p>{error}</p></Alert>}
    </div>
  );
}
