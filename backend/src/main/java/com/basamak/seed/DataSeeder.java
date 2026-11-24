package com.basamak.seed;

import com.basamak.auth.AuthPrincipal;
import com.basamak.auth.Role;
import com.basamak.auth.User;
import com.basamak.auth.UserRepository;
import com.basamak.content.ContentService;
import com.basamak.content.ContentType;
import com.basamak.content.ContentVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Idempotent seed data, loaded automatically on start (basamak.seed.enabled, default true).
 * Demo accounts use the documented demo password from README; never use them outside local/demo environments.
 * Content goes through ContentService so seed content has a real author -> editor -> publisher history.
 */
@Slf4j
@Component
@org.springframework.core.annotation.Order(1) // before CommunitySeeder
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {
    public static final String DEMO_PASSWORD = "Basamak!2026";

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final ContentService content;
    private final ObjectMapper json;
    private final com.basamak.tool.ToolSecrets secrets;

    /** Base URL of the zero-cost mock API that backs the seeded learning tools (docker: http://mock-api:8090). */
    @Value("${MOCK_API_BASE:http://localhost:8090}")
    private String mockApiBase;
    /** Not a real credential: the mock API's demo key (README), stored encrypted like any tool secret. */
    @Value("${MOCK_API_KEY:demo-mock-key}")
    private String mockApiKey;

    @Value("${basamak.seed.enabled:true}")
    private boolean enabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        user("admin@basamak.local", "Yönetici Ayşe", EnumSet.of(Role.ADMIN, Role.MEMBER));
        User author = user("author@basamak.local", "Yazar Ali", EnumSet.of(Role.AUTHOR));
        User editor = user("editor@basamak.local", "Editör Elif", EnumSet.of(Role.EDITOR));
        user("editor2@basamak.local", "Editör Emre", EnumSet.of(Role.EDITOR));
        User publisher = user("publisher@basamak.local", "Yayıncı Yusuf", EnumSet.of(Role.PUBLISHER));
        user("member@basamak.local", "Üye Deniz", EnumSet.of(Role.MEMBER));

        if (content.list(ContentType.POINT_RULE).isEmpty()) {
            // action, objectType, points, policy - rationale in TASARIM §5.1
            rule(author, editor, publisher, "Ders görüntüleme", "LESSON_VIEWED", "LESSON", 1, "ONCE_PER_OBJECT");
            rule(author, editor, publisher, "Eğitime başlama", "TRAINING_STARTED", "TRAINING", 2, "ONCE_PER_OBJECT");
            rule(author, editor, publisher, "Ders tamamlama", "LESSON_COMPLETED", "LESSON", 5, "ONCE_PER_OBJECT");
            rule(author, editor, publisher, "Quiz geçme", "QUIZ_PASSED", "QUIZ", 20, "ONCE_PER_OBJECT");
            rule(author, editor, publisher, "Anket doldurma", "SURVEY_SUBMITTED", "SURVEY", 10, "ONCE_PER_OBJECT");
            rule(author, editor, publisher, "Eğitim tamamlama", "TRAINING_COMPLETED", "TRAINING", 50, "ONCE_PER_OBJECT");
            rule(author, editor, publisher, "Journey tamamlama", "JOURNEY_COMPLETED", "JOURNEY", 200, "ONCE_PER_OBJECT");
            rule(author, editor, publisher, "Araç oluşturma", "TOOL_CREATED", "TOOL", 30, "ONCE_PER_OBJECT");
            rule(author, editor, publisher, "Araç çalıştırma (günlük)", "TOOL_RUN_SUCCEEDED", "TOOL", 2, "ONCE_PER_DAY");
            rule(author, editor, publisher, "İş akışı oluşturma", "WORKFLOW_CREATED", "WORKFLOW", 30, "ONCE_PER_OBJECT");
            rule(author, editor, publisher, "İş akışı tamamlama (günlük)", "WORKFLOW_RUN_SUCCEEDED", "WORKFLOW", 5, "ONCE_PER_DAY");
            rule(author, editor, publisher, "Paylaşma", "ITEM_SHARED", "*", 15, "ONCE_PER_OBJECT");
            rule(author, editor, publisher, "Paylaşılanın başkasınca kullanılması (günlük)", "SHARED_ITEM_USED", "*", 3, "ONCE_PER_DAY");
            rule(author, editor, publisher, "Paylaşılanın çatallanması (yazara)", "SHARED_ITEM_FORKED", "*", 10, "ONCE_PER_OBJECT");
            log.info("Seeded point rules");
        }

        if (content.list(ContentType.TOOL).isEmpty()) {
            // learning tools for trainings and workflows; they call the mock API (rule based, not a real language model)
            publish(author, editor, publisher, ContentType.TOOL, "Metin özetleyici", parse("""
                {"description":"Uzun bir metnin ilk cümlelerinden kısa bir özet, kelime sayısı ve okuma süresi çıkarır.",
                 "inputs":[{"name":"text","label":"Metin","type":"string","required":true,"maxLength":2000},
                           {"name":"sentences","label":"Özet kaç cümle olsun (1-5)","type":"number","required":false}],
                 "request":{"method":"POST","url":"%s/text/summarize","headers":{},"body":{"text":"{{input.text}}","sentences":"{{input.sentences}}"}},
                 "timeoutSeconds":10,"retries":2,"outputPath":""}""".formatted(mockApiBase)));
            publish(author, editor, publisher, ContentType.TOOL, "Terim sözlüğü", parse("""
                {"description":"Bir otomasyon ya da platform teriminin kısa Türkçe açıklamasını verir (API, webhook, cron, token…).",
                 "inputs":[{"name":"term","label":"Terim","type":"string","required":true,"maxLength":60}],
                 "request":{"method":"GET","url":"%s/glossary?term={{input.term}}","headers":{}},
                 "timeoutSeconds":10,"retries":2,"outputPath":""}""".formatted(mockApiBase)));
            UUID checker = publish(author, editor, publisher, ContentType.TOOL, "Metin denetleyici", parse("""
                {"description":"Yazdığın metni denetler: uzun cümleler, tekrarlanan kelimeler, noktalama. Bir puan ve öneri listesi verir.",
                 "inputs":[{"name":"text","label":"Metin","type":"string","required":true,"maxLength":2000}],
                 "request":{"method":"POST","url":"%s/text/check","headers":{"X-Api-Key":"{{secret.apiKey}}"},"body":{"text":"{{input.text}}"}},
                 "timeoutSeconds":10,"retries":2,"outputPath":""}""".formatted(mockApiBase)));
            secrets.set(com.basamak.tool.ToolSecrets.Scope.CONTENT, checker, "apiKey", mockApiKey, admin().getId());
            log.info("Seeded 3 learning tools (mock API at {})", mockApiBase);
        }

        boolean noTrainings = content.list(ContentType.TRAINING).isEmpty();
        boolean noJourneys = content.list(ContentType.JOURNEY).isEmpty();
        if (noTrainings) {
            UUID intro = publish(author, editor, publisher, ContentType.TRAINING, "Basamak'a Giriş", introTraining());
            UUID second = publish(author, editor, publisher, ContentType.TRAINING, "İlk Aracını Tanı", secondTraining());
            List<UUID> beginner = List.of(intro, second);
            if (noJourneys) {
                // every journey ships with at least one training: an empty journey cannot be published (dead end)
                UUID expert = publish(author, editor, publisher, ContentType.TRAINING, "İş Akışlarına Giriş",
                        lessonOnly("Araç, yapay zekâ adımı ve koşulları birleştirerek iş akışı kurmayı öğrenin.", "akis-nedir", "İş akışı nedir?",
                                "Bir **iş akışı**, birbirini izleyen adımlardan oluşur; bir adımın çıktısı sonrakinin girdisidir."));
                UUID master = publish(author, editor, publisher, ContentType.TRAINING, "Topluluğa Katkı",
                        lessonOnly("Araç ve iş akışlarınızı toplulukla paylaşın.", "paylasim", "Paylaşmak ve çatallamak",
                                "Paylaştığınız bir iş akışını başkası çatallarsa siz de puan kazanırsınız."));
                journey(author, editor, publisher, "Beginner", "Temel kavramlar ve ilk araçlar.", 1, false, beginner);
                journey(author, editor, publisher, "Expert", "İleri iş akışları ve otomasyon.", 2, true, List.of(expert));
                journey(author, editor, publisher, "Master", "Topluluğa katkı ve karmaşık akışlar.", 3, true, List.of(master));
                log.info("Seeded 3 journeys with trainings");
            } else {
                // upgrade path for databases seeded before trainings existed: attach them as a new Beginner version
                content.list(ContentType.JOURNEY).stream()
                        .filter(j -> content.latest(j.getId()).getBody().path("order").asInt() == 1).findFirst()
                        .ifPresent(j -> addTrainings(editor, publisher, j.getId(), j.getLockVersion(), beginner));
            }
        }
    }

    private JsonNode lessonOnly(String summary, String key, String title, String text) {
        ObjectNode b = json.createObjectNode().put("summary", summary);
        b.putArray("prerequisites");
        b.putArray("items").addObject().put("key", key).put("type", "lesson").put("title", title).put("content", text);
        return b;
    }

    private void addTrainings(User editor, User publisher, UUID journeyId, long lock, List<UUID> trainings) {
        ContentVersion latest = content.latest(journeyId);
        ObjectNode body = latest.getBody().deepCopy();
        var ids = body.putArray("trainingIds");
        trainings.forEach(t -> ids.add(t.toString()));
        ContentVersion v = content.saveDraft(principal(editor), journeyId, lock, latest.getTitle(), body);
        content.submit(principal(editor), v.getId());
        content.approve(principal(admin()), v.getId(), "Tohum verisi: eğitimler eklendi");
        content.publish(principal(publisher), v.getId(), null);
        log.info("Attached seed trainings to journey {}", latest.getTitle());
    }

    private User admin() {
        return users.findByEmailIgnoreCase("admin@basamak.local").orElseThrow();
    }

    private void rule(User author, User editor, User publisher, String title, String action, String objectType, int points, String policy) {
        ObjectNode b = json.createObjectNode().put("action", action).put("objectType", objectType).put("points", points).put("policy", policy);
        publish(author, editor, publisher, ContentType.POINT_RULE, title, b);
    }

    private void journey(User author, User editor, User publisher, String title, String desc, int order, boolean requiresPrevious, List<UUID> trainings) {
        ObjectNode body = json.createObjectNode();
        body.put("description", desc);
        body.put("order", order);
        var ids = body.putArray("trainingIds");
        trainings.forEach(t -> ids.add(t.toString()));
        body.putObject("unlock").put("requiresPreviousJourney", requiresPrevious).put("minPoints", 0);
        publish(author, editor, publisher, ContentType.JOURNEY, title, body);
    }

    private UUID publish(User author, User editor, User publisher, ContentType type, String title, JsonNode body) {
        ContentVersion v = content.create(principal(author), type, title, body);
        content.submit(principal(author), v.getId());
        content.approve(principal(editor), v.getId(), "Tohum verisi");
        content.publish(principal(publisher), v.getId(), null);
        return v.getItemId();
    }

    private JsonNode introTraining() {
        return parse("""
            {"summary":"Basamak'ı, Journey'leri ve puan sistemini tanıyın.","prerequisites":[],
             "items":[
              {"key":"hos-geldin","type":"lesson","title":"Basamak'a hoş geldiniz",
               "content":"# Basamak nedir?\\n\\nBasamak hem bir **öğrenme platformu** hem de bir topluluktur.\\n\\n- Eğitimleri tamamlayarak Journey'lerde ilerlersiniz.\\n- Araçlar ve yapay zekâ iş akışları kurarsınız.\\n- Her aksiyon puan getirir."},
              {"key":"puanlar","type":"lesson","title":"Puanlar ve seviyeler",
               "content":"## Puan nasıl kazanılır?\\n\\nDers görüntülemek, quiz geçmek, anket doldurmak ve eğitim bitirmek farklı puanlar getirir.\\n\\nAynı dersi tekrar açmak puanı artırmaz."},
              {"key":"giris-quiz","type":"quiz","title":"Giriş quiz'i","passingScore":60,"attemptsAllowed":3,
               "timeLimitSeconds":600,"questionsPerAttempt":3,"showCorrectAnswers":true,"shuffleOptions":true,
               "questions":[
                {"id":"q1","type":"single","text":"Basamak'ta seviyeler neyle ifade edilir?","points":1,
                 "options":[{"id":"a","text":"Journey","correct":true},{"id":"b","text":"Rozet"},{"id":"c","text":"Kupon"}]},
                {"id":"q2","type":"single","text":"Aynı dersi 100 kez açmak kaç kez puan getirir?","points":1,
                 "options":[{"id":"a","text":"Bir kez","correct":true},{"id":"b","text":"100 kez"},{"id":"c","text":"Hiç"}]},
                {"id":"q3","type":"multi","text":"Hangileri puan getirir?","points":1,
                 "options":[{"id":"a","text":"Quiz geçmek","correct":true},{"id":"b","text":"Anket doldurmak","correct":true},{"id":"c","text":"Çıkış yapmak"}]},
                {"id":"q4","type":"single","text":"İçerik ekibi içeriği nerede hazırlar?","points":1,
                 "options":[{"id":"a","text":"CMS","correct":true},{"id":"b","text":"Mobil uygulama"}]},
                {"id":"q5","type":"single","text":"Üyeler hangi sürümü görür?","points":1,
                 "options":[{"id":"a","text":"En son yayınlanan","correct":true},{"id":"b","text":"Son taslak"}]}
               ]},
              {"key":"giris-anket","type":"survey","title":"Görüşünüz","anonymous":false,
               "questions":[
                {"id":"s1","type":"scale","text":"Bu eğitimi ne kadar faydalı buldunuz? (1-5)"},
                {"id":"s2","type":"single","text":"Basamak'ı nereden duydunuz?","options":[{"id":"a","text":"Arkadaş"},{"id":"b","text":"Sosyal medya"},{"id":"c","text":"Diğer"}]},
                {"id":"s3","type":"open","text":"Eklemek istediğiniz bir şey var mı?"}
               ]}
             ]}""");
    }

    private JsonNode secondTraining() {
        return parse("""
            {"summary":"Araçların nasıl çalıştığını öğrenin.","prerequisites":[],
             "items":[
              {"key":"arac-nedir","type":"lesson","title":"Araç nedir?",
               "content":"Bir **araç**, girdi alan ve bir API'ye bağlanıp arka planda çalışan bir birimdir.\\n\\n1. Girdileri doldurursunuz.\\n2. Araç kuyruğa girer.\\n3. Sonucu sayfa yenilenmeden görürsünüz."},
              {"key":"arac-anket","type":"survey","title":"Kısa anket (anonim)","anonymous":true,
               "questions":[{"id":"s1","type":"scale","text":"Araçlar konusu ne kadar açıktı? (1-5)"}]}
             ]}""");
    }

    private JsonNode parse(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private User user(String email, String name, Set<Role> roles) {
        return users.findByEmailIgnoreCase(email).orElseGet(() -> {
            User u = new User();
            u.setEmail(email);
            u.setDisplayName(name);
            u.setPasswordHash(encoder.encode(DEMO_PASSWORD));
            u.setRoles(EnumSet.copyOf(roles));
            u.setEmailVerifiedAt(Instant.now());
            u.setConsentVersion("seed");
            u.setConsentAt(Instant.now());
            return users.save(u);
        });
    }

    public static AuthPrincipal principal(User u) {
        return new AuthPrincipal(u.getId(), u.getEmail(), u.getRoles(), "cms", null);
    }
}
