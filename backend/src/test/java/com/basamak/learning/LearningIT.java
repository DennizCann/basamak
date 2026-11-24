package com.basamak.learning;

import com.basamak.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Scenario 5 (member path to certificate) and scenario 3 (new version while a member is mid-training). */
class LearningIT extends IntegrationTest {
    @Autowired JdbcTemplate jdbc;

    /** Known answers of the seeded intro quiz (question id -> correct option ids). */
    static final Map<String, List<String>> ANSWERS = Map.of(
            "q1", List.of("a"), "q2", List.of("a"), "q3", List.of("a", "b"), "q4", List.of("a"), "q5", List.of("a"));

    @Test
    void scenario5_memberCompletesJourneyAndGetsVerifiableCertificate() throws Exception {
        String m = newMember("s5");
        JsonNode journeys = getJson("/api/me/journeys", m);
        JsonNode beginner = journeys.get(0);
        assertThat(beginner.get("title").asText()).isEqualTo("Beginner");
        assertThat(beginner.get("locked").asBoolean()).isFalse();
        assertThat(journeys.get(1).get("locked").asBoolean()).isTrue(); // Expert waits for Beginner
        String intro = trainingId(beginner, "Basamak'a Giriş");
        String second = trainingId(beginner, "İlk Aracını Tanı");

        postJson("/api/trainings/" + intro + "/enroll", null, m);
        postJson("/api/trainings/" + intro + "/items/hos-geldin/complete", null, m);
        postJson("/api/trainings/" + intro + "/items/puanlar/complete", null, m);

        // quiz: 3 random questions from a bank of 5; answers are never sent to the client
        JsonNode attempt1 = postJson("/api/trainings/" + intro + "/items/giris-quiz/attempts", null, m);
        assertThat(attempt1.get("questions")).hasSize(3);
        assertThat(attempt1.toString()).doesNotContain("\"correct\"");
        JsonNode failed = postJson("/api/quiz-attempts/" + attempt1.get("attemptId").asText() + "/submit", Map.of("answers", Map.of()), m);
        assertThat(failed.get("passed").asBoolean()).isFalse();
        assertThat(failed.get("attemptsLeft").asInt()).isEqualTo(2);

        JsonNode attempt2 = postJson("/api/trainings/" + intro + "/items/giris-quiz/attempts", null, m);
        JsonNode passed = postJson("/api/quiz-attempts/" + attempt2.get("attemptId").asText() + "/submit", Map.of("answers", correctAnswers(attempt2)), m);
        assertThat(passed.get("passed").asBoolean()).isTrue();
        assertThat(passed.get("percent").asInt()).isEqualTo(100);
        assertThat(passed.get("results").get(0).has("correctOptionIds")).isTrue(); // quiz allows showing answers

        JsonNode done1 = postJson("/api/trainings/" + intro + "/items/giris-anket/survey",
                Map.of("answers", Map.of("s1", 5, "s2", "a", "s3", "Güzel")), m);
        assertThat(done1.get("trainingCompleted").asBoolean()).isTrue();

        postJson("/api/trainings/" + second + "/enroll", null, m);
        postJson("/api/trainings/" + second + "/items/arac-nedir/complete", null, m);
        JsonNode done2 = postJson("/api/trainings/" + second + "/items/arac-anket/survey", Map.of("answers", Map.of("s1", 4)), m);
        assertThat(done2.get("trainingCompleted").asBoolean()).isTrue();
        assertThat(done2.get("certificatesIssued")).hasSize(1);
        String serial = done2.get("certificatesIssued").get(0).asText();

        // points: start 2x2 + lessons 3x5 + quiz 20 + surveys 2x10 + trainings 2x50 + journey 200
        JsonNode summary = getJson("/api/me/summary", m);
        assertThat(summary.get("totalPoints").asInt()).isEqualTo(4 + 15 + 20 + 20 + 100 + 200);
        assertThat(summary.get("level").asText()).isEqualTo("Expert");
        JsonNode after = getJson("/api/me/journeys", m);
        assertThat(after.get(0).get("completed").asBoolean()).isTrue();
        assertThat(after.get(1).get("locked").asBoolean()).isFalse();

        // certificate: PDF + public verification; forged serial is invalid
        byte[] pdf = mvc.perform(get("/api/me/certificates/" + serial + "/pdf").header("Authorization", bearer(m)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        mvc.perform(get("/api/public/certificates/" + serial))
           .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true)).andExpect(jsonPath("$.journeyTitle").value("Beginner"));
        mvc.perform(get("/api/public/certificates/BSM-FAKE000000")).andExpect(jsonPath("$.valid").value(false));
        // another member cannot download it
        String other = newMember("s5-other");
        mvc.perform(get("/api/me/certificates/" + serial + "/pdf").header("Authorization", bearer(other))).andExpect(status().isNotFound());

        // the anonymous survey answer is stored without the member's id
        Integer withUser = jdbc.queryForObject("SELECT count(*) FROM survey_response WHERE training_id = ?::uuid AND user_id IS NOT NULL", Integer.class, second);
        assertThat(withUser).isZero();
    }

