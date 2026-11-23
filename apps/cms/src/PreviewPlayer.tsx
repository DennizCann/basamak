import { useMemo, useState } from "react";
import {
  Alert, ITEM_TYPE_LABEL, LessonBody, QuestionForm, QuizResult, SurveyForm,
  type Answers, type PlayerItem, type Question, type QuizResultData,
} from "@basamak/ui";
import type { Json } from "./content";

/**
 * Interactive CMS preview built from the SAME player components as the member app.
 * Everything happens in the browser: there is no preview API, so nothing can be written - no progress, points,
 * attempts, survey answers, certificates or analytics. Draft content is only available here because the CMS
 * content API itself requires a CMS (password + TOTP) session.
 */
export function PreviewPlayer({ body, label }: { body: Json; label: string }) {
  const items: Json[] = body?.items ?? [];
  const [current, setCurrent] = useState<string | null>(items[0]?.key ?? null);
  const [run, setRun] = useState(0); // bump to reset all local preview state
  const item = items.find((i) => i.key === current) ?? items[0];

  return (
    <div className="stack" key={run}>
      <Alert kind="warning">
        <p><strong>ÖNİZLEME</strong> · {label}. Burada yapılan hiçbir şey kaydedilmez: ilerleme, puan, deneme hakkı ve anket cevabı yazılmaz.</p>
        <button className="btn" onClick={() => setRun((r) => r + 1)}>Önizlemeyi sıfırla</button>
      </Alert>
      <p className="muted">{body?.summary}</p>
      {items.length === 0 ? <p className="muted">Bu sürümde içerik ögesi yok.</p> : (
        <div className="preview-layout">
          <nav aria-label="Önizleme içeriği" className="card">
            <ol className="list-plain">
              {items.map((i) => (
                <li key={i.key}>
                  <button className="btn item-btn" aria-current={i.key === item?.key ? "step" : undefined} onClick={() => setCurrent(i.key)}>
                    {i.title || "(başlıksız)"} <span className="muted">· {ITEM_TYPE_LABEL[i.type] ?? i.type}</span>
                  </button>
                </li>
              ))}
            </ol>
          </nav>
          <section className="card stack" aria-label="Önizlenen öge">
            {item && <h2>{item.title || "(başlıksız)"}</h2>}
            {item?.type === "lesson" && <LessonBody item={item as PlayerItem} />}
            {item?.type === "quiz" && <QuizPreview quiz={item} />}
            {item?.type === "survey" && <SurveyPreview survey={item} />}
            {item?.type === "tool" && <p className="muted">Araç ögesi (F3).</p>}
          </section>
        </div>
      )}
    </div>
  );
}

/** Same random selection + grading rules as the server, run locally on the draft's question bank. */
function QuizPreview({ quiz }: { quiz: Json }) {
  const [questions, setQuestions] = useState<Question[] | null>(null);
  const [result, setResult] = useState<QuizResultData | null>(null);
  const bank: Json[] = useMemo(() => quiz.questions ?? [], [quiz]);

  const start = () => {
    const shuffled = [...bank].sort(() => Math.random() - 0.5).slice(0, quiz.questionsPerAttempt ?? bank.length);
    setQuestions(shuffled.map((q) => ({
      id: q.id, type: q.type, text: q.text,
      options: (quiz.shuffleOptions === false ? q.options ?? [] : [...(q.options ?? [])].sort(() => Math.random() - 0.5)).map((o: Json) => ({ id: o.id, text: o.text })),
    })));
    setResult(null);
  };
  const grade = (answers: Answers) => {
    let score = 0, max = 0;
    const results = (questions ?? []).map((asked) => {
      const q = bank.find((b) => b.id === asked.id);
      if (q.type === "open") return { id: q.id, status: "pending_review" };
      const pts = q.points ?? 1;
      max += pts;
      const correct = (q.options ?? []).filter((o: Json) => o.correct).map((o: Json) => o.id).sort();
      const given = ([] as string[]).concat((answers[q.id] as string[] | string | undefined) ?? []).sort();
      const ok = JSON.stringify(correct) === JSON.stringify(given);
      if (ok) score += pts;
      return { id: q.id, correct: ok, correctOptionIds: correct };
    });
    const percent = max === 0 ? 100 : Math.round((score * 100) / max);
    setResult({ percent, passingScore: quiz.passingScore, passed: percent >= quiz.passingScore, attemptsLeft: quiz.attemptsAllowed, results });
    setQuestions(null);
  };

  return (
    <div className="stack">
      <p className="muted">Geçme notu %{quiz.passingScore} · {bank.length} soruluk bankadan deneme başına {quiz.questionsPerAttempt} soru · {quiz.attemptsAllowed} deneme hakkı
        {quiz.timeLimitSeconds ? ` · süre ${Math.round(quiz.timeLimitSeconds / 60)} dk` : ""}</p>
      {result && (
        <>
          <QuizResult result={{ ...result, attemptsLeft: result.passed ? 0 : 1 }} />
          <details><summary>Önizleme: doğru cevaplar</summary>
            <ul>{result.results.map((r) => <li key={r.id}>{bank.find((b) => b.id === r.id)?.text}: {r.status ? "açık uçlu" : (r.correctOptionIds ?? []).map((id) => bank.find((b) => b.id === r.id)?.options?.find((o: Json) => o.id === id)?.text).join(", ")}</li>)}</ul>
          </details>
        </>
      )}
      {!questions && <button className="btn btn-primary" onClick={start} disabled={bank.length === 0}>{result ? "Yeni önizleme denemesi" : "Önizleme denemesi başlat"}</button>}
      {questions && <QuestionForm questions={questions} onSubmit={grade} />}
      <details><summary>Soru bankasının tamamı ({bank.length})</summary>
        <ol>{bank.map((q) => <li key={q.id}>{q.text} <span className="muted">({q.type === "single" ? "tek seçimli" : q.type === "multi" ? "çok seçimli" : "açık uçlu"})</span>
          {q.options && <ul>{q.options.map((o: Json) => <li key={o.id}>{o.text}{o.correct ? " ✓" : ""}</li>)}</ul>}</li>)}</ol>
      </details>
    </div>
  );
}

function SurveyPreview({ survey }: { survey: Json }) {
  const [sent, setSent] = useState<Answers | null>(null);
  if (sent) {
    return (
      <Alert kind="info">
        <p>Önizleme: anket gönderildi ama <strong>kaydedilmedi</strong>. Üyenin göreceği teşekkür mesajı: “Anketi doldurdunuz, teşekkürler.”</p>
        <details><summary>Girilen cevaplar</summary><pre>{JSON.stringify(sent, null, 2)}</pre></details>
        <button className="btn" onClick={() => setSent(null)}>Tekrar dene</button>
      </Alert>
    );
  }
  return <SurveyForm item={survey as PlayerItem} onSubmit={setSent} />;
}
