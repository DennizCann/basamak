package com.basamak.content;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** Immutable full snapshot of the content (JSONB) plus its review/publish metadata. Never deleted. */
@Entity
@Table(name = "content_version")
@Getter
@Setter
public class ContentVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID itemId;
    private int versionNo;
    @Enumerated(EnumType.STRING)
    private VersionStatus status;
    private String title;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode body;
    private Integer basedOnNo;
    private UUID createdBy;
    private Instant createdAt = Instant.now();
    private UUID reviewedBy;
    private Instant reviewedAt;
    private String reviewNote;
    private UUID publishedBy;
    private Instant publishedAt;
    private Instant scheduledAt;
}
