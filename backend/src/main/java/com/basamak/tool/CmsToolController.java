package com.basamak.tool;

import com.basamak.auth.AuthPrincipal;
import com.basamak.auth.Role;
import com.basamak.common.ApiException;
import com.basamak.common.AuditService;
import com.basamak.content.ContentItem;
import com.basamak.content.ContentService;
import com.basamak.content.ContentType;
import com.basamak.content.ContentVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * CMS side of tools. The definition itself is ordinary versioned content (type TOOL, editorial workflow);
 * this controller adds what must NOT be versioned or published: secrets, and the test console.
 */
@RestController
@RequestMapping("/api/cms/tools")
@RequiredArgsConstructor
public class CmsToolController {
    private static final Set<Role> WRITERS = EnumSet.of(Role.AUTHOR, Role.EDITOR, Role.ADMIN);

    private final ContentService content;
    private final ToolSecrets secrets;
    private final ToolExecutor executor;
    private final AuditService audit;
    private final ObjectMapper json;

    public record SecretReq(String value) {}

    public record TestReq(Integer versionNo, JsonNode input) {}

    @GetMapping("/{id}/secrets")
    public List<Map<String, Object>> list(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        tool(id);
        return secrets.list(ToolSecrets.Scope.CONTENT, id);
    }

    @PutMapping("/{id}/secrets/{name}")
    public List<Map<String, Object>> set(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id, @PathVariable String name, @RequestBody SecretReq r) {
        requireWriter(p);
        tool(id);
        secrets.set(ToolSecrets.Scope.CONTENT, id, name, r.value(), p.userId());
        audit.record(p.userId(), "tool.secret_set", "CONTENT_ITEM", id, Map.of("name", name)); // never the value
        return secrets.list(ToolSecrets.Scope.CONTENT, id);
    }

    @DeleteMapping("/{id}/secrets/{name}")
    public List<Map<String, Object>> delete(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id, @PathVariable String name) {
        requireWriter(p);
        tool(id);
        if (secrets.delete(ToolSecrets.Scope.CONTENT, id, name)) audit.record(p.userId(), "tool.secret_deleted", "CONTENT_ITEM", id, Map.of("name", name));
        return secrets.list(ToolSecrets.Scope.CONTENT, id);
    }

    /**
     * Test console: runs ONE attempt of the given (or latest, possibly unpublished) version synchronously and shows
     * the request (secrets masked), status, timing and answer. Same executor and same SSRF rules as real runs.
     */
    @PostMapping("/{id}/test")
    public Map<String, Object> test(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id, @RequestBody TestReq r) {
        if (p.roles().stream().noneMatch(Role.STAFF::contains)) throw ApiException.forbidden("Deneme konsolu yalnız içerik ekibine açık.");
        tool(id);
        ContentVersion v = r.versionNo() == null ? content.latest(id)
                : content.history(id).stream().filter(x -> x.getVersionNo() == r.versionNo()).findFirst().orElseThrow(() -> ApiException.notFound("Sürüm"));
        ToolDefinition def = ToolDefinition.parse(v.getBody());
        JsonNode input = def.validateInput(r.input(), json);
        ToolExecutor.Result res = executor.execute("test:" + UUID.randomUUID(), def, input, secrets.decryptAll(ToolSecrets.Scope.CONTENT, id), true);
        audit.record(p.userId(), "tool.tested", "CONTENT_ITEM", id, Map.of("versionNo", v.getVersionNo(), "outcome", res.outcome().name()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("versionNo", v.getVersionNo());
        out.put("outcome", res.outcome());
        out.put("httpStatus", res.httpStatus());
        out.put("durationMs", res.durationMs());
        out.put("request", res.request());
        out.put("output", res.output());
        out.put("error", res.error());
        out.put("missingSecrets", missing(def, id));
        return out;
    }

    private List<String> missing(ToolDefinition def, UUID id) {
        Set<String> have = new HashSet<>();
        secrets.list(ToolSecrets.Scope.CONTENT, id).forEach(m -> have.add((String) m.get("name")));
        return def.secretNames().stream().filter(n -> !have.contains(n)).toList();
    }

    private ContentItem tool(UUID id) {
        ContentItem item = content.item(id);
        if (item.getType() != ContentType.TOOL) throw ApiException.notFound("Araç");
        return item;
    }

    private static void requireWriter(AuthPrincipal p) {
        if (p.roles().stream().noneMatch(WRITERS::contains)) throw ApiException.forbidden("Sırları yalnız yazar, editör ya da yönetici düzenleyebilir.");
    }
}
