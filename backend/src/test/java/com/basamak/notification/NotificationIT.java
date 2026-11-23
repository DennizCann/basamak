package com.basamak.notification;

import com.basamak.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Scenario 14 (server side): a finished run creates exactly one notification with an in-app link. */
class NotificationIT extends IntegrationTest {
    @Autowired NotificationService notifications;

    @Test
    void finishedWorkflowRunNotifiesOnce_andLinksToTheRun() throws Exception {
        String m = newMember("notify");
        String wf = postJson("/api/workflows", Map.of("name", "Bildirim", "definition",
                Map.of("inputs", List.of(), "steps", List.of(Map.of("id", "a", "type", "ai", "prompt", "x")), "output", "")), m).get("id").asText();
        String run = postJson("/api/workflows/" + wf + "/runs", Map.of("input", Map.of()), m).get("id").asText();

        long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        JsonNode list;
        do {
            list = getJson("/api/me/notifications", m);
            if (list.get("unread").asInt() > 0) break;
            Thread.sleep(100);
        } while (System.nanoTime() < end);
        assertThat(list.get("unread").asInt()).isEqualTo(1);
        JsonNode n = list.get("items").get(0);
        assertThat(n.get("title").asText()).isEqualTo("İş akışı tamamlandı");
        assertThat(n.get("link").asText()).isEqualTo("/workflows/" + wf + "?run=" + run);

        // same event again (e.g. a retried job) -> no second notification
        UUID userId = UUID.fromString(getJson("/api/me", m).get("id").asText());
        notifications.notify(userId, "WORKFLOW_RUN", "tekrar", "", null, "workflow-run:" + run + ":0");
        assertThat(getJson("/api/me/notifications", m).get("items").size()).isEqualTo(1);

        postJson("/api/me/notifications/read-all", null, m);
        assertThat(getJson("/api/me/notifications", m).get("unread").asInt()).isZero();
    }

    @Test
    void channelPreferences_emailOn_inAppOff() throws Exception {
        String m = newMember("prefs");
        String email = getJson("/api/me", m).get("email").asText();
        JsonNode prefs = getJson("/api/me/notifications/prefs", m);
        assertThat(prefs.findValuesAsText("type")).contains("TOOL_RUN", "WORKFLOW_RUN", "CERTIFICATE");
        mvc.perform(jsonPut("/api/me/notifications/prefs", List.of(Map.of("type", "WORKFLOW_RUN", "inApp", false, "email", true, "push", false)))
                .header("Authorization", bearer(m))).andExpect(status().isOk());
        mvc.perform(jsonPut("/api/me/notifications/prefs", List.of(Map.of("type", "NOPE", "inApp", true, "email", true, "push", true)))
                .header("Authorization", bearer(m))).andExpect(status().isBadRequest());

        UUID userId = UUID.fromString(getJson("/api/me", m).get("id").asText());
        notifications.notify(userId, "WORKFLOW_RUN", "İş akışı tamamlandı", "Özet", "/workflows/x", "pref-test:" + userId);
        // stored (dedup, export) but hidden from the in-app centre; delivered by e-mail instead
        assertThat(getJson("/api/me/notifications", m).get("items").size()).isZero();
        verify(mail, timeout(2000)).send(eq(email), contains("İş akışı tamamlandı"), contains("/workflows/x"));

        // a type left on defaults still shows in-app and sends no e-mail
        clearInvocations(mail);
        notifications.notify(userId, "TOOL_RUN", "Araç tamamlandı", "", "/tools", "pref-test2:" + userId);
        JsonNode list = getJson("/api/me/notifications", m);
        assertThat(list.get("unread").asInt()).isEqualTo(1);
        verify(mail, never()).send(eq(email), anyString(), anyString());
        postJson("/api/me/notifications/" + list.get("items").get(0).get("id").asText() + "/read", null, m);
        assertThat(getJson("/api/me/notifications", m).get("unread").asInt()).isZero();
    }
}
