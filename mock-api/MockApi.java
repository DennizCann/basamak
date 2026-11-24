import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fake external API for demonstrating tools without paid services (zero cost). JDK only:
 *   java mock-api/MockApi.java            (port 8090, or MOCK_API_PORT)
 *
 * Learning tools (used by the seeded platform tools; rule-based, NOT a real language model):
 *   POST /text/summarize          {"text":"...","sentences":2} -> first sentences as a summary, word count, reading time
 *   GET  /glossary?term=API       explanation of a platform / automation term (small built-in Turkish glossary)
 *   POST /text/check              {"text":"..."} needs X-Api-Key = MOCK_API_KEY; writing feedback (long sentences, repeats...)
 * Demo helpers:
 *   GET  /weather?city=Ankara     needs header X-Api-Key = MOCK_API_KEY (default demo-mock-key); deterministic answer
 *   GET  /slow?ms=5000            answers after the given delay (shows timeout)
 *   GET  /flaky?key=k&fail=2      the first `fail` calls for a key return 503, then 200 (shows retry)
 *   GET  /status/{code}           answers with that HTTP status
 *   GET  /redirect?to=URL         302 to URL (shows that redirects to internal addresses are blocked)
 *   POST /echo                    returns the JSON body it received
 *   POST /oauth/token             OAuth 2.0 client credentials (client demo-client / demo-secret) -> access_token, 300 s
 *   GET  /secure/report?q=x       needs Authorization: Bearer <token from /oauth/token>
 *   GET  /file?mb=5               a PDF-typed file of the given size (shows file outputs up to 50 MB)
 *   GET  /health
 */
