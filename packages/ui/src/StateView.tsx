import type { ReactNode } from "react";

type Props = {
  loading?: boolean;
  error?: string | null;
  empty?: boolean;
  emptyText?: string;
  onRetry?: () => void;
  children?: ReactNode;
};

/** Standard loading / error / empty states used by every screen. */
export function StateView({ loading, error, empty, emptyText = "Henüz bir şey yok.", onRetry, children }: Props) {
  if (loading) {
    return (
      <div role="status" aria-live="polite" className="stack">
        <span className="visually-hidden">Yükleniyor…</span>
        <div className="skeleton" style={{ height: "1.5rem", width: "60%" }} />
        <div className="skeleton" style={{ height: "1rem" }} />
        <div className="skeleton" style={{ height: "1rem", width: "80%" }} />
      </div>
    );
  }
  if (error) {
    return (
      <div role="alert" className="alert alert-error">
        <p>{error}</p>
        {onRetry && <button className="btn" onClick={onRetry}>Tekrar dene</button>}
      </div>
    );
  }
  if (empty) return <p className="muted">{emptyText}</p>;
  return <>{children}</>;
}
