package com.basamak.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Live status of tool runs over Server-Sent Events (ADR-6).
 * Every status change is first stored in tool_run_event, then pushed to open streams. A client that reconnects
 * sends Last-Event-ID and gets the events it missed replayed from the table, so nothing is lost on a network drop.
 * Fan-out is in-process (single node). With several API nodes this becomes Postgres LISTEN/NOTIFY (documented).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunEvents {
    public static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Map<UUID, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public void append(UUID runId, String type, Map<String, Object> run) {
        String data;
        try {
            data = json.writeValueAsString(run);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Long id = jdbc.queryForObject("INSERT INTO tool_run_event(run_id, type, data) VALUES (?, ?, CAST(? AS jsonb)) RETURNING id",
                Long.class, runId, type, data);
        boolean terminal = TERMINAL.contains(String.valueOf(run.get("status")));
        for (SseEmitter e : subscribers.getOrDefault(runId, Set.of())) {
            send(e, id, type, data);
            if (terminal) e.complete();
        }
    }

    public SseEmitter subscribe(UUID runId, long afterId, boolean runFinished) {
        SseEmitter emitter = new SseEmitter(10 * 60_000L);
        Set<SseEmitter> set = subscribers.computeIfAbsent(runId, k -> new CopyOnWriteArraySet<>());
        set.add(emitter); // register first, then replay: the client drops ids it has already seen
        Runnable remove = () -> {
            set.remove(emitter);
            if (set.isEmpty()) subscribers.remove(runId, set);
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(t -> remove.run());
        jdbc.query("SELECT id, type, data::text AS data FROM tool_run_event WHERE run_id = ? AND id > ? ORDER BY id",
                rs -> { send(emitter, rs.getLong("id"), rs.getString("type"), rs.getString("data")); }, runId, afterId);
        if (runFinished) emitter.complete();
        return emitter;
    }

    private static void send(SseEmitter e, long id, String type, String data) {
        try {
            e.send(SseEmitter.event().id(Long.toString(id)).name(type).data(data, org.springframework.http.MediaType.APPLICATION_JSON));
        } catch (Exception ex) {
            e.completeWithError(ex); // client went away; it will reconnect with Last-Event-ID
        }
    }
}
