package com.basamak.learning;

import com.basamak.content.ContentType;
import com.basamak.content.PublishedContent;
import com.basamak.content.PublishedContent.Published;
import com.basamak.gamification.ActivityEvent;
import com.basamak.gamification.GamificationService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Journey = level. Rules (see TASARIM §5):
 *  - A journey is complete when every training currently in it is completed by the member.
 *  - It is locked if it requires the previous journey (by order) and that one is not complete, or if the member
 *    has fewer points than unlock.minPoints.
 *  - Level = the first journey that is not yet complete. Levels never go down: if a training is added to a
 *    journey the member already finished, the certificate stays; the new training shows as "yeni".
 */
@Service
@RequiredArgsConstructor
public class JourneyProgressService {
    private final JdbcTemplate jdbc;
    private final PublishedContent published;
    private final GamificationService gamification;
    private final CertificateService certificates;

    public record TrainingProgress(UUID id, String title, String status) {}

    /**
     * comingSoon: published but without any published training (possible for journeys published before the
     * empty-journey publish guard, or when its last training is unpublished). It is shown as "Yakında", is never
     * auto-completed (no points/certificates), and the next journey names it as the reason it is still locked.
     */
    public record JourneyProgress(UUID id, String title, String description, int order, int versionNo,
                                  List<TrainingProgress> trainings, int completedCount, boolean completed,
                                  boolean locked, String lockReason, String certificateSerial, boolean comingSoon) {}

    @Transactional(readOnly = true)
    public List<JourneyProgress> forUser(UUID userId) {
        Map<UUID, String> status = new HashMap<>();
        jdbc.query("SELECT training_id, status FROM enrollment WHERE user_id = ?",
                rs -> { status.put((UUID) rs.getObject(1), rs.getString(2)); }, userId);
        Map<UUID, String> certs = new HashMap<>();
        jdbc.query("SELECT journey_id, serial FROM certificate WHERE user_id = ? AND revoked_at IS NULL",
                rs -> { certs.put((UUID) rs.getObject(1), rs.getString(2)); }, userId);
        int points = gamification.total(userId);

        List<Published> journeys = new ArrayList<>(published.list(ContentType.JOURNEY));
        journeys.sort(Comparator.comparingInt(j -> j.body().path("order").asInt()));
        List<JourneyProgress> out = new ArrayList<>();
        boolean previousComplete = true;
        String previousTitle = null;
        for (Published j : journeys) {
            List<TrainingProgress> ts = new ArrayList<>();
            for (JsonNode tid : j.body().path("trainingIds")) {
                UUID id = UUID.fromString(tid.asText());
                published.get(id, ContentType.TRAINING).ifPresent(t ->
                        ts.add(new TrainingProgress(id, t.title(), status.getOrDefault(id, "NOT_STARTED"))));
            }
            int done = (int) ts.stream().filter(t -> "COMPLETED".equals(t.status())).count();
            boolean complete = certs.containsKey(j.itemId()) || (!ts.isEmpty() && done == ts.size());
            String reason = null;
            JsonNode unlock = j.body().path("unlock");
            if (unlock.path("requiresPreviousJourney").asBoolean(false) && !previousComplete) {
                reason = "Bu Journey, '" + previousTitle + "' tamamlanınca açılır.";
            } else if (points < unlock.path("minPoints").asInt(0)) {
                reason = "Bu Journey'i açmak için en az " + unlock.path("minPoints").asInt() + " puan gerekiyor (şu an " + points + ").";
            }
            out.add(new JourneyProgress(j.itemId(), j.title(), j.body().path("description").asText(""),
                    j.body().path("order").asInt(), j.versionNo(), ts, done, complete, reason != null, reason,
                    certs.get(j.itemId()), ts.isEmpty() && !complete));
            previousComplete = complete;
            previousTitle = j.title();
        }
        return out;
    }

    public String level(List<JourneyProgress> ps) {
        for (JourneyProgress p : ps) if (!p.completed()) return p.title();
        return ps.isEmpty() ? null : ps.get(ps.size() - 1).title();
    }

    /** Why the member may not start this training yet, or null if they may. */
    public String lockReason(UUID userId, UUID trainingId, JsonNode trainingBody) {
        List<JourneyProgress> ps = forUser(userId);
        List<JourneyProgress> containing = ps.stream().filter(p -> p.trainings().stream().anyMatch(t -> t.id().equals(trainingId))).toList();
        if (!containing.isEmpty() && containing.stream().allMatch(JourneyProgress::locked)) return containing.get(0).lockReason();
        for (JsonNode pre : trainingBody.path("prerequisites")) {
            UUID pid = UUID.fromString(pre.asText());
            String st = jdbc.queryForList("SELECT status FROM enrollment WHERE user_id = ? AND training_id = ?", String.class, userId, pid)
                    .stream().findFirst().orElse("NOT_STARTED");
            if (!"COMPLETED".equals(st)) {
                String title = published.get(pid, ContentType.TRAINING).map(Published::title).orElse("ön koşul eğitimi");
                return "Önce '" + title + "' eğitimini tamamlamalısınız.";
            }
        }
        return null;
    }

    /** Called inside the training-completion transaction. Issues certificates for newly completed journeys. */
    @Transactional
    public List<String> onTrainingCompleted(UUID userId, UUID trainingId) {
        List<String> serials = new ArrayList<>();
        for (JourneyProgress p : forUser(userId)) {
            boolean contains = p.trainings().stream().anyMatch(t -> t.id().equals(trainingId));
            if (!contains || p.certificateSerial() != null) continue;
            boolean allDone = !p.trainings().isEmpty() && p.trainings().stream().allMatch(t -> "COMPLETED".equals(t.status()));
            if (!allDone) continue;
            String serial = certificates.issue(userId, p.id(), p.versionNo(), p.title());
            gamification.record(ActivityEvent.web(userId, "journey-complete:" + userId + ":" + p.id(), "JOURNEY_COMPLETED",
                    "JOURNEY", p.id().toString(), p.versionNo()));
            serials.add(serial);
        }
        return serials;
    }
}
