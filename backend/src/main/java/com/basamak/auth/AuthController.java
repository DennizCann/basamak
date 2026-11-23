package com.basamak.auth;

import com.basamak.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Member auth for web and mobile.
 * Web: refresh token in an HttpOnly SameSite=Strict cookie; refresh also requires the X-Requested-With header (CSRF).
 * Mobile: refresh token returned in the body and stored in Android Keystore / iOS Keychain.
 */
@RestController
@RequiredArgsConstructor
public class AuthController {
    static final String WEB_COOKIE = "bsm_rt";
    private final AuthService auth;
    private final UserRepository users;
    private final RefreshCookies cookies;
    private final JwtService jwt;

    public record RegisterReq(@Email @NotBlank @Size(max = 254) String email,
                              @NotBlank @Size(max = 128) String password,
                              @NotBlank @Size(min = 2, max = 80) String displayName,
                              boolean consent) {}

    public record LoginReq(@NotBlank String email, @NotBlank String password, String client) {}

    public record TokenReq(@NotBlank String token) {}

    public record ResetReq(@NotBlank String token, @NotBlank String password) {}

    public record EmailReq(@NotBlank @Email String email) {}

    public record RefreshReq(String refreshToken) {}

    public record UserDto(UUID id, String email, String displayName, List<String> roles) {
        static UserDto of(User u) {
            return new UserDto(u.getId(), u.getEmail(), u.getDisplayName(), u.getRoles().stream().map(Enum::name).sorted().toList());
        }
    }

    @PostMapping("/api/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@Valid @RequestBody RegisterReq r) {
        User u = auth.register(r.email(), r.password(), r.displayName(), r.consent());
        return Map.of("id", u.getId(), "message", "Hesabınız oluşturuldu. E-posta adresinize gelen bağlantıyla hesabınızı doğrulayın.");
    }

    @PostMapping("/api/auth/verify-email")
    public Map<String, String> verify(@Valid @RequestBody TokenReq r) {
        auth.verifyEmail(r.token());
        return Map.of("message", "E-posta adresiniz doğrulandı. Artık giriş yapabilirsiniz.");
    }

    @PostMapping("/api/auth/resend-verification")
    public Map<String, String> resend(@Valid @RequestBody EmailReq r) {
        auth.resendVerification(r.email());
        return Map.of("message", "Hesap doğrulanmamışsa yeni bir bağlantı gönderildi.");
    }

    @PostMapping("/api/auth/forgot-password")
    public Map<String, String> forgot(@Valid @RequestBody EmailReq r) {
        auth.forgotPassword(r.email());
        return Map.of("message", "Bu adrese kayıtlı bir hesap varsa şifre sıfırlama bağlantısı gönderildi.");
    }

    @PostMapping("/api/auth/reset-password")
    public Map<String, String> reset(@Valid @RequestBody ResetReq r) {
        auth.resetPassword(r.token(), r.password());
        return Map.of("message", "Şifreniz değiştirildi. Bütün cihazlardaki oturumlar kapatıldı; yeni şifrenizle giriş yapın.");
    }

    public record CodeReq(@jakarta.validation.constraints.NotNull UUID challengeId, String code) {}

    public record ChallengeReq(@jakarta.validation.constraints.NotNull UUID challengeId) {}

    /** Step 1: password. Returns a challenge (no tokens); the code goes to the registered e-mail address. */
    @PostMapping("/api/auth/login")
    public Map<String, Object> login(@Valid @RequestBody LoginReq r, HttpServletRequest req) {
        return challengeDto(auth.memberLogin(r.email(), r.password(), r.client(), info(req)));
    }

    /** Step 2: the e-mailed 6-digit code -> session (cookie for web, body refresh token for mobile). */
    @PostMapping("/api/auth/login/verify")
    public Map<String, Object> verifyCode(@Valid @RequestBody CodeReq r, HttpServletRequest req, HttpServletResponse res) {
        var t = auth.verifyLoginCode(r.challengeId(), r.code(), info(req));
        boolean mobile = "mobile".equals(jwtAudience(t));
        return tokenResponse(t, mobile, WEB_COOKIE, res);
    }

