import { useEffect, useRef, useState, type ReactNode } from "react";
import { Alert, MediaProvider, TextField, errorMessage } from "@basamak/ui";
import { api } from "./auth";
import type { Json } from "./content";

export type MediaAsset = { id: string; kind: "IMAGE" | "VIDEO" | "FILE"; contentType: string; size: number; name: string; url: string };

const KIND_LABEL = { IMAGE: "Görsel", VIDEO: "Video", FILE: "Dosya" } as const;
const ACCEPT = "image/png,image/jpeg,image/gif,image/webp,video/mp4,video/webm,application/pdf,.docx,.xlsx,.pptx,.zip,.txt,.csv,.md";
const UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi;

function size(n: number) {
  return n >= 1024 * 1024 ? `${(n / 1024 / 1024).toLocaleString("tr-TR", { maximumFractionDigits: 1 })} MB` : `${Math.max(1, Math.round(n / 1024)).toLocaleString("tr-TR")} KB`;
}

/**
 * Draft media is not public: an <img>/<video> cannot send the bearer token, so the editor and preview use short-lived
 * signed URLs for every media id the draft references. Members get the public URL only after publication.
 */
export function CmsMediaProvider({ body, children }: { body: Json; children: ReactNode }) {
  const [urls, setUrls] = useState<Record<string, string>>({});
  const asked = useRef(new Set<string>());
  const ids = Array.from(new Set(JSON.stringify(body ?? {}).match(UUID) ?? [])).filter((id) => !asked.current.has(id));
  const key = ids.join(",");
  useEffect(() => {
    if (!ids.length) return;
    ids.forEach((id) => asked.current.add(id));
    api.post<Record<string, string>>("/api/cms/media/sign", { ids }).then((m) => setUrls((u) => ({ ...u, ...m }))).catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
  return <MediaProvider resolve={(id) => urls[id]}>{children}</MediaProvider>;
}

/** Lesson media editor: upload (type and size are checked on the server), then insert as image, video or attachment. */
export function LessonMedia({ lesson, onChange }: { lesson: Json; onChange: (l: Json) => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [last, setLast] = useState<MediaAsset | null>(null);
  const [alt, setAlt] = useState("");
  const [library, setLibrary] = useState<MediaAsset[] | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const attachments: { mediaId: string; name: string; size?: number }[] = lesson.attachments ?? [];

  const upload = async (file: File) => {
    setBusy(true);
    setError(null);
    try {
      const form = new FormData();
      form.append("file", file);
      const a = await api.upload<MediaAsset>("/api/cms/media", form);
      setLast(a);
      setAlt("");
      setLibrary((l) => (l ? [a, ...l] : l));
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
      if (fileRef.current) fileRef.current.value = "";
    }
  };

  const use = (a: MediaAsset) => {
    if (a.kind === "IMAGE") {
      if (!alt.trim()) { setError("Görseli eklemeden önce açıklamasını (alt metin) yazın; ekran okuyucu kullananlar görseli bu metinle anlar."); return; }
      const content = `${(lesson.content ?? "").trimEnd()}\n\n![${alt.replace(/[[\]]/g, "")}](media:${a.id})\n`;
      onChange({ ...lesson, content });
    } else if (a.kind === "VIDEO") {
      onChange({ ...lesson, video: { mediaId: a.id, minSeconds: lesson.video?.minSeconds ?? 0 } });
    } else if (!attachments.some((x) => x.mediaId === a.id)) {
      onChange({ ...lesson, attachments: [...attachments, { mediaId: a.id, name: a.name, size: a.size }] });
    }
    setError(null);
    setLast(null);
  };

  const action = (a: MediaAsset) => (a.kind === "IMAGE" ? "Metne ekle" : a.kind === "VIDEO" ? "Dersin videosu yap" : "Ek dosya olarak ekle");

  return (
    <div className="stack media-library" aria-label="Ders medyası">
      <h4 style={{ margin: 0 }}>Görsel, video ve dosyalar</h4>
      <p className="hint" style={{ margin: 0 }}>Görsel: PNG, JPEG, GIF, WebP (en çok 5 MB). Video: MP4, WebM (en çok 100 MB). Dosya: PDF, DOCX, XLSX, PPTX, ZIP, TXT, CSV (en çok 20 MB). Tür, dosyanın içeriğinden denetlenir; SVG ve HTML kabul edilmez.</p>
      <div className="row">
        <label className="btn" aria-disabled={busy}>
          {busy ? "Yükleniyor…" : "Dosya yükle"}
          <input ref={fileRef} type="file" accept={ACCEPT} className="sr-only" disabled={busy}
                 onChange={(e) => { const f = e.target.files?.[0]; if (f) upload(f); }} />
        </label>
        <button type="button" className="btn" onClick={() => api.get<MediaAsset[]>("/api/cms/media").then(setLibrary).catch((e) => setError(errorMessage(e)))}>
          Yüklenmiş dosyalardan seç
        </button>
      </div>
      {error && <Alert kind="error"><p>{error}</p></Alert>}
      {last && (
        <div className="card stack">
          <p style={{ margin: 0 }}><strong>Yüklendi:</strong> {last.name} · {KIND_LABEL[last.kind]} · {size(last.size)}</p>
          {last.kind === "IMAGE" && <TextField label="Görselin açıklaması (alt metin)" value={alt} onChange={(e) => setAlt(e.target.value)} hint="Örn. Journey haritasında üç seviye" />}
          <div className="row"><button type="button" className="btn btn-primary" onClick={() => use(last)}>{action(last)}</button></div>
        </div>
      )}
      {library && (
        <ul className="stack" style={{ listStyle: "none", padding: 0, margin: 0 }} aria-label="Yüklenmiş dosyalar">
          {library.length === 0 && <li className="muted">Henüz yüklenmiş dosya yok.</li>}
          {library.map((a) => (
            <li key={a.id} className="media-row">
              {a.kind === "IMAGE" && <img src={a.url} alt="" />}
              <span>{a.name} <span className="muted">· {KIND_LABEL[a.kind]} · {size(a.size)}</span></span>
              <button type="button" className="btn" onClick={() => { setLast(a); setAlt(""); }}>Seç</button>
            </li>
          ))}
        </ul>
      )}

      <div className="row" style={{ alignItems: "flex-end" }}>
        <TextField label="Video adresi (https, isteğe bağlı)" value={lesson.video?.url ?? ""} hint={lesson.video?.mediaId ? "Yüklenmiş bir video seçili." : "Yüklenmiş video yerine dış adres de kullanılabilir."}
                   onChange={(e) => onChange({ ...lesson, video: e.target.value ? { url: e.target.value, minSeconds: lesson.video?.minSeconds ?? 0 } : undefined })} />
        {lesson.video && (
          <TextField label="Tamamlamak için en az izleme (sn)" type="number" min={0} value={String(lesson.video.minSeconds ?? 0)}
                     onChange={(e) => onChange({ ...lesson, video: { ...lesson.video, minSeconds: Math.max(0, Number(e.target.value) || 0) } })} />
        )}
        {lesson.video && <button type="button" className="btn btn-danger" onClick={() => { const { video: _v, ...rest } = lesson; onChange(rest); }}>Videoyu kaldır</button>}
      </div>

      {attachments.length > 0 && (
        <ul className="stack" style={{ listStyle: "none", padding: 0, margin: 0 }} aria-label="Ek dosyalar">
          {attachments.map((a) => (
            <li key={a.mediaId} className="media-row">
              <span>📎 {a.name}{a.size ? <span className="muted"> · {size(a.size)}</span> : null}</span>
              <button type="button" className="btn btn-danger" aria-label={`${a.name} ekini kaldır`}
                      onClick={() => onChange({ ...lesson, attachments: attachments.filter((x) => x.mediaId !== a.mediaId) })}>Kaldır</button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
