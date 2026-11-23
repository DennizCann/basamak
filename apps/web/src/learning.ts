export type TrainingStatus = "NOT_STARTED" | "IN_PROGRESS" | "COMPLETED";
export type Training = {
  id: string; title: string; status: TrainingStatus; summary: string; itemCount: number; completedItems: number;
  currentKey: string | null; lastActivityAt: string | null;
};
export type Journey = {
  id: string; title: string; description: string; order: number; trainings: Training[];
  completedCount: number; completed: boolean; locked: boolean; lockReason: string | null; certificateSerial: string | null;
  comingSoon: boolean;
};
export type Certificate = { serial: string; journey_title: string; issued_at: string };
export type Summary = { totalPoints: number; level: string; certificates: Certificate[] };

export function trainingHref(t: Training) {
  return t.status === "IN_PROGRESS" && t.currentKey ? `/trainings/${t.id}?item=${encodeURIComponent(t.currentKey)}` : `/trainings/${t.id}`;
}

export type NextAction =
  | { kind: "empty" }
  | { kind: "welcome"; training: Training; journey: Journey }
  | { kind: "continue"; training: Training; journey: Journey }
  | { kind: "next"; training: Training; journey: Journey }
  | { kind: "done"; waitingOn: Journey | null };

/**
 * The single most useful next step, derived only from real progress data:
 * 1) the in-progress training with the most recent activity (its stored current item),
 * 2) otherwise the first not-started training of the first open journey,
 * 3) otherwise everything available is done -> explain what the next journey waits for (never a fake "continue").
 */
export function nextAction(journeys: Journey[]): NextAction {
  if (journeys.length === 0) return { kind: "empty" };
  const open = journeys.filter((j) => !j.locked && !j.comingSoon);
  const inProgress = open.flatMap((j) => j.trainings.filter((t) => t.status === "IN_PROGRESS").map((t) => ({ t, j })))
    .sort((a, b) => (b.t.lastActivityAt ?? "").localeCompare(a.t.lastActivityAt ?? ""));
  if (inProgress.length) return { kind: "continue", training: inProgress[0].t, journey: inProgress[0].j };
  const anyStarted = journeys.some((j) => j.trainings.some((t) => t.status !== "NOT_STARTED"));
  for (const j of open) {
    const t = j.trainings.find((x) => x.status === "NOT_STARTED");
    if (t) return { kind: anyStarted ? "next" : "welcome", training: t, journey: j };
  }
  return { kind: "done", waitingOn: journeys.find((j) => !j.completed) ?? null };
}

/** Journey shown as the learner's current position on the path. */
export function currentJourneyId(journeys: Journey[], action: NextAction): string | null {
  if (action.kind === "continue" || action.kind === "next" || action.kind === "welcome") return action.journey.id;
  return journeys.find((j) => !j.completed)?.id ?? null;
}
