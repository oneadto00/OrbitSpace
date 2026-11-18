package com.orbitspace.app;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.ApiErrorResponse;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.ErrorCode;
import com.orbitspace.kernel.KernelErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public GlobalExceptionHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ApiErrorResponse> domain(DomainException ex, HttpServletRequest request) {
        var code = ex.errorCode();
        return ResponseEntity.status(code.httpStatus()).body(error(request, code, ex.details(), ex));
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
