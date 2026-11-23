package com.basamak.tool;

import com.basamak.IntegrationTest;
import com.basamak.jobs.JobQueue;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Scenario 6 (CMS tool, secret, test console, member run through the queue with live status, timeout, retry,
 * cancel) and scenario 7 (SSRF: internal targets can be neither saved nor run).
 * A local HTTP server plays the external API; it is "trusted" only for staff tools, exactly like the mock API.
 */
class ToolIT extends IntegrationTest {
    static HttpServer fake;
    static int port;
    static final Map<String, AtomicInteger> flaky = new ConcurrentHashMap<>();
    static final List<String> receivedKeys = new CopyOnWriteArrayList<>();

    @Autowired UrlGuard guard;
    @Autowired JdbcTemplate jdbc;
    @Autowired JobQueue queue;

    @BeforeAll
    static void startFake() throws Exception {
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.setExecutor(Executors.newCachedThreadPool());
        fake.createContext("/", ToolIT::handle);
        fake.start();
        port = fake.getAddress().getPort();
    }

    @AfterAll
    static void stopFake() {
        fake.stop(0);
    }

    @BeforeEach
    void trust() {
        guard.trustForTests("127.0.0.1:" + port);
        guard.setResolverForTests(null);
    }

    private static void handle(HttpExchange ex) throws java.io.IOException {
        String path = ex.getRequestURI().getPath();
        String q = Optional.ofNullable(ex.getRequestURI().getQuery()).orElse("");
        Map<String, String> p = new HashMap<>();
        for (String kv : q.split("&")) if (kv.contains("=")) p.put(kv.substring(0, kv.indexOf('=')), kv.substring(kv.indexOf('=') + 1));
        int code = 200;
        String body;
        switch (path) {
            case "/flaky" -> {
                receivedKeys.add(String.valueOf(ex.getRequestHeaders().getFirst("X-Api-Key")));
                int n = flaky.computeIfAbsent(p.get("key"), k -> new AtomicInteger()).incrementAndGet();
                int fail = Integer.parseInt(p.getOrDefault("fail", "0"));
                if (n <= fail) { code = 503; body = "{\"error\":\"busy\"}"; } else body = "{\"data\":{\"ok\":true,\"call\":" + n + "}}";
            }
            case "/slow" -> {
                try { Thread.sleep(Long.parseLong(p.getOrDefault("ms", "3000"))); } catch (InterruptedException ignored) { }
                body = "{\"slow\":true}";
            }
            case "/redirect" -> {
                ex.getResponseHeaders().set("Location", java.net.URLDecoder.decode(p.get("to"), StandardCharsets.UTF_8));
                ex.sendResponseHeaders(302, -1);
                ex.close();
                return;
            }
            default -> { code = 404; body = "{}"; }
        }
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        try {
            ex.sendResponseHeaders(code, b.length);
            ex.getResponseBody().write(b);
        } catch (java.io.IOException ignored) { // client aborted (timeout / cancel tests)
        }
        ex.close();
    }

    // ------------------------------------------------------------------ scenario 6

