import { Alert, ITEM_TYPE_LABEL, SelectField, TextArea, TextField } from "@basamak/ui";
import type { Item, Json } from "./content";

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

export function TrainingForm({ body, onChange, trainings, disabled, lockedKeys = [], onConfirmRemove }:
  FormProps & { trainings: Item[]; lockedKeys?: string[]; onConfirmRemove?: (key: string) => void }) {
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
        {trainings.map((t) => <option key={t.id} value={t.id}>{t.latest.title}</option>)}
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
          {it.type === "lesson" && <TextArea label="Ders metni (Markdown)" rows={8} value={it.content ?? ""} onChange={(e) => setItem(i, { ...it, content: e.target.value })} />}
          {it.type === "quiz" && <QuizEditor quiz={it} onChange={(q) => setItem(i, q)} />}
          {it.type === "survey" && <SurveyEditor survey={it} onChange={(s) => setItem(i, s)} />}
        </div>
      ))}
      <div className="row">
        <button type="button" className="btn" onClick={() => add("lesson")}>+ Ders</button>
        <button type="button" className="btn" onClick={() => add("quiz")}>+ Quiz</button>
        <button type="button" className="btn" onClick={() => add("survey")}>+ Anket</button>
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
