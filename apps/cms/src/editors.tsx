import { Alert, ITEM_TYPE_LABEL, SelectField, TextArea, TextField, trCompare } from "@basamak/ui";
import type { Item, Json } from "./content";
import { LessonMedia } from "./LessonMedia";

type FormProps = { body: Json; onChange: (b: Json) => void; disabled?: boolean };

export function JourneyForm({ body, onChange, trainings, disabled }: FormProps & { trainings: Item[] }) {
  const set = (k: string, v: unknown) => onChange({ ...body, [k]: v });
  const ids: string[] = body.trainingIds ?? [];
  const move = (i: number, d: number) => {
    const next = [...ids];
    const j = i + d;
    if (j < 0 || j >= next.length) return;
    [next[i], next[j]] = [next[j], next[i]];
    set("trainingIds", next);
  };
  const title = (id: string) => trainings.find((t) => t.id === id)?.latest.title ?? "Silinmiş ya da erişilemeyen eğitim";
  const publishedCount = ids.filter((id) => trainings.find((t) => t.id === id)?.publishedVersionNo).length;
  return (
    <fieldset disabled={disabled} className="stack">
      <legend>Journey</legend>
      {publishedCount === 0 && (
        <Alert kind="warning">
          <p>Bu Journey'de yayınlanmış eğitim yok. Üyeler ilerleyemeyeceği için bu hâliyle <strong>yayınlanamaz</strong>; aşağıdan en az bir yayınlanmış eğitim ekleyin.</p>
        </Alert>
      )}
      <TextArea label="Açıklama" rows={2} value={body.description ?? ""} onChange={(e) => set("description", e.target.value)} />
      <TextField label="Sıra" type="number" min={1} value={body.order ?? 1} onChange={(e) => set("order", Number(e.target.value))} />
      <label className="choice"><input type="checkbox" checked={!!body.unlock?.requiresPreviousJourney}
             onChange={(e) => set("unlock", { ...body.unlock, requiresPreviousJourney: e.target.checked })} /> Önceki Journey bitmiş olmalı</label>
      <TextField label="Açılması için gereken en az puan" type="number" min={0} value={body.unlock?.minPoints ?? 0}
                 onChange={(e) => set("unlock", { ...body.unlock, minPoints: Number(e.target.value) })} />
      <h3>Eğitimler (sıralı)</h3>
      <ol className="list-plain">
        {ids.map((id, i) => (
          <li key={id} className="row">
            <span style={{ flex: 1 }}>{i + 1}. {title(id)}</span>
            <button type="button" className="btn" aria-label={`${title(id)} yukarı taşı`} onClick={() => move(i, -1)}>↑</button>
            <button type="button" className="btn" aria-label={`${title(id)} aşağı taşı`} onClick={() => move(i, 1)}>↓</button>
            <button type="button" className="btn btn-danger" onClick={() => set("trainingIds", ids.filter((x) => x !== id))}>Çıkar</button>
          </li>
        ))}
      </ol>
      <SelectField label="Eğitim ekle" value="" onChange={(e) => e.target.value && set("trainingIds", [...ids, e.target.value])}>
        <option value="">Seçin…</option>
        {trainings.filter((t) => !ids.includes(t.id)).map((t) => <option key={t.id} value={t.id}>{t.latest.title}{t.publishedVersionNo ? "" : " (yayında değil)"}</option>)}
      </SelectField>
    </fieldset>
  );
}

