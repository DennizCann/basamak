package com.basamak.auth;

import com.basamak.common.ApiException;
import com.basamak.common.AuditService;
import com.basamak.common.CryptoService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Registration, e-mail verification, password login with lockout, refresh-token rotation, sessions,
 * password reset and the CMS second factor (TOTP).
 */
@Service
@RequiredArgsConstructor
public class AuthService {
    public static final String CONSENT_VERSION = "2026-11";
    static final int MAX_FAILED = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    static final Duration REFRESH_TTL = Duration.ofDays(30);
    static final Duration CMS_REFRESH_TTL = Duration.ofHours(12);

    private final UserRepository users;
    private final RefreshSessionRepository sessions;
    private final EmailTokenRepository emailTokens;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final TotpService totp;
    private final CryptoService crypto;
    private final MailService mail;
    private final AuditService audit;
    private final EmailCodeService emailCodes;
    private final TrustedDeviceService trustedDevices;

    @Value("${basamak.web-base-url}")
    private String webBaseUrl;

    public record Tokens(String accessToken, String refreshToken, User user) {}

    public record ClientInfo(String userAgent, String ip) {}

    // ---------- registration & verification ----------

    @Transactional
    public User register(String email, String password, String displayName, boolean consent) {
        if (!consent) throw ApiException.badRequest("consent_required",
                "Devam etmek için aydınlatma metnini okuyup açık rızanızı onaylamanız gerekiyor.");
        PasswordPolicy.check(password, email);
        if (users.findByEmailIgnoreCase(email).isPresent()) {
            throw ApiException.conflict("email_taken", "Bu e-posta adresiyle zaten bir hesap var. Giriş yapmayı ya da şifrenizi sıfırlamayı deneyin.", Map.of());
        }
        User u = new User();
        u.setEmail(email.trim());
        u.setPasswordHash(encoder.encode(password));
        u.setDisplayName(displayName.trim());
        u.setRoles(EnumSet.of(Role.MEMBER));
        u.setConsentVersion(CONSENT_VERSION);
        u.setConsentAt(Instant.now());
        users.save(u);
        sendVerification(u);
        audit.record(u.getId(), "user.registered", "user", u.getId(), Map.of());
        return u;
    }

    @Transactional
    public void resendVerification(String email) {
        users.findByEmailIgnoreCase(email).filter(u -> u.getEmailVerifiedAt() == null).ifPresent(this::sendVerification);
        // same response whether or not the address exists (no account enumeration)
    }

    private void sendVerification(User u) {
        String token = createEmailToken(u, "VERIFY", Duration.ofHours(24));
        mail.send(u.getEmail(), "Basamak — e-posta adresinizi doğrulayın",
                "Merhaba " + u.getDisplayName() + ",\n\nHesabınızı etkinleştirmek için bağlantıyı açın:\n"
                        + webBaseUrl + "/verify-email?token=" + token + "\n\nBağlantı 24 saat geçerlidir.");
    }

    @Transactional
    public void verifyEmail(String token) {
        EmailToken t = consumeEmailToken(token, "VERIFY");
        User u = users.findById(t.getUserId()).orElseThrow();
        u.setEmailVerifiedAt(Instant.now());
        audit.record(u.getId(), "user.email_verified", "user", u.getId(), Map.of());
    }

    @Transactional
    public void forgotPassword(String email) {
        users.findByEmailIgnoreCase(email).filter(u -> "ACTIVE".equals(u.getStatus())).ifPresent(u -> {
            String token = createEmailToken(u, "RESET", Duration.ofMinutes(30));
            mail.send(u.getEmail(), "Basamak — şifre sıfırlama",
                    "Şifrenizi sıfırlamak için bağlantıyı açın (30 dakika geçerli):\n"
                            + webBaseUrl + "/reset-password?token=" + token
                            + "\n\nBu isteği siz yapmadıysanız bu e-postayı yok sayabilirsiniz.");
        });
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        EmailToken t = consumeEmailToken(token, "RESET");
        User u = users.findById(t.getUserId()).orElseThrow();
        PasswordPolicy.check(newPassword, u.getEmail());
        u.setPasswordHash(encoder.encode(newPassword));
        u.setFailedLoginCount(0);
        u.setLockedUntil(null);
        // a password reset signs out every device and forgets remembered devices (next login needs the e-mail code)
        sessions.findByUserIdAndRevokedAtIsNullOrderByLastUsedAtDesc(u.getId()).forEach(s -> s.setRevokedAt(Instant.now()));
        trustedDevices.revokeAll(u.getId());
        audit.record(u.getId(), "user.password_reset", "user", u.getId(), Map.of());
    }

    private String createEmailToken(User u, String purpose, Duration ttl) {
        String raw = CryptoService.randomToken();
        EmailToken t = new EmailToken();
        t.setUserId(u.getId());
        t.setTokenHash(CryptoService.sha256(raw));
        t.setPurpose(purpose);
        t.setExpiresAt(Instant.now().plus(ttl));
        emailTokens.save(t);
        return raw;
    }

