package com.basamak.media;

import com.basamak.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Lesson media: byte-sniffed type checks, member access only after publication, signed CMS URLs, schema rules. */
class MediaIT extends IntegrationTest {
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R', 1, 2, 3};

    JsonNode upload(String token, String name, String type, byte[] bytes, int expect) throws Exception {
        var r = mvc.perform(multipart("/api/cms/media").file(new MockMultipartFile("file", name, type, bytes))
                .header("Authorization", bearer(token))).andExpect(status().is(expect)).andReturn();
        return body(r);
    }

    @Test
    void uploadIsTypeCheckedFromBytes_andHiddenFromMembersUntilPublished() throws Exception {
        String author = cmsToken("author@basamak.local"), editor = cmsToken("editor@basamak.local"), publisher = cmsToken("publisher@basamak.local");
        String member = newMember("media");

        // SVG (script inside an image) and a script renamed to .png are rejected; members cannot upload
        upload(author, "x.svg", "image/svg+xml", "<svg onload=alert(1)>".getBytes(StandardCharsets.UTF_8), 400);
        assertThat(upload(author, "evil.png", "image/png", "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8), 400)
                .get("code").asText()).isEqualTo("unsupported_media");
        mvc.perform(multipart("/api/cms/media").file(new MockMultipartFile("file", "a.png", "image/png", PNG))
                .header("Authorization", bearer(member))).andExpect(status().isForbidden());
        // an image over 5 MB is rejected even though the request limit is higher
        byte[] big = new byte[5 * 1024 * 1024 + 10];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        assertThat(upload(author, "big.png", "image/png", big, 400).get("code").asText()).isEqualTo("file_too_large");

        JsonNode img = upload(author, "diyagram.png", "application/octet-stream", PNG, 200);
        assertThat(img.get("kind").asText()).isEqualTo("IMAGE");
        assertThat(img.get("contentType").asText()).isEqualTo("image/png");
        String id = img.get("id").asText();
        JsonNode pdf = upload(author, "notlar.pdf", "application/pdf", "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.UTF_8), 200);

        // missing alt text and unknown media ids are rejected on save
        mvc.perform(jsonPost("/api/cms/items", Map.of("type", "TRAINING", "title", "Medya", "body", Map.of("items", List.of(
                Map.of("key", "a", "type", "lesson", "title", "A", "content", "![](media:" + id + ")"))))).header("Authorization", bearer(author)))
                .andExpect(status().isBadRequest());
        mvc.perform(jsonPost("/api/cms/items", Map.of("type", "TRAINING", "title", "Medya", "body", Map.of("items", List.of(
                Map.of("key", "a", "type", "lesson", "title", "A", "content", "![şema](media:00000000-0000-0000-0000-000000000000)"))))).header("Authorization", bearer(author)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unknown_media"));

        JsonNode t = body(mvc.perform(jsonPost("/api/cms/items", Map.of("type", "TRAINING", "title", "Medya", "body", Map.of("items", List.of(
                Map.of("key", "a", "type", "lesson", "title", "A", "content", "Metin\n\n![Akış şeması](media:" + id + ")",
                        "attachments", List.of(Map.of("mediaId", pdf.get("id").asText(), "name", "Ders notları")))))))
                .header("Authorization", bearer(author))).andExpect(status().isCreated()).andReturn());

        // draft: not readable without a signature; the CMS signed URL works
        mvc.perform(get("/api/public/media/" + id)).andExpect(status().isNotFound());
        String signed = body(mvc.perform(jsonPost("/api/cms/media/sign", Map.of("ids", List.of(id))).header("Authorization", bearer(author)))
                .andExpect(status().isOk()).andReturn()).get(id).asText();
        mvc.perform(get(signed)).andExpect(status().isOk()).andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get("/api/public/media/" + id + "?exp=9999999999&sig=forged")).andExpect(status().isNotFound());

        String v = t.at("/latest/id").asText();
        mvc.perform(post("/api/cms/versions/" + v + "/submit").header("Authorization", bearer(author))).andExpect(status().isOk());
        mvc.perform(post("/api/cms/versions/" + v + "/approve").header("Authorization", bearer(editor))).andExpect(status().isOk());
        mvc.perform(post("/api/cms/versions/" + v + "/publish").header("Authorization", bearer(publisher))).andExpect(status().isOk());

        // published: public read works, the PDF attachment is served as a download-safe type
        mvc.perform(get("/api/public/media/" + id)).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(get("/api/public/media/" + pdf.get("id").asText())).andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));
    }
}