    @PostMapping("/api/auth/login/resend")
    public Map<String, Object> resendCode(@Valid @RequestBody ChallengeReq r) {
        return challengeDto(auth.resendLoginCode(r.challengeId()));
    }

    private Map<String, Object> challengeDto(EmailCodeService.Challenge c) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("challengeId", c.id());
        out.put("maskedEmail", c.maskedEmail());
        out.put("expiresAt", c.expiresAt());
        out.put("resendAvailableAt", c.resendAvailableAt());
        out.put("delivered", c.delivered());
        out.put("message", c.delivered()
                ? "Giriş kodunu " + c.maskedEmail() + " adresine gönderdik."
                : "Kod e-postası şu an gönderilemedi. Birazdan “Kodu yeniden gönder” ile tekrar deneyin.");
        return out;
    }

    private String jwtAudience(AuthService.Tokens t) {
        return jwt.toPrincipal(jwt.parse(t.accessToken())).audience();
    }

    @PostMapping("/api/auth/refresh")
    public Map<String, Object> refresh(@RequestBody(required = false) RefreshReq body, HttpServletRequest req, HttpServletResponse res) {
        if (body != null && body.refreshToken() != null) {
            return tokenResponse(auth.refresh(body.refreshToken(), "mobile"), true, WEB_COOKIE, res);
        }
        cookies.requireCsrfHeader(req);
        return tokenResponse(auth.refresh(cookies.read(req, WEB_COOKIE), "web"), false, WEB_COOKIE, res);
    }

    @PostMapping("/api/auth/logout")
    public Map<String, String> logout(@RequestBody(required = false) RefreshReq body, HttpServletRequest req, HttpServletResponse res) {
        auth.logout(body != null && body.refreshToken() != null ? body.refreshToken() : cookies.read(req, WEB_COOKIE));
        cookies.clear(res, WEB_COOKIE);
        return Map.of("message", "Çıkış yapıldı.");
    }

    @GetMapping("/api/me")
    public UserDto me(@AuthenticationPrincipal AuthPrincipal p) {
        return UserDto.of(users.findById(p.userId()).orElseThrow(() -> ApiException.notFound("Kullanıcı")));
    }

    @GetMapping({"/api/me/sessions", "/api/cms/me/sessions"})
    public List<Map<String, Object>> sessions(@AuthenticationPrincipal AuthPrincipal p) {
        return auth.activeSessions(p.userId()).stream().map(s -> Map.<String, Object>of(
                "id", s.getId(), "audience", s.getAudience(),
                "userAgent", s.getUserAgent() == null ? "" : s.getUserAgent(),
                "createdAt", s.getCreatedAt(), "lastUsedAt", s.getLastUsedAt(),
                "current", s.getId().equals(p.sessionId()))).toList();
    }

    @DeleteMapping({"/api/me/sessions/{id}", "/api/cms/me/sessions/{id}"})
    public Map<String, String> revoke(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        auth.revokeSession(p.userId(), id);
        return Map.of("message", "Oturum kapatıldı.");
    }

    Map<String, Object> tokenResponse(AuthService.Tokens t, boolean bodyRefresh, String cookieName, HttpServletResponse res) {
        if (bodyRefresh) {
            return Map.of("accessToken", t.accessToken(), "refreshToken", t.refreshToken(), "user", UserDto.of(t.user()));
        }
        cookies.write(res, cookieName, t.refreshToken());
        return Map.of("accessToken", t.accessToken(), "user", UserDto.of(t.user()));
    }

    static AuthService.ClientInfo info(HttpServletRequest req) {
        return new AuthService.ClientInfo(req.getHeader("User-Agent"), req.getRemoteAddr());
    }
}
