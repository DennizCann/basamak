package com.basamak.auth;

import com.basamak.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Member login second step: e-mailed one-time code (wrong, expired, reused, resend, limits, storage, delivery failure). */
class LoginCodeIT extends IntegrationTest {
    @Autowired JdbcTemplate jdbc;

    private String member() throws Exception {
        String email = uniqueEmail("code");
        mvc.perform(jsonPost("/api/auth/register", Map.of("email", email, "password", PASSWORD, "displayName", "Kod Test", "consent", true))).andExpect(status().isCreated());
        mvc.perform(jsonPost("/api/auth/verify-email", Map.of("token", tokenFromMail(lastMailTo(email))))).andExpect(status().isOk());
        return email;
    }

    private JsonNode passwordStep(String email) throws Exception {
        return body(mvc.perform(jsonPost("/api/auth/login", Map.of("email", email, "password", PASSWORD))).andExpect(status().isOk()).andReturn());
    }

    private String wrong(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    @Test
    void codeIsSingleUseAndStoredOnlyAsHash() throws Exception {
        String email = member();
        String challenge = passwordStep(email).get("challengeId").asText();
        String code = codeFromMail(lastMailTo(email));
        String stored = jdbc.queryForObject("SELECT code_hash FROM login_challenge WHERE id = ?::uuid", String.class, challenge);
        assertThat(stored).doesNotContain(code).hasSize(64);

        mvc.perform(jsonPost("/api/auth/login/verify", Map.of("challengeId", challenge, "code", code))).andExpect(status().isOk());
        mvc.perform(jsonPost("/api/auth/login/verify", Map.of("challengeId", challenge, "code", code)))
           .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("code_used"));
    }

