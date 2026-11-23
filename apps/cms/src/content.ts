// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type Json = any;

export type Version = {
  id: string; itemId: string; versionNo: number; status: string; title: string; body: Json; basedOnNo: number | null;
  createdBy: string; createdAt: string; reviewedBy: string | null; reviewedAt: string | null; reviewNote: string | null;
  publishedBy: string | null; publishedAt: string | null; scheduledAt: string | null;
};
export type Item = { id: string; type: string; owner: string; lockVersion: number; publishedVersionNo: number | null; latest: Version; updatedAt: string };

/** Content types with their list route and Turkish label (single source for menus, headings and back links). */
export const TYPES = [
  { type: "JOURNEY", label: "Journey'ler", single: "Journey", path: "journeys" },
  { type: "TRAINING", label: "Eğitimler", single: "Eğitim", path: "trainings" },
  { type: "POINT_RULE", label: "Puan kuralları", single: "Puan kuralı", path: "point-rules" },
] as const;

/** Safe internal back link for a content type (never taken from the URL, so no open redirect). */
export function listPathFor(type: string): string {
  return `/content/${TYPES.find((t) => t.type === type)?.path ?? "journeys"}`;
}
export function typeLabel(type: string): string {
  return TYPES.find((t) => t.type === type)?.single ?? type;
}

export const AUDIT_LABEL: Record<string, string> = {
  "content.created": "İçerik oluşturuldu", "content.draft_saved": "Taslak kaydedildi", "content.submitted": "İncelemeye gönderildi",
  "content.approved": "Onaylandı", "content.changes_requested": "Düzeltme istendi", "content.scheduled": "Yayın zamanlandı",
  "content.published": "Yayınlandı", "content.published_scheduled": "Zamanlanmış yayın yapıldı", "content.republished": "Eski sürüm yeniden yayınlandı",
};

export const STATUS_LABEL: Record<string, string> = {
  DRAFT: "Taslak", IN_REVIEW: "İncelemede", CHANGES_REQUESTED: "Düzeltme istendi", APPROVED: "Onaylandı",
  SCHEDULED: "Zamanlandı", PUBLISHED: "Yayında", ARCHIVED: "Eski yayın", SUPERSEDED: "Üzerine yazıldı",
};

export function defaultBody(type: string, order: number): Json {
  switch (type) {
    case "JOURNEY": return { description: "", order, trainingIds: [], unlock: { requiresPreviousJourney: true, minPoints: 0 } };
    case "TRAINING": return { summary: "", prerequisites: [], items: [{ key: "ders-1", type: "lesson", title: "İlk ders", content: "Ders metni" }] };
    case "POINT_RULE": return { action: "LESSON_VIEWED", objectType: "LESSON", points: 1, policy: "ONCE_PER_OBJECT" };
    default: return {};
  }
}

/** Europe/Istanbul is UTC+3 all year (no DST since 2016): datetime-local values are interpreted in Istanbul time. */
export function istanbulLocalToIso(local: string): string {
  return new Date(local + ":00+03:00").toISOString();
}