export function RuleForm({ body, onChange, disabled }: FormProps) {
  const set = (k: string, v: unknown) => onChange({ ...body, [k]: v });
  return (
    <fieldset disabled={disabled} className="stack">
      <legend>Puan kuralı</legend>
      <TextField label="Aksiyon" hint="ör. LESSON_VIEWED, QUIZ_PASSED, TOOL_CREATED" value={body.action ?? ""} onChange={(e) => set("action", e.target.value.toUpperCase())} />
      <TextField label="Nesne türü" hint="LESSON, QUIZ, TRAINING, JOURNEY, TOOL, WORKFLOW ya da * (hepsi)" value={body.objectType ?? "*"} onChange={(e) => set("objectType", e.target.value.toUpperCase())} />
      <TextField label="Puan" type="number" value={body.points ?? 0} onChange={(e) => set("points", Number(e.target.value))} />
      <SelectField label="Tekrar politikası" value={body.policy ?? "ONCE_PER_OBJECT"} onChange={(e) => set("policy", e.target.value)}>
        <option value="ONCE_PER_OBJECT">Nesne başına bir kez</option>
        <option value="ONCE_PER_DAY">Nesne başına günde bir kez (İstanbul günü)</option>
        <option value="EVERY">Her olayda</option>
      </SelectField>
    </fieldset>
  );
}

export function TrainingForm({ body, onChange, trainings, tools = [], disabled, lockedKeys = [], onConfirmRemove }:
  FormProps & { trainings: Item[]; tools?: Item[]; lockedKeys?: string[]; onConfirmRemove?: (key: string) => void }) {
  const set = (k: string, v: unknown) => onChange({ ...body, [k]: v });
  const items: Json[] = body.items ?? [];
  const remove = (i: number) => {
    const key = items[i].key as string;
    if (lockedKeys.includes(key)) {
      const ok = window.confirm(`"${items[i].title}" daha önce yayınlandı. Silerseniz üyelerin bu ögedeki ilerlemesi yeni sürüme taşınmaz ve öge geri eklenirse yeniden puan verilebilir. Silmek istediğinize emin misiniz?`);
      if (!ok) return;
      onConfirmRemove?.(key);
    }
    set("items", items.filter((_, j) => j !== i));
  };
  const setItem = (i: number, v: Json) => set("items", items.map((x, j) => (j === i ? v : x)));
  const add = (type: string) => {
    const n = items.length + 1;
    const base = { key: `${type}-${n}-${Date.now().toString(36).slice(-3)}`, type, title: "" };
    const extra = type === "lesson" ? { content: "" }
      : type === "quiz" ? { passingScore: 60, attemptsAllowed: 3, timeLimitSeconds: 600, questionsPerAttempt: 1, showCorrectAnswers: true, shuffleOptions: true,
          questions: [{ id: "q1", type: "single", text: "", points: 1, options: [{ id: "a", text: "", correct: true }, { id: "b", text: "" }] }] }
      : type === "tool" ? { toolId: tools.find((t) => t.publishedVersionNo)?.id ?? "", toolVersionNo: tools.find((t) => t.publishedVersionNo)?.publishedVersionNo ?? 1 }
      : { anonymous: false, questions: [{ id: "s1", type: "scale", text: "" }] };
    set("items", [...items, { ...base, ...extra }]);
  };
  const move = (i: number, d: number) => {
    const next = [...items];
    const j = i + d;
    if (j < 0 || j >= next.length) return;
    [next[i], next[j]] = [next[j], next[i]];
    set("items", next);
  };
  return (
    <fieldset disabled={disabled} className="stack">
      <legend>Eğitim</legend>
      <TextArea label="Özet" rows={2} value={body.summary ?? ""} onChange={(e) => set("summary", e.target.value)} />
      <SelectField label="Ön koşul eğitimleri" multiple value={body.prerequisites ?? []} hint="Ctrl ile birden çok seçin. Döngü oluşturan seçim kaydedilmez."
                   onChange={(e) => set("prerequisites", Array.from(e.target.selectedOptions).map((o) => o.value))}>
        {[...trainings].sort((a, b) => trCompare(a.latest.title, b.latest.title)).map((t) => <option key={t.id} value={t.id}>{t.latest.title}</option>)}
      </SelectField>
      <h3>İçerik ögeleri</h3>
      {items.map((it, i) => (
        <div key={i} className="card stack">
          <div className="row" style={{ justifyContent: "space-between" }}>
            <strong>{i + 1}. {ITEM_TYPE_LABEL[it.type as string] ?? it.type}</strong>
            <div className="row">
              <button type="button" className="btn" aria-label="Yukarı taşı" onClick={() => move(i, -1)}>↑</button>
              <button type="button" className="btn" aria-label="Aşağı taşı" onClick={() => move(i, 1)}>↓</button>
              <button type="button" className="btn btn-danger" onClick={() => remove(i)}>Sil</button>
            </div>
          </div>
          <TextField label="Başlık" value={it.title} onChange={(e) => setItem(i, { ...it, title: e.target.value })} />
          {lockedKeys.includes(it.key) ? (
            <TextField label="Anahtar (sabit)" value={it.key} readOnly
                       hint="Bu öge yayınlandı; üye ilerlemesi bu anahtarla izlendiği için değiştirilemez. Başlığı serbestçe değiştirebilirsiniz." />
          ) : (
            <TextField label="Anahtar" hint="Yeni öge: ilk yayından sonra sabitlenir." value={it.key}
                       onChange={(e) => setItem(i, { ...it, key: e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, "-") })} />
          )}
          {it.type === "lesson" && <TextArea label="Ders metni (Markdown)" rows={8} value={it.content ?? ""} hint="Başlık: #, kalın: **metin**, liste: -, bağlantı: [metin](https://…), görsel: aşağıdan yükleyip ekleyin." onChange={(e) => setItem(i, { ...it, content: e.target.value })} />}
          {it.type === "lesson" && <LessonMedia lesson={it} onChange={(l) => setItem(i, l)} />}
          {it.type === "quiz" && <QuizEditor quiz={it} onChange={(q) => setItem(i, q)} />}
          {it.type === "survey" && <SurveyEditor survey={it} onChange={(s) => setItem(i, s)} />}
          {it.type === "tool" && <ToolPin item={it} tools={tools} onChange={(t) => setItem(i, t)} />}
        </div>
      ))}
      <div className="row">
        <button type="button" className="btn" onClick={() => add("lesson")}>+ Ders</button>
        <button type="button" className="btn" onClick={() => add("quiz")}>+ Quiz</button>
        <button type="button" className="btn" onClick={() => add("survey")}>+ Anket</button>
        <button type="button" className="btn" onClick={() => add("tool")} disabled={!tools.some((t) => t.publishedVersionNo)}
                title={tools.some((t) => t.publishedVersionNo) ? undefined : "Önce bir aracı yayınlayın."}>+ Araç</button>
      </div>
    </fieldset>
  );
}

