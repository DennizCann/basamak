package com.basamak.report;

import com.basamak.auth.AuthPrincipal;
import com.basamak.auth.Role;
import com.basamak.common.ApiException;
import com.basamak.common.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/** CMS reports + CSV export (editor, publisher, admin). CSV is UTF-8 with BOM (Excel) and formula-injection safe. */
@RestController
@RequestMapping("/api/cms/reports")
@RequiredArgsConstructor
public class CmsReportController {
    private static final Set<Role> READERS = EnumSet.of(Role.EDITOR, Role.PUBLISHER, Role.ADMIN);

    private final ReportService reports;
    private final AuditService audit;

    @GetMapping("/trainings")
    public List<Map<String, Object>> trainings(@AuthenticationPrincipal AuthPrincipal p) {
        require(p);
        return reports.trainings();
    }

    @GetMapping("/trainings/{id}")
    public Map<String, Object> training(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        require(p);
        return Map.of("quizzes", reports.quizzes(id), "surveys", reports.surveys(id));
    }

    @GetMapping("/journeys")
    public List<Map<String, Object>> journeys(@AuthenticationPrincipal AuthPrincipal p) {
        require(p);
        return reports.journeys();
    }

    @GetMapping("/usage")
    public Map<String, Object> usage(@AuthenticationPrincipal AuthPrincipal p) {
        require(p);
        return reports.usage();
    }

    @GetMapping("/journeys.csv")
    public ResponseEntity<byte[]> journeysCsv(@AuthenticationPrincipal AuthPrincipal p) {
        require(p);
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Journey", "Eğitim sayısı", "Başlayan", "Bitiren", "Bırakan (" + ReportService.DROP_DAYS + " gün hareketsiz)", "Bitirme oranı (%)"));
        for (var r : reports.journeys()) rows.add(Arrays.asList(r.get("title"), r.get("trainings"), r.get("started"), r.get("completed"), r.get("dropped"), r.get("completionRate")));
        return csv(p, "journey-raporu", rows);
    }

    @GetMapping("/usage.csv")
    public ResponseEntity<byte[]> usageCsv(@AuthenticationPrincipal AuthPrincipal p) {
        require(p);
        Map<String, Object> u = reports.usage();
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Tür", "Ad", "Çalıştırma", "Hata", "Hata oranı (%)", "Ortalama süre (ms)", "Yapay zekâ maliyeti (USD)"));
        for (var r : (List<Map<String, Object>>) u.get("tools")) rows.add(Arrays.asList("Araç (" + r.get("scope") + ")", r.get("name"), r.get("runs"), r.get("errors"), r.get("errorRate"), r.get("avgMs"), null));
        for (var r : (List<Map<String, Object>>) u.get("workflows")) rows.add(Arrays.asList("İş akışı", r.get("name"), r.get("runs"), r.get("errors"), r.get("errorRate"), r.get("avgMs"), r.get("aiCostUsd")));
        return csv(p, "arac-akis-kullanimi", rows);
    }

    @GetMapping("/versions.csv")
    public ResponseEntity<byte[]> versionsCsv(@AuthenticationPrincipal AuthPrincipal p) {
        require(p);
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Eğitim", "Sürüm", "Başlayan", "Tamamlayan", "Tamamlama oranı (%)"));
        for (var r : reports.trainings()) {
            for (var v : (List<Map<String, Object>>) r.get("versions")) rows.add(Arrays.asList(r.get("title"), v.get("versionNo"), v.get("enrolled"), v.get("completed"), v.get("completionRate")));
        }
        return csv(p, "surum-karsilastirma", rows);
    }

    @GetMapping("/trainings.csv")
    public ResponseEntity<byte[]> trainingsCsv(@AuthenticationPrincipal AuthPrincipal p) {
        require(p);
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Eğitim", "Sürüm", "Başlayan", "Tamamlayan", "Bırakan", "Tamamlama oranı (%)", "Ortalama süre (dk)", "En çok bırakılan öge"));
        for (var r : reports.trainings()) {
            @SuppressWarnings("unchecked") Map<String, Object> md = (Map<String, Object>) r.get("mostDroppedItem");
            rows.add(Arrays.asList(r.get("title"), r.get("versionNo"), r.get("enrolled"), r.get("completed"), r.get("dropped"), r.get("completionRate"),
                    r.get("avgCompletionMinutes"), md == null ? null : md.get("title")));
        }
        return csv(p, "egitim-tamamlama", rows);
    }

    @GetMapping("/trainings/{id}/questions.csv")
    public ResponseEntity<byte[]> questionsCsv(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        require(p);
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Quiz", "Soru", "Soru metni", "Soruldu", "Doğru", "Doğru oranı (%)", "Ortalama süre (sn, yaklaşık)"));
        for (var q : reports.quizzes(id)) {
            @SuppressWarnings("unchecked") List<Map<String, Object>> qs = (List<Map<String, Object>>) q.get("questions");
            for (var s : qs) rows.add(Arrays.asList(q.get("title"), s.get("questionId"), s.get("text"), s.get("asked"), s.get("correct"), s.get("correctRate"), s.get("avgSeconds")));
        }
        return csv(p, "soru-dogru-oranlari", rows);
    }

    @GetMapping("/trainings/{id}/surveys.csv")
    public ResponseEntity<byte[]> surveysCsv(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        require(p);
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Anket", "Soru", "Soru metni", "Seçenek", "Sayı", "Oran (%)"));
        for (var s : reports.surveys(id)) {
            @SuppressWarnings("unchecked") List<Map<String, Object>> qs = (List<Map<String, Object>>) s.get("questions");
            for (var q : qs) {
                @SuppressWarnings("unchecked") List<Map<String, Object>> dist = (List<Map<String, Object>>) q.get("distribution");
                if (Boolean.TRUE.equals(q.get("hidden"))) { rows.add(Arrays.asList(s.get("title"), q.get("questionId"), q.get("text"), "(gizlendi: anonim, 5'ten az cevap)", null, null)); continue; }
                if (dist == null) { rows.add(Arrays.asList(s.get("title"), q.get("questionId"), q.get("text"), "(açık uçlu)", q.get("answered"), null)); continue; }
                for (var d : dist) rows.add(Arrays.asList(s.get("title"), q.get("questionId"), q.get("text"), d.get("label"), d.get("count"), d.get("rate")));
            }
        }
        return csv(p, "anket-dagilimi", rows);
    }

    private ResponseEntity<byte[]> csv(AuthPrincipal p, String name, List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder("﻿");
        for (List<Object> r : rows) {
            StringJoiner j = new StringJoiner(";"); // Turkish Excel expects ';' (',' is the decimal separator)
            for (Object v : r) j.add(cell(v));
            sb.append(j).append("\r\n");
        }
        audit.record(p.userId(), "report.csv_exported", "REPORT", name, Map.of("rows", rows.size() - 1));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "-" + LocalDate.now() + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Quotes every cell; a leading = + - @ (or tab/CR) is neutralised so spreadsheets never evaluate it as a formula. */
    static String cell(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        if (v instanceof java.math.BigDecimal || v instanceof Number) s = s.replace('.', ',');
        else if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static void require(AuthPrincipal p) {
        if (p.roles().stream().noneMatch(READERS::contains)) throw ApiException.forbidden("Raporları editör, yayıncı ya da yönetici görebilir.");
    }
}
