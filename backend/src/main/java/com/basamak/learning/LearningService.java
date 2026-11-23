package com.basamak.learning;

import com.basamak.common.ApiException;
import com.basamak.content.ContentType;
import com.basamak.content.PublishedContent;
import com.basamak.content.PublishedContent.Published;
import com.basamak.content.TrainingSchema;
import com.basamak.gamification.ActivityEvent;
import com.basamak.gamification.GamificationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Member learning: enrolment, lesson/quiz/survey progress, version pinning.
 *
 * Version rule (scenario 3): an enrolment is pinned to the training version it started on. Publishing a new
 * version never changes what an in-progress member sees; they can finish on their version, and they may opt in
 * to the new one ("switch version"), which carries over progress for item keys that still exist.
 * New enrolments always start on the latest published version.
 */
@Service
@RequiredArgsConstructor
public class LearningService {
    static final Duration QUIZ_GRACE = Duration.ofSeconds(60);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final PublishedContent published;
    private final GamificationService gamification;
    private final JourneyProgressService journeys;
    private final ObjectMapper json;
    private final com.basamak.notification.NotificationService notifications;

    record Enrollment(UUID id, UUID trainingId, int versionNo, String status, String currentKey) {}

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public Map<String, Object> view(UUID userId, UUID trainingId) {
        Optional<Enrollment> enr = enrollment(userId, trainingId);
        Optional<Published> latest = published.get(trainingId, ContentType.TRAINING);
        Published shown = enr.flatMap(e -> published.pinned(trainingId, e.versionNo()))
                .or(() -> latest)
                .orElseThrow(() -> ApiException.notFound("Eğitim"));

        Set<String> done = enr.map(e -> completedKeys(e.id())).orElse(Set.of());
        ArrayNode items = json.createArrayNode();
        for (JsonNode it : shown.body().path("items")) {
            // without an enrolment only the outline is returned: a direct link cannot bypass journey locks/prerequisites
            ObjectNode s = sanitize(it, enr.isPresent());
            String key = it.path("key").asText();
            s.put("completed", done.contains(key));
            if ("quiz".equals(it.path("type").asText()) && enr.isPresent()) quizStats(enr.get().id(), key, s);
            items.add(s);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", trainingId);
        out.put("title", shown.title());
        out.put("summary", shown.body().path("summary").asText(""));
        out.put("versionNo", shown.versionNo());
        out.put("latestVersionNo", latest.map(Published::versionNo).orElse(shown.versionNo()));
        out.put("newerVersionAvailable", latest.isPresent() && latest.get().versionNo() != shown.versionNo());
        out.put("items", items);
        out.put("lockReason", journeys.lockReason(userId, trainingId, shown.body()));
        out.put("enrollment", enr.map(e -> Map.of("status", e.status(), "versionNo", e.versionNo(),
                "completedCount", done.size(), "total", items.size(),
                "currentKey", e.currentKey() == null ? "" : e.currentKey())).orElse(null));
        return out;
    }

    // ------------------------------------------------------------------ enrolment

    @Transactional
    public Map<String, Object> enroll(UUID userId, UUID trainingId, String source) {
        Optional<Enrollment> existing = enrollment(userId, trainingId);
        if (existing.isPresent()) return view(userId, trainingId);
        Published t = published.get(trainingId, ContentType.TRAINING).orElseThrow(() -> ApiException.notFound("Eğitim"));
        String lock = journeys.lockReason(userId, trainingId, t.body());
        if (lock != null) throw new ApiException(HttpStatus.FORBIDDEN, "training_locked", lock);
        jdbc.update("""
                INSERT INTO enrollment(user_id, training_id, training_version_no, status) VALUES (?, ?, ?, 'IN_PROGRESS')
                ON CONFLICT (user_id, training_id) DO NOTHING""", userId, trainingId, t.versionNo());
        gamification.record(event(userId, "training-start:" + userId + ":" + trainingId, "TRAINING_STARTED", "TRAINING",
                trainingId.toString(), t.versionNo(), source));
        return view(userId, trainingId);
    }

    /** Opt-in move to the latest published version; progress follows stable item keys. */
    @Transactional
    public Map<String, Object> switchToLatest(UUID userId, UUID trainingId) {
        Enrollment e = requireEnrollment(userId, trainingId);
        Published latest = published.get(trainingId, ContentType.TRAINING).orElseThrow(() -> ApiException.notFound("Eğitim"));
        if (latest.versionNo() == e.versionNo()) return view(userId, trainingId);
        Set<String> keysInNew = new HashSet<>();
        latest.body().path("items").forEach(it -> keysInNew.add(it.path("key").asText()));
        // progress rows for keys that no longer exist are dropped; others are kept as they are
        for (String k : completedKeys(e.id())) {
            if (!keysInNew.contains(k)) jdbc.update("DELETE FROM item_progress WHERE enrollment_id = ? AND item_key = ?", e.id(), k);
        }
        jdbc.update("UPDATE enrollment SET training_version_no = ?, status = 'IN_PROGRESS', completed_at = NULL, updated_at = now() WHERE id = ?",
                latest.versionNo(), e.id());
        maybeComplete(userId, trainingId, "web");
        return view(userId, trainingId);
    }

    // ------------------------------------------------------------------ lessons

    @Transactional
    public Map<String, Object> viewItem(UUID userId, UUID trainingId, String key, String eventKey, String source) {
        Enrollment e = requireEnrollment(userId, trainingId);
        JsonNode item = item(trainingId, e, key);
        jdbc.update("UPDATE enrollment SET current_item_key = ?, updated_at = now() WHERE id = ?", key, e.id());
        String type = item.path("type").asText().toUpperCase(Locale.ROOT);
        var award = gamification.record(event(userId, eventKey == null ? UUID.randomUUID().toString() : eventKey,
                type + "_VIEWED", type, trainingId + ":" + key, e.versionNo(), source));
        return Map.of("pointsAwarded", award.points());
    }

    /** Video heartbeat. Watched time can only grow as fast as wall-clock time passes, so seeking ahead does not count. */
    @Transactional
    public Map<String, Object> videoProgress(UUID userId, UUID trainingId, String key, int positionSeconds) {
        Enrollment e = requireEnrollment(userId, trainingId);
        item(trainingId, e, key);
        jdbc.update("INSERT INTO item_progress(enrollment_id, item_key) VALUES (?, ?) ON CONFLICT DO NOTHING", e.id(), key);
        Map<String, Object> row = jdbc.queryForMap("SELECT watched_seconds, last_beat_at FROM item_progress WHERE enrollment_id = ? AND item_key = ? FOR UPDATE", e.id(), key);
        int watched = ((Number) row.get("watched_seconds")).intValue();
        Timestamp last = (Timestamp) row.get("last_beat_at");
        long allowed = last == null ? 5 : Duration.between(last.toInstant(), Instant.now()).toSeconds() + 5;
        int next = (int) Math.max(watched, Math.min(positionSeconds, watched + allowed));
        jdbc.update("UPDATE item_progress SET watched_seconds = ?, last_beat_at = now() WHERE enrollment_id = ? AND item_key = ?", next, e.id(), key);
        return Map.of("watchedSeconds", next);
    }

    @Transactional
    public Map<String, Object> completeLesson(UUID userId, UUID trainingId, String key, String source) {
        Enrollment e = requireEnrollment(userId, trainingId);
        JsonNode item = item(trainingId, e, key);
        if (!"lesson".equals(item.path("type").asText())) throw ApiException.badRequest("not_a_lesson", "Bu öge bir ders değil.");
        int minSeconds = item.path("video").path("minSeconds").asInt(0);
        if (minSeconds > 0) {
            List<Integer> w = jdbc.queryForList("SELECT watched_seconds FROM item_progress WHERE enrollment_id = ? AND item_key = ?", Integer.class, e.id(), key);
            int watched = w.isEmpty() ? 0 : w.get(0);
            if (watched < minSeconds) {
                throw new ApiException(HttpStatus.CONFLICT, "video_not_watched",
                        "Dersi tamamlamak için videonun en az " + minSeconds + " saniyesini izlemelisiniz (izlenen: " + watched + " sn).");
            }
        }
        markDone(e, key);
        var award = gamification.record(event(userId, "lesson-complete:" + e.id() + ":" + key, "LESSON_COMPLETED", "LESSON",
                trainingId + ":" + key, e.versionNo(), source));
        Map<String, Object> out = new HashMap<>(Map.of("pointsAwarded", award.points()));
        out.putAll(maybeComplete(userId, trainingId, source));
        return out;
    }

    // ------------------------------------------------------------------ quizzes

    @Transactional
    public Map<String, Object> startQuiz(UUID userId, UUID trainingId, String key) {
        Enrollment e = requireEnrollment(userId, trainingId);
        JsonNode quiz = item(trainingId, e, key);
        if (!"quiz".equals(quiz.path("type").asText())) throw ApiException.badRequest("not_a_quiz", "Bu öge bir quiz değil.");
        if (completedKeys(e.id()).contains(key)) throw ApiException.conflict("quiz_passed", "Bu quiz'i zaten geçtiniz.", Map.of());

        // resume an open attempt that is still within its time limit
        List<Map<String, Object>> open = jdbc.queryForList("""
                SELECT id, attempt_no, questions::text AS questions, deadline_at FROM quiz_attempt
                WHERE enrollment_id = ? AND item_key = ? AND submitted_at IS NULL ORDER BY attempt_no DESC LIMIT 1""", e.id(), key);
        if (!open.isEmpty()) {
            Timestamp dl = (Timestamp) open.get(0).get("deadline_at");
            if (dl == null || dl.toInstant().plus(QUIZ_GRACE).isAfter(Instant.now())) return attemptDto(open.get(0), quiz);
            closeExpired((UUID) open.get(0).get("id"));
        }
        int used = jdbc.queryForObject("SELECT count(*) FROM quiz_attempt WHERE enrollment_id = ? AND item_key = ?", Integer.class, e.id(), key);
        int allowed = quiz.path("attemptsAllowed").asInt(1);
        if (used >= allowed) {
            throw ApiException.conflict("no_attempts_left", "Bu quiz için " + allowed + " deneme hakkınızın hepsini kullandınız.", Map.of("attemptsAllowed", allowed));
        }
        // random questions from the bank, options shuffled; correctness is NOT stored in the attempt
        List<JsonNode> bank = new ArrayList<>();
        quiz.path("questions").forEach(bank::add);
        Collections.shuffle(bank, RANDOM);
        int per = quiz.path("questionsPerAttempt").asInt(bank.size());
        ArrayNode chosen = json.createArrayNode();
        for (JsonNode q : bank.subList(0, per)) {
            ObjectNode c = json.createObjectNode();
            c.put("id", q.path("id").asText());
            c.put("type", q.path("type").asText());
            c.put("text", q.path("text").asText());
            c.put("points", q.path("points").asInt(1));
            List<JsonNode> opts = new ArrayList<>();
            q.path("options").forEach(opts::add);
            if (quiz.path("shuffleOptions").asBoolean(true)) Collections.shuffle(opts, RANDOM);
            ArrayNode oa = c.putArray("options");
            opts.forEach(o -> oa.addObject().put("id", o.path("id").asText()).put("text", o.path("text").asText()));
            chosen.add(c);
        }
        int limit = quiz.path("timeLimitSeconds").asInt(0);
        Timestamp deadline = limit > 0 ? Timestamp.from(Instant.now().plusSeconds(limit)) : null;
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO quiz_attempt(id, user_id, enrollment_id, training_id, training_version_no, item_key, attempt_no, questions, deadline_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)""",
                id, userId, e.id(), trainingId, e.versionNo(), key, used + 1, chosen.toString(), deadline);
        return attemptDto(jdbc.queryForMap("SELECT id, attempt_no, questions::text AS questions, deadline_at FROM quiz_attempt WHERE id = ?", id), quiz);
    }

    @Transactional
    public Map<String, Object> submitQuiz(UUID userId, UUID attemptId, JsonNode answers, String source) {
        Map<String, Object> a = jdbc.queryForList("SELECT * FROM quiz_attempt WHERE id = ? AND user_id = ? FOR UPDATE", attemptId, userId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Quiz denemesi"));
        if (a.get("submitted_at") != null) throw ApiException.conflict("already_submitted", "Bu deneme zaten gönderildi.", Map.of());
        UUID trainingId = (UUID) a.get("training_id");
        String key = (String) a.get("item_key");
        int versionNo = (Integer) a.get("training_version_no");
        JsonNode quiz = TrainingSchema.findItem(published.pinned(trainingId, versionNo).orElseThrow().body(), key);
        Timestamp dl = (Timestamp) a.get("deadline_at");
        boolean late = dl != null && Instant.now().isAfter(dl.toInstant().plus(QUIZ_GRACE));

        Map<String, JsonNode> bank = new HashMap<>();
        quiz.path("questions").forEach(q -> bank.put(q.path("id").asText(), q));
        Grade g = grade(bank, read(a.get("questions")), answers, late);
        int score = g.score(), max = g.max();
        ArrayNode results = g.results();
        int percent = max == 0 ? 100 : Math.round(score * 100f / max);
        boolean passed = !late && percent >= quiz.path("passingScore").asInt();
        jdbc.update("UPDATE quiz_attempt SET submitted_at = now(), answers = CAST(? AS jsonb), score = ?, max_score = ?, passed = ? WHERE id = ?",
                answers == null ? "{}" : answers.toString(), score, max, passed, attemptId);
        int usedNow = jdbc.queryForObject("SELECT count(*) FROM quiz_attempt WHERE enrollment_id = ? AND item_key = ?", Integer.class, a.get("enrollment_id"), key);
        if (mayRevealAnswers(quiz, passed, usedNow)) g.revealInto(results);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", score);
        out.put("maxScore", max);
        out.put("percent", percent);
        out.put("passingScore", quiz.path("passingScore").asInt());
        out.put("passed", passed);
        out.put("late", late);
        out.put("results", results);
        int used = jdbc.queryForObject("SELECT count(*) FROM quiz_attempt WHERE enrollment_id = ? AND item_key = ?", Integer.class, a.get("enrollment_id"), key);
        out.put("attemptsLeft", Math.max(0, quiz.path("attemptsAllowed").asInt(1) - used));
        if (passed) {
            Enrollment e = requireEnrollment(userId, trainingId);
            markDone(e, key);
            var award = gamification.record(event(userId, "quiz-pass:" + e.id() + ":" + key, "QUIZ_PASSED", "QUIZ",
                    trainingId + ":" + key, versionNo, source));
            out.put("pointsAwarded", award.points());
            out.putAll(maybeComplete(userId, trainingId, source));
        }
        return out;
    }

    /**
     * Correct answers are shown only if the quiz allows it AND the member can no longer use them to pass:
     * after passing or after the last attempt. Revealing them after a failed attempt with retries left would leak them.
     */
    static boolean mayRevealAnswers(JsonNode quiz, boolean passedAny, int attemptsUsed) {
        return quiz.path("showCorrectAnswers").asBoolean(false)
                && (passedAny || attemptsUsed >= quiz.path("attemptsAllowed").asInt(1));
    }

    record Grade(int score, int max, ArrayNode results, Map<String, Set<String>> correct) {
        void revealInto(ArrayNode rs) {
            for (JsonNode r : rs) {
                Set<String> c = correct.get(r.path("id").asText());
                if (c != null) { ArrayNode ca = ((ObjectNode) r).putArray("correctOptionIds"); c.forEach(ca::add); }
            }
        }
    }

    /** Grades against the pinned version's bank (the exact version the member answered). */
    private Grade grade(Map<String, JsonNode> bank, JsonNode asked, JsonNode answers, boolean late) {
        int score = 0, max = 0;
        ArrayNode results = json.createArrayNode();
        Map<String, Set<String>> correctMap = new HashMap<>();
        for (JsonNode a : asked) {
            JsonNode q = bank.get(a.path("id").asText());
            ObjectNode r = results.addObject().put("id", a.path("id").asText());
            if (q == null) { r.put("status", "unavailable"); continue; }
            if ("open".equals(q.path("type").asText())) { r.put("status", "pending_review"); continue; } // graded by staff
            int pts = q.path("points").asInt(1);
            max += pts;
            Set<String> correct = new TreeSet<>();
            q.path("options").forEach(o -> { if (o.path("correct").asBoolean()) correct.add(o.path("id").asText()); });
            correctMap.put(a.path("id").asText(), correct);
            Set<String> given = new TreeSet<>();
            JsonNode ans = answers == null ? null : answers.get(a.path("id").asText());
            if (ans != null && ans.isArray()) ans.forEach(x -> given.add(x.asText()));
            else if (ans != null && ans.isTextual()) given.add(ans.asText());
            boolean ok = !late && correct.equals(given);
            if (ok) score += pts;
            r.put("correct", ok);
        }
        return new Grade(score, max, results, correctMap);
    }

    /**
     * Read-only review of the member's own submitted attempts: the questions exactly as they were shown, the given
     * answers and the result. Uses the attempt's stored snapshot + its pinned training version, never the current bank.
     * Does not change attempts, points or progress.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> reviewAttempts(UUID userId, UUID trainingId, String key) {
        Enrollment e = requireEnrollment(userId, trainingId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, attempt_no, training_version_no, questions::text AS questions, answers::text AS answers,
                       score, max_score, passed, submitted_at, deadline_at
                FROM quiz_attempt WHERE enrollment_id = ? AND item_key = ? AND user_id = ? AND submitted_at IS NOT NULL
                ORDER BY attempt_no""", e.id(), key, userId);
        boolean passedAny = rows.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("passed")));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            int version = (Integer) r.get("training_version_no");
            JsonNode quiz = published.pinned(trainingId, version).map(p -> TrainingSchema.findItem(p.body(), key)).orElse(null);
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("attemptNo", r.get("attempt_no"));
            dto.put("trainingVersionNo", version);
            dto.put("submittedAt", ((Timestamp) r.get("submitted_at")).toInstant());
            dto.put("passed", r.get("passed"));
            Integer max = (Integer) r.get("max_score");
            dto.put("percent", max == null || max == 0 ? null : Math.round(((Integer) r.get("score")) * 100f / max));
            if (quiz == null || r.get("answers") == null) {
                dto.put("questions", List.of());
                dto.put("note", r.get("answers") == null
                        ? "Süre dolduğu için bu denemede cevap gönderilmedi."
                        : "Bu denemenin sorularına artık ulaşılamıyor.");
                out.add(dto);
                continue;
            }
            Map<String, JsonNode> bank = new HashMap<>();
            quiz.path("questions").forEach(q -> bank.put(q.path("id").asText(), q));
            JsonNode asked = read(r.get("questions"));
            JsonNode answers = read(r.get("answers"));
            Timestamp dl = (Timestamp) r.get("deadline_at");
            boolean late = dl != null && ((Timestamp) r.get("submitted_at")).toInstant().isAfter(dl.toInstant().plus(QUIZ_GRACE));
            Grade g = grade(bank, asked, answers, late);
            if (mayRevealAnswers(quiz, passedAny, rows.size())) g.revealInto(g.results());
            ArrayNode qs = json.createArrayNode();
            for (int i = 0; i < asked.size(); i++) {
                ObjectNode q = asked.get(i).deepCopy();
                q.set("given", answers.path(q.path("id").asText()));
                q.setAll((ObjectNode) g.results().get(i));
                qs.add(q);
            }
            dto.put("questions", qs);
            out.add(dto);
        }
        return out;
    }

    private void closeExpired(UUID attemptId) {
        jdbc.update("UPDATE quiz_attempt SET submitted_at = now(), score = 0, passed = false WHERE id = ? AND submitted_at IS NULL", attemptId);
    }

    private Map<String, Object> attemptDto(Map<String, Object> row, JsonNode quiz) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("attemptId", row.get("id"));
        out.put("attemptNo", row.get("attempt_no"));
        out.put("attemptsAllowed", quiz.path("attemptsAllowed").asInt(1));
        Timestamp dl = (Timestamp) row.get("deadline_at");
        out.put("deadlineAt", dl == null ? null : dl.toInstant());
        out.put("questions", read(row.get("questions")));
        return out;
    }

    // ------------------------------------------------------------------ surveys

    @Transactional
    public Map<String, Object> submitSurvey(UUID userId, UUID trainingId, String key, JsonNode answers, String source) {
        Enrollment e = requireEnrollment(userId, trainingId);
        JsonNode survey = item(trainingId, e, key);
        if (!"survey".equals(survey.path("type").asText())) throw ApiException.badRequest("not_a_survey", "Bu öge bir anket değil.");
        for (JsonNode q : survey.path("questions")) {
            JsonNode ans = answers == null ? null : answers.get(q.path("id").asText());
            if ("scale".equals(q.path("type").asText()) && ans != null && (ans.asInt(0) < 1 || ans.asInt(0) > 5)) {
                throw ApiException.badRequest("invalid_answer", "Ölçekli sorularda 1 ile 5 arasında bir değer seçin.");
            }
        }
        int first = jdbc.update("INSERT INTO survey_submission(user_id, training_id, item_key) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                userId, trainingId, key);
        if (first == 0) throw ApiException.conflict("survey_already_submitted", "Bu anketi zaten doldurdunuz.", Map.of());
        boolean anonymous = survey.path("anonymous").asBoolean(false);
        // anonymous: the answer row has no user id and only a date (no timestamp) to resist re-identification
        jdbc.update("INSERT INTO survey_response(training_id, training_version_no, item_key, user_id, answers) VALUES (?, ?, ?, ?, CAST(? AS jsonb))",
                trainingId, e.versionNo(), key, anonymous ? null : userId, answers == null ? "{}" : answers.toString());
        markDone(e, key);
        var award = gamification.record(event(userId, "survey:" + e.id() + ":" + key, "SURVEY_SUBMITTED", "SURVEY",
                trainingId + ":" + key, e.versionNo(), source));
        Map<String, Object> out = new HashMap<>(Map.of("pointsAwarded", award.points()));
        out.putAll(maybeComplete(userId, trainingId, source));
        return out;
    }

    // ------------------------------------------------------------------ completion

    /** Marks a tool item as done (called by the tool module when a run inside a training succeeds). */
    @Transactional
    public void completeToolItem(UUID userId, UUID trainingId, String key) {
        enrollment(userId, trainingId).ifPresent(e -> {
            markDone(e, key);
            maybeComplete(userId, trainingId, "web");
        });
    }

    private Map<String, Object> maybeComplete(UUID userId, UUID trainingId, String source) {
        Enrollment e = requireEnrollment(userId, trainingId);
        Published version = published.pinned(trainingId, e.versionNo()).orElseThrow();
        Set<String> done = completedKeys(e.id());
        for (JsonNode it : version.body().path("items")) {
            if (!done.contains(it.path("key").asText())) return Map.of("trainingCompleted", false);
        }
        if ("COMPLETED".equals(e.status())) return Map.of("trainingCompleted", true);
        jdbc.update("UPDATE enrollment SET status = 'COMPLETED', completed_at = now(), updated_at = now() WHERE id = ?", e.id());
        gamification.record(event(userId, "training-complete:" + userId + ":" + trainingId, "TRAINING_COMPLETED", "TRAINING",
                trainingId.toString(), e.versionNo(), source));
        List<String> certs = journeys.onTrainingCompleted(userId, trainingId);
        for (String serial : certs) {
            notifications.notify(userId, "CERTIFICATE", "Sertifikan hazır", "Bir Journey'i tamamladın; sertifikanı indirebilirsin.", "/certificates", "cert:" + serial);
        }
        return Map.of("trainingCompleted", true, "certificatesIssued", certs);
    }

    // ------------------------------------------------------------------ helpers

    Optional<Enrollment> enrollment(UUID userId, UUID trainingId) {
        return jdbc.query("SELECT id, training_id, training_version_no, status, current_item_key FROM enrollment WHERE user_id = ? AND training_id = ?",
                (rs, i) -> new Enrollment((UUID) rs.getObject(1), (UUID) rs.getObject(2), rs.getInt(3), rs.getString(4), rs.getString(5)),
                userId, trainingId).stream().findFirst();
    }

    private Enrollment requireEnrollment(UUID userId, UUID trainingId) {
        return enrollment(userId, trainingId).orElseThrow(() ->
                new ApiException(HttpStatus.CONFLICT, "not_enrolled", "Önce eğitime başlamanız gerekiyor."));
    }

    private JsonNode item(UUID trainingId, Enrollment e, String key) {
        JsonNode body = published.pinned(trainingId, e.versionNo()).orElseThrow(() -> ApiException.notFound("Eğitim sürümü")).body();
        JsonNode item = TrainingSchema.findItem(body, key);
        if (item == null) throw ApiException.notFound("Eğitim ögesi '" + key + "'");
        return item;
    }

    private Set<String> completedKeys(UUID enrollmentId) {
        return new HashSet<>(jdbc.queryForList("SELECT item_key FROM item_progress WHERE enrollment_id = ? AND completed_at IS NOT NULL", String.class, enrollmentId));
    }

    private void markDone(Enrollment e, String key) {
        jdbc.update("""
                INSERT INTO item_progress(enrollment_id, item_key, completed_at) VALUES (?, ?, now())
                ON CONFLICT (enrollment_id, item_key) DO UPDATE SET completed_at = COALESCE(item_progress.completed_at, now())""", e.id(), key);
        jdbc.update("UPDATE enrollment SET updated_at = now() WHERE id = ?", e.id());
    }

    private void quizStats(UUID enrollmentId, String key, ObjectNode s) {
        Map<String, Object> st = jdbc.queryForMap("""
                SELECT count(*) AS used, COALESCE(bool_or(passed), false) AS passed, max(CASE WHEN max_score > 0 THEN score * 100 / max_score END) AS best
                FROM quiz_attempt WHERE enrollment_id = ? AND item_key = ? AND submitted_at IS NOT NULL""", enrollmentId, key);
        s.put("attemptsUsed", ((Number) st.get("used")).intValue());
        s.put("passed", (Boolean) st.get("passed"));
        if (st.get("best") != null) s.put("bestPercent", ((Number) st.get("best")).intValue());
    }

    /** Member-safe copy of an item: quiz answers and the question bank are never sent to the client. */
    private ObjectNode sanitize(JsonNode it, boolean full) {
        ObjectNode s = json.createObjectNode();
        String type = it.path("type").asText();
        s.put("key", it.path("key").asText());
        s.put("type", type);
        s.put("title", it.path("title").asText());
        if (!full) return s;
        switch (type) {
            case "lesson" -> {
                s.put("content", it.path("content").asText(""));
                if (it.has("video")) s.set("video", it.get("video"));
            }
            case "quiz" -> {
                for (String f : List.of("passingScore", "attemptsAllowed", "timeLimitSeconds", "questionsPerAttempt")) s.put(f, it.path(f).asInt());
                s.put("showCorrectAnswers", it.path("showCorrectAnswers").asBoolean(false));
            }
            case "survey" -> {
                s.put("anonymous", it.path("anonymous").asBoolean(false));
                ArrayNode qs = s.putArray("questions");
                for (JsonNode q : it.path("questions")) {
                    ObjectNode c = qs.addObject().put("id", q.path("id").asText()).put("type", q.path("type").asText()).put("text", q.path("text").asText());
                    ArrayNode oa = c.putArray("options");
                    q.path("options").forEach(o -> oa.addObject().put("id", o.path("id").asText()).put("text", o.path("text").asText()));
                }
            }
            case "tool" -> {
                s.put("toolId", it.path("toolId").asText());
                s.put("toolVersionNo", it.path("toolVersionNo").asInt());
            }
            default -> { }
        }
        return s;
    }

    private JsonNode read(Object jsonText) {
        try {
            return json.readTree(jsonText.toString());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static ActivityEvent event(UUID userId, String key, String action, String objType, String objId, Integer version, String source) {
        return ActivityEvent.web(userId, key, action, objType, objId, version).withSource(source == null ? "web" : source);
    }
}
