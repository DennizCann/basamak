package com.basamak.seed;

import com.basamak.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The demo community seed makes the Topluluk page usable on a fresh database, through the real services, once. */
class CommunitySeederIT extends IntegrationTest {
    @Autowired CommunitySeeder seeder;

    @Test
    void demoMemberSeesAFeedSharedWorkflowsAndPublicProfilesAndTheSeedRunsOnce() throws Exception {
        seeder.seed();
        seeder.seed(); // idempotent: the second run changes nothing

        String member = memberToken("member@basamak.local");
        JsonNode feed = getJson("/api/feed", member);
        List<String> who = new ArrayList<>();
        feed.forEach(e -> who.add(e.get("userName").asText() + ":" + e.get("type").asText()));
        assertThat(who).contains("Gizem Kaya:ITEM_SHARED", "Mert Aydın:ITEM_SHARED", "Gizem Kaya:WORKFLOW_CREATED");
        assertThat(who).noneMatch(s -> s.startsWith("Zeynep")); // not followed by the demo member

        JsonNode shared = getJson("/api/community/workflows", member);
        List<String> names = new ArrayList<>();
        shared.forEach(w -> names.add(w.get("name").asText()));
        assertThat(names).contains("Terimi örnekle açıkla", "Ders notundan tekrar soruları", "Haftalık çalışma planı");
        assertThat(names.stream().filter(n -> n.equals("Terimi örnekle açıkla"))).hasSize(1);

        String gizemId = null;
        for (JsonNode e : feed) if (e.get("userName").asText().equals("Gizem Kaya")) gizemId = e.get("userId").asText();
        JsonNode profile = getJson("/api/profiles/" + gizemId, member);
        assertThat(profile.get("isPublic").asBoolean()).isTrue();
        assertThat(profile.get("isFollowing").asBoolean()).isTrue();

        String termWf = null;
        for (JsonNode w : shared) if (w.get("name").asText().equals("Terimi örnekle açıkla")) termWf = w.get("id").asText();
        assertThat(getJson("/api/workflows/" + termWf + "/rating", member).get("count").asInt()).isEqualTo(2);
        assertThat(getJson("/api/comments?targetType=WORKFLOW&targetId=" + termWf, member).size()).isEqualTo(1);

        // who to follow: public profiles not followed yet (Zeynep), never the followed ones or private members
        List<String> people = new ArrayList<>();
        getJson("/api/community/people?limit=20", member).forEach(u -> people.add(u.get("displayName").asText()));
        assertThat(people).contains("Zeynep Şahin").doesNotContain("Gizem Kaya", "Mert Aydın", "Üye Deniz");
        String zeynepId = null;
        for (JsonNode u : getJson("/api/community/people?limit=20", member)) if (u.get("displayName").asText().equals("Zeynep Şahin")) zeynepId = u.get("id").asText();
        postJson("/api/profiles/" + zeynepId + "/follow", null, member);
        List<String> after = new ArrayList<>();
        getJson("/api/community/people?limit=20", member).forEach(u -> after.add(u.get("displayName").asText()));
        assertThat(after).doesNotContain("Zeynep Şahin");
    }
}
