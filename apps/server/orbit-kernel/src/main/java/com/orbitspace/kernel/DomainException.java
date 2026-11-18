package com.orbitspace.kernel;

import java.util.Map;

public final class DomainException extends RuntimeException {
    private final ErrorCode errorCode;
    private final Map<String, ?> details;

    public DomainException(ErrorCode errorCode) {
        this(errorCode, Map.of());
    }

    public DomainException(ErrorCode errorCode, Map<String, ?> details) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
        this.details = ApiErrorResponse.copyDetails(details);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, ?> details() {
        return details;
    }
}
