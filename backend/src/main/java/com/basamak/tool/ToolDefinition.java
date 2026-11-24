package com.basamak.tool;

import com.basamak.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tool definition (same shape for CMS tools = TOOL content body, and member tools).
 * <pre>
 * { "description": "...",
 *   "inputs":  [{"name":"city","label":"Şehir","type":"string|number|boolean","required":true,"maxLength":100}],
 *   "request": {"method":"GET|POST","url":"https://api.example.com/weather?city={{input.city}}",
 *               "headers":{"X-Api-Key":"{{secret.apiKey}}"},
 *               "body":{"q":"{{input.city}}"}},            // JSON template, POST only
 *   "timeoutSeconds": 10, "retries": 2,                     // 1..30 s, 0..3 extra attempts
 *   "outputPath": "data.result",                            // optional: pick a field of the JSON answer
 *   "auth": {"type":"oauth2_client_credentials","tokenUrl":"https://auth.example.com/token","scope":"read"} }
 * </pre>
 * OAuth 2.0 client credentials: the client id and secret are the tool secrets {@code clientId} and {@code clientSecret};
 * the executor fetches an access token from tokenUrl (same SSRF rules as the API URL), caches it until it expires and
 * sends it as {@code Authorization: Bearer}. The token is never stored or shown.
 * Non-JSON answers (PDF, image, ZIP, ...) are saved as a file output of up to 50 MB and downloaded from the run.
 * Templates: values are inserted, never evaluated. In the URL they are percent-encoded and only allowed after the
 * host (the host is fixed at save time and checked by UrlGuard); in headers CR/LF are stripped; in the JSON body a
 * value that is exactly one placeholder keeps its JSON type, otherwise it is inserted as a string by Jackson
 * (no string concatenation of JSON, so no JSON injection). Secrets exist only in headers and the URL query.
 */
