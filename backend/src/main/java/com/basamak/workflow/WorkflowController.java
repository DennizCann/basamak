package com.basamak.workflow;

import com.basamak.auth.AuthPrincipal;
import com.basamak.common.ApiException;
import com.basamak.common.CryptoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Member API for workflows, runs (with per-step detail), sharing, forking and triggers; plus the public webhook. */
@RestController
@RequiredArgsConstructor
public class WorkflowController {
    private final WorkflowService workflows;
    private final WorkflowEngine engine;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public record SaveReq(String name, String description, JsonNode definition) {}

    public record RunReq(JsonNode input, String clientRequestId) {}

    public record CronReq(String cron, JsonNode input) {}

    @GetMapping("/api/workflows")
    public List<WorkflowService.Wf> mine(@AuthenticationPrincipal AuthPrincipal p) {
        return workflows.mine(p.userId());
    }

    @GetMapping("/api/community/workflows")
    public List<WorkflowService.Wf> community(@RequestParam(defaultValue = "30") int limit) {
        return workflows.community(limit);
    }

    @GetMapping("/api/workflows/{id}")
    public WorkflowService.Wf get(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        return workflows.visible(p.userId(), id);
    }

    @PostMapping("/api/workflows")
    public WorkflowService.Wf create(@AuthenticationPrincipal AuthPrincipal p, @RequestBody SaveReq r) {
        return workflows.create(p.userId(), r.name(), r.description(), r.definition());
    }

    @PutMapping("/api/workflows/{id}")
    public WorkflowService.Wf update(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id, @RequestBody SaveReq r) {
        return workflows.update(p.userId(), id, r.name(), r.description(), r.definition());
    }

    @DeleteMapping("/api/workflows/{id}")
    public Map<String, Object> delete(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        workflows.delete(p.userId(), id);
        return Map.of("deleted", true);
    }

    @PostMapping("/api/workflows/{id}/share")
    public WorkflowService.Wf share(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id, @RequestBody Map<String, Boolean> r) {
        return workflows.setVisibility(p.userId(), id, Boolean.TRUE.equals(r.get("shared")));
    }

    @PostMapping("/api/workflows/{id}/fork")
    public WorkflowService.Wf fork(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        return workflows.fork(p.userId(), id);
    }

    @PostMapping("/api/workflows/{id}/runs")
    public Map<String, Object> run(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id, @RequestBody RunReq r) {
        workflows.owned(p.userId(), id);
        String key = r.clientRequestId() == null || r.clientRequestId().isBlank() ? null
                : "workflow-manual:" + p.userId() + ":" + r.clientRequestId().substring(0, Math.min(80, r.clientRequestId().length()));
        return runView(p.userId(), engine.start(p.userId(), id, r.input(), "MANUAL", key));
    }

    @GetMapping("/api/workflows/{id}/runs")
    public List<Map<String, Object>> runs(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        workflows.owned(p.userId(), id);
        return jdbc.queryForList("""
                SELECT id, trigger, status, error, failed_step_id AS "failedStepId", total_tokens AS "totalTokens", total_cost AS "totalCost",
                       created_at AS "createdAt", finished_at AS "finishedAt", resume_count AS "resumeCount"
                  FROM workflow_run WHERE workflow_id = ? AND user_id = ? AND parent_run_id IS NULL ORDER BY created_at DESC LIMIT 20""", id, p.userId()).stream().map(WorkflowController::clean).toList();
    }

    @GetMapping("/api/workflow-runs/{runId}")
    public Map<String, Object> runDetail(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID runId) {
        return runView(p.userId(), runId);
    }

    @PostMapping("/api/workflow-runs/{runId}/resume")
    public Map<String, Object> resume(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID runId) {
        engine.resume(p.userId(), runId);
        return runView(p.userId(), runId);
    }

    public record EventReq(String event) {}

    @PutMapping("/api/workflows/{id}/event-trigger")
    public WorkflowService.Wf eventTrigger(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id, @RequestBody EventReq r) {
        return workflows.setEventTrigger(p.userId(), id, r.event());
    }

    @GetMapping("/api/workflow-event-triggers")
    public Map<String, String> eventTriggers() {
        return WorkflowService.EVENT_TRIGGERS;
    }

