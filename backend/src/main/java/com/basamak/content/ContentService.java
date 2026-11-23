package com.basamak.content;

import com.basamak.auth.AuthPrincipal;
import com.basamak.auth.Role;
import com.basamak.common.ApiException;
import com.basamak.common.AuditService;
import com.basamak.common.JsonDiff;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Versioning + editorial workflow for every content type (journey, training, tool, workflow template, point rule).
 * Rules:
 *  - Every save creates a new immutable version (full JSONB snapshot); nothing is overwritten or deleted.
 *  - Saves carry the lockVersion the editor loaded; a stale value is rejected with 409 (no silent overwrite).
 *  - Author writes, editor reviews (cannot approve own version), publisher publishes / schedules / rolls back.
 *  - Every action is written to the append-only audit log.
 */
@Service
@RequiredArgsConstructor
public class ContentService {
    private static final Set<Role> WRITERS = EnumSet.of(Role.AUTHOR, Role.EDITOR, Role.ADMIN);
    private static final Set<Role> REVIEWERS = EnumSet.of(Role.EDITOR, Role.ADMIN);
    private static final Set<Role> PUBLISHERS = EnumSet.of(Role.PUBLISHER, Role.ADMIN);

    private final ContentItemRepository items;
    private final ContentVersionRepository versions;
    private final ContentValidator validator;
    private final AuditService audit;

    // ---------------- writing ----------------

    @Transactional
    public ContentVersion create(AuthPrincipal actor, ContentType type, String title, JsonNode body) {
        requireAny(actor, WRITERS, "İçerik oluşturmak için yazar, editör ya da yönetici rolü gerekir.");
        ContentItem item = new ContentItem();
        item.setType(type);
        item.setOwnerId(actor.userId());
        items.saveAndFlush(item);
        validator.validate(type, item.getId(), body);
        ContentVersion v = newVersion(item, 1, null, title, body, actor);
        audit.record(actor.userId(), "content.created", type.name(), item.getId(), Map.of("versionNo", 1, "title", title));
        return v;
    }

    @Transactional
    public ContentVersion saveDraft(AuthPrincipal actor, UUID itemId, long expectedLockVersion, String title, JsonNode body) {
        ContentItem item = item(itemId);
        requireAny(actor, WRITERS, "İçeriği düzenlemek için yazar, editör ya da yönetici rolü gerekir.");
        if (actor.has(Role.AUTHOR) && !actor.hasAny(Role.EDITOR, Role.ADMIN) && !item.getOwnerId().equals(actor.userId())) {
            throw ApiException.forbidden("Yazarlar yalnız kendi oluşturdukları içeriği düzenleyebilir.");
        }
        ContentVersion latest = versions.findTopByItemIdOrderByVersionNoDesc(itemId).orElseThrow();
        if (item.getLockVersion() != expectedLockVersion) {
            throw conflict(item, latest);
        }
        validator.validate(item.getType(), itemId, body);
        // older unsubmitted drafts are superseded by this save (they stay in history)
        versions.findByItemIdOrderByVersionNoDesc(itemId).stream()
                .filter(v -> v.getStatus() == VersionStatus.DRAFT || v.getStatus() == VersionStatus.CHANGES_REQUESTED)
                .forEach(v -> v.setStatus(VersionStatus.SUPERSEDED));
        touch(item); // bumps lockVersion; a concurrent save fails here with an optimistic-lock 409
        ContentVersion v = newVersion(item, latest.getVersionNo() + 1, latest.getVersionNo(), title, body, actor);
        audit.record(actor.userId(), "content.draft_saved", item.getType().name(), itemId,
                Map.of("versionNo", v.getVersionNo(), "basedOn", latest.getVersionNo()));
        return v;
    }

    // ---------------- review ----------------

    @Transactional
    public ContentVersion submit(AuthPrincipal actor, UUID versionId) {
        ContentVersion v = version(versionId);
        ContentItem item = item(v.getItemId());
        requireAny(actor, WRITERS, "İncelemeye göndermek için yazar, editör ya da yönetici rolü gerekir.");
        requireStatus(v, EnumSet.of(VersionStatus.DRAFT), "Yalnız taslak durumundaki sürüm incelemeye gönderilebilir.");
        requireLatest(v);
        v.setStatus(VersionStatus.IN_REVIEW);
        touch(item);
        audit.record(actor.userId(), "content.submitted", item.getType().name(), item.getId(), Map.of("versionNo", v.getVersionNo()));
        return v;
    }

