package com.basamak.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, UUID> {
    Optional<RefreshSession> findByTokenHash(String tokenHash);

    /** Row lock for rotation: two concurrent refreshes with the same token cannot both rotate it. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from RefreshSession s where s.tokenHash = ?1")
    Optional<RefreshSession> lockByTokenHash(String tokenHash);

    List<RefreshSession> findByUserIdAndRevokedAtIsNullOrderByLastUsedAtDesc(UUID userId);
}
