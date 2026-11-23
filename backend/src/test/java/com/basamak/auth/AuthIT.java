package com.basamak.auth;

import com.basamak.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthIT extends IntegrationTest {

    @Test
    void registerVerifyLogin() throws Exception {
        String email = uniqueEmail("reg");
        mvc.perform(jsonPost("/api/auth/register", Map.of("email", email, "password", "uzun-ve-guclu-1", "displayName", "Test Üye", "consent", false)))
           .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("consent_required"));
        mvc.perform(jsonPost("/api/auth/register", Map.of("email", email, "password", "uzun-ve-guclu-1", "displayName", "Test Üye", "consent", true)))
           .andExpect(status().isCreated());
        // not verified yet -> cannot sign in
        mvc.perform(jsonPost("/api/auth/login", Map.of("email", email, "password", "uzun-ve-guclu-1")))
           .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("email_not_verified"));

        String token = tokenFromMail(lastMailTo(email));
        mvc.perform(jsonPost("/api/auth/verify-email", Map.of("token", token))).andExpect(status().isOk());
        mvc.perform(jsonPost("/api/auth/verify-email", Map.of("token", token))).andExpect(status().isBadRequest()); // single use

        // password alone opens no session: it only returns a challenge
        MvcResult step = mvc.perform(jsonPost("/api/auth/login", Map.of("email", email.toUpperCase(), "password", "uzun-ve-guclu-1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.maskedEmail").value(org.hamcrest.Matchers.containsString("•"))).andReturn();
        assertThat(step.getResponse().getCookie("bsm_rt")).isNull();
        MvcResult r = mvc.perform(jsonPost("/api/auth/login/verify", Map.of("challengeId", body(step).get("challengeId").asText(),
                "code", codeFromMail(lastMailTo(email))))).andExpect(status().isOk()).andReturn();
        String access = body(r).get("accessToken").asText();
        mvc.perform(get("/api/me").header("Authorization", bearer(access)))
           .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email)).andExpect(jsonPath("$.roles[0]").value("MEMBER"));
    }

    @Test
    void duplicateEmailAndWeakPasswordRejected() throws Exception {
        mvc.perform(jsonPost("/api/auth/register", Map.of("email", "member@basamak.local", "password", "uzun-ve-guclu-1", "displayName", "X Y", "consent", true)))
           .andExpect(status().isConflict());
        mvc.perform(jsonPost("/api/auth/register", Map.of("email", uniqueEmail("weak"), "password", "kisa", "displayName", "X Y", "consent", true)))
           .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("weak_password"));
    }

    @Test
    void accountLocksAfterFiveFailures() throws Exception {
        String email = uniqueEmail("lock");
        register(email);
        for (int i = 0; i < 4; i++) {
            mvc.perform(jsonPost("/api/auth/login", Map.of("email", email, "password", "yanlis-sifre-123"))).andExpect(status().isUnauthorized());
        }
        mvc.perform(jsonPost("/api/auth/login", Map.of("email", email, "password", "yanlis-sifre-123")))
           .andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("account_locked"));
        // even the correct password is refused while locked
        mvc.perform(jsonPost("/api/auth/login", Map.of("email", email, "password", PASSWORD))).andExpect(status().isLocked());
    }

    @Test
    void refreshTokenRotatesAndRequiresCsrfHeader() throws Exception {
        MvcResult login = loginWithCode("member@basamak.local", PASSWORD, "web");
        Cookie rt = login.getResponse().getCookie("bsm_rt");
        assertThat(rt).isNotNull();
        assertThat(rt.isHttpOnly()).isTrue();

        mvc.perform(post("/api/auth/refresh").cookie(rt)).andExpect(status().isForbidden()); // no CSRF header

        MvcResult refreshed = mvc.perform(post("/api/auth/refresh").cookie(rt).header("X-Requested-With", "basamak"))
                .andExpect(status().isOk()).andReturn();
        Cookie rt2 = refreshed.getResponse().getCookie("bsm_rt");
        assertThat(rt2.getValue()).isNotEqualTo(rt.getValue());
        // old refresh token no longer works
        mvc.perform(post("/api/auth/refresh").cookie(rt).header("X-Requested-With", "basamak")).andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentRefreshWithSameTokenRotatesOnlyOnce() throws Exception {
        MvcResult login = loginWithCode("member@basamak.local", PASSWORD, "web");
        Cookie rt = login.getResponse().getCookie("bsm_rt");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var go = new java.util.concurrent.CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<MvcResult>>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> { go.await(); return mvc.perform(post("/api/auth/refresh").cookie(rt).header("X-Requested-With", "basamak")).andReturn(); }));
        }
        go.countDown();
        var codes = new java.util.ArrayList<Integer>();
        Cookie winner = null;
        for (var f : futures) {
            MvcResult r = f.get();
            codes.add(r.getResponse().getStatus());
            if (r.getResponse().getStatus() == 200) winner = r.getResponse().getCookie("bsm_rt");
        }
        pool.shutdown();
        assertThat(codes).containsExactlyInAnyOrder(200, 401);
        // the cookie the browser keeps (from the successful response) still works afterwards
        mvc.perform(post("/api/auth/refresh").cookie(winner).header("X-Requested-With", "basamak")).andExpect(status().isOk());
    }

    @Test
    void revokedSessionInvalidatesAccessTokenImmediately() throws Exception {
        String access = memberToken("member@basamak.local");
        JsonNode sessions = body(mvc.perform(get("/api/me/sessions").header("Authorization", bearer(access))).andExpect(status().isOk()).andReturn());
        String current = null;
        for (JsonNode s : sessions) if (s.get("current").asBoolean()) current = s.get("id").asText();
        assertThat(current).isNotNull();
        mvc.perform(delete("/api/me/sessions/" + current).header("Authorization", bearer(access))).andExpect(status().isOk());
        mvc.perform(get("/api/me").header("Authorization", bearer(access))).andExpect(status().isUnauthorized());
    }

    @Test
    void cmsRequiresStaffRoleAndTotp() throws Exception {
        // member token is not a CMS token
        String member = memberToken("member@basamak.local");
        mvc.perform(get("/api/cms/items?type=JOURNEY").header("Authorization", bearer(member))).andExpect(status().isForbidden());
        // members cannot even start the CMS login
        mvc.perform(jsonPost("/api/cms-auth/login", Map.of("email", "member@basamak.local", "password", PASSWORD)))
           .andExpect(status().isForbidden());
        // password step alone gives only an MFA-pending token, which is useless as a bearer token
        JsonNode step = body(mvc.perform(jsonPost("/api/cms-auth/login", Map.of("email", "editor2@basamak.local", "password", PASSWORD)))
                .andExpect(status().isOk()).andReturn());
        mvc.perform(get("/api/cms/items?type=JOURNEY").header("Authorization", bearer(step.get("mfaToken").asText())))
           .andExpect(status().isUnauthorized());
        // wrong TOTP rejected
        mvc.perform(jsonPost("/api/cms-auth/totp", Map.of("mfaToken", step.get("mfaToken").asText(), "code", "000000")))
           .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("bad_totp"));
        // correct TOTP -> CMS access
        String cms = cmsToken("editor2@basamak.local");
        mvc.perform(get("/api/cms/items?type=JOURNEY").header("Authorization", bearer(cms))).andExpect(status().isOk());
        // CMS token is not a member token
        mvc.perform(get("/api/me").header("Authorization", bearer(cms))).andExpect(status().isForbidden());
    }

    private void register(String email) throws Exception {
        mvc.perform(jsonPost("/api/auth/register", Map.of("email", email, "password", PASSWORD, "displayName", "Kilit Test", "consent", true)))
           .andExpect(status().isCreated());
        mvc.perform(jsonPost("/api/auth/verify-email", Map.of("token", tokenFromMail(lastMailTo(email))))).andExpect(status().isOk());
    }
}
