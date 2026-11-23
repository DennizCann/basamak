package com.basamak.auth;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_tokens")
@Getter
@Setter
public class EmailToken {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID userId;
    private String tokenHash;
    private String purpose;
    private Instant expiresAt;
    private Instant usedAt;
}
