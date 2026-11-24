import { useEffect, useRef, useState, type ReactNode } from "react";

export type WizardStep = { title: string; hint?: string };

/**
 * Step-by-step form shell: a numbered step list ("Adım 2 / 4"), one step's content at a time, and Back / Next.
 * `validate` runs before moving forward and returns the reason the step is not complete (shown, focus kept).
 * Focus moves to the step heading on every change so keyboard and screen-reader users know where they are.
 */
export function Wizard({ steps, current, onChange, validate, finishLabel, onFinish, busy, finishDisabledReason, onCancel, error, children }: {
  steps: WizardStep[]; current: number; onChange: (i: number) => void; validate?: (i: number) => string | null;
  finishLabel: string; onFinish: () => void; busy?: boolean; finishDisabledReason?: string | null; onCancel?: () => void; error?: string | null; children: ReactNode;
}) {
  const heading = useRef<HTMLHeadingElement>(null);
  const [reason, setReason] = useState<string | null>(null);
  useEffect(() => setReason(null), [current]);
  const shown = useRef(current); // focus only when the step really changes (not on mount / StrictMode re-run)
  useEffect(() => {
    if (shown.current === current) return;
    shown.current = current;
    heading.current?.focus();
  }, [current]);
  const last = current === steps.length - 1;
  const next = () => {
    const reason = validate?.(current) ?? null;
    if (reason) { setReason(reason); return; }
    if (last) onFinish(); else onChange(current + 1);
  };
  return (
    <div className="wizard stack">
      <ol className="wizard-steps" aria-label="Adımlar">
        {steps.map((s, i) => (
          <li key={s.title} aria-current={i === current ? "step" : undefined} className={i < current ? "done" : i === current ? "current" : ""}>
            {i < current
              ? <button type="button" className="wizard-step-btn" onClick={() => onChange(i)}><span className="wizard-num" aria-hidden="true">✓</span><span>{s.title}</span></button>
              : <span className="wizard-step-btn" aria-disabled="true"><span className="wizard-num" aria-hidden="true">{i + 1}</span><span>{s.title}</span></span>}
          </li>
        ))}
      </ol>
      <div className="stack">
        <h3 ref={heading} tabIndex={-1} className="wizard-title">
          <span className="hint-line">Adım {current + 1} / {steps.length}</span><br />{steps[current].title}
        </h3>
        {steps[current].hint && <p className="muted" style={{ margin: 0 }}>{steps[current].hint}</p>}
        {children}
        {last && finishDisabledReason && <p className="hint" style={{ margin: 0 }}>{finishDisabledReason}</p>}
        {(reason || error) && <div role="alert" className="alert alert-error"><p>{reason ?? error}</p></div>}
      </div>
      <div className="wizard-nav">
        {current > 0 ? <button type="button" className="btn" onClick={() => onChange(current - 1)}>← Geri</button>
          : onCancel ? <button type="button" className="btn" onClick={onCancel}>Vazgeç</button> : <span />}
        <button type="button" className="btn btn-primary" disabled={busy || (last && !!finishDisabledReason)} title={last && finishDisabledReason ? finishDisabledReason : undefined} onClick={next}>{last ? (busy ? "Kaydediliyor…" : finishLabel) : "İleri →"}</button>
      </div>
    </div>
  );
}

/** Small "choose one" card list (templates, step types): a real radio group, so arrows and screen readers work. */
export function ChoiceCards<T extends string>({ name, legend, options, value, onChange }: {
  name: string; legend: string; options: { value: T; title: string; description: string }[]; value: T | null; onChange: (v: T) => void;
}) {
  return (
    <fieldset className="choice-cards">
      <legend>{legend}</legend>
      {options.map((o) => (
        <label key={o.value} className={`choice-card ${value === o.value ? "selected" : ""}`}>
          <input type="radio" name={name} value={o.value} checked={value === o.value} onChange={() => onChange(o.value)} />
          <span><strong>{o.title}</strong><span className="hint-line">{o.description}</span></span>
        </label>
      ))}
    </fieldset>
  );
}
