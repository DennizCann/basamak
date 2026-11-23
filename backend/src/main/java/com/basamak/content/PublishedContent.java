package com.basamak.content;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The ONLY way member-facing code reads content. It can return nothing but the currently published
 * version, so drafts and versions waiting for review can never leak to members (web, mobile or API).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublishedContent {
    private final ContentVersionRepository versions;

    public record Published(UUID itemId, UUID versionId, int versionNo, String title, JsonNode body, Instant publishedAt) {
        static Published of(ContentVersion v) {
            return new Published(v.getItemId(), v.getId(), v.getVersionNo(), v.getTitle(), v.getBody(), v.getPublishedAt());
        }
    }

    public List<Published> list(ContentType type) {
        return versions.findPublishedByType(type).stream().map(Published::of).toList();
    }

    public Optional<Published> get(UUID itemId, ContentType type) {
        return versions.findPublished(itemId, type).map(Published::of);
    }

    /** A specific historical version, used when an enrollment is pinned to the version it started on. */
    public Optional<Published> pinned(UUID itemId, int versionNo) {
        return versions.findByItemIdAndVersionNo(itemId, versionNo)
                .filter(v -> v.getPublishedAt() != null) // was published at some point
                .map(Published::of);
    }
}
