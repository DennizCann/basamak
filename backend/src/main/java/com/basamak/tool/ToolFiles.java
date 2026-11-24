package com.basamak.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * File outputs of tool runs (non-JSON answers such as PDF, images, ZIP; up to 50 MB). The body is streamed to disk,
 * never held in memory, and the run stores only metadata. Files are kept for {@code RETENTION} and then deleted
 * (the run keeps its metadata and says the file expired). Download is owner-only via /api/tool-runs/{id}/file.
 */
@Slf4j
@Component
public class ToolFiles {
    public static final Duration RETENTION = Duration.ofDays(7);
    private static final Pattern FILENAME = Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);

    static final class FileTooLarge extends IOException {}

    private final Path root;

    public ToolFiles(@Value("${basamak.tools.output-dir:data/tool-outputs}") String dir) throws IOException {
        this.root = Paths.get(dir).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    public Map<String, Object> save(InputStream in, String contentType, String contentDisposition, long max) throws IOException {
        String id = UUID.randomUUID().toString();
        Path target = root.resolve(id);
        long size = 0;
        try (var out = Files.newOutputStream(target)) {
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) {
                size += n;
                if (size > max) throw new FileTooLarge();
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            Files.deleteIfExists(target);
            throw e;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fileId", id);
        m.put("name", name(contentDisposition, contentType));
        m.put("contentType", contentType.split(";")[0].trim());
        m.put("size", size);
        m.put("expiresAt", Instant.now().plus(RETENTION).toString());
        return m;
    }

    public Optional<Path> find(String fileId) {
        if (fileId == null || !fileId.matches("[0-9a-f-]{36}")) return Optional.empty();
        Path p = root.resolve(fileId);
        return Files.exists(p) ? Optional.of(p) : Optional.empty();
    }

    private static String name(String cd, String contentType) {
        if (cd != null) {
            Matcher m = FILENAME.matcher(cd);
            if (m.find()) {
                String n = Paths.get(java.net.URLDecoder.decode(m.group(1), java.nio.charset.StandardCharsets.UTF_8).replace('\\', '/')).getFileName().toString();
                n = n.replaceAll("[\\p{Cntrl}\"<>|:*?/]", "_");
                if (!n.isBlank()) return n.length() > 120 ? n.substring(n.length() - 120) : n;
            }
        }
        String ext = switch (contentType.split(";")[0].trim()) {
            case "application/pdf" -> "pdf"; case "image/png" -> "png"; case "image/jpeg" -> "jpg"; case "application/zip" -> "zip";
            case "text/csv" -> "csv"; default -> "bin";
        };
        return "cikti." + ext;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void deleteExpired() {
        Instant limit = Instant.now().minus(RETENTION);
        try (var s = Files.list(root)) {
            s.filter(p -> {
                try { return Files.getLastModifiedTime(p).toInstant().isBefore(limit); } catch (IOException e) { return false; }
            }).forEach(p -> { try { Files.delete(p); } catch (IOException e) { log.warn("could not delete {}", p.getFileName()); } });
        } catch (IOException e) {
            log.warn("tool output cleanup failed: {}", e.getMessage());
        }
    }
}
