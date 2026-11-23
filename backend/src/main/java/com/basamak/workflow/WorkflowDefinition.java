package com.basamak.workflow;

import com.basamak.common.ApiException;
import com.basamak.tool.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Workflow definition. Steps run in list order; a condition step decides which of its `then` / `else` steps run
 * (the others are SKIPPED). A step may only read the workflow input and the outputs of EARLIER steps, so a single
 * workflow is acyclic by construction; cycles through sub-workflows are checked by WorkflowService on save.
 * <pre>
 * { "inputs": [{"name":"city","label":"Şehir","type":"string","required":true}],
 *   "steps": [
 *     {"id":"weather","type":"tool","tool":{"scope":"CONTENT|USER","id":"uuid","versionNo":1},"input":{"city":"{{input.city}}"}},
 *     {"id":"summary","type":"ai","prompt":"Hava: {{steps.weather.output}}. Kısa öneri yaz.","model":"mock-small"},
 *     {"id":"hot","type":"condition","left":"{{steps.weather.output.temperatureC}}","op":"gt","right":"25","then":["warn"],"else":["sub"]},
 *     {"id":"warn","type":"ai","prompt":"Sıcak uyarısı: {{input.city}}"},
 *     {"id":"sub","type":"workflow","workflowId":"uuid","input":{"text":"{{steps.summary.output.text}}"}}
 *   ],
 *   "output": "{{steps.summary.output}}" }
 * </pre>
 * Mapping: a value that is exactly one placeholder keeps its JSON type; otherwise placeholders are inserted as text.
 */
public record WorkflowDefinition(List<ToolDefinition.Input> inputs, List<Step> steps, String output) {

    public record Step(String id, String type, JsonNode raw) {
        public List<String> list(String field) {
            List<String> out = new ArrayList<>();
            raw.path(field).forEach(n -> out.add(n.asText()));
            return out;
        }
    }

    public static final Set<String> TYPES = Set.of("tool", "ai", "condition", "workflow");
    public static final Set<String> OPS = Set.of("eq", "neq", "gt", "gte", "lt", "lte", "contains", "empty", "notEmpty");
    private static final Pattern REF = Pattern.compile("\\{\\{\\s*(input|steps)\\.([A-Za-z0-9_-]+)((?:\\.[A-Za-z0-9_-]+)*)\\s*}}");
    private static final int MAX_STEPS = 20;

    public static WorkflowDefinition parse(JsonNode b) {
        if (b == null || !b.isObject()) throw bad("İş akışı tanımı bir nesne olmalı.");
        ObjectNode forInputs = new ObjectMapper().createObjectNode();
        forInputs.set("inputs", b.path("inputs"));
        forInputs.putObject("request").put("method", "GET").put("url", "https://example.invalid/");
        List<ToolDefinition.Input> inputs = ToolDefinition.parse(forInputs).inputs(); // same input rules as tools
        Set<String> inputNames = new HashSet<>();
        inputs.forEach(i -> inputNames.add(i.name()));

        JsonNode arr = b.path("steps");
        if (!arr.isArray() || arr.isEmpty()) throw bad("İş akışında en az bir adım olmalı.");
        if (arr.size() > MAX_STEPS) throw bad("Bir iş akışında en fazla " + MAX_STEPS + " adım olabilir.");
        List<Step> steps = new ArrayList<>();
        Map<String, Integer> position = new HashMap<>();
        for (JsonNode s : arr) {
            String id = s.path("id").asText("");
            if (!id.matches("[a-z0-9_-]{1,30}")) throw bad("Adım kimliği küçük harf, rakam, - ve _ içermeli (en fazla 30): '" + id + "'");
            if (position.containsKey(id)) throw bad("Adım kimliği tekrar ediyor: " + id);
            String type = s.path("type").asText("");
            if (!TYPES.contains(type)) throw bad("'" + id + "' adımının türü tool, ai, condition ya da workflow olmalı.");
            position.put(id, steps.size());
            steps.add(new Step(id, type, s));
        }
        for (Step s : steps) {
            int pos = position.get(s.id());
            List<String> texts = new ArrayList<>();
            collectText(s.raw(), texts);
            for (String t : texts) checkRefs(t, s.id(), pos, position, inputNames);
            switch (s.type()) {
                case "tool" -> {
                    JsonNode t = s.raw().path("tool");
                    if (!Set.of("CONTENT", "USER").contains(t.path("scope").asText()) || t.path("id").asText("").isBlank()) {
                        throw bad("'" + s.id() + "' araç adımında araç seçilmeli.");
                    }
                }
                case "ai" -> {
                    String p = s.raw().path("prompt").asText("");
                    if (p.isBlank() || p.length() > 8000) throw bad("'" + s.id() + "' yapay zekâ adımının istemi 1-8000 karakter olmalı.");
                }
                case "condition" -> {
                    if (!OPS.contains(s.raw().path("op").asText())) throw bad("'" + s.id() + "' koşulunun işleci geçersiz (eq, neq, gt, gte, lt, lte, contains, empty, notEmpty).");
                    for (String target : concat(s.list("then"), s.list("else"))) {
                        Integer tp = position.get(target);
                        if (tp == null) throw bad("'" + s.id() + "' koşulu olmayan bir adıma yönleniyor: " + target);
                        if (tp <= pos) throw bad("'" + s.id() + "' koşulu yalnız kendisinden SONRAKİ adımlara dallanabilir (geriye dal döngü olur): " + target);
                    }
                }
                case "workflow" -> {
                    try {
                        UUID.fromString(s.raw().path("workflowId").asText());
                    } catch (IllegalArgumentException e) {
                        throw bad("'" + s.id() + "' alt akış adımında iş akışı seçilmeli.");
                    }
                }
                default -> { }
            }
        }
        String out = b.path("output").asText("");
        if (!out.isBlank()) checkRefs(out, "output", steps.size(), position, inputNames);
        return new WorkflowDefinition(inputs, steps, out);
    }

