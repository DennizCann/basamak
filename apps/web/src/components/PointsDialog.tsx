import { useEffect, useRef, useState } from "react";
import { StateView, errorMessage, fmt } from "@basamak/ui";
import { api } from "../auth";

type Rule = { title: string; action: string; points: number; policy: string };
const POLICY: Record<string, string> = {
  ONCE_PER_OBJECT: "her içerik için bir kez",
  ONCE_PER_DAY: "günde bir kez",
  EVERY: "her seferinde",
};

/** Explains points and levels using the published rules (real CMS data). Native <dialog>: focus trap + Escape. */
export function PointsDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const ref = useRef<HTMLDialogElement>(null);
  const [rules, setRules] = useState<Rule[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.open) {
      d.showModal();
      if (!rules) api.get<Rule[]>("/api/point-rules").then(setRules).catch((e) => setError(errorMessage(e)));
    }
    if (!open && d.open) d.close();
  }, [open, rules]);

  return (
    <dialog ref={ref} className="sheet" aria-labelledby="points-title" onClose={onClose}>
      <div className="stack">
        <h2 id="points-title">Puanlar ve seviyeler</h2>
        <p>
          <strong>Seviyen</strong>, öğrenme yolunda henüz tamamlamadığın ilk Journey'dir. Bir Journey'deki eğitimlerin hepsini bitirdiğinde
          sertifika alırsın ve bir sonraki Journey açılır. Seviye eğitim tamamlamaya bağlıdır; puan biriktirerek eğitim atlanmaz.
        </p>
        <p><strong>Puanlar</strong> yaptığın her işten gelir ve toplam puanına eklenir. Aynı işi tekrarlamak puanı artırmaz.</p>
        <StateView loading={!rules && !error} error={error}>
          <div className="table-wrap">
            <table className="rule-table">
              <thead><tr><th>İş</th><th>Puan</th></tr></thead>
              <tbody>
                {rules?.map((r) => (
                  <tr key={r.action + r.title}><td>{r.title}<br /><span className="muted" style={{ fontSize: "var(--font-size-sm)" }}>{POLICY[r.policy] ?? ""}</span></td><td>{fmt.num(r.points)}</td></tr>
                ))}
              </tbody>
            </table>
          </div>
        </StateView>
        <form method="dialog"><button className="btn btn-primary">Kapat</button></form>
      </div>
    </dialog>
  );
}
