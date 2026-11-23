package com.basamak.gamification;

import com.basamak.content.ContentType;
import com.basamak.content.PublishedContent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Rule engine + point ledger.
 *  1. The event is stored once (activity_event.event_key UNIQUE). A duplicate event awards nothing.
 *  2. The matching published rule is looked up (no rule in code: rules are CMS content).
 *  3. The ledger row's idempotency_key encodes the rule's repeat policy, so "100 views of the same lesson"
 *     collapse into one row by a UNIQUE constraint - correct even under concurrent requests.
 *  4. user_score is updated in the same transaction.
 * Adding a new kind of reward = publishing a new POINT_RULE in the CMS; no code change.
 */
@Service
@RequiredArgsConstructor
public class GamificationService {
    public static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");

    private final JdbcTemplate jdbc;
    private final PublishedContent published;
    private final ObjectMapper json;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final BadgeService badges;

    /** Published after points change; the leaderboard stream tells open screens to refresh. */
    public record PointsChanged(UUID userId, int points) {}

    public record Award(boolean eventRecorded, int points, PointRule rule) {
        static final Award DUPLICATE = new Award(false, 0, null);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public Award record(ActivityEvent e) {
        int stored = jdbc.update("""
                INSERT INTO activity_event(user_id, event_key, type, object_type, object_id, object_version, occurred_at, source, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb)) ON CONFLICT (event_key) DO NOTHING""",
                e.userId(), e.eventKey(), e.action(), e.objectType(), e.objectId(), e.objectVersion(),
                Timestamp.from(e.occurredAt()), e.source(), toJson(e.payload()));
        if (stored == 0) return Award.DUPLICATE;
        badges.onEvent(e.userId(), e.action());

        Optional<PointRule> rule = findRule(e.action(), e.objectType());
        if (rule.isEmpty() || rule.get().points() == 0) return new Award(true, 0, rule.orElse(null));
        PointRule r = rule.get();
        int inserted = jdbc.update("""
                INSERT INTO point_ledger(user_id, points, rule_item_id, rule_version_no, action, object_type, object_id, event_key, idempotency_key, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (idempotency_key) DO NOTHING""",
                e.userId(), r.points(), r.ruleId(), r.ruleVersionNo(), e.action(), e.objectType(), e.objectId(),
                e.eventKey(), idempotencyKey(r, e), r.action());
        if (inserted == 0) return new Award(true, 0, r);
        addToScore(e.userId(), r.points());
        events.publishEvent(new PointsChanged(e.userId(), r.points()));
        return new Award(true, r.points(), r);
    }

    /** Correction by a reversing entry; the original row is never changed. Idempotent per original row. */
    @Transactional
    public boolean reverse(long ledgerId, String reason) {
        Map<String, Object> row = jdbc.queryForMap("SELECT user_id, points, action, object_type, object_id, event_key FROM point_ledger WHERE id = ?", ledgerId);
        int points = -((Number) row.get("points")).intValue();
        int n = jdbc.update("""
                INSERT INTO point_ledger(user_id, points, action, object_type, object_id, event_key, idempotency_key, reverses_id, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (idempotency_key) DO NOTHING""",
                row.get("user_id"), points, row.get("action"), row.get("object_type"), row.get("object_id"),
                row.get("event_key"), "reverse|" + ledgerId, ledgerId, reason);
        if (n == 1) addToScore((UUID) row.get("user_id"), points);
        return n == 1;
    }

    public int total(UUID userId) {
        List<Integer> t = jdbc.queryForList("SELECT total FROM user_score WHERE user_id = ?", Integer.class, userId);
        return t.isEmpty() ? 0 : t.get(0);
    }

    public List<Map<String, Object>> recentLedger(UUID userId, int limit) {
        return jdbc.queryForList("""
                SELECT id, points, action, object_type, object_id, rule_version_no, reverses_id, reason, created_at
                FROM point_ledger WHERE user_id = ? ORDER BY id DESC LIMIT ?""", userId, limit);
    }

    /** Most specific published rule wins: exact objectType before "*". */
    public Optional<PointRule> findRule(String action, String objectType) {
        PointRule wildcard = null;
        for (PublishedContent.Published p : published.list(ContentType.POINT_RULE)) {
            JsonNode b = p.body();
            if (!action.equals(b.path("action").asText())) continue;
            String ot = b.path("objectType").asText("*");
            PointRule r = new PointRule(p.itemId(), p.versionNo(), action, ot, b.path("points").asInt(),
                    b.path("policy").asText("ONCE_PER_OBJECT"));
            if (ot.equals(objectType)) return Optional.of(r);
            if ("*".equals(ot)) wildcard = r;
        }
        return Optional.ofNullable(wildcard);
    }

    static String idempotencyKey(PointRule r, ActivityEvent e) {
        String base = e.userId() + "|" + r.action() + "|" + e.objectType() + "|" + e.objectId();
        return switch (r.policy()) {
            case "ONCE_PER_DAY" -> base + "|" + LocalDate.now(ISTANBUL); // server date: device clock cannot be gamed
            case "EVERY" -> "event|" + e.eventKey();
            default -> base;
        };
    }

    private void addToScore(UUID userId, int points) {
        jdbc.update("""
                INSERT INTO user_score(user_id, total, updated_at) VALUES (?, ?, now())
                ON CONFLICT (user_id) DO UPDATE SET total = user_score.total + EXCLUDED.total, updated_at = now()""",
                userId, points);
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o == null ? Map.of() : o);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
