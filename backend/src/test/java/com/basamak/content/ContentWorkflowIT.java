package com.basamak.content;

import com.basamak.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Scenarios 1, 2 and 4 (API level) + publication rules that protect members from unpublished content. */
class ContentWorkflowIT extends IntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ContentService contentService;

    String author, editor, editor2, publisher, member;
    /** A journey can only be published with at least one published training (empty-journey guard). */
    static List<String> seedTrainings;

    @BeforeEach
    void tokens() throws Exception {
        author = cmsToken("author@basamak.local");
        editor = cmsToken("editor@basamak.local");
        editor2 = cmsToken("editor2@basamak.local");
        publisher = cmsToken("publisher@basamak.local");
        member = memberToken("member@basamak.local");
        if (seedTrainings == null) {
            // a dedicated published training so these test journeys never overlap with real/seed learning paths
            JsonNode t = body(mvc.perform(jsonPost("/api/cms/items", Map.of("type", "TRAINING", "title", "CW test eğitimi",
                    "body", Map.of("items", List.of(Map.of("key", "a", "type", "lesson", "title", "A", "content", "x")))))
                    .header("Authorization", bearer(author))).andExpect(status().isCreated()).andReturn());
            String v = t.at("/latest/id").asText();
            mvc.perform(post("/api/cms/versions/" + v + "/submit").header("Authorization", bearer(author))).andExpect(status().isOk());
            mvc.perform(post("/api/cms/versions/" + v + "/approve").header("Authorization", bearer(editor))).andExpect(status().isOk());
            mvc.perform(post("/api/cms/versions/" + v + "/publish").header("Authorization", bearer(publisher))).andExpect(status().isOk());
            seedTrainings = List.of(t.get("id").asText());
        }
    }

    @Test
    void scenario1_seededJourneysAndNewJourneyAppearsWithoutCodeChange() throws Exception {
        JsonNode list = body(mvc.perform(get("/api/journeys").header("Authorization", bearer(member))).andExpect(status().isOk()).andReturn());
        List<String> titles = new ArrayList<>();
        list.forEach(j -> titles.add(j.get("title").asText()));
        assertThat(titles).startsWith("Beginner", "Expert", "Master");

        String title = "Architect " + System.nanoTime();
        JsonNode item = create("JOURNEY", title, Map.of("order", 4, "description", "Yeni seviye", "trainingIds", seedTrainings));
        String itemId = item.get("id").asText();
        // draft is invisible to members, by list and by id
        assertThat(journeyTitles()).doesNotContain(title);
        mvc.perform(get("/api/journeys/" + itemId).header("Authorization", bearer(member))).andExpect(status().isNotFound());

        String v1 = item.at("/latest/id").asText();
        approveAndPublish(v1);
        assertThat(journeyTitles()).contains(title);
        mvc.perform(get("/api/journeys/" + itemId).header("Authorization", bearer(member)))
           .andExpect(status().isOk()).andExpect(jsonPath("$.order").value(4));
    }

    @Test
    void scenario2_reviewPublishSecondVersionDiffAndRollback() throws Exception {
        String title = "Sürüm Testi " + System.nanoTime();
        JsonNode item = create("JOURNEY", title, Map.of("order", 9, "description", "ilk", "trainingIds", seedTrainings));
        String id = item.get("id").asText();
        String v1 = item.at("/latest/id").asText();

        // editor sends back with a reason, author fixes -> v2... keep it simple: v1 approved and published
        approveAndPublish(v1);

        // second version through the same flow
        JsonNode saved = body(mvc.perform(jsonPut("/api/cms/items/" + id + "/draft",
                        Map.of("lockVersion", lock(id), "title", title, "body", Map.of("order", 9, "description", "ikinci", "trainingIds", seedTrainings)))
                .header("Authorization", bearer(author))).andExpect(status().isOk()).andReturn());
        String v2 = saved.at("/latest/id").asText();
        assertThat(saved.at("/latest/versionNo").asInt()).isEqualTo(2);

        // reject needs a note, then it goes back to the author
        mvc.perform(post("/api/cms/versions/" + v2 + "/submit").header("Authorization", bearer(author))).andExpect(status().isOk());
        mvc.perform(jsonPost("/api/cms/versions/" + v2 + "/request-changes", Map.of("note", ""))
                .header("Authorization", bearer(editor))).andExpect(status().isBadRequest());
        mvc.perform(jsonPost("/api/cms/versions/" + v2 + "/request-changes", Map.of("note", "Açıklamayı netleştir"))
                .header("Authorization", bearer(editor))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CHANGES_REQUESTED"));
        JsonNode v3Item = body(mvc.perform(jsonPut("/api/cms/items/" + id + "/draft",
                        Map.of("lockVersion", lock(id), "title", title, "body", Map.of("order", 9, "description", "ikinci (düzeltildi)", "trainingIds", seedTrainings)))
                .header("Authorization", bearer(author))).andExpect(status().isOk()).andReturn());
        String v3 = v3Item.at("/latest/id").asText();
        mvc.perform(post("/api/cms/versions/" + v3 + "/submit").header("Authorization", bearer(author))).andExpect(status().isOk());
        mvc.perform(post("/api/cms/versions/" + v3 + "/approve").header("Authorization", bearer(editor))).andExpect(status().isOk());
        mvc.perform(post("/api/cms/versions/" + v3 + "/publish").header("Authorization", bearer(publisher))).andExpect(status().isOk());

        // member sees only the newest published snapshot
        mvc.perform(get("/api/journeys/" + id).header("Authorization", bearer(member)))
           .andExpect(jsonPath("$.description").value("ikinci (düzeltildi)")).andExpect(jsonPath("$.versionNo").value(3));

        // history keeps every version, diff shows the change
        JsonNode history = body(mvc.perform(get("/api/cms/items/" + id + "/versions").header("Authorization", bearer(editor))).andReturn());
        assertThat(history).hasSize(3);
        assertThat(history.get(0).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(history.get(2).get("status").asText()).isEqualTo("ARCHIVED");
        mvc.perform(get("/api/cms/items/" + id + "/diff?from=1&to=3").header("Authorization", bearer(editor)))
           .andExpect(jsonPath("$.changes[0].path").value("/description"))
           .andExpect(jsonPath("$.changes[0].before").value("ilk"))
           .andExpect(jsonPath("$.changes[0].after").value("ikinci (düzeltildi)"));

        // rollback: re-publish v1
        mvc.perform(post("/api/cms/versions/" + v1 + "/publish").header("Authorization", bearer(publisher))).andExpect(status().isOk());
        mvc.perform(get("/api/journeys/" + id).header("Authorization", bearer(member)))
           .andExpect(jsonPath("$.description").value("ilk")).andExpect(jsonPath("$.versionNo").value(1));

        // audit trail contains the whole story
        JsonNode audit = body(mvc.perform(get("/api/cms/items/" + id + "/audit").header("Authorization", bearer(editor))).andReturn());
        List<String> actions = new ArrayList<>();
        audit.forEach(a -> actions.add(a.get("action").asText()));
        assertThat(actions).contains("content.created", "content.submitted", "content.approved", "content.changes_requested",
                "content.published", "content.republished");
    }

    @Test
    void rolesAreEnforcedOnTheServer() throws Exception {
        JsonNode item = create("JOURNEY", "Rol Testi " + System.nanoTime(), Map.of("order", 8, "trainingIds", seedTrainings));
        String v = item.at("/latest/id").asText();
        mvc.perform(post("/api/cms/versions/" + v + "/submit").header("Authorization", bearer(author))).andExpect(status().isOk());
        // author cannot approve or publish
        mvc.perform(post("/api/cms/versions/" + v + "/approve").header("Authorization", bearer(author))).andExpect(status().isForbidden());
        mvc.perform(post("/api/cms/versions/" + v + "/publish").header("Authorization", bearer(author))).andExpect(status().isForbidden());
        // publisher cannot publish something that was not approved
        mvc.perform(post("/api/cms/versions/" + v + "/publish").header("Authorization", bearer(publisher))).andExpect(status().isConflict());
        // editor cannot approve their own version
        JsonNode own = body(mvc.perform(jsonPost("/api/cms/items", Map.of("type", "JOURNEY", "title", "Editör kendi", "body", Map.of("order", 7, "trainingIds", seedTrainings)))
                .header("Authorization", bearer(editor))).andExpect(status().isCreated()).andReturn());
        String ownV = own.at("/latest/id").asText();
        mvc.perform(post("/api/cms/versions/" + ownV + "/submit").header("Authorization", bearer(editor))).andExpect(status().isOk());
        mvc.perform(post("/api/cms/versions/" + ownV + "/approve").header("Authorization", bearer(editor))).andExpect(status().isForbidden());
        // another author cannot edit someone else's content (object-level rule) - editor can
        mvc.perform(jsonPut("/api/cms/items/" + own.get("id").asText() + "/draft",
                Map.of("lockVersion", lock(own.get("id").asText()), "title", "x", "body", Map.of("order", 7, "trainingIds", seedTrainings)))
                .header("Authorization", bearer(author))).andExpect(status().isForbidden());
    }

    @Test
    void scenario4_staleSaveIsRejectedNotOverwritten() throws Exception {
        JsonNode item = create("JOURNEY", "Çakışma " + System.nanoTime(), Map.of("order", 6, "description", "v1", "trainingIds", seedTrainings));
        String id = item.get("id").asText();
        long loaded = lock(id); // both editors open the same version

        mvc.perform(jsonPut("/api/cms/items/" + id + "/draft", Map.of("lockVersion", loaded, "title", "A", "body", Map.of("order", 6, "description", "editor A", "trainingIds", seedTrainings)))
                .header("Authorization", bearer(editor))).andExpect(status().isOk());
        mvc.perform(jsonPut("/api/cms/items/" + id + "/draft", Map.of("lockVersion", loaded, "title", "B", "body", Map.of("order", 6, "description", "editor B", "trainingIds", seedTrainings)))
                .header("Authorization", bearer(editor2)))
           .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("edit_conflict"))
           .andExpect(jsonPath("$.latestVersionNo").value(2));

        JsonNode latest = body(mvc.perform(get("/api/cms/items/" + id).header("Authorization", bearer(editor))).andReturn());
        assertThat(latest.at("/latest/body/description").asText()).isEqualTo("editor A");
    }

    @Test
    void scenario4_trulyConcurrentSavesExactlyOneWins() throws Exception {
        JsonNode item = create("JOURNEY", "Eşzamanlı " + System.nanoTime(), Map.of("order", 5, "trainingIds", seedTrainings));
        String id = item.get("id").asText();
        long loaded = lock(id);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (String who : List.of(editor, editor2)) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(jsonPut("/api/cms/items/" + id + "/draft",
                        Map.of("lockVersion", loaded, "title", "T", "body", Map.of("order", 5, "trainingIds", seedTrainings)))
                        .header("Authorization", bearer(who))).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : results) codes.add(f.get());
        pool.shutdown();
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        JsonNode history = body(mvc.perform(get("/api/cms/items/" + id + "/versions").header("Authorization", bearer(editor))).andReturn());
        assertThat(history).hasSize(2); // v1 + exactly one accepted save
    }

    @Test
    void scheduledPublicationHappensWhenDue() throws Exception {
        JsonNode item = create("JOURNEY", "Zamanlı " + System.nanoTime(), Map.of("order", 11, "trainingIds", seedTrainings));
        String id = item.get("id").asText();
        String v = item.at("/latest/id").asText();
        mvc.perform(post("/api/cms/versions/" + v + "/submit").header("Authorization", bearer(author))).andExpect(status().isOk());
        mvc.perform(post("/api/cms/versions/" + v + "/approve").header("Authorization", bearer(editor))).andExpect(status().isOk());
        Instant at = Instant.now().plusSeconds(3600);
        mvc.perform(jsonPost("/api/cms/versions/" + v + "/publish", Map.of("scheduledAt", at.toString()))
                .header("Authorization", bearer(publisher))).andExpect(jsonPath("$.status").value("SCHEDULED"));
        mvc.perform(get("/api/journeys/" + id).header("Authorization", bearer(member))).andExpect(status().isNotFound());

        contentService.publishDue(at.plusSeconds(1));
        mvc.perform(get("/api/journeys/" + id).header("Authorization", bearer(member))).andExpect(status().isOk());
    }

    @Test
    void trainingPrerequisiteCyclesAreRejected() throws Exception {
        String a = create("TRAINING", "A", Map.of("prerequisites", List.of())).get("id").asText();
        String b = create("TRAINING", "B", Map.of("prerequisites", List.of(a))).get("id").asText();
        String c = create("TRAINING", "C", Map.of("prerequisites", List.of(b))).get("id").asText();
        // A -> C closes the cycle A -> C -> B -> A
        mvc.perform(jsonPut("/api/cms/items/" + a + "/draft", Map.of("lockVersion", lock(a), "title", "A", "body", Map.of("prerequisites", List.of(c))))
                .header("Authorization", bearer(author)))
           .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("prerequisite_cycle"));
        mvc.perform(jsonPut("/api/cms/items/" + a + "/draft", Map.of("lockVersion", lock(a), "title", "A", "body", Map.of("prerequisites", List.of(a))))
                .header("Authorization", bearer(author)))
           .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("prerequisite_cycle"));
    }

    @Test
    void auditLogIsAppendOnly() {
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET action = 'x'")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log")).hasMessageContaining("append-only");
    }

    // ---- helpers ----

    private JsonNode create(String type, String title, Map<String, Object> bodyMap) throws Exception {
        return body(mvc.perform(jsonPost("/api/cms/items", Map.of("type", type, "title", title, "body", bodyMap))
                .header("Authorization", bearer(author))).andExpect(status().isCreated()).andReturn());
    }

    private void approveAndPublish(String versionId) throws Exception {
        mvc.perform(post("/api/cms/versions/" + versionId + "/submit").header("Authorization", bearer(author))).andExpect(status().isOk());
        mvc.perform(post("/api/cms/versions/" + versionId + "/approve").header("Authorization", bearer(editor))).andExpect(status().isOk());
        mvc.perform(post("/api/cms/versions/" + versionId + "/publish").header("Authorization", bearer(publisher)))
           .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    private long lock(String id) throws Exception {
        return body(mvc.perform(get("/api/cms/items/" + id).header("Authorization", bearer(author))).andReturn()).get("lockVersion").asLong();
    }

    private List<String> journeyTitles() throws Exception {
        JsonNode list = body(mvc.perform(get("/api/journeys").header("Authorization", bearer(member))).andReturn());
        List<String> t = new ArrayList<>();
        list.forEach(j -> t.add(j.get("title").asText()));
        return t;
    }
}
