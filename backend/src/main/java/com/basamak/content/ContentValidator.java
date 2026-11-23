package com.basamak.content;

import com.basamak.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Structural validation of version bodies per content type. Validation runs on every save, so an invalid
 * snapshot can never reach review or publication. Type-specific rules grow per phase (training items in F2...).
 */
@Component
@RequiredArgsConstructor
public class ContentValidator {
    private final ContentItemRepository items;
    private final ContentVersionRepository versions;

    public void validate(ContentType type, UUID itemId, JsonNode body) {
        if (body == null || !body.isObject()) throw ApiException.badRequest("invalid_body", "İçerik gövdesi bir nesne olmalı.");
        switch (type) {
            case JOURNEY -> journey(body);
            case TRAINING -> training(itemId, body);
            default -> { /* validated by their own modules in later phases */ }
        }
    }

    private void journey(JsonNode b) {
        if (!b.path("order").canConvertToInt() || b.path("order").asInt() < 1) {
            throw ApiException.badRequest("invalid_journey", "Journey sırası (order) 1 ya da daha büyük bir tam sayı olmalı.");
        }
        for (UUID id : uuids(b.path("trainingIds"), "trainingIds")) {
            ContentItem t = items.findById(id).orElse(null);
            if (t == null || t.getType() != ContentType.TRAINING) {
                throw ApiException.badRequest("invalid_journey", "Journey'e eklenen " + id + " kimlikli eğitim bulunamadı.");
            }
        }
        JsonNode unlock = b.path("unlock");
        if (!unlock.isMissingNode() && unlock.has("minPoints") && unlock.path("minPoints").asInt(-1) < 0) {
            throw ApiException.badRequest("invalid_journey", "Açılma koşulundaki en az puan negatif olamaz.");
        }
    }

    private void training(UUID itemId, JsonNode b) {
        List<UUID> prereqs = uuids(b.path("prerequisites"), "prerequisites");
        if (prereqs.contains(itemId)) {
            throw ApiException.badRequest("prerequisite_cycle", "Bir eğitim kendisinin ön koşulu olamaz.");
        }
        for (UUID p : prereqs) {
            ContentItem t = items.findById(p).orElse(null);
            if (t == null || t.getType() != ContentType.TRAINING) {
                throw ApiException.badRequest("invalid_training", "Ön koşul olarak seçilen " + p + " kimlikli eğitim bulunamadı.");
            }
        }
        List<UUID> cycle = findCycle(itemId, prereqs);
        if (!cycle.isEmpty()) {
            throw ApiException.badRequest("prerequisite_cycle",
                    "Ön koşullar döngü oluşturuyor (" + cycle + "). Eğitimler birbirini beklememeli.");
        }
    }

    /**
     * Prerequisite graph = latest saved version of every training (draft or published), with this item's edges
     * replaced by the edges being saved. DFS from this item; reaching it again means a cycle.
     */
    List<UUID> findCycle(UUID self, List<UUID> newPrereqs) {
        Map<UUID, List<UUID>> graph = new HashMap<>();
        for (ContentItem t : items.findByTypeOrderByCreatedAtAsc(ContentType.TRAINING)) {
            versions.findTopByItemIdOrderByVersionNoDesc(t.getId())
                    .ifPresent(v -> graph.put(t.getId(), uuidsLenient(v.getBody().path("prerequisites"))));
        }
        graph.put(self, newPrereqs);
        Deque<UUID> path = new ArrayDeque<>();
        Set<UUID> done = new HashSet<>();
        return dfs(self, self, graph, path, done) ? new ArrayList<>(path) : List.of();
    }

    private boolean dfs(UUID start, UUID node, Map<UUID, List<UUID>> g, Deque<UUID> path, Set<UUID> done) {
        path.addLast(node);
        for (UUID next : g.getOrDefault(node, List.of())) {
            if (next.equals(start)) { path.addLast(next); return true; }
            if (done.add(next) && dfs(start, next, g, path, done)) return true;
        }
        path.removeLast();
        return false;
    }

    private static List<UUID> uuids(JsonNode arr, String field) {
        if (arr.isMissingNode() || arr.isNull()) return List.of();
        if (!arr.isArray()) throw ApiException.badRequest("invalid_body", field + " bir liste olmalı.");
        List<UUID> out = new ArrayList<>();
        for (JsonNode n : arr) {
            try {
                out.add(UUID.fromString(n.asText()));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("invalid_body", field + " içinde geçersiz kimlik: " + n.asText());
            }
        }
        return out;
    }

    private static List<UUID> uuidsLenient(JsonNode arr) {
        List<UUID> out = new ArrayList<>();
        if (arr.isArray()) for (JsonNode n : arr) {
            try { out.add(UUID.fromString(n.asText())); } catch (IllegalArgumentException ignored) { }
        }
        return out;
    }
}
