package com.basamak.auth;

import com.basamak.common.ApiException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** HttpOnly refresh cookies (scoped to /api so JS can never read them) + custom-header CSRF guard. */
@Component
public class RefreshCookies {
    private final boolean secure;

    public RefreshCookies(@Value("${basamak.cookie-secure:false}") boolean secure) {
        this.secure = secure;
    }

    void write(HttpServletResponse res, String name, String value) {
        res.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(name, value).httpOnly(true).secure(secure)
                .sameSite("Strict").path("/api").maxAge(Duration.ofDays(30)).build().toString());
    }

    /** Remembered-device cookie: only sent to the auth endpoints, 30 days, HttpOnly, SameSite=Strict. */
    void writeDevice(HttpServletResponse res, String name, String value) {
        res.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(name, value).httpOnly(true).secure(secure)
                .sameSite("Strict").path("/api/auth").maxAge(TrustedDeviceService.TTL).build().toString());
    }

    void clear(HttpServletResponse res, String name) {
        res.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(name, "").httpOnly(true).secure(secure)
                .sameSite("Strict").path("/api").maxAge(0).build().toString());
    }

    String read(HttpServletRequest req, String name) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) if (c.getName().equals(name)) return c.getValue();
        return null;
    }

    /** A cross-site form cannot set custom headers, so this blocks CSRF on cookie-authenticated refresh. */
    void requireCsrfHeader(HttpServletRequest req) {
        if (!"basamak".equals(req.getHeader("X-Requested-With"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "csrf", "İstek reddedildi (CSRF koruması). Sayfayı yenileyip tekrar deneyin.");
        }
    }
}
