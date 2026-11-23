package com.basamak.auth;

import com.basamak.common.ApiException;
import com.basamak.common.AuditService;
import com.basamak.common.CryptoService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * E-mailed one-time login codes for members (second step after the password).
 *  - 6 digits from SecureRandom, valid for 10 minutes, single use.
 *  - Stored only as HMAC-SHA256(serverSecret, challengeId:code); never logged.
 *  - 5 wrong attempts close the challenge (sign in again); wrong attempts also count towards account lockout.
 *  - Resend: 60 s cooldown, at most 5 sends per challenge; a new code replaces (invalidates) the previous one.
 *  - Endpoints are also covered by the per-IP rate limit on /api/auth/**.
 */
@Service
@RequiredArgsConstructor
public class EmailCodeService {
    static final Duration TTL = Duration.ofMinutes(10);
    static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    static final int MAX_ATTEMPTS = 5;
    static final int MAX_SENDS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final MailService mail;
    private final AuditService audit;

    @Value("${basamak.jwt-secret}")
    private String secret;

    public record Challenge(UUID id, String maskedEmail, Instant expiresAt, Instant resendAvailableAt, boolean delivered) {}

    public record Verified(UUID userId, String audience) {}

    @Transactional
    public Challenge start(User u, String audience, AuthService.ClientInfo info) {
        UUID id = UUID.randomUUID();
        String code = newCode();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO login_challenge(id, user_id, audience, code_hash, expires_at, user_agent, ip)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                id, u.getId(), audience, hash(id, code), Timestamp.from(now.plus(TTL)),
                info == null || info.userAgent() == null ? null : info.userAgent().substring(0, Math.min(300, info.userAgent().length())),
                info == null ? null : info.ip());
        boolean delivered = send(u, code);
        return new Challenge(id, mask(u.getEmail()), now.plus(TTL), now.plus(RESEND_COOLDOWN), delivered);
    }

    @Transactional
    public Challenge resend(UUID challengeId) {
        Map<String, Object> c = lock(challengeId);
        if (c.get("consumed_at") != null) throw used();
        Instant now = Instant.now();
        Instant last = ((Timestamp) c.get("last_sent_at")).toInstant();
        if (last.plus(RESEND_COOLDOWN).isAfter(now)) {
            long wait = Duration.between(now, last.plus(RESEND_COOLDOWN)).toSeconds() + 1;
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "resend_too_soon",
                    "Yeni kod istemek için " + wait + " saniye bekleyin.", Map.of("retryAfterSeconds", wait));
        }
        if (((Integer) c.get("send_count")) >= MAX_SENDS) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_sends",
                    "Bu giriş için çok fazla kod istendi. Şifrenizle yeniden giriş yapın.");
        }
        User u = user((UUID) c.get("user_id"));
        String code = newCode();
        // the new hash replaces the old one -> the previous code stops working; attempts start again
        jdbc.update("UPDATE login_challenge SET code_hash = ?, expires_at = ?, attempts = 0, send_count = send_count + 1, last_sent_at = ? WHERE id = ?",
                hash(challengeId, code), Timestamp.from(now.plus(TTL)), Timestamp.from(now), challengeId);
        boolean delivered = send(u, code);
        return new Challenge(challengeId, mask(u.getEmail()), now.plus(TTL), now.plus(RESEND_COOLDOWN), delivered);
    }

    /** Checks the code; on success marks the challenge used (single use). Throws a user-facing error otherwise. */
    @Transactional(noRollbackFor = ApiException.class)
    public Verified verify(UUID challengeId, String code, java.util.function.Consumer<UUID> onWrongCode) {
        Map<String, Object> c = lock(challengeId);
        if (c.get("consumed_at") != null) throw used();
        if (((Timestamp) c.get("expires_at")).toInstant().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.GONE, "code_expired", "Kodun süresi doldu. “Kodu yeniden gönder” ile yeni kod isteyin.");
        }
        int attempts = (Integer) c.get("attempts");
        if (attempts >= MAX_ATTEMPTS) throw tooManyAttempts();
        String given = code == null ? "" : code.trim();
        boolean ok = given.matches("\\d{6}") && MessageDigest.isEqual(
                hash(challengeId, given).getBytes(), ((String) c.get("code_hash")).getBytes());
        UUID userId = (UUID) c.get("user_id");
        if (!ok) {
            jdbc.update("UPDATE login_challenge SET attempts = attempts + 1 WHERE id = ?", challengeId);
            onWrongCode.accept(userId);
            int left = MAX_ATTEMPTS - attempts - 1;
            if (left <= 0) throw tooManyAttempts();
            throw new ApiException(HttpStatus.UNAUTHORIZED, "bad_code",
                    "Kod hatalı. E-postadaki 6 haneli kodu kontrol edin (" + left + " deneme hakkınız kaldı).", Map.of("attemptsLeft", left));
        }
        jdbc.update("UPDATE login_challenge SET consumed_at = now() WHERE id = ?", challengeId);
        audit.record(userId, "user.login_code_verified", "user", userId, Map.of("audience", c.get("audience")));
        return new Verified(userId, (String) c.get("audience"));
    }

    private Map<String, Object> lock(UUID id) {
        return jdbc.queryForList("SELECT * FROM login_challenge WHERE id = ? FOR UPDATE", id).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "challenge_not_found", "Giriş adımı bulunamadı. Şifrenizle yeniden giriş yapın."));
    }

    private boolean send(User u, String code) {
        // the code is only in the body: subjects show up on lock screens and in notification previews
        return mail.send(u.getEmail(), "Basamak giriş kodunuz",
                "Merhaba " + u.getDisplayName() + ",\n\nBasamak'a giriş kodunuz: " + code
                        + "\n\nKod 10 dakika geçerlidir ve yalnız bir kez kullanılabilir."
                        + "\nBu girişi siz yapmadıysanız şifrenizi değiştirin; kodu kimseyle paylaşmayın.");
    }

    private User user(UUID id) {
        return jdbc.query("SELECT id, email, display_name FROM users WHERE id = ?", (rs, i) -> {
            User u = new User();
            u.setId((UUID) rs.getObject(1));
            u.setEmail(rs.getString(2));
            u.setDisplayName(rs.getString(3));
            return u;
        }, id).stream().findFirst().orElseThrow();
    }

    String hash(UUID challengeId, String code) {
        return CryptoService.hmacSha256(secret, challengeId + ":" + code);
    }

    private static String newCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    /** "deniz.can@example.com" -> "de•••••••@example.com" */
    static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 0) return "•••";
        String local = email.substring(0, at);
        String keep = local.substring(0, Math.min(2, local.length()));
        return keep + "•".repeat(Math.max(3, local.length() - keep.length())) + email.substring(at);
    }

    private static ApiException used() {
        return new ApiException(HttpStatus.GONE, "code_used", "Bu kod zaten kullanıldı. Şifrenizle yeniden giriş yapın.");
    }

    private static ApiException tooManyAttempts() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts",
                "Çok fazla hatalı kod girildi. Güvenliğiniz için şifrenizle yeniden giriş yapın.");
    }
}
