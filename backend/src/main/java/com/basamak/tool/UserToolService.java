package com.basamak.tool;

import com.basamak.common.ApiException;
import com.basamak.common.CryptoService;
import com.basamak.gamification.ActivityEvent;
import com.basamak.gamification.GamificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * A member's own tools. Saving is refused when the target is an internal address (scenario 7), and the same check
 * runs again for every request at execution time (DNS can change after saving). Each save keeps the previous
 * definition as a version, so runs always point at the exact definition they used.
 */
@Service
@RequiredArgsConstructor
public class UserToolService {
    private static final int MAX_TOOLS = 50;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final UrlGuard guard;
    private final ToolSecrets secrets;
    private final GamificationService gamification;

    public List<Map<String, Object>> list(UUID owner) {
        return jdbc.query("SELECT id, name, definition::text, version_no, updated_at FROM user_tool WHERE owner_id = ? AND deleted_at IS NULL ORDER BY updated_at DESC",
                (rs, i) -> view(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getTimestamp(5).toInstant()), owner);
    }

    public Map<String, Object> get(UUID owner, UUID id) {
        return jdbc.query("SELECT id, name, definition::text, version_no, updated_at FROM user_tool WHERE id = ? AND owner_id = ? AND deleted_at IS NULL",
                rs -> {
                    if (!rs.next()) throw ApiException.notFound("Araç");
                    return view(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getTimestamp(5).toInstant());
                }, id, owner);
    }

    @Transactional
    public Map<String, Object> create(UUID owner, String name, JsonNode definition, Map<String, String> secretValues) {
        String n = checkName(name);
        ToolDefinition def = validate(definition);
        Integer count = jdbc.queryForObject("SELECT count(*) FROM user_tool WHERE owner_id = ? AND deleted_at IS NULL", Integer.class, owner);
        if (count != null && count >= MAX_TOOLS) throw ApiException.badRequest("too_many_tools", "En fazla " + MAX_TOOLS + " aracınız olabilir.");
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO user_tool(id, owner_id, name, definition, def_hash) VALUES (?, ?, ?, CAST(? AS jsonb), ?)",
                    id, owner, n, definition.toString(), hash(definition));
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "duplicate_tool", "Aynı tanımla bir aracınız zaten var. Mevcut aracı düzenleyin.");
        }
        jdbc.update("INSERT INTO user_tool_version(tool_id, version_no, definition) VALUES (?, 1, CAST(? AS jsonb))", id, definition.toString());
        saveSecrets(owner, id, def, secretValues);
        // Anti-farming: a tool without input fields, or a copy of another member's definition, is saved but earns no
        // creation points (the event is still in "Geçmişim"; recorded under an action no point rule matches).
        boolean trivial = def.inputs().isEmpty();
        boolean copied = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM user_tool WHERE def_hash = ? AND owner_id <> ? AND deleted_at IS NULL)", Boolean.class, hash(definition), owner));
        gamification.record(ActivityEvent.web(owner, "tool-created:" + id, trivial || copied ? "TOOL_CREATED_NO_POINTS" : "TOOL_CREATED", "TOOL", id.toString(), 1));
        return get(owner, id);
    }

    @Transactional
    public Map<String, Object> update(UUID owner, UUID id, String name, JsonNode definition, Map<String, String> secretValues) {
        get(owner, id); // ownership
        String n = checkName(name);
        ToolDefinition def = validate(definition);
        Integer next;
        try {
            next = jdbc.queryForObject("""
                    UPDATE user_tool SET name = ?, definition = CAST(? AS jsonb), def_hash = ?, version_no = version_no + 1, updated_at = now()
                     WHERE id = ? AND owner_id = ? RETURNING version_no""", Integer.class, n, definition.toString(), hash(definition), id, owner);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "duplicate_tool", "Aynı tanımla başka bir aracınız var.");
        }
        jdbc.update("INSERT INTO user_tool_version(tool_id, version_no, definition) VALUES (?, ?, CAST(? AS jsonb))", id, next, definition.toString());
        saveSecrets(owner, id, def, secretValues);
        return get(owner, id);
    }

    @Transactional
    public void delete(UUID owner, UUID id) {
        get(owner, id);
        jdbc.update("UPDATE user_tool SET deleted_at = now() WHERE id = ?", id);
        jdbc.update("DELETE FROM tool_secret WHERE scope = 'USER' AND tool_id = ?", id);
    }

    private void saveSecrets(UUID owner, UUID id, ToolDefinition def, Map<String, String> values) {
        if (values == null) return;
        values.forEach((k, v) -> {
            if (!def.secretNames().contains(k)) throw ApiException.badRequest("invalid_secret", "'" + k + "' sırrı araç tanımında kullanılmıyor.");
            if (v != null && !v.isEmpty()) secrets.set(ToolSecrets.Scope.USER, id, k, v, owner); // empty = keep the stored value
        });
    }

    /** Structure + SSRF check of the fixed part of the URL. Member tools are never "trusted". */
    private ToolDefinition validate(JsonNode definition) {
        ToolDefinition def = ToolDefinition.parse(definition);
        guard.check(def.staticUrlForCheck(), false);
        return def;
    }

    private static String checkName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 120) throw ApiException.badRequest("invalid_tool", "Araç adı 1-120 karakter olmalı.");
        return n;
    }

    private static String hash(JsonNode def) {
        return CryptoService.sha256(def.toString());
    }

    private Map<String, Object> view(UUID id, String name, String def, int version, java.time.Instant updatedAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        try {
            JsonNode d = json.readTree(def);
            m.put("definition", d);
            m.put("secretNames", ToolDefinition.parse(d).secretNames());
        } catch (Exception e) {
            m.put("definition", null);
        }
        m.put("secrets", secrets.list(ToolSecrets.Scope.USER, id));
        m.put("versionNo", version);
        m.put("updatedAt", updatedAt);
        return m;
    }
}
