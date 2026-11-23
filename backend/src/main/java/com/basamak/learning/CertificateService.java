package com.basamak.learning;

import com.basamak.common.ApiException;
import com.basamak.common.CryptoService;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Journey certificates. Anti-forgery: the serial is random and the row carries an HMAC over
 * (serial, user, journey, version, issue time) with a server-only key. The public verification page recomputes it,
 * so an edited database row or a made-up serial shows as invalid. The PDF carries the verification link + QR code.
 */
@Service
@RequiredArgsConstructor
public class CertificateService {
    private static final DateTimeFormatter TR_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.of("Europe/Istanbul"));
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final JdbcTemplate jdbc;

    @Value("${basamak.cert-secret}")
    private String secret;
    @Value("${basamak.web-base-url}")
    private String webBaseUrl;

    @Transactional
    public String issue(UUID userId, UUID journeyId, int journeyVersion, String journeyTitle) {
        String holder = jdbc.queryForObject("SELECT display_name FROM users WHERE id = ?", String.class, userId);
        String serial = "BSM-" + randomSerial();
        Instant issued = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String sig = sign(serial, userId, journeyId, journeyVersion, issued);
        jdbc.update("""
                INSERT INTO certificate(serial, user_id, journey_id, journey_version_no, holder_name, journey_title, issued_at, signature)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (user_id, journey_id) DO NOTHING""",
                serial, userId, journeyId, journeyVersion, holder, journeyTitle, Timestamp.from(issued), sig);
        return jdbc.queryForObject("SELECT serial FROM certificate WHERE user_id = ? AND journey_id = ?", String.class, userId, journeyId);
    }

    public Map<String, Object> verify(String serial) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM certificate WHERE serial = ?", serial);
        if (rows.isEmpty()) return Map.of("valid", false, "serial", serial, "reason", "Bu numarayla bir sertifika bulunamadı.");
        Map<String, Object> c = rows.get(0);
        Instant issued = ((Timestamp) c.get("issued_at")).toInstant();
        boolean sigOk = sign(serial, (UUID) c.get("user_id"), (UUID) c.get("journey_id"), (Integer) c.get("journey_version_no"), issued)
                .equals(c.get("signature"));
        boolean revoked = c.get("revoked_at") != null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", sigOk && !revoked);
        out.put("serial", serial);
        out.put("holderName", c.get("holder_name"));
        out.put("journeyTitle", c.get("journey_title"));
        out.put("issuedAt", TR_DATE.format(issued));
        if (!sigOk) out.put("reason", "Sertifika kaydı doğrulanamadı.");
        if (revoked) out.put("reason", "Bu sertifika geri alınmış.");
        return out;
    }

    public List<Map<String, Object>> forUser(UUID userId) {
        return jdbc.queryForList("SELECT serial, journey_id, journey_title, issued_at FROM certificate WHERE user_id = ? AND revoked_at IS NULL ORDER BY issued_at", userId);
    }

    public byte[] pdf(String serial, UUID requesterId) {
        Map<String, Object> c = jdbc.queryForList("SELECT * FROM certificate WHERE serial = ? AND user_id = ?", serial, requesterId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Sertifika"));
        String verifyUrl = webBaseUrl + "/verify/" + serial;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4.rotate(), 60, 60, 60, 60);
            PdfWriter.getInstance(doc, out);
            doc.addTitle("Basamak Sertifikası " + serial);
            doc.open();
            BaseFont bf = font();
            Font title = new Font(bf, 34, Font.BOLD);
            Font big = new Font(bf, 24);
            Font normal = new Font(bf, 14);
            Font small = new Font(bf, 10);
            doc.add(centered("BASAMAK", new Font(bf, 14, Font.BOLD)));
            doc.add(centered("Başarı Sertifikası", title));
            doc.add(spacer(18));
            doc.add(centered(String.valueOf(c.get("holder_name")), big));
            doc.add(spacer(8));
            doc.add(centered("“" + c.get("journey_title") + "” Journey'ini başarıyla tamamlamıştır.", normal));
            doc.add(spacer(6));
            doc.add(centered("Tarih: " + TR_DATE.format(((Timestamp) c.get("issued_at")).toInstant()), normal));
            doc.add(spacer(14));
            Image qr = Image.getInstance(qrPng(verifyUrl));
            qr.scaleAbsolute(110, 110);
            qr.setAlignment(Element.ALIGN_CENTER);
            doc.add(qr);
            doc.add(centered("Sertifika no: " + serial, small));
            doc.add(centered("Doğrulama: " + verifyUrl, small));
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("PDF üretilemedi", e);
        }
    }

    private String sign(String serial, UUID user, UUID journey, int version, Instant issued) {
        return CryptoService.hmacSha256(secret, serial + "|" + user + "|" + journey + "|" + version + "|" + issued.getEpochSecond());
    }

    private static String randomSerial() {
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    private static byte[] qrPng(String text) throws Exception {
        var matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 300, 300);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(MatrixToImageWriter.toBufferedImage(matrix), "png", png);
        return png.toByteArray();
    }

    private static Paragraph centered(String text, Font f) {
        Paragraph p = new Paragraph(text, f);
        p.setAlignment(Element.ALIGN_CENTER);
        return p;
    }

    private static Paragraph spacer(float h) {
        Paragraph p = new Paragraph(" ");
        p.setSpacingAfter(h);
        return p;
    }

    /** A Unicode TrueType font so Turkish letters (ş, ğ, ı, İ) render; falls back to Helvetica. */
    private static BaseFont font() throws Exception {
        for (String p : List.of("C:/Windows/Fonts/arial.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                "/usr/share/fonts/dejavu/DejaVuSans.ttf", "/Library/Fonts/Arial Unicode.ttf", "/System/Library/Fonts/Supplemental/Arial.ttf")) {
            if (Files.exists(Path.of(p))) return BaseFont.createFont(p, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
        }
        return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
    }
}