public class MockApi {
    private static final Map<String, AtomicInteger> FLAKY = new ConcurrentHashMap<>();
    private static final Set<String> TOKENS = ConcurrentHashMap.newKeySet();
    private static final Map<String, String> GLOSSARY = new LinkedHashMap<>();
    static {
        GLOSSARY.put("api", "Uygulamaların birbirleriyle konuşmak için kullandığı kurallı arayüz. Bir araç, bir API adresine istek gönderip cevap alır.");
        GLOSSARY.put("webhook", "Bir olay olduğunda başka bir sistemin, önceden verilen adrese kendiliğinden istek göndermesi. İş akışlarını dışarıdan başlatmak için kullanılır.");
        GLOSSARY.put("iş akışı", "Birbirini izleyen adımlar zinciri; bir adımın çıktısı sonraki adımın girdisi olur.");
        GLOSSARY.put("araç", "Girdi alan, bir API'ye bağlanıp arka planda çalışan ve çıktı üreten birim.");
        GLOSSARY.put("istem", "Yapay zekâ modeline verilen talimat metni (prompt).");
        GLOSSARY.put("prompt", "Yapay zekâ modeline verilen talimat metni (istem).");
        GLOSSARY.put("token", "Dil modellerinin metni böldüğü küçük parça; maliyet ve sınırlar token sayısıyla hesaplanır.");
        GLOSSARY.put("cron", "Bir işin hangi dakika, saat ve günlerde kendiliğinden çalışacağını yazan zamanlama ifadesi.");
        GLOSSARY.put("json", "Verileri anahtar ve değer çiftleriyle taşıyan, insanların da okuyabildiği metin biçimi.");
        GLOSSARY.put("journey", "Basamak'ta bir seviye; tamamlanması gereken eğitimlerin toplamı (Beginner, Expert, Master).");
        GLOSSARY.put("çatallama", "Paylaşılan bir aracı ya da iş akışını kendi hesabına kopyalayıp geliştirme (fork).");
        GLOSSARY.put("zaman aşımı", "Bir isteğin en fazla ne kadar bekleneceği; süre dolunca istek kesilir ve hata sayılır.");
        GLOSSARY.put("ssrf", "Sunucuyu kandırıp iç ağdaki adreslere istek attırma saldırısı. Basamak üye araçlarında bunu engeller.");
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(Optional.ofNullable(System.getenv("MOCK_API_PORT")).orElse("8090"));
        String apiKey = Optional.ofNullable(System.getenv("MOCK_API_KEY")).orElse("demo-mock-key");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", ex -> {
            try {
                handle(ex, apiKey);
            } catch (Exception e) {
                send(ex, 500, "{\"error\":\"mock failure\"}");
            }
        });
        server.start();
        System.out.println("Mock API on http://127.0.0.1:" + port);
    }

    private static void handle(HttpExchange ex, String apiKey) throws Exception {
        String path = ex.getRequestURI().getPath();
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        switch (path) {
            case "/health" -> send(ex, 200, "{\"status\":\"ok\"}");
            case "/text/summarize" -> {
                Map<String, String> b = flatJson(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String text = b.getOrDefault("text", q.getOrDefault("text", "")).trim();
                if (text.isEmpty()) { send(ex, 400, "{\"error\":\"text is required\"}"); return; }
                int n = Math.max(1, Math.min(5, parseIntOr(b.getOrDefault("sentences", q.getOrDefault("sentences", "2")), 2)));
                String[] sentences = text.split("(?<=[.!?])\\s+");
                String summary = String.join(" ", Arrays.copyOf(sentences, Math.min(n, sentences.length)));
                int words = text.split("\\s+").length;
                send(ex, 200, "{\"summary\":" + str(summary) + ",\"wordCount\":" + words + ",\"sentenceCount\":" + sentences.length
                        + ",\"readingMinutes\":" + Math.max(1, Math.round(words / 200.0)) + ",\"method\":\"ilk cümleler (kurala dayalı, yapay zekâ değil)\"}");
            }
            case "/glossary" -> {
                String term = q.getOrDefault("term", "").trim();
                String key = term.toLowerCase(Locale.forLanguageTag("tr"));
                String def = GLOSSARY.get(key);
                send(ex, 200, "{\"term\":" + str(term) + ",\"found\":" + (def != null) + ",\"definition\":"
                        + str(def != null ? def : "Bu terim sözlükte yok. Şunlardan birini deneyin: " + String.join(", ", GLOSSARY.keySet())) + "}");
            }
            case "/text/check" -> {
                if (!apiKey.equals(ex.getRequestHeaders().getFirst("X-Api-Key"))) { send(ex, 401, "{\"error\":\"invalid or missing X-Api-Key\"}"); return; }
                String text = flatJson(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getOrDefault("text", "").trim();
                List<String> issues = new ArrayList<>();
                for (String sentence : text.split("(?<=[.!?])\\s+")) {
                    int w = sentence.isBlank() ? 0 : sentence.trim().split("\\s+").length;
                    if (w > 25) issues.add("Uzun cümle (" + w + " kelime): bölmeyi düşünün.");
                }
                if (text.contains("  ")) issues.add("Art arda iki boşluk var.");
                if (!text.isEmpty() && Character.isLowerCase(text.charAt(0))) issues.add("Metin büyük harfle başlamıyor.");
                if (!text.isEmpty() && ".!?".indexOf(text.charAt(text.length() - 1)) < 0) issues.add("Metin noktalama işaretiyle bitmiyor.");
                String[] ws = text.toLowerCase(Locale.forLanguageTag("tr")).split("\\s+");
                for (int i = 1; i < ws.length; i++) if (!ws[i].isBlank() && ws[i].equals(ws[i - 1])) issues.add("Tekrarlanan kelime: \"" + ws[i] + "\"");
                int score = Math.max(0, 100 - issues.size() * 15);
                StringBuilder arr = new StringBuilder("[");
                for (int i = 0; i < issues.size(); i++) arr.append(i == 0 ? "" : ",").append(str(issues.get(i)));
                send(ex, 200, "{\"score\":" + score + ",\"issueCount\":" + issues.size() + ",\"issues\":" + arr + "]}");
            }
            case "/weather" -> {
                if (!apiKey.equals(ex.getRequestHeaders().getFirst("X-Api-Key"))) { send(ex, 401, "{\"error\":\"invalid or missing X-Api-Key\"}"); return; }
                String city = q.getOrDefault("city", "İstanbul");
                int h = Math.abs(city.toLowerCase(Locale.ROOT).hashCode());
                String[] conditions = {"Güneşli", "Parçalı bulutlu", "Yağmurlu", "Rüzgârlı", "Karlı"};
                send(ex, 200, "{\"city\":" + str(city) + ",\"temperatureC\":" + (h % 35 - 5) + ",\"condition\":" + str(conditions[h % conditions.length])
                        + ",\"source\":\"basamak-mock\"}");
            }
            case "/slow" -> {
                long ms = Math.min(Long.parseLong(q.getOrDefault("ms", "5000")), 60_000);
                Thread.sleep(ms);
                send(ex, 200, "{\"waitedMs\":" + ms + "}");
            }
            case "/flaky" -> {
                int fail = Integer.parseInt(q.getOrDefault("fail", "2"));
                int n = FLAKY.computeIfAbsent(q.getOrDefault("key", "default"), k -> new AtomicInteger()).incrementAndGet();
                if (n <= fail) send(ex, 503, "{\"error\":\"temporarily unavailable\",\"call\":" + n + "}");
                else send(ex, 200, "{\"ok\":true,\"succeededOnCall\":" + n + "}");
            }
            case "/redirect" -> {
                ex.getResponseHeaders().set("Location", q.getOrDefault("to", "/health"));
                ex.sendResponseHeaders(302, -1);
                ex.close();
            }
            case "/oauth/token" -> {
                Map<String, String> f = query(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (!"client_credentials".equals(f.get("grant_type")) || !"demo-client".equals(f.get("client_id")) || !"demo-secret".equals(f.get("client_secret"))) {
                    send(ex, 401, "{\"error\":\"invalid_client\"}");
                    return;
                }
                String t = "tok-" + UUID.randomUUID();
                TOKENS.add(t);
                send(ex, 200, "{\"access_token\":" + str(t) + ",\"token_type\":\"Bearer\",\"expires_in\":300}");
            }
            case "/secure/report" -> {
                String auth = Optional.ofNullable(ex.getRequestHeaders().getFirst("Authorization")).orElse("");
                if (!auth.startsWith("Bearer ") || !TOKENS.contains(auth.substring(7))) { send(ex, 401, "{\"error\":\"invalid_token\"}"); return; }
                send(ex, 200, "{\"report\":" + str("Rapor: " + q.getOrDefault("q", "")) + ",\"authorized\":true}");
            }
            case "/file" -> {
                long mb = Math.min(Long.parseLong(q.getOrDefault("mb", "1")), 60);
                long size = mb * 1024 * 1024;
                ex.getResponseHeaders().set("Content-Type", "application/pdf");
                ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"rapor-" + mb + "mb.pdf\"");
                ex.sendResponseHeaders(200, size);
                try (var out = ex.getResponseBody()) {
                    byte[] chunk = new byte[64 * 1024];
                    byte[] head = "%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII);
                    System.arraycopy(head, 0, chunk, 0, head.length);
                    for (long sent = 0; sent < size; ) {
                        int n = (int) Math.min(chunk.length, size - sent);
                        out.write(chunk, 0, n);
                        sent += n;
                        if (sent == n) java.util.Arrays.fill(chunk, 0, head.length, (byte) 0);
                    }
                }
                ex.close();
            }
            case "/echo" -> send(ex, 200, "{\"received\":" + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8) + "}");
            default -> {
                if (path.startsWith("/status/")) {
                    int code = Integer.parseInt(path.substring(8));
                    send(ex, code, "{\"status\":" + code + "}");
                } else send(ex, 404, "{\"error\":\"not found\"}");
            }
        }
    }

    /** Tiny flat-JSON reader for the demo endpoints (string/number values of a single object). */
    private static Map<String, String> flatJson(String body) {
        Map<String, String> m = new HashMap<>();
        var mt = java.util.regex.Pattern.compile("\"(\\w+)\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|(-?\\d+))").matcher(body == null ? "" : body);
        while (mt.find()) {
            String v = mt.group(3) != null ? mt.group(3).replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\") : mt.group(4);
            m.put(mt.group(1), v);
        }
        return m;
    }

    private static int parseIntOr(String s, int d) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return d; }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String p : raw.split("&")) {
            int i = p.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(p.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String str(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) ex.getResponseBody().write(b);
        ex.close();
    }
}
