package com.basamak.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * In-app notifications with a per-user SSE stream (replay after Last-Event-ID, so nothing is missed on reconnect).
 * notify() is idempotent by dedup_key: the same event (e.g. a retried job) never produces a second notification.
 * Without FCM (needs a Firebase project, not set up) a notification reaches the phone while the app process is
 * alive; the app shows it as a local notification and a tap opens `link` (TASARIM §8).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Map<UUID, Set<SseEmitter>> streams = new ConcurrentHashMap<>();

    public void notify(UUID userId, String type, String title, String body, String link, String dedupKey) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    INSERT INTO notification(user_id, type, title, body, link, dedup_key) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (dedup_key) DO NOTHING RETURNING id, type, title, body, link, created_at""",
                    userId, type, cut(title, 200), cut(body == null ? "" : body, 500), link, dedupKey);
            if (rows.isEmpty()) return;
            Map<String, Object> n = view(rows.get(0));
            for (SseEmitter e : streams.getOrDefault(userId, Set.of())) send(e, n);
        } catch (Exception e) {
            log.warn("notification {} for {} not stored: {}", type, userId, e.getMessage()); // never breaks the caller
        }
    }

    public Map<String, Object> list(UUID userId, int limit) {
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT id, type, title, body, link, created_at, read_at FROM notification WHERE user_id = ? ORDER BY id DESC LIMIT ?""",
                userId, Math.min(Math.max(limit, 1), 100)).stream().map(NotificationService::view).toList();
        Integer unread = jdbc.queryForObject("SELECT count(*) FROM notification WHERE user_id = ? AND read_at IS NULL", Integer.class, userId);
        return Map.of("items", items, "unread", unread == null ? 0 : unread);
    }

    public void readAll(UUID userId) {
        jdbc.update("UPDATE notification SET read_at = now() WHERE user_id = ? AND read_at IS NULL", userId);
    }

    public SseEmitter subscribe(UUID userId, long afterId) {
        SseEmitter e = new SseEmitter(30 * 60_000L);
        Set<SseEmitter> set = streams.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>());
        set.add(e);
        Runnable remove = () -> set.remove(e);
        e.onCompletion(remove);
        e.onTimeout(remove);
        e.onError(t -> remove.run());
        try {
            e.send(SseEmitter.event().name("ready").data("{}", MediaType.APPLICATION_JSON));
        } catch (Exception ex) {
            remove.run();
        }
        if (afterId > 0) {
            jdbc.queryForList("SELECT id, type, title, body, link, created_at FROM notification WHERE user_id = ? AND id > ? ORDER BY id LIMIT 50", userId, afterId)
                    .forEach(r -> send(e, view(r)));
        }
        return e;
    }

    private void send(SseEmitter e, Map<String, Object> n) {
        try {
            e.send(SseEmitter.event().id(String.valueOf(n.get("id"))).name("notification").data(json.writeValueAsString(n), MediaType.APPLICATION_JSON));
        } catch (Exception ex) {
            e.completeWithError(ex);
        }
    }

    private static Map<String, Object> view(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        r.forEach((k, v) -> m.put(k.equals("created_at") ? "createdAt" : k.equals("read_at") ? "readAt" : k, v instanceof Timestamp t ? t.toInstant() : v));
        return m;
    }

    private static String cut(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }
}
