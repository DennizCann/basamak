import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { Alert, StateView, TextArea, TextField, errorMessage, fmt, ApiError } from "@basamak/ui";
import { api, useAuth } from "../auth";
import { AUDIT_LABEL, STATUS_LABEL, istanbulLocalToIso, listPathFor, typeLabel, type Item, type Json, type Version } from "../content";
import { JourneyForm, RuleForm, TrainingForm } from "../editors";
import { PreviewPlayer } from "../PreviewPlayer";

type Tab = "edit" | "preview" | "history" | "audit";

export function EditorPage() {
  const { id } = useParams();
  const { can } = useAuth();
  const [item, setItem] = useState<Item | null>(null);
  const [title, setTitle] = useState("");
  const [body, setBody] = useState<Json>(null);
  const [dirty, setDirty] = useState(false);
  const [tab, setTab] = useState<Tab>("edit");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [conflict, setConflict] = useState<ApiError | null>(null);
  const [trainings, setTrainings] = useState<Item[]>([]);
  const [jsonMode, setJsonMode] = useState(false);
  const [lockedKeys, setLockedKeys] = useState<string[]>([]);
  const [confirmRemoved, setConfirmRemoved] = useState<string[]>([]);

  const load = useCallback(async () => {
    try {
      const it = await api.get<Item>(`/api/cms/items/${id}`);
      setItem(it);
      setTitle(it.latest.title);
      setBody(structuredClone(it.latest.body));
      setDirty(false);
      setConflict(null);
      setConfirmRemoved([]);
      if (it.type === "JOURNEY" || it.type === "TRAINING") setTrainings(await api.get<Item[]>("/api/cms/items?type=TRAINING"));
      if (it.type === "TRAINING") {
        // keys that were ever published identify member progress: they become read-only in the editor
        const vs = await api.get<Version[]>(`/api/cms/items/${id}/versions`);
        setLockedKeys([...new Set(vs.filter((v) => v.publishedAt).flatMap((v) => (v.body?.items ?? []).map((i: Json) => i.key as string)))]);
      }
    } catch (e) { setError(errorMessage(e)); }
  }, [id]);
  useEffect(() => { load(); }, [load]);

  const change = (b: Json) => { setBody(b); setDirty(true); };

  async function save() {
    if (!item) return;
    setError(null); setNotice(null);
    try {
      const it = await api.put<Item>(`/api/cms/items/${id}/draft`, { lockVersion: item.lockVersion, title, body, confirmRemovedKeys: confirmRemoved });
      setItem(it);
      setDirty(false);
      setConfirmRemoved([]);
      setNotice(`Kaydedildi: yeni taslak s${it.latest.versionNo}.`);
    } catch (e) {
      if (e instanceof ApiError && e.code === "edit_conflict") setConflict(e);
      else setError(errorMessage(e));
    }
  }

  async function workflow(action: string, payload?: unknown) {
    if (!item) return;
    setError(null); setNotice(null);
    try {
      await api.post(`/api/cms/versions/${item.latest.id}/${action}`, payload);
      setNotice({ submit: "İncelemeye gönderildi.", approve: "Onaylandı.", "request-changes": "Yazara geri gönderildi.", publish: "Yayın işlemi tamamlandı." }[action] ?? "Tamam.");
      await load();
    } catch (e) { setError(errorMessage(e)); }
  }

  if (!item) return <main className="container"><StateView loading={!error} error={error} onRetry={load} /></main>;
  const v = item.latest;
  const editable = can("AUTHOR", "EDITOR");

  return (
    <main className="container stack">
      <p><Link to={listPathFor(item.type)}>← {typeLabel(item.type)} listesi</Link></p>
      <h1>{title || "Adsız"} <span className="muted" style={{ fontSize: "var(--font-size-md)" }}>({typeLabel(item.type)})</span></h1>

      <section className="card row" aria-label="Sürüm durumu" style={{ justifyContent: "space-between" }}>
        <div>
          <strong>Son sürüm s{v.versionNo}</strong> · <span className="badge badge-info">{STATUS_LABEL[v.status] ?? v.status}</span>
          <div className="muted">Yayındaki sürüm: {item.publishedVersionNo ? `s${item.publishedVersionNo}` : "yok"} · Sahibi: {item.owner}</div>
          {v.reviewNote && <div className="muted">Editör notu: “{v.reviewNote}”</div>}
          {v.scheduledAt && <div className="muted">Zamanlanan yayın: {fmt.dateTime(v.scheduledAt)} (İstanbul)</div>}
        </div>
        <WorkflowActions version={v} dirty={dirty} can={can} onAction={workflow} />
      </section>
      {item.publishedVersionNo && can("PUBLISHER") && <Unpublish itemId={item.id} versionNo={item.publishedVersionNo} onDone={(m) => { setNotice(m); load(); }} onError={setError} />}

      {error && <Alert kind="error"><p>{error}</p></Alert>}
      {notice && <Alert kind="success"><p>{notice}</p></Alert>}
      {conflict && (
        <Alert kind="warning">
          <p><strong>Kaydedilemedi: çakışma.</strong> {conflict.message}</p>
          <p>Sunucudaki son sürüm: s{String(conflict.details.latestVersionNo)}. Değişiklikleriniz bu tarayıcıda duruyor; aşağıdaki kopyayı alıp güncel sürümü yükleyebilirsiniz.</p>
          <details><summary>Kaydedilmemiş değişikliklerim (JSON)</summary><pre>{JSON.stringify({ title, body }, null, 2)}</pre></details>
          <div className="row">
            <button className="btn btn-primary" onClick={load}>Güncel sürümü yükle</button>
            <button className="btn" onClick={() => setConflict(null)}>Kapat</button>
          </div>
        </Alert>
      )}

      <div role="tablist" aria-label="Düzenleyici sekmeleri" className="row">
        {([["edit", "Düzenle"], ["preview", "Önizleme"], ["history", "Geçmiş ve fark"], ["audit", "Denetim kaydı"]] as [Tab, string][]).map(([k, l]) => (
          <button key={k} role="tab" aria-selected={tab === k} className={`btn ${tab === k ? "btn-primary" : ""}`} onClick={() => setTab(k)}>{l}</button>
        ))}
      </div>

      {tab === "edit" && (
        <section className="card stack" aria-label="Düzenle">
          <TextField label="Başlık" value={title} maxLength={200} disabled={!editable} onChange={(e) => { setTitle(e.target.value); setDirty(true); }} />
          <label className="choice"><input type="checkbox" checked={jsonMode} onChange={(e) => setJsonMode(e.target.checked)} /> Gelişmiş: JSON olarak düzenle</label>
          {jsonMode ? <JsonEditor value={body} onChange={change} disabled={!editable} />
            : item.type === "JOURNEY" ? <JourneyForm body={body} onChange={change} trainings={trainings} disabled={!editable} />
            : item.type === "TRAINING" ? <TrainingForm body={body} onChange={change} trainings={trainings.filter((t) => t.id !== item.id)} disabled={!editable}
                                                       lockedKeys={lockedKeys} onConfirmRemove={(k) => setConfirmRemoved((c) => [...new Set([...c, k])])} />
            : item.type === "POINT_RULE" ? <RuleForm body={body} onChange={change} disabled={!editable} />
            : <JsonEditor value={body} onChange={change} disabled={!editable} />}
          <div className="row">
            <button className="btn btn-primary" disabled={!editable || !dirty} onClick={save}>Yeni sürüm olarak kaydet</button>
            {!editable && <span className="hint">Düzenlemek için yazar ya da editör rolü gerekir.</span>}
            {editable && !dirty && <span className="hint">Değişiklik yapınca kaydedebilirsiniz.</span>}
          </div>
        </section>
      )}
      {tab === "preview" && (
        <Preview title={title} type={item.type} body={body}
                 label={`${dirty ? "kaydedilmemiş değişiklikler" : `taslak s${v.versionNo} (${STATUS_LABEL[v.status] ?? v.status})`} · yayındaki sürüm: ${item.publishedVersionNo ? "s" + item.publishedVersionNo : "yok"}`} />
      )}
      {tab === "history" && <History itemId={item.id} canPublish={can("PUBLISHER")} onChanged={load}
                                     titles={Object.fromEntries(trainings.map((t) => [t.id, t.latest.title]))} />}
      {tab === "audit" && <Audit itemId={item.id} />}
    </main>
  );
}

