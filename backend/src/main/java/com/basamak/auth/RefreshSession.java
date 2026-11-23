package com.basamak.auth;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One signed-in device/browser. The refresh token is stored only as a SHA-256 hash and rotated on every use. */
@Entity
@Table(name = "refresh_sessions")
@Getter
@Setter
public class RefreshSession {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID userId;
    private String tokenHash;
    private String audience;
    private String userAgent;
    private String ip;
    private Instant createdAt = Instant.now();
    private Instant lastUsedAt = Instant.now();
    private Instant expiresAt;
    private Instant revokedAt;

    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
