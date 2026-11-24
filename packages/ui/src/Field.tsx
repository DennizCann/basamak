import { useId, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from "react";

type Common = { label: string; hint?: string; error?: string | null };

/** Labelled input with hint and error wired through aria-describedby / aria-invalid. */
export function TextField({ label, hint, error, ...rest }: Common & InputHTMLAttributes<HTMLInputElement>) {
  const id = useId();
  const desc = [hint ? id + "-h" : "", error ? id + "-e" : ""].filter(Boolean).join(" ") || undefined;
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <input id={id} aria-invalid={!!error} aria-describedby={desc} {...rest} />
      {hint && <span id={id + "-h"} className="hint">{hint}</span>}
      {error && <span id={id + "-e"} className="error" role="alert">{error}</span>}
    </div>
  );
}

export function TextArea({ label, hint, error, ...rest }: Common & TextareaHTMLAttributes<HTMLTextAreaElement>) {
  const id = useId();
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <textarea id={id} aria-invalid={!!error} aria-describedby={hint ? id + "-h" : undefined} {...rest} />
      {hint && <span id={id + "-h"} className="hint">{hint}</span>}
      {error && <span className="error" role="alert">{error}</span>}
    </div>
  );
}

export function SelectField({ label, hint, children, ...rest }: Common & SelectHTMLAttributes<HTMLSelectElement> & { children: ReactNode }) {
  const id = useId();
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <select id={id} {...rest}>{children}</select>
      {hint && <span className="hint">{hint}</span>}
    </div>
  );
}

export function Alert({ kind = "info", children }: { kind?: "info" | "error" | "success" | "warning"; children: ReactNode }) {
  return (
    <div className={`alert alert-${kind}`} role={kind === "error" ? "alert" : "status"}>
      {children}
    </div>
  );
}

export function ProgressBar({ value, max, label }: { value: number; max: number; label: string }) {
  const pct = max === 0 ? 0 : Math.round((value / max) * 100);
  return (
    <div className="progress" role="progressbar" aria-valuemin={0} aria-valuemax={max} aria-valuenow={value} aria-label={label}>
      <div className="progress-bar" style={{ width: pct + "%" }} />
      <span className="progress-text">{value}/{max}</span>
    </div>
  );
}

/** Turkish number/date formatting (1.250 puan, 23.11.2026). */
const trCollator = new Intl.Collator("tr", { sensitivity: "base", numeric: true });

/** Turkish alphabetical order (ç after c, ı before i, İ/i and I/ı handled): use for every name/title list. */
export const trCompare = (a: string, b: string) => trCollator.compare(a, b);

export const fmt = {
  num: (n: number) => new Intl.NumberFormat("tr-TR").format(n),
  date: (iso: string) => new Intl.DateTimeFormat("tr-TR", { timeZone: "Europe/Istanbul", day: "2-digit", month: "2-digit", year: "numeric" }).format(new Date(iso)),
  dateTime: (iso: string) =>
    new Intl.DateTimeFormat("tr-TR", { timeZone: "Europe/Istanbul", day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit" }).format(new Date(iso)),
};
