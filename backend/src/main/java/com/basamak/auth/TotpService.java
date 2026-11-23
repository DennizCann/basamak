package com.basamak.auth;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.springframework.stereotype.Service;

/** RFC 6238 TOTP (30 s, 6 digits, SHA1 for authenticator-app compatibility). */
@Service
public class TotpService {
    private final DefaultCodeVerifier verifier;

    public TotpService() {
        this.verifier = new DefaultCodeVerifier(new DefaultCodeGenerator(), new SystemTimeProvider());
        this.verifier.setAllowedTimePeriodDiscrepancy(1);
    }

    public String newSecret() {
        return new DefaultSecretGenerator(32).generate();
    }

    public String otpauthUri(String email, String secret) {
        return new QrData.Builder().label(email).secret(secret).issuer("Basamak CMS")
                .algorithm(HashingAlgorithm.SHA1).digits(6).period(30).build().getUri();
    }

    public boolean verify(String secret, String code) {
        return code != null && code.matches("\\d{6}") && verifier.isValidCode(secret, code);
    }
}
