package com.defendermate.graph.exception;

/**
 * Thrown when the {@code k} parameter supplied to a top-{@code k} query is zero or negative.
 *
 * <p>Maps to HTTP {@code 400 Bad Request}.
 *
 * <p>TODO: add an upper-bound validation once graph-size limits are enforced to prevent
 *          excessively large result sets from being returned.
 */
public class InvalidKValueException extends RuntimeException {

    /**
     * @param message a concise, user-facing description of the constraint violation
     */
    public InvalidKValueException(String message) {
        super(message);
    }
}
