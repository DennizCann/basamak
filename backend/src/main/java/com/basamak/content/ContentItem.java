package com.basamak.content;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Stable identity of a piece of content. Every save creates a ContentVersion; this row only points at the
 * currently published version. lockVersion is the optimistic-lock token clients must echo back on save.
 */
@Entity
@Table(name = "content_item")
@Getter
@Setter
public class ContentItem {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Enumerated(EnumType.STRING)
    private ContentType type;
    private UUID ownerId;
    private UUID publishedVersionId;
    @Version
    @Column(name = "lock_version")
    private long lockVersion;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
