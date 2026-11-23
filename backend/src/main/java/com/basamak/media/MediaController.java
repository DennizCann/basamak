package com.basamak.media;

import com.basamak.auth.AuthPrincipal;
import com.basamak.auth.Role;
import com.basamak.common.ApiException;
import com.basamak.common.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@RestController
@RequiredArgsConstructor
public class MediaController {
    private final MediaService media;
    private final AuditService audit;

    /** Upload (content team only). Type and size are checked by MediaService from the file's bytes. */
    @PostMapping(path = "/api/cms/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@AuthenticationPrincipal AuthPrincipal p, @RequestParam("file") MultipartFile file) throws IOException {
        staff(p);
        if (file == null || file.isEmpty()) throw ApiException.badRequest("empty_file", "Dosya seçilmedi ya da boş.");
        MediaService.Asset a;
        try (var in = file.getInputStream()) {
            a = media.store(p.userId(), file.getOriginalFilename(), in);
        }
        audit.record(p.userId(), "MEDIA_UPLOADED", "MEDIA", a.id(), Map.of("kind", a.kind(), "size", a.size(), "type", a.contentType()));
        return view(a);
    }

    @GetMapping("/api/cms/media")
    public List<Map<String, Object>> list(@AuthenticationPrincipal AuthPrincipal p) {
        staff(p);
        return media.recent(100).stream().map(this::view).toList();
    }

    /** Short-lived signed URLs for the media a draft references (CMS editor and preview). */
    @PostMapping("/api/cms/media/sign")
    public Map<String, String> sign(@AuthenticationPrincipal AuthPrincipal p, @RequestBody Map<String, List<String>> body) {
        staff(p);
        Map<String, String> out = new LinkedHashMap<>();
        for (String raw : body.getOrDefault("ids", List.of()).stream().limit(200).toList()) {
            try {
                UUID id = UUID.fromString(raw);
                if (media.exists(id)) out.put(raw, media.signedPath(id));
            } catch (IllegalArgumentException ignored) { /* not an id */ }
        }
        return out;
    }

    /**
     * Public read: allowed while a published version references the file, or with a valid CMS signature.
     * Range requests (video seeking) are handled by Spring for Resource bodies.
     */
    @GetMapping("/api/public/media/{id}")
    public ResponseEntity<Resource> read(@PathVariable UUID id, @RequestParam(required = false) Long exp, @RequestParam(required = false) String sig) {
        MediaService.Asset a = media.get(id).orElseThrow(() -> ApiException.notFound("Dosya"));
        boolean signed = media.validSignature(id, exp, sig);
        if (!signed && !media.isPublished(id)) throw ApiException.notFound("Dosya");
        boolean inline = !"FILE".equals(a.kind()) || a.contentType().equals("application/pdf");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(a.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                        .filename(a.name(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .header("Cross-Origin-Resource-Policy", "cross-origin")
                .cacheControl(CacheControl.maxAge(Duration.ofHours(signed ? 0 : 1)).cachePrivate())
                .body(new FileSystemResource(media.file(a)));
    }

    private Map<String, Object> view(MediaService.Asset a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id());
        m.put("kind", a.kind());
        m.put("contentType", a.contentType());
        m.put("size", a.size());
        m.put("name", a.name());
        m.put("createdAt", a.createdAt());
        m.put("url", media.signedPath(a.id()));
        return m;
    }

    private static void staff(AuthPrincipal p) {
        if (p.roles().stream().noneMatch(Role.STAFF::contains)) throw ApiException.forbidden("Dosya yüklemeyi yalnız içerik ekibi yapabilir.");
    }
}