    @Test
    void scenario6_cmsToolWithSecret_testConsole_memberRunRetriesUntilSuccess_withLiveEvents() throws Exception {
        String admin = cmsToken("admin@basamak.local");
        String key = "s6-" + UUID.randomUUID();
        Map<String, Object> def = toolDef("http://127.0.0.1:" + port + "/flaky?key={{input.k}}&fail=2", 5, 2);
        JsonNode tool = createContent(admin, "TOOL", "Kararsız servis", def);
        String toolId = tool.get("id").asText();

        // secret: stored encrypted, listed without value, never in the versioned body
        mvc.perform(jsonPut("/api/cms/tools/" + toolId + "/secrets/apiKey", Map.of("value", "s3cret-value-123")).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        JsonNode secrets = getJson("/api/cms/tools/" + toolId + "/secrets", admin);
        assertThat(secrets.toString()).contains("apiKey").doesNotContain("s3cret-value-123");
        String stored = jdbc.queryForObject("SELECT ciphertext FROM tool_secret WHERE tool_id = ?::uuid", String.class, toolId);
        assertThat(stored).doesNotContain("s3cret-value-123");

        // test console: one attempt, request shown with the secret masked
        JsonNode test = postJson("/api/cms/tools/" + toolId + "/test", Map.of("input", Map.of("k", key + "-console")), admin);
        assertThat(test.get("outcome").asText()).isEqualTo("RETRYABLE");
        assertThat(test.get("httpStatus").asInt()).isEqualTo(503);
        assertThat(test.toString()).doesNotContain("s3cret-value-123").contains("••••");

        publish(admin, tool.at("/latest/id").asText());
        String trainingId = publishTraining(admin, toolId);

        String m = newMember("tool6");
        postJson("/api/trainings/" + trainingId + "/enroll", null, m);
        JsonNode form = getJson("/api/trainings/" + trainingId + "/tools/t1", m);
        assertThat(form.toString()).contains("\"name\":\"k\"").doesNotContain("127.0.0.1").doesNotContain("apiKey");

        JsonNode run = postJson("/api/tool-runs", Map.of("trainingId", trainingId, "itemKey", "t1", "input", Map.of("k", key), "clientRequestId", "click-1"), m);
        // same clientRequestId (double click) -> same run
        JsonNode again = postJson("/api/tool-runs", Map.of("trainingId", trainingId, "itemKey", "t1", "input", Map.of("k", key), "clientRequestId", "click-1"), m);
        assertThat(again.get("id")).isEqualTo(run.get("id"));

        JsonNode done = waitFor(m, run.get("id").asText(), Set.of("SUCCEEDED", "FAILED", "TIMED_OUT"), Duration.ofSeconds(20));
        assertThat(done.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.get("attempts").asInt()).isEqualTo(3);
        assertThat(done.at("/output/ok").asBoolean()).isTrue(); // outputPath "data"
        assertThat(done.toString()).doesNotContain("s3cret-value-123");
        assertThat(receivedKeys).contains("s3cret-value-123"); // the real value reached the API

        List<String> types = jdbc.queryForList("SELECT type FROM tool_run_event WHERE run_id = ?::uuid ORDER BY id", String.class, run.get("id").asText());
        assertThat(types).containsSubsequence("queued", "running", "retrying", "running", "retrying", "running", "succeeded");

        // SSE replay after Last-Event-ID: only events after id 2 are sent again
        MvcResult sse = mvc.perform(get("/api/tool-runs/" + run.get("id").asText() + "/events").header("Authorization", bearer(m)).header("Last-Event-ID", "0"))
                .andReturn();
        assertThat(sse.getResponse().getContentAsString()).contains("event:succeeded");

        // training item completed + points for the run
        Integer pts = jdbc.queryForObject("SELECT count(*) FROM point_ledger WHERE action = 'TOOL_RUN_SUCCEEDED' AND event_key = ?", Integer.class, "tool-run:" + run.get("id").asText());
        assertThat(pts).isEqualTo(1);
        JsonNode training = getJson("/api/trainings/" + trainingId, m);
        assertThat(training.toString()).contains("COMPLETED");
    }

    @Test
    void timeout_isReportedAfterAllAttempts() throws Exception {
        String admin = cmsToken("admin@basamak.local");
        JsonNode tool = createContent(admin, "TOOL", "Yavaş servis", toolDef("http://127.0.0.1:" + port + "/slow?ms=3000&k={{input.k}}", 1, 0));
        publish(admin, tool.at("/latest/id").asText());
        String trainingId = publishTraining(admin, tool.get("id").asText());
        String m = newMember("tooltimeout");
        postJson("/api/trainings/" + trainingId + "/enroll", null, m);
        JsonNode run = postJson("/api/tool-runs", Map.of("trainingId", trainingId, "itemKey", "t1", "input", Map.of("k", "x")), m);
        JsonNode done = waitFor(m, run.get("id").asText(), Set.of("SUCCEEDED", "FAILED", "TIMED_OUT"), Duration.ofSeconds(15));
        assertThat(done.get("status").asText()).isEqualTo("TIMED_OUT");
        assertThat(done.get("durationMs").asLong()).isBetween(900L, 2900L);
    }

    @Test
    void cancel_abortsARunningRequest() throws Exception {
        String admin = cmsToken("admin@basamak.local");
        JsonNode tool = createContent(admin, "TOOL", "İptal edilecek", toolDef("http://127.0.0.1:" + port + "/slow?ms=8000&k={{input.k}}", 20, 0));
        publish(admin, tool.get("latest").get("id").asText());
        String trainingId = publishTraining(admin, tool.get("id").asText());
        String m = newMember("toolcancel");
        postJson("/api/trainings/" + trainingId + "/enroll", null, m);
        String runId = postJson("/api/tool-runs", Map.of("trainingId", trainingId, "itemKey", "t1", "input", Map.of("k", "x")), m).get("id").asText();
        waitFor(m, runId, Set.of("RUNNING"), Duration.ofSeconds(10));
        long t0 = System.nanoTime();
        postJson("/api/tool-runs/" + runId + "/cancel", null, m);
        JsonNode done = waitFor(m, runId, Set.of("CANCELLED", "SUCCEEDED", "FAILED"), Duration.ofSeconds(5));
        assertThat(done.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(4));
    }

    @Test
    void runsOfOtherMembersAreInvisible() throws Exception {
        String a = newMember("toolowner");
        String b = newMember("toolother");
        guard.setResolverForTests(h -> new InetAddress[]{InetAddress.getByName("93.184.216.34")});
        JsonNode tool = postJson("/api/my-tools", Map.of("name", "Benim aracım", "definition", toolDef("https://api.example.test/x?q={{input.k}}", 2, 0)), a);
        mvc.perform(get("/api/my-tools/" + tool.get("id").asText()).header("Authorization", bearer(b))).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ scenario 7

    @Test
    void scenario7_memberToolTargetingInternalAddressesCannotBeSaved() throws Exception {
        String m = newMember("ssrf");
        guard.setResolverForTests(h -> h.equals("evil.example.test")
                ? new InetAddress[]{InetAddress.getByName("93.184.216.34"), InetAddress.getByName("127.0.0.1")} // one bad answer is enough
                : InetAddress.getAllByName(h));
        List<String> blocked = List.of(
                "http://127.0.0.1:" + port + "/flaky",          // trusted for STAFF tools only
                "http://localhost:8080/api/me",
                "http://LOCALHOST./",
                "http://10.0.0.5/",
                "http://172.16.3.4/",
                "http://192.168.1.1/",
                "http://169.254.169.254/latest/meta-data/",
                "http://[::1]/",
                "http://[::ffff:127.0.0.1]/",
                "http://[fd00::1]/",
                "http://0.0.0.0/",
                "http://2130706433/",                          // 127.0.0.1 as a decimal number
                "http://0x7f.0.0.1/",                          // hex octet
                "http://127.1/",                               // short form
                "http://metadata.google.internal/",
                "http://printer.local/",
                "http://evil.example.test/",                   // DNS answer contains a loopback address
                "http://user:pw@example.com/",
                "file:///etc/passwd",
                "gopher://127.0.0.1/",
                "http://{{input.k}}/x");                      // host from input
        for (String url : blocked) {
            MvcResult r = mvc.perform(jsonPost("/api/my-tools", Map.of("name", "ssrf", "definition", toolDef(url, 2, 0))).header("Authorization", bearer(m))).andReturn();
            assertThat(r.getResponse().getStatus()).as(url).isEqualTo(400);
            assertThat(body(r).get("code").asText()).as(url).isIn("blocked_url", "invalid_tool");
        }
    }

    @Test
    void scenario7_dnsRebindingAfterSave_isBlockedAtRunTime() throws Exception {
        String m = newMember("rebind");
        AtomicInteger calls = new AtomicInteger();
        guard.setResolverForTests(h -> calls.incrementAndGet() == 1
                ? new InetAddress[]{InetAddress.getByName("93.184.216.34")}  // at save: a public address
                : new InetAddress[]{InetAddress.getByName("127.0.0.1")});    // later: points inside
        JsonNode tool = postJson("/api/my-tools", Map.of("name", "Değişken DNS", "definition", toolDef("http://rebind.example.test:" + port + "/flaky?key={{input.k}}", 2, 0), "secrets", Map.of("apiKey", "member-key")), m);
        JsonNode run = postJson("/api/tool-runs", Map.of("userToolId", tool.get("id").asText(), "input", Map.of("k", "rebind")), m);
        JsonNode done = waitFor(m, run.get("id").asText(), Set.of("SUCCEEDED", "FAILED", "TIMED_OUT"), Duration.ofSeconds(10));
        assertThat(done.get("status").asText()).isEqualTo("FAILED");
        assertThat(done.get("error").asText()).contains("127.0.0.1");
        assertThat(flaky.get("rebind")).isNull(); // the request never reached the internal server
    }

    @Test
    void scenario7_redirectToInternalAddressIsBlocked() throws Exception {
        String admin = cmsToken("admin@basamak.local");
        String to = java.net.URLEncoder.encode("http://169.254.169.254/latest/meta-data/", StandardCharsets.UTF_8);
        JsonNode test = createContent(admin, "TOOL", "Yönlendirme", toolDef("http://127.0.0.1:" + port + "/redirect?to=" + to + "&k={{input.k}}", 2, 0));
        mvc.perform(jsonPut("/api/cms/tools/" + test.get("id").asText() + "/secrets/apiKey", Map.of("value", "k1")).header("Authorization", bearer(admin)));
        JsonNode r = postJson("/api/cms/tools/" + test.get("id").asText() + "/test", Map.of("input", Map.of("k", "x")), admin);
        assertThat(r.get("outcome").asText()).isEqualTo("BLOCKED");
        assertThat(r.get("error").asText()).contains("169.254.169.254");
    }

    // ------------------------------------------------------------------ queue

    @Test
    void queue_concurrentClaimsNeverTakeTheSameJob() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            UUID ref = UUID.randomUUID();
            queue.enqueue("TEST_NOOP", ref, "test-noop:" + ref, 1);
            ids.add(ref);
        }
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<List<JobQueue.Claimed>>> fs = new ArrayList<>();
        for (int i = 0; i < 6; i++) fs.add(pool.submit(() -> queue.claim("t", 10, Duration.ofSeconds(30))));
        Set<UUID> seen = new HashSet<>();
        for (var f : fs) for (var c : f.get()) if (c.kind().equals("TEST_NOOP")) assertThat(seen.add(c.id())).isTrue();
        pool.shutdown();
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> toolDef(String url, int timeout, int retries) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("description", "Test aracı");
        d.put("inputs", List.of(Map.of("name", "k", "label", "Anahtar", "type", "string", "required", true)));
        d.put("request", Map.of("method", "GET", "url", url, "headers", Map.of("X-Api-Key", "{{secret.apiKey}}")));
        d.put("timeoutSeconds", timeout);
        d.put("retries", retries);
        d.put("outputPath", "data");
        return d;
    }

