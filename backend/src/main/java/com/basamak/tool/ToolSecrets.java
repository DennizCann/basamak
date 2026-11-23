package com.basamak.tool;

import com.basamak.common.ApiException;
import com.basamak.common.CryptoService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Tool secrets, encrypted at rest (AES-256-GCM). The plain value is accepted once and is never returned by any API:
 * reads give only the name and when it was last set. Values are decrypted only inside the worker for the request.
 */
@Service
@RequiredArgsConstructor
public class ToolSecrets {
    public enum Scope { CONTENT, USER }

    private final JdbcTemplate jdbc;
    private final CryptoService crypto;

    public void set(Scope scope, UUID toolId, String name, String value, UUID actor) {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]{0,59}")) throw ApiException.badRequest("invalid_secret", "Sır adı harfle başlamalı; harf, rakam ve _ içerebilir.");
        if (value == null || value.isEmpty() || value.length() > 4000) throw ApiException.badRequest("invalid_secret", "Sır değeri boş olamaz (en fazla 4000 karakter).");
        jdbc.update("""
                INSERT INTO tool_secret(scope, tool_id, name, ciphertext, updated_by) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (scope, tool_id, name) DO UPDATE SET ciphertext = EXCLUDED.ciphertext, updated_by = EXCLUDED.updated_by, updated_at = now()""",
                scope.name(), toolId, name, crypto.encrypt(value), actor);
    }

    public boolean delete(Scope scope, UUID toolId, String name) {
        return jdbc.update("DELETE FROM tool_secret WHERE scope = ? AND tool_id = ? AND name = ?", scope.name(), toolId, name) > 0;
    }

    /** Names + last update only; values never leave the server. */
    public List<Map<String, Object>> list(Scope scope, UUID toolId) {
        return jdbc.queryForList("SELECT name, updated_at AS \"updatedAt\" FROM tool_secret WHERE scope = ? AND tool_id = ? ORDER BY name",
                scope.name(), toolId);
    }

    Map<String, String> decryptAll(Scope scope, UUID toolId) {
        Map<String, String> out = new HashMap<>();
        jdbc.query("SELECT name, ciphertext FROM tool_secret WHERE scope = ? AND tool_id = ?",
                rs -> { out.put(rs.getString("name"), crypto.decrypt(rs.getString("ciphertext"))); }, scope.name(), toolId);
        return out;
    }
}
