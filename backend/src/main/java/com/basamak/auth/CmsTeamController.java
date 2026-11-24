package com.basamak.auth;

import com.basamak.common.ApiException;
import com.basamak.common.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Content team list and the "lost authenticator" path: an ADMIN resets another staff member's TOTP. The member's CMS
 * sessions end at once and the next CMS login shows the setup screen (new QR) again. An admin cannot reset their own
 * TOTP (another admin must do it), so a stolen admin session cannot silently move the second factor.
 */
@RestController
@RequiredArgsConstructor
public class CmsTeamController {
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    @GetMapping("/api/cms/team")
    public List<Map<String, Object>> team(@AuthenticationPrincipal AuthPrincipal p) {
        admin(p);
        return jdbc.queryForList("""
                SELECT u.id, u.email, u.display_name AS "displayName", u.totp_enabled AS "totpEnabled",
                       string_agg(r.role, ',' ORDER BY r.role) AS roles
                FROM users u JOIN user_roles r ON r.user_id = u.id
                WHERE u.status = 'ACTIVE' AND r.role IN ('AUTHOR','EDITOR','PUBLISHER','ADMIN')
                GROUP BY u.id ORDER BY u.display_name""");
    }

    @Transactional
    @PostMapping("/api/cms/team/{id}/totp-reset")
    public Map<String, Object> resetTotp(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        admin(p);
        if (id.equals(p.userId())) {
            throw ApiException.badRequest("own_totp", "Kendi doğrulayıcı ayarını sıfırlayamazsın; başka bir yöneticiden iste.");
        }
        int n = jdbc.update("""
                UPDATE users SET totp_enabled = false, totp_secret_enc = NULL
                WHERE id = ? AND status = 'ACTIVE' AND EXISTS (SELECT 1 FROM user_roles r WHERE r.user_id = users.id AND r.role IN ('AUTHOR','EDITOR','PUBLISHER','ADMIN'))""", id);
        if (n == 0) throw ApiException.notFound("İçerik ekibi üyesi");
        int sessions = jdbc.update("UPDATE refresh_sessions SET revoked_at = now() WHERE user_id = ? AND audience = 'cms' AND revoked_at IS NULL", id);
        audit.record(p.userId(), "cms.totp_reset", "user", id, Map.of("sessionsEnded", sessions));
        return Map.of("ok", true, "sessionsEnded", sessions);
    }

    private static void admin(AuthPrincipal p) {
        if (!p.has(Role.ADMIN)) throw ApiException.forbidden("Bu işlemi yalnız yönetici yapabilir.");
    }
}