    private JsonNode createContent(String token, String type, String title, Object body) throws Exception {
        MvcResult r = mvc.perform(jsonPost("/api/cms/items", Map.of("type", type, "title", title, "body", body)).header("Authorization", bearer(token))).andReturn();
        if (r.getResponse().getStatus() != 201) throw new AssertionError(r.getResponse().getContentAsString());
        return body(r);
    }

    private void publish(String admin, String versionId) throws Exception {
        for (String step : List.of("submit", "approve", "publish")) {
            mvc.perform(post("/api/cms/versions/" + versionId + "/" + step).header("Authorization", bearer(admin))).andExpect(status().isOk());
        }
    }

    private String publishTraining(String admin, String toolId) throws Exception {
        if (jdbc.queryForObject("SELECT count(*) FROM tool_secret WHERE tool_id = ?::uuid", Integer.class, toolId) == 0) {
            mvc.perform(jsonPut("/api/cms/tools/" + toolId + "/secrets/apiKey", Map.of("value", "k-" + UUID.randomUUID())).header("Authorization", bearer(admin)));
        }
        Map<String, Object> body = Map.of("summary", "Araç eğitimi", "items",
                List.of(Map.of("key", "t1", "type", "tool", "title", "Aracı çalıştır", "toolId", toolId, "toolVersionNo", 1)));
        JsonNode t = createContent(admin, "TRAINING", "Araç eğitimi " + UUID.randomUUID().toString().substring(0, 6), body);
        publish(admin, t.at("/latest/id").asText());
        return t.get("id").asText();
    }

    private JsonNode waitFor(String token, String runId, Set<String> statuses, Duration max) throws Exception {
        long end = System.nanoTime() + max.toNanos();
        JsonNode r;
        do {
            r = getJson("/api/tool-runs/" + runId, token);
            if (statuses.contains(r.get("status").asText())) return r;
            Thread.sleep(100);
        } while (System.nanoTime() < end);
        throw new AssertionError("run " + runId + " stayed " + r.get("status").asText() + ": " + r);
    }
}