    private EmailToken consumeEmailToken(String raw, String purpose) {
        EmailToken t = emailTokens.findByTokenHashAndPurpose(CryptoService.sha256(raw == null ? "" : raw), purpose)
                .filter(x -> x.getUsedAt() == null && x.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> ApiException.badRequest("invalid_token",
                        "Bağlantı geçersiz ya da süresi dolmuş. Yeni bir bağlantı isteyin."));
        t.setUsedAt(Instant.now());
        return t;
    }

    // ---------- password check with lockout ----------

    /** Verifies the password; counts failures and locks the account after MAX_FAILED attempts. */
    @Transactional(noRollbackFor = ApiException.class)
    public User checkPassword(String email, String password) {
        Instant now = Instant.now();
        User u = users.findByEmailIgnoreCase(email == null ? "" : email.trim()).orElse(null);
        if (u == null || !"ACTIVE".equals(u.getStatus())) {
            encoder.matches(password == null ? "" : password, "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5BWX4Z6hz5fTWyiLuVRc.5bDH1eW2"); // timing equaliser
            throw badCredentials();
        }
        if (u.isLocked(now)) throw locked(u);
        if (!encoder.matches(password == null ? "" : password, u.getPasswordHash())) {
            registerFailure(u, "password");
            if (u.isLocked(now)) throw locked(u);
            throw badCredentials();
        }
        u.setFailedLoginCount(0);
        u.setLockedUntil(null);
        return u;
    }

    private void registerFailure(User u, String factor) {
        u.setFailedLoginCount(u.getFailedLoginCount() + 1);
        if (u.getFailedLoginCount() >= MAX_FAILED) {
            u.setLockedUntil(Instant.now().plus(LOCK_DURATION));
            u.setFailedLoginCount(0);
            audit.record(u.getId(), "user.locked", "user", u.getId(), Map.of("factor", factor));
        }
        users.save(u);
    }