    private static void checkRefs(String text, String stepId, int pos, Map<String, Integer> position, Set<String> inputs) {
        Matcher m = REF.matcher(text);
        while (m.find()) {
            if (m.group(1).equals("input")) {
                if (!inputs.contains(m.group(2))) throw bad("'" + stepId + "' tanımlı olmayan girdiyi kullanıyor: " + m.group(2));
            } else {
                Integer p = position.get(m.group(2));
                if (p == null) throw bad("'" + stepId + "' olmayan bir adıma başvuruyor: " + m.group(2));
                if (p >= pos) throw bad("'" + stepId + "' yalnız önceki adımların çıktısını kullanabilir (" + m.group(2) + " sonra geliyor).");
            }
        }
        if (text.replaceAll(REF.pattern(), "").contains("{{")) {
            throw bad("'" + stepId + "' içinde geçersiz şablon. {{input.ad}} ya da {{steps.adim.output.alan}} kullanın.");
        }
    }

    public Set<UUID> subflowIds() {
        Set<UUID> ids = new LinkedHashSet<>();
        for (Step s : steps) if (s.type().equals("workflow")) ids.add(UUID.fromString(s.raw().path("workflowId").asText()));
        return ids;
    }

    public boolean usesUserTools() {
        return steps.stream().anyMatch(s -> s.type().equals("tool") && "USER".equals(s.raw().path("tool").path("scope").asText()));
    }

    // ------------------------------------------------------------------ runtime mapping

    /** ctx = {"input": {...}, "steps": {"id": {"output": ...}}} */
    public static JsonNode resolve(JsonNode template, JsonNode ctx, ObjectMapper json) {
        if (template == null || template.isMissingNode() || template.isNull()) return json.createObjectNode();
        if (template.isTextual()) {
            String t = template.asText();
            Matcher whole = REF.matcher(t.trim());
            if (whole.matches()) return lookup(ctx, whole);
            return new TextNode(interpolate(t, ctx));
        }
        if (template.isObject()) {
            ObjectNode o = json.createObjectNode();
            template.fields().forEachRemaining(e -> o.set(e.getKey(), resolve(e.getValue(), ctx, json)));
            return o;
        }
        if (template.isArray()) {
            var a = json.createArrayNode();
            template.forEach(x -> a.add(resolve(x, ctx, json)));
            return a;
        }
        return template;
    }

    public static String interpolate(String text, JsonNode ctx) {
        Matcher m = REF.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            JsonNode v = lookup(ctx, m);
            m.appendReplacement(sb, Matcher.quoteReplacement(v.isMissingNode() || v.isNull() ? "" : v.isValueNode() ? v.asText() : v.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static JsonNode lookup(JsonNode ctx, Matcher m) {
        JsonNode n = m.group(1).equals("input") ? ctx.path("input").path(m.group(2)) : ctx.path("steps").path(m.group(2));
        String rest = m.group(3);
        if (rest != null && !rest.isEmpty()) {
            for (String part : rest.substring(1).split("\\.")) n = n.isArray() && part.matches("\\d+") ? n.path(Integer.parseInt(part)) : n.path(part);
        }
        return n.isMissingNode() ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : n;
    }

    public static boolean evaluate(String op, JsonNode left, String right) {
        String l = left == null || left.isNull() ? "" : left.isValueNode() ? left.asText() : left.toString();
        BigDecimal ln = num(l), rn = num(right);
        return switch (op) {
            case "eq" -> ln != null && rn != null ? ln.compareTo(rn) == 0 : l.equals(right);
            case "neq" -> ln != null && rn != null ? ln.compareTo(rn) != 0 : !l.equals(right);
            case "gt" -> ln != null && rn != null && ln.compareTo(rn) > 0;
            case "gte" -> ln != null && rn != null && ln.compareTo(rn) >= 0;
            case "lt" -> ln != null && rn != null && ln.compareTo(rn) < 0;
            case "lte" -> ln != null && rn != null && ln.compareTo(rn) <= 0;
            case "contains" -> l.toLowerCase(Locale.ROOT).contains(right.toLowerCase(Locale.ROOT));
            case "empty" -> l.isBlank();
            case "notEmpty" -> !l.isBlank();
            default -> false;
        };
    }

    private static BigDecimal num(String s) {
        try {
            return s == null || s.isBlank() ? null : new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void collectText(JsonNode n, List<String> out) {
        if (n.isTextual()) out.add(n.asText());
        n.forEach(c -> collectText(c, out));
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> l = new ArrayList<>(a);
        l.addAll(b);
        return l;
    }

    static ApiException bad(String msg) {
        return ApiException.badRequest("invalid_workflow", msg);
    }
}
