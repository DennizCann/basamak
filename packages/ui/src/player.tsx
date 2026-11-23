import { useState, type FormEvent } from "react";
import { Markdown } from "./Markdown";
import { Alert } from "./Field";
import { useMediaUrl } from "./media";

/**
 * Presentational training player parts shared by the member web app and the CMS preview.
 * They never call the API themselves: the host decides what "submit" means (real API vs. local preview),
 * so the CMS preview cannot write progress, points, attempts or survey answers.
 */
export type Option = { id: string; text: string; correct?: boolean };
export type Question = { id: string; type: string; text: string; options?: Option[] };
export type PlayerItem = {
  key: string; type: "lesson" | "quiz" | "survey" | "tool"; title: string; completed?: boolean;
  content?: string; video?: { url?: string; mediaId?: string; minSeconds?: number };
  attachments?: { mediaId: string; name: string; size?: number }[];
  passingScore?: number; attemptsAllowed?: number; timeLimitSeconds?: number; questionsPerAttempt?: number;
  attemptsUsed?: number; passed?: boolean; bestPercent?: number; showCorrectAnswers?: boolean;
  anonymous?: boolean; questions?: Question[];
};
export type Answers = Record<string, string[] | string | number>;
export type QuizResultData = {
  percent: number; passingScore: number; passed: boolean; attemptsLeft: number; late?: boolean;
  results: { id: string; correct?: boolean; status?: string; correctOptionIds?: string[] }[];
};

export const ITEM_TYPE_LABEL: Record<string, string> = { lesson: "Ders", quiz: "Quiz", survey: "Anket", tool: "Araç" };

/** Unambiguous attempt wording: used vs. remaining, incl. zero/one/finished states. */
export function attemptsText(used: number, allowed: number, completed: boolean): string {
  const left = Math.max(0, allowed - used);
  if (completed) return `Quiz geçildi · ${used} deneme kullanıldı.`;
  if (used === 0) return `${allowed} deneme hakkınız var.`;
  if (left === 0) return `${used} deneme kullanıldı, deneme hakkınız kalmadı.`;
  return `${used} deneme kullanıldı, ${left} deneme hakkınız kaldı.`;
}

const KB = 1024;
function fileSize(n?: number) {
  if (!n) return "";
  return n >= KB * KB ? `${(n / KB / KB).toLocaleString("tr-TR", { maximumFractionDigits: 1 })} MB` : `${Math.max(1, Math.round(n / KB)).toLocaleString("tr-TR")} KB`;
}

export function LessonBody({ item, onTime }: { item: PlayerItem; onTime?: (seconds: number) => void }) {
  const mediaUrl = useMediaUrl();
  const videoSrc = item.video ? (item.video.mediaId ? mediaUrl(item.video.mediaId) : item.video.url) : undefined;
  return (
    <>
      {item.video && (
        <figure style={{ margin: 0 }}>
          <video controls preload="metadata" playsInline src={videoSrc} style={{ width: "100%" }} aria-label={`${item.title} videosu`}
                 onTimeUpdate={(e) => onTime?.(e.currentTarget.currentTime)} />
          {item.video.minSeconds ? <figcaption className="hint">Dersi tamamlamak için en az {item.video.minSeconds} saniye izleyin. İleri sarılan süre sayılmaz.</figcaption> : null}
        </figure>
      )}
      {item.content ? <Markdown source={item.content} /> : null}
      {item.attachments?.length ? (
        <section aria-label="Ek dosyalar">
          <h3>Ek dosyalar</h3>
          <ul className="attachments">
            {item.attachments.map((a) => {
              const href = mediaUrl(a.mediaId);
              return (
                <li key={a.mediaId}>
                  {href ? <a href={href} target="_blank" rel="noopener noreferrer" download>{a.name}</a> : <span>{a.name}</span>}
                  {a.size ? <span className="muted"> · {fileSize(a.size)}</span> : null}
                </li>
              );
            })}
          </ul>
        </section>
      ) : null}
    </>
  );
}

