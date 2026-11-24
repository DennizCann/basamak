import type { Definition, Step } from "../workflows";
import { STEP_LABEL } from "../workflows";

const W = 150, H = 54, GX = 24, GY = 46, PAD = 16;

/** Groups like the engine: a "parallel" step joins the row of the step before it. */
function rows(steps: Step[]): Step[][] {
  const out: Step[][] = [];
  for (const s of steps) {
    if (s.parallel && out.length) out[out.length - 1].push(s);
    else out.push([s]);
  }
  return out;
}

function refs(s: Step): string[] {
  const text = JSON.stringify({ ...s, then: undefined, else: undefined });
  return Array.from(new Set(Array.from(text.matchAll(/\{\{\s*steps\.([A-Za-z0-9_-]+)/g), (m) => m[1])));
}

/**
 * Visual view of the workflow being built: rows top to bottom in execution order, side-by-side boxes run in parallel,
 * solid arrows = data flow (an output used as input), dashed arrows = condition branches (evet / hayır).
 * Updates live while the member edits the form; the list below the graph says the same in text (screen readers).
 */
export function FlowGraph({ def }: { def: Definition }) {
  if (!def.steps.length) return null;
  const grid = rows(def.steps);
  const maxCols = Math.max(...grid.map((r) => r.length));
  const width = PAD * 2 + maxCols * W + (maxCols - 1) * GX;
  const height = PAD * 2 + grid.length * H + (grid.length - 1) * GY;
  const pos = new Map<string, { x: number; y: number }>();
  grid.forEach((row, ri) => {
    const rowW = row.length * W + (row.length - 1) * GX;
    const x0 = (width - rowW) / 2;
    row.forEach((s, ci) => pos.set(s.id, { x: x0 + ci * (W + GX), y: PAD + ri * (H + GY) }));
  });
  type Edge = { from: string; to: string; kind: "data" | "yes" | "no" };
  const edges: Edge[] = [];
  for (const s of def.steps) {
    for (const r of refs(s)) if (pos.has(r) && r !== s.id) edges.push({ from: r, to: s.id, kind: "data" });
    if (s.type === "condition") {
      (s.then ?? []).forEach((t) => pos.has(t) && edges.push({ from: s.id, to: t, kind: "yes" }));
      (s.else ?? []).forEach((t) => pos.has(t) && edges.push({ from: s.id, to: t, kind: "no" }));
    }
  }
  const text = edges.map((e) => `${e.from} → ${e.to}${e.kind === "yes" ? " (koşul doğruysa)" : e.kind === "no" ? " (koşul yanlışsa)" : ""}`);
  return (
    <figure className="flow-graph" aria-labelledby="flow-graph-cap">
      <div className="flow-graph-scroll">
        <svg viewBox={`0 0 ${width} ${height}`} width={width} height={height} role="img" aria-label="İş akışı şeması">
          <defs>
            <marker id="fg-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M0 0L10 5L0 10z" fill="currentColor" />
            </marker>
          </defs>
          {edges.map((e, i) => {
            const a = pos.get(e.from)!, b = pos.get(e.to)!;
            const x1 = a.x + W / 2, y1 = a.y + H, x2 = b.x + W / 2, y2 = b.y;
            const midY = (y1 + y2) / 2;
            return (
              <g key={i} className={`fg-edge fg-${e.kind}`}>
                <path d={`M${x1} ${y1} C${x1} ${midY} ${x2} ${midY} ${x2} ${y2 - 2}`} fill="none" stroke="currentColor" strokeWidth="1.6"
                      strokeDasharray={e.kind === "data" ? undefined : "5 4"} markerEnd="url(#fg-arrow)" />
                {e.kind !== "data" && <text x={(x1 + x2) / 2 + 4} y={midY - 4} fontSize="11" fill="currentColor">{e.kind === "yes" ? "evet" : "hayır"}</text>}
              </g>
            );
          })}
          {def.steps.map((s) => {
            const p = pos.get(s.id)!;
            return (
              <g key={s.id} className={`fg-node fg-${s.type}`}>
                <rect x={p.x} y={p.y} width={W} height={H} rx="10" />
                <text x={p.x + 10} y={p.y + 21} fontSize="11" className="fg-type">{STEP_LABEL[s.type]}{s.parallel ? " · paralel" : ""}</text>
                <text x={p.x + 10} y={p.y + 40} fontSize="13" fontWeight="600">{s.id.length > 18 ? s.id.slice(0, 17) + "…" : s.id}</text>
              </g>
            );
          })}
        </svg>
      </div>
      <figcaption id="flow-graph-cap" className="hint-line">
        Şema: yukarıdan aşağı çalışma sırası; yan yana kutular aynı anda çalışır. Düz ok veri akışı, kesik ok koşul dalı.
        {text.length > 0 && <span className="visually-hidden"> Bağlantılar: {text.join("; ")}.</span>}
      </figcaption>
    </figure>
  );
}
