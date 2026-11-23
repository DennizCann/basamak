package com.basamak.auth;

import com.basamak.common.ApiException;

/** ASVS 2.1: min length 10, max 128, not equal to e-mail, not a well-known password. */
final class PasswordPolicy {
    private PasswordPolicy() {}

    private static final java.util.Set<String> COMMON = java.util.Set.of(
            "1234567890", "password123", "qwertyuiop", "sifre12345", "parola1234", "basamak123", "1q2w3e4r5t");

    static void check(String password, String email) {
        if (password == null || password.length() < 10 || password.length() > 128) {
            throw ApiException.badRequest("weak_password", "Şifre 10 ile 128 karakter arasında olmalı.");
        }
        if (COMMON.contains(password.toLowerCase()) || (email != null && password.equalsIgnoreCase(email))) {
            throw ApiException.badRequest("weak_password", "Bu şifre çok kolay tahmin edilebilir. Daha uzun ve size özgü bir şifre seçin.");
        }
    }
}
