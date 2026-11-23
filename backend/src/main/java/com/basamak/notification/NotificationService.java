package com.basamak.notification;

import com.basamak.auth.MailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
 * <p>Channels per event type are the member's choice (notification_pref; defaults in {@link #TYPES}): in-app (the
 * notification centre), e-mail (sent after the caller's transaction commits) and push (the mobile app only shows
 * events whose push flag is on). The row is always stored, so dedup and "Verilerimi indir" stay complete.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MailService mail;
    private final Map<UUID, Set<SseEmitter>> streams = new ConcurrentHashMap<>();
    @Value("${basamak.web-base-url}")
    private String webBaseUrl;

    public record TypeInfo(String label, boolean inApp, boolean email, boolean push) {}

    /** Event types members can configure, with their defaults. */
    public static final Map<String, TypeInfo> TYPES = new LinkedHashMap<>();
    static {
        TYPES.put("TOOL_RUN", new TypeInfo("Araç çalıştırması bitti", true, false, true));
        TYPES.put("WORKFLOW_RUN", new TypeInfo("İş akışı bitti ya da durdu", true, false, true));
        TYPES.put("CERTIFICATE", new TypeInfo("Seviye atladın, sertifika hazır", true, true, true));
        TYPES.put("FORKED", new TypeInfo("Paylaştığın akış çatallandı", true, false, true));
        TYPES.put("BADGE", new TypeInfo("Yeni rozet kazandın", true, false, true));
        TYPES.put("COMMENT", new TypeInfo("İçeriğine yorum ya da cevap geldi", true, false, false));
        TYPES.put("FOLLOW", new TypeInfo("Seni takip etmeye başladılar", true, false, false));
    }

    public record Channels(boolean inApp, boolean email, boolean push) {}

    public Channels channels(UUID userId, String type) {
        TypeInfo d = TYPES.getOrDefault(type, new TypeInfo(type, true, false, true));
        return jdbc.query("SELECT in_app, email, push FROM notification_pref WHERE user_id = ? AND event_type = ?",
                (rs, i) -> new Channels(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3)), userId, type)
                .stream().findFirst().orElse(new Channels(d.inApp(), d.email(), d.push()));
    }

    public List<Map<String, Object>> prefs(UUID userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        TYPES.forEach((type, info) -> {
            Channels c = channels(userId, type);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type); m.put("label", info.label()); m.put("inApp", c.inApp()); m.put("email", c.email()); m.put("push", c.push());
            out.add(m);
        });
        return out;
    }

    public void savePref(UUID userId, String type, boolean inApp, boolean email, boolean push) {
        if (!TYPES.containsKey(type)) throw com.basamak.common.ApiException.badRequest("unknown_type", "Bilinmeyen bildirim türü: " + type);
        jdbc.update("""
                INSERT INTO notification_pref(user_id, event_type, in_app, email, push) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (user_id, event_type) DO UPDATE SET in_app = EXCLUDED.in_app, email = EXCLUDED.email, push = EXCLUDED.push, updated_at = now()""",
                userId, type, inApp, email, push);
    }

    public void notify(UUID userId, String type, String title, String body, String link, String dedupKey) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    INSERT INTO notification(user_id, type, title, body, link, dedup_key) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (dedup_key) DO NOTHING RETURNING id, type, title, body, link, created_at""",
                    userId, type, cut(title, 200), cut(body == null ? "" : body, 500), link, dedupKey);
            if (rows.isEmpty()) return;
            Channels c = channels(userId, type);
            Map<String, Object> n = view(rows.get(0));
            n.put("push", c.push());
            if (c.inApp() || c.push()) for (SseEmitter e : streams.getOrDefault(userId, Set.of())) send(e, n);
            if (c.email()) afterCommit(() -> email(userId, title, body, link));
        } catch (Exception e) {
            log.warn("notification {} for {} not stored: {}", type, userId, e.getMessage()); // never breaks the caller
        }
    }

    private void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { r.run(); }
            });
        } else r.run();
    }

    private void email(UUID userId, String title, String body, String link) {
        String to = jdbc.query("SELECT email FROM users WHERE id = ? AND deleted_at IS NULL", (rs, i) -> rs.getString(1), userId).stream().findFirst().orElse(null);
        if (to == null) return;
        mail.send(to, "Basamak: " + title, (body == null ? "" : body) + (link == null ? "" : "\n\nAç: " + webBaseUrl + link)
                + "\n\nBu e-postayı bildirim tercihlerin nedeniyle aldın. Değiştirmek için: " + webBaseUrl + "/notifications");
    }

    public void read(UUID userId, long id) {
        jdbc.update("UPDATE notification SET read_at = now() WHERE user_id = ? AND id = ? AND read_at IS NULL", userId, id);
    }

    public Map<String, Object> list(UUID userId, int limit) {
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT n.id, n.type, n.title, n.body, n.link, n.created_at, n.read_at FROM notification n
                LEFT JOIN notification_pref p ON p.user_id = n.user_id AND p.event_type = n.type
                WHERE n.user_id = ? AND coalesce(p.in_app, true) ORDER BY n.id DESC LIMIT ?""",
                userId, Math.min(Math.max(limit, 1), 100)).stream().map(NotificationService::view).toList();
        Integer unread = jdbc.queryForObject("""
                SELECT count(*) FROM notification n LEFT JOIN notification_pref p ON p.user_id = n.user_id AND p.event_type = n.type
                WHERE n.user_id = ? AND n.read_at IS NULL AND coalesce(p.in_app, true)""", Integer.class, userId);
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
