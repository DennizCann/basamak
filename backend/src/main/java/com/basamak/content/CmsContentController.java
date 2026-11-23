package com.basamak.content;

import com.basamak.auth.AuthPrincipal;
import com.basamak.auth.UserRepository;
import com.basamak.common.AuditService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/** CMS content API (requires a CMS token = password + TOTP). Role checks happen in ContentService. */
@RestController
@RequestMapping("/api/cms")
@RequiredArgsConstructor
public class CmsContentController {
    private final ContentService content;
    private final UserRepository users;
    private final AuditService audit;

    public record CreateReq(@NotNull ContentType type, @NotBlank @Size(max = 200) String title, @NotNull JsonNode body) {}

    public record SaveReq(long lockVersion, @NotBlank @Size(max = 200) String title, @NotNull JsonNode body, List<String> confirmRemovedKeys) {}

    public record NoteReq(String note) {}

    public record PublishReq(Instant scheduledAt) {}

    public record VersionDto(UUID id, UUID itemId, int versionNo, String status, String title, JsonNode body, Integer basedOnNo,
                             String createdBy, Instant createdAt, String reviewedBy, Instant reviewedAt, String reviewNote,
                             String publishedBy, Instant publishedAt, Instant scheduledAt) {}

    public record ItemDto(UUID id, String type, String owner, long lockVersion, Integer publishedVersionNo,
                          VersionDto latest, Instant updatedAt) {}

    @GetMapping("/items")
    public List<ItemDto> list(@RequestParam ContentType type) {
        return content.list(type).stream().map(this::itemDto).toList();
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemDto create(@AuthenticationPrincipal AuthPrincipal p, @Valid @RequestBody CreateReq r) {
        ContentVersion v = content.create(p, r.type(), r.title(), r.body());
        return itemDto(content.item(v.getItemId()));
    }

    @GetMapping("/items/{id}")
    public ItemDto get(@PathVariable UUID id) {
        return itemDto(content.item(id));
    }

    @PutMapping("/items/{id}/draft")
    public ItemDto save(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id, @Valid @RequestBody SaveReq r) {
        content.saveDraft(p, id, r.lockVersion(), r.title(), r.body(),
                r.confirmRemovedKeys() == null ? Set.of() : new HashSet<>(r.confirmRemovedKeys()));
        return itemDto(content.item(id));
    }

    @GetMapping("/items/{id}/versions")
    public List<VersionDto> history(@PathVariable UUID id) {
        List<ContentVersion> vs = content.history(id);
        Map<UUID, String> names = names(vs);
        return vs.stream().map(v -> dto(v, names)).toList();
    }

    @GetMapping("/items/{id}/versions/{no}")
    public VersionDto versionByNo(@PathVariable UUID id, @PathVariable int no) {
        ContentVersion v = content.history(id).stream().filter(x -> x.getVersionNo() == no).findFirst()
                .orElseThrow(() -> com.basamak.common.ApiException.notFound("Sürüm " + no));
        return dto(v, names(List.of(v)));
    }

    @GetMapping("/items/{id}/diff")
    public Map<String, Object> diff(@PathVariable UUID id, @RequestParam int from, @RequestParam int to) {
        return content.diff(id, from, to);
    }

    @GetMapping("/items/{id}/audit")
    public List<Map<String, Object>> auditTrail(@PathVariable UUID id) {
        ContentItem item = content.item(id);
        return audit.forEntity(item.getType().name(), id.toString());
    }

    public record ReasonReq(String reason) {}

    @PostMapping("/items/{id}/unpublish")
    public ItemDto unpublish(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id, @RequestBody ReasonReq r) {
        return itemDto(content.unpublish(p, id, r.reason()));
    }

    @PostMapping("/versions/{id}/submit")
    public VersionDto submit(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        return single(content.submit(p, id));
    }

    @PostMapping("/versions/{id}/approve")
    public VersionDto approve(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id, @RequestBody(required = false) NoteReq r) {
        return single(content.approve(p, id, r == null ? null : r.note()));
    }

    @PostMapping("/versions/{id}/request-changes")
    public VersionDto requestChanges(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id, @RequestBody NoteReq r) {
        return single(content.requestChanges(p, id, r.note()));
    }

    @PostMapping("/versions/{id}/publish")
    public VersionDto publish(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id, @RequestBody(required = false) PublishReq r) {
        return single(content.publish(p, id, r == null ? null : r.scheduledAt()));
    }

    // ---- mapping ----

    private ItemDto itemDto(ContentItem i) {
        ContentVersion latest = content.latest(i.getId());
        Integer pubNo = i.getPublishedVersionId() == null ? null : content.version(i.getPublishedVersionId()).getVersionNo();
        Map<UUID, String> names = names(List.of(latest));
        names.putAll(nameMap(Set.of(i.getOwnerId())));
        return new ItemDto(i.getId(), i.getType().name(), names.get(i.getOwnerId()), i.getLockVersion(), pubNo,
                dto(latest, names), i.getUpdatedAt());
    }

    private VersionDto single(ContentVersion v) {
        return dto(v, names(List.of(v)));
    }

    private VersionDto dto(ContentVersion v, Map<UUID, String> n) {
        return new VersionDto(v.getId(), v.getItemId(), v.getVersionNo(), v.getStatus().name(), v.getTitle(), v.getBody(),
                v.getBasedOnNo(), n.get(v.getCreatedBy()), v.getCreatedAt(), n.get(v.getReviewedBy()), v.getReviewedAt(),
                v.getReviewNote(), n.get(v.getPublishedBy()), v.getPublishedAt(), v.getScheduledAt());
    }

    private Map<UUID, String> names(List<ContentVersion> vs) {
        Set<UUID> ids = new HashSet<>();
        vs.forEach(v -> { ids.add(v.getCreatedBy()); ids.add(v.getReviewedBy()); ids.add(v.getPublishedBy()); });
        ids.remove(null);
        return nameMap(ids);
    }

    private Map<UUID, String> nameMap(Set<UUID> ids) {
        Map<UUID, String> m = new HashMap<>();
        users.findAllById(ids).forEach(u -> m.put(u.getId(), u.getDisplayName()));
        return m;
    }
}