function QuizEditor({ quiz, onChange }: { quiz: Json; onChange: (q: Json) => void }) {
  const set = (k: string, v: unknown) => onChange({ ...quiz, [k]: v });
  const qs: Json[] = quiz.questions ?? [];
  const setQ = (i: number, v: Json) => set("questions", qs.map((x, j) => (j === i ? v : x)));
  return (
    <div className="stack">
      <div className="grid">
        <TextField label="Geçme notu (%)" type="number" min={0} max={100} value={quiz.passingScore} onChange={(e) => set("passingScore", Number(e.target.value))} />
        <TextField label="Deneme hakkı" type="number" min={1} value={quiz.attemptsAllowed} onChange={(e) => set("attemptsAllowed", Number(e.target.value))} />
        <TextField label="Süre (saniye, 0 = sınırsız)" type="number" min={0} value={quiz.timeLimitSeconds} onChange={(e) => set("timeLimitSeconds", Number(e.target.value))} />
        <TextField label="Deneme başına soru" type="number" min={1} max={qs.length} value={quiz.questionsPerAttempt} onChange={(e) => set("questionsPerAttempt", Number(e.target.value))} />
      </div>
      <label className="choice"><input type="checkbox" checked={!!quiz.showCorrectAnswers} onChange={(e) => set("showCorrectAnswers", e.target.checked)} /> Üye sonuçta doğru cevapları görsün</label>
      <h4>Soru bankası ({qs.length})</h4>
      {qs.map((q, i) => (
        <fieldset key={i} className="stack">
          <legend>Soru {i + 1}</legend>
          <TextField label="Soru metni" value={q.text} onChange={(e) => setQ(i, { ...q, text: e.target.value })} />
          <SelectField label="Tür" value={q.type} onChange={(e) => setQ(i, { ...q, type: e.target.value })}>
            <option value="single">Tek seçimli</option><option value="multi">Çok seçimli</option><option value="open">Açık uçlu</option>
          </SelectField>
          {q.type !== "open" && (q.options ?? []).map((o: Json, k: number) => (
            <div key={k} className="row">
              <input aria-label={`Şık ${k + 1}`} value={o.text} onChange={(e) => setQ(i, { ...q, options: q.options.map((x: Json, m: number) => (m === k ? { ...x, text: e.target.value } : x)) })} style={{ flex: 1, minWidth: 0 }} />
              <label className="choice"><input type="checkbox" checked={!!o.correct}
                     onChange={(e) => setQ(i, { ...q, options: q.options.map((x: Json, m: number) => (m === k ? { ...x, correct: e.target.checked } : q.type === "single" && e.target.checked ? { ...x, correct: false } : x)) })} /> Doğru</label>
            </div>
          ))}
          {q.type !== "open" && <button type="button" className="btn" onClick={() => setQ(i, { ...q, options: [...(q.options ?? []), { id: String.fromCharCode(97 + (q.options?.length ?? 0)), text: "" }] })}>+ Şık</button>}
          <button type="button" className="btn btn-danger" onClick={() => set("questions", qs.filter((_, j) => j !== i))}>Soruyu sil</button>
        </fieldset>
      ))}
      <button type="button" className="btn" onClick={() => set("questions", [...qs, { id: `q${qs.length + 1}-${Date.now().toString(36).slice(-3)}`, type: "single", text: "", points: 1, options: [{ id: "a", text: "", correct: true }, { id: "b", text: "" }] }])}>+ Soru</button>
    </div>
  );
}

