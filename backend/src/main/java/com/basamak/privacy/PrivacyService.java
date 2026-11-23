package com.basamak.privacy;

import com.basamak.auth.MailService;
import com.basamak.common.ApiException;
import com.basamak.common.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * KVKK / GDPR (scenario 12): "Geçmişim", data export and account deletion.
 *
 * Deletion = anonymisation. Personal data (e-mail, name, password, tokens, secrets, tool/workflow definitions and
 * run inputs/outputs, certificate holder name) is erased; the append-only activity log and point ledger keep only
 * the random user id, which after deletion no longer points to a person, so aggregate statistics (completion
 * rates, quiz correctness, leaderboards history) stay correct. Survey answers are detached from the user id.
 */
@Service
@RequiredArgsConstructor
public class PrivacyService {
    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    /** Never part of an export: hashes, encrypted secrets and signatures. */
    private static final Set<String> HIDDEN = Set.of("password_hash", "totp_secret_enc", "token_hash", "code_hash", "webhook_token_hash",
            "def_hash", "signature", "ciphertext", "idempotency_key", "secret_enc");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    private final MailService mail;

    // ------------------------------------------------------------------ history

    public static final Map<String, String> CATEGORY_SQL = Map.of(
            "learning", "e.type NOT LIKE 'TOOL%' AND e.type NOT LIKE 'WORKFLOW%' AND e.type NOT IN ('ITEM_SHARED','SHARED_ITEM_FORKED','SHARED_ITEM_USED')",
            "tools", "e.type LIKE 'TOOL%'",
            "workflows", "e.type LIKE 'WORKFLOW%'",
            "community", "e.type IN ('ITEM_SHARED','SHARED_ITEM_FORKED','SHARED_ITEM_USED')");

