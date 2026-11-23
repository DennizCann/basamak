package com.basamak.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Bearer JWT -> AuthPrincipal. Authorities: ROLE_x for each role and AUD_x for the token audience.
 * The session id inside the token is checked so that "close this session" takes effect immediately.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final RefreshSessionRepository sessions;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) {
            try {
                Claims c = jwt.parse(h.substring(7));
                AuthPrincipal p = jwt.toPrincipal(c);
                boolean sessionOk = p.sessionId() == null
                        || sessions.findById(p.sessionId()).map(s -> s.isActive(Instant.now())).orElse(false);
                if (sessionOk && !"mfa".equals(p.audience())) {
                    List<GrantedAuthority> auth = new ArrayList<>();
                    p.roles().forEach(r -> auth.add(new SimpleGrantedAuthority("ROLE_" + r.name())));
                    auth.add(new SimpleGrantedAuthority("AUD_" + p.audience()));
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(p, null, auth));
                }
            } catch (JwtException | IllegalArgumentException ignored) {
                // invalid/expired token -> stays anonymous -> 401 from the entry point
            }
        }
        chain.doFilter(req, res);
    }
}
