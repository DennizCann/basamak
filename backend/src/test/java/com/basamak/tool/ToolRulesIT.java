package com.basamak.tool;

import com.basamak.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Section 3.5: daily run quota by level, breaking-change flag on a new tool version. */
class ToolRulesIT extends IntegrationTest {
    @Autowired ToolRunService runs;

    static Map<String, Object> def(boolean extraRequired, String outputPath) {
        var inputs = new java.util.ArrayList<Object>(List.of(Map.of("name", "q", "label", "Soru", "type", "string", "required", true)));
        if (extraRequired) inputs.add(Map.of("name", "lang", "label", "Dil", "type", "string", "required", true));
        return Map.of("inputs", inputs, "request", Map.of("method", "GET", "url", "https://example.org/s?q={{input.q}}"), "outputPath", outputPath);
    }

    @Test
    void dailyQuotaDependsOnLevel_andIsEnforced() throws Exception {
        String m = newMember("quota");
        JsonNode q = getJson("/api/tool-runs/quota", m);
        assertThat(q.get("limit").asInt()).isEqualTo(20); // Beginner: 1 x 20
        assertThat(q.get("used").asInt()).isZero();
        String tool = postJson("/api/my-tools", Map.of("name", "Kota", "definition", def(false, "")), m).get("id").asText();
        Object original = ReflectionTestUtils.getField(runs, "dailyRunsPerLevel");
        try {
            ReflectionTestUtils.setField(runs, "dailyRunsPerLevel", 1);
            postJson("/api/tool-runs", Map.of("userToolId", tool, "input", Map.of("q", "a")), m);
            mvc.perform(jsonPost("/api/tool-runs", Map.of("userToolId", tool, "input", Map.of("q", "b"))).header("Authorization", bearer(m)))
                    .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("daily_quota"));
        } finally {
            ReflectionTestUtils.setField(runs, "dailyRunsPerLevel", original);
        }
    }

    @Test
    void newVersionThatBreaksTheSchemaIsFlagged() throws Exception {
        String m = newMember("compat");
        String tool = postJson("/api/my-tools", Map.of("name", "Sürüm", "definition", def(false, "")), m).get("id").asText();
        JsonNode same = body(mvc.perform(jsonPut("/api/my-tools/" + tool, Map.of("name", "Sürüm 2", "definition", def(false, ""))).header("Authorization", bearer(m))).andReturn());
        assertThat(same.get("breakingChanges").size()).isZero();
        JsonNode broken = body(mvc.perform(jsonPut("/api/my-tools/" + tool, Map.of("name", "Sürüm 3", "definition", def(true, "data"))).header("Authorization", bearer(m))).andReturn());
        assertThat(broken.get("breakingChanges").toString()).contains("Yeni zorunlu girdi").contains("Çıktı seçimi değişti");
    }
}