public record ToolDefinition(String description, List<Input> inputs, String method, String url, Map<String, String> headers,
                             JsonNode body, int timeoutSeconds, int retries, String outputPath, String oauthTokenUrl, String oauthScope) {

    public record Input(String name, String label, String type, boolean required, int maxLength) {}

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*(input|secret)\\.([A-Za-z0-9_]{1,60})\\s*}}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,59}");

    public static ToolDefinition parse(JsonNode b) {
        if (b == null || !b.isObject()) throw bad("Araç tanımı bir nesne olmalı.");
        List<Input> inputs = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (JsonNode in : b.path("inputs")) {
            String name = in.path("name").asText("");
            if (!NAME.matcher(name).matches()) throw bad("Girdi adı harfle başlamalı; harf, rakam ve _ içerebilir: '" + name + "'");
            if (!names.add(name)) throw bad("Girdi adı tekrar ediyor: " + name);
            String type = in.path("type").asText("string");
            if (!Set.of("string", "number", "boolean").contains(type)) throw bad("'" + name + "' girdisinin türü string, number ya da boolean olmalı.");
            int max = in.path("maxLength").asInt(500);
            inputs.add(new Input(name, in.path("label").asText(name), type, in.path("required").asBoolean(false), Math.min(Math.max(max, 1), 2000)));
        }
        if (inputs.size() > 20) throw bad("Bir araçta en fazla 20 girdi olabilir.");
        JsonNode req = b.path("request");
        String method = req.path("method").asText("GET").toUpperCase(Locale.ROOT);
        if (!Set.of("GET", "POST").contains(method)) throw bad("HTTP yöntemi GET ya da POST olmalı.");
        String url = req.path("url").asText("").trim();
        if (url.isEmpty()) throw bad("Aracın istek adresi (URL) boş.");
        if (url.length() > 2000) throw bad("URL en fazla 2000 karakter olabilir.");
        Map<String, String> headers = new LinkedHashMap<>();
        req.path("headers").fields().forEachRemaining(e -> {
            if (!e.getKey().matches("[A-Za-z0-9-]{1,60}")) throw bad("Geçersiz başlık adı: " + e.getKey());
            if (Set.of("host", "content-length", "connection", "transfer-encoding").contains(e.getKey().toLowerCase(Locale.ROOT))) {
                throw bad(e.getKey() + " başlığı araçta ayarlanamaz.");
            }
            headers.put(e.getKey(), e.getValue().asText(""));
        });
        JsonNode body = req.path("body");
        if (!body.isMissingNode() && !body.isNull() && !method.equals("POST")) throw bad("İstek gövdesi yalnız POST için kullanılabilir.");
        int timeout = b.path("timeoutSeconds").asInt(10);
        if (timeout < 1 || timeout > 30) throw bad("Zaman aşımı 1 ile 30 saniye arasında olmalı.");
        int retries = b.path("retries").asInt(2);
        if (retries < 0 || retries > 3) throw bad("Yeniden deneme sayısı 0 ile 3 arasında olmalı.");
        JsonNode auth = b.path("auth");
        String tokenUrl = null, scope = null;
        if (!auth.isMissingNode() && !auth.isNull()) {
            if (!"oauth2_client_credentials".equals(auth.path("type").asText())) throw bad("Kimlik doğrulama türü yalnız oauth2_client_credentials olabilir.");
            tokenUrl = auth.path("tokenUrl").asText("").trim();
            if (!tokenUrl.startsWith("https://") && !tokenUrl.startsWith("http://")) throw bad("OAuth jeton adresi (tokenUrl) http(s) olmalı.");
            if (tokenUrl.contains("{{")) throw bad("OAuth jeton adresi şablon içeremez.");
            scope = auth.path("scope").asText("");
        }
        ToolDefinition d = new ToolDefinition(b.path("description").asText(""), inputs, method, url, headers,
                body.isMissingNode() || body.isNull() ? null : body, timeout, retries, b.path("outputPath").asText(""), tokenUrl, scope);
        d.checkPlaceholders(names);
        return d;
    }

    /** Placeholders must refer to declared inputs; secrets only in headers/URL query; the host must be literal. */
    private void checkPlaceholders(Set<String> inputNames) {
        int schemeEnd = url.indexOf("://");
        int pathStart = schemeEnd < 0 ? -1 : indexOfAny(url, schemeEnd + 3, "/?#");
        int firstPlaceholder = url.indexOf("{{");
        if (firstPlaceholder >= 0 && (pathStart < 0 || firstPlaceholder < pathStart)) {
            throw bad("Sunucu adı şablonla oluşturulamaz; {{...}} yalnız yol ve sorgu kısmında kullanılabilir.");
        }
        List<String> texts = new ArrayList<>(List.of(url));
        texts.addAll(headers.values());
        for (String t : texts) refs(t, inputNames);
        if (body != null) {
            List<String> bodyTexts = new ArrayList<>();
            collectText(body, bodyTexts);
            for (String t : bodyTexts) {
                for (String[] r : refs(t, inputNames)) if (r[0].equals("secret")) throw bad("Sırlar istek gövdesinde kullanılamaz; başlıkta kullanın.");
            }
        }
    }

    private static List<String[]> refs(String text, Set<String> inputNames) {
        List<String[]> out = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            if (m.group(1).equals("input") && !inputNames.contains(m.group(2))) throw bad("Tanımlı olmayan girdi kullanılmış: " + m.group(2));
            out.add(new String[]{m.group(1), m.group(2)});
        }
        if (text.replaceAll(PLACEHOLDER.pattern(), "").contains("{{")) throw bad("Şablon yalnız {{input.ad}} ya da {{secret.ad}} biçiminde olabilir.");
        return out;
    }

    public Set<String> secretNames() {
        Set<String> s = new TreeSet<>();
        List<String> texts = new ArrayList<>(List.of(url));
        texts.addAll(headers.values());
        for (String t : texts) {
            Matcher m = PLACEHOLDER.matcher(t);
            while (m.find()) if (m.group(1).equals("secret")) s.add(m.group(2));
        }
        if (oauthTokenUrl != null) { s.add("clientId"); s.add("clientSecret"); }
        return s;
    }

    /** Validates member input against the declared inputs; returns a normalised copy. */
    public ObjectNode validateInput(JsonNode input, ObjectMapper json) {
        ObjectNode out = json.createObjectNode();
        JsonNode in = input == null ? json.createObjectNode() : input;
        for (Input i : inputs) {
            JsonNode v = in.path(i.name());
            if (v.isMissingNode() || v.isNull() || (v.isTextual() && v.asText().isBlank())) {
                if (i.required()) throw ApiException.badRequest("invalid_input", "'" + i.label() + "' alanı zorunlu.");
                continue;
            }
            switch (i.type()) {
                case "number" -> {
                    if (!v.isNumber() && !(v.isTextual() && v.asText().matches("-?\\d+(\\.\\d+)?"))) throw ApiException.badRequest("invalid_input", "'" + i.label() + "' bir sayı olmalı.");
                    out.put(i.name(), v.isNumber() ? v.decimalValue() : new java.math.BigDecimal(v.asText()));
                }
                case "boolean" -> out.put(i.name(), v.isBoolean() ? v.asBoolean() : Boolean.parseBoolean(v.asText()));
                default -> {
                    String s = v.asText();
                    if (s.length() > i.maxLength()) throw ApiException.badRequest("invalid_input", "'" + i.label() + "' en fazla " + i.maxLength() + " karakter olabilir.");
                    out.put(i.name(), s);
                }
            }
        }
        return out;
    }

    public String renderUrl(JsonNode input, Map<String, String> secrets) {
        return replace(url, input, secrets, v -> URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20"));
    }

    public Map<String, String> renderHeaders(JsonNode input, Map<String, String> secrets) {
        Map<String, String> out = new LinkedHashMap<>();
        headers.forEach((k, v) -> out.put(k, replace(v, input, secrets, s -> s.replaceAll("[\\r\\n]", " "))));
        return out;
    }

    public JsonNode renderBody(JsonNode input, ObjectMapper json) {
        return body == null ? null : renderNode(body, input, json);
    }

    private JsonNode renderNode(JsonNode n, JsonNode input, ObjectMapper json) {
        if (n.isTextual()) {
            Matcher whole = PLACEHOLDER.matcher(n.asText().trim());
            if (whole.matches() && whole.group(1).equals("input")) {
                JsonNode v = input.path(whole.group(2));
                return v.isMissingNode() ? json.nullNode() : v;
            }
            return new TextNode(replace(n.asText(), input, Map.of(), s -> s));
        }
        if (n.isObject()) {
            ObjectNode o = json.createObjectNode();
            n.fields().forEachRemaining(e -> o.set(e.getKey(), renderNode(e.getValue(), input, json)));
            return o;
        }
        if (n.isArray()) {
            var a = json.createArrayNode();
            n.forEach(x -> a.add(renderNode(x, input, json)));
            return a;
        }
        return n;
    }

    private static String replace(String template, JsonNode input, Map<String, String> secrets, java.util.function.UnaryOperator<String> enc) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value;
            if (m.group(1).equals("input")) {
                JsonNode v = input.path(m.group(2));
                value = v.isMissingNode() || v.isNull() ? "" : v.asText();
            } else {
                value = secrets.get(m.group(2));
                if (value == null) throw ApiException.badRequest("missing_secret", "Aracın '" + m.group(2) + "' sırrı tanımlanmamış. Araç sahibi sırrı eklemeli.");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(enc.apply(value)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static void collectText(JsonNode n, List<String> out) {
        if (n.isTextual()) out.add(n.asText());
        n.forEach(c -> collectText(c, out));
    }

    private static int indexOfAny(String s, int from, String chars) {
        for (int i = from; i < s.length(); i++) if (chars.indexOf(s.charAt(i)) >= 0) return i;
        return -1;
    }

    /** The host part with placeholders removed is fixed, so it can be checked at save time. */
    public String staticUrlForCheck() {
        return url.replaceAll(PLACEHOLDER.pattern(), "x");
    }

    static ApiException bad(String msg) {
        return ApiException.badRequest("invalid_tool", msg);
    }
}
