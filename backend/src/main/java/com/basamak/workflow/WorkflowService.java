package com.basamak.workflow;

import com.basamak.common.ApiException;
import com.basamak.common.CryptoService;
import com.basamak.content.PublishedContent;
import com.basamak.gamification.ActivityEvent;
import com.basamak.gamification.GamificationService;
import com.basamak.tool.ToolRunService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Member workflows: save (with validation and cycle detection), share, fork, cron and webhook triggers.
 * Sharing rule: a PUBLIC workflow may use only CMS tools (platform keys), never a member's own tool, so a fork or
 * a sub-flow call never spends the author's API key. Forking credits the author (SHARED_ITEM_FORKED).
 */
@Service
@RequiredArgsConstructor
public class WorkflowService {
    private static final int MAX_WORKFLOWS = 50;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ToolRunService tools;
    private final PublishedContent published;
    private final GamificationService gamification;

    public record Wf(UUID id, UUID ownerId, String ownerName, String name, String description, JsonNode definition, int versionNo,
                     String visibility, UUID forkedFrom, int forkCount, boolean hasWebhook, String cron, JsonNode cronInput,
                     java.time.Instant nextRunAt, java.time.Instant updatedAt) {}

    // ------------------------------------------------------------------ read

    public Wf get(UUID id) {
        List<Wf> l = jdbc.query("""
                SELECT w.*, u.display_name FROM workflow w JOIN users u ON u.id = w.owner_id WHERE w.id = ? AND w.deleted_at IS NULL""",
                (rs, i) -> map(rs), id);
        if (l.isEmpty()) throw ApiException.notFound("İş akışı");
        return l.get(0);
    }

    /** Owner sees everything; others only PUBLIC workflows. */
    public Wf visible(UUID viewer, UUID id) {
        Wf w = get(id);
        if (!w.ownerId().equals(viewer) && !"PUBLIC".equals(w.visibility())) throw ApiException.notFound("İş akışı");
        return w;
    }

    public Wf owned(UUID owner, UUID id) {
        Wf w = get(id);
        if (!w.ownerId().equals(owner)) throw ApiException.notFound("İş akışı");
        return w;
    }

    public List<Wf> mine(UUID owner) {
        return jdbc.query("""
                SELECT w.*, u.display_name FROM workflow w JOIN users u ON u.id = w.owner_id
                 WHERE w.owner_id = ? AND w.deleted_at IS NULL ORDER BY w.updated_at DESC""", (rs, i) -> map(rs), owner);
    }

    public List<Wf> community(int limit) {
        return jdbc.query("""
                SELECT w.*, u.display_name FROM workflow w JOIN users u ON u.id = w.owner_id
                 WHERE w.visibility = 'PUBLIC' AND w.deleted_at IS NULL AND u.status = 'ACTIVE'
                 ORDER BY w.fork_count DESC, w.updated_at DESC LIMIT ?""", (rs, i) -> map(rs), Math.min(limit, 100));
    }

    public JsonNode definitionAt(UUID id, int versionNo) {
        String d = jdbc.query("SELECT definition::text FROM workflow_version WHERE workflow_id = ? AND version_no = ?",
                rs -> rs.next() ? rs.getString(1) : null, id, versionNo);
        if (d == null) throw ApiException.notFound("İş akışı sürümü");
        return read(d);
    }

    // ------------------------------------------------------------------ write

