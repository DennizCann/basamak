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
 *   GET  /weather?city=Ankara     needs header X-Api-Key = MOCK_API_KEY (default demo-mock-key); deterministic answer
 *   GET  /slow?ms=5000            answers after the given delay (shows timeout)
 *   GET  /flaky?key=k&fail=2      the first `fail` calls for a key return 503, then 200 (shows retry)
 *   GET  /status/{code}           answers with that HTTP status
 *   GET  /redirect?to=URL         302 to URL (shows that redirects to internal addresses are blocked)
 *   POST /echo                    returns the JSON body it received
 *   GET  /health
 */
public class MockApi {
    private static final Map<String, AtomicInteger> FLAKY = new ConcurrentHashMap<>();

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
            case "/echo" -> send(ex, 200, "{\"received\":" + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8) + "}");
            default -> {
                if (path.startsWith("/status/")) {
                    int code = Integer.parseInt(path.substring(8));
                    send(ex, code, "{\"status\":" + code + "}");
                } else send(ex, 404, "{\"error\":\"not found\"}");
            }
        }
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
