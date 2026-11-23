package com.basamak.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Append-only audit log (a DB trigger rejects UPDATE/DELETE). Joins the caller's transaction. */
@Service
@RequiredArgsConstructor
public class AuditService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public void record(UUID actorId, String action, String entityType, Object entityId, Map<String, ?> details) {
        try {
            jdbc.update("""
                    INSERT INTO audit_log(actor_id, action, entity_type, entity_id, details, request_id)
                    VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)""",
                    actorId, action, entityType, entityId == null ? null : entityId.toString(),
                    json.writeValueAsString(details == null ? Map.of() : details), MDC.get(RequestIdFilter.MDC_KEY));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Map<String, Object>> forEntity(String entityType, String entityId) {
        return jdbc.queryForList("""
                SELECT a.id, a.action, a.entity_type, a.entity_id, a.details::text AS details, a.created_at,
                       u.display_name AS actor
                FROM audit_log a LEFT JOIN users u ON u.id = a.actor_id
                WHERE a.entity_type = ? AND a.entity_id = ? ORDER BY a.id DESC LIMIT 200""", entityType, entityId);
    }
}
