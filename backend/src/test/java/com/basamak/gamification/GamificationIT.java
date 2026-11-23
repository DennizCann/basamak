package com.basamak.gamification;

import com.basamak.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Scenario 10: different actions give different points, rules change from the CMS, duplicates count once. */
class GamificationIT extends IntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired GamificationService gamification;

    @Test
    void openingTheSameLessonHundredTimesGivesPointsOnce() throws Exception {
        String m = newMember("view100");
        String intro = introId(m);
        postJson("/api/trainings/" + intro + "/enroll", null, m);
        for (int i = 0; i < 100; i++) postJson("/api/trainings/" + intro + "/items/hos-geldin/view", null, m);

        UUID user = userId(m);
        assertThat(ledgerRows(user, "LESSON_VIEWED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM activity_event WHERE user_id = ? AND type = 'LESSON_VIEWED'", Integer.class, user)).isEqualTo(100);
    }

    @Test
    void sameEventTwiceIsRecordedOnceEvenConcurrently() throws Exception {
        String m = newMember("dup");
        UUID user = userId(m);
        ActivityEvent e = ActivityEvent.web(user, "dup-test-" + UUID.randomUUID(), "TOOL_CREATED", "TOOL", UUID.randomUUID().toString(), 1);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<GamificationService.Award>> fs = new ArrayList<>();
        for (int i = 0; i < 4; i++) fs.add(pool.submit(() -> { go.await(); return gamification.record(e); }));
        go.countDown();
        int recorded = 0, points = 0;
        for (var f : fs) { var a = f.get(); if (a.eventRecorded()) recorded++; points += a.points(); }
        pool.shutdown();

        assertThat(recorded).isEqualTo(1);
        assertThat(points).isEqualTo(30);
        assertThat(gamification.total(user)).isEqualTo(30);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM activity_event WHERE event_key = ?", Integer.class, e.eventKey())).isEqualTo(1);

        // same via HTTP with an Idempotency-Key (e.g. mobile sync re-sending)
        String intro = introId(m);
        postJson("/api/trainings/" + intro + "/enroll", null, m);
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/trainings/" + intro + "/items/puanlar/view").header("Authorization", bearer(m)).header("Idempotency-Key", "offline-123"))
               .andExpect(status().isOk());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM activity_event WHERE user_id = ? AND type = 'LESSON_VIEWED'", Integer.class, user)).isEqualTo(1);
    }

    @Test
    void differentActionsGiveDifferentPoints() throws Exception {
        String m = newMember("diff");
        String intro = introId(m);
        postJson("/api/trainings/" + intro + "/enroll", null, m);                  // start
        postJson("/api/trainings/" + intro + "/items/hos-geldin/view", null, m);   // view
        postJson("/api/trainings/" + intro + "/items/hos-geldin/complete", null, m); // complete
        Map<String, Integer> byAction = new HashMap<>();
        jdbc.query("SELECT action, points FROM point_ledger WHERE user_id = ?", rs -> { byAction.put(rs.getString(1), rs.getInt(2)); }, userId(m));
        assertThat(byAction).containsEntry("TRAINING_STARTED", 2).containsEntry("LESSON_VIEWED", 1).containsEntry("LESSON_COMPLETED", 5);
    }

    @Test
    void ruleChangedInCmsAppliesWithoutCodeChangeAndLedgerKeepsRuleVersion() throws Exception {
        String admin = cmsToken("admin@basamak.local");
        // the published "Ders tamamlama" rule
        JsonNode rules = getJson("/api/cms/items?type=POINT_RULE", admin);
        JsonNode rule = null;
        for (JsonNode r : rules) if ("LESSON_COMPLETED".equals(r.at("/latest/body/action").asText())) rule = r;
        String ruleId = rule.get("id").asText();

        String before = newMember("rule-before");
        String intro = introId(before);
        postJson("/api/trainings/" + intro + "/enroll", null, before);
        postJson("/api/trainings/" + intro + "/items/hos-geldin/complete", null, before);

        // admin changes the rule to 8 points -> new version through the normal workflow
        JsonNode saved = body(mvc.perform(jsonPut("/api/cms/items/" + ruleId + "/draft", Map.of("lockVersion", rule.get("lockVersion").asLong(),
                "title", rule.at("/latest/title").asText(),
                "body", Map.of("action", "LESSON_COMPLETED", "objectType", "LESSON", "points", 8, "policy", "ONCE_PER_OBJECT")))
                .header("Authorization", bearer(admin))).andExpect(status().isOk()).andReturn());
        int newVersion = saved.at("/latest/versionNo").asInt();
        for (String step : List.of("submit", "approve", "publish")) {
            mvc.perform(post("/api/cms/versions/" + saved.at("/latest/id").asText() + "/" + step).header("Authorization", bearer(admin))).andExpect(status().isOk());
        }
        try {
            String after = newMember("rule-after");
            postJson("/api/trainings/" + intro + "/enroll", null, after);
            postJson("/api/trainings/" + intro + "/items/hos-geldin/complete", null, after);

            Map<String, Object> oldRow = jdbc.queryForMap("SELECT points, rule_version_no FROM point_ledger WHERE user_id = ? AND action = 'LESSON_COMPLETED'", userId(before));
            Map<String, Object> newRow = jdbc.queryForMap("SELECT points, rule_version_no FROM point_ledger WHERE user_id = ? AND action = 'LESSON_COMPLETED'", userId(after));
            assertThat(oldRow.get("points")).isEqualTo(5);               // history is not recalculated
            assertThat(newRow.get("points")).isEqualTo(8);
            assertThat(newRow.get("rule_version_no")).isEqualTo(newVersion);
        } finally {
            // roll back to the original rule version so other tests see the seed value
            JsonNode history = getJson("/api/cms/items/" + ruleId + "/versions", admin);
            String v1 = history.get(history.size() - 1).get("id").asText();
            mvc.perform(post("/api/cms/versions/" + v1 + "/publish").header("Authorization", bearer(admin))).andExpect(status().isOk());
        }
    }

    @Test
    void ledgerIsAppendOnlyAndCorrectionsAreReversingEntries() throws Exception {
        String m = newMember("rev");
        UUID user = userId(m);
        gamification.record(ActivityEvent.web(user, "rev-" + UUID.randomUUID(), "TOOL_CREATED", "TOOL", UUID.randomUUID().toString(), 1));
        long id = jdbc.queryForObject("SELECT id FROM point_ledger WHERE user_id = ?", Long.class, user);
        assertThatThrownBy(() -> jdbc.update("UPDATE point_ledger SET points = 999 WHERE id = ?", id)).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM point_ledger WHERE id = ?", id)).hasMessageContaining("append-only");

        assertThat(gamification.reverse(id, "silinen araç")).isTrue();
        assertThat(gamification.reverse(id, "tekrar")).isFalse(); // idempotent
        assertThat(gamification.total(user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM point_ledger WHERE user_id = ?", Integer.class, user)).isEqualTo(2);
    }

    private String introId(String token) throws Exception {
        for (JsonNode t : getJson("/api/me/journeys", token).get(0).get("trainings")) {
            if (t.get("title").asText().equals("Basamak'a Giriş")) return t.get("id").asText();
        }
        throw new AssertionError("intro training missing");
    }

    private UUID userId(String token) throws Exception {
        return UUID.fromString(getJson("/api/me", token).get("id").asText());
    }

    private int ledgerRows(UUID user, String action) {
        return jdbc.queryForObject("SELECT count(*) FROM point_ledger WHERE user_id = ? AND action = ?", Integer.class, user, action);
    }
}