function WorkflowActions({ version, dirty, can, onAction }: { version: Version; dirty: boolean; can: (...r: string[]) => boolean; onAction: (a: string, p?: unknown) => void }) {
  const [note, setNote] = useState("");
  const [when, setWhen] = useState("");
  const s = version.status;
  return (
    <div className="stack" style={{ minWidth: "min(100%, 20rem)" }}>
      {s === "DRAFT" && can("AUTHOR", "EDITOR") && (
        <button className="btn btn-primary" disabled={dirty} onClick={() => onAction("submit")}>İncelemeye gönder</button>
      )}
      {s === "DRAFT" && dirty && <span className="hint">Önce değişikliklerinizi kaydedin.</span>}
      {s === "IN_REVIEW" && can("EDITOR") && (
        <>
          <TextArea label="İnceleme notu" hint="Geri gönderirken zorunlu." rows={2} value={note} onChange={(e) => setNote(e.target.value)} />
          <div className="row">
            <button className="btn btn-primary" onClick={() => onAction("approve", { note })}>Onayla</button>
            <button className="btn" disabled={!note.trim()} onClick={() => onAction("request-changes", { note })}>Gerekçeyle geri gönder</button>
          </div>
        </>
      )}
      {(s === "APPROVED" || s === "SCHEDULED") && can("PUBLISHER") && (
        <>
          <button className="btn btn-primary" onClick={() => onAction("publish")}>Şimdi yayınla</button>
          <div className="row">
            <div className="field"><label htmlFor="sched">İleri tarihe zamanla (İstanbul saati)</label>
              <input id="sched" type="datetime-local" value={when} onChange={(e) => setWhen(e.target.value)} /></div>
            <button className="btn" disabled={!when} onClick={() => onAction("publish", { scheduledAt: istanbulLocalToIso(when) })}>Zamanla</button>
          </div>
        </>
      )}
      {s === "IN_REVIEW" && !can("EDITOR") && <span className="muted">Editör incelemesi bekleniyor.</span>}
      {s === "APPROVED" && !can("PUBLISHER") && <span className="muted">Yayıncı bekleniyor.</span>}
    </div>
  );
}

