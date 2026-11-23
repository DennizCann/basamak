package com.basamak;

import com.basamak.auth.MailService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Shared base: one Spring context + one test database (basamak_test, recreated by Liquibase drop-first per run).
 * Mail is captured instead of sent. Seed users exist (DataSeeder) with the demo password.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {
    protected static final String PASSWORD = "Basamak!2026";
    private static final Map<String, String> TOTP_SECRETS = new ConcurrentHashMap<>();

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @MockitoBean protected MailService mail;

    @BeforeEach
    void mailOk() {
        when(mail.send(anyString(), anyString(), anyString())).thenReturn(true);
    }

    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
    }

    protected String lastMailTo(String to) {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mail, atLeastOnce()).send(eq(to), anyString(), text.capture());
        return text.getValue();
    }

    protected static String tokenFromMail(String text) {
        int i = text.indexOf("token=");
        return text.substring(i + 6).split("\\s")[0];
    }

    protected JsonNode body(MvcResult r) throws Exception {
        String s = r.getResponse().getContentAsString();
        return s.isEmpty() ? json.nullNode() : json.readTree(s);
    }

    protected MockHttpServletRequestBuilder jsonPost(String url, Object payload) throws Exception {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
    }

    protected MockHttpServletRequestBuilder jsonPut(String url, Object payload) throws Exception {
        return put(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
    }

    protected String memberToken(String email) throws Exception {
        MvcResult r = loginWithCode(email, PASSWORD, "web");
        if (r.getResponse().getStatus() != 200) throw new AssertionError("login failed: " + r.getResponse().getContentAsString());
        return body(r).get("accessToken").asText();
    }

    /** Member login: password step, then the 6-digit code from the (captured) e-mail. Returns the verify result. */
    protected MvcResult loginWithCode(String email, String password, String client) throws Exception {
        MvcResult step = mvc.perform(jsonPost("/api/auth/login", Map.of("email", email, "password", password, "client", client))).andReturn();
        if (step.getResponse().getStatus() != 200) throw new AssertionError("password step failed: " + step.getResponse().getContentAsString());
        String challenge = body(step).get("challengeId").asText();
        return mvc.perform(jsonPost("/api/auth/login/verify", Map.of("challengeId", challenge, "code", codeFromMail(lastMailTo(email))))).andReturn();
    }

    protected static String codeFromMail(String text) {
        var m = java.util.regex.Pattern.compile("giriş kodunuz: (\\d{6})").matcher(text);
        if (!m.find()) throw new AssertionError("no login code in mail: " + text);
        return m.group(1);
    }

    /** Full CMS sign-in: password step, enrol/verify TOTP with a code computed from the secret. */
    protected String cmsToken(String email) throws Exception {
        JsonNode step = body(mvc.perform(jsonPost("/api/cms-auth/login", Map.of("email", email, "password", PASSWORD))).andReturn());
        if (step.path("setupRequired").asBoolean()) TOTP_SECRETS.put(email, step.get("secret").asText());
        String code = totpNow(TOTP_SECRETS.get(email));
        MvcResult r = mvc.perform(jsonPost("/api/cms-auth/totp", Map.of("mfaToken", step.get("mfaToken").asText(), "code", code))).andReturn();
        if (r.getResponse().getStatus() != 200) throw new AssertionError("totp failed: " + r.getResponse().getContentAsString());
        return body(r).get("accessToken").asText();
    }

    protected static String totpNow(String secret) throws Exception {
        return new DefaultCodeGenerator().generate(secret, Math.floorDiv(Instant.now().getEpochSecond(), 30));
    }

    /** Registers + verifies a fresh member and returns an access token. */
    protected String newMember(String prefix) throws Exception {
        String email = uniqueEmail(prefix);
        MvcResult r = mvc.perform(jsonPost("/api/auth/register", Map.of("email", email, "password", PASSWORD, "displayName", "Test " + prefix, "consent", true))).andReturn();
        if (r.getResponse().getStatus() != 201) throw new AssertionError(r.getResponse().getContentAsString());
        mvc.perform(jsonPost("/api/auth/verify-email", Map.of("token", tokenFromMail(lastMailTo(email)))));
        return memberToken(email);
    }

    protected JsonNode getJson(String url, String token) throws Exception {
        MvcResult r = mvc.perform(get(url).header("Authorization", bearer(token))).andReturn();
        if (r.getResponse().getStatus() != 200) throw new AssertionError(url + " -> " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString());
        return body(r);
    }

    protected JsonNode postJson(String url, Object payload, String token) throws Exception {
        MvcResult r = mvc.perform(jsonPost(url, payload == null ? Map.of() : payload).header("Authorization", bearer(token))).andReturn();
        if (r.getResponse().getStatus() != 200) throw new AssertionError(url + " -> " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString());
        return body(r);
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }
}
