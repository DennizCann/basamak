package com.basamak.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * CMS sign-in is a separate flow from the member app: password -> TOTP (mandatory) -> CMS-audience token.
 * First sign-in enrols the authenticator (otpauth URI shown as QR in the CMS).
 */
@RestController
@RequiredArgsConstructor
public class CmsAuthController {
    static final String CMS_COOKIE = "bsm_cms_rt";
    private final AuthService auth;
    private final AuthController memberAuth;
    private final RefreshCookies cookies;

    public record LoginReq(@NotBlank String email, @NotBlank String password) {}

    public record TotpReq(@NotBlank String mfaToken, @NotBlank String code) {}

    @PostMapping("/api/cms-auth/login")
    public Map<String, Object> login(@Valid @RequestBody LoginReq r) {
        var step = auth.cmsPasswordStep(r.email(), r.password());
        Map<String, Object> out = new HashMap<>();
        out.put("mfaToken", step.mfaToken());
        out.put("setupRequired", step.setupRequired());
        if (step.setupRequired()) {
            out.put("otpauthUri", step.otpauthUri());
            out.put("secret", step.secret());
        }
        return out;
    }

    @PostMapping("/api/cms-auth/totp")
    public Map<String, Object> totp(@Valid @RequestBody TotpReq r, HttpServletRequest req, HttpServletResponse res) {
        var t = auth.cmsTotpStep(r.mfaToken(), r.code(), AuthController.info(req));
        return memberAuth.tokenResponse(t, false, CMS_COOKIE, res);
    }

    @PostMapping("/api/cms-auth/refresh")
    public Map<String, Object> refresh(HttpServletRequest req, HttpServletResponse res) {
        cookies.requireCsrfHeader(req);
        return memberAuth.tokenResponse(auth.refresh(cookies.read(req, CMS_COOKIE), "cms"), false, CMS_COOKIE, res);
    }

    @PostMapping("/api/cms-auth/logout")
    public Map<String, String> logout(HttpServletRequest req, HttpServletResponse res) {
        auth.logout(cookies.read(req, CMS_COOKIE));
        cookies.clear(res, CMS_COOKIE);
        return Map.of("message", "Çıkış yapıldı.");
    }
}
