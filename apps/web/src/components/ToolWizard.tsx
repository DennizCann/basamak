import { useState } from "react";
import { ChoiceCards, SelectField, TextArea, TextField, Wizard, errorMessage } from "@basamak/ui";
import { api } from "../auth";
import type { ToolInput } from "./ToolRunner";

export type ToolDef = {
  description?: string; inputs: ToolInput[]; request: { method: "GET" | "POST"; url: string; headers?: Record<string, string>; body?: unknown };
  timeoutSeconds: number; retries: number; outputPath?: string;
};
export type EditableTool = { id: string; name: string; definition: ToolDef; secrets: { name: string }[] };

const SECRET = "apiKey";
type Template = "blank" | "wiki" | "dict";
const TEMPLATES: { value: Template; title: string; description: string; name: string; def: ToolDef }[] = [
  { value: "blank", title: "Boş araç", description: "Her şeyi kendin tanımla.", name: "",
    def: { description: "", inputs: [{ name: "soru", label: "Soru", type: "string", required: true, maxLength: 200 }], request: { method: "GET", url: "https://", headers: {} }, timeoutSeconds: 10, retries: 2, outputPath: "" } },
  { value: "wiki", title: "Konu özeti (Vikipedi)", description: "Bir konunun Türkçe Vikipedi özetini getirir. Ücretsiz, anahtar gerekmez.", name: "Konu özeti",
    def: { description: "Bir konunun kısa Vikipedi özetini getirir.", inputs: [{ name: "konu", label: "Konu", type: "string", required: true, maxLength: 120 }],
      request: { method: "GET", url: "https://tr.wikipedia.org/api/rest_v1/page/summary/{{input.konu}}", headers: {} }, timeoutSeconds: 10, retries: 2, outputPath: "extract" } },
  { value: "dict", title: "İngilizce sözlük", description: "Bir İngilizce kelimenin anlamlarını getirir. Ücretsiz, anahtar gerekmez.", name: "İngilizce sözlük",
    def: { description: "İngilizce bir kelimenin anlamlarını listeler.", inputs: [{ name: "kelime", label: "Kelime", type: "string", required: true, maxLength: 60 }],
      request: { method: "GET", url: "https://api.dictionaryapi.dev/api/v2/entries/en/{{input.kelime}}", headers: {} }, timeoutSeconds: 10, retries: 2, outputPath: "0.meanings" } },
];

const TYPE_LABEL: Record<string, string> = { string: "Metin", number: "Sayı", boolean: "Evet / Hayır" };

/** Technical input name from the label: Turkish letters to ASCII, starts with a letter, unique. */
function slug(label: string, taken: string[]) {
  const map: Record<string, string> = { ç: "c", ğ: "g", ı: "i", İ: "i", ö: "o", ş: "s", ü: "u", Ç: "c", Ğ: "g", Ö: "o", Ş: "s", Ü: "u" };
  let s = label.replace(/[çğıİöşüÇĞÖŞÜ]/g, (c) => map[c]).toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "");
  if (!/^[a-z]/.test(s)) s = "alan" + (s ? "_" + s : "");
  s = s.slice(0, 40);
  let out = s, n = 2;
  while (taken.includes(out)) out = `${s}_${n++}`;
  return out;
}

/**
 * Member tool, step by step: 1) what it does (or a template), 2) what the user is asked, 3) where it connects (+ key),
 * 4) summary and save. Advanced options (timeout, retries, headers, body, result field) are folded away.
 */
