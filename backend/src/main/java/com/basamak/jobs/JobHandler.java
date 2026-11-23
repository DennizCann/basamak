package com.basamak.jobs;

import java.time.Duration;

/** Executes one claimed job of a given kind. The worker applies the returned decision to the queue. */
public interface JobHandler {
    String kind();

    Decision handle(JobQueue.Claimed job);

    sealed interface Decision permits Done, Retry, Dead {}

    record Done() implements Decision {}

    record Retry(Duration delay, String error) implements Decision {}

    record Dead(String error) implements Decision {}
}
