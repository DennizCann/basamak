package com.basamak.auth;

import java.util.Set;
import java.util.UUID;

/**
 * Authenticated caller, built from a verified access JWT.
 * audience: web | mobile | cms. CMS tokens exist only after password + TOTP.
 */
public record AuthPrincipal(UUID userId, String email, Set<Role> roles, String audience, UUID sessionId) {
    public boolean has(Role r) {
        return roles.contains(r);
    }

    public boolean hasAny(Role... rs) {
        for (Role r : rs) if (roles.contains(r)) return true;
        return false;
    }
}
