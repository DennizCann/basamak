package com.basamak.jobs;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Polls the queue and runs jobs on a bounded thread pool (concurrency limit = basamak.jobs.workers).
 * A job is claimed only when a thread is free, so a slow external API can tie up at most `workers` threads and
 * never the HTTP request threads of the API.
 */
@Slf4j
@Component
public class JobWorker {
    private static final Duration LEASE = Duration.ofSeconds(90); // > longest attempt (30 s tool timeout + redirects)

    private final JobQueue queue;
    private final Map<String, JobHandler> handlers;
    private final ThreadPoolExecutor pool;
    private final boolean enabled;
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName();

    public JobWorker(JobQueue queue, List<JobHandler> handlers,
                     @Value("${basamak.jobs.workers:4}") int workers,
                     @Value("${basamak.jobs.enabled:true}") boolean enabled) {
        this.queue = queue;
        this.handlers = handlers.stream().collect(Collectors.toMap(JobHandler::kind, Function.identity()));
        this.enabled = enabled;
        this.pool = new ThreadPoolExecutor(workers, workers, 60, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
            Thread t = new Thread(r, "job-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @Scheduled(fixedDelayString = "${basamak.jobs.poll-ms:250}")
    public void poll() {
        if (!enabled) return;
        int free = pool.getMaximumPoolSize() - pool.getActiveCount();
        for (JobQueue.Claimed job : queue.claim(workerId, free, LEASE)) {
            try {
                pool.execute(() -> run(job));
            } catch (RejectedExecutionException e) {
                queue.retry(job.id(), Duration.ZERO, "worker busy"); // give it back immediately
            }
        }
    }

    private void run(JobQueue.Claimed job) {
        MDC.put("jobId", job.id().toString());
        try {
            JobHandler h = handlers.get(job.kind());
            if (h == null) { queue.dead(job.id(), "no handler for " + job.kind()); return; }
            switch (h.handle(job)) {
                case JobHandler.Done d -> queue.done(job.id());
                case JobHandler.Retry r -> queue.retry(job.id(), r.delay(), r.error());
                case JobHandler.Dead d -> queue.dead(job.id(), d.error());
            }
        } catch (Exception e) {
            log.error("job {} failed unexpectedly", job.id(), e);
            if (job.attempts() < job.maxAttempts()) queue.retry(job.id(), Duration.ofSeconds(2), e.getClass().getSimpleName());
            else queue.dead(job.id(), e.getClass().getSimpleName());
        } finally {
            MDC.remove("jobId");
        }
    }

    @PreDestroy
    void stop() {
        pool.shutdown();
    }
}