function SurveyEditor({ survey, onChange }: { survey: Json; onChange: (s: Json) => void }) {
  const qs: Json[] = survey.questions ?? [];
  const setQ = (i: number, v: Json) => onChange({ ...survey, questions: qs.map((x, j) => (j === i ? v : x)) });
  return (
    <div className="stack">
      <label className="choice"><input type="checkbox" checked={!!survey.anonymous} onChange={(e) => onChange({ ...survey, anonymous: e.target.checked })} /> Anonim anket (cevaplar kişiyle eşleştirilmez)</label>
      {qs.map((q, i) => (
        <fieldset key={i} className="stack">
          <legend>Soru {i + 1}</legend>
          <TextField label="Soru metni" value={q.text} onChange={(e) => setQ(i, { ...q, text: e.target.value })} />
          <SelectField label="Tür" value={q.type} onChange={(e) => setQ(i, { ...q, type: e.target.value, options: ["single", "multi"].includes(e.target.value) ? (q.options ?? [{ id: "a", text: "" }, { id: "b", text: "" }]) : undefined })}>
            <option value="scale">1–5 ölçek</option><option value="single">Tek seçimli</option><option value="multi">Çok seçimli</option><option value="open">Açık uçlu</option>
          </SelectField>
          {["single", "multi"].includes(q.type) && (q.options ?? []).map((o: Json, k: number) => (
            <input key={k} aria-label={`Şık ${k + 1}`} value={o.text} onChange={(e) => setQ(i, { ...q, options: q.options.map((x: Json, m: number) => (m === k ? { ...x, text: e.target.value } : x)) })} />
          ))}
          {["single", "multi"].includes(q.type) && <button type="button" className="btn" onClick={() => setQ(i, { ...q, options: [...q.options, { id: String.fromCharCode(97 + q.options.length), text: "" }] })}>+ Şık</button>}
          <button type="button" className="btn btn-danger" onClick={() => onChange({ ...survey, questions: qs.filter((_, j) => j !== i) })}>Soruyu sil</button>
        </fieldset>
      ))}
      <button type="button" className="btn" onClick={() => onChange({ ...survey, questions: [...qs, { id: `s${qs.length + 1}-${Date.now().toString(36).slice(-3)}`, type: "scale", text: "" }] })}>+ Soru</button>
    </div>
  );
}

