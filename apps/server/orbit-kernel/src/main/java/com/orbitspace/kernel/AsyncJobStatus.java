package com.orbitspace.kernel;

public enum AsyncJobStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    RETRY_WAIT,
    FAILED,
    CANCELLED
}
