package com.basamak.notification;

import com.basamak.auth.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/me/notifications")
@RequiredArgsConstructor
public class NotificationController {
    private final NotificationService notifications;

    @GetMapping
    public Map<String, Object> list(@AuthenticationPrincipal AuthPrincipal p, @RequestParam(defaultValue = "30") int limit) {
        return notifications.list(p.userId(), limit);
    }

    @PostMapping("/read-all")
    public Map<String, Object> readAll(@AuthenticationPrincipal AuthPrincipal p) {
        notifications.readAll(p.userId());
        return Map.of("ok", true);
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal AuthPrincipal p, @RequestHeader(name = "Last-Event-ID", required = false) String last) {
        long after = 0;
        try { after = last == null ? 0 : Long.parseLong(last.trim()); } catch (NumberFormatException ignored) { }
        return notifications.subscribe(p.userId(), after);
    }
}
