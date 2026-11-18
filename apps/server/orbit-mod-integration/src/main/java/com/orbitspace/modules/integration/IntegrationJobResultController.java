package com.orbitspace.modules.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;
import com.orbitspace.modules.integration.IntegrationDtos.InternalClaimJobRequest;
import com.orbitspace.modules.integration.IntegrationDtos.InternalClaimedJobResponse;
import com.orbitspace.modules.integration.IntegrationDtos.InternalJobFailRequest;
import com.orbitspace.modules.integration.IntegrationDtos.InternalJobFailResponse;
import com.orbitspace.modules.integration.IntegrationDtos.InternalJobResultRequest;
import com.orbitspace.modules.integration.IntegrationDtos.InternalJobResultResponse;
import com.orbitspace.modules.integration.IntegrationDtos.InternalStoredOutputResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Set;

/**
 * Authenticated internal worker gateway (/internal/v1/jobs/**). Every endpoint validates HMAC-SHA256,
 * timestamp freshness, and single-use nonce before reading or accepting any payload.
 */
@RestController
public class IntegrationJobResultController {
    private static final String CONTENT_SHA_HEADER = "X-Orbit-Content-Sha256";

    private final IntegrationHmacSupport hmac;
    private final IntegrationJobResultService service;
    private final ObjectMapper mapper;
    private final Validator validator;

    public IntegrationJobResultController(
            IntegrationHmacSupport hmac,
            IntegrationJobResultService service,
            ObjectMapper mapper,
            Validator validator) {
        this.hmac = hmac;
        this.service = service;
        this.mapper = mapper;
        this.validator = validator;
    }

    @PostMapping(
            value = "/internal/v1/jobs/{jobId}/claim",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public InternalClaimedJobResponse claimJob(
            @PathVariable String jobId, HttpServletRequest request) {
        byte[] rawBody = hmac.verifyAndReadBody(request);
        InternalClaimJobRequest body = parseAndValidate(rawBody, InternalClaimJobRequest.class);
        return service.claimJob(jobId, body);
    }

    @PostMapping(
            value = "/internal/v1/jobs/{jobId}/output",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public InternalStoredOutputResponse uploadOutput(
            @PathVariable String jobId,
            @RequestHeader(value = CONTENT_SHA_HEADER, required = false) String contentSha256,
            HttpServletRequest request) {
        byte[] rawBody = hmac.verifyAndReadBody(request);
        return service.storeOutput(jobId, contentSha256, rawBody);
    }

    @PostMapping(
            value = "/internal/v1/jobs/{jobId}/result",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public InternalJobResultResponse acceptResult(
            @PathVariable String jobId, HttpServletRequest request) {
        byte[] rawBody = hmac.verifyAndReadBody(request);
        InternalJobResultRequest body = parseAndValidate(rawBody, InternalJobResultRequest.class);
        return service.acceptResult(jobId, body);
    }

    @PostMapping(
            value = "/internal/v1/jobs/{jobId}/fail",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public InternalJobFailResponse failJob(
            @PathVariable String jobId, HttpServletRequest request) {
        byte[] rawBody = hmac.verifyAndReadBody(request);
        InternalJobFailRequest body = parseAndValidate(rawBody, InternalJobFailRequest.class);
        return service.failJob(jobId, body);
    }

    private <T> T parseAndValidate(byte[] rawBody, Class<T> type) {
        try {
            T parsed = mapper.readValue(rawBody, type);
            Set<ConstraintViolation<T>> violations = validator.validate(parsed);
            if (!violations.isEmpty()) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            return parsed;
        } catch (IOException ex) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
    }
}
