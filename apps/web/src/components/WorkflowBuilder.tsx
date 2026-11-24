import { useEffect, useState } from "react";
import { ChoiceCards, SelectField, TextArea, TextField, Wizard, errorMessage } from "@basamak/ui";
import { api } from "../auth";
import { FlowGraph } from "./FlowGraph";
import type { ToolInput } from "./ToolRunner";
import { OPS, STEP_LABEL, newStepId, type Definition, type PlatformTool, type Step, type Workflow } from "../workflows";

type MyTool = { id: string; name: string; versionNo: number; definition: { inputs: ToolInput[] } };
export type Draft = { name: string; description: string; definition: Definition };

/** Output fields of the seeded learning tools, so members can pick "Özet → metin" instead of typing a path. */
const KNOWN_FIELDS: Record<string, [string, string][]> = {
  "Metin özetleyici": [["summary", "özet"], ["wordCount", "kelime sayısı"], ["readingMinutes", "okuma süresi (dk)"]],
  "Terim sözlüğü": [["definition", "açıklama"], ["found", "bulundu mu"]],
  "Metin denetleyici": [["score", "puan"], ["issueCount", "sorun sayısı"], ["issues", "öneriler"]],
};

const STEP_TYPES: { value: Step["type"]; title: string; description: string }[] = [
  { value: "tool", title: "Araç", description: "Bir aracı çalıştırır (ör. metni özetle, terimi açıkla)." },
  { value: "ai", title: "Yapay zekâ", description: "Talimat verirsin; önceki adımların sonuçlarını talimata ekleyebilirsin." },
  { value: "condition", title: "Koşul", description: "Bir sonuca göre hangi adımların çalışacağını seçer (evet / hayır)." },
  { value: "workflow", title: "Alt akış", description: "Başka bir iş akışını bu akışın bir adımı olarak çalıştırır." },
];

type TemplateKey = "blank" | "quiz" | "plan" | "glossary";
const TEMPLATE_CARDS: { value: TemplateKey; title: string; description: string }[] = [
  { value: "blank", title: "Boş akış", description: "Adımları kendin ekle." },
  { value: "quiz", title: "Ders notundan tekrar soruları", description: "Notu özetler, özetten 5 soru yazar; metin uzunsa çalışma planı da önerir." },
  { value: "plan", title: "Haftalık çalışma planı", description: "Hedefini ve ayırabileceğin süreyi alır, 7 günlük plan yazar." },
  { value: "glossary", title: "Terimi açıkla", description: "Terimi sözlükte arar; bulursa örnekle anlatır, bulamazsa benzerlerini önerir." },
];

