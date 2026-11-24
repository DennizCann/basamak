package com.basamak.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    @Test
    void blocksLoginAfterLimitPerMinute() throws Exception {
        RateLimitFilter f = new RateLimitFilter(3, 100);
        int[] codes = new int[5];
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/login");
            req.setRemoteAddr("10.1.1.1");
            MockHttpServletResponse res = new MockHttpServletResponse();
            f.doFilter(req, res, new MockFilterChain());
            codes[i] = res.getStatus();
        }
        assertThat(codes).containsExactly(200, 200, 200, 429, 429);
    }

    @Test
    void otherIpsAndGetRequestsAreNotAffected() throws Exception {
        RateLimitFilter f = new RateLimitFilter(1, 100);
        MockHttpServletRequest a = new MockHttpServletRequest("POST", "/api/auth/login");
        a.setRemoteAddr("10.0.0.1");
        f.doFilter(a, new MockHttpServletResponse(), new MockFilterChain());
        MockHttpServletRequest b = new MockHttpServletRequest("POST", "/api/auth/login");
        b.setRemoteAddr("10.0.0.2");
        MockHttpServletResponse rb = new MockHttpServletResponse();
        f.doFilter(b, rb, new MockFilterChain());
        assertThat(rb.getStatus()).isEqualTo(200);
        MockHttpServletRequest g = new MockHttpServletRequest("GET", "/api/journeys");
        g.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse rg = new MockHttpServletResponse();
        f.doFilter(g, rg, new MockFilterChain());
        assertThat(rg.getStatus()).isEqualTo(200);
    }

    @Test
    void runStartsShareOnePerMinuteBudgetPerIp() throws Exception {
        RateLimitFilter f = new RateLimitFilter(100, 2);
        String[] paths = {"/api/tool-runs", "/api/workflows/abc/runs", "/api/tool-runs"};
        int[] codes = new int[3];
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", paths[i]);
            req.setRemoteAddr("10.2.2.2");
            MockHttpServletResponse res = new MockHttpServletResponse();
            f.doFilter(req, res, new MockFilterChain());
            codes[i] = res.getStatus();
        }
        assertThat(codes).containsExactly(200, 200, 429);
    }
}