/** Question form for one quiz attempt (single / multi / open). */
export function QuestionForm({ questions, onSubmit, timeLeft }: { questions: Question[]; onSubmit: (a: Answers) => void; timeLeft?: number | null }) {
  const [answers, setAnswers] = useState<Answers>({});
  const [missing, setMissing] = useState<string[]>([]);
  const toggle = (q: Question, opt: string) => {
    if (q.type === "single") setAnswers({ ...answers, [q.id]: [opt] });
    else {
      const cur = (answers[q.id] as string[] | undefined) ?? [];
      setAnswers({ ...answers, [q.id]: cur.includes(opt) ? cur.filter((x) => x !== opt) : [...cur, opt] });
    }
  };
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const empty = questions.filter((q) => {
      const a = answers[q.id];
      return a === undefined || (Array.isArray(a) && a.length === 0) || (typeof a === "string" && !a.trim());
    }).map((q) => q.id);
    if (empty.length && !missing.length) { setMissing(empty); return; } // warn once, allow sending anyway
    onSubmit(answers);
  };
  return (
    <form onSubmit={submit} className="stack">
      {timeLeft != null && (
        <p role="timer" aria-live={timeLeft <= 30 ? "assertive" : "off"} className={timeLeft <= 30 ? "alert alert-warning" : "muted"}>
          Kalan süre: {Math.floor(timeLeft / 60)}:{String(timeLeft % 60).padStart(2, "0")}
        </p>
      )}
      {missing.length > 0 && <Alert kind="warning"><p>{missing.length} soru boş. Boş sorular yanlış sayılır; yine de göndermek için tekrar "Cevapları gönder"e basın.</p></Alert>}
      {questions.map((q, n) => (
        <fieldset key={q.id} aria-invalid={missing.includes(q.id) || undefined}>
          <legend>{n + 1}. {q.text} {q.type === "multi" && <span className="muted">(birden çok seçilebilir)</span>}</legend>
          {q.type === "open" ? (
            <textarea aria-label={q.text} rows={3} style={{ width: "100%" }} onChange={(e) => setAnswers({ ...answers, [q.id]: e.target.value })} />
          ) : (q.options ?? []).map((o) => (
            <label key={o.id} className="choice">
              <input type={q.type === "single" ? "radio" : "checkbox"} name={q.id}
                     checked={((answers[q.id] as string[] | undefined) ?? []).includes(o.id)} onChange={() => toggle(q, o.id)} />
              <span>{o.text}</span>
            </label>
          ))}
        </fieldset>
      ))}
      <button className="btn btn-primary">Cevapları gönder</button>
    </form>
  );
}

