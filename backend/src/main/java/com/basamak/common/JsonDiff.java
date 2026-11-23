package com.basamak.common;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Structural diff of two JSON snapshots, used for the CMS "fark görünümü" between versions. */
public final class JsonDiff {
    private JsonDiff() {}

    public record Change(String path, String op, JsonNode before, JsonNode after) {}

    public static List<Change> diff(JsonNode before, JsonNode after) {
        List<Change> out = new ArrayList<>();
        walk("", before, after, out);
        return out;
    }

    private static void walk(String path, JsonNode a, JsonNode b, List<Change> out) {
        if (a == null || a.isMissingNode()) {
            if (b != null && !b.isMissingNode()) out.add(new Change(path(path), "added", null, b));
            return;
        }
        if (b == null || b.isMissingNode()) {
            out.add(new Change(path(path), "removed", a, null));
            return;
        }
        if (a.isObject() && b.isObject()) {
            Set<String> keys = new TreeSet<>();
            a.fieldNames().forEachRemaining(keys::add);
            b.fieldNames().forEachRemaining(keys::add);
            for (String k : keys) walk(path + "/" + k, a.path(k), b.path(k), out);
        } else if (a.isArray() && b.isArray()) {
            int n = Math.max(a.size(), b.size());
            for (int i = 0; i < n; i++) walk(path + "/" + i, a.path(i), b.path(i), out);
        } else if (!a.equals(b)) {
            out.add(new Change(path(path), "changed", a, b));
        }
    }

    private static String path(String p) {
        return p.isEmpty() ? "/" : p;
    }
}
