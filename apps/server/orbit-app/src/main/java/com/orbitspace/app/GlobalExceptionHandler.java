package com.orbitspace.app;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.ApiErrorResponse;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.ErrorCode;
import com.orbitspace.kernel.KernelErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler
        implements AuthenticationEntryPoint, AccessDeniedHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ObjectMapper mapper;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public GlobalExceptionHandler(
            ObjectMapper mapper, ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.mapper = mapper;
        this.meterRegistryProvider = meterRegistryProvider;
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ApiErrorResponse> domain(DomainException ex, HttpServletRequest request) {
        var code = ex.errorCode();
        recordDomainErrorMetric(code);
        return ResponseEntity.status(code.httpStatus()).body(error(request, code, ex.details(), ex));
    }

    private void recordDomainErrorMetric(ErrorCode code) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null || code == null) {
            return;
        }
        if (code == KernelErrorCode.QUOTA_EXCEEDED) {
            Counter.builder("orbit.quota.rejections").register(registry).increment();
        } else if (code == KernelErrorCode.SSRF_TARGET_BLOCKED
                || code == KernelErrorCode.REMOTE_URL_FORBIDDEN) {
            Counter.builder("orbit.egress.blocked").register(registry).increment();
        }
    }

    @ExceptionHandler({AuthenticationException.class, AccessDeniedException.class, ConstraintViolationException.class})
    ResponseEntity<ApiErrorResponse> rejected(RuntimeException ex, HttpServletRequest request) {
        var code = ex instanceof ConstraintViolationException ? KernelErrorCode.INVALID_REQUEST : securityCode(ex);
        return ResponseEntity.status(code.httpStatus()).body(error(request, code, Map.of(), ex));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest webRequest) {
        var servlet = (ServletWebRequest) webRequest;
        if (servlet.getResponse() != null && servlet.getResponse().isCommitted()) {
            return null;
        }
        var code = status.is5xxServerError() ? KernelErrorCode.INTERNAL_ERROR
                : status.value() == 404 ? KernelErrorCode.RESOURCE_NOT_FOUND : KernelErrorCode.INVALID_REQUEST;
        return new ResponseEntity<>(error(servlet.getRequest(), code, Map.of(), ex), headers, status);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> unexpected(Exception ex, HttpServletRequest request) {
        return ResponseEntity.internalServerError().body(error(request, KernelErrorCode.INTERNAL_ERROR, Map.of(), ex));
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        writeSecurityError(request, response, ex);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        writeSecurityError(request, response, ex);
    }

    private void writeSecurityError(HttpServletRequest request, HttpServletResponse response, RuntimeException ex)
            throws IOException {
        var code = securityCode(ex);
        response.setStatus(code.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), error(request, code, Map.of(), ex));
    }

    private static KernelErrorCode securityCode(RuntimeException ex) {
        if (ex instanceof CsrfException) {
            return KernelErrorCode.CSRF_INVALID;
        }
        return ex instanceof AuthenticationException ? KernelErrorCode.AUTH_UNAUTHORIZED : KernelErrorCode.AUTH_FORBIDDEN;
    }

    private static ApiErrorResponse error(HttpServletRequest request, ErrorCode code, Map<String, ?> details,
            Exception ex) {
        String id = RequestIdFilter.requestId(request);
        // Exception messages and rejected values may include credentials, SQL, paths or source text.
        LOG.atLevel(code.httpStatus() >= 500 ? org.slf4j.event.Level.ERROR : org.slf4j.event.Level.WARN)
                .log("requestId={} code={} exception={}", id, code.code(), ex.getClass().getSimpleName());
        return new ApiErrorResponse(code.code(), code.defaultMessage(), id, details);
    }
}
