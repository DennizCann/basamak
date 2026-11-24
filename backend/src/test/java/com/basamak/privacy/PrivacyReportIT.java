package com.basamak.privacy;

import com.basamak.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Scenario 12 (history, export, deletion with anonymisation) and scenario 13 (CMS reports + CSV). */
class PrivacyReportIT extends IntegrationTest {
    @Autowired JdbcTemplate jdbc;

    // ------------------------------------------------------------------ scenario 12

    @Test
    void scenario12_historyFiltersAndPages() throws Exception {
        String admin = cmsToken("admin@basamak.local");
        String training = training(admin, "=Geçmiş testi"); // title starting with '=' (CSV test below uses it)
        String m = newMember("history");
        postJson("/api/trainings/" + training + "/enroll", null, m);
        postJson("/api/trainings/" + training + "/items/ders/view", null, m);
        postJson("/api/trainings/" + training + "/items/ders/complete", null, m);

        JsonNode all = getJson("/api/me/history", m);
        assertThat(all.get("total").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(all.toString()).contains("LESSON_COMPLETED").contains("Ders bir"); // item title resolved
        assertThat(all.get("items").get(0).has("points")).isTrue();
        assertThat(getJson("/api/me/history?category=tools", m).get("total").asInt()).isZero();
        assertThat(getJson("/api/me/history?category=learning", m).get("total").asInt()).isEqualTo(all.get("total").asInt());
        String tomorrow = LocalDate.now().plusDays(2).toString();
        assertThat(getJson("/api/me/history?from=" + tomorrow, m).get("total").asInt()).isZero();
        JsonNode page2 = getJson("/api/me/history?size=1&page=1", m);
        assertThat(page2.get("items").size()).isEqualTo(1);
        assertThat(page2.get("items").get(0).get("id")).isNotEqualTo(getJson("/api/me/history?size=1&page=0", m).get("items").get(0).get("id"));
        mvc.perform(get("/api/me/history?category=nope").header("Authorization", bearer(m))).andExpect(status().isBadRequest());
    }

    @Test
    void scenario12_exportContainsMyDataButNoSecrets() throws Exception {
        String m = newMember("export");
        MvcResult r = mvc.perform(get("/api/me/export").header("Authorization", bearer(m))).andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getHeader("Content-Disposition")).contains("attachment").contains(".json");
        JsonNode data = json.readTree(r.getResponse().getContentAsString());
        assertThat(data.at("/profile/email").asText()).startsWith("export-");
        assertThat(data.has("pointLedger")).isTrue();
        assertThat(data.toString()).doesNotContain("password_hash").doesNotContain("token_hash").doesNotContain("$2a$");
    }