/** Takes the content off the member side; history stays and the archived version can be re-published. */
function Unpublish({ itemId, versionNo, onDone, onError }: { itemId: string; versionNo: number; onDone: (m: string) => void; onError: (m: string) => void }) {
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const submit = async () => {
    if (!window.confirm(`Yayındaki s${versionNo} üyelerden kaldırılacak. Sürüm geçmişi korunur ve daha sonra yeniden yayınlanabilir. Devam edilsin mi?`)) return;
    setBusy(true);
    try { await api.post(`/api/cms/items/${itemId}/unpublish`, { reason }); setReason(""); onDone("Yayından kaldırıldı. Sürüm geçmişi korundu."); }
    catch (e) { onError(errorMessage(e)); }
    finally { setBusy(false); }
  };
  return (
    <details className="card">
      <summary>Yayından kaldır</summary>
      <div className="stack" style={{ marginTop: "var(--space-3)" }}>
        <p className="muted" style={{ margin: 0 }}>İçerik üyelere görünmez olur; hiçbir sürüm silinmez. İşlem denetim kaydına gerekçesiyle yazılır.</p>
        <TextArea label="Gerekçe (zorunlu)" rows={2} value={reason} onChange={(e) => setReason(e.target.value)} />
        <div><button className="btn btn-danger" disabled={!reason.trim() || busy} onClick={submit}>Yayından kaldır</button></div>
      </div>
    </details>
  );
}

function JsonEditor({ value, onChange, disabled }: { value: Json; onChange: (v: Json) => void; disabled?: boolean }) {
  const [text, setText] = useState(JSON.stringify(value, null, 2));
  const [err, setErr] = useState<string | null>(null);
  return (
    <TextArea label="İçerik gövdesi (JSON)" rows={18} value={text} disabled={disabled} error={err}
              style={{ fontFamily: "var(--font-mono)" }}
              onChange={(e) => {
                setText(e.target.value);
                try { onChange(JSON.parse(e.target.value)); setErr(null); } catch { setErr("Geçerli bir JSON değil; kaydetmeden önce düzeltin."); }
              }} />
  );
}