    @Transactional
    public Wf create(UUID owner, String name, String description, JsonNode definition) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM workflow WHERE owner_id = ? AND deleted_at IS NULL", Integer.class, owner);
        if (n != null && n >= MAX_WORKFLOWS) throw ApiException.badRequest("too_many_workflows", "En fazla " + MAX_WORKFLOWS + " iş akışınız olabilir.");
        UUID id = UUID.randomUUID();
        JsonNode def = validate(owner, id, definition, false);
        jdbc.update("INSERT INTO workflow(id, owner_id, name, description, definition) VALUES (?, ?, ?, ?, CAST(? AS jsonb))",
                id, owner, checkName(name), desc(description), def.toString());
        jdbc.update("INSERT INTO workflow_version(workflow_id, version_no, definition) VALUES (?, 1, CAST(? AS jsonb))", id, def.toString());
        gamification.record(ActivityEvent.web(owner, "workflow-created:" + id, "WORKFLOW_CREATED", "WORKFLOW", id.toString(), 1));
        return get(id);
    }

    @Transactional
    public Wf update(UUID owner, UUID id, String name, String description, JsonNode definition) {
        Wf w = owned(owner, id);
        JsonNode def = validate(owner, id, definition, "PUBLIC".equals(w.visibility()));
        Integer v = jdbc.queryForObject("""
                UPDATE workflow SET name = ?, description = ?, definition = CAST(? AS jsonb), version_no = version_no + 1, updated_at = now()
                 WHERE id = ? RETURNING version_no""", Integer.class, checkName(name), desc(description), def.toString(), id);
        jdbc.update("INSERT INTO workflow_version(workflow_id, version_no, definition) VALUES (?, ?, CAST(? AS jsonb))", id, v, def.toString());
        return get(id);
    }

    @Transactional
    public void delete(UUID owner, UUID id) {
        owned(owner, id);
        jdbc.update("UPDATE workflow SET deleted_at = now(), cron = NULL, next_run_at = NULL, webhook_token_hash = NULL WHERE id = ?", id);
    }

    @Transactional
    public Wf setVisibility(UUID owner, UUID id, boolean shared) {
        Wf w = owned(owner, id);
        if (shared) {
            if (WorkflowDefinition.parse(w.definition()).usesUserTools()) {
                throw ApiException.badRequest("private_tools", "Kendi araçlarınızı kullanan bir akış paylaşılamaz (anahtarınız başkasınca harcanmasın). Paylaşmak için yalnız platform araçlarını kullanın.");
            }
            for (UUID sub : WorkflowDefinition.parse(w.definition()).subflowIds()) {
                if (!"PUBLIC".equals(get(sub).visibility())) {
                    throw ApiException.badRequest("private_subflow", "Bu akış paylaşılmamış bir alt akış (“" + get(sub).name() + "”) kullanıyor. Önce onu paylaşın.");
                }
            }
            jdbc.update("UPDATE workflow SET visibility = 'PUBLIC', updated_at = now() WHERE id = ?", id);
            gamification.record(ActivityEvent.web(owner, "shared:workflow:" + id, "ITEM_SHARED", "WORKFLOW", id.toString(), w.versionNo()));
        } else {
            jdbc.update("UPDATE workflow SET visibility = 'PRIVATE', updated_at = now() WHERE id = ?", id);
        }
        return get(id);
    }

    /** Copies a PUBLIC workflow (current version) into the member's own list; the author earns points once per fork. */
    @Transactional
    public Wf fork(UUID user, UUID sourceId) {
        Wf src = visible(user, sourceId);
        if (src.ownerId().equals(user)) throw ApiException.badRequest("own_workflow", "Kendi iş akışınızı çatallayamazsınız; kopyasını düzenleyebilirsiniz.");
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO workflow(id, owner_id, name, description, definition, forked_from) VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)""",
                id, user, cut(src.name() + " (çatal)", 120), src.description(), src.definition().toString(), src.id());
        jdbc.update("INSERT INTO workflow_version(workflow_id, version_no, definition) VALUES (?, 1, CAST(? AS jsonb))", id, src.definition().toString());
        jdbc.update("UPDATE workflow SET fork_count = fork_count + 1 WHERE id = ?", src.id());
        gamification.record(ActivityEvent.web(src.ownerId(), "forked:" + id, "SHARED_ITEM_FORKED", "WORKFLOW", id.toString(), src.versionNo()));
        return get(id);
    }

    // ------------------------------------------------------------------ triggers

    /** Returns the plain webhook token ONCE; only its SHA-256 is stored. */
    @Transactional
    public String rotateWebhook(UUID owner, UUID id) {
        owned(owner, id);
        String token = CryptoService.randomToken();
        jdbc.update("UPDATE workflow SET webhook_token_hash = ?, updated_at = now() WHERE id = ?", CryptoService.sha256(token), id);
        return token;
    }

    @Transactional
    public Wf disableWebhook(UUID owner, UUID id) {
        owned(owner, id);
        jdbc.update("UPDATE workflow SET webhook_token_hash = NULL WHERE id = ?", id);
        return get(id);
    }

    public Optional<Wf> byWebhookToken(String token) {
        if (token == null || token.length() < 20) return Optional.empty();
        List<Wf> l = jdbc.query("""
                SELECT w.*, u.display_name FROM workflow w JOIN users u ON u.id = w.owner_id
                 WHERE w.webhook_token_hash = ? AND w.deleted_at IS NULL AND u.status = 'ACTIVE'""", (rs, i) -> map(rs), CryptoService.sha256(token));
        return l.stream().findFirst();
    }

    /** 5-field cron (minute hour day month weekday) in Europe/Istanbul; at most once a minute. null/blank turns it off. */
    @Transactional
    public Wf setCron(UUID owner, UUID id, String cron, JsonNode input) {
        Wf w = owned(owner, id);
        if (cron == null || cron.isBlank()) {
            jdbc.update("UPDATE workflow SET cron = NULL, cron_input = NULL, next_run_at = NULL WHERE id = ?", id);
            return get(id);
        }
        String c = cron.trim().replaceAll("\\s+", " ");
        if (c.split(" ").length != 5) throw ApiException.badRequest("invalid_cron", "Zamanlama 5 alanlı olmalı: dakika saat gün ay haftanın-günü (ör. */15 * * * *).");
        CronExpression expr;
        try {
            expr = CronExpression.parse("0 " + c);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_cron", "Zamanlama ifadesi geçersiz: " + e.getMessage());
        }
        JsonNode validated = WorkflowDefinition.parse(w.definition()).inputs().isEmpty() ? json.createObjectNode() : validateInput(w.definition(), input);
        ZonedDateTime next = expr.next(ZonedDateTime.now(GamificationService.ISTANBUL));
        jdbc.update("UPDATE workflow SET cron = ?, cron_input = CAST(? AS jsonb), next_run_at = ? WHERE id = ?",
                c, validated.toString(), Timestamp.from(next.toInstant()), id);
        return get(id);
    }

    public static ZonedDateTime nextCron(String cron) {
        return CronExpression.parse("0 " + cron).next(ZonedDateTime.now(GamificationService.ISTANBUL));
    }

    // ------------------------------------------------------------------ validation

    public JsonNode validateInput(JsonNode definition, JsonNode input) {
        ObjectNode forTool = json.createObjectNode();
        forTool.set("inputs", definition.path("inputs"));
        forTool.putObject("request").put("method", "GET").put("url", "https://example.invalid/");
        return com.basamak.tool.ToolDefinition.parse(forTool).validateInput(input, json);
    }

    /**
     * Structure, access to every referenced tool / sub-flow, pins member-tool versions, and rejects direct or
     * indirect cycles through sub-flows (A -> B -> C -> A), using the current definitions of all involved workflows.
     */
    JsonNode validate(UUID owner, UUID selfId, JsonNode definition, boolean isPublic) {
        WorkflowDefinition def = WorkflowDefinition.parse(definition);
        ObjectNode out = definition.deepCopy();
        var steps = (com.fasterxml.jackson.databind.node.ArrayNode) out.path("steps");
        for (int i = 0; i < steps.size(); i++) {
            JsonNode s = steps.get(i);
            if (!"tool".equals(s.path("type").asText())) continue;
            ObjectNode t = (ObjectNode) s.path("tool");
            UUID toolId;
            try {
                toolId = UUID.fromString(t.path("id").asText());
            } catch (IllegalArgumentException e) {
                throw WorkflowDefinition.bad("'" + s.path("id").asText() + "' adımında geçersiz araç kimliği.");
            }
            if ("CONTENT".equals(t.path("scope").asText())) {
                int v = t.path("versionNo").asInt(0);
                if (v == 0) v = published.get(toolId, com.basamak.content.ContentType.TOOL)
                        .orElseThrow(() -> WorkflowDefinition.bad("'" + s.path("id").asText() + "' adımındaki platform aracı yayında değil.")).versionNo();
                tools.contentTool(toolId, v); // must be a published (pinned) version
                t.put("versionNo", v);
            } else {
                if (isPublic) throw ApiException.badRequest("private_tools", "Paylaşılan bir akışa kendi aracınız eklenemez.");
                t.put("versionNo", tools.currentUserToolVersion(owner, toolId)); // pin the current version of my tool
            }
        }
        for (UUID sub : def.subflowIds()) {
            if (sub.equals(selfId)) throw ApiException.badRequest("workflow_cycle", "Bir iş akışı kendisini alt akış olarak çağıramaz.");
            visible(owner, sub); // own or PUBLIC
        }
        List<UUID> cycle = findCycle(selfId, def.subflowIds());
        if (!cycle.isEmpty()) {
            List<String> names = cycle.stream().map(id -> id.equals(selfId) ? "bu akış" : nameOf(id)).toList();
            throw ApiException.badRequest("workflow_cycle", "Alt akışlar döngü oluşturuyor: " + String.join(" → ", names) + ". Bu akış kaydedilemez.");
        }
        return out;
    }

    List<UUID> findCycle(UUID self, Set<UUID> newEdges) {
        Deque<UUID> path = new ArrayDeque<>();
        path.add(self);
        return dfs(self, self, newEdges, path, new HashSet<>()) ? new ArrayList<>(path) : List.of();
    }

    private boolean dfs(UUID start, UUID node, Set<UUID> edgesOfNode, Deque<UUID> path, Set<UUID> seen) {
        for (UUID next : edgesOfNode) {
            path.addLast(next);
            if (next.equals(start)) return true;
            if (seen.add(next) && dfs(start, next, edgesOf(next), path, seen)) return true;
            path.removeLast();
        }
        return false;
    }

    private Set<UUID> edgesOf(UUID id) {
        String d = jdbc.query("SELECT definition::text FROM workflow WHERE id = ? AND deleted_at IS NULL", rs -> rs.next() ? rs.getString(1) : null, id);
        if (d == null) return Set.of();
        Set<UUID> out = new LinkedHashSet<>();
        for (JsonNode s : read(d).path("steps")) {
            if ("workflow".equals(s.path("type").asText())) {
                try { out.add(UUID.fromString(s.path("workflowId").asText())); } catch (IllegalArgumentException ignored) { }
            }
        }
        return out;
    }

    private String nameOf(UUID id) {
        return jdbc.query("SELECT name FROM workflow WHERE id = ?", rs -> rs.next() ? "“" + rs.getString(1) + "”" : id.toString(), id);
    }

    // ------------------------------------------------------------------ helpers

    private Wf map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp next = rs.getTimestamp("next_run_at");
        String cronInput = rs.getString("cron_input");
        return new Wf(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class), rs.getString("display_name"), rs.getString("name"),
                rs.getString("description"), read(rs.getString("definition")), rs.getInt("version_no"), rs.getString("visibility"),
                rs.getObject("forked_from", UUID.class), rs.getInt("fork_count"), rs.getString("webhook_token_hash") != null,
                rs.getString("cron"), cronInput == null ? null : read(cronInput), next == null ? null : next.toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private JsonNode read(String s) {
        try {
            return json.readTree(s);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String checkName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 120) throw ApiException.badRequest("invalid_workflow", "İş akışı adı 1-120 karakter olmalı.");
        return n;
    }

    private static String desc(String d) {
        return d == null ? "" : cut(d.trim(), 500);
    }

    private static String cut(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }
}
