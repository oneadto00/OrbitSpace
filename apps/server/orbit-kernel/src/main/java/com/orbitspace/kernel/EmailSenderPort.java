package com.orbitspace.kernel;

public interface EmailSenderPort {
    /** Deliver a transactional plain-text email; the body can contain a single-use secret. */
    void send(String recipient, String subject, String plainTextBody);
}
