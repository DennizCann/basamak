package com.basamak.learning;

import com.basamak.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Review fixes: empty-journey dead end, direct-link content access, published key protection, quiz review. */
class JourneyPolicyIT extends IntegrationTest {
    @Autowired JdbcTemplate jdbc;
    String admin;

    @BeforeEach
    void login() throws Exception {
        admin = cmsToken("admin@basamak.local");
    }

    @Test
    void emptyJourneyCannotBePublished() throws Exception {
        JsonNode j = create("JOURNEY", "Boş " + System.nanoTime(), Map.of("order", 40, "trainingIds", List.of()));
        submitApprove(j.at("/latest/id").asText());
        mvc.perform(post("/api/cms/versions/" + j.at("/latest/id").asText() + "/publish").header("Authorization", bearer(admin)))
           .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("empty_journey"));

        // a journey whose only training is still a draft is also empty for members
        String draftTraining = create("TRAINING", "Taslak eğitim " + System.nanoTime(), LearningIT.lessons("x", "a")).get("id").asText();
        JsonNode j2 = create("JOURNEY", "Taslaklı " + System.nanoTime(), Map.of("order", 41, "trainingIds", List.of(draftTraining)));
        submitApprove(j2.at("/latest/id").asText());
        mvc.perform(post("/api/cms/versions/" + j2.at("/latest/id").asText() + "/publish").header("Authorization", bearer(admin)))
           .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("empty_journey"));
    }

    @Test
    void legacyEmptyJourneyIsComingSoonAndNextOneSaysWhatItWaitsFor() throws Exception {
        String t = publishedTraining("Yakında testi " + System.nanoTime());
        String title = "Eski Boş " + System.nanoTime();
        String empty = publish(create("JOURNEY", title, Map.of("order", 60, "trainingIds", List.of(t), "unlock", Map.of("requiresPreviousJourney", false))));
        String next = publish(create("JOURNEY", "Sonraki " + System.nanoTime(), Map.of("order", 61, "trainingIds", List.of(t), "unlock", Map.of("requiresPreviousJourney", true))));
        // simulate content published before the guard existed: the published snapshot has no trainings
        jdbc.update("UPDATE content_version SET body = jsonb_set(body, '{trainingIds}', '[]'::jsonb) WHERE item_id = ?::uuid AND status = 'PUBLISHED'", empty);

        String m = newMember("soon");
        Map<String, JsonNode> byId = new HashMap<>();
        getJson("/api/me/journeys", m).forEach(x -> byId.put(x.get("id").asText(), x));
        JsonNode e = byId.get(empty), n = byId.get(next);
        assertThat(e.get("comingSoon").asBoolean()).isTrue();
        assertThat(e.get("completed").asBoolean()).isFalse();          // never auto-completed
        assertThat(e.get("certificateSerial").isNull()).isTrue();      // no certificate
        assertThat(n.get("locked").asBoolean()).isTrue();
        assertThat(n.get("lockReason").asText()).contains(title);       // names what it waits for
        assertThat(getJson("/api/me/summary", m).get("totalPoints").asInt()).isZero();
    }

    @Test
    void notEnrolledMemberGetsOnlyTheOutline() throws Exception {
        String m = newMember("outline");
        String intro = null;
        for (JsonNode t : getJson("/api/me/journeys", m).get(0).get("trainings")) if (t.get("title").asText().equals("Basamak'a Giriş")) intro = t.get("id").asText();
        JsonNode view = getJson("/api/trainings/" + intro, m);
        assertThat(view.get("items").get(0).has("content")).isFalse();
        assertThat(view.toString()).doesNotContain("questions");
        postJson("/api/trainings/" + intro + "/enroll", null, m);
        assertThat(getJson("/api/trainings/" + intro, m).get("items").get(0).has("content")).isTrue();
    }

    @Test
    void publishedItemKeysCannotSilentlyDisappear() throws Exception {
        String id = publishedTraining("Anahtar " + System.nanoTime());
        JsonNode item = getJson("/api/cms/items/" + id, admin);
        // "rename" a -> a2 is a removal of a published key
        mvc.perform(jsonPut("/api/cms/items/" + id + "/draft", Map.of("lockVersion", item.get("lockVersion").asLong(),
                "title", item.at("/latest/title").asText(), "body", LearningIT.lessons("y", "a2", "b"))).header("Authorization", bearer(admin)))
           .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("published_keys_removed")).andExpect(jsonPath("$.keys[0]").value("a"));
        // explicit, confirmed removal is allowed
        mvc.perform(jsonPut("/api/cms/items/" + id + "/draft", Map.of("lockVersion", item.get("lockVersion").asLong(),
                "title", item.at("/latest/title").asText(), "body", LearningIT.lessons("y", "b"), "confirmRemovedKeys", List.of("a")))
                .header("Authorization", bearer(admin))).andExpect(status().isOk());
    }

    @Test
    void quizReviewShowsOwnAttemptsAndRevealsAnswersOnlyWhenAllowed() throws Exception {
        String m = newMember("review");
        String intro = null;
        for (JsonNode t : getJson("/api/me/journeys", m).get(0).get("trainings")) if (t.get("title").asText().equals("Basamak'a Giriş")) intro = t.get("id").asText();
        postJson("/api/trainings/" + intro + "/enroll", null, m);

        JsonNode a1 = postJson("/api/trainings/" + intro + "/items/giris-quiz/attempts", null, m);
        JsonNode failed = postJson("/api/quiz-attempts/" + a1.get("attemptId").asText() + "/submit", Map.of("answers", Map.of()), m);
        assertThat(failed.toString()).doesNotContain("correctOptionIds"); // retries left -> no answers leaked

        JsonNode review = getJson("/api/trainings/" + intro + "/items/giris-quiz/attempts", m);
        assertThat(review).hasSize(1);
        JsonNode q = review.get(0).get("questions").get(0);
        assertThat(q.has("text")).isTrue();
        assertThat(q.get("correct").asBoolean()).isFalse();
        assertThat(review.toString()).doesNotContain("correctOptionIds");
        // reviewing does not consume attempts
        getJson("/api/trainings/" + intro + "/items/giris-quiz/attempts", m);
        assertThat(getJson("/api/trainings/" + intro, m).get("items").get(2).get("attemptsUsed").asInt()).isEqualTo(1);

        JsonNode a2 = postJson("/api/trainings/" + intro + "/items/giris-quiz/attempts", null, m);
        Map<String, Object> ans = new HashMap<>();
        a2.get("questions").forEach(x -> ans.put(x.get("id").asText(), LearningIT.ANSWERS.get(x.get("id").asText())));
        assertThat(postJson("/api/quiz-attempts/" + a2.get("attemptId").asText() + "/submit", Map.of("answers", ans), m).get("passed").asBoolean()).isTrue();
        JsonNode after = getJson("/api/trainings/" + intro + "/items/giris-quiz/attempts", m);
        assertThat(after).hasSize(2);
        assertThat(after.toString()).contains("correctOptionIds"); // passed -> quiz allows showing answers

        // another member has no access to these attempts
        String other = newMember("review-other");
        mvc.perform(get("/api/trainings/" + intro + "/items/giris-quiz/attempts").header("Authorization", bearer(other)))
           .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("not_enrolled"));
    }

    @Test
    void journeyCardsCarryRealProgressAndRulesAreListed() throws Exception {
        String m = newMember("cards");
        JsonNode intro = null;
        for (JsonNode t : getJson("/api/me/journeys", m).get(0).get("trainings")) if (t.get("title").asText().equals("Basamak'a Giriş")) intro = t;
        assertThat(intro.get("itemCount").asInt()).isEqualTo(4);
        assertThat(intro.get("completedItems").asInt()).isZero();
        assertThat(intro.get("currentKey").isNull()).isTrue();
        String id = intro.get("id").asText();
        postJson("/api/trainings/" + id + "/enroll", null, m);
        postJson("/api/trainings/" + id + "/items/hos-geldin/complete", null, m);
        postJson("/api/trainings/" + id + "/items/puanlar/view", null, m);
        JsonNode after = null;
        for (JsonNode t : getJson("/api/me/journeys", m).get(0).get("trainings")) if (t.get("id").asText().equals(id)) after = t;
        assertThat(after.get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(after.get("completedItems").asInt()).isEqualTo(1);
        assertThat(after.get("currentKey").asText()).isEqualTo("puanlar");
        assertThat(after.get("lastActivityAt").isNull()).isFalse();

        JsonNode rules = getJson("/api/point-rules", m);
        assertThat(rules.size()).isGreaterThan(5);
        assertThat(rules.toString()).contains("Quiz geçme");
    }

    // ---- helpers ----

    private JsonNode create(String type, String title, Map<String, Object> body) throws Exception {
        return body(mvc.perform(jsonPost("/api/cms/items", Map.of("type", type, "title", title, "body", body)).header("Authorization", bearer(admin)))
                .andExpect(status().isCreated()).andReturn());
    }

    private void submitApprove(String v) throws Exception {
        for (String s : List.of("submit", "approve")) mvc.perform(post("/api/cms/versions/" + v + "/" + s).header("Authorization", bearer(admin))).andExpect(status().isOk());
    }

    private String publish(JsonNode created) throws Exception {
        String v = created.at("/latest/id").asText();
        submitApprove(v);
        mvc.perform(post("/api/cms/versions/" + v + "/publish").header("Authorization", bearer(admin))).andExpect(status().isOk());
        return created.get("id").asText();
    }

    private String publishedTraining(String title) throws Exception {
        return publish(create("TRAINING", title, LearningIT.lessons("x", "a", "b")));
    }
}
