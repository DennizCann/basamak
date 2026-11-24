package com.basamak.report;

import com.basamak.common.ApiException;
import com.basamak.content.ContentType;
import com.basamak.content.PublishedContent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * CMS reports (scenario 13), read from the operational tables. Counts include deleted (anonymised) members, which is
 * exactly why deletion anonymises instead of removing rows. Question texts come from the version each attempt used.
 * At larger scale these become pre-aggregated read models fed by activity events (TASARIM §9).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {
    /** A started, unfinished enrollment with no activity for this long counts as "dropped" (TASARIM §7.7). */
    public static final int DROP_DAYS = 14;
    /** Anonymous surveys: distributions with fewer answers than this are hidden (no re-identification). */
    public static final int MIN_ANONYMOUS_ANSWERS = 5;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PublishedContent published;

    public List<Map<String, Object>> trainings() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var t : published.list(ContentType.TRAINING)) {
            Map<String, Object> r = jdbc.queryForMap("""
                    SELECT count(*)::int AS enrolled, count(*) FILTER (WHERE status = 'COMPLETED')::int AS completed,
                           avg(extract(epoch FROM completed_at - started_at)) FILTER (WHERE status = 'COMPLETED') AS avg_seconds
                      FROM enrollment WHERE training_id = ?""", t.itemId());
            int enrolled = (Integer) r.get("enrolled"), completed = (Integer) r.get("completed");
            Integer dropped = jdbc.queryForObject("SELECT count(*)::int FROM enrollment WHERE training_id = ? AND status = 'IN_PROGRESS' AND updated_at < now() - make_interval(days => ?)",
                    Integer.class, t.itemId(), DROP_DAYS);
            List<Map<String, Object>> stuck = jdbc.queryForList("""
                    SELECT current_item_key AS key, count(*)::int AS n FROM enrollment
                     WHERE training_id = ? AND status = 'IN_PROGRESS' AND updated_at < now() - make_interval(days => ?) AND current_item_key IS NOT NULL
                     GROUP BY current_item_key ORDER BY n DESC LIMIT 1""", t.itemId(), DROP_DAYS);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("trainingId", t.itemId());
            m.put("title", t.title());
            m.put("versionNo", t.versionNo());
            m.put("enrolled", enrolled);
            m.put("completed", completed);
            m.put("completionRate", rate(completed, enrolled));
            m.put("avgCompletionMinutes", r.get("avg_seconds") == null ? null : Math.round(((Number) r.get("avg_seconds")).doubleValue() / 60.0));
            m.put("dropped", dropped == null ? 0 : dropped);
            if (!stuck.isEmpty()) {
                String key = (String) stuck.get(0).get("key");
                String title = key;
                for (JsonNode it : t.body().path("items")) if (key.equals(it.path("key").asText())) title = it.path("title").asText();
                m.put("mostDroppedItem", Map.of("key", key, "title", title, "members", stuck.get(0).get("n")));
            } else m.put("mostDroppedItem", null);
            // version comparison: the same numbers per training version (members finish the version they started)
            m.put("versions", jdbc.queryForList("""
                    SELECT training_version_no AS "versionNo", count(*)::int AS enrolled, count(*) FILTER (WHERE status = 'COMPLETED')::int AS completed,
                           round(100.0 * count(*) FILTER (WHERE status = 'COMPLETED') / nullif(count(*), 0), 1) AS "completionRate"
                      FROM enrollment WHERE training_id = ? GROUP BY training_version_no ORDER BY training_version_no""", t.itemId()));
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> (String) m.get("title"), TR));
        return out;
    }

    static final java.text.Collator TR = java.text.Collator.getInstance(Locale.forLanguageTag("tr-TR"));

    /** Per journey: members who started any of its trainings, finished it (certificate), and dropped (inactive). */
    public List<Map<String, Object>> journeys() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var j : published.list(ContentType.JOURNEY)) {
            List<UUID> ids = new ArrayList<>();
            j.body().path("trainingIds").forEach(x -> { try { ids.add(UUID.fromString(x.asText())); } catch (IllegalArgumentException ignored) { } });
            Map<String, Object> r = jdbc.queryForMap("""
                    WITH started AS (SELECT user_id, max(updated_at) AS last FROM enrollment WHERE training_id = ANY(CAST(? AS uuid[])) GROUP BY user_id),
                         done AS (SELECT DISTINCT user_id FROM certificate WHERE journey_id = ?)
                    SELECT (SELECT count(*) FROM started)::int AS started, (SELECT count(*) FROM done)::int AS completed,
                           (SELECT count(*) FROM started s WHERE s.user_id NOT IN (SELECT user_id FROM done) AND s.last < now() - make_interval(days => ?))::int AS dropped""",
                    "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}", j.itemId(), DROP_DAYS);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("journeyId", j.itemId());
            m.put("title", j.title());
            m.put("trainings", ids.size());
            m.put("started", r.get("started"));
            m.put("completed", r.get("completed"));
            m.put("dropped", r.get("dropped"));
            m.put("completionRate", rate((Integer) r.get("completed"), (Integer) r.get("started")));
            out.add(m);
        }
        return out;
    }

    /** Tool and workflow usage (all members): runs, error rate, average duration, AI cost. */
    public Map<String, Object> usage() {
        List<Map<String, Object>> tools = jdbc.queryForList("""
                SELECT tool_name AS name, scope, count(*)::int AS runs,
                       count(*) FILTER (WHERE status IN ('FAILED','TIMED_OUT'))::int AS errors,
                       round(100.0 * count(*) FILTER (WHERE status IN ('FAILED','TIMED_OUT')) / nullif(count(*) FILTER (WHERE status NOT IN ('QUEUED','RUNNING','RETRYING','CANCELLED')), 0), 1) AS "errorRate",
                       round(avg(duration_ms))::bigint AS "avgMs"
                  FROM tool_run GROUP BY tool_name, scope ORDER BY runs DESC LIMIT 100""");
        List<Map<String, Object>> workflows = jdbc.queryForList("""
                SELECT w.name, w.visibility, count(r.*)::int AS runs,
                       count(*) FILTER (WHERE r.status = 'FAILED')::int AS errors,
                       round(100.0 * count(*) FILTER (WHERE r.status = 'FAILED') / nullif(count(*) FILTER (WHERE r.status IN ('SUCCEEDED','FAILED')), 0), 1) AS "errorRate",
                       round(avg(extract(epoch FROM r.finished_at - r.started_at) * 1000))::bigint AS "avgMs",
                       coalesce(sum(r.total_cost), 0) AS "aiCostUsd", coalesce(sum(r.total_tokens), 0)::bigint AS tokens
                  FROM workflow_run r JOIN workflow w ON w.id = r.workflow_id
                 WHERE r.parent_run_id IS NULL GROUP BY w.id, w.name, w.visibility ORDER BY runs DESC LIMIT 100""");
        return Map.of("tools", tools, "workflows", workflows);
    }

    /** Per quiz: attempts, pass rate, average score; per question: how often it was answered correctly. */
    public List<Map<String, Object>> quizzes(UUID trainingId) {
        requireTraining(trainingId);
        Map<String, Map<String, Object>> quizzes = new LinkedHashMap<>();
        Map<String, JsonNode> versionBodies = new HashMap<>();
        jdbc.query("""
                SELECT training_version_no, item_key, answers::text AS answers, questions::text AS questions, score, max_score, passed,
                       extract(epoch FROM submitted_at - started_at) AS secs
                  FROM quiz_attempt WHERE training_id = ? AND submitted_at IS NOT NULL ORDER BY item_key""", rs -> {
            int v = rs.getInt("training_version_no");
            String key = rs.getString("item_key");
            JsonNode body = versionBodies.computeIfAbsent(v + "", x -> published.pinned(trainingId, v).map(p -> p.body()).orElse(json.nullNode()));
            JsonNode quiz = null;
            for (JsonNode it : body.path("items")) if (key.equals(it.path("key").asText())) quiz = it;
            Map<String, Object> q = quizzes.computeIfAbsent(key, k -> newQuiz(k));
            if (quiz != null) q.put("title", quiz.path("title").asText());
            q.put("attempts", (Integer) q.get("attempts") + 1);
            if (rs.getBoolean("passed")) q.put("passed", (Integer) q.get("passed") + 1);
            int max = rs.getInt("max_score");
            q.put("scoreSum", (Double) q.get("scoreSum") + (max == 0 ? 0 : 100.0 * rs.getInt("score") / max));
            JsonNode answers = read(rs.getString("answers"));
            @SuppressWarnings("unchecked") Map<String, Map<String, Object>> questions = (Map<String, Map<String, Object>>) q.get("questionStats");
            JsonNode askedList = read(rs.getString("questions"));
            double perQuestion = askedList.size() == 0 ? 0 : rs.getDouble("secs") / askedList.size();
            for (JsonNode asked : askedList) {
                String qid = asked.path("id").asText(asked.asText());
                JsonNode def = null;
                if (quiz != null) for (JsonNode d : quiz.path("questions")) if (qid.equals(d.path("id").asText())) def = d;
                if (def == null || "open".equals(def.path("type").asText())) continue;
                Map<String, Object> st = questions.computeIfAbsent(qid, x -> new LinkedHashMap<>(Map.of("questionId", x, "asked", 0, "correct", 0, "text", "")));
                st.put("text", def.path("text").asText());
                st.put("asked", (Integer) st.get("asked") + 1);
                st.merge("secondsSum", perQuestion, (x, y) -> (Double) x + (Double) y);
                Set<String> correct = new TreeSet<>(), given = new TreeSet<>();
                def.path("options").forEach(o -> { if (o.path("correct").asBoolean()) correct.add(o.path("id").asText()); });
                JsonNode a = answers.path(qid);
                if (a.isArray()) a.forEach(x -> given.add(x.asText())); else if (!a.isMissingNode() && !a.isNull()) given.add(a.asText());
                if (correct.equals(given)) st.put("correct", (Integer) st.get("correct") + 1);
            }
        }, trainingId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> q : quizzes.values()) {
            int attempts = (Integer) q.get("attempts");
            @SuppressWarnings("unchecked") Map<String, Map<String, Object>> questions = (Map<String, Map<String, Object>>) q.remove("questionStats");
            q.put("passRate", rate((Integer) q.get("passed"), attempts));
            q.put("avgScore", attempts == 0 ? null : BigDecimal.valueOf((Double) q.remove("scoreSum") / attempts).setScale(1, RoundingMode.HALF_UP));
            q.remove("scoreSum");
            List<Map<String, Object>> qs = new ArrayList<>(questions.values());
            qs.forEach(st -> {
                st.put("correctRate", rate((Integer) st.get("correct"), (Integer) st.get("asked")));
                // time per question is not recorded separately: attempt duration / questions in that attempt (approximation)
                Object sum = st.remove("secondsSum");
                st.put("avgSeconds", sum == null ? null : Math.round((Double) sum / (Integer) st.get("asked")));
            });
            q.put("questions", qs);
            out.add(q);
        }
        return out;
    }

    /** Per survey question: option / scale distribution; open answers are counted and the latest few shown without names. */
    public List<Map<String, Object>> surveys(UUID trainingId) {
        var t = requireTraining(trainingId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode item : t.body().path("items")) {
            if (!"survey".equals(item.path("type").asText())) continue;
            String key = item.path("key").asText();
            List<JsonNode> responses = jdbc.query("SELECT answers::text FROM survey_response WHERE training_id = ? AND item_key = ? ORDER BY submitted_on DESC",
                    (rs, i) -> read(rs.getString(1)), trainingId, key);
            List<Map<String, Object>> questions = new ArrayList<>();
            for (JsonNode q : item.path("questions")) {
                String qid = q.path("id").asText(), type = q.path("type").asText();
                Map<String, Object> st = new LinkedHashMap<>();
                st.put("questionId", qid);
                st.put("text", q.path("text").asText());
                st.put("type", type);
                int answered = 0;
                Map<String, Integer> counts = new LinkedHashMap<>();
                if (type.equals("scale")) for (int i = 1; i <= 5; i++) counts.put(String.valueOf(i), 0);
                for (JsonNode o : q.path("options")) counts.put(o.path("id").asText(), 0);
                List<String> open = new ArrayList<>();
                for (JsonNode r : responses) {
                    JsonNode a = r.path(qid);
                    if (a.isMissingNode() || a.isNull() || (a.isTextual() && a.asText().isBlank())) continue;
                    answered++;
                    if (type.equals("open")) { if (open.size() < 20) open.add(a.asText()); continue; }
                    if (a.isArray()) a.forEach(x -> counts.merge(x.asText(), 1, Integer::sum)); else counts.merge(a.asText(), 1, Integer::sum);
                }
                st.put("answered", answered);
                boolean anonymous = item.path("anonymous").asBoolean(false);
                if (anonymous && answered < MIN_ANONYMOUS_ANSWERS) {
                    st.put("hidden", true); // too few answers: a distribution could reveal who answered what
                    st.put("hiddenReason", "Anonim ankette " + MIN_ANONYMOUS_ANSWERS + "'ten az cevap var; dağılım kimliği ele vermemek için gizlendi.");
                } else if (!type.equals("open")) {
                    List<Map<String, Object>> dist = new ArrayList<>();
                    for (var e : counts.entrySet()) {
                        String label = e.getKey();
                        for (JsonNode o : q.path("options")) if (e.getKey().equals(o.path("id").asText())) label = o.path("text").asText();
                        Map<String, Object> d = new LinkedHashMap<>(); // rate may be null (no answers yet): no Map.of
                        d.put("value", e.getKey());
                        d.put("label", label);
                        d.put("count", e.getValue());
                        d.put("rate", rate(e.getValue(), answered));
                        dist.add(d);
                    }
                    st.put("distribution", dist);
                } else {
                    st.put("latestAnswers", open);
                }
                questions.add(st);
            }
            out.add(Map.of("key", key, "title", item.path("title").asText(), "anonymous", item.path("anonymous").asBoolean(false),
                    "responses", responses.size(), "questions", questions));
        }
        return out;
    }

    private PublishedContent.Published requireTraining(UUID id) {
        return published.get(id, ContentType.TRAINING).orElseThrow(() -> ApiException.notFound("Yayındaki eğitim"));
    }

    private static Map<String, Object> newQuiz(String key) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("key", key);
        q.put("title", key);
        q.put("attempts", 0);
        q.put("passed", 0);
        q.put("scoreSum", 0.0);
        q.put("questionStats", new LinkedHashMap<String, Map<String, Object>>());
        return q;
    }

    static BigDecimal rate(int part, int whole) {
        return whole == 0 ? null : BigDecimal.valueOf(100.0 * part / whole).setScale(1, RoundingMode.HALF_UP);
    }

    private JsonNode read(String s) {
        try {
            return s == null ? json.createObjectNode() : json.readTree(s);
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }
}