    @Transactional
    public ContentVersion approve(AuthPrincipal actor, UUID versionId, String note) {
        ContentVersion v = version(versionId);
        ContentItem item = item(v.getItemId());
        requireAny(actor, REVIEWERS, "Onaylamak için editör ya da yönetici rolü gerekir.");
        requireStatus(v, EnumSet.of(VersionStatus.IN_REVIEW), "Yalnız incelemedeki sürüm onaylanabilir.");
        if (v.getCreatedBy().equals(actor.userId()) && !actor.has(Role.ADMIN)) {
            throw ApiException.forbidden("Kendi hazırladığınız sürümü onaylayamazsınız; başka bir editörün incelemesi gerekir.");
        }
        v.setStatus(VersionStatus.APPROVED);
        v.setReviewedBy(actor.userId());
        v.setReviewedAt(Instant.now());
        v.setReviewNote(note);
        touch(item);
        audit.record(actor.userId(), "content.approved", item.getType().name(), item.getId(), Map.of("versionNo", v.getVersionNo()));
        return v;
    }

    @Transactional
    public ContentVersion requestChanges(AuthPrincipal actor, UUID versionId, String note) {
        ContentVersion v = version(versionId);
        ContentItem item = item(v.getItemId());
        requireAny(actor, REVIEWERS, "Geri göndermek için editör ya da yönetici rolü gerekir.");
        requireStatus(v, EnumSet.of(VersionStatus.IN_REVIEW), "Yalnız incelemedeki sürüm geri gönderilebilir.");
        if (note == null || note.isBlank()) {
            throw ApiException.badRequest("note_required", "Geri gönderirken yazara neyi düzeltmesi gerektiğini açıklayan bir gerekçe yazın.");
        }
        v.setStatus(VersionStatus.CHANGES_REQUESTED);
        v.setReviewedBy(actor.userId());
        v.setReviewedAt(Instant.now());
        v.setReviewNote(note);
        touch(item);
        audit.record(actor.userId(), "content.changes_requested", item.getType().name(), item.getId(),
                Map.of("versionNo", v.getVersionNo(), "note", note));
        return v;
    }

    // ---------------- publishing ----------------

    /**
     * Publishes an approved version now, schedules it (scheduledAt in the future), or re-publishes a version that
     * was published before (rollback). Re-publishing does not need a new review: that exact snapshot was approved.
     */
    @Transactional
    public ContentVersion publish(AuthPrincipal actor, UUID versionId, Instant scheduledAt) {
        ContentVersion v = version(versionId);
        ContentItem item = item(v.getItemId());
        requireAny(actor, PUBLISHERS, "Yayınlamak için yayıncı ya da yönetici rolü gerekir.");
        if (v.getStatus() == VersionStatus.ARCHIVED) {
            doPublish(item, v, actor.userId(), "content.republished");
            return v;
        }
        requireStatus(v, EnumSet.of(VersionStatus.APPROVED, VersionStatus.SCHEDULED),
                "Yalnız editörün onayladığı ya da daha önce yayınlanmış bir sürüm yayınlanabilir.");
        validator.validateForPublish(item.getType(), v.getBody());
        if (scheduledAt != null && scheduledAt.isAfter(Instant.now())) {
            v.setStatus(VersionStatus.SCHEDULED);
            v.setScheduledAt(scheduledAt);
            v.setPublishedBy(actor.userId());
            touch(item);
            audit.record(actor.userId(), "content.scheduled", item.getType().name(), item.getId(),
                    Map.of("versionNo", v.getVersionNo(), "scheduledAt", scheduledAt.toString()));
            return v;
        }
        doPublish(item, v, actor.userId(), "content.published");
        return v;
    }

    /** Called by the scheduler for due SCHEDULED versions (rows locked with SKIP LOCKED). */
    @Transactional
    public int publishDue(Instant now) {
        List<ContentVersion> due = versions.lockDueScheduled(now);
        for (ContentVersion v : due) {
            doPublish(item(v.getItemId()), v, v.getPublishedBy(), "content.published_scheduled");
        }
        return due.size();
    }

