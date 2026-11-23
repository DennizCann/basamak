package com.basamak.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Short-lived HS256 access tokens. Refresh tokens are opaque and live in refresh_sessions. */
@Service
public class JwtService {
    public static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    public static final Duration MFA_TTL = Duration.ofMinutes(5);
    private final SecretKey key;

    public JwtService(@Value("${basamak.jwt-secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret));
    }

    public String issueAccess(User u, String audience, UUID sessionId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(u.getId().toString())
                .claim("email", u.getEmail())
                .claim("roles", u.getRoles().stream().map(Enum::name).toList())
                .claim("sid", sessionId.toString())
                .audience().add(audience).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ACCESS_TTL)))
                .signWith(key)
                .compact();
    }

    /** Intermediate token after a correct CMS password; only usable on the TOTP endpoint. */
    public String issueMfaPending(User u) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(u.getId().toString())
                .audience().add("mfa").and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(MFA_TTL)))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public AuthPrincipal toPrincipal(Claims c) {
        @SuppressWarnings("unchecked")
        List<String> roles = c.get("roles", List.class);
        Set<Role> rs = roles == null ? EnumSet.noneOf(Role.class)
                : roles.stream().map(Role::valueOf).collect(Collectors.toCollection(() -> EnumSet.noneOf(Role.class)));
        String aud = c.getAudience().iterator().next();
        String sid = c.get("sid", String.class);
        return new AuthPrincipal(UUID.fromString(c.getSubject()), c.get("email", String.class), rs, aud,
                sid == null ? null : UUID.fromString(sid));
    }
}