/** Shows the draft the way members will see it, at phone and desktop widths. */
function Preview({ title, type, body, label }: { title: string; type: string; body: Json; label: string }) {
  const [width, setWidth] = useState(390);
  return (
    <section className="stack" aria-label="Önizleme">
      <div className="row" role="group" aria-label="Önizleme genişliği">
        {[390, 1440].map((w) => <button key={w} className={`btn ${width === w ? "btn-primary" : ""}`} aria-pressed={width === w} onClick={() => setWidth(w)}>{w === 390 ? "Mobil (390)" : "Masaüstü (1440)"}</button>)}
      </div>
      {width > 900 && <p className="hint">Masaüstü görünümü 1440 piksel genişliğinde; ekranınız daha darsa çerçeve içinde yatay kaydırın.</p>}
      {/* the frame scrolls inside itself, so a 1440 px preview never widens the CMS page */}
      <div style={{ overflowX: "auto", maxWidth: "100%", border: "1px dashed var(--color-border)", borderRadius: "var(--radius-md)" }}>
        <div className="preview-frame" style={{ width, minWidth: width, margin: "0 auto", padding: "var(--space-4)", background: "var(--color-bg)" }}>
          <h1>{title}</h1>
          {type === "TRAINING" ? (
            <PreviewPlayer body={body} label={label} />
          ) : type === "JOURNEY" ? (
            <div className="card"><p>{body?.description}</p><p className="muted">{body?.trainingIds?.length ?? 0} eğitim</p></div>
          ) : <pre>{JSON.stringify(body, null, 2)}</pre>}
        </div>
      </div>
    </section>
  );
}

type Diff = { from: number; to: number; titleChanged: boolean; fromTitle: string; toTitle: string; changes: { path: string; op: string; before: Json; after: Json }[] };

/** Shows a referenced content id by its title when known; the raw value stays available on demand. */
function DiffValue({ value, titles }: { value: Json; titles: Record<string, string> }) {
  if (value === null || value === undefined) return <>—</>;
  if (typeof value === "string" && titles[value]) return <>{titles[value]} <span className="muted">(eğitim)</span></>;
  const text = JSON.stringify(value, null, 1);
  if (typeof value !== "object" && text.length <= 80) return <>{typeof value === "string" ? value : text}</>;
  return <details><summary>{Array.isArray(value) ? `liste (${value.length})` : typeof value === "object" ? "nesne" : text.slice(0, 60) + "…"}</summary><pre>{text}</pre></details>;
}

