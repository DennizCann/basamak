/** Thin progress meter; the numbers are outside the bar so they always have full text contrast. */
export function Meter({ value, max, label, unit = "içerik" }: { value: number; max: number; label: string; unit?: string }) {
  const pct = max === 0 ? 0 : Math.round((value / max) * 100);
  return (
    <div className={`meter ${max > 0 && value >= max ? "is-done" : ""}`}>
      <div className="meter-track" role="progressbar" aria-label={label} aria-valuemin={0} aria-valuemax={max} aria-valuenow={value}>
        <div className="meter-fill" style={{ width: pct + "%" }} />
      </div>
      <span className="meter-label">{value}/{max} {unit}</span>
    </div>
  );
}
