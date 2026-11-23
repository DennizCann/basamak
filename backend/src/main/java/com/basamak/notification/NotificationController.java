package com.basamak.notification;

import com.basamak.auth.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
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

    @PostMapping("/{id}/read")
    public Map<String, Object> read(@AuthenticationPrincipal AuthPrincipal p, @PathVariable long id) {
        notifications.read(p.userId(), id);
        return Map.of("ok", true);
    }

    /** Which event reaches the member through which channel (in-app, e-mail, push). */
    @GetMapping("/prefs")
    public List<Map<String, Object>> prefs(@AuthenticationPrincipal AuthPrincipal p) {
        return notifications.prefs(p.userId());
    }

    public record PrefReq(String type, boolean inApp, boolean email, boolean push) {}

    @PutMapping("/prefs")
    public List<Map<String, Object>> savePrefs(@AuthenticationPrincipal AuthPrincipal p, @RequestBody List<PrefReq> body) {
        for (PrefReq r : body) notifications.savePref(p.userId(), r.type(), r.inApp(), r.email(), r.push());
        return notifications.prefs(p.userId());
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal AuthPrincipal p, @RequestHeader(name = "Last-Event-ID", required = false) String last) {
        long after = 0;
        try { after = last == null ? 0 : Long.parseLong(last.trim()); } catch (NumberFormatException ignored) { }
        return notifications.subscribe(p.userId(), after);
    }
}
