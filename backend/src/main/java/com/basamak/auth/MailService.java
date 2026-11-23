package com.basamak.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** Plain-text mail via SMTP (Mailpit locally). Failures are logged, never leak to the caller. */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {
    private final JavaMailSender sender;

    public boolean send(String to, String subject, String text) {
        try {
            SimpleMailMessage m = new SimpleMailMessage();
            m.setFrom("Basamak <no-reply@basamak.local>");
            m.setTo(to);
            m.setSubject(subject);
            m.setText(text);
            sender.send(m);
            return true;
        } catch (Exception e) {
            log.warn("Mail could not be sent: {}", e.getMessage());
            return false;
        }
    }
}
