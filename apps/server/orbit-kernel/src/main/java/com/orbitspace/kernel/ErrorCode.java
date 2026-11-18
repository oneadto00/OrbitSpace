package com.orbitspace.kernel;

public interface ErrorCode {
    String code();
    int httpStatus();
    String defaultMessage();
}