    @Test
    void wrongCodesAreCountedAndTheFifthClosesTheChallenge() throws Exception {
        String email = member();
        String challenge = passwordStep(email).get("challengeId").asText();
        String code = codeFromMail(lastMailTo(email));
        mvc.perform(jsonPost("/api/auth/login/verify", Map.of("challengeId", challenge, "code", wrong(code))))
           .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("bad_code")).andExpect(jsonPath("$.attemptsLeft").value(4));
        for (int i = 0; i < 3; i++) mvc.perform(jsonPost("/api/auth/login/verify", Map.of("challengeId", challenge, "code", wrong(code))));
        mvc.perform(jsonPost("/api/auth/login/verify", Map.of("challengeId", challenge, "code", wrong(code))))
           .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("too_many_attempts"));
        // even the right code is refused now: the member must start again with the password
        mvc.perform(jsonPost("/api/auth/login/verify", Map.of("challengeId", challenge, "code", code)))
           .andExpect(status().isTooManyRequests());
    }

    @Test
    void expiredCodeIsRejectedAndResendGivesAWorkingNewCode() throws Exception {
        String email = member();
        String challenge = passwordStep(email).get("challengeId").asText();
        String old = codeFromMail(lastMailTo(email));
        jdbc.update("UPDATE login_challenge SET expires_at = now() - interval '1 second', last_sent_at = now() - interval '2 minutes' WHERE id = ?::uuid", challenge);
        mvc.perform(jsonPost("/api/auth/login/verify", Map.of("challengeId", challenge, "code", old)))
           .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("code_expired"));

        mvc.perform(jsonPost("/api/auth/login/resend", Map.of("challengeId", challenge))).andExpect(status().isOk());
        String fresh = codeFromMail(lastMailTo(email));
        if (!fresh.equals(old)) { // the previous code no longer works after a resend
            mvc.perform(jsonPost("/api/auth/login/verify", Map.of("challengeId", challenge, "code", old)))
               .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("bad_code"));
        }
        mvc.perform(jsonPost("/api/auth/login/verify", Map.of("challengeId", challenge, "code", fresh)))
           .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").exists());
    }

    @Test
    void resendHasACooldown() throws Exception {
        String email = member();
        JsonNode step = passwordStep(email);
        assertThat(step.get("resendAvailableAt").asText()).isNotBlank();
        mvc.perform(jsonPost("/api/auth/login/resend", Map.of("challengeId", step.get("challengeId").asText())))
           .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("resend_too_soon"))
           .andExpect(jsonPath("$.retryAfterSeconds").exists());
    }

    @Test
    void deliveryFailureIsReportedWithoutOpeningASession() throws Exception {
        String email = member();
        when(mail.send(eq(email), anyString(), anyString())).thenReturn(false);
        mvc.perform(jsonPost("/api/auth/login", Map.of("email", email, "password", PASSWORD)))
           .andExpect(status().isOk()).andExpect(jsonPath("$.delivered").value(false))
           .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("gönderilemedi")))
           .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    private jakarta.servlet.http.Cookie rememberedDevice(String email) throws Exception {
        String challenge = passwordStep(email).get("challengeId").asText();
        var r = mvc.perform(jsonPost("/api/auth/login/verify", Map.of("challengeId", challenge, "code", codeFromMail(lastMailTo(email)), "remember", true)))
                .andExpect(status().isOk()).andReturn();
        var c = r.getResponse().getCookie("bsm_dev");
        assertThat(c).isNotNull();
        assertThat(c.isHttpOnly()).isTrue();
        return c;
    }

    @Test
    void rememberedDeviceSkipsTheCodeButStillNeedsThePassword() throws Exception {
        String email = member();
        var device = rememberedDevice(email);
        mvc.perform(jsonPost("/api/auth/login", Map.of("email", email, "password", PASSWORD)).cookie(device))
           .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("authenticated")).andExpect(jsonPath("$.accessToken").exists());
        // wrong password is still refused on a remembered device
        mvc.perform(jsonPost("/api/auth/login", Map.of("email", email, "password", "yanlis-sifre-123")).cookie(device))
           .andExpect(status().isUnauthorized());
        // without the cookie (another browser) the code is required again
        mvc.perform(jsonPost("/api/auth/login", Map.of("email", email, "password", PASSWORD)))
           .andExpect(jsonPath("$.status").value("code_required"));
    }

    @Test
    void notRememberedByDefaultAndDeviceTokenIsBoundToItsAccount() throws Exception {
        String email = member();
        String challenge = passwordStep(email).get("challengeId").asText();
        var r = mvc.perform(jsonPost("/api/auth/login/verify", Map.of("challengeId", challenge, "code", codeFromMail(lastMailTo(email)))))
                .andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getCookie("bsm_dev")).isNull();

        var device = rememberedDevice(email);
        String other = member();
        mvc.perform(jsonPost("/api/auth/login", Map.of("email", other, "password", PASSWORD)).cookie(device))
           .andExpect(jsonPath("$.status").value("code_required")); // someone else's device token does not help
    }

    @Test
    void passwordResetForgetsRememberedDevices() throws Exception {
        String email = member();
        var device = rememberedDevice(email);
        mvc.perform(jsonPost("/api/auth/forgot-password", Map.of("email", email))).andExpect(status().isOk());
        mvc.perform(jsonPost("/api/auth/reset-password", Map.of("token", tokenFromMail(lastMailTo(email)), "password", PASSWORD))).andExpect(status().isOk());
        mvc.perform(jsonPost("/api/auth/login", Map.of("email", email, "password", PASSWORD)).cookie(device))
           .andExpect(jsonPath("$.status").value("code_required"));
    }

    @Test
    void mobileRememberedDeviceTokenTravelsInTheBody() throws Exception {
        String email = member();
        String challenge = body(mvc.perform(jsonPost("/api/auth/login", Map.of("email", email, "password", PASSWORD, "client", "mobile"))).andReturn()).get("challengeId").asText();
        JsonNode v = body(mvc.perform(jsonPost("/api/auth/login/verify", Map.of("challengeId", challenge, "code", codeFromMail(lastMailTo(email)), "remember", true)))
                .andExpect(status().isOk()).andReturn());
        String deviceToken = v.get("deviceToken").asText();
        mvc.perform(jsonPost("/api/auth/login", Map.of("email", email, "password", PASSWORD, "client", "mobile", "deviceToken", deviceToken)))
           .andExpect(jsonPath("$.status").value("authenticated")).andExpect(jsonPath("$.refreshToken").exists());
    }

    @Test
    void mobileGetsRefreshTokenInBodyAfterTheCode() throws Exception {
        String email = member();
        var r = loginWithCode(email, PASSWORD, "mobile");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r).get("refreshToken").asText()).isNotBlank();
    }
}
