import type { ToolInput } from "./components/ToolRunner";

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type Json = any;

export type Step = {
  id: string; type: "tool" | "ai" | "condition" | "workflow";
  tool?: { scope: "CONTENT" | "USER"; id: string; versionNo?: number }; input?: Record<string, string>;
  prompt?: string; model?: string;
  left?: string; op?: string; right?: string; then?: string[]; else?: string[];
  workflowId?: string;
};
export type Definition = { inputs: ToolInput[]; steps: Step[]; output: string };
export type Workflow = {
  id: string; ownerId: string; ownerName: string; name: string; description: string; definition: Definition; versionNo: number;
  visibility: "PRIVATE" | "PUBLIC"; forkedFrom: string | null; forkCount: number; hasWebhook: boolean; cron: string | null;
  cronInput: Json; nextRunAt: string | null; updatedAt: string;
};
export type StepRun = {
  id: number; stepId: string; type: string; status: string; input: Json; output: Json; error: string | null; attempts: number;
  durationMs: number | null; tokens: number; cost: number; childRunId: string | null;
};
export type WorkflowRun = {
  id: string; workflowId: string; workflowName: string; workflowVersionNo: number; trigger: string; status: string; input: Json; output: Json;
  error: string | null; failedStepId: string | null; totalTokens: number; totalCost: number; resumeCount: number;
  createdAt: string; finishedAt: string | null; steps: StepRun[];
};
export type PlatformTool = { id: string; name: string; versionNo: number; description: string; inputs: ToolInput[] };

export const STEP_LABEL: Record<string, string> = { tool: "Araç", ai: "Yapay zekâ", condition: "Koşul", workflow: "Alt akış" };
export const RUN_STATUS: Record<string, string> = {
  QUEUED: "Sırada", RUNNING: "Çalışıyor", SUCCEEDED: "Tamamlandı", FAILED: "Hata", SKIPPED: "Atlandı (koşul)",
};
export const TRIGGER_LABEL: Record<string, string> = { MANUAL: "Elle", CRON: "Zamanlı", WEBHOOK: "Webhook", SUBFLOW: "Alt akış" };
export const OPS: [string, string][] = [
  ["gt", "büyüktür"], ["gte", "büyük ya da eşittir"], ["lt", "küçüktür"], ["lte", "küçük ya da eşittir"], ["eq", "eşittir"],
  ["neq", "eşit değildir"], ["contains", "içerir"], ["empty", "boştur"], ["notEmpty", "boş değildir"],
];

export function newStepId(steps: Step[], type: string): string {
  let n = steps.length + 1;
  const base = { tool: "arac", ai: "yz", condition: "kosul", workflow: "alt" }[type] ?? "adim";
  while (steps.some((s) => s.id === `${base}${n}`)) n++;
  return `${base}${n}`;
}

export function fmtCost(usd: number): string {
  return `$${Number(usd).toFixed(6)}`;
}
