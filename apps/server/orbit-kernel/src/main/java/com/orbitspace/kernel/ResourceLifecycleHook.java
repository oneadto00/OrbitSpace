package com.orbitspace.kernel;

public interface ResourceLifecycleHook {
    record Change(String spaceId, ResourceCardVerifierPort.Reference reference,
                  ResourceLifecycleState state, long lifecycleVersion) {}

    /** Invoked in the resource transaction to invalidate derived previews and append recoverable card events. */
    void onLifecycleChanged(Change change);
}
