package com.basamak.workflow;

import com.basamak.common.ApiException;
import com.basamak.gamification.ActivityEvent;
import com.basamak.gamification.GamificationService;
import com.basamak.jobs.JobHandler;
import com.basamak.jobs.JobQueue;
import com.basamak.tool.ToolExecutor;
import com.basamak.tool.ToolRunService;
import com.basamak.tool.ToolSecrets;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

/**
 * Executes workflow runs (job kind WORKFLOW_RUN).
 * - Steps run in order; every step execution is stored with its input, output, duration, tokens and cost.
 * - A failing step fails the run and records failed_step_id; "resume" re-queues the run and the engine reuses the
 *   outputs of steps that already succeeded, so execution continues FROM the failed step (nothing done twice).
 * - Sub-flows run inline as child runs (depth limit 5, guards against cycles that appeared after saving).
 * - AI output is data only: it can feed later steps and conditions, but never selects tools, URLs or secrets
 *   (prompt-injection blast radius is limited to the text it produces).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowEngine implements JobHandler {
    public static final String KIND = "WORKFLOW_RUN";
    private static final int MAX_DEPTH = 5;
    private static final int MAX_ACTIVE_PER_USER = 5;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JobQueue queue;
    private final WorkflowService workflows;
    private final ToolRunService tools;
    private final ToolExecutor executor;
    private final ToolSecrets secrets;
    private final AiProvider ai;
    private final GamificationService gamification;
    private final com.basamak.notification.NotificationService notifications;

    // ------------------------------------------------------------------ start / resume

    @Transactional
    public UUID start(UUID userId, UUID workflowId, JsonNode rawInput, String trigger, String idempotencyKey) {
        WorkflowService.Wf w = workflows.get(workflowId);
        JsonNode input = workflows.validateInput(w.definition(), rawInput);
        Integer active = jdbc.queryForObject("SELECT count(*) FROM workflow_run WHERE user_id = ? AND status IN ('QUEUED','RUNNING') AND parent_run_id IS NULL",
                Integer.class, userId);
        if (active != null && active >= MAX_ACTIVE_PER_USER) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_runs", "Aynı anda en fazla " + MAX_ACTIVE_PER_USER + " iş akışı çalışabilir.");
        }
        UUID runId = UUID.randomUUID();
        UUID stored = queue.enqueue(KIND, runId, idempotencyKey == null ? "workflow-run:" + runId : idempotencyKey, 1);
        if (!stored.equals(runId)) return stored; // same webhook delivery / cron tick / double click -> the same run
        jdbc.update("""
                INSERT INTO workflow_run(id, workflow_id, workflow_version_no, user_id, trigger, input, status)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), 'QUEUED')""", runId, workflowId, w.versionNo(), userId, trigger, input.toString());
        return runId;
    }

    @Transactional
    public void resume(UUID userId, UUID runId) {
        Map<String, Object> r = jdbc.queryForMap("SELECT user_id, status, parent_run_id, resume_count FROM workflow_run WHERE id = ?", runId);
        if (!userId.equals(r.get("user_id")) || r.get("parent_run_id") != null) throw ApiException.notFound("Çalıştırma");
        if (!"FAILED".equals(r.get("status"))) throw ApiException.badRequest("not_failed", "Yalnız hata ile biten bir çalıştırma devam ettirilebilir.");
        int n = ((Number) r.get("resume_count")).intValue() + 1;
        jdbc.update("UPDATE workflow_run SET status = 'QUEUED', resume_count = ?, error = NULL, finished_at = NULL WHERE id = ?", n, runId);
        queue.enqueue(KIND, runId, "workflow-run:" + runId + ":resume:" + n, 1);
    }

    // ------------------------------------------------------------------ job handler

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public Decision handle(JobQueue.Claimed job) {
        Map<String, Object> run = jdbc.queryForMap("SELECT status FROM workflow_run WHERE id = ?", job.refId());
        if (!Set.of("QUEUED", "RUNNING").contains((String) run.get("status"))) return new Done();
        String result = execute(job.refId(), job.id(), 0);
        Map<String, Object> r = jdbc.queryForMap("SELECT r.user_id, r.workflow_id, r.resume_count, w.name, r.failed_step_id FROM workflow_run r JOIN workflow w ON w.id = r.workflow_id WHERE r.id = ?", job.refId());
        boolean ok = "SUCCEEDED".equals(result);
        notifications.notify((UUID) r.get("user_id"), "WORKFLOW_RUN", ok ? "İş akışı tamamlandı" : "İş akışı bir adımda durdu",
                r.get("name") + (ok ? " başarıyla bitti." : ": '" + r.get("failed_step_id") + "' adımı hata verdi. Kaldığı yerden devam ettirebilirsin."),
                "/workflows/" + r.get("workflow_id") + "?run=" + job.refId(), "workflow-run:" + job.refId() + ":" + r.get("resume_count"));
        return new Done();
    }

    /** Runs (or continues) one run synchronously. Returns its final status. */
    String execute(UUID runId, UUID jobId, int depth) {
        Map<String, Object> run = jdbc.queryForMap("SELECT workflow_id, workflow_version_no, user_id, input::text AS input FROM workflow_run WHERE id = ?", runId);
        UUID userId = (UUID) run.get("user_id");
        UUID workflowId = (UUID) run.get("workflow_id");
        jdbc.update("UPDATE workflow_run SET status = 'RUNNING', started_at = coalesce(started_at, now()) WHERE id = ?", runId);
        // a step left RUNNING by a crashed server is closed as failed; it runs again below
        jdbc.update("UPDATE workflow_step_run SET status = 'FAILED', error = 'Sunucu yeniden başladı', finished_at = now() WHERE run_id = ? AND status = 'RUNNING'", runId);
        WorkflowDefinition def;
        try {
            def = WorkflowDefinition.parse(workflows.definitionAt(workflowId, (Integer) run.get("workflow_version_no")));
        } catch (ApiException e) {
            return fail(runId, null, e.getMessage());
        }
        ObjectNode ctx = json.createObjectNode();
        ctx.set("input", read((String) run.get("input")));
        ObjectNode stepsCtx = ctx.putObject("steps");

        // resume: outputs of steps that already succeeded (latest execution per step) are reused, not re-run
        Map<String, String> previous = new HashMap<>();
        jdbc.query("""
                SELECT DISTINCT ON (step_id) step_id, status, output::text AS output FROM workflow_step_run
                 WHERE run_id = ? ORDER BY step_id, id DESC""", rs -> {
            previous.put(rs.getString("step_id"), rs.getString("status"));
            if ("SUCCEEDED".equals(rs.getString("status"))) stepsCtx.putObject(rs.getString("step_id")).set("output", read(rs.getString("output")));
        }, runId);

        Set<String> skipped = new HashSet<>();
        for (WorkflowDefinition.Step step : def.steps()) {
            if (jobId != null) queue.heartbeat(jobId, Duration.ofSeconds(180));
            if ("SUCCEEDED".equals(previous.get(step.id())) || "SKIPPED".equals(previous.get(step.id()))) {
                if (step.type().equals("condition") && stepsCtx.has(step.id())) skipOtherBranch(step, stepsCtx.path(step.id()).path("output"), skipped);
                continue;
            }
            if (skipped.contains(step.id())) {
                jdbc.update("INSERT INTO workflow_step_run(run_id, step_id, step_type, status, finished_at) VALUES (?, ?, ?, 'SKIPPED', now())", runId, step.id(), step.type());
                continue;
            }
            long t0 = System.nanoTime();
            JsonNode stepInput = stepInput(step, ctx);
            Long stepRowId = jdbc.queryForObject("""
                    INSERT INTO workflow_step_run(run_id, step_id, step_type, status, input) VALUES (?, ?, ?, 'RUNNING', CAST(? AS jsonb)) RETURNING id""",
                    Long.class, runId, step.id(), step.type(), stepInput.toString());
            try {
                StepResult r = switch (step.type()) {
                    case "tool" -> tool(userId, step, stepInput, jobId);
                    case "ai" -> aiStep(step, stepInput);
                    case "condition" -> condition(step, ctx);
                    case "workflow" -> subflow(userId, runId, step, stepInput, depth);
                    default -> throw new IllegalStateException("unknown step type");
                };
                long ms = (System.nanoTime() - t0) / 1_000_000;
                jdbc.update("""
                        UPDATE workflow_step_run SET status = 'SUCCEEDED', output = CAST(? AS jsonb), duration_ms = ?, tokens = ?, cost = ?, attempts = ?,
                               child_run_id = ?, finished_at = now() WHERE id = ?""",
                        r.output().toString(), ms, r.tokens(), r.cost(), r.attempts(), r.childRunId(), stepRowId);
                jdbc.update("UPDATE workflow_run SET total_tokens = total_tokens + ?, total_cost = total_cost + ? WHERE id = ?", r.tokens(), r.cost(), runId);
                stepsCtx.putObject(step.id()).set("output", r.output());
                if (step.type().equals("condition")) skipOtherBranch(step, r.output(), skipped);
            } catch (StepFailed | ApiException e) {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                int attempts = e instanceof StepFailed sf ? sf.attempts : 1;
                UUID child = e instanceof StepFailed sf ? sf.childRunId : null;
                jdbc.update("UPDATE workflow_step_run SET status = 'FAILED', error = ?, duration_ms = ?, attempts = ?, child_run_id = ?, finished_at = now() WHERE id = ?",
                        e.getMessage(), ms, attempts, child, stepRowId);
                return fail(runId, step.id(), "'" + step.id() + "' adımı başarısız: " + e.getMessage());
            } catch (RuntimeException e) {
                log.warn("workflow step {} crashed", step.id(), e);
                jdbc.update("UPDATE workflow_step_run SET status = 'FAILED', error = ?, finished_at = now() WHERE id = ?", "Beklenmeyen hata: " + e.getClass().getSimpleName(), stepRowId);
                return fail(runId, step.id(), "'" + step.id() + "' adımında beklenmeyen hata.");
            }
        }
        JsonNode output = def.output().isBlank()
                ? (def.steps().isEmpty() ? json.nullNode() : lastOutput(def, stepsCtx))
                : WorkflowDefinition.resolve(json.getNodeFactory().textNode(def.output()), ctx, json);
        jdbc.update("UPDATE workflow_run SET status = 'SUCCEEDED', output = CAST(? AS jsonb), failed_step_id = NULL, error = NULL, finished_at = now() WHERE id = ?",
                output.toString(), runId);
        if (depth == 0) {
            try {
                gamification.record(ActivityEvent.web(userId, "workflow-run:" + runId, "WORKFLOW_RUN_SUCCEEDED", "WORKFLOW", workflowId.toString(), null));
            } catch (Exception e) {
                log.warn("points for workflow run {} failed", runId, e);
            }
        }
        return "SUCCEEDED";
    }

    private JsonNode lastOutput(WorkflowDefinition def, ObjectNode stepsCtx) {
        for (int i = def.steps().size() - 1; i >= 0; i--) {
            JsonNode o = stepsCtx.path(def.steps().get(i).id()).path("output");
            if (!o.isMissingNode()) return o;
        }
        return json.nullNode();
    }

    private String fail(UUID runId, String stepId, String error) {
        jdbc.update("UPDATE workflow_run SET status = 'FAILED', failed_step_id = ?, error = ?, finished_at = now() WHERE id = ?", stepId, error, runId);
        return "FAILED";
    }

    private static void skipOtherBranch(WorkflowDefinition.Step step, JsonNode output, Set<String> skipped) {
        boolean result = output.path("result").asBoolean(false);
        skipped.addAll(step.list(result ? "else" : "then"));
    }

    // ------------------------------------------------------------------ step types

    record StepResult(JsonNode output, int tokens, BigDecimal cost, int attempts, UUID childRunId) {}

    static final class StepFailed extends RuntimeException {
        final int attempts;
        final UUID childRunId;

        StepFailed(String msg, int attempts, UUID childRunId) {
            super(msg);
            this.attempts = attempts;
            this.childRunId = childRunId;
        }
    }

    private JsonNode stepInput(WorkflowDefinition.Step step, JsonNode ctx) {
        return switch (step.type()) {
            case "ai" -> json.createObjectNode().put("prompt", WorkflowDefinition.interpolate(step.raw().path("prompt").asText(), ctx))
                    .put("model", step.raw().path("model").asText("mock-small"));
            case "condition" -> json.createObjectNode().set("left", WorkflowDefinition.resolve(step.raw().path("left"), ctx, json));
            default -> WorkflowDefinition.resolve(step.raw().path("input"), ctx, json);
        };
    }

    private StepResult tool(UUID userId, WorkflowDefinition.Step step, JsonNode input, UUID jobId) {
        JsonNode t = step.raw().path("tool");
        UUID toolId = UUID.fromString(t.path("id").asText());
        int v = t.path("versionNo").asInt();
        boolean content = "CONTENT".equals(t.path("scope").asText());
        ToolRunService.Resolved r = content ? tools.contentTool(toolId, v) : tools.ownedUserToolVersion(userId, toolId, v);
        JsonNode validated = r.def().validateInput(input, json);
        Map<String, String> sec = secrets.decryptAll(r.scope(), toolId);
        int max = r.def().retries() + 1;
        ToolExecutor.Result res = null;
        for (int attempt = 1; attempt <= max; attempt++) {
            if (jobId != null) queue.heartbeat(jobId, Duration.ofSeconds(180));
            res = executor.execute("wf:" + UUID.randomUUID(), r.def(), validated, sec, content);
            if (res.outcome() == ToolExecutor.Outcome.SUCCEEDED) return new StepResult(res.output() == null ? json.nullNode() : res.output(), 0, BigDecimal.ZERO, attempt, null);
            if (!res.retryable() || attempt == max) throw new StepFailed(res.error(), attempt, null);
            sleep(1000L << (attempt - 1));
        }
        throw new StepFailed(res == null ? "araç çalışmadı" : res.error(), max, null);
    }

    private StepResult aiStep(WorkflowDefinition.Step step, JsonNode input) {
        try {
            AiProvider.Result r = ai.complete(input.path("prompt").asText(), input.path("model").asText());
            ObjectNode out = json.createObjectNode().put("text", r.text()).put("model", r.model());
            return new StepResult(out, r.promptTokens() + r.completionTokens(), r.costUsd(), 1, null);
        } catch (IllegalStateException e) {
            throw new StepFailed(e.getMessage(), 1, null);
        }
    }

    private StepResult condition(WorkflowDefinition.Step step, JsonNode ctx) {
        JsonNode left = WorkflowDefinition.resolve(step.raw().path("left"), ctx, json);
        String right = WorkflowDefinition.interpolate(step.raw().path("right").asText(""), ctx);
        boolean result = WorkflowDefinition.evaluate(step.raw().path("op").asText(), left, right);
        ObjectNode out = json.createObjectNode().put("result", result).put("branch", result ? "then" : "else");
        out.set("left", left);
        return new StepResult(out, 0, BigDecimal.ZERO, 1, null);
    }

    private StepResult subflow(UUID userId, UUID parentRunId, WorkflowDefinition.Step step, JsonNode input, int depth) {
        if (depth + 1 >= MAX_DEPTH) throw new StepFailed("Alt akış derinliği " + MAX_DEPTH + " sınırını aştı (döngü olabilir).", 1, null);
        UUID subId = UUID.fromString(step.raw().path("workflowId").asText());
        WorkflowService.Wf sub = workflows.visible(userId, subId); // still own or PUBLIC at run time
        JsonNode subInput = workflows.validateInput(sub.definition(), input);
        UUID childId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO workflow_run(id, workflow_id, workflow_version_no, user_id, parent_run_id, parent_step_id, trigger, input, status)
                VALUES (?, ?, ?, ?, ?, ?, 'SUBFLOW', CAST(? AS jsonb), 'QUEUED')""",
                childId, subId, sub.versionNo(), userId, parentRunId, step.id(), subInput.toString());
        String status = execute(childId, null, depth + 1);
        Map<String, Object> child = jdbc.queryForMap("SELECT output::text AS output, error, total_tokens, total_cost FROM workflow_run WHERE id = ?", childId);
        if (!"SUCCEEDED".equals(status)) throw new StepFailed("Alt akış başarısız: " + child.get("error"), 1, childId);
        if (!sub.ownerId().equals(userId)) {
            gamification.record(ActivityEvent.web(sub.ownerId(), "shared-used:" + childId, "SHARED_ITEM_USED", "WORKFLOW", subId.toString(), sub.versionNo()));
        }
        return new StepResult(read((String) child.get("output")), ((Number) child.get("total_tokens")).intValue(), (BigDecimal) child.get("total_cost"), 1, childId);
    }

    private JsonNode read(String s) {
        try {
            return s == null ? json.nullNode() : json.readTree(s);
        } catch (java.io.IOException e) {
            return json.getNodeFactory().textNode(s);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
