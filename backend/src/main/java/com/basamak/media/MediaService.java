package com.basamak.media;

import com.basamak.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lesson media (images, video, attachments).
 * <ul>
 *   <li>Type is decided by the file's magic bytes, never by the client's Content-Type or the file name. SVG and HTML are
 *       not accepted (script inside an image). Per-kind size limits.</li>
 *   <li>Files are immutable and stored by sha256 (an identical upload is stored once). Versioning is by reference:
 *       a lesson version points at media ids, so old versions keep working and nothing is overwritten.</li>
 *   <li>Members may read a file only while it is referenced by a <b>published</b> version, so draft media never leaks.
 *       The CMS gets short-lived signed URLs (an &lt;img&gt; cannot send a bearer token).</li>
 * </ul>
 */
@Service
public class MediaService {
    public static final long MAX_IMAGE = 5L * 1024 * 1024, MAX_VIDEO = 100L * 1024 * 1024, MAX_FILE = 20L * 1024 * 1024;
    private static final long SIGNED_URL_SECONDS = 3600;

    public record Asset(UUID id, String kind, String contentType, long size, String sha256, String name, Instant createdAt) {}
    record Detected(String kind, String contentType, String ext) {}

    private final JdbcTemplate jdbc;
    private final Path root;
    private final byte[] key;
    private final Map<UUID, Long> publishedCache = new ConcurrentHashMap<>();

