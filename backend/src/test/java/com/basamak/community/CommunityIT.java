package com.basamak.community;

import com.basamak.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Section 3.8 + badges (3.7): opt-in profiles, follow/feed, comments with masking, ratings, moderation and restriction. */
class CommunityIT extends IntegrationTest {

    String sharedWorkflow(String token) throws Exception {
        String wf = postJson("/api/workflows", Map.of("name", "Paylaşılan akış", "definition",
                Map.of("inputs", List.of(), "steps", List.of(Map.of("id", "a", "type", "ai", "prompt", "x")), "output", "")), token).get("id").asText();
        postJson("/api/workflows/" + wf + "/share", Map.of("shared", true), token);
        return wf;
    }

    @Test
    void profilesFollowFeedBadges() throws Exception {
        String a = newMember("profa"), b = newMember("profb");
        String aId = getJson("/api/me", a).get("id").asText();

        // private by default: others get 404, the owner sees it
        mvc.perform(get("/api/profiles/" + aId).header("Authorization", bearer(b))).andExpect(status().isNotFound());
        assertThat(getJson("/api/profiles/" + aId, a).get("self").asBoolean()).isTrue();
        mvc.perform(post("/api/profiles/" + aId + "/follow").header("Authorization", bearer(b))).andExpect(status().isNotFound());

        mvc.perform(jsonPut("/api/me/profile-settings", Map.of("isPublic", true, "showLevel", true, "showBadges", true, "showJourneys", false,
                "showShared", true, "bio", "Bana ali@ornek.com adresinden yaz")).header("Authorization", bearer(a)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bio").value("Bana [e-posta gizlendi] adresinden yaz"));

        String wf = sharedWorkflow(a); // WORKFLOW_CREATED + ITEM_SHARED -> badges FIRST_WORKFLOW, FIRST_SHARE
        JsonNode followed = postJson("/api/profiles/" + aId + "/follow", null, b);
        assertThat(followed.get("isFollowing").asBoolean()).isTrue();
        assertThat(followed.get("followers").asInt()).isEqualTo(1);
        assertThat(followed.has("journeys")).isFalse(); // hidden by the owner's choice
        assertThat(followed.get("badges").findValuesAsText("key")).contains("FIRST_WORKFLOW", "FIRST_SHARE");
        assertThat(followed.get("sharedWorkflows").get(0).get("id").asText()).isEqualTo(wf);
        assertThat(followed.at("/streak/current").asInt()).isEqualTo(1);

        JsonNode feed = getJson("/api/feed", b);
        assertThat(feed.findValuesAsText("type")).contains("ITEM_SHARED", "WORKFLOW_CREATED");
        assertThat(feed.get(0).get("userName").asText()).isNotBlank();

        // going private removes the member from followers' feeds
        mvc.perform(jsonPut("/api/me/profile-settings", Map.of("isPublic", false, "showLevel", true, "showBadges", true, "showJourneys", true, "showShared", true, "bio", ""))
                .header("Authorization", bearer(a))).andExpect(status().isOk());
        assertThat(getJson("/api/feed", b).size()).isZero();
    }

    @Test
    void commentsRatingsAndModeration() throws Exception {
        String owner = newMember("cmo"), troll = newMember("cmt"), other = newMember("cmx");
        String editor = cmsToken("editor@basamak.local");
        String wf = sharedWorkflow(owner);

        // personal data masked, profanity refused
        JsonNode c = postJson("/api/comments", Map.of("targetType", "WORKFLOW", "targetId", wf, "body", "Harika! Ara: 0532 123 45 67"), other);
        assertThat(c.get("body").asText()).isEqualTo("Harika! Ara: [telefon gizlendi]");
        mvc.perform(jsonPost("/api/comments", Map.of("targetType", "WORKFLOW", "targetId", wf, "body", "Bu ne SALAK bir akış")).header("Authorization", bearer(troll)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("profanity"));
        // reply notifies the parent author
        postJson("/api/comments", Map.of("targetType", "WORKFLOW", "targetId", wf, "parentId", c.get("id").asLong(), "body", "Teşekkürler"), owner);
        assertThat(getJson("/api/me/notifications", other).get("items").findValuesAsText("type")).contains("COMMENT");

        // rating: not your own, 1..5, average
        mvc.perform(jsonPut("/api/workflows/" + wf + "/rating", Map.of("stars", 5)).header("Authorization", bearer(owner))).andExpect(status().isBadRequest());
        mvc.perform(jsonPut("/api/workflows/" + wf + "/rating", Map.of("stars", 4)).header("Authorization", bearer(other))).andExpect(status().isOk());
        JsonNode r = body(mvc.perform(jsonPut("/api/workflows/" + wf + "/rating", Map.of("stars", 2)).header("Authorization", bearer(troll))).andReturn());
        assertThat(r.get("count").asInt()).isEqualTo(2);
        assertThat(r.get("average").asDouble()).isEqualTo(3.0);

        // three reported + hidden comments -> the author is restricted automatically
        for (int i = 0; i < 3; i++) {
            long id = postJson("/api/comments", Map.of("targetType", "WORKFLOW", "targetId", wf, "body", "spam " + i), troll).get("id").asLong();
            postJson("/api/comments/" + id + "/report", Map.of("reason", "Spam"), other);
            assertThat(getJson("/api/cms/moderation/reports", editor).findValuesAsText("commentId")).contains(String.valueOf(id));
            JsonNode h = postJson("/api/cms/moderation/comments/" + id + "/hide", Map.of("reason", "spam"), editor);
            assertThat(h.get("authorRestricted").asBoolean()).isEqualTo(i == 2);
        }
        mvc.perform(jsonPost("/api/comments", Map.of("targetType", "WORKFLOW", "targetId", wf, "body", "tekrar")).header("Authorization", bearer(troll)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("restricted"));
        JsonNode list = getJson("/api/comments?targetType=WORKFLOW&targetId=" + wf, other);
        assertThat(list.findValuesAsText("body")).contains("Bu yorum topluluk kuralları nedeniyle gizlendi.");
        assertThat(list.toString()).doesNotContain("spam 0");
        // members cannot moderate
        mvc.perform(get("/api/cms/moderation/reports").header("Authorization", bearer(other))).andExpect(status().isForbidden());
    }
}
