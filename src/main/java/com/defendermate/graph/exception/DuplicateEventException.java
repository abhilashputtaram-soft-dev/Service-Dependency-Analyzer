package com.defendermate.graph.exception;

/**
 * Thrown when an ingestion request submits an event whose ID has already been processed,
 * indicating a duplicate submission.
 *
 * <p>Maps to HTTP {@code 409 Conflict}.
 *
 * <p>TODO: include the duplicate {@code eventId} as a structured field once multi-field
 *          error responses are supported, to aid client-side idempotency checks.
 */
public class DuplicateEventException extends RuntimeException {

    /**
     * @param message a concise, user-facing description of the conflict
     */
    public DuplicateEventException(String message) {
        super(message);
    }
}