    public MediaService(JdbcTemplate jdbc, @Value("${basamak.media-dir:data/media}") String dir,
                        @Value("${basamak.cert-secret}") String secret) throws IOException {
        this.jdbc = jdbc;
        this.root = Paths.get(dir).toAbsolutePath().normalize();
        Files.createDirectories(root);
        this.key = ("media:" + secret).getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    public Asset store(UUID owner, String originalName, InputStream in) throws IOException {
        Path tmp = Files.createTempFile(root, "up-", ".part");
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] head = new byte[16];
            int headLen = 0;
            long size = 0;
            try (var out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) {
                    if (headLen < head.length) { int c = Math.min(n, head.length - headLen); System.arraycopy(buf, 0, head, headLen, c); headLen += c; }
                    size += n;
                    if (size > MAX_VIDEO) throw tooBig(MAX_VIDEO);
                    sha.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            if (size == 0) throw ApiException.badRequest("empty_file", "Dosya boş. Başka bir dosya seçin.");
            Detected d = detect(Arrays.copyOf(head, headLen), originalName, tmp);
            long limit = switch (d.kind) { case "IMAGE" -> MAX_IMAGE; case "VIDEO" -> MAX_VIDEO; default -> MAX_FILE; };
            if (size > limit) throw tooBig(limit);
            String hash = HexFormat.of().formatHex(sha.digest());
            Path target = root.resolve(hash.substring(0, 2)).resolve(hash);
            Files.createDirectories(target.getParent());
            if (!Files.exists(target)) {
                try { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE); } catch (FileAlreadyExistsException same) { /* identical upload raced us */ }
            }
            String name = safeName(originalName, d.ext);
            UUID id = jdbc.queryForObject("""
                    INSERT INTO media_asset(owner_id, kind, content_type, size_bytes, sha256, original_name)
                    VALUES (?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class, owner, d.kind, d.contentType, size, hash, name);
            return get(id).orElseThrow();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public Optional<Asset> get(UUID id) {
        return jdbc.query("SELECT * FROM media_asset WHERE id = ?", (rs, i) -> new Asset(
                (UUID) rs.getObject("id"), rs.getString("kind"), rs.getString("content_type"), rs.getLong("size_bytes"),
                rs.getString("sha256"), rs.getString("original_name"), rs.getTimestamp("created_at").toInstant()), id).stream().findFirst();
    }

    public List<Asset> recent(int limit) {
        return jdbc.query("SELECT * FROM media_asset ORDER BY created_at DESC LIMIT ?", (rs, i) -> new Asset(
                (UUID) rs.getObject("id"), rs.getString("kind"), rs.getString("content_type"), rs.getLong("size_bytes"),
                rs.getString("sha256"), rs.getString("original_name"), rs.getTimestamp("created_at").toInstant()), limit);
    }

    public boolean exists(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM media_asset WHERE id = ?)", Boolean.class, id));
    }

    public Path file(Asset a) {
        return root.resolve(a.sha256().substring(0, 2)).resolve(a.sha256());
    }

    /** True while some content item's current published version references the id (cached 30 s). */
    public boolean isPublished(UUID id) {
        long now = System.currentTimeMillis();
        Long until = publishedCache.get(id);
        if (until != null && until > now) return true;
        boolean ok = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM content_item i JOIN content_version v ON v.id = i.published_version_id
                              WHERE strpos(v.body::text, ?) > 0)""", Boolean.class, id.toString()));
        if (ok) publishedCache.put(id, now + 30_000);
        return ok;
    }

    public String signedPath(UUID id) {
        long exp = Instant.now().getEpochSecond() + SIGNED_URL_SECONDS;
        return "/api/public/media/" + id + "?exp=" + exp + "&sig=" + sign(id, exp);
    }

    public boolean validSignature(UUID id, Long exp, String sig) {
        if (exp == null || sig == null || exp < Instant.now().getEpochSecond()) return false;
        return MessageDigest.isEqual(sign(id, exp).getBytes(StandardCharsets.US_ASCII), sig.getBytes(StandardCharsets.US_ASCII));
    }

    private String sign(UUID id, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal((id + ":" + exp).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Magic-byte sniffing. Office files are ZIP containers; plain text must be valid UTF-8 without NUL bytes. */
    static Detected detect(byte[] h, String name, Path file) throws IOException {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (starts(h, 0x89, 'P', 'N', 'G')) return new Detected("IMAGE", "image/png", "png");
        if (starts(h, 0xFF, 0xD8, 0xFF)) return new Detected("IMAGE", "image/jpeg", "jpg");
        if (starts(h, 'G', 'I', 'F', '8')) return new Detected("IMAGE", "image/gif", "gif");
        if (starts(h, 'R', 'I', 'F', 'F') && h.length >= 12 && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') return new Detected("IMAGE", "image/webp", "webp");
        if (h.length >= 8 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') return new Detected("VIDEO", "video/mp4", "mp4");
        if (starts(h, 0x1A, 0x45, 0xDF, 0xA3)) return new Detected("VIDEO", "video/webm", "webm");
        if (starts(h, '%', 'P', 'D', 'F')) return new Detected("FILE", "application/pdf", "pdf");
        if (starts(h, 'P', 'K', 3, 4)) {
            if (lower.endsWith(".docx")) return new Detected("FILE", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");
            if (lower.endsWith(".xlsx")) return new Detected("FILE", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");
            if (lower.endsWith(".pptx")) return new Detected("FILE", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx");
            return new Detected("FILE", "application/zip", "zip");
        }
        if ((lower.endsWith(".txt") || lower.endsWith(".csv") || lower.endsWith(".md")) && Files.size(file) <= MAX_FILE && isUtf8Text(Files.readAllBytes(file))) {
            return new Detected("FILE", "text/plain; charset=utf-8", lower.substring(lower.lastIndexOf('.') + 1));
        }
        throw ApiException.badRequest("unsupported_media",
                "Bu dosya türü desteklenmiyor. Görsel: PNG, JPEG, GIF, WebP (en çok 5 MB). Video: MP4, WebM (en çok 100 MB). "
                        + "Dosya: PDF, DOCX, XLSX, PPTX, ZIP, TXT, CSV (en çok 20 MB). SVG ve HTML güvenlik nedeniyle kabul edilmez.");
    }

    private static boolean isUtf8Text(byte[] b) {
        for (byte x : b) if (x == 0) return false;
        try {
            StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b));
            return true;
        } catch (java.nio.charset.CharacterCodingException e) {
            return false;
        }
    }

    private static boolean starts(byte[] h, int... sig) {
        if (h.length < sig.length) return false;
        for (int i = 0; i < sig.length; i++) if ((h[i] & 0xFF) != sig[i]) return false;
        return true;
    }

    private static String safeName(String original, String ext) {
        String base = original == null ? "" : Paths.get(original.replace('\\', '/')).getFileName().toString();
        base = base.replaceAll("[\\p{Cntrl}\"<>|:*?/]", "_").strip();
        if (base.isBlank()) base = "dosya." + ext;
        return base.length() > 200 ? base.substring(base.length() - 200) : base;
    }

    private static ApiException tooBig(long limit) {
        return ApiException.badRequest("file_too_large", "Dosya çok büyük. Bu tür için sınır " + (limit / (1024 * 1024)) + " MB; dosyayı küçültüp tekrar yükleyin.");
    }
}