    @GetMapping("/api/me/ai-budget")
    public Map<String, Object> aiBudget(@AuthenticationPrincipal AuthPrincipal p) {
        return engine.budget(p.userId());
    }

    @PutMapping("/api/workflows/{id}/cron")
    public WorkflowService.Wf cron(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id, @RequestBody CronReq r) {
        return workflows.setCron(p.userId(), id, r.cron(), r.input());
    }

    /** The token is shown once; store it in the calling system. */
    @PostMapping("/api/workflows/{id}/webhook")
    public Map<String, Object> webhook(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        String token = workflows.rotateWebhook(p.userId(), id);
        return Map.of("token", token, "path", "/api/hooks/workflows/" + token);
    }

    @DeleteMapping("/api/workflows/{id}/webhook")
    public WorkflowService.Wf disableWebhook(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        return workflows.disableWebhook(p.userId(), id);
    }

    /**
     * Public webhook (scenario 9). Idempotent: the same Idempotency-Key header (or, without it, the same body) starts
     * the run only once; a repeated delivery gets 200 with the existing run instead of 202.
     */
    @PostMapping("/api/hooks/workflows/{token}")
    public ResponseEntity<Map<String, Object>> hook(@PathVariable String token, @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                                    @RequestBody(required = false) JsonNode body) {
        WorkflowService.Wf w = workflows.byWebhookToken(token).orElseThrow(() -> ApiException.notFound("Webhook"));
        JsonNode input = body == null ? json.createObjectNode() : body;
        String k = key != null && !key.isBlank() ? key.trim() : "body:" + CryptoService.sha256(input.toString());
        if (k.length() > 120) throw ApiException.badRequest("invalid_key", "Idempotency-Key en fazla 120 karakter olabilir.");
        String jobKey = "webhook:" + w.id() + ":" + k;
        boolean existed = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM job WHERE idempotency_key = ?)", Boolean.class, jobKey));
        UUID runId = engine.start(w.ownerId(), w.id(), input, "WEBHOOK", jobKey);
        return ResponseEntity.status(existed ? HttpStatus.OK : HttpStatus.ACCEPTED).body(Map.of("runId", runId, "duplicate", existed));
    }

    private Map<String, Object> runView(UUID userId, UUID runId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.id, r.workflow_id AS "workflowId", w.name AS "workflowName", r.workflow_version_no AS "workflowVersionNo", r.user_id,
                       r.trigger, r.status, r.input::text AS input, r.output::text AS output, r.error, r.failed_step_id AS "failedStepId",
                       r.total_tokens AS "totalTokens", r.total_cost AS "totalCost", r.resume_count AS "resumeCount",
                       r.created_at AS "createdAt", r.started_at AS "startedAt", r.finished_at AS "finishedAt", r.parent_run_id AS "parentRunId"
                  FROM workflow_run r JOIN workflow w ON w.id = r.workflow_id WHERE r.id = ?""", runId);
        if (rows.isEmpty() || !userId.equals(rows.get(0).get("user_id"))) throw ApiException.notFound("Çalıştırma");
        Map<String, Object> run = clean(rows.get(0));
        run.remove("user_id");
        run.put("input", parse(run.get("input")));
        run.put("output", parse(run.get("output")));
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Map<String, Object> s : jdbc.queryForList("""
                SELECT id, step_id AS "stepId", step_type AS "type", status, input::text AS input, output::text AS output, error, attempts,
                       duration_ms AS "durationMs", tokens, cost, child_run_id AS "childRunId", started_at AS "startedAt", finished_at AS "finishedAt"
                  FROM workflow_step_run WHERE run_id = ? ORDER BY id""", runId)) {
            Map<String, Object> m = clean(s);
            m.put("input", parse(m.get("input")));
            m.put("output", parse(m.get("output")));
            steps.add(m);
        }
        run.put("steps", steps);
        return run;
    }

    /** JDBC timestamps -> Instant (ISO-8601 in JSON). */
    static Map<String, Object> clean(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        row.forEach((k, v) -> m.put(k, v instanceof java.sql.Timestamp t ? t.toInstant() : v));
        return m;
    }

    private Object parse(Object s) {
        if (s == null) return null;
        try {
            return json.readTree((String) s);
        } catch (Exception e) {
            return s;
        }
    }
}
