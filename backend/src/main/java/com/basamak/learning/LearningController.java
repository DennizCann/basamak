package com.basamak.learning;

import com.basamak.auth.AuthPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Member learning API. Clients may send an Idempotency-Key header on actions; mobile offline sync re-sends the
 * same key so an action recorded offline and synced twice is counted once.
 */
@RestController
@RequiredArgsConstructor
public class LearningController {
    private final LearningService learning;
    private final JourneyProgressService journeys;
    private final CertificateService certificates;
    private final com.basamak.gamification.GamificationService gamification;
    private final com.basamak.content.PublishedContent published;

    public record AnswersReq(JsonNode answers) {}

    public record ProgressReq(int positionSeconds) {}

    @GetMapping("/api/trainings/{id}")
    public Map<String, Object> training(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        return learning.view(p.userId(), id);
    }

    @PostMapping("/api/trainings/{id}/enroll")
    public Map<String, Object> enroll(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        return learning.enroll(p.userId(), id, p.audience());
    }

    @PostMapping("/api/trainings/{id}/switch-version")
    public Map<String, Object> switchVersion(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        return learning.switchToLatest(p.userId(), id);
    }

    @PostMapping("/api/trainings/{id}/items/{key}/view")
    public Map<String, Object> view(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id, @PathVariable String key,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return learning.viewItem(p.userId(), id, key, idem == null ? null : p.userId() + ":" + idem, p.audience());
    }

    @PostMapping("/api/trainings/{id}/items/{key}/progress")
    public Map<String, Object> progress(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id, @PathVariable String key,
                                        @RequestBody ProgressReq r) {
        return learning.videoProgress(p.userId(), id, key, r.positionSeconds());
    }

    @PostMapping("/api/trainings/{id}/items/{key}/complete")
    public Map<String, Object> complete(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id, @PathVariable String key) {
        return learning.completeLesson(p.userId(), id, key, p.audience());
    }

    @PostMapping("/api/trainings/{id}/items/{key}/attempts")
    public Map<String, Object> startQuiz(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id, @PathVariable String key) {
        return learning.startQuiz(p.userId(), id, key);
    }

    @GetMapping("/api/trainings/{id}/items/{key}/attempts")
    public List<Map<String, Object>> reviewQuiz(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id, @PathVariable String key) {
        return learning.reviewAttempts(p.userId(), id, key);
    }

    @PostMapping("/api/quiz-attempts/{attemptId}/submit")
    public Map<String, Object> submitQuiz(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID attemptId, @RequestBody AnswersReq r) {
        return learning.submitQuiz(p.userId(), attemptId, r.answers(), p.audience());
    }

    @PostMapping("/api/trainings/{id}/items/{key}/survey")
    public Map<String, Object> survey(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id, @PathVariable String key,
                                      @RequestBody AnswersReq r) {
        return learning.submitSurvey(p.userId(), id, key, r.answers(), p.audience());
    }

    @GetMapping("/api/me/summary")
    public Map<String, Object> summary(@AuthenticationPrincipal AuthPrincipal p) {
        var progress = journeys.forUser(p.userId());
        return Map.of("totalPoints", gamification.total(p.userId()),
                "level", journeys.level(progress) == null ? "" : journeys.level(progress),
                "recentPoints", gamification.recentLedger(p.userId(), 20),
                "certificates", certificates.forUser(p.userId()));
    }

    @GetMapping("/api/me/certificates/{serial}/pdf")
    public ResponseEntity<byte[]> certificatePdf(@AuthenticationPrincipal AuthPrincipal p, @PathVariable String serial) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"basamak-sertifika-" + serial + ".pdf\"")
                .body(certificates.pdf(serial, p.userId()));
    }

    @GetMapping("/api/public/certificates/{serial}")
    public Map<String, Object> verify(@PathVariable String serial) {
        return certificates.verify(serial);
    }

    /** Published point rules, so members can see how points are earned (real CMS data, read-only). */
    @GetMapping("/api/point-rules")
    public List<Map<String, Object>> pointRules() {
        return published.list(com.basamak.content.ContentType.POINT_RULE).stream()
                .map(r -> Map.<String, Object>of("title", r.title(), "action", r.body().path("action").asText(),
                        "points", r.body().path("points").asInt(), "policy", r.body().path("policy").asText("ONCE_PER_OBJECT")))
                .filter(r -> ((Integer) r.get("points")) > 0)
                .sorted(java.util.Comparator.comparingInt(r -> (Integer) r.get("points")))
                .toList();
    }

    /** Catalogue of published trainings with the member's lock state (read-only). */
    @GetMapping("/api/catalog/trainings")
    public List<JourneyProgressService.CatalogEntry> catalog(@AuthenticationPrincipal AuthPrincipal p) {
        return journeys.catalog(p.userId());
    }

    @GetMapping("/api/me/journeys")
    public List<JourneyProgressService.JourneyProgress> myJourneys(@AuthenticationPrincipal AuthPrincipal p) {
        return journeys.forUser(p.userId());
    }
}
