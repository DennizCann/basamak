package com.basamak.content;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class ContentRepositories {
    private ContentRepositories() {}
}

interface ContentItemRepository extends JpaRepository<ContentItem, UUID> {
    List<ContentItem> findByTypeOrderByCreatedAtAsc(ContentType type);
}

interface ContentVersionRepository extends JpaRepository<ContentVersion, UUID> {
    List<ContentVersion> findByItemIdOrderByVersionNoDesc(UUID itemId);

    Optional<ContentVersion> findTopByItemIdOrderByVersionNoDesc(UUID itemId);

    Optional<ContentVersion> findByItemIdAndVersionNo(UUID itemId, int versionNo);

    @Query(value = "SELECT * FROM content_version WHERE status = 'SCHEDULED' AND scheduled_at <= ?1 ORDER BY scheduled_at FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<ContentVersion> lockDueScheduled(Instant now);

    /** Only rows the published pointer refers to: the one and only query path members use. */
    @Query("select v from ContentVersion v, ContentItem i where i.publishedVersionId = v.id and i.type = ?1")
    List<ContentVersion> findPublishedByType(ContentType type);

    @Query("select v from ContentVersion v, ContentItem i where i.publishedVersionId = v.id and i.id = ?1 and i.type = ?2")
    Optional<ContentVersion> findPublished(UUID itemId, ContentType type);
}
