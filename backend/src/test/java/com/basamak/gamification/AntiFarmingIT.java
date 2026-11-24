package com.basamak.gamification;

import com.basamak.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import com.basamak.learning.LearningService;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Section 3.7 abuse rules: open-and-close lessons, empty/copied tools, repeated and mutual forks do not inflate points. */
// shares the cached test context (a second context would run its own job workers on the same database)
class AntiFarmingIT extends IntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired LearningService learning;

    int points(String token, String action) throws Exception {
        UUID id = UUID.fromString(getJson("/api/me", token).get("id").asText());
        Integer n = jdbc.queryForObject("SELECT coalesce(sum(points), 0) FROM point_ledger WHERE user_id = ? AND action = ?", Integer.class, id, action);
        return n == null ? 0 : n;
    }

    @Test
    void lessonCannotBeCompletedRightAfterOpening() throws Exception {
        ReflectionTestUtils.setField(learning, "minLessonSeconds", 2);
        try {
        String m = newMember("dwell");
        JsonNode beginner = getJson("/api/me/journeys", m).get(0);
        String intro = null;
        for (JsonNode t : beginner.get("trainings")) if (t.get("title").asText().equals("Basamak'a Giriş")) intro = t.get("id").asText();
        postJson("/api/trainings/" + intro + "/enroll", null, m);
        postJson("/api/trainings/" + intro + "/items/hos-geldin/view", null, m);
        mvc.perform(post("/api/trainings/" + intro + "/items/hos-geldin/complete").header("Authorization", bearer(m)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("too_fast"));
        assertThat(points(m, "LESSON_COMPLETED")).isZero();
        Thread.sleep(2100);
        postJson("/api/trainings/" + intro + "/items/hos-geldin/complete", null, m);
        assertThat(points(m, "LESSON_COMPLETED")).isPositive();
        } finally {
            ReflectionTestUtils.setField(learning, "minLessonSeconds", 0);
        }
    }

    @Test
    void copiedOrEmptyToolsEarnNoCreationPoints() throws Exception {
        String a = newMember("toola"), b = newMember("toolb");
        Map<String, Object> def = Map.of("inputs", List.of(Map.of("name", "q", "label", "Soru", "type", "string", "required", true)),
                "request", Map.of("method", "GET", "url", "https://example.org/search?q={{input.q}}"));
        postJson("/api/my-tools", Map.of("name", "Arama", "definition", def), a);
        assertThat(points(a, "TOOL_CREATED")).isPositive();
        postJson("/api/my-tools", Map.of("name", "Benim aramam", "definition", def), b); // same definition, other member
        assertThat(points(b, "TOOL_CREATED")).isZero();
        String c = newMember("toolc");
        postJson("/api/my-tools", Map.of("name", "Boş", "definition", Map.of("inputs", List.of(), "request", Map.of("method", "GET", "url", "https://example.org/ping"))), c);
        assertThat(points(c, "TOOL_CREATED")).isZero();
    }

    @Test
    void repeatedAndMutualForksDoNotInflate() throws Exception {
        String a = newMember("forka"), b = newMember("forkb");
        String wfA = shared(a), wfB = shared(b);
        postJson("/api/workflows/" + wfA + "/fork", null, b);
        postJson("/api/workflows/" + wfA + "/fork", null, b); // again
        int aPoints = points(a, "SHARED_ITEM_FORKED");
        assertThat(aPoints).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM point_ledger WHERE action = 'SHARED_ITEM_FORKED' AND object_id = ?", Integer.class, wfA)).isEqualTo(1);
        postJson("/api/workflows/" + wfB + "/fork", null, a); // a forks back: mutual pair
        assertThat(points(b, "SHARED_ITEM_FORKED")).isZero();
    }

    String shared(String token) throws Exception {
        String wf = postJson("/api/workflows", Map.of("name", "Akış", "definition",
                Map.of("inputs", List.of(), "steps", List.of(Map.of("id", "a", "type", "ai", "prompt", "x")), "output", "")), token).get("id").asText();
        postJson("/api/workflows/" + wf + "/share", Map.of("shared", true), token);
        return wf;
    }
}
