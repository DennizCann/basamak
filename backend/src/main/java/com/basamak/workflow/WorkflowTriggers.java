package com.basamak.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.UUID;

/**
 * Cron trigger (scenario 9). Due workflows are locked with SKIP LOCKED, so with several servers each tick starts
 * exactly one run; the job idempotency key "cron:{workflow}:{scheduled time}" makes even a retried tick a no-op.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowTriggers {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final WorkflowEngine engine;
    private final TransactionTemplate tx;

    /**
     * Platform-event trigger: after the member's learning event is committed, start their workflows subscribed to it.
     * Idempotent per (event, workflow): a replayed or synced-again event never starts a second run.
     */
    @org.springframework.transaction.event.TransactionalEventListener(fallbackExecution = true)
    public void onActivity(com.basamak.gamification.GamificationService.ActivityRecorded e) {
        if (!WorkflowService.EVENT_TRIGGERS.containsKey(e.action())) return;
        jdbc.query("""
                SELECT id FROM workflow WHERE owner_id = ? AND event_trigger = ? AND deleted_at IS NULL""", rs -> {
            UUID id = rs.getObject("id", UUID.class);
            var input = json.createObjectNode().put("eventType", e.action()).put("objectType", e.objectType())
                    .put("objectId", e.objectId()).put("occurredAt", String.valueOf(e.occurredAt()));
            try {
                tx.executeWithoutResult(s -> engine.start(e.userId(), id, filterInputs(id, input), "EVENT", "event:" + e.eventKey() + ":" + id));
            } catch (Exception ex) {
                log.warn("event run of workflow {} not started: {}", id, ex.getMessage());
            }
        }, e.userId(), e.action());
    }

    /** Only the event fields the workflow declares as inputs are passed (unknown inputs are rejected by validation). */
    private com.fasterxml.jackson.databind.JsonNode filterInputs(UUID workflowId, com.fasterxml.jackson.databind.node.ObjectNode all) {
        String def = jdbc.queryForObject("SELECT definition::text FROM workflow WHERE id = ?", String.class, workflowId);
        var out = json.createObjectNode();
        try {
            for (var in : json.readTree(def).path("inputs")) {
                String n = in.path("name").asText();
                if (all.has(n)) out.set(n, all.get(n));
            }
        } catch (Exception ignored) { /* invalid definition: engine reports it */ }
        return out;
    }

    @Scheduled(fixedDelayString = "${basamak.workflows.cron-poll-ms:15000}", initialDelay = 5000)
    public void fireDue() {
        tx.executeWithoutResult(s -> jdbc.query("""
                SELECT w.id, w.owner_id, w.cron, w.cron_input::text AS input, w.next_run_at FROM workflow w JOIN users u ON u.id = w.owner_id
                 WHERE w.cron IS NOT NULL AND w.next_run_at <= now() AND w.deleted_at IS NULL AND u.status = 'ACTIVE'
                 ORDER BY w.next_run_at LIMIT 20 FOR UPDATE OF w SKIP LOCKED""", rs -> {
            UUID id = rs.getObject("id", UUID.class);
            Timestamp due = rs.getTimestamp("next_run_at");
            try {
                engine.start(rs.getObject("owner_id", UUID.class), id, json.readTree(rs.getString("input")), "CRON",
                        "cron:" + id + ":" + due.toInstant().getEpochSecond());
            } catch (Exception e) {
                log.warn("cron run of workflow {} not started: {}", id, e.getMessage()); // e.g. too many active runs: next tick
            }
            jdbc.update("UPDATE workflow SET next_run_at = ? WHERE id = ?",
                    Timestamp.from(WorkflowService.nextCron(rs.getString("cron")).toInstant()), id);
        }));
    }
}
