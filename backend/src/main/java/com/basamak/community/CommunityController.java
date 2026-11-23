package com.basamak.community;

import com.basamak.auth.AuthPrincipal;
import com.basamak.auth.Role;
import com.basamak.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequiredArgsConstructor
public class CommunityController {
    private static final Set<Role> MODERATORS = EnumSet.of(Role.EDITOR, Role.PUBLISHER, Role.ADMIN);
    private final CommunityService community;

    @GetMapping("/api/me/profile-settings")
    public CommunityService.Settings settings(@AuthenticationPrincipal AuthPrincipal p) {
        return community.settings(p.userId());
    }

    @PutMapping("/api/me/profile-settings")
    public CommunityService.Settings saveSettings(@AuthenticationPrincipal AuthPrincipal p, @RequestBody CommunityService.Settings s) {
        return community.saveSettings(p.userId(), s);
    }

    @GetMapping("/api/profiles/{id}")
    public Map<String, Object> profile(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        return community.profile(p.userId(), id);
    }

    @PostMapping("/api/profiles/{id}/follow")
    public Map<String, Object> follow(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        community.follow(p.userId(), id);
        return community.profile(p.userId(), id);
    }

    @DeleteMapping("/api/profiles/{id}/follow")
    public Map<String, Object> unfollow(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        community.unfollow(p.userId(), id);
        return Map.of("ok", true);
    }

    @GetMapping("/api/feed")
    public List<Map<String, Object>> feed(@AuthenticationPrincipal AuthPrincipal p, @RequestParam(required = false) Long before,
                                          @RequestParam(defaultValue = "20") int limit) {
        return community.feed(p.userId(), before, limit);
    }

    @GetMapping("/api/comments")
    public List<Map<String, Object>> comments(@AuthenticationPrincipal AuthPrincipal p, @RequestParam String targetType, @RequestParam UUID targetId) {
        return community.comments(p.userId(), targetType, targetId);
    }

    public record CommentReq(String targetType, UUID targetId, Long parentId, String body) {}

    @PostMapping("/api/comments")
    public Map<String, Object> add(@AuthenticationPrincipal AuthPrincipal p, @RequestBody CommentReq r) {
        if (r.targetType() == null || r.targetId() == null) throw ApiException.badRequest("bad_target", "Yorumun nereye yazıldığı eksik.");
        return community.addComment(p.userId(), r.targetType(), r.targetId(), r.parentId(), r.body());
    }

    @DeleteMapping("/api/comments/{id}")
    public Map<String, Object> delete(@AuthenticationPrincipal AuthPrincipal p, @PathVariable long id) {
        community.deleteOwn(p.userId(), id);
        return Map.of("ok", true);
    }

    @PostMapping("/api/comments/{id}/report")
    public Map<String, Object> report(@AuthenticationPrincipal AuthPrincipal p, @PathVariable long id, @RequestBody Map<String, String> body) {
        community.report(p.userId(), id, body.get("reason"));
        return Map.of("ok", true);
    }

    @GetMapping("/api/workflows/{id}/rating")
    public Map<String, Object> rating(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        return community.rating(p.userId(), id);
    }

    @PutMapping("/api/workflows/{id}/rating")
    public Map<String, Object> rate(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id, @RequestBody Map<String, Integer> body) {
        return community.rate(p.userId(), id, body.getOrDefault("stars", 0));
    }

    // ---------------------------------------------------------------- CMS moderation

    @GetMapping("/api/cms/moderation/reports")
    public List<Map<String, Object>> reports(@AuthenticationPrincipal AuthPrincipal p) {
        mod(p);
        return community.openReports();
    }

    @PostMapping("/api/cms/moderation/comments/{id}/hide")
    public Map<String, Object> hide(@AuthenticationPrincipal AuthPrincipal p, @PathVariable long id, @RequestBody(required = false) Map<String, String> body) {
        mod(p);
        return community.hide(p.userId(), id, body == null ? null : body.get("reason"));
    }

    @PostMapping("/api/cms/moderation/comments/{id}/dismiss")
    public Map<String, Object> dismiss(@AuthenticationPrincipal AuthPrincipal p, @PathVariable long id) {
        mod(p);
        community.dismiss(p.userId(), id);
        return Map.of("ok", true);
    }

    @PostMapping("/api/cms/moderation/users/{id}/restrict")
    public Map<String, Object> restrict(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id, @RequestBody Map<String, Object> body) {
        mod(p);
        community.restrict(p.userId(), id, ((Number) body.getOrDefault("days", 7)).intValue(), (String) body.get("reason"));
        return Map.of("ok", true);
    }

    private static void mod(AuthPrincipal p) {
        if (p.roles().stream().noneMatch(MODERATORS::contains)) throw ApiException.forbidden("Moderasyonu editör, yayıncı ya da yönetici yapabilir.");
    }
}
