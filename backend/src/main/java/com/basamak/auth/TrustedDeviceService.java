package com.basamak.auth;

import com.basamak.common.CryptoService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * "Remember this device" for the member e-mail code. The device keeps a random token (HttpOnly cookie on web,
 * secure storage on mobile); the server keeps only its SHA-256. A remembered device skips the code step for 30 days;
 * the password is still always required. A password reset revokes every remembered device of the account.
 */
@Service
@RequiredArgsConstructor
public class TrustedDeviceService {
    static final Duration TTL = Duration.ofDays(30);
    private final JdbcTemplate jdbc;

    public String remember(UUID userId, String userAgent) {
        String raw = CryptoService.randomToken();
        jdbc.update("INSERT INTO trusted_device(user_id, token_hash, user_agent, expires_at) VALUES (?, ?, ?, ?)",
                userId, CryptoService.sha256(raw), userAgent == null ? null : userAgent.substring(0, Math.min(300, userAgent.length())),
                Timestamp.from(Instant.now().plus(TTL)));
        return raw;
    }

    /** True only for a valid, unexpired, unrevoked token that belongs to this very user. */
    public boolean isTrusted(UUID userId, String raw) {
        if (raw == null || raw.isBlank()) return false;
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM trusted_device
                WHERE token_hash = ? AND user_id = ? AND revoked_at IS NULL AND expires_at > now()""",
                Integer.class, CryptoService.sha256(raw), userId);
        return n != null && n > 0;
    }

    public void revokeAll(UUID userId) {
        jdbc.update("UPDATE trusted_device SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL", userId);
    }
}
