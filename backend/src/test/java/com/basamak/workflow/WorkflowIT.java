package com.basamak.workflow;

import com.basamak.IntegrationTest;
import com.basamak.tool.UrlGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Scenarios 8 (engine), 9 (cron + idempotent webhook) and 11 (fork credits the author, leaderboard). */
class WorkflowIT extends IntegrationTest {
    static HttpServer fake;
    static int port;
    static final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

    @Autowired UrlGuard guard;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkflowTriggers triggers;
    @Autowired WorkflowEngine engine;

    static String weatherTool, flakyTool; // published CMS tools (shared by the tests)

    @BeforeAll
    static void startFake() throws Exception {
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.setExecutor(Executors.newCachedThreadPool());
        fake.createContext("/", ex -> {
            String q = Optional.ofNullable(ex.getRequestURI().getQuery()).orElse("");
            Map<String, String> p = new HashMap<>();
            for (String kv : q.split("&")) if (kv.contains("=")) p.put(kv.substring(0, kv.indexOf('=')), java.net.URLDecoder.decode(kv.substring(kv.indexOf('=') + 1), StandardCharsets.UTF_8));
            int code = 200;
            String body;
            if (ex.getRequestURI().getPath().equals("/weather")) {
                body = "{\"city\":\"" + p.get("city") + "\",\"temperatureC\":" + ("Antalya".equals(p.get("city")) ? 31 : 12) + "}";
            } else {
                int n = calls.computeIfAbsent(p.get("key"), k -> new AtomicInteger()).incrementAndGet();
                if (n <= Integer.parseInt(p.getOrDefault("fail", "0"))) { code = 503; body = "{}"; } else body = "{\"ok\":true,\"call\":" + n + "}";
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        fake.start();
        port = fake.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        fake.stop(0);
    }

    @BeforeEach
    void tools() throws Exception {
        guard.trustForTests("127.0.0.1:" + port);
        guard.setResolverForTests(null);
        if (weatherTool == null) {
            String admin = cmsToken("admin@basamak.local");
            weatherTool = cmsTool(admin, "Hava (test)", "http://127.0.0.1:" + port + "/weather?city={{input.city}}", "city");
            flakyTool = cmsTool(admin, "Kararsız (test)", "http://127.0.0.1:" + port + "/flaky?key={{input.key}}&fail=1", "key");
        }
    }

    // ------------------------------------------------------------------ scenario 8

    @Test
    void scenario8_toolThenAiThenConditionAndSubflow_withStepDetails() throws Exception {
        String m = newMember("wf8");
        String sub = create(m, "Alt akış", def(List.of(Map.of("name", "text", "label", "Metin", "type", "string", "required", true)),
                List.of(Map.of("id", "echo", "type", "ai", "prompt", "Alt akış: {{input.text}}")), "{{steps.echo.output.text}}"));
        String main = create(m, "Ana akış", def(List.of(Map.of("name", "city", "label", "Şehir", "type", "string", "required", true)), List.of(
                Map.of("id", "weather", "type", "tool", "tool", Map.of("scope", "CONTENT", "id", weatherTool), "input", Map.of("city", "{{input.city}}")),
                Map.of("id", "advice", "type", "ai", "prompt", "{{input.city}} için sıcaklık {{steps.weather.output.temperatureC}} derece. Öneri yaz."),
                Map.of("id", "hot", "type", "condition", "left", "{{steps.weather.output.temperatureC}}", "op", "gt", "right", "25",
                        "then", List.of("warn"), "else", List.of("mild")),
                Map.of("id", "warn", "type", "ai", "prompt", "Sıcak uyarısı"),
                Map.of("id", "mild", "type", "ai", "prompt", "Ilık"),
                Map.of("id", "share", "type", "workflow", "workflowId", sub, "input", Map.of("text", "{{steps.advice.output.text}}"))),
                "{{steps.share.output}}"));

        JsonNode run = runAndWait(m, main, Map.of("city", "Antalya"));
        assertThat(run.get("status").asText()).isEqualTo("SUCCEEDED");
        Map<String, JsonNode> steps = byStep(run);
        assertThat(steps.get("weather").at("/output/temperatureC").asInt()).isEqualTo(31);
        assertThat(steps.get("advice").at("/input/prompt").asText()).contains("Antalya için sıcaklık 31 derece");    // mapping
        assertThat(steps.get("advice").get("tokens").asInt()).isPositive();
        assertThat(steps.get("advice").get("cost").decimalValue()).isPositive();
        assertThat(steps.get("advice").get("durationMs").isNumber()).isTrue();
        assertThat(steps.get("hot").at("/output/result").asBoolean()).isTrue();
        assertThat(steps.get("warn").get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(steps.get("mild").get("status").asText()).isEqualTo("SKIPPED");
        assertThat(steps.get("share").get("childRunId").isNull()).isFalse();
        assertThat(run.get("output").asText()).contains("Alt akış:");
        assertThat(run.get("totalCost").decimalValue()).isPositive();
    }

    @Test
    void scenario8_resumeContinuesFromTheFailedStep_withoutRepeatingEarlierSteps() throws Exception {
        String m = newMember("wfresume");
        String key = "resume-" + UUID.randomUUID();
        String wf = create(m, "Devam", def(List.of(), List.of(
                Map.of("id", "first", "type", "ai", "prompt", "İlk adım"),
                Map.of("id", "call", "type", "tool", "tool", Map.of("scope", "CONTENT", "id", flakyTool), "input", Map.of("key", key)),
                Map.of("id", "last", "type", "ai", "prompt", "Sonuç: {{steps.call.output.call}}")), ""));
        JsonNode failed = runAndWait(m, wf, Map.of());
        assertThat(failed.get("status").asText()).isEqualTo("FAILED");
        assertThat(failed.get("failedStepId").asText()).isEqualTo("call");

        postJson("/api/workflow-runs/" + failed.get("id").asText() + "/resume", null, m);
        JsonNode done = waitRun(m, failed.get("id").asText());
        assertThat(done.get("status").asText()).isEqualTo("SUCCEEDED");
        List<String> executed = new ArrayList<>();
        done.get("steps").forEach(s -> executed.add(s.get("stepId").asText() + ":" + s.get("status").asText()));
        assertThat(executed).containsExactly("first:SUCCEEDED", "call:FAILED", "call:SUCCEEDED", "last:SUCCEEDED"); // "first" ran once
        assertThat(byStep(done).get("last").at("/input/prompt").asText()).isEqualTo("Sonuç: 2");
    }

    @Test
    void scenario8_directAndIndirectCyclesCannotBeSaved() throws Exception {
        String m = newMember("wfcycle");
        String c = create(m, "C", simple("c"));
        String b = create(m, "B", sub(c));
        String a = create(m, "A", sub(b));
        // C -> A would close A -> B -> C -> A
        MvcResult r = mvc.perform(jsonPut("/api/workflows/" + c, Map.of("name", "C", "definition", sub(a))).header("Authorization", bearer(m))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(r).get("code").asText()).isEqualTo("workflow_cycle");
        assertThat(body(r).get("detail").asText()).contains("“A”").contains("“B”");
        // self reference
        r = mvc.perform(jsonPut("/api/workflows/" + a, Map.of("name", "A", "definition", sub(a))).header("Authorization", bearer(m))).andReturn();
        assertThat(body(r).get("code").asText()).isEqualTo("workflow_cycle");
        // a step may not read a LATER step, and a condition may not branch backwards
        r = mvc.perform(jsonPost("/api/workflows", Map.of("name", "İleri", "definition", def(List.of(), List.of(
                Map.of("id", "x", "type", "ai", "prompt", "{{steps.y.output.text}}"), Map.of("id", "y", "type", "ai", "prompt", "y")), "")))
                .header("Authorization", bearer(m))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        r = mvc.perform(jsonPost("/api/workflows", Map.of("name", "Geri", "definition", def(List.of(), List.of(
                Map.of("id", "x", "type", "ai", "prompt", "x"),
                Map.of("id", "if", "type", "condition", "left", "1", "op", "eq", "right", "1", "then", List.of("x"), "else", List.of())), "")))
                .header("Authorization", bearer(m))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ scenario 9

    @Test
    void scenario9_webhookIsIdempotent() throws Exception {
        String m = newMember("wfhook");
        String wf = create(m, "Webhook", simple("hook"));
        String token = postJson("/api/workflows/" + wf + "/webhook", null, m).get("token").asText();

        MvcResult first = mvc.perform(jsonPost("/api/hooks/workflows/" + token, Map.of("x", 1)).header("Idempotency-Key", "delivery-42")).andReturn();
        MvcResult again = mvc.perform(jsonPost("/api/hooks/workflows/" + token, Map.of("x", 1)).header("Idempotency-Key", "delivery-42")).andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(202);
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(again).get("duplicate").asBoolean()).isTrue();
        assertThat(body(again).get("runId")).isEqualTo(body(first).get("runId"));
        // without a key, an identical body is the same delivery too
        String r1 = body(mvc.perform(jsonPost("/api/hooks/workflows/" + token, Map.of("y", 2))).andReturn()).get("runId").asText();
        String r2 = body(mvc.perform(jsonPost("/api/hooks/workflows/" + token, Map.of("y", 2))).andReturn()).get("runId").asText();
        assertThat(r1).isEqualTo(r2);
        Integer runs = jdbc.queryForObject("SELECT count(*) FROM workflow_run WHERE workflow_id = ?::uuid AND trigger = 'WEBHOOK'", Integer.class, wf);
        assertThat(runs).isEqualTo(2);
        mvc.perform(jsonPost("/api/hooks/workflows/wrong-token-wrong-token-xx", Map.of())).andExpect(status().isNotFound());
        assertThat(waitRun(m, body(first).get("runId").asText()).get("status").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    void scenario9_cronStartsOneRunPerTick() throws Exception {
        String m = newMember("wfcron");
        String wf = create(m, "Zamanlı", simple("cron"));
        mvc.perform(jsonPut("/api/workflows/" + wf + "/cron", Map.of("cron", "*/5 * * * *")).header("Authorization", bearer(m))).andExpect(status().isOk());
        mvc.perform(jsonPut("/api/workflows/" + wf + "/cron", Map.of("cron", "her gün")).header("Authorization", bearer(m))).andExpect(status().isBadRequest());
        java.sql.Timestamp due = java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(5).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        jdbc.update("UPDATE workflow SET next_run_at = ? WHERE id = ?::uuid", due, wf);
        triggers.fireDue();
        triggers.fireDue();                                                           // already moved to the next tick
        jdbc.update("UPDATE workflow SET next_run_at = ? WHERE id = ?::uuid", due, wf); // same tick delivered again
        triggers.fireDue();
        Integer runs = jdbc.queryForObject("SELECT count(*) FROM workflow_run WHERE workflow_id = ?::uuid AND trigger = 'CRON'", Integer.class, wf);
        assertThat(runs).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT next_run_at > now() FROM workflow WHERE id = ?::uuid", Boolean.class, wf)).isTrue();
    }

    // ------------------------------------------------------------------ scenario 11

    @Test
    void scenario11_forkCreditsTheAuthor_andLeaderboardShowsIt() throws Exception {
        String author = newMember("wfauthor");
        String forker = newMember("wfforker");
        String wf = create(author, "Paylaşılan akış", def(List.of(), List.of(
                Map.of("id", "w", "type", "tool", "tool", Map.of("scope", "CONTENT", "id", weatherTool), "input", Map.of("city", "Rize"))), ""));
        postJson("/api/workflows/" + wf + "/share", Map.of("shared", true), author);
        JsonNode community = getJson("/api/community/workflows", forker);
        assertThat(community.toString()).contains(wf);

        int before = getJson("/api/me/summary", author).path("totalPoints").asInt(-1);
        JsonNode fork = postJson("/api/workflows/" + wf + "/fork", null, forker);
        assertThat(fork.get("forkedFrom").asText()).isEqualTo(wf);
        assertThat(getJson("/api/workflows/" + wf, forker).get("forkCount").asInt()).isEqualTo(1);
        Integer credited = jdbc.queryForObject("SELECT count(*) FROM point_ledger WHERE action = 'SHARED_ITEM_FORKED' AND user_id = (SELECT owner_id FROM workflow WHERE id = ?::uuid)",
                Integer.class, wf);
        assertThat(credited).isEqualTo(1);
        mvc.perform(post("/api/workflows/" + wf + "/fork").header("Authorization", bearer(author))).andExpect(status().isBadRequest()); // own

        JsonNode week = getJson("/api/leaderboard?period=week&limit=100", author);
        assertThat(week.toString()).contains("Test wfauthor");
        JsonNode all = getJson("/api/leaderboard?period=all&limit=100", author);
        assertThat(all.get("top").isArray()).isTrue();
        if (before >= 0) assertThat(getJson("/api/me/summary", author).path("totalPoints").asInt()).isGreaterThan(before);
    }

    @Test
    void workflowsUsingOwnToolsCannotBeShared() throws Exception {
        String m = newMember("wfprivate");
        guard.setResolverForTests(h -> new java.net.InetAddress[]{java.net.InetAddress.getByName("93.184.216.34")});
        String tool = postJson("/api/my-tools", Map.of("name", "Benim", "definition", Map.of("inputs", List.of(),
                "request", Map.of("method", "GET", "url", "https://api.example.test/x"))), m).get("id").asText();
        String wf = create(m, "Özel", def(List.of(), List.of(Map.of("id", "t", "type", "tool", "tool", Map.of("scope", "USER", "id", tool), "input", Map.of())), ""));
        MvcResult r = mvc.perform(jsonPost("/api/workflows/" + wf + "/share", Map.of("shared", true)).header("Authorization", bearer(m))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(r).get("code").asText()).isEqualTo("private_tools");
    }

    // ------------------------------------------------------------------ helpers

    private String cmsTool(String admin, String title, String url, String input) throws Exception {
        Map<String, Object> body = Map.of("inputs", List.of(Map.of("name", input, "label", input, "type", "string", "required", true)),
                "request", Map.of("method", "GET", "url", url), "timeoutSeconds", 5, "retries", 0);
        MvcResult r = mvc.perform(jsonPost("/api/cms/items", Map.of("type", "TOOL", "title", title, "body", body)).header("Authorization", bearer(admin))).andReturn();
        JsonNode created = body(r);
        for (String step : List.of("submit", "approve", "publish")) {
            mvc.perform(post("/api/cms/versions/" + created.at("/latest/id").asText() + "/" + step).header("Authorization", bearer(admin))).andExpect(status().isOk());
        }
        return created.get("id").asText();
    }

    @Test
    void parallelGroup_runsTogether_andALaterStepMergesBothOutputs() throws Exception {
        String m = newMember("wfpar");
        Map<String, Object> d = def(List.of(), List.of(
                Map.of("id", "a", "type", "tool", "tool", Map.of("scope", "CONTENT", "id", weatherTool), "input", Map.of("city", "Rize")),
                Map.of("id", "b", "type", "tool", "parallel", true, "tool", Map.of("scope", "CONTENT", "id", weatherTool), "input", Map.of("city", "Antalya")),
                Map.of("id", "c", "type", "ai", "parallel", true, "prompt", "Bağımsız özet"),
                Map.of("id", "merge", "type", "ai", "prompt", "{{steps.a.output.temperatureC}} ve {{steps.b.output.temperatureC}}")), "{{steps.merge.output.text}}");
        JsonNode run = runAndWait(m, create(m, "Paralel", d), Map.of());
        assertThat(run.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(byStep(run).get("merge").at("/input/prompt").asText()).isEqualTo("12 ve 31");

        // a step of a parallel group cannot read its sibling's output
        Map<String, Object> bad = def(List.of(), List.of(
                Map.of("id", "a", "type", "ai", "prompt", "x"),
                Map.of("id", "b", "type", "ai", "parallel", true, "prompt", "{{steps.a.output.text}}")), "");
        mvc.perform(jsonPost("/api/workflows", Map.of("name", "Hatalı", "definition", bad)).header("Authorization", bearer(m))).andExpect(status().isBadRequest());
    }

    @Test
    void missingRequiredToolMapping_isRejectedOnSave() throws Exception {
        String m = newMember("wfmap");
        Map<String, Object> d = def(List.of(), List.of(Map.of("id", "w", "type", "tool", "tool", Map.of("scope", "CONTENT", "id", weatherTool), "input", Map.of())), "");
        MvcResult r = mvc.perform(jsonPost("/api/workflows", Map.of("name", "Eşlemesiz", "definition", d)).header("Authorization", bearer(m))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("eşlenmemiş");
    }

    @Test
    void platformEventTrigger_startsTheWorkflowOnce_whenTheMemberCompletesALesson() throws Exception {
        String m = newMember("wfevent");
        String wf = create(m, "Ders bitince", def(List.of(Map.of("name", "objectId", "label", "Nesne", "type", "string", "required", true)),
                List.of(Map.of("id", "note", "type", "ai", "prompt", "Tebrik: {{input.objectId}}")), ""));
        mvc.perform(jsonPut("/api/workflows/" + wf + "/event-trigger", Map.of("event", "WORKFLOW_RUN_SUCCEEDED")).header("Authorization", bearer(m)))
                .andExpect(status().isBadRequest()); // only learning events: no self-triggering loops
        mvc.perform(jsonPut("/api/workflows/" + wf + "/event-trigger", Map.of("event", "LESSON_COMPLETED")).header("Authorization", bearer(m)))
                .andExpect(status().isOk());
        String intro = null;
        for (JsonNode t : getJson("/api/me/journeys", m).get(0).get("trainings")) if (t.get("title").asText().equals("Basamak'a Giriş")) intro = t.get("id").asText();
        postJson("/api/trainings/" + intro + "/enroll", null, m);
        postJson("/api/trainings/" + intro + "/items/hos-geldin/complete", null, m);
        postJson("/api/trainings/" + intro + "/items/hos-geldin/complete", null, m); // duplicate event: no second run
        long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        List<Map<String, Object>> runs;
        do {
            runs = jdbc.queryForList("SELECT status, trigger, input::text AS input FROM workflow_run WHERE workflow_id = ?::uuid", wf);
            if (!runs.isEmpty() && "SUCCEEDED".equals(runs.get(0).get("status"))) break;
            Thread.sleep(100);
        } while (System.nanoTime() < end);
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).get("trigger")).isEqualTo("EVENT");
        assertThat((String) runs.get(0).get("input")).contains(intro + ":hos-geldin");
    }

    @Test
    void monthlyAiBudget_refusesNewRuns_andStopsARunningOneSafely() throws Exception {
        String m = newMember("wfbudget");
        String wf = create(m, "Bütçe", def(List.of(), List.of(
                Map.of("id", "w", "type", "tool", "tool", Map.of("scope", "CONTENT", "id", weatherTool), "input", Map.of("city", "Rize")),
                Map.of("id", "ai", "type", "ai", "prompt", "Özet {{steps.w.output.city}}")), ""));
        java.math.BigDecimal original = (java.math.BigDecimal) org.springframework.test.util.ReflectionTestUtils.getField(engine, "monthlyBudgetUsd");
        try {
            JsonNode first = runAndWait(m, wf, Map.of());
            assertThat(first.get("status").asText()).isEqualTo("SUCCEEDED");
            // budget now equals what was spent -> exhausted
            org.springframework.test.util.ReflectionTestUtils.setField(engine, "monthlyBudgetUsd", engine.monthlySpend(
                    UUID.fromString(getJson("/api/me", m).get("id").asText())));
            assertThat(getJson("/api/me/ai-budget", m).get("exhausted").asBoolean()).isTrue();
            MvcResult r = mvc.perform(jsonPost("/api/workflows/" + wf + "/runs", Map.of("input", Map.of())).header("Authorization", bearer(m))).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(402);
            assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("bütçen doldu");
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(engine, "monthlyBudgetUsd", original);
        }
    }

    private static Map<String, Object> def(List<?> inputs, List<?> steps, String output) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("inputs", inputs);
        d.put("steps", steps);
        d.put("output", output);
        return d;
    }

    private static Map<String, Object> simple(String id) {
        return def(List.of(), List.of(Map.of("id", id, "type", "ai", "prompt", "Merhaba " + id)), "");
    }

    private static Map<String, Object> sub(String workflowId) {
        return def(List.of(), List.of(Map.of("id", "call", "type", "workflow", "workflowId", workflowId, "input", Map.of())), "");
    }

    private String create(String token, String name, Map<String, Object> definition) throws Exception {
        return postJson("/api/workflows", Map.of("name", name, "definition", definition), token).get("id").asText();
    }

    private JsonNode runAndWait(String token, String wf, Map<String, Object> input) throws Exception {
        return waitRun(token, postJson("/api/workflows/" + wf + "/runs", Map.of("input", input), token).get("id").asText());
    }

    private JsonNode waitRun(String token, String runId) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        JsonNode r;
        do {
            r = getJson("/api/workflow-runs/" + runId, token);
            if (Set.of("SUCCEEDED", "FAILED").contains(r.get("status").asText())) return r;
            Thread.sleep(100);
        } while (System.nanoTime() < end);
        throw new AssertionError("run did not finish: " + r);
    }

    private static Map<String, JsonNode> byStep(JsonNode run) {
        Map<String, JsonNode> m = new HashMap<>();
        run.get("steps").forEach(s -> m.put(s.get("stepId").asText(), s)); // latest execution wins
        return m;
    }
}
