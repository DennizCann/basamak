package com.basamak.community;

import com.basamak.common.ApiException;
import com.basamak.common.AuditService;
import com.basamak.content.ContentType;
import com.basamak.content.PublishedContent;
import com.basamak.gamification.BadgeService;
import com.basamak.learning.JourneyProgressService;
import com.basamak.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Community (section 3.8): public profiles the member opts into, follows and an activity feed, discussions on
 * trainings and shared workflows, ratings, and moderation (report -> hide -> restrict repeat offenders).
 * <ul>
 *   <li>Profiles are private until the member makes them public; each part (level, badges, journeys, shared) is a
 *       separate choice. The feed only shows followed members whose profile is public.</li>
 *   <li>Comments: personal data (e-mail addresses, phone numbers) is masked before storing; a small profanity list
 *       rejects the comment with a reason. Max 10 comments per 10 minutes per member (spam).</li>
 *   <li>Moderation: 3 hidden comments within 30 days restrict the author from commenting for 7 days automatically;
 *       staff can also restrict manually. Every moderation action is audited.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CommunityService {
    static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    static final Pattern PHONE = Pattern.compile("(\\+?\\d[\\d ()-]{8,}\\d)");
    /** Deliberately short and illustrative; a real list (and a classifier) is a TASARIM decision. */
    static final List<String> PROFANITY = List.of("aptal", "salak", "gerizekalı", "şerefsiz", "idiot", "stupid");
    static final int HIDDEN_LIMIT = 3, RESTRICT_DAYS = 7;
    static final java.time.format.DateTimeFormatter TR_TIME = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(java.time.ZoneId.of("Europe/Istanbul"));

    private final JdbcTemplate jdbc;
    private final PublishedContent published;
    private final JourneyProgressService journeys;
    private final BadgeService badges;
    private final NotificationService notifications;
    private final AuditService audit;

    // ------------------------------------------------------------------ profile

    public record Settings(boolean isPublic, boolean showLevel, boolean showBadges, boolean showJourneys, boolean showShared, String bio) {}

    public Settings settings(UUID userId) {
        return jdbc.query("SELECT * FROM profile_settings WHERE user_id = ?", (rs, i) -> new Settings(rs.getBoolean("is_public"),
                rs.getBoolean("show_level"), rs.getBoolean("show_badges"), rs.getBoolean("show_journeys"), rs.getBoolean("show_shared"),
                rs.getString("bio")), userId).stream().findFirst().orElse(new Settings(false, true, true, true, true, ""));
    }

    public Settings saveSettings(UUID userId, Settings s) {
        String bio = maskPersonalData(Optional.ofNullable(s.bio()).orElse("").strip());
        if (bio.length() > 280) throw ApiException.badRequest("bio_too_long", "Tanıtım metni en çok 280 karakter olabilir.");
        rejectProfanity(bio);
        jdbc.update("""
                INSERT INTO profile_settings(user_id, is_public, show_level, show_badges, show_journeys, show_shared, bio) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET is_public = EXCLUDED.is_public, show_level = EXCLUDED.show_level, show_badges = EXCLUDED.show_badges,
                  show_journeys = EXCLUDED.show_journeys, show_shared = EXCLUDED.show_shared, bio = EXCLUDED.bio, updated_at = now()""",
                userId, s.isPublic(), s.showLevel(), s.showBadges(), s.showJourneys(), s.showShared(), bio);
        return settings(userId);
    }

    public Map<String, Object> profile(UUID viewer, UUID userId) {
        String name = jdbc.query("SELECT display_name FROM users WHERE id = ? AND status = 'ACTIVE'", (rs, i) -> rs.getString(1), userId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Üye"));
        boolean self = userId.equals(viewer);
        Settings s = settings(userId);
        if (!s.isPublic() && !self) throw ApiException.notFound("Profil");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", userId);
        m.put("displayName", name);
        m.put("self", self);
        m.put("isPublic", s.isPublic());
        m.put("bio", s.bio());
        m.put("followers", count("SELECT count(*) FROM follow WHERE followee_id = ?", userId));
        m.put("following", count("SELECT count(*) FROM follow WHERE follower_id = ?", userId));
        m.put("isFollowing", !self && count("SELECT count(*) FROM follow WHERE follower_id = ? AND followee_id = ?", viewer, userId) > 0);
        if (self || s.showLevel()) {
            var ps = journeys.forUser(userId);
            m.put("level", journeys.level(ps));
            m.put("score", count("SELECT coalesce((SELECT total FROM user_score WHERE user_id = ?), 0)", userId));
            m.put("streak", badges.streak(userId));
        }
        if (self || s.showBadges()) {
            m.put("badges", badges.badges(userId));
            m.put("allBadges", BadgeService.BADGES.values());
        }
        if (self || s.showJourneys()) {
            m.put("journeys", jdbc.queryForList("""
                    SELECT journey_title AS title, issued_at AS "completedAt", serial FROM certificate
                    WHERE user_id = ? AND revoked_at IS NULL ORDER BY issued_at""", userId));
        }
        if (self || s.showShared()) {
            m.put("sharedWorkflows", jdbc.queryForList("""
                    SELECT w.id, w.name, w.description, w.fork_count AS "forkCount",
                           (SELECT round(avg(stars)::numeric, 1) FROM rating r WHERE r.target_type = 'WORKFLOW' AND r.target_id = w.id) AS "avgRating"
                    FROM workflow w WHERE w.owner_id = ? AND w.visibility = 'PUBLIC' AND w.deleted_at IS NULL ORDER BY w.updated_at DESC LIMIT 20""", userId));
        }
        m.put("visibility", Map.of("level", s.showLevel(), "badges", s.showBadges(), "journeys", s.showJourneys(), "shared", s.showShared()));
        return m;
    }

    @Transactional
    public void follow(UUID follower, UUID followee) {
        if (follower.equals(followee)) throw ApiException.badRequest("self_follow", "Kendini takip edemezsin.");
        if (!settings(followee).isPublic()) throw ApiException.notFound("Profil");
        int n = jdbc.update("INSERT INTO follow(follower_id, followee_id) VALUES (?, ?) ON CONFLICT DO NOTHING", follower, followee);
        if (n == 1) {
            String name = jdbc.queryForObject("SELECT display_name FROM users WHERE id = ?", String.class, follower);
            boolean followerPublic = settings(follower).isPublic();
            notifications.notify(followee, "FOLLOW", name + " seni takip etmeye başladı", "",
                    followerPublic ? "/u/" + follower : null, "follow:" + follower + ":" + followee + ":" + Instant.now().truncatedTo(ChronoUnit.DAYS));
        }
    }

    public void unfollow(UUID follower, UUID followee) {
        jdbc.update("DELETE FROM follow WHERE follower_id = ? AND followee_id = ?", follower, followee);
    }

    /** Followed members' notable actions (only members whose profile is public). Newest first, keyset paging by id. */
    public List<Map<String, Object>> feed(UUID viewer, Long beforeId, int limit) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT e.id, e.type, e.object_type AS "objectType", e.object_id AS "objectId", e.received_at AS "at",
                       u.id AS "userId", u.display_name AS "userName"
                FROM follow f
                JOIN activity_event e ON e.user_id = f.followee_id
                JOIN users u ON u.id = f.followee_id AND u.status = 'ACTIVE'
                JOIN profile_settings ps ON ps.user_id = f.followee_id AND ps.is_public
                WHERE f.follower_id = ? AND e.type IN ('TRAINING_COMPLETED','JOURNEY_COMPLETED','ITEM_SHARED','QUIZ_PASSED','WORKFLOW_CREATED')
                  AND e.id < coalesce(CAST(? AS bigint), 9223372036854775807)
                ORDER BY e.id DESC LIMIT ?""", viewer, beforeId, Math.min(Math.max(limit, 1), 50));
        for (Map<String, Object> r : rows) {
            r.put("objectTitle", title((String) r.get("objectType"), (String) r.get("objectId")));
            if (r.get("at") instanceof Timestamp t) r.put("at", t.toInstant());
        }
        return rows;
    }

    private String title(String type, String id) {
        try {
            UUID uuid = UUID.fromString(id);
            return switch (type) {
                case "TRAINING" -> published.get(uuid, ContentType.TRAINING).map(v -> v.title()).orElse(null);
                case "JOURNEY" -> published.get(uuid, ContentType.JOURNEY).map(v -> v.title()).orElse(null);
                case "WORKFLOW" -> jdbc.query("SELECT name FROM workflow WHERE id = ? AND visibility = 'PUBLIC' AND deleted_at IS NULL",
                        (rs, i) -> rs.getString(1), uuid).stream().findFirst().orElse(null);
                case "TOOL" -> jdbc.query("SELECT name FROM user_tool WHERE id = ? AND visibility = 'PUBLIC' AND deleted_at IS NULL",
                        (rs, i) -> rs.getString(1), uuid).stream().findFirst().orElse(null);
                default -> null;
            };
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ comments

    /** The target must be visible to members: a published training or a public workflow. Returns the owner to notify. */
    private Optional<UUID> checkTarget(String type, UUID id) {
        return switch (type) {
            case "TRAINING" -> {
                if (published.get(id, ContentType.TRAINING).isEmpty()) throw ApiException.notFound("Eğitim");
                yield Optional.empty();
            }
            case "WORKFLOW" -> Optional.of(jdbc.query("SELECT owner_id FROM workflow WHERE id = ? AND visibility = 'PUBLIC' AND deleted_at IS NULL",
                    (rs, i) -> (UUID) rs.getObject(1), id).stream().findFirst().orElseThrow(() -> ApiException.notFound("Paylaşılan iş akışı")));
            default -> throw ApiException.badRequest("bad_target", "Yorum yalnız eğitimlere ve paylaşılan iş akışlarına yazılabilir.");
        };
    }

    public List<Map<String, Object>> comments(UUID viewer, String type, UUID id) {
        checkTarget(type, id);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.id, c.parent_id AS "parentId", c.author_id AS "authorId", u.display_name AS "authorName",
                       coalesce(ps.is_public, false) AS "authorPublic", c.body, c.created_at AS "createdAt",
                       c.deleted_at IS NOT NULL AS deleted, c.hidden_at IS NOT NULL AS hidden
                FROM comment c JOIN users u ON u.id = c.author_id LEFT JOIN profile_settings ps ON ps.user_id = c.author_id
                WHERE c.target_type = ? AND c.target_id = ? ORDER BY c.id LIMIT 500""", type, id);
        for (Map<String, Object> r : rows) {
            boolean gone = Boolean.TRUE.equals(r.get("deleted")) || Boolean.TRUE.equals(r.get("hidden"));
            if (Boolean.TRUE.equals(r.get("hidden"))) r.put("body", "Bu yorum topluluk kuralları nedeniyle gizlendi.");
            else if (Boolean.TRUE.equals(r.get("deleted"))) r.put("body", "Bu yorum silindi.");
            r.put("mine", viewer.equals(r.get("authorId")));
            if (gone || !Boolean.TRUE.equals(r.get("authorPublic"))) { if (!viewer.equals(r.get("authorId"))) r.put("authorId", null); }
            if (r.get("createdAt") instanceof Timestamp t) r.put("createdAt", t.toInstant());
            r.remove("authorPublic");
        }
        return rows;
    }

    @Transactional
    public Map<String, Object> addComment(UUID author, String type, UUID id, Long parentId, String rawBody) {
        Optional<UUID> owner = checkTarget(type, id);
        Instant restricted = jdbc.queryForObject("SELECT restricted_until FROM users WHERE id = ?", Timestamp.class, author) instanceof Timestamp t ? t.toInstant() : null;
        if (restricted != null && restricted.isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "restricted", "Yorum yazman " + TR_TIME.format(restricted)
                    + " tarihine kadar kısıtlandı (gizlenen yorumlar nedeniyle). Bu tarihten sonra yeniden yazabilirsin.", Map.of("until", restricted));
        }
        String body = Optional.ofNullable(rawBody).orElse("").strip();
        if (body.isEmpty()) throw ApiException.badRequest("empty_comment", "Yorum boş olamaz.");
        if (body.length() > 2000) throw ApiException.badRequest("comment_too_long", "Yorum en çok 2.000 karakter olabilir.");
        rejectProfanity(body);
        body = maskPersonalData(body);
        if (count("SELECT count(*) FROM comment WHERE author_id = ? AND created_at > now() - interval '10 minutes'", author) >= 10) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "comment_rate", "Çok hızlı yorum yazıyorsun. Birkaç dakika sonra tekrar dene.", Map.of());
        }
        UUID parentAuthor = null;
        if (parentId != null) {
            var p = jdbc.queryForList("SELECT author_id, parent_id, target_type, target_id FROM comment WHERE id = ?", parentId);
            if (p.isEmpty() || !type.equals(p.get(0).get("target_type")) || !id.equals(p.get(0).get("target_id"))) throw ApiException.notFound("Cevaplanan yorum");
            if (p.get(0).get("parent_id") != null) parentId = (Long) p.get(0).get("parent_id"); // one level of replies
            parentAuthor = (UUID) p.get(0).get("author_id");
        }
        Long cid = jdbc.queryForObject("INSERT INTO comment(target_type, target_id, parent_id, author_id, body) VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class, type, id, parentId, author, body);
        String name = jdbc.queryForObject("SELECT display_name FROM users WHERE id = ?", String.class, author);
        String link = ("TRAINING".equals(type) ? "/trainings/" : "/workflows/") + id + "#yorum-" + cid;
        if (parentAuthor != null && !parentAuthor.equals(author)) {
            notifications.notify(parentAuthor, "COMMENT", name + " yorumuna cevap verdi", cut(body), link, "comment:" + cid + ":reply");
        }
        if (owner.isPresent() && !owner.get().equals(author) && !owner.get().equals(parentAuthor)) {
            notifications.notify(owner.get(), "COMMENT", name + " iş akışına yorum yazdı", cut(body), link, "comment:" + cid + ":owner");
        }
        return Map.of("id", cid, "body", body);
    }

    public void deleteOwn(UUID author, long id) {
        int n = jdbc.update("UPDATE comment SET deleted_at = now() WHERE id = ? AND author_id = ? AND deleted_at IS NULL", id, author);
        if (n == 0) throw ApiException.notFound("Yorum");
    }

    public void report(UUID reporter, long commentId, String reason) {
        var c = jdbc.queryForList("SELECT author_id FROM comment WHERE id = ? AND deleted_at IS NULL AND hidden_at IS NULL", commentId);
        if (c.isEmpty()) throw ApiException.notFound("Yorum");
        if (reporter.equals(c.get(0).get("author_id"))) throw ApiException.badRequest("own_comment", "Kendi yorumunu bildiremezsin; silebilirsin.");
        String r = Optional.ofNullable(reason).orElse("").strip();
        if (r.isEmpty()) throw ApiException.badRequest("reason_required", "Bildirme nedenini seç ya da yaz.");
        jdbc.update("INSERT INTO comment_report(comment_id, reporter_id, reason) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                commentId, reporter, r.length() > 300 ? r.substring(0, 300) : r);
    }

    // ------------------------------------------------------------------ ratings

    public Map<String, Object> rating(UUID viewer, UUID workflowId) {
        checkTarget("WORKFLOW", workflowId);
        Map<String, Object> m = new LinkedHashMap<>(jdbc.queryForMap("""
                SELECT count(*) AS count, round(coalesce(avg(stars), 0)::numeric, 1) AS average FROM rating WHERE target_type = 'WORKFLOW' AND target_id = ?""", workflowId));
        m.put("mine", jdbc.query("SELECT stars FROM rating WHERE target_type = 'WORKFLOW' AND target_id = ? AND user_id = ?",
                (rs, i) -> rs.getInt(1), workflowId, viewer).stream().findFirst().orElse(null));
        return m;
    }

    public Map<String, Object> rate(UUID viewer, UUID workflowId, int stars) {
        UUID owner = checkTarget("WORKFLOW", workflowId).orElseThrow();
        if (owner.equals(viewer)) throw ApiException.badRequest("own_item", "Kendi iş akışına puan veremezsin.");
        if (stars < 1 || stars > 5) throw ApiException.badRequest("bad_stars", "Puan 1 ile 5 arasında olmalı.");
        jdbc.update("""
                INSERT INTO rating(target_type, target_id, user_id, stars) VALUES ('WORKFLOW', ?, ?, ?)
                ON CONFLICT (target_type, target_id, user_id) DO UPDATE SET stars = EXCLUDED.stars, updated_at = now()""", workflowId, viewer, stars);
        return rating(viewer, workflowId);
    }

    // ------------------------------------------------------------------ moderation (staff)

    public List<Map<String, Object>> openReports() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.id AS "commentId", c.body, c.target_type AS "targetType", c.target_id AS "targetId", c.created_at AS "createdAt",
                       c.author_id AS "authorId", u.display_name AS "authorName", u.restricted_until AS "restrictedUntil",
                       count(r.id) AS reports, string_agg(DISTINCT r.reason, ' · ') AS reasons, min(r.created_at) AS "firstReportedAt",
                       (SELECT count(*) FROM comment h WHERE h.author_id = c.author_id AND h.hidden_at > now() - interval '30 days') AS "hiddenLast30"
                FROM comment_report r JOIN comment c ON c.id = r.comment_id JOIN users u ON u.id = c.author_id
                WHERE r.resolved_at IS NULL
                GROUP BY c.id, u.id ORDER BY count(r.id) DESC, min(r.created_at) LIMIT 200""");
        rows.forEach(r -> r.replaceAll((k, v) -> v instanceof Timestamp t ? t.toInstant() : v));
        return rows;
    }

    @Transactional
    public Map<String, Object> hide(UUID staff, long commentId, String reason) {
        var c = jdbc.queryForList("SELECT author_id FROM comment WHERE id = ?", commentId);
        if (c.isEmpty()) throw ApiException.notFound("Yorum");
        UUID author = (UUID) c.get(0).get("author_id");
        jdbc.update("UPDATE comment SET hidden_at = now(), hidden_by = ?, hidden_reason = ? WHERE id = ? AND hidden_at IS NULL", staff, cut(reason), commentId);
        jdbc.update("UPDATE comment_report SET resolved_at = now(), resolved_by = ?, resolution = 'HIDDEN' WHERE comment_id = ? AND resolved_at IS NULL", staff, commentId);
        audit.record(staff, "COMMENT_HIDDEN", "COMMENT", commentId, Map.of("reason", reason == null ? "" : reason));
        boolean restricted = false;
        if (count("SELECT count(*) FROM comment WHERE author_id = ? AND hidden_at > now() - interval '30 days'", author) >= HIDDEN_LIMIT) {
            restrict(staff, author, RESTRICT_DAYS, "Son 30 günde " + HIDDEN_LIMIT + " yorumu gizlendi (otomatik)");
            restricted = true;
        }
        return Map.of("hidden", true, "authorRestricted", restricted);
    }

    public void dismiss(UUID staff, long commentId) {
        jdbc.update("UPDATE comment_report SET resolved_at = now(), resolved_by = ?, resolution = 'DISMISSED' WHERE comment_id = ? AND resolved_at IS NULL", staff, commentId);
        audit.record(staff, "REPORT_DISMISSED", "COMMENT", commentId, Map.of());
    }

    public void restrict(UUID staff, UUID userId, int days, String reason) {
        if (days < 0 || days > 365) throw ApiException.badRequest("bad_days", "Kısıtlama 0-365 gün olabilir (0 kaldırır).");
        jdbc.update("UPDATE users SET restricted_until = ? WHERE id = ?", days == 0 ? null : Timestamp.from(Instant.now().plus(days, ChronoUnit.DAYS)), userId);
        audit.record(staff, days == 0 ? "MEMBER_UNRESTRICTED" : "MEMBER_RESTRICTED", "USER", userId, Map.of("days", days, "reason", reason == null ? "" : reason));
    }

    // ------------------------------------------------------------------ helpers

    static String maskPersonalData(String s) {
        return PHONE.matcher(EMAIL.matcher(s).replaceAll("[e-posta gizlendi]")).replaceAll("[telefon gizlendi]");
    }

    static void rejectProfanity(String s) {
        String lower = s.toLowerCase(Locale.forLanguageTag("tr"));
        for (String w : PROFANITY) {
            if (Pattern.compile("(^|[^\\p{L}])" + Pattern.quote(w) + "([^\\p{L}]|$)").matcher(lower).find()) {
                throw ApiException.badRequest("profanity", "Metinde topluluk kurallarına uymayan bir ifade var. Lütfen saygılı bir dille yeniden yaz.");
            }
        }
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static String cut(String s) {
        return s == null ? "" : s.length() > 140 ? s.substring(0, 140) + "…" : s;
    }
}
