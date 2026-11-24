package com.basamak.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window rate limit per client IP for sensitive endpoints (login, register, password reset, tool runs).
 * In-memory: fine for a single node; with several nodes this moves to Postgres/Redis (see TASARIM §9).
 */
@Component
@Order(10)
public class RateLimitFilter extends OncePerRequestFilter {
    private final int authPerMinute;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    private final int runsPerMinute;

    public RateLimitFilter(@Value("${basamak.rate-limit.auth-per-minute:20}") int authPerMinute,
                           @Value("${basamak.rate-limit.runs-per-minute:30}") int runsPerMinute) {
        this.authPerMinute = authPerMinute;
        this.runsPerMinute = runsPerMinute;
    }

    private record Window(long minute, int count) {}

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getRequestURI();
        return !"POST".equals(req.getMethod())
                || !(p.startsWith("/api/auth/") || p.startsWith("/api/cms-auth/") || isRunStart(p));
    }

    /** Starting tool / workflow runs and test-console calls (the expensive, outward-facing actions). */
    static boolean isRunStart(String p) {
        return p.equals("/api/tool-runs") || (p.startsWith("/api/workflows/") && p.endsWith("/runs")) || (p.startsWith("/api/cms/tools/") && p.endsWith("/test"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long minute = System.currentTimeMillis() / 60_000;
        boolean run = isRunStart(req.getRequestURI());
        String key = req.getRemoteAddr() + "|" + (run ? "runs" : req.getRequestURI());
        Window w = windows.compute(key, (k, old) ->
                old == null || old.minute() != minute ? new Window(minute, 1) : new Window(minute, old.count() + 1));
        if (w.count() > (run ? runsPerMinute : authPerMinute)) {
            res.setStatus(429);
            res.setHeader("Retry-After", "60");
            res.setContentType("application/problem+json;charset=UTF-8");
            res.getWriter().write(run
                    ? "{\"code\":\"rate_limited\",\"detail\":\"Bir dakikada çok fazla çalıştırma başlattınız. Biraz bekleyip tekrar deneyin.\"}"
                    : "{\"code\":\"rate_limited\",\"detail\":\"Çok fazla deneme yaptınız. Bir dakika bekleyip tekrar deneyin.\"}");
            return;
        }
        if (windows.size() > 50_000) windows.clear(); // crude memory guard
        chain.doFilter(req, res);
    }
}
