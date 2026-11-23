package com.basamak.content;

import com.basamak.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Set;

/**
 * Shape of a training version body (the training is an aggregate: its lessons, quizzes and surveys live inside).
 * <pre>
 * { "summary": "...", "prerequisites": [trainingId...],
 *   "items": [
 *     {"key":"intro","type":"lesson","title":"...","content":"markdown","video":{"url":"...","minSeconds":60}},
 *     {"key":"q1","type":"quiz","title":"...","passingScore":60,"attemptsAllowed":3,"timeLimitSeconds":300,
 *      "questionsPerAttempt":3,"showCorrectAnswers":true,
 *      "questions":[{"id":"a","type":"single|multi|open","text":"...","points":1,
 *                    "options":[{"id":"x","text":"...","correct":true}]}]},
 *     {"key":"s1","type":"survey","title":"...","anonymous":false,
 *      "questions":[{"id":"a","type":"single|multi|scale|open","text":"...","options":[{"id":"x","text":"..."}]}]},
 *     {"key":"t1","type":"tool","title":"...","toolId":"uuid","toolVersionNo":1}
 *   ] }
 * </pre>
 * itemKey is stable across versions; progress is tracked per key, which is what lets a member move to a new version.
 */
public final class TrainingSchema {
    private TrainingSchema() {}

    public static void validateItems(JsonNode b) {
        JsonNode items = b.path("items");
        if (items.isMissingNode()) return; // early drafts may have no items yet; publishing requires them
        if (!items.isArray()) throw bad("items bir liste olmalı.");
        Set<String> keys = new HashSet<>();
        for (JsonNode it : items) {
            String key = it.path("key").asText("");
            if (!key.matches("[a-z0-9-]{1,60}")) throw bad("Öge anahtarı (key) küçük harf, rakam ve tire içermeli: '" + key + "'");
            if (!keys.add(key)) throw bad("Öge anahtarı tekrar ediyor: " + key);
            if (it.path("title").asText("").isBlank()) throw bad("'" + key + "' ögesinin başlığı boş.");
            switch (it.path("type").asText()) {
                case "lesson" -> {
                    if (it.path("content").asText("").isBlank() && !it.has("video")) throw bad("'" + key + "' dersinin metni ya da videosu olmalı.");
                }
                case "quiz" -> quiz(key, it);
                case "survey" -> survey(key, it);
                case "tool" -> {
                    if (it.path("toolId").asText("").isBlank() || !it.path("toolVersionNo").canConvertToInt()) {
                        throw bad("'" + key + "' araç ögesi bir araca ve sürüm numarasına sabitlenmeli.");
                    }
                }
                default -> throw bad("'" + key + "' ögesinin türü lesson, quiz, survey ya da tool olmalı.");
            }
        }
    }

    private static void quiz(String key, JsonNode q) {
        int pass = q.path("passingScore").asInt(-1);
        if (pass < 0 || pass > 100) throw bad("'" + key + "' quiz'inin geçme notu 0-100 arasında olmalı.");
        if (q.path("attemptsAllowed").asInt(0) < 1) throw bad("'" + key + "' quiz'inde en az 1 deneme hakkı olmalı.");
        JsonNode qs = q.path("questions");
        if (!qs.isArray() || qs.isEmpty()) throw bad("'" + key + "' quiz'inin soru bankası boş.");
        int per = q.path("questionsPerAttempt").asInt(qs.size());
        if (per < 1 || per > qs.size()) throw bad("'" + key + "' quiz'inde deneme başına soru sayısı 1 ile soru bankası büyüklüğü arasında olmalı.");
        Set<String> ids = new HashSet<>();
        for (JsonNode question : qs) {
            String id = question.path("id").asText("");
            if (id.isBlank() || !ids.add(id)) throw bad("'" + key + "' quiz'inde soru kimlikleri boş ya da tekrar ediyor.");
            String type = question.path("type").asText();
            if (question.path("text").asText("").isBlank()) throw bad("'" + key + "/" + id + "' sorusunun metni boş.");
            if (type.equals("open")) continue;
            if (!type.equals("single") && !type.equals("multi")) throw bad("'" + key + "/" + id + "' soru türü single, multi ya da open olmalı.");
            int correct = 0, count = 0;
            for (JsonNode o : question.path("options")) {
                count++;
                if (o.path("correct").asBoolean(false)) correct++;
            }
            if (count < 2) throw bad("'" + key + "/" + id + "' sorusunda en az iki şık olmalı.");
            if (type.equals("single") && correct != 1) throw bad("'" + key + "/" + id + "' tek seçimli soruda tam bir doğru şık olmalı.");
            if (type.equals("multi") && correct < 1) throw bad("'" + key + "/" + id + "' çok seçimli soruda en az bir doğru şık olmalı.");
        }
    }

    private static void survey(String key, JsonNode s) {
        JsonNode qs = s.path("questions");
        if (!qs.isArray() || qs.isEmpty()) throw bad("'" + key + "' anketinde en az bir soru olmalı.");
        for (JsonNode q : qs) {
            String type = q.path("type").asText();
            if (!Set.of("single", "multi", "scale", "open").contains(type)) {
                throw bad("'" + key + "' anketinde soru türü single, multi, scale ya da open olmalı.");
            }
            if ((type.equals("single") || type.equals("multi")) && q.path("options").size() < 2) {
                throw bad("'" + key + "' anketinde seçmeli soruların en az iki şıkkı olmalı.");
            }
        }
    }

    private static ApiException bad(String msg) {
        return ApiException.badRequest("invalid_training", msg);
    }

    public static JsonNode findItem(JsonNode body, String key) {
        for (JsonNode it : body.path("items")) if (key.equals(it.path("key").asText())) return it;
        return null;
    }
}