function History({ itemId, canPublish, onChanged, titles }: { itemId: string; canPublish: boolean; onChanged: () => void; titles: Record<string, string> }) {
  const [versions, setVersions] = useState<Version[] | null>(null);
  const [from, setFrom] = useState<number | null>(null);
  const [to, setTo] = useState<number | null>(null);
  const [diff, setDiff] = useState<Diff | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    api.get<Version[]>(`/api/cms/items/${itemId}/versions`).then((vs) => {
      setVersions(vs);
      if (vs.length > 1) { setFrom(vs[1].versionNo); setTo(vs[0].versionNo); }
    }).catch((e) => setError(errorMessage(e)));
  }, [itemId]);
  useEffect(load, [load]);
  useEffect(() => {
    if (from && to && from !== to) api.get<Diff>(`/api/cms/items/${itemId}/diff?from=${from}&to=${to}`).then(setDiff).catch((e) => setError(errorMessage(e)));
    else setDiff(null);
  }, [itemId, from, to]);

  const republish = async (v: Version) => {
    try { await api.post(`/api/cms/versions/${v.id}/publish`); load(); onChanged(); }
    catch (e) { setError(errorMessage(e)); }
  };

  return (
    <section className="stack" aria-label="Geçmiş ve fark">
      {error && <Alert kind="error"><p>{error}</p></Alert>}
      <StateView loading={!versions && !error}>
        <div className="card table-wrap">
          <table>
            <thead><tr><th>Sürüm</th><th>Durum</th><th>Hazırlayan</th><th>İnceleyen</th><th>Yayın</th><th></th></tr></thead>
            <tbody>
              {versions?.map((v) => (
                <tr key={v.id}>
                  <td>s{v.versionNo}{v.basedOnNo ? <span className="muted"> (s{v.basedOnNo} üzerine)</span> : null}</td>
                  <td>{STATUS_LABEL[v.status] ?? v.status}</td>
                  <td>{v.createdBy}<br /><span className="muted">{fmt.dateTime(v.createdAt)}</span></td>
                  <td>{v.reviewedBy ?? "—"}{v.reviewNote ? <><br /><span className="muted">“{v.reviewNote}”</span></> : null}</td>
                  <td>{v.publishedAt ? fmt.dateTime(v.publishedAt) : "—"}</td>
                  <td>{v.status === "ARCHIVED" && canPublish && <button className="btn" onClick={() => republish(v)}>Bu sürümü yeniden yayınla</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {versions && versions.length > 1 && (
          <div className="card stack">
            <h2>Fark görünümü</h2>
            <div className="row">
              <label>Eski <select value={from ?? ""} onChange={(e) => setFrom(Number(e.target.value))}>{versions.map((v) => <option key={v.id} value={v.versionNo}>s{v.versionNo}</option>)}</select></label>
              <label>Yeni <select value={to ?? ""} onChange={(e) => setTo(Number(e.target.value))}>{versions.map((v) => <option key={v.id} value={v.versionNo}>s{v.versionNo}</option>)}</select></label>
            </div>
            {diff && (diff.changes.length === 0 && !diff.titleChanged ? <p className="muted">İki sürüm aynı.</p> : (
              <div className="table-wrap">
                <table>
                  <thead><tr><th>Alan</th><th>Değişiklik</th><th>s{diff.from}</th><th>s{diff.to}</th></tr></thead>
                  <tbody>
                    {diff.titleChanged && <tr><td>başlık</td><td className="diff-changed">değişti</td><td><pre>{diff.fromTitle}</pre></td><td><pre>{diff.toTitle}</pre></td></tr>}
                    {diff.changes.map((c, i) => (
                      <tr key={i}>
                        <td>{fieldLabel(c.path)}<br /><code className="muted" style={{ fontSize: "var(--font-size-xs)" }}>{c.path}</code></td>
                        <td className={`diff-${c.op}`}>{{ added: "eklendi", removed: "silindi", changed: "değişti" }[c.op] ?? c.op}</td>
                        <td><DiffValue value={c.before} titles={titles} /></td>
                        <td><DiffValue value={c.after} titles={titles} /></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ))}
          </div>
        )}
      </StateView>
    </section>
  );
}

const FIELD_LABEL: Record<string, string> = {
  trainingIds: "Eğitimler", description: "Açıklama", order: "Sıra", unlock: "Açılma koşulu", requiresPreviousJourney: "Önceki Journey şartı",
  minPoints: "En az puan", summary: "Özet", prerequisites: "Ön koşullar", items: "İçerik ögeleri", title: "Başlık", content: "Ders metni",
  questions: "Sorular", options: "Şıklar", text: "Metin", correct: "Doğru şık", passingScore: "Geçme notu", attemptsAllowed: "Deneme hakkı",
  timeLimitSeconds: "Süre", questionsPerAttempt: "Deneme başına soru", showCorrectAnswers: "Doğru cevapları göster", anonymous: "Anonim",
  action: "Aksiyon", objectType: "Nesne türü", points: "Puan", policy: "Tekrar politikası", key: "Anahtar", type: "Tür",
};
/** "/items/2/questions/0/text" -> "İçerik ögeleri 3 › Sorular 1 › Metin" */
function fieldLabel(path: string): string {
  return path.split("/").filter(Boolean).map((p) => (/^\d+$/.test(p) ? String(Number(p) + 1) : FIELD_LABEL[p] ?? p)).join(" › ") || "Gövde";
}

function Audit({ itemId }: { itemId: string }) {
  const [rows, setRows] = useState<{ id: number; action: string; actor: string; created_at: string; details: string }[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { api.get<typeof rows>(`/api/cms/items/${itemId}/audit`).then(setRows).catch((e) => setError(errorMessage(e))); }, [itemId]);
  return (
    <StateView loading={!rows && !error} error={error} empty={rows?.length === 0}>
      <div className="card table-wrap">
        <table>
          <thead><tr><th>Zaman</th><th>Kim</th><th>İşlem</th><th>Ayrıntı</th></tr></thead>
          <tbody>{rows?.map((r) => (
            <tr key={r.id}>
              <td>{fmt.dateTime(r.created_at)}</td>
              <td>{r.actor ?? "Sistem"}</td>
              <td>{AUDIT_LABEL[r.action] ?? r.action}</td>
              <td><details><summary>Ayrıntı</summary><p className="muted">İşlem kodu: <code>{r.action}</code></p><pre>{r.details}</pre></details></td>
            </tr>
          ))}</tbody>
        </table>
      </div>
    </StateView>
  );
}
