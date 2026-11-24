package com.basamak.tool;

import java.util.*;

/**
 * Breaking-change detection between two versions of a tool definition (section 3.5: "girdi ya da çıktı şemasını bozan
 * değişiklik ayrıca işaretlenir"). A change is breaking when a caller that worked with the old version could fail or
 * get a different shape with the new one:
 * <ul>
 *   <li>an input was removed or its type changed;</li>
 *   <li>a new required input appeared, or an optional input became required;</li>
 *   <li>the output selection (outputPath) changed, so the result has a different shape.</li>
 * </ul>
 * Trainings and workflows pin a tool version, so a breaking version never affects them until they are updated.
 */
public final class ToolCompat {
    private ToolCompat() {}

    public static List<String> breakingChanges(ToolDefinition oldDef, ToolDefinition newDef) {
        List<String> out = new ArrayList<>();
        Map<String, ToolDefinition.Input> before = new LinkedHashMap<>();
        oldDef.inputs().forEach(i -> before.put(i.name(), i));
        Map<String, ToolDefinition.Input> after = new LinkedHashMap<>();
        newDef.inputs().forEach(i -> after.put(i.name(), i));
        for (var o : before.values()) {
            var n = after.get(o.name());
            if (n == null) out.add("'" + o.name() + "' girdisi kaldırıldı.");
            else {
                if (!n.type().equals(o.type())) out.add("'" + o.name() + "' girdisinin türü değişti (" + o.type() + " → " + n.type() + ").");
                if (n.required() && !o.required()) out.add("'" + o.name() + "' girdisi zorunlu oldu.");
            }
        }
        for (var n : after.values()) {
            if (!before.containsKey(n.name()) && n.required()) out.add("Yeni zorunlu girdi: '" + n.name() + "'.");
        }
        if (!Objects.equals(oldDef.outputPath(), newDef.outputPath())) {
            out.add("Çıktı seçimi değişti ('" + oldDef.outputPath() + "' → '" + newDef.outputPath() + "'); sonucun biçimi farklı olabilir.");
        }
        return out;
    }
}
