package com.basamak.jobs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Queue metrics on /actuator/metrics (request timing comes from Spring's http.server.requests):
 * <ul>
 *   <li>{@code basamak.jobs.queued} / {@code basamak.jobs.running}: queue length and work in progress (gauges,
 *       read from the job table at most every 10 s);</li>
 *   <li>{@code basamak.jobs.finished{kind,outcome}}: done / retry / dead per job kind, so the failure rate is
 *       finished{outcome=dead} / finished.</li>
 * </ul>
 */
@Component
public class JobMetrics {
    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;
    private final AtomicLong queued = new AtomicLong(), running = new AtomicLong();
    private volatile long readAt;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public JobMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
        Gauge.builder("basamak.jobs.queued", this, m -> { m.refresh(); return m.queued.get(); }).description("Jobs waiting in the queue").register(registry);
        Gauge.builder("basamak.jobs.running", this, m -> { m.refresh(); return m.running.get(); }).description("Jobs being executed").register(registry);
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        if (now - readAt < 10_000) return;
        readAt = now;
        try {
            jdbc.query("SELECT status, count(*) FROM job WHERE status IN ('QUEUED','RUNNING') GROUP BY status", rs -> {
                if ("QUEUED".equals(rs.getString(1))) queued.set(rs.getLong(2)); else running.set(rs.getLong(2));
            });
        } catch (Exception ignored) { /* metrics never break the app */ }
    }

    public void finished(String kind, String outcome) {
        counters.computeIfAbsent(kind + "|" + outcome, k -> Counter.builder("basamak.jobs.finished").tag("kind", kind).tag("outcome", outcome)
                .description("Finished job attempts by outcome").register(registry)).increment();
    }
}