function template(key: TemplateKey, tools: PlatformTool[]): Draft {
  const tool = (name: string) => tools.find((t) => t.name === name);
  const summarize = tool("Metin özetleyici"), glossary = tool("Terim sözlüğü");
  switch (key) {
    case "quiz":
      return { name: "Ders notundan tekrar soruları", description: "Ders notumu özetler ve tekrar soruları üretir.", definition: {
        inputs: [{ name: "notlar", label: "Ders notların", type: "string", required: true, maxLength: 2000 }],
        steps: [
          summarize ? { id: "ozet", type: "tool", tool: { scope: "CONTENT", id: summarize.id }, input: { text: "{{input.notlar}}", sentences: "3" } }
            : { id: "ozet", type: "ai", prompt: "Şu notu 3 cümlede özetle: {{input.notlar}}" },
          { id: "sorular", type: "ai", prompt: summarize ? "Bu özetten 5 kısa tekrar sorusu yaz:\n{{steps.ozet.output.summary}}" : "Bu özetten 5 kısa tekrar sorusu yaz:\n{{steps.ozet.output.text}}" },
          ...(summarize ? [
            { id: "uzun_mu", type: "condition", left: "{{steps.ozet.output.wordCount}}", op: "gt", right: "150", then: ["plan"], else: [] } as Step,
            { id: "plan", type: "ai", prompt: "Bu ders notu yaklaşık {{steps.ozet.output.readingMinutes}} dakikalık. Konuyu 3 oturuma bölen kısa bir çalışma planı öner." } as Step,
          ] : []),
        ], output: "{{steps.sorular.output.text}}" } };
    case "plan":
      return { name: "Haftalık çalışma planı", description: "Hedefime göre 7 günlük çalışma planı.", definition: {
        inputs: [{ name: "hedef", label: "Bu haftaki hedefin", type: "string", required: true, maxLength: 300 },
                 { name: "saat", label: "Haftada kaç saat ayırabilirsin?", type: "number", required: true, maxLength: 10 }],
        steps: [{ id: "plan", type: "ai", prompt: "Hedef: {{input.hedef}}. Haftada {{input.saat}} saat ayırabiliyorum. Günlere bölünmüş, kısa maddeli 7 günlük bir çalışma planı yaz." }],
        output: "{{steps.plan.output.text}}" } };
    case "glossary":
      return { name: "Terimi açıkla", description: "Bilmediğim terimi örnekle anlatır.", definition: {
        inputs: [{ name: "terim", label: "Terim", type: "string", required: true, maxLength: 60 }],
        steps: glossary ? [
          { id: "sozluk", type: "tool", tool: { scope: "CONTENT", id: glossary.id }, input: { term: "{{input.terim}}" } },
          { id: "bulundu_mu", type: "condition", left: "{{steps.sozluk.output.found}}", op: "eq", right: "true", then: ["ornek"], else: ["oneri"] },
          { id: "ornek", type: "ai", prompt: "Şu açıklamayı günlük hayattan bir örnekle anlat: {{steps.sozluk.output.definition}}" },
          { id: "oneri", type: "ai", prompt: "'{{input.terim}}' sözlükte yok. Bu konuyla ilgili öğrenmem gereken 3 temel terimi öner." },
        ] : [{ id: "ornek", type: "ai", prompt: "{{input.terim}} terimini bir örnekle anlat." }],
        output: "" } };
    default:
      return { name: "", description: "", definition: { inputs: [], steps: [], output: "" } };
  }
}

function slug(label: string, taken: string[]) {
  const map: Record<string, string> = { ç: "c", ğ: "g", ı: "i", İ: "i", ö: "o", ş: "s", ü: "u", Ç: "c", Ğ: "g", Ö: "o", Ş: "s", Ü: "u" };
  let s = label.replace(/[çğıİöşüÇĞÖŞÜ]/g, (c) => map[c]).toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "");
  if (!/^[a-z]/.test(s)) s = "girdi" + (s ? "_" + s : "");
  s = s.slice(0, 40);
  let out = s, n = 2;
  while (taken.includes(out)) out = `${s}_${n++}`;
  return out;
}

const TYPE_LABEL: Record<string, string> = { string: "Metin", number: "Sayı", boolean: "Evet / Hayır" };

/**
 * Step-by-step workflow builder: 1) name (or a template), 2) inputs, 3) steps - one step edited at a time, values
 * picked from a list ("Girdi: Konu", "Özet adımı → özet") instead of typing {{...}}, 4) result and save.
 * Parallel steps, step ids and free-form values are under "Gelişmiş".
 */
