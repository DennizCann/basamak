package com.basamak.gamification;

import java.util.UUID;

/**
 * A published point rule (CMS content of type POINT_RULE, versioned like any content).
 * policy decides how often the same member can earn it:
 *  ONCE_PER_OBJECT - once per (action, object) forever (e.g. viewing a lesson)
 *  ONCE_PER_DAY    - once per (action, object) per Europe/Istanbul day (e.g. running a tool)
 *  EVERY           - every distinct event
 */
public record PointRule(UUID ruleId, int ruleVersionNo, String action, String objectType, int points, String policy) {
    public static final java.util.Set<String> POLICIES = java.util.Set.of("ONCE_PER_OBJECT", "ONCE_PER_DAY", "EVERY");
}
