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

    @GetMapping("/trainings.csv")
    public ResponseEntity<byte[]> trainingsCsv(@AuthenticationPrincipal AuthPrincipal p) {
        require(p);
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Eğitim", "Sürüm", "Başlayan", "Tamamlayan", "Tamamlama oranı (%)", "Ortalama süre (dk)"));
        for (var r : reports.trainings()) {
            rows.add(Arrays.asList(r.get("title"), r.get("versionNo"), r.get("enrolled"), r.get("completed"), r.get("completionRate"), r.get("avgCompletionMinutes")));
        }
        return csv(p, "egitim-tamamlama", rows);
    }

    @GetMapping("/trainings/{id}/questions.csv")
    public ResponseEntity<byte[]> questionsCsv(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        require(p);
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Quiz", "Soru", "Soru metni", "Soruldu", "Doğru", "Doğru oranı (%)"));
        for (var q : reports.quizzes(id)) {
            @SuppressWarnings("unchecked") List<Map<String, Object>> qs = (List<Map<String, Object>>) q.get("questions");
            for (var s : qs) rows.add(Arrays.asList(q.get("title"), s.get("questionId"), s.get("text"), s.get("asked"), s.get("correct"), s.get("correctRate")));
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
