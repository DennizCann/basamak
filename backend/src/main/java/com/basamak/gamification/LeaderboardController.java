package com.basamak.gamification;

import com.basamak.auth.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Leaderboard (scenario 11): weekly (Monday 00:00 Europe/Istanbul, from the ledger) and all-time (user_score).
 * Live: committed point changes mark the board dirty; at most once a second every open stream gets a "changed"
 * event and refetches. Only display names and points are shown; deleted (anonymised) accounts are excluded.
 */
@RestController
@RequiredArgsConstructor
public class LeaderboardController {
    private final JdbcTemplate jdbc;
    private final Set<SseEmitter> streams = new CopyOnWriteArraySet<>();
    private final AtomicBoolean dirty = new AtomicBoolean();

    public record Row(int rank, UUID userId, String displayName, int points, boolean me) {}

    @GetMapping("/api/leaderboard")
    public Map<String, Object> board(@AuthenticationPrincipal AuthPrincipal p, @RequestParam(defaultValue = "week") String period,
                                     @RequestParam(defaultValue = "20") int limit) {
        boolean week = !"all".equals(period);
        Instant since = weekStart();
        String ranked = week ? """
                SELECT l.user_id, sum(l.points)::int AS points FROM point_ledger l JOIN users u ON u.id = l.user_id
                 WHERE l.created_at >= ? AND u.status = 'ACTIVE' GROUP BY l.user_id HAVING sum(l.points) > 0"""
                : """
                SELECT s.user_id, s.total AS points FROM user_score s JOIN users u ON u.id = s.user_id
                 WHERE u.status = 'ACTIVE' AND s.total > 0""";
        String sql = "SELECT r.user_id, u.display_name, r.points, rank() OVER (ORDER BY r.points DESC)::int AS rnk FROM (" + ranked
                + ") r JOIN users u ON u.id = r.user_id ORDER BY r.points DESC, u.display_name LIMIT ?";
        Object[] args = week ? new Object[]{Timestamp.from(since), Math.min(limit, 100)} : new Object[]{Math.min(limit, 100)};
        List<Row> top = jdbc.query(sql, (rs, i) -> new Row(rs.getInt("rnk"), rs.getObject("user_id", UUID.class), rs.getString("display_name"),
                rs.getInt("points"), rs.getObject("user_id", UUID.class).equals(p.userId())), args);
        Map<String, Object> mine = null;
        if (top.stream().noneMatch(Row::me)) {
            String meSql = "SELECT r.points, (SELECT count(*) FROM (" + ranked + ") x WHERE x.points > r.points)::int + 1 AS rnk FROM (" + ranked + ") r WHERE r.user_id = ?";
            Object[] meArgs = week ? new Object[]{Timestamp.from(since), Timestamp.from(since), p.userId()} : new Object[]{p.userId()};
            mine = jdbc.query(meSql, rs -> rs.next() ? Map.<String, Object>of("rank", rs.getInt("rnk"), "points", rs.getInt("points")) : null, meArgs);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", week ? "week" : "all");
        out.put("since", week ? since : null);
        out.put("top", top);
        out.put("me", mine);
        return out;
    }

    @GetMapping(path = "/api/leaderboard/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter e = new SseEmitter(30 * 60_000L);
        streams.add(e);
        e.onCompletion(() -> streams.remove(e));
        e.onTimeout(() -> streams.remove(e));
        e.onError(t -> streams.remove(e));
        try {
            e.send(SseEmitter.event().name("ready").data("{}", MediaType.APPLICATION_JSON));
        } catch (Exception ex) {
            streams.remove(e);
        }
        return e;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPoints(GamificationService.PointsChanged e) {
        dirty.set(true);
    }

    @Scheduled(fixedDelay = 1000)
    public void push() {
        if (!dirty.getAndSet(false) || streams.isEmpty()) return;
        String data = "{\"at\":\"" + Instant.now() + "\"}";
        for (SseEmitter e : streams) {
            try {
                e.send(SseEmitter.event().name("changed").data(data, MediaType.APPLICATION_JSON));
            } catch (Exception ex) {
                streams.remove(e);
            }
        }
    }

    static Instant weekStart() {
        return ZonedDateTime.now(GamificationService.ISTANBUL).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .toLocalDate().atStartOfDay(GamificationService.ISTANBUL).toInstant();
    }
}
