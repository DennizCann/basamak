package com.basamak.content;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Publishes versions whose scheduled time (stored as UTC instant, entered in Europe/Istanbul) has passed.
 * Missed ticks (server down) are caught up on the next run because the query is "scheduled_at <= now".
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledPublisher {
    private final ContentService content;

    @Scheduled(fixedDelayString = "${basamak.publish-scheduler-ms:15000}")
    public void run() {
        int n = content.publishDue(Instant.now());
        if (n > 0) log.info("Published {} scheduled version(s)", n);
    }
}
