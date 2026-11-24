package com.basamak.seed;

import com.basamak.auth.Role;
import com.basamak.auth.User;
import com.basamak.auth.UserRepository;
import com.basamak.community.CommunityService;
import com.basamak.content.ContentType;
import com.basamak.content.PublishedContent;
import com.basamak.workflow.WorkflowService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;

/**
 * Demo community so the Topluluk page is not an empty dead end on a fresh database: three fictional members with
 * public profiles share workflows built from the seeded platform tools, rate and comment on each other's work and on
 * the intro training, and the demo member follows two of them. Everything goes through the real services
 * (WorkflowService, CommunityService), so activity events, points, notifications and validation are the same as for
 * real members; nothing is written behind their back. Shared member tools are not seeded: a member tool may not call
 * the local mock API (SSRF guard), and the seed must not depend on the public internet.
 * Runs once (skipped when the first demo member exists); off in backend tests (basamak.seed.community=false).
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class CommunitySeeder implements ApplicationRunner {
    static final String GIZEM = "gizem@basamak.local", MERT = "mert@basamak.local", ZEYNEP = "zeynep@basamak.local";

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final WorkflowService workflows;
    private final CommunityService community;
    private final PublishedContent published;
    private final ObjectMapper json;

    @Value("${basamak.seed.enabled:true}")
    private boolean enabled;
    @Value("${basamak.seed.community:true}")
    private boolean communityEnabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (enabled && communityEnabled) seed();
    }

    @Transactional
    public void seed() {
        if (users.findByEmailIgnoreCase(GIZEM).isPresent()) return;
        User member = users.findByEmailIgnoreCase("member@basamak.local").orElse(null);
        UUID glossary = tool("Terim sözlüğü"), summarizer = tool("Metin özetleyici");
        if (member == null || glossary == null || summarizer == null) return; // base seed missing: nothing to build on

        User gizem = user(GIZEM, "Gizem Kaya"), mert = user(MERT, "Mert Aydın"), zeynep = user(ZEYNEP, "Zeynep Şahin");
        profile(gizem, "Otomasyonu öğreniyorum; kısa ve işe yarayan akışlar paylaşıyorum.");
        profile(mert, "Ders notlarını tekrar sorularına çeviren akışlar kuruyorum.");
        profile(zeynep, "Haftalık çalışma planı yapmadan başlamam.");

        UUID termWf = share(gizem, "Terimi örnekle açıkla", "Bilmediğim terimi sözlükte arar, bulursa günlük bir örnekle anlatır.", """
                {"inputs":[{"name":"terim","label":"Terim","type":"string","required":true,"maxLength":60}],
                 "steps":[
                  {"id":"sozluk","type":"tool","tool":{"scope":"CONTENT","id":"%s"},"input":{"term":"{{input.terim}}"}},
                  {"id":"bulundu_mu","type":"condition","left":"{{steps.sozluk.output.found}}","op":"eq","right":"true","then":["ornek"],"else":["oneri"]},
                  {"id":"ornek","type":"ai","prompt":"Şu açıklamayı günlük hayattan bir örnekle anlat: {{steps.sozluk.output.definition}}"},
                  {"id":"oneri","type":"ai","prompt":"'{{input.terim}}' sözlükte yok. Bu konuyla ilgili öğrenmem gereken 3 temel terimi öner."}
                 ],"output":""}""".formatted(glossary));
        UUID quizWf = share(mert, "Ders notundan tekrar soruları", "Notu özetler, özetten 5 kısa tekrar sorusu yazar.", """
                {"inputs":[{"name":"notlar","label":"Ders notların","type":"string","required":true,"maxLength":2000}],
                 "steps":[
                  {"id":"ozet","type":"tool","tool":{"scope":"CONTENT","id":"%s"},"input":{"text":"{{input.notlar}}","sentences":"3"}},
                  {"id":"sorular","type":"ai","prompt":"Bu özetten 5 kısa tekrar sorusu yaz:\\n{{steps.ozet.output.summary}}"}
                 ],"output":"{{steps.sorular.output.text}}"}""".formatted(summarizer));
        share(zeynep, "Haftalık çalışma planı", "Hedefini ve ayırabileceğin süreyi alır, 7 günlük plan yazar.", """
                {"inputs":[{"name":"hedef","label":"Bu haftaki hedefin","type":"string","required":true,"maxLength":300},
                           {"name":"saat","label":"Haftada kaç saat ayırabilirsin?","type":"number","required":true}],
                 "steps":[{"id":"plan","type":"ai","prompt":"Hedef: {{input.hedef}}. Haftada {{input.saat}} saat ayırabiliyorum. Günlere bölünmüş, kısa maddeli 7 günlük bir çalışma planı yaz."}],
                 "output":"{{steps.plan.output.text}}"}""");

        // the community talks to each other (ratings and comments through the same rules as members: masking, profanity)
        community.rate(mert.getId(), termWf, 5);
        community.rate(zeynep.getId(), termWf, 4);
        community.rate(gizem.getId(), quizWf, 5);
        community.addComment(mert.getId(), "WORKFLOW", termWf, null, "Webhook terimini denedim, örnek çok anlaşılır olmuş. Teşekkürler!");
        community.addComment(zeynep.getId(), "WORKFLOW", quizWf, null, "Sınav haftası için tam aradığım şey. Soru sayısını 10 yapan bir sürüm de güzel olur.");
        published.list(ContentType.TRAINING).stream().filter(t -> t.title().equals("Basamak'a Giriş")).findFirst().ifPresent(t -> {
            var c = community.addComment(gizem.getId(), "TRAINING", t.itemId(), null, "Puan kuralları bölümü kafamdaki soruları netleştirdi. Quiz'de çoklu seçimli soruya dikkat!");
            community.addComment(zeynep.getId(), "TRAINING", t.itemId(), (Long) c.get("id"), "Katılıyorum, ben de ilk denemede o soruda takıldım.");
        });

        // follows: the demo members follow each other; the demo member follows two of them, so the feed is not empty
        community.follow(gizem.getId(), mert.getId());
        community.follow(mert.getId(), gizem.getId());
        community.follow(zeynep.getId(), gizem.getId());
        community.follow(member.getId(), gizem.getId());
        community.follow(member.getId(), mert.getId());
        log.info("Seeded demo community: 3 public members, 3 shared workflows, ratings, comments and follows");
    }

    private UUID tool(String name) {
        return published.list(ContentType.TOOL).stream().filter(t -> t.title().equals(name)).map(PublishedContent.Published::itemId).findFirst().orElse(null);
    }

    private UUID share(User owner, String name, String description, String definition) {
        try {
            JsonNode def = json.readTree(definition);
            UUID id = workflows.create(owner.getId(), name, description, def).id();
            workflows.setVisibility(owner.getId(), id, true);
            return id;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void profile(User u, String bio) {
        community.saveSettings(u.getId(), new CommunityService.Settings(true, true, true, true, true, bio));
    }

    private User user(String email, String name) {
        User u = new User();
        u.setEmail(email);
        u.setDisplayName(name);
        u.setPasswordHash(encoder.encode(DataSeeder.DEMO_PASSWORD));
        u.setRoles(EnumSet.of(Role.MEMBER));
        u.setEmailVerifiedAt(Instant.now());
        u.setConsentVersion("seed");
        u.setConsentAt(Instant.now());
        return users.saveAndFlush(u); // flushed: profile_settings and follow rows reference it via JDBC right away
    }
}