    private void doPublish(ContentItem item, ContentVersion v, UUID actorId, String action) {
        if (item.getPublishedVersionId() != null && !item.getPublishedVersionId().equals(v.getId())) {
            versions.findById(item.getPublishedVersionId()).ifPresent(old -> old.setStatus(VersionStatus.ARCHIVED));
        }
        v.setStatus(VersionStatus.PUBLISHED);
        v.setPublishedAt(Instant.now());
        v.setPublishedBy(actorId);
        v.setScheduledAt(null);
        item.setPublishedVersionId(v.getId());
        touch(item);
        audit.record(actorId, action, item.getType().name(), item.getId(), Map.of("versionNo", v.getVersionNo()));
    }

    // ---------------- reading (CMS) ----------------

    @Transactional(readOnly = true)
    public ContentItem item(UUID id) {
        return items.findById(id).orElseThrow(() -> ApiException.notFound("İçerik"));
    }

    @Transactional(readOnly = true)
    public ContentVersion version(UUID id) {
        return versions.findById(id).orElseThrow(() -> ApiException.notFound("Sürüm"));
    }

    @Transactional(readOnly = true)
    public List<ContentItem> list(ContentType type) {
        return items.findByTypeOrderByCreatedAtAsc(type);
    }

    @Transactional(readOnly = true)
    public List<ContentVersion> history(UUID itemId) {
        item(itemId);
        return versions.findByItemIdOrderByVersionNoDesc(itemId);
    }

    @Transactional(readOnly = true)
    public ContentVersion latest(UUID itemId) {
        return versions.findTopByItemIdOrderByVersionNoDesc(itemId).orElseThrow(() -> ApiException.notFound("Sürüm"));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> diff(UUID itemId, int fromNo, int toNo) {
        ContentVersion a = versions.findByItemIdAndVersionNo(itemId, fromNo).orElseThrow(() -> ApiException.notFound("Sürüm " + fromNo));
        ContentVersion b = versions.findByItemIdAndVersionNo(itemId, toNo).orElseThrow(() -> ApiException.notFound("Sürüm " + toNo));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", fromNo);
        out.put("to", toNo);
        out.put("titleChanged", !a.getTitle().equals(b.getTitle()));
        out.put("fromTitle", a.getTitle());
        out.put("toTitle", b.getTitle());
        out.put("changes", JsonDiff.diff(a.getBody(), b.getBody()));
        return out;
    }

    // ---------------- helpers ----------------

    private ContentVersion newVersion(ContentItem item, int no, Integer basedOn, String title, JsonNode body, AuthPrincipal actor) {
        if (title == null || title.isBlank()) throw ApiException.badRequest("title_required", "Başlık boş olamaz.");
        ContentVersion v = new ContentVersion();
        v.setItemId(item.getId());
        v.setVersionNo(no);
        v.setBasedOnNo(basedOn);
        v.setStatus(VersionStatus.DRAFT);
        v.setTitle(title.trim());
        v.setBody(body);
        v.setCreatedBy(actor.userId());
        return versions.save(v);
    }

    private void touch(ContentItem item) {
        item.setUpdatedAt(Instant.now());
        items.saveAndFlush(item);
    }

    private void requireLatest(ContentVersion v) {
        ContentVersion latest = latest(v.getItemId());
        if (latest.getVersionNo() != v.getVersionNo()) {
            throw ApiException.conflict("not_latest", "Bu sürümden sonra yeni bir sürüm kaydedilmiş. Güncel sürümle devam edin.",
                    Map.of("latestVersionNo", latest.getVersionNo()));
        }
    }

    private ApiException conflict(ContentItem item, ContentVersion latest) {
        return ApiException.conflict("edit_conflict",
                "Bu içerik siz düzenlerken başka biri tarafından kaydedildi. Güncel sürümü yükleyip değişikliklerinizi yeniden uygulayın.",
                Map.of("currentLockVersion", item.getLockVersion(), "latestVersionNo", latest.getVersionNo(),
                        "latestCreatedBy", latest.getCreatedBy().toString()));
    }

    private static void requireAny(AuthPrincipal actor, Set<Role> roles, String message) {
        if (actor.roles().stream().noneMatch(roles::contains)) throw ApiException.forbidden(message);
    }

    private static void requireStatus(ContentVersion v, Set<VersionStatus> allowed, String message) {
        if (!allowed.contains(v.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "invalid_state", message + " (şu anki durum: " + v.getStatus() + ")");
        }
    }
}