export function WorkflowBuilder({ initial, isNew, selfId, onSave, onCancel }: {
  initial: Draft; isNew: boolean; selfId?: string; onSave: (d: Draft) => Promise<void>; onCancel?: () => void;
}) {
  const [step, setStep] = useState(0);
  const [tpl, setTpl] = useState<TemplateKey | null>(isNew ? "blank" : null);
  const [draft, setDraft] = useState<Draft>(structuredClone(initial));
  const [platform, setPlatform] = useState<PlatformTool[]>([]);
  const [mine, setMine] = useState<MyTool[]>([]);
  const [flows, setFlows] = useState<Workflow[]>([]);
  const [editing, setEditing] = useState<number | null>(null);
  const [adding, setAdding] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const def = draft.definition;
  const setDef = (d: Definition) => setDraft({ ...draft, definition: d });

  useEffect(() => {
    Promise.all([api.get<PlatformTool[]>("/api/tools"), api.get<MyTool[]>("/api/my-tools"), api.get<Workflow[]>("/api/workflows"), api.get<Workflow[]>("/api/community/workflows")])
      .then(([p, m, own, shared]) => { setPlatform(p); setMine(m); setFlows([...own, ...shared.filter((s) => !own.some((o) => o.id === s.id))].filter((f) => f.id !== selfId)); })
      .catch((e) => setError(errorMessage(e)));
  }, [selfId]);

  const toolName = (s: Step) => s.tool?.scope === "USER" ? mine.find((t) => t.id === s.tool?.id)?.name : platform.find((t) => t.id === s.tool?.id)?.name;
  const inputsOf = (s: Step): ToolInput[] => {
    if (s.type === "tool") return (s.tool?.scope === "USER" ? mine.find((t) => t.id === s.tool?.id)?.definition.inputs : platform.find((t) => t.id === s.tool?.id)?.inputs) ?? [];
    if (s.type === "workflow") return flows.find((f) => f.id === s.workflowId)?.definition.inputs ?? [];
    return [];
  };
  /** Values a step at index i may use: workflow inputs and outputs of the steps before it. */
  const sources = (i: number): { value: string; label: string }[] => {
    const out = def.inputs.map((x) => ({ value: `{{input.${x.name}}}`, label: `Girdi: ${x.label || x.name}` }));
    def.steps.slice(0, i).forEach((s) => {
      const name = s.type === "tool" ? toolName(s) ?? "araç" : STEP_LABEL[s.type];
      if (s.type === "ai") out.push({ value: `{{steps.${s.id}.output.text}}`, label: `${s.id} (yapay zekâ) → metin` });
      else if (s.type === "condition") out.push({ value: `{{steps.${s.id}.output.result}}`, label: `${s.id} (koşul) → sonuç` });
      else {
        (KNOWN_FIELDS[name] ?? []).forEach(([f, l]) => out.push({ value: `{{steps.${s.id}.output.${f}}}`, label: `${s.id} (${name}) → ${l}` }));
        out.push({ value: `{{steps.${s.id}.output}}`, label: `${s.id} (${name}) → tüm sonuç` });
      }
    });
    return out;
  };
  const setStepAt = (i: number, s: Step) => setDef({ ...def, steps: def.steps.map((x, j) => (j === i ? s : x)) });
  const removeStep = (i: number) => {
    const id = def.steps[i].id;
    setDef({ ...def, steps: def.steps.filter((_, j) => j !== i).map((s) => ({ ...s, then: s.then?.filter((x) => x !== id), else: s.else?.filter((x) => x !== id) })) });
    setEditing(null);
  };
  const move = (i: number, d: number) => {
    const s = [...def.steps];
    const j = i + d;
    if (j < 0 || j >= s.length) return;
    [s[i], s[j]] = [s[j], s[i]];
    setDef({ ...def, steps: s });
    setEditing(j);
  };
  const addStep = (type: Step["type"]) => {
    const sid = newStepId(def.steps, type);
    const base: Step = type === "ai" ? { id: sid, type, prompt: "" }
      : type === "condition" ? { id: sid, type, left: "", op: "gt", right: "", then: [], else: [] }
      : type === "tool" ? { id: sid, type, tool: platform[0] ? { scope: "CONTENT", id: platform[0].id } : undefined, input: {} }
      : { id: sid, type, workflowId: flows[0]?.id, input: {} };
    setDef({ ...def, steps: [...def.steps, base] });
    setEditing(def.steps.length);
    setAdding(false);
  };

  const validate = (i: number): string | null => {
    if (i === 0 && !draft.name.trim()) return "Akışa bir ad ver.";
    if (i === 1 && def.inputs.some((x) => !x.label.trim())) return "Her girdinin bir etiketi olmalı.";
    if (i === 2) {
      if (def.steps.length === 0) return "En az bir adım ekle.";
      for (const s of def.steps) {
        if (s.type === "ai" && !s.prompt?.trim()) return `'${s.id}' yapay zekâ adımının talimatı boş.`;
        if (s.type === "tool" && !s.tool?.id) return `'${s.id}' adımında bir araç seç.`;
        if (s.type === "workflow" && !s.workflowId) return `'${s.id}' adımında bir alt akış seç.`;
        if (s.type === "tool" || s.type === "workflow") {
          const missing = inputsOf(s).filter((x) => x.required && !String(s.input?.[x.name] ?? "").trim());
          if (missing.length) return `'${s.id}' adımında doldurulmamış zorunlu alan: ${missing.map((x) => x.label || x.name).join(", ")}.`;
        }
      }
    }
    return null;
  };

  const save = async () => {
    setSaving(true);
    setError(null);
    try { await onSave(draft); } catch (e) { setError(errorMessage(e)); } finally { setSaving(false); }
  };

  /** Shows {{...}} references as their labels («Girdi: Konu», «ozet → özet») in summaries. */
  const human = (t: string) => sources(def.steps.length).reduce((acc, o) => acc.split(o.value).join(`«${o.label.replace(/^Girdi: /, "")}»`), t);
  const cut = (t: string) => (t.length > 110 ? t.slice(0, 109) + "…" : t);
  const summary = (s: Step) => cut(human(s.type === "tool" ? `Araç: ${toolName(s) ?? "seçilmedi"}`
    : s.type === "ai" ? `Yapay zekâ: ${s.prompt ?? ""}`
    : s.type === "condition" ? `Koşul: ${s.left || "?"} ${OPS.find(([k]) => k === s.op)?.[1] ?? s.op} ${s.right ?? ""}`
    : `Alt akış: ${flows.find((f) => f.id === s.workflowId)?.name ?? "seçilmedi"}`));

  const steps = [
    { title: "Ad ve amaç", hint: "Akışın ne yaptığını kısaca yaz. Hazır bir örnekle başlayabilirsin." },
    { title: "Girdiler", hint: "Akış başlarken kullanıcıdan istenecek bilgiler. Hiç girdi olmayabilir." },
    { title: "Adımlar", hint: "Adımlar yukarıdan aşağı sırayla çalışır. Bir adımı düzenlemek için üzerine tıkla; aynı anda tek adım açılır." },
    { title: "Sonuç ve kaydet", hint: "Akış bittiğinde neyin sonuç olarak gösterileceğini seç ve kaydet." },
  ];

  return (
    <section className="card stack" aria-label="İş akışı kurucu">
      <Wizard steps={steps} current={step} onChange={(i) => { setStep(i); setEditing(null); setAdding(false); }} validate={validate}
              finishLabel={isNew ? "Akışı oluştur" : "Kaydet (yeni sürüm)"} onFinish={save} busy={saving} onCancel={onCancel} error={error}>
        {step === 0 && (
          <div className="stack">
            {isNew && <ChoiceCards name="wf-template" legend="Nasıl başlamak istersin?" value={tpl} options={TEMPLATE_CARDS}
                                   onChange={(k) => { setTpl(k); setDraft(template(k, platform)); }} />}
            <TextField label="Akışın adı" required maxLength={120} value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
            <TextField label="Kısa açıklama (isteğe bağlı)" maxLength={500} value={draft.description} onChange={(e) => setDraft({ ...draft, description: e.target.value })} />
          </div>
        )}

        {step === 1 && (
          <div className="stack">
            {def.inputs.length === 0 && <p className="muted">Bu akış kullanıcıdan bilgi istemiyor.</p>}
            {def.inputs.map((x, k) => (
              <div key={k} className="card stack">
                <div className="row space-between"><strong>{k + 1}. girdi</strong>
                  <button type="button" className="btn btn-sm btn-danger" onClick={() => setDef({ ...def, inputs: def.inputs.filter((_, j) => j !== k) })}>Kaldır</button></div>
                <TextField label="Soru / etiket" value={x.label} placeholder="ör. Ders notların"
                           onChange={(e) => setDef({ ...def, inputs: def.inputs.map((y, j) => (j === k ? { ...y, label: e.target.value, ...(isNew ? { name: slug(e.target.value, def.inputs.filter((_, m) => m !== k).map((z) => z.name)) } : {}) } : y)) })} />
                <div className="row">
                  <SelectField label="Tür" value={x.type} onChange={(e) => setDef({ ...def, inputs: def.inputs.map((y, j) => (j === k ? { ...y, type: e.target.value as ToolInput["type"] } : y)) })}>
                    {Object.entries(TYPE_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                  </SelectField>
                  <label className="choice"><input type="checkbox" checked={x.required} onChange={(e) => setDef({ ...def, inputs: def.inputs.map((y, j) => (j === k ? { ...y, required: e.target.checked } : y)) })} /> <span>Zorunlu</span></label>
                </div>
              </div>
            ))}
            <div><button type="button" className="btn" onClick={() => setDef({ ...def, inputs: [...def.inputs, { name: slug("girdi", def.inputs.map((y) => y.name)), label: "", type: "string", required: true, maxLength: 500 }] })}>+ Girdi ekle</button></div>
          </div>
        )}

        {step === 2 && (
          <div className="stack">
            <FlowGraph def={def} />
            {def.steps.length === 0 && <p className="muted">Henüz adım yok. “Adım ekle” ile başla.</p>}
            <ol className="stack" style={{ listStyle: "none", padding: 0, margin: 0 }} aria-label="Adımlar">
              {def.steps.map((s, i) => (
                <li key={s.id + i} className={`card stack step-card ${editing === i ? "open" : ""}`}>
                  <div className="row space-between">
                    <button type="button" className="step-card-head" aria-expanded={editing === i} onClick={() => setEditing(editing === i ? null : i)}>
                      <strong>{i + 1}. {STEP_LABEL[s.type]}</strong> <span className="muted">· {summary(s)}</span>{s.parallel && <span className="muted"> · paralel</span>}
                    </button>
                    <div className="row">
                      <button type="button" className="btn btn-sm" aria-label={`${s.id} yukarı taşı`} disabled={i === 0} onClick={() => move(i, -1)}>↑</button>
                      <button type="button" className="btn btn-sm" aria-label={`${s.id} aşağı taşı`} disabled={i === def.steps.length - 1} onClick={() => move(i, 1)}>↓</button>
                      <button type="button" className="btn btn-sm btn-danger" onClick={() => removeStep(i)}>Sil</button>
                    </div>
                  </div>
                  {editing === i && (
                    <StepEditor step={s} index={i} def={def} sources={sources(i)} platform={platform} mine={mine} flows={flows}
                                inputs={inputsOf(s)} onChange={(n) => setStepAt(i, n)} onDone={() => setEditing(null)} />
                  )}
                </li>
              ))}
            </ol>
            {adding ? (
              <div className="card stack">
                <ChoiceCards name="step-type" legend="Ne tür bir adım eklemek istersin?" value={null} options={STEP_TYPES} onChange={addStep} />
                <div><button type="button" className="btn" onClick={() => setAdding(false)}>Vazgeç</button></div>
              </div>
            ) : <div><button type="button" className="btn btn-primary" onClick={() => { setAdding(true); setEditing(null); }}>+ Adım ekle</button></div>}
          </div>
        )}

        {step === 3 && (
          <div className="stack">
            <SelectField label="Akışın sonucu" value={def.output} onChange={(e) => setDef({ ...def, output: e.target.value })}
                         hint="Çalıştırma bittiğinde gösterilecek değer.">
              <option value="">Son çalışan adımın sonucu</option>
              {sources(def.steps.length).filter((o) => o.value.startsWith("{{steps.")).map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
            </SelectField>
            <dl className="summary-list">
              <dt>Ad</dt><dd>{draft.name}</dd>
              <dt>Girdiler</dt><dd>{def.inputs.length ? def.inputs.map((x) => x.label || x.name).join(" · ") : "—"}</dd>
              <dt>Adımlar</dt><dd>{def.steps.map((s, i) => `${i + 1}. ${summary(s)}`).join(" → ")}</dd>
            </dl>
          </div>
        )}
      </Wizard>
    </section>
  );
}

/** A value: picked from the list of inputs / earlier outputs, or typed as a fixed value. */
function ValueField({ label, value, onChange, sources, hint, multiline }: {
  label: string; value: string; onChange: (v: string) => void; sources: { value: string; label: string }[]; hint?: string; multiline?: boolean;
}) {
  const known = sources.some((o) => o.value === value);
  const [custom, setCustom] = useState(!known && !!value);
  const mode = custom || (!known && !!value) ? "__custom" : value;
  return (
    <div className="stack" style={{ gap: 4 }}>
      <SelectField label={label} value={mode} hint={hint}
                   onChange={(e) => { if (e.target.value === "__custom") { setCustom(true); onChange(known ? "" : value); } else { setCustom(false); onChange(e.target.value); } }}>
        <option value="">— Seç —</option>
        {sources.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
        <option value="__custom">Sabit değer ya da elle yaz…</option>
      </SelectField>
      {mode === "__custom" && (multiline
        ? <TextArea label={`${label}: değer`} rows={3} value={value} onChange={(e) => onChange(e.target.value)} />
        : <TextField label={`${label}: değer`} value={value} onChange={(e) => onChange(e.target.value)} hint="Birkaç değeri birleştirmek için {{input.ad}} ya da {{steps.adim.output}} yazabilirsin." />)}
    </div>
  );
}

function StepEditor({ step: s, index, def, sources, platform, mine, flows, inputs, onChange, onDone }: {
  step: Step; index: number; def: Definition; sources: { value: string; label: string }[]; platform: PlatformTool[]; mine: MyTool[];
  flows: Workflow[]; inputs: ToolInput[]; onChange: (s: Step) => void; onDone: () => void;
}) {
  const prev = index > 0 ? def.steps[index - 1] : null;
  const canParallel = !!prev && (s.type === "tool" || s.type === "ai") && (prev.type === "tool" || prev.type === "ai");
  const later = def.steps.slice(index + 1);
  return (
    <div className="stack step-editor">
      {s.type === "tool" && (
        <SelectField label="Hangi araç?" value={s.tool ? `${s.tool.scope}:${s.tool.id}` : ""} onChange={(e) => {
          const [scope, tid] = e.target.value.split(":");
          onChange({ ...s, tool: { scope: scope as "CONTENT" | "USER", id: tid }, input: {} });
        }}>
          <option value="">Seçin…</option>
          <optgroup label="Platform araçları">{platform.map((t) => <option key={t.id} value={`CONTENT:${t.id}`}>{t.name}</option>)}</optgroup>
          <optgroup label="Araçlarım (paylaşılan akışta kullanılamaz)">{mine.map((t) => <option key={t.id} value={`USER:${t.id}`}>{t.name}</option>)}</optgroup>
        </SelectField>
      )}
      {s.type === "workflow" && (
        <SelectField label="Hangi akış?" value={s.workflowId ?? ""} onChange={(e) => onChange({ ...s, workflowId: e.target.value, input: {} })}>
          <option value="">Seçin…</option>
          {flows.map((f) => <option key={f.id} value={f.id}>{f.name}</option>)}
        </SelectField>
      )}
      {(s.type === "tool" || s.type === "workflow") && inputs.map((inp) => (
        <ValueField key={inp.name} label={`${inp.label || inp.name}${inp.required ? "" : " (isteğe bağlı)"}`} sources={sources}
                    value={String(s.input?.[inp.name] ?? "")} onChange={(v) => onChange({ ...s, input: { ...s.input, [inp.name]: v } })} />
      ))}
      {s.type === "ai" && (
        <div className="stack">
          <TextArea label="Talimat" rows={4} value={s.prompt ?? ""} onChange={(e) => onChange({ ...s, prompt: e.target.value })}
                    hint="Sahte model (ücretsiz): cevap ve maliyet benzetimdir. Talimata önceki sonuçları eklemek için aşağıdan seç." />
          {sources.length > 0 && (
            <SelectField label="Talimata değer ekle" value="" onChange={(e) => { if (e.target.value) onChange({ ...s, prompt: `${s.prompt ?? ""}${s.prompt ? " " : ""}${e.target.value}` }); }}>
              <option value="">— Eklemek için seç —</option>
              {sources.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
            </SelectField>
          )}
        </div>
      )}
      {s.type === "condition" && (
        <div className="stack">
          <ValueField label="Neye bakılsın?" sources={sources} value={s.left ?? ""} onChange={(v) => onChange({ ...s, left: v })} />
          <div className="row">
            <SelectField label="Karşılaştırma" value={s.op} onChange={(e) => onChange({ ...s, op: e.target.value })}>
              {OPS.map(([k, l]) => <option key={k} value={k}>{l}</option>)}
            </SelectField>
            {!["empty", "notEmpty"].includes(s.op ?? "") && <TextField label="Değer" value={s.right ?? ""} onChange={(e) => onChange({ ...s, right: e.target.value })} />}
          </div>
          <Branch label="Evetse çalışacak adımlar" steps={later} value={s.then ?? []} onChange={(v) => onChange({ ...s, then: v })} />
          <Branch label="Hayırsa çalışacak adımlar" steps={later} value={s.else ?? []} onChange={(v) => onChange({ ...s, else: v })} />
          {later.length === 0 && <p className="hint">Koşul yalnız kendisinden sonraki adımları seçebilir; önce o adımları ekle.</p>}
        </div>
      )}
      <details>
        <summary>Gelişmiş</summary>
        <div className="stack" style={{ marginTop: "var(--space-2)" }}>
          <TextField label="Adım kimliği" value={s.id} hint="Değerlerde bu adla görünür: küçük harf, rakam, - ve _."
                     onChange={(e) => onChange({ ...s, id: e.target.value.toLowerCase().replace(/[^a-z0-9_-]/g, "") })} />
          {canParallel && (
            <label className="choice"><input type="checkbox" checked={!!s.parallel} onChange={(e) => onChange({ ...s, parallel: e.target.checked || undefined })} />
              <span>Önceki adımla aynı anda (paralel) çalışsın. Paralel adımlar birbirinin sonucunu kullanamaz.</span></label>
          )}
        </div>
      </details>
      <div><button type="button" className="btn" onClick={onDone}>Tamam</button></div>
    </div>
  );
}

function Branch({ label, steps, value, onChange }: { label: string; steps: Step[]; value: string[]; onChange: (v: string[]) => void }) {
  if (steps.length === 0) return null;
  return (
    <fieldset>
      <legend>{label}</legend>
      <div className="row">
        {steps.map((s) => (
          <label key={s.id} className="choice"><input type="checkbox" checked={value.includes(s.id)}
            onChange={(e) => onChange(e.target.checked ? [...value, s.id] : value.filter((x) => x !== s.id))} /> <span>{s.id}</span></label>
        ))}
      </div>
    </fieldset>
  );
}
