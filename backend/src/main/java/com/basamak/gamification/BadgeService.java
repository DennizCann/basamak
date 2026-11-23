package com.basamak.gamification;

import com.basamak.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Badges and the daily streak. Both are derived from stored activity events, inside the same transaction:
 * <ul>
 *   <li>A badge is awarded once (PK user+badge); the award is idempotent, so a replayed event cannot award twice.</li>
 *   <li>The streak counts Europe/Istanbul calendar days with at least one recorded action, using the SERVER time the
 *       event was received, not the device clock: an offline action synced later counts for the day it arrived, so
 *       changing the phone's date cannot build a streak.</li>
 *   <li>Badges give no points (they are a separate reward); points stay in the ledger only.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class BadgeService {
    public record Badge(String key, String title, String description) {}

    public static final Map<String, Badge> BADGES = new LinkedHashMap<>();
    static {
        for (Badge b : List.of(
                new Badge("FIRST_LESSON", "İlk adım", "İlk dersini tamamladın."),
                new Badge("FIRST_QUIZ", "Sınavı geçtin", "İlk quiz'ini geçtin."),
                new Badge("FIRST_TOOL", "Araç ustası", "İlk aracını oluşturdun."),
                new Badge("FIRST_WORKFLOW", "İlk iş akışı", "İlk iş akışını kurdun."),
                new Badge("FIRST_SHARE", "Paylaşımcı", "Bir iş akışını toplulukla paylaştın."),
                new Badge("FIRST_JOURNEY", "Seviye atladın", "İlk Journey'ini tamamladın."),
                new Badge("STREAK_3", "3 günlük seri", "Üç gün üst üste öğrendin."),
                new Badge("STREAK_7", "7 günlük seri", "Yedi gün üst üste öğrendin."))) {
            BADGES.put(b.key(), b);
        }
    }

    private static final Map<String, String> FIRST_OF = Map.of(
            "LESSON_COMPLETED", "FIRST_LESSON", "QUIZ_PASSED", "FIRST_QUIZ", "TOOL_CREATED", "FIRST_TOOL",
            "WORKFLOW_CREATED", "FIRST_WORKFLOW", "ITEM_SHARED", "FIRST_SHARE", "JOURNEY_COMPLETED", "FIRST_JOURNEY");

    /** Actions that count as "being active" for the streak (merely viewing does not). */
    private static final Set<String> STREAK_ACTIONS = Set.of("LESSON_COMPLETED", "QUIZ_PASSED", "QUIZ_SUBMITTED", "SURVEY_SUBMITTED",
            "TRAINING_COMPLETED", "TOOL_RUN_SUCCEEDED", "WORKFLOW_RUN_SUCCEEDED", "TOOL_CREATED", "WORKFLOW_CREATED");

    private final JdbcTemplate jdbc;
    private final NotificationService notifications;

    /** Called for every newly stored activity event (never for a duplicate). */
    public void onEvent(UUID userId, String action) {
        String first = FIRST_OF.get(action);
        if (first != null) award(userId, first);
        if (STREAK_ACTIONS.contains(action)) {
            int current = touchStreak(userId, LocalDate.now(GamificationService.ISTANBUL));
            if (current >= 3) award(userId, "STREAK_3");
            if (current >= 7) award(userId, "STREAK_7");
        }
    }

    int touchStreak(UUID userId, LocalDate today) {
        return jdbc.queryForObject("""
                INSERT INTO user_streak(user_id, current, longest, last_day) VALUES (?, 1, 1, ?)
                ON CONFLICT (user_id) DO UPDATE SET
                  current = CASE WHEN user_streak.last_day = EXCLUDED.last_day THEN user_streak.current
                                 WHEN user_streak.last_day = EXCLUDED.last_day - 1 THEN user_streak.current + 1
                                 ELSE 1 END,
                  longest = GREATEST(user_streak.longest, CASE WHEN user_streak.last_day = EXCLUDED.last_day THEN user_streak.current
                                 WHEN user_streak.last_day = EXCLUDED.last_day - 1 THEN user_streak.current + 1 ELSE 1 END),
                  last_day = GREATEST(user_streak.last_day, EXCLUDED.last_day)
                RETURNING current""", Integer.class, userId, java.sql.Date.valueOf(today));
    }

    void award(UUID userId, String key) {
        int n = jdbc.update("INSERT INTO user_badge(user_id, badge_key) VALUES (?, ?) ON CONFLICT DO NOTHING", userId, key);
        if (n == 1) {
            Badge b = BADGES.get(key);
            notifications.notify(userId, "BADGE", "Yeni rozet: " + b.title(), b.description(), "/profile", "badge:" + userId + ":" + key);
        }
    }

    public List<Map<String, Object>> badges(UUID userId) {
        return jdbc.query("SELECT badge_key, awarded_at FROM user_badge WHERE user_id = ? ORDER BY awarded_at", (rs, i) -> {
            Badge b = BADGES.get(rs.getString(1));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", rs.getString(1));
            m.put("title", b == null ? rs.getString(1) : b.title());
            m.put("description", b == null ? "" : b.description());
            m.put("awardedAt", rs.getTimestamp(2).toInstant());
            return m;
        }, userId);
    }

    /** Current streak is 0 if the last active day is before yesterday (Istanbul). */
    public Map<String, Object> streak(UUID userId) {
        LocalDate today = LocalDate.now(GamificationService.ISTANBUL);
        return jdbc.query("SELECT current, longest, last_day FROM user_streak WHERE user_id = ?", (rs, i) -> {
            LocalDate last = rs.getDate(3).toLocalDate();
            int current = last.isBefore(today.minusDays(1)) ? 0 : rs.getInt(1);
            return Map.<String, Object>of("current", current, "longest", rs.getInt(2), "activeToday", last.equals(today));
        }, userId).stream().findFirst().orElse(Map.of("current", 0, "longest", 0, "activeToday", false));
    }
}