    @Test
    void quizAttemptLimitAndRandomOrder() throws Exception {
        String m = newMember("quiz");
        String intro = trainingId(getJson("/api/me/journeys", m).get(0), "Basamak'a Giriş");
        postJson("/api/trainings/" + intro + "/enroll", null, m);
        Set<String> orders = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            JsonNode a = postJson("/api/trainings/" + intro + "/items/giris-quiz/attempts", null, m);
            StringBuilder sb = new StringBuilder();
            a.get("questions").forEach(q -> { sb.append(q.get("id").asText()); q.get("options").forEach(o -> sb.append(o.get("id").asText())); });
            orders.add(sb.toString());
            postJson("/api/quiz-attempts/" + a.get("attemptId").asText() + "/submit", Map.of("answers", Map.of()), m);
        }
        assertThat(orders).hasSizeGreaterThan(1);
        mvc.perform(post("/api/trainings/" + intro + "/items/giris-quiz/attempts").header("Authorization", bearer(m)))
           .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("no_attempts_left"));
    }

    @Test
    void lockedJourneyTrainingCannotBeStarted() throws Exception {
        String admin = cmsToken("admin@basamak.local");
        String m = newMember("lock");
        // add a training to the (locked) Expert journey
        String trainingId = publishTraining(admin, "Expert Eğitimi " + System.nanoTime(), lessons("Expert dersi", "a"));
        JsonNode expert = getJson("/api/me/journeys", m).get(1);
        JsonNode cmsItem = getJson("/api/cms/items/" + expert.get("id").asText(), admin);
        Map<String, Object> body = json.convertValue(cmsItem.at("/latest/body"), Map.class);
        List<String> ids = new ArrayList<>((List<String>) body.get("trainingIds"));
        ids.add(trainingId);
        body.put("trainingIds", ids);
        JsonNode saved = body(mvc.perform(jsonPut("/api/cms/items/" + expert.get("id").asText() + "/draft",
                Map.of("lockVersion", cmsItem.get("lockVersion").asLong(), "title", "Expert", "body", body)).header("Authorization", bearer(admin))).andReturn());
        publishVersion(admin, saved.at("/latest/id").asText());

        mvc.perform(post("/api/trainings/" + trainingId + "/enroll").header("Authorization", bearer(m)))
           .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("training_locked"));
    }

    @Test
    void scenario3_newVersionWhileMemberIsMidTraining() throws Exception {
        String admin = cmsToken("admin@basamak.local");
        String id = publishTraining(admin, "Sürüm Kuralı " + System.nanoTime(), lessons("v1 metni", "a", "b"));
        String early = newMember("s3-early");   // will finish on v1
        String mover = newMember("s3-mover");   // will switch to v2
        for (String m : List.of(early, mover)) {
            postJson("/api/trainings/" + id + "/enroll", null, m);
            postJson("/api/trainings/" + id + "/items/a/complete", null, m);
        }

        // v2: lesson a reworded, lesson b removed, lesson c added
        JsonNode item = getJson("/api/cms/items/" + id, admin);
        JsonNode saved = body(mvc.perform(jsonPut("/api/cms/items/" + id + "/draft",
                Map.of("lockVersion", item.get("lockVersion").asLong(), "title", item.at("/latest/title").asText(), "body", lessons("v2 metni", "a", "c"),
                        "confirmRemovedKeys", List.of("b"))) // removing a published item must be confirmed explicitly
                .header("Authorization", bearer(admin))).andReturn());
        publishVersion(admin, saved.at("/latest/id").asText());

        // rule: in-progress members stay on their version and are told a newer one exists
        JsonNode view = getJson("/api/trainings/" + id, early);
        assertThat(view.get("versionNo").asInt()).isEqualTo(1);
        assertThat(view.get("newerVersionAvailable").asBoolean()).isTrue();
        assertThat(view.get("items").get(0).get("content").asText()).isEqualTo("v1 metni a");
        // ...and can finish it on v1
        JsonNode fin = postJson("/api/trainings/" + id + "/items/b/complete", null, early);
        assertThat(fin.get("trainingCompleted").asBoolean()).isTrue();

        // opt-in switch carries over progress for keys that still exist (a), drops removed ones
        JsonNode switched = postJson("/api/trainings/" + id + "/switch-version", null, mover);
        assertThat(switched.get("versionNo").asInt()).isEqualTo(2);
        assertThat(switched.get("items").get(0).get("completed").asBoolean()).isTrue();  // a
        assertThat(switched.get("items").get(1).get("completed").asBoolean()).isFalse(); // c
        assertThat(switched.at("/enrollment/completedCount").asInt()).isEqualTo(1);

        // new members start on the latest version
        String late = newMember("s3-late");
        JsonNode lateView = postJson("/api/trainings/" + id + "/enroll", null, late);
        assertThat(lateView.get("versionNo").asInt()).isEqualTo(2);
        assertThat(lateView.get("items").get(0).get("content").asText()).isEqualTo("v2 metni a");
    }

    // ---------------- helpers ----------------

    @Test
    void timedQuiz_memberCanAskForMoreTimeOnce() throws Exception {
        String m = newMember("extend");
        String intro = trainingId(getJson("/api/me/journeys", m).get(0), "Basamak'a Giriş");
        postJson("/api/trainings/" + intro + "/enroll", null, m);
        JsonNode a = postJson("/api/trainings/" + intro + "/items/giris-quiz/attempts", null, m);
        java.time.Instant before = java.time.Instant.parse(a.get("deadlineAt").asText());
        JsonNode ext = postJson("/api/quiz-attempts/" + a.get("attemptId").asText() + "/extend", null, m);
        assertThat(java.time.Duration.between(before, java.time.Instant.parse(ext.get("deadlineAt").asText())).toSeconds()).isEqualTo(300); // 600 s limit -> +50 %
        assertThat(ext.get("extended").asBoolean()).isTrue();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/quiz-attempts/" + a.get("attemptId").asText() + "/extend")
                .header("Authorization", bearer(m))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
    }

    private static String trainingId(JsonNode journey, String title) {
        for (JsonNode t : journey.get("trainings")) if (t.get("title").asText().equals(title)) return t.get("id").asText();
        throw new AssertionError("training not found: " + title);
    }

    private Map<String, Object> correctAnswers(JsonNode attempt) {
        Map<String, Object> a = new HashMap<>();
        attempt.get("questions").forEach(q -> a.put(q.get("id").asText(), ANSWERS.get(q.get("id").asText())));
        return a;
    }

    static Map<String, Object> lessons(String text, String... keys) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (String k : keys) items.add(Map.of("key", k, "type", "lesson", "title", "Ders " + k, "content", text + " " + k));
        return Map.of("summary", "test", "prerequisites", List.of(), "items", items);
    }

    private String publishTraining(String admin, String title, Map<String, Object> body) throws Exception {
        JsonNode created = body(mvc.perform(jsonPost("/api/cms/items", Map.of("type", "TRAINING", "title", title, "body", body))
                .header("Authorization", bearer(admin))).andExpect(status().isCreated()).andReturn());
        publishVersion(admin, created.at("/latest/id").asText());
        return created.get("id").asText();
    }

    /** Admin may approve own drafts (four-eyes exception), which keeps these tests short. */
    private void publishVersion(String admin, String versionId) throws Exception {
        for (String step : List.of("submit", "approve", "publish")) {
            mvc.perform(post("/api/cms/versions/" + versionId + "/" + step).header("Authorization", bearer(admin))).andExpect(status().isOk());
        }
    }
}
