package com.basamak.auth;

import com.basamak.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Lost authenticator: an admin resets another staff member's TOTP; the next CMS login shows the setup step again. */
class CmsTeamIT extends IntegrationTest {

    @Test
    void adminResetsAnotherStaffMembersTotp() throws Exception {
        String admin = cmsToken("admin@basamak.local");
        String editor = cmsToken("editor2@basamak.local"); // enrols TOTP
        JsonNode team = getJson("/api/cms/team", admin);
        String editorId = null, adminId = null;
        for (JsonNode u : team) {
            if (u.get("email").asText().equals("editor2@basamak.local")) editorId = u.get("id").asText();
            if (u.get("email").asText().equals("admin@basamak.local")) adminId = u.get("id").asText();
        }
        mvc.perform(get("/api/cms/team").header("Authorization", bearer(editor))).andExpect(status().isForbidden());
        mvc.perform(post("/api/cms/team/" + adminId + "/totp-reset").header("Authorization", bearer(admin))).andExpect(status().isBadRequest());

        postJson("/api/cms/team/" + editorId + "/totp-reset", null, admin);
        JsonNode step = body(mvc.perform(jsonPost("/api/cms-auth/login", Map.of("email", "editor2@basamak.local", "password", PASSWORD))).andReturn());
        assertThat(step.get("setupRequired").asBoolean()).isTrue();
        assertThat(step.get("otpauthUri").asText()).startsWith("otpauth://totp/");
        cmsToken("editor2@basamak.local"); // enrols again with the new secret
    }
}
