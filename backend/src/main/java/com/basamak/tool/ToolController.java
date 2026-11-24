package com.basamak.tool;

import com.basamak.auth.AuthPrincipal;
import com.basamak.common.ApiException;
import com.basamak.content.PublishedContent;
import com.basamak.content.TrainingSchema;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

/** Member API: run tools (inside a training or own tools), follow runs live (SSE), cancel, manage own tools. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ToolController {
    private final ToolRunService runs;
    private final RunEvents events;
    private final UserToolService userTools;
    private final PublishedContent published;
    private final JdbcTemplate jdbc;
    private final ToolFiles toolFiles;

    public record RunReq(UUID trainingId, String itemKey, UUID userToolId, JsonNode input, String clientRequestId) {}

    public record ToolReq(@NotBlank String name, JsonNode definition, Map<String, String> secrets) {}

    /** What the member needs to show the run form of a training's tool item (no URL, headers or secrets). */
    @GetMapping("/trainings/{trainingId}/tools/{itemKey}")
    public Map<String, Object> trainingTool(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID trainingId, @PathVariable String itemKey) {
        Integer v = jdbc.query("SELECT training_version_no FROM enrollment WHERE user_id = ? AND training_id = ?",
                rs -> rs.next() ? rs.getInt(1) : null, p.userId(), trainingId);
        if (v == null) throw ApiException.badRequest("not_enrolled", "Önce eğitime başlayın.");
        JsonNode item = TrainingSchema.findItem(published.pinned(trainingId, v).orElseThrow(() -> ApiException.notFound("Eğitim")).body(), itemKey);
        if (item == null || !"tool".equals(item.path("type").asText())) throw ApiException.notFound("Araç ögesi");
        var r = runs.contentTool(UUID.fromString(item.path("toolId").asText()), item.path("toolVersionNo").asInt());
        return Map.of("name", r.name(), "versionNo", r.versionNo(), "description", r.def().description(),
                "inputs", r.def().inputs(), "timeoutSeconds", r.def().timeoutSeconds(), "retries", r.def().retries());
    }

    /** Published platform (CMS) tools a member may use in workflows: name, version and inputs only. */
    @GetMapping("/tools")
    public List<Map<String, Object>> platformTools() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var p : published.list(com.basamak.content.ContentType.TOOL)) {
            try {
                var d = ToolDefinition.parse(p.body());
                out.add(Map.of("id", p.itemId(), "name", p.title(), "versionNo", p.versionNo(), "description", d.description(), "inputs", d.inputs()));
            } catch (ApiException ignored) { /* an invalid old definition is simply not offered */ }
        }
        return out;
    }

    @PostMapping("/tool-runs")
    public Map<String, Object> start(@AuthenticationPrincipal AuthPrincipal p, @RequestBody RunReq r) {
        if (r.userToolId() != null) return runs.startUserTool(p.userId(), r.userToolId(), r.input(), r.clientRequestId());
        if (r.trainingId() == null || r.itemKey() == null) throw ApiException.badRequest("invalid_run", "trainingId ve itemKey ya da userToolId gerekli.");
        return runs.startFromTraining(p.userId(), r.trainingId(), r.itemKey(), r.input(), r.clientRequestId());
    }

    @GetMapping("/tool-runs")
    public List<Map<String, Object>> recent(@AuthenticationPrincipal AuthPrincipal p, @RequestParam(defaultValue = "20") int limit) {
        return runs.recent(p.userId(), limit);
    }

    /** File output of a run (owner only). Served as a download with its detected type; never rendered inline. */
    @GetMapping("/tool-runs/{id}/file")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> file(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        Map<String, Object> run = runs.run(p.userId(), id);
        JsonNode f = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(run.get("output")).path("file");
        var path = toolFiles.find(f.path("fileId").asText(null))
                .orElseThrow(() -> ApiException.notFound("Dosya (7 günlük saklama süresi dolmuş olabilir)"));
        MediaType type;
        try { type = MediaType.parseMediaType(f.path("contentType").asText("application/octet-stream")); } catch (Exception e) { type = MediaType.APPLICATION_OCTET_STREAM; }
        return org.springframework.http.ResponseEntity.ok().contentType(type)
                .header("Content-Disposition", org.springframework.http.ContentDisposition.attachment()
                        .filename(f.path("name").asText("cikti.bin"), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new org.springframework.core.io.FileSystemResource(path));
    }

    @GetMapping("/tool-runs/quota")
    public Map<String, Object> quota(@AuthenticationPrincipal AuthPrincipal p) {
        return runs.quota(p.userId());
    }

    @GetMapping("/tool-runs/{id}")
    public Map<String, Object> get(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        return runs.run(p.userId(), id);
    }

    @PostMapping("/tool-runs/{id}/cancel")
    public Map<String, Object> cancel(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        return runs.cancel(p.userId(), id);
    }

    /** Live status. Reconnect with Last-Event-ID (header) or ?after= to receive only what was missed. */
    @GetMapping(path = "/tool-runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id,
                             @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
                             @RequestParam(name = "after", required = false) Long after) {
        runs.run(p.userId(), id); // ownership
        long from = after != null ? after : parse(lastEventId);
        return events.subscribe(id, from, runs.isFinished(id));
    }

    private static long parse(String v) {
        try {
            return v == null ? 0 : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---- own tools

    @GetMapping("/my-tools")
    public List<Map<String, Object>> myTools(@AuthenticationPrincipal AuthPrincipal p) {
        return userTools.list(p.userId());
    }

    @GetMapping("/my-tools/{id}")
    public Map<String, Object> myTool(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        return userTools.get(p.userId(), id);
    }

    @PostMapping("/my-tools")
    public Map<String, Object> create(@AuthenticationPrincipal AuthPrincipal p, @RequestBody ToolReq r) {
        return userTools.create(p.userId(), r.name(), r.definition(), r.secrets());
    }

    @PutMapping("/my-tools/{id}")
    public Map<String, Object> update(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id, @RequestBody ToolReq r) {
        return userTools.update(p.userId(), id, r.name(), r.definition(), r.secrets());
    }

    @PostMapping("/my-tools/{id}/share")
    public Map<String, Object> share(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id, @RequestBody Map<String, Boolean> body) {
        return userTools.share(p.userId(), id, Boolean.TRUE.equals(body.get("shared")));
    }

    @GetMapping("/community/tools")
    public List<Map<String, Object>> communityTools(@RequestParam(defaultValue = "50") int limit) {
        return userTools.community(limit);
    }

    @PostMapping("/community/tools/{id}/fork")
    public Map<String, Object> forkTool(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        return userTools.fork(p.userId(), id);
    }

    @DeleteMapping("/my-tools/{id}")
    public Map<String, Object> delete(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        userTools.delete(p.userId(), id);
        return Map.of("deleted", true);
    }
}