    public Map<String, Object> history(UUID userId, String category, LocalDate from, LocalDate to, int page, int size) {
        int s = Math.min(Math.max(size, 1), 50), p = Math.max(page, 0);
        StringBuilder where = new StringBuilder("e.user_id = ?");
        List<Object> args = new ArrayList<>(List.of(userId));
        if (category != null && !category.isBlank()) {
            String c = CATEGORY_SQL.get(category);
            if (c == null) throw ApiException.badRequest("invalid_filter", "Kategori learning, tools, workflows ya da community olmalı.");
            where.append(" AND ").append(c);
        }
        if (from != null) { where.append(" AND e.occurred_at >= ?"); args.add(Timestamp.from(from.atStartOfDay(IST).toInstant())); }
        if (to != null) { where.append(" AND e.occurred_at < ?"); args.add(Timestamp.from(to.plusDays(1).atStartOfDay(IST).toInstant())); }
        if (from != null && to != null && from.isAfter(to)) throw ApiException.badRequest("invalid_filter", "Başlangıç tarihi bitişten sonra olamaz.");

        Integer total = jdbc.queryForObject("SELECT count(*) FROM activity_event e WHERE " + where, Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(p * s);
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT e.id, e.type, e.object_type AS "objectType", e.object_id AS "objectId", e.occurred_at AS "occurredAt", e.source,
                       coalesce((SELECT sum(l.points) FROM point_ledger l WHERE l.event_key = e.event_key AND l.user_id = e.user_id), 0)::int AS points,
                       coalesce(cv.title, w.name, ut.name, tv.title) AS "objectTitle",
                       (SELECT it->>'title' FROM jsonb_array_elements(tv.body->'items') it WHERE it->>'key' = split_part(e.object_id, ':', 2)) AS "itemTitle"
                  FROM activity_event e
                  LEFT JOIN content_item ci ON ci.id::text = e.object_id
                  LEFT JOIN content_version cv ON cv.id = ci.published_version_id
                  LEFT JOIN workflow w ON w.id::text = e.object_id AND w.deleted_at IS NULL
                  LEFT JOIN user_tool ut ON ut.id::text = e.object_id AND ut.deleted_at IS NULL
                  LEFT JOIN content_item ti ON e.object_id LIKE '%:%' AND ti.id::text = split_part(e.object_id, ':', 1)
                  LEFT JOIN content_version tv ON tv.id = ti.published_version_id
                 WHERE""" + " " + where + " ORDER BY e.occurred_at DESC, e.id DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items.stream().map(PrivacyService::clean).toList());
        out.put("page", p);
        out.put("size", s);
        out.put("total", total);
        return out;
    }

    // ------------------------------------------------------------------ export

    /** Everything stored about the member, in one JSON document (secrets and hashes excluded). */
    @Transactional
    public Map<String, Object> export(UUID userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exportedAt", Instant.now());
        out.put("format", "basamak-export-v1");
        out.put("profile", rows("SELECT id, email, display_name, status, email_verified_at, consent_version, consent_at, created_at FROM users WHERE id = ?", userId).stream().findFirst().orElse(Map.of()));
        out.put("sessions", rows("SELECT * FROM refresh_sessions WHERE user_id = ?", userId));
        out.put("rememberedDevices", rows("SELECT * FROM trusted_device WHERE user_id = ?", userId));
        out.put("enrollments", rows("SELECT * FROM enrollment WHERE user_id = ?", userId));
        out.put("itemProgress", rows("SELECT p.* FROM item_progress p JOIN enrollment e ON e.id = p.enrollment_id WHERE e.user_id = ?", userId));
        out.put("quizAttempts", rows("SELECT id, training_id, training_version_no, item_key, attempt_no, started_at, submitted_at, answers, score, max_score, passed FROM quiz_attempt WHERE user_id = ?", userId));
        out.put("surveyResponses", rows("SELECT * FROM survey_response WHERE user_id = ?", userId));
        out.put("activity", rows("SELECT * FROM activity_event WHERE user_id = ? ORDER BY occurred_at", userId));
        out.put("pointLedger", rows("SELECT * FROM point_ledger WHERE user_id = ? ORDER BY created_at", userId));
        out.put("certificates", rows("SELECT * FROM certificate WHERE user_id = ?", userId));
        out.put("tools", rows("SELECT * FROM user_tool WHERE owner_id = ?", userId));
        out.put("toolSecretNames", rows("SELECT tool_id, name, updated_at FROM tool_secret WHERE scope = 'USER' AND updated_by = ?", userId));
        out.put("toolRuns", rows("SELECT * FROM tool_run WHERE user_id = ? ORDER BY created_at", userId));
        out.put("workflows", rows("SELECT * FROM workflow WHERE owner_id = ?", userId));
        out.put("notifications", rows("SELECT * FROM notification WHERE user_id = ?", userId));
        out.put("workflowRuns", rows("SELECT * FROM workflow_run WHERE user_id = ? ORDER BY created_at", userId));
        audit.record(userId, "privacy.exported", "USER", userId, Map.of());
        return out;
    }

    // ------------------------------------------------------------------ delete

    @Transactional
    public void deleteAccount(UUID userId, String password, String confirmation) {
        Map<String, Object> u = jdbc.queryForMap("SELECT email, display_name, password_hash, status FROM users WHERE id = ?", userId);
        if ("DELETED".equals(u.get("status"))) throw ApiException.notFound("Hesap");
        if (password == null || !passwords.matches(password, (String) u.get("password_hash"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "wrong_password", "Şifre doğru değil. Hesabınızı silmek için mevcut şifrenizi girin.");
        }
        if (!"SİL".equals(confirmation) && !"SIL".equalsIgnoreCase(confirmation)) {
            throw ApiException.badRequest("confirmation_required", "Onay için kutuya SİL yazın.");
        }
        String email = (String) u.get("email");
        mail.send(email, "Basamak hesabınız silindi",
                "Merhaba,\n\nBasamak hesabınız isteğiniz üzerine silindi. Kişisel verileriniz anonimleştirildi; bu işlem geri alınamaz.\n"
                        + "Bu isteği siz yapmadıysanız destek ekibine yazın.\n");

        String anon = "deleted+" + userId + "@deleted.invalid";
        jdbc.update("""
                UPDATE users SET email = ?, display_name = 'Silinmiş üye', password_hash = '!deleted', status = 'DELETED', deleted_at = now(),
                       totp_secret_enc = NULL, totp_enabled = false, failed_login_count = 0, locked_until = NULL WHERE id = ?""", anon, userId);
        // sessions, devices, one-time codes
        jdbc.update("DELETE FROM refresh_sessions WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM trusted_device WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM email_tokens WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM login_challenge WHERE user_id = ?", userId);
        // own tools: secrets gone, definitions erased (runs keep only status/timing for statistics)
        jdbc.update("DELETE FROM tool_secret WHERE scope = 'USER' AND tool_id IN (SELECT id FROM user_tool WHERE owner_id = ?)", userId);
        jdbc.update("DELETE FROM user_tool_version WHERE tool_id IN (SELECT id FROM user_tool WHERE owner_id = ?)", userId);
        jdbc.update("UPDATE user_tool SET name = '[silindi]', definition = '{}'::jsonb, def_hash = md5(id::text), deleted_at = coalesce(deleted_at, now()) WHERE owner_id = ?", userId);
        jdbc.update("UPDATE tool_run SET input = '{}'::jsonb, output = NULL, error = NULL, cancel_requested = cancel_requested WHERE user_id = ?", userId);
        // workflows: off the community, triggers off, content erased; forks made by others are their own copies
        jdbc.update("UPDATE workflow SET name = '[silindi]', description = '', definition = '{\"inputs\":[],\"steps\":[]}'::jsonb, visibility = 'PRIVATE', "
                + "cron = NULL, cron_input = NULL, next_run_at = NULL, webhook_token_hash = NULL, deleted_at = coalesce(deleted_at, now()) WHERE owner_id = ?", userId);
        jdbc.update("UPDATE workflow_version SET definition = '{\"inputs\":[],\"steps\":[]}'::jsonb WHERE workflow_id IN (SELECT id FROM workflow WHERE owner_id = ?)", userId);
        jdbc.update("UPDATE workflow_run SET input = '{}'::jsonb, output = NULL, error = NULL WHERE user_id = ?", userId);
        jdbc.update("UPDATE workflow_step_run SET input = NULL, output = NULL, error = NULL WHERE run_id IN (SELECT id FROM workflow_run WHERE user_id = ?)", userId);
        // survey answers stay for statistics but no longer belong to anyone
        jdbc.update("UPDATE survey_response SET user_id = NULL WHERE user_id = ?", userId);
        // certificates: the holder name is personal data; the public verification page now says "revoked"
        jdbc.update("UPDATE certificate SET holder_name = 'Silinmiş üye', revoked_at = coalesce(revoked_at, now()) WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM user_score WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM notification WHERE user_id = ?", userId);
        audit.record(userId, "privacy.account_deleted", "USER", userId, Map.of());
    }

    // ------------------------------------------------------------------ helpers

    private List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.queryForList(sql, args).stream().map(r -> {
            Map<String, Object> m = clean(r);
            m.keySet().removeIf(HIDDEN::contains);
            m.replaceAll((k, v) -> v != null && v.getClass().getSimpleName().equals("PGobject") ? readJson(v.toString()) : v);
            return m;
        }).toList();
    }

    private Object readJson(String s) {
        try {
            return s == null ? null : json.readTree(s);
        } catch (Exception e) {
            return s;
        }
    }

    static Map<String, Object> clean(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        row.forEach((k, v) -> m.put(k, v instanceof Timestamp t ? t.toInstant() : v));
        return m;
    }
}
