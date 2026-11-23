package com.basamak.seed;

import com.basamak.auth.AuthPrincipal;
import com.basamak.auth.Role;
import com.basamak.auth.User;
import com.basamak.auth.UserRepository;
import com.basamak.content.ContentService;
import com.basamak.content.ContentType;
import com.basamak.content.ContentVersion;
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
import java.util.Set;

/**
 * Idempotent seed data, loaded automatically on start (basamak.seed.enabled, default true).
 * Demo accounts use the documented demo password from README; never use them outside local/demo environments.
 * Content is created through ContentService so seed journeys have a real author -> editor -> publisher history.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {
    public static final String DEMO_PASSWORD = "Basamak!2026";

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final ContentService content;
    private final ObjectMapper json;

    @Value("${basamak.seed.enabled:true}")
    private boolean enabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        User admin = user("admin@basamak.local", "Yönetici Ayşe", EnumSet.of(Role.ADMIN, Role.MEMBER));
        User author = user("author@basamak.local", "Yazar Ali", EnumSet.of(Role.AUTHOR));
        User editor = user("editor@basamak.local", "Editör Elif", EnumSet.of(Role.EDITOR));
        user("editor2@basamak.local", "Editör Emre", EnumSet.of(Role.EDITOR));
        User publisher = user("publisher@basamak.local", "Yayıncı Yusuf", EnumSet.of(Role.PUBLISHER));
        user("member@basamak.local", "Üye Deniz", EnumSet.of(Role.MEMBER));

        if (content.list(ContentType.JOURNEY).isEmpty()) {
            journey(author, editor, publisher, "Beginner", "Temel kavramlar ve ilk araçlar.", 1, false);
            journey(author, editor, publisher, "Expert", "İleri iş akışları ve otomasyon.", 2, true);
            journey(author, editor, publisher, "Master", "Topluluğa katkı ve karmaşık akışlar.", 3, true);
            log.info("Seeded 3 journeys");
        }
        log.debug("Seed admin {}", admin.getEmail());
    }

    private void journey(User author, User editor, User publisher, String title, String desc, int order, boolean requiresPrevious) {
        ObjectNode body = json.createObjectNode();
        body.put("description", desc);
        body.put("order", order);
        body.putArray("trainingIds");
        body.putObject("unlock").put("requiresPreviousJourney", requiresPrevious).put("minPoints", 0);
        ContentVersion v = content.create(principal(author), ContentType.JOURNEY, title, body);
        content.submit(principal(author), v.getId());
        content.approve(principal(editor), v.getId(), "Tohum verisi");
        content.publish(principal(publisher), v.getId(), null);
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