export function ToolWizard({ tool, onClose, onSaved }: { tool: EditableTool | null; onClose: () => void; onSaved: (id: string, breaking: string[]) => void }) {
  const [step, setStep] = useState(0);
  const [template, setTemplate] = useState<Template | null>(tool ? null : "blank");
  const [name, setName] = useState(tool?.name ?? "");
  const [def, setDef] = useState<ToolDef>(tool?.definition ?? TEMPLATES[0].def);
  const keyHeader = Object.entries(def.request.headers ?? {}).find(([, v]) => v.includes(`{{secret.${SECRET}}}`))?.[0] ?? "";
  const [auth, setAuth] = useState<"none" | "key">(keyHeader ? "key" : "none");
  const [header, setHeader] = useState(keyHeader || "X-Api-Key");
  const [secret, setSecret] = useState("");
  const [bodyText, setBodyText] = useState(def.request.body ? JSON.stringify(def.request.body, null, 2) : "");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const hasSecret = !!tool?.secrets.find((s) => s.name === SECRET);

  const pick = (t: Template) => {
    const tpl = TEMPLATES.find((x) => x.value === t)!;
    setTemplate(t);
    setDef(tpl.def);
    if (tpl.name) setName(tpl.name);
    setBodyText("");
  };
  const setReq = (patch: Partial<ToolDef["request"]>) => setDef({ ...def, request: { ...def.request, ...patch } });
  const setInput = (i: number, patch: Partial<ToolInput>) => setDef({ ...def, inputs: def.inputs.map((x, j) => (j === i ? { ...x, ...patch } : x)) });

  const validate = (i: number): string | null => {
    if (i === 0 && !name.trim()) return "Araca bir ad ver.";
    if (i === 1) {
      if (def.inputs.some((x) => !x.label.trim())) return "Her alanın bir etiketi olmalı (kullanıcının göreceği soru).";
    }
    if (i === 2) {
      if (!/^https?:\/\/[^/{]+/.test(def.request.url.trim())) return "Adres http:// ya da https:// ile başlamalı ve sunucu adı içermeli.";
      const unknown = Array.from(def.request.url.matchAll(/\{\{\s*input\.([A-Za-z0-9_]+)\s*}}/g)).map((m) => m[1]).filter((n) => !def.inputs.some((x) => x.name === n));
      if (unknown.length) return `Adreste tanımlı olmayan alan var: ${unknown.join(", ")}. 2. adımdaki alanları kullan.`;
      if (auth === "key" && !header.trim()) return "Anahtarın gönderileceği başlık adını yaz (ör. X-Api-Key).";
      if (auth === "key" && !secret && !hasSecret) return "API anahtarını gir; şifrelenerek saklanır.";
      if (def.request.method === "POST" && bodyText.trim()) { try { JSON.parse(bodyText); } catch { return "İstek gövdesi geçerli bir JSON değil (Gelişmiş ayarlar)."; } }
    }
    return null;
  };

  const save = async () => {
    setSaving(true);
    setError(null);
    try {
      const headers: Record<string, string> = auth === "key" ? { [header.trim()]: `{{secret.${SECRET}}}` } : {};
      const autoBody = Object.fromEntries(def.inputs.map((x) => [x.name, `{{input.${x.name}}}`]));
      const body = def.request.method === "POST" ? (bodyText.trim() ? JSON.parse(bodyText) : autoBody) : undefined;
      const definition = { ...def, request: { method: def.request.method, url: def.request.url.trim(), headers, ...(body !== undefined ? { body } : {}) } };
      const payload = { name: name.trim(), definition, secrets: auth === "key" && secret ? { [SECRET]: secret } : undefined };
      if (tool) {
        const r = await api.put<{ id: string; breakingChanges?: string[] }>(`/api/my-tools/${tool.id}`, payload);
        onSaved(r.id, r.breakingChanges ?? []);
      } else {
        const r = await api.post<{ id: string }>("/api/my-tools", payload);
        onSaved(r.id, []);
      }
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setSaving(false);
    }
  };

  const steps = [
    { title: "Ne yapacak?", hint: "Aracın adı ve kısa açıklaması. Hazır bir örnekle başlayabilirsin." },
    { title: "Kullanıcıdan ne istenecek?", hint: "Araç çalıştırılırken doldurulacak alanlar. Her alan, formda bir soru olur." },
    { title: "Nereye bağlanacak?", hint: "Aracın istek göndereceği web servisi. İç ağ adresleri (localhost, 10.x, 192.168.x…) güvenlik nedeniyle kullanılamaz." },
    { title: "Özet ve kaydet", hint: "Kontrol et ve kaydet. Kaydettikten sonra aracı hemen deneyebilirsin." },
  ];

  return (
    <section className="card stack" aria-labelledby="tool-wizard-title">
      <h2 id="tool-wizard-title" style={{ margin: 0 }}>{tool ? `“${tool.name}” aracını düzenle` : "Yeni araç"}</h2>
      <Wizard steps={steps} current={step} onChange={setStep} validate={validate} finishLabel={tool ? "Kaydet (yeni sürüm)" : "Kaydet"}
              onFinish={save} busy={saving} onCancel={onClose} error={error}>
        {step === 0 && (
          <div className="stack">
            {!tool && <ChoiceCards name="tool-template" legend="Nasıl başlamak istersin?" value={template} onChange={pick}
                                   options={TEMPLATES.map(({ value, title, description }) => ({ value, title, description }))} />}
            <TextField label="Araç adı" required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} />
            <TextField label="Kısa açıklama (isteğe bağlı)" value={def.description ?? ""} onChange={(e) => setDef({ ...def, description: e.target.value })}
                       hint="Aracı çalıştıracak kişi ne işe yaradığını buradan anlar." />
          </div>
        )}

        {step === 1 && (
          <div className="stack">
            {def.inputs.length === 0 && <p className="muted">Bu araç kullanıcıdan bilgi istemiyor. İstersen alan ekle.</p>}
            <ol className="stack" style={{ listStyle: "none", padding: 0, margin: 0 }}>
              {def.inputs.map((x, k) => (
                <li key={k} className="card stack">
                  <div className="row space-between"><strong>{k + 1}. alan</strong>
                    <button type="button" className="btn btn-sm btn-danger" onClick={() => setDef({ ...def, inputs: def.inputs.filter((_, j) => j !== k) })}>Kaldır</button></div>
                  <TextField label="Soru / etiket" value={x.label} placeholder="ör. Konu" required
                             onChange={(e) => setInput(k, { label: e.target.value, ...(tool ? {} : { name: slug(e.target.value, def.inputs.filter((_, j) => j !== k).map((y) => y.name)) }) })}
                             hint={`Adreste kullanmak için: {{input.${x.name}}}`} />
                  <div className="row">
                    <SelectField label="Tür" value={x.type} onChange={(e) => setInput(k, { type: e.target.value as ToolInput["type"] })}>
                      {Object.entries(TYPE_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                    </SelectField>
                    <label className="choice"><input type="checkbox" checked={x.required} onChange={(e) => setInput(k, { required: e.target.checked })} /> <span>Zorunlu</span></label>
                  </div>
                </li>
              ))}
            </ol>
            <div><button type="button" className="btn" onClick={() => setDef({ ...def, inputs: [...def.inputs, { name: slug("alan", def.inputs.map((y) => y.name)), label: "", type: "string", required: false, maxLength: 200 }] })}>+ Alan ekle</button></div>
          </div>
        )}

        {step === 2 && (
          <div className="stack">
            <SelectField label="İstek türü" value={def.request.method} onChange={(e) => setReq({ method: e.target.value as "GET" | "POST" })}
                         hint="GET: bilgi sorar (çoğu servis). POST: bilgi gönderir; alanlar otomatik olarak JSON gövdesine konur.">
              <option value="GET">GET (bilgi al)</option>
              <option value="POST">POST (bilgi gönder)</option>
            </SelectField>
            <TextField label="Servis adresi (URL)" required value={def.request.url} onChange={(e) => setReq({ url: e.target.value })}
                       hint="Alanları adresin yol ya da sorgu kısmına ekleyebilirsin; sunucu adı sabit olmalı." />
            {def.inputs.length > 0 && (
              <div className="chip-row" aria-label="Adrese alan ekle">
                {def.inputs.map((x) => (
                  <button key={x.name} type="button" className="btn btn-sm" onClick={() => setReq({ url: def.request.url + `{{input.${x.name}}}` })}>+ {x.label || x.name}</button>
                ))}
              </div>
            )}
            <fieldset className="stack">
              <legend>Kimlik doğrulama</legend>
              <label className="choice"><input type="radio" name="auth" checked={auth === "none"} onChange={() => setAuth("none")} /> <span>Gerekmiyor</span></label>
              <label className="choice"><input type="radio" name="auth" checked={auth === "key"} onChange={() => setAuth("key")} /> <span>API anahtarı</span></label>
              {auth === "key" && (
                <div className="stack">
                  <TextField label="Anahtarın gönderileceği başlık" value={header} onChange={(e) => setHeader(e.target.value)} hint="Servisin belgesinde yazar; çoğu zaman X-Api-Key." />
                  <TextField label={hasSecret ? "API anahtarı (kayıtlı; değiştirmek için yaz)" : "API anahtarı"} type="password" autoComplete="off"
                             value={secret} onChange={(e) => setSecret(e.target.value)} hint="Şifrelenerek saklanır; ekranda, kayıtlarda ve paylaşımda asla görünmez." />
                </div>
              )}
            </fieldset>
            <details>
              <summary>Gelişmiş ayarlar</summary>
              <div className="stack" style={{ marginTop: "var(--space-3)" }}>
                <TextField label="Sonuç alanı (isteğe bağlı)" placeholder="ör. extract ya da data.result" value={def.outputPath ?? ""}
                           onChange={(e) => setDef({ ...def, outputPath: e.target.value })} hint="Cevabın yalnız bir kısmını göstermek için." />
                <div className="row">
                  <TextField label="Zaman aşımı (sn, 1-30)" type="number" min={1} max={30} value={def.timeoutSeconds} onChange={(e) => setDef({ ...def, timeoutSeconds: Number(e.target.value) })} />
                  <TextField label="Yeniden deneme (0-3)" type="number" min={0} max={3} value={def.retries} onChange={(e) => setDef({ ...def, retries: Number(e.target.value) })} />
                </div>
                {def.request.method === "POST" && (
                  <TextArea label="İstek gövdesi (JSON, boşsa alanlardan otomatik)" rows={4} value={bodyText} onChange={(e) => setBodyText(e.target.value)} hint={'Örnek: {"metin": "{{input.metin}}"}'} />
                )}
              </div>
            </details>
          </div>
        )}

        {step === 3 && (
          <dl className="summary-list">
            <dt>Ad</dt><dd>{name}</dd>
            {def.description && <><dt>Açıklama</dt><dd>{def.description}</dd></>}
            <dt>Sorulacaklar</dt><dd>{def.inputs.length ? def.inputs.map((x) => `${x.label} (${TYPE_LABEL[x.type]}${x.required ? ", zorunlu" : ""})`).join(" · ") : "—"}</dd>
            <dt>Bağlantı</dt><dd>{def.request.method} {def.request.url}</dd>
            <dt>Kimlik</dt><dd>{auth === "key" ? `API anahtarı (${header})` : "Gerekmiyor"}</dd>
            <dt>Ayarlar</dt><dd>{def.timeoutSeconds} sn zaman aşımı · {def.retries} yeniden deneme{def.outputPath ? ` · sonuç alanı: ${def.outputPath}` : ""}</dd>
          </dl>
        )}
      </Wizard>
    </section>
  );
}
