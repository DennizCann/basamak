package com.basamak.jobs;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Durable job queue on PostgreSQL (ADR-2).
 * - enqueue is idempotent (UNIQUE idempotency_key): the same logical job is never queued twice.
 * - claim uses FOR UPDATE SKIP LOCKED, so N workers (threads or servers) never take the same job.
 * - a claimed job holds a lease (locked_until); if the server dies, the lease expires and another worker
 *   takes the job again - nothing is lost on restart.
 */
@Service
@RequiredArgsConstructor
public class JobQueue {
    private final JdbcTemplate jdbc;

    public record Claimed(UUID id, String kind, UUID refId, int attempts, int maxAttempts, String requestId) {}

    /** Returns the ref_id of the job stored under this key (the new one, or the one queued earlier). */
    public UUID enqueue(String kind, UUID refId, String idempotencyKey, int maxAttempts) {
        int n = jdbc.update("""
                INSERT INTO job(kind, ref_id, idempotency_key, max_attempts, request_id) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING""", kind, refId, idempotencyKey, maxAttempts, org.slf4j.MDC.get("requestId"));
        if (n == 1) return refId;
        return jdbc.queryForObject("SELECT ref_id FROM job WHERE idempotency_key = ?", UUID.class, idempotencyKey);
    }

    public List<Claimed> claim(String workerId, int limit, Duration lease) {
        if (limit <= 0) return List.of();
        return jdbc.query("""
                UPDATE job SET status = 'RUNNING', attempts = attempts + 1, locked_by = ?, updated_at = now(),
                       locked_until = now() + make_interval(secs => ?)
                 WHERE id IN (SELECT id FROM job
                               WHERE (status = 'QUEUED' AND run_after <= now())
                                  OR (status = 'RUNNING' AND locked_until < now())
                               ORDER BY run_after LIMIT ? FOR UPDATE SKIP LOCKED)
                RETURNING id, kind, ref_id, attempts, max_attempts, request_id""",
                (rs, i) -> new Claimed(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getObject("ref_id", UUID.class),
                        rs.getInt("attempts"), rs.getInt("max_attempts"), rs.getString("request_id")),
                workerId, (double) lease.toSeconds(), limit);
    }

    /** Long jobs extend their lease while they make progress, so no other worker takes them over. */
    public void heartbeat(UUID id, Duration lease) {
        jdbc.update("UPDATE job SET locked_until = now() + make_interval(secs => ?) WHERE id = ? AND status = 'RUNNING'", (double) lease.toSeconds(), id);
    }

    public void done(UUID id) {
        jdbc.update("UPDATE job SET status = 'DONE', locked_until = NULL, updated_at = now() WHERE id = ?", id);
    }

    public void retry(UUID id, Duration delay, String error) {
        jdbc.update("""
                UPDATE job SET status = 'QUEUED', locked_until = NULL, last_error = ?, updated_at = now(),
                       run_after = now() + make_interval(secs => ?) WHERE id = ?""", error, (double) delay.toMillis() / 1000.0, id);
    }

    public void dead(UUID id, String error) {
        jdbc.update("UPDATE job SET status = 'DEAD', locked_until = NULL, last_error = ?, updated_at = now() WHERE id = ?", error, id);
    }

    /** Takes a job that is still waiting (QUEUED) off the queue; false if a worker already holds it. */
    public boolean cancelQueued(String kind, UUID refId) {
        return jdbc.update("UPDATE job SET status = 'DONE', last_error = 'cancelled', updated_at = now() WHERE kind = ? AND ref_id = ? AND status = 'QUEUED'",
                kind, refId) > 0;
    }
}
