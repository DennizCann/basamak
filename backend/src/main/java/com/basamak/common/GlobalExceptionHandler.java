package com.basamak.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Every error leaves the API as RFC 7807 problem+json with code, message and requestId. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException e) {
        return build(e.getStatus(), e.getCode(), e.getMessage(), e.getDetails());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.put(f.getField(), f.getDefaultMessage()));
        return build(HttpStatus.BAD_REQUEST, "validation_failed",
                "Formdaki bazı alanlar hatalı. İşaretli alanları düzeltip tekrar deneyin.", Map.of("fields", fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException e) {
        return build(HttpStatus.BAD_REQUEST, "malformed_request", "İstek okunamadı. Gönderilen veriyi kontrol edin.", Map.of());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> optimistic(ObjectOptimisticLockingFailureException e) {
        return build(HttpStatus.CONFLICT, "edit_conflict",
                "Bu içerik siz düzenlerken başka biri tarafından kaydedildi. Güncel sürümü yükleyip değişikliklerinizi yeniden uygulayın.", Map.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> denied(AccessDeniedException e) {
        return build(HttpStatus.FORBIDDEN, "forbidden", "Bu işlem için yetkiniz yok.", Map.of());
    }

    @ExceptionHandler({org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> notFound(Exception e) {
        return build(HttpStatus.NOT_FOUND, "not_found", "İstenen kayıt ya da adres bulunamadı.", Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest req) {
        log.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "Beklenmeyen bir hata oluştu. Birkaç saniye sonra tekrar deneyin; sorun sürerse istek kimliğini destek ekibine iletin.", Map.of());
    }

    private ResponseEntity<ProblemDetail> build(HttpStatus status, String code, String message, Map<String, Object> details) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, message);
        pd.setTitle(code);
        pd.setProperty("code", code);
        pd.setProperty("requestId", MDC.get(RequestIdFilter.MDC_KEY));
        details.forEach(pd::setProperty);
        return ResponseEntity.status(status).body(pd);
    }
}