/** Tool item: pins one published version of a tool (a newer tool version never changes this training silently). */
function ToolPin({ item, tools, onChange }: { item: Json; tools: Item[]; onChange: (v: Json) => void }) {
  const tool = tools.find((t) => t.id === item.toolId);
  const versions = tool?.publishedVersionNo ? Array.from({ length: tool.publishedVersionNo }, (_, i) => i + 1) : [];
  return (
    <div className="row">
      <SelectField label="Araç" value={item.toolId ?? ""} onChange={(e) => {
        const t = tools.find((x) => x.id === e.target.value);
        onChange({ ...item, toolId: e.target.value, toolVersionNo: t?.publishedVersionNo ?? 1 });
      }}>
        <option value="">Seçin…</option>
        {tools.filter((t) => t.publishedVersionNo).map((t) => <option key={t.id} value={t.id}>{t.latest.title}</option>)}
      </SelectField>
      <SelectField label="Sabitlenen sürüm" value={item.toolVersionNo ?? 1} hint="Yalnız yayınlanmış bir sürüm yayına alınabilir."
                   onChange={(e) => onChange({ ...item, toolVersionNo: Number(e.target.value) })}>
        {versions.map((v) => <option key={v} value={v}>s{v}{v === tool?.publishedVersionNo ? " (yayındaki)" : ""}</option>)}
      </SelectField>
    </div>
  );
}

