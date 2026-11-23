package com.basamak.privacy;

import com.basamak.auth.AuthPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class PrivacyController {
    private final PrivacyService privacy;
    private final ObjectMapper json;

    public record DeleteReq(String password, String confirmation) {}

    /** "Geçmişim": filter by category and date range (Istanbul days), paged. */
    @GetMapping("/history")
    public Map<String, Object> history(@AuthenticationPrincipal AuthPrincipal p,
                                       @RequestParam(required = false) String category,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                       @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return privacy.history(p.userId(), category, from, to, page, size);
    }

    /** "Verilerimi indir": a JSON file with everything stored about me. */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal AuthPrincipal p) throws Exception {
        byte[] body = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(privacy.export(p.userId()));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"basamak-verilerim-" + LocalDate.now() + ".json\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_JSON).body(body);
    }

    @PostMapping("/delete")
    public Map<String, Object> delete(@AuthenticationPrincipal AuthPrincipal p, @RequestBody DeleteReq r) {
        privacy.deleteAccount(p.userId(), r.password(), r.confirmation());
        return Map.of("deleted", true);
    }
}
