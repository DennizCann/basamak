package com.basamak.tool;

import com.basamak.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes ONE attempt of a tool request. Retry policy belongs to the caller (job handler); this class only says
 * whether the failure is worth retrying.
 * Limits: total deadline = timeoutSeconds (enforced by aborting the request), 1 MB response cap, max 3 redirects
 * each re-validated by UrlGuard, no cookies, no proxy, secrets masked in everything returned or logged.
 */
@Slf4j
@Component
public class ToolExecutor {
    public static final int MAX_BYTES = 1_000_000;
    private static final int MAX_REDIRECTS = 3;

    public enum Outcome { SUCCEEDED, HTTP_ERROR, RETRYABLE, TIMED_OUT, BLOCKED, CANCELLED, FAILED }

    public record Result(Outcome outcome, Integer httpStatus, JsonNode output, String error, long durationMs, Map<String, Object> request) {
        public boolean retryable() { return outcome == Outcome.RETRYABLE || outcome == Outcome.TIMED_OUT; }
    }

    private final UrlGuard guard;
    private final ObjectMapper json;
    private final CloseableHttpClient trustedClient;
    private final CloseableHttpClient memberClient;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tool-deadline");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, HttpUriRequestBase> live = new ConcurrentHashMap<>();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    public ToolExecutor(UrlGuard guard, ObjectMapper json) {
        this.guard = guard;
        this.json = json;
        this.trustedClient = client(host -> guard.isTrustedHostName(host) ? InetAddress.getAllByName(host) : guard.resolveChecked(host));
        this.memberClient = client(guard::resolveChecked);
    }

    private static CloseableHttpClient client(Resolve r) {
        DnsResolver dns = new DnsResolver() {
            @Override public InetAddress[] resolve(String host) throws UnknownHostException { return r.resolve(host); }
            @Override public String resolveCanonicalHostname(String host) { return host; }
        };
        var cm = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(dns) // the connection uses exactly the addresses that passed the check
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(5)).setSocketTimeout(Timeout.ofSeconds(30)).build())
                .setMaxConnTotal(64).setMaxConnPerRoute(16).build();
        return HttpClients.custom().setConnectionManager(cm).disableRedirectHandling().disableAutomaticRetries()
                .disableCookieManagement().disableAuthCaching().setUserAgent("Basamak-Tool/1.0").build();
    }

    private interface Resolve { InetAddress[] resolve(String host) throws UnknownHostException; }

    /** Aborts a running attempt (member pressed "İptal"). Returns true when a live request was found. */
    public boolean cancel(String runKey) {
        cancelled.add(runKey);
        HttpUriRequestBase req = live.get(runKey);
        if (req != null) req.cancel();
        return req != null;
    }

    public Result execute(String runKey, ToolDefinition def, JsonNode input, Map<String, String> secrets, boolean trusted) {
        long start = System.nanoTime();
        Map<String, String> masked = new HashMap<>();
        secrets.keySet().forEach(k -> masked.put(k, "••••"));
        Map<String, Object> preview = new LinkedHashMap<>();
        try {
            preview.put("method", def.method());
            preview.put("url", def.renderUrl(input, masked));
            preview.put("headers", def.renderHeaders(input, masked));
            JsonNode body = def.renderBody(input, json);
            if (body != null) preview.put("body", body);

            String url = def.renderUrl(input, secrets);
            Map<String, String> headers = def.renderHeaders(input, secrets);
            String method = def.method();
            String bodyText = body == null ? null : json.writeValueAsString(body);
            URI uri = guard.check(url, trusted);
            String originHost = UrlGuard.hostPort(uri);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(def.timeoutSeconds());

            for (int hop = 0; ; hop++) {
                HttpUriRequestBase req = new HttpUriRequestBase(method, uri);
                long remainingMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                req.setConfig(RequestConfig.custom().setResponseTimeout(Timeout.ofMilliseconds(remainingMs)).build());
                boolean sameOrigin = UrlGuard.hostPort(uri).equals(originHost);
                if (sameOrigin) headers.forEach(req::setHeader); // never forward secrets to another host on redirect
                req.setHeader("Accept", "application/json, text/plain;q=0.9, */*;q=0.5");
                if (bodyText != null) req.setEntity(new StringEntity(bodyText, ContentType.APPLICATION_JSON));

                AtomicReference<Boolean> timedOut = new AtomicReference<>(false);
                ScheduledFuture<?> killer = timer.schedule(() -> { timedOut.set(true); req.cancel(); }, remainingMs, TimeUnit.MILLISECONDS);
                live.put(runKey, req);
                if (cancelled.remove(runKey)) req.cancel();
                Response resp;
                try {
                    resp = (trusted ? trustedClient : memberClient).execute(req, r -> {
                        Header loc = r.getFirstHeader("Location");
                        return new Response(r.getCode(), loc == null ? null : loc.getValue(), r.getEntity() == null ? new byte[0] : readCapped(r.getEntity().getContent()));
                    });
                } catch (IOException e) {
                    long ms = elapsed(start);
                    if (timedOut.get()) return new Result(Outcome.TIMED_OUT, null, null, "İstek " + def.timeoutSeconds() + " saniyede tamamlanmadı (zaman aşımı).", ms, preview);
                    if (req.isCancelled()) return new Result(Outcome.CANCELLED, null, null, "Çalıştırma iptal edildi.", ms, preview);
                    if (e instanceof TooLarge) return new Result(Outcome.FAILED, null, null, "Cevap 1 MB sınırını aşıyor.", ms, preview);
                    return new Result(Outcome.RETRYABLE, null, null, "Bağlantı hatası: " + scrub(String.valueOf(e.getMessage()), secrets), ms, preview);
                } finally {
                    killer.cancel(false);
                    live.remove(runKey);
                }

                if (resp.status >= 300 && resp.status < 400 && resp.location != null) {
                    if (hop >= MAX_REDIRECTS) return new Result(Outcome.FAILED, resp.status, null, "Çok fazla yönlendirme.", elapsed(start), preview);
                    uri = guard.check(uri.resolve(resp.location).toString(), trusted); // every hop is validated again
                    if (resp.status == 303 || ((resp.status == 301 || resp.status == 302) && method.equals("POST"))) { method = "GET"; bodyText = null; }
                    continue;
                }
                long ms = elapsed(start);
                JsonNode out = parse(resp.body, def.outputPath());
                if (resp.status >= 200 && resp.status < 300) return new Result(Outcome.SUCCEEDED, resp.status, out, null, ms, preview);
                boolean retry = resp.status == 408 || resp.status == 425 || resp.status == 429 || resp.status >= 500;
                String msg = "Dış servis " + resp.status + " döndü" + (retry ? " (geçici hata)." : ".");
                return new Result(retry ? Outcome.RETRYABLE : Outcome.HTTP_ERROR, resp.status, out, msg, ms, preview);
            }
        } catch (ApiException e) {
            Outcome o = "blocked_url".equals(e.getCode()) ? Outcome.BLOCKED : Outcome.FAILED;
            return new Result(o, null, null, e.getMessage(), elapsed(start), preview);
        } catch (Exception e) {
            log.warn("tool execution failed: {}", e.getClass().getSimpleName());
            return new Result(Outcome.FAILED, null, null, "Araç çalıştırılamadı: " + scrub(String.valueOf(e.getMessage()), secrets), elapsed(start), preview);
        } finally {
            cancelled.remove(runKey);
        }
    }

    private record Response(int status, String location, byte[] body) {}

    private static final class TooLarge extends IOException {}

    private static byte[] readCapped(InputStream in) throws IOException {
        byte[] data = in.readNBytes(MAX_BYTES + 1);
        if (data.length > MAX_BYTES) throw new TooLarge();
        return data;
    }

    private JsonNode parse(byte[] body, String outputPath) {
        String text = new String(body, StandardCharsets.UTF_8);
        JsonNode node;
        try {
            node = text.isBlank() ? json.nullNode() : json.readTree(text);
        } catch (Exception e) {
            return new TextNode(text.length() > 10_000 ? text.substring(0, 10_000) + "…" : text);
        }
        if (outputPath != null && !outputPath.isBlank() && node.isContainerNode()) {
            JsonNode picked = node;
            for (String part : outputPath.split("\\.")) picked = picked.isArray() && part.matches("\\d+") ? picked.path(Integer.parseInt(part)) : picked.path(part);
            if (!picked.isMissingNode()) return picked;
        }
        return node;
    }

    static String scrub(String text, Map<String, String> secrets) {
        String s = text;
        for (String v : secrets.values()) if (v != null && v.length() >= 3) s = s.replace(v, "••••");
        return s;
    }

    private static long elapsed(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    @PreDestroy
    void close() throws IOException {
        timer.shutdownNow();
        trustedClient.close();
        memberClient.close();
    }
}