/** CMS tool definition (versioned content). Secrets are NOT part of it: they are set on the "Sırlar ve deneme" tab. */
export function ToolForm({ body, onChange, disabled }: FormProps) {
  const set = (k: string, v: unknown) => onChange({ ...body, [k]: v });
  const req = body.request ?? { method: "GET", url: "", headers: {} };
  const setReq = (k: string, v: unknown) => set("request", { ...req, [k]: v });
  const headers: [string, string][] = Object.entries(req.headers ?? {});
  const setHeaders = (h: [string, string][]) => setReq("headers", Object.fromEntries(h));
  const inputs: Json[] = body.inputs ?? [];
  const setInput = (i: number, v: Json) => set("inputs", inputs.map((x, j) => (j === i ? v : x)));
  return (
    <fieldset disabled={disabled} className="stack">
      <legend>Araç</legend>
      <TextArea label="Açıklama (üyeye gösterilir)" rows={2} value={body.description ?? ""} onChange={(e) => set("description", e.target.value)} />
      <div className="row">
        <SelectField label="Yöntem" value={req.method} onChange={(e) => setReq("method", e.target.value)}>
          <option value="GET">GET</option><option value="POST">POST</option>
        </SelectField>
        <div style={{ flex: "1 1 20rem" }}>
          <TextField label="Adres (URL)" value={req.url ?? ""} onChange={(e) => setReq("url", e.target.value)}
                     hint="Girdi: {{input.ad}} (yalnız yol/sorguda). Sır: {{secret.ad}}. İç ağ adresleri reddedilir; yerel sahte servis (localhost:8090) yalnız CMS araçlarına açıktır." />
        </div>
      </div>
      <h3>Başlıklar</h3>
      {headers.map(([k, v], i) => (
        <div key={i} className="row">
          <TextField label="Ad" value={k} onChange={(e) => setHeaders(headers.map((h, j) => (j === i ? [e.target.value, h[1]] : h)))} />
          <TextField label="Değer" value={v} hint="Anahtar için {{secret.apiKey}} kullanın; değeri buraya yazmayın."
                     onChange={(e) => setHeaders(headers.map((h, j) => (j === i ? [h[0], e.target.value] : h)))} />
          <button type="button" className="btn btn-danger" onClick={() => setHeaders(headers.filter((_, j) => j !== i))}>Sil</button>
        </div>
      ))}
      <div><button type="button" className="btn" onClick={() => setHeaders([...headers, ["X-Api-Key", "{{secret.apiKey}}"]])}>+ Başlık</button></div>
      {req.method === "POST" && (
        <TextArea label="İstek gövdesi (JSON şablonu)" rows={4} value={JSON.stringify(req.body ?? {}, null, 2)}
                  onChange={(e) => { try { setReq("body", JSON.parse(e.target.value)); } catch { /* keep typing */ } }} />
      )}
      <h3>Girdiler</h3>
      {inputs.map((inp, i) => (
        <div key={i} className="row">
          <TextField label="Ad" value={inp.name} onChange={(e) => setInput(i, { ...inp, name: e.target.value })} />
          <TextField label="Etiket" value={inp.label ?? ""} onChange={(e) => setInput(i, { ...inp, label: e.target.value })} />
          <SelectField label="Tür" value={inp.type ?? "string"} onChange={(e) => setInput(i, { ...inp, type: e.target.value })}>
            <option value="string">Metin</option><option value="number">Sayı</option><option value="boolean">Evet/Hayır</option>
          </SelectField>
          <label className="choice"><input type="checkbox" checked={!!inp.required} onChange={(e) => setInput(i, { ...inp, required: e.target.checked })} /> Zorunlu</label>
          <button type="button" className="btn btn-danger" onClick={() => set("inputs", inputs.filter((_, j) => j !== i))}>Sil</button>
        </div>
      ))}
      <div><button type="button" className="btn" onClick={() => set("inputs", [...inputs, { name: `girdi${inputs.length + 1}`, label: "", type: "string", required: false }])}>+ Girdi</button></div>
      <div className="row">
        <TextField label="Zaman aşımı (sn, 1-30)" type="number" min={1} max={30} value={body.timeoutSeconds ?? 10} onChange={(e) => set("timeoutSeconds", Number(e.target.value))} />
        <TextField label="Yeniden deneme (0-3)" type="number" min={0} max={3} value={body.retries ?? 2} onChange={(e) => set("retries", Number(e.target.value))} />
        <TextField label="Sonuç alanı (isteğe bağlı)" placeholder="data.result" value={body.outputPath ?? ""} onChange={(e) => set("outputPath", e.target.value)} />
      </div>
      <fieldset className="stack">
        <legend>Kimlik doğrulama</legend>
        <label className="choice"><input type="checkbox" checked={!!body.auth} onChange={(e) => set("auth", e.target.checked ? { type: "oauth2_client_credentials", tokenUrl: "", scope: "" } : undefined)} />
          <span>OAuth 2.0 istemci kimliği (client credentials)</span></label>
        {body.auth && (
          <>
            <TextField label="Jeton adresi (tokenUrl)" placeholder="https://auth.example.com/oauth/token" value={body.auth.tokenUrl ?? ""} onChange={(e) => set("auth", { ...body.auth, tokenUrl: e.target.value })} />
            <TextField label="Kapsam (scope, isteğe bağlı)" value={body.auth.scope ?? ""} onChange={(e) => set("auth", { ...body.auth, scope: e.target.value })} />
            <p className="hint">İstemci kimliği ve sırrı "Sırlar ve deneme" sekmesinde <code>clientId</code> ve <code>clientSecret</code> adıyla şifreli saklanır. Jeton her çağrıda otomatik alınır, süresi bitene kadar bellekte tutulur ve hiçbir yerde gösterilmez.</p>
          </>
        )}
      </fieldset>
      <p className="hint">JSON ya da metin dışındaki cevaplar (PDF, görsel, ZIP…) 50 MB'a kadar dosya çıktısı olarak saklanır (7 gün) ve üye çalıştırma ekranından indirir.</p>
    </fieldset>
  );
}
