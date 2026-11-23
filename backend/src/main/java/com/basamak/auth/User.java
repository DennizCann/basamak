package com.basamak.auth;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String email;
    private String passwordHash;
    private String displayName;
    private String status = "ACTIVE";
    private Instant emailVerifiedAt;
    private int failedLoginCount;
    private Instant lockedUntil;
    private String totpSecretEnc;
    private boolean totpEnabled;
    private String consentVersion;
    private Instant consentAt;
    private Instant createdAt = Instant.now();
    private Instant deletedAt;
    @Version
    private long version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role")
    @Enumerated(EnumType.STRING)
    private Set<Role> roles = EnumSet.noneOf(Role.class);

    public boolean isStaff() {
        return roles.stream().anyMatch(Role.STAFF::contains);
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