    @Test
    void scenario12_deleteAnonymisesButKeepsStatistics() throws Exception {
        String admin = cmsToken("admin@basamak.local");
        String training = training(admin, "Silme testi");
        String email = uniqueEmail("gone");
        mvc.perform(jsonPost("/api/auth/register", Map.of("email", email, "password", PASSWORD, "displayName", "Silinecek Kişi", "consent", true)));
        mvc.perform(jsonPost("/api/auth/verify-email", Map.of("token", tokenFromMail(lastMailTo(email)))));
        String m = memberToken(email);
        UUID userId = UUID.fromString(getJson("/api/me", m).get("id").asText());
        postJson("/api/trainings/" + training + "/enroll", null, m);
        postJson("/api/trainings/" + training + "/items/ders/complete", null, m);
        postJson("/api/trainings/" + training + "/items/anket/survey", Map.of("answers", Map.of("puan", 4, "fikir", "Telefonum 0555 000 00 00")), m);
        int eventsBefore = jdbc.queryForObject("SELECT count(*) FROM activity_event WHERE user_id = ?", Integer.class, userId);
        JsonNode reportBefore = training(admin, training, true);

        mvc.perform(jsonPost("/api/me/delete", Map.of("password", "yanlis-sifre", "confirmation", "SİL")).header("Authorization", bearer(m))).andExpect(status().isForbidden());
        mvc.perform(jsonPost("/api/me/delete", Map.of("password", PASSWORD, "confirmation", "")).header("Authorization", bearer(m))).andExpect(status().isBadRequest());
        postJson("/api/me/delete", Map.of("password", PASSWORD, "confirmation", "SİL"), m);

        Map<String, Object> u = jdbc.queryForMap("SELECT email, display_name, status, password_hash FROM users WHERE id = ?", userId);
        assertThat(u.get("email").toString()).isEqualTo("deleted+" + userId + "@deleted.invalid");
        assertThat(u.get("display_name")).isEqualTo("Silinmiş üye");
        assertThat(u.get("status")).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_sessions WHERE user_id = ?", Integer.class, userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM survey_response WHERE user_id = ?", Integer.class, userId)).isZero();
        // the append-only log is kept (pseudonymous) -> statistics unchanged
        assertThat(jdbc.queryForObject("SELECT count(*) FROM activity_event WHERE user_id = ?", Integer.class, userId)).isEqualTo(eventsBefore);
        JsonNode reportAfter = training(admin, training, true);
        assertThat(reportAfter.get("enrolled").asInt()).isEqualTo(reportBefore.get("enrolled").asInt());
        // the old e-mail can no longer sign in
        MvcResult login = mvc.perform(jsonPost("/api/auth/login", Map.of("email", email, "password", PASSWORD, "client", "web"))).andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(401);
        // a confirmation mail went to the address before it was erased
        assertThat(lastMailTo(email)).contains("silindi");
    }

    // ------------------------------------------------------------------ scenario 13


    @Test
    void scenario13_reportsAndCsv() throws Exception {
        String admin = cmsToken("admin@basamak.local");
        String training = training(admin, "=Rapor testi");
        for (int i = 0; i < 2; i++) {
            String m = newMember("report" + i);
            postJson("/api/trainings/" + training + "/enroll", null, m);
            JsonNode att = postJson("/api/trainings/" + training + "/items/sinav/attempts", null, m);
            Map<String, Object> answers = new HashMap<>(Map.of("q1", List.of("a"), "q2", List.of(i == 0 ? "a" : "b")));
            postJson("/api/quiz-attempts/" + att.get("attemptId").asText() + "/submit", Map.of("answers", answers), m);
            postJson("/api/trainings/" + training + "/items/anket/survey", Map.of("answers", Map.of("puan", i == 0 ? 5 : 3, "fikir", "=1+1")), m);
        }
        JsonNode detail = getJson("/api/cms/reports/trainings/" + training, admin);
        JsonNode quiz = detail.get("quizzes").get(0);
        assertThat(quiz.get("attempts").asInt()).isEqualTo(2);
        Map<String, Double> rates = new HashMap<>();
        quiz.get("questions").forEach(q -> rates.put(q.get("questionId").asText(), q.get("correctRate").asDouble()));
        assertThat(rates).containsEntry("q1", 100.0).containsEntry("q2", 50.0);
        JsonNode survey = detail.get("surveys").get(0);
        assertThat(survey.get("responses").asInt()).isEqualTo(2);
        JsonNode scale = survey.get("questions").get(0).get("distribution");
        Map<String, Integer> counts = new HashMap<>();
        scale.forEach(d -> counts.put(d.get("value").asText(), d.get("count").asInt()));
        assertThat(counts).containsEntry("5", 1).containsEntry("3", 1).containsEntry("1", 0);
        assertThat(training(admin, training, true).get("enrolled").asInt()).isEqualTo(2);

        MvcResult csv = mvc.perform(get("/api/cms/reports/trainings.csv").header("Authorization", bearer(admin))).andExpect(status().isOk()).andReturn();
        String text = new String(csv.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿\"Eğitim\";").contains("\"'=Rapor testi ").doesNotContain("\"=Rapor");
        String q = new String(mvc.perform(get("/api/cms/reports/trainings/" + training + "/questions.csv").header("Authorization", bearer(admin)))
                .andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(q).contains("\"50,0\"");
        mvc.perform(get("/api/cms/reports/trainings").header("Authorization", bearer(cmsToken("author@basamak.local")))).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode training(String admin, String trainingId, boolean report) throws Exception {
        for (JsonNode t : getJson("/api/cms/reports/trainings", admin)) if (t.get("trainingId").asText().equals(trainingId)) return t;
        throw new AssertionError("training not in report");
    }

    /** lesson + 2-question quiz + survey (scale + open) */
    private String training(String admin, String title) throws Exception {
        return training(admin, title, false, false);
    }

    @Test
    void section312_dropoutVersionsJourneysUsageAndAnonymityThreshold() throws Exception {
        String admin = cmsToken("admin@basamak.local");
        String training = training(admin, "Anonim rapor", true, true);
        for (int i = 0; i < 3; i++) {
            String m = newMember("anon" + i);
            postJson("/api/trainings/" + training + "/enroll", null, m);
            postJson("/api/trainings/" + training + "/items/anket/survey", Map.of("answers", Map.of("puan", 4, "fikir", "iyi")), m);
        }
        // one member stopped 20 days ago on the lesson -> dropped, and that lesson is the most dropped item
        jdbc.update("UPDATE enrollment SET updated_at = now() - interval '20 days', current_item_key = 'ders' WHERE training_id = ?::uuid AND user_id = "
                + "(SELECT user_id FROM enrollment WHERE training_id = ?::uuid ORDER BY started_at LIMIT 1)", training, training);
        JsonNode row = training(admin, training, true);
        assertThat(row.get("dropped").asInt()).isEqualTo(1);
        assertThat(row.at("/mostDroppedItem/title").asText()).isEqualTo("Ders bir");
        assertThat(row.get("versions").get(0).get("enrolled").asInt()).isEqualTo(3);

        // anonymous survey with 3 (< 5) answers: distribution hidden, open answers too
        JsonNode q = getJson("/api/cms/reports/trainings/" + training, admin).get("surveys").get(0).get("questions").get(0);
        assertThat(q.get("hidden").asBoolean()).isTrue();
        assertThat(q.has("distribution")).isFalse();

        JsonNode journeys = getJson("/api/cms/reports/journeys", admin);
        assertThat(journeys.findValuesAsText("title")).contains("Beginner");
        JsonNode usage = getJson("/api/cms/reports/usage", admin);
        assertThat(usage.has("tools")).isTrue();
        assertThat(usage.has("workflows")).isTrue();
        for (String csv : List.of("journeys.csv", "usage.csv", "versions.csv")) {
            mvc.perform(get("/api/cms/reports/" + csv).header("Authorization", bearer(admin))).andExpect(status().isOk());
        }
    }

    private String training(String admin, String title, boolean anonymousSurvey, boolean unused) throws Exception {
        Map<String, Object> quiz = new LinkedHashMap<>();
        quiz.put("key", "sinav"); quiz.put("type", "quiz"); quiz.put("title", "Sınav"); quiz.put("passingScore", 50); quiz.put("attemptsAllowed", 3);
        quiz.put("questionsPerAttempt", 2); quiz.put("showCorrectAnswers", false);
        quiz.put("questions", List.of(
                Map.of("id", "q1", "type", "single", "text", "Bir", "points", 1, "options", List.of(Map.of("id", "a", "text", "A", "correct", true), Map.of("id", "b", "text", "B"))),
                Map.of("id", "q2", "type", "single", "text", "İki", "points", 1, "options", List.of(Map.of("id", "a", "text", "A", "correct", true), Map.of("id", "b", "text", "B")))));
        Map<String, Object> survey = Map.of("key", "anket", "type", "survey", "title", "Anket", "anonymous", anonymousSurvey, "questions", List.of(
                Map.of("id", "puan", "type", "scale", "text", "Puanın?"), Map.of("id", "fikir", "type", "open", "text", "Fikrin?"),
                Map.of("id", "bos", "type", "single", "text", "Kimse cevaplamadı", "options", List.of(Map.of("id", "x", "text", "X"), Map.of("id", "y", "text", "Y")))));
        Map<String, Object> body = Map.of("summary", "Rapor", "items", List.of(
                Map.of("key", "ders", "type", "lesson", "title", "Ders bir", "content", "Metin"), quiz, survey));
        MvcResult r = mvc.perform(jsonPost("/api/cms/items", Map.of("type", "TRAINING", "title", title + " " + UUID.randomUUID().toString().substring(0, 5), "body", body))
                .header("Authorization", bearer(admin))).andReturn();
        JsonNode created = body(r);
        for (String step : List.of("submit", "approve", "publish")) {
            mvc.perform(post("/api/cms/versions/" + created.at("/latest/id").asText() + "/" + step).header("Authorization", bearer(admin))).andExpect(status().isOk());
        }
        return created.get("id").asText();
    }
}
