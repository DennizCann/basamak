package com.basamak.tool;

import com.basamak.common.ApiException;
import com.basamak.content.PublishedContent;
import com.basamak.content.TrainingSchema;
import com.basamak.gamification.ActivityEvent;
import com.basamak.gamification.GamificationService;
import com.basamak.jobs.JobHandler;
import com.basamak.jobs.JobQueue;
import com.basamak.learning.LearningService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.*;

/**
 * Member-facing tool runs: queue a run, follow it, cancel it. Also the TOOL_RUN job handler that performs the
 * attempts with timeout + retry (exponential backoff 1 s, 2 s, 4 s) and publishes every status change.
 * Status: QUEUED -> RUNNING -> (RETRYING -> RUNNING)* -> SUCCEEDED | FAILED | TIMED_OUT | CANCELLED
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolRunService implements JobHandler {
    public static final String KIND = "TOOL_RUN";
    private static final int MAX_ACTIVE_PER_USER = 5;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JobQueue queue;
    private final RunEvents events;
    private final ToolExecutor executor;
    private final ToolSecrets secrets;
    private final PublishedContent published;
    private final GamificationService gamification;
    private final LearningService learning;

    public record Resolved(ToolSecrets.Scope scope, UUID toolId, int versionNo, String name, ToolDefinition def) {}

    // ------------------------------------------------------------------ start / read / cancel

    /** A tool item inside a training: the tool version is the one pinned in the member's enrolled training version. */
    @Transactional
    public Map<String, Object> startFromTraining(UUID userId, UUID trainingId, String itemKey, JsonNode input, String clientRequestId) {
        Integer trainingVersion = jdbc.query("SELECT training_version_no FROM enrollment WHERE user_id = ? AND training_id = ?",
                rs -> rs.next() ? rs.getInt(1) : null, userId, trainingId);
        if (trainingVersion == null) throw ApiException.badRequest("not_enrolled", "Aracı çalıştırmak için önce eğitime başlayın.");
        JsonNode body = published.pinned(trainingId, trainingVersion).orElseThrow(() -> ApiException.notFound("Eğitim")).body();
        JsonNode item = TrainingSchema.findItem(body, itemKey);
        if (item == null || !"tool".equals(item.path("type").asText())) throw ApiException.notFound("Araç ögesi");
        Resolved r = contentTool(UUID.fromString(item.path("toolId").asText()), item.path("toolVersionNo").asInt());
        return start(userId, r, trainingId, itemKey, input, clientRequestId);
    }

    @Transactional
    public Map<String, Object> startUserTool(UUID userId, UUID toolId, JsonNode input, String clientRequestId) {
        return start(userId, userTool(userId, toolId), null, null, input, clientRequestId);
    }

    private Map<String, Object> start(UUID userId, Resolved r, UUID trainingId, String itemKey, JsonNode rawInput, String clientRequestId) {
        JsonNode input = r.def().validateInput(rawInput, json);
        Integer active = jdbc.queryForObject("SELECT count(*) FROM tool_run WHERE user_id = ? AND status IN ('QUEUED','RUNNING','RETRYING')", Integer.class, userId);
        if (active != null && active >= MAX_ACTIVE_PER_USER) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_runs", "Aynı anda en fazla " + MAX_ACTIVE_PER_USER + " çalıştırmanız olabilir. Birinin bitmesini bekleyin.");
        }
        UUID runId = UUID.randomUUID();
        // double click / network retry with the same clientRequestId -> the same run, not a second one
        String key = clientRequestId != null && !clientRequestId.isBlank()
                ? "tool-run:" + userId + ":" + clientRequestId.substring(0, Math.min(80, clientRequestId.length()))
                : "tool-run:" + runId;
        UUID stored = queue.enqueue(KIND, runId, key, r.def().retries() + 1);
        if (!stored.equals(runId)) return run(userId, stored);
        jdbc.update("""
                INSERT INTO tool_run(id, user_id, scope, tool_id, tool_version_no, tool_name, training_id, item_key, input, status, max_attempts, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), 'QUEUED', ?, ?)""",
                runId, userId, r.scope().name(), r.toolId(), r.versionNo(), r.name(), trainingId, itemKey, input.toString(),
                r.def().retries() + 1, MDC.get("requestId"));
        Map<String, Object> dto = dto(runId);
        events.append(runId, "queued", dto);
        return dto;
    }

    public Map<String, Object> run(UUID userId, UUID runId) {
        Map<String, Object> dto = dto(runId);
        if (!userId.equals(dto.get("userId"))) throw ApiException.notFound("Çalıştırma");
        return dto;
    }

    public List<Map<String, Object>> recent(UUID userId, int limit) {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM tool_run WHERE user_id = ? ORDER BY created_at DESC LIMIT ?", UUID.class, userId, Math.min(limit, 100));
        return ids.stream().map(this::dto).toList();
    }

    public boolean isFinished(UUID runId) {
        return RunEvents.TERMINAL.contains(String.valueOf(dto(runId).get("status")));
    }

    @Transactional
    public Map<String, Object> cancel(UUID userId, UUID runId) {
        Map<String, Object> dto = run(userId, runId);
        if (RunEvents.TERMINAL.contains(String.valueOf(dto.get("status")))) return dto;
        jdbc.update("UPDATE tool_run SET cancel_requested = true WHERE id = ?", runId);
        // waiting in the queue (first attempt or between retries): take it out and finish now
        if (queue.cancelQueued(KIND, runId)) return finish(runId, "CANCELLED", null, null, "Çalıştırma iptal edildi.", null, "cancelled");
        executor.cancel(runId.toString()); // running now: abort the HTTP request; the handler records CANCELLED
        return dto(runId);
    }

    // ------------------------------------------------------------------ job handler

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public Decision handle(JobQueue.Claimed job) {
        UUID runId = job.refId();
        Map<String, Object> run = dto(runId);
        String status = String.valueOf(run.get("status"));
        if (RunEvents.TERMINAL.contains(status)) return new Done();
        if (Boolean.TRUE.equals(run.get("cancelRequested"))) { finish(runId, "CANCELLED", null, null, "Çalıştırma iptal edildi.", null, "cancelled"); return new Done(); }
        if (job.attempts() > job.maxAttempts()) { // lease expired after the last attempt (e.g. server restart)
            finish(runId, "FAILED", null, null, "Çalıştırma tamamlanamadı (sunucu yeniden başladı).", null, "failed");
            return new Done();
        }
        UUID userId = (UUID) run.get("userId");
        Resolved r;
        try {
            r = "USER".equals(run.get("scope"))
                    ? userToolVersion((UUID) run.get("toolId"), (Integer) run.get("toolVersionNo"))
                    : contentTool((UUID) run.get("toolId"), (Integer) run.get("toolVersionNo"));
        } catch (ApiException e) {
            finish(runId, "FAILED", null, null, e.getMessage(), null, "failed");
            return new Done();
        }
        jdbc.update("UPDATE tool_run SET status = 'RUNNING', attempts = ?, started_at = coalesce(started_at, now()) WHERE id = ?", job.attempts(), runId);
        events.append(runId, "running", dto(runId));

        JsonNode input = json.valueToTree(run.get("input"));
        ToolExecutor.Result res = executor.execute(runId.toString(), r.def(), input, secrets.decryptAll(r.scope(), r.toolId()), r.scope() == ToolSecrets.Scope.CONTENT);

        if (res.outcome() == ToolExecutor.Outcome.SUCCEEDED) {
            finish(runId, "SUCCEEDED", res.httpStatus(), res.output(), null, res.durationMs(), "succeeded");
            rewardAndComplete(userId, runId, r, run);
            return new Done();
        }
        boolean cancelRequested = Boolean.TRUE.equals(jdbc.queryForObject("SELECT cancel_requested FROM tool_run WHERE id = ?", Boolean.class, runId));
        if (res.outcome() == ToolExecutor.Outcome.CANCELLED || cancelRequested) {
            finish(runId, "CANCELLED", res.httpStatus(), null, "Çalıştırma iptal edildi.", res.durationMs(), "cancelled");
            return new Done();
        }
        if (res.retryable() && job.attempts() < job.maxAttempts()) {
            Duration delay = Duration.ofSeconds(1L << (job.attempts() - 1)); // 1 s, 2 s, 4 s
            jdbc.update("UPDATE tool_run SET status = 'RETRYING', http_status = ?, error = ?, duration_ms = ? WHERE id = ?",
                    res.httpStatus(), res.error(), res.durationMs(), runId);
            Map<String, Object> dto = dto(runId);
            dto.put("nextAttemptInMs", delay.toMillis());
            events.append(runId, "retrying", dto);
            return new Retry(delay, res.error());
        }
        String finalStatus = res.outcome() == ToolExecutor.Outcome.TIMED_OUT ? "TIMED_OUT" : "FAILED";
        finish(runId, finalStatus, res.httpStatus(), res.output(), res.error(), res.durationMs(), finalStatus.toLowerCase(Locale.ROOT));
        return new Done();
    }

    private void rewardAndComplete(UUID userId, UUID runId, Resolved r, Map<String, Object> run) {
        try {
            gamification.record(ActivityEvent.web(userId, "tool-run:" + runId, "TOOL_RUN_SUCCEEDED", "TOOL", r.toolId().toString(), r.versionNo()));
            if (run.get("trainingId") != null) learning.completeToolItem(userId, (UUID) run.get("trainingId"), (String) run.get("itemKey"));
        } catch (Exception e) {
            log.warn("post-run bookkeeping failed for run {}", runId, e); // the run itself succeeded; do not fail it
        }
    }

    private Map<String, Object> finish(UUID runId, String status, Integer http, JsonNode output, String error, Long durationMs, String event) {
        jdbc.update("""
                UPDATE tool_run SET status = ?, http_status = coalesce(?, http_status), output = CAST(? AS jsonb), error = ?,
                       duration_ms = coalesce(?, duration_ms), finished_at = now() WHERE id = ?""",
                status, http, output == null ? null : output.toString(), error, durationMs, runId);
        Map<String, Object> dto = dto(runId);
        events.append(runId, event, dto);
        return dto;
    }

    // ------------------------------------------------------------------ definitions

    /** A CMS tool version that has been published at some point (pinned versions stay runnable). */
    public Resolved contentTool(UUID toolId, int versionNo) {
        var v = published.pinned(toolId, versionNo).orElseThrow(() -> ApiException.notFound("Araç sürümü"));
        return new Resolved(ToolSecrets.Scope.CONTENT, toolId, versionNo, v.title(), ToolDefinition.parse(v.body()));
    }

    private Resolved userTool(UUID owner, UUID toolId) {
        Map<String, Object> row = jdbc.query("SELECT owner_id, name, version_no FROM user_tool WHERE id = ? AND deleted_at IS NULL",
                rs -> rs.next() ? Map.<String, Object>of("owner", rs.getObject(1), "name", rs.getString(2), "v", rs.getInt(3)) : null, toolId);
        if (row == null || !owner.equals(row.get("owner"))) throw ApiException.notFound("Araç");
        return userToolVersion(toolId, (Integer) row.get("v"));
    }

    /** For workflow steps: a member tool version, only if it belongs to the given owner. */
    public Resolved ownedUserToolVersion(UUID owner, UUID toolId, int versionNo) {
        UUID o = jdbc.query("SELECT owner_id FROM user_tool WHERE id = ? AND deleted_at IS NULL", rs -> rs.next() ? rs.getObject(1, UUID.class) : null, toolId);
        if (o == null || !o.equals(owner)) throw ApiException.notFound("Araç");
        return userToolVersion(toolId, versionNo);
    }

    public int currentUserToolVersion(UUID owner, UUID toolId) {
        return (Integer) userTool(owner, toolId).versionNo();
    }

    private Resolved userToolVersion(UUID toolId, int versionNo) {
        return jdbc.query("""
                SELECT t.name, v.definition::text FROM user_tool t JOIN user_tool_version v ON v.tool_id = t.id
                 WHERE t.id = ? AND v.version_no = ?""", rs -> {
            if (!rs.next()) throw ApiException.notFound("Araç");
            try {
                return new Resolved(ToolSecrets.Scope.USER, toolId, versionNo, rs.getString(1), ToolDefinition.parse(json.readTree(rs.getString(2))));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }, toolId, versionNo);
    }

    // ------------------------------------------------------------------ dto

    Map<String, Object> dto(UUID runId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, user_id, scope, tool_id, tool_version_no, tool_name, training_id, item_key, input::text AS input, status,
                       attempts, max_attempts, cancel_requested, http_status, output::text AS output, error, duration_ms,
                       created_at, started_at, finished_at FROM tool_run WHERE id = ?""", runId);
        if (rows.isEmpty()) throw ApiException.notFound("Çalıştırma");
        Map<String, Object> r = rows.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.get("id"));
        out.put("userId", r.get("user_id"));
        out.put("scope", r.get("scope"));
        out.put("toolId", r.get("tool_id"));
        out.put("toolVersionNo", r.get("tool_version_no"));
        out.put("toolName", r.get("tool_name"));
        out.put("trainingId", r.get("training_id"));
        out.put("itemKey", r.get("item_key"));
        out.put("input", readJson((String) r.get("input")));
        out.put("status", r.get("status"));
        out.put("attempts", r.get("attempts"));
        out.put("maxAttempts", r.get("max_attempts"));
        out.put("cancelRequested", r.get("cancel_requested"));
        out.put("httpStatus", r.get("http_status"));
        out.put("output", readJson((String) r.get("output")));
        out.put("error", r.get("error"));
        out.put("durationMs", r.get("duration_ms"));
        out.put("createdAt", ts(r.get("created_at")));
        out.put("startedAt", ts(r.get("started_at")));
        out.put("finishedAt", ts(r.get("finished_at")));
        return out;
    }

    private static Object ts(Object o) {
        return o instanceof java.sql.Timestamp t ? t.toInstant() : o;
    }

    private Object readJson(String s) {
        if (s == null) return null;
        try {
            return json.readTree(s);
        } catch (java.io.IOException e) {
            return s;
        }
    }
}