    private static ApiException badCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "bad_credentials", "E-posta ya da şifre hatalı. Bilgilerinizi kontrol edip tekrar deneyin.");
    }

    private static ApiException locked(User u) {
        return new ApiException(HttpStatus.LOCKED, "account_locked",
                "Çok fazla hatalı deneme yapıldığı için hesap geçici olarak kilitlendi. 15 dakika sonra tekrar deneyin ya da şifrenizi sıfırlayın.",
                Map.of("lockedUntil", u.getLockedUntil().toString()));
    }

    // ---------- member login (web / mobile) ----------

    /**
     * Member login step 1: password. No session is opened here; a one-time code is e-mailed to the account's
     * registered address and must be confirmed with {@link #verifyLoginCode}.
     */
    /** Either a session (remembered device) or a code challenge (new device). */
    public record LoginOutcome(Tokens tokens, EmailCodeService.Challenge challenge) {}

    /** Result of the code step; deviceToken is set when the member chose "remember this device". */
    public record CodeLogin(Tokens tokens, String deviceToken) {}

    @Transactional(noRollbackFor = ApiException.class)
    public LoginOutcome memberLogin(String email, String password, String client, ClientInfo info, String deviceToken) {
        String audience = "mobile".equals(client) ? "mobile" : "web";
        User u = checkPassword(email, password);
        if (u.getEmailVerifiedAt() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "email_not_verified",
                    "E-posta adresiniz henüz doğrulanmadı. Gelen kutunuzdaki bağlantıyı açın ya da yeni bağlantı isteyin.");
        }
        if (trustedDevices.isTrusted(u.getId(), deviceToken)) {
            audit.record(u.getId(), "user.login_trusted_device", "user", u.getId(), Map.of("audience", audience));
            return new LoginOutcome(openSession(u, audience, REFRESH_TTL, info), null);
        }
        return new LoginOutcome(null, emailCodes.start(u, audience, info));
    }

    /** Member login step 2: the e-mailed code. Only now is a session opened. */
    @Transactional(noRollbackFor = ApiException.class)
    public CodeLogin verifyLoginCode(UUID challengeId, String code, ClientInfo info, boolean remember) {
        var v = emailCodes.verify(challengeId, code, userId -> users.findById(userId).ifPresent(u -> registerFailure(u, "email_code")));
        User u = users.findById(v.userId()).filter(x -> "ACTIVE".equals(x.getStatus())).orElseThrow(AuthService::badCredentials);
        if (u.isLocked(Instant.now())) throw locked(u);
        u.setFailedLoginCount(0);
        String device = remember ? trustedDevices.remember(u.getId(), info == null ? null : info.userAgent()) : null;
        return new CodeLogin(openSession(u, v.audience(), REFRESH_TTL, info), device);
    }

    public EmailCodeService.Challenge resendLoginCode(UUID challengeId) {
        return emailCodes.resend(challengeId);
    }

    // ---------- CMS login: password -> TOTP ----------

    public record CmsStep1(String mfaToken, boolean setupRequired, String otpauthUri, String secret) {}

    @Transactional(noRollbackFor = ApiException.class)
    public CmsStep1 cmsPasswordStep(String email, String password) {
        User u = checkPassword(email, password);
        if (!u.isStaff()) {
            audit.record(u.getId(), "cms.login_denied", "user", u.getId(), Map.of("reason", "not_staff"));
            throw ApiException.forbidden("CMS'e yalnız içerik ekibi (yazar, editör, yayıncı, yönetici) girebilir.");
        }
        if (u.isTotpEnabled()) return new CmsStep1(jwt.issueMfaPending(u), false, null, null);
        String secret = u.getTotpSecretEnc() == null ? null : crypto.decrypt(u.getTotpSecretEnc());
        if (secret == null) {
            secret = totp.newSecret();
            u.setTotpSecretEnc(crypto.encrypt(secret));
        }
        return new CmsStep1(jwt.issueMfaPending(u), true, totp.otpauthUri(u.getEmail(), secret), secret);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Tokens cmsTotpStep(String mfaToken, String code, ClientInfo info) {
        AuthPrincipal p;
        try {
            var claims = jwt.parse(mfaToken);
            if (!claims.getAudience().contains("mfa")) throw new IllegalArgumentException();
            p = new AuthPrincipal(UUID.fromString(claims.getSubject()), null, EnumSet.noneOf(Role.class), "mfa", null);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "mfa_expired", "Doğrulama adımının süresi doldu. Şifrenizle yeniden giriş yapın.");
        }
        User u = users.findById(p.userId()).orElseThrow(AuthService::badCredentials);
        if (u.isLocked(Instant.now())) throw locked(u);
        if (u.getTotpSecretEnc() == null || !totp.verify(crypto.decrypt(u.getTotpSecretEnc()), code)) {
            registerFailure(u, "totp");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "bad_totp", "Doğrulama kodu hatalı. Uygulamadaki güncel 6 haneli kodu girin.");
        }
        if (!u.isTotpEnabled()) {
            u.setTotpEnabled(true);
            audit.record(u.getId(), "cms.totp_enabled", "user", u.getId(), Map.of());
        }
        u.setFailedLoginCount(0);
        audit.record(u.getId(), "cms.login", "user", u.getId(), Map.of());
        return openSession(u, "cms", CMS_REFRESH_TTL, info);
    }

    // ---------- sessions & refresh ----------

    private Tokens openSession(User u, String audience, Duration ttl, ClientInfo info) {
        String raw = CryptoService.randomToken();
        RefreshSession s = new RefreshSession();
        s.setUserId(u.getId());
        s.setTokenHash(CryptoService.sha256(raw));
        s.setAudience(audience);
        s.setUserAgent(info == null || info.userAgent() == null ? null : truncate(info.userAgent(), 300));
        s.setIp(info == null ? null : info.ip());
        s.setExpiresAt(Instant.now().plus(ttl));
        sessions.save(s);
        return new Tokens(jwt.issueAccess(u, audience, s.getId()), raw, u);
    }

    /** Rotates the refresh token: the old value stops working immediately. */
    @Transactional
    public Tokens refresh(String rawRefresh, String expectedAudience) {
        Instant now = Instant.now();
        RefreshSession s = sessions.lockByTokenHash(CryptoService.sha256(rawRefresh == null ? "" : rawRefresh))
                .filter(x -> x.isActive(now) && x.getAudience().equals(expectedAudience))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "session_expired", "Oturumunuz sona erdi. Lütfen yeniden giriş yapın."));
        User u = users.findById(s.getUserId()).filter(x -> "ACTIVE".equals(x.getStatus()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "session_expired", "Oturumunuz sona erdi. Lütfen yeniden giriş yapın."));
        String raw = CryptoService.randomToken();
        s.setTokenHash(CryptoService.sha256(raw));
        s.setLastUsedAt(now);
        return new Tokens(jwt.issueAccess(u, s.getAudience(), s.getId()), raw, u);
    }

    @Transactional
    public void logout(String rawRefresh) {
        if (rawRefresh == null) return;
        sessions.findByTokenHash(CryptoService.sha256(rawRefresh)).ifPresent(s -> s.setRevokedAt(Instant.now()));
    }

    public List<RefreshSession> activeSessions(UUID userId) {
        Instant now = Instant.now();
        return sessions.findByUserIdAndRevokedAtIsNullOrderByLastUsedAtDesc(userId).stream().filter(s -> s.isActive(now)).toList();
    }

    @Transactional
    public void revokeSession(UUID userId, UUID sessionId) {
        RefreshSession s = sessions.findById(sessionId).filter(x -> x.getUserId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Oturum"));
        s.setRevokedAt(Instant.now());
        audit.record(userId, "session.revoked", "session", sessionId, Map.of());
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