export function QuizResult({ result }: { result: QuizResultData }) {
  return (
    <Alert kind={result.passed ? "success" : "warning"}>
      <p><strong>{result.passed ? "Tebrikler, geçtiniz!" : "Bu sefer olmadı."}</strong> Puanınız %{result.percent} (geçme notu %{result.passingScore}).</p>
      {result.late && <p>Süre dolduktan sonra gönderildiği için deneme geçersiz sayıldı.</p>}
      {!result.passed && <p>{result.attemptsLeft > 0 ? `Kalan deneme hakkı: ${result.attemptsLeft}` : "Deneme hakkınız kalmadı."}</p>}
      {result.results.some((x) => x.status === "pending_review") && <p>Açık uçlu cevaplarınız içerik ekibi tarafından değerlendirilecek.</p>}
      {!result.passed && result.attemptsLeft > 0 && <p className="muted">Doğru cevaplar, quiz'i geçtiğinizde ya da deneme hakkınız bitince gösterilir.</p>}
    </Alert>
  );
}

export type ReviewQuestion = Question & { given?: unknown; correct?: boolean; status?: string; correctOptionIds?: string[] };
export type ReviewAttempt = { attemptNo: number; trainingVersionNo: number; submittedAt: string; passed: boolean | null; percent: number | null; note?: string; questions: ReviewQuestion[] };

/** Read-only review of submitted attempts exactly as they were shown. */
export function QuizReview({ attempts }: { attempts: ReviewAttempt[] }) {
  if (!attempts.length) return <p className="muted">Henüz gönderilmiş bir deneme yok.</p>;
  return (
    <div className="stack">
      {attempts.map((a) => (
        <details key={a.attemptNo} open={a.attemptNo === attempts[attempts.length - 1].attemptNo}>
          <summary>{a.attemptNo}. deneme · {a.passed ? "geçti" : "geçmedi"}{a.percent != null ? ` · %${a.percent}` : ""}</summary>
          {a.note && <p className="muted">{a.note}</p>}
          <ol className="stack">
            {a.questions.map((q) => {
              const given = Array.isArray(q.given) ? (q.given as string[]) : typeof q.given === "string" ? [q.given] : [];
              const optText = (id: string) => q.options?.find((o) => o.id === id)?.text ?? id;
              return (
                <li key={q.id}>
                  <p style={{ margin: 0 }}><strong>{q.text}</strong></p>
                  <p style={{ margin: 0 }}>Cevabınız: {q.type === "open" ? (given[0] || "—") : given.length ? given.map(optText).join(", ") : "boş"}</p>
                  <p style={{ margin: 0 }} className={q.correct ? "diff-added" : q.status ? "muted" : "diff-removed"}>
                    {q.status === "pending_review" ? "Değerlendirme bekliyor" : q.status === "unavailable" ? "Soru artık mevcut değil" : q.correct ? "✓ Doğru" : "✗ Yanlış"}
                  </p>
                  {q.correctOptionIds && <p style={{ margin: 0 }} className="muted">Doğru cevap: {q.correctOptionIds.map(optText).join(", ")}</p>}
                </li>
              );
            })}
          </ol>
        </details>
      ))}
    </div>
  );
}

export function SurveyForm({ item, onSubmit }: { item: PlayerItem; onSubmit: (a: Answers) => void }) {
  const [answers, setAnswers] = useState<Answers>({});
  return (
    <form className="stack" onSubmit={(e) => { e.preventDefault(); onSubmit(answers); }}>
      {item.anonymous && <Alert kind="info"><p>Bu anket anonimdir: cevaplarınız adınızla eşleştirilmeden saklanır.</p></Alert>}
      {item.questions?.map((q, n) => (
        <fieldset key={q.id}>
          <legend>{n + 1}. {q.text}</legend>
          {q.type === "scale" && (
            <div className="row" role="radiogroup" aria-label={q.text}>
              {[1, 2, 3, 4, 5].map((v) => (
                <label key={v} className="choice"><input type="radio" name={q.id} checked={answers[q.id] === v} onChange={() => setAnswers({ ...answers, [q.id]: v })} /> {v}</label>
              ))}
            </div>
          )}
          {(q.type === "single" || q.type === "multi") && (q.options ?? []).map((o) => (
            <label key={o.id} className="choice">
              <input type={q.type === "single" ? "radio" : "checkbox"} name={q.id}
                     onChange={(e) => {
                       if (q.type === "single") setAnswers({ ...answers, [q.id]: o.id });
                       else {
                         const cur = (answers[q.id] as string[] | undefined) ?? [];
                         setAnswers({ ...answers, [q.id]: e.target.checked ? [...cur, o.id] : cur.filter((x) => x !== o.id) });
                       }
                     }} />
              <span>{o.text}</span>
            </label>
          ))}
          {q.type === "open" && <textarea aria-label={q.text} rows={3} style={{ width: "100%" }} onChange={(e) => setAnswers({ ...answers, [q.id]: e.target.value })} />}
        </fieldset>
      ))}
      <button className="btn btn-primary">Anketi gönder</button>
    </form>
  );
}
